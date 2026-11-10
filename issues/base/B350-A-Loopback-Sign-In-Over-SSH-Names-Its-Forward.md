# B350 — A Loopback Sign-In Over SSH Names Its Forward

```json
{
  "$schema": "issue-1.json",
  "id": "B350",
  "title": "A Loopback Sign-In Over SSH Names Its Forward",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 46,
  "blockedBy": [],
  "covers": "A `loopback` sign-in over SSH runs: Porelo names the callback port and the interface shows the one `ssh -L` line to open first"
}
```

**What must be true.** A `loopback` sign-in in a session reached over SSH runs instead of being refused: Porelo names
the callback port, and the interface shows the person the one `ssh -L <port>:localhost:<port> <host>` line to open on
their own machine before they open the link.

Filed when a `loopback` sign-in over SSH was made a refusal. Not planned in a sprint.
