# Triggers Configuration Guide

This directory contains Tekton trigger configurations for various CI/CD workflows. The triggers handle different GitHub events and Harbor image push events to trigger appropriate pipeline runs.

## Directory Structure

```
triggers/
├── _/                          # Common triggers
│   ├── fake-github/           # Test triggers simulating GitHub events from TiBuild.
│   ├── harbor/                # Harbor registry related triggers
├── PingCAP-QE/                # PingCAP-QE org specific triggers
├── pingcap/                   # PingCAP ORG repositories triggers
│   ├── _/                     # Common triggers for PingCAP ORG repos
│   ├── ctl/                   # ctl TiUP PKG related triggers
│   ├── advanced-statefulset/
│   ├── tidb/
│   ├── tidb-binlog/
│   ├── tidb-ctl/
│   ├── tidb-operator/
│   ├── tidb-tools/
│   ├── tiflow-operator/
│   ├── tiproxy/
├── tikv/                      # TiKV ORG repositories triggers
│   ├── _/                     # Common triggers for TiKV ORG repos
│   ├── tikv/                  # TiKV specific triggers
```

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
- [CEL Specification](https://github.com/google/cel-spec)
