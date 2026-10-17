# B200 — A Varlink Call Has A Read Deadline

**Status:** Accepted.

**What must be true.** A varlink call through porelo-wire's `VarlinkClient` ends within a stated time when the other
side accepted the connection and never answers, with a named error that says so. No contract changes: the
interfaces, their methods and their answers stay as they are.

**Why.** `VarlinkClient` reads an answer with no time limit (`VarlinkConnection.receive`), so a daemon that accepts and
never answers holds every caller: the IntelliJ plugin, whose gate calls hold `PoreloWatch`'s lock so that closing a
project hangs too (porelo-intellij [IJ06](https://github.com/porelo-ai/porelo-intellij/blob/main/issues/README.md#ij06),
found in its reading on 2026-10-17), and Porelo's own callers. The build reader already bounds its calls around the
client (`InstalledBuildReader`, 10 s to start, 120 s a question); every other caller does not. A streaming call
(`callMore`) needs its own rule, since a stream may rightly be quiet for long. The interface is not one of the callers:
its own Dart client already ends a call answered once after 30 s, and leaves a stream unbounded on purpose - the same
rule this asks for.
