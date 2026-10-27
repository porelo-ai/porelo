# B306 — Porelo's Build Runs On The Local Forge

**Status:** Idea.

**What must be true.** porelo's `build.yml` runs green on the host's Forgejo and on the central forge on the same
commit, with no edit between them, as porelo-project
[PJ60](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj60) asks of every repository that
builds: the acceptance legs ask for machines by kind, no workflow names a provider, and the registries it publishes to
and reads from are variables of the setup.

## What is in the way today

- **The legs:** the acceptance job rents a leg per OS through `porelo-machines leg`, with `vars.LEG_TYPES` naming
  server types, and `sweep.yml` sweeps by provider; on the local forge they ask the host's pool for a kind instead
  (porelo-buildtools [BT25](https://github.com/porelo-ai/porelo-buildtools/blob/main/issues/README.md#bt25)).
- **Publishing:** snapshots go to Sonatype and the packages to Artifactory; on the local forge they go to the forge's
  registries, switched by a variable present on both forges, never a branch in the workflow.
- **The published binary is the one the Ubuntu leg tested**, fetched back with a manifest; that holds on both forges.
