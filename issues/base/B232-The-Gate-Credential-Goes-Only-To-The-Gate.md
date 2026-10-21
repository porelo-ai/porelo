# B232 — The Gate Credential Goes Only To The Gate

**Status:** Implementing.
**Size:** XS - one configuration key in `TaskWorkspace`, and a test with real git.

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

- `TaskWorkspace.gateCredential` sets the key scoped to the gate's own URL, `http.<gate base URL>.extraHeader`, which
  git applies only to requests under that URL.
- Red first: `TaskWorkspaceTest` runs real git with the task's configuration against two local servers, the gate and
  another host, and reads which of them received `Authorization`; the unscoped key fails it.
- The acceptance suite's existing clone and push scenarios show the gate still takes the scoped header.

## Acceptance

- In a task, `git ls-remote` of a public repository on a declared host answers, and the gate still takes a push.
  Seen to fail: the unscoped key.
