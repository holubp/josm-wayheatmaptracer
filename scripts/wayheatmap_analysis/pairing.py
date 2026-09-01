"""Deterministic conservative matching of manual OSM ways to captured source ways."""

from __future__ import annotations

import math
from dataclasses import dataclass

from .safe_osm import WayRecord


@dataclass(frozen=True)
class BundleWay:
    """One source way recovered from a validated debug bundle."""

    bundle_sha256: str
    bundle_label: str
    way: WayRecord


@dataclass(frozen=True)
class PairingDecision:
    """A paired, ambiguous, or unmatched manual reference without coordinates."""

    reference_sha256: str
    reference_label: str
    reference_way_id: int
    reference_signature: str
    status: str
    bundle_sha256: str | None
    bundle_label: str | None
    bundle_way_id: int | None
    score: float
    reasons: tuple[str, ...]
    alternatives: tuple[dict[str, object], ...] = ()

    def to_manifest(self) -> dict[str, object]:
        """Return deterministic JSON/YAML-compatible non-sensitive metadata."""

        return {
            "referenceSha256": self.reference_sha256,
            "referenceLabel": self.reference_label,
            "referenceWayId": self.reference_way_id,
            "referenceSignature": self.reference_signature,
            "status": self.status,
            "bundleSha256": self.bundle_sha256,
            "bundleLabel": self.bundle_label,
            "bundleWayId": self.bundle_way_id,
            "score": round(self.score, 6),
            "reasons": list(self.reasons),
            "alternatives": list(self.alternatives),
        }


def pair_references(
    references: list[tuple[str, str, WayRecord]],
    bundle_ways: list[BundleWay],
    *,
    minimum_score: float = 55.0,
    dominance_margin: float = 15.0,
) -> list[PairingDecision]:
    """Pair each reference only when one candidate is both sufficient and dominant."""

    decisions = []
    ordered_bundles = sorted(bundle_ways,
                             key=lambda item: (item.bundle_sha256, item.way.way_id, item.bundle_label))
    for reference_sha, reference_label, reference in sorted(
        references, key=lambda item: (item[0], item[2].way_id, item[1])
    ):
        ranked = []
        for candidate in ordered_bundles:
            score, reasons = _score(reference, candidate.way)
            ranked.append((score, candidate.bundle_sha256, candidate.way.way_id,
                           candidate.bundle_label, reasons))
        ranked.sort(key=lambda item: (-item[0], item[1], item[2], item[3]))
        signature = reference.signature.geometry_hash
        if not ranked or ranked[0][0] < minimum_score:
            decisions.append(PairingDecision(reference_sha, reference_label, reference.way_id,
                                              signature, "unmatched", None, None, None,
                                              ranked[0][0] if ranked else 0.0,
                                              ranked[0][4] if ranked else ()))
            continue
        best = ranked[0]
        competing = [item for item in ranked[1:] if best[0] - item[0] < dominance_margin]
        if competing:
            alternatives = tuple(_alternative(item) for item in [best, *competing])
            decisions.append(PairingDecision(reference_sha, reference_label, reference.way_id,
                                              signature, "ambiguous", None, None, None,
                                              best[0], best[4], alternatives))
            continue
        decisions.append(PairingDecision(reference_sha, reference_label, reference.way_id,
                                          signature, "paired", best[1], best[3], best[2],
                                          best[0], best[4]))
    return decisions


def _alternative(item) -> dict[str, object]:
    """Serialize one non-selected plausible pairing without geometry."""

    return {"bundleSha256": item[1], "bundleWayId": item[2], "bundleLabel": item[3],
            "score": round(item[0], 6), "reasons": list(item[4])}


def _score(reference: WayRecord, candidate: WayRecord) -> tuple[float, tuple[str, ...]]:
    """Score documented identity, endpoint, length, and monotone-overlap evidence."""

    score = 0.0
    reasons = []
    if reference.way_id > 0 and reference.way_id == candidate.way_id:
        score += 70.0
        reasons.append("exact-way-id")
    if reference.node_refs and (reference.node_refs == candidate.node_refs
                                or reference.node_refs == tuple(reversed(candidate.node_refs))):
        score += 25.0
        reasons.append("ordered-node-ids")
    if reference.signature.geometry_hash == candidate.signature.geometry_hash:
        score += 50.0
        reasons.append("geometry-hash")
    if reference.signature.node_count == candidate.signature.node_count:
        score += 5.0
        reasons.append("node-count")
    maximum_length = max(1, reference.signature.length_bucket_meters,
                         candidate.signature.length_bucket_meters)
    relative_length_error = abs(reference.signature.length_bucket_meters
                                - candidate.signature.length_bucket_meters) / maximum_length
    if relative_length_error <= 0.05:
        score += 15.0
        reasons.append("length-bucket")
    elif relative_length_error <= 0.20:
        score += 6.0
        reasons.append("similar-length")
    endpoint_distance = _endpoint_distance(reference, candidate)
    if endpoint_distance <= 2.0:
        score += 20.0
        reasons.append("endpoint-correspondence")
    elif endpoint_distance <= 10.0:
        score += 10.0
        reasons.append("near-endpoints")
    elif endpoint_distance <= 50.0:
        score += 3.0
        reasons.append("possible-endpoints")
    overlap = _mean_monotone_distance(reference, candidate)
    if overlap <= 3.0:
        score += 25.0
        reasons.append("monotone-overlap")
    elif overlap <= 15.0:
        score += 10.0
        reasons.append("near-overlap")
    return score, tuple(reasons)


def _endpoint_distance(left: WayRecord, right: WayRecord) -> float:
    """Return the lower mean endpoint distance across both directions."""

    if not left.coordinates or not right.coordinates or left.coordinates[0] is None \
            or left.coordinates[-1] is None or right.coordinates[0] is None \
            or right.coordinates[-1] is None:
        return math.inf
    direct = (_distance(left.coordinates[0], right.coordinates[0])
              + _distance(left.coordinates[-1], right.coordinates[-1])) / 2
    reverse = (_distance(left.coordinates[0], right.coordinates[-1])
               + _distance(left.coordinates[-1], right.coordinates[0])) / 2
    return min(direct, reverse)


def _mean_monotone_distance(left: WayRecord, right: WayRecord) -> float:
    """Compare direction-normalized geometries at monotone path fractions."""

    a = [point for point in left.coordinates if point is not None]
    b = [point for point in right.coordinates if point is not None]
    if not a or not b:
        return math.inf
    count = max(len(a), len(b))

    def compare(candidate):
        """Measure one candidate direction at common monotone fractions."""

        total = 0.0
        for index in range(count):
            ai = round(index * (len(a) - 1) / max(1, count - 1))
            bi = round(index * (len(candidate) - 1) / max(1, count - 1))
            total += _distance(a[ai], candidate[bi])
        return total / count
    return min(compare(b), compare(list(reversed(b))))


def _distance(left, right) -> float:
    """Return haversine ground distance in metres."""

    lat1, lon1 = map(math.radians, left)
    lat2, lon2 = map(math.radians, right)
    dlat, dlon = lat2 - lat1, lon2 - lon1
    value = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 6_371_008.8 * 2 * math.asin(min(1.0, math.sqrt(value)))
