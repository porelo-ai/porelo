# B123 — Message Transports Behind A Published API

**Status:** Analyzed.
**Size:** XL - a new published library, the host's client, a stub, and a move in another repository; splits below.

**What must be true.** A message transport is written against a published, versioned Java API, as an agent is - a
library it builds on and a contract Sokar checks at runtime - and every transport lives in a repository of its own,
so a new one needs nothing from `sokar` but that API.

## Why

A transport is already a package of its own: `sokar-message-matrix` is installed on the host, Sokar finds it and
runs it, and no Matrix code is in `sokar` (the decision "Sokar knows transports; the transport knows Matrix"). What
it builds against is not. The contract is a command line - `describe`, `send`, `poll`, the lifecycle verbs, exit
codes from 0 to 78, files in a directory Sokar made - written down only in
[writing a transport](../../doc/transports.md). So every transport parses its own arguments, maps its own exit codes
and writes its own JSON, and a mismatch between what Sokar expects and what a transport does is found when a message
does not arrive.

Agents solved the same problem differently, and better for whoever writes the next one: `sokar-agent-api` on Central
holds both the contract and the code that serves it - an agent implements an interface, the library speaks the
versioned varlink protocol `org.fuin.sokar.Agent1`, and Sokar refuses an agent whose protocol version it does not
know rather than misreading it. The build readers take that shape too ([writing a build reader](../../doc/build-readers.md)). Messaging should not be the one
extension point left on a hand-written command line.

## What changes

- **A published `sokar-transport-api`**, beside `sokar-agent-api`: the transport's interface (describe, send, poll,
  read, receipt, and the lifecycle `setup`, `enroll`, `retire`, `join`, `settings`), the server side an implementation
  runs, and the client Sokar uses. Every refusal a named value, as the exit codes are today.
- **A versioned varlink contract**, `org.fuin.sokar.Transport1`, with a protocol version Sokar checks before it uses a
  transport; one that speaks another version is refused against its own name, and the others stay usable.
- **Secrets stay where they are today**: kept in the vault and handed to the transport on the host, never into a
  task; over the owner-only socket rather than the environment of a command.
- **A stub transport in `sokar`**, as `agents/stub` is for agents, so the acceptance suite proves the contract
  without a homeserver; nothing outside it names a transport.
- **`sokar-message-matrix` moves to the API** in its own repository; a second transport is written against the API
  alone.

## Plan

Read in the code at `30c8169b`:

- **Today's contract, host side**, in `apps/app-messaging/src/main/java/org/fuin/sokar/app/`: `TransportDescription`
  (runs `describe` and reads what a transport takes), `TransportSend`, `TransportPoll`, `TransportConversations`
  (`waitDirect`, the room) and `TransportLifecycle` (`setup`, `enroll`, `retire`, `join`, `settings`, `clear`), each
  building a command line, passing secrets in the command's environment (`TransportSend.Acting.environment`) and
  mapping exit codes. `MessageWatch` starts a loop per task's direct chat and per project's conversation on top.
- **The shape to copy**: `agents/api` (`sokar-agent-api`: `Agent`, `AgentServer`, `AgentProtocol`, `AgentMain`,
  `AgentRegistry`) and `builds/api` (`sokar-build-api`), with `agents/stub` and `builds/stub` proving each in the
  suite. The varlink plumbing is in `wire` (`sokar-wire`).

It splits into five issues, each L or smaller, in this order:

1. **The library and its contract (L)**: `transports/api` as `sokar-transport-api` - the interface, every refusal a
   named value, `org.fuin.sokar.Transport1` with its protocol version, the server an implementation runs, the
   client. Red first: a test client against a test server that speaks version 2 is refused by name.
2. **A stub transport (M)**: `transports/stub`, built against the API and `sokar-wire` only, carrying messages
   between two tasks through a directory; a dependency check fails the build if it names anything else of `sokar`.
3. **The host speaks the API (L)**: the five `Transport*` classes use the client; secrets go over the owner-only
   socket, not the environment; one transport refused for its version leaves the others working. The suite's
   messaging scenarios run on the stub. Red first: a scenario that greps the process list and a task's environment
   for a transport secret.
4. **`sokar-message-matrix` moves (L, its own repository)**: built against the API, released, and its scenarios
   green against a `sokar` that speaks only the API.
5. **The command line goes (M)**: `doc/transports.md` rewritten for the API; the command-line contract, its exit
   codes and their tests removed from `sokar`.

## Acceptance

- A transport built only against `sokar-transport-api` and `sokar-wire` carries a message between two tasks, and
  `sokar` holds no line of it. Seen to fail: the stub transport needs anything from `sokar` beyond those two.
- A transport speaking an unknown protocol version is refused, named, and the other transports keep working. Seen
  to fail: one broken transport stops messages through another.
- Everything `doc/transports.md` promises today holds over the API: the guarantees of delivery, the signature
  beside the message, the refusals and what each means. Seen to fail: a scenario of the messaging features that
  passes with the command line fails with the API.
- No transport secret reaches a task or a command line. Seen to fail: a search of the process list and of a task's
  environment finds one.

## To be checked

- **Whether a transport runs as a long-lived process**, as an agent does, or is started per call as today: a
  process per poll costs a JVM start where the transport is not native (Agent Core's).
- **How the move happens without a gap**: both contracts side by side for one release, or `sokar-message-matrix`
  and `sokar` moved in one step (Agent Core's, with the agent of `sokar-message-matrix`).
- **Whether `sokar-agent-api`, the build readers' API and this one share the scaffolding** - discovery, handshake,
  protocol version, a broken one recorded against its own name - in one library, or each carries its own
  (Agent Core's).
