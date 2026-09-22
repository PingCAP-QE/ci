#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECKER="${ROOT_DIR}/.ci/check-jenkins-job-references.sh"

if [[ ! -f "${CHECKER}" ]]; then
  echo "checker not found: ${CHECKER}" >&2
  exit 1
fi

failures=0

# make_new_job <root> <org> <repo> <branch> <job> <pod:yes|no|missing>
make_new_job() {
  local root="$1" org="$2" repo="$3" branch="$4" job="$5" pod="$6"
  local jdir="${root}/jenkins/jobs/${org}/${repo}/${branch}/${job}"
  mkdir -p "${jdir}"

  cat >"${jdir}/dsl.groovy" <<EOF
final fullRepo = '${org}/${repo}'
final branchAlias = '${branch}'
final jobName = '${job}'
pipelineJob("\${fullRepo}/\${jobName}") {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath("jenkins/jobs/\${fullRepo}/\${branchAlias}/\${jobName}/Jenkinsfile")
        }
    }
}
EOF

  {
    echo "final GIT_FULL_REPO_NAME = '${org}/${repo}'"
    echo "final BRANCH_ALIAS = '${branch}'"
    if [[ "${pod}" != "no" ]]; then
      # shellcheck disable=SC2016
      echo 'final POD_TEMPLATE_FILE = "jenkins/jobs/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod.yaml"'
    fi
    echo "pipeline { agent { kubernetes {} } }"
  } >"${jdir}/Jenkinsfile"

  if [[ "${pod}" == "yes" ]]; then
    cat >"${jdir}/pod.yaml" <<'EOF'
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: main
      image: busybox
EOF
  fi
}

# make_legacy_job <root> <org> <repo> <branch> <job>
make_legacy_job() {
  local root="$1" org="$2" repo="$3" branch="$4" job="$5"
  mkdir -p "${root}/jobs/${org}/${repo}/${branch}" "${root}/pipelines/${org}/${repo}/${branch}"

  cat >"${root}/jobs/${org}/${repo}/${branch}/${job}.groovy" <<EOF
final jobName = '${job}'
pipelineJob('${org}/${repo}/${job}') {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath("pipelines/${org}/${repo}/${branch}/${job}.groovy")
        }
    }
}
EOF

  cat >"${root}/pipelines/${org}/${repo}/${branch}/${job}.groovy" <<EOF
final GIT_FULL_REPO_NAME = '${org}/${repo}'
final BRANCH_ALIAS = '${branch}'
final POD_TEMPLATE_FILE = "pipelines/${org}/${repo}/${branch}/pod-${job}.yaml"
pipeline { agent { kubernetes {} } }
EOF

  cat >"${root}/pipelines/${org}/${repo}/${branch}/pod-${job}.yaml" <<'EOF'
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: main
      image: busybox
EOF
}

setup_case() {
  local name="$1" root="$2"
  case "${name}" in
    valid_new_layout)
      make_new_job "${root}" acme widget latest build yes
      ;;
    job_without_pod)
      make_new_job "${root}" acme widget latest build no
      ;;
    dangling_scriptpath)
      local jdir="${root}/jenkins/jobs/acme/widget/latest/build"
      mkdir -p "${jdir}"
      cat >"${jdir}/dsl.groovy" <<'EOF'
final fullRepo = 'acme/widget'
final branchAlias = 'latest'
final jobName = 'build'
pipelineJob("${fullRepo}/${jobName}") {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath("jenkins/jobs/${fullRepo}/${branchAlias}/${jobName}/Jenkinsfile")
        }
    }
}
EOF
      ;;
    dangling_pod)
      make_new_job "${root}" acme widget latest build missing
      ;;
    orphan_artifact)
      make_new_job "${root}" acme widget latest build yes
      mkdir -p "${root}/jenkins/jobs/acme/widget/latest/extra"
      cat >"${root}/jenkins/jobs/acme/widget/latest/extra/pod-unused.yaml" <<'EOF'
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: main
      image: busybox
EOF
      ;;
    legacy_layout)
      make_legacy_job "${root}" acme widget latest build
      ;;
    duplicate_job)
      make_new_job "${root}" acme widget latest build yes
      make_legacy_job "${root}" acme widget latest build
      ;;
    missing_jenkinsfile)
      make_new_job "${root}" acme widget latest build yes
      rm "${root}/jenkins/jobs/acme/widget/latest/build/Jenkinsfile"
      ;;
    *)
      echo "unknown case: ${name}" >&2
      exit 1
      ;;
  esac
}

run_checker() {
  local root="$1"
  shift
  set +e
  CHECK_OUT="$(bash "${CHECKER}" --root "${root}" "$@" 2>&1)"
  CHECK_RC=$?
  set -e
}

check_case() {
  local name="$1" expect_rc="$2"
  shift 2

  local tmp
  tmp="$(mktemp -d)"
  setup_case "${name}" "${tmp}"

  run_checker "${tmp}" "$@"
  local rc="${CHECK_RC}" out="${CHECK_OUT}"
  rm -rf "${tmp}"

  if [[ "${rc}" -eq "${expect_rc}" ]]; then
    echo "[PASS] ${name} (rc=${rc})"
  else
    echo "[FAIL] ${name} (expect rc=${expect_rc}, got rc=${rc})" >&2
    echo "${out}" >&2
    failures=$((failures + 1))
  fi
}

check_case valid_new_layout 0
check_case job_without_pod 0
check_case dangling_scriptpath 1
check_case dangling_pod 1
check_case legacy_layout 0
check_case duplicate_job 1
check_case missing_jenkinsfile 1
check_case orphan_artifact 0
check_case orphan_artifact 1 --strict

if [[ "${failures}" -ne 0 ]]; then
  echo "${failures} checker test case(s) failed." >&2
  exit 1
fi

echo "All Jenkins job reference-checker tests passed."
