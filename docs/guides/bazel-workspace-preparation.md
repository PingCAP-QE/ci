# Bazel Workspace Preparation and Multi-Cloud Cache Configuration

## Background

When CI jobs migrated from AWS to GCP, the legacy bazel cache/mirror endpoints
(`bazel-cache.pingcap.net:8080`, `ats.apps.svc`, `cache.hawkingrei.com`,
`mirror.bazel.build`) became unreachable or unstable. Historically every
pipeline carried its own "Hotfix bazel deps/cache (temporary)" stage that
duplicated the same cleanup logic, making any cache-endpoint change a
shotgun edit across 100+ files.

That logic is now centralized in the `tipipeline` shared library:

- `libraries/tipipeline/vars/bazel.groovy` — the Groovy API
- `scripts/pingcap/tidb/prepare-bazel-workspace.sh` — the actual cleanup logic
  in plain bash (executed from the agent workspace ci checkout)
- `libraries/tipipeline/tests/TestBazel.groovy` — unit + functional tests

## For pipeline authors

Call the helpers from a pipeline inside `dir(REFS.repo)`:

```groovy
stage('Prepare bazel workspace') {
    steps {
        dir(REFS.repo) {
            script { bazel.prepareWorkspace() }
        }
    }
}
```

### `bazel.prepareWorkspace(opts)`

Runs the workspace preparation script. Options:

| Option | Default | Meaning |
|--------|---------|---------|
| `cloud` | `CI_CLOUD_ENV` or `gcp` | Cloud env name, see [envConfig](#cloud-configuration-envconfig) |
| `stripUrls` | from `envConfig` | Legacy cache/mirror URLs to remove from `WORKSPACE`/`DEPS.bzl` |
| `patchCheckTarget` | `true` | Patch the Makefile `check:` target to drop `check-bazel-prepare` |
| `remoteCache` | from `envConfig` | `null`, `[mode: 'disable']` or `[mode: 'set', url: '...']` |
| `repositoryCache` | from `envConfig` | `null`, `'/path'` or `[path: '/path', guard: true|false]` |
| `ensureTmpDir` | `false` | Create the bazel tmp dir (default `/home/jenkins/.tidb/tmp`) |

`remoteCache: [mode: 'disable']` removes `try-import /data/bazel` from
`.bazelrc` and appends `--noremote_accept_cached` / `--noremote_upload_local_results`
for `build`, `test` and `run`. `[mode: 'set', url: '...']` replaces any existing
`--remote_cache=` lines and points all three scopes at the given cache service.

### `bazel.reapplyStaleUrlCleanup(opts)`

Guarded variant used in matrix test stages that restore a workspace from
stash/cache: only re-applies the cleanup when stale URLs are still present.
With the stash/unstash handoff (build-consistent backup) it is redundant and
can be dropped.

### Workspace handoff between matrix pods

Use S3-backed `stash`/`unstash` instead of the legacy `ws/${BUILD_TAG}` cache:

```groovy
// main stage, inside dir(REFS.repo):
stash name: 'ws', includes: '**/*'

// matrix test stage, inside dir(REFS.repo):
unstash 'ws'
```

Stash `includes` are resolved against the execution directory; use `'**/*'`
inside `dir(...)`. Stash excludes `.git` by default, so drop git debug output
on the unstash side.

## Cloud configuration (`envConfig`)

The cloud environment is selected through the `CI_CLOUD_ENV` env var,
injected globally by Jenkins (no per-job definition needed — the same job
definition runs on any cloud). It defaults to `gcp`.

`bazel.envConfig()` returns the per-cloud configuration:

| Field | Meaning |
|-------|---------|
| `stripUrls` | Legacy cache/mirror URLs to remove from `WORKSPACE`/`DEPS.bzl` |
| `remoteCache` | `null`, `[mode: 'disable']` or `[mode: 'set', url: '...']` |
| `repositoryCachePath` | Shared local repository cache path, or `null` (opt-in per job) |

The current `gcp` entry keeps the pre-migration behavior: strip the 4 legacy
URLs, no `.bazelrc` remote-cache override on mainline, shared repository
cache opt-in per job.

## Onboarding a new cloud

To run the same jobs on a new cloud environment:

1. **Add an `envConfig` entry** in `libraries/tipipeline/vars/bazel.groovy`:
   - `stripUrls`: the legacy URLs that are unreachable in the new cloud
   - `remoteCache`: `[mode: 'set', url: 'https://cache.<cloud>...']` to point
     all jobs at the new cloud's bazel cache service, or `[mode: 'disable']`
   - `repositoryCachePath`: the shared repository-cache mount of the new cloud
2. **Adjust pod templates**: mount the cloud's shared cache volumes (e.g. the
   repository-cache path) in the relevant `pod*.yaml` files.
3. **Inject `CI_CLOUD_ENV`** globally on the Jenkins instance running that cloud.

Job definitions do not need to change — they call `bazel.prepareWorkspace()`
without cloud-specific arguments.

## Testing

```bash
groovy libraries/tipipeline/tests/TestBazel.groovy
```

Runs table-driven tests for `stripPattern`/`envConfig`/`workspaceEnv` plus
functional tests that execute `scripts/pingcap/tidb/prepare-bazel-workspace.sh`
against fixture workspaces (GNU and BSD sed compatible). The prow presubmit
`pull-test-jenkins-libraries` runs them on the CI image (groovy:4.0.32-jdk17).
