# B308 — A Guide For Adding A Provider Or An Agent

**Status:** Analyzed.
**Size:** L - crosses repositories: the kit's feature set and its steps, porelo-parent's acceptance profile, the
guide and its scripts here; each adapter switches to the kit's set in an issue of its own.

**What must be true.** Whoever adds a provider or an agent finds in `doc/` one guide to the checks that prove it
safe, and beside the acceptance kit the scripts that run them, so each new one is tested as the last was rather than
by a walk written from scratch.

## Why

The same patterns were rewritten for each provider and agent: porelo-codex
[CX01](https://github.com/porelo-ai/porelo-codex/blob/main/issues/README.md#cx01), porelo-pi
[PI21](https://github.com/porelo-ai/porelo-pi/blob/main/issues/README.md#pi21), porelo-agy
[AG01](https://github.com/porelo-ai/porelo-agy/blob/main/issues/README.md#ag01), [P11](../providers/README.md#p11),
[P12](../providers/README.md#p12), [B272](README.md#b272) and [B303](README.md#b303). Each walk found its faults
again, and a walk written in a hurry spends a provider's money on a fault an earlier one had already met.

## The shape proposed

- **Part 1, providers:** what a provider is asked before it is shipped, and an example script that records through
  the broker how it answers: its framing (typed, untyped, events), its statuses and refusals, a renewal with a
  rotated refresh token, and the headers it needs beside the token.
- **Part 2, agents.** All five adapters carry the same feature files - `credential`, `installed`, `session`,
  `terminal`, `waiting`, `wake`, in two of them `broker` - each its own copy, three copied word for word and two
  written anew. The patterns, each with the harm it prevents:
  1. *The credential never reaches the task* - a real token in the container is the agent's to leak; the container's
     environment, export and session record hold only the phantom (kit, whole).
  2. *No sign-in can be stored where the agent's CLI reads one* - a CLI's own `/login` would leave a non-expiring key
     in the task; every write, move and replace of its stores is refused, as porelo-pi
     [PI21](https://github.com/porelo-ai/porelo-pi/blob/main/issues/README.md#pi21) proves (kit: the script, its
     paths as parameters; guide: why a root-owned file under the home does not hold).
  3. *A subscription is renewed only on the host* - Part 1's, linked from here.
  4. *First-run questions are measured attended, never guessed* - an unanswered dialog stops an attended task or hangs
     an unattended one (kit: the two scenarios; guide: links the shared adapter rules).
  5. *Refusals say what they are before anything is built* (kit, whole).
  6. *A session survives a stop and a start*, read where the adapter says it is (kit, whole, in the paid leg).
  7. *Waiting is read from what the agent declares, and wake follows it*, an agent that cannot tell never woken (kit:
     the scenarios; guide: how to measure the screen).
  8. *The pin and what installs it agree* - `installed.feature` whole, `PinAgreementTest` as a base class in the agent
     API's test support.
  9. *An operator walk is a checked script*, the shared shape below (kit).

  An adapter keeps only what is its agent's alone: its stores' paths, its directory, its login verb, its first-run
  dialogs, what its screen shows when it waits, and the scenarios of an exception.
- **Shared by both:** the walk script's shape - version and adapter digest read back first, a throwaway vault and
  project in a test account, the script checked by SHA-256, a cap on provider admissions counted by the broker, a stop
  at the first failure with no retry, no real token in the container's environment, files, processes or session
  record, and a cleanup of only what it made that reports what is left.
- **Where:** the guide is one page in `doc/`, in `mkdocs.yml`'s navigation; the scripts sit beside the acceptance
  kit, so an agent repository runs them from the published kit.


## Plan

Read in porelo at 7490b4a7 (`acceptance/kit`: seven step classes, `World`, `GitHubReport`; no feature in the kit's
jar today) and in the adapters at porelo-pi 1ab0e47, porelo-omp b545d4b, porelo-claude-code c2f18ca, porelo-codex
88acb8a and porelo-agy 6a73582. Each adapter's `AcceptanceIT` selects only its own directory
(`@SelectClasspathResource("org/fuin/porelo/agent/impl/<agent>/acceptance")`) with the kit as its only glue
(`cucumber.glue` `org.fuin.porelo.acceptance`), and none has a step of its own. What they hold:

| Feature | pi | omp | claude | codex | agy | Into the kit |
|---|---|---|---|---|---|---|
| `installed` | 8 | 8 | 8 | 2 | 3 | all eight; the bill's three tagged `@bill` |
| `terminal` | 3 + 1 | 4 | 4 + 1 | 4 | 4 | `nocred`, `noask`, refused host, login preview (`@login` / `@no-login`) |
| `session` | 2 | 2 | 2 | 1 | 1 | the stop and start; the attached one tagged `@attached` |
| `credential` | 9 | 9 | 9 | - | 1 + 1 | the eight the three share; the detached broker answer for agy too |
| `broker` | - | 1 | - | - | 1 | the one scenario, tagged `@broker` |
| `waiting`, `wake`, `subscription` | 1, 1, - | 1, 2, - | 1, 2, 1 | 1, 2, - | -, 2, - | stay the adapters' (below) |

1. **The kit ships `org/fuin/porelo/acceptance/agent/*.feature`** in its jar, written once from the copies above.
   A value an adapter differs in is read from its configuration, never written into the feature: new step phrasings
   name it - `mentions the agent's name`, `shows the agent's label`, `the vault holds no credential for the agent's
   provider`, `shows the agent's refused host`, `a task ... for the agent` - and `World` reads `porelo.agent.name`,
   `.label`, `.package`, `.provider`, `.refused-host`, `.declared-host`, `.bill.tree` and `.bill.cli` from system
   properties. A scenario whose value is not set fails naming the property, so a missing one is never a pass.
2. **What an adapter runs is chosen by tags**: `porelo.agent.tags` (for example `not @bill and @login`) goes to
   `cucumber.filter.tags`. `AcceptanceIT` selects the kit's directory beside its own.
3. **porelo-parent's acceptance profile** carries the Failsafe configuration the five adapters repeat today - the
   host, the user, the report plugins - and passes every `porelo.agent.*` property, so an adapter sets values and
   deletes its copies, nothing else. `GitHubReport` names a failing scenario of the kit's set by its path in the
   kit, since no file of the adapter holds it.
4. **Stays the adapter's**: what only its agent does - pi's sign-in stores and wake, claude's memory and
   subscription, codex's model picker, agy's refused key - and `waiting` and `wake` of omp, claude, codex and agy,
   whose waits are written against each agent's screen. They move once their mechanics are kit steps, an issue of
   their own.
5. **The guide** `doc/agent-checks.md`, in `mkdocs.yml`'s navigation: Part 1 (a provider, with a recording script
   beside the kit), Part 2 (an agent: the patterns above, each linking the kit's scenario that holds it), and the
   walk script's shared shape.
6. **Red first**: a kit test reads every feature of the set and fails on a step no kit class defines and on a
   `porelo.agent.*` property the kit's README does not list; seen red with one step misspelled. `World` refuses an
   unset value, seen red with the property left out.

## Decided

- **The kit ships the shared features as a feature set each adapter runs from the kit's jar with its own parameters**,
  never as files an adapter copies. The analysis proves it against the five adapters that exist: each one's feature
  files mapped to the kit's parameterized ones, what stays the adapter's alone, and one adapter run green from the
  kit's set.

## Acceptance

- One adapter's acceptance run is green with its copies of the shared scenarios deleted and the kit's set selected
  instead, on the VM, against the published kit snapshot.
- The guide is in the published site's navigation, and its examples run as written.
- Seen to fail first: the kit's test with a misspelled step, and a run with a value left out.

## To be checked

- The proof the decision asks for: porelo-codex, whose shared scenarios cost no provider money, run green on the VM
  from a locally built kit with `installed` and `terminal` taken from the kit's set.
- Whether Cucumber 7.34.8's JUnit engine takes `@SelectClasspathResource` twice in one suite, one directory from the
  kit's jar: otherwise one `cucumber.features` property naming both.
