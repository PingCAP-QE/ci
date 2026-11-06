#!/usr/bin/env bash
set -euo pipefail

# Credential-hygiene policy for the CI trees that can reach a token.
#
# Every tree below handles credentials or pod/job definitions that receive
# them, so each is in scope:
#   .ci/          maintenance scripts (some run with CI credentials)
#   dockerfiles/  images that bake in tools and env vars
#   jenkins/      Jenkinsfiles + Job DSL (the pipelines that bind credentials)
#   libraries/    shared pipeline code (askpass helpers, credential binding)
#   prow-jobs/    Prow job definitions (env valueFrom/secretKeyRef)
#   scripts/      helper scripts run from CI
#   tekton/       Tekton Tasks/Pipelines/Triggers (param defaults, env values)
#
# FAIL-CLOSED allowlist: the allowlist is a reviewed file in this repository.
# It is resolved from the repository root and is deliberately NOT overridable
# through an environment variable, so code under check cannot redirect the
# check at a permissive allowlist.

if ! git rev-parse --show-toplevel >/dev/null 2>&1; then
  echo "ERROR: not inside a git work tree; the credential policy check needs git history." >&2
  exit 1
fi
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "${REPO_ROOT}"

BASE_SHA="${PULL_BASE_SHA:-}"
HEAD_SHA="${PULL_PULL_SHA:-${PULL_HEAD_SHA:-HEAD}}"
ALLOWLIST_FILE="${REPO_ROOT}/.ci/security-policy-allowlist.txt"

if [[ -z "$BASE_SHA" ]]; then
  BASE_SHA="HEAD~1"
fi

CREDENTIAL_SCAN_PATHS=(
  '.ci'
  'dockerfiles'
  'jenkins'
  'libraries'
  'prow-jobs'
  'scripts'
  'tekton'
)

# A renamed/removed tree must fail loudly instead of silently shortening the
# scan to nothing.
for path in "${CREDENTIAL_SCAN_PATHS[@]}"; do
  if [[ ! -d "${REPO_ROOT}/${path}" ]]; then
    echo "ERROR: credential-policy scan path does not exist: ${path}" >&2
    exit 1
  fi
done

# Credentials live in shell scripts, Groovy pipelines, YAML job/task
# definitions, Dockerfiles and (extension-less) Jenkinsfiles.
changed_files=()
while IFS= read -r line; do
  [[ -n "$line" ]] && changed_files+=("$line")
done < <(
  git diff --name-only "${BASE_SHA}..${HEAD_SHA}" -- "${CREDENTIAL_SCAN_PATHS[@]}" \
    | rg -N -e '\.(groovy|ya?ml|sh|bash)$' -e '(^|/)(Dockerfile[^/]*|Jenkinsfile[^/]*)$' || true
)

if [[ ${#changed_files[@]} -eq 0 ]]; then
  echo "No changed credential-risk files in scope."
  exit 0
fi

echo "Checking credential policy on ${#changed_files[@]} changed files"

failed=0

is_allowlisted() {
  local rule="$1"
  local file_path="$2"

  [[ -f "${ALLOWLIST_FILE}" ]] || return 1

  while IFS= read -r line; do
    [[ -n "${line}" ]] || continue
    [[ "${line}" =~ ^# ]] && continue

    local allow_rule path_regex
    allow_rule="$(echo "${line}" | cut -f1)"
    path_regex="$(echo "${line}" | cut -f2-)"

    [[ "${allow_rule}" == "${rule}" ]] || continue
    [[ -n "${path_regex}" ]] || continue

    if [[ "${file_path}" =~ ${path_regex} ]]; then
      return 0
    fi
  done < "${ALLOWLIST_FILE}"

  return 1
}

for f in "${changed_files[@]}"; do
  [[ -f "$f" ]] || continue

  # Rule 1: reject explicit secret-like literals in changed CI scripts.
  if rg -n -i "(token|password|secret|access[_-]?key|secret[_-]?key)\s*[:=]\s*['\"][^$][^'\"]{4,}['\"]" "$f" >/tmp/cred_literal.$$; then
    if is_allowlisted "hardcoded_literal" "$f"; then
      echo "[ALLOWLIST] hardcoded_literal matched for $f"
    else
      echo "[BLOCK] potential hard-coded secret literal in $f"
      cat /tmp/cred_literal.$$
      failed=1
    fi
  fi

  # Rule 2: reject obvious secret echo/printf in Groovy/bash snippets.
  if rg -n -i '(^|[^[:alnum:]_])(echo|printf)([^[:alnum:]_]|$).*\$\{?[A-Za-z_][A-Za-z0-9_]*(TOKEN|PASSWORD|SECRET|KEY)[A-Za-z0-9_]*\}?' "$f" >/tmp/cred_echo.$$; then
    if is_allowlisted "secret_echo" "$f"; then
      echo "[ALLOWLIST] secret_echo matched for $f"
    else
      echo "[BLOCK] potential secret value echo in $f"
      cat /tmp/cred_echo.$$
      failed=1
    fi
  fi

  # Rule 3: for Prow job YAML, forbid direct `value:` for secret-like env names.
  if [[ "$f" == prow-jobs/* && "$f" =~ \.(yaml|yml)$ ]]; then
    if awk '
      BEGIN{bad=0; name=""}
      /^[[:space:]]*- name:[[:space:]]*/ {
        name=$0
        sub(/.*- name:[[:space:]]*/, "", name)
      }
      /^[[:space:]]*name:[[:space:]]*/ {
        name=$0
        sub(/.*name:[[:space:]]*/, "", name)
      }
      /^[[:space:]]*value:[[:space:]]*/ {
        if (name ~ /(TOKEN|PASSWORD|SECRET|KEY)/) {
          print NR ":" $0
          bad=1
        }
      }
      END{exit bad}
    ' "$f" >/tmp/cred_yaml.$$; then
      :
    else
      if is_allowlisted "secret_env_plain_value" "$f"; then
        echo "[ALLOWLIST] secret_env_plain_value matched for $f"
      else
        echo "[BLOCK] secret-like env var should use valueFrom/secretKeyRef in $f"
        cat /tmp/cred_yaml.$$
        failed=1
      fi
    fi
  fi

done

rm -f /tmp/cred_literal.$$ /tmp/cred_echo.$$ /tmp/cred_yaml.$$

if [[ "$failed" -ne 0 ]]; then
  echo "Credential policy violations found."
  exit 1
fi

echo "Credential policy check passed."
