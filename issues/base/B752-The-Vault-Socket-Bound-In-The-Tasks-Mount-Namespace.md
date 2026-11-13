# B752 — The Vault Socket Bound In The Task's Mount Namespace

```json
{
  "$schema": "issue-1.json",
  "id": "B752",
  "title": "The Vault Socket Bound In The Task's Mount Namespace",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 51,
  "blockedBy": [],
  "covers": "The vault socket a task's agent reaches is a socket file Porelo's relay binds in the task's own mount namespace, through the container's root, never a file bind-mounted from the host.",
  "openQuestions": 0
}
```

**Why this size.** S: the relay in `supervisor` gains one option that binds a socket file through the container's
root; `CredentialWiring` and `TaskLaunch` start the relay for a socket agent too, after the container exists;
`TaskRunner` drops one mount; each part has unit tests. No FFM and no contract another repository reads. Estimate: 45
minutes (Core's median for S). It starts after [B742](README.md#b742), which changes the same `podman exec` paths.

**What must be true.** The vault socket a task's agent connects to lies in the task's own mount namespace, and only
the task's root can replace it. A process of the same account on the host cannot put a file in its place without
joining the task.

Split from [B175](README.md#b175)'s step 9. Measured on walk4 (Ubuntu 26.04): AppArmor judges a socket with a file
path by file rules, so `porelo-task`'s peer rule never saw a connect to Porelo's path sockets. The abstract name the
peer rule holds for is [B757](README.md#b757).

**Decided 2026-11-13 (operator).** Choice C is built alone now. Choice A, an abstract socket with an FFM listener,
becomes [B757](README.md#b757) and waits for a client that takes an abstract address. On AppArmor the peer rule of
[B753](README.md#b753) then protects no shipped socket agent; that is accepted. With this build, `doc/security.md`
says that the peer rule holds only for abstract sockets ([B757](README.md#b757)), and that until then the task's own
mount namespace protects the socket file.

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

- **`VaultProxy` cannot run in the task's namespace.** It needs the host's resolver and egress: a broker bound in the
  namespace read the host's `resolv.conf` and resolved nothing (`CredentialWiring.relay`'s comment), and the
  provider's host is withheld from the task's ruleset. Only a forwarder sits in the task.
- **`nsenter -U -n --preserve-credentials` fails under `podman unshare`.** Measured on Ubuntu 26.04 with a rootless
  container: `podman unshare nsenter -t <pid> -U -n --preserve-credentials` answers `EINVAL` (setns into the user
  namespace it is already in). Today's form, `podman unshare nsenter --target <pid> --net`, works and stays.
- **No mount namespace is joined.** The relay binds through `/proc/<pid>/root`, so its upstream connect still sees
  the host's files.

## Plan

- **The relay binds in the task.** `porelo vault relay` (`VaultRelayCommand`, `SocketRelay` and the new `TaskSocket`
  in `supervisor`) gains `--bind-in <pid> --at <path>`, beside `--listen <port>`. It binds `TaskWiring.VAULT_SOCKET`,
  `/run/porelo-vault/vault.sock`, through `/proc/<pid>/root`, in a directory of its own made root-owned `0755`, the
  socket `0666`. Not under `/run/porelo`, which a task keeps root-only `0700`, so the agent could not reach a socket
  there. No part of the path below the root may be a link or writable by others, since the relay is the host's
  account and a link inside the container's root resolves against the host's. It is
  still started by `CredentialWiring.relay` under `podman unshare` after the container exists, and still forwards to
  the host's `<state>/vault.sock`. Measured on Ubuntu 26.04 with a rootless container: bound so under `podman
  unshare`, the agent user (1001) reached it with curl (`204`), and could not remove it.
- **The task's side.** `TaskRunner` (`app-base`) no longer bind-mounts `vault.sock` at `TaskWiring.VAULT_MOUNT`.
  The socket variable names `/run/porelo-vault/vault.sock`. `TaskLaunch` starts the relay for a socket agent too, before
  the agent runs, and a resumed task starts it again with the container's new pid, as `TaskControl` does for the URL
  relay.
- **The broker stays on the host**, bound at `<state>/vault.sock` in the owner-only state directory, reached only by
  the relay.
- **What holds.** It is a path socket, so AppArmor's peer rule does not judge it. What holds is that only the task's
  root can replace the file, and a host process must join the task to do so.
- **Docs.** `doc/security.md` as decided above.
- **First red:**
  - a relay bound through a process's root makes the socket inside, not on the host;
  - in `apps/app`, the task's specification has no bind mount of a socket.

## Risks

- **SELinux.** The relay runs as `container_runtime_t` under `podman unshare`; whether `container_t` may connect to
  the socket it binds in the container's root is asked on Fedora before the change goes out.

## Acceptance

- In a running task no socket Porelo serves is bind-mounted from the host. `/run/porelo-vault/vault.sock` is a socket of
  the task's root, and the agent reaches the vault proxy through it. Seen to fail: today's bind mount of the host's
  `vault.sock`.
- A process on the host in the same account, not joined to the task, cannot replace the task's socket file. Seen to
  fail: today's bind-mounted file, which the host's account can replace.
- `doc/security.md` says where the peer rule holds and what protects the socket file until then.

## Built

- `TaskSocket` binds the socket through the container's root, refusing a link or a directory others may write
  anywhere on the path; `porelo vault relay --bind-in <pid> --at <path>` serves it beside or instead of `--listen`.
- `TaskRunner` mounts no socket; `TaskLaunch` starts the relay for a socket agent after the container runs, and a
  resume starts it again with the container's new pid (`--bind-in` follows `--target`).
- `doc/security.md` and `doc/credentials.md` say where the socket is and what protects it.
- Seen red, then green: `TaskSocketTest` (a link where the directory goes or above it, a directory others may write)
  with the checks taken out; `TaskRunCommandTest`'s socket agent created with no socket mounted from the host, with a
  socket mount put back.
- Against a real rootless container (opt-in `-Dporelo.test.vault.socket=true`, podman 5.7): the agent's account
  reached the broker through the socket, and could neither remove nor replace it, nor add a file beside it.

- The reading by another model: a directory above the socket's that is not the task root's, or that its group may
  write, and a `..` in the path are refused now (seen red, then green). Left, with the reason: a mount an image's own
  links redirect onto the socket's directory is the image's, and so its builder's risk; a failure only under
  `OutOfMemoryError`, and a cast in `SocketRelay.address()` that no unix relay calls. A relay missing or stale on
  resume, and a relay that lives on with one endpoint, are [B758](README.md#b758).
- The SELinux module is 1.2: `container_runtime_t`, the relay under `podman unshare`, may bind the labelled socket,
  as `unconfined_t` of the same account may. Measured on Fedora 44 under 1.1: labelled, the relay was refused the
  socket; unlabelled, the task was refused the connect.

## To be checked

- On Fedora with SELinux enforcing and module 1.2 loaded, a task's agent connects to the socket the relay binds as
  `container_runtime_t`: the CI's Fedora leg, which loads its own tree's module.
