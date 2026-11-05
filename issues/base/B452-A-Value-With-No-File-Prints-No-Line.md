# B452 — A Value With No File Prints No Line

**Status:** Review.
**Size:** XS - one comment's words, with a test.

**What must be true.** A value a tool has by itself is said to be that tool's, and a draft's comment prints no `:0`.

## Found

Agent Ecosystems read the Maven adapter's answer on `utils4j`: Maven's built-in Central comes with `file` "" and `line` 0, as the
contract asks of a tool's built-in default. `ProjectProposal.java:312` writes the comment `file:line` from those fields, so the draft
reads `:0 (MACHINE, maven)`, twice for one host, as the comment leaves out the notes that tell its sources apart. Their `EC12` adds
the word "built in" to the adapter's note.

## What Is Asked

With `file` empty, the comment prints the note, not `:0`. Whether [B444](README.md#b444)'s question holds the same is read here.

## As built

The comment prints the note in place of `file:line`. The question already said nothing where a source is empty. The adapter's note says
"built in" by Agent Ecosystems' `EC12`.
