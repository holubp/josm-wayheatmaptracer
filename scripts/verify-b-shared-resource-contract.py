"""Checked Task0 arithmetic worksheet; it grants no numerical execution authority.

Usage: python3 scripts/verify-b-shared-resource-contract.py counts.json

The flat counts file describes algebraic input dimensions, not captured observations.
The command prints a proof-contract candidate with entryEligible=false pending
independent review. Future code must satisfy its closed body/layout contract;
a matching digest or successful CLI is never numerical execution authority.
"""

import hashlib
import json
import math
import sys
from fractions import Fraction
from pathlib import Path


LONG_MAX = (1 << 63) - 1
ATTEMPT_BYTES = 64 * 1024 * 1024
ROOT = Path(__file__).resolve().parents[1]
CONTRACT_PATH = ROOT / "superpowers/docs/plans/2026-10-21-b-shared-position-connection-resource-contract.md"
SOURCE_PINS = {
    "src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/model/MetricRegion.java":
        "02820875cbf59e1792c9a16c73666255410973f7574ac70e93b39d965355f697",
    "src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/model/ScalarEvidenceField.java":
        "136c84e5e504145863c5f407cbe27915f2507b0f0287213fc8d79a7dc3be0b1f",
    "src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/AttemptMemoryLedger.java":
        "94f8d0cdf4cc914ee295e82160764a5f330e6321af9bd2a1637a9b4f61a85b6b",
}
COUNT_KEYS = frozenset(("n", "modes", "width", "height", "source_vertices", "source_cells",
                        "samples", "states", "components", "mixture_entries", "local_entries",
                        "metadata_slots", "footprint_vertices", "pairs", "region_edges",
                        "live_existing_bytes", "source_region_edges", "source_region_polygons"))
UNPROVED = ()
JDK_SOURCE_PINS = {
    "BigInteger.java": "50c292cec45578f911a309690f990036a946ee09768ed30e4d54fb18a18a95ee",
    "MutableBigInteger.java": "a9ad677a26ca32d7d39ce4e80197141c5af1b9a8aafa86511b005cc0b843b4b4",
    "StrictMath.java": "1a52f5cdb3cf7d902e0b1e1ba4733435a434fb4c5c8a50d18e5f1c6ad98c7661",
    "Math.java": "ca85b54e20ee35896b675fef59f201dbc2c56c263f9c2a89cfa10285090bb1d1",
}
CALLER_SLOTS = dict(constants=16, edge=16, clipping=16, events=16,
                    coordinates=16, conversion=16, expression_temporaries=32,
                    output_aliases=8)
DAG_PARTITIONS = dict(metadata=(32, 32, 64, 64, 32, 32),
                      response=(9 * 64, 128, 32, 64, 96, 96, 32),
                      context=(16, 16, 8, 8, 8, 32, 8, 32),
                      deficit=(32, 32, 32), pair=(128, 128, 128, 128),
                      event=(1024, 512, 256, 128, 1536, 640))


class ProofError(ValueError):
    """A count, claim or requested proof admission is unsupported."""


def _natural(value):
    if type(value) is not int or not 0 <= value <= LONG_MAX:
        raise ProofError("nonnegative signed-long integer required; bool is not a count")
    return value


def check_exact_size(value, shift=0):
    """Check absolute magnitude and a proposed left shift before allocation.

    Args:
        value: Signed Python integer modelling an exact public DAG operand.
        shift: Nonnegative proposed left-shift distance.
    Returns:
        Conservative shifted absolute-magnitude bit bound.
    Raises:
        ProofError: Invalid input or a magnitude exceeding51232-bit limbs.
    """
    if type(value) is not int:
        raise ProofError("signed exact integer required")
    _natural(shift)
    size = checked_add(abs(value).bit_length(), shift) if value else 0
    if size > 512 * 32:
        raise ProofError("public absolute magnitude exceeds512 limbs before allocation")
    return size


def checked_add(*values):
    """Add nonnegative Java-long counts without overflow.

    Args:
        *values: Integer counts; booleans are refused.
    Returns:
        Exact nonnegative signed-long sum.
    Raises:
        ProofError: Invalid input or signed-long overflow.
    """
    result = 0
    for value in values:
        result += _natural(value)
        if result > LONG_MAX:
            raise ProofError("signed-long addition overflow")
    return result


def checked_mul(*values):
    """Multiply nonnegative Java-long counts with checked intermediates.

    Args:
        *values: Integer factors, including zero if appropriate.
    Returns:
        Exact signed-long product.
    Raises:
        ProofError: Invalid input or signed-long overflow.
    """
    result = 1
    for value in values:
        result *= _natural(value)
        if result > LONG_MAX:
            raise ProofError("signed-long multiplication overflow")
    return result


def array_bytes(length, width):
    """Mirror the existing ledger's aligned primitive/reference array charge.

    Args:
        length: Nonnegative element count.
        width: Nonnegative bytes per element.
    Returns:
        The checked16-byte-header charge rounded up to8 bytes.
    Raises:
        ProofError: Invalid count or overflow.
    """
    return checked_add(checked_add(16, checked_mul(length, width)), 7) // 8 * 8


def _object_bytes(fields):
    return checked_add(checked_add(16, _natural(fields)), 7) // 8 * 8


def _deep_bytes(rows, columns):
    return checked_add(array_bytes(rows, 8), checked_mul(rows, array_bytes(columns, 8)))


def _bounded_read(path, maximum):
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= maximum:
        raise ProofError("bounded regular file required: " + str(path))
    with path.open("rb") as stream:
        data = stream.read(maximum + 1)
    if not 0 < len(data) <= maximum:
        raise ProofError("bounded regular file changed during read")
    return data


def arithmetic_pins():
    """Derive exact rational spec pins, independently of all Java numerics.

    Returns:
        Canonical rational strings for coupled normalization and filter/integral pins.
    """
    def density(width):
        response = (Fraction(0), Fraction(1, 2), Fraction(1))
        area = sum(response) * width
        mass = 4 * area
        uniform = 1 / (1 + mass) / (3 * width)
        return [str(uniform + mass / (1 + mass) * value / area) for value in response]

    background, nuisance, peak = Fraction(1, 50), Fraction(1, 25), Fraction(2, 25)
    excess = max(Fraction(0), peak - background - nuisance)
    wrong = max(Fraction(0), background + (peak - background) / 4 - background - nuisance)
    return {"wide": density(Fraction(1)), "narrow": density(Fraction(1, 10)),
            "excess_then_b3": str(excess / 4), "b3_then_excess": str(wrong),
            "diagonal_sqrt2_coefficient": str((4 * Fraction(3, 2) + 4) / 6)}


def validate_pins(pins):
    """Refuse changed rational pins, including filter-before-excess mutations.

    Args:
        pins: Exact rational pin dictionary to compare.
    Raises:
        ProofError: A pin differs from the declared algebra.
    """
    if pins != arithmetic_pins():
        raise ProofError("arithmetic pin differs")


def bit_bounds(words=512):
    """Derive expression bit ceilings; not a JDK allocation/complexity proof.

    Args:
        words: Declared32-bit magnitude-word ceiling.
    Returns:
        Bounds for the specified affine/event/midpoint operation DAG.
    Raises:
        ProofError: The conversion bound does not fit the word ceiling.
    """
    _natural(words)
    operand = 2098
    difference = operand + 1
    determinant = 2 * operand + 1
    affine = operand + difference + 1
    delta = affine + 1
    face_numerator = max(affine, determinant + 18) + 1
    face_denominator = delta
    midpoint_numerator = face_numerator + face_denominator + 1
    midpoint_denominator = 2 * face_denominator + 1
    source_numerator = max(affine + midpoint_denominator,
                           delta + midpoint_numerator) + 1
    fractional = max(source_numerator, determinant + midpoint_denominator + 18) + 1
    rounded_conversion = fractional + 1074
    result = dict(operand=operand, difference=difference, determinant=determinant,
                  affine=affine, delta=delta, face_numerator=face_numerator,
                  face_denominator=face_denominator, midpoint_numerator=midpoint_numerator,
                  midpoint_denominator=midpoint_denominator, fractional=fractional,
                  rounded_conversion=rounded_conversion, ceiling=checked_mul(words, 32))
    if rounded_conversion >= result["ceiling"]:
        raise ProofError("rational conversion exceeds word ceiling")
    return result


def boundary_owner(coordinate, cells):
    """Apply lower-index interior-face ownership to an exact rational coordinate.

    Args:
        coordinate: Fraction in the closed[0,cells] interval.
        cells: Positive integer cell count.
    Returns:
        The interior cell index, with outer faces owned by their interior cell.
    Raises:
        ProofError: Wrong type or coordinate outside the rectangle.
    """
    if type(coordinate) is not Fraction or type(cells) is not int or cells < 1:
        raise ProofError("exact rational coordinate and positive cells required")
    if coordinate < 0 or coordinate > cells:
        raise ProofError("outside rectangle must be clipped before cell ownership")
    floor = coordinate.numerator // coordinate.denominator
    return max(0, min(cells - 1, floor - (coordinate.denominator == 1)))


def rounded_enclosure(exact):
    """Enclose a tiny exact rational's binary64 rounding for proof controls.

    Args:
        exact: Exact Fraction representing one mathematical operation result.
    Returns:
        Adjacent binary64 endpoints enclosing the exact real value.
    Raises:
        ProofError: Non-Fraction, overflow or positive underflow.
    """
    if type(exact) is not Fraction:
        raise ProofError("exact Fraction required")
    try:
        nominal = float(exact)
    except OverflowError as exc:
        raise ProofError("nonfinite rounding") from exc
    if not math.isfinite(nominal) or exact > 0 and nominal == 0:
        raise ProofError("nonfinite rounding or positive underflow")
    if Fraction(nominal) == exact:
        return nominal, nominal
    return math.nextafter(nominal, -math.inf), math.nextafter(nominal, math.inf)


def one_ulp_enclosure(nominal):
    """Enclose an externally obtained one-ulp-contract result conservatively.

    Args:
        nominal: Finite binary64 result; this function does not evaluate a transcendental.
    Returns:
        Two neighbouring representables on each side, covering binade changes.
    Raises:
        ProofError: Nonfinite input or nonfinite enclosure endpoint.
    """
    if type(nominal) is not float or not math.isfinite(nominal):
        raise ProofError("finite binary64 result required")
    lo, hi = nominal, nominal
    for _ in range(2):
        lo, hi = math.nextafter(lo, -math.inf), math.nextafter(hi, math.inf)
    if not math.isfinite(lo) or not math.isfinite(hi):
        raise ProofError("nonfinite enclosure")
    return lo, hi


def _validate_counts(counts):
    if type(counts) is not dict or set(counts) != COUNT_KEYS:
        raise ProofError("exact closed count inventory required")
    for value in counts.values():
        _natural(value)
    n, modes, states = counts["n"], counts["modes"], counts["states"]
    vertices = checked_mul(counts["width"], counts["height"])
    if (not 1 <= n <= 81 or modes > 8192 or not n <= states <= 96 * n
            or counts["samples"] > 401 * n or min(counts["width"], counts["height"]) < 2
            or vertices > 191401 or not 4 <= counts["source_vertices"] <= 191401
            or not 1 <= counts["source_cells"] <= 190404
            or counts["footprint_vertices"] > checked_mul(modes, vertices)
            or counts["region_edges"] in (1, 2)
            or counts["source_region_polygons"] < 1
            or counts["source_region_edges"] < checked_mul(3, counts["source_region_polygons"])
            or counts["components"] > modes + n
            or counts["local_entries"] > modes * 96
            or counts["mixture_entries"] > counts["components"] * 96
            or counts["pairs"] > max(0, n - 1) * 96 * 96):
        raise ProofError("experiment capability bound exceeded")
    return vertices


def _payload(c, vertices):
    a, o = array_bytes, _object_bytes
    n, m, s, p = c["n"], c["modes"], c["states"], c["pairs"]
    cells = checked_mul(c["width"] - 1, c["height"] - 1)
    events = checked_add(2, checked_mul(2, c["region_edges"]))
    # BoundedEventList: facade/iterator, overlapping backing stores, boxed values,
    # and the separately funded conservative opaque sort inventory coexist.
    region = checked_add(o(56), o(16), o(24), checked_mul(2, o(24)),
                         checked_mul(2, a(events, 8)), checked_mul(events, o(8)),
                         a(events, 16), o(24), checked_mul(events, 2, o(16)),
                         o(16), checked_mul(3, o(24)))
    return {
        "source": checked_add(384, a(c["source_vertices"], 8),
                              a(c["source_vertices"], 1), a(c["source_cells"], 1)),
        "descriptors": checked_add(512, checked_mul(22, a(m, 8)),
                                   checked_mul(12, a(m, 4)), a(n + 1, 4)),
        "planes": checked_add(1024, checked_mul(8, a(vertices, 8)),
                              checked_mul(4, a(vertices, 1)), a(cells, 4)),
        "coordinates": checked_add(128, checked_mul(2, a(s, 8)), a(n + 1, 4)),
        "table": checked_add(768, checked_mul(3, a(p, 8)), a(p, 4),
                             checked_mul(2, a(n, 4)), a(n, 8), checked_mul(3, o(32)), a(4, 8)),
        "exact": checked_add(checked_mul(512, checked_add(o(64), a(1024, 4))),
                             a(128, 8), 4096),
        "row": checked_add(1024, checked_mul(6, _deep_bytes(c["components"], min(s, 96))),
                           checked_mul(12, a(c["components"], 8)),
                           checked_mul(4, a(min(s, 96), 8))),
        "region": region if c["region_edges"] else 0,
        "report": 1024 * 1024,
    }


def derive(counts):
    """Check the proposed input-dependent U/layout; refuse execution eligibility.

    Args:
        counts: Closed flat integer census, independently supplied by a caller.
    Returns:
        Conditional work/payload derivation, source/contract bindings and open proofs.
    Raises:
        ProofError: Malformed counts, overflow, source drift or64MiB refusal.
    """
    vertices = _validate_counts(counts)
    for name, digest in SOURCE_PINS.items():
        data = _bounded_read(ROOT / name, 65536)
        if hashlib.sha256(data).hexdigest() != digest:
            raise ProofError("existing source proof pin differs: " + name)
    c = counts
    r = checked_add(c["footprint_vertices"], checked_mul(4, c["local_entries"]))
    z = checked_mul(c["pairs"], checked_add(c["width"], c["height"], 4))
    e = c["region_edges"]
    events = checked_add(2, checked_mul(2, e))
    region = checked_mul(events, checked_add(checked_mul(128, e + 1),
                                           (events - 1).bit_length(), 64)) if e else 0
    kappa = (2 * 512 + 2) ** 2
    source_queries = checked_add(vertices, c["samples"])
    source_membership = checked_add(32, checked_mul(16, c["source_region_polygons"]),
                                    checked_mul(128, c["source_region_edges"]))
    work = checked_add(
        1024, checked_mul(256, checked_add(c["metadata_slots"], c["source_vertices"],
             c["source_cells"], c["states"], c["n"], c["modes"], vertices,
             c["mixture_entries"])), checked_mul(1024, r), checked_mul(128, 81, vertices),
        checked_mul(96, vertices), checked_mul(512, c["pairs"]), checked_mul(4096, z),
        checked_mul(128, c["pairs"], region), checked_mul(source_queries, source_membership),
        checked_mul(1 + kappa,
             checked_add(checked_mul(256, checked_add(c["modes"], r, c["pairs"])),
                         checked_mul(512, z))))
    payload = _payload(c, vertices)
    # Conservative simultaneous proposed peak; no credit for unproved early release.
    peak = checked_add(c["live_existing_bytes"], *payload.values())
    if peak > ATTEMPT_BYTES:
        raise ProofError("proposed payload plus live existing bytes exceeds64MiB")
    contract_bytes = _bounded_read(CONTRACT_PATH, 65536)
    return dict(status="PROOF_CANDIDATE_REVIEW_REQUIRED", entryEligible=False,
                unproved=list(UNPROVED),
                counts=dict(response_locations=r, stencil_reads=checked_mul(9, r),
                            interval_events_upper=z, region_proxy=region,
                            source_region_queries=source_queries,
                            source_region_query_upper=source_membership,
                            exact_api_upper=checked_add(
                                checked_mul(256, checked_add(c["modes"], r, c["pairs"])),
                                checked_mul(512, z))),
                work_upper=work, work_unit="REGISTERED_WEIGHTED_BUILD_UNITS",
                exact_unit="WEIGHTED_QUADRATIC_LIMB_PAIR_PROXY",
                scalar_block_ceilings={key: checked_add(*parts)
                                       for key, parts in DAG_PARTITIONS.items()},
                future_compliance=["body-DAG", "owned-layouts", "checkpoint-spacing"],
                jdk=dict(version="OpenJDK17.0.12+7", source_pins=dict(JDK_SOURCE_PINS),
                         caller_slots=dict(CALLER_SLOTS), library_slots=376,
                         total_slots=checked_add(376, *CALLER_SLOTS.values()),
                         internal_limbs=1024, public_magnitude_limbs=512),
                payload_bytes=payload, proposed_peak_bytes=peak, bit_bounds=bit_bounds(),
                proofSha256=hashlib.sha256(contract_bytes).hexdigest(),
                source_pins=dict(SOURCE_PINS))


def validate_claim(counts, claim):
    """Reject a changed formula/allocation claim; matching claims remain ineligible.

    Args:
        counts: Original closed input census.
        claim: Complete proposed derivation to authenticate.
    Raises:
        ProofError: Any field differs, including type-sensitive canonical JSON.
    """
    expected = json.dumps(derive(counts), sort_keys=True, separators=(",", ":"), allow_nan=False)
    actual = json.dumps(claim, sort_keys=True, separators=(",", ":"), allow_nan=False)
    if actual != expected:
        raise ProofError("claim differs from independent checked derivation")


def require_entry(report):
    """Refuse numerical entry; this worksheet cannot issue review/execution authority.

    Args:
        report: Proposed derivation; no caller-controlled flag confers authority.
    Raises:
        ProofError: Always: independent contract review/execution authority is external.
    """
    raise ProofError("independent source proof review and execution authority required")


def main(argv=None):
    """Print a bounded conditional derivation without capture, JVM or engine calls.

    Args:
        argv: One counts JSON path, or None for command-line arguments.
    Returns:
        Zero for a valid conditional report; numerical execution is never eligible.
    Raises:
        ProofError: Bad invocation, nonclosed JSON or invalid counts.
        OSError: Input cannot be read.
    """
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 1:
        raise ProofError("usage: verify-b-shared-resource-contract.py counts.json")
    raw = _bounded_read(Path(args[0]), 8192)
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ProofError("duplicate count key")
            result[key] = value
        return result
    report = derive(json.loads(raw.decode("utf-8"), object_pairs_hook=unique))
    print(json.dumps(report, sort_keys=True, separators=(",", ":"), allow_nan=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
