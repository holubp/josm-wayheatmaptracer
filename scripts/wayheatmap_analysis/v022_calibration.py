"""Leakage-resistant confidence calibration policy for v0.22 trace results.

The module deliberately separates deterministic safety disposition from empirical
correctness confidence. It never learns from Apply/Cancel behavior, engine objective,
OSM identity, or several crops/detectors belonging to the same geographic location.
"""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import math
from typing import Iterable, Mapping, Sequence


ALLOWED_FEATURES = (
    "direct_coverage",
    "worst_gap_m",
    "branch_ambiguity",
    "local_defect_count",
    "solver_complete",
    "constraint_class",
)


class CalibrationError(ValueError):
    """Raised when labels, groups, features, or held-out policy are unsafe."""


@dataclass(frozen=True)
class CalibrationExample:
    """One independently human-labeled route outcome grouped by physical location."""

    location_group: str
    label_correct: bool
    label_source: str
    features: Mapping[str, float | str]
    parameter_hash: str
    domain_id: str

    def validate(self) -> None:
        """Reject surrogate labels and non-versioned or non-finite examples."""

        if not self.location_group or self.label_source != "independent-human-reference":
            raise CalibrationError("confidence labels require an independent human reference")
        if not _hash(self.parameter_hash) or not self.domain_id:
            raise CalibrationError("calibration example lacks a versioned domain identity")
        if set(self.features) != set(ALLOWED_FEATURES):
            raise CalibrationError("calibration feature schema does not match v0.22")
        for name, value in self.features.items():
            if name == "constraint_class":
                if not isinstance(value, str) or not value:
                    raise CalibrationError("constraint class must be categorical")
            elif not isinstance(value, (int, float)) or not math.isfinite(float(value)):
                raise CalibrationError("calibration features must be finite")


@dataclass(frozen=True)
class CalibrationPolicy:
    """Versioned calibrated or explicitly unavailable confidence policy."""

    schema: str
    domain_id: str
    parameter_hash: str
    feature_schema: tuple[str, ...]
    training_groups: tuple[str, ...]
    heldout_groups: tuple[str, ...]
    calibrated: bool
    reason: str

    def confidence_available(self, domain_id: str, parameter_hash: str,
                             solver_complete: bool) -> bool:
        """Return whether empirical confidence is valid for this exact result domain."""

        return (self.calibrated and solver_complete and domain_id == self.domain_id
                and parameter_hash == self.parameter_hash)

    def as_json(self) -> str:
        """Serialize a stable public status without examples or geographic identifiers."""

        payload = {
            "schema": self.schema,
            "domainId": self.domain_id,
            "parameterHash": self.parameter_hash,
            "featureSchema": list(self.feature_schema),
            "trainingGroupCount": len(self.training_groups),
            "heldoutGroupCount": len(self.heldout_groups),
            "calibrated": self.calibrated,
            "reason": self.reason,
        }
        return json.dumps(payload, sort_keys=True, indent=2) + "\n"


def grouped_split(examples: Sequence[CalibrationExample], heldout_fraction: float = 0.25,
                  salt: str = "v022-heldout-1") -> tuple[list[CalibrationExample], list[CalibrationExample]]:
    """Split whole geographic groups deterministically, never individual rows."""

    if not 0.0 < heldout_fraction < 1.0 or not salt:
        raise CalibrationError("held-out split parameters are invalid")
    for example in examples:
        example.validate()
    groups = sorted({example.location_group for example in examples})
    if len(groups) < 2:
        raise CalibrationError("at least two independent location groups are required")
    ranked = sorted(groups, key=lambda group: hashlib.sha256(f"{salt}\0{group}".encode()).digest())
    heldout_count = max(1, min(len(groups) - 1, round(len(groups) * heldout_fraction)))
    heldout = set(ranked[:heldout_count])
    return ([example for example in examples if example.location_group not in heldout],
            [example for example in examples if example.location_group in heldout])


def build_policy(examples: Sequence[CalibrationExample], domain_id: str,
                 parameter_hash: str, minimum_training_groups: int = 50) -> CalibrationPolicy:
    """Build an honest status policy; insufficient labels remain explicitly uncalibrated."""

    if not domain_id or not _hash(parameter_hash) or minimum_training_groups < 2:
        raise CalibrationError("calibration policy identity is invalid")
    matching = [example for example in examples
                if example.domain_id == domain_id and example.parameter_hash == parameter_hash]
    training, heldout = grouped_split(matching)
    training_groups = tuple(sorted({example.location_group for example in training}))
    heldout_groups = tuple(sorted({example.location_group for example in heldout}))
    calibrated = len(training_groups) >= minimum_training_groups and bool(heldout_groups)
    reason = "validated-independent-domain" if calibrated else "insufficient-independent-labeled-groups"
    return CalibrationPolicy("wayheatmaptracer-confidence-v1", domain_id, parameter_hash,
                             ALLOWED_FEATURES, training_groups, heldout_groups, calibrated, reason)


def validate_result_label(row: Mapping[str, object]) -> None:
    """Reject result rows that try to derive truth from UI or optimizer behavior."""

    forbidden = ("wasApplied", "applyDecision", "selectedByUser", "optimizerAsTruth")
    if any(key in row for key in forbidden):
        raise CalibrationError("application behavior cannot be used as a correctness label")
    if row.get("labelSource") != "independent-human-reference":
        raise CalibrationError("result lacks an independent correctness label")


def verify_locked_evaluation(policy: CalibrationPolicy, evaluated_parameter_hash: str,
                             evaluated_groups: Iterable[str]) -> None:
    """Ensure held-out evaluation uses the frozen parameters and only reserved groups."""

    groups = set(evaluated_groups)
    if evaluated_parameter_hash != policy.parameter_hash:
        raise CalibrationError("held-out evaluation changed the frozen parameters")
    if not groups or not groups.issubset(set(policy.heldout_groups)):
        raise CalibrationError("evaluation contains training or unknown location groups")


def _hash(value: str) -> bool:
    return isinstance(value, str) and len(value) == 64 and all(character in "0123456789abcdef"
                                                               for character in value)
