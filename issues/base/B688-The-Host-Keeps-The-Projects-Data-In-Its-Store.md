# B688 — The Host Keeps The Project's Data In Its Store

```json
{
  "$schema": "issue-1.json",
  "id": "B688",
  "title": "The Host Keeps The Project's Data In Its Store",
  "state": "Implementing",
  "size": "M",
  "priority": "Medium",
  "rank": 42,
  "blockedBy": [],
  "covers": "`porelo-host store put|keep|restore` copies each kept entry into the project's restic store and puts it back at `up`.",
  "openQuestions": 0
}
```

**Why this size.** three subcommands of `porelo-host`, root-only key files, a runner that executes each entry as its
account, and restic driven through files; estimated M by Agent Machines.

Filed 2026-11-13 for Agent Machines. porelo-host's collection waits on it for its `store` role; the cut between the
two was agreed with the Coordinator on 2026-11-12.

**What must be true.**
- **`porelo-host store put`**, run as root over SSH with a host admin's card, reads lines `store <name> <base64>` on
  stdin, one each for `repository`, `password` and `s3-credentials`.
  - It writes each to `/etc/porelo-host/store/<name>`, root's, mode 0600, and refuses any other name.
  - No value ever goes on a command line or into a log.
- **`porelo-host store keep [--all]`** runs, as root, each entry that is due into a fresh directory, as the entry's
  account (`runuser`), then `restic backup --tag <entry>` and `restic forget --tag <entry> --keep-last <n>`. The
  schedule is `hourly`, or `down` with `--all`.
  - It stops at the first entry that fails and exits non-zero naming it. No forget runs after a failure.
  - restic gets its keys through `RESTIC_REPOSITORY_FILE`, `RESTIC_PASSWORD_FILE` and the S3 credentials file, never
    through its arguments or environment values.
- **`porelo-host store restore`** runs at `up`, before the homeserver and the first task start: `restic restore latest
  --tag <entry>`, then the entry's restore command.
  - A store with no snapshot yet is a first start, and is said so.
  - The room's `{"restore": "present"}` counts as unchanged.
- **The entries are data**, each with its account, its commands and how many snapshots it keeps:
  - the room: `porelo-message-matrix backup|restore <dir>`, as `porelo-matrix`, keep 48;
  - each person's usage records: `porelo keep [--restore] <dir>`, as the person, keep 48; exit 69 at a restore while a
    task runs;
  - the Maven cache, only once Porelo's shared cache exists;
  - the vault is never an entry.
- **The keys lie in the clear** on the host's disk, root-only. That is porelo-host's documented exception (its
  decisions, "The store's keys on the host"), and the key reaches its bucket only.

**Not here, in porelo-host's collection:** the `store` role installing restic pinned by version and checked against
its SHA256SUMS; the hourly systemd timer of root that runs `store keep`; `up` calling `store restore` before the
homeserver; `down` calling `store keep --all` first and stopping if it fails; removing `/etc/porelo-host/store/` at the
end of `down`.

## Plan

To be written when it is taken: read `host`'s command tree and its existing root-only file writes first.

## Acceptance

- `store put` writes the three files root-only and refuses another name; no value appears in `ps`, the journal or the
  command's output.
- `store keep` runs each due entry as its account and stops at the first failure without a forget; `store restore` at
  a first start says so.
- Each seen to fail first.
