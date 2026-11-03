# B423 — Small Words Found In A Host Rehearsal

**Status:** Idea.
**Severity:** low: each misleads a person a little, and none gives a wrong result.

**What must be true.** Each of these says what happened.

- `PassphraseTiers.require` names a tier by its object's text, a lambda's, in "No passphrase available, tried:".
- `UpstreamProbe` says "public half" when a token was offered, and "refused" when none was.
- `doc/commands.md` names `task list --json`, and `task list` has no such option.
- A refused start warns "cannot record the start's step" and names a `.tmp` file of no use to a person.
- `gate.log` prints the first four characters of the gate's token. That is little to learn from, and no secret belongs in a log.
- A signed project file whose `snippet_file` was deleted, or is now a symbolic link, is refused without naming that file: "does not
  read", or "expected a regular file".

**To be checked.**

- Each line again at its place, since these were noted during a rehearsal and not worked on.
