---
name: zot-image-repair
description: "Validate and repair a broken image on the hub-zot.pingcap.net zot registry by launching the zot-validate-repair-image Tekton task in the ksy-pingcap-cicd cluster. Use when a hub-zot image cannot be pulled or fails `crane validate` because layer blobs are missing or corrupt on the ee-zot KSY S3 backend. Do not run local repair against the zot backend."
---

# Zot Image Repair

Repair a broken image served by the `hub-zot.pingcap.net` zot registry. The
registry stores blobs on a KSY S3 bucket (`ee-zot`) in the `ksy-pingcap-cicd`
cluster. When a layer blob is missing or truncated on S3, pulls fail and
`crane validate` reports errors such as:

```text
error verifying size; got 0, want 784874
```

Repair re-downloads the healthy blobs from an upstream source image and
uploads them back to S3, without rewriting the manifest. The job runs as the
`zot-validate-repair-image` Tekton task in namespace `ee-cd`, never on a
local machine.

## Scope

- Operate on images served by `hub-zot.pingcap.net`.
- Launch and follow `zot-validate-repair-image` TaskRuns in the `ee-cd`
  namespace of the `ksy-pingcap-cicd` cluster.
- Source images and Tekton task/tool definitions live in `PingCAP-QE/ci`
  (`tekton/v1/tasks/zot-validate-repair-image.yaml` and
  `tools/validate-repair-zot-image.sh`).

## Prerequisites

- `kubectl` with the `ksy-pingcap-cicd` context (current namespace `ee-cd`).
- `tkn` (Tekton CLI) installed.
- The `zot-validate-repair-image` Task exists in `ee-cd`:
  `tkn task list -n ee-cd --context ksy-pingcap-cicd`
- The `ks3utilconfig` secret exists in `ee-cd`. It contains a single key
  `.ks3utilconfig` with credentials for the KSY S3 bucket that backs zot.

## Background

- zot frontends `https://hub-zot.pingcap.net` (see the zot StatefulSet in the
  `zot` namespace).
- Its storage driver points at S3 bucket `ee-zot` on
  `ks3-cn-beijing.ksyuncs.com` (check `kubectl get cm zot-config -n zot -o
  yaml` if the bucket ever changes).
- The mirror name on zot encodes the upstream registry, so
  `hub-zot.pingcap.net/mirrors/<key>/<repo>` mirrors the OCI artifact repo
  `us-docker.pkg.dev/pingcap-testing-account/<key>/<repo>`. Examples:
  - `mirrors/hub/pingcap/tidb/images/tidb-server:v7.5.7` mirrors
    `us-docker.pkg.dev/pingcap-testing-account/hub/pingcap/tidb/images/tidb-server:v7.5.7`
  - `mirrors/tidbx/...` mirrors `us-docker.pkg.dev/pingcap-testing-account/tidbx/...`

## Workflow

1. Confirm the broken image and record the exact `target-image` reference
   (with tag or digest) from the user or the failing pull.
2. Derive the `source-image`. The safest source is the upstream repo that the
   mirror was copied from. Rewrite the reference:
   `hub-zot.pingcap.net/mirrors/<key>/<repo>:<tag>` →
   `us-docker.pkg.dev/pingcap-testing-account/<key>/<repo>:<tag>`.
   If the mapping is unclear, ask the user for the authoritative source image
   instead of guessing.
3. Derive the repair parameters:
   - `bucket`: `ee-zot` (S3 backend bucket from the zot config).
   - `workspace ks3util-config`: mounted from the `ks3utilconfig` secret.
   - `config-file-path`: `.ks3utilconfig` (the default).
4. Launch the repair as a Tekton TaskRun. All repair logic already lives in
   the `zot-validate-repair-image` task; do not duplicate it in another
   script. Either start the task with `tkn`:

   ```bash
   tkn task start zot-validate-repair-image \
     --namespace ee-cd --context ksy-pingcap-cicd \
     --param target-image=hub-zot.pingcap.net/mirrors/hub/pingcap/tidb/images/tidb-server:v7.5.7 \
     --param source-image=us-docker.pkg.dev/pingcap-testing-account/hub/pingcap/tidb/images/tidb-server:v7.5.7 \
     --param bucket=ee-zot \
     --workspace name=ks3util-config,secret=ks3utilconfig \
     --showlog
   ```

   Or apply a TaskRun manifest directly:

   ```bash
   kubectl --context ksy-pingcap-cicd -n ee-cd create -f - <<'EOF'
   apiVersion: tekton.dev/v1
   kind: TaskRun
   metadata:
     generateName: zot-repair-
     namespace: ee-cd
   spec:
     taskRef:
       name: zot-validate-repair-image
     params:
       - name: target-image
         value: hub-zot.pingcap.net/mirrors/hub/pingcap/tidb/images/tidb-server:v7.5.7
       - name: source-image
         value: us-docker.pkg.dev/pingcap-testing-account/hub/pingcap/tidb/images/tidb-server:v7.5.7
       - name: bucket
         value: ee-zot
       - name: config-file-path
         value: .ks3utilconfig
     workspaces:
       - name: ks3util-config
         secret:
           secretName: ks3utilconfig
   EOF
   ```

5. Follow the run until it finishes:

   ```bash
   tkn taskrun logs <taskrun-name> -n ee-cd --context ksy-pingcap-cicd --follow
   tkn taskrun list -n ee-cd --context ksy-pingcap-cicd
   ```

   A successful run prints `Validation PASSED — image is intact.` and ends
   with status `Succeeded`. A run that fails to repair all blobs exits
   non-zero; re-run only after confirming the `source-image` is healthy.

6. Confirm the fix externally if needed:

   ```bash
   crane validate --remote hub-zot.pingcap.net/mirrors/hub/pingcap/tidb/images/tidb-server:v7.5.7
   ```

## Guardrails

- Never repair against the zot backend from a local machine. The S3
  credentials are cluster secrets (`ee-cd/ks3utilconfig`) and the repair must
  run as the Tekton task.
- Do not change the zot `Task`, the repair tool, or the `ee-zot` bucket for a
  single repair. If the task or `tools/validate-repair-zot-image.sh` needs a
  bug fix, open a `PingCAP-QE/ci` PR first: the task downloads the tool from
  the repo `main` branch at runtime.
- The `source-image` must expose the same layer blobs as the target manifest.
  Prefer the upstream mirror source; when in doubt ask the user.
- Repair only uploads blobs; it never rewrites tags or manifests.
- When naming a TaskRun explicitly (instead of `generateName`), keep it a
  short DNS-1123 label (lowercase letters, digits, `-`).

## Read Next

- Task definition: `tekton/v1/tasks/zot-validate-repair-image.yaml`
- Repair tool source: `tools/validate-repair-zot-image.sh`
