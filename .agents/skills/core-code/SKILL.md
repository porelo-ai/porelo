---
name: core-code
description: porelo (core) code rules: Java style, nullness. Use when changing Java code in porelo.
---

# core-code

## Code

- **Java 25: records for data, sealed interfaces where the set of cases is closed.**
- **US spelling in prose and identifiers**; *afterwards* and *towards* stay as they are.
- **A comment in `pom.xml` is one line too.**
- **A secret reaches a container as `--env NAME`**, the value copied from Porelo's own environment; podman drops a name
  it cannot resolve instead of failing, so the names and the values come from one object
  (`ContainerSpec.toArguments()` and `environment()`), tested at both places.
- **A line holds no character of a token**: a start names its variable, and `PhantomToken.toString` and
  `TaskToken.toString` say what it is for. A prefix is part of a secret.
- **A record that holds a secret overrides `toString` to name what it is for**, never what it holds: `CommandResult`
  prints its exit code alone, as a program's output may hold anything, and an address prints without its user
  (`Grants.printable`).
- **The real credential never enters a task container**: the agent holds a phantom token that the broker swaps on the
  way out, and the provider's own host stays reachable, since the token is worthless there.
- **Fail closed on the start path**: the nft hook refuses to start a container whose ruleset does not load.

### Nullness

- **NullAway runs in every main compile as an error, scoped by `@NullMarked`**; test code is not checked.
- **A package without `@NullMarked` is skipped in silence**, so `NullMarkedPackagesTest` fails on one with main code.
- **NullAway trusts unannotated libraries**, so a planted defect that proves the check runs dereferences something
  declared `@Nullable` here (`Json.parse`), never a JDK method.
- **Only what is compiled is checked**: judge with `clean`, not after an incremental `-pl` build.
- **The compiler's lists append** (`combine.children="append"`): every module with commands declares picocli's
  processor, and `AreaModulesTest` fails when one does not.
- **An optional picocli option with no default is `@Nullable`**, and `OptionNullnessTest` walks the command tree.
- **`.mvn/jvm.config` opens javac's internals for Error Prone on JDK 25**; a bare `mvn` from elsewhere misses it.
- **No suppressions**: an invariant the checker cannot see is a `requireNonNull` whose message says it.
