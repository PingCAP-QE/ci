---
name: prow-image-bump-keep-variant
description: "Bump container image tags in Prow job YAMLs under prow-jobs/ while keeping the tag variant suffix (e.g. -dind, -alpine). Use when asked to update or refresh Prow job images, or to bump container/initContainer tags to a newer version without changing variants."
---

# Prow Image Bump (Keep Variant)

## Quick workflow

1. Identify the target image repo and the new base version (without variant).
2. Update `spec.containers[].image` and `spec.initContainers[].image` in `prow-jobs/**`.
3. Keep the existing tag variant suffix (text after the first `-`) while bumping the version.
4. Validate with a quick `rg` search and review the changed YAML.

## Preferred automation (shell only)

Use the bundled script to update only container and initContainer images under `prow-jobs/`.
It auto-discovers newer tags using `crane` or `oras` and preserves the tag variant suffix.

```bash
skills/prow-image-bump-keep-variant/scripts/bump_prow_job_images.sh \
  --image docker \
  --tool crane \
  --dry-run --verbose

skills/prow-image-bump-keep-variant/scripts/bump_prow_job_images.sh \
  --image docker \
  --tool crane
```

Behavior:
- Keeps tag variants by preserving everything after the first `-` in the old tag.
  - Example: `docker:28.1-dind` -> `docker:28.2-dind`
- If the old tag has no `-` variant, only the version changes.
- Only updates `image:` lines that are under `containers:` or `initContainers:` blocks.
- Skips digest-pinned images (e.g. `image@sha256:...`).
- Resolves \"newer\" by sorting tags with `sort -V` (semver-ish).

## Manual fallback

If you need to update only a subset or the script is too broad:

1. Find candidates:

```bash
rg --glob 'prow-jobs/**/*.yaml' -n "image:" prow-jobs
```

2. Edit only the `image:` lines under `containers:` or `initContainers:`.
3. Keep the existing tag variant suffix.

## Notes and edge cases

- Variant definition: everything after the first `-` in the tag.
- If the image includes a registry with a port (e.g. `registry:5000/repo:tag`), the script still matches the repo name before the tag.
- For multi-image updates, run the script once per image repo/version.
- Requirements: `yq` plus either `crane` or `oras`.
