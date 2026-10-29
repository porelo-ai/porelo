# B224 — An Agent Protocol That Only Grows

**Status:** Analyzed.
**Size:** M - small in code, one module and its tests, but it changes the contract every agent repository reads, and
its rule goes into the contract's documentation.

**What must be true.** A method added to the agent contract (`Agent1`) does not force every agent repository to release
in step with `porelo`: an agent built against an older contract keeps working, and a method it lacks is answered as
"this agent cannot say", never as "nothing found".

## Why

`InstalledAgent`'s handshake refuses any `protocolVersion` other than `AgentProtocol.VERSION`, so a new method, such
as host findings, means a
new version and every agent rebuilt and released together with `porelo`.

## The shape proposed

- **The handshake accepts an agent at or above the lowest version this `porelo` needs**, each agent reporting the
  version of the contract it was compiled against through `AgentServer`.
- **`Agent1` only grows**: a method or an optional field may be added; a changed parameter, field or meaning of an
  existing method raises the lowest version instead. Written into the contract's Javadoc and `agents/README.md`, since
  the agent repositories rely on it.
- **A method the agent lacks** answers varlink's `org.varlink.service.MethodNotFound`, which `porelo` renders as "this
  agent cannot say".
- **A method whose answer can be unknown carries that state itself**, distinct from an empty answer: an agent that
  implements a lookup but cannot read its tool's state there says so.

## Plan

Read in the code on 2026-10-20 (`agents/api`).

1. **`AgentProtocol`**: `VERSION` stays what an agent reports (3 today, bumped to 4 when the first method is added
   under the new rule); a new `LOWEST = 3` is what `porelo` accepts. Their Javadoc and `agents/README.md`'s *Protocol
   versions* (`:405-414`) state the rule: a method or an optional field is added without raising `LOWEST`; a renamed,
   removed or reinterpreted one raises it. The three bumps so far (1 to 3) were each of the second kind: the binaries
   split out, `ContainerSetup`'s `endpoint`, the definition's `provider`.
2. **`InstalledAgent.handshake`** (`:312-331`): refuse below `LOWEST`, naming both numbers and which side to upgrade;
   accept anything at or above it, a newer agent included, since `porelo` calls only methods it knows.
3. **An optional call**: `InstalledAgent.optional(method, parameters)` returns a sealed `Answer` - `Answered(map)` or
   `CannotSay(method)` - mapping only a `VarlinkException` whose `getErrorName()` is
   `org.varlink.service.MethodNotFound`; every other error still throws. `format` (`:465`) and `ended` (`:487`) catch
   every `VarlinkException` today; they keep their fallback but go through `optional`, so a real failure is no
   longer read as an old agent.
4. **A test seam**: a package-private `InstalledAgent` constructor over an already-connected `VarlinkClient`, so a
   test can serve a chosen `Describe` and a chosen subset of methods from a plain `VarlinkServer`; no test covers a
   version mismatch today.
5. `builds/api`'s handshake stays exact; a build reader is not touched here.

**Red first:** `InstalledAgentVersionTest`: an agent answering `protocolVersion` 2 is refused with both numbers; 4 is
accepted; a call `optional` makes to a method the server lacks is `CannotSay`, and one the server answers with
another error throws.

## Acceptance

- An agent stub answering the previous version is accepted and works; one answering below the lowest version is
  refused with both versions named.
- A call to a method the stub lacks is rendered as "cannot say", and seen to fail first where it was rendered as
  nothing found.
- The rule is in `AgentProtocol`'s Javadoc and `agents/README.md`.
