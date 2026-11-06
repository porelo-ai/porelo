# B470 — A Repository Of Its Own Stays In Default And Says What It Needs

**Status:** Idea.
**Size:** -

**What must be true.** A checkout with no project of an organization goes into `default`, and no start writes a
`project.yml` for it. The repository's own `porelo.yml` says what its task needs, in the project file's form less what
defines a project (name, organization, repositories, members): hosts a task reaches, credentials by name and kind (never
a value), tool repositories and `extensions.<adapter>`. A first start's detection writes `porelo.yml`, not `project.yml`.

`porelo.yml` adds to the project file's defaults: mappings merge key by key; lists take the project's entries, then the
repository's; a single value the repository sets wins. `null` removes a key the project set; in a list a quoted
`"!entry"` removes that inherited entry and `"!*"` every inherited one, and an unquoted `!` is refused, naming the
quotes. A start and its dry run name each value's source - the project, the repository or Porelo's default - and what
was removed.

In `default` the person allows a repository's settings once on this machine, kept outside the repository and bound to
what was asked; a change asks again for what is new only. In a project of an organization, `porelo.yml` is signed by
the project admin or by a developer with access to the repository, and the follow checks every commit that changes it.

The decision is recorded in porelo-project's `doc/decisions.md`, "A repository of its own stays in Default and says what
it needs" (operator, 2026-11-06). It replaces the image rule "first whole file wins, nothing mixed", and
[B467](README.md#b467) folds into it: a task's tool repositories live in `porelo.yml`, with the project file as their
default.

## To be analyzed

- `porelo.yml`'s new keys, their schema and the merge with the project file.
- The allowance in `default`: what it holds, where it lives on this machine, and what a change asks again.
- The first start writing `porelo.yml`, and what happens to checkouts that already got a `project.yml` of their own.
- How [B467](README.md#b467) folds in, and which issues this makes needless.
