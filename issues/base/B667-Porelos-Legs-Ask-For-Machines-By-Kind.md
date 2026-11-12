# B667 — Porelo's Legs Ask For Machines By Kind

```json
{
  "$schema": "issue-1.json",
  "id": "B667",
  "title": "Porelo's Legs Ask For Machines By Kind",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 88,
  "blockedBy": [],
  "covers": "The workflows pass PORELO_MACHINES_API and PORELO_MACHINES_SSH, the legs ask porelo-machines by kind and never name a provider or a server type, and check-providers passes on porelo."
}
```

Filed 2026-11-12 for ST22 from Agent Machines (porelo-buildtools BT25). porelo still names a provider where every
other repository asks porelo-machines by kind. On main, `.github/workflows/build.yml` (the legs' rent and clean-up
steps) and `.github/workflows/sweep.yml` pass `HETZNER_API` and `HETZNER_SSH`, and
`acceptance/legs/src/main/java/org/fuin/porelo/legs/Main.java` reads them and takes `--type`.

**What must be true.** The workflows pass `PORELO_MACHINES_API` and `PORELO_MACHINES_SSH`, the legs ask with `--kind`
(`amd64-build` for the leg with four accounts) and never name a provider or a server type, and
`porelo-release check-providers` passes on porelo. porelo-buildtools BT83 then drops the old call and the `HETZNER_*`
fallbacks, and waits for this one.

## To be checked

- How this meets [B322](README.md#b322), whose leg takes the host's pool by kind and keeps `--type` for the rented
  machine: built first, this leaves B322 only the pool's source and the workflow condition.
- `vars.LEG_TYPES` and the core workflow tests that pin the legs' arguments (`PublishWorkflowTest` and its neighbours).
