# B453 — A Draft Replaces A Repository's Own Image

**Status:** Backlog.
**Size:** S - the draft reads one more file, with its question and tests.

**What must be true.** A file a first start or `project init` writes does not silently replace the image a repository says it needs.

Not in the test's version unless the operator says so.

## Found

Agent Ecosystems' corpus, on `porelo-parent` at `e3bf01e`, on 2026-11-05. Its `porelo.yml` has a pinned `base_image`, a `snippet`
installing GraalVM into `/opt/graalvm`, and `env: JAVA_HOME: /opt/graalvm`. At a first start, `./mvnw` stopped after 1 s: "The
JAVA_HOME environment variable is not defined correctly". The start said `image lines from .../project.yml` and
`image env JAVA_HOME from porelo.yml at refs/heads/main`. The pinned base went the same way, by the same rule.

## The Judgment

**The fault is in the draft, not in taking keys.** `ImageChoice` takes `base_image` and the snippet whole and `env` per variable, as
`doc/project-file.md` and `doc/decisions.md` state, so that an override of one variable changes only that one. The project file's
entry ranks above `porelo.yml`, which is right when a person writes it. But `ProjectProposal`, `FirstStart` and `ProjectInitCommand`
never read `porelo.yml`: the draft outranks a file nobody looked at, on what a plugin guessed from `pom.xml`.

The rule itself leaves a person's own file open to the same trap: a snippet of theirs leaves a repository's `env` standing. Whether
the start should say so, when a variable comes from a source whose snippet lost, is a question of its own, not decided here.

## What Is Asked

- When the checkout's `porelo.yml` or `.porelo.yml` sets `image`, the draft writes no `base_image` or `snippet` over it, and the
  question says the repository's own image stands, with what the plugin would have added named as a note.
- The egress hosts of the plugin stay in the draft, as they are not an image's.
- Read with [B449](README.md#b449), which brings a repository's `porelo.yml` into the question and the hash: they should not be
  built apart.
