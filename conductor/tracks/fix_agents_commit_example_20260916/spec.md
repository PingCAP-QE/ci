# Specification: Fix the AGENTS.md commit-convention example

## Goal

Remove a self-contradiction in the root `AGENTS.md`: the commit convention forbids the
`ci` type for job/pipeline configuration changes, yet the examples list
`ci(prow): add presubmit for tiflow lint`, which uses exactly that forbidden type.

## Scope

- `AGENTS.md`: replace the contradictory example with a compliant one.

## Out of Scope

- Other unrelated `AGENTS.md` inaccuracies.
- Any CI job, pipeline or branch configuration.

## Acceptance Criteria

- `AGENTS.md` contains no `ci(...)` example that contradicts the "do not use `ci` type
  except for `.github`/`.ci` changes" rule.
- `pre-commit run --files AGENTS.md` passes.

## First governed track

This is the first real maintenance change executed through the
[Job and Pipeline Change Governance](../../../docs/guides/job-change-governance.md)
lifecycle, used to validate that the governance process works end to end.
