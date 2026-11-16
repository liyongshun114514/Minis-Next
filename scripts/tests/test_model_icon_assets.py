"""Validate bundled offline model icons without optional image libraries."""
import json
from pathlib import Path
import struct
import unittest
import zlib

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'src/android/app/src/main/assets/model-icons'


class ModelIconAssetsTest(unittest.TestCase):
    def test_catalog_is_unique_and_covers_every_png(self):
        rows = json.loads((ASSETS / 'catalog.json').read_text())
        self.assertGreaterEqual(len(rows), 20)
        self.assertEqual(len(rows), len({r['id'] for r in rows}))
        self.assertEqual({r['file'] for r in rows}, {p.name for p in ASSETS.glob('*.png')})
        for row in rows:
            self.assertEqual(Path(row['file']).name, row['file'])
            self.assertTrue(row['name'])
            self.assertIsInstance(row['aliases'], list)

    def test_png_dimensions_crc_and_pixel_stream(self):
        for path in ASSETS.glob('*.png'):
            with self.subTest(icon=path.name):
                data = path.read_bytes()
                self.assertEqual(data[:8], b'\x89PNG\r\n\x1a\n')
                offset, compressed, dimensions = 8, bytearray(), None
                while offset < len(data):
                    length = struct.unpack('>I', data[offset:offset+4])[0]
                    kind = data[offset+4:offset+8]
                    payload = data[offset+8:offset+8+length]
                    crc = struct.unpack('>I', data[offset+8+length:offset+12+length])[0]
                    self.assertEqual(zlib.crc32(kind + payload) & 0xffffffff, crc)
                    if kind == b'IHDR':
                        dimensions = struct.unpack('>IIBBBBB', payload)
                        self.assertEqual(dimensions, (192, 192, 8, 6, 0, 0, 0))
                    if kind == b'IDAT':
                        compressed.extend(payload)
                    offset += length + 12
                    if kind == b'IEND':
                        break
                self.assertIsNotNone(dimensions)
                self.assertEqual(offset, len(data))
                self.assertEqual(len(zlib.decompress(compressed)), 192 * (1 + 192 * 4))

    def test_upstream_notice_ships_with_assets(self):
        notice = (ASSETS / 'LICENSE-lobehub-icons.txt').read_text()
        self.assertIn('MIT License', notice)
        self.assertIn('Copyright (c) 2023 LobeHub', notice)
        self.assertIn('Permission is hereby granted', notice)
        self.assertIn('LobeHub Icons', (ROOT / 'THIRD_PARTY_LICENSES.md').read_text())


if __name__ == '__main__':
    unittest.main()
