# B757 — The Vault Socket As An Abstract Name In The Task

```json
{
  "$schema": "issue-1.json",
  "id": "B757",
  "title": "The Vault Socket As An Abstract Name In The Task",
  "state": "Idea",
  "size": "M",
  "priority": "Medium",
  "rank": 55,
  "blockedBy": ["B752"],
  "covers": "Where an agent's client takes an abstract address, Porelo's relay binds the vault socket as `@porelo-vault` in the task's network namespace, so `porelo-task`'s peer rule holds for it; it waits for a client that takes one.",
  "openQuestions": 0
}
```

**Why this size.** M: the relay in `supervisor` gains an abstract listener through FFM with its registration in the
native image, `CredentialWiring` gives the agent the abstract address where its definition says it takes one, and
each part has unit tests. Estimate: 63 minutes (Core's median for M), p80 229.

**What must be true.** Where an agent's client takes an abstract address, the vault socket it connects to is an
abstract name only Porelo's relay could have bound, and the kernel refuses a connect to any other binder.

Split from [B752](README.md#b752), its choice A. [B752](README.md#b752) builds choice C: a socket file in the task's
own mount namespace. AppArmor judges a path socket by file rules, so `porelo-task`'s peer rule
([B753](README.md#b753)) holds only for an abstract socket. Abstract names belong to a network namespace, so they are
bound in the task's own. Measured on walk4 (Ubuntu 26.04): the peer rule refused every abstract binder that was not
`porelo`.

**Decided 2026-11-13 (operator).** Today no shipped client takes an abstract address, so this waits for one. It is
built once an agent's definition says its client takes `@`.

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

So no client that reads the vault socket variable takes an abstract address today. An agent's repository would say
that its client takes `@` in a new definition field, never naming Porelo.

## Limits that shape the plan

- **Java binds no abstract name.** Measured with JDK 25 (GraalVM 25.4.4.1.1): `UnixDomainSocketAddress.of("\0…")`
  throws `InvalidPathException: Nul character not allowed`. An abstract listener needs FFM (`socket`, `bind`,
  `listen`, `accept`, `read`, `write`), so `SocketContext.openUnixSocket()`, which returns a `ServerSocketChannel`,
  cannot gain an abstract form.
- **The relay's label.** Measured on Ubuntu 26.04: `podman unshare nsenter --target <pid> --net <program>` leaves
  `nsenter` in `podman`, and a program at the end of that chain that a profile attaches to by path wears that
  profile. So, inferred: on a machine-wide install the relay started as `/usr/bin/porelo` wears plain `porelo`, and
  `peer=(label=porelo)` matches its abstract socket. An account install stays outside ([B754](README.md#b754)).

## Plan

- **The listener.** `porelo vault relay` gains `--abstract porelo-vault`, beside [B752](README.md#b752)'s
  `--bind-in <pid>`. It binds `@porelo-vault` in the task's network namespace through an FFM listener in
  `supervisor`, labeled through `SocketContext` on the binding thread, and forwards to the host's `vault.sock`.
- **The agent's side.** `CredentialWiring.agentEnvironment` puts `@porelo-vault` into `route.socketEnvironment()`
  where the agent's definition says its client takes an abstract address; every other socket agent keeps
  [B752](README.md#b752)'s socket file.
- **FFM registration.** An FFM downcall is not found by static analysis. The listener's calls go into
  `reachability-metadata.json` through `-Pnative,ffm-check` in the same commit, or the native `porelo` fails at the
  first abstract bind.
- **`wire` stays free of FFM.** `wire` is published to Central and agents build against it; FFM there would ask
  every consumer for native access. The listener stays in `supervisor`, and `SocketContext` keeps only the label,
  applied on the thread that calls `socket()`.
- **First red:** in `supervisor`, the relay bound to an abstract name in a fresh network namespace (`pasta
  --config-net`, since `unshare -rn` is refused on Ubuntu 26.04) is reached from inside and refused outside; skipped
  where neither works.

## Risks

- **SELinux.** The relay runs as `container_runtime_t` under `podman unshare`; whether it may set `porelo_socket_t`
  through `sockcreate`, and `container_t` may connect to it, is asked on Fedora before the change goes out.

## Acceptance

- In a task of an agent whose client takes an abstract address, `ss -xlp` lists `@porelo-vault`, and the agent
  reaches the vault proxy through it.
- On a machine-wide install with the profiles loaded, a process on the host in the same account, joined to the
  task's network namespace, binds `@porelo-vault` before Porelo does, and the agent's connect is refused
  (`operation="connect"` denied in the kernel's log). Seen to fail: [B752](README.md#b752)'s socket file, which the
  peer rule does not judge.
- The native `porelo` binds the abstract name, so the FFM calls are registered.

## To be checked

- Whether an account install's relay, started from `podman` (a profile flagged unconfined), ends in plain `porelo` or
  a stack after its self-entry. Needs root to load the profile, on an Ubuntu 26.04 machine with a running task.
- Whether a Node client takes an abstract address in a socket variable: a Node binary connecting with `socketPath:
  '@name'` to a server on `\0name` measures it.
