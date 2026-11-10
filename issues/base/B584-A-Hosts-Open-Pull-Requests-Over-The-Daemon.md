# [B584](README.md#b584) — A Host's Open Pull Requests Over The Daemon

**Status:** Implementing. Filed 2026-11-09 under ST290, from the fourth move.
**Size:** M - a read and a watch of the host forge's pull requests per project, with makers and end, in Tasks1.

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
