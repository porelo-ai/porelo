# B252 — A Sign-In Collected on No Disk

```json
{
  "$schema": "issue-1.json",
  "id": "B252",
  "title": "A Sign-In Collected on No Disk",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 79,
  "blockedBy": [],
  "covers": "A sign-in made in a login container reaches the vault without lying in plaintext on a disk: the agent's config directory is a `tmpfs` of a container kept running until it is collected, and the host's copies are made in `$XDG_RUNTIME_DIR`",
  "openQuestions": 0
}
```

**Why this size.** M: one class (`AgentLogin`, `apps/app-vault`) changes how its container runs and where its copies
go, with `Podman` (`runtime`) gaining an exec, the tests of both, `doc/decisions.md` and `doc/credentials.md`, and a
login on the test VM as the acceptance step. Estimate: 63 minutes (Core's median for M), p80 229. The login's way
of running is the larger half, and the reason it is not an S.

**What must be true.** A sign-in made in a login container reaches the vault without lying in plaintext on a disk
on the way: not in the container's own layer, not in a temporary directory of the host.

## Why

`AgentLogin` lets the agent write its sign-in to its own files in the throwaway login container, copies the config
directory out into a temporary directory of the host (`Files.createTempDirectory`, owner-only), lets the agent's
extractor read it, stores it in the vault and deletes the copy. Only the vault stores it, encrypted. But on the way
the plaintext lies in the container's writable layer, which is on disk, and in the host's temporary directory, which
may be on disk too, and both may reach swap.

It is an accepted risk for every agent alike (`doc/decisions.md`, "A sign-in passes through plaintext on its way to
the vault"); this narrows it, as that entry already names. Raised for porelo-codex: Codex CLI keeps its sign-in in a
keyring, or in a plaintext file it accepts only as such. A keyring-only sign-in leaves nothing to collect today.

## Plan

Read in today's code. `AgentLogin.launch` makes one directory for every copy with
`Files.createTempDirectory("porelo-login-")`, in the system's temporary directory, and `sweepLeftoverCopies` looks
for a killed login's copies there. `runIn` starts the container with `podman run --interactive --tty --network host
... <image> <login command>`, with no mount for the config directory. While it runs, a watcher copies the config
directory out (`podman cp`, `Podman.copyOut`) into `<copies>/watch` every few seconds and ends the login once the
extractor finds a credential. When the watcher found none, the config directory is copied into `<copies>/collected`
**after `podman run` has returned**, from the stopped container. A `tmpfs` is gone once its container stops, so
making the directory a `tmpfs` alone would lose that last collection.

1. **The copies in memory.** The copies' directory is made under `$XDG_RUNTIME_DIR/porelo/login/` (from
   `context.paths().xdg().runtime()`), owner-only, instead of the system's temporary directory. `sweepLeftoverCopies`
   sweeps there, and also once more in the system's temporary directory for what an older binary left.
2. **The container kept until collected.** `runIn` starts the container detached with a holding process as its
   command (`sleep infinity`) and with `--mount
   type=tmpfs,destination=<config directory>,tmpfs-mode=0700,chown`, so the agent's user owns it. The login then runs
   with a new `Podman.exec(container, interactive, detachKeys, command)`: `podman exec --interactive --tty
   --detach-keys ctrl-] <container> <login command>`. Its exit code is judged as today's `podman run`'s. The last
   collection copies out of the still-running container, and the teardown removes it as today. A detached login
   (`Detached`) runs the exec detached in the same way.
3. **What stays.** The watcher, the extractor, the detach key, the hints and every outcome stay as they are. The
   `podman cp` from a running container reads its `tmpfs`, since it works in the container's mounts.
4. **Docs.** `doc/decisions.md`'s entry says what is now in memory and what may still reach swap: only an
   encrypted swap, or no swap, keeps a sign-in off every disk. `doc/credentials.md`
   says where a login's copies lie for its moment.

## Changed while built

Step 2 as planned ran the login by `podman exec` beside `sleep infinity`. `AgentSignInStart` answers `podman attach
--sig-proxy=false <container>`, and an attach joins the container's main process: a window would have attached to
`sleep`, and a detached login's `podman wait` would never have ended. So the login stays the container's main command,
under a holding process: `/bin/sh -c '"$@"; code=$?; printf … > /tmp/porelo-login-exit; trap "exit 0" TERM; sleep
300 & wait'`. `podman run`, the attach, the detach key and its test of a running container stay as they were.

## Built

- `AgentLogin.copiesBase`: the copies' directory is made under `$XDG_RUNTIME_DIR/porelo/login`, owner-only; a login
  sweeps there and, for what an older binary left, in the system's temporary directory.
- The login container runs with `--mount type=tmpfs,destination=<config directory>,tmpfs-mode=0700,chown`
  (`AgentLogin.inMemory`) and its command under `AgentLogin.holding`.
- The watch asks for the exit mark before each look. A look after the mark is the last: it stores what it finds as
  before, or, finding nothing, records the login's exit code and stops the container, which ends `podman run` (or a
  detached login's `podman wait`). The exit code judged is the login's own, from the mark. A container that ended
  otherwise took its `tmpfs` with it, and the copy after the run then finds nothing.
- A detach key pressed after the login exited, before its last look, is a person leaving a finished login, not a
  cancel: `runIn` asks for the exit mark first and then looks itself, while the container still runs.
- The holding process ends itself after 5 minutes (`AgentLogin.HOLDING`), so a watch that failed cannot keep the
  container for ever; a stop after the login ended takes effect at once (`trap`).
- `doc/decisions.md`'s entry and `doc/credentials.md` say what lies in memory and that only an encrypted swap, or
  none, keeps a sign-in off every disk. A changelog entry.

A reading by a second model found that detach (Red, fixed with
`AgentLoginTest.theDetachKeyPressedAfterTheLoginExitedEndsNothingTheLoginFinished`, seen red first), and texts that
still called the login the container's first process with no shell (`doc/credentials.md`, `AgentLogin.signIn`,
`40-agent.varlink`'s comment), and a second scenario that looked for copies in `/tmp` alone: all fixed. Left as they
are: a stop while the login still runs waits up to 5 s for the holding shell, as it did before for every agent that,
as the first process, did not handle `TERM` itself; a watch that can never read the exit mark holds the container
until the holding process ends after 5 minutes, and then judges the run's exit, 0.

Tests: `AgentLoginTest.aSignInLiesInMemoryOnItsWayToTheVault` (the `tmpfs` mount, every copy under the runtime
directory, a look before the stop) and `aLoginThatExitsLeavingNothingIsLookedAtOnceMoreBeforeItsContainerStops`
(exit 3 said, a look before the stop) were red on the code before and are green. A stand-in holds `podman run`
until a stop and loses the container's memory with it. The login and sign-in tests around it are green
(`AgentLoginTest` 47, `AgentSignInRunsTest` 14, `AgentSignInsTest` 10, `VaultLoginCommandTest` 4,
`AgentLoginFromDirectoryTest` 9, `PoreloDaemonTest` 134). `agent-login.feature` checks that no copy is left under
`$XDG_RUNTIME_DIR` and none was made in `/tmp`.

Checked on the VM (podman 5.7.0, the `porelo-login` image), 2026-11-14: the `tmpfs` with `chown` is owned by `agent`
with mode 0700; the image has `/bin/sh` and coreutils' `sleep`; `podman cp` out of the running container reads the
`tmpfs`; the container's layer (`podman mount`) holds no file of the directory; the holding process kept the
container after the login exited with 3, recorded 3, and a stop took 0.1 s. With snapshot.252 deployed there,
`agent-login.feature`'s three scenarios (stored while the stub runs, Ctrl-] ends a login and stores nothing, stored
after the login ended with the vault shut) and `start-waits-for-sign-in.feature` (a detached sign-in) are green, and
no login container, no copy under `$XDG_RUNTIME_DIR` and none under `/tmp` was left. Log:
`acceptance/suite` run of 2026-11-14, kept by the agent that ran it.

## Red-first test

- In `AgentLoginTest` (`apps/app`), with the recording command runner the test already uses: a login's `run` arguments
  carry a `tmpfs` mount at the agent's config directory, and the copies' directory lies under the context's runtime
  directory. Both fail today: there is no mount, and the copies are in the system's temporary directory.
- A case whose login writes its credential and exits before the watcher looks: the credential is still collected,
  and the copy is taken while the container runs (the stand-in records `cp` before `stop`). Today the copy is taken
  after the container has stopped.

## Risks

- A login that writes its sign-in outside the declared config directory (a file elsewhere under the agent's home,
  or a link out of the directory) writes it to the layer again. The extractor finds nothing there today either, so
  such a login already fails; the test VM shows which agents write where.
- `podman exec` and `podman run` differ in how a closed terminal and a detach end them. The detach and the abandoned
  login must be seen to work as before, on the test VM.
- `$XDG_RUNTIME_DIR` is a `tmpfs` of a size limit (often a tenth of memory). A login's config directory is small, but
  the watcher copies it whole each time; the copies are replaced, not added.
- A `tmpfs` can be swapped out. Only an encrypted swap, or none, closes that, and the docs say so.

## Blockers

None.

## Acceptance

- On the test VM, during a login: `podman mount` of the login container shows no file of the config directory in
  its layer, and nothing of the login's copies lies under the system's temporary directory. Seen to fail first:
  both hold the credential file today.
- A login whose agent exits at once after writing its credential still stores it.
- `vault login` of each agent in `agent-login.feature` still stores its credential, and a detach still ends it.
- `doc/decisions.md` says what the change narrows and what it leaves.

## Decided 2026-11-13 (operator)

A `tmpfs` that the system may swap out is enough. The docs say, with this build, that only an encrypted swap, or no
swap, keeps a sign-in off every disk. Both steps stay in this one issue, size M.

## To be checked

- Which file each real agent's login writes, and whether any lies outside its declared config directory: the stub
  writes inside it; a real agent's login needs an account.
