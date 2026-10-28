# B306 — Porelo's Build Runs On The Local Forge

**Status:** Idea.

**What must be true.** porelo's workflows run green on the host's Forgejo and on the central forge on the same commit,
with no edit between them, as porelo-project
[PJ60](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj60) asks of every repository that
builds.

## What is in the way today

- **The legs:** `build.yml`'s acceptance job rents a leg per OS through this repository's `acceptance/legs`
  (`leg --os ...`, with `vars.LEG_TYPES` naming server types) and sweeps its own machines with `porelo-machines sweep
  --mine`; on the local forge the legs ask the host's pool for a kind (porelo-buildtools
  [BT25](https://github.com/porelo-ai/porelo-buildtools/blob/main/issues/README.md#bt25)).
- **The other workflows that name the provider:** `sweep.yml` passes `HETZNER_API` to `sweep --now`, and
  `artifactory-smoke.yml` rents a machine and uploads to Artifactory; `delete-runs.yml` calls GitHub's API.
- **Publishing:** the Maven deploy is switched between Central and the forge's Maven registry by the deploy profile
  [PJ60](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj60) names (`PORELO_DEPLOY_PROFILE`), and the Artifactory uploads of the packages stay off on the local forge by
  `PORELO_PUBLISH`, each a variable present on both forges, never a branch in the workflow.
- **The agent repositories build against what this Build deploys there:** porelo's snapshots (the
  `porelo-acceptance-kit` among them) reach the forge's registry only from porelo's own Build on that forge, never
  copied from Sonatype, so a change made in a task can be built against before it reaches GitHub (porelo-pi
  [PI38](https://github.com/porelo-ai/porelo-pi/blob/main/issues/README.md#pi38), porelo-omp
  [OM35](https://github.com/porelo-ai/porelo-omp/blob/main/issues/README.md#om35), porelo-claude-code
  [CC38](https://github.com/porelo-ai/porelo-claude-code/blob/main/issues/README.md#cc38)).
- **What holds on both:** the published binary is the one the Ubuntu leg tested, fetched back with a manifest.
