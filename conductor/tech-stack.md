# Tech Stack

This document records the technology stack actually used by PingCAP-QE/ci (an as-code CI/CD configuration and tooling monorepo).

## Configuration & Pipeline Definition Languages

| Technology | Usage | Locations |
|---|---|---|
| **YAML** | Prow job definitions (presubmits/postsubmits/periodics), Tekton Tasks/Pipelines/TriggerBindings/TriggerTemplates, Kubernetes pod templates, tool configs | `prow-jobs/`, `tekton/v0`, `tekton/v1`, `pipelines/.../pod-*.yaml`, `configs/` |
| **Groovy** | Jenkins Job DSL and declarative pipeline scripts; shared pipeline libraries (functions + helper classes) | `jobs/`, `pipelines/`, `libraries/tipipeline`, `libraries/tisys` |

## CI/CD Orchestration Platforms

| Platform | Role |
|---|---|
| **Prow** (Kubernetes-native) | PR trigger/tide/merge-queue layer, hosted at prow.tidb.net |
| **Jenkins** | Backend worker executing heavy builds/tests triggered by Prow jobs (prow.tidb.net/jenkins) |
| **Tekton v0 / v1** | Cloud-native CD & backend worker flows: build, delivery, release, hotfix, Harbor/GitHub event routing, env-gcp/env-prod2 triggers |

## Tooling Languages & Runtimes

| Technology | Usage | Locations |
|---|---|---|
| **Go** | Standalone CI helper tools (PR error log review, gomod sync, dependency listing, deprecated release checkers) | `tools/error-log-review`, `tools/gomod-sync`, `tools/list-go-dependencies`, `tools/deprecated/` |
| **Deno / TypeScript** | CI automation & operational tooling (release flow scripts, ops statistics/analytics, GitHub/Prow automation, flaky-test reporters, S3 cache/notification plugins) | `scripts/flow/`, `scripts/ops/`, `scripts/pingcap/`, `scripts/plugins/`, `tools/reporters/ci/flaky-tests/`, `tools/list-rust-dependencies/` |
| **Python** | Legacy ops scripts (Jenkins build-log parsing, deprecated release checkers) | `scripts/ops/parse-jenkins-build-log`, `tools/deprecated/release-checker`, `tools/deprecated/release-check-version` |
| **Shell** | CI maintenance & verification scripts | `scripts/`, `.ci/` |

## Supporting Infrastructure

| Technology | Usage |
|---|---|
| **Kubernetes** | Container orchestration for CI runners and Tekton execution |
| **Docker** | CI base images and pod containers (`dockerfiles/ci`, `dockerfiles/ci/tici`) |
| **kustomize** | Manifest generation for prow-jobs and tekton resources |
| **GitHub API / Prow CLI (ghpr)** | PR automation, job triggers, owners management |

## Development & QA Tooling

- **pre-commit** hooks: end-of-file-fixer, trailing-whitespace, gitleaks (secret scanning)
- **Conventional Commits** for commit/PR conventions
- **Go modules** per tool under `tools/` (each tool has its own `go.mod`)
- **deno.json** task definitions for Deno-based tools
- **Groovy + JUnit 4** unit tests for shared pipeline library functions (`libraries/tipipeline/tests`, `libraries/tisys/tests`)
