# Guide: Keep a Feature Branch Up to Date with Its Base Release Branch

This guide explains how PingCAP-QE/ci keeps a long-lived feature branch (for
example `feature/release-8.5-fts`) automatically in sync with the release branch
it is based on (for example `release-8.5`), while preserving the feature
branch's own git tags (for example `v8.5.*-fts`).

The mechanism is a scheduled Prow periodic job that merges the release branch
into the feature branch with the GitHub Merge API. It is implemented by
[`scripts/plugins/sync-branch.ts`](../../scripts/plugins/sync-branch.ts) and
triggered by `periodic-sync-branch-tidb-feature-release-8.5-fts` in
[`prow-jobs/pingcap-qe/ci/periodics.yaml`](../../prow-jobs/pingcap-qe/ci/periodics.yaml).

## Background

A feature branch is usually created from a release branch and carries extra
commits that are not on the release branch. The team wants:

1. Every change that lands on `release-8.5` (including the changes that are
   released as `v8.5.x`) to also land on `feature/release-8.5-fts`.
2. To release `v8.5.x-fts` from `feature/release-8.5-fts` shortly after
   `v8.5.x` is released from `release-8.5`.
3. The existing `v8.5.*-fts` git tags to stay on `feature/release-8.5-fts`.

## Design: merge, never reset, rebase or force-push

The sync always adds a **merge commit** on the feature branch:

```
release-8.5:        A --- B --- C --- D (v8.5.4)
                             \
feature/release-8.5-fts:       E --- F (v8.5.3-fts) --- M (merge release-8.5)
```

`M` is a normal merge commit whose first parent is the previous feature branch
head and whose second parent is `release-8.5`. This has two important
properties:

- **Tags are preserved.** Git tags are refs, so a merge never creates, moves or
  deletes them. Because the merge keeps the feature branch's history reachable,
  the commits that `v8.5.*-fts` tags point to remain part of the feature branch.
- **Feature commits are preserved.** The FTS-specific commits (`E`, `F`) are not
  dropped.

Do **not** implement this with `git reset --hard`, `git rebase`,
`git push --force` or by mirroring `release-8.5` onto the feature branch. Those
operations rewrite history and would orphan the commits that the `v8.5.*-fts`
tags point to. The tag ref would still exist, but the release it refers to would
no longer be reachable from the feature branch, which is exactly what this
requirement forbids.

The job also never calls any tag API. `v8.5.*-fts` tags are created by the
normal feature-branch build/release flow (see
[`scripts/flow/build/versioning-strategy.ts`](../../scripts/flow/build/versioning-strategy.ts)),
not by the sync job.

## How the automation works

The Prow periodic job runs
[`scripts/plugins/sync-branch.ts`](../../scripts/plugins/sync-branch.ts) daily
with these arguments:

```yaml
args:
  - --owner=pingcap
  - --repository=tidb
  - --source_branch=release-8.5
  - --target_branch=feature/release-8.5-fts
  - --github_private_token=$(GITHUB_API_TOKEN)
```

For every target branch the script:

1. Skips the target if the branch does not exist.
2. Calls `POST /repos/{owner}/{repo}/merges` with `base=<target>` and
   `head=<source>`, which creates a merge commit on the target branch.
   - `201 Created` — a merge commit was created. Done.
   - `204 No Content` — the target already contains the source. Nothing to do.
3. On `409 Conflict` it opens (or reuses) a pull request so a human can resolve
   the conflict, then exits non-zero so the failure is visible on the Prow
   dashboard.

The GitHub Merge API is used instead of a local clone so the job is small,
fast, and never needs repository write access over SSH.

### Conflict handling

Merge conflicts are expected when a change on `release-8.5` edits the same lines
as an FTS-specific commit. The script cannot create a conflicted merge commit
through the API, so it falls back to a pull request and a failing job.

To resolve a conflict:

```bash
git fetch origin
git checkout feature/release-8.5-fts
git merge origin/release-8.5
# resolve the conflicts, then:
git push origin feature/release-8.5-fts
```

Once the merge commit is pushed, the conflict resolution pull request has
nothing left to merge and can be closed. The next scheduled run will then report
"already up to date".

If `--notify_webhook_url` is provided, the script also sends a Lark card when it
hits a conflict.

## Adding or changing a synced branch

1. Update the `--target_branch` argument(s) of the periodic job in
   `prow-jobs/pingcap-qe/ci/periodics.yaml`. The argument is repeatable, so one
   job can keep several feature branches in sync with the same source branch.
2. If you add a new Prow job file, run
   `.ci/update-prow-job-kustomization.sh` and commit the change.
3. Make sure the branch that pushes the merge commit (`github-token`) is allowed
   to push to the target branch. If the target branch is protected, allow the
   bot to bypass the protection or the merge will be rejected.

## Running the sync manually

The script can be run locally to preview what it would do:

```bash
deno run --allow-net scripts/plugins/sync-branch.ts \
  --owner=pingcap \
  --repository=tidb \
  --source_branch=release-8.5 \
  --target_branch=feature/release-8.5-fts \
  --github_private_token="$(gh auth token)" \
  --dry_run
```

Drop `--dry_run` to perform the merge. Unit tests for the pure helper functions
run with:

```bash
deno test --allow-net scripts/plugins/sync-branch.test.ts
```

## Relationship to the `-fts` release

The intended order is:

1. `v8.5.x` is released from `release-8.5` (the release commit and tag land on
   `release-8.5`).
2. The next scheduled sync merges `release-8.5` into
   `feature/release-8.5-fts`.
3. The `feature/release-8.5-fts` build/release flow produces `v8.5.x-fts` and
   tags it on the feature branch.

Because the sync only merges, step 3 never invalidates tags created by previous
releases.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Job reports `cannot merge ... automatically: merge conflicts` | `release-8.5` and the feature branch changed the same lines | Resolve the conflict as shown above |
| Job reports `target branch ... does not exist, skipping` | The feature branch was renamed or deleted | Update `--target_branch` or recreate the branch |
| Merge rejected with a protection error | The bot cannot push to the protected feature branch | Allow the bot to bypass branch protection |
| Feature branch never gets new commits | The periodic job is disabled or the cron is too infrequent | Check the job in `prow.tidb.net` and adjust `cron` |

## See Also

- [Cherry-Pick Pull Request](./cherry-pick-pull-request.md) - How to cherry-pick
  a pull request onto another branch
- [Versioning Strategy](../../scripts/flow/build/versioning-strategy.md) - How
  release versions and build tags are computed
