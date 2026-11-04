# B433 — A Start Over The Daemon Asks For Its Certificate

**Status:** Idea.

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

## The shape

- `Start`'s reply stream asks for the certificate, as `--certify-stdin` asks on a line of its output. The interface checks
  what `RemoteStart` checks and has the person's agent sign.
- A reply stream cannot answer, so the certificate comes back by a second call into the waiting start, bound to that one
  start and limited in time.
- `Start` stays as it is where nothing asks: a client that gives no certificate gets today's answer.
- `task certify` over the daemon follows the same way, or a task on a host cannot be renewed from an interface.

Not by forwarding: `AllowAgentForwarding no` on a host is meant, and a socket forwarded some other way lets root there sign
anything with the card's key.

## Acceptance

- Against a host, a start through the daemon with the agent at the person's computer runs, and the task is certified.
- A certificate for another task, another key or more than seven days is refused and starts nothing.
- A certificate that never comes ends the start by its limit, leaving nothing behind.
- A second caller cannot answer another's waiting start.

## Open questions

- This way, or an interface running `task start --host` itself, which needs nothing new here and leaves `Start`'s progress
  behind. This one is proposed.
