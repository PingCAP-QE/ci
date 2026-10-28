# Hotfix branch build isolation

## Background

Hotfix branches are usually created **from a GA tag** (for example
`release-8.5-20261020-abc1234` from `v8.5.8`, or
`release-8.5.8-release.1-20261020` from `v8.5.8`). Right after creation the
branch points at the tagged commit, so `git describe --tags` returns the bare
GA version (`v8.5.8`).

Two things made that dangerous:

1. the generic branch-push trigger also matched hotfix branch names, so the
   build ran immediately on branch creation;
2. the build pushed the same OCI tags as the release build
   (`v8.5.8_linux_amd64` in `.../hub/...`), overwriting the released artifact,
   and reported the artifact to the publisher, which can publish it to the tiup
   mirrors.

The root cause (version derivation ignoring the triggering ref type) is being
fixed separately. This document describes the containment added to the
triggers and the registry layout.

## Design

- **Separate trigger.** Hotfix branches are handled by their own trigger
  (`_/git-push-branch-build-hotfix-linux.yaml`) instead of being folded into
  `_/git-push-branch-build-community-linux.yaml`. Changes to hotfix handling
  therefore cannot affect regular branch builds, and vice versa.
- **Separate registry.** Hotfix branch builds push to
  `us-docker.pkg.dev/pingcap-testing-account/hotfix` via the
  `gcp-hotfix-build-params` binding, so they cannot overwrite the artifacts in
  `hub`. Note that `hotfix` already holds officially released hotfix artifacts
  built from release-anchor tags (`vX.Y.Z-YYYYMMDD-<sha>`), therefore its
  retention policy must stay non-destructive (see below).
- **No delivery.** The same binding sets `publisher-url` to the empty string,
  so `pingcap-deliver-binaries` does not notify the tiup publisher. In
  addition, `packages/delivery.yaml` rules only match the `hub` and `tidbx`
  registries, so `hotfix` artifacts cannot be delivered even if the publisher
  is called by mistake.
- **No `ctl`.** `ctl` is assembled from other components' OCI packages, which
  are not built into `hotfix`. Hotfix candidates of `ctl` have no value, so the
  component is not built there. Its dependencies
  (`pingcap/third-party/etcd`, `pingcap/tidb-ctl/package`) stay read-only in
  `hub`.
- **Branch pushes only.** Hotfix **release tags** (`vX.Y.Z-release.N`) remain
  official releases: they keep using `hub` and the publisher, in their own
  dedicated tag trigger.

## Registry layout

| Registry | Content | Delivered |
| --- | --- | --- |
| `hub` | artifacts built from tag refs and from `master`/`main`/`release-X.Y` branches | yes (images + tiup) |
| `hotfix` | officially released hotfix artifacts built from `vX.Y.Z-YYYYMMDD-<sha>` tags, plus (for now) hotfix branch candidates | tag-built ones by their own flow |
| `dev` | ad-hoc / custom builds | no |
| `tidbx` | nextgen builds | per delivery rules |

## Retention

The `hotfix` registry stores both officially released hotfix artifacts (built
from release-anchor tags `vX.Y.Z-YYYYMMDD-<sha>`) and branch-built tags
(`release-X.Y-YYYYMMDD-vX.Y.Z_<os>_<arch>`). Released artifacts are kept, while
branch-built tags are pruned after 30 days by an Artifact Registry cleanup
policy (`tag_prefixes = ["release-"]`).

Decision: hotfix branch candidates stay in the `hotfix` registry for now (no
dedicated candidate repository). Only branch-name tags with the `release-` prefix
are pruned automatically.

Two constraints are worth knowing:

- Artifact Registry KEEP policies win over DELETE policies, so the repository
  must not define a blanket `KEEP tagged` rule together with the pruning rule.
- Tags of the form `vX.Y.Z-<profile>` (for example `v8.5.6-pre`), which also come
  from builds at a tagged commit, share the `vX.Y.Z-` prefix with the released
  `vX.Y.Z-YYYYMMDD-<sha>` tags and cannot be pruned with prefix-based cleanup.
  They are pruned either by not writing candidates into this registry (dedicated
  candidate repository) or by a regex-based cleanup job.

## Known risk

Candidate branch builds share the `hotfix` registry with the official
release-anchor artifacts. A branch created **from a hotfix tag** (for example
`v8.5.8-20261016-5feda11`) makes `git describe` return that tag version, so the
candidate build would push `v8.5.8-20261016-5feda11_<os>_<arch>` and overwrite
the released artifact. Until version derivation is fixed, candidates should be
redirected to a dedicated repository (for example `hotfix-candidate`) so that
no branch build can touch release artifacts.

## Follow-ups

- Fix the version computation so a non-tag ref can never produce a bare
  semver version (`PingCAP-QE/ci`, `versioning-strategy.ts`).
- Refuse to overwrite immutable release tags in the artifacts build templates
  (`PingCAP-QE/artifacts`, `packages/scripts/*.sh.tmpl`).
- Make the publisher publish by digest and fail on version/content conflicts.
- Add a periodic OCI-vs-tiup-mirror hash check.
- Add a darwin hotfix trigger if macOS hotfix candidates are needed (today
  only linux hotfix branches are built).
