"""Offline P1 characterization; run with Python 3, Mike Farah yq v4 and Bash."""

import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = Path(__file__).parent / "fixtures/generate-package-artifacts"
TOKEN = re.compile(r"\$\((params\.[^)]+|(?:step\.)?results\.[^)]+\.path)\)")


def read_yaml(path):
    return json.loads(subprocess.check_output(["yq", "-o=json", ".", str(path)]))


def substitute(value, bindings):
    # One pass: parameter values must not be recursively interpreted as Tekton syntax.
    return TOKEN.sub(lambda match: bindings[match[1]], value)


def execute(step, bindings, mode):
    with tempfile.TemporaryDirectory(prefix="tekton-generate-") as directory:
        root = Path(directory)
        for child in ("workspace", "results", "bin", "home"):
            (root / child).mkdir()
        replacements = dict(bindings, **{
            "results.pushed.path": str(root / "results/pushed"),
            "step.results.generated.path": str(root / "results/generated"),
        })
        if "params.pushed-result-path" in bindings:
            # The caller passes a Task result path, resolved before Action params.
            assert bindings["params.pushed-result-path"] == "$(results.pushed.path)"
            replacements["params.pushed-result-path"] = replacements["results.pushed.path"]

        def render(value):
            return substitute(value, replacements).replace("/workspace/", str(root / "workspace") + "/")

        # No inherited credentials, Git config, proxies or real Git on PATH.
        env = {
            "PATH": str(root / "bin"), "HOME": str(root / "home"),
            "LC_ALL": "C", "STUB_ROOT": str(root), "STUB_MODE": mode,
        }
        env.update({entry["name"]: render(entry["value"]) for entry in step.get("env", [])})
        git = root / "bin/git"
        git.write_text(f"#!{sys.executable}\n" + (FIXTURES / "tools.py").read_text())
        git.chmod(0o700)
        (root / "bin/cat").symlink_to(shutil.which("cat"))
        script = render(step["script"])
        if not script.lstrip().startswith("#!"):
            # Tekton Pipelines v1.3.1 pkg/pod/script.go defaultScriptPreamble.
            script = "#!/bin/sh\nset -e\n" + script
        script_file = root / "generate.sh"
        script_file.write_text(script)
        process = subprocess.run(
            [shutil.which("bash"), "--posix", str(script_file),
             *[render(arg) for arg in step.get("args", [])]],
            cwd=root, env=env, capture_output=True, text=True, timeout=10,
        )

        def content(path):
            file = root / path
            return file.read_text() if file.exists() else None

        trace = content("calls.jsonl") or ""
        return {
            "exit": process.returncode,
            "generated": content("results/generated"),
            "pushed": content("results/pushed"),
            "script": content("workspace/build-package-artifacts.sh"),
            "calls": json.loads("[" + ",".join(trace.replace(str(root), "<root>").splitlines()) + "]"),
            "stdout": process.stdout.replace(str(root), "<root>"),
            "stderr": process.stderr.replace(str(root), "<root>"),
        }


class GenerateContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tasks = {
            os: read_yaml(ROOT / f"tekton/v1/tasks/pingcap-build-binaries-{os}.yaml")
            for os in ("linux", "darwin")
        }
        cls.action = read_yaml(FIXTURES / "action.yaml")
        cls.reference = read_yaml(FIXTURES / "reference.yaml")

    def test_candidate_boundary(self):
        action = self.action["spec"]
        self.assertEqual(self.action["apiVersion"], "tekton.dev/v1beta1")
        self.assertEqual(self.reference["ref"]["name"], self.action["metadata"]["name"])
        self.assertEqual(set(self.reference), {"name", "ref", "params"})
        self.assertEqual(
            {p["name"] for p in self.reference["params"]},
            {p["name"] for p in action["params"]},
        )
        self.assertNotIn("$(params.", action["script"])
        for task in self.tasks.values():
            original = task["spec"]["steps"][0]
            self.assertEqual(original["name"], self.reference["name"])
            self.assertEqual(original["image"], action["image"])
            self.assertEqual(original["results"], action["results"])
            for step in task["spec"]["steps"][1:]:
                self.assertEqual(step["when"], [{
                    "input": "$(steps.generate.results.generated)",
                    "operator": "in", "values": ["true"],
                }])

    def test_delivery_accepts_existing_empty_result(self):
        for os in self.tasks:
            pipeline = read_yaml(ROOT / f"tekton/v1/pipelines/pingcap-build-package-{os}.yaml")
            delivery = next(t for t in pipeline["spec"]["tasks"] if t["name"] == "deliver-binaries")
            self.assertIn({
                "input": "$(tasks.build-binaries.results.pushed)",
                "operator": "notin", "values": ["{}", '"{}"'],
            }, delivery["when"])

    def test_behavior_table(self):
        cases = [
            ("default", {}, "generated"),
            ("arm64", {"arch": "arm64"}, "generated"),
            ("tag", {"git-ref": "v8.5.4", "version": "v8.5.4"}, "generated"),
            ("pull-request", {"git-ref": "pull/123/head"}, "generated"),
            ("sha-equals-ref", {"git-ref": "abc1234", "git-sha": "abc1234"}, "generated"),
            ("empty-sha", {"git-sha": ""}, "generated"),
            ("failpoint", {"profile": "failpoint"}, "generated"),
            ("fips", {"profile": "fips", "component": "pd"}, "generated"),
            ("registry", {"registry": "registry.invalid:5000/team/builds"}, "generated"),
            ("no-output", {}, "no-output"),
            ("clone-failure", {}, "clone-failure"),
            ("generator-failure", {}, "generator-failure"),
            ("partial-failure", {}, "partial-failure"),
        ]
        for os, task in self.tasks.items():
            for name, overrides, mode in cases:
                with self.subTest(os=os, case=name):
                    params = {p["name"]: p["default"] for p in task["spec"]["params"] if "default" in p}
                    params.update({
                        "component": "tidb", "version": "v8.5.3", "git-ref": "release-8.5",
                        "git-sha": "abc1234", "registry": "registry.invalid/test", **overrides,
                    })
                    task_bindings = {"params." + k: v for k, v in params.items()}
                    original = execute(task["spec"]["steps"][0], task_bindings, mode)
                    # Bind the actual candidate reference before evaluating the Action.
                    reference_bindings = dict(task_bindings, **{"results.pushed.path": "$(results.pushed.path)"})
                    action_bindings = {
                        "params." + p["name"]: substitute(p["value"], reference_bindings)
                        for p in self.reference["params"]
                    }
                    actual = execute(self.action["spec"], action_bindings, mode)
                    self.assertEqual(actual, original)
                    self.assert_golden(original, params, mode)

    def assert_golden(self, actual, params, mode):
        failed = mode.endswith("failure")
        self.assertEqual(actual["exit"], 17 if mode == "clone-failure" else 23 if failed else 0)
        self.assertEqual(actual["generated"], None if failed else "false" if mode == "no-output" else "true")
        self.assertEqual(actual["pushed"], '"{}"' if mode == "no-output" else None)
        self.assertEqual(actual["script"], "#!/bin/sh\nexit 99\n" if mode in ("generated", "partial-failure") else None)
        expected_calls = [["git", [
            "clone", "--depth=1", "--branch=main",
            "https://github.com/PingCAP-QE/artifacts.git", "<root>/workspace/artifacts",
        ]]]
        if mode != "clone-failure":
            expected_calls.append(["generator", [
                params["component"], params["os"], params["arch"], params["version"],
                params["profile"], params["git-ref"],
                "" if params["git-sha"] == params["git-ref"] else params["git-sha"],
                "<root>/workspace/artifacts/packages/packages.yaml.tmpl",
                "<root>/workspace/build-package-artifacts.sh", params["registry"],
            ]])
        self.assertEqual(actual["calls"], expected_calls)
        self.assertEqual(actual["stderr"], "")
        self.assertEqual(actual["stdout"], "" if failed else
                         "🤷 no output script generated!\n" if mode == "no-output" else
                         "#!/bin/sh\nexit 99\n")


if __name__ == "__main__":
    unittest.main(verbosity=2)
