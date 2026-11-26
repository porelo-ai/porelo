# The review profile

Every review document Porelo hands out is SARIF 2.1.0 in one closed profile, version 1: what a task
receives with a reject, and what the command line, the interface and the IDE plugin read through
`Comments`. The daemon alone writes it, from the comments and replies it keeps; every reader holds to
this page and to the schema `review-profile-1.schema.json`, published in `porelo-build-api` under
`org/fuin/porelo/build/api/review/`. A field not listed here is not in the profile, and a document
holding one fails the schema. [Profile 2](#profile-2-a-forges-conversation-and-a-submission) extends
it for a forge's conversation and a producer's submission.

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

File and general anchors, findings (`kind: "fail"`), Markdown, source levels, risk classes and
relocation are added by the change that first writes them, each as a new `porelo/profileVersion`
agreed with the readers; none is admitted ahead of it. Forge accounts are in profile 2.

## Profile 2: a forge's conversation and a submission

Profile 2 is profile 1 with exactly what a forge's conversation and a submission need, so the forge
adapter, the reviewer task and Porelo exchange one format. Its schema is
`review-profile-2.schema.json`, beside profile 1's in `porelo-build-api`; the tests jar of
`porelo-build-api` (`<type>test-jar</type>`) carries one passing fixture each for a forge page, a
submitted comment and a submitted reply, under `org/fuin/porelo/build/api/review/fixtures/`, and the
failing ones under `fixtures/failing/`. Porelo writes profile 2 for a forge's conversation.

Everything profile 1 says holds, but for these points:

- **Root:** `properties` holds `porelo/profileVersion: "2"` and `porelo/documentKind`, which is `page`
  for a document a reader receives and `submission` for one a producer sends.
- **A pull request run.** A run names either a push, as in profile 1, or a pull request: then
  `porelo/pullRequest` is the pull request's `https` URL in place of `porelo/push`, and
  `versionControlProvenance[0].repositoryUri` is the forge's `https` repository URL in place of the
  `urn:porelo:repository` URI. Such a URL holds no user, query or fragment. A pull request run
  carries no `porelo/project` or `porelo/repository`, and the schema refuses them: a forge's owner
  is no Porelo project, and the pull request's address and `repositoryUri` name the repository. A
  run naming `porelo/push` requires both, as in profile 1.
- **Producers by run.** A pull request run's page holds only `forge-account` results: what Porelo,
  its owner or a task says on a pull request reaches the forge through an account there. A push
  run holds no `forge-account` result.
- **A forge page's `guid`** is the adapter's stable UUIDv4 for each comment, the same on every page
  it writes; Porelo takes it as the item's id at its place.
- **Thread ids.** `porelo/threadId` and `porelo/replyTo` stay opaque strings of at most 64
  characters. In a pull request's document they are the forge's native ids; for GitHub, a thread is
  its root comment's REST id as a decimal string. A reply from outside Porelo is a `porelo/replyTo`
  naming such an id, with no further field.
- **Text** stays plain text as profile 1 says: an adapter puts a forge's text into `message.text`
  escaped as written there, and `message.markdown` fails the schema.
- **Patterns** end in `(?![\s\S])` rather than `$`, since a `$` in some regular expression engines
  also matches before a final line feed and would let a value ending in one through.

### A forge account as producer

| `porelo/sourceKind` | Channel | Shown as |
|---|---|---|
| `forge-account` | a forge's conversation, through its adapter | the forge account's name |

`porelo/forgeAccountId` (the forge's id of the account, at most 64 characters; for GitHub its
numeric id as a decimal string), `porelo/forgeAccountName` (its login, at most 256 characters) and
`porelo/createdAt` are required with `forge-account` and refused with any other source;
`porelo/taskId` stays with `task` alone.

`porelo/createdAt` is when the forge says the comment was written, never when it was last edited:
RFC 3339 in UTC, whole seconds and a final `Z`, as `2026-11-20T09:12:05Z`. Porelo takes it as the
item's time, so the last word of a thread says when it was said, and writes it back unchanged on
the pages a task reads.

### A submission

A submission is what a producer sends: one or more results, each a new comment or a reply, without
the fields only Porelo assigns, since the producer is the channel's to stamp.

- A submission is always a pull request run: it names `porelo/pullRequest` and the forge's
  repository URL, and no `porelo/push`, `porelo/project`, `porelo/repository` or
  `urn:porelo:repository` URI.
- A result carries no `guid`, `porelo/threadState`, `porelo/sourceKind`, `porelo/taskId`,
  `porelo/forgeAccountId`, `porelo/forgeAccountName` or `porelo/createdAt`, and no
  `porelo/threadId`: a new comment
  opens a thread, and a reply names its thread in `porelo/replyTo` alone. A submitted reply's
  location is its thread's as the producer read it; it is not used to route the reply, which
  `porelo/replyTo` alone does.
- The run carries no `porelo/completeness`, which belongs to a page.
- A submission naming any of these fails the schema.

### Bounds

A reader of a profile 2 document holds it to these bounds, agreed with the receivers, and refuses a
document past any of them before reading further. The schema states those a JSON Schema keyword can
express; the others are the reader's alone. In `porelo-build-api`, `SarifReading` holds a document
to the bounds on its bytes and tokens and to its schema, and `ProfileCheck` then to the rest and to
its channel: an adapter sends a page of a pull request run, a task a submission. Each refusal
names its rule and a JSON Pointer, never the document's text.

| Bound | In the schema |
|---|---|
| a document at most 1 MiB of UTF-8 | no |
| a result at most 32 KiB of UTF-8 | no |
| at most 100 results | `maxItems` |
| JSON nesting at most 24 levels | no |
| at most 100000 JSON tokens | no |
| a string at most 16384 characters | `maxLength` on every string |
| a property name at most 128 characters | `propertyNames` on each property bag; every object is closed besides |
| a number at most 20 characters as written | no |
| an artifact location's `uri` at most 2048 characters as written, in profile 1 too | no: the schema's `maxLength` is 12288 |
| a region at most 100 lines | no |
| a replacement (`insertedContent`) at most its anchored lines plus 10 lines | no |
| a replacement's line at most 400 characters | no |
| a replacement at most 8 KiB in all, braces as written | `maxLength` 8192 as a necessary bound; the bytes are the reader's |
| `message.text` at most 8192 bytes of UTF-8 per result as given, before SARIF's escaping, as in profile 1 | `maxLength` 16384 on the written form, since each escape doubles one character; the bytes as given are the reader's |
| a pull request run's `repositoryUri` is `https://<host>[:<port>]` and 2 to 8 path segments, the host a DNS name of at most 253 characters (labels of 1 to 63 letters, digits and `-`, none starting or ending with `-`, no trailing dot) and the port 1 to 65535 without a leading zero, and its `porelo/pullRequest` that URL and 1 to 3 more segments, the last all digits; each segment 1 to 100 of `A-Z`, `a-z`, `0-9`, `.`, `_`, `~` and `-`, neither `.` nor `..`, so no percent-encoding, no empty segment and no trailing slash | no |
| a forge account's `porelo/createdAt` is `YYYY-MM-DDThh:mm:ssZ`, a day its month has, an hour of 00 to 23 and a minute and a second of 00 to 59 | the form by `pattern`; that the day and time exist is the reader's |
