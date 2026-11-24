# The review profile

Every review document Porelo hands out is SARIF 2.1.0 in one closed profile, version 1: what a task
receives with a reject, and what the command line, the interface and the IDE plugin read through
`Comments`. The daemon alone writes it, from the comments and replies it keeps; every reader holds to
this page and to the schema bundled with porelo's gate, `review-profile-1.schema.json`. A field not
listed here is not in the profile, and a document holding one fails the schema.

## The document

- **Root:** `version` is `2.1.0`, `$schema` is `https://json.schemastore.org/sarif-2.1.0.json`, and
  `properties` holds only `porelo/profileVersion: "1"`.
- **One run.** Its tool is `Porelo`. `versionControlProvenance[0]` names the repository as
  `urn:porelo:repository:<project>:<repository>` and the reviewed commit in full as `revisionId`;
  `columnKind` is `unicodeCodePoints`.
- **The run's properties:** `porelo/project`, `porelo/repository`, `porelo/push` (the waiting
  push's name), `porelo/baseRevisionId` (the commit the push is compared with, absent when the review
  names none) and `porelo/completeness`.
- **Pages.** A page holds at most 100 results and at most 1 MiB of UTF-8, and at least one result
  when there are any. `porelo/completeness` is `complete` on the last page and `partial` on every
  other; `failed` and `unknown` are the profile's other two words. A complete page is no evidence that
  a review is complete: findings and runs are not in this profile yet.

## A comment and a reply

Each comment and each reply is a result of its own, in the threads' order and each thread's items in
theirs.

| Field | Value |
|---|---|
| `guid` | the daemon's UUIDv4 for this item |
| `kind`, `level` | `review`, `none` |
| `message.text` | plain text, at most 8 KiB as given; written with each backslash and square bracket escaped by a backslash and each literal brace doubled, as SARIF reads a message, so no text becomes a link or a placeholder; no Markdown |
| `locations[0]` | the file as a repository-relative URI reference (each byte but the unreserved ones and `/` percent-encoded; no scheme, no leading `/`, no empty, `.` or `..` segment) and an inclusive line region, lines counted from 1; a reply's is its thread's |
| `fixes[0]` | a comment's suggestion only, never a reply's: one change of the same file, whose `deletedRegion` is the comment's lines and whose `insertedContent.text` replaces them, at most 8 KiB, braces as written; only on the new side |
| `porelo/anchorKind` | `line` |
| `porelo/diffSide` | `old` (the base's version of the file) or `new` (the reviewed commit's) |
| `porelo/threadId` | the thread the item belongs to |
| `porelo/replyTo` | a reply only: the thread it answers |
| `porelo/threadState` | `open` or `resolved`; resolving clears no finding and approves nothing |
| `porelo/sourceKind` | who gave the item, by channel |
| `porelo/taskId` | with `task` only: the task's name |

A reply carries no anchor of its own: it stands at its thread's place, and never shares a
`correlationGuid` with another result.

## Producers

Porelo cannot tell who sits behind a channel, so a producer is named by the channel the item came
through, and shown in these words:

| `porelo/sourceKind` | Channel | Shown as |
|---|---|---|
| `owner-account` | the daemon's socket and the command line of the account that owns Porelo | owner account |
| `task` | an agent in a Porelo task, through its mailbox | task `<name>` |

No other word names the daemon socket's producer.

## Limits

- A text and a suggestion are at most 8 KiB of UTF-8 each. A text holds no control character other
  than line feed and tab, no format character (bidi, zero-width, tag), no line or paragraph separator
  and no lone surrogate; the daemon refuses such an item when it is given, and refuses to write one.
- A line region spans at most 100 lines; a suggestion replaces its anchor's lines with at most
  10 more, each at most 400 Unicode scalar values.

## Later fields

File and general anchors, findings (`kind: "fail"`), Markdown, source levels, risk classes, forge
accounts and relocation are added by the change that first writes them, each as a new
`porelo/profileVersion` agreed with the readers; none is admitted ahead of it.
