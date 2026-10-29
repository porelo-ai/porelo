# B311 — A Project Verified Without Following It

**Status:** Review.
**Size:** S - one command over `Reconcile`'s check, its JSON, and the tests.

**What must be true.** `porelo project verify --repository <path|bundle> --signed-by <key>... [--project <name>]
[--since <commit>] [--access-repository <path|bundle>] [--access-since <commit>] [--json]` checks a project, and its
access file, exactly as following would, writes nothing, reads nothing of the account it runs as, and needs no
vault, home or network; root's `sync` and the interface's *Join* use it instead of a checker of their own.
`--signed-by` is repeated for several keys, each the key itself, never a fingerprint.

## Why

Decided by the operator: porelo-host's `porelo-host-person
sync` runs it as a throwaway unprivileged user on root's
copies of the bundles, against the key pinned at the project's registration; the interface's *Join* runs it on the
developer's laptop. A second implementation of the handover rules would drift from Porelo's, and its drift is where a
forged developer list would pass.

## Plan

Read in the code on 2026-10-28.

- `porelo project inventory --machine-file` already verifies a project file against a key "exactly as `porelo
  project follow` checks it, and nothing is recorded, followed or pinned" (`ProjectInventoryCommand`), through
  `Reconcile`'s check in a scratch directory (`FollowSignedBy.checked`).
- **The plan:** `ProjectVerifyCommand` (`app-project`) runs the same check on the given repository or bundle, with
  `--since` as the commit in force, and for the access repository ([B310](README.md#b310)) with `--access-since`.
- It answers `{"project","commit","access_commit","signer","signers","host","developers","transports"}` for the
  newest commits that hold, `signers` being the keys in force after a handover, or exits 65 naming why; 66 when a
  path is not there.
- It reads no vault and opens no network: a bundle or a local path only, refused otherwise.
- **Docs:** `doc/commands.md`, `doc/hosts.md`. **Red first:** a forged access commit, a non-descending one, a key
  handed over, each answered as `follow` answers it.

## Acceptance

- Seen to fail first, then green, in `app-project` tests.
- Run with no `$HOME`, no vault and no network (as `systemd-run -p DynamicUser=yes -p PrivateNetwork=yes` gives),
  it answers the same.
