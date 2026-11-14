# B767 — The Docs Say What The Code Does

```json
{
  "$schema": "issue-1.json",
  "id": "B767",
  "title": "The Docs Say What The Code Does",
  "state": "Review",
  "size": "S",
  "priority": "Highest",
  "rank": 3,
  "blockedBy": [],
  "covers": "Every sentence of porelo's user-facing docs (README.md, doc/ but decisions.md, the changelog) that the code on main does not bear out is corrected, or filed as a bug where the code is wrong",
  "openQuestions": 0
}
```

**Why this size.** S: the reading is done by sub-agents, then each finding is checked in the code by hand and fixed in
one commit. Estimate: 38 minutes (Core's median for S).

**What must be true.** A command, flag, path, default, state, message or behavior a page names is the one the code
has. Where the page is right and the code wrong, the bug is filed, not the page changed. No behavior changes here.

Filed 2026-11-14 on the operator's order, under ST361.

## Plan

- Sub-agents on Sonnet read the pages against main (196b13fb0), split into commands.md, credentials.md with
  project-file.md and running.md, and the rest. They run no test suite and no VM.
- Each finding is checked in the code. Only a confirmed one is changed.
- Left to the issue on how the Git mirror is kept, held unpushed on another branch: `doc/how-it-works.md`'s gate section and
  its section on `default`.

## Built

The sub-agents read about 1,600 claims on 22 pages and the titles of the 508 unreleased changelog entries. They
reported 13 page findings and 11 changelog findings. Each page finding was checked in the code. All 13 are true; 12
are fixed here, and one (`porelo task run --reads` in `how-it-works.md`, which is `task start`) belongs to that held issue's
passage. None was a fault of the code, so no bug is filed.

- `doc/commands.md`: the `project` row says what the group does (`ProjectsCommand`), not that it follows.
  `certified` is in `task status --json`, since `task list` has no `--json`. `project remove` replaces the gone
  `project unfollow`, among the commands that name a survivor, and TAB offers projects for `project remove` and
  `project clear`. `gate pending` takes `-p` and `-r` (`GatePendingCommand`).
- `doc/running.md`: the dump names `porelo-upstream`, `porelo-messages` and `porelo-room-sweep`. No thread is
  called `porelo-follow`.
- `doc/credentials.md`: a refused client credential's message carries the HTTP status and an OAuth error code
  (`TokenPurchase`). The fallback names include the host-less `git.ssh` and `git.token`
  (`GitCredentialNames.candidatesFor`).
- `doc/getting-started.md`: the `follow`, `--signed-by` and `--unverified` bullets are gone, since a project is read
  at each start. The bullet now says the start takes `project.yml` only when an admin of the project signed its
  newest change.
- `doc/corporate-security.md`: no socket to a key enters a container (as `security.md` says). The developer may allow
  a repository its own settings in `default`, where the page said "follow an unsigned project".
- `README.md`, `doc/pitch.md`: every capability is dropped except the two Porelo's own session needs
  (`ContainerSpec`: `--cap-add SETUID,SETGID`), and the agent holds none.
- The changelog, by the operator's decision of 2026-11-14: every unreleased entry for a command added since v0.5.0
  and removed again, or for one removed since, leaves the coming release notes. Eleven files are deleted
  (`project follow --access-repository`, `unfollow`, `inventory`, `access`, `identity backup-key --project`, and the
  follow's pins). Six keep only what still holds: `task status --json`; `identity` without `project access replace`;
  the credential-free addresses without `registry joinable` and the access file's `source`; `forge lend-token` for
  porelo-host's `forge-token --lend`; the room's persons without `talk room`; the organization's commands without
  `org projects`. That following by hand is gone is said by the entry already there,
  `start-reads-the-organization.yml`.
- The tests tagged `documents` are green.
