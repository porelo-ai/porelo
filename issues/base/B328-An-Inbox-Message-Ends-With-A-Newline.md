# B328 — An Inbox Message Ends With A Newline

```json
{
  "$schema": "issue-1.json",
  "id": "B328",
  "title": "An Inbox Message Ends With A Newline",
  "state": "Idea",
  "size": "-",
  "priority": "Low",
  "rank": 47,
  "blockedBy": [],
  "covers": "A message delivered into a task's inbox ends with a newline, so `cat` of it leaves the prompt on its own line"
}
```

**What must be true.** A message Porelo writes into a task's inbox (`/run/porelo/mail/inbox/new/<id>.json`) ends with a
newline, so `cat` of it leaves the shell's prompt on a line of its own, as a text file does.

Seen in an IntelliJ walk: `cat` of an inbox message left the prompt on the line of the JSON.

## Acceptance

- Every file Porelo delivers into an inbox ends with exactly one newline; reading it as JSON is unchanged.
