"""Deterministic, non-sensitive manual-reference pairing tests."""

from __future__ import annotations

from wayheatmap_analysis.pairing import BundleWay, pair_references
from wayheatmap_analysis.safe_osm import parse_osm_bytes


def osm(way_id: int, coordinates: list[tuple[float, float]]) -> bytes:
    nodes = "".join(
        f"<node id='{index}' lat='{lat}' lon='{lon}'/>"
        for index, (lat, lon) in enumerate(coordinates, 1)
    )
    refs = "".join(f"<nd ref='{index}'/>" for index in range(1, len(coordinates) + 1))
    return f"<osm>{nodes}<way id='{way_id}'>{refs}</way></osm>".encode()


def test_unique_exact_id_and_geometry_pair_is_selected_deterministically():
    reference = parse_osm_bytes(osm(7, [(50.0, 14.0), (50.001, 14.001)]))
    exact = parse_osm_bytes(osm(7, [(50.0, 14.0), (50.001, 14.001)]))
    other = parse_osm_bytes(osm(8, [(49.0, 13.0), (49.001, 13.001)]))

    decisions = pair_references(
        [("ref-hash", "reference.osm", reference.ways[0])],
        [
            BundleWay("b-hash", "bundle-b.zip", other.ways[0]),
            BundleWay("a-hash", "bundle-a.zip", exact.ways[0]),
        ],
    )

    assert decisions[0].status == "paired"
    assert decisions[0].bundle_sha256 == "a-hash"
    assert "exact-way-id" in decisions[0].reasons
    assert "50.0" not in repr(decisions[0].to_manifest())


def test_equal_plausible_matches_are_reported_as_ambiguous_without_guessing():
    reference = parse_osm_bytes(osm(-1, [(50.0, 14.0), (50.001, 14.001)]))
    candidate = parse_osm_bytes(osm(-1, [(50.0, 14.0), (50.001, 14.001)]))

    decisions = pair_references(
        [("ref-hash", "reference.osm", reference.ways[0])],
        [
            BundleWay("a-hash", "a.zip", candidate.ways[0]),
            BundleWay("b-hash", "b.zip", candidate.ways[0]),
        ],
    )

    assert decisions[0].status == "ambiguous"
    assert decisions[0].bundle_sha256 is None
    assert [item["bundleSha256"] for item in decisions[0].alternatives] == ["a-hash", "b-hash"]


def test_unrelated_geometry_is_left_unmatched():
    reference = parse_osm_bytes(osm(-1, [(50.0, 14.0), (50.001, 14.001)]))
    other = parse_osm_bytes(osm(-2, [(20.0, 1.0), (20.1, 1.1)]))

    decision = pair_references(
        [("ref-hash", "reference.osm", reference.ways[0])],
        [BundleWay("other", "other.zip", other.ways[0])],
    )[0]

    assert decision.status == "unmatched"
