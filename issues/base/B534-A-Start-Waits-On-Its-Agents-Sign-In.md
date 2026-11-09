# [B534](README.md#b534) — A Start Waits On Its Agent's Sign-In

**Status:** Review. Split 2026-11-09 from [B530](README.md#b530), whose build stopped at the start's wait (the split
its plan foresaw).
**Size:** M - a start parameter, two typed errors, the credential check moved before side effects, one acceptance step.

**What must be true.** A start over the daemon that needs its agent's credential, while a sign-in of that agent runs,
waits on that sign-in and goes on once it is stored; without the wait, or after a sign-in that stored nothing, the
answer is a typed value naming the agent, never exit 69 and a sentence, and nothing has been made.

## Plan

[B530](README.md#b530)'s "After the plan reading" holds for this part; in short:

- `Start` gains `waitForSignIn: ?bool`, kept out of a task's MCP start parameters (there a missing credential answers as
  today). The wait goes through `AgentSignInRuns.await` ([B530](README.md#b530)), its own 30-minute limit, and
  re-checks the vault.
- New typed errors `SignInNeeded(agent, running: ?string)` and `VaultLocked`; `CredentialWiring` answers three states
  (present, missing, vault locked), not a sentence. A start waits only when the missing credential is the one the
  agent's sign-in makes.
- The credential check and the wait come before anything with side effects (hooks, the agents' processes, the upstream
  probe, a purchase), and `startExisting` is checked too.
- Red first: a start with `waitForSignIn` goes on after a stored sign-in; refuses with `SignInNeeded` after one that
  stored nothing; a locked vault answers `VaultLocked`; nothing is made before the refusal.

## As built

A start missing its agent's model credential for the selected provider may wait on that agent's running sign-in,
with its own 30-minute limit through `AgentSignInRuns.await`. It re-checks exactly the credential the start needs
when the wait ends: a sign-in that stored for another provider, or stored nothing, leaves `SignInNeeded(agent,
running)` and nothing is made. A locked vault answers `VaultLocked`. Other missing credentials, including forge,
builds and other OAuth grants, never cause a sign-in wait. The daemon passes one registry instance to both sign-in
methods and starts; no agent API change is needed. `waitForSignIn` is absent from a task's MCP parameters and tool
description. Both new starts and resumes check credentials before hook repair, agent credential extraction,
upstream probes, purchases, conversations or container creation. A stopped task uses its recorded provider and
broker credential key without requiring its agent adapter to remain installed. A task already running still
repairs stale hooks; it starts no new agent session and does not wait for a sign-in.

The credential gate comes after a start's own refusals: the daemon's `UnknownHostKey` and `NoSuchDestination` are
answered first, and a project file or repository the start cannot settle (a task name it refuses, a missing
`--repository`) is left to the launch's refusal in its usual order; the gate answers only for credentials.

## Acceptance

- Unit tests as above, each seen red first. The acceptance step with the stub agent: start a sign-in, start a task that
  waits, store the stub's credential, the task runs.
