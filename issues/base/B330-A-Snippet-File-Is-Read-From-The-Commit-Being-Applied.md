# B330 — A Snippet File Is Read From The Commit Being Applied

**Status:** Idea.

**What must be true.** A follow, a `follow --dry-run` and `porelo project verify` read `image.snippet_file` (and a
repository's) from the commit they judge. Today a commit that adds or changes a snippet file is refused as
`unusable` ("no image snippet at <clone>/<file>"), or judged with the old snippet.

Seen while building B311 (2026-10-29): `Reconcile.applied` reads the project file at the new commit with
`readAt`, but `ProjectReader` resolves the snippet beside `<clone>/project.yml` in the working tree. The working
tree is moved to the commit only after that check (`reset --hard`). A fresh clone, as every check uses, has no
working tree, so any project with a snippet file is refused on its first follow.

## Acceptance

- A first follow of a signed project with `image.snippet_file` applies it.
- A commit that changes the snippet file is judged with the new snippet.
- A snippet file absent from the commit is still refused, even when the old working tree holds one.
