# B424 — A Person's Forge Token Replaced At Each Command

**Status:** Accepted.
**Size:** S - one lend per measuring round instead of one per repository, and its test.
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
- It also stores the new token in the vault as `<forge>.person` "so their own tools keep one that wor**What HO146 and its switch already do** (on main since 2026-11-08), read in the code:

- `porelo-host-person forge-token --lend` makes a token for one command, named `porelo-host-lend-<UTC time>-<uuid>`. It
  deletes only lends a day old, never the person's token and never a younger lend.
- Both of porelo's callers ask for `--lend`: `HostForgeLending` for every gate git command, including `porelod`'s
  measuring through `GateSupport`, and `HostForgeAccess` for taking a project at a task start.
- So two commands at once no longer take each other's token. A clone between its opening request and its transfer
  keeps its token. `porelo-host-push`'s token, and the `<forge>.person` entry it writes, stay valid while porelo asks.

**What stays.** `porelod`'s measuring still asks for one lend per repository every 15 minutes, and a lend lives until
the next lend after a day. With three mirrors that is about 290 tokens of the person's login on the forge at once, each
able to do what the person's own token can.

- **The change:** measuring takes one lend per round and uses it for every repository of that round, or none where no
  repository is on the host's forge.
- **Seen to fail first:** a round over three repositories on the host's forge asks `forge-token --lend` three times.
- `porelo-host-push` waiting on the terminal after it took its token is porelo-host's, not this issue's.
