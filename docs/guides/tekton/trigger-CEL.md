# CEL for Tekton Trigger Interceptors

This guide helps contributors write clear, safe, and maintainable CEL (Common Expression Language) expressions for Tekton Triggers interceptors, especially the `cel` interceptor used in `Trigger` resources.

It covers:
- What CEL is used for in Tekton Triggers
- Anatomy of a `cel` interceptor
- Practical CEL patterns (regex, JSON parsing, null-safety, overlays)
- Robust filtering and extraction
- Common pitfalls and a checklist


## 1) When and why to use the CEL interceptor

Use `cel` interceptors to:
- Filter incoming events before they create PipelineRuns/TaskRuns
- Validate event payloads (e.g., only process GitHub issue comments with a specific command)
- Extract and transform fields from the event (`overlays`) and pass them to bindings/templates as `extensions.*`

Benefits:
- Prevents noisy or malicious events from triggering pipelines
- Reduces logic duplication in Tasks
- Makes triggers self-contained and easier to reason about


## 2) Anatomy of a CEL interceptor

A `cel` interceptor typically defines:
- `params.filter`: a boolean CEL expression. If this evaluates to true, the event passes; otherwise, it is dropped.
- `params.overlays`: computed values (key/expression pairs) added to the `extensions` map. These can be bound to template params.

Minimal scaffold:

    spec:
      interceptors:
        - ref: { name: cel }
          params:
            - name: filter
              value: <CEL boolean expression>
            - name: overlays
              value:
                - key: someKey
                  expression: <CEL expression>
      bindings:
        - { name: someParam, value: $(extensions.someKey) }
      template: ...
      

Event payload is exposed as a dynamic object under `body` (e.g., `body.comment.body` for a GitHub issue_comment event).


## 3) CEL essentials for Tekton

- Logical: `&&`, `||`, `!`
- Comparison: `==`, `!=`, `<`, `>`, `<=`, `>=`
- Membership: `'key' in map` checks if a map contains a key
- Ternary: `cond ? a : b`
- Size: `size(x)` works for strings, lists, and maps
- Strings (subset): `contains`, `startsWith`, `endsWith`, `lowerAscii`, `upperAscii`, `trim`, `matches` (RE2), `split`
- Indexing: `map['key']`, `list[0]`
- Dot access: `map.key` (returns null if absent)
- Null checks: `x != null`
- JSON helpers (Tekton additions):
  - `parseJSON()` converts a JSON string to a CEL map/list
  - `marshalJSON()` converts a CEL map/list to a JSON string

Regex engine: RE2. For multi-line matches, use the `(?s)` flag to make `.` match newlines. Carefully escape backslashes when writing inside YAML.


## 4) Robust filtering patterns

A) Filter by event action, repository, and actor

    value: >-
      body.action == 'created' &&
      body.repository.full_name in ['pingcap/tidb', 'pingcap/tiflow'] &&
      body.sender.login != null

Rationale: restrict to creation events, known repos, and ensure sender exists.

B) Filter on comment command

    value: >-
      body.action == 'created' &&
      body.comment.body != null &&
      body.comment.body.lowerAscii().startsWith('/deliver-images')

Rationale: only act on `issue_comment` creation with a specific command prefix.

C) Filter using regex (multi-line safe)

    value: >-
      body.action == 'created' &&
      body.comment.body != null &&
      body.comment.body.matches('(?s).*<!--\\s*META\\s*=\\s*\\{.*?\\}\\s*-->.*')

Rationale: only pass if the comment contains an HTML comment with a META JSON object.
Note the double escaping of `\` for YAML strings and the `(?s)` flag for multi-line comments.


## 5) Extracting data with overlays (parse once, reuse)

Avoid repeating expensive string splits and `parseJSON()` calls. Compute once in `overlays` and reuse in both the `filter` and subsequent overlays via `extensions`.

Pattern: parse an embedded JSON block from a comment

    params:
      - name: filter
        value: >-
          body.action == 'created' &&
          body.comment.body != null &&
          body.comment.body.matches('(?s)<!--\\s*META\\s*=\\s*\\{.*?\\}\\s*-->') &&
          size(
            body.comment.body.split('<!--')[1]
              .split('-->')[0]
              .replace('META', '')
              .replace('=', '')
              .trim()
              .parseJSON()
              .images
          ) > 0
      - name: overlays
        value:
          - key: meta
            expression: >-
              body.comment.body.split('<!--')[1]
                .split('-->')[0]
                .replace('META', '')
                .replace('=', '')
                .trim()
                .parseJSON()
          - key: images
            expression: extensions.meta.images.marshalJSON()
          - key: stage
            expression: extensions.meta.stage

Then in bindings:

    bindings:
      - ref: github-issue-comment
      - { name: images, value: $(extensions.images) }
      - { name: stage,  value: $(extensions.stage) }

Notes:
- We normalize around `META=` by removing `META` and `=` explicitly, then `trim()` before `parseJSON()`.
- We use `size(list) > 0` instead of `.length`.
- We use `marshalJSON()` for lists/maps when the Task param expects a JSON string.


## 6) Safer JSON handling

- Always guard `parseJSON()` with a preceding `matches()` to ensure the string you parse looks like JSON.
- Check keys exist before indexing:
  - For dynamic maps from JSON, use `'key' in map` to check presence
  - Example: `'images' in extensions.meta && size(extensions.meta.images) > 0`
- Null-safety: prefer `x != null` checks; dot-access on missing keys returns null.
- Avoid chaining deep property access without guards. Break into smaller overlays or use membership checks.


## 7) YAML and escaping tips

- Use `value: >-` folded scalars for multi-line CEL. This keeps YAML readable and avoids excessive quoting.
- Inside CEL regex strings, escape backslashes for YAML: to get `\s` into the regex, write `\\s` in YAML.
- Prefer a single place for complex regex. If multiple conditions share the same pattern, consider moving the expensive `parseJSON()` to an overlay and make the `filter` simple.


## 8) Common pitfalls (and fixes)

- Using `.length` on lists: CEL uses `size(list)`, not `.length`.
- Over-parsing: avoid repeating `split(...).parseJSON()` in several places; compute once in an overlay.
- Missing null checks: event fields (like `body.comment`) may be null depending on the webhook type. Guard with `!= null`.
- Wide-open triggers: add constraints like `body.action == 'created'`, repository allowlists, and perhaps actor/team checks when appropriate.
- Regex greediness: prefer `.*?` with `(?s)` for multi-line non-greedy matches inside HTML comments.
- Incorrect brace escaping: in regex, literal `{` and `}` should be matched as part of an object; generally use `\\{.*?\\}` as part of a larger pattern, or anchor them between literal separators you control.
- Case sensitivity: normalize with `lowerAscii()` before checking commands or keywords.


## 9) End-to-end example trigger (GitHub issue_comment)

This example triggers a TaskRun only when an issue comment is created in selected repos and contains a META JSON block with a non-empty `images` list. It parses the JSON once and passes `images` and `stage` to the Task.

    apiVersion: triggers.tekton.dev/v1beta1
    kind: Trigger
    metadata:
      name: example-notified-successful-image-delivery
      labels:
        type: github-issue-comment
    spec:
      interceptors:
        - name: filter and extract META from comment
          ref: { name: cel }
          params:
            - name: filter
              value: >-
                body.action == 'created' &&
                body.repository.full_name in ['pingcap/tidb', 'pingcap/tiflow'] &&
                body.comment.body != null &&
                body.comment.body.matches('(?s)<!--\\s*META\\s*=\\s*\\{.*?\\}\\s*-->') &&
                size(
                  body.comment.body.split('<!--')[1]
                    .split('-->')[0]
                    .replace('META', '')
                    .replace('=', '')
                    .trim()
                    .parseJSON()
                    .images
                ) > 0
            - name: overlays
              value:
                - key: meta
                  expression: >-
                    body.comment.body.split('<!--')[1]
                      .split('-->')[0]
                      .replace('META', '')
                      .replace('=', '')
                      .trim()
                      .parseJSON()
                - key: images
                  expression: extensions.meta.images.marshalJSON()
                - key: stage
                  expression: extensions.meta.stage
      bindings:
        - ref: github-issue-comment
        - { name: images, value: $(extensions.images) }
        - { name: stage,  value: $(extensions.stage) }
      template:
        spec:
          params:
            - name: images
            - name: stage
          resourceTemplates:
            - apiVersion: tekton.dev/v1
              kind: TaskRun
              metadata:
                generateName: notify-deliver-images-
              spec:
                params:
                  - name: images
                    value: $(tt.params.images)
                  - name: stage
                    value: $(tt.params.stage)
                taskRef:
                  name: pingcap-notify-to-update-ops-tidbx
                workspaces:
                  - name: notify-config
                    secret:
                      secretName: image-delivery-notify-config-tidbx
                  - name: ops-config
                    secret:
                      secretName: image-delivery-ops-config-tidbx


## 10) Validation and troubleshooting

- Add temporary overlays to debug computed values (e.g., `- key: debug_body; expression: body.comment.body`), then echo them in your Task to inspect runtime values.
- Start with a permissive `filter`, deploy, capture sample events, then tighten conditions step by step.
- If matching fails:
  - Print the raw comment and re-check your regex (escaping and `(?s)` flag)
  - Ensure your event `action` matches what GitHub sends (e.g., `created` for issue comments)
  - Verify required fields are non-null (guard with `!= null`)

Local testing strategies:
- Reuse recorded payloads (e.g., a saved JSON file) by posting them to your EventListener.
- Log `$(extensions.*)` in a dummy Task to verify overlay extraction logic.


## 11) Quick reference (cheat sheet)

- Make it pass
  - Only on creation: `body.action == 'created'`
  - Only from repos: `body.repository.full_name in ['org/repo1', 'org/repo2']`
  - Only if comment present: `body.comment.body != null`
- Strings
  - Normalize: `s.lowerAscii().trim()`
  - Regex: `s.matches('(?s)^...$')` (RE2; escape backslashes)
  - Split: `s.split('delim')`
- JSON
  - Parse: `s.parseJSON()` → map/list
  - Serialize: `x.marshalJSON()` → string
  - Presence: `'key' in m`
  - Size: `size(x)` for map/list/string
- Defensive checks
  - `x != null`
  - `'key' in m && m['key'] != null`
  - `size(list) > 0`

Keep expressions small and readable. Prefer overlays for complex transformations.


## 12) Review checklist

Before submitting a Trigger with CEL:

- [ ] Filter limits event scope (action, repo, and optionally actor or org)
- [ ] All fields accessed are null-checked
- [ ] Regex is RE2-compatible, multi-line safe if needed, and properly escaped for YAML
- [ ] JSON is parsed only after a structural check (`matches(...)`)
- [ ] Overlay values are reused, not recomputed
- [ ] Lists/maps passed to Task params are serialized with `marshalJSON()` when required
- [ ] Names and comments explain intent (why), not just what


## 13) Further reading

- Tekton Triggers: Interceptors and CEL
- CEL Language (cel.dev): syntax, standard functions
- RE2 Regex syntax

If you’re unsure about an expression or want a second pair of eyes, open a PR with a short description and a sample payload; reviewers can help verify correctness and edge cases.