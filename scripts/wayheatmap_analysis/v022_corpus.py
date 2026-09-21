"""Private-corpus inventory, verification, and escaped report generation for v0.22."""

from __future__ import annotations

import hashlib
import html
import json
import re
from pathlib import Path
from typing import Any

from .privacy import findings, safe_label, safe_scalar
from .safe_zip import ArchiveError, BundleSource, SafeArchiveReader


SCHEMA = "wayheatmaptracer-v022-corpus-1"
EXPECTED_REFERENCE_CASES = 14
REFERENCE_SCHEMA = "wayheatmaptracer-v022-reference-1"


class CorpusError(ValueError):
    """A safe user-facing corpus validation failure."""


def inventory(inputs: Path, require_reference_set: bool, reference_manifest: Path | None = None,
              reference_manifest_sha256: str | None = None) -> dict[str, Any]:
    """Inventory validated debug bundles below one explicitly supplied directory."""

    if not inputs.is_dir():
        raise CorpusError("input directory does not exist")
    paths = sorted(path for path in inputs.iterdir() if path.is_file() and path.suffix.lower() == ".zip")
    cases: list[dict[str, Any]] = []
    errors: list[dict[str, str]] = []
    for path in paths:
        pending: list[dict[str, Any]] = []
        contains_private_text = False

        def collect_bundle(bundle: BundleSource) -> None:
            """Keep only one manifest-ready record after validation completes."""

            digest = hashlib.sha256(bundle.data).hexdigest()
            metadata = _bundle_metadata(bundle)
            pending.append({
                "bundleName": safe_label(bundle.name, digest),
                "bundleSha256": digest,
                "byteSize": len(bundle.data),
                "debugFormat": safe_scalar(metadata.get("formatVersion")),
                "pluginVersion": safe_scalar(metadata.get("pluginVersion")),
                "selectionWayId": _selection_way_id(metadata),
                "replayCapability": _replay_capability(metadata),
                "missingInputs": _missing_inputs(metadata),
            })

        def scan_member(member: Any) -> None:
            """Remember only whether one validated text chunk is private."""

            nonlocal contains_private_text
            contains_private_text |= bool(
                findings(member.name, identity=member.name)
                or findings(member.text, identity=member.name)
            )

        try:
            inspection = SafeArchiveReader().inspect(
                path,
                on_bundle=collect_bundle,
                on_member=scan_member,
            )
            if contains_private_text:
                raise ArchiveError("PRIVACY", "archive contains credential-like material")
        except ArchiveError as error:
            errors.append({"source": _safe_path(path), "code": error.code})
            continue
        for bundle in pending:
            cases.append({
                "caseId": f"case-{len(cases) + 1:03d}",
                "sourcePath": str(path.resolve()),
                "sourceFilename": path.name,
                "exportId": _export_id(path.name),
                "selectionWayId": bundle.get("selectionWayId"),
                "outerSha256": inspection.sha256,
                **bundle,
            })
    cases.sort(key=lambda case: (case["bundleSha256"], case["caseId"]))
    for index, case in enumerate(cases, 1):
        case["caseId"] = f"case-{index:03d}"
    if require_reference_set:
        if reference_manifest is None:
            raise CorpusError("strict inventory requires --reference-manifest")
        if reference_manifest_sha256 is None:
            raise CorpusError("strict inventory requires --reference-manifest-sha256")
        if errors:
            raise CorpusError("strict inventory rejects quarantined archives")
        if len(cases) != EXPECTED_REFERENCE_CASES:
            raise CorpusError(f"required reference set has {len(cases)} cases; expected {EXPECTED_REFERENCE_CASES}")
        _assert_reference_binding(cases, _read_reference_manifest(reference_manifest, reference_manifest_sha256))
    return {"schema": SCHEMA, "inputRoot": str(inputs.resolve()), "cases": cases, "errors": errors}


def verify(manifest_path: Path, require_reference_set: bool = False,
           reference_manifest: Path | None = None,
           reference_manifest_sha256: str | None = None) -> dict[str, Any]:
    """Verify outer and nested bundle hashes from a previously written manifest."""

    manifest = _read_json(manifest_path)
    if manifest.get("schema") != SCHEMA or not isinstance(manifest.get("cases"), list):
        raise CorpusError("unsupported or malformed corpus manifest")
    if require_reference_set:
        if reference_manifest is None:
            raise CorpusError("strict verification requires --reference-manifest")
        if reference_manifest_sha256 is None:
            raise CorpusError("strict verification requires --reference-manifest-sha256")
        if len(manifest["cases"]) != EXPECTED_REFERENCE_CASES:
            raise CorpusError(f"required reference set has {len(manifest['cases'])} cases; expected {EXPECTED_REFERENCE_CASES}")
        _assert_reference_binding(manifest["cases"], _read_reference_manifest(reference_manifest, reference_manifest_sha256))
    by_path: dict[Path, list[dict[str, Any]]] = {}
    for case in manifest["cases"]:
        path = Path(case.get("sourcePath", ""))
        by_path.setdefault(path, []).append(case)
    verified = 0
    for path, expected_cases in by_path.items():
        actual_bundles: set[str] = set()
        contains_private_text = False

        def scan_member(member: Any) -> None:
            """Retain only the privacy verdict while the archive reader streams chunks."""

            nonlocal contains_private_text
            contains_private_text |= bool(
                findings(member.name, identity=member.name)
                or findings(member.text, identity=member.name)
            )

        inspection = SafeArchiveReader().inspect(
            path,
            on_bundle=lambda bundle: actual_bundles.add(hashlib.sha256(bundle.data).hexdigest()),
            on_member=scan_member,
        )
        if contains_private_text:
            raise CorpusError("archive contains credential-like material")
        expected_outer = {case.get("outerSha256") for case in expected_cases}
        if expected_outer != {inspection.sha256}:
            raise CorpusError("outer archive checksum mismatch")
        expected_bundles = {case.get("bundleSha256") for case in expected_cases}
        if expected_bundles != actual_bundles:
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
        text_limit = SafeArchiveReader().limits.max_text_member_bytes
        for name in ("diagnostics.json", "status.json", "replay-manifest.json"):
            if name in names:
                if archive.getinfo(name).file_size > text_limit:
                    raise ArchiveError("TEXT_SIZE", "text-like member exceeds retention limit")
                raw = archive.read(name)
                if findings(raw.decode("utf-8", "replace")):
                    raise CorpusError("bundle metadata contains credential-like material")
                value = json.loads(raw)
                return value if isinstance(value, dict) else {}
    return {}


def _selection_way_id(bundle: dict[str, Any]) -> Any:
    """Extract the selected way identity from validated bundle metadata."""

    selection = bundle.get("selection")
    if isinstance(selection, dict):
        return selection.get("wayId")
    return None


def _export_id(filename: str) -> str:
    """Derive the export identity from the stable debug-export filename."""

    match = re.match(r"^last-slide-debug-(.+?)(?:\(\d+\))?\.zip$", filename)
    return match.group(1) if match else Path(filename).stem


def _read_reference_manifest(path: Path, expected_sha256: str) -> list[dict[str, Any]]:
    if re.fullmatch(r"[0-9a-fA-F]{64}", expected_sha256) is None:
        raise CorpusError("reference manifest SHA-256 must be a 64-character hexadecimal digest")
    try:
        raw = path.read_bytes()
        value = json.loads(raw)
    except (OSError, json.JSONDecodeError) as error:
        raise CorpusError("cannot read JSON input") from error
    if hashlib.sha256(raw).hexdigest() != expected_sha256.lower():
        raise CorpusError("reference manifest SHA-256 mismatch")
    if not isinstance(value, dict):
        raise CorpusError("JSON input must be an object")
    if value.get("schema") != REFERENCE_SCHEMA or not isinstance(value.get("cases"), list):
        raise CorpusError("unsupported or malformed reference manifest")
    required = ("sourceFilename", "exportId", "selectionWayId", "outerSha256", "bundleSha256")
    cases = value["cases"]
    if any(not isinstance(case, dict) or any(field not in case for field in required) for case in cases):
        raise CorpusError("reference manifest case is missing required identity fields")
    if any(not isinstance(case["sourceFilename"], str) or not isinstance(case["exportId"], str)
           or not isinstance(case["selectionWayId"], int)
           or isinstance(case["selectionWayId"], bool)
           or not isinstance(case["outerSha256"], str) or not isinstance(case["bundleSha256"], str)
           or re.fullmatch(r"[0-9a-fA-F]{64}", case["outerSha256"]) is None
           or re.fullmatch(r"[0-9a-fA-F]{64}", case["bundleSha256"]) is None
           for case in cases):
        raise CorpusError("reference manifest contains invalid identity fields")
    return cases


def _identity(case: dict[str, Any]) -> tuple[Any, ...]:
    return tuple(case.get(field) for field in
                 ("sourceFilename", "exportId", "selectionWayId", "outerSha256", "bundleSha256"))


def _assert_reference_binding(actual: list[dict[str, Any]], reference: list[dict[str, Any]]) -> None:
    for case in actual:
        source_path = case.get("sourcePath")
        source_filename = case.get("sourceFilename")
        if (not isinstance(source_path, str) or not isinstance(source_filename, str)
                or Path(source_path).name != source_filename):
            raise CorpusError("reference binding requires sourcePath/sourceFilename agreement")
    actual_ids = [_identity(case) for case in actual]
    reference_ids = [_identity(case) for case in reference]
    if len(set(reference_ids)) != len(reference_ids):
        raise CorpusError("duplicate reference identity")
    if len(set(actual_ids)) != len(actual_ids):
        raise CorpusError("duplicate corpus identity")
    if set(actual_ids) != set(reference_ids):
        raise CorpusError("reference identity mismatch")


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
