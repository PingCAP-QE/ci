#!/usr/bin/env python3
"""Check CSE policies/mail and execute the real wrapper with offline stubs."""

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
CASES = [
    ("", "cloud-engine", "55 0 * * *"),
    ("-ng2609", "release-nextgen-202609", "5 1 * * *"),
    ("-ng2603", "release-nextgen-202603", "15 1 * * *"),
    ("-ng2510", "release-nextgen-20251011", "25 1 * * *"),
]
MAIL_VARS = ["PARAM_EMAIL_TO", "PARAM_EMAIL_CC", "SMTP_SECURE", "SMTP_HOST",
             "SMTP_PORT", "SMTP_USER", "SMTP_PASS"]
MARKER = "SYNTHETIC_PRIVATE_CASE_DO_NOT_PUBLISH"


def job(repo, suffix):
    matches = [j for j in JOBS if j["name"] == f"periodic-reporter-flaky-tests-{repo}{suffix}"]
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


assert {j["name"] for j in JOBS if j["name"].startswith("periodic-reporter-flaky-tests-cse")} == {
    "periodic-reporter-flaky-tests-cse" + suffix for suffix, _, _ in CASES
}, "unexpected CSE branch scope"
base = job("cse", "")["spec"]["containers"][0]
for suffix, branch, cron in CASES:
    cse, tidb = job("cse", suffix), job("tidb", suffix)
    container = cse["spec"]["containers"][0]
    cse_env, tidb_env = env(container), env(tidb["spec"]["containers"][0])
    assert cse["cluster"] == tidb["cluster"] == "tke-pingcap-cicd"
    assert cse["hidden"] and cse["decorate"]
    assert cse["cron"] == cron and cse["max_concurrency"] == 1
    assert cse["spec"]["activeDeadlineSeconds"] == 300
    assert len(cse["spec"]["containers"]) == 1
    assert {k: v for k, v in container.items() if k != "env"} == {
        k: v for k, v in base.items() if k != "env"
    }, "CSE branches must share the same private-output wrapper"
    assert container["image"] == tidb["spec"]["containers"][0]["image"]
    assert cse_env["PARAM_BRANCH"]["value"] == branch
    assert set(cse_env["PARAM_ISSUE_OPS_OPTIONS"]["value"].split()) == set(
        tidb_env["PARAM_ISSUE_OPS_OPTIONS"]["value"].split()
    )
    assert set(cse_env["PARAM_ISSUE_LABELS"]["value"].split(",")) == set(
        tidb_env["PARAM_ISSUE_LABELS"]["value"].split(",")
    )
    for name in MAIL_VARS:
        assert cse_env[name] == tidb_env[name], (branch, name, "mail settings differ from TiDB")
    assert cse_env["DB_URL"] == tidb_env["PARAM_DSN"]
    assert cse_env["GITHUB_TOKEN"] == tidb_env["GITHUB_TOKEN"]

    script = container["args"][0]
    assert ">/tmp/cse-flaky-reporter.log 2>&1" in script
    assert "--html /tmp/cse-flaky-report.html" in script
    subprocess.run(["bash", "-n"], input=script, text=True, check=True)
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
                           for name, item in cse_env.items()}
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
            assert flag(args, "--issue-labels") == cse_env["PARAM_ISSUE_LABELS"]["value"]
            assert flag(args, "--html") == str(private_html)
            assert flag(args, "--email-to") == "to@example.invalid"
            assert flag(args, "--email-cc") == "cc@example.invalid"
            assert flag(args, "--email-from") == "from@example.invalid"
            assert branch in flag(args, "--email-subject")
            assert flag(args, "--from") == "2026-11-18" and flag(args, "--to") == "2026-11-19"
            assert flag(args, "--issue-mutation-limit") == "10000"
            assert {arg for arg in args if arg in {
                "--issue-create", "--issue-comment", "--issue-reopen"
            }} == set(cse_env["PARAM_ISSUE_OPS_OPTIONS"]["value"].split())
    print(f"PASS: {branch}: TiDB policy/mail parity; success/failure output stays private")
