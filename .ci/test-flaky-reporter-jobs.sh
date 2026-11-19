#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG="${1:-${ROOT_DIR}/prow-jobs/pingcap-qe/ci/periodics.yaml}"

# Resolve YAML aliases, then compare each PD job with its TiDB counterpart.
yq -o=json 'explode(.)' "${CONFIG}" | jq -e '
  def job($name):
    [.periodics[] | select(.name == $name)]
    | if length == 1 then .[0] else error("missing or duplicate job: " + $name) end;
  def env($container):
    $container.env | map({key: .name, value: del(.name)}) | from_entries;

  . as $config
  | [
      ["", "master", "10 0 * * *"],
      ["-v85", "release-8.5", "20 0 * * *"],
      ["-ng2609", "release-nextgen-202609", "30 0 * * *"],
      ["-ng2603", "release-nextgen-202603", "40 0 * * *"],
      ["-ng2510", "release-nextgen-20251011", "50 0 * * *"]
    ]
  | all(.[];
      . as [$suffix, $branch, $cron]
      | ($config | job("periodic-reporter-flaky-tests-tidb" + $suffix)) as $tidb
      | ($config | job("periodic-reporter-flaky-tests-pd" + $suffix)) as $pd
      | $tidb.spec.containers[0] as $tidb_container
      | $pd.spec.containers[0] as $pd_container
      | env($tidb_container) as $tidb_env
      | env($pd_container) as $pd_env
      | if (
          $pd.cron == $cron
          and $pd.cluster == $tidb.cluster
          and $pd.decorate == $tidb.decorate
          and $pd.spec.activeDeadlineSeconds == $tidb.spec.activeDeadlineSeconds
          and ($pd.spec.containers | length) == 1
          and ($pd_container | del(.env)) == ($tidb_container | del(.env))
          and ($pd_container.env | length) == ($pd_env | length)
          and $tidb_env.PARAM_REPO.value == "pingcap/tidb"
          and ($tidb_env.PARAM_ISSUE_LABELS.value | split(",") | index("component/test")) != null
          and $pd_env.PARAM_REPO.value == "tikv/pd"
          and $pd_env.PARAM_BRANCH.value == $branch
          and ($pd_env | del(.PARAM_REPO, .PARAM_ISSUE_LABELS))
            == ($tidb_env | del(.PARAM_REPO, .PARAM_ISSUE_LABELS))
          and ($pd_env.PARAM_ISSUE_LABELS.value | split(",") | sort)
            == ($tidb_env.PARAM_ISSUE_LABELS.value | split(",") | map(select(. != "component/test")) | sort)
        ) then true else error("PD/TiDB reporter mismatch: " + $branch) end
    )
' >/dev/null

echo "PASS: all five PD reporters reuse the TiDB runner and branch policies"
