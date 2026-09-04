---
name: zot-image-repair
description: "Validate and repair a broken image on the hub-zot.pingcap.net zot registry by launching the zot-validate-repair-image Tekton task. Use when a hub-zot image cannot be pulled or fails `crane validate` because layer blobs are missing or corrupt on the zot KSY S3 backend. Confirm the target cluster context and namespace with the user before launching (the known deployment is ksy-pingcap-cicd/ee-cd, but never assume it). Do not run local repair against the zot backend."
---

# Zot Image Repair

Repair a broken image served by the `hub-zot.pingcap.net` zot registry. The
registry stores blobs on a KSY S3 bucket. When a layer blob is missing or
truncated on S3, pulls fail and `crane validate` reports errors such as:

```text
error verifying size; got 0, want 784874
```

Repair re-downloads the healthy blobs from an upstream source image and
uploads them back to S3, without rewriting the manifest. The job runs as the
`zot-validate-repair-image` Tekton task, never on a local machine.

## Scope

- Operate on images served by `hub-zot.pingcap.net`.
- Launch and follow `zot-validate-repair-image` TaskRuns in the target
  cluster and namespace that the user chooses.
- Source images and Tekton task/tool definitions live in `PingCAP-QE/ci`
  (`tekton/v1/tasks/zot-validate-repair-image.yaml` and
  `tools/validate-repair-zot-image.sh`).

## Prerequisites

- `kubectl` with at least one configured context that hosts the
  `zot-validate-repair-image` Task.
- `tkn` (Tekton CLI) installed.
- A `ks3utilconfig` secret in the chosen namespace. It contains a single key
  `.ks3utilconfig` with credentials for the KSY S3 bucket that backs zot.

## Resolve the environment first (always confirm with the user)

Do not hardcode a cluster context or namespace. Before doing anything else:

1. If the user already told you which cluster context and namespace to use,
   verify them and proceed.
2. Otherwise discover candidates and present them as choices to the user:

   ```bash
   # candidate cluster contexts
   kubectl config get-contexts -o name

   # contexts + namespaces that actually host the repair task (tkn)
   for ctx in $(kubectl config get-contexts -o name); do
     echo "== $ctx =="
     tkn task list -A --context "$ctx" --no-headers 2>/dev/null \
       | grep zot-validate-repair-image || true
   done
   ```

3. For the chosen context, confirm the namespace and that the prerequisites
   exist there:

   ```bash
   tkn task list -n <namespace> --context <context> --no-headers \
     | grep zot-validate-repair-image
   kubectl --context <context> -n <namespace> get secret ks3utilconfig
   ```

4. Ask the user to confirm the context/namespace pair before launching
   anything. Offer the discovered candidates as options; a known deployment
   is `ksy-pingcap-cicd` / `ee-cd`, but only use it when the user confirms.

Throughout the rest of this skill, replace `<context>` and `<namespace>`
with the confirmed values.

## Workflow

1. Confirm the broken image and record the exact `target-image` reference
   (with tag or digest) from the user or the failing pull.
2. Resolve the environment (cluster context + namespace) as described above.
3. Derive the `source-image`. The safest source is the upstream repo that the
   mirror was copied from. Rewrite the reference:
   `hub-zot.pingcap.net/mirrors/<key>/<repo>:<tag>` →
   `us-docker.pkg.dev/pingcap-testing-account/<key>/<repo>:<tag>`.
   If the mapping is unclear, ask the user for the authoritative source image
   instead of guessing.
4. Derive the repair parameters:
   - `bucket`: the KSY S3 bucket that backs the zot registry. Derive it from
     the zot registry config in the chosen cluster when reachable (the zot
     StatefulSet usually lives in a `zot` namespace with a `zot-config`
     ConfigMap holding `storage.storageDriver.bucket`); the known value is
     `ee-zot`. Confirm with the user if you cannot locate it.
   - `workspace ks3util-config`: mounted from the `ks3utilconfig` secret
     (confirm the secret exists in the chosen namespace).
   - `config-file-path`: `.ks3utilconfig` (the default).
5. Launch the repair as a Tekton TaskRun. All repair logic already lives in
   the `zot-validate-repair-image` task; do not duplicate it in another
   script. Either start the task with `tkn`:

   ```bash
   tkn task start zot-validate-repair-image \
     --namespace <namespace> --context <context> \
     --param target-image=<target-image> \
     --param source-image=<source-image> \
     --param bucket=<bucket> \
     --workspace name=ks3util-config,secret=ks3utilconfig \
     --showlog
   ```

   Or apply a TaskRun manifest directly:

   ```bash
   kubectl --context <context> -n <namespace> create -f - <<'EOF'
   apiVersion: tekton.dev/v1
   kind: TaskRun
   metadata:
     generateName: zot-repair-
     namespace: <namespace>
   spec:
     taskRef:
       name: zot-validate-repair-image
     params:
       - name: target-image
         value: <target-image>
       - name: source-image
         value: <source-image>
       - name: bucket
         value: <bucket>
       - name: config-file-path
         value: .ks3utilconfig
     workspaces:
       - name: ks3util-config
         secret:
           secretName: ks3utilconfig
   EOF
   ```

6. Follow the run until it finishes:

   ```bash
   tkn taskrun logs <taskrun-name> -n <namespace> --context <context> --follow
   tkn taskrun list -n <namespace> --context <context>
   ```

   A successful run prints `Validation PASSED — image is intact.` and ends
   with status `Succeeded`. The task re-validates the image itself, so no
   further local verification is needed. A run that fails to repair all
   blobs exits non-zero; re-run only after confirming the `source-image` is
   healthy.

## Guardrails

- Always confirm the cluster context and namespace with the user first.
  Never silently assume `ksy-pingcap-cicd` or `ee-cd`.
- Never repair against the zot backend from a local machine. The S3
  credentials are cluster secrets (e.g. `ee-cd/ks3utilconfig`) and the
  repair must run as the Tekton task.
- Do not change the zot `Task`, the repair tool, or the S3 bucket for a
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
