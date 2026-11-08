# [B530](README.md#b530) — One Sign-In Per Agent That Starts Wait On

**Status:** Backlog. Filed 2026-11-08 from the third move's finding 7/7b (the operator's decision: the whole flow runs
in the window).
**Size:** L - a new daemon contract (three methods), a lock and a wait the start takes, the sweep made safe, and the
batch's order; one acceptance step.

**What must be true.** A person starting work over the daemon learns, before any task is made, that the task's agent is
not signed in on this machine, and can sign it in from there: the sign-in runs as a login task in its own terminal, one
at a time per agent on a machine. A start that needs that agent's credential while its sign-in runs waits on it and goes
on once it is stored, instead of opening a second sign-in or failing. A batch start signs in each missing agent once,
one after another, and only then starts its tasks.

## Plan

- Today: `porelo vault login` (`VaultLoginCommand` → `AgentLogin.login`, apps/app-vault) builds `porelo-login`
  unlocked, sweeps every `porelo-login-<millis>` container (`sweepLeftoverLogins`, `podman ps --all`, running ones
  included) and runs its own. A second sign-in on the machine therefore removes the first one's live container. Over
  the daemon, `Start` of an AGENT or UNATTENDED task without the credential answers exit 69 with nothing made
  (TaskLaunch's gate without an `Offer`); `CanStart` already says `CREDENTIAL_MISSING` / `VAULT_LOCKED`.
- **The lock.** `AgentLogin.login` holds `FileLocks.holding(<state>/logins/<agent>.lock, …)` from before the sweep to
  the end of the run. The sweep removes a login container only where its lock is free (`FileLocks.free`): a login
  container records its agent in a label, so a held one is spared. Red first: two logins of one agent, the second
  must not remove the first's container (`AgentLoginTest`, a fake podman).
- **The contract** (40-agent.varlink, `AgentMethods`):
  - `AgentSignIns() -> (signIns: []AgentSignIn)`: per agent its vault state (signed in, not, cannot say, from
    `CredentialWiring.credentialUnavailable`) and a running sign-in's task name, if one runs.
  - `AgentSignInStart(agent) -> (task, started: bool)`: starts the login as a LOGIN task the window attaches to like
    any task's terminal; `started: false` with the running one's task when its lock is held. Refused as a named value
    when the agent cannot log in (`canLogIn` false) or the vault is locked.
  - `AgentSignInWait(agent) -> (stored: bool, reason)`: returns when the agent's sign-in ends; `stored` from the vault
    afterwards, never from the login's exit code.
- **The start waits.** `Start` gains `waitForSignIn: ?bool`; with it, a start missing its agent's credential while a
  sign-in of that agent runs waits on it (a `CertificateWaits`-style future per agent in the daemon, with the start's
  own time limit) and re-checks the vault once it ends. Without it, the answer is a new typed error `SignInNeeded(agent,
  running: ?string)` instead of exit 69, so a client never parses a sentence.
- **The batch** is the client's: it asks `AgentSignIns` once, runs `AgentSignInStart`/`Wait` for each missing agent in
  turn, then starts. Porelo's part is that a second client's start waits, not signs in again.
- Docs: doc/credentials.md (one sign-in per agent per machine, the wait); the contract's comments.

## Acceptance

- Unit: two logins of one agent leave the first's container alone; a start with `waitForSignIn` goes on after a stored
  sign-in and refuses with `SignInNeeded` after one that stored nothing; `AgentSignInStart` while one runs answers
  `started: false` and the running task. Each seen red first.
- Acceptance step with the stub agent: start a sign-in, start a task that waits, store the stub's credential, the task
  runs.

## To be checked

- How a LOGIN task's terminal is attached through the daemon today (the login runs `--interactive --tty` in the
  caller's terminal); the plan reading decides whether the login runs under tmux like a task.
- Whether the vault being locked during the wait (the person locks it) ends the wait as `stored: false`.
