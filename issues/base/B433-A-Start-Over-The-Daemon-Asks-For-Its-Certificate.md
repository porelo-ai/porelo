# B433 — A Start Over The Daemon Asks For Its Certificate

**Status:** Implementing.
**Size:** M - one contract change other repositories read, in the daemon and the task's start, with its documents.

**What must be true.** A task can be started on a host through the daemon's `Start`, its key certified by the person's own
agent at their own computer. The agent is never forwarded.

## Why

A bug: on a host every start through the daemon is refused, so an interface built on `Start` cannot start a task on a host
at all. Seen on a rented host: a start got through the project, the vault and the agent's login and stopped at the
certificate.

- A host starts no task without a certificate (`TaskLaunch`, `doc/commands.md`), and rightly.
- `certifyingOver`, which takes the certificate from the starting person's machine, is reached only from `--certify-stdin`.
  `TaskMethods` passes no certifier, so `Start` depends on an agent the daemon itself holds, and on a host it has none.
- `porelo task start TASK --host LOGIN@HOST --project PROJECT` does it right, by `RemoteStart`, over its own ssh connection
  with `ForwardAgent=no`. It was named for that host; whether the tasks that then ran there went that way is not recorded
  here.

## Plan

The operator chose this way: `Start` carries the request and the interface signs by the local agent.

- **`Start` gains `certify: ?bool`,** declared in `15-task.varlink`, so an interface sees it as it sees `steps`. With it, and only on a
  stream, the stream may carry one reply `{"certify": {"start", "key", "principal", "key_id", "valid_before", "certifiers"}}`: the
  fields `StdinCertifier` asks for, with `start`, a random id made for this one wait.
- **A new method, `Certify(start, certificate: ?string, refused: ?string)`,** on a connection of its own, as the first is streaming.
  An unknown or spent `start` is an error and changes nothing. `refused` ends the start with those words.
- **The daemon's side:** a certifier in `app-task`, as the daemon only registers methods, holding the waits by id, reached through a new `TaskLaunch.certifyingBy`, as
  `certifyingOver` stays for `--certify-stdin`. The certificate is at most what `StdinCertifier` reads, and
  `TaskCertification.certify` checks it as any other. A wait ends at five minutes, or when the stream closes, as a refused start,
  and leaves nothing behind.
- **The interface's side, with no Porelo needed on that computer:** it checks what `RemoteStart.certify` checks: Ed25519, the one
  principal, the end, a listed certifier its agent holds. It makes the certificate as `AgentCertifier` does, by `ssh-keygen` and the
  agent, and the daemon's check still has the last word.
- **Renewal stays `porelo task certify --host` at the person's computer,** which needs no change. Each row of `List` and `Watch`
  gains `certifiedUntil`, so an interface can say a certificate is ending and name that command; no method of its own.
- **Off a host nothing changes for a client that does not send `certify`:** a task whose starter's agent holds no certifier's key still
  starts uncertified there. An interface sends it for a machine that is not its own.
- **Red first:** a daemon test: a `Start` with `certify` gets the request, and a good certificate through `Certify` lets it go on.

Not by forwarding: `AllowAgentForwarding no` on a host is meant, and a socket forwarded some other way lets root there sign
anything with the card's key.

## Acceptance

- Against a host, a start through the daemon with the agent at the person's computer runs, and the task is certified.
- A certificate for another task, another key or more than seven days is refused and starts nothing.
- A certificate that never comes ends the start by its limit, leaving nothing behind.
- A second caller cannot answer another's waiting start.
