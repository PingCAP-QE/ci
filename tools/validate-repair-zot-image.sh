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

extract_broken_digests() {
    local logfile="$1"

    grep -oE 'sha256:[a-f0-9]{64}' "$logfile" 2>/dev/null \
        | sort -u \
        || true
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

    if [[ -n "${env_var:-}" ]]; then
        val="${!env_var:-}"
        if [[ -n "$val" ]]; then
            info "Using $env_var from environment."
            echo "$val"
            return
        fi
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

    info "Downloading blob ${CYAN}$digest${NC} from ${CYAN}$source_image${NC} ..."
    crane blob "${source_image}@${digest}" > "$outfile" 2>"$TMPDIR/blob-dl.log"

    if [[ ! -s "$outfile" ]]; then
        error "Downloaded blob is empty or failed for $digest."
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
        error "No sha256sum or shasum found, cannot verify blob integrity."
        return 1
    fi

    if [[ "$downloaded_hash" != "$hash" ]]; then
        error "Blob integrity check failed for $digest."
        error "  expected: $hash"
        error "  got:      $downloaded_hash"
        rm -f "$outfile"
        return 1
    fi

    local size
    size=$(wc -c < "$outfile" | tr -d ' ')
    info "  -> saved $size bytes (digest verified)"
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

    local s3_path="s3://${bucket}/${repo_path}/blobs/${alg}/${hash}"
    local config_opt=()
    if [[ -n "$config_file" ]]; then
        config_opt=(--config-file "$config_file")
    fi

    info "Uploading to ${CYAN}$s3_path${NC} ..."
    if ks3util cp "${config_opt[@]}" "$local_file" "$s3_path" 2>&1 | sed 's/^/  /'; then
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

    local broken_digests
    mapfile -t broken_digests < <(extract_broken_digests "$VALIDATE_LOG")

    if [[ ${#broken_digests[@]} -eq 0 ]]; then
        warn "No sha256 digests found in error output. Cannot determine broken blobs."
        error "Raw error log saved at: $VALIDATE_LOG"
        exit 1
    fi

    echo
    echo "${BOLD}Broken blob digests (${#broken_digests[@]} total):${NC}"
    for d in "${broken_digests[@]}"; do
        echo "  ${RED}$d${NC}"
    done
    echo ""

    # ---- Step 4: Ask to repair ----

    if ! $auto_yes; then
        if ! confirm "Do you want to attempt repair?"; then
            info "Repair skipped. Exiting."
            exit 1
        fi
    fi

    # ---- Step 5: Gather repair info ----

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

    # ---- Show summary and confirm ----

    echo ""
    header "Summary"
    echo "  ${BOLD}Target image:${NC}    $remote_image"
    echo "  ${BOLD}Source image:${NC}    $source_image"
    echo "  ${BOLD}S3 bucket:${NC}      $bucket"
    echo "  ${BOLD}S3 blob path:${NC}    s3://${bucket}/${repo_path}/blobs/<alg>/<digest>"
    if [[ -n "$config_file" ]]; then
        echo "  ${BOLD}Config file:${NC}     $config_file"
    else
        echo "  ${BOLD}Config file:${NC}     (ks3util default)"
    fi
    echo "  ${BOLD}Blobs to repair:${NC} ${#broken_digests[@]}"
    echo ""

    if ! $auto_yes; then
        if ! confirm "Proceed with repair?" "Y"; then
            info "Repair cancelled. Exiting."
            exit 1
        fi
    fi

    # ---- Step 6-7: Download & Upload ----

    repair_blobs "$source_image" broken_digests "$bucket" "$repo_path" "$config_file" "$max_retries" || {
        error "Some blobs could not be repaired. See above for details."
    }

    # ---- Step 8: Re-validate ----

    if run_validate "$remote_image"; then
        info "${GREEN}Repair successful — image is now valid.${NC}"
        exit 0
    else
        print_validate_errors "$VALIDATE_LOG"
        warn "Image still has validation errors after repair."
        warn "You may need to repair additional blobs or check S3 connectivity."
        exit 1
    fi
}

main "$@"
