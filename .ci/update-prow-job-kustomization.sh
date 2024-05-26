#!/bin/sh

sed -i 's/^[[:blank:]]*"files": \[\]$/&/' prow-jobs/kustomization.yaml
key_index=0
for f in $(find prow-jobs -name "*.yaml" -type f | sed 's|prow-jobs/||' | grep -v kustomization.yaml | sort); do
    # I want format the key_index with 3 char width.
    key_index=$((key_index + 1))
    key=$(printf "%03d.yaml" $key_index)
    sed -i "s/^[[:blank:]]*\"files\": \[$/$&\\n    \"$key=$f\",/" prow-jobs/kustomization.yaml
done

# use git diff to judge if the file is changed, if yes ,then print the error message and exit with 1.
if ! git diff --exit-code prow-jobs/kustomization.yaml >/dev/null; then
    echo "prow-jobs/kustomization.yaml is not up to date, please run '.ci/update-prow-job-kustomization.sh' and commit the changes"
    exit 1
fi
