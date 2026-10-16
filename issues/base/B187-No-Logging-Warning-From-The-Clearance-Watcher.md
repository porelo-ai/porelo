# B187 — No Logging Warning From The Clearance Watcher

**Status:** Review.
**Size:** S - the logging facade told to stay silent in the binary, its native-image entry, and a test of the
watcher's log.

**What must be true.** `clearance.log` holds what the clearance watcher says, and no warnings from a logging library
it does not use.

## Why

A security analysis on a test machine found `clearance.log` starting with three lines of
`SLF4J(W): No SLF4J providers were found`. A log that opens with warnings teaches a person to skip its first lines.

## Plan

Read in today's code: `dbus-java-core` brings in `slf4j-api` (2.0.17) in `clearance` and so in the binary. It is the
D-Bus client the watcher uses for its desktop prompt. No provider is on the class path, so SLF4J warns the first time
the D-Bus client asks for a logger, which only the watcher does.

The plan:

- **The fix:** the binary sets `slf4j.provider` to SLF4J's own `NOP_SLF4JServiceProvider` at its start, before
  anything logs. SLF4J 2 reads that property and then neither searches nor warns. Set in `main`, it covers every
  command; it is not set as a JVM option, which the native binary does not take.
- **The native image:** that class is named in `reflect-config.json`, since SLF4J instantiates it by name.
- **Why not a binding:** adding `slf4j-nop` would do the same through the service loader. It is one more artefact to
  keep current for a library whose output nobody reads.

Steps:

1. **Red first:** a test runs the watcher's D-Bus path far enough to ask for a logger, and nothing is written to
   standard error.
2. The property in `main`, and the reflection entry.
3. **On the VM:** a stub task with clearance `prompt`; `clearance.log` holds no `SLF4J` line.
4. Reactor, documents and the suite on the VM (`not @restart`).

## As built

The switch is a different one from the plan, with the same effect and less to keep. Instead of naming SLF4J's
no-operation provider, which needs a reflection entry, `PoreloCli.main` sets SLF4J's own
`slf4j.internal.verbosity=ERROR` before anything logs:

- SLF4J 2.0.17 reports the missing provider at `WARN` through its `Reporter`, so the warning is not said, and it falls
  back to its no-operation logger as before.
- Nothing is instantiated by name, so the native image needs no new entry.
- A value somebody set is left alone.

`PoreloCliTest` asks for a logger with no provider bound and finds nothing said on standard error.

