# B602 — A Task Made Before Comes Through The Upgrade

```json
{
  "$schema": "issue-1.json",
  "id": "B602",
  "title": "A Task Made Before Comes Through The Upgrade",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 7,
  "blockedBy": ["B601"],
  "covers": "A task made before the change survives the upgrade whole."
}
```

**Why this size.** one real-machine upgrade scenario and the operating notes.

Implementing since 2026-11-10 (`porelo-project` ST307; the scenario first, its run after [B601](README.md#b601)). Built by Core; in Review since 2026-11-11, until the operator's test. Filed 2026-11-10 in `porelo-project` ST307 from [B538](README.md#b538)'s approved plan (the operator, 2026-11-10).

High priority (the operator, 2026-11-09, for [B538](README.md#b538)).

**What must be true.** A kept task made by the release before this change keeps its container, id, workspace, session, mail, credential route and MCP child through an upgrade of the CLI, daemon and hooks together, a daemon restart, a stop and a start (the operator, 2026-11-10: existing names stay for their lifetime; the three upgrade together). A new task started after it gets the new name.

## Plan

[B538](README.md#b538)'s plan, slice 7: its builder's part, the files it names and the tests that go red first
are this issue's plan. Read [B538](README.md#b538)'s "Design to approve before building" first; the evidence lines there are where
to start.

## Acceptance

- The upgrade scenario passes on a real machine against a package from before the change.
- This is a test script, with no red-first unit tests or unit suite. Its proof is the real-machine run; no task of
  another project is touched.

## As built

One opt-in acceptance scenario, `task-package-upgrade.feature`, covers a task genuinely created by a pinned published
predecessor. `UpgradeSteps` and the kit's `task-upgrade.py` driver install both package payloads into a dedicated
account using the deploy tool's account layout and atomic replacement of the CLI, daemon and hooks. All input files
are pre-staged on the VM and checked against supplied SHA-256 pins; both package versions and digests are recorded
with the scenario. The shared kit supplies the one SSH connection, test vault, project fixtures, declared agent-ready
check and scenario daemon. Endpoint and filter executables are installed explicitly. A local TLS provider and a
deferring queue transport need no paid service or external transport server. Missing prerequisites fail the run.

The scenario checks the old container id, name, uninterrupted start time and helper records while testing the live
gate, terminal attach, queued signed mail and credential route. It separately recreates only its own daemon and
exercises Watch, a fresh clearance event and the old endpoint's child ownership. Stop and resume by logical name
must preserve the workspace sentinel, agent session input, inbox, queue bytes and route-token fingerprint. The two
new-task assertions expect the compact default-project name and underscore-separated other-project name and depend
on [B601](README.md#b601) being in the candidate. Cleanup removes only attempted scenario tasks with checked ids;
failure is reported with survivors retained for inspection. No renaming command or production implementation was
added.

The suite's default selection and the ordinary leg's tag expression explicitly exclude `@upgrade`, independently
of `@slow`. The dedicated command selects it and only `AcceptanceIT`, keeping the kit's separate connection probe
out of this one-connection run. There is no conditional skip for missing pins or endpoint and transport prerequisites.

`doc/running.md` describes lifetime names and upgrading the CLI, daemon and hooks together. `doc/build.md` gives the
machine prerequisites, all package and helper pin variables, and the exact selected-feature command with its glue.
**The real-machine run.** On 2026-11-10/11 the scenario ran on a throwaway libvirt VM made for it
(`porelo-b602-<stamp>`, deleted afterwards) in a dedicated account `upgrade`. The predecessor was
`0.5.1~snapshot.476` (c0125aceb, sha256 `0a2ea66d…38cc16`). The candidate was this branch's
`0.5.1~snapshot.9602+local.20261110T214357` (with [B601](README.md#b601); sha256 `5bafd1c5…8154af`). The tenth run
passed whole: 48 steps, 0 failed. The first nine runs found what the scenario had wrong about the predecessor, each
fixed in the scenario, not the product:

- The predecessor refuses a CLI start of a task whose project allows an MCP endpoint ("only porelod can give" it). The
  driver starts the old task, and resumes it, through porelod's `Start`, in mode `AGENT`.
- A start opens no agent session; only an attach does. The scenario attaches once with the predecessor's CLI and
  detaches before the ready check. That check names the container directly, by a new
  `the {string} agent in container {string} reaches work...` step, since this release's naming rule does not give
  `porelo-porelo-kept` back.
- Mail is signed only by a certified task. The old task is certified as an interface does it: the project is placed
  as its organization's, naming a test person's key made for the run, and `Start` with `certify` is answered by
  `Certify` with the key the start asks for, signed by `ssh-keygen`.
- A finished MCP run leaves its child's container up. The parent stops its own old child before bringing it back by
  logical name, which proves its ownership twice.
- The resumed agent's memory of its first input is shown by the attach that follows the resume, not by a tmux
  capture before any session exists.
- A removed task whose mail was recorded leaves `mail/<container>/record`, and its retired name stays taken
  (`ContainerNameTaken` on the next run). The cleanup removes the mailbox and identity records of the scenario's own
  names once their containers are gone. The product side is [B610](README.md#b610).

On failure, the queue check now prints the mailbox, the talk log and what `say` and `pass` answered. A failed MCP
call prints its HTTP status, body and the task's helpers, never a token.

Offline acceptance `test-compile` passed, and the driver passed a syntax check. `HostServicesTest` passed all six
tests after its ordinary-leg selector expectation was updated. No unit tests were added for the upgrade script, which
is a test script. The document tools passed `check-citations`, `check-readmes` and `check-doc-site`.
