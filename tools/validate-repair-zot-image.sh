#!/usr/bin/env bash
set -euo pipefail

RED=$'\033[0;31m'
GREEN=$'\033[0;32m'
YELLOW=$'\033[1;33m'
CYAN=$'\033[0;36m'
BOLD=$'\033[1m'
NC=$'\033[0m'

TMPDIR="${TMPDIR:-/tmp}/validate-repair-$$"
VALIDATE_LOG=""

usage() {
    cat <<EOF
${BOLD}validate-repair-zot-image${NC} — Validate a zot registry image and repair broken layer blobs on KSY S3.

${BOLD}Usage:${NC}
  $0 [options] <remote-image-url>

${BOLD}Arguments:${NC}
  remote-image-url    Full remote image URL (e.g. registry.example.com/repo/image:tag)

${BOLD}Options:${NC}
  --source <url>      Source image URL (non-interactive mode)
  --bucket <name>     S3 bucket name (non-interactive mode)
  -c, --config-file <path>
                      ks3util config file path for S3 authentication.
                      When not set, ks3util uses its default config
                      (~/.ks3utilconfig).
  -y, --yes           Auto-confirm all prompts (non-interactive mode)
  --max-retries <n>   Max download/upload attempts per blob (default: 3)

${BOLD}Description:${NC}
  1. Runs ${CYAN}crane validate${NC} against the remote image.
  2. Reports validation result; on failure, lists all broken blob digests.
  3. Prompts whether to attempt repair.
  4. If confirmed, asks for a ${CYAN}source image URL${NC} (where healthy blobs live)
      and target ${CYAN}KSY S3${NC} backend credentials.
  5. Downloads each broken blob with ${CYAN}crane blob${NC} from the source image.
  6. Uploads each blob to S3 with ${CYAN}ks3util cp${NC}.
  7. Re-runs ${CYAN}crane validate${NC} to confirm the fix.

${BOLD}Requirements:${NC}
  - crane   (go-containerregistry)     https://github.com/google/go-containerregistry
  - ks3util (Kingsoft Cloud S3 CLI)    https://www.ksyun.com
  - jq      (JSON processor)           https://jqlang.github.io/jq

${BOLD}Authentication:${NC}
  ks3util reads credentials from a config file. Use --config-file to specify
  one, otherwise ks3util falls back to its default path (~/.ks3utilconfig).
  In interactive mode, the script will also prompt for AK/SK/region/endpoint
  and pass them directly to ks3util.

${BOLD}Non-interactive example:${NC}
  $0 --source source-registry.example.com/mirrors/example/image:tag \\
     --bucket my-bucket \\
     --config-file /path/to/ks3utilconfig \\
     --yes \\
     target-registry.example.com/mirrors/example/image:tag
EOF
}

info()    { echo "${GREEN}[INFO]${NC}  $*"; }
warn()    { echo "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo "${RED}[ERROR]${NC} $*"; }
header()  { echo; echo "${BOLD}${CYAN}=== $* ===${NC}"; echo; }

require_command() {
    command -v "$1" >/dev/null 2>&1 || { error "Required command '$1' not found in PATH."; exit 1; }
}

cleanup() {
    rm -rf "$TMPDIR"
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------

run_validate() {
    local image="$1"
    local logfile="$TMPDIR/validate.log"

    header "Validating image: $image"
    info "Running: crane validate --remote $image"

    set +e
    crane validate --remote "$image" >"$logfile" 2>&1
    local ret=$?
    set -e

    VALIDATE_LOG="$logfile"

    if [[ $ret -eq 0 ]]; then
        info "Validation ${GREEN}PASSED${NC} — image is intact."
        return 0
    fi

    warn "Validation ${RED}FAILED${NC} (exit code $ret)."
    return 1
}

# ---------------------------------------------------------------------------
# Error parsing & layer digest resolution
# ---------------------------------------------------------------------------

parse_validate_errors() {
    local logfile="$1"
    local -n _out_type=$2
    local -n _out_children=$3
    local -n _out_sizes=$4

    _out_type="image"
    _out_children=()
    _out_sizes=()

    if grep -q 'Manifests\[' "$logfile" 2>/dev/null; then
        _out_type="index"
        while IFS= read -r digest; do
            [[ -n "$digest" ]] && _out_children+=("$digest")
        done < <(grep -oE 'Manifests\[[0-9]+\]\(sha256:[a-f0-9]{64}\)' "$logfile" \
            | grep -oE 'sha256:[a-f0-9]{64}' | sort -u)
    fi

    while IFS= read -r size; do
        [[ -n "$size" ]] && _out_sizes+=("$size")
    done < <(grep -oE 'want [0-9]+' "$logfile" | awk '{print $2}' | sort -u)
}

_resolve_layers_from_ref() {
    local manifest_ref="$1"
    local -n _rl_sizes=$2
    local -n _rl_digests=$3

    local manifest_json
    manifest_json=$(crane manifest "$manifest_ref" 2>/dev/null) || {
        warn "Failed to get manifest for: $manifest_ref"
        return 1
    }

    if [[ ${#_rl_sizes[@]} -eq 0 ]]; then
        info "  No size hints — will repair all layers in manifest."
        while IFS= read -r d; do
            [[ -n "$d" ]] && _rl_digests+=("$d")
        done < <(echo "$manifest_json" | jq -r '.layers[]?.digest // empty' 2>/dev/null)
    else
        for want_size in "${_rl_sizes[@]}"; do
            info "  Looking for layer with size=${want_size} ..."
            local matching
            matching=$(echo "$manifest_json" | jq -r --argjson size "${want_size}" \
                '.layers[]? | select(.size == $size) | .digest' 2>/dev/null)
            local found=false
            while IFS= read -r d; do
                [[ -n "$d" ]] || continue
                _rl_digests+=("$d")
                found=true
            done <<< "$matching"
            if ! $found; then
                warn "  No layer found with size=${want_size} in manifest."
            fi
        done
    fi
}

resolve_layer_digests() {
    local image="$1"
    local validate_type="$2"
    local -n _rs_children=$3
    local -n _rs_sizes=$4
    local -n _out_digests=$5

    _out_digests=()

    if [[ "$validate_type" == "index" && ${#_rs_children[@]} -gt 0 ]]; then
        for child_digest in "${_rs_children[@]}"; do
            local ref="${image}@${child_digest}"
            header "Resolving layers from child manifest: $child_digest"
            _resolve_layers_from_ref "$ref" _rs_sizes _out_digests
        done
    else
        _resolve_layers_from_ref "$image" _rs_sizes _out_digests
    fi

    local -A _seen
    local deduped=()
    for d in "${_out_digests[@]}"; do
        [[ -n "${_seen[$d]:-}" ]] && continue
        _seen[$d]=1
        deduped+=("$d")
    done
    _out_digests=("${deduped[@]}")
}

print_validate_errors() {
    local logfile="$1"

    echo ""
    echo "${BOLD}--- Error details (last 20 lines) ---${NC}"
    tail -20 "$logfile" | sed 's/^/  /'
    echo "${BOLD}---------------------------------------${NC}"
    echo ""
}

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

extract_repo_path() {
    local image="$1"

    # Strip tag or digest
    image="${image%%@*}"
    image="${image%%:*}"

    # Strip protocol if any
    image="${image#*://}"

    # Split host and path: everything after the first '/' is the repo path
    local repo_path="${image#*/}"

    if [[ -z "$repo_path" || "$repo_path" == "$image" ]]; then
        error "Cannot extract repo path from image URL: $1"
        error "Expected format: <registry>/<org>/<repo>[:<tag>|@<digest>]"
        exit 1
    fi

    echo "$repo_path"
}

# ---------------------------------------------------------------------------
# Repair
# ---------------------------------------------------------------------------

confirm() {
    local prompt="$1"
    local default="${2:-N}"
    local answer

    if [[ "$default" == "Y" ]]; then
        read -r -p "$(echo "${YELLOW}$prompt [Y/n]: ${NC}")" answer
        answer="${answer:-Y}"
    else
        read -r -p "$(echo "${YELLOW}$prompt [y/N]: ${NC}")" answer
        answer="${answer:-N}"
    fi

    [[ "$answer" =~ ^[Yy]$ ]]
}

prompt_required() {
    local prompt="$1"
    local env_var="$2"
    local val

    val="${!env_var:-}"
    if [[ -n "$val" ]]; then
        info "Using $env_var from environment."
        echo "$val"
        return
    fi

    while true; do
        read -r -p "$(echo "${CYAN}$prompt: ${NC}")" val
        if [[ -n "$val" ]]; then
            echo "$val"
            return
        fi
        warn "This field is required."
    done
}

download_blob() {
    local source_image="$1"
    local digest="$2"
    local outfile="$TMPDIR/blobs/${digest//[:\/]/_}"

    mkdir -p "$(dirname "$outfile")"

    info "Downloading blob ${CYAN}$digest${NC} from ${CYAN}$source_image${NC} ..." >&2
    crane blob "${source_image}@${digest}" > "$outfile" 2>"$TMPDIR/blob-dl.log"

    if [[ ! -s "$outfile" ]]; then
        error "Downloaded blob is empty or failed for $digest." >&2
        cat "$TMPDIR/blob-dl.log" >&2
        return 1
    fi

    local hash="${digest#*:}"
    local downloaded_hash
    if command -v sha256sum >/dev/null 2>&1; then
        downloaded_hash=$(sha256sum "$outfile" | awk '{print $1}')
    elif command -v shasum >/dev/null 2>&1; then
        downloaded_hash=$(shasum -a 256 "$outfile" | awk '{print $1}')
    else
        error "No sha256sum or shasum found, cannot verify blob integrity." >&2
        return 1
    fi

    if [[ "$downloaded_hash" != "$hash" ]]; then
        error "Blob integrity check failed for $digest." >&2
        error "  expected: $hash" >&2
        error "  got:      $downloaded_hash" >&2
        rm -f "$outfile"
        return 1
    fi

    local size
    size=$(wc -c < "$outfile" | tr -d ' ')
    info "  -> saved $size bytes (digest verified)" >&2
    echo "$outfile"
}

upload_blob() {
    local local_file="$1"
    local digest="$2"
    local bucket="$3"
    local repo_path="$4"
    local config_file="$5"

    local alg="${digest%%:*}"
    local hash="${digest#*:}"

    local s3_path="ks3://${bucket}/${repo_path}/blobs/${alg}/${hash}"
    local config_opt=()
    if [[ -n "$config_file" ]]; then
        config_opt=(--config-file "$config_file")
    fi

    info "Uploading to ${CYAN}$s3_path${NC} ..."
    if ks3util cp -f "${config_opt[@]}" "$local_file" "$s3_path" 2>&1 | sed 's/^/  /'; then
        info "  -> uploaded successfully."
    else
        error "Upload failed for $digest."
        return 1
    fi
}

repair_blobs() {
    local source_image="$1"
    local -n digests_ref=$2
    local bucket="$3"
    local repo_path="$4"
    local config_file="$5"
    local max_retries="${6:-3}"

    local failed=()
    local repaired=()

    header "Repairing ${#digests_ref[@]} broken blob(s)"

    for digest in "${digests_ref[@]}"; do
        echo ""
        info "--- Processing $digest ---"

        local retry=0
        local local_file=""
        local ok=false

        while (( retry < max_retries )); do
            if (( retry > 0 )); then
                warn "  Retry $retry/$((max_retries - 1)) for $digest ..."
            fi

            if ! local_file=$(download_blob "$source_image" "$digest"); then
                retry=$((retry + 1))
                continue
            fi

            if ! upload_blob "$local_file" "$digest" "$bucket" "$repo_path" "$config_file"; then
                retry=$((retry + 1))
                continue
            fi

            ok=true
            break
        done

        if $ok; then
            repaired+=("$digest")
        else
            error "Failed to repair $digest after $max_retries attempt(s)."
            failed+=("$digest")
        fi
    done

    echo ""
    if [[ ${#repaired[@]} -gt 0 ]]; then
        info "${GREEN}Successfully repaired ${#repaired[@]} blob(s):${NC}"
        for d in "${repaired[@]}"; do echo "    $d"; done
    fi

    if [[ ${#failed[@]} -gt 0 ]]; then
        error "${RED}Failed to repair ${#failed[@]} blob(s):${NC}"
        for d in "${failed[@]}"; do echo "    $d"; done
        return 1
    fi

    return 0
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

main() {
    require_command crane
    require_command ks3util
    require_command jq

    local remote_image=""
    local source_image=""
    local bucket=""
    local config_file=""
    local auto_yes=false
    local max_retries=3

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --source)
                source_image="$2"
                shift 2
                ;;
            --bucket)
                bucket="$2"
                shift 2
                ;;
            -c|--config-file)
                config_file="$2"
                shift 2
                ;;
            --max-retries)
                max_retries="$2"
                shift 2
                ;;
            -y|--yes)
                auto_yes=true
                shift
                ;;
            -h|--help)
                usage
                exit 0
                ;;
            -*)
                error "Unknown option: $1"
                usage
                exit 1
                ;;
            *)
                if [[ -z "$remote_image" ]]; then
                    remote_image="$1"
                else
                    error "Unexpected argument: $1"
                    usage
                    exit 1
                fi
                shift
                ;;
        esac
    done

    if [[ -z "$remote_image" ]]; then
        usage
        exit 1
    fi

    mkdir -p "$TMPDIR"

    # ---- Step 1-3: Validate ----

    if run_validate "$remote_image"; then
        exit 0
    fi

    print_validate_errors "$VALIDATE_LOG"

    # ---- Step 4: Ask to repair ----

    if ! $auto_yes; then
        if ! confirm "Do you want to attempt repair?"; then
            info "Repair skipped. Exiting."
            exit 1
        fi
    fi

    # ---- Step 5: Gather repair info (once) ----

    echo ""
    if ! $auto_yes; then
        header "Repair configuration"
    fi

    if [[ -z "$source_image" ]]; then
        source_image=$(prompt_required "Source image URL (e.g. registry.example.com/repo/image:tag)" "")
    else
        info "Source image: ${CYAN}$source_image${NC}"
    fi

    local repo_path
    repo_path=$(extract_repo_path "$remote_image")
    info "Auto-derived S3 blob prefix from image URL: ${CYAN}${repo_path}/blobs/${NC}"

    if [[ -z "$bucket" ]]; then
        bucket=$(prompt_required "S3 bucket name" "")
    else
        info "S3 bucket: ${CYAN}$bucket${NC}"
    fi

    if [[ -n "$config_file" ]]; then
        info "Using ks3util config file: ${CYAN}$config_file${NC}"
        if [[ ! -f "$config_file" ]]; then
            error "Config file not found: $config_file"
            exit 1
        fi
    else
        info "No --config-file specified, ks3util will use default config (~/.ks3utilconfig)."
    fi

    # ---- Repair loop: parse → repair → re-validate → detect new errors ----

    local -A attempted_digests=()
    local round=0
    local validate_type="image"
    local child_manifests=()
    local size_hints=()
    local broken_digests=()

    while true; do
        round=$((round + 1))

        validate_type="image"
        child_manifests=()
        size_hints=()
        parse_validate_errors "$VALIDATE_LOG" validate_type child_manifests size_hints

        info "Validation scope: ${CYAN}${validate_type}${NC} (round ${round})"
        if [[ ${#child_manifests[@]} -gt 0 ]]; then
            # Process only the first broken child manifest per round;
            # the next will surface on re-validation after this one is fixed.
            child_manifests=("${child_manifests[0]}")
            info "  Child manifests with errors:"
            for cm in "${child_manifests[@]}"; do
                echo "    ${YELLOW}$cm${NC}"
            done
        fi
        if [[ ${#size_hints[@]} -gt 0 ]]; then
            info "  Layer size hints: ${size_hints[*]}"
        fi

        broken_digests=()
        resolve_layer_digests "$remote_image" "$validate_type" child_manifests size_hints broken_digests

        if [[ ${#broken_digests[@]} -eq 0 ]]; then
            warn "Could not resolve any broken layer blob digests from error output."
            error "Raw error log saved at: $VALIDATE_LOG"
            exit 1
        fi

        local new_digests=()
        for d in "${broken_digests[@]}"; do
            if [[ -z "${attempted_digests[$d]:-}" ]]; then
                new_digests+=("$d")
            fi
        done

        if [[ ${#new_digests[@]} -eq 0 ]]; then
            warn "All broken layers have already been repaired/attempted. Cannot make further progress."
            exit 1
        fi

        for d in "${new_digests[@]}"; do
            attempted_digests[$d]=1
        done

        echo
        header "Repair round ${round}"
        echo "${BOLD}Broken layer blob digests (${#new_digests[@]} new, ${#attempted_digests[@]} total attempted):${NC}"
        for d in "${new_digests[@]}"; do
            echo "  ${RED}$d${NC}"
        done
        echo ""

        echo "  ${BOLD}Target image:${NC}    $remote_image"
        echo "  ${BOLD}Source image:${NC}    $source_image"
        echo "  ${BOLD}S3 bucket:${NC}      $bucket"
        echo "  ${BOLD}S3 blob path:${NC}    ks3://${bucket}/${repo_path}/blobs/<alg>/<digest>"
        if [[ -n "$config_file" ]]; then
            echo "  ${BOLD}Config file:${NC}     $config_file"
        else
            echo "  ${BOLD}Config file:${NC}     (ks3util default)"
        fi
        echo "  ${BOLD}Blobs to repair:${NC} ${#new_digests[@]}"
        echo ""

        if [[ $round -eq 1 ]] && ! $auto_yes; then
            if ! confirm "Proceed with repair?" "Y"; then
                info "Repair cancelled. Exiting."
                exit 1
            fi
        fi

        repair_blobs "$source_image" new_digests "$bucket" "$repo_path" "$config_file" "$max_retries" || {
            error "Some blobs could not be repaired. See above for details."
        }

        if run_validate "$remote_image"; then
            info "${GREEN}Repair successful — image is now valid after ${round} round(s).${NC}"
            exit 0
        fi

        print_validate_errors "$VALIDATE_LOG"
        info "Still has validation errors after round ${round}; checking for new broken layers..."
    done
}

main "$@"
