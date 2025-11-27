#!/usr/bin/env bash
set -e

# todo: gen the script automatic.

function tag_oci_repo() {
  local repo="$1"
  local base_tag="$2"
  local dst_tag="$3"
  local force="${4:-false}"

  echo "⬆️ Create tag '$dst_tag' on ${repo}:${base_tag}'"

  if oras discover --distribution-spec v1.1-referrers-tag ${repo}:${dst_tag}; then
    if [ "$force" == "true" ]; then
      oras discover --distribution-spec v1.1-referrers-tag ${repo}:${base_tag} && oras tag ${repo}:${base_tag} ${dst_tag} || exit 1
    else
      echo "🏃 Skip tag '$registry/${repo}:${dst_tag}', it exists."
    fi
  else
    oras discover --distribution-spec v1.1-referrers-tag ${repo}:${base_tag} && oras tag ${repo}:${base_tag} ${dst_tag} || exit 1
  fi

  echo "✅ Taged '$dst_tag' on '$registry/${repo}:${base_tag}'"
}

# For tiup pkgs
function tag_oci_artifact_repos() {
  echo "🚀 Prepare for tiup packages..."
  local rc_ver="$1"
  local ga_ver="$2"
  local registry="$3"
  local force="${4:-false}"
  local save_results_file="${5:-results.yaml}"

  # community
  repos=(
    "pingcap/tidb/package"
    "pingcap/ctl/package"
    "pingcap/monitoring/package"
    "pingcap/ng-monitoring/package"
    "pingcap/tidb-dashboard/package"
    "pingcap/tiflash/package"
    "pingcap/tiflow/package"
    "tikv/pd/package"
    "tikv/tikv/package"
  )
  # binlog is stoped releasing since v8.4.0.
  if [[ "$(printf '%s\n' "v8.4.0" "$ga_ver" | sort -V | head -n1)" == "$ga_ver" ]]; then
    repos+=(
      "pingcap/tidb-binlog/package"
    )
  fi
  # pingcap/ticdc/package published since v8.5.4
  if [[ "$(printf '%s\n' "v8.5.4" "$ga_ver" | sort -V | head -n1)" == "v8.5.4" ]]; then
    repos+=(
      "pingcap/ticdc/package"
    )
  fi

  platforms=("linux_amd64" "linux_arm64" "darwin_amd64" "darwin_arm64")

  # enterprise
  enterprise_repos=(
    "pingcap/tidb/package"
    "pingcap/tiflash/package"
    "tikv/pd/package"
    "tikv/tikv/package"
  )
  enterprise_platforms=("linux_amd64" "linux_arm64")

  for repo in "${repos[@]}"; do
    for platform in "${platforms[@]}"; do
      tag_oci_repo "$registry/${repo}" "${rc_ver}_${platform}" "${ga_ver}_${platform}" "$force"
      yq -i ".packages += \"$registry/${repo}:${ga_ver}_${platform}\"" $save_results_file
    done
  done
  for repo in "${enterprise_repos[@]}"; do
    for platform in "${enterprise_platforms[@]}"; do
      tag_oci_repo "$registry/${repo}" "${rc_ver}-enterprise_${platform}" "${ga_ver}-enterprise_${platform}" "$force"
      yq -i ".packages += \"$registry/${repo}:${ga_ver}-enterprise_${platform}\"" $save_results_file
    done
  done

  echo "✅ Taged for tiup packages."
}

# for images
function tag_oci_image_repos() {
  echo "🚀 Prepare for images..."
  local rc_ver="$1"
  local ga_ver="$2"
  local registry="$3"
  local force="${4:-false}"

  # community
  images=(
    "pingcap/tidb/images/br"
    "pingcap/tidb/images/dumpling"
    "pingcap/tidb/images/tidb-lightning"
    "pingcap/tidb/images/tidb-server"
    "pingcap/monitoring/image"
    "pingcap/ng-monitoring/image"
    "pingcap/tidb-dashboard/image"
    "pingcap/tiflash/image"
    "pingcap/tiflow/images/cdc"
    "pingcap/tiflow/images/dm"
    "pingcap/tiflow/images/tiflow"
    "tikv/pd/image"
    "tikv/tikv/image"
  )
  # binlog is stoped releasing since v8.4.0.
  if [[ "$(printf '%s\n' "v8.4.0" "$ga_ver" | sort -V | head -n1)" == "$ga_ver" ]]; then
    images+=(
      "pingcap/tidb-binlog/image"
    )
  fi
  # ticdc will publish from ticdc repo since v8.5.4
  if [[ "$(printf '%s\n' "v8.5.4" "$ga_ver" | sort -V | head -n1)" == "v8.5.4" ]]; then
    # remove "pingcap/tiflow/images/cdc":
    images=("${images[@]/pingcap\/tiflow\/images\/cdc/}")
    # Filter out empty elements that might result from the removal
    images=(${images[@]})
    images+=(
      "pingcap/ticdc/image"
    )
  fi

  for img in "${images[@]}"; do
    tag_oci_repo "$registry/${img}" "$rc_ver" "$ga_ver" "$force"
    yq -i ".images += \"$registry/${img}:${ga_ver}\"" $save_results_file
  done

  # enterprise
  enterprise_images=(
    "pingcap/tidb/images/br"
    "pingcap/tidb/images/dumpling"
    "pingcap/tidb/images/tidb-lightning"
    "pingcap/tidb/images/tidb-server"
    "pingcap/tiflash/image"
    "tikv/pd/image"
    "tikv/tikv/image"
  )
  for img in "${enterprise_images[@]}"; do
    tag_oci_repo "$registry/${img}" "${rc_ver}-enterprise" "${ga_ver}-enterprise" "$force"
    yq -i ".images += \"$registry/${img}:${ga_ver}-enterprise\"" $save_results_file
  done

  echo "✅ Taged for images."
}

# For tiup pkgs
function publish_tiup_from_oci_artifact_repos() {
  echo "🚀 Prepare for tiup packages..."
  local rc_ver="$1"
  local registry="$2"

  # community
  repos=(
    "pingcap/tidb/package"
    "pingcap/ctl/package"
    "pingcap/monitoring/package"
    "pingcap/ng-monitoring/package"
    "pingcap/tidb-binlog/package"
    "pingcap/tidb-dashboard/package"
    "pingcap/tiflash/package"
    "pingcap/tiflow/package"
    "tikv/pd/package"
    "tikv/tikv/package"
  )
  platforms=("linux_amd64" "linux_arm64" "darwin_amd64" "darwin_arm64")

  for repo in "${repos[@]}"; do
    for platform in "${platforms[@]}"; do
      publish_tiup_oci_repo "$registry/${repo}" "${rc_ver}_${platform}"
    done
  done

  echo "✅ Published for tiup packages."
}

function publish_tiup_oci_repo() {
  local repo="$1"
  local tag="$2"

  echo "⬆️ Publish tiup from ${repo}:${tag} ..."
  tkn -n ee-cd task start publish-tiup-from-oci-artifact \
    --param artifact-url="${repo}:${tag}" \
    --param nightly=false \
    --param tiup-mirror="http://tiup.pingcap.net:8988" \
    -w name=lock-tiup,claimName=pvc-lock-tiup-staging \
    -w name=dockerconfig,secret=hub-pingcap-net-ee \
    -w name=tiup-keys,secret=tiup-credentials-staging \
    --showlog

  echo "✅ Published tiup from ${repo}:${tag}."
}

function main() {
  local rc_ver="$1"
  local ga_ver="$2"
  local registry="${3:-hub.pingcap.net}"
  local force="${4:-false}"
  local save_results_file="${5:-results.yaml}"

  :> $save_results_file
  yq -i '.packages=[]' $save_results_file
  tag_oci_artifact_repos "$rc_ver" "$ga_ver" "$registry" "$force" "$save_results_file"
  yq -i '.images=[]' $save_results_file
  tag_oci_image_repos "$rc_ver" "$ga_ver" "$registry" "$force" "$save_results_file"

  # if you need republish the tiup packages, please uncomment it, and comment above two lines.
  #publish_tiup_from_oci_artifact_repos "$rc_ver" "$registry"
}

main "$@"
