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

### Environment Variables

The program can also read configuration from environment variables:

- `GITHUB_API_TOKEN` - GitHub personal access token
- `GITHUB_REPOSITORY_OWNER` - Repository owner (e.g., "pingcap")
- `GITHUB_REPOSITORY` - Repository name (e.g., "tidb")
- `GITHUB_PR_NUMBER` - PR number (e.g., "12345")

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
    approvers:
      - "github_username1"
      - "github_username2"

settings:
  min_approvals: 1
  require_repo_specific_approvers: true
  check_behavior:
    mode: "check_and_fail"  # or "check_and_warn"
```

## Exit Codes

- `0` - Success (no error logs found or properly approved)
- `1` - Failure (error logs found but not approved, when configured to fail)
