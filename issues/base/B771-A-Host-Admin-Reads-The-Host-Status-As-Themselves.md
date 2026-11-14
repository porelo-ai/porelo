# B771 — A Host Admin Reads the Host Status as Themselves

```json
{
  "$schema": "issue-1.json",
  "id": "B771",
  "title": "A Host Admin Reads the Host Status as Themselves",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 128,
  "blockedBy": [],
  "covers": "A person marked `host_admin` reads a secret-free status of their host without root: the follow writes it where only the host's admins can read it",
  "openQuestions": 0
}
```

**Why this size.** M: the follow writes a second, secret-free status file and keeps a group of the host's admins in
step with the organization, and `porelo-host org status` grows a way for a non-root caller, with tests and the host
docs. Estimate: 63 minutes (Core's median for M), p80 229.

**What must be true.** A person the organization marks `host_admin` for a host can see, logged in as themselves,
what the host is doing: its organization, the commit it follows, its persons and projects with their states. That
view holds no secret and needs neither root nor sudo.

Filed 2026-11-14 from the operator's live test on a rented host (porelo 0.5.1~snapshot.543, ubuntu 26.04), under ST361.
The coming SSH console (F566) needs it.

## Why

Seen: the person, `host_admin` true, has no sudo, and `porelo-host org status` is root's alone (`uid.real() != 0`
refuses with 77, and `OrgStatus` refuses sudo). `/etc/porelo-host/organization/host-projects.json` is 0600 root. So a
host admin cannot see their own machine's state.

## Plan

- The status Porelo already builds (`HostOrganization.status()`) is read for what it holds. Only fields that are no
  secret go into a public view: no token, no key, no pin. The full view stays root's.
- Each follow writes that view to `/run/porelo-host/status.json`, mode 0640, group `porelo-host-admins`. It is
  written whole, by rename.
- The follow keeps the group `porelo-host-admins` in step with the persons the organization marks `host_admin` for
  this host, as it already keeps their accounts. A person no longer marked leaves the group at the next follow.
- `porelo-host org status` run by a non-root caller reads that file and prints it as root's status prints. It says
  how old the file is, and says nothing more when it is missing ("not followed yet"). `HostEnvironment` and the root
  gate let exactly this read through for non-root callers. They never let a non-root caller write.
- The host docs say who can read what.

## Red-first test

- `PoreloHostTest`: `org status` with a non-root uid, as a member of the admins' group, answers from the public view.
  Today it exits 77.
- `FollowProjectsTest` (or the follow's own test): a follow writes the public view without the token and the key,
  0640, and puts exactly the `host_admin` persons into the group.

## Risks

- The group is a new account object on the host. It is created by the follow as root, like the persons' accounts,
  and removed with the host's own cleanup.
- A stale file can mislead. It names the follow that wrote it and its time.

## Blockers

None.

## Acceptance

- On a host, a `host_admin` person runs `porelo-host org status` as themselves and sees the state. A person without
  `host_admin` cannot read the file.

## Open questions

None.
