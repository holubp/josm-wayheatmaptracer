"""Deterministic public-key DATA only; no process, file or trust installation.

GnuPG's documented legacy ring contains binary OpenPGP packets. This module
checks the retained public input, armour CRC and primary fingerprint, then
compares its binary output with the independently retained dearmor digest.
These checks do not authenticate a tool, execute signature verification or
issue a SOURCE/HOST/runtime receipt. Generic parsing below is private UNIT
machinery; only the fixed retained Temurin public input has a DATA wrapper.
"""
import base64
import binascii
import hashlib

PUBLIC_ASCII_BYTES = 1838
PUBLIC_ASCII_SHA256 = '466784df8a3cf06ce898ee6493140167db56a647edbad110b1f0566fd3555e46'
PRIMARY = '3B04D753C9050D9A5D343F39843C48A565F8F04B'
HISTORICAL_RING_SHA256 = 'cc960a856b09839ce0eb11777ea9362379302301cdb39c19e5c93c4e1ed1d56c'
MAX_ARMOR = 65536
MAX_RING = 4096
MAX_PACKETS = 32


def _need(condition, reason):
    if not condition:
        raise ValueError(reason)


def _crc24(data):
    value = 0xB704CE
    for octet in data:
        value ^= octet << 16
        for _ in range(8):
            value <<= 1
            if value & 0x1000000:
                value ^= 0x1864CFB
    return value & 0xFFFFFF


def _public_packets(raw):
    """Bounded public-packet framing, not key/signature cryptographic validation."""
    _need(0 < len(raw) <= MAX_RING, 'PUBLIC_RING_SIZE')
    cursor = 0
    count = 0
    primary = None
    while cursor < len(raw):
        count += 1
        _need(count <= MAX_PACKETS, 'PUBLIC_PACKET_COUNT')
        header = raw[cursor]
        cursor += 1
        _need(header & 0x80, 'PUBLIC_PACKET_HEADER')
        if header & 0x40:
            tag = header & 0x3F
            _need(cursor < len(raw), 'PUBLIC_PACKET_LENGTH')
            first = raw[cursor]
            cursor += 1
            if first < 192:
                length = first
            elif first < 224:
                _need(cursor < len(raw), 'PUBLIC_PACKET_LENGTH')
                length = ((first - 192) << 8) + raw[cursor] + 192
                cursor += 1
            else:
                _need(first == 255, 'PUBLIC_PACKET_PARTIAL')
                _need(cursor + 4 <= len(raw), 'PUBLIC_PACKET_LENGTH')
                length = int.from_bytes(raw[cursor:cursor + 4], 'big')
                cursor += 4
        else:
            tag = (header >> 2) & 15
            kind = header & 3
            _need(kind != 3, 'PUBLIC_PACKET_INDETERMINATE')
            width = (1, 2, 4)[kind]
            _need(cursor + width <= len(raw), 'PUBLIC_PACKET_LENGTH')
            length = int.from_bytes(raw[cursor:cursor + width], 'big')
            cursor += width
        _need(tag in (2, 6, 13, 14, 17), 'PUBLIC_PACKET_TAG')
        _need(length > 0 and length <= len(raw) - cursor, 'PUBLIC_PACKET_EXTENT')
        _need(primary is not None or tag == 6, 'PUBLIC_PRIMARY_FIRST')
        if tag in (6, 14):
            _need(length >= 6 and raw[cursor] == 4, 'PUBLIC_KEY_VERSION')
        if tag == 6:
            _need(primary is None, 'PUBLIC_PRIMARY_COUNT')
            body = raw[cursor:cursor + length]
            primary = hashlib.sha1(b'\x99' + length.to_bytes(2, 'big') + body).hexdigest().upper()
        cursor += length
    _need(primary is not None, 'PUBLIC_PRIMARY_COUNT')
    return primary


def _decode_armored_public_key(data):
    _need(type(data) is bytes and 0 < len(data) <= MAX_ARMOR, 'PUBLIC_ARMOR_SIZE')
    _need(data.endswith(b'\n') and b'\r' not in data, 'PUBLIC_ARMOR_LINES')
    lines = data.split(b'\n')
    _need(lines.pop() == b'', 'PUBLIC_ARMOR_LINES')
    _need(len(lines) >= 5 and lines[0] == b'-----BEGIN PGP PUBLIC KEY BLOCK-----'
          and lines[-1] == b'-----END PGP PUBLIC KEY BLOCK-----', 'PUBLIC_ARMOR_FRAME')
    cursor = 1
    while cursor < len(lines) and lines[cursor]:
        row = lines[cursor]
        _need(cursor <= 8 and len(row) <= 128 and row.startswith((b'Version:', b'Comment:'))
              and all(32 <= value <= 126 for value in row), 'PUBLIC_ARMOR_HEADER')
        cursor += 1
    _need(cursor < len(lines) and lines[cursor] == b'', 'PUBLIC_ARMOR_SEPARATOR')
    rows = lines[cursor + 1:-2]
    checksum = lines[-2]
    _need(rows and len(rows) <= 80 and all(1 <= len(row) <= 76 for row in rows), 'PUBLIC_ARMOR_BODY')
    _need(len(checksum) == 5 and checksum.startswith(b'='), 'PUBLIC_ARMOR_CRC')
    payload = b''.join(rows)
    _need(len(payload) <= 4 * ((MAX_RING + 2) // 3), 'PUBLIC_RING_SIZE')
    try:
        raw = base64.b64decode(payload, validate=True)
        crc = base64.b64decode(checksum[1:], validate=True)
    except (binascii.Error, ValueError):
        raise ValueError('PUBLIC_ARMOR_BASE64') from None
    _need(base64.b64encode(raw) == payload, 'PUBLIC_ARMOR_BASE64')
    _need(len(crc) == 3 and _crc24(raw) == int.from_bytes(crc, 'big'), 'PUBLIC_ARMOR_CRC')
    return raw, _public_packets(raw)


def temurin_public_key_data(data):
    """Return immutable raw-ring bytes and an explicitly nonauthoritative report.

    The constants bind previously retained PUBLIC evidence. They cannot approve
    a trust anchor or satisfy the separate protected-lifetime/tool-profile gates.

    Args:
        data: Exact retained ASCII-armored Temurin public-key bytes.

    Returns:
        A pair containing immutable binary-ring bytes and a diagnostic dictionary
        with source/output hashes, primary fingerprint and qualified=False.

    Raises:
        ValueError: If the source identity, framing, bounds, checksum, fingerprint
            or independently retained binary-ring digest does not match.
    """
    _need(type(data) is bytes and len(data) == PUBLIC_ASCII_BYTES
          and hashlib.sha256(data).hexdigest() == PUBLIC_ASCII_SHA256, 'PUBLIC_KEY_SOURCE')
    raw, primary = _decode_armored_public_key(data)
    _need(primary == PRIMARY, 'PUBLIC_PRIMARY_MISMATCH')
    digest = hashlib.sha256(raw).hexdigest()
    _need(digest == HISTORICAL_RING_SHA256, 'PUBLIC_RING_TRANSCRIPTION')
    return raw, {'scope': 'PUBLIC_KEY_DATA', 'qualified': False,
                 'factual_authority': 'NOT_ESTABLISHED', 'primary': primary,
                 'bytes': len(raw), 'sha256': digest, 'input_sha256': PUBLIC_ASCII_SHA256}
