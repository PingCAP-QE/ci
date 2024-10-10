#!/usr/bin/env bash

yq -i ".configMapGenerator[0].files = []" prow-jobs/kustomization.yaml
for f in $(find prow-jobs -name "*.yaml" -type f | sed 's|prow-jobs/||' | grep -v kustomization.yaml | LC_COLLATE=C sort); do
    key=$(echo "$f" | sed 's|/|_|g')
    yq -i ".configMapGenerator[0].files += \"$key=$f\"" prow-jobs/kustomization.yaml
done

# use git diff to judge if the file is changed, if yes ,then print the error message and exit with 1.
if ! git diff --exit-code prow-jobs/kustomization.yaml; then
    echo "prow-jobs/kustomization.yaml is not up to date, please run '.ci/update-prow-job-kustomization.sh' and commit the changes"
    exit 1
fi
