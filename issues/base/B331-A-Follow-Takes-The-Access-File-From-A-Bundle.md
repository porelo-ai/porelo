# B331 — A Follow Takes The Access File From A Bundle

**Status:** Backlog.
**Size:** S - one option on `follow` carried into `Reconcile`'s run and dry run, the follow record unchanged; docs
and tests.

**What must be true.** `porelo project follow NAME <project bundle> --signed-by <key> --access-repository <path|bundle>`
follows a project whose `access: {repository: <url>}` names a repository that the host cannot fetch. The access file
comes from the bundle given and is checked exactly as a fetched one is: signed by a key the project holds, and
descending from the access commit in force. The URL is never fetched. A follow from a file is read once, so a newer
access bundle is applied the same way, by following again.

Asked by Agent Machines for porelo-host HO45 (2026-10-29): a host gets the access repository only as a bundle, the
bring follows with both bundles, and B310's follow fetches the URL with the person's git credential. B311
(`project verify`) and B320 (`project inventory`) check an access bundle, but neither makes a follow take one.
`Reconcile` already has an `accessFrom` that is used in place of the URL.

## Plan

Read in the code on 2026-10-29.

- **Today:** `ProjectFollowCommand` calls `FollowSignedBy.follow`/`check`, which run `new Reconcile(context)`, so the
  access repository is fetched from the URL the project names. `Reconcile` already takes an `accessFrom`, which is
  used in place of that URL (B311, B320).
- **The plan:** `--access-repository <path|bundle>` on `follow`, local paths only as in `verify`, carried into each
  `Reconcile` that follow and `--dry-run` start. It is accepted only with a project followed from a file, which is
  read once and never fetched again, so nothing later fetches the URL in its place. The access clone and record are
  written as for a fetched access file, and `ProjectAccess` reads them unchanged.
- **Docs:** `doc/commands.md` (follow), `doc/hosts.md` (the bring follows with both bundles). **Red first:** a
  follow from two bundles whose access URL is unreachable.

## Acceptance

- A follow from a project bundle and an access bundle applies the access file without fetching its URL.
- An access bundle signed by a key the project does not hold is refused as a fetched one is (`unknown_key`).
- `--access-repository` with a project that names no access repository is refused, and with a project followed
  from an address.
- Seen to fail first, then green, in `app-project` tests.
