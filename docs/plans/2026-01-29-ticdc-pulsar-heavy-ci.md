# Ticdc Pulsar Heavy CI Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add pulsar heavy presubmit jobs for ticdc (latest, release-9.0-beta, next-gen) aligned with heavy resource specs.

**Architecture:** Copy existing pulsar light pipelines and adjust them to run the heavy integration script; add Job DSL and Prow presubmit entries per branch; reuse heavy resource profiles from mysql/storage for pods.

**Tech Stack:** Jenkins Job DSL (Groovy), Jenkins Pipeline (Groovy), Prow presubmit YAML, Kubernetes pod YAML.

### Task 1: Add latest pulsar heavy pipeline and job

**Files:**
- Create: `pipelines/pingcap/ticdc/latest/pull_cdc_pulsar_integration_heavy/pipeline.groovy`
- Create: `pipelines/pingcap/ticdc/latest/pull_cdc_pulsar_integration_heavy/pod-test.yaml`
- Create: `pipelines/pingcap/ticdc/latest/pull_cdc_pulsar_integration_heavy/pod-build.yaml`
- Create: `jobs/pingcap/ticdc/latest/pull_cdc_pulsar_integration_heavy.groovy`

**Step 1: Capture baseline (expected missing files)**

Run: `ls pipelines/pingcap/ticdc/latest/pull_cdc_pulsar_integration_heavy`
Expected: No such file or directory

**Step 2: Create heavy pipeline and pods**

- Copy `pipelines/pingcap/ticdc/latest/pull_cdc_pulsar_integration_light/pipeline.groovy` → heavy path
- Replace light script with `./tests/integration_tests/run_heavy_it_in_ci.sh pulsar ${TEST_GROUP}`
- Keep `cdc.prepareIntegrationTestPulsarConsumerBinariesWithCacheLock` in prepare stage
- Set pod-test resources to match heavy (mysql/storage): memory 32Gi, cpu 6
- Set pod-build resources to match heavy build pods: memory 8Gi, cpu 6

**Step 3: Add Jenkins job DSL**

- Copy `jobs/pingcap/ticdc/latest/pull_cdc_pulsar_integration_light.groovy` → heavy job file
- Update job name and pipeline scriptPath to heavy directory

### Task 2: Add next-gen pulsar heavy pipeline and job

**Files:**
- Create: `pipelines/pingcap/ticdc/latest/pull_cdc_pulsar_integration_heavy_next_gen/pipeline.groovy`
- Create: `pipelines/pingcap/ticdc/latest/pull_cdc_pulsar_integration_heavy_next_gen/pod.yaml`
- Create: `jobs/pingcap/ticdc/latest/pull_cdc_pulsar_integration_heavy_next_gen.groovy`

**Step 1: Capture baseline (expected missing files)**

Run: `ls pipelines/pingcap/ticdc/latest/pull_cdc_pulsar_integration_heavy_next_gen`
Expected: No such file or directory

**Step 2: Create heavy next-gen pipeline and pod**

- Copy `pipelines/pingcap/ticdc/latest/pull_cdc_pulsar_integration_light_next_gen/pipeline.groovy` → heavy next-gen path
- Replace light script with `./tests/integration_tests/run_heavy_it_in_ci.sh pulsar ${TEST_GROUP}`
- Keep `cdc.prepareIntegrationTestPulsarConsumerBinariesWithCacheLock(REFS, ng-binary)`
- Copy `pipelines/pingcap/ticdc/latest/pull_cdc_mysql_integration_heavy_next_gen/pod.yaml` → new pod

**Step 3: Add Jenkins job DSL**

- Copy `jobs/pingcap/ticdc/latest/pull_cdc_pulsar_integration_light_next_gen.groovy` → heavy next-gen job file
- Update job name and pipeline scriptPath to heavy next-gen directory

### Task 3: Add release-9.0-beta pulsar heavy pipeline and job

**Files:**
- Create: `pipelines/pingcap/ticdc/release-9.0-beta/pull_cdc_pulsar_integration_heavy.groovy`
- Create: `pipelines/pingcap/ticdc/release-9.0-beta/pod-pull_cdc_pulsar_integration_heavy.yaml`
- Create: `jobs/pingcap/ticdc/release-9.0-beta/pull_cdc_pulsar_integration_heavy.groovy`

**Step 1: Capture baseline (expected missing files)**

Run: `ls pipelines/pingcap/ticdc/release-9.0-beta/pull_cdc_pulsar_integration_heavy.groovy`
Expected: No such file or directory

**Step 2: Create heavy pipeline and pod**

- Copy `pipelines/pingcap/ticdc/release-9.0-beta/pull_cdc_pulsar_integration_light.groovy` → heavy file
- Replace light script with `./tests/integration_tests/run_heavy_it_in_ci.sh pulsar ${TEST_GROUP}`
- Copy `pipelines/pingcap/ticdc/release-9.0-beta/pod-pull_cdc_mysql_integration_heavy.yaml` → new pod

**Step 3: Add Jenkins job DSL**

- Copy `jobs/pingcap/ticdc/release-9.0-beta/pull_cdc_pulsar_integration_light.groovy` → heavy job file
- Update job name and pipeline scriptPath to heavy file

### Task 4: Add Prow presubmit entries

**Files:**
- Modify: `prow-jobs/pingcap/ticdc/latest-presubmits.yaml`
- Modify: `prow-jobs/pingcap/ticdc/latest-presubmits-next-gen.yaml`
- Modify: `prow-jobs/pingcap/ticdc/release-9.0-beta-presubmits.yaml`

**Step 1: Add latest presubmit**

- Add `pingcap/ticdc/pull_cdc_pulsar_integration_heavy`
- `run_before_merge: true`, context `pull-cdc-pulsar-integration-heavy`
- Trigger pattern includes `pull-cdc-pulsar-integration-heavy|heavy|all`

**Step 2: Add next-gen presubmit**

- Add `pingcap/ticdc/pull_cdc_pulsar_integration_heavy_next_gen`
- `run_before_merge: true`, context `pull-cdc-pulsar-integration-heavy-next-gen`
- Trigger pattern includes `pull-cdc-pulsar-integration-heavy-next-gen|next-gen`

**Step 3: Add release-9.0-beta presubmit**

- Add `pingcap/ticdc/release-9.0-beta/pull_cdc_pulsar_integration_heavy`
- `run_before_merge: true`, context `pull-cdc-pulsar-integration-heavy`
- Trigger pattern matches release-9.0-beta style (no heavy/all shortcuts)

### Task 5: Update documentation

**Files:**
- Modify: `docs/jobs/README.md`

**Step 1: Document ticdc jobs**

- Add a `pingcap/ticdc` entry under Job Definitions to keep README in sync with new jobs

### Task 6: Optional verification

**Files:**
- Modify: `.ci/update-prow-job-kustomization.sh` (not expected)

**Step 1: Update prow job kustomization (if required by repo workflow)**

Run: `.ci/update-prow-job-kustomization.sh`
Expected: kustomization updated or no changes

**Step 2: Quick sanity checks**

Run: `git status -sb`
Expected: new pipeline/job/pod/prow/doc files only
