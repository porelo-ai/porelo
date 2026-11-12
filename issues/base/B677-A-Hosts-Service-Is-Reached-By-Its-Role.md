# B677 — A Host's Service Is Reached By Its Role

```json
{
  "$schema": "issue-1.json",
  "id": "B677",
  "title": "A Host's Service Is Reached By Its Role",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 3,
  "blockedBy": [],
  "covers": "`porelo service connect <forge-web|forge-git|homeserver>`, run on a host over ssh, joins its standard input and output to the Unix socket the host serves that role on, as the person, so a window on another machine carries one service through one ssh channel and the service still knows who it is."
}
```

**Why this size.** one command that checks a root-made socket and relays bytes both ways, its tests and its man
page; no contract.

Filed 2026-11-12 for the frontend's F290 (the operator's way 1, a loopback carrier). Rewritten the same day: the first
plan read the role's port from the host's card on loopback, but the card is root's alone on a host, and the forge's
web and the homeserver listen on no loopback port at all, only on Unix sockets whose gates take the login from the
peer's credentials.

**What must be true.** A person's window reaches a host's forge (web, git) and homeserver over ssh alone: per
connection it runs, through the login shell, `porelo service connect <role>`, which connects as that person to the
socket porelo-host serves the role on and carries bytes between it and its own standard input and output until both
sides end. The roles: `forge-web` is `/run/porelo-forge/web.sock`, `forge-git` is `/run/porelo-forge/git.sock`,
`homeserver` is `/run/porelo-matrix/client.sock`. The gates behind them see the person's own uid, as they do for a
local client. An unknown role is refused with exit 64; a host that does not serve the role (no socket) or a socket
that refuses is exit 69 with the role named and which of the two it was. The command carries no credential, reads no
card and resolves no name.

## Plan

- `ServiceConnectCommand` under a `service` group: the role names a fixed path; check the socket and its directory
  are root's and not writable by others (as `RootOwnedFile` checks a file) before connecting; copy both ways with
  half-close, as `VaultRelayCommand` and the supervisor's `SocketRelay` carry a socket.
- Nothing written to standard output but the service's bytes; refusals go to standard error.
- The paths are one table, so a test can point it at a temporary directory.
- Red first: a Unix-socket echo for `forge-web` carries a line both ways; an unknown role is exit 64; a missing socket
  and a socket nobody accepts on are exit 69 with their own words; a socket in a directory others can write is
  refused.
- Man page and the CLI reference name the roles, the sockets and the exit codes.

## Acceptance

- On a host, `printf 'GET / HTTP/1.0\r\n\r\n' | ssh <host> porelo service connect forge-web` answers with the
  forge's page as that person; a test fails while bytes are lost or the wrong socket is reached.
