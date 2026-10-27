"""Validate the separately labelled exploratory quality protocol.

No function here grants qualified capture, runtime, publication or model authority.
The baseline and all case quality results must come from real recorded executions.
"""
import json
import math
import re
from pathlib import Path

CASES = (
    "strong-parallel-gap", "connected-straight", "connected-short-bend", "connected-sharp-apex",
    "faint-six-metre-bend", "faint-twelve-metre-bend", "genuine-junction",
    "positive-twenty-five-representation", "negative-twenty-five-representation",
    "flecks-versus-faint-coherent", "valid-sparse-gap", "invalid-unknown-gap", "censored-flank",
    "strong-modulated-versus-quiet-faint", "unknown-detour", "uniform-null", "alternating-null",
    "half-pitch-alias", "quarter-phase-alias", "reverse-original-mesh",
    "thin-three-strands", "thin-three-strands-intermittent", "thin-strands-merge-separate",
    "drifting-strong-with-faint-original", "thin-strong-sharp-bend", "thin-faint-coherent-sharp-bend",
    "thin-local-overlap-noise", "switchback-thin-strong", "switchback-thin-faint",
    "same-trail-three-strong", "same-trail-five-strong", "same-trail-three-separated-strong",
)
SCREENING = (
    ("strong-modulated-versus-quiet-faint", "intense"),
    ("thin-strong-sharp-bend", "intense"), ("switchback-thin-strong", "intense"),
    ("faint-twelve-metre-bend", "faint-intermittent"),
    ("thin-three-strands-intermittent", "faint-intermittent"),
    ("thin-strands-merge-separate", "representative"),
    ("same-trail-three-strong", "representative"),
    ("same-trail-three-separated-strong", "representative"),
)
LIMITS = dict(trackedAllocationBytes=67108864, states=96, pairs=8000000,
              solverScores=128000000, cooperativeSeconds=300, externalSeconds=360,
              searchHalfWidthMeters=25, jvmMaxHeapMiB=512, receiptBytes=1048576,
              caseArtifactBytes=67108864, uploadBytes=536870912)
QUALITY = dict(sampleSpacingNativePixels=.5, meanToleranceNativePixels=.25,
               p95ToleranceNativePixels=.5,
               sparseNormalizationMeters={"faint-twelve-metre-bend":1,"same-trail-three-strong":1,"same-trail-three-separated-strong":1},
               distance="UNSIGNED_TRUTH_CHAINAGE_TO_NEAREST_RETURNED_CLOSED_SEGMENT",
               endpoints="INCLUDE_BOTH_FINAL_ENDPOINT_ONCE",
               aggregation="EQUAL_SAMPLE_WEIGHTS_P95_SORTED_CEIL_0.95_N_MINUS_1",
               truthPolicy="POST_RETURN_ONLY_NOT_ORIGINAL_OSM_GUIDE",
               safetyScope="EXPLORATORY_ORACLES_NOT_PRODUCTION_PREVIEW_OR_APPLY")


def validate_protocol(p):
    """Check fixed prospective metadata; acceptance is not launch permission."""
    try:
        valid = (p["schema"] == 1 and p["evidenceClass"] == "EXPLORATORY_QUALITY_ONLY_V1"
                 and p["totalPluginMemoryQualified"] is False
                 and p["caseRolePolicy"] == "PRESERVE_ORIGINAL_REQUIRED_AND_STRESS_DISPOSITIONS"
                 and tuple(p["cases"]) == CASES
                 and tuple((x["id"], x["category"]) for x in p["screening"]) == SCREENING
                 and p["gains"] == [1, 4, 16, 64] and p["etas"] == [0, .25, 1, 4]
                 and p["limits"] == LIMITS and p["quality"] == QUALITY
                 and p["memoryCoverage"]["tracked"]
                 and len(p["memoryCoverage"]["excluded"]) >= 5
                 and p["memoryCoverage"]["claim"] == "TRACKED_PEAK_ONLY_NOT_TOTAL_PLUGIN_OR_PROCESS_BOUND"
                 and p["schedule"] == dict(baselineSuitabilityAttempts=8,
                     candidateScreeningAttempts=128, frozenConfirmationAttempts=32,
                     remainingBaselineAttempts=24, maximumAttempts=192,
                     fitAuthority="EXPLORATORY_ONLY_NOT_ORIGINAL_TRAIN_SELECT",
                     retries=0, solverCallsPerCandidate=1))
    except (KeyError, TypeError, ValueError) as exc:
        raise ValueError("Incomplete exploratory protocol") from exc
    if not valid:
        raise ValueError("Exploratory protocol differs from approved quality experiment")
    return p


def settings(p):
    """Return the complete gain-major fixed family; never choose from outcomes."""
    validate_protocol(p)
    return [(g, e) for g in p["gains"] for e in p["etas"]]


def require_baseline_launch(p):
    """Validate the user-designated source/defaults recipe for preparation.

    Actual class origin, compiled bytes and input equivalence remain mandatory
    checks in the isolated runner before engine entry; metadata is not evidence
    that these runtime checks have already passed.
    """
    validate_protocol(p)
    b = p.get("baseline", {})
    if (b.get("status") != "SOURCE_PINNED_DEFAULTS_APPROVED"
            or b.get("sourceCommit") != "117baaf09f4b81a38ad4679eedc8fd1b12ed3d2a"
            or b.get("sourceTree") != "a701c0bb3fe6e213f4aa44d12763863f209d6a32"
            or b.get("defaultsToStringSha256") != "4a6c463c009d9dc454741a8146de9f69c1e065b98f5026514a921c9b377a9d77"
            or b.get("exactHistoricalTestedBinaryClaim") is not False
            or b.get("inputEquivalenceRequired") is not True
            or b.get("primarySelectionPolicy") != "FIRST_NATIVE_RETURNED_ALTERNATIVE"
            or b.get("nativePolicy") != [96, 8000000, 128000000, 32, 8]
            or b.get("nativeLedgerBytes") != 268435456 or b.get("stepMeters") != 1.5):
        raise ValueError("Unresolved or changed source-pinned baseline recipe")
    return b


def suitability(p, rows):
    """Assess a complete baseline screening set, not candidate model results.

    This pure assessor expects authenticated execution receipts from its caller.
    It does not authenticate arbitrary dictionaries as numerical evidence.
    """
    validate_protocol(p)
    expected = [x["id"] for x in p["screening"]]
    if len(rows) != len(expected) or [r.get("caseId") for r in rows] != expected:
        raise ValueError("Missing, duplicate, unknown or reordered baseline receipts")
    if any(type(r.get("completed")) is not bool or type(r.get("hardPassed")) is not bool
           for r in rows):
        raise ValueError("Baseline completed/quality fields must be measured booleans")
    failures = [r["caseId"] for x, r in zip(p["screening"], rows)
                if x["category"] != "intense" and r["completed"] and not r["hardPassed"]]
    ok = all(r["completed"] for r in rows) and bool(failures)
    return dict(status="SUITABLE" if ok else "INCONCLUSIVE",
                demonstratedSparseFailures=failures,
                reason="BASELINE_FAILURE_REPRODUCED" if ok else "MISSING_EXECUTION_OR_NO_REPRODUCED_SPARSE_FAILURE")


def centering_metrics(truth, returned, native_pitch):
    """Sample truth chainage at half a native pixel; compare actual polylines.

    Output node density never sets the sampling weights. These centering metrics
    do not replace independent no-hop, coverage or bend-preservation oracles.
    """
    if not math.isfinite(native_pitch) or native_pitch <= 0:
        raise ValueError("Factual positive native pitch required")
    if (len(truth) < 2 or len(returned) < 2
            or any(len(p) != 2 or not all(math.isfinite(x) for x in p)
                   for p in [*truth, *returned])):
        raise ValueError("Finite truth and returned polylines required")
    lengths = [math.dist(a, b) for a, b in zip(truth, truth[1:])]
    total = math.fsum(lengths)
    spacing = .5 * native_pitch
    if not math.isfinite(total) or total <= 0 or total / spacing > 1000000:
        raise ValueError("Degenerate or oversized truth sampling")
    chainages = [i * spacing for i in range(math.ceil(total / spacing))] + [total]
    errors = []
    segment = 0
    start = 0.
    for s in chainages:
        while segment < len(lengths) - 1 and (lengths[segment] == 0 or s > start + lengths[segment]):
            start += lengths[segment]
            segment += 1
        a, b = truth[segment:segment + 2]
        fraction = (s - start) / lengths[segment] if lengths[segment] else 0.
        p = (a[0] + fraction * (b[0] - a[0]), a[1] + fraction * (b[1] - a[1]))
        nearest = math.inf
        for c, d in zip(returned, returned[1:]):
            dx, dy = d[0] - c[0], d[1] - c[1]
            length2 = dx * dx + dy * dy
            t = max(0., min(1., ((p[0] - c[0]) * dx + (p[1] - c[1]) * dy) / length2)) if length2 else 0.
            nearest = min(nearest, math.hypot(p[0] - (c[0] + t * dx), p[1] - (c[1] + t * dy)))
        if not math.isfinite(nearest):
            raise ValueError("Nonfinite geometric error")
        errors.append(nearest)
    return dict(sampleCount=len(errors), spacingMeters=spacing,
                meanMeters=math.fsum(errors) / len(errors),
                p95Meters=sorted(errors)[math.ceil(.95 * len(errors)) - 1])


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("protocol", type=Path)
    parser.add_argument("--require-baseline-launch", action="store_true")
    args = parser.parse_args()
    protocol = json.loads(args.protocol.read_text())
    validate_protocol(protocol)
    if args.require_baseline_launch:
        require_baseline_launch(protocol)
    print(json.dumps(dict(evidenceClass=protocol["evidenceClass"],
                          baselineStatus=protocol["baseline"]["status"],
                          cases=len(protocol["cases"]), candidateSettings=len(settings(protocol)))))
