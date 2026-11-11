# B611 — A Start On An Organization's Host Trusts Its Pinned Key

```json
{
  "$schema": "issue-1.json",
  "id": "B611",
  "title": "A Start On An Organization's Host Trusts Its Pinned Key",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 20,
  "blockedBy": [],
  "covers": "`task start --host` on a host the organization's signed card lists connects with the card's `ssh_host_key` alone, no `trust-host` first; a key that does not match is refused in words; hosts the organization does not list keep `trust-host`."
}
```

**Why this size.** one lookup of the organization's trusted host cards in `app-task`, a pinned connection beside
today's, ssh's host-key failure turned into a refusal, the remote-start tests and a scenario.

Filed 2026-11-11 from the operator's decision on Agent Machines' VM proof run 5.

**What must be true.**

- `porelo task start --host <login>@<host>`, where `<host>` is a host the organization's signed card lists (by its
  name or its `address`), connects trusting the `ssh_host_key` that card pins. The person needs no
  `porelo credentials trust-host` for it.
- The pin is the only key trusted for that connection: neither the person's own ssh `known_hosts` nor Porelo's own
  known-hosts file can make a different key acceptable for a host the organization lists.
- A host key that does not match the pin is refused with words. The refusal names the host, says the organization
  pins another key, and says who to tell. It is never accepted, never offered to `trust-host` and never written to a
  known-hosts file.
- A host the organization does not list, or a machine with no organization, behaves as today: its key is trusted
  through `trust-host` or the person's own ssh.
- Only a card from the organization's last verified read counts. A card that is not accepted, or has no
  `ssh_host_key`, gives no pin, and the start falls back to today's path.
- `porelo task certify` with `--host` reaches the host the same way.

## Plan

- `OrganizationHostPin` in `app-task` decides the pin. It reads the organization with `IdentityOrganization.read`.
  No organization on this machine: no pin, today's path. A read that cannot be had at all is refused (69): whether
  the organization pins the host cannot be said.
- `<host>` (lowercased, a trailing dot and brackets taken off) is matched against each host's name and its card's
  `address`, read the same way. No match: no pin, today's path.
- A match in a stale or incomplete read is refused (69): the pin may have changed since. More than one host matching
  is refused (65): the organization contradicts itself.
- One match whose card is missing, has no `ssh_host_key`, or is signed by no current admin of that host (a backup key
  is no admin, a revoked person is no longer listed) gives no pin, and the start takes today's path.
- A card whose address, port or key ssh would not take is refused (65), naming whom to tell.
- On a pin, `RemoteStart.connection` opens a closeable connection: today's options, then one temporary known-hosts file
  holding only the card's key under a fixed `HostKeyAlias`. Beside it go `GlobalKnownHostsFile=/dev/null`,
  `KnownHostsCommand=none`, `UpdateHostKeys=no`, `VerifyHostKeyDNS=no`, `CheckHostIP=no`, the key's
  `HostKeyAlgorithms`, `-p <ssh_port>` and `<login>@<address>`. Options on ssh's command line win over the person's
  own ssh configuration, so their keys and jumps still apply but no file of theirs can trust another key. The
  temporary file lives until the start ends.
- `RemoteStart.run` sets `SSH_ASKPASS_REQUIRE=never`. On a pinned connection, ssh ending with 255 after "Host key
  verification failed" is refused with 77: the host showed another key than the organization's card pins, nothing
  was trusted, and the host's admins are named.
- `TaskRunCommand.onHost` and `TaskCertifyCommand` both reach the host this way.
- Red first: `OrganizationHostPinTest` covers each rule above. `RemoteStartConnectionTest` asks `ssh -G` how it reads
  a pinned line (the known-hosts file, alias, port and address). `RemoteStartTest` checks that a pinned connection's
  verification failure answers 77 with the admins named.
- `doc/commands.md` for `--host` and `trust-host`; changelog under *Added*.

Left out, for the Coordinator: whether a host must carry the start's `--project` before its pin counts. The plan
reading raised it. Today a host the organization lists is pinned whatever the project.

## Settled

- The card's `ssh_port` is used: `-p` from the card.
- A person's own ssh alias for the host is not matched against the organization: it takes today's path. A name or
  address the organization lists is pinned whatever the person's configuration says.

## Acceptance

- The unit tests above, each seen red first.
- On a host the organization's card lists, `task start --host` without `trust-host` starts, and with another key on
  the card is refused with 77.

## As built

- The pin is decided in `OrganizationHostPin`; `RemoteStart.Connection` holds the temporary known-hosts file and
  deletes it when the start or certification ends.
- The plan reading's findings not taken here: a host carrying the start's `--project` (left to the Coordinator, above),
  and `PinnedHostSsh.open` refusing IPv6 addresses, which this connection does not use.
- The scenario on a real machine is the acceptance's host run; it is not in the unit build.
