# B763 — No Token Lent To A Host's Gate

```json
{
  "$schema": "issue-1.json",
  "id": "B763",
  "title": "No Token Lent To A Host's Gate",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 56,
  "blockedBy": [],
  "covers": "A transport is lent no registration token for a homeserver that is a host's gate - a `unix:` socket or an https name under `.localhost` - since the Matrix transport drops it there; a person's own loopback homeserver and an https server keep theirs",
  "openQuestions": 0
}
```

**Why this size.** S: one class in `app-base`, its tests, one decision and one changelog entry; the lending's interface
does not change. Estimate: 38 minutes (Core's median for S).

**What must be true.** porelo lends a registration token only where a transport can use it. For a homeserver that is a
host's gate, which the Matrix transport registers through without a token and where it drops one it is lent, porelo
lends nothing, so no vault entry has to be kept for an address that never receives it.

## Plan

- Today `LentEntry.server` (apps/app-base) answers a `unix:` address with the address itself and an https address
  with its host, `foo.localhost` included. `TransportLifecycle` then lends `<entry>.<server>` to `setup` and `enroll`.
  Plain http to a name under `.localhost` already lends nothing: `LOOPBACK_HTTP` takes `localhost` alone.
- `LentEntry.server` answers `null` for a `unix:` address and for an https host whose last label is `localhost`.
  `http://localhost:<port>`, `127.0.0.0/8` and `[::1]` keep `http:<host>:<port>`, and every other https host keeps its
  name.
- `doc/decisions.md`: the row and section "A socket's registration token is lent under the socket's path" are removed,
  and the index row of the loopback decision stays. `changelog/unreleased/lent-for-a-socket.yml` is removed, since it
  never shipped, and one entry says a host's gate is lent nothing.
- `ProjectShowCommand`, which names the entry a person must store, then names none for such a homeserver, through the
  same `LentEntry.name`.
- Red first: `TransportLentTest` - a `unix:` homeserver and `https://gate.host.localhost` are lent nothing, even with
  an entry under their names in the vault. Its socket cases that expect a token are turned around, not kept beside.
- Built once porelo-message-matrix's transport drops the token for a host's gate. Built earlier, a homeserver that
  still asks for one through a tunnel would get none.

## Built

- `LentEntry.server` answers `null` for a `unix:` address and for an https host that ends in `.localhost`, or in
  `.localhost.`, which the resolver reads as the same name. `http://localhost:<port>`, `127.0.0.0/8`, `[::1]` and every
  other https host keep their entry, `https://localhost` and `https://localhost.example.org` among them.
- `AddressForm.isUnixSocket` had no other caller and is removed. `ProjectShowCommand` names no entry for a gate,
  through the same `LentEntry.name`.
- `doc/decisions.md`: "A host's gate is lent no registration token" replaces the socket's decision. `doc/transports.md`
  says the same. The never-released changelog entry for the socket goes, and one entry under *changed* says it.
- Red first: `LentEntryTest.aHostsGateLendsNothing` (5 cases) and `TransportLentTest.aHostsGateIsLentNoToken...`
  (4 cases). The socket tests that expected a token are turned around or removed where no socket lends at all.
- The reading (agy, gemini-4-argon-high), each finding checked in the code:
  - Yellow, fixed: `https://gate.host.localhost.` passed `AddressForm.isHttps` and was lent under
    `gate.host.localhost.`. Two cases in `aHostsGateLendsNothing` were red, then green.
  - Yellow, fixed: `AddressForm.isUnixSocket` was left without a caller, and a test comment still spoke of a tunnel.
  - No removed test guards a harm that no remaining test guards: every `unix:` address now lends nothing.
- Green, run locally: 184 tests in `LentEntryTest`, `TransportLentTest`, `OrgReadingCommandsTest` and the
  `ProjectShow` tests, 82 in `AddressFormTest`, 0 skipped. The tests tagged `documents` are green too.

## Acceptance

- `TransportLentTest` seen red, then green. `http://localhost:8008` and an https server are still lent their entries.
