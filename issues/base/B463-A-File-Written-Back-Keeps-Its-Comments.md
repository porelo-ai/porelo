# B463 — A File Written Back Keeps Its Comments

**Status:** Review.
**Size:** S - the organization writer, a local Composer workaround, and regression tests.

**What must be true.** Updating this machine's `organization.yml` keeps a person's comments and unrelated settings: a round trip through SnakeYAML's node tree with
comments, not a map dumped anew. A `project.yml` is never changed by Porelo ([B462](README.md#b462)). A minimal parser of our own stands only for
`porelo-wire`; every larger file uses SnakeYAML.

## The Files Written Back

By a search for SnakeYAML's `dump` and `serialize` outside tests, in `porelo`:

- **`OrgAccess.settle`** updates this machine's `organization.yml` through its nodes, keeping comments and unrelated settings.
- **`ProjectFile.edited`** in `organization/git-tree` rewrites a project's `project.yml` from a map. It goes with [B462](README.md#b462), as Porelo no longer
  changes that file.
- **`ProjectFile`'s proposal** dumps a new entry, with no comments to lose.

The writer search also found the text serializers in `CredentialDeclarations`, `Destination`, and `TreeFiles`.
They remain separate from this change, which fixes `organization.yml`; credential declarations and organization Git-tree
files would need their own comment-preserving edits. Generated task records and new proposals have no person's comments to retain.

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

- **The way around it, until SnakeYAML is fixed:** `TolerantComposer`, in `app-organization`, subclasses SnakeYAML's Composer.
  Whole comment lines misclassified as inline comments after a block scalar become block comments on the next key;
  trailing comments are retained at the document's end. SnakeYAML still parses, resolves, and serializes the document.
- **Removal signal:** a test requires plain SnakeYAML to fail composing the build fixture with comments. Once that succeeds,
  the test fails saying the workaround can go. The workaround's source comment names that test.
- The documentation examples stay as written. Nothing is reported upstream.

## Plan

- `OrgAccess.settle` composes with comments and changes only `adapter` and `settings.repository`. Other keys and comments survive.
  A missing file or an empty or comment-only file gains the owned keys. Invalid YAML, invalid mapping shapes, and duplicate
  keys in the mappings being edited are refused without overwriting the original. Serialization completes before a temporary
  file is written and atomically moved into place; the temporary file is removed on exit.
- The settings mapping is detached before an edit, so an alias cannot change an unrelated mapping. SnakeYAML discards comments
  on aliases even with comment processing enabled: the writer compares comment text and order before and after serialization
  and refuses a lossy write, asking for commented aliases to be expanded. The original bytes remain intact.
- Keep the workaround local to `app-organization`: this is the writer that needs it, and no shared area needs a new dependency.
- Tests stay in `apps/app`. The build fixture is copied unchanged into test resources; the reduced example above and all valid
  YAML examples in `doc/project-file.md` round-trip with values and inline and whole-line comments retained in order.

## Acceptance

- Regression tests first reproduce lost comments, lost unknown settings, overwriting unreadable settings, and the Composer crash.
- The same tests pass with the node writer and workaround. A deliberate successful compose makes the removal guard fail with its removal message.
- Independent read-only review found shared mapping mutation through aliases, discarded alias comments, and a crash on nested
  trailing comment groups. Each has a regression seen failing before its fix; alias-comment loss is handled by refusing the write.
- No other repository or desktop-window change is needed. Acceptance scenarios and VMs are not run for this change.
  `acceptance/suite`'s `machine/machine.feature` is the setup coverage location; a dedicated scenario would seed a commented
  `organization.yml`, run `setup --organization ... --replace` against a prepared organization, and check the retained comments and extra keys.

## Validation limits

- A clean targeted reactor passes 198 tests: the YAML regressions, organization writing and reading commands, organization creation,
  and document-tag enforcement. The document-test profile and citation check pass too.
- The full unit reactor stops in `porelo-wire`: socket tests reject directories owned by the user namespace's overflow UID,
  including `/` and `/tmp`. The security check is unchanged; the full suite needs an environment with valid directory ownership.
- No acceptance scenario, VM, native build, upstream report, or desktop change is run as part of this work.
