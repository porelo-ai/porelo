# [B521](README.md#b521) — The Gate Is Also The Task's Origin

**Status:** Backlog. Filed 2026-11-08 from the third move's finding 13a (the operator's decision, 19:38Z).
**Size:** S - one remote more in the workspace's clone script.

**What must be true.** A task's workspace has a remote `origin` beside `porelo`, at the same gate address, fetched
with it, so `origin/main` works in every check a repository runs. A push still reaches only the gate, whichever remote
it names.

## Why

Agent Pi had no `origin` on porelo-move-3 and ran its check-commits against `porelo/main`. Repositories' checks and
agents' habits name `origin`. `TaskWorkspace.cloneScript` adds only `porelo`.
