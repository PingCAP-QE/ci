"""Executable fixture installed as git and as the artifact script generator."""

import json
import os
from pathlib import Path
import shutil
import sys

root = Path(os.environ["STUB_ROOT"])
mode = os.environ["STUB_MODE"]
args = sys.argv[1:]
tool = "git" if Path(sys.argv[0]).name == "git" else "generator"
with (root / "calls.jsonl").open("a") as trace:
    trace.write(json.dumps([tool, args]) + "\n")

if tool == "git":
    if mode == "clone-failure":
        sys.exit(17)
    destination = root / "workspace/artifacts"
    # Fail rather than allowing an unexpected clone destination.
    assert args == [
        "clone", "--depth=1", "--branch=main",
        "https://github.com/PingCAP-QE/artifacts.git", str(destination),
    ], args
    scripts = destination / "packages/scripts"
    scripts.mkdir(parents=True)
    shutil.copyfile(__file__, scripts / "gen-package-artifacts-with-config.sh")
    (scripts / "gen-package-artifacts-with-config.sh").chmod(0o700)
else:
    assert len(args) == 10, args
    output = root / "workspace/build-package-artifacts.sh"
    assert args[8] == str(output), args
    if mode in ("generated", "partial-failure"):
        # This file must only be printed, never executed by the generate step.
        output.write_text("#!/bin/sh\nexit 99\n")
    if mode in ("generator-failure", "partial-failure"):
        sys.exit(23)
