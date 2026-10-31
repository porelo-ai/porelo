# B380 — Small Leftovers Of Sprint 6m

**Status:** Idea.
**Size:** -, for michi to place - one gathering issue for the small edge cases sprint 6m's readings and fixes left,
as michi decided on 2026-10-31: none of them reaches another person's key, card, socket, mailbox or container, shows
or lends a credential, trusts a key wrongly or loses data. Each one placed is split out with its own size.

**What must be true.** Each point below holds, or is decided not to be worth it.

- An scp address without a user, `host:a@b:c`, is cut like `user:password@host:path` to `b:c`, though git connects to
  `host` with the path `a@b:c`; the two spellings cannot be told apart from the address alone. Found by the fix of
  [B375](README.md#b375).
