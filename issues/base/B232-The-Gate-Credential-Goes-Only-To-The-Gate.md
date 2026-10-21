# B232 — The Gate Credential Goes Only To The Gate

**Status:** Idea.

**What must be true.** A task's git sends the gate's credential to the gate alone, never to another host it fetches
from, so a git dependency on a public forge is fetched and the gate's token stays inside the machine.

## Why

Measured in a task on the VM (`0.5.1~snapshot.307`, project class `guarded`, egress opened for `github.com`): the task's
environment sets `GIT_CONFIG_KEY_0=http.extraHeader` with `Authorization: Basic` of `porelo:<gate token>`
(`TaskWorkspace.environment`). The key carries no URL, so git sends the header on every HTTPS request. `git ls-remote`
of a public GitHub repository fails with `could not read Username`, since GitHub answers 401 to the foreign header,
and the same command with the header emptied answers. The gate token reaches `github.com`, against the rule that a
credential is never passed on to another host.

## Plan

- `TaskWorkspace.environment` sets the key scoped to the gate's own URL, `http.<gate base URL>.extraHeader`, which
  git applies only to requests to that URL.
- Red first: a test in `TaskWorkspaceTest` that the key names the gate's URL and no other, and an acceptance step
  that a task's git sends no `Authorization` to a declared host other than the gate.

## Acceptance

- In a task, `git ls-remote` of a public repository on a declared host answers, and the gate still takes a push.
  Seen to fail: the unscoped key.
