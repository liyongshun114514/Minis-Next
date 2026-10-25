#!/usr/bin/env python3
"""Static format check for this project's self-built aarch64 helper pair."""
from pathlib import Path
import struct
import sys


def check(path, android):
    data = Path(path).read_bytes()
    assert data[:6] == b'\x7fELF\x02\x01', f'{path}: ELF64 little-endian required'
    kind, machine = struct.unpack_from('<HH', data, 16)
    assert machine == 183, f'{path}: not aarch64'
    offset = struct.unpack_from('<Q', data, 32)[0]
    size, count = struct.unpack_from('<HH', data, 54)
    interps = []
    for i in range(count):
        typ, flags, off, addr, _, filesz, memsz, align = struct.unpack_from('<IIQQQQQQ', data, offset+i*size)
        if typ == 3:
            interps.append(data[off:off+filesz].rstrip(b'\0'))
        if android and typ == 1:
            assert align >= 16384 and (addr-off) % 16384 == 0, f'{path}: LOAD not 16 KiB compatible'
    if android:
        assert kind == 3 and interps == [b'/system/bin/linker64'], f'{path}: Android PIE executable required'
    else:
        assert not interps, f'{path}: guest client must be static'
    print(f'PASS {path}; ELF64 aarch64 {"Android PIE 16KiB" if android else "static guest"}; not executed')


if __name__ == '__main__':
    check(sys.argv[1], True)
    check(sys.argv[2], False)
