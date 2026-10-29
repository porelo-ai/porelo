# B200 — A Varlink Call Has A Read Deadline

**Status:** Analyzed.
**Size:** M - a bounded read in `porelo-wire`, which `porelo-agent-api` publishes onward, and the callers in four
modules moved onto it, three hand-made limits among them; no contract changes.

**What must be true.** A varlink call through porelo-wire's `VarlinkClient` ends within a stated time when the other
side accepted the connection and never answers, with a named error that says so. No contract changes: the
interfaces, their methods and their answers stay as they are.

**Why.** `VarlinkClient` reads an answer with no time limit (`VarlinkConnection.receive`), so a daemon that accepts and
never answers holds every caller: the IntelliJ plugin, whose gate calls hold `PoreloWatch`'s lock so that closing a
project hangs too, and Porelo's own callers. The build reader already bounds its calls around the
client (`InstalledBuildReader`, 10 s to start, 120 s a question); every other caller does not. A streaming call
(`callMore`) needs its own rule, since a stream may rightly be quiet for long. The interface is not one of the callers:
its own Dart client already ends a call answered once after 30 s, and leaves a stream unbounded on purpose - the same
rule this asks for.

## Plan

Read in the code on 2026-10-20 (`wire`, `agents/api`, `builds/api`, `apps`, `daemon`).

1. **Where it blocks**: `VarlinkConnection.receive` (`:64`) ends in a blocking `channel.read` (`:81`) on a unix-domain
   `SocketChannel`, which has no `SO_TIMEOUT` and no `socket()` adapter. `receive(Duration)` instead switches the
   channel to non-blocking, as `peerGone` (`:98`) already does for a moment, and waits on a `Selector` for `OP_READ`
   with the time left, looping until a whole message or the deadline; the 4 MiB cap stays.
2. **The client**: `call(method, parameters)` waits at most `VarlinkClient.DEFAULT_DEADLINE`, 30 seconds, the limit
   the interface's own client already keeps; `call(method, parameters, Duration)` takes another. A call that
   outlives it throws `VarlinkTimeoutException`, a subclass of `VarlinkException` naming the method and the limit,
   with no error name, so `InstalledBuildReader`'s check that a null name means the connection failed (`:237`)
   still holds; the connection is closed, since a late answer would be read as the next call's.
3. **A stream**: `callMore` keeps no deadline by default - `ClearanceService.Subscribe` and `ShieldSubscribe` are
   quiet until there is news - and `callMore(method, parameters, consumer, Duration)` bounds the wait for each reply
   where a caller wants it.
4. **The callers**:
   - `InstalledAgent`: every call, not only the handshake (`EXTRACT_CREDENTIAL`, `CONTAINER_SETUP`, `BUILD_COMMAND`,
     `FORMAT`, `ENDED`), at the default; its `bounded` helper stays only for the ready line, which is not varlink.
   - `InstalledBuildReader`: its 120 s `CALL_SECONDS` passed as the deadline; on `VarlinkTimeoutException` it kills
     the process as `bounded` does today.
   - `DoctorCommand.versionAt`: its own executor goes, the 5 s passed as the deadline.
   - `ShieldReadCommand` and the daemon's `ClearanceService.Verdict` call (`EgressMethods`, `:262`): the default.
5. `wire/README.md` states the rule: an answer within a deadline, a stream unbounded unless asked.

**Red first:** `VarlinkTest`: a server that accepts, reads the call and never answers - the shape
`aStreamCutShortIsAnErrorRatherThanAnEnding` (`:134`) once hung on - makes `call(..., 200 ms)` throw
`VarlinkTimeoutException` within a second; today the test hangs, so it runs under JUnit's own timeout. A
`callMore` without a deadline against the same server still waits, and one with a deadline ends.

## Acceptance

- A call to a side that accepted and never answers ends after its deadline with `VarlinkTimeoutException`, naming
  the method; a stream without one keeps waiting.
- An agent that hangs in `ContainerSetup` fails the task's start after the deadline, named, instead of holding it.
- No varlink interface changes.
