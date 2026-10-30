# Tekton CI/CD Configuration

This directory contains Tekton configuration files for the CI/CD system used by PingCAP, TiKV, and related organizations. Tekton is a cloud-native CI/CD framework that provides Kubernetes-native pipelines, triggers, and other resources.

## Version Information

- **`v1/`**: Current, actively maintained version. Use this for all configurations and modifications.

## Directory Structure

```
tekton/
├── README.md                   # This file
├── OWNERS                      # Approval configuration
├── tests/                      # Test files for Tekton configurations
└── v1/                         # Tekton configurations
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

### Image Delivery
`pingcap-deliver-images` has two modes, selected by its `publisher-url` parameter:

- **Service mode** (`publisher-url` set): the task submits every image reference to
  `POST /api/v2/publish/images` on tibuild-v2 and polls
  `GET /api/v2/publish/units/{id}` until every destination is terminal. A
  `partial` or `failed` unit fails the TaskRun. The event sent by
  `post-notify-publisher` carries the unit summaries (per-target state and
  digest).
- **Legacy mode** (`publisher-url` empty): the task keeps generating the crane
  script with the remote deno generator and running it in the pod. This is the
  rollback path during the rollout.

The pipeline parameter is `image-publisher-url` (separate from `publisher-url`,
which still points at the old publisher for TiUP delivery). Only
`pingcap/advanced-statefulset` binds it today; its triggers live under
`v1/triggers/triggers/env-gcp/pingcap/advanced-statefulset/`.

### Build Profiles
- `release` - Standard release build, now always for community release build.
- `enterprise` - Enterprise version build
- `community` - Community version build
- `failpoint` - Build with failpoint enabled
- `fips` - FIPS compliant build

## Environment Status (2026-10-21)

- **`env-gcp/`** is the active trigger environment for the gcp and tencentcloud
  clusters. Darwin targets use the gcp macbuild path
  (`build-component-darwin-mac` / `build-component-all-platforms-mac`).
- **`env-prod2/`** is being retired. Do not add new triggers there; new CI
  should target `env-gcp/`. The prod2 Tekton deployment is tracked in the
  `prod2_tekton_decommission_20261021` Conductor track.

## Getting Started

### For New Component CI
1. Review existing configurations in `v1/triggers/`
2. Create appropriate trigger definitions
3. Add necessary bindings and templates
4. Test using the CEL evaluation tools
5. Add to kustomization files

### Automated Configuration Tests

Tests use Deno/TypeScript in `tests/`, alongside the existing GitHub event helper.
Run from the repository root:

```bash
deno test --frozen --lock=tekton/tests/deno.lock --allow-read --allow-write --allow-env --allow-run=git,sh,deno tekton/tests/*.test.ts scripts/flow/build/versioning-strategy.test.ts
```

Routing tests evaluate the YAML's actual CEL filters and overlays. Git/credential
fixtures run locally and do not submit builds or publish artifacts.

### Testing CEL Interceptors
```bash
# Install the CEL evaluation tool
go install github.com/tektoncd/triggers/cmd/cel-eval@v0.20.2

# Test trigger filters
cel-eval --filename test-event.json --expression "your_cel_expression"
```

## Development Guidelines

### Adding New Triggers
1. Place trigger YAML in the active environment directory (`env-gcp/`); `env-prod2/` is retired.
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

**Remember**: Always use `v1/` for Tekton development.
