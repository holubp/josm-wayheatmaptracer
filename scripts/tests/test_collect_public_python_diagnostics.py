import json
import json
import os
from pathlib import Path

import pytest

from scripts import collect_public_python_diagnostics as collector
from scripts.collect_public_python_diagnostics import MAX_LOG_BYTES, collect


def _failed_public_run(output: Path, python_log: bytes = b"Traceback (most recent call last):\nAssertionError\n"):
    (output / "stages").mkdir(parents=True)
    (output / "stages/python-tests.log").write_bytes(python_log)
    (output / "stages/strict-production-replay.log").write_text("private replay evidence")
    (output / "private").mkdir()
    (output / "private/benchmark.log").write_text("private benchmark evidence")
    (output / "status.json").write_text(json.dumps({
        "profile": "public", "state": "failed",
        "stages": [{"name": "python-tests", "state": "failed", "exit_code": 1}],
    }))
    (output / "summary.json").write_text(json.dumps({"profile": "public", "result": "FAIL"}))


def test_collector_retains_bounded_python_traceback_without_masking_validation_failure(tmp_path: Path):
    validation = tmp_path / "validation"
    diagnostic = tmp_path / "diagnostic"
    _failed_public_run(validation)

    result = collect(validation, diagnostic)

    assert result == {"schema": 1, "state": "collected", "reason": "python-stage-failed",
                     "log_bytes": len(b"Traceback (most recent call last):\nAssertionError\n")}
    assert (diagnostic / "python-tests.log").read_bytes() == b"Traceback (most recent call last):\nAssertionError\n"
    assert json.loads((diagnostic / "status.json").read_text()) == result
    assert json.loads((validation / "summary.json").read_text())["result"] == "FAIL"
    assert set(path.name for path in diagnostic.iterdir()) == {"python-tests.log", "status.json"}


@pytest.mark.parametrize("problem", (
    "missing", "missing-status", "oversize", "symlink", "nonregular", "private-profile",
))
def test_collector_refuses_unavailable_or_nonpublic_python_logs_without_copying_other_outputs(
    tmp_path: Path, problem: str,
):
    validation = tmp_path / "validation"
    diagnostic = tmp_path / "diagnostic"
    _failed_public_run(validation)
    source = validation / "stages/python-tests.log"
    if problem == "missing":
        source.unlink()
    elif problem == "missing-status":
        (validation / "status.json").unlink()
    elif problem == "oversize":
        source.write_bytes(b"x" * (MAX_LOG_BYTES + 1))
    elif problem == "symlink":
        source.unlink()
        source.symlink_to(validation / "stages/strict-production-replay.log")
    elif problem == "nonregular":
        source.unlink()
        source.mkdir()
    elif problem == "private-profile":
        status = json.loads((validation / "status.json").read_text())
        status["profile"] = "rc"
        (validation / "status.json").write_text(json.dumps(status))

    result = collect(validation, diagnostic)

    assert result["state"] == "unavailable"
    assert result["reason"] in {
        "python-log-missing", "python-log-too-large", "python-log-not-regular",
        "public-profile-required", "validation-status-unavailable",
    }
    assert (diagnostic / "status.json").is_file()
    assert not (diagnostic / "python-tests.log").exists()
    assert set(path.name for path in diagnostic.iterdir()) == {"status.json"}


def test_collector_accepts_log_exactly_at_the_byte_limit(tmp_path: Path):
    validation = tmp_path / "validation"
    diagnostic = tmp_path / "diagnostic"
    payload = b"x" * MAX_LOG_BYTES
    _failed_public_run(validation, payload)

    result = collect(validation, diagnostic)

    assert result["state"] == "collected"
    assert result["log_bytes"] == MAX_LOG_BYTES
    assert (diagnostic / "python-tests.log").stat().st_size == MAX_LOG_BYTES


def test_collector_does_not_collect_unrelated_logs_when_python_stage_did_not_fail(tmp_path: Path):
    validation = tmp_path / "validation"
    diagnostic = tmp_path / "diagnostic"
    _failed_public_run(validation)
    status = json.loads((validation / "status.json").read_text())
    status["stages"][0]["state"] = "passed"
    (validation / "status.json").write_text(json.dumps(status))

    result = collect(validation, diagnostic)

    assert result == {"schema": 1, "state": "not-applicable", "reason": "python-stage-not-failed"}
    assert not (diagnostic / "python-tests.log").exists()
    assert set(path.name for path in diagnostic.iterdir()) == {"status.json"}


@pytest.mark.parametrize("linked_component", ("validation-root", "stages"))
def test_collector_rejects_symlinked_source_directories(tmp_path: Path, linked_component: str):
    validation = tmp_path / "validation"
    outside = tmp_path / "outside"
    diagnostic = tmp_path / "diagnostic"
    _failed_public_run(validation)
    outside.mkdir()
    if linked_component == "validation-root":
        (outside / "status.json").write_bytes((validation / "status.json").read_bytes())
        (outside / "stages").mkdir()
        (outside / "stages/python-tests.log").write_bytes((validation / "stages/python-tests.log").read_bytes())
        validation.rename(tmp_path / "validation-original")
        validation.symlink_to(outside, target_is_directory=True)
    else:
        (outside / "python-tests.log").write_text("outside log")
        (validation / "stages").rename(validation / "stages-original")
        (validation / "stages").symlink_to(outside, target_is_directory=True)

    result = collect(validation, diagnostic)

    assert result["state"] == "unavailable"
    assert result["reason"] == ("validation-status-unavailable" if linked_component == "validation-root"
                                 else "python-log-not-regular")
    assert not (diagnostic / "python-tests.log").exists()


def test_collector_refuses_preexisting_diagnostic_directory_without_changing_sentinels(tmp_path: Path):
    validation = tmp_path / "validation"
    diagnostic = tmp_path / "diagnostic"
    _failed_public_run(validation)
    diagnostic.mkdir()
    (diagnostic / "python-tests.log").write_bytes(b"stale sentinel")
    (diagnostic / "status.json").write_bytes(b"stale status")
    before = {path.name: path.read_bytes() for path in diagnostic.iterdir()}

    with pytest.raises(FileExistsError):
        collect(validation, diagnostic)

    assert {path.name: path.read_bytes() for path in diagnostic.iterdir()} == before


def test_collector_rejects_fifo_without_waiting_for_a_writer(tmp_path: Path):
    validation = tmp_path / "validation"
    diagnostic = tmp_path / "diagnostic"
    _failed_public_run(validation)
    source = validation / "stages/python-tests.log"
    source.unlink()
    os.mkfifo(source)

    result = collect(validation, diagnostic)

    assert result["state"] == "unavailable"
    assert result["reason"] == "python-log-not-regular"
    assert not (diagnostic / "python-tests.log").exists()


def test_collector_cleans_only_its_fresh_output_when_status_write_fails(tmp_path: Path, monkeypatch):
    validation = tmp_path / "validation"
    diagnostic = tmp_path / "diagnostic"
    _failed_public_run(validation)
    original_write = collector._write_exclusive

    def fail_status_write(directory_fd: int, name: str, data: bytes) -> None:
        if name == "status.json":
            raise OSError("simulated status write failure")
        original_write(directory_fd, name, data)

    monkeypatch.setattr(collector, "_write_exclusive", fail_status_write)

    with pytest.raises(OSError, match="simulated status write failure"):
        collect(validation, diagnostic)

    assert not diagnostic.exists()
