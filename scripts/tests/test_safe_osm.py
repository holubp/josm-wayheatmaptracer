"""Security and deterministic-signature tests for local OSM XML parsing."""

from __future__ import annotations

import gzip

import pytest

from wayheatmap_analysis.safe_osm import OsmError, OsmLimits, parse_osm_bytes


NORMAL_OSM = b"""<?xml version='1.0'?>
<osm version='0.6' generator='test'>
  <node id='9223372036854775806' lat='50.0000' lon='14.0000'/>
  <node id='2' lat='50.0001' lon='14.0002'><tag k='barrier' v='gate'/></node>
  <node id='3' lat='50.0002' lon='14.0004'/>
  <way id='17'><nd ref='9223372036854775806'/><nd ref='2'/><nd ref='3'/>
    <tag k='highway' v='path'/></way>
  <relation id='9'/>
</osm>"""


def test_parses_normal_osm_and_emits_coordinate_free_stable_signature():
    first = parse_osm_bytes(NORMAL_OSM, source_name="reference.osm")
    second = parse_osm_bytes(NORMAL_OSM, source_name="different-local-name.osm")

    assert first.root_name == "osm"
    assert first.node_count == 3
    assert first.way_count == 1
    assert first.relation_count == 1
    assert first.ways[0].way_id == 17
    assert first.ways[0].node_refs[0] == 9223372036854775806
    assert first.way_signatures == second.way_signatures
    public = first.way_signatures[0].to_manifest()
    assert set(public) == {
        "wayId",
        "nodeCount",
        "lengthBucketMeters",
        "endpointDegreeClass",
        "endpointTagPresence",
        "protectedNodeIdHash",
        "geometryHash",
    }
    assert "50.000" not in repr(public)
    assert "14.000" not in repr(public)


def test_accepts_real_gzip_magic_and_rejects_suffix_or_trailing_size_abuse():
    compressed = gzip.compress(NORMAL_OSM, mtime=0)
    parsed = parse_osm_bytes(compressed, source_name="reference.osm.gz")
    assert parsed.way_count == 1

    with pytest.raises(OsmError) as caught:
        parse_osm_bytes(NORMAL_OSM, source_name="reference.osm.gz")
    assert caught.value.code == "GZIP_MAGIC"

    with pytest.raises(OsmError) as caught:
        parse_osm_bytes(
            compressed,
            source_name="reference.osm.gz",
            limits=OsmLimits(max_uncompressed_bytes=32),
        )
    assert caught.value.code == "UNCOMPRESSED_SIZE"

    with pytest.raises(OsmError) as caught:
        parse_osm_bytes(b"\x1f\x8bnot-a-gzip-stream", source_name="reference.osm.gz")
    assert caught.value.code == "MALFORMED_GZIP"


@pytest.mark.parametrize(
    "payload, code",
    [
        (b"<!DOCTYPE osm [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><osm>&xxe;</osm>", "DTD"),
        (b"<osm xmlns:xi='http://www.w3.org/2001/XInclude'><xi:include href='file:///x'/></osm>", "XINCLUDE"),
        (b"<osm xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance' xsi:schemaLocation='x http://host/x'/>", "EXTERNAL_SCHEMA"),
    ],
)
def test_rejects_external_xml_features(payload, code):
    with pytest.raises(OsmError) as caught:
        parse_osm_bytes(payload, source_name="malicious.osm")
    assert caught.value.code == code


def test_rejects_element_depth_attribute_and_text_limits():
    with pytest.raises(OsmError) as caught:
        parse_osm_bytes(b"<osm><a/><b/></osm>", limits=OsmLimits(max_elements=2))
    assert caught.value.code == "ELEMENT_LIMIT"

    with pytest.raises(OsmError) as caught:
        parse_osm_bytes(b"<osm><a><b/></a></osm>", limits=OsmLimits(max_depth=2))
    assert caught.value.code == "DEPTH_LIMIT"

    with pytest.raises(OsmError) as caught:
        parse_osm_bytes(b"<osm><a x='12345'/></osm>", limits=OsmLimits(max_attribute_bytes=4))
    assert caught.value.code == "ATTRIBUTE_LIMIT"

    with pytest.raises(OsmError) as caught:
        parse_osm_bytes(b"<osm><note>12345</note></osm>", limits=OsmLimits(max_text_bytes=4))
    assert caught.value.code == "TEXT_LIMIT"


def test_rejects_malformed_or_out_of_range_geometry():
    with pytest.raises(OsmError) as caught:
        parse_osm_bytes(b"<osm><node id='1' lat='91' lon='0'/></osm>")
    assert caught.value.code == "INVALID_COORDINATE"

    with pytest.raises(OsmError) as caught:
        parse_osm_bytes(b"<osm><way id='x'/></osm>")
    assert caught.value.code == "INVALID_OSM"
