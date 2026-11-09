# Specification: Run pingcap-inc/tidb release jobs on Tencent

## Goal

Finish the partial Jenkins migration using the same controller and BR agent
configuration as `pingcap/tidb/release-8.5`.

## Scope

- Change the shared Prow Jenkins route from `master: "1"` to `master: "0"`.
  This moves build, check, check2, unit, DDL-v1 unit and BR integration jobs;
  MySQL and optional E2E jobs already use controller 0.
- Align the BR Pod template exactly with its upstream counterpart: pinned
  builder image, 24Gi / 3 CPU requests and limits, and MySQL client setup.
- Keep job names, status contexts, triggers, branch filters, Jenkinsfiles,
  checkout authentication and the other five agent templates unchanged.
- Do not change TiDB source, dependencies, or the shared Bazel library.

## Acceptance criteria

- All eight existing presubmits resolve to controller 0, with exactly six
  effective route changes and no other Prow job behavior changes.
- The BR Pod template is byte-for-byte identical to the upstream template.
- Reference, YAML, resource, privilege and storage checks pass.
- Staging/replay results are recorded before promotion. Existing TiDB Go 1.27
  source/test failures must be distinguished from migration failures.
- The migration PR remains draft until runtime validation is acceptable;
  preparing it must not change production routing or merge a PR.
