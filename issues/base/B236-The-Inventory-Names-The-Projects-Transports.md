# B236 — The Inventory Names The Project's Transports

**Status:** Review: published since `0.5.1~snapshot.317`; porelo-host's play reads `porelo_transports`.
**Size:** M - one module, `app-project`, but the inventory is a contract porelo-host's play reads, with `doc/hosts.md`.

**What must be true.** `porelo project inventory` names the packages of the transports a project's tasks use, so a
host's play installs each where the project names it, as it installs a task's agent package.

## Why

porelo-host's [HO02](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho02) installs what a project
needs on its host from the second play's inventory. [B217](README.md#b217) carries each task's `agent_package` there;
nothing carries a transport, so the Matrix transport and its homeserver cannot be installed where the project names
them, and [HO02](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho02) cannot reach its end. Whether it goes into each entry of `porelo_tasks` or into a list of its own beside
it is for the analysis.

## Plan

Read in `ProjectInventoryCommand`, `Mail`, `TransportDirectory`, `doc/hosts.md` and porelo-message-matrix's
package names.

1. **A list of its own beside `porelo_tasks`**, since transports belong to the project and its tasks share the
   conversation: `porelo_transports: [{scheme, package}]`, from `project.mail().conversations()` and each peer's
   transport, once each, in file order; the package `porelo-message-transport-<scheme>` by default, as
   `agent_package` defaults. `porelo_inventory_version` stays 1, since a key is added.
2. **Docs:** the example and a bullet in `doc/hosts.md`.
3. **Red first:** `ProjectInventoryCommandTest.writesEachTransportTheProjectUsesWithItsPackage`, then
   `writesNoTransportsForAProjectWithoutMail`.

**Decided:**
- The homeserver package is left to porelo-host's play; the inventory names transports only.
- A machine file names no transport's package; the default `porelo-message-transport-<scheme>` holds.

## Acceptance

- `project inventory` on a project with a Matrix conversation lists `porelo-message-transport-matrix` under
  `porelo_transports`. Seen to fail first: the key is absent.
- A project without `mail` lists none, and the version stays 1.

## To be checked

- With porelo-host: that its play reads a list beside `porelo_tasks`.
