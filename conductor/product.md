# Initial Concept

A centralized, as-code repository (PingCAP-QE/ci) that defines and manages continuous integration/continuous delivery for PingCAP, TiKV and related GitHub organizations. It combines Prow triggers, Jenkins Job DSL, Jenkins pipeline implementations, Tekton v0/v1 resources, and shared libraries into one governed monorepo organized by <org>/<repo>/<branch>.

# Product Overview

The product is a single source of truth for all CI/CD configuration across the PingCAP/TiDB product line. Every CI job is represented by three collaborating artifacts: a Prow trigger in prow-jobs/, a Jenkins Job DSL definition in jobs/, and a Jenkins pipeline implementation in pipelines/, complemented by shared libraries, Tekton delivery/release resources, and operational scripts. The repo is governed by OWNERS-based approvals, staged promotion, pre-commit hooks, and Conventional Commits.

# Vision

Reliable, consistent, and easy-to-maintain CI/CD at scale for the entire TiDB/TiKV ecosystem. CI owners, SIGs, and release engineers should be able to discover how a job works, change it safely, test it in staging, and promote it to production with confidence, while every supported release branch stays continuously green.

# Target Users

- CI owners & SIGs: review and approve job/pipeline changes, own branch support.
- Product developers (TiDB/TiKV/TiFlash/TiFlow/PD/...): rely on presubmit/postsubmit jobs for fast PR feedback.
- Release engineers: operate delivery, artifact publication and RC-to-GA flows.
- Infrastructure operators: run prow.tidb.net and the Jenkins/Tekton backend clusters.

# Success Criteria

- Reliable CI: repeatable, fast presubmit/postsubmit feedback across all maintained branches.
- Safe staged rollout: changes verified in staging before promotion to production.
- Config consistency: uniform structure, naming and documentation for jobs/pipelines.
- Governance & compliance: OWNERS approvals, pre-commit hooks, gitleaks, Conventional Commits, tidy EOL handling.

# Focus Areas & Priorities

1. Manage all CI/CD configurations across Prow, Jenkins and Tekton for every product repo.
2. Branch maintenance: keep jobs/pipelines in sync with the supported branch matrix (latest + every release-X.Y).
3. Reliability of presubmit/postsubmit and periodic jobs; fast feedback on PRs.

# Product Principles

- Configuration as code: everything versioned, reviewed and reproducible.
- Changes to production first proven in staging.
- Clarity and discoverability: docs, diagrams and consistent naming make job behavior obvious.
- Automation over toil: verification scripts, kustomization updates, pre-commit hooks.
- Security: secrets never committed (gitleaks); permissions enforced via OWNERS.

# Out of Scope (non-goals)

- Source code of the products themselves (tidb/tikv/etc. live in their own repos).
- Runtime infrastructure provisioning for the CI clusters beyond the pipeline resources declared here.
