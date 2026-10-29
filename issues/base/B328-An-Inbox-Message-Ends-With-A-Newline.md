# B328 — An Inbox Message Ends With A Newline

**Status:** Idea.

**What must be true.** A message Porelo writes into a task's inbox (`/run/porelo/mail/inbox/new/<id>.json`) ends with a
newline, so `cat` of it leaves the shell's prompt on a line of its own, as a text file does.

Seen in the operator's IntelliJ walk (2026-10-29): `cat` of an inbox message left the prompt on the line of the JSON.

## Acceptance

- Every file Porelo delivers into an inbox ends with exactly one newline; reading it as JSON is unchanged.
