# B175 — The Profile That May Bind The Socket

```json
{
  "$schema": "issue-1.json",
  "id": "B175",
  "title": "The Profile That May Bind The Socket",
  "state": "Implementing",
  "size": "L",
  "priority": "Medium",
  "rank": 13,
  "blockedBy": ["B135"],
  "covers": "On an AppArmor machine a task connects only to a socket one of Porelo's binaries bound, by a task profile that refuses any other peer, as the proposed SELinux domain requires on SELinux machines.",
  "openQuestions": 0
}
```

**Why this size.** AppArmor cannot carry over the SELinux rule as written, so a design is decided first; then two profiles,
the task's way into one, the package's scripts, `doctor`, and a scenario on the ubuntu leg. Re-estimated on 2026-11-13
after the walk4 measurements: steps 1-8 are built, but the lock they build does not hold for path sockets, and step 9
moves every socket a task reaches to an abstract name bound in the task's network namespace. What is left is still L
and is to be split, the transport change of step 9 apart from its profile, `doctor` and leg parts.

**What must be true.** On a machine that confines programs by AppArmor rather than SELinux, a task connects only to a
socket that one of Porelo's binaries bound, as the SELinux domain proposed by [B40](README.md#b40) requires,
so a process of the same account that is not Porelo cannot stand in for the broker. Neither second lock is built:
`selinux/porelo_socket.te` lets `unconfined_t` bind the labeled socket, and `doc/security.md` does not say where
that lock holds.

## Why it cannot be the same rule

[B40](README.md#b40) proposes forbidding the bind: only `porelo_t` may create a socket of `porelo_socket_t`, and
`container_t` may connect only to that type. AppArmor has neither half of that.

- **AppArmor cannot forbid an unconfined process anything.** The operator's shell and everything it starts are
  `unconfined`; no profile restricts what they bind. So the rule cannot sit on the binding side.
- **AppArmor confines by the path a program was executed from, not by a label on the file.** A profile attaches to
  `/usr/bin/porelo`; there is nothing like `chcon` that an unprivileged build could set on its output, and loading a
  profile needs root.
- **Rootless podman gives a task no profile.** Measured on Ubuntu 26.04: `podman info` reports `apparmorEnabled:
  false` for the rootless account, and a task's processes run as `crun (unconfined)`, the label of crun's own
  unconfined profile.
- **Any process may enter a loaded profile by name.** Measured on Ubuntu 26.04 with
  `apparmor_restrict_unprivileged_unconfined=1`: an unprivileged process enters an enforcing profile with `aa-exec`,
  and inside a rootless task container (`no-new-privileges`, `--cap-drop ALL`) a shell entered another profile by
  writing `exec <profile>` to `/proc/self/attr/apparmor/exec`. A child of a process in a profile flagged
  `unconfined` stays in it across `exec` when no other profile attaches.

What AppArmor does offer is the connecting side: a confined process may be allowed to `connect` to a unix socket only
when the peer's label is a given one (`unix (connect) type=stream peer=(label=porelo)`), where the kernel mediates unix
sockets (`/sys/kernel/security/apparmor/features/network/af_unix`, present on Ubuntu 26.04). So the lock moves: Porelo's
binaries wear the label `porelo`, and the task is put into a profile of its own that may connect to no unix socket whose
peer is not `porelo`. A stand-in bound by any other process is refused at the task's `connect`, which is the property
[B40](README.md#b40) buys. The last point above is also the honest bound: like `chcon` on an SELinux machine, a process
of the operator's that deliberately enters `porelo` passes. The lock stops a process that is not Porelo, not one that
claims to be it.

**Decided 2026-10-18:**
- **The lock is on the connecting side:** a task's profile may connect only to a peer in `porelo`. It holds the same
  property for a task; a host-side client that is not Porelo's code is not covered, as `doc/security.md` will say.
- **`porelo` and `porelod` enter the profile `porelo` themselves** (the plan's step 5), so a leg, a developer's build
  and an account install are covered as `/usr` is. Any process of the account could enter it too, the same bound
  `chcon` gives on SELinux.

## Plan

Read in the code on 2026-11-04. Sockets a task reaches are opened by `SocketContext.openUnixSocket()` in
`VarlinkServer` (wire), `VaultProxy` (supervisor) and `SshAgentServer` (vault); `TaskRunner` mounts the vault socket at
`TaskWiring.VAULT_MOUNT` and the mailbox at `Mailbox.MOUNT`; agents are started in the container through `podman exec`
built in `Podman` (runtime); a URL-only agent reaches the broker over TCP through `SocketRelay`, started by
`VaultRelayCommand` through `podman unshare`. The `.deb` is laid out in `dist/dist-deb/pom.xml` (jdeb, control files in
`dist/dist-deb/src/deb/control/`); [B135](README.md#b135) adds its `postinst` and `postrm`. The ubuntu leg installs
into the account's local `bin` folder and `$XDG_DATA_HOME/porelo/agents` (`ci/leg-install.sh`), not under `/usr`.

1. **Measure first, on the ubuntu leg** (to be checked below): the task profile's peer rule refuses a `connect` to a
   socket bound by an `unconfined` process and allows one bound by a process in `porelo`, across the bind mount into the
   rootless container; the result goes into the profile's comment.
2. **Red first:** an acceptance scenario on the ubuntu leg in which a script running as the operator, outside
   `porelo`, binds the socket a task's agent then connects to. It connects today; the scenario expects a refusal, and a
   task served by Porelo itself still reaches the vault proxy. Beside it, `AppArmorProfileTest` (wire) over a fake
   `/proc` and `/sys` tree for the four answers of step 6.
3. **Two profiles, in a new `apparmor/` beside `selinux/`:**
   - `apparmor/porelo` - profile `porelo`, `flags=(unconfined)`, attached to `/usr/bin/porelo`, `/usr/bin/porelod` and
     `/usr/libexec/porelo/**`, so every agent and hook package is covered without a change in its repository. It
     confines nothing; it is the label the task's rule names. Helpers and agents started by `porelod` inherit it.
   - `apparmor/porelo-task` - enforcing, allowing what a task does today, with a unix `connect` allowed only to a peer
     labelled `porelo` or `porelo-task`, and no `change_profile`, so a task that is in it cannot leave it.
   Every rule carries a comment naming what it is for; the test of [B40](README.md#b40) step 6 in
   `dist/package-check` reads these files too.
4. **The task enters `porelo-task` before the agent runs.** `Podman` wraps every `podman exec` that runs the agent or
   anything the agent controls: `sh -c 'printf "exec porelo-task" > /proc/self/attr/apparmor/exec && exec "$@"'`, only
   where `doctor`'s probe of step 6 says the profile is loaded; elsewhere the command is unchanged. Porelo's own
   execs (`infocmp`, `tee`, `mkdir`, `chmod`) stay as they are. The relay is Porelo's code and checks its peer itself:
   `SocketRelay` reads the host socket's `SO_PEERCRED` pid and refuses a peer whose
   `/proc/<pid>/attr/apparmor/current` is not `porelo`, where the profile is loaded.
5. **A binary not under `/usr` enters `porelo` itself.** `porelo` and `porelod` at start, where AppArmor runs, the
   profile is loaded and they are not in it, write `exec porelo` to their own attribute and re-execute
   `/proc/self/exe` with the same arguments. That covers a developer's build and the account install the leg uses,
   names no developer's path in the profile, and is the counterpart of the decision "A developer's build labels
   itself" (see open questions).
6. **`DoctorCommand`** gains an `apparmor profile` probe beside `selinux policy`, through a new `AppArmorProfile`
   (wire): *not needed* (`/sys/module/apparmor/parameters/enabled` is not `Y`), *missing* (AppArmor runs, `porelo-task`
   is not loaded; names the install line), *absent on this kernel* (no `features/network/af_unix`: the profile loads
   but the kernel does not hold the peer rule), *installed* (and this binary is in `porelo`).
7. **Packaging, `.deb` only** (Fedora and RHEL do not run AppArmor): `dist/dist-deb/pom.xml` ships both files under
   `/etc/apparmor.d/` as conffiles; the `postinst` of [B135](README.md#b135) runs `apparmor_parser -r -W` on both
   where AppArmor is enabled, a failure saying what failed and not failing the install; `postrm` unloads them on remove
   and deletes them on purge. `apparmor/install-apparmor-profile.sh` does the same for an account install, as
   `install-selinux-policy.sh` does. `Parity` in `dist/package-check` names the two files as `.deb`-only; the
   package check reads the scripts for the load and the unload. The leg (`Leg.run`, as root, on `--os ubuntu`) loads
   the profiles from the tree after `ci/leg-install.sh`.
8. **Docs:** `doc/security.md` states where the second lock holds - SELinux machines by label, AppArmor machines whose
   kernel mediates unix sockets by the task's profile, nowhere else - and its bound; `doc/getting-started.md`, the
   Debian and Ubuntu section; `doc/decisions.md` an entry for the lock on the connecting side and for the self-entry.
9. **The redesign: Porelo's in-task sockets become abstract names** (decided on the walk4 measurements of
   2026-11-13, see Built). A peer rule does not hold for a socket with a file path, which AppArmor judges by file
   rules; it holds for an abstract one.
   - The vault proxy, the ssh agent, the mailbox and the broker's socket a task reaches are bound as abstract names
     (`@porelo-...`) by the packaged `porelod`, inside each task's network namespace, which it joins with `setns`
     (user and network namespace of the container, as `nsenter -t <pid> -U -n --preserve-credentials` does). Abstract
     names are per network namespace, so they are bound there, not on the host; the bind mounts of these sockets go.
   - `porelo-task` keeps one connect rule, `unix (connect) peer=(label=porelo)`, beside `peer=(label=porelo-task)` for
     the task's own sockets, under `abi <abi/5.0>`.
   - The lock holds for a machine-wide install only: only a binary the profile `porelo` attaches to by path wears plain
     `porelo`. An account install or a developer's build enters `porelo` by name, which on Ubuntu
     (`apparmor_restrict_unprivileged_unconfined`) becomes the stack `porelo//&unconfined`, and its own tasks refuse
     it. `doctor` says so, and Porelo does not wrap a task there; the self-entry of step 5 goes, or stays only for the
     label it gives where no stacking is enforced.
   - **The bounds:** `porelo` is flagged `unconfined`, so an account process can still start `/usr/bin/porelod` with
     `LD_PRELOAD`, or trace a `porelod` it started, and bind in its name. The lock stops a process that is not
     Porelo's binary, not one that runs inside it. A host-side client that is not Porelo's code is not held.
   - **Proved by:** `AppArmorProfileTest` (wire) for the stacked label read as not `porelo`; unit tests of the
     namespace join and of the abstract bind in `wire` and `supervisor`, red first against today's path sockets;
     `AppArmorProfilesTest` (`dist/package-check`) for the rule and the abi line; and the scenario of step 2 on the
     ubuntu leg, installed machine-wide (`@restart` legs): a script outside `porelo`, joined to the task's namespace,
     binds the name before `porelod` does and the agent is refused, a task served by Porelo reaches the vault proxy, the
     ssh agent and the mailbox, and the measurements of v2 and v3 below repeat as one scenario.

## Built (2026-11-13)

- `AppArmorProfile` in `wire` answers `doctor`'s probe over `/proc` and `/sys`: not needed, missing, absent on this
  kernel, outside (loaded, this process not in `porelo`), installed. `/sys/kernel/security/apparmor/policy/profiles`
  is read without root (measured on Ubuntu 26.04). `doctor`'s `apparmor profile` line reports everything but
  `installed` as `DEGRADED`, so a machine without the profiles stays ready.
- `apparmor/porelo` (flagged `unconfined`, attached to `/usr/bin/porelo`, `/usr/bin/porelod`, `/usr/libexec/porelo/**`)
  and `apparmor/porelo-task` (enforcing; unix `connect` only to a peer `porelo` or `porelo-task`; no `change_profile`,
  `px` or `cx`), every rule commented.
- `Podman.confining` puts the agent's unattended run, every attach and the session into `porelo-task` through
  `sh -c 'printf "exec %s" "$0" > /proc/self/attr/apparmor/exec && exec "$@"'`, only where the probe says
  `installed`; Porelo's own execs stay as they are.
- `porelo` and `porelod` enter `porelo` themselves at start where AppArmor runs, the profile is loaded and they are
  not in it, and re-execute their own binary with the same arguments (`AppArmorEntry`).
- The relay does not read its peer's pid, which Java's `SO_PEERCRED` does not give without the channel's file
  descriptor: every upstream connect is made on one platform thread that entered `porelo-task` first, so the kernel
  holds the relay to the task's rule. A refused entry stops the relay.
- The `.deb` ships both profiles as conffiles under `/etc/apparmor.d`; a new `postinst` loads them with
  `apparmor_parser -r -W` where AppArmor runs, and a new `postrm` unloads them on remove and purge and deletes them
  on purge. The SELinux part of [B135](README.md#b135) is still to be added to the same scripts.
  `apparmor/install-apparmor-profile.sh` (with `--remove`) does the same for an account install. `Parity` treats the
  profiles as `.deb`-only; `AppArmorProfiles` in `dist/package-check` holds the packaged profiles to the tree, a
  comment over every rule, the scripts' load and unload and the conffiles. The ubuntu leg loads the profiles as root
  after `ci/leg-install.sh`.
- `doc/security.md`, `doc/getting-started.md` and `doc/decisions.md` say where the second lock holds and its bound.
- Still to be run on the ubuntu leg, with the profiles loaded: the peer rule across the bind mount (unconfined peer
  refused, `porelo` allowed) and the rule form under `abi <abi/4.0>`; self-entry from plain `unconfined`, under the
  daemon unit and inside the relay's namespace wrapper; the relay's `changeprofile` and its refusal of a stand-in; a
  task reaching the vault proxy, ssh agent and mailbox, tmux driven from outside, `/bin/sh` in every image, sockets
  of unwrapped processes in the container; `exec porelo` refused inside a task; doctor's answers; `apt install`,
  `remove` and `purge` on Ubuntu 26.04; the package check against the built packages; whether Debian 13's kernel has
  `features/network/af_unix`. The acceptance scenario of step 2 is still to be written.
- Measured on walk4 (Ubuntu 26.04, kernel 7.0.0-38, the package installed machine-wide), the lock does not hold:
  `doctor` says `installed` and `porelod` runs in `porelo`, and inside a task `exec porelo` and `changeprofile porelo`
  are refused, but a process in `porelo-task` connected both to a socket an unconfined process bound and to one a
  process in `porelo` bound, and so did the relay's thread; the kernel recorded no `connect` denial. `apparmor.d(5)`
  says why: "Unix domain sockets with file system paths are mediated via file access rules." Porelo's sockets are
  files, `porelo-task` grants every file, and the peer rule never sees the connect. An unprivileged `exec porelo`
  from plain `unconfined` lands in the stack `porelo//&unconfined` (`apparmor_restrict_unprivileged_unconfined`).
  `doc/security.md` and `doc/decisions.md` now say that on AppArmor the `0700` directory is the bound.
- Measured on walk4 the same day, candidate task profiles beside the packaged ones (`v4`: as built, `abi 4.0`;
  `v5`: `abi 5.0`; `v5s`: also `peer=(label=porelo//&unconfined)`; `v5f`: also `deny /s/** w`), each binder once
  plain `unconfined`, once after `exec porelo` and once after `changeprofile porelo`:
  - Path sockets: every candidate connected to all three, with no record in the kernel's log; even `deny /s/** w`
    did not stop it.
  - Abstract sockets: every candidate refused all three, `operation="connect" class="net" family="unix"` denied with
    `peer="unconfined"`. Both `exec porelo` and `changeprofile porelo` from plain `unconfined` gave the stack
    `porelo//&unconfined`, which the kernel logs as "change_profile unprivileged unconfined converted to stacking";
    the peer rule is checked against each part of the stack, and the `unconfined` part matches none. `SO_PEERSEC`
    read the whole stack.
- Measured on walk4 next, with a copy of `python3` at `/usr/libexec/porelo/b175-python3`, where `porelo` attaches by
  path, and the candidate `v5`: that binary wears plain `porelo`, and a task in the candidate reaches its abstract
  socket both in the host's network namespace and in the task's own, the binder having joined it with `nsenter`. A
  binder that entered `porelo` by name (the stack) and a plain `unconfined` one are refused; the kernel recorded three
  denied connects with `peer="unconfined"`. 9 expectations held, none failed. This decides step 9.

## Acceptance

- On the ubuntu leg with the profiles loaded, a process of the operator's outside `porelo` binds the socket a task
  connects to, and the task's agent is refused. Seen to fail: the agent's request reaches that process.
- A task served by Porelo reaches the vault proxy and the relay as today, on the leg, installed in the account. Seen
  to fail: a `connect` of the task or the relay to Porelo's own socket is refused.
- The agent in a task cannot leave `porelo-task`. Seen to fail: a process in the task writes `exec porelo` to its
  attribute and the next `exec` runs as `porelo`.
- A machine without AppArmor, or without the profiles, behaves as today, and `doctor` says which it is; one whose
  kernel does not mediate unix sockets says the lock is absent there. Seen to fail: a task is refused there, or
  `doctor` reports `installed` where the kernel would not hold the rule.
- `apt install` on Ubuntu 26.04 loads both profiles and `apt purge` leaves neither loaded nor on disk. Seen to fail: a
  profile missing after the install, or left after the purge.

## To be checked

- That the peer rule is held for a pathname socket bind-mounted into a rootless container, on Ubuntu 26.04, and which
  rule form expresses "every unix connect except to `porelo`" in an otherwise permissive enforcing profile (an `all`
  rule with a `deny`, or rule priorities) in the parser versions of Ubuntu 26.04 and Debian 13.
- Whether Debian 13's kernel mediates unix sockets at all (`features/network/af_unix`); if not, Debian 13 is a
  machine where `doctor` says the lock is absent. Only the ubuntu leg has a kernel of its own; Debian is checked in a
  container, which shares the build host's kernel, so the answer comes from a Debian 13 machine or its kernel's
  sources.
- Which Ubuntu release the ubuntu leg's snapshot boots; the scenario needs 26.04 or a kernel that mediates unix
  sockets.
- That entering a profile is allowed from plain `unconfined` with `apparmor_restrict_unprivileged_unconfined=1` and
  under `no-new-privileges` (measured only from a process already in an unconfined-flagged profile), and how an
  unprivileged process tells that `porelo-task` is loaded, since `/sys/kernel/security/apparmor/profiles` is
  root-only.
- That every image Porelo builds has `/bin/sh` for the wrapper of step 4, or that a static helper is mounted instead.
- That `/proc/<pid>/attr/apparmor/current` of the host-side peer is readable to the relay inside `podman unshare`.
