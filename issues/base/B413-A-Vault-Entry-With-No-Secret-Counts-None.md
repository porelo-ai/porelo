# B413 — A Vault Entry With No Secret Counts None

```json
{
  "$schema": "issue-1.json",
  "id": "B413",
  "title": "A Vault Entry With No Secret Counts None",
  "state": "Idea",
  "size": "-",
  "priority": "Low",
  "rank": 26,
  "blockedBy": [],
  "covers": "`vault list` names no length for an entry with no secret, where a public client's grant reads `1 characters`"
}
```

**Severity.** low: it reads wrong, and nothing goes wrong by it.

**What must be true.** `vault list` does not name a length for an entry that holds no secret.

**What holds today.** Seen in a walk: a public client's grant lists as `oauth-device 1 characters`. Read in the code:
`VaultListCommand` prints `entry.value().length()` for every entry, and `GrantEntry` stores such a grant with the placeholder
`NO_SECRET`, `-`, as its value.
