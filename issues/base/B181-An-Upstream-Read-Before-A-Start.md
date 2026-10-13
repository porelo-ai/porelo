# B181 — An Upstream Read Before A Start

**Status:** Review.
**Size:** M - one probe beside the gate's lending, four start-check outcomes that grow `CanStart`'s enumeration, a
remedy at the terminal built like the offer of an unknown host key, `credentials check` running the same probe, unit
tests per cause and one acceptance scenario against an sshd of its own.

**What must be true.** Before anything is created, a start makes sure the upstream of a gated task can be read the way
the gate's clone will read it. When it cannot, the start says why and how to fix it:

- **When it is checked:** the start check asks the upstream (or fetches the mirror, where there is one) with the same
  ssh command and the same lent credential the clone would use.
- **What it answers:** a start-check outcome of its own for each cause, sorted from what git and ssh said, each with
  the command that fixes it:
  - The host key is unknown: `trust-host` with the host on its port.
  - The key was refused: say which credential was tried - none declared, ssh's default key files, or which vault entry.
    Name the command that declares a key for the upstream and the one that stores it, and say that through the daemon
    only a key in the vault or one without a passphrase works.
  - The repository is not found, or not readable with that credential.
  - The host cannot be reached.
- **What a start at a terminal offers:** the remedy, the way the offer of an unknown host key does - for example taking
  a key from `~/.ssh` into the vault and declaring it - and then the start again. A shell is offered only where it
  helps, as when no agent is installed: a shell in a task without a gate has an empty workspace and fixes nothing
  about a key.
- **`sokar credentials check <url>`:** really connects, with the same check, so a person can test before starting.
  Today it only says which credential would be used.

Today the check covers only the host key. A refused key surfaces from the gate's `git clone --bare` as "no git gate
for this task" with git's raw output, and the start then offers a shell in its place. That shell has an empty
workspace and no gate. Found in a test against a self-hosted forge on its own port, after trusting its host. Close
to [PJ37](https://github.com/sokar-ai/sokar-project/blob/main/issues/README.md#pj37), which is about working with the
person's own key where no machine key may be added.

## Plan

Read in today's code:

- The gate clones its mirror once, in `GitGate.initialize`, as `git clone --bare` through the lending
  `GateSupport.credentials` hands it - `VaultGitCredentials`, which is `FollowCredential.open`. Failing, it surfaces in
  `WorkspaceSetup.openWorkspace` as "no git gate for this task" with git's words. An agent's start then offers a
  shell (`TaskLaunch`, `shellInstead("there is no gate ...")`), or refuses with "nothing was created".
- Once the mirror is there, `GitGate.refresh` fetches before each start and is never fatal: the task starts from what
  the mirror holds and is told so. An `offline` project never refreshes.
- `StartCheck` asks only the host key (`withHost`). `CredentialDeclarations.check` says which credential a URL would
  use, without connecting; `sokar credentials check` prints that.
- `FollowCredential.sshCommand` sets `StrictHostKeyChecking=yes` and the known-hosts file. It sets no timeout.

The plan:

- **The probe:** a new `UpstreamProbe` in `app-model` runs `git ls-remote --heads --end-of-options <upstream>` through
  `GateSupport.credentials(...).forUrl(upstream)` - the very lease the clone takes. It adds
  `-o BatchMode=yes -o ConnectTimeout=10` to the ssh command for this call, and git's low-speed limit for https. It
  runs in the process that will clone - the CLI with the person's ssh agent, or the daemon without one - so the answer
  is the clone's.
- **Its answer:** sorted from what git and ssh wrote, by the same kind of reading as `HostKeys.refusedTheHost`:
  - `UNKNOWN_HOST_KEY`: the host key is unknown. This already exists, and the probe never runs before `withHost`
    answers.
  - `UPSTREAM_KEY_REFUSED`: `Permission denied (publickey)`, or an https 401 or 403. The detail names what was offered,
    from `CredentialDeclarations.check`:
    - nothing declared, so only ssh's default key files and, at a terminal, the person's ssh agent;
    - or the vault entry by name.
    It gives the two commands: `sokar credentials declare <match> --kind ssh-key --vault <entry>`, then
    `sokar vault put <entry> --from-file <key>`. It says that through the daemon only a key in the vault, or one
    without a passphrase, works.
  - `UPSTREAM_NOT_FOUND`: "Repository not found", "does not appear to be a git repository", an https 404.
  - `UPSTREAM_UNREACHABLE`: no route, a timeout, a name that does not resolve.
  - Anything else: git's own last line, as `UPSTREAM_UNREADABLE`.
- **When it runs, and what it decides:**
  - **Mirror not there yet:** the start needs the clone, so every cause refuses the start before anything is created.
    This includes an unreachable host.
  - **Mirror there:** the start can go on from the mirror, as `refresh` already allows, so each cause is a warning
    with its fix and the start goes on. A refused key there still matters for handing approved work back, which is
    why it is said and not left to the refresh's line.
  - **An `offline` project** names no upstream and takes no `--upstream`, so it is never asked.
  - **What it costs:** one round trip per start, bounded by the ten-second connect timeout. Only gated starts with an
    upstream pay it. A local path or a start without a gate asks nothing.
- **The remedy at a terminal:** for `UPSTREAM_KEY_REFUSED` without a declaration, `TaskLaunch` offers - as
  `trustUnmet` does for a host key, never taken by `--yes` - to take one of the keys `sokar credentials keys` lists.
  It stores the key in the vault under `git.ssh.<host>`, declares it for the upstream with its port, and asks the
  probe again. With a declaration whose key was refused it offers nothing it cannot know - which key the forge expects
  is the forge's - and says so.
- **No shell where it fixes nothing:** the shell offer for a missing gate goes. A gate that still fails after the probe
  passed is refused with "nothing was created" and the cause. The shell offer stays where no agent is installed.
- **`sokar credentials check <url>`:** for `--purpose git` (its default) it runs the probe after saying which
  credential would be used, and exits non-zero with the cause and its fix when the read fails. `--no-connect` keeps
  today's answer.
- **The contract:** `15-task.varlink` grows `CanStart`'s outcome enumeration by the four names, and `Start` gets an
  error `UpstreamUnreadable(cause, detail, fix)`. A client that does not know a new value shows `detail`, as it does
  for every outcome. The interface (`sokar-frontend`) and the IDE plugin are told on the channel.

Steps:

1. **Red first:** `UpstreamProbeTest` with a recording runner, one case per cause from real git and ssh output. Also
   the timeout options on the ssh command, and the lease's environment being the clone's.
2. `UpstreamProbe`, the outcomes in `StartCheck` (after `withHost`) and `CanStart`, the decision by mirror and class
   in `TaskLaunch` and the daemon's `Start`, the remedy, `credentials check`, `doc/commands.md`.
3. **Acceptance:** an sshd of the scenario's own, as in the one for a host on its own port, with an
   `authorized_keys` that admits no key. A start into `default` is refused with `UPSTREAM_KEY_REFUSED` and creates
   nothing. `credentials check` says the same. After a key is declared and stored, `credentials check` says ready.
4. Reactor, documents and the suite on the VM (`not @restart`).

## Open questions

None that blocks it. Two defaults to confirm:

- **Unreachable host:** refuses only where there is no mirror yet. Where there is one, it warns and the start goes on.
- **Name of the stored key:** the remedy stores it as `git.ssh.<host>`, the name a declaration would be looked for
  under. On a port other than 22 the entry still carries the host only, as a credential's name does today.

## As built

- **The probe:** `UpstreamProbe` in `app-model`.
  - `forStart` decides by the mirror; `probe` reads through a lending; `answer` sorts what git and ssh said.
  - A shut vault is said as such, with `sokar vault unlock`. A host whose key changed comes back as
    `UNKNOWN_HOST_KEY`, without a fix.
- **The start:**
  - `StartCheck.withUpstream` runs last and only on `READY`.
  - `TaskLaunch` reads the upstream after the host question, refuses with "nothing was created", warns where a mirror
    is there, and remembers the refusal for the daemon's typed `UpstreamUnreadable`.
  - The shell offer for a missing gate is gone.
- **The remedy:** it does not list the keys itself. It asks for a path, naming `sokar credentials keys`, and runs
  `sokar vault put <entry> --from-file <path>` on the terminal. The entry is the declared one, or `git.ssh.<host>`,
  which the lending finds without a declaration, so none is written.
- **`credentials check`:** it reads only when the credential is ready or none is needed. The record-only scenario of
  `credentials-commands.feature` uses `--no-connect`.
- **Asked only where it decides something:**
  - `CanStart` does not read the upstream when a mirror is there, because its answer is READY either way.
  - A successful read counts for 30 seconds, so a start dialog that asks every few seconds does not reach the upstream
    each time. A refusal is never kept.
- **Correction to the plan:** an `offline` project cannot name an upstream, so it has no branch of its own.

