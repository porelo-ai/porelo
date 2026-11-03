# B424 — A Person's Forge Token Replaced At Each Command

**Status:** Idea.
**Severity:** low for now: a retry works, and a failure refuses, but the person's own forge token can die under anything else holding it.

**What must be true.** Nothing a person does with their forge token on a host fails because another command of theirs asked for one.

**What holds today.** `porelo-host-person forge-token` deletes every token of the person's login and makes a new one, by the host's
design. Since the forge's addresses on a host are lent per command, `porelo` and `porelod` ask for one each time they reach the
forge. `porelod`'s measuring does so for each repository every 15 minutes: three calls in about two seconds with three mirrors, as
seen in the host's journal. A task's token is on another login, `porelo-<user>`, and is not touched.

Measured on a test host, with a 200 MB branch:

- A clone with the token replaced one and two seconds in finished: a transfer under way is not cut.
- A clone with the token replaced in a tight loop failed at once: `RPC failed; HTTP 502`, exit 70. The opening request and the transfer are two requests, and between them the token can be gone.
- A task's 200 MB push through its gate, with the person's token replaced twice during it, reached the forge.
- Five pairs of `forge-token` called at once all answered 0.

Read in the code, not run:

- `porelo-host-push` asks for the token first, then waits on the terminal for the central token, then uses the first one. A person typing while a measure runs, or another command of theirs, stops that push. It pushes without force, so it can be run again.
- It also stores the new token in the vault as `<forge>.person` "so their own tools keep one that works". Any later command makes that entry stale. Nothing in `porelo` or the window reads it.

**To be checked.**

- Whether `forge-token` needs to take back earlier tokens at all, which is the host's to say, or whether `porelod` can measure on the host with no token of the person's.
