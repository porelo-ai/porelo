# B441 — A Credential's Length Reaches No Agent And No Line

**Status:** Backlog.
**Size:** S - a check of who can read two fields, and closing what it finds.

**What must be true.** A credential's length is shown to its owner and reaches no agent and no log.

## Why

A length is not secret from the person who holds the credential, so these stay: `vault put`, `vault list`, `vault import`,
`vault login`, the star per character in a prompt, and the daemon's fields for the person's own interface. To anyone else it
narrows a guess, and a line in a log outlives the moment.

The daemon answers with it in two places: `length` for an import and `characters` in a list, both in `VaultMethods`.

## To be checked

- Who can read those two fields over the daemon's socket, inside a task as well as outside it.
- Whether any log line or agent-facing output carries a length.

Close what reaches an agent or a line, each with a test seen to fail first.
