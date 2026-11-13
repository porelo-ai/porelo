# B693 — A Stub Asking In The Workspace It Is Given

```json
{
  "$schema": "issue-1.json",
  "id": "B693",
  "title": "A Stub Asking In The Workspace It Is Given",
  "state": "Backlog",
  "size": "XS",
  "priority": "Low",
  "rank": 47,
  "blockedBy": [],
  "covers": "The stub agent's script looks for `.porelo-stub-asks` in `$PORELO_STUB_WORKSPACE` when it is set, so a test can run it to its question outside a container.",
  "openQuestions": 0
}
```

**Why this size.** One path in the stub's script and its test.

Filed 2026-11-13 from [B506](README.md#b506): the stub script checks `.porelo-stub-asks` under `/workspace` only, not
under `$PORELO_STUB_WORKSPACE`, which its other files honour. [B506](README.md#b506)'s stub test therefore reads the question out of the
rendered script instead of running the script to it.

**What must be true.** With `PORELO_STUB_WORKSPACE` set, the stub asks its question when that directory holds
`.porelo-stub-asks`; without it, `/workspace` as today.

## Plan

- `stub.yaml`'s script: `${PORELO_STUB_WORKSPACE:-/workspace}/.porelo-stub-asks`.
- Red first: `StubAgentTest` runs the script with the variable pointing at a temporary directory holding the file and
  sees the question printed.

## Acceptance

- The stub run in a given workspace asks its question; a test fails while it looks only under `/workspace`.
