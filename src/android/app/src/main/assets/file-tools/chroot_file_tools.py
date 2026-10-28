#!/usr/bin/python3
"""Restricted text-file helper for the CHROOT file tools.

Protocol (one process per call; no shell, no eval, no subprocess):

    /usr/bin/python3 -I chroot_file_tools.py REQUEST_JSON RESPONSE_JSON

REQUEST_JSON
    {"operation": "read" | "write" | "edit",
     "path": "/absolute/guest/path",
     "args": { ...original file_read / file_write / file_edit parameters... }}

RESPONSE_JSON
    Pre-created by the App (so the App owns it), written in place with a plain
    open("w") -- never replaced atomically -- as {"success": bool, "text": str}.
    stdout stays empty (the protocol reserves it, so a stray print can never
    corrupt a response); diagnostics go to stderr only and never include the
    request body.

The parent process owns Root approval, session mounts, read-only
interception, timeouts and response-size limits.  This helper does none of
those. Existing targets keep their inode, owner, mode and ACL. A separate
bridge-supplied app_handoff policy gives newly created objects in App-managed
roots to the real App uid/gid using the open descriptor. It never scans or
repairs an existing directory, changes modes/ACLs, or remaps Shell identity.

Exit status is 0 whenever a response was written (even {"success": false}),
and 1 only when the response file itself could not be written.
"""

import errno
import json
import os
import re
import stat as stat_module
import sys
from collections import deque

# file_read semantics (mirrors FileReadTool.kt).
MAX_READ_CHARS = 80_000
DEFAULT_MAX_LENGTH = 15_000
BINARY_SNIFF_BYTES = 8192

# file_edit ceiling, identical to FileToolIO.MAX_EDIT_BYTES (8 MiB): the edit
# holds the whole file plus a rewritten copy in memory.
MAX_EDIT_BYTES = 8 * 1024 * 1024

CHUNK_SIZE = 64 * 1024
_LINE_TERMINATOR = re.compile(rb"\r\n|\r|\n")


class ToolError(Exception):
    """A failure that already carries the agent-facing message."""


# --------------------------------------------------------------------------
# small value coercion helpers (org.json opt* equivalents)
# --------------------------------------------------------------------------

def _as_int(value, default):
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        try:
            return int(value)
        except (ValueError, OverflowError):
            return default
    if isinstance(value, str):
        try:
            return int(value.strip())
        except (TypeError, ValueError):
            return default
    return default


def _as_bool(value):
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    if isinstance(value, str):
        return value.strip().lower() in ("true", "1", "yes")
    return False


def _errno_reason(exc):
    """'Permission denied [EACCES]' from a Python errno, else the exception text."""
    number = getattr(exc, "errno", None)
    if isinstance(number, int) and number > 0:
        try:
            text = os.strerror(number)
        except (ValueError, OverflowError):
            text = str(exc)
        name = errno.errorcode.get(number)
        return "%s [%s]" % (text, name) if name else text
    return "%s: %s" % (type(exc).__name__, exc)


def _reason_for_errno(number):
    try:
        text = os.strerror(number)
    except (ValueError, OverflowError):
        text = "errno %d" % number
    name = errno.errorcode.get(number)
    return "%s [%s]" % (text, name) if name else text


# --------------------------------------------------------------------------
# path validation
# --------------------------------------------------------------------------

def _validated_path(path):
    if not isinstance(path, str) or path.strip() == "":
        raise ToolError("Error: 'path' is required")
    if "\x00" in path:
        raise ToolError("Error: 'path' contains a NUL byte")
    if not os.path.isabs(path):
        raise ToolError("Error: 'path' must be an absolute guest path: " + path)
    return path


def _stat_or_raise(path):
    try:
        return os.stat(path)
    except FileNotFoundError:
        raise ToolError("Error: File not found: " + path)
    except NotADirectoryError as exc:
        raise ToolError("Error: cannot access %s: %s" % (path, _errno_reason(exc)))
    except OSError as exc:
        raise ToolError("Error: cannot access %s: %s" % (path, _errno_reason(exc)))


# --------------------------------------------------------------------------
# parent directory creation (file_write auto-creates parents, always)
# --------------------------------------------------------------------------

class ParentDirFailure(ToolError):
    def __init__(self, target, parent, failed, reason):
        self.target = target
        self.parent = parent
        self.failed = failed
        self.reason = reason
        ToolError.__init__(
            self,
            "Error: cannot create parent directory %s for %s: %s.\n"
            "  failed path: %s\n"
            "  Nothing was written to %s (the write was not attempted)."
            % (parent, target, reason, failed, target),
        )


class AppHandoff:
    """Ownership of objects created by this request in App-managed bind roots.

    Existing files/directories and objects outside these roots are untouched.
    The bridge supplies this policy separately from model-controlled args.
    """
    def __init__(self, policy):
        self.uid = self.gid = None
        self.roots = []
        self.count = 0
        if policy is None:
            return
        self.uid, self.gid = policy["uid"], policy["gid"]
        if type(self.uid) is not int or type(self.gid) is not int or self.uid <= 0 or self.gid <= 0:
            raise ToolError("Error: invalid App handoff identity; nothing was written")
        self.roots = [os.path.realpath(_validated_path(p)) for p in policy["roots"]]

    def applies(self, path):
        actual = os.path.realpath(path)
        return any(actual.startswith(root.rstrip("/") + "/") for root in self.roots)

    def created(self, path, fd):
        if not self.applies(path):
            return
        info = os.fstat(fd)
        if (info.st_uid, info.st_gid) != (self.uid, self.gid):
            os.fchown(fd, self.uid, self.gid)
        after = os.fstat(fd)
        if (after.st_uid, after.st_gid) != (self.uid, self.gid):
            raise OSError(errno.EIO, "App ownership handoff did not persist")
        self.count += 1


def _ensure_parent_dirs(target, handoff=None):
    """Create every missing ancestor of `target`, stopping at the first failure.

    Raises ParentDirFailure (and the caller must not open the file afterwards).
    """
    parent = os.path.dirname(target) or "/"
    if os.path.isdir(parent):
        return parent

    missing = []
    current = parent
    while not os.path.isdir(current):
        if os.path.exists(current) or os.path.islink(current):
            raise ParentDirFailure(
                target,
                parent,
                current,
                "%s is not a directory: %s"
                % (current, _reason_for_errno(errno.ENOTDIR)),
            )
        missing.append(current)
        nxt = os.path.dirname(current)
        if nxt == current or nxt == "":
            break
        current = nxt

    for directory in reversed(missing):
        try:
            os.mkdir(directory, 0o777)
            if handoff is not None and handoff.applies(directory):
                fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
                try:
                    handoff.created(directory, fd)
                finally:
                    os.close(fd)
        except FileExistsError:
            if not os.path.isdir(directory):
                raise ParentDirFailure(
                    target,
                    parent,
                    directory,
                    "%s appeared but is not a directory" % directory,
                )
        except OSError as exc:
            raise ParentDirFailure(
                target,
                parent,
                directory,
                "create directory failed: %s" % _errno_reason(exc),
            )

    if not os.path.isdir(parent):
        raise ParentDirFailure(
            target,
            parent,
            parent,
            "the directory still does not exist after creation",
        )
    return parent


# --------------------------------------------------------------------------
# streaming line reader (file_read semantics: \\n, \\r\\n or a lone \\r)
# --------------------------------------------------------------------------

def _append_capped(pending, segment, cap, tail):
    """Keep the start for head, the end for tail, including very long lines."""
    if not tail:
        pending.extend(segment[:max(0, cap - len(pending))])
        return pending
    pending.extend(segment)
    if len(pending) > cap:
        del pending[:len(pending) - cap]
    return pending


def _iter_lines(handle, cap, tail=False):
    """Yield each line's bytes (each capped to its last `cap` bytes).

    A trailing terminator does not start an extra empty line, matching
    kotlin.io.readLines / FileToolIO.scanLines.
    """
    pending = bytearray()
    carry = b""
    while True:
        chunk = handle.read(CHUNK_SIZE)
        if not chunk:
            break
        data = carry + chunk if carry else chunk
        carry = b""
        if data.endswith(b"\r"):
            # A CR at a chunk boundary may be the first half of CRLF.
            data = data[:-1]
            carry = b"\r"
        if not data:
            continue
        position = 0
        for match in _LINE_TERMINATOR.finditer(data):
            segment = data[position:match.start()]
            position = match.end()
            pending = _append_capped(pending, segment, cap, tail)
            line = bytes(pending)
            pending = bytearray()
            yield line
        pending = _append_capped(pending, data[position:], cap, tail)
    if carry:
        line = bytes(pending)
        pending = bytearray()
        yield line
    if pending:
        yield bytes(pending)


def _scan_head(handle, offset, lines, cap):
    start = max(offset - 1, 0)
    end = None if lines is None else start + lines
    buffer = bytearray()
    total = 0
    selected = 0
    for line in _iter_lines(handle, cap):
        index = total
        total += 1
        if index < start or (end is not None and index >= end):
            continue
        if selected > 0 and len(buffer) < cap:
            buffer.append(0x0A)
        if len(buffer) < cap:
            buffer.extend(line[: cap - len(buffer)])
        selected += 1
    return total, selected, bytes(buffer)


def _scan_tail(handle, lines, cap):
    keep = lines
    kept = deque()
    kept_bytes = 0
    total = 0
    for line in _iter_lines(handle, cap, tail=True):
        total += 1
        if keep is not None and keep <= 0:
            continue
        kept.append(line)
        kept_bytes += len(line)
        if keep is not None:
            while len(kept) > keep:
                kept_bytes -= len(kept.popleft())
        # Drop whole lines from the front while what stays still holds more
        # than `cap` bytes: takeLast(maxLength) never reaches them.
        while len(kept) > 1:
            joined_without_first = kept_bytes - len(kept[0]) + (len(kept) - 2)
            if joined_without_first < cap:
                break
            kept_bytes -= len(kept.popleft())
    selected = total if keep is None else min(keep, total)
    if keep is not None and keep <= 0:
        content = b""
    else:
        content = b"\n".join(kept)
    return total, selected, content


# --------------------------------------------------------------------------
# read
# --------------------------------------------------------------------------

def _read_header(path, size, total_lines, effective_start, effective_end,
                 truncated, max_length, next_offset):
    header = "[%s | %d bytes | %d lines | showing %d-%d of %d" % (
        path, size, total_lines, effective_start, effective_end, total_lines)
    if truncated:
        header += " | truncated at %d chars" % max_length
        if next_offset is not None:
            header += ", next_offset=%d" % next_offset
        else:
            header += ", retry with a smaller lines value"
    header += "]"
    return header


def op_read(path, args):
    path = _validated_path(path)

    offset = max(_as_int(args.get("offset", 1), 1), 1)
    if "lines" in args and args.get("lines") is not None:
        requested_lines = _as_int(args.get("lines"), 0)
        if requested_lines < 0:
            raise ToolError("Error: 'lines' must not be negative")
    else:
        requested_lines = None
    max_length = _as_int(args.get("max_length", DEFAULT_MAX_LENGTH),
                         DEFAULT_MAX_LENGTH)
    max_length = max(0, min(max_length, MAX_READ_CHARS))
    direction = "tail" if args.get("direction") == "tail" else "head"

    info = _stat_or_raise(path)
    size = info.st_size
    if stat_module.S_ISDIR(info.st_mode):
        raise ToolError("Error: Path is a directory: " + path)

    try:
        handle = open(path, "rb")
    except OSError as exc:
        raise ToolError("Error: cannot read %s: %s" % (path, _errno_reason(exc)))

    with handle:
        sniff = handle.read(min(BINARY_SNIFF_BYTES, max(size, 0)))
        if b"\x00" in sniff:
            return True, "[%s | %d bytes | binary file \u2014 cannot display contents]" % (
                path, size)
        handle.seek(0)
        cap = max(4 * (max_length + 1), 4)
        if direction == "tail":
            total_lines, selected, content_bytes = _scan_tail(
                handle, requested_lines, cap)
        else:
            total_lines, selected, content_bytes = _scan_head(
                handle, offset, requested_lines, cap)

    content = content_bytes.decode("utf-8", "replace")

    if direction == "tail":
        show_start = (total_lines - selected) + 1
    else:
        show_start = offset
    show_end = show_start + selected - 1

    effective_start = show_start
    effective_end = show_end
    next_offset = None
    truncated = False

    if len(content) > max_length:
        truncated = True
        if direction == "tail":
            # tail means the end of the file: keep the end, not the start.
            content = content[-max_length:] if max_length > 0 else ""
            first_newline = content.find("\n")
            if 0 <= first_newline < len(content) - 1:
                content = content[first_newline + 1:]
            effective_start = effective_end - content.count("\n")
        else:
            content = content[:max_length]
            # Back off to the last complete line so the next page neither
            # re-reads nor splits a line.
            last_newline = content.rfind("\n")
            if last_newline > 0:
                content = content[:last_newline]
            effective_end = show_start + content.count("\n")
            if effective_end < total_lines:
                next_offset = effective_end + 1

    header = _read_header(path, size, total_lines, effective_start,
                          effective_end, truncated, max_length, next_offset)
    return True, header + "\n" + content


# --------------------------------------------------------------------------
# write
# --------------------------------------------------------------------------

def _file_length_or_none(path):
    try:
        return os.stat(path).st_size
    except OSError:
        return None


def op_write(path, args, handoff=None):
    path = _validated_path(path)
    content = args.get("content", "")
    if content is None:
        content = ""
    if not isinstance(content, str):
        raise ToolError("Error: 'content' must be a string")
    append = _as_bool(args.get("append", False))
    stage = "append to" if append else "write"

    try:
        data = content.encode("utf-8")
    except UnicodeEncodeError:
        raise ToolError("Error: Content is not valid UTF-8")

    # Auto-create parents regardless of create_dirs (the existing file_write
    # behavior). A failure here stops the call: the file is never opened.
    _ensure_parent_dirs(path, handoff)

    handle = None
    try:
        # Exclusive creation distinguishes our new inode from existing content.
        # Existing overwrite/append retains inode, owner, mode and ACL.
        try:
            # A normal output symlink may name a not-yet-created target.
            # Open its resolved target exclusively so this new inode also
            # participates in App ownership handoff.
            handle = open(os.path.realpath(path), "xb")
        except FileExistsError:
            handle = open(path, "ab" if append else "wb")
        else:
            try:
                if handoff is not None:
                    handoff.created(path, handle.fileno())
            except OSError as exc:
                handle.close()
                raise ToolError(
                    "Error: App ownership handoff failed for new file %s: %s. "
                    "An empty new file may exist; no content was written."
                    % (path, _errno_reason(exc)))
    except OSError as exc:
        raise ToolError(
            "Error: cannot %s %s: %s\n"
            "  Nothing was written to %s (the file could not be opened)."
            % (stage, path, _errno_reason(exc), path))

    try:
        try:
            handle.write(data)
            handle.flush()
        finally:
            try:
                handle.close()
            except OSError:
                pass
    except OSError as exc:
        length = _file_length_or_none(path)
        raise ToolError(
            "Error: cannot %s %s: %s\n"
            "  file: length=%s bytes\n"
            "  The write is incomplete - re-read %s before retrying."
            % (stage, path, _errno_reason(exc),
               "unknown" if length is None else length, path))

    try:
        size = os.stat(path).st_size
    except OSError as exc:
        raise ToolError(
            "Error: cannot stat %s after writing: %s\n"
            "  %d bytes were handed to the file; the write may be complete."
            % (path, _errno_reason(exc), len(data)))
    return True, "Wrote to %s (%d bytes)" % (path, size)


# --------------------------------------------------------------------------
# edit
# --------------------------------------------------------------------------

def op_edit(path, args):
    path = _validated_path(path)
    old_string = args.get("old_string")
    if old_string is None:
        old_string = ""
    if not isinstance(old_string, str):
        raise ToolError("Error: 'old_string' must be a string")
    if old_string == "":
        raise ToolError("Error: 'old_string' is required and cannot be empty")
    new_string = args.get("new_string", "")
    if new_string is None:
        new_string = ""
    if not isinstance(new_string, str):
        raise ToolError("Error: 'new_string' must be a string")
    replace_all = _as_bool(args.get("replace_all", False))

    info = _stat_or_raise(path)
    if stat_module.S_ISDIR(info.st_mode):
        raise ToolError("Error: Path is a directory: " + path)
    size = info.st_size
    if size > MAX_EDIT_BYTES:
        raise ToolError(
            "Error: %s is %d bytes; file_edit handles files up to %d bytes. "
            "Use the shell (e.g. sed or a script) for larger files."
            % (path, size, MAX_EDIT_BYTES))

    try:
        with open(path, "rb") as handle:
            raw = handle.read(MAX_EDIT_BYTES + 1)
    except OSError as exc:
        raise ToolError("Error: cannot read %s: %s" % (path, _errno_reason(exc)))
    if len(raw) > MAX_EDIT_BYTES:
        raise ToolError(
            "Error: %s is %d bytes; file_edit handles files up to %d bytes. "
            "Use the shell (e.g. sed or a script) for larger files."
            % (path, len(raw), MAX_EDIT_BYTES))

    try:
        content = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ToolError(
            "Error: cannot read %s: file is not valid UTF-8 (byte offset %d)\n"
            "  Nothing was written; the file was not modified."
            % (path, exc.start))

    # Non-overlapping count, exactly like the Kotlin indexOf loop.
    count = content.count(old_string)
    if count == 0:
        raise ToolError("Error: old_string not found in " + path)
    if count > 1 and not replace_all:
        raise ToolError(
            "Error: old_string found %d times in %s. Use replace_all=true to replace "
            "all occurrences, or provide a more specific old_string that matches "
            "exactly once." % (count, path))

    if replace_all:
        updated = content.replace(old_string, new_string)
        replacements = count
    else:
        updated = content.replace(old_string, new_string, 1)
        replacements = 1

    try:
        data = updated.encode("utf-8")
    except UnicodeEncodeError:
        raise ToolError(
            "Error: the edited content is not valid UTF-8; nothing was written")

    handle = None
    try:
        handle = open(path, "wb")
    except OSError as exc:
        raise ToolError(
            "Error: cannot write %s: %s\n"
            "  Nothing was written to %s (the file could not be opened)."
            % (path, _errno_reason(exc), path))

    try:
        try:
            handle.write(data)
            handle.flush()
        finally:
            try:
                handle.close()
            except OSError:
                pass
    except OSError as exc:
        length = _file_length_or_none(path)
        raise ToolError(
            "Error: cannot write %s: %s\n"
            "  file: length=%s bytes\n"
            "  The file may be partially written or truncated - re-read %s "
            "before retrying."
            % (path, _errno_reason(exc),
               "unknown" if length is None else length, path))

    try:
        written = os.stat(path).st_size
    except OSError as exc:
        raise ToolError(
            "Error: cannot stat %s after writing: %s\n"
            "  %d bytes were handed to the file; the edit may be complete."
            % (path, _errno_reason(exc), len(data)))
    if written != len(data):
        raise ToolError(
            "Error: wrote %d bytes to %s but the file is %d bytes\n"
            "  The write may be incomplete - re-read %s before retrying."
            % (len(data), path, written, path))
    return True, "Edited %s (%d replacement(s), %d bytes)" % (
        path, replacements, written)


# --------------------------------------------------------------------------
# process entry point
# --------------------------------------------------------------------------

_HANDLERS = {"read": op_read, "write": op_write, "edit": op_edit}


def _safe(exc):
    """Short single-line form of an exception, for stderr only."""
    try:
        text = str(exc)
    except Exception:
        text = ""
    text = " ".join(text.split())
    if len(text) > 200:
        text = text[:200] + "..."
    return text or "(no message)"


def _respond(response_path, success, text, metadata=None):
    """Overwrite the pre-created response file in place with the JSON result."""
    result = {"success": bool(success), "text": str(text)}
    if metadata is not None:
        result["execution"] = metadata
    payload = json.dumps(result, ensure_ascii=False)
    handle = open(response_path, "w", encoding="utf-8", newline="")
    try:
        handle.write(payload)
        handle.flush()
    finally:
        handle.close()


def main(argv):
    if len(argv) != 3:
        sys.stderr.write(
            "usage: chroot_file_tools.py REQUEST_JSON RESPONSE_JSON\n")
        return 2
    request_path = argv[1]
    response_path = argv[2]

    success = False
    text = None
    path = ""
    handoff = None
    try:
        with open(request_path, "rb") as handle:
            request = json.loads(handle.read().decode("utf-8"))
        if not isinstance(request, dict):
            raise ValueError("request must be a JSON object")
        operation = request.get("operation")
        path = request.get("path")
        args = request.get("args")
        if args is None:
            args = {}
        if not isinstance(args, dict):
            args = {}
        if not isinstance(path, str) or path.strip() == "":
            fallback = args.get("path")
            path = fallback if isinstance(fallback, str) else ""
        handler = _HANDLERS.get(operation) if isinstance(operation, str) else None
        if handler is None:
            name = operation if isinstance(operation, str) else type(operation).__name__
            raise ToolError(
                "Error: unsupported operation: %s (expected read, write or edit)"
                % name)
        handoff = AppHandoff(request.get("app_handoff"))
        if operation == "write":
            success, text = handler(path, args, handoff)
        else:
            success, text = handler(path, args)
    except ToolError as exc:
        success, text = False, str(exc)
    except (OSError, ValueError, UnicodeDecodeError) as exc:
        detail = _safe(exc)
        success, text = False, "Error: invalid request: " + detail
        sys.stderr.write("chroot_file_tools: %s: %s\n"
                         % (type(exc).__name__, detail))
    except Exception as exc:
        # Never echo the request body: stderr carries the exception only.
        detail = _safe(exc)
        success = False
        text = "Error: internal failure in chroot file helper: " + detail
        sys.stderr.write("chroot_file_tools: %s: %s\n"
                         % (type(exc).__name__, detail))

    if text is None:
        success, text = False, "Error: no result"
    try:
        metadata = {"backend": "CHROOT", "euid": os.geteuid(), "egid": os.getegid(),
                    "handed_off": handoff.count if handoff else 0}
        try:
            info = os.stat(path)
            metadata.update(uid=info.st_uid, gid=info.st_gid,
                            mode=format(info.st_mode & 0o7777, "04o"))
        except (OSError, ValueError, TypeError):
            pass
        _respond(response_path, success, text, metadata)
    except Exception as exc:
        sys.stderr.write("chroot_file_tools: cannot write response: %s: %s\n"
                         % (type(exc).__name__, _safe(exc)))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
