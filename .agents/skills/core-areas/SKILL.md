---
name: core-areas
description: porelo's areas: the app-* modules, their dependency boundaries, daemon methods, contract parts and file locations, and who works in which. Use before changing an app-* module, its boundaries or a daemon method.
---

# core-areas

## Areas, and who works in which

The command line and the daemon's logic are modules by area, `app-<area>`, submodules of `apps/` beside `app`, each in the package `org.fuin.porelo.app`.

| Area | Module | Daemon methods | Contract part | File locations |
|---|---|---|---|---|
| shared ground: context, paths, records | `app-base`, `app-model` | - | `10-common` | `PoreloPaths` |
| agents and providers | `app-agents` | `AgentMethods` | `40-agent` | `AgentPaths` |
| the vault, credentials, grants | `app-vault` | `VaultMethods` | `30-vault` | `VaultPaths` |
| messages and their conversation | `app-messaging` | `MessagingMethods` | `45-messaging` | `MessagingPaths` |
| egress, the shield, clearance | `app-egress` | `EgressMethods` | `25-egress` | `EgressPaths` |
| tasks | `app-task` | `TaskMethods` | `15-task` | `TaskPaths` |
| projects, backups, clearing | `app-project` | `ProjectMethods` | `20-project` | `ProjectPaths` |
| the gate and review | `app-gate` | `GateMethods` | `35-gate` | `GatePaths` |
| the organization's commands: `org`, `project member\|repository\|address\|create-repository\|show`, `setup --organization` | `app-organization` | - | - | - |
| the command line, `doctor`, `setup` | `app` | `MachineMethods` | `50-machine` | - |

- **The modules' dependencies are the boundaries**: `base` depends on no area, `model` on `base` and `organization/git-tree`,
  `agents` on `base`; `vault`, `messaging` and `egress` on `base` and `model` and not on each other (`egress` also on
  `agents`); `task` on all of those and `organization/git-tree`; `project` on `task` and `messaging`; `gate` on `messaging`;
  `organization` on `base`, `model`, `organization/api` and `organization/git-tree`.
- **A test that finds the repository's root from its module goes up two levels from `apps/app`** -
  `Path.of("").toAbsolutePath().getParent().getParent()`, or `Path.of("..", "..")`. Moved under `apps/`, a test that
  went up one level searched `apps/` alone: `NullMarkedPackagesTest` and `RealAccountTest` still passed that way
  (measured), and only `AgentIsolationTest` failed. `porelo-release check-readmes` holds every
  module's `README.md`; the documentation site shows `doc/` only, so a README is read in the repository.
- **Each area registers its daemon methods in its `<Area>Methods` and describes them in its own contract file** under
  `wire/src/main/resources/varlink/org.fuin.porelo.Tasks1/`; `InterfaceDescriptionTest` holds each to its part.
- **File locations are per area** (`context.paths().messaging().mailbox(...)`); `PoreloPaths` keeps the roots.
- **The tests stay in `app`**, which shares the package with every area.
- **`vault clear` belongs to the `vault` command group but lives in the clearing area's module**, added where the
  command line is assembled, because it reaches transports and tasks, which the vault's area does not depend on;
  `ClearedItem` and `MachineDeployKey` sit in the common part of the contract, since project and vault both answer them.
- **An agent changing an area it does not own says so in the channel first**, and a change to `app-base` or
  `app-model` is always said there first.
