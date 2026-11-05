# B463 — A File Written Back Keeps Its Comments

**Status:** Backlog.
**Size:** S - about 60 minutes, below.

**What must be true.** A YAML file a person keeps and Porelo writes back changed keeps its comments: a round trip through SnakeYAML's node tree with
comments, not a map dumped anew. A `project.yml` is never changed by Porelo ([B462](README.md#b462)). A minimal parser of our own stands only for
`porelo-wire`; every larger file uses SnakeYAML.

The operator's word, 2026-11-05.

## The Files Written Back

By a search for SnakeYAML's `dump` and `serialize` outside tests, in `porelo`:

- **`OrgAccess.settle`** writes this machine's `organization.yml` whole, from a map: a comment a person wrote there is lost.
- **`ProjectFile.edited`** in `organization/git-tree` rewrites a project's `project.yml` from a map. It goes with [B462](README.md#b462), as Porelo no longer
  changes that file.
- **`ProjectFile`'s proposal** dumps a new entry, with no comments to lose.

Text written by hand elsewhere is not in this search; a reading of each writer of a `.yml` comes first.

## SnakeYAML's Fault

`compose` with `setProcessComments(true)` throws `ClassCastException: CommentEvent cannot be cast to NodeEvent` in `Composer.composeNode`, on the first
example in `doc/project-file.md`. The smallest file that shows it, in SnakeYAML 2.7, valid YAML that loads without comments:

```yaml
a:
  s: |
    x

  # c

  # d
  p: 1
```

A literal block scalar inside a mapping, then **two groups of comment lines with a blank line between them**, then the next key. One group composes; so
does the same at the top level. So it is a fault of SnakeYAML's comment handling, not of the files.

- **The way around it, until SnakeYAML is fixed:** none in the file without changing what a person wrote. A writer that meets the throw refuses to
  write and says why, and the file stays as it was. The fault goes to SnakeYAML's tracker with this file.
- **Our own docs' example** avoids it once its comment groups after `snippet` have no blank line between them, if that is wanted.

## Plan

- `OrgAccess.settle` on the node tree with comments, writing only the keys it owns, and its whole-file write kept. 20 minutes.
- Tests: a round trip of a file with comments kept, the file above refused with the reason, and the docs' examples. 30 minutes.
- The fault reported upstream. 10 minutes.
