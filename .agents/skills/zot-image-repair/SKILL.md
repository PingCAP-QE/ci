---
name: zot-image-repair
description: "Validate and repair a broken image on the hub-zot.pingcap.net zot registry by running tools/validate-repair-zot-image.sh with crane and ks3util. Use when a hub-zot image cannot be pulled or fails `crane validate` because layer blobs are missing or corrupt on the zot KSY S3 backend. The script probes the KS3 intranet endpoint first and falls back to the extranet one, so it works both inside and outside the KSY network."
---

# Zot Image Repair

Repair a broken image served by the `hub-zot.pingcap.net` zot registry. The
registry stores blobs on a KSY S3 bucket. When a layer blob is missing or
truncated on S3, pulls fail and `crane validate` reports errors such as:

```text
error verifying size; got 0, want 784874
```

Repair re-downloads the healthy blobs from an upstream source image and
uploads them back to S3, without rewriting the manifest or the tags.

The repair itself is a single shell script,
`tools/validate-repair-zot-image.sh` in `PingCAP-QE/ci`. There is no Tekton
task in the loop any more: the KSY Tekton deployment that used to run
`zot-validate-repair-image` is gone, and
`tekton/v1/tasks/zot-validate-repair-image.yaml` is kept for reference only.

## Scope

- Operate on images served by `hub-zot.pingcap.net`.
- Run the script directly on a host that has `crane`, `ks3util` and `jq`,
  with a KS3 credential file available.
- Never write your own repair logic: the script validates, resolves the broken
  blob digests from the validate output, verifies every downloaded blob's
  sha256 and re-validates the image afterwards.

## Prerequisites

- `crane` (go-containerregistry), `ks3util` (Kingsoft Cloud S3 CLI) and `jq`
  on `PATH`.
- `bash` 4.3 or newer: the script uses namerefs, so the stock `/bin/bash`
  3.2 on macOS is not enough (`brew install bash`, then run the script with
  that shell).
- A `ks3utilconfig` file (default `~/.ks3utilconfig`) holding AK/SK for the
  KSY S3 bucket that backs zot, e.g.:

  ```ini
  [Credentials]
  accessKeyID=...
  accessKeySecret=...
  endpoint=ks3-cn-beijing.ksyuncs.com
  ```

  Keep it out of the repository; never print or commit its contents.

## KS3 endpoint: intranet first, extranet as fallback

KS3 exposes two endpoints for the same bucket
(`ks3-cn-beijing-internal.ksyuncs.com` and `ks3-cn-beijing.ksyuncs.com`). The
intranet one only resolves inside the KSY network. The script handles this
automatically:

1. It asks the bucket for both names (`ks3util stat ks3://<bucket>/`).
2. It probes the intranet endpoint first.
3. When the probe fails it falls back to the extranet endpoint, and only keeps
   the endpoint from the config file if neither can be determined.

So run it as-is; do not hardcode an endpoint and do not edit the config file
to switch between intranet and extranet.

## Workflow

1. Confirm the broken image with the user and record the exact reference
   (with tag or digest) from the failing pull.
2. Derive the `source-image`. The safest source is the upstream repo the
   mirror was copied from:

   ```text
   hub-zot.pingcap.net/mirrors/<key>/<repo>:<tag>
     -> us-docker.pkg.dev/pingcap-testing-account/<key>/<repo>:<tag>
   ```

   If the mapping is unclear, ask the user for the authoritative source image
   instead of guessing.
3. Use bucket `ee-zot` (ask the user when a different bucket is expected).
4. Run the script. Start interactively so the user sees the target, source,
   bucket, resolved endpoint and the blob list before anything is uploaded:

   ```bash
   ./tools/validate-repair-zot-image.sh \
     --source us-docker.pkg.dev/pingcap-testing-account/<key>/<repo>:<tag> \
     --bucket ee-zot \
     --config-file ~/.ks3utilconfig \
     hub-zot.pingcap.net/mirrors/<key>/<repo>:<tag>
   ```

   Add `--yes` only when the user asked for a non-interactive run. The script
   exits non-zero when the image is still invalid or a blob could not be
   repaired.
5. Report the outcome: the script re-runs `crane validate` at the end, so no
   separate verification step is needed. A successful run prints
   `Repair successful — image is now valid after N round(s).`

## Guardrails

- Repair is a write to a shared bucket: confirm the target image and the
  source image with the user before running, and never run with `--yes`
  without that confirmation.
- The `source-image` must expose the same layer blobs as the target manifest.
  Prefer the upstream mirror source; when in doubt ask the user.
- Repair only uploads blobs; it never rewrites tags or manifests.
- Never copy a `ks3utilconfig` into the repo, into a command line argument
  visible in shell history, or into the conversation.
- Do not patch the script ad hoc for a single repair. If it needs a bug fix,
  open a `PingCAP-QE/ci` PR first.

## Read Next

- Repair script: `tools/validate-repair-zot-image.sh`
- Legacy Tekton task (not deployed): `tekton/v1/tasks/zot-validate-repair-image.yaml`
