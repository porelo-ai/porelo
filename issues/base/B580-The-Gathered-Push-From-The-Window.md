# B580 — The Gathered Push From The Window

```json
{
  "$schema": "issue-1.json",
  "id": "B580",
  "title": "The Gathered Push From The Window",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 15,
  "blockedBy": ["B544"],
  "covers": "`PushToCentral` over the daemon: the gathered push to central with progress and typed refusals, for the window's release."
}
```

**Why this size.** a daemon method for [B544](README.md#b544)'s gathered push, its progress and refusals as values.

Filed 2026-11-09 under ST291, from the fourth move.

**What must be true.** A client of porelod can bring a host's finished `main` to central for a project, as
[B544](README.md#b544)'s `porelo host push` does, with progress and a typed answer, so the window offers it as the
release a person expects in class `online`.

## Plan

- A Tasks1 method `PushToCentral(project)` over the daemon, streaming progress, answering typed refusals (vault shut,
  no central token, central refused the token, nothing to push), calling what [B544](README.md#b544) builds.
- Its interface text and `doc/`; the window's button is porelo-frontend's.
- Red first: a refused central token answers its typed error, never a git prompt's words.


## As built

- Tasks1 `PushToCentral(project, repositories)` lives in the gate's part beside the host's review methods. Optional repository selection follows the person's checked configuration. Streaming replies carry only a repository and a fixed stage; the final answer separates confirmed pushes from already up-to-date mains.
- `HostGatherPush` shares [B544](README.md#b544)'s validation, vault access, checked host token issuer and `HostGatherFlow` with `porelo host push` and `pull`. The CLI retains its terminal policy and words. The daemon always disables prompts and uses the existing noninteractive sudo rule, with no new privilege.
- Named wire errors distinguish vault unlock routes, a missing central credential and its storage command, refused central authentication, no changes, host trust/preflight failures, unavailable local token issuance, central refusal/reconciliation and an interrupted write whose remote outcome is unknown. Confirmed earlier completions survive later failures.
- The account's forge-token lock covers issuance through transfer and also standalone CLI issuance. Daemon Git processes have a ten-minute deadline, and a streaming disconnect stops owned processes and prevents later repositories. Credentials stay destination-scoped, out of arguments and wire replies; mutable token buffers are cleared after use.
- MCP always excludes this release decision, including when explicitly listed. The window offers the action through the daemon; its button belongs to the frontend repository.
- Requested behavior tests were observed red on behavior before their implementations; additional lifecycle guards were proven with compiling deliberate mutations and restored. Validation, independent review and sandbox limitations are in the untracked worktree report.
