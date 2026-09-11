# `ti-community-infra/configs` Reference

## Repo root

Prefer the sibling checkout at `../../ti-community-infra/configs` from `PingCAP-QE/ci`.

## Main edit surfaces

- `prow/config/external_plugins_config.yaml`
  - Remove version-specific label allowlists so users cannot add EOL labels anymore.
  - Do this for every TiDB product component repo still listed there, not just `pingcap/tidb`.
  - Search for:
    - `affects-X.Y`
    - `may-affects-X.Y`
    - `needs-cherry-pick-release-X.Y`
    - `type/cherry-pick-for-release-X.Y`
- `prow/config/labels.yaml`
  - Remove maintained label definitions for the EOL version.
- `prow/config/labels.md`
  - Generated from `labels.yaml`; do not hand-edit.
- `prow/config/config.yaml`
  - Search all `release-X.Y` and `release-X.Y.` entries.
  - Search patch and hotfix support entries too, including feature branches or patched-branch prefixes that keep `vX.Y.*` mergeable.
  - Remove only the entries that keep the EOL branch family mergeable or otherwise treated as maintained across product repos such as `pingcap/tidb`, `tikv/tikv`, `tikv/pd`, `pingcap/tiflash`, `pingcap/tiflow`, `pingcap/monitoring`, docs repos, and QE repos when present.

## Discovery commands

Replace `X.Y` with the target version.

```bash
rg -n "affects-X\\.Y|may-affects-X\\.Y|needs-cherry-pick-release-X\\.Y|type/cherry-pick-for-release-X\\.Y|release-X\\.Y|release-X\\.Y\\.|feature/release-X\\.Y\\.[0-9]+|vX\\.Y\\.[0-9]+" \
  prow/config/external_plugins_config.yaml \
  prow/config/labels.yaml \
  prow/config/config.yaml
```

## Edit rules

- `external_plugins_config.yaml`
  - Remove the EOL labels from every affected product-repo allowlist.
  - The goal is denial by absence from `additional_labels` or other allowlists.
- `labels.yaml`
  - Remove every maintained label about the EOL version, not just `affects-X.Y`.
  - Keep other versions untouched.
- `config.yaml`
  - Focus on Tide query blocks with `includedBranches` or `excludedBranches`.
  - Remove exact EOL entries such as `release-X.Y` and patched-branch prefixes such as `release-X.Y.`.
  - Also remove version-specific branch protection, patch-prefix, feature-branch, or context-map entries when they exist only to support that EOL branch or its hotfix flow.
  - Do not broaden prefix rules by accident when deleting one line from a mixed block.

## Validation

Run from the `ti-community-infra/configs` repo root:

```bash
bash scripts/update-labels.sh
bash scripts/verify-labels.sh
```

If `checkconfig` is available, also run:

```bash
checkconfig --plugin-config=prow/config/plugins.yaml --config-path=prow/config/config.yaml
```

Then confirm the EOL identifiers are gone from live config:

```bash
rg -n "affects-X\\.Y|may-affects-X\\.Y|needs-cherry-pick-release-X\\.Y|type/cherry-pick-for-release-X\\.Y|release-X\\.Y|release-X\\.Y\\.|feature/release-X\\.Y\\.[0-9]+|vX\\.Y\\.[0-9]+" \
  prow/config/external_plugins_config.yaml \
  prow/config/labels.yaml \
  prow/config/config.yaml \
  prow/config/labels.md
```

If a remaining match is purely historical commentary, mention it explicitly in the handoff.
