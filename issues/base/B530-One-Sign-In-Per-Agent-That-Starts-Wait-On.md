# [B530](README.md#b530) — One Sign-In Per Agent That Starts Wait On

**Status:** Review. The start's wait is split off as [B534](README.md#b534). Filed 2026-11-08 from the third move's finding 7/7b (the operator's decision: the whole flow runs
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
- **What the build found** (the first half, built: the lock, the sweep, the opener, the provider, the store, the three
  methods):
  - `LoginCredentials.put` already removed a stale `grant/<credential>` (and `client-secret/<credential>`) when the
    new sign-in is not renewable; what `AgentLogin.store` lacked was closing the open authorization question.
  - `AgentSignIns` is taken: apps/app-agents has an `AgentSignIns` report (`porelo agents --json`'s `signed_in`) in
    the same package. The daemon's one-per-agent registry is `AgentSignInRuns` (apps/app-vault), whose state follows
    that report's rule (`SelectedProvider.credentialKey`, `CredentialHeaders.lacking`).
  - `AgentSignInStart(agent, force, remote) -> (container, started, attach)`: the login is no task, so it answers its
    container and the command that attaches, and the refusal is `SignInRefused(agent, reason, detail)`. `remote` is
    the client's word: the daemon sees only the local end of an ssh forward and cannot tell a remote client itself.
  - `AgentLogin.login` refused no-vault only after the login ran; it now refuses before, with the opener it keeps.
  - **Not built yet: the start's wait** (`waitForSignIn`, `SignInNeeded`, the credential check before side effects and
    in `startExisting`, the `VaultLocked` answer). It splits into a second issue, as foreseen under "Size", and this
    issue stays open until that one is filed.

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

## After the plan reading

The build takes these answers; each harm gets a test seen red first.

- **The login is no task.** There is no LOGIN mode, and a login container runs `--network host` with the real
  credential in it: it stays out of `isTask`, `List`, `Watch`, `Screen` and tmux. `AgentSignInStart` runs it detached
  (`podman run -d -i -t`, the agent's login as PID 1, no shell), the window attaches to PID 1 (`podman attach`), and the
  container is removed when PID 1 ends. `AgentSignInStart`, `AgentSignInWait` are in `McpMethods.NEVER`,
  `AgentSignIns` in `READING`; `waitForSignIn` stays out of a task's MCP start parameters, where a missing credential
  answers as today.
- **The vault's opener is taken once, at the start of the login**, and used to store: a `vault lock` during a running
  sign-in no longer drops the credential the person just made.
- **The provider:** the sign-in stores under the provider the agent's extractor names (`value.provider()`), never a
  hard-coded default; an agent with no default provider is answered, not thrown at. A start waits only when the
  missing credential is the one the agent's sign-in makes.
- **The lock:** `<state>/logins/<agent>.lock` is taken before the sweep; it records the login's container name as soon
  as it is chosen. `FileLocks.free` answers free for a lock file that is not there (and is reachable from the daemon).
  The sweep removes a `porelo-login-*` container that has no agent label or whose agent's lock is free. A second
  `vault login` of a held agent does not queue behind it: it says that one runs, and after taking the lock it checks
  the vault again.
- **The wait:** `porelod` keeps each agent's last sign-in result, so `AgentSignInWait` after a sign-in that ended
  answers at once with its result; a wait that finds none running and nothing stored answers `stored: false` at once;
  a sign-in started by `porelo vault login` in a terminal is waited on through its lock file, not an in-memory future.
  The wait has its own limit (30 minutes), not a run's `minutes`.
- **The start:** the credential check and the wait come before anything with side effects (hooks, the agents'
  processes, the upstream probe, a purchase), and a resumed task (`startExisting`) is checked too. A locked vault
  answers `NEEDS_VAULT` / `VaultLocked`, never `SignInNeeded` (`CredentialWiring` answers three states, not a sentence).
- **`AgentSignInStart` takes `force: ?bool`** and answers `ALREADY_SIGNED_IN` where the machine's own agent directory
  holds a sign-in, so the window offers its import first. A loopback login is refused for a remote client.
- **`AgentLogin.store`** removes a stale `grant/<credential>` when the new credential has no refresh token, and clears
  the open authorization question for it.
- Size: L, at its top end; if the build shows it larger, it splits at the start's wait (a second issue).
