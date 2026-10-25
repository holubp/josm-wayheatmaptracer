"""Tiny PUBLIC-key DATA tests; no tool invocation or runtime qualification."""
import base64
import hashlib
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/b-public-keyring-data.py'
FIXTURE = Path(__file__).with_name('fixtures') / 'temurin-public-key.asc.b64'


def public_key_fixture():
    return base64.b64decode(b''.join(FIXTURE.read_bytes().splitlines()), validate=True)


def armor(payload):
    # Independent RFC4880 bit-at-a-time test construction.
    crc = 0xB704CE
    for value in payload:
        crc ^= value << 16
        for _ in range(8):
            crc <<= 1
            if crc & 0x1000000:
                crc ^= 0x1864CFB
    return (b'-----BEGIN PGP PUBLIC KEY BLOCK-----\n\n'
            + base64.b64encode(payload) + b'\n='
            + base64.b64encode(crc.to_bytes(3, 'big'))
            + b'\n-----END PGP PUBLIC KEY BLOCK-----\n')


class PublicKeyringDataTest(unittest.TestCase):
    def module(self):
        self.assertTrue(SCRIPT.is_file(), 'PUBLIC key DATA decoder is not implemented')
        spec = importlib.util.spec_from_file_location('public_key_data_unit', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_retained_public_key_matches_independent_historical_binary_digest(self):
        m = self.module()
        source = public_key_fixture()
        self.assertEqual(len(source), 1838)
        self.assertEqual(hashlib.sha256(source).hexdigest(),
                         '466784df8a3cf06ce898ee6493140167db56a647edbad110b1f0566fd3555e46')
        binary, report = m.temurin_public_key_data(source)
        self.assertEqual(hashlib.sha256(binary).hexdigest(),
                         'cc960a856b09839ce0eb11777ea9362379302301cdb39c19e5c93c4e1ed1d56c')
        self.assertEqual(report['primary'], '3B04D753C9050D9A5D343F39843C48A565F8F04B')
        self.assertEqual(report['bytes'], len(binary))
        self.assertEqual(report['scope'], 'PUBLIC_KEY_DATA')
        self.assertFalse(report['qualified'])
        self.assertEqual(report['factual_authority'], 'NOT_ESTABLISHED')

    def test_crc_has_independent_standard_vector(self):
        self.assertEqual(self.module()._crc24(b'123456789'), 0x21CF02)

    def test_fixed_source_cannot_be_replaced_by_self_consistent_input(self):
        m = self.module()
        source = public_key_fixture()
        for data in (source + b'\n', source.replace(b'\n', b'\r\n'), armor(b'\xc6\x06\x04\0\0\0\0\x01')):
            with self.subTest(data_size=len(data)), self.assertRaisesRegex(ValueError, '^PUBLIC_KEY_SOURCE$'):
                m.temurin_public_key_data(data)

    def test_crc_armor_and_base64_corruption_refuse(self):
        m = self.module()
        source = public_key_fixture()
        lines = source.splitlines()
        crc = next(i for i, row in enumerate(lines) if row.startswith(b'='))
        broken_crc = lines.copy()
        broken_crc[crc] = b'=AAAA'
        for data in (b'\n'.join(broken_crc) + b'\n', source + source,
                     source.replace(b'PUBLIC KEY BLOCK', b'PRIVATE KEY BLOCK'),
                     source.replace(b'\n\n', b'\n\n!', 1), source[:-20], b'X' * 65537):
            with self.subTest(size=len(data)), self.assertRaises(ValueError):
                m._decode_armored_public_key(data)

    def test_packet_bounds_secret_tags_multiple_primary_and_truncation_refuse(self):
        m = self.module()
        primary = b'\xc6\x06\x04\0\0\0\0\x01'
        for payload in (b'\xc5\x01X', b'\xc7\x01X', b'\xc6\xff\0\0\x10\0x',
                        b'\xc6\xe0X', b'\x9bX', primary + primary,
                        primary + b'\xc2\x05x', b'\xc2\x01x' + primary,
                        primary + b'\xcb\x01x', b'\xc6\x06\x05\0\0\0\0\x01'):
            with self.subTest(payload=payload), self.assertRaises(ValueError):
                m._decode_armored_public_key(armor(payload))

    def test_old_and_new_packet_lengths_preserve_public_packet_bytes(self):
        m = self.module()
        body = b'\x04\0\0\0\0\x01'
        for packet in (b'\xc6\x06' + body, b'\x98\x06' + body,
                       b'\x99\0\x06' + body, b'\x9a\0\0\0\x06' + body,
                       b'\xc6\xff\0\0\0\x06' + body):
            with self.subTest(packet=packet):
                binary, primary = m._decode_armored_public_key(armor(packet))
                self.assertEqual(binary, packet)
                self.assertEqual(primary, hashlib.sha1(b'\x99\0\x06' + body).hexdigest().upper())

    def test_raw_ring_and_packet_cardinality_are_bounded(self):
        m = self.module()
        primary = b'\xc6\x06\x04\0\0\0\0\x01'
        for payload in (primary + b'\xc2\x01x' * 64,
                        primary + b'\xcd\xff\0\0\x20\0' + b'x' * 8192):
            with self.assertRaises(ValueError):
                m._decode_armored_public_key(armor(payload))

    def test_two_octet_new_length_and_noncanonical_base64(self):
        m = self.module()
        primary = b'\xc6\x06\x04\0\0\0\0\x01'
        payload = primary + b'\xcd\xc0\x08' + b'u' * 200
        encoded = armor(payload)
        # Wrap the test body's base64 to satisfy the independent line ceiling.
        lines = encoded.splitlines()
        body = lines[2]
        encoded = b'\n'.join(lines[:2] + [body[i:i + 64] for i in range(0, len(body), 64)] + lines[3:]) + b'\n'
        self.assertEqual(m._decode_armored_public_key(encoded)[0], payload)
        original = armor(primary)
        # The last quartet's pad bits must be zero, even if decoding preserves bytes.
        bad = original.replace(b'AAE=', b'AAF=')
        self.assertNotEqual(bad, original)
        with self.assertRaisesRegex(ValueError, '^PUBLIC_ARMOR_BASE64$'):
            m._decode_armored_public_key(bad)


if __name__ == '__main__':
    unittest.main()
