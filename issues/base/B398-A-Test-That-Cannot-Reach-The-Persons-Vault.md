# B398 — A Test That Cannot Reach The Person's Vault

**Status:** Implementing.

**What must be true.** A test run on a person's machine never reads or writes that person's vault or configuration, even
when it builds a command the wrong way. Today 106 commands start with `PoreloContext.real()` until a factory gives them
another (counted with a search on 2026-11-02), and picocli's factory is not asked for an instance a test passes in itself. A
test of `vault put` built so reached the real context and stopped only because no passphrase was to be had; with the vault
unlocked it would have written its fake token into the person's vault.

## To be checked

- Every test that builds a command or `PoreloContext.real()` directly. A first search for `CommandLine(new ...Command()` has 5
  hits: `VaultLoginCommand` three times in `AgentLoginFromDirectoryTest`, and the root `TalkCommand` in `TransportLentTest` and
  `TransportConversationsTest`. Not read yet.
- `PoreloContext.real()` runs when a command is constructed, before a factory gives it its context, so refusing there is not
  enough by itself.
