# B487 — The Organization Adapter Apart From Porelo

```json
{
  "$schema": "issue-1.json",
  "id": "B487",
  "title": "The Organization Adapter Apart From Porelo",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 8,
  "blockedBy": [],
  "covers": "Everything about the organization and the source of its projects moves out of `porelo` into an organization adapter, an executable of its own asked across a process boundary; `porelo` keeps tasks, the machine, code repositories, a cache of project settings, and `default` and hand-written projects."
}
```

**What must be true.** `porelo` checks only what it must check in repositories - whether a code repository's commits are
signed by whom the project allows, and a repository's `porelo.yml` - and works on the machine it runs on. Everything about
the organization and about the source of an organization's projects is done by an organization adapter: an executable of
its own, with a command line of its own, installed on the machine where `porelo` runs. `porelo` asks it across a process
boundary. Trusting an adapter is the user's choice, which Porelo cannot check; Porelo vouches for its own Git
implementation.

## Why

How an organization is kept is one implementation among possible others. The API that keeps the other way open exists
(`organization/api`, `organization/contract`, the Git adapter `organization/git-tree`), but the adapter is linked into
`porelo`, and the organization's commands are `porelo`'s own. A second implementation would have to ship inside `porelo`.

## The shape

### The parts

| Part | What it holds | License |
|---|---|---|
| `organization/api`, the contract | Questions and answers as plain data, among them the type of a project's settings | Apache-2.0 |
| `organization/contract` | The checks any adapter runs against itself ([B401](README.md#b401) adds to them) | Apache-2.0 |
| A library for `project.yml` | Reading and writing the YAML file into the settings type | Apache-2.0 |
| `porelo` | Tasks, the machine, code repositories; the cache of project settings; `default` and hand-written projects | as today |
| The Git adapter | The organization's repository, the signed `project.yml` in a project's repository, cards, roles, pins | as today |

The Git adapter is first a module of its own in this repository, built as its own executable and package. Today it is
spread over three modules: `organization/git-tree`, the organization's commands in `apps/app-organization`, and the
identity and card commands in `apps/app-vault`.

### The commands

| Area | Commands | Belongs to | Where it acts |
|---|---|---|---|
| Tasks | `task`, `talk`, `panic` | `porelo` | this machine; `task start --host` on another host, over ssh |
| Tasks | `gate` | `porelo` | this machine; `gate approve` passes work to the code repository's forge (central or the host's own) |
| Tasks | `approve` | `porelo` | a checkout on this machine |
| Machine | `setup`, `doctor`, `daemon`, `events`, `prune`, `tighten`, `keep`, `clear`, `completion` | `porelo` | this machine |
| Machine | `setup --organization` | adapter | points this machine at an organization once it answers |
| Machine | `host whoami` | `porelo` | this machine |
| Machine | `host unlock` | `porelo` | a host's vault, with a key from this machine's ssh agent |
| Credentials | `vault`, `credentials`, `shield`, `providers` | `porelo` | this machine |
| Installed | `agents`, `ecosystems` | `porelo` | this machine |
| Code repositories | `commit`, `commit verify` | `porelo` | a host: judges the commits a push brings to its forge |
| Code repositories | `build status` | `porelo` | the code repository's forge, reading only |
| Code repositories | `project sign-repository-file` | `porelo` | a code repository (`porelo.yml`) |
| Projects | `project default`, `remove`, `clear`, `list` and `show` (this machine's part) | `porelo` | this machine |
| Projects | `project init` | adapter | reads a checkout on this machine; with `--project --yes` writes to the project's repository |
| Projects | `project create-repository` (its old name `open`) | adapter | the forge: creates the project's repository with its first signed commit, and files its address in the organization's repository |
| Projects | `project sign` | adapter | the project's repository (`project.yml`) |
| Projects | `project verify` | adapter | a host, at each follow; reads a copy of the project's repository, with no network |
| Projects | `project address`, `member` | adapter | the organization's repository |
| Projects | `project repository`, `review` | adapter | shows the entry a project admin writes; writes nothing |
| Projects | `project list` and `show` (the organization's part) | adapter | the organization's repository, reading only |
| Organization | `org create`, `owner`, `admin`, `person`, `project`, `host` | adapter | the organization's repository, writing |
| Organization | `org host refresh` | adapter | a host: hands it the organization's forge token over pinned ssh |
| Organization | `org fetch`, `signers` | adapter | a host: reads the organization's repository and writes the host's state |
| Organization | `org add-read-key`, `remove-read-key` | adapter | the forge: a host's deploy key |
| Organization | `org card`, `host-card` | adapter | the organization's repository, reading only |
| Cards and keys | `identity propose`, `show`, `keys` | adapter | this machine (ssh agent) and the forge, reading only |
| Cards and keys | `identity create`, `rotate`, `renew`, `backup-key` | adapter | signs with this machine's ssh agent and files the card in the organization's repository |
| Cards and keys | `identity revoke` | adapter | this machine; files nothing, prints the new card |
| Cards and keys | `card verify`, `verify-host` | adapter | this machine, reading only |
| Cards and keys | `card make-host` | adapter | this machine; prints the host card, signed by the host admin's key |

`porelo` still uses the key in the person's ssh agent to certify a task, and asks the adapter whether the card for it
holds. `project list` and `show` join two sources: what the adapter says of the organization, and what this machine has.

`project address` changes the organization's entry, `projects/<project>/repository.yml` in the organization's
repository, by a signed commit; it refuses a project whose address `project create-repository` has not filed yet. Its
help's "a project that is opened" uses the command's old name.

### A project's settings on a machine

| Project | Source | Who judges | Where `porelo` keeps it |
|---|---|---|---|
| An organization's project | the adapter; for the Git adapter, the signed `project.yml` in the project's repository | the adapter | `porelo`'s cache, with revision and time of judging; renewed by a call at a start |
| `default` | `porelo` itself; `project default` adds repositories | nobody | this machine's `project.yml`, through the shared library |
| A hand-written project | the person, under `$XDG_CONFIG_HOME/porelo/projects/NAME/` | nobody | the same file, through the shared library |

`porelo` checks a repository's `porelo.yml` itself, against these settings. An adapter other than Git need not use the
library: it answers with the settings type.

### What `porelo` asks the adapter

- A project's judged settings: its repositories with their central addresses, egress, images, agents, the ecosystems'
  settings, whether a review is required.
- Who may sign in a project, which keys are revoked, which role a person holds.
- Whether the card for the key in the person's ssh agent holds.

`IdentitySource`, the one thing a store asks back, does not cross the boundary: the forge's key list that answers it
(`HttpForgeDirectory`) and every caller of it (the store, `OrgAccess`, the identity commands) are all the adapter's.

### What moves, read in the code

- **Into the adapter:** `organization/git-tree` whole, `TreeWalk` and `GitTreeWrites` among it (both used only by the
  store and the adapter's commands: `OrgWriting`, `OrgCreateCheck`, `IdentityOrganization`); `apps/app-organization`
  but `EcosystemsCommand`, `ProjectSignRepositoryFileCommand` and this machine's part of `project list` and `show`;
  from `apps/app-vault` the `identity` and `card` commands with `IdentityOrganization`, `IdentityChange` and
  `IdentityFacts`.
- **Stays with `porelo`, out of `git-tree`:** `RepositoryFileJudge`, which judges a repository's `porelo.yml`
  (`RepositorySettingsSignature` in `app-model`); `FetchAccess` and `ForgeAddress`, which `porelo` uses for its own
  fetches too (`OrganizationAccess` in `app-model`, `HostForgeAccess` in `app-task`).
- **Replaced by a call and the cache:** `ProjectTaking` in `app-task` reads and judges `project.yml` at its source
  itself, through `ProjectFile` and `CommitSigning`; a start asks the adapter instead.
- `app-model`, `app-task` and `app-vault` then no longer depend on `porelo-organization-git-tree`.

### Who else is touched

Read in their code; the coordinator asks their agents.

- `porelo-frontend`: the organization page, the project pages and *My key and card* call `porelo` with `org ...`,
  `project address|member|repository|review|init|create-repository|list|show` and `identity ...`
  (`lib/src/app/porelo_organization_source.dart`, `organization_source.dart`, `identity.dart`, `key_recovery.dart`,
  `forge_key_check.dart`, `host_access.dart`, `porelo_app.dart`, with their tests); they call the adapter instead, and
  `project list` and `show` keep both parts. The daemon's contract has no organization method, so nothing over varlink
  changes.
- `porelo-host`: `roles/porelo/files/porelo-host-person` calls `org fetch`, `org signers`, `org add-read-key` and
  `project verify` on the host, and its tests stand in for `org`; they call the adapter, and a project's root-owned
  file on a host comes from the adapter's answer.
- `porelo-ecosystems`: an ecosystem adapter receives its settings from the settings type, through `porelo`, in place
  of reading `project.yml`.
- `porelo-claude-code`: its acceptance calls `project list` only, which stays.

### Issues of this repository it changes

- Their work moves into the adapter, the issue unchanged otherwise: [B359](README.md#b359), [B360](README.md#b360),
  [B401](README.md#b401), [B405](README.md#b405), [B406](README.md#b406), [B407](README.md#b407),
  [B408](README.md#b408), [B414](README.md#b414), [B416](README.md#b416), [B427](README.md#b427),
  [B465](README.md#b465), [B475](README.md#b475) and [B476](README.md#b476).
- [B198](README.md#b198): settings inherited down the organization's tree are the adapter's answer in the settings
  type, no longer read by `ProjectTaking`.
- [B437](README.md#b437): its fast answer becomes the adapter's, and a start, a gate and a listing read `porelo`'s
  cache; much of it is this issue's cache.
- None is made needless by reading.

## Decision

`doc/decisions.md`, *The organization adapter is an executable of its own*, holds what this builds and the exposure it
accepts.

## Acceptance

- `porelo` holds no command that reads or writes the organization's repository or a project's `project.yml` at its
  source; each command in the table runs where the table says.
- `porelo` links no module of the Git adapter; it reaches the adapter only through the contract, and a start, a gate and
  a listing work from its cache of settings.
- The Git adapter passes `organization/contract`, run as its own executable across the boundary.
- `default` and a hand-written project work with no adapter installed.
- The documents say which adapter a machine uses, how the person chooses it, and that its answers decide whom Porelo
  believes.

## To be checked

- How it splits into issues of L or smaller.
