---
name: tekton-cd-backend-worker
description: "Handle Tekton v1 backend CD work in PingCAP-QE/ci, including delivery and release Tasks, Pipelines, TriggerBindings, TriggerTemplates, and env-gcp/env-prod2 Triggers under tekton/v1. Use when adding or modifying publisher, artifact delivery, Harbor or GitHub event routing, RC-to-GA release flows, or Tekton trigger-to-pipeline wiring."
---

# Tekton CD Backend Worker

## Scope
Own Tekton CD backend changes under `tekton/v1/**`.

Default write scope:
- `tekton/v1/tasks/**`
- `tekton/v1/pipelines/**`
- `tekton/v1/triggers/**`

Do not edit Jenkins, Prow, or non-Tekton CI paths unless the user explicitly asks for cross-system changes.

## Repo Map
- `tekton/v1/tasks/delivery/`
  - delivery to publisher, registry fan-out, Harbor-style notifications, fileserver publication.
- `tekton/v1/tasks/release/`
  - RC-to-GA tagging, GitHub releases, delivery waiters, offline package upload, release follow-up PRs.
- `tekton/v1/pipelines/pingcap-build-package-linux.yaml`
  - Linux package build orchestration, then binary and image delivery.
- `tekton/v1/pipelines/pingcap-build-package-darwin.yaml`
  - Darwin package build orchestration, then binary delivery.
- `tekton/v1/pipelines/pingcap-release-ga.yaml`
  - GA orchestration for RC tag promotion, GitHub releases, delivery waiters, offline packages, and plugins.
- `tekton/v1/triggers/bindings/`
  - environment and profile defaults passed into templates.
- `tekton/v1/triggers/templates/`
  - shared PipelineRun or TaskRun wiring.
- `tekton/v1/triggers/triggers/env-gcp/`
  - GCP-facing trigger definitions.
- `tekton/v1/triggers/triggers/env-prod2/`
  - prod2-facing trigger definitions.

## Workflow
1. Classify the change surface first.
   - Task behavior change: update the `Task` and every caller that passes changed params or consumes changed results.
   - Pipeline orchestration change: update the `Pipeline` and the trigger templates that instantiate it.
   - Trigger or event routing change: update the binding, template, and trigger as one chain. Some flows create `TaskRun` objects directly instead of `PipelineRun`.
   - New YAML file: update kustomization resources.
2. Trace parameters end to end.
   - For new or changed params, follow the full path that applies to the flow:
     `Trigger/Binding -> TriggerTemplate -> PipelineRun -> Pipeline -> Task`
   - For direct task execution templates, trace:
     `Trigger/Binding -> TriggerTemplate -> TaskRun -> Task`
   - For results or `when` expression changes, update all downstream consumers.
3. Start from the nearest working neighbor.
   - Prefer cloning an adjacent file in the same environment and component.
   - Reuse shared templates under `tekton/v1/triggers/templates/_/` before creating repo-specific copies.
4. Keep environment-specific intent intact.
   - `env-gcp` and `env-prod2` are not interchangeable.
   - Preserve existing routing, repo allowlists, and endpoint choices unless the request explicitly changes them.

## CD-Specific Guardrails
- Keep `apiVersion: tekton.dev/v1` for `Task` and `Pipeline` objects.
- Keep `apiVersion: triggers.tekton.dev/v1beta1` for trigger resources unless the repo migrates as a whole.
- Preserve existing defaults unless the change request is specifically about them:
  - `publisher-url`
  - `delivery-config-url`
  - `generator-script-url`
  - `notify-webhook-url`
- Preserve existing step images and bundled tooling choices unless the request is explicitly about upgrading runtime dependencies. Delivery and release tasks rely on tools such as `deno`, `yq`, `curl`, `oras`, `crane`, and `publisher-cli`.
- Many delivery and release tasks depend on remote scripts or config from `PingCAP-QE/artifacts`. If a change seems to belong in those remote assets, call that out instead of forcing a local workaround.

## Repo-Specific Patterns
- `pingcap-deliver-binaries` sends binary artifacts to publisher and can emit Harbor-style events for downstream component builds.
- `pingcap-deliver-images` generates `delivery.sh` from remote delivery rules, executes it with retries, and can emit success cloud-events.
- `publish-fileserver-from-oci-artifact` uses `publisher-cli` and waits for publishing status.
- `tag-and-delivery-rc2ga-on-oci-artifacts` is the RC-to-GA bridge for both packages and images.
- `pingcap-release-ga` is the main release pipeline to inspect first for GA behavior changes.

## Trigger And CEL Rules
- Keep filters narrow. Restrict by event type, repository, tag or branch pattern, and payload shape.
- Use overlays for derived values such as `git-ref`, `os`, and `arch` instead of repeating parsing logic.
- When editing nontrivial CEL, read `docs/guides/tekton/trigger-CEL.md` first.
- Prefer explicit indexing and null-safe checks over clever expressions. This repo already carries compatibility constraints for Tekton CEL.

## Kustomization And Validation
- After adding, moving, or removing Tekton YAML, run:

```bash
bash .ci/update-tekton-kustomizations.sh
```

- If available, validate the rendered manifests:

```bash
kubectl kustomize tekton/v1/tasks >/dev/null
kubectl kustomize tekton/v1/pipelines >/dev/null
kubectl kustomize tekton/v1/triggers/templates >/dev/null
kubectl kustomize tekton/v1/triggers/triggers/env-gcp >/dev/null
kubectl kustomize tekton/v1/triggers/triggers/env-prod2 >/dev/null
```

- For CEL changes, use `cel-eval` if it is installed. If not, validate by staying close to an adjacent working trigger and citing any unverified assumptions.

## Output Expectations
Provide:
- which Tekton objects changed,
- how params, results, or event fields were propagated,
- whether any remote `artifacts` or publisher behavior was assumed,
- what validation ran and what was not verified.
