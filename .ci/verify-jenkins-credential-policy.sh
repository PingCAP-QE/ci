#!/usr/bin/env bash
set -euo pipefail

BASE_SHA="${PULL_BASE_SHA:-}"
HEAD_SHA="${PULL_PULL_SHA:-${PULL_HEAD_SHA:-HEAD}}"

if [[ -z "$BASE_SHA" ]]; then
  BASE_SHA="HEAD~1"
fi

# Focus on credentials-risk surface only.
changed_files=()
while IFS= read -r line; do
  [[ -n "$line" ]] && changed_files+=("$line")
done < <(
  git diff --name-only "${BASE_SHA}..${HEAD_SHA}" -- \
    'pipelines/**' 'jobs/**' 'libraries/**' 'prow-jobs/**' \
    | rg -N '\.(groovy|ya?ml)$' || true
)

if [[ ${#changed_files[@]} -eq 0 ]]; then
  echo "No changed Groovy/YAML files in credentials-risk scope."
  exit 0
fi

echo "Checking credential policy on ${#changed_files[@]} changed files"

failed=0

for f in "${changed_files[@]}"; do
  [[ -f "$f" ]] || continue

  # Rule 1: reject explicit secret-like literals in changed CI scripts.
  if rg -n -i "(token|password|secret|access[_-]?key|secret[_-]?key)\s*[:=]\s*['\"][^$][^'\"]{4,}['\"]" "$f" >/tmp/cred_literal.$$; then
    echo "[BLOCK] potential hard-coded secret literal in $f"
    cat /tmp/cred_literal.$$
    failed=1
  fi

  # Rule 2: reject obvious secret echo/printf in Groovy/bash snippets.
  if rg -n -i "\b(echo|printf)\b.*\$\{?[A-Za-z_][A-Za-z0-9_]*(TOKEN|PASSWORD|SECRET|KEY)[A-Za-z0-9_]*\}?" "$f" >/tmp/cred_echo.$$; then
    echo "[BLOCK] potential secret value echo in $f"
    cat /tmp/cred_echo.$$
    failed=1
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
      echo "[BLOCK] secret-like env var should use valueFrom/secretKeyRef in $f"
      cat /tmp/cred_yaml.$$
      failed=1
    fi
  fi

done

rm -f /tmp/cred_literal.$$ /tmp/cred_echo.$$ /tmp/cred_yaml.$$

if [[ "$failed" -ne 0 ]]; then
  echo "Credential policy violations found."
  exit 1
fi

echo "Credential policy check passed."
