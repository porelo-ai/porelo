# B334 — A Person Kept In The Project Room

**Status:** Accepted.

**What must be true.** Every developer the applied access file names is in the project's room as a person, under
their own account on the host's homeserver, and a developer dropped from the access file leaves the room, as their
machine does. The room's maker keeps persons and machines to the list in the same act, after each applied access
commit.

The contract with the transport: a person is named by their host account, the key under `developers:` in the access
file, and that is also their login on the homeserver. The transport's `members` verb takes `--persons` with those
accounts, comma separated and sorted, beside `--machines`; Porelo gives it only when the transport's description
lists `persons` among the options it takes, so a transport without it keeps machines alone. Its answer carries
`persons_invited` and `persons_removed` beside `invited` and `removed`. Without `--persons` the transport leaves
every person as it is. Inviting and removing a person stays inside `members`: Porelo has no verb of its own for it.

## Acceptance

- After an applied access commit, `members` is called with every developer's account as a person, and `talk room`
  shows the persons invited and removed on lines of their own.
- A developer dropped from the access file is passed no more, and the transport's answer names them removed.
- Against a transport whose description does not list `persons`, `--persons` is not given and machines are kept as
  before.
- Seen to fail first: today's `keepMembers` passes machines alone.
