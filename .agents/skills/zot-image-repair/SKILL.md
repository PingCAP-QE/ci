---
name: zot-image-repair
description: "Validate and repair a broken image on the hub-zot.pingcap.net zot registry by running tools/validate-repair-zot-image.sh with crane and ks3util. Use when a hub-zot image cannot be pulled or fails `crane validate` because layer blobs are missing or corrupt on the zot KSY S3 backend. Covers fetching that single script and installing its crane / ks3util / jq dependencies, and the script probes the KS3 intranet endpoint first and falls back to the extranet one."
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

The repair is a single shell script, `tools/validate-repair-zot-image.sh` in
`PingCAP-QE/ci`. It is the only file needed from that repository: installing
it plus three CLIs is covered in **Prepare** below, and cloning the whole
repo is not required. There is no Tekton task in the loop any more: both the
KSY Tekton deployment that ran `zot-validate-repair-image` and its task
definition (`tekton/v1/tasks/zot-validate-repair-image.yaml`, which downloaded
and executed the script from a mutable `@main` ref with KS3 credentials in
scope) have been removed.

## Prepare (one time per machine)

### 1. Get the repair script

Inside a `PingCAP-QE/ci` checkout, use the tracked file:

```bash
SCRIPT=./tools/validate-repair-zot-image.sh
```

Anywhere else, download just that one file (~20 KB) instead of cloning:

```bash
SCRIPT="$HOME/.local/lib/zot-image-repair/validate-repair-zot-image.sh"
mkdir -p "$(dirname "$SCRIPT")"
curl -fsSL -o "$SCRIPT" \
  https://cdn.jsdelivr.net/gh/PingCAP-QE/ci@main/tools/validate-repair-zot-image.sh
chmod +x "$SCRIPT"
```

If jsDelivr is unreachable, the same file is served at
`https://raw.githubusercontent.com/PingCAP-QE/ci/main/tools/validate-repair-zot-image.sh`.
The `@main` in the URL is a moving ref: pin a tag or commit there when the
setup has to be reproducible. To follow a pull request instead of `main`, use
the branch name in place of `main`.

Only `tools/validate-repair-zot-image.sh` is needed — do not clone the whole
repository for it. A sparse checkout is the option that keeps the repository
layout when you want `git pull` updates:

```bash
git clone --depth 1 --filter=blob:none --sparse https://github.com/PingCAP-QE/ci.git ~/zot-repair
git -C ~/zot-repair sparse-checkout set .agents/skills/zot-image-repair tools
```

(`sparse-checkout` in cone mode only accepts directories, hence `tools` and
not the script path.)

### 2. Install crane, ks3util and jq

| Tool | How to install |
|---|---|
| `crane` | `brew install crane` · `go install github.com/google/go-containerregistry/cmd/crane@latest` · or a release binary from `github.com/google/go-containerregistry/releases` |
| `jq` | `brew install jq` · `apt-get install -y jq` · `dnf install -y jq` |
| `ks3util` | not packaged anywhere: take the archive for your OS/arch from the Kingsoft version index `https://ks3util-version-update.ks3-cn-beijing.ksyuncs.com/history/index.html` and put the `ks3util` binary on `PATH` |

`ghcr.io/pingcap-qe/cd/utils/ks3util` is the image CI uses and it already
contains the binary — handy on Linux, but it does not help on macOS.

The script also needs **bash 4.3 or newer** (it uses namerefs). macOS still
ships `/bin/bash` 3.2, where it fails with `local: -n: invalid option`:
`brew install bash`, then run `bash "$SCRIPT" ...` with the Homebrew shell
first on `PATH` (or call `/opt/homebrew/bin/bash` directly).

### 3. Prepare registry authentication

Repositories under `us-docker.pkg.dev/pingcap-testing-account` are a mix of
public and private, and the private ones need an authenticated pull. Set the
credential up once rather than guessing per image:

```bash
gcloud auth login
gcloud auth configure-docker us-docker.pkg.dev
```

crane picks it up through `DOCKER_CONFIG` (default `~/.docker/config.json`).
Prefer the credential helper over pasting a token into the config by hand; if
it reports `Reauthentication failed`, re-run `gcloud auth login`.

### 4. Provide KS3 credentials

The script reads a `ks3utilconfig` file (default `~/.ks3utilconfig`) holding
AK/SK for the bucket that backs zot:

```ini
[Credentials]
accessKeyID=...
accessKeySecret=...
endpoint=ks3-cn-beijing.ksyuncs.com
```

`chmod 600` it. Never commit it, never pass the keys on the command line, and
never paste its contents into the conversation. An `endpoint` line is
optional: see *KS3 endpoint* below.

### 5. Verify the setup (read only)

```bash
bash -n "$SCRIPT"                        # syntax check, needs bash 4.3+
"$SCRIPT" --help
crane version && jq --version && ks3util --version
crane manifest <target-image> >/dev/null   # target registry: read
crane manifest <source-image> >/dev/null   # source registry: authenticated read
ks3util -c ~/.ks3utilconfig stat ks3://ee-zot/
```

The `stat` call proves credentials and endpoint work (it prints
`IntranetEndpoint` and `ExtranetEndpoint` for the bucket); the two `manifest`
calls prove pull access to both registries. If any of them fails, stop here —
repair cannot work either. Bucket write access cannot be checked without
writing; see *Permissions*.

## Permissions

Repair touches three systems, and needs a different permission on each:

| System | Needed | Used for |
|---|---|---|
| Target registry `hub-zot.pingcap.net` | **read** | `crane validate` and `crane manifest` to find the broken blobs, plus the re-validation after the repair |
| Source registry (e.g. `us-docker.pkg.dev/pingcap-testing-account`) | **read**, auth prepared (public and private repos are mixed) | `crane blob` to download the healthy blobs |
| KS3 bucket (`ee-zot`) | **read + write** | `stat` to discover the two endpoints, `ks3util cp` to upload blobs under `<repo_path>/blobs/<alg>/<digest>` |

- **Registry read**: the target registry only needs read for this workflow, and
  the source has to be pullable. Repositories under `pingcap-testing-account`
  are a **mix of public and private**, so do not assume either — prepare the
  authenticated access described in *Prepare* and let crane use it. crane reads
  credentials from the local docker config (`DOCKER_CONFIG`); for Artifact
  Registry that is `gcloud auth login` + `gcloud auth configure-docker
  us-docker.pkg.dev`. A `401` / `denied` means the credential is missing or
  expired: refresh it (ask the user) instead of retrying anonymously.
- A stale credential helper fails *before* the request is made, e.g.
  `gcloud.auth.docker-helper ... Reauthentication failed`. That is a credential
  problem even for a public repository, so fix the credential — it is not a
  missing permission and not something to route around.
- **Bucket write** is carried by the AK/SK in `ks3utilconfig`. `ks3util stat`
  only proves read access; a missing write permission surfaces at the first
  `ks3util cp` as `AccessDenied`. There is no read-only way to prove write
  access, so never report the setup as verified before an upload has actually
  succeeded, or the user has confirmed the AK/SK may write to the bucket.
- **Never escalate to get around a missing permission**: no second credential,
  no different bucket, no substituting another source image. Report which of
  the three permissions is missing and stop.

## Scope

- Operate on images served by `hub-zot.pingcap.net`.
- Run the script directly on a host that has `crane`, `ks3util` and `jq`,
  with a KS3 credential file available.
- Never write your own repair logic: the script validates, resolves the broken
  blob digests from the validate output, verifies every downloaded blob's
  sha256 and re-validates the image afterwards.

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
4. Check the three permissions in *Permissions* (target read, source read,
   bucket write), then run the script. Start interactively so the user sees
   the target, source, bucket, resolved endpoint and the blob list before
   anything is uploaded:

   ```bash
   bash "$SCRIPT" \
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
