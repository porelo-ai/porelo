# B40 — The Domain That May Bind The Socket

**Status:** Estimated.
**Size:** L - a domain of Sokar's own is a design to decide first, the module gains file contexts, and the agent and
build binaries bind labelled sockets too.

**What must be true.** Only Sokar may bind a socket carrying Sokar's label, and a task that connects
to one reaches Sokar rather than something else the operator happens to be running.

## Why

It is small. A security review read `selinux/sokar_socket.te` and objected to `unconfined_t` holding
`create bind listen`. The objection as written overstates it; what survives examination is real and
is what this file is for.

## What the policy says today

`sokar_socket_t` is placed on the socket object by the server, which sets its socket creation
context before `bind()`. Three domains appear:

- `unconfined_t` — binds the sockets and connects to them as a host-side client.
- `container_t` — the task, which may only connect.
- `container_runtime_t` — the relay inside the task's network namespace, which may only connect.

**The container half is already right, and was measured.** Neither container domain may create a
socket of this type, so a compromised container cannot stand up a listener wearing the label and
collect another task's token. That is the property the labelling was bought for and nothing here
weakens it.

## What is actually left

`unconfined_t` is the operator's own login domain, not Sokar's. Every process the operator runs is
in it, so any of them may bind a socket carrying `sokar_socket_t` — and a task connecting to the
credential socket cannot tell which of them answered.

**The honest bound on this is that such a process has already lost.** Anything running as the
operator can read the vault file, the runtime directory and the socket's own parent directory,
which is `0700` and is what actually keeps other users out. So this is not a privilege boundary
being crossed; it is a second lock on a door whose first lock is the uid.

**What makes it worth doing anyway is that the second lock is cheap and specific.** A domain
transition on `/usr/bin/sokar` would mean the label can be bound by that binary and by nothing
else, so an operator-owned process that is not Sokar cannot impersonate the broker even though it
could read everything else. The distance between "already lost" and "lost quietly" is the whole of
what this buys.

## Plan

Read in today's code. `SocketContext` (`wire/src/main/java/org/fuin/sokar/wire/SocketContext.java`) labels a socket
when it is created, through `/proc/thread-self/attr/sockcreate`, and `openUnixSocket()` is the only way a labelled
socket is made. Its callers are `VarlinkServer` (wire), `VaultProxy` (supervisor) and `SshAgentServer` (vault).
`VarlinkServer` is served by `SokarDaemon` (`/usr/bin/sokard`), `ClearanceService`, `BuildServer` and `AgentServer`,
so the agent and build binaries under `/usr/libexec/sokar` bind labelled sockets as well, not only `sokar` and
`sokard`. `gate serve`, `vault serve` and `vault relay` are subcommands of `/usr/bin/sokar`, so the CLI and the
helpers are one binary and can be one domain. The relay enters the task's namespace through `podman unshare`, runs
as `container_runtime_t` and only connects. `DoctorCommand` (`apps/app`) holds the `selinux policy` probe. The
packages ship `selinux/sokar_socket.te` and `install-selinux-policy.sh` under `/usr/share/sokar/selinux`; [B135](README.md#b135) makes
the package load the module, and this issue changes what it loads.

Steps, in order:

1. Measure on the fedora leg, enforcing, module loaded: `ps -Z` for `sokard`, the three helpers, an agent binary
   and a build binary, started from a shell and from `sokard.service`. The domains go into the policy's comment.
2. Red first: an acceptance step on the fedora leg in which a script running as the operator writes the socket
   context and binds. It succeeds today; the step expects a refusal.
3. A domain for Sokar's binaries: `sokar_exec_t` on `/usr/bin/sokar`, `/usr/bin/sokard` and everything under
   `/usr/libexec/sokar`, through a new `selinux/sokar_socket.fc`, and a transition from `unconfined_t` to `sokar_t`
   on executing them. Labelling the directory by pattern covers every agent package without a change in the agent
   repositories. `sokar_t` may do what the operator's processes may do otherwise, so the domain narrows the socket
   rule and nothing else. That needs the reference policy's interfaces, so `install-selinux-policy.sh` (and the
   package scripts of [B135](README.md#b135)) build with the policy development files instead of `checkmodule` alone.
4. Move `setsockcreate` and `create bind listen accept` from `unconfined_t` to `sokar_t`. `unconfined_t` keeps
   `connectto`, for host-side clients that are not Sokar. The two container rules stay as they are.
5. A developer's build is labelled with `chcon`, set by the build after each build: the native build (`-Pnative`)
   runs `chcon -t sokar_exec_t` on every binary it writes when the machine has SELinux enabled and the policy
   loaded, and does nothing elsewhere. The label is lost on every rebuild, and every rebuild sets it again; the
   policy names no developer's path. A binary that is still unlabelled - copied, or built another way - stays
   `unconfined_t` and can no longer create a labelled socket. `SocketContext` treats that refusal as it treats a
   missing module today - an unlabelled socket - and `doctor` says the policy is loaded, this binary is not in
   Sokar's domain, and `chcon -t sokar_exec_t <binary>` is the remedy.
6. Every rule carries a comment naming its domain and why; a test in `dist/package-check`, which already reads what
   the packages ship, refuses a rule without one.
7. `doc/security.md` states the asymmetry: the `0700` runtime directory and the uid are the control on every
   machine (`VaultProxy` and `SshAgentServer` set it); the label is a second lock on SELinux machines only.

**Not here: AppArmor.** An equivalent for AppArmor machines - only Sokar's binaries may bind the socket - is wanted,
and is an issue of its own; until it is built, `doc/security.md` says the second lock exists on SELinux machines only.

## Acceptance

- A process that is not Sokar, running as the operator, cannot bind a socket labelled
  `sokar_socket_t` — demonstrated by trying it, not by reading the policy. Seen to fail: on an
  enforcing machine with the policy loaded, a test process outside Sokar's domain binds such a
  socket.
- Sokar itself binds and connects as it does today, on a machine with the policy loaded and
  SELinux enforcing. Seen to fail: `gate serve`, `vault serve` or `vault relay` is denied a bind
  or a connect there.
- The container half is unchanged: both container domains may connect and neither may create. Seen
  to fail: `container_t` or `container_runtime_t` can create a socket of the type, or cannot connect
  to one.
- A machine without the policy behaves as it does now, and says which of the two it is. Seen to
  fail: a machine without the policy refuses what it allows today, or does not say the policy is
  absent.
- The policy states which domain each rule is for and why, so a later reader can tell a decision
  from an accident. Seen to fail: a rule in the policy without that statement.
- A developer's native build serves a task on an enforcing machine with the policy loaded, straight after the build.
  Seen to fail: a freshly built `sokar` is denied a bind, or the build fails on a machine without SELinux.
