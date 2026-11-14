# B592 — The Window's Refresh Calls The Host's Own Program

```json
{
  "$schema": "issue-1.json",
  "id": "B592",
  "title": "The Window's Refresh Calls The Host's Own Program",
  "state": "Backlog",
  "size": "XS",
  "priority": "Medium",
  "rank": 8,
  "blockedBy": [],
  "covers": "`porelo org host refresh` runs `/usr/sbin/porelo-host org refresh` once the collection installs it; no call names `porelo-host-person`."
}
```

**Why this size.** one program name in `OrgHostRefreshCommand`, its words and `doc/commands.md`.

Filed 2026-11-10 under ST291, from the check of [B550](README.md#b550) against `main`.

It waits on [porelo-host#HO156](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho156), an issue of another repository.

**What must be true.** `porelo org host refresh` hands the organization's token to the host's own program,
`/usr/sbin/porelo-host org refresh`, once a host's collection installs that package, and its refusal words name that
program; no host-side call of porelo names the Python `porelo-host-person` any more.

## Plan

Read in the code: [B550](README.md#b550)'s `org refresh` is built in `host/` (`PoreloHost`, `HostFollow`), but
`OrgHostRefreshCommand` (apps/app-organization, the command at :188 and its invalid-answer words at :219) still runs
`porelo-host-person org refresh` over root's ssh, and `doc/commands.md` describes that. Switching before
[HO156](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho156) installs `porelo-host` on a host would
break the refresh there, hence the blocker.

- The absolute path `/usr/sbin/porelo-host` comes with [B764](README.md#b764). Keep the exit-code mapping (Java's frame answers 77
  for a non-root caller where Python answered 69: map both to the same refusal).
- The words at :219, `doc/commands.md`'s row, and the remaining mentions of `porelo-host-person` in `apps/` and
  `host/` messages (`CommitVerifyCommand`, `HostArrivals`, `FollowRooms`, `PoreloHost`, `FollowContext`'s staging
  prefix) name `porelo-host`.
- Red first: the refresh command's argument list names `/usr/sbin/porelo-host`; a host answering 77 is refused as 69 is.
