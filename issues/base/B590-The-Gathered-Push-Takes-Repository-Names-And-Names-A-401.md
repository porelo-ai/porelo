# [B590](README.md#b590) — The Gathered Push Takes Repository Names And Names A 401

**Status:** Analyzed. Filed 2026-11-10 under ST290, from the fourth move.
**Size:** M - carry repository aliases from the follow into selection and detect central HTTP refusal before transferring any repository.
**Blocked by:** [B544](README.md#b544), [B580](README.md#b580).

**What must be true.** `porelo host push` accepts either a repository's key in `project.yml` or its name on the host's
forge and pushes the same repository. A central HTTP 401 ends with a refusal naming that forge and the status, with
nothing pushed and no credential prompt or replacement-token request caused by that refusal.

## Plan

Read in the code: `apps/app-vault`'s `HostGatherConfig.Repository` keeps only a forge name and central URL, and
`select` matches only that name. `host/`'s `FollowProjects.pushConfigs` writes names from the forge's repository list;
`takenAs` already maps a project-file key to the repository basename in `central`. `HostGatherFlow` pushes repositories
sequentially and renders other Git failures as text. Its Git calls already disable terminal prompts and credential
helpers, scope authorization to the destination and disable redirects. [B580](README.md#b580), Implementing, owns the
daemon's progress and typed refusals, including a refused central token; this issue adds alias selection and reliable
HTTP-status detection to that shared flow, not another daemon method or refusal vocabulary.

- Extend `FollowProjects.pushConfigs` to retain the project-file key beside the existing forge name and central URL,
  from the verified project file. Extend `HostGatherConfig.parse` and `select` for that representation, keeping
  existing configurations readable. Resolve both names to one destination before transferring; reject ambiguity and
  repeated selection of the same repository through two names, listing the available key/name pairs.
- In the shared gathered flow, obtain the central authentication result as a bounded HTTP status before the first
  push across all selected destinations, using the same credential, CA and no-redirect rules. Reuse the typed
  central-token refusal introduced by [B580](README.md#b580), carrying forge and HTTP status into both renderers.
  Do not infer a 401 from Git's username-prompt sentence or enable HTTP tracing that could print authorization.
- A refused token stops before any push, with a nonzero CLI result; no retry asks for another token. Keep the
  preexisting explicit vault-unlock behavior separate from a central refusal. Preserve accounting for a refusal
  that occurs later during transfer: report any earlier successful pushes honestly, never claim rollback.
- Red first: `HostGatherConfigTest` selects a fixture whose key differs from its forge name through either name,
  refuses a collision and refuses selecting both aliases. A gathered-flow test uses a scripted HTTPS central that
  returns 401: its forge and status survive in the typed result and CLI output, zero push requests occur, no prompt
  runs, and no raw or encoded token appears. A second destination refusing preflight also prevents the first push.
- Document both repository names and the refused-token answer in `doc/hosts.md`; run the same flow through the daemon
  acceptance supplied by [B580](README.md#b580).

## Acceptance

- A project whose repository key differs from the forge name reaches the same repository through either spelling;
  an unknown or ambiguous spelling changes no repository and names the available pairs.
- On a machine with an open vault and a central token answered with 401, the push names the forge and `401`, exits
  nonzero, changes no central ref and asks for nothing. The daemon carries that refusal as a value.

## To be checked

- Choose the bounded authentication probe with the shared flow's implementation so that it tests the selected Git
  endpoint's credential acceptance, including for a public repository, rather than trusting an anonymous read.
