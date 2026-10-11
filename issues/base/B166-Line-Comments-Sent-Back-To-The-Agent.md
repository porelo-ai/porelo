# B166 — Line Comments Sent Back To The Agent

**Status:** Analyzed. The first of the code-comment area; the forge ([B171](README.md#b171)) and the reviewer role ([B172](README.md#b172)) build on it.
**Size:** L - a contract three other repositories read (`Tasks1`, the message schema), a store on the host, the CLI,
the message path, docs and an acceptance step.

**What must be true.** A comment on code is a message: one model, checked on the host and never in the container,
passing the controls messages already pass - the sluice, a person's release where one is asked, the journal. A person
reviewing waiting work comments on lines of its diff, in `sokar gate review`, the interface and the IDE plugin, and a
reject carries those comments into the task (way A). The agent answers in the same threads (way B, locally). Code and
commits still travel only through the gate.

## Why

`gate reject` gives one reason today, and a person who wants three things changed writes them out of context. Comments
on lines close the loop from review to rework without going around the gate. Comparable tools do it. One model for every
way a comment travels - local, to and from a forge, from an agent reviewing - keeps one set of checks for all of them.

## The model

| Field | Content |
|---|---|
| Anchor | repository, commit, file, line or lines, side (old or new) |
| Text | plain text, checked by the sluice |
| Suggestion (optional) | replacement lines for exactly the anchor, as a forge's "suggested change" |
| Thread | thread id, reply-to, state (open or resolved) |
| Origin | a person (local), an agent (which task, which role), a forge account (which one) |

- **The anchor is checked on the host:** the named lines exist in the named commit. A comment on a line that is not
  there is refused, with what is there.
- **A suggestion** is allowed both ways, as its own field. It may hold only lines for exactly its anchor, within a
  size limit, and the host checks it as a small diff against the anchor's lines. It is never applied automatically:
  in a task the agent applies it, and the result goes through the gate again.
- **Every comment is journaled:** who commented what, where, and who released it ([B154](README.md#b154)).

## The ways built here

- **A, a local review to the task:** comments kept with the waiting push on the host. A reject delivers them to the task
  as one message, each with its anchor, its lines and the lines around them, and any suggestion; the agent is woken as
  for any message ([B120](README.md#b120)).
- **B, the agent's replies, locally:** the agent replies in a thread ("done in commit …") through its message path;
  the sluice checks the reply as egress (text only, no encoded data, no links), the anchor is checked, and the reply
  shows in the review beside the person's comment, marked with its origin. Nothing leaves the machine.

## The shape

- The comment is the sixth kind, `comment`, of [B108](README.md#b108)'s narrowed schema, with a fixed data part of its own; its rules
  are the sluice's (`sokar-message-sluice` [SL24](https://github.com/sokar-ai/sokar-message-sluice/blob/main/issues/README.md#sl24)): the anchor checked by its shape, the suggestion only by the
  detectors for encoded data, and no origin stated by the sender - the host adds it after the filter.
- The daemon serves comments, threads and replies through one set of methods for the CLI, the interface
  (`sokar-frontend`) and the IDE plugin (`sokar-intellij`), which show each comment with its origin.

## Plan

Answers for the other repositories, read in today's code:

- **A sixth kind, not a part of its own.** [B108](README.md#b108)'s schema already gives each kind a fixed data part beside one text
  part; `comment` fits that exactly: `metadata` holds `kind: comment`, `to`, `from`, `thread`; the text part is the
  comment's text; the data part holds `anchor`, an optional `suggestion` and an optional `replyTo`. A part of its own
  would need a second envelope rule set in the filter for the same thing. [B108](README.md#b108)'s schema document
  (`apps/app-messaging/src/main/resources/org/fuin/sokar/app/message.schema.json`) gains the kind and these limits.
- **The anchor's fields:** `repository` (a name from `Project.repositories`), `commit` (40 or 64 lower-case hex),
  `path` (relative, no `..`, at most 1024 bytes), `side` (`old` or `new`), `first` and `last` (line numbers from 1,
  `last` at least `first`, at most 100 lines apart). One line is `first == last`.
- **A suggestion's size limit:** a list of lines, at most the anchor's line count plus 10, each line at most 400
  characters, the whole at most 8 KiB. The same constants are in the schema document [SL24](https://github.com/sokar-ai/sokar-message-sluice/blob/main/issues/README.md#sl24) reads; [SL24](https://github.com/sokar-ai/sokar-message-sluice/blob/main/issues/README.md#sl24) may lower
  them once it has measured real suggestions, never raise them alone.
- **The host checks that the anchor exists, the filter checks its shape.** As [B108](README.md#b108) settles it, the envelope is the
  filter's and the host checks only what the host alone can judge: whether the commit, the file and the lines are
  there, which needs the repository. So an agent's reply passes the filter first and the host checks its anchor
  after; a person's comment never passes the filter (it is a `PersonNote`, from this machine), so for it the host
  checks the shape too, with the same constants, before it checks the lines.
- **The daemon's methods,** in `daemon/src/main/resources/varlink/org.fuin.sokar.Tasks1/35-gate.varlink`, each
  taking `project`, `upstream`, `repository` and the push's `name` as the gate methods do:
  - `Comment(..., anchor: Anchor, text: string, suggestion: ?[]string, replyTo: ?string) -> (comment: Comment)` -
    a person's comment or reply;
  - `Comments(..., name) -> (threads: []Thread)` - every thread on the push, oldest first;
  - `ResolveThread(..., thread: string, resolved: bool) -> (thread: Thread)`;
  - `Reject` gains nothing: it delivers the open threads with the reason, and its `told` says so.
  - Types `Anchor(repository, commit, path, side, first, last)`, `CommentOrigin(kind: person|agent|forge,
    task: ?string, role: ?string, forge: ?string, account: ?string)`, `Comment(id, thread, replyTo: ?string, anchor,
    text, suggestion: ?[]string, origin, at)`, `Thread(id, anchor, state: open|resolved, comments: []Comment)`.
  - Errors `AnchorNotInCommit(anchor: Anchor, lines: int)` (the file's line count on that side, or -1 when the
    file is not there), `SuggestionOutsideAnchor(lines: int, limit: int)`, `CommentTooLarge(bytes: int, limit: int)`.

Steps:

1. **Red first:** a test in `gate` that a comment on line 12 of a file the pushed commit holds with 10 lines is
   refused with the line count, and one on line 3 is kept.
2. **`gate/.../ReviewComments.java`:** the store, one JSON file per thread under the mirror's
   `sokar/comments/<push name>/`, written through a temporary file and a rename. The anchor check reads the file with
   `git cat-file` at the commit (`new`) or at the review's base (`old`, the same base `rankedReview` diffs against),
   and the lines around it for delivery. Threads stay bound to the commit they name; a new push of the same task shows
   them as on an earlier commit and does not move them.
3. **The CLI:** `sokar gate comment <name> --path P --line 12[-14] [--side old] [--suggestion-file F] [--reply-to T]
   <text>` and `sokar gate comments <name>` in `apps/app-gate`; `GateReviewCommand` prints each thread under its
   line; `GateRejectCommand` and the daemon's `Reject` hand the open threads to `PersonNote.rejectedWork`, which writes
   one note with a data part listing them, each with its anchor, its lines and three lines around them.
4. **The daemon:** `GateMethods` registers the three methods; the varlink file says them; `SokarDaemonTest` holds the
   file and the methods together.
5. **Way B:** the mailbox guide (`MailboxGuide`) tells the agent to reply with `kind: comment`, `to: review` and the
   thread's id. After the filter, `MessageDispatch` routes `to: review` to `ReviewComments` instead of a transport;
   the host checks the anchor, adds the origin (`agent`, the task, its role from the task's record, `developer` until
   [B172](README.md#b172)) and appends to the thread. A reply refused there is bounced as any refused message is (`HostBounce`).
6. **The journal:** each comment, reply and resolve as one line in the gate's `sokar/comments/journal.log` with who,
   where and when; it moves into [B154](README.md#b154)'s log once that is built.
7. **Docs:** `doc/commands.md` (`gate comment`, `gate comments`, what a reject carries), `doc/messages.md` (the
   `comment` kind and `to: review`).
8. **Acceptance:** a scenario in `acceptance/suite/.../gate/gate-review.feature` with the stub: two comments and a
   reject arrive as one message naming both anchors; a comment beyond the file's end and a suggestion longer than its
   bound are refused; the stub's reply shows in `gate comments` marked as the task's. The reply's step needs [SL24](https://github.com/sokar-ai/sokar-message-sluice/blob/main/issues/README.md#sl24)'s
   filter to let the `comment` data part through, so [SL24](https://github.com/sokar-ai/sokar-message-sluice/blob/main/issues/README.md#sl24) is built from this issue's schema before that step runs.

## Acceptance

- Seen to fail first, then green: two comments on a waiting push and a reject arrive in the task as one message naming
  both anchors; a comment on a line the commit does not have is refused; a suggestion that touches a line outside its
  anchor is refused; the agent's reply appears in the thread, marked as the agent's.
- `doc/commands.md` and `doc/messages.md` say it.

## Open questions

- Whether threads should later move with a new push of the same task to the lines they were on, rather than stay on
  the commit they name, as the first step does (the operator's).

## To be checked

- Whether [B108](README.md#b108) is built first, or this issue writes [B108](README.md#b108)'s schema document with the sixth kind in it (Agent
  Core's).
