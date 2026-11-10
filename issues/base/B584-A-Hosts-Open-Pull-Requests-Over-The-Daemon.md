# B584 — A Host's Open Pull Requests Over The Daemon

```json
{
  "$schema": "issue-1.json",
  "id": "B584",
  "title": "A Host's Open Pull Requests Over The Daemon",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 22,
  "blockedBy": [],
  "covers": "A host forge's open pull requests per project, with makers, read and watched over the daemon until each merges or closes."
}
```

**Why this size.** a read and a watch of the host forge's pull requests per project, with makers and end, in Tasks1.

Filed 2026-11-09 under ST290, from the fourth move.

**What must be true.** A client of porelod on a host can read, and watch, the open pull requests of a project's
repositories on the host's forge: repository, number, title, head commit, its makers (author, committer, the commit's
`Assisted-by`/`Co-Authored-By` trailers) and state, with a final event when one merges or closes, so the window names
a pull request waiting for the person's approval and merge as the one action and closes that row when it ends.

## Plan

Read in the code: `HostPullRequests` already speaks to the host's Forgejo over `127.0.0.1` with a task's token, opens a
review and finds an open one; nothing reads the list for a person or reports an end.

- A reader beside `HostPullRequests` lists a repository's open pull requests and a pull request's state, with the
  person's own forge token from the vault, the same no-proxy, no-redirect client, bounded pages and sizes.
- The head commit's makers read from the host's mirror of the repository (author, committer, trailers), not from
  the forge's words.
- Tasks1 `PullRequests(project)` and a watch `WatchPullRequests(project)` that polls at a bounded interval and sends a
  final event per pull request that merged or closed; interface text and doc/.
- Red first: a fake forge's open pull request is listed with its makers; one that merges between two polls ends with
  `merged`; a forge answer over the size bound is refused.

## As built

- `HostPullRequestReader` reads the person's `<name>.person` vault entry. `HostForgeSettings` shares the bounded,
  root-owned plain-file read and name/account checks with `HostForgeTokenCommand`; the reader additionally requires
  one valid `git_port`. It never mints a token or accepts a forge address or account from the caller.
- Each carried repository is read at the configured port on literal `127.0.0.1`, with no proxy or redirects.
  Responses and complete wire replies are bounded at one MiB, with at most 100 repositories, 500 open rows, and
  100 pages of 50 per repository. Invalid numbers, commit ids, states or shapes refuse the observation.
- Makers of exactly the head commit come from the local mirror, with a ten-second git deadline and 64 KiB output
  bound. Displayed fields keep 1000 code points, and at most 50 unfolded help trailers are returned.
- `PullRequests(project)` and `WatchPullRequests(project)` are registered in the gate's Tasks1 part. The watch waits
  five seconds after a completed round. It sends the current open list and separately confirmed `merged` or `closed`
  events; each terminal event ends a row, not the watch. Failed access and absence alone never become closed.
- A missing or unusable token answers `MissingHostForgeToken(credential, storeCommand)`, naming
  `porelo host forge-token`; a locked or unreadable vault is a separate named refusal. Both methods are in MCP's
  `never` set because a task cannot spend the person's identity to read their review queue.
- The four requested reader tests were observed red on behavior before implementation, then green. Daemon tests
  were observed red for wire-error mapping, MCP exclusion, empty/one-shot watches, disconnect and per-row end events.
  Final validation results and sandbox refusals are recorded in the untracked worktree report.
