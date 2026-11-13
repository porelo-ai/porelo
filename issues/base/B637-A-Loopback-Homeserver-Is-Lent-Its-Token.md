# B637 — A Loopback Homeserver Is Lent Its Token

```json
{
  "$schema": "issue-1.json",
  "id": "B637",
  "title": "A Loopback Homeserver Is Lent Its Token",
  "state": "Review",
  "size": "S",
  "priority": "Low",
  "rank": 1,
  "blockedBy": [],
  "covers": "porelo lends matrix-registration-token.<server> to a homeserver on loopback http, as it does to an https or Unix socket one, so a person's local homeserver can be named for a project; any other plain http address still lends nothing."
}
```

**Why this size.** `LentEntry.server` and its one rule, read by the lending and by whoever names the entry
beforehand, with tests for each form.

Filed 2026-11-12 from the operator's decision on ST314. `LentEntry.server` lends only for a plain https address or a
homeserver's Unix socket. A person's own homeserver on `http://127.0.0.1:<port>` or `http://localhost:<port>` lends
nothing, so it cannot be named for a project.

**What must be true.** A loopback http address (127.0.0.0/8, ::1, localhost) lends for its host and port, under a
server name that cannot be mistaken for an https one of the same host. Any other http address still lends nothing.
The rule stays one rule for the lending and for naming the entry beforehand.

## Built

- **`LentEntry.server`** (app-base): a homeserver on loopback http is lent `matrix-registration-token.<server>` under
  `http:<host>:<port>`, for example `http:127.0.0.1:8008`. This follows the socket's `unix:<path>`, and no https
  name can begin `http:`. The host is lowercased and the port always written (`80` when none is given).
- **Loopback is spelled one way:**
  - lent: `127.0.0.0/8` in plain dotted decimal, `localhost`, `[::1]`
  - nothing lent: `127.1`, hex or octal forms, a long or mapped IPv6 form, a zone id, `x.localhost`, a port of 0 or
    over 65535, a user part, a query or fragment, and any other plain http address, as before
- **One name serves both sides.** `project show`, which names the entry beforehand, and the lending in
  `TransportLifecycle` both read `server()`, so neither changed.
- **Docs.**
  - `doc/transports.md`: the `http:` form, what counts as loopback, why the port stays in the name, and that a port
    proves nothing about who listens.
  - `doc/decisions.md`: a new entry and index row, "A loopback homeserver's registration token is lent under its host
    and port".
- **Tests.** `LentEntryTest` is new, with 45 cases. `TransportLentTest` gains 8, end to end:
  - a token reaches loopback in four spellings
  - it reaches neither another port, another loopback address, https on the same host, nor http elsewhere
  - `vault put` under the loopback name works
  - `project show` names the entry that is lent

  On the build box they failed before the change (`buildbox-porelo-de98433ba39b-20261113T173140Z.log`, 19 of 185)
  and pass after it (`buildbox-porelo-912d552ad45a-20261113T173543Z.log`, 187 tests, 0 skipped).
