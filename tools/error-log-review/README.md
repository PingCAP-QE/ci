# Error Log Review

A lightweight Go program to automatically check PR diffs for error logging changes and enforce approval requirements.

## Features

- Regex-based pattern matching for different repositories
- GitHub API integration for PR diff analysis
- Approval verification through ti-chi-bot approval comments
- Configurable CI behavior (fail or warn)

## Usage

> **Note**: The default configuration file is located at `configs/error-log-review/config.yaml` in the repository root directory. See `config.yaml.example` in this directory for a configuration template.

### Command Line

```bash
# Basic usage (uses default config from configs/error-log-review/config.yaml)
./error-log-review -pr "pingcap/tidb#12345" -token "your_github_token"

# With custom local config file
./error-log-review -config "custom-config.yaml" -pr "https://github.com/pingcap/tidb/pull/12345"

# Dry run mode
./error-log-review -pr "pingcap/tidb#12345" -dry-run
```

### Pinned reference in CI

The `pull-error-log-review` presubmits run this tool straight out of this
repository, so they pin **both** the binary and the policy to a single commit:

```
go run github.com/PingCAP-QE/ci/tools/error-log-review@<sha> -config /tmp/config.yaml ...
wget -O /tmp/config.yaml https://cdn.jsdelivr.net/gh/pingcap-qe/ci@<sha>/configs/error-log-review/config.yaml
```

A mutable ref (`@main`) would let a push that did not go through review — or a
compromise of the CDN — change both the code and the approver list while
`GITHUB_API_TOKEN` is in the environment of the job. When you change this tool or
`configs/error-log-review/config.yaml`, bump the two refs to the merge commit in
these six files, which is a mechanical search for the old SHA:

- `prow-jobs/tikv/pd/latest-presubmits.yaml`
- `prow-jobs/tikv/tikv/latest-presubmits.yaml`
- `prow-jobs/pingcap/ticdc/latest-presubmits.yaml`
- `prow-jobs/pingcap/tiflow/latest-presubmits.yaml`
- `prow-jobs/pingcap/tidb/latest-presubmits.yaml`
- `prow-jobs/pingcap/tiflash/latest-presubmits.yaml`

The pin is deliberately not automatic: a change to the approver list does not
take effect until one of the above is updated, and that update is visible in
review.

### Environment Variables

The program can also read configuration from environment variables:

- `GITHUB_API_TOKEN` - GitHub personal access token
- `GITHUB_BROKER_URL` - in-cluster `github-token-broker` base URL, used instead of a PAT
- `GITHUB_REPOSITORY_OWNER` - Repository owner (e.g., "pingcap")
- `GITHUB_REPOSITORY` - Repository name (e.g., "tidb")
- `GITHUB_PR_NUMBER` - PR number (e.g., "12345")

### Broker-minted tokens

Instead of a long-lived PAT, the tool can mint a GitHub App installation token
from the in-cluster `github-token-broker`:

```bash
./error-log-review -pr "pingcap/tidb#12345" \
  -broker-url http://github-token-broker.github-token-broker.svc
```

The broker identifies the caller with the Pod ServiceAccount token
(`-broker-caller-token-file`, default
`/var/run/secrets/kubernetes.io/serviceaccount/token`) through a `TokenReview`,
and only mints a token if its policy grants that namespace and ServiceAccount
the repository. The request defaults to `-broker-permissions
pull_requests=read,issues=read`, which is what the diff and comment lookups
need. Nothing long-lived is written to the Pod.

Use this instead of `GITHUB_API_TOKEN` where no PAT should sit in the Pod: the
token is scoped to the repository and permissions the broker policy grants the
caller, and it expires with the job.

### CI Integration

Example GitHub Actions workflow:

```yaml
- name: Check Error Log Changes
          env:
          GITHUB_API_TOKEN: ${{ secrets.GITHUB_API_TOKEN }}
  run: |
    cd tools/error-log-review
    go run . -pr "${{ github.repository }}#${{ github.event.number }}"
```

## Build

```bash
go mod download
go build -o error-log-review
```

## Configuration

The tool uses a YAML configuration file to define:

- Repository-specific log patterns to detect
- Required approvers for each repository
- Exclusion patterns to skip certain files/directories
- Global behavior settings

See `config.yaml.example` for a detailed configuration template with comments.

### Configuration Structure

```yaml
repositories:
  - name: "owner/repo"
    patterns:
      - name: "pattern_name"
        description: "Pattern description"
        regex: "regular_expression"
        excludes:  # Optional: pattern-specific exclusions
          - "tests/**"
          - "*_test.go"
    approvers:
      - "github_username1"
      - "github_username2"
    excludes:  # Optional: repository-level exclusions (apply to all patterns)
      - "vendor/**"
      - "third_party/**"

settings:
  min_approvals: 1
  require_repo_specific_approvers: true
  check_behavior:
    mode: "check_and_fail"  # or "check_and_warn"
```

### Exclusion Patterns

You can exclude files from being checked using glob patterns at two levels:

1. **Pattern-specific excludes**: Apply only to specific patterns
   ```yaml
   patterns:
     - name: "string_literals"
       regex: "..."
       excludes:
         - "tests/**"           # Exclude all files under tests/ directory
         - "*_test.go"          # Exclude all Go test files
         - "examples/**"        # Exclude all files under examples/ directory
   ```

2. **Repository-level excludes**: Apply to all patterns in the repository
   ```yaml
   repositories:
     - name: "owner/repo"
       excludes:
         - "vendor/**"
         - "third_party/**"
       patterns:
         - name: "..."
   ```

Glob pattern syntax:
- `*` matches any sequence of non-separator characters
- `**` at the end of a path matches that directory and all subdirectories
- `?` matches any single non-separator character
- Examples:
  - `tests/**` - matches all files under tests/ directory and subdirectories
  - `*_test.go` - matches all test files in the root directory
  - `pkg/*/test.go` - matches test.go in any immediate subdirectory of pkg/

## Exit Codes

- `0` - Success (no error logs found or properly approved)
- `1` - Failure (error logs found but not approved, when configured to fail)
