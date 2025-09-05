#! /usr/bin/env bash
# This script collects information about the current release and generates markdown for release issues.

previous_release="v8.5.1" # comment it when current release is not a patch release.
current_release="v8.5.2"

### !!! Please update the list of repositories before running this script.
repos=(
    pingcap/tidb
    pingcap/tiflow
    pingcap/tiflash
    tikv/pd
    tikv/tikv
    pingcap/monitoring
    pingcap/ng-monitoring
    pingcap/tidb-dashboard

    pingcap/ticdc # started since v9.0.0
    pingcap/tidb-tools # migrated to pingcap/tiflow since v9.0.0
    pingcap/tidb-binlog # deprecated since v8.3.0
)

echo "### Working Tags: ${current_release}"

for repo in "${repos[@]}"; do
    echo -e "- **${repo##*/}** [${current_release}](https://github.com/${repo}/releases/tag/${current_release})";
done

if [ -n "$previous_release" ]; then
    echo ""
    echo "### Check the diff with previous version(${previous_release})"
    for repo in "${repos[@]}"; do
        echo -e "- **${repo##*/}** [diff](https://github.com/${repo}/compare/${previous_release}...${current_release})";
    done
fi
