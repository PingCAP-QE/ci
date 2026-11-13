# Tekton tests

## Binary package generator: P1 behavior baseline

Run from any directory with Python 3, Mike Farah `yq` v4 and Bash installed:

```sh
python3 tekton/tests/test_generate_package_artifacts.py
```

The command above assumes the repository root as the current directory; otherwise
pass the absolute path to the test file. It does not require Python packages,
cluster credentials, a Docker daemon or network access.

The test reads the current `generate` steps directly from both production Task
YAMLs, then compares them with the candidate StepAction and caller fragment in
`fixtures/generate-package-artifacts/`. The candidate is deliberately outside
`tekton/v1`: it is not included in deployment kustomizations or Flux reconciliation.
No production Task, Pipeline or Trigger is changed by this phase.

### Contract and coverage

There are 13 scenarios for each of the Linux and Darwin Tasks (26 scenarios,
52 script executions), plus checks on the candidate boundary and downstream
empty-result guard:

| Scenario | Required behavior |
| --- | --- |
| Default platform; arm64 | Forward component, OS and architecture in their existing argument positions |
| Version tag; PR ref | Preserve ref and version separately |
| SHA equals ref | Pass an empty SHA argument, retaining its position |
| Empty SHA | Preserve the empty argument |
| Failpoint; FIPS | Preserve profile and component |
| Registry with port and nested path | Preserve the complete registry argument |
| No generated script | Succeed; write `false` and the exact bytes `"{}"`, without a newline |
| Git clone failure | Return exit 17; do not invoke the generator or create result files |
| Generator failure | Return exit 23; do not create result files |
| Generator writes a file then fails | Return exit 23; preserve the partial file but do not report success |

The comparison checks exit status, exact result bytes, output script content,
stdout/stderr and recorded Git/generator arguments. Separate golden expectations
prevent two implementations with the same regression from passing by agreement.
The generated fixture script exits 99 if executed; the generate step must only
print it. Build and publish are never executed.

The caller fragment preserves the `generate` name and passes the existing Task
result path. The Action owns only the `generated` Step Result. The successful
generation path leaves `pushed` absent for the unchanged publish step to write;
the no-output path writes the original sentinel so delivery remains skipped.

### Isolation and limits

- Every execution uses a fresh temporary workspace, home and result directory.
- Git and the external artifact generator are fixtures, with no real Git on PATH.
  Only the local `cat` executable is exposed alongside the fixture. Credentials,
  proxies and the caller's shell environment are not inherited.
- Workspace paths are relocated into the temporary directory. The small local
  substitution helper supports only the parameters/result paths used here; it
  is not a Tekton controller emulator.
- Execution uses local Bash in POSIX mode. The existing `==` test is retained.
  For scripts without a shebang, the harness supplies `#!/bin/sh` and `set -e`,
  matching [Tekton v1.3.1's default preamble](https://github.com/tektoncd/pipeline/blob/v1.3.1/pkg/pod/script.go).
- Tests cover ordinary pipeline inputs, not arbitrary shell metacharacters.
  Moving parameter values into environment variables deliberately avoids direct
  script interpolation; equivalence is not claimed for injected shell programs.
- The fixtures characterize how the wrapper invokes the remote generator, not
  the current contents of `PingCAP-QE/artifacts@main` or real build outputs.

This local suite does **not** establish runtime image compatibility, StepAction
resolution, controller-side variable substitution, volume mounts, scheduling,
result collection or actual `when` evaluation. P2 must validate those with real
isolated TaskRuns on the target Tekton version, covering success, no-output and
failure. In particular, local Bash success does not prove the release image's
`/bin/sh` behavior. Docker was unavailable during initial P1 validation.

### Promotion gate

P0 found all three clusters (`tke-pingcap-cicd`, `gke_prow`,
`ksy-pingcap-cicd`) running Pipelines v1.3.1 with StepActions enabled, but all
follow the same `ci/main` through Flux. Keep candidates outside production
deployment paths until isolated execution is verified. Before replacing a
production Task, establish per-cluster promotion through the environment's
GitOps configuration; retain old Action versions while runs can reference them.
Do not infer that `push=false` prevents the current binary Task from publishing.
