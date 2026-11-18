# Implementation Plan: PD Jenkins runtime

## Phase 1: Prepare and verify the candidate
- [x] Stage the real-cluster pipeline, DSL, and pod template together.
- [x] Update the three NextGen unit entry points with legacy compatibility.
- [x] Verify published images and unchanged runner scopes/resources.
- [x] Run repository validators and exercise current/legacy command paths.
- [x] Record cache/build and parallel preparation verification evidence.

## Phase 2: Staging verification and promotion
- [ ] Review and merge the staging candidate.
- [ ] Run the staged job and all three unit jobs against the intended PD revision.
- [ ] Compare complete job times, including preparation and cache transport.
- [ ] Promote the staged Jenkins files after successful staging evidence.

Full-repository `pre-commit` fails on the unmodified base in the resource and
privilege policy hooks. The candidate has the same blocking paths and content.
