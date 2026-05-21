#!/usr/bin/env bash
set -euo pipefail

usage() {
    echo "usage: $0 <registry-url> <image-tag> <output-path>" >&2
}

normalize_registry_ref() {
    local registry_url="$1"

    registry_url="${registry_url#https://}"
    registry_url="${registry_url#http://}"
    printf '%s\n' "${registry_url}"
}

build_platform_list_from_index() {
    local manifest_file="$1"

    jq -c '
        [
            .manifests[]
            | select(.platform.os != null and .platform.architecture != null)
            | .platform
            | .os + "/" + .architecture + (if .variant then "/" + .variant else "" end)
        ]
    ' "${manifest_file}"
}

build_platform_list_from_config() {
    local config_file="$1"

    jq -ce '
        [
            (
                if (.os // "") == "" or (.architecture // "") == "" then
                    error("image config is missing os/architecture")
                else
                    .os + "/" + .architecture + (if .variant then "/" + .variant else "" end)
                end
            )
        ]
    ' "${config_file}"
}

main() {
    if [[ "$#" -ne 3 ]]; then
        usage
        exit 1
    fi

    local registry_url="$1"
    local image_tag="$2"
    local output_path="$3"
    local registry_ref
    local image_ref
    local tmp_dir
    local manifest_file
    local descriptor_file
    local manifest_created
    local created_at=""
    local digest
    local media_type
    local multi_arch=false
    local platforms_json
    local labels_json="{}"
    local config_file
    local plain_http_arg=""

    registry_ref="$(normalize_registry_ref "${registry_url}")"
    image_ref="${registry_ref}:${image_tag}"

    if [[ "${registry_url}" == http://* ]]; then
        plain_http_arg="--plain-http"
    fi

    tmp_dir="$(mktemp -d)"
    trap "rm -rf '${tmp_dir}'" EXIT

    manifest_file="${tmp_dir}/manifest.json"
    descriptor_file="${tmp_dir}/descriptor.json"

    oras manifest fetch ${plain_http_arg:+"${plain_http_arg}"} --output "${manifest_file}" "${image_ref}" >/dev/null
    oras manifest fetch ${plain_http_arg:+"${plain_http_arg}"} --descriptor --output "${descriptor_file}" "${image_ref}" >/dev/null

    digest="$(jq -r '.digest // empty' "${descriptor_file}")"
    if [[ -z "${digest}" ]]; then
        echo "missing digest in descriptor for ${image_ref}" >&2
        exit 1
    fi

    media_type="$(jq -r '.mediaType // empty' "${manifest_file}")"
    manifest_created="$(jq -r '.annotations["org.opencontainers.image.created"] // empty' "${manifest_file}")"

    case "${media_type}" in
        application/vnd.oci.image.index.v1+json|application/vnd.docker.distribution.manifest.list.v2+json)
            local -a platforms=()
            local -a created_values=()
            local -a config_files=()
            local created

            multi_arch=true
            platforms_json="$(build_platform_list_from_index "${manifest_file}")"

            if [[ "${platforms_json}" == "[]" ]]; then
                echo "multi-arch image ${image_ref} does not expose any platforms" >&2
                exit 1
            fi

            while IFS= read -r platform; do
                platforms+=("${platform}")
            done < <(jq -r '.[]' <<<"${platforms_json}")

            for i in "${!platforms[@]}"; do
                config_file="${tmp_dir}/config-${i}.json"
                oras manifest fetch-config ${plain_http_arg:+"${plain_http_arg}"} --platform "${platforms[$i]}" --output "${config_file}" "${image_ref}" >/dev/null
                config_files+=("${config_file}")

                created="$(jq -r '.created // empty' "${config_file}")"
                if [[ -n "${created}" ]]; then
                    created_values+=("${created}")
                fi
            done

            labels_json="$(jq -cs 'reduce .[] as $config ({}; . * ($config.config.Labels // {}))' "${config_files[@]}")"

            # This workflow exposes image creation time, not registry-side push time.
            if [[ -n "${manifest_created}" ]]; then
                created_at="${manifest_created}"
            elif [[ "${#created_values[@]}" -gt 0 ]]; then
                created_at="$(printf '%s\n' "${created_values[@]}" | sort | tail -n 1)"
            fi
            ;;
        *)
            config_file="${tmp_dir}/config.json"
            oras manifest fetch-config ${plain_http_arg:+"${plain_http_arg}"} --output "${config_file}" "${image_ref}" >/dev/null

            platforms_json="$(build_platform_list_from_config "${config_file}")"
            labels_json="$(jq -c '.config.Labels // {}' "${config_file}")"

            if [[ -n "${manifest_created}" ]]; then
                created_at="${manifest_created}"
            else
                created_at="$(jq -r '.created // empty' "${config_file}")"
            fi
            ;;
    esac

    if [[ -z "${created_at}" ]]; then
        echo "image ${image_ref} does not expose a created timestamp" >&2
        exit 1
    fi

    if ! jq -en --arg timestamp "${created_at}" '$timestamp | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T")' >/dev/null; then
        echo "image ${image_ref} returned a non-ISO8601 timestamp: ${created_at}" >&2
        exit 1
    fi

    mkdir -p "$(dirname "${output_path}")"

    jq -n \
        --arg image_ref "${image_ref}" \
        --arg created_at "${created_at}" \
        --arg digest "${digest}" \
        --argjson multi_arch "${multi_arch}" \
        --argjson platforms "${platforms_json}" \
        --argjson labels "${labels_json}" \
        '{
            image_ref: $image_ref,
            created_at: $created_at,
            digest: $digest,
            multi_arch: $multi_arch,
            platforms: $platforms,
            labels: $labels
        }' > "${output_path}"

    jq -e '
        (.image_ref | type == "string") and
        (.created_at | type == "string") and
        (.digest | type == "string") and
        (.multi_arch | type == "boolean") and
        (.platforms | type == "array") and
        (.labels | type == "object")
    ' "${output_path}" >/dev/null
}

main "$@"
