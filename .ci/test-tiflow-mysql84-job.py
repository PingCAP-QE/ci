#!/usr/bin/env python3
"""Offline contract checks for the optional DM MySQL 8.4 staging job (needs yq)."""

import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parent.parent
JOB = "pingcap/tiflow/staging/pull_dm_integration_test_mysql84"
CONTEXT = "pull-dm-integration-test-mysql84"
JOB_DIR = ROOT / "jenkins/jobs" / JOB
BASE_DIR = ROOT / "jenkins/jobs/pingcap/tiflow/latest/pull_dm_integration_test"


def yaml(path):
    return json.loads(subprocess.check_output(
        ["yq", "-o=json", "explode(.)", str(path)], text=True
    ))


jobs = yaml(ROOT / "prow-jobs/pingcap/tiflow/latest-presubmits.yaml")["presubmits"]["pingcap/tiflow"]
matches = [job for job in jobs if job["name"] == JOB]
assert len(matches) == 1, "missing or duplicate staging job"
trial = matches[0]
assert trial["context"] == CONTEXT
assert trial["optional"] is True
assert trial["always_run"] is False
assert trial["run_before_merge"] is False
assert not trial.get("skip_report", False), "keep failures visible"
assert "run_if_changed" not in trial and "skip_if_only_changed" not in trial
assert trial["branches"] == ["^master$"]
assert trial["agent"] == "jenkins" and trial["labels"]["master"] == "0"
for command, expected in [
    (f"/test {CONTEXT}", True),
    (f"/test {CONTEXT}\n", True),
    ("/test all", False),
    ("/test pull-dm-integration-test", False),
    (f"/test {CONTEXT}-other", False),
]:
    assert bool(re.search(trial["trigger"], command)) == expected, command
assert trial["rerun_command"] == f"/test {CONTEXT}"
assert sum(job.get("context") == CONTEXT for job in jobs) == 1
required = next(job for job in jobs if job["name"] == "pingcap/tiflow/pull_dm_integration_test")
assert not required.get("optional", False) and required["run_before_merge"]

dsl = (JOB_DIR / "dsl.groovy").read_text()
pipeline = (JOB_DIR / "Jenkinsfile").read_text()
assert f"pipelineJob('{JOB}')" in dsl
assert f'jenkins/jobs/{JOB}/Jenkinsfile' in dsl
assert f'jenkins/jobs/{JOB}/pod.yaml' in pipeline
assert 'matrixCache.' not in pipeline, "manual trials must run every group"
assert 'parallelsAlwaysFailFast()' not in pipeline
assert "'dm-integration-test-mysql84'" in pipeline
assert 'VERSION_WITH_UPDATE()' in pipeline
assert 'make dm_integration_test_in_group GROUP="${TEST_GROUP}"' in pipeline
groups = re.findall(r"'(G\d\d|TLS_GROUP)'", pipeline)
assert groups == [f"G{i:02d}" for i in range(12)] + ["TLS_GROUP"]

pod = yaml(JOB_DIR / "pod.yaml")
baseline = yaml(BASE_DIR / "pod.yaml")
containers = {c["name"]: c for c in pod["spec"]["containers"]}
original = {c["name"]: c for c in baseline["spec"]["containers"]}
for name in original.keys() - {"mysql2"}:
    assert containers[name] == original[name], f"unexpected change to {name}"
mysql2 = containers["mysql2"]
assert mysql2["image"] == (
    "docker.io/library/mysql:8.4.13.1@sha256:"
    "8c233bad33d520a7aae1c1e619c3a9775f4a1ba2d463d692fada04ec7f40be00"
)
assert original["mysql2"]["image"] == "docker.io/library/mysql:8.0.21"
assert mysql2["env"] == original["mysql2"]["env"]
assert "--ssl=ON" not in mysql2["args"]
assert not any(arg.startswith("--default-authentication-plugin") for arg in mysql2["args"])
for arg in ["--log-bin", "--binlog-format=ROW", "--gtid-mode=ON",
            "--enforce-gtid-consistency=ON", "--tls-version=TLSv1.2,TLSv1.3",
            "--mysql-native-password=ON", "--authentication-policy=*:mysql_native_password,,"]:
    assert arg in mysql2["args"], arg
for container in pod["spec"]["containers"] + pod["spec"]["initContainers"]:
    resources = container["resources"]
    assert resources["requests"] == resources["limits"], container["name"]

print("DM MySQL 8.4 staging job: all offline contract checks passed")
