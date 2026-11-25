# Actions runner image

This image extends the official GitHub Actions runner for ARC with a small set
of commonly used build and archive tools. It is not a full replacement for the
[GitHub-hosted Ubuntu VM](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md).

The base image supplies the runner, Kubernetes hooks, Git, curl, jq, unzip and
Docker CLI. This image adds coreutils, gzip, make, GNU tar, wget, xz and zip.
Go, Node.js and other language toolchains are not preinstalled: use the
appropriate `actions/setup-*` action. A setup action may need network access
to download a tool. Docker CLI requires an accessible Docker daemon; this image
does not start one.

The TiUP release workflow additionally needs Go (provided by `setup-go`), Bash,
Git, make, curl, GNU tar, gzip, coreutils (`sha256sum`, `b2sum`, `mktemp` and
others), and network access to its mirrors. Its release build disables CGO, so
this image does not add a C compiler for that build.

## Publishing and updates

The image is built and published with [skaffold](https://skaffold.dev) from the
`ci-actions-runner` module in `../skaffold.yaml`. The
`release-ci-runtime-images.yaml` workflow publishes
`ghcr.io/pingcap-qe/ci/actions-runner` for `linux/amd64` and `linux/arm64`. It
runs on relevant changes to `main`, manual dispatch on `main`, and monthly.

Images are tagged with skaffold's default `gitCommit` tagger (`git describe
--tags --always`, e.g. `v2026.11.22-1-gd182790`). Each run pushes that multi-arch
tag, smoke tests the resulting digest on both architectures, and only then
updates `latest`. A failed smoke test leaves the git tag published but does not
move `latest`. The package may need to be made public or granted access to
consuming repositories before runners can pull it.

The git-derived tag and `latest` are mutable: the monthly rebuild reuses the
same tag so it can refresh the explicitly installed Ubuntu packages, and a
later run for the same commit replaces it. For reproducible deployments use the
image digest. The upstream runner version and digest are pinned in the
Dockerfile and should be updated via a reviewed PR. Monthly rebuilds do not
update the pinned runner or all packages inherited from it.

## Build and test

The workflows and the steps below use skaffold 2.16.0. Run skaffold from
`dockerfiles/ci` so the module in `skaffold.yaml` is visible:

```sh
cd dockerfiles/ci
skaffold build \
  --module=ci-actions-runner \
  --push=false \
  --platform=linux/amd64 \
  --file-output=/tmp/actions-runner-build.json
bash actions-runner/smoke-test.sh \
  "$(jq -r '.builds[0].tag' /tmp/actions-runner-build.json)"
```

Use `--platform=linux/arm64` on ARM hosts.

In the ARC runner scale set Helm values, replace the image with a tested
version and preserve the `runner` container name and command:

```yaml
template:
  spec:
    containers:
      - name: runner
        image: ghcr.io/pingcap-qe/ci/actions-runner@sha256:<tested-digest>
        command: ["/home/runner/run.sh"]
```

For private packages, configure the appropriate `imagePullSecrets` for the
runner pod. See the [ARC deployment documentation](https://docs.github.com/en/actions/how-tos/manage-runners/use-actions-runner-controller/deploy-runner-scale-sets).
