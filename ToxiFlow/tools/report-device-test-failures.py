#!/usr/bin/env python3
"""Expose failing Android instrumentation tests as GitHub Actions annotations.

Run after a failed emulator job; never mask its original exit status. The XML
results remain available as artifacts for complete stack traces.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import xml.etree.ElementTree as ET


def escape_command_data(text: str) -> str:
    """Escape data for a GitHub Actions workflow command, including newlines."""
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def failures(results: Path) -> tuple[list[str], int]:
    messages: list[str] = []
    files = sorted(results.rglob("*.xml")) if results.is_dir() else []
    for file in files:
        try:
            root = ET.parse(file).getroot()
        except (ET.ParseError, OSError) as error:
            print(f"Could not read Android test result {file}: {error}")
            continue
        for case in root.iter():
            if local_name(case.tag) != "testcase":
                continue
            label = f"{case.get('classname', 'Android test')}#{case.get('name', '(unnamed)')}"
            for problem in case:
                if local_name(problem.tag) not in ("failure", "error"):
                    continue
                detail = "".join(problem.itertext()).strip() or problem.get("message", "No failure details")
                messages.append(f"{label}\n{detail}")
    return messages, len(files)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", nargs="?", type=Path,
                        default=Path("ToxiFlow/app/build/outputs/androidTest-results/connected"))
    args = parser.parse_args()
    messages, file_count = failures(args.results)
    if not file_count:
        print(f"No Android test result XML found in {args.results}; inspect the emulator job log.")
    elif not messages:
        print(f"No failing test cases found in {file_count} Android test result file(s).")
    else:
        print(f"Found {len(messages)} failing Android test(s) in {file_count} result file(s).")
        # Limit annotation count/size; full reports and XML are still uploaded as artifacts.
        for message in messages[:10]:
            if len(message) > 6000:
                message = message[:6000] + "\n... truncated; see the full test report artifact."
            print("::error title=Android device test failed::" + escape_command_data(message))
        if len(messages) > 10:
            print(f"{len(messages) - 10} additional failure(s) are in the test report artifact.")


if __name__ == "__main__":
    main()
