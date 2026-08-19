# Prow Pod Timeouts and `pod_pending_timeout`

This guide explains the Prow pod lifecycle timeouts, how to configure
`pod_pending_timeout` per job, and the class of problems it solves (e.g. slow
source cloning in the clonerefs init container).

## Background

Jobs with `decorate: true` run the Prow pod utilities, which add an init
container (named `clonerefs`) that clones the source code before the main
containers start.

While init containers are running, the Kubernetes pod phase is still
`Pending`. The Prow controller
([plank](https://docs.prow.k8s.io/docs/components/deprecated/plank/), now
[prow-controller-manager](https://docs.prow.k8s.io/docs/components/core/prow-controller-manager/))
applies timeout garbage collection based on that phase, so a slow clone in the
init container counts against the **pod pending timeout** — not against the
job's `decoration_config.timeout`, which only limits the main container.

## The three pod timeouts

| Field | Cluster-level default | Error description shown on job | Trigger condition |
|-------|-----------------------|--------------------------------|-------------------|
| `pod_unscheduled_timeout` | 5m | `Pod scheduling timeout.` | Pod never got scheduled (`status.startTime` empty) |
| `pod_pending_timeout` | 10m | `Pod pending timeout.` | Pod scheduled but still `Pending` (includes time running init containers) |
| `pod_running_timeout` | 48h | `Job failed.` | Pod stuck in `Running` state |

Both `pod_pending_timeout` and `pod_unscheduled_timeout` can be overridden per
job via `decoration_config` (see below). The cluster-wide defaults live in the
`plank` section of the Prow config, which is managed outside this repository
(in the deployment infra repo).

## How to configure it per job

In `/prow-jobs/<org>/<repo>/<branch>-<job-type>.yaml`, set
`pod_pending_timeout` inside the job's `decoration_config` (it is a Go
`time.Duration` string, e.g. `20m`, `2h`):

```yaml
presubmits:
  pingcap/tiflash:
    - name: pull-sanitizer-tsan
      decorate: true
      decoration_config:
        timeout: 3h
        pod_pending_timeout: 20m
      spec:
        containers:
          - name: run-sanitizer
            image: <builder-image>
```

When using YAML anchors for shared decoration configs, set it once and reuse
it across jobs:

```yaml
global_definitions:
  sanitizer_decoration_config: &sanitizer_decoration_config
    timeout: 3h
    pod_pending_timeout: 20m
    ssh_key_secrets:
      - github-ssh-secret
```

## What problems does it solve?

The most common real-world case is a **slow init-container clone** that
exceeds the 10m default pending timeout:

- Repositories with many submodules: the clonerefs init container updates
  submodules serially with a full clone — `git submodule update --init
  --recursive` (no `--jobs`, no `--depth`). For example, `pingcap/tiflash`
  has 51 submodules, and its sanitizer jobs (`pull-sanitizer-asan`,
  `pull-sanitizer-tsan`) frequently failed with `Pod pending timeout.` even
  though the clone itself was healthy.
- Large repositories over slow network links.
- Slow container image pulls counted while the pod waits in `Pending`.

Setting a per-job `pod_pending_timeout` gives these jobs enough headroom
without affecting other jobs or the cluster-wide default.

## How to diagnose

1. The GitHub check shows the failure description `Pod pending timeout.` (or
   `Pod scheduling timeout.`).
2. On the Prow dashboard, open the job's pod; if the `clonerefs` init
   container is still running while the phase is `Pending`, the timeout is
   being consumed by cloning.
3. Check the clonerefs init container logs for submodule progress.

## Limitations and alternatives

- `pod_pending_timeout` only **prevents spurious cancellation** — it does not
  speed up cloning.
- If clones are long, prefer fixing the root cause instead of (or in addition
  to) raising the timeout:
  - `skip_cloning: true` and clone inside the job container with
    `git clone --depth 1 --recurse-submodules --shallow-submodules -j <N>`,
    which parallelizes submodule fetches and lets the clone time count against
    the job `timeout` instead of the pending timeout.
  - Point clones at a faster GitHub mirror.
- Raising the timeout masks real scheduling problems; if a job is unscheduled
  for a long time, check `pod_unscheduled_timeout` and the pod resource
  requests instead.

## References

- `DecorationConfig.PodPendingTimeout` field documentation:
  https://pkg.go.dev/sigs.k8s.io/prow/pkg/apis/prowjobs/v1#DecorationConfig
- Source: `pkg/apis/prowjobs/v1/types.go`:
  https://github.com/kubernetes-sigs/prow/blob/main/pkg/apis/prowjobs/v1/types.go
- Controller behavior: `pkg/plank/reconciler.go`:
  https://github.com/kubernetes-sigs/prow/blob/main/pkg/plank/reconciler.go
- Documented Prow config with all options (cluster-level `plank` section):
  https://github.com/kubernetes-sigs/prow/blob/main/pkg/config/prow-config-documented.yaml
