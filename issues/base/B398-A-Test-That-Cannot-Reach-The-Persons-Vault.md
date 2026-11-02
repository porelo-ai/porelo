# B398 — A Test That Cannot Reach The Person's Vault

**Status:** Idea.

**What must be true.** A test run on a person's machine never reads or writes that person's vault or configuration, even
when it builds a command the wrong way. Today 106 commands start with `PoreloContext.real()` until a factory gives them
another (counted with a search on 2026-11-02), and picocli's factory is not asked for an instance a test passes in itself. A
test of `vault put` built so reached the real context and stopped only because no passphrase was to be had; with the vault
unlocked it would have written its fake token into the person's vault.

## To be checked

- Whether a test JVM can make `PoreloContext.real()` refuse, for instance by a property surefire sets, and what in the test
  suite relies on it today.
- Whether any test now passes in a command that reads real state: a first search for `CommandLine(new ...Command()` finds
  only the root `TalkCommand` in two tests, whose subcommands do get the factory's context. Not read further.
