# B252 — A Sign-In Collected on No Disk

```json
{
  "$schema": "issue-1.json",
  "id": "B252",
  "title": "A Sign-In Collected on No Disk",
  "state": "Analyzed",
  "size": "M",
  "priority": "High",
  "rank": 79,
  "blockedBy": [],
  "covers": "A sign-in made in a login container reaches the vault without lying in plaintext on a disk: the agent's config directory is a `tmpfs` of a container kept running until it is collected, and the host's copies are made in `$XDG_RUNTIME_DIR`",
  "openQuestions": 2
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
4. **Docs.** `doc/decisions.md`'s entry says what is now in memory and what may still reach swap. `doc/credentials.md`
   says where a login's copies lie for its moment.

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
- A `tmpfs` can be swapped out. Only an encrypted swap, or none, closes that (see *Open questions*).

## Blockers

None.

## Acceptance

- On the test VM, during a login: `podman mount` of the login container shows no file of the config directory in
  its layer, and nothing of the login's copies lies under the system's temporary directory. Seen to fail first:
  both hold the credential file today.
- A login whose agent exits at once after writing its credential still stores it.
- `vault login` of each agent in `agent-login.feature` still stores its credential, and a detach still ends it.
- `doc/decisions.md` says what the change narrows and what it leaves.

## Open questions

- Is a `tmpfs` that the system may swap out enough, with the docs saying that only an encrypted swap or none keeps a
  sign-in off every disk? The proposal is yes. The alternative is to warn, or refuse, a login on a machine whose swap
  is not encrypted.
- One issue or two? The copies in `$XDG_RUNTIME_DIR` (step 1) are an S on their own and close the host side at once.
  The container's `tmpfs` (step 2) changes how a login runs. The proposal is to build both here, as one M.

## To be checked

- That podman's `tmpfs` mount takes `chown`, so the agent's user can write the directory, on the podman the test VM
  runs.
- That every login image has `sleep`, or else which holding process to use.
- That `podman cp` out of a running container reads a `tmpfs` mounted in it.
- Which file each agent's login writes, and whether any lies outside its declared config directory.
