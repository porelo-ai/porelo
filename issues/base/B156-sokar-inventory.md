# B156 — sokar inventory

**Status:** Estimated; blocked by sokar [B148](README.md#b148).
**Size:** M - one command and its daemon method over records others write; the models need [B148](README.md#b148)'s record first.

**What must be true.** `sokar inventory` lists the agents, providers, models, credentials and their last use on this
machine, the account it describes and the projects each credential serves, also as JSON for central collection; the
register itself stays the organisation's.

## Why

An organisation asked to keep a register of its AI use has to collect it from each machine.

**Guideline points it answers:** BSI catalogue 2.1; NCSC asset management; Acalvio 1. From a review of security guidelines, 2026-10-09.

## The shape

Built from [B144](README.md#b144), [B148](README.md#b148) and [B154](README.md#b154); human and JSON output.

## Plan

Each part already has its source; the command only gathers them:

- agents: the installed agents and their versions, as `sokar agents` reads them (`apps/app-agents/.../
  AgentsCommand.java`, `agents.all()`);
- providers: as `sokar providers` reads them (`ProvidersCommand`), with the endpoint each points at;
- credentials: names, kinds and providers as `sokar credentials list` reads them (`CredentialsListCommand`, the
  credential registry in `VaultPaths.credentialRegistry()`), never a value; device slots with their last use
  (`Keyslot.lastUsed`); deploy keys (`DeployKeys`);
- the projects each credential serves: for a deploy key, the project it was made for (`DeployKeys.Key`); for a
  provider's credential, the projects whose tasks were started with it (`--credential`, `TaskProfile`), and those
  whose `project.yml` allows its provider once [B151](README.md#b151) is built;
- the account: the OS user the inventory describes, beside the node id, so a machine with several Sokar accounts
  gives one inventory per account that a collector tells apart;
- last use of a credential: the `CredentialUse` record [B153](README.md#b153) adds, written where the broker and the gate lend one;
- models: the model each provider answered with, from [B148](README.md#b148)'s per-task record, gathered over the tasks still on the
  machine and over [B154](README.md#b154)'s event log for those gone.

Steps:

1. Red first, in `apps/app`: a machine with one stub agent, one provider, one credential and one project that uses
   it; `sokar inventory --json` is an unknown command today; afterwards it names each with its last use, the account,
   and the project under the credential, and no secret value is in the output.
2. `Inventory` in `app-model`: one list built once, rendered as text and as JSON (as `DoctorCommand` does with
   `--json`), with `version`, `node` (the node id of `NodeIdentity`), `account` (the OS user) and `collected`
   (when), so a central collector tells machines and accounts apart; each credential carries `projects`, the names
   of the projects it serves.
3. `InventoryCommand` in `apps/app`, registered in `SokarCli`; `--json` and `--since`.
4. The daemon's `Inventory()` in `50-machine.varlink`, from the same `Inventory`; `InterfaceDescriptionTest`.
5. `doc/commands.md` gives the shape; `doc/corporate-security.md` says how an organisation collects it.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The output names the account, and under each credential the projects it serves; a credential no project uses says
  so.
- The documentation page that describes the area says it.

## To be checked

- Whether a provider's credential serving a project is best read from the project's allowed providers
  ([B151](README.md#b151)) or from the tasks that used it, or both (Agent Core's).
- Whether the models of tasks already removed count, which needs [B154](README.md#b154)'s log to carry the model (Agent Core's).
