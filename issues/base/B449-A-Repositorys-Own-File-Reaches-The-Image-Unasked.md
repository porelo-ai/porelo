# B449 — A Repository's Own File Reaches The Image Unasked

```json
{
  "$schema": "issue-1.json",
  "id": "B449",
  "title": "A Repository's Own File Reaches The Image Unasked",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 20,
  "blockedBy": [],
  "covers": "A repository's `porelo.yml` chooses no line of an image without a person's yes."
}
```

**Why this size.** S to M.

**What must be true.** A repository's `porelo.yml` chooses no line of an image on a person's machine without their yes.

The operator's word: after the test, as an issue of its own. Built 2026-11-10 by Agent Ecosystems under Agent Core's
oversight, on the operator's word of that day.

## Found

Codex's reading of [B444](README.md#b444), and read in `ImageChoice.java`, whose own comment states the order. It is older
than that issue:

- In Default, a start in a checkout reads the checkout's `.porelo.yml` and `porelo.yml` with their snippets, whatever was
  answered: at a No, at `--no-input`, and with no plugin (`:148`).
- In any other project, it reads `porelo.yml` from the default branch of the mirror (`:181`), so an approved project takes
  new image lines from upstream without a new question.

## What Is Asked

- On the Default path and at a first start, a `porelo.yml` shows in the question and counts in the hash, and a No leaves
  it out.
- An approved project's changed upstream `porelo.yml` asks again.

**Decided:** the image keys join what a repository's own files ask: `image.from` when it differs from the project's,
each line the files add as `image.run[n]`, and each variable as `image.env.NAME`. The same allowance, hash and question
cover them, and a changed line asks again. "A No leaves it out" means the file is not used: at a No, at `--no-input` and
with nobody at a terminal the start is refused, as for its other settings, so no image is built that nobody answered
for. At a first start, a `.porelo.yml` already in the checkout is shown in the question and counts in the hash, since
the same yes allows it.

## As built

- **Default and first start:** `RepositoryOwnSettings.of` reads the checkout's image through one `checkoutImages`, which
  `ImageChoice.preview` uses too; `image.from` (when it differs from the project's), each `image.run[n]` and each
  `image.env.NAME` join `asked`, so `RepositoryAllowance`, the hash and the question cover them. At `ASK_NEW` a
  `.porelo.yml` already in the checkout is shown and counts in the hash; `takeRepository` checks the hash before it
  writes. A dry run and `porelo task prepare` refuse lines nobody allowed, as a start does.
- **An approved project's file from the mirror:** `RepositoryAllowance` is kept per project
  (`<project>/allowed/<repository>.json`, `default` as before); `ImageChoice.preview` and `of` throw
  `ImageChoice.NotAllowed` with what to allow and its hash for lines not allowed, before and after the fetch; the
  launch asks once with the same offer, and a No or nobody refuses. An organization's signed file is unchanged. The
  first start after the upgrade asks once (changelog and doc say so).
- **Over the daemon:** `CanStart` answers `SETTINGS_NOT_ALLOWED` with the lines and the hash; `AllowSettings` resolves the
  project and repository as `CanStart` does (`ImageChoice.allowIfUnchanged`).
- **Tests**, each seen red first: `RepositoryOwnSettingsTest`, `FirstStartTest`, `ImageChoiceTest`, `TaskRunCommandTest`,
  a daemon test; `task-repository-image.feature` asks at a terminal and is refused with `--no-input`.
- **Left, older or apart from this issue:** a `run_file` in a default checkout never starts ([B605](README.md#b605));
  egress domains in a first-start `.porelo.yml` are shown but not marked or counted in hosts; `takeRepository` reads the
  files and the hash separately, so a change made and undone between the two reads could pass; a stale mirror's file
  is asked before the fetch and may be asked again after it; at a real terminal a No at the first start asks a second
  question about `.porelo.yml`.

## To Check

Codex says these builds have the host's network. To be checked here, as it decides how much a `RUN` line can do.

**Answer: yes, with full outbound access, and the host's own services reachable.** `Podman.buildArguments` makes
`podman build --tag ... --file ... [--memory] [--cpu-period/--cpu-quota] [--no-cache | --build-arg LAYER_EPOCH] <dir>`
and passes no `--network`; `TaskRunner` and `Preparation` build through it. So podman's default for a rootless build
decides: `default_rootless_network_cmd`, `pasta` unless `containers.conf` says otherwise, and `porelo setup` sets
nothing there. Measured with podman 5.7.0, rootless and pasta, on the account `porelo setup` set up, with a `RUN` line
as a repository may write one:

| From the `RUN` line                         | `podman build` as Porelo calls it | `--network=none` |
|---------------------------------------------|-----------------------------------|------------------|
| DNS, `deb.debian.org`                       | resolves                          | fails            |
| TCP to `1.1.1.1:443`, `deb.debian.org:80`   | connects                          | fails            |
| A host service on `0.0.0.0`, as `host.containers.internal` | connects            | fails            |
| A host service on `127.0.0.1` only          | fails                             | fails            |

No egress rule of the task applies while the image is built; a `RUN` line reaches what the host reaches, and a
service of the host's that listens beyond loopback. Hence the image's lines are asked for like every other setting.
