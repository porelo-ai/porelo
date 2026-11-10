# B398 — A Test That Cannot Reach The Person's Vault

```json
{
  "$schema": "issue-1.json",
  "id": "B398",
  "title": "A Test That Cannot Reach The Person's Vault",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 123,
  "blockedBy": [],
  "covers": "A test that builds a command the wrong way falls to the real context, and so to the person's vault"
}
```

**Why this size.** a refusing real context and paths in a test's JVM, set by the build for every unit test; core, the app's context and paths, and the root's surefire configuration (sized 2026-11-10 from what was built).

**What must be true.** A test run on a person's machine never reads or writes that person's vault or configuration, even
when it builds a command the wrong way. Today 106 commands start with `PoreloContext.real()` until a factory gives them
another (counted with a search on 2026-11-02), and picocli's factory is not asked for an instance a test passes in itself. A
test of `vault put` built so reached the real context and stopped only because no passphrase was to be had; with the vault
unlocked it would have written its fake token into the person's vault.

## Plan

- The build sets `porelo.test.real.account.refused` for every unit test's JVM, in the root's surefire configuration beside
  `porelo.packaged.root`; `RealAccount` in `core` reads it, and it only ever refuses, so it names no other vault to read.
- `PoreloContext.real()` still answers, since commands call it when they are constructed, with a context that holds no real
  path and refuses when any part of it is used, by an `Error` no `catch` of an exception takes for the command's own refusal.
- `XdgPaths.current()`, and through it `PoreloPaths.current()`, `TrustedCertificates.builder()` and an HTTPS remote under
  `GitCredentials.NONE`, refuses too. So does `~` in a declared path, which is the real home.
- With nothing named, `PoreloBinary` in a test's JVM names no program, where a real run takes the `porelo` on the path, and
  `OwnPorelo`'s launcher sets the property for its child: a helper a test starts inherits the real `HOME` and XDG variables.
- `PORELO_VAULT` is kept out of a unit test's JVM: it overrides whatever paths a test gave.

## To be checked

- A helper started by a test through a launcher that is not `OwnPorelo` runs on whatever account its environment names; the
  earlier four such launchers were read; the two registry launchers are gone, and a new one is held by nothing.
- Whether a person's own project called `uc` ever got a gate from the starts counted below: it means reading the person's
  configuration, so it was not looked at.
- `TaskLaunch.agentSocket` reads `SSH_AUTH_SOCK` from the process, not the context: the variable is kept from the test JVM, and the read could still move to the context.

## Checked

- **A command in main reached the real context from a test on every run.** `TaskLogsCommand.rendered` handed picocli
  instances of three commands with a factory, so they kept their field's `real()`: `TaskLogArchiveTest` ran them on the real
  machine and passed whatever they found. It now hands their classes. The daemon's archive used a fresh `real()` the same way.
- **`TaskRunCommandTest` started `gate serve --project uc` on the real account 32 times a run**, counted by a temporary
  recorder in `OwnPorelo`'s launcher: each child inherited the real `HOME` and XDG variables and looked the project up there.
- **`/usr/bin/porelo` was started by no test in `apps/app` or `daemon` in that recorded run**, counted the same way at the
  fallback.
- **`AgentLoginFromDirectoryTest` read the real vault file on every run.** Two of its three `VaultLoginCommand` hits are not
  dry runs, so `call()` asked the real vault whether it exists and has a passphrase slot, and with none would have asked for
  its opener, before refusing the options with 64. They now get the test's context.
- **The other commands a test builds itself:** `TalkCommand` holds no context, and its subcommands get the factory's. Every
  other `new XCommand()` either calls `setContext` on the next lines or uses nothing of its context.
- **Tests that used `real()` only to walk or parse the tree** (`CliErrorsTest`, `CompleteCommandTest`, `OptionNullnessTest`,
  `CommandsDocumentedTest`) now use `NoAccount`, since building the tree asks the context for its paths.
- **Child JVMs left without the property, each read:** `CommitVerifyCommandTest` runs with no `HOME` and
  `-Duser.home` in the test's directory; `HelperSocketOnSignalTest` sets the XDG variables to the test's.
  `RegistryVerifyCommandTest` and `RegistryJoinableCommandTest` are gone; their helpers no longer run.
