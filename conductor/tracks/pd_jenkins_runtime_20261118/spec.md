# Specification: Reduce PD Jenkins preparation and unit-test overhead

## Goal
Reduce repeated toolchain downloads, compiler work, and serialized preparation
while preserving PD test coverage, assertions, runner identities, and resources.

## Scope
- Stage the existing real-cluster Jenkins job with concurrent PD compilation and
  peer downloads, a published Go 1.27 image, and per-PR Go caches.
- Update the three existing NextGen unit jobs to use the published Go 1.27 image
  and the PD three-runner entry point when supported. Preserve legacy grouping
  for older PD branches.
- Keep production Jenkins files unchanged until staging verification and promotion.

## Acceptance Criteria
- Compilation and downloads finish successfully before binary verification/tests.
- Cache hits still build the current revision. Cache snapshots contain preparation
  data and are saved before test execution. Do not cache `bin/pd-server` or
  use its existence to skip the build.
- All three unit job names, resource limits, scopes, failure exits, and NEXT_GEN
  settings remain unchanged. Legacy branches run all original indices.
- Image manifests resolve for linux/amd64 and provide Go 1.27.3 or newer.
- Repository validators pass. Staging/replay and complete CI timing evidence are
  required before production promotion or a five-minute performance claim.
