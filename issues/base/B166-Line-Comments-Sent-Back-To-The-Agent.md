# B166 — Line Comments Sent Back To The Agent

**Status:** soon. The first of the code-comment area; the forge (B171) and the reviewer role (B172) build on it.

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
- **Every comment is journaled:** who commented what, where, and who released it (B154).

## The ways built here

- **A, a local review to the task:** comments kept with the waiting push on the host. A reject delivers them to the task
  as one message, each with its anchor, its lines and the lines around them, and any suggestion; the agent is woken as
  for any message (B120).
- **B, the agent's replies, locally:** the agent replies in a thread ("done in commit …") through its message path;
  the sluice checks the reply as egress (text only, no encoded data, no links), the anchor is checked, and the reply
  shows in the review beside the person's comment, marked with its origin. Nothing leaves the machine.

## The shape

- The comment is a message kind of its own, `comment`, whose rules the sluice holds (`sokar-message-sluice` SL24): the
  anchor checked by its shape, the suggestion only by the detectors for encoded data, and no origin stated by the
  sender - the host adds it after the filter.
- The daemon serves comments, threads and replies through one set of methods for the CLI, the interface
  (`sokar-frontend`) and the IDE plugin (`sokar-intellij`), which show each comment with its origin.
- Size: medium.

## Acceptance

- Seen to fail first, then green: two comments on a waiting push and a reject arrive in the task as one message naming
  both anchors; a comment on a line the commit does not have is refused; a suggestion that touches a line outside its
  anchor is refused; the agent's reply appears in the thread, marked as the agent's.
- `doc/commands.md` and `doc/messages.md` say it.

## To be checked

- Whether comments survive a new push of the same task, moved to the lines they were on.
- The size limit of a suggestion, the same here and in SL24.
- Whether `comment` is a kind of the sluice's narrowed schema or a part of its own (SL24's open point).
