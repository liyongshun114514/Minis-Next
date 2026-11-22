#!/usr/bin/env python3
"""Prepare checksum-pinned SDK jars for Robolectric's offline resolver in CI."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

MANIFEST = Path(__file__).with_name("robolectric-sdks.json")


def verified(path, checksum):
    if not path.is_file():
        return False
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha512").hexdigest() == checksum


def prepare(destination, manifest=MANIFEST, downloader=subprocess.run):
    config = json.loads(Path(manifest).read_text())
    destination = Path(destination).resolve()
    destination.mkdir(parents=True, exist_ok=True)
    for sdk in config["sdks"]:
        version = sdk["version"]
        name = f"android-all-instrumented-{version}.jar"
        target = destination / name
        checksum = sdk["sha512"]
        if verified(target, checksum):
            print(f"SDK {sdk['api']}: verified cache", flush=True)
            continue
        # Never let a partial/corrupt cache entry reach Robolectric.
        target.unlink(missing_ok=True)
        partial = target.with_suffix(".jar.partial")
        artifact_path = f"org/robolectric/android-all-instrumented/{version}/{name}"
        for base in config["repositories"]:
            partial.unlink(missing_ok=True)
            url = f"{base.rstrip('/')}/{artifact_path}"
            print(f"SDK {sdk['api']}: fetching {url}", flush=True)
            try:
                result = downloader(
                    ["curl", "--fail", "--location", "--silent", "--show-error",
                     "--connect-timeout", "15", "--max-time", "180",
                     "--retry", "2", "--retry-delay", "2", "--retry-max-time", "240",
                     "--output", str(partial), url], check=False,
                )
                if result.returncode == 0 and verified(partial, checksum):
                    partial.replace(target)
                    print(f"SDK {sdk['api']}: SHA-512 verified", flush=True)
                    break
                print(f"SDK {sdk['api']}: download/checksum failed; trying next mirror", flush=True)
            finally:
                partial.unlink(missing_ok=True)
        else:
            raise RuntimeError(f"No verified download available for {name}")
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=Path)
    args = parser.parse_args()
    prepare(args.directory)


if __name__ == "__main__":
    main()
