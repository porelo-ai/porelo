# B590 — The Gathered Push Takes Repository Names And Names A 401

```json
{
  "$schema": "issue-1.json",
  "id": "B590",
  "title": "The Gathered Push Takes Repository Names And Names A 401",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 8,
  "blockedBy": ["B544", "B580"],
  "covers": "The gathered push takes a project-file key or forge name and reports a central 401 before any push, through the shared typed refusal."
}
```

**Why this size.** carry repository aliases from the follow into selection and detect central HTTP refusal before transferring any repository.

Filed 2026-11-10 under ST290, from the fourth move.

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

- Supervisor machine check, not run here: follow a verified project whose key and forge name differ, then push
  separately through either name using the CLI and the existing daemon flow. Check that each selects the same
  repository, repeated aliases refuse, and ambiguous names change no repository.
- Supervisor machine check, not run here: on a public central repository, verify that its authenticated
  `info/refs?service=git-receive-pack` endpoint rejects a refused credential even while anonymous reads succeed.
  With the vault already open, check CLI words, nonzero exit, no prompts or replacement command, unchanged central
  refs, and the daemon's `CentralTokenRefused` forge/status fields. Repeat with only the second destination refusing.
- Supervisor machine check, not run here: retain the installed sudo rule and account ownership checks; exercise
  follow through its systemd unit and use the shared daemon acceptance from [B580](README.md#b580).

## As built

- Follow retains verified project-file keys beside forge names in the person's push configuration. Existing rows
  remain readable; several verified keys for one forge repository coalesce into one destination. Selection accepts
  either spelling, refuses collisions across destinations and repeated selection through aliases, and lists the
  available key/name pairs before any transfer. This preserves the existing follow case in which another selected
  project key keeps the same central repository. A verified key's canonical central address must match the forge's
  recorded migration; matching only the basename would silently release a different repository after a refused
  migration. That case was reproduced before adding the address check.
- The existing shared gathered flow preflights every selected central Git receive advertisement before any clone
  or push. The checked curl uses the same central credential and resolved root-owned CA, HTTPS only, no inherited
  configuration, proxies, redirects or prompts, a fifteen-second deadline and bounded captured output. Error bodies
  are discarded; an observed 401 survives a nonzero curl exit. No HTTP trace is enabled.
- The CLI names the central forge and HTTP 401. The existing typed refusal gains optional forge and status fields;
  its replacement command is empty. Missing credentials and explicit vault unlocking retain their own outcomes.
  Later transfer-time refusals retain confirmed earlier pushes. No privileged verb or sudo grant is added.
- Plan and code received separate read-only reviews. The code finding about incomplete 401 bodies was reproduced
  as four failing behavior cases before its fix. A proposal to refuse every repeated project-key correspondence
  was narrowed after the existing follow regression demonstrated that several keys can identify one destination;
  conflicting destinations still refuse. Validation and sandbox limitations are in the local handover report.
