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
├── jobs/                    # Jenkins job DSL definitions
│   └── <org>/<repo>/
│       └── <branch>/        # Branch-specific configs
├── pipelines/               # Jenkins pipeline implementations
│   └── <org>/<repo>/
│       └── <branch>/
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
├── jenkins/                 # [Deprecated] Legacy configs
├── .ci/                     # CI maintenance scripts
└── configs/                 # Tool configurations
```

## File Naming Conventions

### Prow Jobs (`/prow-jobs/<org>/<repo>/<branch>-<job-type>.yaml`)
- **Branch specifiers**: `latest` (trunk), `release-x.y` (versions)
- **Job types**: `presubmits` (PRs), `postsubmits` (merges), `periodics` (scheduled)

### Jenkins Jobs (`/jobs/<org>/<repo>/<branch>/<job-type>_<job-name>.groovy`)
- **Job types**: `pull` (PR tests), `merged` (post-merge), `periodics` (scheduled)
- **Naming**: `[a-z][a-z0-9_]*[a-z0-9]`

### Jenkins Pipelines (`/pipelines/<org>/<repo>/<branch>/`)
- Pipeline scripts: `*.groovy`
- Pod templates: `pod-*.yaml`

## Development Guidelines

### Git Commit Convention

Follow the **Conventional Commits** specification for commit messages:

- Spec: https://www.conventionalcommits.org/en/v1.0.0/
- Format: `<type>(<scope>): <subject>`
  - `type`: e.g. `feat`, `fix`, `docs`, `chore`, `ci`, `refactor`, `test`
  - `scope`: optional but recommended for this repo (e.g. `prow`, `pipelines`, `jobs`, `tekton`, `tools`, `libraries`)
  - `subject`: imperative, present tense (e.g. "add", "fix", "update")

Examples:
- `ci(prow): add presubmit for tiflow lint`
- `pipelines(tiflow): increase pipeline timeout`
- `docs(agents): document Conventional Commits`
- `test(libraries): add unit tests for parseCIParamsFromPRTitle`

## Common Tasks for Agents

### 1. Adding/Modifying CI Jobs

1. Update Prow job trigger in `/prow-jobs/<org>/<repo>/`
2. Update Jenkins job DSL in `/jobs/<org>/<repo>/<branch>/`
3. Update pipeline script in `/pipelines/<org>/<repo>/<branch>/`
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
groovy libraries/tipipeline/test/TestComponent.groovy
```

**Key practices:**
- Load functions from source via `GroovyShell.parse()` — do not duplicate code
- Use **table-driven** tests: define a table of cases, iterate with `each`
- Name methods in `should` style: `shouldExtractParamsFromTitle()`
- Include assertion messages for failure diagnosis
- Tests live in `libraries/tipipeline/test/`

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
- **Jenkins Backend**: https://do.pingcap.net/jenkins
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
