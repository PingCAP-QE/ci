# Branch Support and EOL Governance

This guide describes how supported release branches are added, kept in sync, and retired
across the CI configuration in this repository, and how hotfix and EOL controls are wired
into Prow.

## Branch model

CI configuration is branch-scoped. The same job usually exists once per supported branch,
always in a directory named after the branch:

| Branch form | Meaning | Directory suffix |
|---|---|---|
| `latest` | Trunk / feature branches | `latest/` |
| `release-X.Y` | A supported minor release line (for example `release-8.5`) | `release-X.Y/` |
| `release-X.Y.Z` | A patch branch | `release-X.Y.Z/` |
| `release-X.Y-YYYYMMDD-vX.Y.Z` | A dated customer hotfix branch | `release-X.Y-YYYYMMDD-vX.Y.Z/` |

The directory convention is applied consistently across the layers:

- Prow triggers: `prow-jobs/<org>/<repo>/<branch-special>-<jobtype>.yaml`
- Jenkins job folder: `jenkins/jobs/<org>/<repo>/<branch-special>/<job>/`
  (`dsl.groovy`, `Jenkinsfile`, optional `pod.yaml` / `pod-<purpose>.yaml`)

## Supported branch matrix

The supported branch matrix is the set of branches for which jobs exist. Keep it in sync
with the product's supported release lines: when a minor version becomes supported, add
its directory in the layers; when it reaches end of life, remove it everywhere.

## Adding a new `release-X.Y`

1. Identify the component repos that need the branch (for example `pingcap/tidb`,
   `tikv/tikv`, `tikv/pd`, `pingcap/tiflash`, `pingcap/tiflow`).
2. Create the branch directory and copy the closest existing release as a template:
   - `jenkins/jobs/<org>/<repo>/release-X.Y/`
3. Create or update the Prow trigger file
   `prow-jobs/<org>/<repo>/release-X.Y-<jobtype>.yaml`, reusing the job definitions from a
   supported branch.
4. Update every internal reference so the new branch is self-consistent:
   - the Prow job `name` and `branches` entries,
   - the Job DSL `pipelineJob('<org>/<repo>/<job>')` name and `scriptPath`,
   - the pipeline `POD_TEMPLATE_FILE` and any branch/version constants.
5. Regenerate the Prow kustomization:
   ```bash
   .ci/update-prow-job-kustomization.sh
   ```
6. Validate syntax and, for behavioral changes, replay a job from the new branch.

## Retiring an EOL `release-X.Y`

End-of-life work retires the whole `vX.Y.*` family, including patch tags and customer
hotfix branches. It spans `PingCAP-QE/ci` and the sibling
`ti-community-infra/configs` repository.

1. **Discover candidates first.** Run the discovery helper and review the list before
   deleting anything:
   ```bash
   .agents/skills/tidb-eol-engineering-efficiency/scripts/find_tidb_eol_candidates.sh X.Y
   ```
2. **Clean `PingCAP-QE/ci`** for every affected product component:
   - remove branch-dedicated Prow jobs,
   - remove the matching Jenkins job folders (`jenkins/jobs/<org>/<repo>/<branch>/<job>/`),
   - remove periodic/cron Prow jobs tied to the version,
   - remove version-specific branch, tag and regex ranges that keep the line active,
   - remove patch/hotfix SDLC support (`release-X.Y.Z`, dated hotfix branches,
     `feature/release-X.Y.Z-*`, `feature_release-X.Y.Z-*`),
   - review Tekton resources under `tekton/` for triggers or pipelines that still target
     the EOL branch and remove those entries.
3. Regenerate and validate:
   ```bash
   .ci/update-prow-job-kustomization.sh
   .ci/update-tekton-kustomizations.sh
   .ci/verify-jenkins-pipelines.sh
   ```
4. **Clean `ti-community-infra/configs`**: remove the EOL labels from
   `prow/config/external_plugins_config.yaml`, remove the label definitions from
   `prow/config/labels.yaml`, regenerate `prow/config/labels.md`, and remove the EOL
   branch entries from the merge-allowing Prow config blocks.
5. **Validate both repos** with targeted searches and confirm only intentional historical
   references remain.

In shared files, remove only the EOL entries rather than deleting the whole file, and
leave trunk and still-supported minors untouched.

## Hotfix and EOL control hooks

- **Hotfix branches.** `release-X.Y.Z` and dated `release-X.Y-YYYYMMDD-vX.Y.Z` branches
  are hotfix flows. A Tide status of "Merge is forbidden" is expected for hotfix branches;
  the bot merges automatically once all CI checks pass (see
  [FAQ Q7](./FAQ.md)). Keep hotfix-aware logic in shared libraries and pipelines intact
  for the versions that are still supported.
- **Merge guards.** Merging to a retired branch is blocked by removing its entries from
  the Prow config that allows merges to `release-X.Y` and its patch/hotfix variants.
- **Label controls.** For an EOL version, remove the `affects-X.Y` (and related
  cherry-pick) labels from the external-plugins config so users can no longer apply them,
  remove the maintained label definitions, and regenerate the generated label docs.
- **Discovery and audit.** Use the discovery script above, then re-run targeted searches
  to confirm no live routing remains for the EOL line. Historical comments and
  cross-version test fixtures may remain outside the active CI paths; call them out
  instead of deleting blindly.

## Validation commands

```bash
# Regenerate generated manifests after Prow changes
.ci/update-prow-job-kustomization.sh

# Validate Jenkins pipeline syntax
.ci/verify-jenkins-pipelines.sh

# Search for remaining live routing for an EOL line (replace X.Y/XY)
rg -n "release-X\.Y($|\.)|release-X\.Y-|feature[/_]release-X\.Y([.-]|$)|vX\.Y\.[0-9]+|vXY" \
  prow-jobs jenkins/jobs
```

## See Also

- [Job and Pipeline Change Governance](./job-change-governance.md) — change lifecycle and review checklist
- [Contributing](../contributing.md) — staging-to-production mechanics
- [FAQ](./FAQ.md) — hotfix branch and Tide behavior
- EOL skill: `.agents/skills/tidb-eol-engineering-efficiency/`
