#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Bump prow job images while keeping tag variants.

Usage:
  bump_prow_job_images.sh --root prow-jobs --image maniator/gh --tool crane
  bump_prow_job_images.sh --root prow-jobs --image docker --tool oras

Options:
  --root    Root directory to scan (default: prow-jobs)
  --image   Image repo to update (e.g., maniator/gh, docker, ghcr.io/org/repo)
  --tool    Tag listing tool: crane or oras
  --dry-run Do not modify files
  --verbose Print each change
USAGE
}

ROOT="prow-jobs"
IMAGE_REPO=""
TOOL=""
DRY_RUN=0
VERBOSE=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --root) ROOT="$2"; shift 2;;
    --image) IMAGE_REPO="$2"; shift 2;;
    --tool) TOOL="$2"; shift 2;;
    --dry-run) DRY_RUN=1; shift;;
    --verbose) VERBOSE=1; shift;;
    -h|--help) usage; exit 0;;
    *) echo "Unknown arg: $1"; usage; exit 1;;
  esac
 done

if [[ -z "$IMAGE_REPO" || -z "$TOOL" ]]; then
  usage
  exit 1
fi

if ! command -v yq >/dev/null 2>&1; then
  echo "yq is required" >&2
  exit 1
fi

if [[ "$TOOL" == "crane" ]]; then
  if ! command -v crane >/dev/null 2>&1; then
    echo "crane is required" >&2
    exit 1
  fi
elif [[ "$TOOL" == "oras" ]]; then
  if ! command -v oras >/dev/null 2>&1; then
    echo "oras is required" >&2
    exit 1
  fi
else
  echo "--tool must be crane or oras" >&2
  exit 1
fi

list_tags() {
  local repo="$1"
  if [[ "$TOOL" == "crane" ]]; then
    crane ls "$repo"
  else
    oras repo tags "$repo"
  fi
}

list_yaml_files() {
  if command -v rg >/dev/null 2>&1; then
    rg --files -g '*.yaml' "$ROOT"
  else
    find "$ROOT" -type f -name '*.yaml'
  fi
}

# Determine current tag variants used under containers/initContainers for this image repo.
CURRENT_TAGS=()
while IFS= read -r line; do
  [[ -n "$line" ]] && CURRENT_TAGS+=("$line")
done < <(
  while IFS= read -r file; do
    yq -r '.. | select(has("image")) | .image' "$file"
  done < <(list_yaml_files) 2>/dev/null \
    | sed -E 's/^"|"$//g' \
    | awk -v repo="$IMAGE_REPO" 'index($0, repo ":") == 1 {print $0}' \
    | sed -E "s|^${IMAGE_REPO}:||" \
    | sort -u
)

if [[ ${#CURRENT_TAGS[@]} -eq 0 ]]; then
  echo "No matching images found for ${IMAGE_REPO} under ${ROOT}" >&2
  exit 1
fi

# For each current tag, keep variant suffix after '-g<sha>' when present (or legacy first '-') and find latest tag with same variant.
LATEST_FOR_VARIANT=()

TAGS=$(list_tags "$IMAGE_REPO" | tr -d "\r")

for tag in "${CURRENT_TAGS[@]}"; do
  variant=""
  base="$tag"
  mode="legacy"
  if [[ "$tag" =~ ^(.*-g[0-9a-fA-F]+)(-(.*))?$ ]]; then
    base="${BASH_REMATCH[1]}"
    variant="${BASH_REMATCH[3]}"
    mode="gsha"
  elif [[ "$tag" == *-* ]]; then
    base="${tag%%-*}"
    variant="${tag#*-}"
  else
    mode="none"
  fi

  if [[ "$mode" == "gsha" ]]; then
    if [[ -n "$variant" ]]; then
candidates=$(echo "$TAGS" | grep "-g[0-9a-fA-F]+-${v}")
    else
      candidates=$(echo "$TAGS" | awk '/-g[0-9a-fA-F]+$/ {print $0}')
    fi
  elif [[ "$mode" == "legacy" ]]; then
    if [[ -n "$variant" ]]; then
candidates=$(echo "$TAGS" | grep "^*-${v}" )
    else
      candidates=$(echo "$TAGS" | awk 'index($0, "-") == 0 {print $0}')
    fi
  else
    candidates=$(echo "$TAGS" | awk 'index($0, "-") == 0 {print $0}')
  fi

  if [[ -z "$candidates" ]]; then
    echo "No candidate tags found for variant '${variant}'" >&2
    exit 1
  fi

  latest=$(echo "$candidates" | sort -V | tail -n 1)
  LATEST_FOR_VARIANT+=("$tag=$latest")
 done

# Apply replacements
CHANGES=0
for mapping in "${LATEST_FOR_VARIANT[@]}"; do
  old_tag="${mapping%%=*}"
  new_tag="${mapping#*=}"

  [[ "$old_tag" == "$new_tag" ]] && continue

  if [[ $VERBOSE -eq 1 ]]; then
    echo "${IMAGE_REPO}:${old_tag} -> ${IMAGE_REPO}:${new_tag}"
  fi

  if [[ $DRY_RUN -eq 0 ]]; then
    # Replace only in containers/initContainers image fields
    old_pat=$(printf '%s' "${IMAGE_REPO}:${old_tag}" | sed -E 's/[][\.^$*+?(){}|]/\\\\&/g')
    new_pat=$(printf '%s' "${IMAGE_REPO}:${new_tag}")
    rg -l --glob "${ROOT}/**/*.yaml" -F "${IMAGE_REPO}:${old_tag}" \
      | while read -r file; do
          # yq inplace: update only image fields
          yq -i '(.. | select(has("containers")) | .containers[]?.image) |= sub("'"${old_pat}"'", "'"${new_pat}"'") | (.. | select(has("initContainers")) | .initContainers[]?.image) |= sub("'"${old_pat}"'", "'"${new_pat}"'")' "$file"
        done
  fi
  CHANGES=$((CHANGES+1))
 done

if [[ $CHANGES -eq 0 ]]; then
  echo "No changes made."
else
  if [[ $DRY_RUN -eq 1 ]]; then
    echo "Would update ${CHANGES} tag variant(s) (dry-run)."
  else
    echo "Updated ${CHANGES} tag variant(s)."
  fi
fi
