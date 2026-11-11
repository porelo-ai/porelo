# B611 — A Start On An Organization's Host Trusts Its Pinned Key

```json
{
  "$schema": "issue-1.json",
  "id": "B611",
  "title": "A Start On An Organization's Host Trusts Its Pinned Key",
  "state": "Backlog",
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

- Red first: a `RemoteStartConnectionTest` case that a listed host's connection trusts only a known-hosts file
  holding the card's key, and one that an unlisted host keeps today's two files.
- Look the host up in the organization's last verified state (`GitTreeOffline`'s host rows, which `app-task`
  already depends on). Match `<host>` against the card's `host` and `address`. On a match, connect to `address` on
  `ssh_port` through `PinnedHostSsh`'s trust options (one temporary known-hosts file, `StrictHostKeyChecking=yes`).
  The person's agent and their own ssh login still apply.
- In `RemoteStart.run`, turn ssh's "Host key verification failed" on a pinned connection into the mismatch refusal
  with its own exit code. Grep the features for today's wording of an unreachable host before changing it.
- `TaskRunCommand.onHost` and `TaskCertifyCommand` use the same connection.
- A scenario on a real machine: an organization whose card pins the host's key starts without `trust-host`, and
  with a wrong key on the card is refused.
- `doc/` where `--host` and `trust-host` are explained: what an organization's host needs, and what it does not.

## To be checked

- Whether a card's `ssh_port` other than 22 is used for `--host`. Today `--host` has no port and ssh's own
  configuration may give one.
- Whether a person's own ssh configuration alias for the host still resolves to the card's address, or the card's
  address replaces it.
