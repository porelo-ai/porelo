# B624 — The Outage Mirror Leaves The Package Check

```json
{
  "$schema": "issue-1.json",
  "id": "B624",
  "title": "The Outage Mirror Leaves The Package Check",
  "state": "Idea",
  "size": "XS",
  "priority": "Medium",
  "rank": 40,
  "blockedBy": [],
  "covers": "The temporary non-Canonical Ubuntu mirror the package check, the channel line check and a task image's apt steps fall back on during Canonical's outage of 2026-11-11 is removed once Canonical's archive serves again."
}
```

**Why this size.** one constant and its use in `Install`, one in `Containerfile`, one block in `ChannelLine`'s script,
their tests.

Filed 2026-11-11 with the change it removes.

**What must be true.** The package check asks only Canonical's mirrors again, as before the outage, and nothing in
porelo names `mirrors.edge.kernel.org`.

How it is now: during Canonical's outage (its archive and DNS down from 11:06Z), the operator ordered a public Ubuntu
mirror outside Canonical's hosts as the last fallback of every apt step in porelo's Build and Publish.

- `Install.OUTAGE_MIRROR` is the fourth entry of `checkUbuntu`'s mirrors, after azure, archive and azure again, tried
  when an attempt timed out, a fetch was refused, or `apt-get update` failed with no cached lists to stand in.
- `ChannelLine.script` rewrites an Ubuntu image's `ubuntu.sources` to it when its first `apt-get update` fails.
- `Containerfile.OUTAGE_MIRROR` is the last Ubuntu entry of a task image's fallback mirrors
  (`FALLBACK_PACKAGE_SOURCES`), after azure's and Canonical's own, before Debian's.
- Signatures are checked as always: no `trusted=yes`, no `--allow-unauthenticated`.

## Plan

- Remove `Install.OUTAGE_MIRROR` and its entry, `Containerfile.OUTAGE_MIRROR` and its entry, and the block in
  `ChannelLine.script`; keep `UPDATE_FAILED` (an update that
  failed on a Canonical mirror goes on to the other), unless the operator wants the old behaviour back.
- `InstallTest` and `ChannelLineTest` back to three attempts and no rewrite; `ContainerfileTest` and its golden file
  without the mirror.

## Acceptance

- `grep -r mirrors.edge.kernel.org` finds nothing outside the changelog; the package check's tests pass.
