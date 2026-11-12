# B677 — A Host's Service Is Reached By Its Role

```json
{
  "$schema": "issue-1.json",
  "id": "B677",
  "title": "A Host's Service Is Reached By Its Role",
  "state": "Review",
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

## Built (2026-11-12)

- `ServiceConnectCommand` under the new top-level `service` group (`ServiceCommand`, registered in `PoreloCli`).
  `HOST_SOCKETS` is the one table of roles and sockets; the test builds the command over a table in a temporary
  directory, an attribute source and root's uid.
- The socket's own owner is the service's account (the forge's, the homeserver's), never root, so the check asks of
  the socket only that it is a socket nobody else may write, and of every directory above it up to `/` that it is
  root's and written by root alone: only root could have placed the socket there. Root is uid 0 only in the initial
  user namespace (`/proc/self/uid_map` the identity map); elsewhere the command refuses with 69, since an owner of 0
  there says nothing.
- Exit 0 when the service ends; 64 for an unknown role; 69 naming the role for no socket ("this host serves none"),
  a socket that refuses, or a refused socket or directory; 70 for a failure while carrying. The end of standard input
  half-closes the socket, and the service's end ends the command, as `porelo daemon connect` does.
- `doc/commands.md` gains the `service` section with the roles, sockets and exit codes; `porelo` has no man page of
  its own.
- Tests (`ServiceConnectCommandTest`, unit level, real Unix sockets): a request carried to the forge-web socket and
  echoed back only after the input's end arrived; forge-git and homeserver reach their own sockets and not the web
  one; an unknown role 64 through the real command line; a missing socket and a socket nobody accepts on 69 with
  their own words; a socket in a directory others can write, in a directory another account owns, a socket others
  can write and a plain file are refused with no connection made. All nine were seen red against a stub that
  returned 0.
