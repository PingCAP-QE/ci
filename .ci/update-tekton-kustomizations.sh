#!/usr/bin/env bash

set -euo pipefail

should_fail="false"
for kf in $(find tekton/v1 -name kustomization.yaml); do
    d=$(dirname $kf)

    pushd $d
        for f in $(find . -name "*.yaml" -type f | sed 's|./||' | grep -v kustomization.yaml | LC_COLLATE=C sort); do
            key=$(echo "$f" | sed 's|/|_|g')
            yq -i ".resources += \"$f\"" kustomization.yaml
        done
    popd

    # sort and uniq for .resources field
    yq -i '.resources |= (sort | unique)' $kf

    if ! git diff --exit-code $kf; then
        echo "$kf is not up to date, please run '.ci/update-tekton-kustomizations.sh' and commit the changes"
        should_fail="true"
    fi
done
if [ "$should_fail" = "true" ]; then
    exit 1
fi
