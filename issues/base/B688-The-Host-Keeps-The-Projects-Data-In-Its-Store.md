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

Written 2026-11-13 by Agent Core, after reading `host`'s command tree (`PoreloHost`, picocli), its root-only files
(`HostFiles`, `RootOwnedFile`) and how it runs a command as another account (`FollowRooms`, `runuser` through the
host's runner).

- **A `store` subcommand of `porelo-host`**, with `put`, `keep [--all]` and `restore`, in a new `HostStore` beside the
  other host commands. It runs as root only, refused otherwise as the other root commands are. Every process goes
  through the host's runner, so tests fake it.
- **`store put`:** reads `store <name> <base64>` lines from stdin, with names `repository`, `password` and
  `s3-credentials` and nothing else. It writes each decoded to `/etc/porelo-host/store/<name>` through `HostFiles`,
  root's, mode 0600, the directory 0700. A refused line writes nothing at all, so the three are written together or
  not. Values are never printed; the answer names only the files written.
- **The entries are data, decided here:**
  - A fixed list in the host module, `StoreEntries`, each with:
    - its name (the restic tag)
    - its account
    - its backup and restore commands, each with `<dir>` standing for the directory
    - how many snapshots it keeps
    - its schedule, `hourly` or `down`
  - The room: tag `room`, account `porelo-matrix`, `porelo-message-matrix backup|restore <dir>`, keep 48, hourly.
  - Each person's usage records: tag `usage-<login>`, the person's account, `porelo keep [--restore] <dir>`, keep 48,
    hourly. The persons come from the host's people records (`HostRecords`), so each person is an entry.
  - The Maven cache is not listed until Porelo's shared cache exists. The vault is never listed, and a test pins
    that.
- **Due entries:** `keep` runs every `hourly` entry. `--all` adds the `down` ones (none today). Nothing is
  remembered between runs: the timer that calls `keep` sets the pace.
- **`store keep`:** for each due entry, in the list's order:
  1. Make a fresh directory owned by the entry's account.
  2. Run the backup command there as the account (`runuser -u <account> --`).
  3. Run `restic backup --tag <entry>` and then `restic forget --tag <entry> --keep-last <n>`, as root.
  4. Remove the directory.

  It stops at the first entry that fails, with no forget after it, and exits 70 naming the entry and the step. Before
  the first entry, a store not yet made (`restic cat config` failing as no repository) is made once with
  `restic init`.
- **restic's keys:** `RESTIC_REPOSITORY_FILE` and `RESTIC_PASSWORD_FILE` name the files, and
  `AWS_SHARED_CREDENTIALS_FILE` names the S3 credentials file. These are file paths in the environment, never values;
  no argument carries a key.
- **`store restore`:**
  - A store not yet made, or one with no snapshot of an entry, is a first start and is said so per entry.
  - Otherwise each entry gets `restic restore latest --tag <entry> --target <dir>`, then its restore command as its
    account.
  - The room's answer `{"restore": "present"}` counts as unchanged. A person's `porelo keep --restore` exiting 69
    (a task runs) is said and stops the restore with 69.
- **Red first, in `host`'s tests** (a new `HostStoreTest` over a fake runner and a temporary root):
  - `put` writes the three files 0600 and refuses a fourth name with nothing written; no value appears in the
    output or in any argument the runner saw.
  - `keep` runs a due entry's backup as its account and then restic with the tag. With a failing second entry it
    stops, runs no forget for it, and exits 70 naming it.
  - restic's arguments and environment carry file paths, never a key.
  - `restore` on a store with no snapshot says first start.
  - The entries list has no vault.

## Acceptance

- `store put` writes the three files root-only and refuses another name; no value appears in `ps`, the journal or the
  command's output.
- `store keep` runs each due entry as its account and stops at the first failure without a forget; `store restore` at
  a first start says so.
- Each seen to fail first.
