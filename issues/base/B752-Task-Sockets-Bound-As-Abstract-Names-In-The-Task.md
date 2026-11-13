# B752 — Task Sockets Bound As Abstract Names In The Task

```json
{
  "$schema": "issue-1.json",
  "id": "B752",
  "title": "Task Sockets Bound As Abstract Names In The Task",
  "state": "Implementing",
  "size": "M",
  "priority": "Medium",
  "rank": 51,
  "blockedBy": [],
  "covers": "The vault socket a task's agent reaches is bound by Porelo's relay in the task, an abstract name `@porelo-vault` where the agent's client takes one and a socket file in the task's own filesystem where it does not, never a file bind-mounted from the host.",
  "openQuestions": 2
}
```

**Why this size.** M: the relay in `supervisor` gains a listener in the task, a socket file through the container's
root and, for choice A, an abstract one through FFM with its registration; `CredentialWiring` and `TaskLaunch`
start the relay for a socket agent too, after the container exists; `TaskRunner` drops one mount; each part has unit
tests. Estimate: 63 minutes (Core's median for M), p80 229. Choice C alone would be S (38 minutes, p80 75): no FFM, one
relay option and the mount. It starts after [B742](README.md#b742), which changes the same `podman exec` paths.

**What must be true.** The vault socket a task's agent connects to is one only Porelo's relay in the task could have
bound. A process of the same account on the host cannot put a file in its place without joining the task.

Split from [B175](README.md#b175)'s step 9. Measured on walk4 (Ubuntu 26.04): AppArmor judges a socket with a file
path by file rules, so `porelo-task`'s peer rule never saw a connect to Porelo's path sockets. For abstract sockets it
refused every binder that was not `porelo`. Abstract names belong to a network namespace, so they are bound in the
task's own.

## Today's code

Read at the start of this issue:

- **One socket reaches a task: the vault proxy's.** `CredentialWiring.startVault` starts `porelo vault serve
  --socket <state>/vault.sock` before the container, and `TaskRunner` bind-mounts that file at
  `TaskWiring.VAULT_MOUNT` (`/run/porelo/vault.sock`). `agentEnvironment` puts that path into the agent's socket
  variable (`route.socketEnvironment()`).
- **No other socket reaches a task.** `Mailbox.MOUNT` is a directory of message files, with no socket in it. Nothing
  in a task's start runs `porelo vault agent` or sets `SSH_AUTH_SOCK`. No `VarlinkServer` serves a task: the daemon,
  the clearance watcher and the agent, build and ecosystem servers are reached from the host only.
- **A URL agent already has a relay in its namespace.** `CredentialWiring.relay` starts `porelo vault relay --listen
  9419 --socket <state>/vault.sock` through `EgressPolicy.inNamespace` (`podman unshare nsenter --target <pid>
  --net`). It listens on TCP loopback in the task's network namespace and connects to the host's path socket.

## Limits that shape the plan

- **Java binds no abstract name.** Measured with JDK 25 (GraalVM 25.4.4.1.1): `UnixDomainSocketAddress.of("\0…")`
  throws `InvalidPathException: Nul character not allowed`. An abstract listener needs FFM (`socket`, `bind`,
  `listen`, `accept`, `read`, `write`), so `SocketContext.openUnixSocket()`, which returns a `ServerSocketChannel`,
  cannot gain an abstract form.
- **`VaultProxy` cannot run in the task's namespace.** It needs the host's resolver and egress: a broker bound in the
  namespace read the host's `resolv.conf` and resolved nothing (`CredentialWiring.relay`'s comment), and the
  provider's host is withheld from the task's ruleset. Only a forwarder sits in the namespace.
- **The plan's `nsenter -U -n --preserve-credentials` fails under `podman unshare`.** Measured on Ubuntu 26.04 with a
  rootless container: `podman unshare nsenter -t <pid> -U -n --preserve-credentials` answers `EINVAL` (setns into the
  user namespace it is already in). Today's form, `podman unshare nsenter --target <pid> --net`, works and stays.

## Who reads the vault socket variable

Only an agent whose definition says `endpoint: socket` gets the variable; every other shipped agent is a URL agent
served by the TCP relay. Read in each agent's definition:

| Agent | Endpoint | Runtime | Abstract address in the variable |
|---|---|---|---|
| Claude Code (`ANTHROPIC_UNIX_SOCKET`) | socket | Bun single-file executable | Not taken. Measured with 2.1.323: with `@porelo-probe-claude` a server bound to `\0porelo-probe-claude` got no connection in 40 s; the same run with a path socket got `POST /v1/messages`. `\0` cannot be carried in an environment variable. |
| Pi | url | Node | Does not read the variable. |
| oh-my-pi | url | Bun single-file executable | Does not read the variable. |
| Codex | url | Rust (musl) | Does not read the variable. |
| Antigravity | url | Go | Does not read the variable. |
| OpenSSH 10.2p1 (`SSH_AUTH_SOCK`, no task gets it today) | - | C | Not taken. Measured: `SSH_AUTH_SOCK=@name ssh-add -l` answers "No such file or directory", and a server on `\0name` got no connection. |

So today no client that reads the vault socket variable takes an abstract address. Choice A has no consumer until an
agent's client takes `@` (or an agent declares a translation of its own), which the agent's repository would say in
a new definition field, never Porelo by name.

## The relay's label

Measured on Ubuntu 26.04 without loading Porelo's profiles, from a process in an unconfined-flagged profile, the way
`CredentialWiring.relay` starts it (`podman unshare nsenter --target <pid> --net <program>`):

- `exec /usr/bin/podman` moves the process into `podman` (its profile, flagged unconfined, attaches by path), and
  `nsenter`, which no profile attaches to, stays in `podman`.
- A program at the end of that chain that a profile attaches to by path (`/usr/bin/rpm`, profile `rpm`, flagged
  unconfined) wears that profile.

So, inferred: on a machine-wide install the relay started as `/usr/bin/porelo` wears plain `porelo`, since
`apparmor/porelo` attaches to it by path, and `peer=(label=porelo)` would match its abstract socket. An account
install or a developer's build is not attached, and stays in `podman`, unless its self-entry takes it into `porelo`
from there (unmeasured, see To be checked).

## Plan

Choice A where an agent's client takes an abstract address, choice C for every client that does not; today that is
every one.

- **The relay in the task.** `porelo vault relay` (`VaultRelayCommand`, `SocketRelay` in `supervisor`) gains a unix
  listener in the task, beside `--listen <port>`, still started by `CredentialWiring.relay` through
  `EgressPolicy.inNamespace` after the container exists, and still forwarding to the host's `vault.sock`.
  - **A:** `--abstract porelo-vault` binds `@porelo-vault` in the task's network namespace through an FFM listener
    in `supervisor`, labeled through `SocketContext` on the binding thread. The socket variable gets
    `@porelo-vault`. **The AppArmor lock holds** on a machine-wide install: an abstract socket is held by the peer
    rule of [B753](README.md#b753), and the relay wears `porelo`. It does not hold on an account install
    ([B754](README.md#b754)).
  - **C:** `--bind-in <pid>` binds `TaskWiring.VAULT_MOUNT` through `/proc/<pid>/root/run/porelo/vault.sock`, with
    `/run/porelo` made root-owned `0755` and the socket `0666`. No mount namespace is joined, so the upstream connect
    still sees the host's files. The socket variable keeps `/run/porelo/vault.sock`. Measured on Ubuntu 26.04 with a
    rootless container: bound so under `podman unshare`, the agent user (1001) reached it with curl (`204`), and could
    not remove it. **The AppArmor lock does not hold**: it is a path socket, judged by file rules. What holds is
    that only the task's root can replace the file, and a host process must join the task to do so.
- **The task's side.** `TaskRunner` (`app-base`) no longer mounts `vault.sock` at `TaskWiring.VAULT_MOUNT`.
  `CredentialWiring.agentEnvironment` (`app-task`) puts the address the relay bound into `route.socketEnvironment()`.
  `TaskLaunch` starts the relay for a socket agent too, before the agent runs, and a resumed task starts it again with
  the container's new pid, as `TaskControl` does for the URL relay.
- **The broker stays on the host**, bound at `<state>/vault.sock` in the owner-only state directory, reached only by
  the relay.
- **First red:**
  - in `supervisor`, the relay bound to an abstract name in a fresh network namespace (`pasta --config-net`, since
    `unshare -rn` is refused on Ubuntu 26.04) is reached from inside and refused outside; skipped where neither
    works;
  - a relay bound through a process's root makes the socket inside, not on the host;
  - in `apps/app`, the task's specification has no bind mount of a socket.

## Risks

- **FFM registration.** An FFM downcall is not found by static analysis: the listener's calls go into
  `reachability-metadata.json` through `-Pnative,ffm-check` in the same commit, or the native `porelo` fails at the
  first abstract bind.
- **`wire` is published to Central.** The FFM listener stays out of `wire`: agents build against it, and FFM there
  would ask every consumer for native access. `SocketContext` keeps only the label, applied on the thread that calls
  `socket()`.
- **SELinux.** The relay runs as `container_runtime_t` under `podman unshare`; whether it may set
  `porelo_socket_t` through `sockcreate`, and `container_t` may connect to it, is asked on Fedora before the change
  goes out.

## Acceptance

- In a running task no socket Porelo serves is bind-mounted from the host; the agent reaches the vault proxy through
  the relay's socket in the task (`ss -xlp` lists `@porelo-vault` under A, `/run/porelo/vault.sock` is a socket of
  the task's root under C).
- Under A, a process on the host in the same account cannot bind `@porelo-vault` in the task's namespace before Porelo
  does without joining it. Seen to fail: today's bind-mounted file, which the host's account can replace.

## Open questions

- Is A built now, although no shipped agent's client takes an abstract address (Claude Code 2.1.323 refuses `@`), or
  is C built alone and A deferred until an agent declares it can take one?
- On AppArmor machines the lock of [B753](README.md#b753) then holds for no shipped socket agent. Is that accepted
  and said in `doc/security.md`, or is a socket agent served over the relay's TCP port there instead?

## To be checked

- Whether an account install's relay, started from `podman` (a profile flagged unconfined), ends in plain `porelo` or
  a stack after its self-entry. Needs root to load the profile; the operator runs this on an Ubuntu 26.04 machine
  with a running task, and it reverts what it loads:

      sudo install -m 0755 /usr/bin/python3 /usr/libexec/porelo/b752-python3
      sudo apparmor_parser -r -W apparmor/porelo
      pid=$(podman inspect -f '{{.State.Pid}}' <task container>)
      /usr/libexec/porelo/b752-python3 -c 'import subprocess,sys; subprocess.run(["podman","unshare","/usr/bin/nsenter","--target",sys.argv[1],"--net","/usr/libexec/porelo/b752-python3","-c","print(open(\"/proc/self/attr/apparmor/current\").read())"])' "$pid"
      sudo apparmor_parser -R apparmor/porelo
      sudo rm /usr/libexec/porelo/b752-python3

  It prints the label of the copy at the end of the relay's chain; `porelo (unconfined)` confirms the inference
  above.
- Whether a Node client takes an abstract address in a socket variable, for a future socket agent: unmeasured, since no
  shipped socket agent runs on Node; a Node binary connecting with `socketPath: '@name'` to a server on `\0name`
  measures it.
