# B747 — An Unreadable Local Upstream Is Named As Such

```json
{
  "$schema": "issue-1.json",
  "id": "B747",
  "title": "An Unreadable Local Upstream Is Named As Such",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 72,
  "blockedBy": [],
  "covers": "A start on a repository whose upstream is a local path this account cannot read is refused before anything is made, naming it as unreadable with what fixes it, never as missing and never with git's command line.",
  "openQuestions": 0
}
```

**Why this size.** S: the start's upstream check learns local paths, with its refusal and tests in `apps/app`.
Estimate: 45 minutes (Core's median for S).

**What must be true.** A person whose repository's upstream exists but cannot be read is told that it cannot be read
and how to give access. They are not told that it does not exist, and nothing is left behind.

Found while playing [B682](README.md#b682) on a test VM (2026-11-13). A bare repository at mode 000 was added with
`porelo project default add /tmp/…/st30-…-locked.git --name st30-…-locked` and then started with `-p default -r
st30-…-locked`:

```
porelo: no git gate for this task: 'git -c credential.helper= -c gpg.ssh.program=ssh-keygen -c credential.helper= clone --bare --no-hardlinks --end-of-options /tmp/…/st30-…-locked.git $XDG_DATA_HOME/porelo/mirrors/default/st30-…-locked.git' failed with exit code 128: fatal: repository '/tmp/…/st30-…-locked.git' does not exist
porelo: nothing was created; the agent would work in an empty workspace with nowhere to hand its work back. Fix what is said above, or '--attach shell' starts a shell without a gate
```

The exit code was 69. The repository exists, but the refusal says it does not, and it repeats Porelo's own git
command. An empty directory stays at `$XDG_DATA_HOME/porelo/mirrors/default/st30-…-locked.git`.

## Plan

- `UpstreamProbe.forStart` (`apps/app-model`) asks an upstream before anything is made, but returns `null` for any
  address that `remote()` does not call remote, so a local path goes straight to the gate's clone in
  `WorkspaceSetup` (`apps/app-task`, "no git gate for this task", about line 223). Give a local path, or a `file://`
  one, its own check:
  - a path that does not exist answers `NOT_FOUND`;
  - one that this account cannot read or enter (`Files.isReadable`, `Files.isExecutable` on the directory) answers
    `UNREADABLE`: "<path> cannot be read by this account: give it read access, or add a copy it can read".
- `StartCheck.withUpstream` then refuses as it does for a remote upstream, before the mirror is made.
- A clone that fails in `WorkspaceSetup` removes the mirror directory it made.
- First red, in `apps/app`: `UpstreamProbeTest` with a directory at mode 000 expects `UNREADABLE` and no command line
  in the words; a missing path expects `NOT_FOUND`.

## Acceptance

- A start on a local upstream at mode 000 is refused before anything is made, names it as one that cannot be read,
  and says how to give access, with no git command line in the words.
- A missing local upstream is named as missing.
- No mirror directory is left by either refusal.
