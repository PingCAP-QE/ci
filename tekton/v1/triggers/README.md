# Triggers Configuration Guide

This directory contains Tekton trigger configurations for various CI/CD workflows. The triggers handle different GitHub events and Harbor image push events to trigger appropriate pipeline runs.

## Directory Structure

```
triggers/
├── bindings/                  # Trigger bindings
├── templates/                 # Trigger templates
├── triggers/                  # Trigger definitions
│   ├── env-gcp/               # Active triggers for gcp + tencentcloud
│   │   ├── _/                 # Common triggers (incl. darwin macbuild)
│   │   ├── pingcap-inc/       # pingcap-inc org specific triggers
│   │   ├── tikv/              # TiKV org specific triggers
│   ├── env-prod2/             # Legacy prod2 triggers (being retired)
│   │   ├── _/                 # Common triggers for prod2
│   │   ├── PingCAP-QE/        # PingCAP-QE org specific triggers
│   │   ├── ctl/               # ctl TiUP PKG related triggers
│   │   ├── harbor/            # OCI registry related triggers, currently support for Harbor events.
│   │   ├── pingcap/           # PingCAP ORG repositories triggers
│   │   ├── pingcap-inc/       # pingcap-inc org specific triggers
```

> **Environment status (2026-10-21):** `env-gcp/` is the active environment for
> gcp and tencentcloud; darwin builds use the gcp macbuild path. `env-prod2/`
> is being retired — do not add new triggers there.

## Security model

The trigger plane creates PipelineRuns/TaskRuns that clone repositories with
CI credentials, so these invariants are not optional. They are enforced by the
files in this directory and covered by `tekton/tests/trigger-security.test.ts`.

### 1. Request headers are never a trust source

Values read with `header.canonical('ce-param...')` are set by whoever POSTs to
the EventListener. They may select between options that are **already**
authorized, but they must never admit a repository or replace its identity.

- The repository allowlist is always derived from `body.repository.full_name`
  (or `body.repository.owner.login` plus `body.repository.name`).
- Devbuild (fake-github) routes accept **only the allow-listed upstream
  repositories**, matched on the exact `body.repository.full_name`. A renamed
  or temporary fork is rejected: a repository name is not a unique key across
  organisations, so matching on it would let e.g. `attacker/tidb` through.
  Building renamed/temporary forks is intentionally disabled for now — see
  "Known regression" in item 4.
- `ce-paramProduct` may pick the build component, but the filter requires it to
  agree with the repository name (with the `cloud-storage-engine`/`tikv` and
  `tiflash-cse`/`tiflash` aliases). It never widens the repository allowlist.
- Do not re-introduce a "this repo is allowlisted" header such as
  `ce-paramGithubRepo`. That pattern let any non-allowlisted repository through
  (security finding `655d8930-bebf-4acc-9a20-2eddb0383c20`).

### 2. The clone URL is derived from the allowlisted full name

The `github-{branch-push,pr,tag-create,branch-create}` TriggerBindings build
`git-url` as `https://github.com/$(body.repository.full_name).git`. They never
bind `body.repository.clone_url`, so the repository that is cloned cannot
differ from the repository the CEL allowlist approved
(security finding `afe15663-40ae-4f4b-af1e-9aed7cfddadf`).

### 3. GitHub webhooks are authenticated by HMAC at the EventListener

GitHub App `214286` (`ti-chi-bot`) delivers to Prow, whose `prow-tekton`
external plugin forwards the original payload **and headers** (including
`X-Hub-Signature-256`) to the `github` EventListener. The EventListeners are
deployed from `PingCAP-QE/ee-ops`
(`apps/{gcp,tencentcloud}/tekton/configs/triggers/event-listeners/el-github.yaml`)
and verify the signature with the Tekton `github` interceptor and the
`el-github-webhook` secret (an `ExternalSecret` over the same Secret Manager
key Prow uses, `gcp_prow_github_app_webhook_hmac`).

Signature verification is owned by that deployment, not by this repository.
Every `type: github-*` Trigger here is selected by `el-github`, whose
triggerGroups run the `github` interceptor (`PingCAP-QE/ee-ops` PR #2619, merged
2026-11-04). This repository deliberately does **not** repeat the interceptor on
each Trigger: one place means no drift, and dozens of copies of a security
control make the diff harder to review rather than safer
(security findings `b85e5f9d-8a2d-4639-aad1-f1634f5c4563` and
`f3ff97ab-c1d8-401f-b2c3-6da9a090d0de`).

Invariant to keep: every `type: github-*` Trigger must be selected by
`el-github`. If a new EventListener ever selects these Triggers, it must verify
the GitHub signature too.

**Deployment requirement:** the `el-github-webhook` secret must exist in the
EventListener namespace (`ee-cd`); it is created by `PingCAP-QE/ee-ops`, not by
this repository. If it is missing every webhook Trigger fails closed (events
are dropped) and the EventListener logs `error getting secret`. Do not invent a
secret value and do not remove the `secretRef` to make a non-standard
deployment work.

### 4. Devbuild (fake-github) and other non-GitHub listeners

`fake-github-*` Triggers are selected by the `tibuild` EventListener
(`PingCAP-QE/ee-ops`), which receives CloudEvents from the in-cluster
`tibuild-v2` service. Those events are not GitHub-signed, so a GitHub HMAC
interceptor cannot be used. Compensating controls are the strict allow-listed
`body.repository.full_name` (item 1) and the derived clone URL (item 2), plus
the fact that the EventListener is a ClusterIP service.

**Known regression (accepted 2026-11-06).** tibuild regularly builds renamed or
temporary forks, and the pre-hardening filter allowed those through the
`ce-paramGithubRepo` escape hatch — i.e. only because of the vulnerability
itself. With the strict `body.repository.full_name` allowlist in place those
builds are now rejected by the triggers. This is a deliberate, temporary
trade-off while the `el-tibuild` transport is unauthenticated: fix the
transport (follow-up task-9 in `PingCAP-QE/ee-ops`), then re-open fork
devbuild in a reviewed way. Do **not** restore it by matching on
`body.repository.name`.

Remaining work, owned outside this repository: authenticate that transport (for
example restrict `el-tibuild` ingress to the tibuild namespace with a
NetworkPolicy, or sign the CloudEvent in `tibuild-v2` / `ee-ops`). A shared
secret passed in a header is **not** an acceptable substitute — headers are
attacker-controlled, exactly like the removed `ce-paramGithubRepo`.

The `harbor` (`type: image-push`) and `notify` (`type: ce-notify`) triggers are
likewise not GitHub webhooks; their listener authentication is owned by the
ingress/EventListeners in `PingCAP-QE/ee-ops`.

## How to test CEL interceptors

## Testing CEL Interceptors

To test the CEL (Common Expression Language) interceptors locally:

1. Install the CEL evaluation tool:
```bash
go install github.com/tektoncd/triggers/cmd/cel-eval@v0.20.2
```

2. Test trigger filters using the provided HTTP files in each component directory

## Key Trigger Types

- **GitHub Events**
  - Branch Push (`github-branch-push`)
  - Pull Request (`github-pr`)
  - Tag Creation (`github-tag-create`)
  - Branch Creation (`github-branch-create`)

- **Harbor Events**
  - Image Push (`image-push`)
  - Artifact Push (`artifact-push`)

## Build Profiles

- `release` - Standard release build
- `enterprise` - Enterprise version build
- `enterprise-without-plugins` - Enterprise build without plugins
- `failpoint` - Build with failpoint enabled
- `community` - Community version build
- `fips` - FIPS compliant build
- `rust` - Standalone Rust SQL node (`tidb-server`) for `pingcap/tidb@hparser-integration`; linux amd64/arm64, publishes the `tidb` tiup package to the staging mirror only, builds no container images

## Common Parameters

- `timeout` - Build timeout duration
- `source-ws-size` - Workspace size for source code
- `builder-resources-cpu` - CPU resources for builder
- `builder-resources-memory` - Memory resources for builder
- `profile` - Build profile type
- `component` - Component name
- `os` - Target operating system
- `arch` - Target architecture

## Adding New Triggers

1. Create trigger YAML in appropriate directory
2. Add filter conditions using CEL expressions
3. Define appropriate bindings and template references
4. Add to `kustomization.yaml`
5. Create test HTTP file
6. Test locally before deployment

## Resources

- [Tekton Triggers Documentation](https://tekton.dev/docs/triggers/)
- [Tekton CEL expression extensions](https://tekton.dev/docs/triggers/cel_expressions/)
- [CEL Specification](https://github.com/google/cel-spec)
