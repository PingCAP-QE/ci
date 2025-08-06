# Critical Log Review

A lightweight Go program to automatically check PR diffs for critical logging changes and enforce approval requirements.

## Features

- Regex-based pattern matching for different repositories
- GitHub API integration for PR diff analysis
- Approval verification through ti-chi-bot approval comments
- Configurable CI behavior (fail or warn)

## Usage

### Command Line

```bash
# Basic usage
./critical-log-review -pr "pingcap/tidb#12345" -token "your_github_token"

# With custom config
./critical-log-review -config "custom-config.yaml" -pr "https://github.com/pingcap/tidb/pull/12345"

# Dry run mode
./critical-log-review -pr "pingcap/tidb#12345" -dry-run
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
- name: Check Critical Log Changes
          env:
          GITHUB_API_TOKEN: ${{ secrets.GITHUB_API_TOKEN }}
  run: |
    cd tools/critical-log-review
    go run . -pr "${{ github.repository }}#${{ github.event.number }}"
```

## Build

```bash
go mod download
go build -o critical-log-review
```

## Configuration

See `config.yaml` for repository-specific patterns and approver lists.

## Exit Codes

- `0` - Success (no critical logs found or properly approved)
- `1` - Failure (critical logs found but not approved, when configured to fail)
