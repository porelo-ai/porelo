# B236 — The Inventory Names The Project's Transports

**Status:** Idea.

**What must be true.** `porelo project inventory` names the packages of the transports a project's tasks use, so a
host's play installs each where the project names it, as it installs a task's agent package.

## Why

porelo-host's [HO02](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho02) installs what a project
needs on its host from the second play's inventory. [B217](README.md#b217) carries each task's `agent_package` there;
nothing carries a transport, so the Matrix transport and its homeserver cannot be installed where the project names
them, and [HO02](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho02) cannot reach its end. Whether it goes into each entry of `porelo_tasks` or into a list of its own beside
it is for the analysis.
