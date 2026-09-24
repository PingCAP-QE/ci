# AGENTS.md

This document provides essential information for AI agents working on this repository.

## Project Overview

This is the **PingCAP-QE/ci** repository - a comprehensive CI/CD configuration repository for PingCAP, TiKV, and related organizations. It manages continuous integration pipelines for multiple repositories including:

- `pingcap/tidb` - TiDB database
- `pingcap/tiflash` - TiFlash columnar storage
- `pingcap/tiflow` - Data flow platform
- `tikv/tikv` - Distributed key-value store
- `tikv/pd` - Placement Driver for TiKV
- And more...

The CI system uses **Prow** (Kubernetes-native CI) + **Jenkins** (backend worker) architecture.

## Key Technologies

- **Jenkins** - CI/CD automation server
- **Prow** - Kubernetes-based CI/CD system (prow.tidb.net)
- **Tekton** - Cloud-native CI/CD framework
- **Groovy** - Jenkins pipeline DSL scripts
- **YAML** - Configuration files (Prow jobs, pod templates)
- **Go** - Various CI tools (`error-log-review`, `gomod-sync`, `gethash`, etc.)
- **Kubernetes** - Container orchestration for CI runners

## Repository Structure

```
.
├── docs/                    # Documentation
│   ├── core-concepts.md     # CI architecture overview
│   ├── designs/             # Design documents
│   ├── guides/              # User guides
│   │   └── testing-library-code.md  # Testing shared library code
│   └── jobs/                # Job documentation
├── prow-jobs/               # Prow job trigger configurations
│   └── <org>/<repo>/        # Organized by GitHub org/repo
├── jenkins/                 # Jenkins job definitions (one folder per job)
│   └── jobs/<org>/<repo>/<branch>/<job>/
│       ├── dsl.groovy       # Jenkins Job DSL (pipelineJob)
│       ├── Jenkinsfile      # declarative pipeline
│       └── pod*.yaml        # Kubernetes pod template (optional)
├── tekton/                  # Tekton CI/CD resources
│   └── v<version>/
├── libraries/               # Jenkins shared libraries
│   └── tipipeline/
│       ├── vars/            # Library functions (source)
│       ├── src/             # Helper classes
│       └── test/            # Unit tests (table-driven JUnit 4)
├── tools/                   # CI helper tools
│   ├── error-log-review/    # PR error log checker (Go)
│   ├── gomod-sync/          # Go module sync tool (Go)
│   ├── gethash/             # Git hash utility (Go)
│   └── ...
├── scripts/                 # Utility scripts
├── .ci/                     # CI maintenance scripts
└── configs/                 # Tool configurations
```

## File Naming Conventions

### Prow Jobs (`/prow-jobs/<org>/<repo>/<branch>-<job-type>.yaml`)
- **Branch specifiers**: `latest` (trunk), `release-x.y` (versions)
- **Job types**: `presubmits` (PRs), `postsubmits` (merges), `periodics` (scheduled)

### Jenkins Jobs (`/jenkins/jobs/<org>/<repo>/<branch>/<job>/`)
- One folder per job:
  - `dsl.groovy` — Jenkins Job DSL (`pipelineJob`); its `scriptPath` points at the sibling `Jenkinsfile`.
  - `Jenkinsfile` — declarative pipeline.
  - `pod.yaml` — Kubernetes pod template when the job has exactly one; `pod-<purpose>.yaml` (`pod-build.yaml`, `pod-test.yaml`, `pod-main.yaml`) when it has several; omitted when it has none. Do not repeat the job name in the file name — the job folder already carries it.
  - `aa_folder.groovy` — folder definition, one level above the job folders.
- All `scriptPath` and pod-template references are repo-root-relative.
- The legacy `/jobs/**` and `/pipelines/**` trees were retired by the
  one-folder-per-job migration (merged 2026-09-24); every job now lives under
  `/jenkins/jobs/**`.

## Development Guidelines

### Git Commit Convention

Follow the **Conventional Commits** specification for commit messages:

- Spec: https://www.conventionalcommits.org/en/v1.0.0/
- Format: `<type>(<scope>): <subject>`
  - `type`: e.g. `feat`, `fix`, `docs`, `chore`, `refactor`, `test`, but do not use `ci` type except for `.github` and `.ci` changes because this repo is for designed to manage CI/CD jobs.
  - `scope`: optional but recommended for this repo (e.g. `prow-jobs`, `pipelines`, `jobs`, `tekton`, `tools`, `libraries`)
  - `subject`: imperative, present tense (e.g. "add", "fix", "update")

- **Language**: All commit messages and PR titles/descriptions must be written in English.

Examples:
- `feat(prow-jobs): add presubmit for tiflow lint`
- `fix(tiflow): increase pipeline timeout`
- `docs(agents): document Conventional Commits`
- `test(libraries): add unit tests for parseCIParamsFromPRTitle`

### GitHub PR Comments and Descriptions

Be careful when writing `#NNN`-style references in GitHub PR titles, descriptions, or comments:

- GitHub renders a bare `#NNN` (e.g. `#11`, `#5096`) as a cross-reference link to an issue/PR in the repo, which is usually wrong when the number refers to a Jenkins build number, a commit, a local sequence, or anything else that is not a GitHub issue/PR.
- Use a full URL instead, or wrap the token in backticks/code (e.g. `ghpr_mysql_test #11` inside backticks renders literally) when a cross-reference is not intended.
- When a reference to a GitHub issue/PR *is* intended, prefer the explicit form `<owner>/<repo>#NNN` (or a full URL) over a bare `#NNN` to avoid ambiguity across repos.
- Review rendered text before posting: a wrong auto-link cannot be seen by readers as plain text.

### Jenkins Shared Library Code Organization

Applies to `libraries/*/vars/*.groovy` (Jenkins global variables). Follow these rules so large files stay navigable:

- Put **file-level fields and constants at the top** of the file (e.g. `@Field` caches and resource paths), before the first function.
- Group functions by **feature/cohesion**, not by visibility. Do not sort the whole file by `public`/`private`; a `private` helper must stay next to the public entry that uses it.
- Inside a feature block, put the **public entry first, then its private helpers** (top-down reading).
- Prefix each feature block with a banner comment:

  ```groovy
  // ============================================================
  // <Feature name>
  // ============================================================
  ```

- Prefer a **data/config file over hardcoded branches**: special-case mappings live under `libraries/tipipeline/resources/configs/` (e.g. `component-branch-mapping.yaml`) and are read via `libraryResource` (+ `readYaml`), with a defensive fallback so a missing or invalid config never breaks pipelines. Keep scripts under `resources/scripts/` and other resource kinds in their own subdirectories.
- **Log the branch-resolution reason for every path** — PR-title param, config mapping, or derived/default rule — including the matched rule/source and the resolved branch, so CI logs stay transparent about why a branch was chosen.
- Add or update tests in `libraries/tipipeline/tests/` for any behavior change; keep a golden/characterization table for behavior that must not regress.

## Common Tasks for Agents

### 1. Adding/Modifying CI Jobs

1. Update Prow job trigger in `/prow-jobs/<org>/<repo>/`
2. Edit the job folder `/jenkins/jobs/<org>/<repo>/<branch>/<job>/`: `dsl.groovy`
   (Job DSL), `Jenkinsfile` (pipeline), `pod*.yaml` (pod template)
3. Run `.ci/check-jenkins-job-references.sh` to confirm the references resolve
4. Run `.ci/update-prow-job-kustomization.sh` after Prow job changes

### 2. Pipeline Development Workflow

Per `docs/contributing.md`:
1. Copy pipeline to staging directory with changes
2. Create PR for review
3. Test in staging after PR merge
4. Create PR to move from staging to production
5. Include test results and links in PR

### 3. Testing Library Code

Pure functions in `libraries/tipipeline/vars/` can be tested locally with Groovy + JUnit 4.

See the full guide at `docs/guides/testing-library-code.md`.

**Quick start:**

```bash
# Install Groovy (macOS)
brew install groovy

# Run tests
groovy libraries/tipipeline/tests/TestComponent.groovy
```

**Key practices:**
- Load functions from source via `GroovyShell.parse()` — do not duplicate code
- Use **table-driven** tests: define a table of cases, iterate with `each`
- Name methods in `should` style: `shouldExtractParamsFromTitle()`
- Include assertion messages for failure diagnosis
- Tests live in `libraries/tipipeline/tests/`

### 4. Running Verification Scripts

```bash
# Verify Jenkins pipelines syntax
.ci/verify-jenkins-pipelines.sh

# Update Prow job kustomization
.ci/update-prow-job-kustomization.sh

# Update Tekton kustomizations
.ci/update-tekton-kustomizations.sh
```

### 5. Pre-commit Hooks

This repository uses pre-commit with:
- `end-of-file-fixer` - Ensures files end with a newline
- `trailing-whitespace` - Removes trailing whitespace
- `gitleaks` - Prevents secret leakage

Run before committing:
```bash
pre-commit run --all-files
```

## CI System URLs

- **Prow Dashboard**: https://prow.tidb.net
- **Jenkins Backend**: https://prow.tidb.net/jenkins
- **Prow Commands**: https://prow.tidb.net/command-help
- **Merge Queue**: https://prow.tidb.net/tide

## Important Context

### PR Trigger Commands (from FAQ)
- `/ok-to-test` - Trigger CI for external contributors
- `/hold` - Hold merge
- `/unhold` - Unhold merge
- `/close` or `/reopen` - Close/reopen PR
- `/test <context>` - Trigger specific test

### Common CI Patterns
- TiDB PRs can reference tidb-test PRs: `staistics: fix ... | tidb-test=pr/2114`
- Batch merging supported for tidb, tiflow, pd repos
- Hotfix branches show "Merge is forbidden" until all checks pass

## Tool Development (Go)

Tools are located in `/tools/` directory:
- Each tool has its own `go.mod`
- Use `go build` to compile
- Some tools have configs in `/configs/`

Example:
```bash
cd tools/gomod-sync
go build -o gomod-sync
./gomod-sync --source=from/go.mod --target=to/go.mod
```

## Getting Help

- **FAQ**: `docs/guides/FAQ.md`
- **Testing Guide**: `docs/guides/testing-library-code.md`
- **Contributing Guide**: `docs/contributing.md`
- **DeepWiki**: https://deepwiki.com/PingCAP-QE/ci
- **GitHub Issues**: https://github.com/PingCAP-QE/ci/issues
- **Community Discussions**: https://github.com/PingCAP-QE/ci/discussions

## Approval Requirements

- Approvers defined in `OWNERS` and `OWNERS_ALIASES`
- `sig-approvers-ee` approves CI infrastructure changes
- Project-specific SIGs approve their respective job configs

## License

Apache License 2.0 - See `LICENSE` file
