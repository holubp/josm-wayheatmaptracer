#!/usr/bin/env python3
"""Copy only a bounded failed public Python stage log for CI diagnosis."""

from __future__ import annotations

import argparse
import errno
import json
import os
import stat
import sys
from pathlib import Path
from typing import Any

MAX_LOG_BYTES = 65_536
MAX_STATUS_BYTES = 1_048_576
_DIR_FLAGS = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
_READ_FLAGS = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)


def _absolute(path: Path) -> Path:
    return Path(os.path.abspath(os.fspath(path)))


def _open_directory_chain(path: Path) -> int:
    """Open an absolute directory path without following any symlink component."""
    absolute = _absolute(path)
    prefixes = [Path(*absolute.parts[:index]) for index in range(1, len(absolute.parts) + 1)]
    for prefix in prefixes:
        info = prefix.lstat()
        if not stat.S_ISDIR(info.st_mode):
            raise ValueError("directory-path-not-regular")
    descriptor = -1
    opened_index = -1
    for index, prefix in enumerate(prefixes):
        try:
            descriptor = os.open(prefix, _DIR_FLAGS)
        except PermissionError:
            continue
        opened_index = index
        break
    if descriptor < 0:
        raise PermissionError("no accessible directory ancestor")
    try:
        for component in absolute.parts[opened_index + 1:]:
            next_descriptor = os.open(component, _DIR_FLAGS, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = next_descriptor
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


def _read_leaf(directory_fd: int, name: str, limit: int, too_large: str, not_regular: str) -> bytes:
    descriptor = os.open(name, _READ_FLAGS, dir_fd=directory_fd)
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode):
            raise ValueError(not_regular)
        if info.st_size > limit:
            raise ValueError(too_large)
        with os.fdopen(descriptor, "rb", closefd=False) as source:
            data = source.read(limit + 1)
    finally:
        os.close(descriptor)
    if len(data) > limit:
        raise ValueError(too_large)
    return data


def _read_status(validation_fd: int) -> dict[str, Any]:
    try:
        raw = _read_leaf(validation_fd, "status.json", MAX_STATUS_BYTES,
                         "validation-status-unavailable", "validation-status-unavailable")
        value = json.loads(raw)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        raise ValueError("validation-status-unavailable") from exc
    if not isinstance(value, dict):
        raise ValueError("validation-status-unavailable")
    return value


def _read_python_log(validation_fd: int) -> bytes:
    try:
        stages_fd = os.open("stages", _DIR_FLAGS, dir_fd=validation_fd)
    except FileNotFoundError as exc:
        raise ValueError("python-log-missing") from exc
    except OSError as exc:
        raise ValueError("python-log-not-regular") from exc
    try:
        try:
            return _read_leaf(stages_fd, "python-tests.log", MAX_LOG_BYTES,
                              "python-log-too-large", "python-log-not-regular")
        except FileNotFoundError as exc:
            raise ValueError("python-log-missing") from exc
        except OSError as exc:
            if exc.errno in (errno.ELOOP, errno.ENOTDIR):
                raise ValueError("python-log-not-regular") from exc
            raise ValueError("python-log-unreadable") from exc
    finally:
        os.close(stages_fd)


def _write_exclusive(directory_fd: int, name: str, data: bytes) -> None:
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(name, flags, 0o600, dir_fd=directory_fd)
    try:
        with os.fdopen(descriptor, "wb", closefd=False) as destination:
            destination.write(data)
            destination.flush()
            os.fsync(destination.fileno())
    except BaseException:
        os.close(descriptor)
        try:
            os.unlink(name, dir_fd=directory_fd)
        except FileNotFoundError:
            pass
        raise
    else:
        os.close(descriptor)


def _failed_public_result(validation_output: Path) -> tuple[dict[str, Any], bytes | None]:
    try:
        validation_fd = _open_directory_chain(validation_output)
    except (OSError, ValueError):
        return {"schema": 1, "state": "unavailable", "reason": "validation-status-unavailable"}, None
    try:
        try:
            run_status = _read_status(validation_fd)
        except ValueError:
            return {"schema": 1, "state": "unavailable", "reason": "validation-status-unavailable"}, None
        if run_status.get("profile") != "public":
            return {"schema": 1, "state": "unavailable", "reason": "public-profile-required"}, None
        stages = run_status.get("stages")
        python_stage = next((stage for stage in stages if isinstance(stage, dict)
                             and stage.get("name") == "python-tests"), None) if isinstance(stages, list) else None
        if not isinstance(python_stage, dict) or python_stage.get("state") != "failed":
            return {"schema": 1, "state": "not-applicable", "reason": "python-stage-not-failed"}, None
        try:
            data = _read_python_log(validation_fd)
        except ValueError as exc:
            return {"schema": 1, "state": "unavailable", "reason": str(exc)}, None
        return {"schema": 1, "state": "collected", "reason": "python-stage-failed",
                "log_bytes": len(data)}, data
    finally:
        os.close(validation_fd)


def collect(validation_output: Path, diagnostic_output: Path) -> dict[str, Any]:
    """Collect into a new exclusively claimed directory; never reuse prior artifacts."""
    diagnostic = _absolute(Path(diagnostic_output))
    parent_fd = -1
    output_fd = -1
    owned_directory = False
    owned_files: list[str] = []
    try:
        parent_fd = _open_directory_chain(diagnostic.parent)
        os.mkdir(diagnostic.name, 0o700, dir_fd=parent_fd)
        owned_directory = True
        output_fd = os.open(diagnostic.name, _DIR_FLAGS, dir_fd=parent_fd)
        result, log_data = _failed_public_result(Path(validation_output))
        if log_data is not None:
            _write_exclusive(output_fd, "python-tests.log", log_data)
            owned_files.append("python-tests.log")
        status_data = (json.dumps(result, sort_keys=True) + "\n").encode("utf-8")
        _write_exclusive(output_fd, "status.json", status_data)
        owned_files.append("status.json")
        return result
    except BaseException:
        if output_fd >= 0:
            for name in owned_files:
                try:
                    os.unlink(name, dir_fd=output_fd)
                except FileNotFoundError:
                    pass
        if output_fd >= 0:
            os.close(output_fd)
            output_fd = -1
        if owned_directory and parent_fd >= 0:
            try:
                os.rmdir(diagnostic.name, dir_fd=parent_fd)
            except OSError:
                pass
        raise
    finally:
        if output_fd >= 0:
            os.close(output_fd)
        if parent_fd >= 0:
            os.close(parent_fd)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--validation-output", required=True, type=Path)
    parser.add_argument("--diagnostic-output", required=True, type=Path)
    arguments = parser.parse_args()
    try:
        result = collect(arguments.validation_output, arguments.diagnostic_output)
    except (OSError, ValueError) as exc:
        print(f"public Python diagnostics unavailable: {type(exc).__name__}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
