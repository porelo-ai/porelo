# B175 — The Profile That May Bind The Socket

**Status:** Analyzed; blocked by sokar [B135](README.md#b135).
**Size:** L - AppArmor cannot carry over the SELinux rule as written, so a design is decided first; then two profiles,
the task's way into one, the package's scripts, `doctor`, and a scenario on the ubuntu leg.

**What must be true.** On a machine that confines programs by AppArmor rather than SELinux, a task connects only to a
socket that one of Sokar's binaries bound, as the SELinux domain of [B40](README.md#b40) ensures on SELinux machines,
so a process of the same account that is not Sokar cannot stand in for the broker. Until it is built, that second lock
holds on SELinux machines only, and `doc/security.md` says so.

## Why it cannot be the same rule

[B40](README.md#b40) forbids the bind: only `sokar_t` may create a socket of `sokar_socket_t`, and `container_t` may
connect only to that type. AppArmor has neither half of that.

- **AppArmor cannot forbid an unconfined process anything.** The operator's shell and everything it starts are
  `unconfined`; no profile restricts what they bind. So the rule cannot sit on the binding side.
- **AppArmor confines by the path a program was executed from, not by a label on the file.** A profile attaches to
  `/usr/bin/sokar`; there is nothing like `chcon` that an unprivileged build could set on its output, and loading a
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
when the peer's label is a given one (`unix (connect) type=stream peer=(label=sokar)`), where the kernel mediates unix
sockets (`/sys/kernel/security/apparmor/features/network/af_unix`, present on Ubuntu 26.04). So the lock moves: Sokar's
binaries wear the label `sokar`, and the task is put into a profile of its own that may connect to no unix socket whose
peer is not `sokar`. A stand-in bound by any other process is refused at the task's `connect`, which is the property
[B40](README.md#b40) buys. The last point above is also the honest bound: like `chcon` on an SELinux machine, a process
of the operator's that deliberately enters `sokar` passes. The lock stops a process that is not Sokar, not one that
claims to be it.

## Plan

Read in today's code. Sockets a task reaches are opened by `SocketContext.openUnixSocket()` in `VarlinkServer`
(wire), `VaultProxy` (supervisor) and `SshAgentServer` (vault); `TaskRunner` mounts the vault socket at
`TaskWiring.VAULT_MOUNT` and the mailbox at `Mailbox.MOUNT`; agents are started in the container through `podman exec`
built in `Podman` (runtime); a URL-only agent reaches the broker over TCP through `SocketRelay`, started by
`VaultRelayCommand` through `podman unshare`. The `.deb` is laid out in `dist/dist-deb/pom.xml` (jdeb, control files in
`dist/dist-deb/src/deb/control/`); [B135](README.md#b135) adds its `postinst` and `postrm`. The ubuntu leg installs
into `~/.local/bin` and `~/.local/share/sokar/agents` (`ci/leg-install.sh`), not under `/usr`.

1. **Measure first, on the ubuntu leg** (to be checked below): the task profile's peer rule refuses a `connect` to a
   socket bound by an `unconfined` process and allows one bound by a process in `sokar`, across the bind mount into the
   rootless container; the result goes into the profile's comment.
2. **Red first:** an acceptance scenario on the ubuntu leg in which a script running as the operator, outside
   `sokar`, binds the socket a task's agent then connects to. It connects today; the scenario expects a refusal, and a
   task served by Sokar itself still reaches the vault proxy. Beside it, `AppArmorProfileTest` (wire) over a fake
   `/proc` and `/sys` tree for the four answers of step 6.
3. **Two profiles, in a new `apparmor/` beside `selinux/`:**
   - `apparmor/sokar` - profile `sokar`, `flags=(unconfined)`, attached to `/usr/bin/sokar`, `/usr/bin/sokard` and
     `/usr/libexec/sokar/**`, so every agent and hook package is covered without a change in its repository. It
     confines nothing; it is the label the task's rule names. Helpers and agents started by `sokard` inherit it.
   - `apparmor/sokar-task` - enforcing, allowing what a task does today, with a unix `connect` allowed only to a peer
     labelled `sokar` or `sokar-task`, and no `change_profile`, so a task that is in it cannot leave it.
   Every rule carries a comment naming what it is for; the test of [B40](README.md#b40) step 6 in
   `dist/package-check` reads these files too.
4. **The task enters `sokar-task` before the agent runs.** `Podman` wraps every `podman exec` that runs the agent or
   anything the agent controls: `sh -c 'printf "exec sokar-task" > /proc/self/attr/apparmor/exec && exec "$@"'`, only
   where `doctor`'s probe of step 6 says the profile is loaded; elsewhere the command is unchanged. Sokar's own
   execs (`infocmp`, `tee`, `mkdir`, `chmod`) stay as they are. The relay is Sokar's code and checks its peer itself:
   `SocketRelay` reads the host socket's `SO_PEERCRED` pid and refuses a peer whose
   `/proc/<pid>/attr/apparmor/current` is not `sokar`, where the profile is loaded.
5. **A binary not under `/usr` enters `sokar` itself.** `sokar` and `sokard` at start, where AppArmor runs, the
   profile is loaded and they are not in it, write `exec sokar` to their own attribute and re-execute
   `/proc/self/exe` with the same arguments. That covers a developer's build and the `~/.local` install the leg uses,
   names no developer's path in the profile, and is the counterpart of the decision "A developer's build labels
   itself" (see open questions).
6. **`DoctorCommand`** gains an `apparmor profile` probe beside `selinux policy`, through a new `AppArmorProfile`
   (wire): *not needed* (`/sys/module/apparmor/parameters/enabled` is not `Y`), *missing* (AppArmor runs, `sokar-task`
   is not loaded; names the install line), *absent on this kernel* (no `features/network/af_unix`: the profile loads
   but the kernel does not hold the peer rule), *installed* (and this binary is in `sokar`).
7. **Packaging, `.deb` only** (Fedora and RHEL do not run AppArmor): `dist/dist-deb/pom.xml` ships both files under
   `/etc/apparmor.d/` as conffiles; the `postinst` of [B135](README.md#b135) runs `apparmor_parser -r -W` on both
   where AppArmor is enabled, a failure saying what failed and not failing the install; `postrm` unloads them on remove
   and deletes them on purge. `apparmor/install-apparmor-profile.sh` does the same for a `~/.local` install, as
   `install-selinux-policy.sh` does. `Parity` in `dist/package-check` names the two files as `.deb`-only; the
   package check reads the scripts for the load and the unload. The leg (`Leg.run`, as root, on `--os ubuntu`) loads
   the profiles from the tree after `ci/leg-install.sh`.
8. **Docs:** `doc/security.md` states where the second lock holds - SELinux machines by label, AppArmor machines whose
   kernel mediates unix sockets by the task's profile, nowhere else - and its bound; `doc/getting-started.md`, the
   Debian and Ubuntu section; `doc/decisions.md` an entry for the lock on the connecting side and for the self-entry.

## Acceptance

- On the ubuntu leg with the profiles loaded, a process of the operator's outside `sokar` binds the socket a task
  connects to, and the task's agent is refused. Seen to fail: the agent's request reaches that process.
- A task served by Sokar reaches the vault proxy and the relay as today, on the leg, installed under `~/.local`. Seen
  to fail: a `connect` of the task or the relay to Sokar's own socket is refused.
- The agent in a task cannot leave `sokar-task`. Seen to fail: a process in the task writes `exec sokar` to its
  attribute and the next `exec` runs as `sokar`.
- A machine without AppArmor, or without the profiles, behaves as today, and `doctor` says which it is; one whose
  kernel does not mediate unix sockets says the lock is absent there. Seen to fail: a task is refused there, or
  `doctor` reports `installed` where the kernel would not hold the rule.
- `apt install` on Ubuntu 26.04 loads both profiles and `apt purge` leaves neither loaded nor on disk. Seen to fail: a
  profile missing after the install, or left after the purge.

## Open questions

- **The lock on the connecting side.** AppArmor cannot forbid the operator's own processes a bind, so the plan puts
  the rule on the task: it may connect only to a peer in `sokar`. That holds the same property for a task, but not
  for a host-side client that is not Sokar's code. Proposal: accept this shape; the alternative is to reject the
  issue and let `doc/security.md` say for good that the second lock is SELinux-only.
- **Sokar enters its own profile.** Path attachment covers only `/usr`; the leg and a developer's build run from
  elsewhere. Proposal: `sokar` and `sokard` enter `sokar` themselves (step 5), which any process of the account could
  equally do - the same bound `chcon` gives on SELinux. The alternative is to attach to `/usr` only and have a
  developer and a `~/.local` install start Sokar through `aa-exec -p sokar`, by hand.

## To be checked

- That the peer rule is held for a pathname socket bind-mounted into a rootless container, on Ubuntu 26.04, and which
  rule form expresses "every unix connect except to `sokar`" in an otherwise permissive enforcing profile (an `all`
  rule with a `deny`, or rule priorities) in the parser versions of Ubuntu 26.04 and Debian 13.
- Whether Debian 13's kernel mediates unix sockets at all (`features/network/af_unix`); if not, Debian 13 is a
  machine where `doctor` says the lock is absent. Only the ubuntu leg has a kernel of its own; Debian is checked in a
  container, which shares the build host's kernel, so the answer comes from a Debian 13 machine or its kernel's
  sources.
- Which Ubuntu release the ubuntu leg's snapshot boots; the scenario needs 26.04 or a kernel that mediates unix
  sockets.
- That entering a profile is allowed from plain `unconfined` with `apparmor_restrict_unprivileged_unconfined=1` and
  under `no-new-privileges` (measured only from a process already in an unconfined-flagged profile), and how an
  unprivileged process tells that `sokar-task` is loaded, since `/sys/kernel/security/apparmor/profiles` is
  root-only.
- That every image Sokar builds has `/bin/sh` for the wrapper of step 4, or that a static helper is mounted instead.
- That `/proc/<pid>/attr/apparmor/current` of the host-side peer is readable to the relay inside `podman unshare`.
