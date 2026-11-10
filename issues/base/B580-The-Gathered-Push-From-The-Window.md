# [B580](README.md#b580) — The Gathered Push From The Window

**Status:** Implementing, since 2026-11-10. Filed 2026-11-09 under ST291, from the fourth move.
**Size:** M - a daemon method for [B544](README.md#b544)'s gathered push, its progress and refusals as values.

**What must be true.** A client of porelod can bring a host's finished `main` to central for a project, as
[B544](README.md#b544)'s `porelo host push` does, with progress and a typed answer, so the window offers it as the
release a person expects in class `online`.

## Plan

- A Tasks1 method `PushToCentral(project)` over the daemon, streaming progress, answering typed refusals (vault shut,
  no central token, central refused the token, nothing to push), calling what [B544](README.md#b544) builds.
- Its interface text and `doc/`; the window's button is porelo-frontend's.
- Red first: a refused central token answers its typed error, never a git prompt's words.
