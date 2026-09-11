---
name: test-jenkins-pipeline-changes-in-pr-by-replaying
description: "Test Jenkins pipeline changes in a PR by replaying changed jobs in PingCAP-QE/ci with cross-branch dedup (prefer newest branch when diffs match), Jenkins job-name resolution from jobs DSL, pod-yaml-aware inline replay, retry tracking, and continuous PR status comments."
---

# Test Jenkins Pipeline Changes In PR By Replaying

## Overview
Replay PR-changed Jenkins pipelines with four core rules:
- Deduplicate same logical jobs across branches by comparing change content.
- If changes are identical, replay only the newest branch candidate (`latest` first) to save resources.
- Resolve Jenkins job full names from `jobs/` DSL files (not only from pipeline paths).
- Keep a single PR comment continuously updated with replay planning, progress, and final outcomes.

## Required Inputs
- Load Jenkins credentials from `.env` by default (no prompting, no helper scripts):
  - Read `JENKINS_USER` and `JENKINS_TOKEN` directly from `.env` (supports both `KEY: value` and `KEY=value`).
  - Only ask the user if `.env` is missing or the keys are not present.
  - Never paste tokens into PR comments or logs, and avoid printing `.env` contents (don’t `cat` it).

Example (read repo-root `.env`, don’t `source` it):

```bash
ENV_FILE="$(git rev-parse --show-toplevel)/.env"
JENKINS_USER="$(rg -m1 '^JENKINS_USER[:=]' "$ENV_FILE" | sed -E 's/^JENKINS_USER[:=][[:space:]]*//')"
JENKINS_TOKEN="$(rg -m1 '^JENKINS_TOKEN[:=]' "$ENV_FILE" | sed -E 's/^JENKINS_TOKEN[:=][[:space:]]*//')"
export JENKINS_USER JENKINS_TOKEN
```
- Resolve PR number from current branch when not provided:

```bash
gh pr view --json number,url,headRefName,baseRefName
```

## Workflow
1. Resolve replay scope (default PR diff, or user-specified scope).
2. Build replay candidates from changed pipeline scripts/pod YAML and group by logical job key.
3. Compare cross-branch changes for the same logical job and deduplicate identical changes.
4. Resolve Jenkins job full name from `jobs/<org>/<repo>/<branch>/*.groovy` DSL.
5. Replay selected candidates and update one PR comment continuously.
6. Poll run status, retry failures up to limit, and publish final results.

## Step 1: Resolve replay scope
Default behavior: auto-detect changed CI files from current PR diff:

```bash
git diff --name-only origin/main...HEAD \
  | rg '^pipelines/.*(\.groovy|pod(-[^/]+)?\.ya?ml)$|^jobs/.*\.groovy$'
```

If the user gives an explicit scope, use that instead of auto scope. Common explicit scopes:
- specific files (`--script-file <file>` one by one),
- branch range (`--base-sha <sha> --head-sha <sha>` with `--auto-changed`),
- path filter (for example only `pipelines/.../latest/...`).

Rule:
- No explicit scope from user -> use PR auto-detected scope.
- Explicit scope provided -> replay only the specified scope.

If `.ci/replay-jenkins-build.sh` fails on missing `rg`, create a temporary shim:

```bash
cat >/tmp/rg <<'SH'
#!/usr/bin/env bash
exec grep "$@"
SH
chmod +x /tmp/rg
```

Run replay commands with `PATH="/tmp:$PATH"` for that session.

## Step 2: Build candidates and deduplicate across branches
- Candidate source types:
  - pipeline Groovy: `pipelines/<org>/<repo>/<branch>/...*.groovy`
  - pod YAML: `pipelines/<org>/<repo>/<branch>/pod*.yaml` (attach to its pipeline candidate)
- Logical job key for grouping:
  - `.../<job>/pipeline.groovy` -> `<org>/<repo>/<job>`
  - `.../<job>.groovy` -> `<org>/<repo>/<job>`
- For candidates under the same key but different branches, compare their PR-diff change content:
  - if equivalent -> replay only the newest branch candidate,
  - if different -> replay each branch candidate.
- Branch priority for dedup selection:
  1. `latest`
  2. `release-x.y` by version (newer first)
  3. other branches.

One practical fingerprint method (per candidate, include pipeline Groovy + related pod YAML diff bodies):

```bash
git diff --no-color origin/main...HEAD -- <candidate_files> \
  | sed -E '/^(diff --git|index |--- |\+\+\+ |@@)/d; s#/(latest|release-[^/]+)/#/<branch>/#g' \
  | shasum -a 256
```

If fingerprint is the same across branches for the same logical job key, keep only the newest branch candidate.

Record dedup decisions in the PR comment (for example: `release-8.5 skipped, same diff as latest`).

## Step 3: Resolve Jenkins job name from jobs DSL (required)
Do not rely only on pipeline path inference. Resolve the Jenkins job full name from `jobs/` DSL:

1. Find DSL files whose `scriptPath(...)` matches the pipeline script path.
2. Read `pipelineJob(...)` in that DSL file.
3. If `pipelineJob` uses simple variables, resolve from local constants in the same file.
4. Convert full job name (`org/repo/.../job`) into Jenkins URL by inserting `/job/` between segments.

Example (`latest` path without `latest` in Jenkins job name):

```bash
# pipeline script: pipelines/pingcap/tidb/latest/ghpr_build/pipeline.groovy
# jobs DSL may define: pipelineJob("${fullRepo}/${jobName}")
# resolved full name: pingcap/tidb/ghpr_build
job_url="${JENKINS_URL}/job/pingcap/job/tidb/job/ghpr_build"
```

Tip: A pipeline under a `latest` directory does not mean the Jenkins job path contains `latest`.

## Step 4: Replay jobs
Replay each selected candidate with `.ci/replay-jenkins-build.sh`.

```bash
# Assumes `JENKINS_USER` and `JENKINS_TOKEN` are already exported (from `.env`)
.ci/replay-jenkins-build.sh \
  --script-file <pipeline.groovy> \
  --job-url <job_url_from_jobs_dsl> \
  --jenkins-url https://prow.tidb.net/jenkins \
  --selector lastSuccessfulBuild \
  --verbose
```

Notes:
- If candidate includes pod YAML changes, replay must keep inline pod template mode enabled.
- `.ci/replay-jenkins-build.sh` already enables inline pod YAML by default.
- Use `--no-inline-pod-yaml` only when explicitly needed and never for pod-YAML-change validation.

## Step 5: Update PR comment continuously (task-item style)
Post or patch one replay-status comment and keep it updated throughout execution:
- Start phase: planned replay list + dedup decisions.
- Submit phase: replay URL for each submitted task.
- Polling phase: state transitions (`queued`, `building`, `success`, `failure`, `skipped`).
- End phase: aggregate counts (`success`, `failure`, `skipped`) and unresolved root causes.

Checklist item policy:
- One checkbox item per selected replay candidate.
- `[x]` only when final `SUCCESS`.
- `[ ]` for `FAILURE`, `ABORTED`, `UNSTABLE`, `BUILDING`, or skipped.

Prefer patching an existing replay comment via `gh api` when updating status in-place.

Recommended comment marker/template:

```markdown
<!-- jenkins-replay-status -->
## Jenkins Replay Status
- [ ] `pipelines/...` -> `org/repo/job_name`
  - status: building (attempt 1/4)
  - replay: https://prow.tidb.net/jenkins/job/.../123
  - note: dedup target, same diff as release-x.y
```

## Step 6: Track status and mark successes
Query each replay build URL:

```bash
curl -fsS -u "${JENKINS_USER}:${JENKINS_TOKEN}" '<build_url>/api/json?tree=building,result,url'
```

## Step 7: Retry failures
- Stop when success, or retries reach max (initial + 3 retries).

Before each retry:
- inspect failure logs and classify infra/config/test failure,
- attempt a targeted fix (prefer pod template YAML/config fix over unrelated groovy workaround),
- replay again to confirm the fix effect.

Example retry:

```bash
# Assumes `JENKINS_USER` and `JENKINS_TOKEN` are already exported (from `.env`)
.ci/replay-jenkins-build.sh \
  --script-file <failed-pipeline.groovy> \
  --jenkins-url https://prow.tidb.net/jenkins \
  --selector lastSuccessfulBuild \
  --wait --verbose
```

Record each attempt URL and result in the PR comment.

## Failure triage hints
- `container utils not found in pod`: replay/pod-template mismatch symptom; ensure replay uses local pod YAML (default inline mode in replay script).
- Deterministic test assertion mismatch (for example SQL/result mismatch) after infra issue is solved: treat as test/runtime failure, likely unrelated to this CI config change; report explicitly.
- `ReplayAction not found`: selected source build cannot be replayed; mark skipped with reason and continue.

## Guardrails
- Always resolve replay job names from `jobs/` DSL files; path inference alone is insufficient.
- Apply cross-branch dedup only when compared diff content is equivalent.
- Prefer fixing pod template YAML for missing containers; avoid pipeline-groovy workaround when the issue is template drift.
- Keep PR updates concise and reproducible: include exact Jenkins URLs, attempt counts, dedup decisions, and final summary counts (`success`, `failure`, `skipped`).
