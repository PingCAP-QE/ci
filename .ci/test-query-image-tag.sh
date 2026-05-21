#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "${tmp_dir}"' EXIT

mock_bin="${tmp_dir}/bin"
mkdir -p "${mock_bin}"

cat > "${mock_bin}/oras" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

fixture_for_ref() {
    case "$1" in
        example.com/team/app:v1.2.3)
            printf '%s\n' single
            ;;
        registry.example.com/org/app:multi)
            printf '%s\n' multi
            ;;
        *)
            echo "not found: $1" >&2
            return 1
            ;;
    esac
}

copy_fixture() {
    local source_file="$1"
    local output_file="$2"

    cp "${MOCK_FIXTURE_DIR}/${source_file}" "${output_file}"
}

main() {
    local subcommand="$1"
    shift

    if [[ "${subcommand}" != "manifest" ]]; then
        echo "unsupported mock subcommand: ${subcommand}" >&2
        exit 1
    fi

    local action="$1"
    shift
    local descriptor=false
    local output_file=""
    local platform=""

    while [[ "$#" -gt 0 ]]; do
        case "$1" in
            --plain-http)
                shift
                ;;
            --descriptor)
                descriptor=true
                shift
                ;;
            --output)
                output_file="$2"
                shift 2
                ;;
            --platform)
                platform="$2"
                shift 2
                ;;
            *)
                break
                ;;
        esac
    done

    local image_ref="$1"
    local fixture

    fixture="$(fixture_for_ref "${image_ref}")"

    case "${action}" in
        fetch)
            if [[ "${descriptor}" == "true" ]]; then
                copy_fixture "${fixture}/descriptor.json" "${output_file}"
            else
                copy_fixture "${fixture}/manifest.json" "${output_file}"
            fi
            ;;
        fetch-config)
            if [[ -z "${platform}" ]]; then
                copy_fixture "${fixture}/config.json" "${output_file}"
                exit 0
            fi

            case "${platform}" in
                linux/amd64)
                    copy_fixture "${fixture}/config-linux-amd64.json" "${output_file}"
                    ;;
                linux/arm64)
                    copy_fixture "${fixture}/config-linux-arm64.json" "${output_file}"
                    ;;
                *)
                    echo "unsupported mock platform: ${platform}" >&2
                    exit 1
                    ;;
            esac
            ;;
        *)
            echo "unsupported mock manifest action: ${action}" >&2
            exit 1
            ;;
    esac
}

main "$@"
EOF

chmod +x "${mock_bin}/oras"

fixture_dir="${tmp_dir}/fixtures"
mkdir -p "${fixture_dir}/single" "${fixture_dir}/multi"

cat > "${fixture_dir}/single/manifest.json" <<'EOF'
{
  "schemaVersion": 2,
  "mediaType": "application/vnd.oci.image.manifest.v1+json",
  "config": {
    "mediaType": "application/vnd.oci.image.config.v1+json",
    "digest": "sha256:singleconfig",
    "size": 7023
  }
}
EOF

cat > "${fixture_dir}/single/descriptor.json" <<'EOF'
{
  "mediaType": "application/vnd.oci.image.manifest.v1+json",
  "digest": "sha256:singlemanifest",
  "size": 1024
}
EOF

cat > "${fixture_dir}/single/config.json" <<'EOF'
{
  "created": "2026-05-20T12:34:56Z",
  "architecture": "amd64",
  "os": "linux",
  "config": {
    "Labels": {
      "org.opencontainers.image.revision": "abc123",
      "app.kubernetes.io/name": "single-app"
    }
  }
}
EOF

cat > "${fixture_dir}/multi/manifest.json" <<'EOF'
{
  "schemaVersion": 2,
  "mediaType": "application/vnd.oci.image.index.v1+json",
  "manifests": [
    {
      "mediaType": "application/vnd.oci.image.manifest.v1+json",
      "digest": "sha256:linuxamd64",
      "size": 768,
      "platform": {
        "os": "linux",
        "architecture": "amd64"
      }
    },
    {
      "mediaType": "application/vnd.oci.image.manifest.v1+json",
      "digest": "sha256:linuxarm64",
      "size": 768,
      "platform": {
        "os": "linux",
        "architecture": "arm64"
      }
    }
  ]
}
EOF

cat > "${fixture_dir}/multi/descriptor.json" <<'EOF'
{
  "mediaType": "application/vnd.oci.image.index.v1+json",
  "digest": "sha256:multimanifest",
  "size": 2048
}
EOF

cat > "${fixture_dir}/multi/config-linux-amd64.json" <<'EOF'
{
  "created": "2026-05-20T11:00:00Z",
  "architecture": "amd64",
  "os": "linux",
  "config": {
    "Labels": {
      "org.opencontainers.image.source": "https://github.com/example/app",
      "org.opencontainers.image.revision": "deadbeef"
    }
  }
}
EOF

cat > "${fixture_dir}/multi/config-linux-arm64.json" <<'EOF'
{
  "created": "2026-05-20T11:05:00Z",
  "architecture": "arm64",
  "os": "linux",
  "config": {
    "Labels": {
      "org.opencontainers.image.source": "https://github.com/example/app",
      "org.opencontainers.image.version": "multi"
    }
  }
}
EOF

output_dir="${tmp_dir}/output"
mkdir -p "${output_dir}"

PATH="${mock_bin}:${PATH}" MOCK_FIXTURE_DIR="${fixture_dir}" \
    "${repo_root}/scripts/artifacts/query-image-tag.sh" \
    "example.com/team/app" \
    "v1.2.3" \
    "${output_dir}/single.json"

jq -e '
    .image_ref == "example.com/team/app:v1.2.3" and
    .pushed_at == "2026-05-20T12:34:56Z" and
    .digest == "sha256:singlemanifest" and
    .multi_arch == false and
    .platforms == ["linux/amd64"] and
    .labels["org.opencontainers.image.revision"] == "abc123" and
    .labels["app.kubernetes.io/name"] == "single-app"
' "${output_dir}/single.json" >/dev/null

PATH="${mock_bin}:${PATH}" MOCK_FIXTURE_DIR="${fixture_dir}" \
    "${repo_root}/scripts/artifacts/query-image-tag.sh" \
    "registry.example.com/org/app" \
    "multi" \
    "${output_dir}/multi.json"

jq -e '
    .image_ref == "registry.example.com/org/app:multi" and
    .pushed_at == "2026-05-20T11:05:00Z" and
    .digest == "sha256:multimanifest" and
    .multi_arch == true and
    .platforms == ["linux/amd64", "linux/arm64"] and
    .labels["org.opencontainers.image.source"] == "https://github.com/example/app" and
    .labels["org.opencontainers.image.revision"] == "deadbeef" and
    .labels["org.opencontainers.image.version"] == "multi"
' "${output_dir}/multi.json" >/dev/null

if PATH="${mock_bin}:${PATH}" MOCK_FIXTURE_DIR="${fixture_dir}" \
    "${repo_root}/scripts/artifacts/query-image-tag.sh" \
    "registry.example.com/org/app" \
    "missing" \
    "${output_dir}/missing.json"; then
    echo "expected missing image lookup to fail" >&2
    exit 1
fi

if [[ -e "${output_dir}/missing.json" ]]; then
    echo "missing image lookup should not create an artifact" >&2
    exit 1
fi
