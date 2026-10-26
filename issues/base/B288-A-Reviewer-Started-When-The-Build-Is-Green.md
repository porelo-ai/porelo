# [B288](README.md#b288) — A Reviewer Started When The Build Is Green

**Status:** Idea.

**What must be true.** An enabled project starts one reviewer on the exact pushed commit whose build is green,
subject to the source policy and a bounded project budget. E0 input never triggers a reviewer automatically, and an
agent's check never approves or merges the work.

## Proposed

- Blocked by: [B171](README.md#b171), [B172](README.md#b172), [B293](README.md#b293).
- Build observation from [B253](README.md#b253) is present as `BuildWatch` and `TaskBuilds`.
- Blocks: none.
- Off unless `project.yml` enables it and names reviewer agent/provider settings. The daemon polls authenticated
  forge state and decides itself; an event is only a wake-up hint. `BuildWatch` follows online tasks as a helper;
  daemon scheduling and durable recovery are additions, not behavior already supplied by that class.
- Only a final successful build for the pinned commit can trigger the review. Recheck PR head, every relevant
  commit's source and [B293](README.md#b293)'s restrictions before starting. E1/I1 may trigger under policy; an internal
  PR containing an E0 commit cannot. Keep A separate from a numeric source ordering and preserve restrictions of
  consumed input. A clean check on E0/C3 cannot replace a person.
- Record the project, repository, task/PR, head, policy and review identity durably. Running/done work is not started
  again after repeated polls or daemon restart. Start [B172](README.md#b172)'s reviewer, pin its SARIF run to that
  commit and stop it after durable delivery; reviewer output and bot echoes cannot trigger another review.
- Cap automatic starts per project per rolling hour; exhausted budget holds the start with a visible reason.
  The person can stop the reviewer like any task and sees the project setting that started it.

## Open questions

- Should the project name one reviewer or a bounded list, and what is the default maximum starts per hour?
- May task-origin A work with authenticated E1/I1 inputs trigger a reviewer automatically, and under which explicit
  policy? A remains an agent producer; it must not be relabeled I1 to enable the original task-to-review loop.

## To be checked

- Measure restart-safe reservations, burst behavior and races between green builds and head movement. Exercise the
  stub adapter with duplicate success, failed/unknown builds, mixed provenance and a changed head before launch.
