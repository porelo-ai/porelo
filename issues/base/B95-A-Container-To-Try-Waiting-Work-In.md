# [B95](README.md#b95) — A Container To Try Waiting Work In

**Status:** Idea.

**What must be true.** A reviewer or the host can build, test or scan waiting work in a fresh throw-away container,
with source/risk restrictions and no way back to the gate. Output, exit and normalized SARIF evidence remain beside
the exact reviewed commit; E0/E1 work is only read and commented until this execution path exists.

## Proposed

- Blocked by: [B136](README.md#b136), [B292](README.md#b292), [B293](README.md#b293).
- Add `TryRun` in `apps/app-gate` and `GateTryCommand`: `porelo gate try <task> -- <argv...>`, with project/repository
  selection and exact captured commit. Refuse moved work as `MovedSinceReview`. `35-gate.varlink` gains streaming
  `Try` with the same target/commit/argv, output chunks and final exit; refuse calls without streaming as
  `StreamRequired`. Command output is not executed or parsed as authorization.
- Export the pinned commit with `git archive` directly into a workspace inside the new container. Never materialize
  a checkout on the host or expose the mirror's `.git`, hooks, writable refs or the developer container's caches.
  Use a trusted pinned project image, not an image built from the unreviewed change on the host. Repository-local
  archive attributes and unusual paths cannot make exported content silently differ from the reviewed tree.
- Derive a container specification from the relevant `TaskRunner` setup in `apps/app-base` and `ContainerSpec` in
  `runtime`, without a normal task's gate token/address, vault/provider socket, mailbox, guide, host mounts or daemon
  access. Give it a distinct Porelo kind recognized by cleanup but absent from task listings. Always remove it in
  final cleanup and preserve command output and status; no result is copied back into the mirror.
- Intersect [B293](README.md#b293)'s source and risk rules: E0 or C3 has no secrets, tokens or network egress; E1 has
  no secrets/tokens and only allowed project egress; A gains no secrets/tokens. I1 follows project rules within risk
  and machine policy, with no credentials by default. Strong no-egress rules must also omit gate and host-service
  exceptions: `NftRuleset` permits them even in ordinary offline tasks. A project setting cannot undo the bound.
- Use project limits, with a review-specific ceiling if approved. Add a repository `try` argv list in the project
  model, `ProjectReader` and `ProjectSchema`; a scalar shell command is refused. A person's command override cannot
  widen network or secret rights. No command is taken from SARIF invocations.
- Retain command, commit, exit and output with the review, exposing bounded `TryResult` metadata and a separate
  output stream. Scanner files go only through [B292](README.md#b292)'s isolated import worker and verified root
  mapping; normalized findings are pinned to this commit. A failed, oversized or timed-out import is partial/failed,
  never clean. A successful process exit alone is not a verdict on the work.
- An IDE follow-up uses a backend installed in the trusted image and reached inside the container; no backend or
  reviewed build runs on the host. Coordinate remote access with [B100](README.md#b100). Update command, project-file,
  gate and reach documentation with the implemented execution limits.

## Open questions

- Should try runs use the task's resource ceiling or a separate review ceiling, and with which defaults?
- Must approval record the exact try run it followed in addition to its commit?

## To be checked

- Verify a specification fixture contains no host services, gate, credential sockets or forbidden mounts for E0/C3;
  prove failure to reach each in acceptance. Exercise E1 allowed egress without secrets and refuse policy widening.
- Verify archive materialization, hooks and cleanup using actual container/mirror observations, including
  interruption and an unstartable container. The mirror must be unchanged and the try container absent afterward.
- Determine retained-output and scanner-file transfer bounds and the I1-only secret mechanism, if a project permits
  it, without granting the reviewed run the vault or provider socket. Other sources cannot receive credentials.
