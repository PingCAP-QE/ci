#!/usr/bin/env python3
"""Check all flaky reporter profiles and exercise CSE privacy with offline stubs."""

import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
CONFIG = os.environ.get(
    "PROW_FLAKY_REPORTER_CONFIG", str(ROOT / "prow-jobs/pingcap-qe/ci/periodics.yaml")
)
JOBS = json.loads(subprocess.check_output(
    ["yq", "-o=json", "explode(.)", CONFIG], text=True
))["periodics"]
BRANCHES = [
    ("", "master", ""),
    ("-v85", "release-8.5", ",affects-8.5"),
    ("-ng2609", "release-nextgen-202609", ",affects-26.9"),
    ("-ng2603", "release-nextgen-202603", ",affects-26.3"),
    ("-ng2510", "release-nextgen-20251011", ",affects-25.10"),
]
# Profile: job suffix, repository, branches, UTC crons, base issue labels.
PROFILES = [
    ("tidb", "pingcap/tidb", BRANCHES,
     ["5 0", "15 0", "25 0", "35 0", "45 0"], "component/test,flaky-test,type/enhancement"),
    ("pd", "tikv/pd", BRANCHES,
     ["10 0", "20 0", "30 0", "40 0", "50 0"], "flaky-test,type/enhancement"),
    ("cse", None, [("", "cloud-engine", ""), *BRANCHES[2:]],
     ["55 0", "5 1", "15 1", "25 1"], "component/test,flaky-test,type/enhancement"),
]
MAIL_VARS = ["PARAM_EMAIL_TO", "PARAM_EMAIL_CC", "SMTP_SECURE", "SMTP_HOST",
             "SMTP_PORT", "SMTP_USER", "SMTP_PASS", "GITHUB_TOKEN"]
MARKER = "SYNTHETIC_PRIVATE_CASE_DO_NOT_PUBLISH"
PREFIX = "periodic-reporter-flaky-tests-"


def job(repo, suffix):
    matches = [j for j in JOBS if j["name"] == PREFIX + repo + suffix]
    assert len(matches) == 1, (repo, suffix, "missing or duplicate job")
    return matches[0]


def env(container):
    values = {item["name"]: {k: v for k, v in item.items() if k != "name"}
              for item in container["env"]}
    assert len(values) == len(container["env"]), "duplicate environment variable"
    return values


def flag(args, name):
    values = [args[i + 1] for i, arg in enumerate(args) if arg == name]
    values += [arg.split("=", 1)[1] for arg in args if arg.startswith(name + "=")]
    assert len(values) == 1, (name, "missing or duplicate option")
    return values[0]


assert {j["name"] for j in JOBS if j["name"].startswith(PREFIX)} == {
    PREFIX + repo + suffix for repo, _, branches, _, _ in PROFILES for suffix, _, _ in branches
}, "unexpected reporter branch scope"
tidb_base = job("tidb", "")["spec"]["containers"][0]
shared_env = env(tidb_base)
for repo, repository, branches, crons, labels in PROFILES:
    base = job(repo, "")["spec"]["containers"][0]
    assert len(branches) == len(crons)
    for (suffix, branch, affects), cron in zip(branches, crons):
        reporter = job(repo, suffix)
        container = reporter["spec"]["containers"][0]
        values = env(container)
        assert reporter["cluster"] == "tke-pingcap-cicd" and reporter["decorate"]
        assert reporter["cron"] == cron + " * * *"
        assert reporter["spec"]["activeDeadlineSeconds"] == 300
        assert len(reporter["spec"]["containers"]) == 1
        assert {k: v for k, v in container.items() if k != "env"} == {
            k: v for k, v in base.items() if k != "env"
        }, (repo, branch, "runner differs across branches")
        assert container["image"] == tidb_base["image"]
        assert values["PARAM_BRANCH"]["value"] == branch
        operations = ["--issue-create", "--issue-comment"] + (["--issue-reopen"] if not suffix else [])
        assert sorted(values["PARAM_ISSUE_OPS_OPTIONS"]["value"].split()) == sorted(operations)
        assert sorted(values["PARAM_ISSUE_LABELS"]["value"].split(",")) == sorted((labels + affects).split(","))
        for name in MAIL_VARS:
            assert values[name] == shared_env[name], (repo, branch, name, "shared settings differ")
        assert values["DB_URL" if repo == "cse" else "PARAM_DSN"] == shared_env["PARAM_DSN"]
        script = container["args"][0]
        subprocess.run(["bash", "-n"], input=script, text=True, check=True)
        if repo != "cse":
            assert values["PARAM_REPO"]["value"] == repository
            assert values["PARAM_CASE_THRESHOLD_MS"]["value"] == "100000"
            assert values["ISSUE_MUTATION_LIMIT"]["value"] == "10000"
            tidb_container = job("tidb", suffix)["spec"]["containers"][0]
            assert {k: v for k, v in container.items() if k != "env"} == {
                k: v for k, v in tidb_container.items() if k != "env"
            }, "PD must reuse the TiDB runner"
            excluded = {"PARAM_REPO", "PARAM_ISSUE_LABELS"}
            assert {k: v for k, v in values.items() if k not in excluded} == {
                k: v for k, v in env(tidb_container).items() if k not in excluded
            }, "PD/TiDB environment mismatch"
            continue
        assert reporter["hidden"] and reporter["max_concurrency"] == 1
        assert ">/tmp/cse-flaky-reporter.log 2>&1" in script
        assert "--html /tmp/cse-flaky-report.html" in script
        for exit_code in (0, 19):
            with tempfile.TemporaryDirectory(prefix="cse-flaky-test-", dir="/tmp") as directory:
                work = Path(directory)
                for name, body in {
                    "date": "import sys; print('2026-11-18' if '-d' in sys.argv else '2026-11-19')",
                    "deno": """import json, os, pathlib, sys
if sys.argv[1] == 'eval':
    print('example/renamed-private-repo')
else:
    args = sys.argv[1:]
    pathlib.Path(os.environ['STUB_ARGS']).write_text(json.dumps(args))
    pathlib.Path(args[args.index('--html') + 1]).write_text(os.environ['MARKER'])
    print(os.environ['MARKER'])
    print(os.environ['MARKER'], file=sys.stderr)
    sys.exit(int(os.environ['STUB_EXIT']))
""",
                }.items():
                    executable = work / name
                    executable.write_text("#!/usr/bin/env python3\n" + body + "\n")
                    executable.chmod(0o755)
                runtime_env = {name: item.get("value", "synthetic-secret")
                               for name, item in values.items()}
                runtime_env.update(PATH=str(work) + os.pathsep + os.environ["PATH"],
                                   STUB_ARGS=str(work / "args.json"), STUB_EXIT=str(exit_code),
                                   MARKER=MARKER, PARAM_EMAIL_TO="to@example.invalid",
                                   PARAM_EMAIL_CC="cc@example.invalid", SMTP_USER="from@example.invalid")
                private_log, private_html = work / "reporter.log", work / "report.html"
                script_under_test = script.replace("/tmp/cse-flaky-reporter.log", str(private_log)).replace(
                    "/tmp/cse-flaky-report.html", str(private_html)
                )
                result = subprocess.run(container["command"] + [script_under_test],
                                        env=runtime_env, text=True, capture_output=True)
                assert result.returncode == (0 if exit_code == 0 else 1), result.stderr
                assert MARKER not in result.stdout + result.stderr, "private output leaked"
                assert private_log.read_text().count(MARKER) == 2
                assert private_html.read_text() == MARKER
                args = json.loads((work / "args.json").read_text())
                assert "--allow-write=/tmp" in args
                assert flag(args, "--repo") == "example/renamed-private-repo"
                assert flag(args, "--branch") == branch
                assert flag(args, "--issue-labels") == values["PARAM_ISSUE_LABELS"]["value"]
                assert flag(args, "--html") == str(private_html)
                assert flag(args, "--email-to") == "to@example.invalid"
                assert flag(args, "--email-cc") == "cc@example.invalid"
                assert flag(args, "--email-from") == "from@example.invalid"
                assert branch in flag(args, "--email-subject")
                assert flag(args, "--from") == "2026-11-18" and flag(args, "--to") == "2026-11-19"
                assert flag(args, "--issue-mutation-limit") == "10000"
                assert {arg for arg in args if arg in {
                    "--issue-create", "--issue-comment", "--issue-reopen"
                }} == set(operations)
    print(f"PASS: {repo}: branch scope, runner, policy, and shared settings")
print("PASS: all 14 reporters; all 8 CSE success/failure runs keep private output pod-local")
