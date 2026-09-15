"""Leakage-resistant empirical confidence calibration for v0.22 trace results.

This offline module fits one frozen, regularized logistic model per exact source
configuration. It is deliberately separate from deterministic geometry policy and
never learns from Apply/Cancel behavior, objectives, OSM identities, or duplicated
crops from one geographic site.
"""
from __future__ import annotations
from dataclasses import dataclass
import hashlib
import json
import math
from typing import Iterable, Mapping, Sequence
ALLOWED_FEATURES = ('direct_coverage', 'worst_gap_m', 'branch_ambiguity', 'local_defect_count', 'solver_complete', 'constraint_class')
NUMERIC_FEATURES = ALLOWED_FEATURES[:-1]
CALIBRATION_SCHEMA = 'wayheatmaptracer-confidence-v3'
FEATURE_SCHEMA_VERSION = 'v022-feature-encoding-2'
NUMERIC_RANGES = {'direct_coverage': (0.0, 1.0), 'worst_gap_m': (0.0, 10000.0), 'branch_ambiguity': (0.0, 1.0), 'local_defect_count': (0.0, 1000.0), 'solver_complete': (0.0, 1.0)}

class CalibrationError(ValueError):
    """Raised when labels, model metadata, or locked evaluation are unsafe."""

def _bounded_float(value: object, name: str, minimum: float, maximum: float) -> None:
    if type(value) not in (int, float) or not minimum <= value <= maximum or (not math.isfinite(value)):
        raise CalibrationError(f'{name} is outside the supported solver range')

@dataclass(frozen=True)
class FeatureEncoding:
    """Deeply immutable feature encoding owned by one serialized model."""
    version: str
    numeric: tuple[tuple[str, str], ...]
    constraints: tuple[tuple[str, float], ...]

    def as_payload(self) -> dict[str, object]:
        """Return a fresh ordinary mapping suitable for public JSON export."""
        return {'version': self.version, 'numeric': dict(self.numeric), 'constraint_class': dict(self.constraints)}

    @classmethod
    def from_payload(cls, payload: object) -> FeatureEncoding:
        """Load only the one versioned encoding supported by this module."""
        if not isinstance(payload, dict) or set(payload) != {'version', 'numeric', 'constraint_class'}:
            raise CalibrationError('model feature encoding is incompatible')
        numeric, constraints = (payload['numeric'], payload['constraint_class'])
        if not isinstance(numeric, dict) or not isinstance(constraints, dict):
            raise CalibrationError('model feature encoding is malformed')
        result = cls(payload['version'], tuple(sorted(numeric.items())), tuple(sorted(constraints.items())))
        if result != DEFAULT_ENCODING:
            raise CalibrationError('model feature encoding does not match v0.22')
        return result

    def constraint_value(self, value: str) -> float:
        values = dict(self.constraints)
        if value not in values:
            raise CalibrationError('constraint class is not in the fixed encoding')
        return values[value]

    def encode(self, features: Mapping[str, float | str]) -> tuple[float, ...]:
        _validate_features(features, self)
        return (float(features['direct_coverage']), math.log1p(float(features['worst_gap_m'])) / 3.0, float(features['branch_ambiguity']), math.log1p(float(features['local_defect_count'])) / 3.0, float(features['solver_complete']), self.constraint_value(features['constraint_class']))
DEFAULT_ENCODING = FeatureEncoding(FEATURE_SCHEMA_VERSION, (('branch_ambiguity', 'identity'), ('direct_coverage', 'identity'), ('local_defect_count', 'log1p-divide-3'), ('solver_complete', 'identity'), ('worst_gap_m', 'log1p-divide-3')), (('fixed', 0.0), ('mixed', 0.5), ('movable', 1.0)))
FEATURE_SCHEMA_HASH = hashlib.sha256(json.dumps({'features': ALLOWED_FEATURES, 'encoding': DEFAULT_ENCODING.as_payload()}, sort_keys=True, separators=(',', ':')).encode()).hexdigest()

@dataclass(frozen=True)
class SolverConfig:
    """Predeclared deterministic optimizer settings, selected before splitting."""
    regularization: float = 0.05
    learning_rate: float = 0.2
    max_iterations: int = 2000
    gradient_tolerance: float = 1e-05

    def validate(self) -> None:
        """Reject boolean, non-finite, or unsupported numerical solver controls."""
        _bounded_float(self.regularization, 'regularization', 0.0, 10.0)
        _bounded_float(self.learning_rate, 'learning_rate', 1e-12, 1.0)
        _bounded_float(self.gradient_tolerance, 'gradient_tolerance', 1e-12, 0.1)
        if type(self.max_iterations) is not int or not 0 <= self.max_iterations <= 10000:
            raise CalibrationError('max_iterations is outside the supported solver range')

    def as_payload(self) -> dict[str, float | int]:
        self.validate()
        return {'gradientTolerance': self.gradient_tolerance, 'learningRate': self.learning_rate, 'maxIterations': self.max_iterations, 'regularization': self.regularization}

    @classmethod
    def from_payload(cls, payload: object) -> SolverConfig:
        if not isinstance(payload, dict) or set(payload) != {'gradientTolerance', 'learningRate', 'maxIterations', 'regularization'}:
            raise CalibrationError('model solver metadata is incompatible')
        config = cls(payload['regularization'], payload['learningRate'], payload['maxIterations'], payload['gradientTolerance'])
        config.validate()
        return config

@dataclass(frozen=True)
class ValidationConfig:
    """Fixed heldout reliability and no-extra-review rules, hashed into the model."""
    probability_bin_edges: tuple[float, ...] = (0.0, 0.25, 0.5, 0.75, 1.0)
    minimum_groups_per_populated_bin: int = 300
    reliability_error_limit: float = 0.1
    confidence_level: float = 0.95
    no_extra_review_threshold: float = 0.95

    def validate(self) -> None:
        if len(self.probability_bin_edges) < 2 or self.probability_bin_edges[0] != 0.0 or self.probability_bin_edges[-1] != 1.0 or any((type(value) is not float or not math.isfinite(value) for value in self.probability_bin_edges)) or any((left >= right for left, right in zip(self.probability_bin_edges, self.probability_bin_edges[1:]))) or (type(self.minimum_groups_per_populated_bin) is not int) or (self.minimum_groups_per_populated_bin < 1):
            raise CalibrationError('validation configuration is invalid')
        _bounded_float(self.reliability_error_limit, 'reliability_error_limit', 1e-06, 0.5)
        _bounded_float(self.confidence_level, 'confidence_level', 0.5, 0.999)
        _bounded_float(self.no_extra_review_threshold, 'no_extra_review_threshold', 0.5, 1.0)

    def as_payload(self) -> dict[str, object]:
        self.validate()
        return {'confidenceLevel': self.confidence_level, 'minimumGroupsPerPopulatedBin': self.minimum_groups_per_populated_bin, 'noExtraReviewThreshold': self.no_extra_review_threshold, 'probabilityBinEdges': list(self.probability_bin_edges), 'reliabilityErrorLimit': self.reliability_error_limit}

    @classmethod
    def from_payload(cls, payload: object) -> ValidationConfig:
        if not isinstance(payload, dict) or set(payload) != {'confidenceLevel', 'minimumGroupsPerPopulatedBin', 'noExtraReviewThreshold', 'probabilityBinEdges', 'reliabilityErrorLimit'}:
            raise CalibrationError('model validation metadata is incompatible')
        edges = payload['probabilityBinEdges']
        if not isinstance(edges, list):
            raise CalibrationError('model probability bins are malformed')
        config = cls(tuple(edges), payload['minimumGroupsPerPopulatedBin'], payload['reliabilityErrorLimit'], payload['confidenceLevel'], payload['noExtraReviewThreshold'])
        config.validate()
        return config
DEFAULT_VALIDATION_CONFIG = ValidationConfig()
VALIDATION_CONFIG_HASH = hashlib.sha256(json.dumps(DEFAULT_VALIDATION_CONFIG.as_payload(), sort_keys=True, separators=(',', ':')).encode()).hexdigest()

@dataclass(frozen=True)
class CalibrationExample:
    """One independently human-labeled route outcome grouped by physical site."""
    location_group: str
    label_correct: bool
    label_source: str
    features: Mapping[str, float | str]
    parameter_hash: str
    domain_id: str

    def validate(self) -> None:
        if not isinstance(self.location_group, str) or not self.location_group:
            raise CalibrationError('calibration example lacks an independent location group')
        if type(self.label_correct) is not bool:
            raise CalibrationError('correctness labels must be boolean')
        if self.label_source != 'independent-human-reference':
            raise CalibrationError('confidence labels require an independent human reference')
        if not _hash(self.parameter_hash) or not isinstance(self.domain_id, str) or (not self.domain_id):
            raise CalibrationError('calibration example lacks a versioned domain identity')
        _validate_features(self.features, DEFAULT_ENCODING)

@dataclass(frozen=True)
class CalibrationModel:
    """Immutable fitted logistic model with exact fit and validation identity."""
    schema: str
    domain_id: str
    domain_hash: str
    parameter_hash: str
    feature_schema: tuple[str, ...]
    feature_schema_hash: str
    encoding: FeatureEncoding
    solver: SolverConfig
    validation_config: ValidationConfig
    validation_config_hash: str
    intercept: float
    coefficients: tuple[float, ...]
    solver_complete: bool
    model_hash: str

    @property
    def feature_encoding(self) -> dict[str, object]:
        """Return a detached compatibility/export copy; mutation cannot affect the model."""
        return self.encoding.as_payload()

    def predict(self, features: Mapping[str, float | str]) -> float:
        """Predict with the immutable model encoding; reject unavailable or non-finite scores."""
        if not self.solver_complete:
            raise CalibrationError('empirical probability is unavailable: solver was incomplete')
        values = self.encoding.encode(features)
        score = self.intercept + sum((weight * value for weight, value in zip(self.coefficients, values)))
        if not math.isfinite(score):
            raise CalibrationError('model score is non-finite')
        return _sigmoid(score)

    def _payload_without_hash(self) -> dict[str, object]:
        return {'coefficients': list(self.coefficients), 'domainHash': self.domain_hash, 'domainId': self.domain_id, 'featureEncoding': self.encoding.as_payload(), 'featureSchema': list(self.feature_schema), 'featureSchemaHash': self.feature_schema_hash, 'intercept': self.intercept, 'parameterHash': self.parameter_hash, 'schema': self.schema, 'solver': self.solver.as_payload(), 'solverComplete': self.solver_complete, 'validationConfig': self.validation_config.as_payload(), 'validationConfigHash': self.validation_config_hash}

    def as_payload(self) -> dict[str, object]:
        payload = self._payload_without_hash()
        payload['modelHash'] = self.model_hash
        return payload

    def as_json(self) -> str:
        return json.dumps(self.as_payload(), sort_keys=True, indent=2, allow_nan=False) + '\n'

    @classmethod
    def from_json(cls, value: str) -> CalibrationModel:
        """Load a complete hash-bound model or raise CalibrationError for invalid metadata."""
        try:
            payload = json.loads(value)
        except (TypeError, ValueError) as exc:
            raise CalibrationError('model JSON is invalid') from exc
        expected = {'coefficients', 'domainHash', 'domainId', 'featureEncoding', 'featureSchema', 'featureSchemaHash', 'intercept', 'modelHash', 'parameterHash', 'schema', 'solver', 'solverComplete', 'validationConfig', 'validationConfigHash'}
        if not isinstance(payload, dict) or set(payload) != expected:
            raise CalibrationError('model metadata is incomplete or incompatible')
        try:
            model = cls(payload['schema'], payload['domainId'], payload['domainHash'], payload['parameterHash'], tuple(payload['featureSchema']), payload['featureSchemaHash'], FeatureEncoding.from_payload(payload['featureEncoding']), SolverConfig.from_payload(payload['solver']), ValidationConfig.from_payload(payload['validationConfig']), payload['validationConfigHash'], float(payload['intercept']), tuple((float(v) for v in payload['coefficients'])), payload['solverComplete'], payload['modelHash'])
        except (TypeError, ValueError, OverflowError) as exc:
            raise CalibrationError('model metadata has invalid types') from exc
        model._validate_integrity()
        return model

    def _validate_integrity(self) -> None:
        if self.schema != CALIBRATION_SCHEMA or not isinstance(self.domain_id, str) or (not self.domain_id) or (self.domain_hash != _domain_hash(self.domain_id)) or (not _hash(self.parameter_hash)) or (self.feature_schema != ALLOWED_FEATURES) or (self.feature_schema_hash != FEATURE_SCHEMA_HASH) or (self.encoding != DEFAULT_ENCODING) or (self.validation_config != DEFAULT_VALIDATION_CONFIG) or (self.validation_config_hash != VALIDATION_CONFIG_HASH) or (type(self.solver_complete) is not bool) or (len(self.coefficients) != len(ALLOWED_FEATURES)) or (not math.isfinite(self.intercept)) or any((not math.isfinite(v) for v in self.coefficients)) or (not _hash(self.model_hash)):
            raise CalibrationError('model metadata does not match the confidence contract')
        if _sha256(self._payload_without_hash()) != self.model_hash:
            raise CalibrationError('model hash does not match its serialized metadata')

@dataclass(frozen=True)
class ValidationResult:
    """Actual heldout outcome metrics and fixed group-level validation evidence."""
    model_hash: str
    evaluated_group_count: int
    evaluated_outcome_count: int
    positive_group_count: int
    negative_group_count: int
    mixed_label_group_count: int
    mean_prediction: float
    minimum_prediction: float
    maximum_prediction: float
    predicted_positive_group_count: int
    accuracy: float
    brier_score: float
    log_loss: float
    mean_calibration_error: float
    reliability_status: str
    reliability_bins: tuple[dict[str, object], ...]
    validation_config_hash: str
    eligible_group_count: int
    severe_site_event_count: int
    severe_error_upper_95: float | None
    severe_error_certified: bool

    def _eligibility_threshold(self) -> float:
        return DEFAULT_VALIDATION_CONFIG.no_extra_review_threshold

    def as_payload(self) -> dict[str, object]:
        count = self.evaluated_group_count
        brier_margin = math.sqrt(math.log(40.0) / (2.0 * count))
        signed_margin = math.sqrt(2.0 * math.log(40.0) / count)
        return {'accuracy': self.accuracy, 'brierScore': self.brier_score, 'evaluatedGroupCount': count, 'evaluatedOutcomeCount': self.evaluated_outcome_count, 'logLoss': self.log_loss, 'meanCalibrationError': self.mean_calibration_error, 'mixedLabelGroupCount': self.mixed_label_group_count, 'modelHash': self.model_hash, 'negativeGroupCount': self.negative_group_count, 'positiveGroupCount': self.positive_group_count, 'predictions': {'groupWeightedCount': count, 'maximum': self.maximum_prediction, 'mean': self.mean_prediction, 'minimum': self.minimum_prediction, 'positiveAtHalfCount': self.predicted_positive_group_count}, 'reliability': {'accepted': self.reliability_status == 'accepted', 'bins': [dict(item) for item in self.reliability_bins], 'status': self.reliability_status, 'validationConfigHash': self.validation_config_hash}, 'severeError': {'certifiedNoExtraReview': self.severe_error_certified, 'doesNotGrantApplyPermission': True, 'eligibility': 'any outcome prediction at or above predeclared noExtraReviewThreshold', 'eligibilityThreshold': self._eligibility_threshold(), 'eligibleGroupCount': self.eligible_group_count, 'oneSidedUpper95': self.severe_error_upper_95, 'severeSiteEventCount': self.severe_site_event_count, 'siteEvent': 'any qualifying wrong outcome in an eligible site'}, 'uncertainty': {'assumptions': 'independent heldout geographic groups; actual outcomes averaged within each group to total weight one', 'brierScoreInterval': [max(0.0, self.brier_score - brier_margin), min(1.0, self.brier_score + brier_margin)], 'calibrationErrorInterval': [max(-1.0, self.mean_calibration_error - signed_margin), min(1.0, self.mean_calibration_error + signed_margin)], 'method': 'two-sided-hoeffding-95-group-level'}}

@dataclass(frozen=True)
class CalibrationPolicy:
    """Fitted/evaluated policy; calibrated only after the predeclared gate accepts."""
    schema: str
    domain_id: str
    domain_hash: str
    parameter_hash: str
    feature_schema: tuple[str, ...]
    feature_schema_hash: str
    training_groups: tuple[str, ...]
    heldout_groups: tuple[str, ...]
    calibrated: bool
    reason: str
    model: CalibrationModel | None
    validation: ValidationResult | None

    def confidence_available(self, domain_id: str, parameter_hash: str, solver_complete: bool, *, feature_schema: Sequence[str]=ALLOWED_FEATURES, model_hash: str | None=None) -> bool:
        return self.calibrated and self.model is not None and (self.validation is not None) and self.model.solver_complete and solver_complete and (domain_id == self.domain_id) and (_domain_hash(domain_id) == self.domain_hash) and (parameter_hash == self.parameter_hash) and (tuple(feature_schema) == self.feature_schema) and (model_hash is None or model_hash == self.model.model_hash)

    def as_json(self) -> str:
        payload = {'calibrated': self.calibrated, 'domainHash': self.domain_hash, 'domainId': self.domain_id, 'featureSchema': list(self.feature_schema), 'featureSchemaHash': self.feature_schema_hash, 'heldoutGroupCount': len(self.heldout_groups), 'parameterHash': self.parameter_hash, 'reason': self.reason, 'schema': self.schema, 'trainingGroupCount': len(self.training_groups)}
        if self.model is not None:
            payload['model'] = self.model.as_payload()
        if self.validation is not None:
            payload['validation'] = self.validation.as_payload()
        return json.dumps(payload, sort_keys=True, indent=2, allow_nan=False) + '\n'

def grouped_split(examples: Sequence[CalibrationExample], heldout_fraction: float=0.25, salt: str='v022-heldout-1') -> tuple[list[CalibrationExample], list[CalibrationExample]]:
    """Partition complete geographic groups deterministically; never split a site."""
    if not 0.0 < heldout_fraction < 1.0 or not salt:
        raise CalibrationError('held-out split parameters are invalid')
    for example in examples:
        example.validate()
    groups = sorted({example.location_group for example in examples})
    if len(groups) < 2:
        raise CalibrationError('at least two independent location groups are required')
    ranked = sorted(groups, key=lambda group: hashlib.sha256(f'{salt}\x00{group}'.encode()).digest())
    heldout = set(ranked[:max(1, min(len(groups) - 1, round(len(groups) * heldout_fraction)))])
    return ([row for row in examples if row.location_group not in heldout], [row for row in examples if row.location_group in heldout])

def build_policy(examples: Sequence[CalibrationExample], domain_id: str, parameter_hash: str, minimum_training_groups: int=50, minimum_heldout_groups: int=10, solver_config: SolverConfig=SolverConfig()) -> CalibrationPolicy:
    """Fit training groups and evaluate untouched groups under fixed reliability rules.

    Invalid data or incomplete evidence returns an unavailable policy; empirical
    confidence never grants permission to apply OSM edits."""
    if not isinstance(domain_id, str) or not domain_id or (not _hash(parameter_hash)) or (type(minimum_training_groups) is not int) or (minimum_training_groups < 2) or (type(minimum_heldout_groups) is not int) or (minimum_heldout_groups < 1):
        raise CalibrationError('calibration policy identity is invalid')
    try:
        solver_config.validate()
    except CalibrationError:
        return _unavailable(domain_id, parameter_hash, (), (), 'invalid-solver-configuration')
    try:
        for row in examples:
            row.validate()
    except CalibrationError:
        return _unavailable(domain_id, parameter_hash, (), (), 'invalid-calibration-data')
    matching = [row for row in examples if row.domain_id == domain_id and row.parameter_hash == parameter_hash]
    groups = tuple(sorted({row.location_group for row in matching}))
    if len(groups) < 2:
        return _unavailable(domain_id, parameter_hash, groups, (), 'insufficient-independent-labeled-groups')
    training, heldout = grouped_split(matching)
    train_groups = tuple(sorted({r.location_group for r in training}))
    held_groups = tuple(sorted({r.location_group for r in heldout}))
    if len(train_groups) < minimum_training_groups or len(held_groups) < minimum_heldout_groups:
        return _unavailable(domain_id, parameter_hash, train_groups, held_groups, 'insufficient-independent-labeled-groups')
    if not _has_both_label_outcomes(training):
        return _unavailable(domain_id, parameter_hash, train_groups, held_groups, 'insufficient-label-outcome-variation')
    model = _fit_model(training, domain_id, parameter_hash, solver_config)
    if model is None:
        return _unavailable(domain_id, parameter_hash, train_groups, held_groups, 'solver-numerical-failure')
    if not model.solver_complete:
        return CalibrationPolicy(CALIBRATION_SCHEMA, domain_id, _domain_hash(domain_id), parameter_hash, ALLOWED_FEATURES, FEATURE_SCHEMA_HASH, train_groups, held_groups, False, 'solver-incomplete', model, None)
    validation = _evaluate(model, heldout)
    reason = 'validated-independent-domain' if validation.reliability_status == 'accepted' else f'validation-{validation.reliability_status}'
    return CalibrationPolicy(CALIBRATION_SCHEMA, domain_id, _domain_hash(domain_id), parameter_hash, ALLOWED_FEATURES, FEATURE_SCHEMA_HASH, train_groups, held_groups, validation.reliability_status == 'accepted', reason, model, validation)

def validate_result_label(row: Mapping[str, object]) -> None:
    """Reject labels whose provenance is not an independent human reference."""
    if any((key in row for key in ('wasApplied', 'applyDecision', 'selectedByUser', 'optimizerAsTruth'))):
        raise CalibrationError('application behavior cannot be used as a correctness label')
    if row.get('labelSource') != 'independent-human-reference' or type(row.get('labelCorrect')) is not bool:
        raise CalibrationError('result lacks an independent boolean correctness label')

def verify_locked_evaluation(policy: CalibrationPolicy, evaluated_parameter_hash: str, evaluated_groups: Iterable[str], *, evaluated_feature_schema: Sequence[str]=ALLOWED_FEATURES, model_hash: str | None=None) -> None:
    """Require the exact reserved held-out inventory and frozen model identity."""
    sequence = tuple(evaluated_groups)
    if evaluated_parameter_hash != policy.parameter_hash:
        raise CalibrationError('held-out evaluation changed the frozen parameters')
    if tuple(evaluated_feature_schema) != policy.feature_schema:
        raise CalibrationError('held-out evaluation changed the feature schema')
    if len(set(sequence)) != len(sequence) or set(sequence) != set(policy.heldout_groups) or len(sequence) != len(policy.heldout_groups):
        raise CalibrationError('evaluation groups do not exactly match the reserved heldout set')
    if policy.model is None or not policy.model.solver_complete:
        raise CalibrationError('held-out evaluation has no complete frozen model')
    if model_hash is not None and model_hash != policy.model.model_hash:
        raise CalibrationError('held-out evaluation changed the frozen model')

def one_sided_binomial_upper_bound(errors: int, group_count: int, alpha: float=0.05) -> float:
    """Return the exact one-sided binomial upper bound for independent site events."""
    if type(errors) is not int or type(group_count) is not int or group_count < 1 or (errors < 0) or (errors > group_count) or (type(alpha) is bool) or (not isinstance(alpha, (int, float))) or (not 0.0 < alpha < 1.0):
        raise CalibrationError('binomial bound inputs are invalid')
    if errors == group_count:
        return 1.0
    if errors == 0:
        return 1.0 - alpha ** (1.0 / group_count)
    low, high = (errors / group_count, 1.0)
    for _ in range(100):
        middle = (low + high) / 2
        if _binomial_cdf(errors, group_count, middle) > alpha:
            low = middle
        else:
            high = middle
    return (low + high) / 2

def _fit_model(rows: Sequence[CalibrationExample], domain_id: str, parameter_hash: str, solver: SolverConfig) -> CalibrationModel | None:
    groups = _group_outcomes(rows)
    group_vectors = [[(DEFAULT_ENCODING.encode(r.features), float(r.label_correct)) for r in outcomes] for _, outcomes in groups]
    base = sum((sum((label for _, label in outcomes)) / len(outcomes) for outcomes in group_vectors)) / len(group_vectors)
    if not math.isfinite(base):
        return None
    intercept = math.log(min(1.0 - 1e-12, max(1e-12, base)) / (1.0 - min(1.0 - 1e-12, max(1e-12, base))))
    coefficients = [0.0] * len(ALLOWED_FEATURES)
    complete = False
    for _ in range(solver.max_iterations):
        try:
            group_gradients = []
            for outcomes in group_vectors:
                errors = []
                for vector, label in outcomes:
                    score = intercept + sum((w * x for w, x in zip(coefficients, vector)))
                    if not math.isfinite(score):
                        return None
                    errors.append(_sigmoid(score) - label)
                group_gradients.append([sum(errors) / len(errors)] + [sum((error * vector[index] for error, (vector, _) in zip(errors, outcomes))) / len(outcomes) for index in range(len(coefficients))])
            gradient = [sum((row[index] for row in group_gradients)) / len(group_gradients) for index in range(len(coefficients) + 1)]
            gradient[1:] = [value + solver.regularization * coefficients[index] for index, value in enumerate(gradient[1:])]
        except (OverflowError, ValueError):
            return None
        if any((not math.isfinite(value) for value in gradient)):
            return None
        if max((abs(value) for value in gradient)) <= solver.gradient_tolerance:
            complete = True
            break
        proposed_intercept = intercept - solver.learning_rate * gradient[0]
        proposed = [value - solver.learning_rate * gradient[index + 1] for index, value in enumerate(coefficients)]
        if not math.isfinite(proposed_intercept) or any((not math.isfinite(value) for value in proposed)):
            return None
        intercept, coefficients = (proposed_intercept, proposed)
    payload = {'coefficients': coefficients, 'domainHash': _domain_hash(domain_id), 'domainId': domain_id, 'featureEncoding': DEFAULT_ENCODING.as_payload(), 'featureSchema': list(ALLOWED_FEATURES), 'featureSchemaHash': FEATURE_SCHEMA_HASH, 'intercept': intercept, 'parameterHash': parameter_hash, 'schema': CALIBRATION_SCHEMA, 'solver': solver.as_payload(), 'solverComplete': complete, 'validationConfig': DEFAULT_VALIDATION_CONFIG.as_payload(), 'validationConfigHash': VALIDATION_CONFIG_HASH}
    return CalibrationModel(CALIBRATION_SCHEMA, domain_id, _domain_hash(domain_id), parameter_hash, ALLOWED_FEATURES, FEATURE_SCHEMA_HASH, DEFAULT_ENCODING, solver, DEFAULT_VALIDATION_CONFIG, VALIDATION_CONFIG_HASH, intercept, tuple(coefficients), complete, _sha256(payload))

def _evaluate(model: CalibrationModel, rows: Sequence[CalibrationExample]) -> ValidationResult:
    groups = _group_outcomes(rows)
    group_stats = []
    eligible = 0
    severe = 0
    for _, outcomes in groups:
        predictions = [model.predict(row.features) for row in outcomes]
        labels = [float(row.label_correct) for row in outcomes]
        mean_p = sum(predictions) / len(predictions)
        mean_y = sum(labels) / len(labels)
        group_stats.append((mean_p, mean_y, predictions, labels))
        qualifying = [index for index, prediction in enumerate(predictions) if prediction >= model.validation_config.no_extra_review_threshold]
        if qualifying:
            eligible += 1
            if any((not outcomes[index].label_correct for index in qualifying)):
                severe += 1
    count = len(group_stats)
    outcome_count = sum((len(item[2]) for item in group_stats))
    brier = sum((sum(((p - y) ** 2 for p, y in zip(predictions, labels))) / len(predictions) for _, _, predictions, labels in group_stats)) / count
    logloss = sum((sum((-y * math.log(max(p, 1e-15)) - (1 - y) * math.log(max(1 - p, 1e-15)) for p, y in zip(predictions, labels))) / len(predictions) for _, _, predictions, labels in group_stats)) / count
    accuracy = sum((sum(((p >= 0.5) == bool(y) for p, y in zip(predictions, labels))) / len(predictions) for _, _, predictions, labels in group_stats)) / count
    reliability_status, bins = _reliability(group_stats, model.validation_config)
    upper = one_sided_binomial_upper_bound(severe, eligible) if eligible else None
    return ValidationResult(model.model_hash, count, outcome_count, sum((y == 1.0 for _, y, _, _ in group_stats)), sum((y == 0.0 for _, y, _, _ in group_stats)), sum((0.0 < y < 1.0 for _, y, _, _ in group_stats)), sum((p for p, _, _, _ in group_stats)) / count, min((p for p, _, _, _ in group_stats)), max((p for p, _, _, _ in group_stats)), sum((p >= 0.5 for p, _, _, _ in group_stats)), accuracy, brier, logloss, sum((p - y for p, y, _, _ in group_stats)) / count, reliability_status, bins, VALIDATION_CONFIG_HASH, eligible, severe, upper, bool(reliability_status == 'accepted' and upper is not None and (upper <= 0.01)))

def _reliability(stats: Sequence[tuple[float, float, list[float], list[float]]], config: ValidationConfig) -> tuple[str, tuple[dict[str, object], ...]]:
    indexed = [[] for _ in range(len(config.probability_bin_edges) - 1)]
    for _, _, predictions, labels in stats:
        per_site = [[] for _ in range(len(indexed))]
        for prediction, label in zip(predictions, labels):
            _bounded_float(prediction, 'predicted probability', 0.0, 1.0)
            # Interior bins exclude their upper edge; the final bin includes one.
            index = next((index for index in range(len(indexed)) if prediction < config.probability_bin_edges[index + 1]), len(indexed) - 1)
            per_site[index].append(prediction - label)
        for index, residuals in enumerate(per_site):
            if residuals:
                indexed[index].append(sum(residuals) / len(residuals))
    alpha = 1.0 - config.confidence_level
    bins = []
    failed = False
    incomplete = False
    for index, residuals in enumerate(indexed):
        if not residuals:
            continue
        mean = sum(residuals) / len(residuals)
        margin = math.sqrt(2.0 * math.log(2.0 * len(indexed) / alpha) / len(residuals))
        upper = abs(mean) + margin
        status = 'accepted'
        if abs(mean) > config.reliability_error_limit:
            status = 'failed'
            failed = True
        elif len(residuals) < config.minimum_groups_per_populated_bin:
            status = 'inconclusive'
            incomplete = True
        elif upper > config.reliability_error_limit:
            status = 'failed'
            failed = True
        bins.append({'bin': [config.probability_bin_edges[index], config.probability_bin_edges[index + 1]], 'groupCount': len(residuals), 'meanError': mean, 'twoSidedUpperAbsError95': upper, 'status': status})
    return ('failed' if failed else 'inconclusive' if incomplete else 'accepted', tuple(bins))

def _group_outcomes(rows: Sequence[CalibrationExample]) -> list[tuple[str, tuple[CalibrationExample, ...]]]:
    groups: dict[str, dict[str, CalibrationExample]] = {}
    for row in rows:
        groups.setdefault(row.location_group, {})[_canonical_json({'features': row.features, 'label': row.label_correct})] = row
    return [(group, tuple((groups[group][key] for key in sorted(groups[group])))) for group in sorted(groups)]

def _has_both_label_outcomes(rows: Sequence[CalibrationExample]) -> bool:
    return {row.label_correct for row in rows} == {False, True}

def _unavailable(domain_id: str, parameter_hash: str, training: Sequence[str], heldout: Sequence[str], reason: str) -> CalibrationPolicy:
    return CalibrationPolicy(CALIBRATION_SCHEMA, domain_id, _domain_hash(domain_id), parameter_hash, ALLOWED_FEATURES, FEATURE_SCHEMA_HASH, tuple(training), tuple(heldout), False, reason, None, None)

def _validate_features(features: Mapping[str, float | str], encoding: FeatureEncoding) -> None:
    if set(features) != set(ALLOWED_FEATURES):
        raise CalibrationError('calibration feature schema does not match v0.22')
    for name in NUMERIC_FEATURES:
        value = features[name]
        _bounded_float(value, name, *NUMERIC_RANGES[name])
    if not isinstance(features['constraint_class'], str):
        raise CalibrationError('constraint class must be categorical')
    encoding.constraint_value(features['constraint_class'])

def _binomial_cdf(errors: int, n: int, p: float) -> float:
    if p <= 0:
        return 1.0
    if p >= 1:
        return 0.0
    values = [math.lgamma(n + 1) - math.lgamma(k + 1) - math.lgamma(n - k + 1) + k * math.log(p) + (n - k) * math.log1p(-p) for k in range(errors + 1)]
    maximum = max(values)
    return math.exp(maximum) * sum((math.exp(value - maximum) for value in values))

def _sigmoid(value: float) -> float:
    if value >= 0:
        return 1.0 / (1.0 + math.exp(-value))
    result = math.exp(value)
    return result / (1.0 + result)

def _canonical_json(value: object) -> str:
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False)

def _sha256(value: object) -> str:
    return hashlib.sha256(_canonical_json(value).encode()).hexdigest()

def _domain_hash(domain_id: str) -> str:
    return _sha256({'domainId': domain_id})

def _hash(value: str) -> bool:
    return isinstance(value, str) and len(value) == 64 and all((c in '0123456789abcdef' for c in value))
