# B308 — A Guide For Adding A Provider Or An Agent

**Status:** Idea.

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

## To be checked

- Whether the kit ships the shared features as files an adapter copies, or as a feature set it runs with its own
  parameters from the kit's jar: the second keeps one copy, if Cucumber can run a feature from a jar.
