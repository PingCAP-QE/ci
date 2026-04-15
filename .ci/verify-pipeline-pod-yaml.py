#!/usr/bin/env python3

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


EMPTY_VOLUMES_RE = re.compile(
    r"^(?P<indent>\s*)volumes:\s*(?P<inline>null|~|\[\])?\s*(?:#.*)?$"
)


def iter_yaml_files(paths: list[str]) -> list[Path]:
    if paths:
        candidates = [Path(path) for path in paths]
    else:
        candidates = [Path("pipelines")]

    yaml_files: list[Path] = []
    for candidate in candidates:
        if candidate.is_dir():
            yaml_files.extend(sorted(candidate.rglob("*.yaml")))
            yaml_files.extend(sorted(candidate.rglob("*.yml")))
            continue
        if candidate.suffix in {".yaml", ".yml"}:
            yaml_files.append(candidate)

    # Preserve order while deduplicating.
    return list(dict.fromkeys(yaml_files))


def find_empty_volumes(path: Path) -> list[str]:
    problems: list[str] = []
    lines = path.read_text(encoding="utf-8").splitlines()

    for index, line in enumerate(lines):
        match = EMPTY_VOLUMES_RE.match(line)
        if not match:
            continue

        inline_value = match.group("inline")
        if inline_value is not None:
            problems.append(
                f"{path}:{index + 1}: spec.volumes must be omitted when empty, "
                f"not set to {inline_value!r}"
            )
            continue

        current_indent = len(match.group("indent"))
        next_index = index + 1
        while next_index < len(lines):
            next_line = lines[next_index]
            stripped = next_line.strip()
            if not stripped or stripped.startswith("#"):
                next_index += 1
                continue

            next_indent = len(next_line) - len(next_line.lstrip(" "))
            if next_line.lstrip().startswith("- "):
                break

            if next_indent <= current_indent:
                problems.append(
                    f"{path}:{index + 1}: spec.volumes must be omitted when empty, "
                    "not left as a dangling key"
                )
            else:
                problems.append(
                    f"{path}:{index + 1}: spec.volumes must remain a YAML sequence, "
                    "not another nested mapping"
                )
            break
        else:
            problems.append(
                f"{path}:{index + 1}: spec.volumes must be omitted when empty, "
                "not left at end of file"
            )

    return problems


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Verify pipeline pod YAML files do not keep empty spec.volumes keys."
    )
    parser.add_argument(
        "paths",
        nargs="*",
        help="Optional YAML files or directories to scan. Defaults to pipelines/.",
    )
    args = parser.parse_args()

    yaml_files = iter_yaml_files(args.paths)
    if not yaml_files:
        print("No YAML files found to verify.")
        return 0

    problems: list[str] = []
    for path in yaml_files:
        problems.extend(find_empty_volumes(path))

    if problems:
        print("Found pipeline pod YAML files with empty spec.volumes nodes:")
        for problem in problems:
            print(f"  - {problem}")
        return 1

    print(
        f"Checked {len(yaml_files)} pipeline YAML files: "
        "no empty spec.volumes nodes found."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
