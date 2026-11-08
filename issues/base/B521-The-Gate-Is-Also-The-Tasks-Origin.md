# [B521](README.md#b521) — The Gate Is Also The Task's Origin

**Status:** Review. Filed 2026-11-08 from the third move's finding 13a (the operator's decision, 19:38Z).
**Size:** S - one remote more in the workspace's clone script.

**What must be true.** A task's workspace has a remote `origin` beside `porelo`, at the same gate address, fetched
with it, so `origin/main` works in every check a repository runs. A push still reaches only the gate, whichever remote
it names.

## Why

Agent Pi had no `origin` on porelo-move-3 and ran its check-commits against `porelo/main`. Repositories' checks and
agents' habits name `origin`. `TaskWorkspace.cloneScript` adds only `porelo`.

## As built

- `TaskWorkspace.cloneScript` adds `origin` at `$PORELO_REMOTE_URL` beside `porelo`, also to a workspace from before,
  fetches both in one `git fetch --multiple porelo origin`, and sets `remote.origin.push` to the task's ref as
  `remote.porelo.push` is; `remote.pushDefault` stays `porelo`.
- Test: `TaskWorkspaceTest.theGateIsAlsoTheWorkspacesOriginAndAPushThereGoesToTheTasksRef`, seen red against main.
  `TaskRunCommandTest`'s stand-in podman matches the new fetch.
- After its reading (agy gemini-4-argon-high, read-only, on 73dfbaa3f; each finding checked in the code):
  - `remote.origin.fetch` maps the task's ref as `remote.porelo.fetch` does. Before, work pushed with
    `git push origin` was counted as unpushed by `UnhandedWork` and held the task's removal (seen red).
  - `origin` is set to the gate at every start, also where the agent had pointed it elsewhere, whose fetch failed
    the whole fetch.
  - `checkout.defaultRemote porelo`, so `git switch <branch>` is not ambiguous between the two remotes.
  - Test: `TaskWorkspaceTest.aPushToOriginIsHandedInAsOneToPoreloAndOriginIsAlwaysTheGate`.

