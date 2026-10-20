# B227 — A Project Followed From A Path Is Refreshed

**Status:** Analyzed.
**Size:** S - one skip removed from the watch, one outcome for a path that is gone, the follow's help and
`doc/commands.md`; one module and its tests, no contract.

**What must be true.** A project followed from a directory or a bundle on the machine takes a newer commit there at
`porelo project refresh`, under the same checks as one fetched from an address - its signature, a descendant of the
commit in force - so a person who keeps the project's repository locally commits and refreshes, and never follows
again.

## Why

Today `ConfigurationWatch` (`:181`) answers `FROM_A_FILE` for any project whose source is a path and reads nothing:
"follow it again from a newer file to change it". The reason it gives, that an offline project never connects, does
not hold for a path on the same machine: reading it connects nowhere. A `file://` address is fetched instead
(`FollowedProjects.fromAFile`), which a person must know to use.

## The shape proposed

- `refresh` reads the recorded path as it fetches an address, and the outcome is the one a fetch has (`APPLIED`,
  `UNCHANGED`, a refusal); an offline project followed from a path stays readable this way.
- A path that is gone - an unmounted drive, a moved directory - is its own outcome naming the path, and what was in
  force stays.
- **Decided:** the daemon's background watch reads a path in the same round as it fetches an address; nothing
  connects, and a commit in the local repository takes effect without a command.

## Plan

Read in the code on 2026-10-20 (`apps/app-project`).

1. **`ConfigurationWatch.once`** (`:181`) answers `FROM_A_FILE` for every path and reads nothing. That skip goes;
   `Reconcile.run` already fetches a path as it fetches an address (`git fetch <path> HEAD`, `:396`) and already
   leaves out the offline check for a path (`:646`), so the signature walk, the descendant rule and `--accept-rewrite`
   apply unchanged. `refresh` and the daemon's round (`PoreloDaemon`, `:1064`) both go through `once`, so both read
   it.
2. **A path that is gone**: before the fetch, a recorded path that is neither a directory nor a file answers a new
   `Reconcile.Outcome.SOURCE_MISSING` naming the path, and what was in force stays; `ProjectRefreshCommand` (`:69`)
   exits non-zero for it, as for any other failure. `FROM_A_FILE` is removed with its last use.
3. **Text**: `ProjectFollowCommand`'s `<url|file>` help no longer says "read once and never fetched";
   `doc/commands.md` (`:409`) says a path is read again at every refresh, the offline project's way included.

**Red first:** `ConfigurationWatchTest`: a project followed from a local repository, then a new commit made there,
is at that commit after one round; today it stays and answers `FROM_A_FILE`. A recorded path that was removed
answers `SOURCE_MISSING` and keeps the commit in force.

## Acceptance

- Followed from a local directory, a new commit there is in force after `porelo project refresh` and, with nothing
  run, after the daemon's next round.
- A commit there that is not signed by the pinned key, or not a descendant, is refused as it is from an address.
- A path that is gone is named, and the project keeps what it had.
