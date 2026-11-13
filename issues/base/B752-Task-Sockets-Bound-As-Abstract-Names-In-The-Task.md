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
  "covers": "The vault proxy, the ssh agent, the mailbox and the broker's socket that a task reaches are abstract unix names bound by Porelo inside the task's network namespace, not files bind-mounted from the host.",
  "openQuestions": 0
}
```

**Why this size.** M: four servers in three modules (`wire`, `supervisor`, `vault`) learn an abstract bind, a helper
joins the task's namespaces to bind there, `TaskRunner`'s bind mounts and the agents' socket variables change, and
each part has unit tests. No contract another repository reads changes, apart from the address in a socket variable.
Estimate: 63 minutes (Core's median for M), p80 229. It starts after [B742](README.md#b742), which changes the same
`podman exec` paths.

**What must be true.** Every socket a task's agent connects to is one that only a process inside the task's network
namespace could have bound. A process of the same account on the host cannot put a file in its place.

Split from [B175](README.md#b175)'s step 9. Measured on walk4 (Ubuntu 26.04): AppArmor judges a socket with a file
path by file rules, so `porelo-task`'s peer rule never saw a connect to Porelo's path sockets. For abstract sockets it
refused every binder that was not `porelo`. Abstract names belong to a network namespace, so they are bound in the
task's own.

## Plan

- **The servers.** `SocketContext.openUnixSocket()` (`wire`) gains an abstract form, a name `@porelo-<what>` with no
  file. `VarlinkServer` (`wire`), `VaultProxy` (`supervisor`) and `SshAgentServer` (`vault`) take it where they serve
  a task. A server bound to an abstract name removes no file and never `chmod`s one.
- **The bind in the task's namespace.** A JVM with threads cannot `setns` into a user namespace, so the server
  process is started inside the task's user and network namespaces, as the relay is today through
  `EgressPolicy.inNamespace` (`shield`) with `nsenter -t <pid> -U -n --preserve-credentials`. It is started by the
  packaged `porelod`, so it wears `porelo`. It binds there and talks to the host side as it does now.
- **The task's side.** `TaskRunner` (`app-base`) no longer mounts `vault.sock` at `TaskWiring.VAULT_MOUNT`, nor the
  socket under `Mailbox.MOUNT`. `CredentialWiring` (`app-task`) puts the abstract address into the agent's socket
  variable (`route.socketEnvironment()`), and `SSH_AUTH_SOCK` and the mailbox's socket the same way. The mailbox's
  files stay where they are.
- **First red:** in `wire` and `supervisor`, a server bound to an abstract name in a fresh network namespace (`unshare
  -rn`) is reached from inside that namespace and not from outside, against today's path socket. In `apps/app`, the
  task's specification has no bind mount of a socket.

## Acceptance

- In a running task no socket Porelo serves is a file (`ss -xlp` in the task lists the `@porelo-` names), and the
  agent reaches the vault proxy, the ssh agent and the mailbox through them.
- A process on the host in the same account cannot bind any of these names in the task's namespace before Porelo
  does without joining it. Seen to fail: today's bind-mounted file, which the host's account can replace.

## To be checked

- Whether every shipped agent's client accepts an abstract address in its socket variable (Node's `@`/`\0` form,
  OpenSSH's `SSH_AUTH_SOCK`). Where one does not, a socket file inside the task, bound by Porelo's process in the
  task, stands in for it, and the agent is named in its own repository.
