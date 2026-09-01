"""Offline multiscale local-shape evidence for WayHeatmapTracer.

This module is deliberately independent of JOSM and of geographic geometry.
It consumes a one-dimensional sequence of cumulative chainage and lateral
heatmap-center observations.  Lateral values are expressed in rendered image
pixels and normalized by the supplied native source-pixel pitch before any
trend fitting.  The implementation mirrors the deterministic common
6/10/20 metre classifier used by the Java cleanup path:

* weighted affine and quadratic least-squares fits;
* exactly three Huber IRLS reweighting passes;
* physical two-sided windows and direct-evidence gaps;
* residual amplitude and reversal spacing;
* separate wrinkle, bend, ambiguity, and intervention scores.

No coordinates are accepted, produced, persisted, or inferred here.  This
makes the module suitable for bounded analysis of exported numeric diagnostics
without exposing OSM geometry.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
import math
from typing import Iterable, Sequence


ANALYSIS_RADII_METERS: tuple[float, ...] = (6.0, 10.0, 20.0)
"""The fixed physical half-window radii used by the common classifier."""

ROBUST_ITERATIONS = 3
"""The fixed number of Huber IRLS refinement passes."""

MAX_OBSERVATIONS = 100_000
"""Maximum sequence length accepted by the bounded offline analyzer."""

_EPSILON = 1.0e-12


@dataclass(frozen=True, slots=True)
class ShapeObservation:
    """One profile's coordinate-free lateral evidence.

    Parameters
    ----------
    chainage_m:
        Monotonic cumulative ground distance in metres.
    center_px:
        Local lateral center in rendered-raster pixels.  This is the value
        fitted by the classifier, not a geographic coordinate.
    raw_center_px, light_center_px, standard_center_px, core_center_px:
        Optional raw, light-filtered, standard-filtered, and robust-core
        centers.  Missing channels fall back to ``center_px`` so old exports
        can still be analyzed deterministically.
    confidence:
        Bounded localization confidence.  Values outside ``[0, 1]`` are
        rejected rather than silently changing the evidence.
    uncertainty_px:
        Non-negative lateral uncertainty in rendered-raster pixels.
    direct:
        Whether this profile has directly observed heatmap support.  False
        values form hard boundaries and cannot authorize cleanup.
    motion_support:
        Existing longitudinal motion support in ``[0, 1]``.  It can protect a
        genuine bend but cannot create direct evidence.
    scale_conflict:
        Whether fine and coarse observations disagree at this profile.
    parent_merge:
        Whether this profile is a merged parent rather than direct child
        evidence.  It is treated as a scale conflict for classification.
    selected_offset_px:
        Optional optimizer offset retained only as scalar diagnostic data.
    """

    chainage_m: float
    center_px: float
    raw_center_px: float | None = None
    light_center_px: float | None = None
    standard_center_px: float | None = None
    core_center_px: float | None = None
    confidence: float = 1.0
    uncertainty_px: float = 0.25
    direct: bool = True
    motion_support: float = 0.0
    scale_conflict: bool = False
    parent_merge: bool = False
    selected_offset_px: float | None = None

    def __post_init__(self) -> None:
        """Validate scalar units and bounded inputs."""
        finite = (
            self.chainage_m,
            self.center_px,
            self.confidence,
            self.uncertainty_px,
            self.motion_support,
        )
        if not all(math.isfinite(value) for value in finite):
            raise ValueError("shape observation values must be finite")
        if self.chainage_m < 0.0:
            raise ValueError("chainage_m must be non-negative")
        if not 0.0 <= self.confidence <= 1.0:
            raise ValueError("confidence must be in [0, 1]")
        if self.uncertainty_px < 0.0:
            raise ValueError("uncertainty_px must be non-negative")
        if not 0.0 <= self.motion_support <= 1.0:
            raise ValueError("motion_support must be in [0, 1]")
        for value in self.channels:
            if not math.isfinite(value):
                raise ValueError("center channels must be finite when supplied")
        if self.selected_offset_px is not None and not math.isfinite(self.selected_offset_px):
            raise ValueError("selected_offset_px must be finite when supplied")

    @property
    def channels(self) -> tuple[float, float, float, float]:
        """Return the four center channels with deterministic fallbacks."""
        return tuple(
            self.center_px if value is None else value
            for value in (
                self.raw_center_px,
                self.light_center_px,
                self.standard_center_px,
                self.core_center_px,
            )
        )


@dataclass(frozen=True, slots=True)
class ScaleEvidence:
    """Evidence calculated for one physical half-window around a profile."""

    radius_m: float
    valid: bool
    direct_coverage: float
    trend_kind: str
    trend_center_source_px: float
    trend_slope_source_px_per_m: float
    trend_curvature_source_px_per_m2: float
    uncertainty_source_px: float
    residual_source_px: float
    residual_amplitude_source_px: float
    reversal_count: int
    reversal_spacing_m: float | None
    channel_agreement: float
    reliability: float
    wrinkle_score: float
    bend_score: float
    reason: str

    def as_dict(self) -> dict[str, object]:
        """Return JSON-friendly scalar diagnostics for offline reports."""
        return asdict(self)


@dataclass(frozen=True, slots=True)
class ShapeEvidence:
    """Final per-profile common-classifier result and all scale diagnostics."""

    profile_index: int
    chainage_m: float
    source_pixel_pitch_px: float
    direct_coverage: float
    raw_center_source_px: float
    light_center_source_px: float
    standard_center_source_px: float
    core_center_source_px: float
    trend_center_source_px: float
    trend_slope_source_px_per_m: float
    trend_curvature_source_px_per_m2: float
    trend_uncertainty_source_px: float
    residual_source_px: float
    residual_amplitude_source_px: float
    reversal_count: int
    reversal_spacing_m: float | None
    scale_agreement: float
    trend_reliability: float
    wrinkle_score: float
    bend_score: float
    ambiguity_score: float
    cleanup_intervention: float
    bend_protection: float
    selected_offset_source_px: float | None
    decision: str
    reason: str
    scales: tuple[ScaleEvidence, ...]

    def as_dict(self) -> dict[str, object]:
        """Return nested JSON-friendly diagnostics without any coordinates."""
        value = asdict(self)
        value["scales"] = [scale.as_dict() for scale in self.scales]
        return value


@dataclass(frozen=True, slots=True)
class _NormalizedObservation:
    """Internal observation with all lateral values in source-pixel units."""

    chainage_m: float
    local_center_source_px: float
    consensus_source_px: float
    weight: float
    channel_agreement: float
    local_channel_support: float


@dataclass(frozen=True, slots=True)
class _Polynomial:
    """Internal affine or quadratic polynomial in metres."""

    intercept: float
    linear: float
    quadratic: float
    degree: int

    def value(self, x_m: float) -> float:
        """Evaluate this polynomial at relative chainage ``x_m``."""
        return self.intercept + self.linear * x_m + self.quadratic * x_m * x_m


class ShapeEvidenceAnalyzer:
    """Compute deterministic common 6/10/20 m local-shape evidence.

    The analyzer has no mutable state.  ``max_observations`` is an explicit
    resource bound for offline callers and does not alter mathematical output
    for accepted inputs.
    """

    def __init__(self, max_observations: int = MAX_OBSERVATIONS) -> None:
        """Create an analyzer with a finite input-size limit."""
        if max_observations < 5:
            raise ValueError("max_observations must allow a five-profile window")
        self.max_observations = max_observations

    def analyze(
        self,
        observations: Sequence[ShapeObservation] | Iterable[ShapeObservation],
        source_pixel_pitch_px: float,
    ) -> tuple[ShapeEvidence, ...]:
        """Classify every profile in a directly supplied scalar sequence.

        Parameters
        ----------
        observations:
            Profiles in increasing cumulative chainage.  A non-direct profile
            is a hard gap; windows never bridge it.
        source_pixel_pitch_px:
            Native source-pixel pitch in rendered-raster pixels.  All lateral
            metrics are divided by this value before fitting.

        Returns
        -------
        tuple[ShapeEvidence, ...]
            One deterministic result per input profile.  Boundary, gap, and
            insufficient-window results are ``UNAVAILABLE`` and have zero
            cleanup intervention.
        """
        profiles = tuple(observations)
        self._validate_profiles(profiles)
        pitch = _positive(source_pixel_pitch_px, "source_pixel_pitch_px")
        return tuple(
            self._profile_evidence(profiles, index, pitch)
            for index in range(len(profiles))
        )

    def _validate_profiles(self, profiles: tuple[ShapeObservation, ...]) -> None:
        if len(profiles) > self.max_observations:
            raise ValueError("observation sequence exceeds configured bound")
        previous = -math.inf
        for profile in profiles:
            if profile.chainage_m <= previous:
                raise ValueError("chainage must be strictly increasing")
            previous = profile.chainage_m

    def _profile_evidence(
        self,
        profiles: tuple[ShapeObservation, ...],
        index: int,
        pitch: float,
    ) -> ShapeEvidence:
        target = profiles[index]
        centers = tuple(value / pitch for value in target.channels)
        if not target.direct:
            return self._unavailable(index, target, pitch, centers, "non-direct")
        scales = tuple(
            self._scale_evidence(profiles, index, pitch, radius)
            for radius in ANALYSIS_RADII_METERS
        )
        valid_scales = tuple(scale for scale in scales if scale.valid)
        if not valid_scales:
            reason = "boundary-censored" if index == 0 or index == len(profiles) - 1 else "insufficient-window"
            return self._unavailable(index, target, pitch, centers, reason, scales)

        selected = max(
            valid_scales,
            key=lambda scale: (
                scale.reliability * max(scale.wrinkle_score, scale.bend_score),
                -scale.radius_m,
            ),
        )
        wrinkle = max(scale.wrinkle_score for scale in valid_scales)
        bend = max(target.motion_support, *(scale.bend_score for scale in valid_scales))
        reliability = max(scale.reliability for scale in valid_scales)
        ambiguity = _clamp(
            2.0 * min(wrinkle, bend)
            + 0.35 * (1.0 - reliability)
            + (1.0 if target.scale_conflict or target.parent_merge else 0.0)
        )
        bend_protection = _clamp(max(bend, target.motion_support))
        intervention = _clamp(
            wrinkle
            * (1.0 - bend_protection) ** 2
            * (1.0 - ambiguity)
            * reliability
        )
        if target.scale_conflict or target.parent_merge:
            decision, reason = "AMBIGUOUS", "scale-conflict"
        elif ambiguity >= 0.65 and intervention < 0.20:
            decision, reason = "AMBIGUOUS", "ambiguous"
        elif bend_protection >= 0.45 and bend_protection > wrinkle:
            decision, reason = "SUPPORTED_BEND", "supported-bend"
            intervention = 0.0
        elif intervention >= 0.15:
            decision, reason = "WRINKLE", "direct-wrinkle"
        else:
            decision, reason = "STABLE", "stable"

        selected_offset = (
            None
            if target.selected_offset_px is None
            else target.selected_offset_px / pitch
        )
        return ShapeEvidence(
            profile_index=index,
            chainage_m=target.chainage_m,
            source_pixel_pitch_px=pitch,
            direct_coverage=selected.direct_coverage,
            raw_center_source_px=centers[0],
            light_center_source_px=centers[1],
            standard_center_source_px=centers[2],
            core_center_source_px=centers[3],
            trend_center_source_px=selected.trend_center_source_px,
            trend_slope_source_px_per_m=selected.trend_slope_source_px_per_m,
            trend_curvature_source_px_per_m2=selected.trend_curvature_source_px_per_m2,
            trend_uncertainty_source_px=selected.uncertainty_source_px,
            residual_source_px=selected.residual_source_px,
            residual_amplitude_source_px=selected.residual_amplitude_source_px,
            reversal_count=selected.reversal_count,
            reversal_spacing_m=selected.reversal_spacing_m,
            scale_agreement=selected.channel_agreement,
            trend_reliability=reliability,
            wrinkle_score=wrinkle,
            bend_score=bend,
            ambiguity_score=ambiguity,
            cleanup_intervention=intervention,
            bend_protection=bend_protection,
            selected_offset_source_px=selected_offset,
            decision=decision,
            reason=reason,
            scales=scales,
        )

    def _unavailable(
        self,
        index: int,
        target: ShapeObservation,
        pitch: float,
        centers: tuple[float, float, float, float],
        reason: str,
        scales: tuple[ScaleEvidence, ...] = (),
    ) -> ShapeEvidence:
        """Build a typed zero-intervention result for missing local evidence."""
        return ShapeEvidence(
            profile_index=index,
            chainage_m=target.chainage_m,
            source_pixel_pitch_px=pitch,
            direct_coverage=0.0,
            raw_center_source_px=centers[0],
            light_center_source_px=centers[1],
            standard_center_source_px=centers[2],
            core_center_source_px=centers[3],
            trend_center_source_px=centers[3],
            trend_slope_source_px_per_m=0.0,
            trend_curvature_source_px_per_m2=0.0,
            trend_uncertainty_source_px=0.0,
            residual_source_px=0.0,
            residual_amplitude_source_px=0.0,
            reversal_count=0,
            reversal_spacing_m=None,
            scale_agreement=0.0,
            trend_reliability=0.0,
            wrinkle_score=0.0,
            bend_score=0.0,
            ambiguity_score=1.0 if reason == "scale-conflict" else 0.0,
            cleanup_intervention=0.0,
            bend_protection=0.0,
            selected_offset_source_px=(
                None if target.selected_offset_px is None else target.selected_offset_px / pitch
            ),
            decision="UNAVAILABLE",
            reason=reason,
            scales=scales,
        )

    def _scale_evidence(
        self,
        profiles: tuple[ShapeObservation, ...],
        target_index: int,
        pitch: float,
        radius: float,
    ) -> ScaleEvidence:
        window = self._direct_window(profiles, target_index, radius)
        target = profiles[target_index]
        left_span = target.chainage_m - window[0].chainage_m if window else 0.0
        right_span = window[-1].chainage_m - target.chainage_m if window else 0.0
        span = left_span + right_span
        if len(window) < 5 or span < 0.65 * radius or left_span < 0.25 * radius or right_span < 0.25 * radius:
            return _invalid_scale(radius, "insufficient-two-sided-window")

        normalized = tuple(self._normalize(profile, pitch) for profile in window)
        affine = _robust_fit(normalized, 1, target.chainage_m)
        if affine is None:
            return _invalid_scale(radius, "ill-conditioned-affine")
        quadratic = _robust_fit(normalized, 2, target.chainage_m)
        affine_error = _weighted_error(normalized, affine, target.chainage_m)
        quadratic_error = affine_error if quadratic is None else _weighted_error(
            normalized, quadratic, target.chainage_m
        )
        improvement = 0.0 if affine_error <= _EPSILON else _clamp(
            (affine_error - quadratic_error) / affine_error
        )
        curvature_amplitude = (
            0.0
            if quadratic is None
            else abs(quadratic.quadratic) * radius * radius
        )
        use_quadratic = quadratic is not None and improvement >= 0.15 and curvature_amplitude >= 0.08
        trend = quadratic if use_quadratic else affine
        residuals = tuple(
            observation.local_center_source_px - trend.value(
                observation.chainage_m - target.chainage_m
            )
            for observation in normalized
        )
        reversal_count, reversal_spacing = _reversals(
            window, residuals, deadband=0.10
        )
        exposure = 0.0 if reversal_count < 2 else _clamp(
            (radius - (radius if reversal_spacing is None else reversal_spacing)) / radius
        )
        amplitude = _percentile_abs(residuals, 0.80)
        uncertainty = max(0.05, 1.4826 * _mad(residuals))
        confidence = sum(profile.confidence for profile in window) / len(window)
        reliability = (
            span / (2.0 * radius)
            * _clamp(confidence)
            * (1.0 - _smooth_step(0.50, 1.50, uncertainty))
        )
        channel_agreement = sum(item.channel_agreement for item in normalized) / len(normalized)
        local_channel_support = sum(item.local_channel_support for item in normalized) / len(normalized)
        bend = _clamp(
            max(
                _smooth_step(0.08, 0.45, curvature_amplitude) * improvement,
                target.motion_support,
            )
            * channel_agreement
            * reliability
        )
        wrinkle = _clamp(
            exposure
            * _smooth_step(0.08, 0.35, amplitude)
            * reliability
            * (0.55 + 0.45 * (1.0 - local_channel_support))
            * (1.0 - bend)
        )
        return ScaleEvidence(
            radius_m=radius,
            valid=True,
            direct_coverage=_clamp(span / (2.0 * radius)),
            trend_kind="quadratic" if use_quadratic else "affine",
            trend_center_source_px=trend.intercept,
            trend_slope_source_px_per_m=trend.linear,
            trend_curvature_source_px_per_m2=2.0 * trend.quadratic,
            uncertainty_source_px=uncertainty,
            residual_source_px=residuals[window.index(target)] if target in window else 0.0,
            residual_amplitude_source_px=amplitude,
            reversal_count=reversal_count,
            reversal_spacing_m=reversal_spacing,
            channel_agreement=channel_agreement,
            reliability=_clamp(reliability),
            wrinkle_score=wrinkle,
            bend_score=bend,
            reason="valid",
        )

    def _direct_window(
        self,
        profiles: tuple[ShapeObservation, ...],
        target_index: int,
        radius: float,
    ) -> tuple[ShapeObservation, ...]:
        """Collect a bounded two-sided window without crossing a gap."""
        target = profiles[target_index]
        if not target.direct:
            return ()
        left = target_index
        right = target_index
        while (
            left > 0
            and profiles[left - 1].direct
            and target.chainage_m - profiles[left - 1].chainage_m <= radius + _EPSILON
        ):
            left -= 1
        while (
            right + 1 < len(profiles)
            and profiles[right + 1].direct
            and profiles[right + 1].chainage_m - target.chainage_m <= radius + _EPSILON
        ):
            right += 1
        return profiles[left : right + 1]

    def _normalize(self, profile: ShapeObservation, pitch: float) -> _NormalizedObservation:
        channels = tuple(value / pitch for value in profile.channels)
        ordered = sorted(channels)
        consensus = (ordered[1] + ordered[2]) / 2.0
        spread = ordered[3] - ordered[0]
        agreement = 1.0 - _smooth_step(0.20, 1.00, spread)
        local_support = 1.0 - _smooth_step(
            0.15, 0.75, abs(profile.center_px / pitch - consensus)
        )
        uncertainty = max(0.25, profile.uncertainty_px / pitch)
        weight = max(0.05, profile.confidence) / uncertainty**2
        return _NormalizedObservation(
            chainage_m=profile.chainage_m,
            local_center_source_px=profile.center_px / pitch,
            consensus_source_px=consensus,
            weight=weight,
            channel_agreement=_clamp(agreement),
            local_channel_support=_clamp(local_support),
        )


def analyze_shape_evidence(
    observations: Sequence[ShapeObservation] | Iterable[ShapeObservation],
    source_pixel_pitch_px: float,
    *,
    max_observations: int = MAX_OBSERVATIONS,
) -> tuple[ShapeEvidence, ...]:
    """Convenience wrapper for :class:`ShapeEvidenceAnalyzer.analyze`."""
    return ShapeEvidenceAnalyzer(max_observations).analyze(observations, source_pixel_pitch_px)


def _invalid_scale(radius: float, reason: str) -> ScaleEvidence:
    return ScaleEvidence(
        radius_m=radius,
        valid=False,
        direct_coverage=0.0,
        trend_kind="unavailable",
        trend_center_source_px=0.0,
        trend_slope_source_px_per_m=0.0,
        trend_curvature_source_px_per_m2=0.0,
        uncertainty_source_px=0.0,
        residual_source_px=0.0,
        residual_amplitude_source_px=0.0,
        reversal_count=0,
        reversal_spacing_m=None,
        channel_agreement=0.0,
        reliability=0.0,
        wrinkle_score=0.0,
        bend_score=0.0,
        reason=reason,
    )


def _robust_fit(
    observations: tuple[_NormalizedObservation, ...], degree: int, origin_m: float
) -> _Polynomial | None:
    if len(observations) < degree + 1:
        return None
    base_weights = [observation.weight for observation in observations]
    weights = list(base_weights)
    fit = _weighted_fit(observations, weights, degree, origin_m)
    if fit is None:
        return None
    for _ in range(ROBUST_ITERATIONS):
        residuals = [
            observation.local_center_source_px - fit.value(observation.chainage_m - origin_m)
            for observation in observations
        ]
        scale = max(0.05, 1.4826 * _mad(residuals))
        for index, residual in enumerate(residuals):
            normalized = abs(residual) / max(_EPSILON, 1.5 * scale)
            weights[index] = base_weights[index] * (1.0 if normalized <= 1.0 else 1.0 / normalized)
        fit = _weighted_fit(observations, weights, degree, origin_m)
        if fit is None:
            return None
    return fit


def _weighted_fit(
    observations: tuple[_NormalizedObservation, ...],
    weights: Sequence[float],
    degree: int,
    origin_m: float,
) -> _Polynomial | None:
    size = degree + 1
    matrix = [[0.0 for _ in range(size)] for _ in range(size)]
    vector = [0.0 for _ in range(size)]
    for observation, weight in zip(observations, weights):
        x = observation.chainage_m - origin_m
        powers = [1.0, x, x * x, x * x * x, x**4]
        for row in range(size):
            vector[row] += weight * powers[row] * observation.local_center_source_px
            for column in range(size):
                matrix[row][column] += weight * powers[row + column]
    solution = _solve(matrix, vector)
    if solution is None:
        return None
    return _Polynomial(
        intercept=solution[0],
        linear=solution[1] if degree >= 1 else 0.0,
        quadratic=solution[2] if degree >= 2 else 0.0,
        degree=degree,
    )


def _solve(matrix: list[list[float]], vector: list[float]) -> list[float] | None:
    """Solve a tiny system with deterministic partial-pivot elimination."""
    size = len(vector)
    work = [row[:] + [value] for row, value in zip(matrix, vector)]
    for column in range(size):
        pivot = max(range(column, size), key=lambda row: abs(work[row][column]))
        if abs(work[pivot][column]) <= 1.0e-10:
            return None
        work[column], work[pivot] = work[pivot], work[column]
        divisor = work[column][column]
        for index in range(column, size + 1):
            work[column][index] /= divisor
        for row in range(size):
            if row == column:
                continue
            factor = work[row][column]
            for index in range(column, size + 1):
                work[row][index] -= factor * work[column][index]
    result = [work[index][size] for index in range(size)]
    return result if all(math.isfinite(value) for value in result) else None


def _weighted_error(
    observations: tuple[_NormalizedObservation, ...], fit: _Polynomial, origin_m: float
) -> float:
    total = 0.0
    weights = 0.0
    for observation in observations:
        residual = observation.local_center_source_px - fit.value(observation.chainage_m - origin_m)
        total += observation.weight * residual * residual
        weights += observation.weight
    return math.inf if weights <= _EPSILON else total / weights


def _reversals(
    profiles: Sequence[ShapeObservation],
    residuals: Sequence[float],
    deadband: float,
) -> tuple[int, float | None]:
    previous_sign = 0
    distances: list[float] = []
    count = 0
    for index in range(1, len(residuals)):
        change = residuals[index] - residuals[index - 1]
        sign = 1 if change > deadband else -1 if change < -deadband else 0
        if sign:
            if previous_sign and sign != previous_sign:
                count += 1
                distances.append(profiles[index].chainage_m)
            previous_sign = sign
    spacings = [right - left for left, right in zip(distances, distances[1:])]
    return count, (_median(sorted(spacings)) if spacings else None)


def _mad(values: Sequence[float]) -> float:
    center = _median(sorted(values))
    return _median(sorted(abs(value - center) for value in values))


def _percentile_abs(values: Sequence[float], percentile: float) -> float:
    ordered = sorted(abs(value) for value in values)
    index = max(0, min(len(ordered) - 1, math.ceil(percentile * len(ordered)) - 1))
    return ordered[index]


def _median(values: Sequence[float]) -> float:
    middle = len(values) // 2
    return values[middle] if len(values) % 2 else (values[middle - 1] + values[middle]) / 2.0


def _smooth_step(onset: float, full: float, value: float) -> float:
    normalized = _clamp((value - onset) / max(_EPSILON, full - onset))
    return normalized * normalized * (3.0 - 2.0 * normalized)


def _clamp(value: float) -> float:
    return max(0.0, min(1.0, value))


def _positive(value: float, name: str) -> float:
    if not math.isfinite(value) or value <= 0.0:
        raise ValueError(f"{name} must be finite and positive")
    return value
