# Tekton CI/CD Configuration

This directory contains Tekton configuration files for the CI/CD system used by PingCAP, TiKV, and related organizations. Tekton is a cloud-native CI/CD framework that provides Kubernetes-native pipelines, triggers, and other resources.

## Version Information

**IMPORTANT DEPRECATION NOTICE:**

- **`v1/`**: Current, actively maintained version. Use this for all new configurations and modifications.
- **`v0/`**: **DEPRECATED** - Legacy version. Do not use for new development. Existing configurations may still be in use but should be migrated to v1.

### Migration Guidance
If you are maintaining or updating existing CI/CD workflows, please migrate from v0 to v1. The v1 version includes:
- Improved trigger configurations
- Better resource management
- Enhanced CEL interceptor support
- Updated template structures

## Directory Structure

```
tekton/
├── README.md                   # This file
├── OWNERS                      # Approval configuration
├── tests/                      # Test files for Tekton configurations
├── v0/                         # DEPRECATED - Legacy Tekton configurations
│   ├── pipelines/              # Pipeline definitions
│   ├── tasks/                  # Task definitions
│   ├── triggers/               # Trigger definitions and templates
│   └── ...                     # Other v0 resources
└── v1/                         # Current Tekton configurations
    ├── pipelines/              # Pipeline definitions
    ├── tasks/                  # Task definitions
    ├── triggers/               # Trigger configurations (see triggers/README.md)
    │   ├── bindings/           # Trigger bindings
    │   ├── templates/          # Trigger templates
    │   ├── triggers/           # Trigger definitions by environment
    │   └── README.md           # Detailed triggers documentation
    └── ...                     # Other v1 resources
```

## Key Components

### 1. Pipelines
Complete CI/CD workflow definitions that orchestrate the execution of tasks in a specific order.

### 2. Tasks
Individual build steps or operations that can be composed into pipelines.

### 3. Triggers
Triggers handle GitHub events (pushes, PRs, tags) and Harbor image push events to initiate pipeline runs. See [v1/triggers/README.md](v1/triggers/README.md) for detailed information.

### 4. Templates
Pipeline and trigger templates define reusable CI/CD workflows for different component types and build profiles.

### 5. Bindings
Trigger bindings extract and transform event data into pipeline parameters.

## Common Workflows

### GitHub Event Processing
- **Branch Push**: Triggers builds on branch updates
- **Pull Request**: Runs PR validation and tests
- **Tag Creation**: Creates release builds
- **Branch Creation**: Handles new branch setup

### Build Profiles
- `release` - Standard release build, now always for community release build.
- `enterprise` - Enterprise version build
- `community` - Community version build
- `failpoint` - Build with failpoint enabled
- `fips` - FIPS compliant build

## Getting Started

### For New Component CI
1. Review existing configurations in `v1/triggers/`
2. Create appropriate trigger definitions
3. Add necessary bindings and templates
4. Test using the CEL evaluation tools
5. Add to kustomization files

### Testing CEL Interceptors
```bash
# Install the CEL evaluation tool
go install github.com/tektoncd/triggers/cmd/cel-eval@v0.20.2

# Test trigger filters
cel-eval --filename test-event.json --expression "your_cel_expression"
```

## Development Guidelines

### Adding New Triggers
1. Place trigger YAML in appropriate environment directory (`env-prod2/`, `env-gcp/`, etc.)
2. Use consistent naming conventions
3. Include comprehensive CEL filters
4. Add test HTTP files for validation
5. Update relevant kustomization.yaml files

### File Naming Conventions
- Trigger files: `*-trigger.yaml` or descriptive names like `github-pr.yaml`
- Binding files: `*-binding.yaml`
- Template files: `*-template.yaml`

## Resources

- [Tekton Documentation](https://tekton.dev/docs/)
- [Tekton Triggers](https://tekton.dev/docs/triggers/)
- [CEL Expression Language](https://github.com/google/cel-spec)
- [PingCAP CI Documentation](https://deepwiki.com/PingCAP-QE/ci)

## Support

For questions or issues with Tekton configurations:
1. Check the [v1/triggers/README.md](v1/triggers/README.md) for detailed triggers documentation
2. Review existing configurations for similar use cases
3. Contact the CI infrastructure team or check GitHub discussions

---

**Remember**: Always use `v1/` for new development. The `v0/` directory is maintained for legacy compatibility only and should not be modified except for migration purposes.
