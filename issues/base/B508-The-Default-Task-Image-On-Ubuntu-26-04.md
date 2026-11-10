# [B508](README.md#b508) — The Default Task Image On Ubuntu 26.04

**Status:** Idea. To be built after the trial moves, by the operator's word of 2026-11-08.

**What must be true.** A task image built from Porelo's defaults starts from `ubuntu:26.04` instead of `ubuntu:24.04`,
and every place that names the default names the new one.

## What changes

- **Every task image:** a project that names no base image and a repository's own image, both from the default, are
  built anew on their next start.
- **Where porelo names the default, in the code:**
  - `DefaultProject.BASE_IMAGE`, the `default` project;
  - `ProjectProposal`, a proposed project file;
  - `ProjectCreateRepositoryCommand`, a new repository's file;
  - `AgentLogin.LOGIN_BASE_IMAGE`, the image an agent's login runs in.
  - The tests and the documentation that name 24.04.
- **Ecosystems' list:** porelo-ecosystems' Maven adapter knows which base images carry which tools
  (`adapters/maven/src/main/java/org/fuin/porelo/ecosystem/maven/ToolNeeds.java`), so the list there moves with it,
  and its release goes with Porelo's.
- **The packages layer:** each package a task image installs from Ubuntu is checked to exist under the same name in
  26.04.

## To be checked

- Whether the agents' install steps (`agents/`) hold on 26.04 as they do on 24.04, measured by a build of each.
