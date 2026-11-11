#!/usr/bin/env bash
# Run a policy gate with the reviewed base revision's copy of the gate script.
#
# The merge ACL is delegated per OWNERS subtree, while the resources a pod may
# bind — Secrets, ServiceAccounts, storage classes, privileged containers — are
# namespace-wide. The gate scripts are therefore the control that keeps a
# delegated approver from granting their own job the Jenkins admin token or the
# org GitHub token.
#
# A presubmit checks out the pull request's merge commit, so running
# `.ci/verify-*.sh` straight from that checkout lets a PR that both widens a pod
# spec and rewrites the gate be judged by its own weakened copy, and report a
# green check. This runner materialises the gate script from PULL_BASE_SHA
# instead; the content under test (pod templates, job definitions, scanned trees)
# and the exemption ledger still come from the pull request.
#
# The ledger stays a pull-request file on purpose: `.ci/` has no OWNERS of its
# own, so it inherits the repository root OWNERS (`sig-approvers-ee`). Adding or
# removing an exemption therefore needs a central approver, which is exactly the
# review a delegated subtree cannot supply. What must not be pull-request
# controlled is the code that interprets the ledger, and the inputs it trusts:
# the allowlist path and the expiry clock are command-line options of the gates,
# not environment variables.
#
# The presubmit loads this runner itself from the base revision
# (`git show "${PULL_BASE_SHA}:.ci/run-policy-gate.sh"`), so a PR cannot weaken
# the runner either.
#
# Usage: .ci/run-policy-gate.sh <gate> [extra args ...]
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: .ci/run-policy-gate.sh <gate> [extra args ...]

  pod-privilege             pod privilege / Secret / ServiceAccount gate
  pod-resource              pod resource policy gate
  secret-scan               credential policy + gitleaks scan
  storage-class             storage class policy gate
  pr-content                pull request content policy
  jenkins-job-references    jenkins job reference checks
USAGE
}

gate="${1-}"
case "${gate}" in
  "" | -h | --help)
    usage
    exit 2
    ;;
esac
shift

base="${PULL_BASE_SHA:-}"
if [[ -z "${base}" ]]; then
  echo "ERROR: PULL_BASE_SHA is required: the gate has to come from the reviewed base revision" >&2
  exit 1
fi

if ! git cat-file -e "${base}^{commit}" 2>/dev/null; then
  git fetch --quiet --depth=1 origin "${base}" >/dev/null 2>&1 || true
fi
git cat-file -e "${base}^{commit}" 2>/dev/null || {
  echo "ERROR: cannot read base revision ${base}" >&2
  exit 1
}

# The presubmit materialises this runner into $TMPDIR and runs it from there, so
# the repository has to come from git rather than from the script's own path.
repo_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "${repo_root}"

# Materialise the base copy inside `.ci/` so the gate keeps resolving the
# repository from its own location (`REPO_ROOT=$(dirname $0)/..`).
materialised=()
cleanup() {
  local f
  for f in ${materialised[@]+"${materialised[@]}"}; do
    rm -f "${f}"
  done
}
trap cleanup EXIT

# Prints the path of the materialised copy; the caller records it for cleanup.
new_gate_script() {
  local src="$1"
  local dst
  # busybox mktemp only accepts a trailing XXXXXX template, so build the name
  # ourselves; the file lives for one gate run and is removed by the trap.
  dst=".ci/.policy-base-$(basename "${src}" .sh)-$$.sh"
  rm -f "${dst}"
  if ! git show "${base}:${src}" >"${dst}" 2>/dev/null; then
    rm -f "${dst}"
    echo "ERROR: cannot read ${src} at base revision ${base}" >&2
    exit 1
  fi
  printf '%s' "${dst}"
}

case "${gate}" in
  pod-privilege)
    s="$(new_gate_script .ci/verify-pod-privilege-policy.sh)"; materialised+=("$s")
    bash "$s" "$@"
    ;;
  pod-resource)
    s="$(new_gate_script .ci/verify-pod-resource-policy.sh)"; materialised+=("$s")
    bash "$s" "$@"
    ;;
  secret-scan)
    s="$(new_gate_script .ci/verify-jenkins-credential-policy.sh)"; materialised+=("$s")
    bash "$s" "$@"
    s="$(new_gate_script .ci/verify-secret-scan.sh)"; materialised+=("$s")
    sh "$s"
    ;;
  storage-class)
    s="$(new_gate_script .ci/verify-storage-class-policy.sh)"; materialised+=("$s")
    bash "$s" "$@"
    ;;
  pr-content)
    s="$(new_gate_script .ci/check-pr-content-policy.sh)"; materialised+=("$s")
    bash "$s" --base-sha "${base}" "$@"
    ;;
  jenkins-job-references)
    s="$(new_gate_script .ci/check-jenkins-job-references.sh)"; materialised+=("$s")
    bash "$s" --quiet "$@"
    ;;
  *)
    echo "ERROR: unknown gate '${gate}'" >&2
    usage >&2
    exit 2
    ;;
esac
