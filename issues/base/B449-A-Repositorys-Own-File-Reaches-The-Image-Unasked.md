# B449 — A Repository's Own File Reaches The Image Unasked

**Status:** Backlog.
**Size:** S to M.

**What must be true.** A repository's `porelo.yml` chooses no line of an image on a person's machine without their yes.

The operator's word: after the test, as an issue of its own.

## Found

Codex's reading of [B444](README.md#b444), and read in `ImageChoice.java`, whose own comment states the order. It is older
than that issue:

- In Default, a start in a checkout reads the checkout's `.porelo.yml` and `porelo.yml` with their snippets, whatever was
  answered: at a No, at `--no-input`, and with no plugin (`:148`).
- In any other project, it reads `porelo.yml` from the default branch of the mirror (`:181`), so an approved project takes
  new image lines from upstream without a new question.

## What Is Asked

- On the Default path and at a first start, a `porelo.yml` shows in the question and counts in the hash, and a No leaves
  it out.
- An approved project's changed upstream `porelo.yml` asks again.

## To Check

Codex says these builds have the host's network. To be checked here, as it decides how much a `RUN` line can do.
