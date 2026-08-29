#!/usr/bin/env bash
set -euo pipefail

# General validation for changes to Prow **kubernetes-agent** jobs (jobs that
# carry a `spec.containers` pod spec, i.e. `decorate: true` / agent kubernetes).
#
# It is intentionally *not* tied to any single job: it validates every changed
# kubernetes-agent job in a PR that touches prow-jobs YAML, so future job
# additions (like pull-license-check) are exercised the same way.
#
# Two layers:
#   1. Static checks (fast, no cluster access):
#      - container image is a literal, fully-qualified reference (no `$var`,
#        no bare `latest`), and its tag exists in the registry when reachable
#      - container declares command/args and resource requests/limits
#      - the embedded shell (command + args) passes `bash -n`
#   2. Dynamic check (real run, best-effort):
#      - actually create a ProwJob (prowjobs.prow.k8s.io) on the prow cluster
#        from the job's own pod spec, point it at the target repo's trunk, and
#        wait until the ProwJob reaches a terminal state (success / failure /
#        aborted), then delete it. This validates the definition end-to-end:
#        pod schedules, image pulls, the command actually runs to completion.
#
# The dynamic check needs RBAC on the prow cluster: create/get/watch/delete on
# prowjobs + get/list/watch on pods. The required RBAC is managed by GitOps in
# PingCAP-QE/ee-ops (apps/gcp/prow/post/job-ns/rbac.yaml, applied by Flux
# `prow-post`) and granted to the dedicated ServiceAccount `prow-job-validation`
# in the pod namespace (prow-test-pods) that this job runs with. ProwJob CRs are
# created in the prowjob namespace (apps) where plank watches them, while the
# spawned pods run in the pod namespace (prow-test-pods). Without RBAC the
# script falls back to static-only and reports the steps to enable the create.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

BASE_SHA="${PULL_BASE_SHA:-}"
HEAD_SHA="${PULL_PULL_SHA:-HEAD}"
MAX_CREATE_JOBS="${MAX_CREATE_JOBS:-3}"
# how long to wait for a created ProwJob to reach a terminal state
# (jobs get a decoration timeout of 30m, so give this some slack)
COMPLETION_TIMEOUT="${COMPLETION_TIMEOUT:-2400}"
SKIP_CREATE="${SKIP_CREATE:-0}"

# The prow cluster splits namespaces: ProwJob CRs live in the prowjob namespace
# (plank watches it there) while the job pods plank spawns run in the pod
# namespace. Override via env when targeting a different cluster.
PJOB_NAMESPACE="${PJOB_NAMESPACE:-apps}"
POD_NAMESPACE="${POD_NAMESPACE:-prow-test-pods}"

CRANE_BIN=""
workdir="$(mktemp -d)"
trap 'rm -rf "${workdir}"' EXIT

failures=0
created=0

warn() { echo "  ⚠  ${*}" >&2; }
fail() { echo "  ✗  ${*}" >&2; failures=$((failures + 1)); }

# ---------------------------------------------------------------- utilities

get_crane() {
  [[ -n "${CRANE_BIN}" ]] && return 0
  local tmp
  tmp="$(mktemp -d)"
  local url="https://github.com/google/go-containerregistry/releases/download/v0.21.5/go-containerregistry_Linux_x86_64.tar.gz"
  if command -v wget >/dev/null 2>&1; then
    wget -qO "${tmp}/crane.tar.gz" "${url}"
  else
    curl -fsSL -o "${tmp}/crane.tar.gz" "${url}"
  fi
  tar xzf "${tmp}/crane.tar.gz" -C "${tmp}" crane
  CRANE_BIN="${tmp}/crane"
}

get_kubeconfig() {
  local token ca
  token="/var/run/secrets/kubernetes.io/serviceaccount/token"
  ca="/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"
  [[ -f "${token}" ]] || return 1
  local server="https://${KUBERNETES_SERVICE_HOST}:${KUBERNETES_SERVICE_PORT}"
  cat > "${workdir}/kubeconfig" <<EOF
apiVersion: v1
kind: Config
clusters:
  - name: in-cluster
    cluster:
      server: ${server}
      certificate-authority: ${ca}
contexts:
  - name: in-cluster
    context:
      cluster: in-cluster
      user: in-cluster
current-context: in-cluster
users:
  - name: in-cluster
    user:
      tokenFile: ${token}
EOF
  export KUBECONFIG="${workdir}/kubeconfig"
  return 0
}

# ------------------------------------------------------- changed file scoping

changed_files() {
  if [[ -n "${BASE_SHA}" ]]; then
    git diff --name-only --diff-filter=AM "${BASE_SHA}" "${HEAD_SHA}" -- prow-jobs/ 2>/dev/null || true
  else
    git diff --name-only --diff-filter=AM HEAD~1 HEAD -- prow-jobs/ 2>/dev/null || true
  fi
}

# ---------------------------------------------------------- static checks

static_check_container() {
  local name="$1" image="$2" cmd="$3" args0="$4" has_req="$5" has_lim="$6"
  echo "    container: ${name:-main}"

  if [[ "${image}" == *'$'* ]]; then
    warn "${image} is templated (contains \$) - skipped image checks"
  elif [[ "${image}" != *"/"* ]]; then
    warn "${image} is not a fully-qualified image reference (missing registry/repo)"
  else
    local tag="${image##*:}"
    if [[ "${image}" == *"@"* ]]; then
      echo "      image pinned by digest: ${image}"
    elif [[ "${tag}" == "latest" ]]; then
      warn "${image} uses mutable 'latest' tag"
    elif [[ "${tag}" == "${image}" ]]; then
      warn "${image} has no explicit tag"
    else
      # Best-effort: registry may be private or unreachable -> warn, not fail.
      if ! get_crane >/dev/null 2>&1 || ! "${CRANE_BIN}" digest "${image}" >/dev/null 2>&1; then
        warn "could not verify image exists (crane): ${image}"
      else
        echo "      image exists: ${image}"
      fi
    fi
  fi

  if [[ -z "${cmd}" && -z "${args0}" ]]; then
    warn "no command/args declared - job may rely on image entrypoint"
  fi

  # lint the embedded shell script (command + args[0] when it is -c style)
  if [[ -n "${args0}" && ( "${cmd}" == *bash* || "${cmd}" == *sh* ) ]]; then
    if ! printf '%s\n' "${args0}" | bash -n >/dev/null 2>&1; then
      fail "shell syntax error in args for container ${name:-main}"
    else
      echo "      shell args: syntax OK"
    fi
  fi

  if [[ "${has_req}" != "1" && "${has_lim}" != "1" ]]; then
    warn "container ${name:-main} declares neither resource requests nor limits"
  fi
}

static_check_job() {
  local job="$1"
  local name repo kind decorate
  name="$(jq -r '.name' <<<"${job}")"
  repo="$(jq -r '.repo' <<<"${job}")"
  kind="$(jq -r '.kind' <<<"${job}")"
  echo "  [static] ${kind} ${name} (${repo})"

  local c
  while read -r c; do
    local image cmd args0 has_req has_lim
    image="$(jq -r '.image // ""' <<<"${c}")"
    cmd="$(jq -r '.command | join(" ") // ""' <<<"${c}")"
    args0="$(jq -r '.args[0] // ""' <<<"${c}")"
    has_req="$(jq -r 'if (.resources.requests != null) then "1" else "0" end' <<<"${c}")"
    has_lim="$(jq -r 'if (.resources.limits != null) then "1" else "0" end' <<<"${c}")"
    static_check_container "${name}" "${image}" "${cmd}" "${args0}" "${has_req}" "${has_lim}"
  done < <(jq -c '.spec.containers[]' <<<"${job}")
}

# ------------------------------------------------- dynamic (create ProwJob)

# resolve the default branch of a public GitHub repo (HEAD symref, fallback list)
resolve_default_branch() {
  local org="$1" repo_name="$2"
  local head_ref
  head_ref="$(git ls-remote --symref "https://github.com/${org}/${repo_name}.git" HEAD 2>/dev/null | awk '/^ref:/ {sub(/^refs\/heads\//, "", $2); print $2; exit}')"
  [[ -n "${head_ref}" ]] && { echo "${head_ref}"; return 0; }
  local b
  for b in master main; do
    if git ls-remote "https://github.com/${org}/${repo_name}.git" "refs/heads/${b}" 2>/dev/null | grep -q .; then
      echo "${b}"
      return 0
    fi
  done
  return 1
}

create_prowjob() {
  local job="$1"
  local name repo kind job_type
  name="$(jq -r '.name' <<<"${job}")"
  repo="$(jq -r '.repo' <<<"${job}")"
  kind="$(jq -r '.kind' <<<"${job}")"
  job_type="presubmit"
  [[ "${kind}" == "postsubmit" ]] && job_type="postsubmit"

  local org repo_name
  org="${repo%/*}"
  repo_name="${repo#*/}"
  local branch base_sha
  branch="$(resolve_default_branch "${org}" "${repo_name}" || echo master)"
  base_sha="$(git ls-remote "https://github.com/${org}/${repo_name}.git" "refs/heads/${branch}" 2>/dev/null | awk '{print $1; exit}')"
  [[ -n "${base_sha}" ]] || { fail "cannot resolve ${branch} head sha for ${repo}"; return 1; }

  local pjname
  pjname="ci-validate-${name//\//-}"
  pjname="${pjname//[^a-zA-Z0-9-]/-}"
  pjname="${pjname:0:55}-$(date +%s)"
  [[ ${#pjname} -le 63 ]] || pjname="${pjname: -63}"

  local podspec deco timeout_min
  podspec="$(jq -c '.spec' <<<"${job}")"
  deco="$(jq -c '.decoration_config // {}' <<<"${job}")"
  timeout_min="$(jq -r '(.timeout // "30m")' <<<"${deco}")"
  [[ "${timeout_min}" == *m ]] || timeout_min="30m"

  jq -n \
    --arg name "${pjname}" \
    --arg ns "${PJOB_NAMESPACE}" \
    --arg type "${job_type}" \
    --arg job "${name}" \
    --arg org "${org}" \
    --arg repo "${repo_name}" \
    --arg branch "${branch}" \
    --arg sha "${base_sha}" \
    --argjson deco "${deco}" \
    --arg timeout "${timeout_min}" \
    --argjson podspec "${podspec}" \
    '{
      apiVersion: "prow.k8s.io/v1",
      kind: "ProwJob",
      metadata: { name: $name, namespace: $ns },
      spec: {
        type: $type,
        agent: "kubernetes",
        job: $job,
        refs: { org: $org, repo: $repo, base_ref: $branch, base_sha: $sha },
        decorate: true,
        decoration_config: ($deco + { timeout: $timeout }),
        pod_spec: $podspec
      }
    }' | yq -P > "${workdir}/${pjname}.yaml"

  echo "    creating ProwJob ${pjname} in ${PJOB_NAMESPACE} (${repo}@${branch})"
  if ! kubectl apply -f "${workdir}/${pjname}.yaml" >/dev/null 2>&1; then
    echo "      unable to create ProwJob (RBAC not granted?) - apply the RBAC"
    echo "      in PingCAP-QE/ee-ops apps/gcp/prow/post/job-ns/rbac.yaml (Flux prow-post) to enable this"
    return 0
  fi

  # wait for the ProwJob to reach a terminal state (success/failure/aborted),
  # i.e. the spawned job pod actually ran to completion, not just started.
  local state=""
  local waited=0
  while (( waited < COMPLETION_TIMEOUT )); do
    state="$(kubectl -n "${PJOB_NAMESPACE}" get prowjob "${pjname}" -o jsonpath='{.status.state}' 2>/dev/null || true)"
    case "${state}" in
      success|failure|aborted) break ;;
    esac
    sleep 15
    waited=$((waited + 15))
  done

  case "${state}" in
    success)
      echo "      ProwJob ${pjname} completed: success ✓"
      ;;
    failure)
      fail "ProwJob ${pjname} completed with failure (job ran but its command/tests failed)"
      ;;
    aborted)
      fail "ProwJob ${pjname} aborted (prow decoration timeout)"
      ;;
    *)
      fail "ProwJob ${pjname} did not reach a terminal state within ${COMPLETION_TIMEOUT}s (state=${state:-unknown}); recent events:"
      kubectl -n "${POD_NAMESPACE}" get events --field-selector "involvedObject.name=${pjname}" --sort-by=.lastTimestamp 2>/dev/null | tail -n 5 | sed 's/^/        /' || true
      ;;
  esac
  kubectl -n "${PJOB_NAMESPACE}" delete prowjob "${pjname}" --ignore-not-found >/dev/null 2>&1 || true
}

# --------------------------------------------------------------------- main

files=()
while IFS= read -r line; do
  files+=("${line}")
done < <(changed_files | LC_ALL=C sort -u)
if [[ ${#files[@]} -eq 0 ]]; then
  echo "no prow-jobs YAML changes, nothing to validate"
  exit 0
fi

for tool in jq yq kubectl; do
  command -v "${tool}" >/dev/null || { echo "ERROR: '${tool}' not found" >&2; exit 1; }
done

if [[ "${SKIP_CREATE}" == "1" ]]; then
  echo "SKIP_CREATE=1, dynamic ProwJob creation disabled"
elif ! get_kubeconfig; then
  echo "  in-cluster kubeconfig unavailable, dynamic ProwJob creation disabled"
fi

for f in "${files[@]}"; do
  [[ -f "${REPO_ROOT}/${f}" ]] || continue
  echo "== validating ${f}"
  jobs="$(
    yq -o=json '[.presubmits // {} | to_entries[] | .key as $repo | .value[] | select(.spec != null) | {"kind":"presubmit", "repo":$repo, "name":.name, "branches":.branches, "decorate":(.decorate // false), "decoration_config":(.decoration_config // {}), "spec":.spec}]' "${REPO_ROOT}/${f}" 2>/dev/null
    yq -o=json '[.postsubmits // {} | to_entries[] | .key as $repo | .value[] | select(.spec != null) | {"kind":"postsubmit", "repo":$repo, "name":.name, "branches":.branches, "decorate":(.decorate // false), "decoration_config":(.decoration_config // {}), "spec":.spec}]' "${REPO_ROOT}/${f}" 2>/dev/null
  )"
  jobs="$(jq -s 'add' <<<"${jobs}" 2>/dev/null || echo '[]')"
  while read -r job; do
    [[ -n "${job}" ]] || continue
    static_check_job "${job}"
    if [[ "${SKIP_CREATE}" != "1" && -n "${KUBECONFIG:-}" && "${created}" -lt "${MAX_CREATE_JOBS}" ]]; then
      create_prowjob "${job}" || true
      created=$((created + 1))
    fi
  done < <(jq -c '.[]' <<<"${jobs}")
done

if [[ "${failures}" -ne 0 ]]; then
  echo "prow kubernetes-agent job validation FAILED (${failures} issue(s))"
  exit 1
fi
echo "prow kubernetes-agent job validation PASSED"
