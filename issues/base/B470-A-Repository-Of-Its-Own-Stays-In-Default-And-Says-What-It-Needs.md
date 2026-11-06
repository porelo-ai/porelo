# B470 — A Repository Of Its Own Stays In Default And Says What It Needs

**Status:** Implementing.
**Size:** L - the repository file's form and the merge in `core`, the image rule and the sources in `app-model`, the
allowance and two daemon calls, the judging of `porelo.yml` in an organization's project, the first start's draft;
built in four steps, each safe on its own. Estimate 180 minutes for the whole, from today's actuals against
estimates.

**What must be true.** A checkout with no project of an organization goes into `default`, and no start writes a
`project.yml` for it. The repository's own `porelo.yml` says what its task needs, in the project file's form less what
defines a project (name, organization, repositories, members): hosts a task reaches, credentials by name and kind (never
a value), tool repositories and `extensions.<adapter>`. A first start's detection writes `porelo.yml`, not `project.yml`.

`porelo.yml` adds to the project file's defaults: mappings merge key by key; lists take the project's entries, then the
repository's; a single value the repository sets wins. `null` removes a key the project set; in a list a quoted
`"!entry"` removes that inherited entry and `"!*"` every inherited one, and an unquoted `!` is refused, naming the
quotes. A start and its dry run name each value's source - the project, the repository or Porelo's default - and what
was removed.

In `default` the person allows a repository's settings once on this machine, kept outside the repository and bound to
what was asked; a change asks again for what is new only. In a project of an organization, `porelo.yml` is signed by
the project admin or by a developer with access to the repository, and the follow checks every commit that changes it.

The decision is recorded in porelo-project's `doc/decisions.md`, "A repository of its own stays in Default and says what
it needs" (operator, 2026-11-06). It replaces the image rule "first whole file wins, nothing mixed", and
[B467](README.md#b467) folds into it: a task's tool repositories live in `porelo.yml`, with the project file as their
default.

## Found

- `porelo.yml` today holds `image` alone (`RepositoryFile`, `Sections.REPOSITORY`); any other key is refused by name.
  `ImageChoice` takes the first whole source - local override, the repository's file, the project's `image` - and mixes
  nothing. In `default` it reads the checkout's `.porelo.yml` and `porelo.yml` unasked, at a No and at `--no-input`
  ([B449](README.md#b449)); in another project it reads the mirror's default branch, unasked after the project's
  approval.
- A first start with a plugin writes a `project.yml` into the checkout and approves it as a project of this machine
  alone (`FirstStart`, `ProjectApprovals`); [B469](README.md#b469) taught every task command to find such a checkout.
- An organization's `project.yml` is judged at each start by its newest change: signed by a project admin's key valid
  at that commit's date. `porelo.yml` in a repository is read from reviewed work and ranked dangerous by the review, but
  no signature is asked of it.

## Plan

- **The form.** `porelo.yml` takes the project file's sections less what defines a project: `project`, `repositories`
  and the organization's members stay the project's; `image`, `build`, `egress`, `limits`, `mail`, `credentials`,
  `builds`, `parameters`, `branch`, `base_branch`, and from [B467](README.md#b467) `tool_repositories` and `extensions` are allowed.
  `.porelo.yml`, the local override, has the same form.
- **The merge, on the YAML tree before it is bound**, so the project file's own checks judge the result: mappings merge
  key by key; a list is the project's entries, then the repository's; a single value the repository sets wins. `null`
  or `~` removes a key the project set. In a list a quoted `"!entry"` removes that inherited entry, `"!*"` every one; an
  unquoted `!` is refused at its line, naming the quotes; a `"!entry"` matching nothing is refused, naming it. The order
  is the project's file, then `porelo.yml`, then `.porelo.yml`. The image's whole-file rule goes.
- **Sources.** Each value keeps where it came from: the project, `porelo.yml of <repository>`, the local override, or
  Porelo's default; a removed entry keeps what removed it. The start and its dry run print them as the image lines do
  today, and `removed` lines name what went.
- **Default: the allowance.** A repository's settings are allowed once on this machine, kept under
  `$XDG_STATE_HOME/porelo/default/allowed/<repository>.json`: the repository's upstream and checkout, and the merged
  values allowed, each with its source. A start compares: nothing new starts; anything new, changed or removed is asked
  at a terminal, showing only that, and refused without one, naming `porelo task start` at a terminal. A yes stores the
  new whole. Daemon: `CanStart` answers `SETTINGS_NOT_ALLOWED` with `settingsToAllow` and `settingsHash` (SHA-256 over
  exactly what was shown), and `AllowSettings(repository, hash)` answers `ALLOWED`, or `CHANGED` and allows nothing
  when the file moved since.
- **An organization's project: the signature.** `porelo.yml` at the mirror's default branch is taken only when every
  commit that changed it since the last one taken is signed by a project admin or a developer with access to that
  repository, valid at the commit's date; the last taken commit is kept per repository. Otherwise the start refuses with
  `SETTINGS_UNSIGNED`, naming the file and the commit. Until a repository's file was ever taken, the first commit judged
  is its newest change, as for `project.yml`.
- **The first start** writes `porelo.yml`, not `project.yml`, and stays in `default`; `FirstStart` keeps its shape with
  `project` `default` and gains `fileName`. A yes is also the allowance of what it wrote.
- **Checkouts that already got a `project.yml`** stay projects of this machine and start as today. A start there says
  once that `porelo.yml` keeps such a repository in `default`; nothing is converted unasked.
- **Steps, each safe on its own:** (1) the merge on the YAML tree with each value's source, and the image added key by
  key; removals and the keys beyond `image` reach a start with (2), every key beyond `image` still refused until allowed or signed,
  so nothing widens before (2) and (3); (2) the allowance in `default` with the two calls; (3) the signature
  in an organization's project; (4) the first start's draft, `fileName`, and the note in old checkouts. The tool
  repositories of [B467](README.md#b467) follow as their own issue on top, once the settings reach a start.
- **Red first:** a merge test per rule (map, list, value, `null`, `"!entry"`, `"!*"`, unquoted `!`, a removal matching
  nothing); a dry run naming each source; a default start refused on a new egress host until allowed, and asking only
  the changed part; an organization's start refused on an unsigned commit among several; a first start writing
  `porelo.yml`.

## Needless or folded

- [B467](README.md#b467): its section moves into `porelo.yml`; it stays in Backlog and is reworked on top of this.
- [B449](README.md#b449): the allowance in `default` and the signature elsewhere are its answer; it folds in here.
- [B221](README.md#b221) and [B444](README.md#b444) (in Review): the draft they built now targets `porelo.yml`; step
  (4) changes it, so neither needs more work of its own.
