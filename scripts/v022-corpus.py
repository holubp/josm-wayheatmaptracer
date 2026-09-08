#!/usr/bin/env python3
"""Command-line entry point for strict v0.22 private-corpus handling."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from wayheatmap_analysis.v022_corpus import CorpusError, assert_no_credentials, inventory, render_report, verify


def parser() -> argparse.ArgumentParser:
    """Build the stable inventory, verify, and report CLI."""

    result = argparse.ArgumentParser()
    commands = result.add_subparsers(dest="command", required=True)
    inventory_command = commands.add_parser("inventory")
    inventory_command.add_argument("--inputs", type=Path, required=True)
    inventory_command.add_argument("--output", type=Path, required=True)
    inventory_command.add_argument("--require-reference-set", action="store_true")
    verify_command = commands.add_parser("verify")
    verify_command.add_argument("--manifest", type=Path, required=True)
    report_command = commands.add_parser("report")
    report_command.add_argument("--manifest", type=Path, required=True)
    report_command.add_argument("--results", type=Path, required=True)
    report_command.add_argument("--output", type=Path, required=True)
    return result


def main(argv: list[str] | None = None) -> int:
    """Execute one command and return a process exit status."""

    arguments = parser().parse_args(argv)
    try:
        if arguments.command == "inventory":
            value = inventory(arguments.inputs, arguments.require_reference_set)
            output = json.dumps(value, indent=2, sort_keys=True) + "\n"
        elif arguments.command == "verify":
            value = verify(arguments.manifest)
            output = json.dumps(value, indent=2, sort_keys=True) + "\n"
            print(output, end="")
            return 0
        else:
            output = render_report(arguments.manifest, arguments.results)
        assert_no_credentials(output)
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        arguments.output.write_text(output, encoding="utf-8")
        return 0
    except CorpusError as error:
        print(f"v0.22 corpus error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
