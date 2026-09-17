# CodeRabbit review label

The `coderabbit-review-label` Task reconciles `do-not-merge/request-change`.
It supplements the existing OWNERS, approved and lgtm requirements; it does not
require every commit to receive a CodeRabbit approval.

## Policy

- Read all review pages from GitHub, selecting `coderabbitai[bot]` with type `Bot`.
- Order submitted decisions by submission time and review ID, ignoring COMMENTED
  and PENDING reviews. Include DISMISSED as a conservative barrier: dismissal
  must not revive an older approval.
- The latest CHANGES_REQUESTED adds the blocker, including when the PR has since
  received new commits.
- The latest APPROVED can remove an existing blocker only when its `commit_id`
  equals the current PR head SHA. Recheck the snapshot before and after removal.
- All other states preserve the existing label. Resolving a conversation alone
  does not clear it. An approval followed by a push does not create a new blocker.
- The label stores the outstanding block. There is no historical-approval search
  that can resurrect a resolved objection. Manual bypass is not implemented.

API errors fail the TaskRun. If verification fails after deletion, the Task
attempts to restore the label; failed runs need operator attention and retry.
No PR code is checked out or executed. Event values enter the shell through
environment variables, and repository/PR identifiers are validated.

## Rollout (deployment and event routing pending)

1. The pilot is `ti-community-infra/configs`, restricted to PRs whose base
   branch is exactly `test_ai_review`, in
   `tekton/v1/triggers/triggers/env-gcp/_/github-pr-coderabbit-review-label.yaml`.
   Create the branch separately if it does not exist. CodeRabbit must review PRs
   targeting this non-default branch; creating or retargeting a PR alone is not
   sufficient evidence that the review event chain works.
2. Deploy the registered Task, TriggerTemplate and Trigger to the same namespace.
   The existing `github` secret needs PR read and issue label write permissions.
   The selected release image must provide bash, gh (with --slurp), and jq.
3. Verify the authenticated webhook/EventListener route and its trigger selector.
   This Trigger uses the existing `type: github-pr` label but also needs delivery
   of `pull_request_review` events. EventListener definitions are not in this
   change; do not assume the pull_request selector accepts review events.
4. In configs/prow/config/plugins.yaml, add `pull_request_review` to exactly the
   external-plugin endpoint serving this deployment for the pilot repository
   `ti-community-infra/configs`, along with `pull_request`. Its existing plugin
   entries must be preserved.
   Do not enable both tekton2-ee-cd and prow-tekton without verifying routing.
5. Sync the new label definition from configs. Every Tide query admitting a pilot
   PR must exclude the exact label; bare `do-not-merge` is not a wildcard.
6. Reconcile existing open pilot PRs using the Task before relying on the gate.
   Historical dismissed reviews cannot reconstruct a missing blocker; inspect
   these PRs explicitly during rollout.

## Limits

This is an asynchronous Tide label gate, not a GitHub required check or a lock
on manual merging. Snapshot rechecks and restoration reduce stale writes but do
not serialize concurrent TaskRuns or atomically coordinate with Tide. A failed
delivery, process termination, or concurrent merge can leave a window before
the label is applied/restored. This implementation must not be described as a
zero-race merge guarantee. Retries re-read GitHub state and are safe to repeat.

No automatic backfill, periodic repair, protected manual override, or additional
label permission policy is installed by this change. These are rollout or future
extensions, not existing functionality.
