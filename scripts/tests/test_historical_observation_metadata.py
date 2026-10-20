"""Authenticate historical metadata without creating executable authority."""
import base64
import io
import warnings
import zipfile
import struct
from unittest.mock import patch
import pytest
from hypothesis import given, settings, strategies as st
from scripts.tests import historical_observation_metadata as historical


def packed(entries, compression=zipfile.ZIP_STORED):
    """Build a bounded parser-only mutation; it has no authentic historical digest."""
    out = io.BytesIO()
    with warnings.catch_warnings(), zipfile.ZipFile(out, 'w', compression) as archive:
        warnings.simplefilter('ignore', UserWarning)
        for name, value in entries:
            archive.writestr(name, value)
    return out.getvalue()


def test_encoded_fixture_requires_independent_digest_even_for_decodable_whitespace():
    raw = historical.RESOURCE.read_bytes()
    assert len(historical.authenticate_encoded(raw)) == 315387
    with pytest.raises(ValueError, match='encoded'):
        historical.authenticate_encoded(raw + b'\n')


@pytest.mark.parametrize('entries', [
    [('a', b'x'), ('a', b'x')], [('a', b'x'), ('unknown', b'x')], [],
    [('../a', b'x')], [('/a', b'x')], [('a/../b', b'x')], [('a\\b', b'x')],
    [('C:a', b'x')], [('a/', b'')], [('a', b'x' * 1048577)],
])
def test_parser_rejects_duplicate_unsafe_unknown_incomplete_and_oversized(entries):
    with pytest.raises(ValueError):
        historical.parse_members(packed(entries), {'a'})


@settings(max_examples=15, deadline=None)
@given(st.text(alphabet='abcdefghijklmnopqrstuvwxyz0123456789', min_size=1, max_size=30))
def test_parser_rejects_traversal_before_returning_metadata(suffix):
    with pytest.raises(ValueError):
        historical.parse_members(packed([('../' + suffix, b'x')]), {'a'})


def test_valid_parser_fixture_is_data_only_and_preserves_exact_bytes():
    assert historical.parse_members(packed([('a', b'opaque source text')]), {'a'}) == {'a': b'opaque source text'}


def test_authenticated_public_archive_matches_independent_java_contract_and_is_immutable():
    members = historical.historical_members()
    assert len(members) == 87
    assert sum(map(len, members.values())) == 1214991
    assert members[historical.FREEZE] == (historical.ROOT / historical.FREEZE).read_bytes()
    text = (historical.ROOT / 'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/HistoricalObservationMetadataFixture.java').read_text()
    for value in [historical.REVISION, historical.ARCHIVE_SHA, historical.FREEZE_SHA, *historical.EXTRA.values()]:
        assert value in text
    with pytest.raises(TypeError):
        members['not-public'] = b'not an input'
    with pytest.raises(ValueError, match='unknown historical'):
        historical.historical_bytes('../private')


@settings(max_examples=20, deadline=None)
@given(st.integers(min_value=0, max_value=426049))
def test_any_changed_encoded_byte_fails_independent_authentication(index):
    raw = bytearray(historical.RESOURCE.read_bytes())
    raw[index] ^= 1
    with pytest.raises(ValueError, match='encoded archive digest'):
        historical.authenticate_encoded(raw)


def test_entry_and_expanded_bounds_refuse_before_returning_any_members():
    with pytest.raises(ValueError, match='directory bounds'):
        historical.parse_members(packed([(str(i), b'') for i in range(88)]), {str(i) for i in range(87)})
    entries = [(name, b'x' * 700000) for name in ('a', 'b', 'c')]
    with pytest.raises(ValueError, match='expanded bound'):
        historical.parse_members(packed(entries, zipfile.ZIP_DEFLATED), {'a', 'b', 'c'})
    with pytest.raises(ValueError, match='bounds'):
        historical.parse_members(b'x' * 2097153, {'a'})


def test_forged_directory_count_refuses_before_allocating_zip_metadata():
    raw = bytearray(packed([('a', b'x'), ('b', b'y')]))
    end = raw.rfind(b'PK\x05\x06')
    struct.pack_into('<HH', raw, end + 8, 1, 1)
    with patch.object(historical.zipfile, 'ZipFile', side_effect=AssertionError('entered ZIP metadata parser')):
        with pytest.raises(ValueError, match='directory'):
            historical.parse_members(bytes(raw), {'a', 'b'})
