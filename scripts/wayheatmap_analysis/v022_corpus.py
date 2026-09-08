"""Private-corpus inventory, verification, and escaped report generation for v0.22."""

from __future__ import annotations

import hashlib
import html
import json
from pathlib import Path
from typing import Any

from .privacy import findings, safe_label, safe_scalar
from .safe_zip import ArchiveError, BundleSource, SafeArchiveReader


SCHEMA = "wayheatmaptracer-v022-corpus-1"
EXPECTED_REFERENCE_CASES = 14


class CorpusError(ValueError):
    """A safe user-facing corpus validation failure."""


def inventory(inputs: Path, require_reference_set: bool) -> dict[str, Any]:
    """Inventory validated debug bundles below one explicitly supplied directory."""

    if not inputs.is_dir():
        raise CorpusError("input directory does not exist")
    paths = sorted(path for path in inputs.iterdir() if path.is_file() and path.suffix.lower() == ".zip")
    cases: list[dict[str, Any]] = []
    errors: list[dict[str, str]] = []
    for path in paths:
        try:
            inspection = SafeArchiveReader().inspect(path)
        except ArchiveError as error:
            errors.append({"source": _safe_path(path), "code": error.code})
            continue
        for bundle in inspection.bundles:
            digest = hashlib.sha256(bundle.data).hexdigest()
            metadata = _bundle_metadata(bundle)
            cases.append({
                "caseId": f"case-{len(cases) + 1:03d}",
                "sourcePath": str(path.resolve()),
                "outerSha256": inspection.sha256,
                "bundleName": safe_label(bundle.name, digest),
                "bundleSha256": digest,
                "byteSize": len(bundle.data),
                "debugFormat": safe_scalar(metadata.get("formatVersion")),
                "pluginVersion": safe_scalar(metadata.get("pluginVersion")),
                "replayCapability": _replay_capability(metadata),
                "missingInputs": _missing_inputs(metadata),
            })
    cases.sort(key=lambda case: (case["bundleSha256"], case["caseId"]))
    for index, case in enumerate(cases, 1):
        case["caseId"] = f"case-{index:03d}"
    if require_reference_set and len(cases) != EXPECTED_REFERENCE_CASES:
        raise CorpusError(f"required reference set has {len(cases)} cases; expected {EXPECTED_REFERENCE_CASES}")
    return {"schema": SCHEMA, "inputRoot": str(inputs.resolve()), "cases": cases, "errors": errors}


def verify(manifest_path: Path) -> dict[str, Any]:
    """Verify outer and nested bundle hashes from a previously written manifest."""

    manifest = _read_json(manifest_path)
    if manifest.get("schema") != SCHEMA or not isinstance(manifest.get("cases"), list):
        raise CorpusError("unsupported or malformed corpus manifest")
    by_path: dict[Path, list[dict[str, Any]]] = {}
    for case in manifest["cases"]:
        path = Path(case.get("sourcePath", ""))
        by_path.setdefault(path, []).append(case)
    verified = 0
    for path, expected_cases in by_path.items():
        inspection = SafeArchiveReader().inspect(path)
        expected_outer = {case.get("outerSha256") for case in expected_cases}
        if expected_outer != {inspection.sha256}:
            raise CorpusError("outer archive checksum mismatch")
        actual_bundles = {hashlib.sha256(bundle.data).hexdigest() for bundle in inspection.bundles}
        expected_bundles = {case.get("bundleSha256") for case in expected_cases}
        if not expected_bundles.issubset(actual_bundles):
            raise CorpusError("nested bundle checksum mismatch")
        verified += len(expected_cases)
    return {"schema": SCHEMA, "verifiedCases": verified, "verified": True}


def render_report(manifest_path: Path, results_path: Path) -> str:
    """Render a self-contained escaped HTML report with every requested case and method."""

    manifest = _read_json(manifest_path)
    results = _read_results(results_path)
    rows: list[str] = []
    for case in manifest.get("cases", []):
        case_id = str(case.get("caseId", "unknown"))
        matching = [result for result in results if str(result.get("caseId")) == case_id]
        if not matching:
            matching = [{"caseId": case_id, "engine": "not-run", "status": "missing-result",
                         "reason": "No replay result was supplied."}]
        for result in matching:
            cells = [case_id, result.get("engine", ""), result.get("status", ""), result.get("reason", "")]
            rows.append("<tr>" + "".join(f"<td>{html.escape(str(cell))}</td>" for cell in cells) + "</tr>")
    return ("<!doctype html><meta charset=\"utf-8\"><title>WayHeatmapTracer v0.22 replay</title>"
            "<h1>WayHeatmapTracer v0.22 replay</h1><table><thead><tr><th>Case</th><th>Engine</th>"
            "<th>Status</th><th>Reason</th></tr></thead><tbody>" + "".join(rows) + "</tbody></table>")


def assert_no_credentials(value: str) -> None:
    """Reject output containing credential markers before it is persisted."""

    if findings(value):
        raise CorpusError("generated output contains credential-like material")


def _bundle_metadata(bundle: BundleSource) -> dict[str, Any]:
    # SafeArchiveReader has already validated names, duplicates, CRCs, sizes, and expansion budgets.
    import io
    import zipfile

    with zipfile.ZipFile(io.BytesIO(bundle.data)) as archive:
        names = set(archive.namelist())
        for name in ("diagnostics.json", "status.json", "replay-manifest.json"):
            if name in names:
                raw = archive.read(name)
                if findings(raw.decode("utf-8", "replace")):
                    raise CorpusError("bundle metadata contains credential-like material")
                value = json.loads(raw)
                return value if isinstance(value, dict) else {}
    return {}


def _replay_capability(metadata: dict[str, Any]) -> str:
    if metadata.get("editPlan") and metadata.get("evidenceFrame"):
        return "FULL_EDIT_PLAN"
    if metadata.get("renderedCapture") or metadata.get("heatmapCapture"):
        return "RASTER_INFERENCE"
    if metadata.get("candidateMetrics") or metadata.get("profileCount"):
        return "SCALAR_INFERENCE"
    return "FINAL_GEOMETRY"


def _missing_inputs(metadata: dict[str, Any]) -> list[str]:
    missing = []
    if not metadata.get("editPlan"):
        missing.append("complete-edit-plan")
    if not metadata.get("incidentRelations"):
        missing.append("incident-relations")
    return missing


def _read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise CorpusError("cannot read JSON input") from error
    if not isinstance(value, dict):
        raise CorpusError("JSON input must be an object")
    return value


def _read_results(path: Path) -> list[dict[str, Any]]:
    value = _read_json(path)
    results = value.get("results", [])
    if not isinstance(results, list) or any(not isinstance(result, dict) for result in results):
        raise CorpusError("results must be an array of objects")
    return results


def _safe_path(path: Path) -> str:
    digest = hashlib.sha256(str(path).encode()).hexdigest()
    return safe_label(path.name, digest)
