# Specification: optional DM MySQL 8.4 compatibility trial

## Goal
Run DM integration tests against MySQL 5.7 and MySQL 8.4.13.1 without changing
or weakening the existing required MySQL 8.0.21 job.

## Scope
- Add a staging Jenkins job, its Pod template and DSL, and a manual-only Prow
  presubmit for `pingcap/tiflow` master.
- Keep failures visible with an independent optional status context.
- Pin the MySQL 8.4 image and isolate cached build/test results.
- Do not patch TiFlow tests at runtime or hide compatibility failures. TiFlow's
  remaining legacy replication SQL needs a separate compatibility change.

## Acceptance criteria
- Only the dedicated `/test pull-dm-integration-test-mysql84` command triggers
  the new job; it is optional and does not run before merge automatically.
- Existing required job definitions remain unchanged.
- All DM groups, including TLS_GROUP, are retained. One failed group does not
  cancel the others. Version information and failure artifacts are available.
- Static checks and a local MySQL startup/authentication smoke test pass.
- After deployment, an operator runs the new staging job on a TiFlow PR and
  verifies that its pending/failed context does not block merging. Promotion
  to a required job is explicitly out of scope and requires a later PR.
