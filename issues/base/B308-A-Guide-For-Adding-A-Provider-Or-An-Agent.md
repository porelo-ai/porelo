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
- **Part 2, agents**, kept by the agents' repositories: first-run questions measured attended; sign-in stores the
  agent cannot write; a session kept across stop and start; idle and busy wake; the version pin and the update that
  moves it.
- **Shared by both:** the walk script's shape - version and adapter digest read back first, a throwaway vault and
  project in a test account, the script checked by SHA-256, a cap on provider admissions counted by the broker, a stop
  at the first failure with no retry, no real token in the container's environment, files, processes or session
  record, and a cleanup of only what it made that reports what is left.
- **Where:** the guide is one page in `doc/`, in `mkdocs.yml`'s navigation; the scripts sit beside the acceptance
  kit, so an agent repository runs them from the published kit.
