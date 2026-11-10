# B444 — The First Start Finds What A Repository Needs

```json
{
  "$schema": "issue-1.json",
  "id": "B444",
  "title": "The First Start Finds What A Repository Needs",
  "state": "Review",
  "size": "L",
  "priority": "Medium",
  "rank": 96,
  "blockedBy": [],
  "covers": "`task start` in a checkout with no `project.yml` finds what it needs, asks yes or no, writes the file and starts from it."
}
```

**Why this size.** the start, the daemon and a question at a terminal.

**What must be true.** `porelo task start` in a checkout with no `project.yml` runs the detection, says what it found and what
that allows, asks a short yes or no, writes `project.yml` where the person stands, and starts from it. Porelo does not commit it.
With no plugin installed or nothing recognized, the start goes as today and says so. Once a `project.yml` exists, Porelo does
not compare or update it.

## Why

A new person follows the guide, stands in their repository and runs `porelo task start`. They know nothing of
`porelo project init`. Today that start works in `default` with Porelo's settings, `plugins: []`, so a Maven repository gets
neither Maven nor Java nor Maven Central, and the agent cannot build there.

[B220](README.md#b220) and [B221](README.md#b221) cover the plugins and `project init`, not `task start`.

## Decided

- The result lands in `project.yml` in the directory the person is in, for them to commit and adapt.
- A short confirmation before it is written: what was found, what it would allow.
- `porelo*` brings the ecosystems, so the plugin is there on a machine installed the standard way.
- `--yes` or an unattended start: nothing is written and nothing opened. The start runs as today in Default, and says what was
  found and to start once without `--yes`.
- The window's *New work*: on this computer the same question, with `project.yml` in the checkout. On another machine the window
  uses what the repository holds and says if nothing is there; no plugin runs there.
- A repository of an organization's project: no detection. What the project sets holds; if something is missing, the start names
  the command for the Project Admin, `porelo project init ... --project ...`.
- No at the question: the start runs as today, nothing is kept, and the next start asks again.
- The question shows the packages and tools found and the network access it opens, so a repository decides nothing by itself.
  As built that holds for `project.yml` and the files it names; a repository's `porelo.yml` is not part of the question until
  [B449](README.md#b449), by the operator's word.
- A `project.yml` in a checkout this machine does not know gets the same yes or no at the start. Porelo keeps its hash locally and
  asks again when the file changes. It is an approval, with no comparison to what a plugin would say. A No or `--yes` starts as
  today. This holds only for the Default path: an organization's project takes its signed file, and a `project.yml` in its
  checkout decides nothing.
- The file's project name is `<repository>-project`. A project named like its repository is refused, because `OwnRepository` reads
  that repository as the project's own, where no task works.
- A name that is already another local project's, or `default`, is refused at the question, so a repository cannot take over a
  project this machine has.
- The writing path shares `ProjectProposal` and passes the name through its `project` parameter. `project init` without a name
  takes the same `<repository>-project`, from one helper there; it printed `proposed` until the operator's first try showed it.

## What Is New In The Start

A start reads no `project.yml` from the checkout today. `TaskRunCommand` takes a project by name, from `-p` or `WorkOrigin`, else
`default`, and `ProjectSource` finds its file by that name: the organization's judged clone, one written under the configuration
directory, or one this machine recorded. So both the question and starting from the file are new here, and they go before
`ProjectTaking`. An approved file becomes a recorded project of this machine.

## The Daemon's Interface

For an interface on the person's own computer, which then starts as usual by the project's name:

- `Tasks1.FirstStart(checkout: string) -> (outcome: string, found: []Finding, hosts: []string, file: string, project: string, hash: string)`.
  `outcome` is one of `ASK_NEW` (nothing is configured and something was recognized), `ASK_UNKNOWN_FILE` (a `project.yml` this
  machine has not approved), `KNOWN` (nothing to ask), `NOT_DEFAULT` (a repository of an organization's project), `NO_PLUGIN` or
  `NOTHING_RECOGNIZED`. `file` is the text that would be or was written, and `hash` is what the answer names.
- `Finding` is `(what: string, value: string, source: string)`: a package or tool, and the file it was read from.
- `Tasks1.ConfirmFirstStart(checkout: string, hash: string) -> (project: string)` writes the file if it is new, keeps its hash and
  records the project, and refuses a hash that no longer matches. A No has no call: nothing is kept.

## As built

- `FirstStart` in `app-task` answers one question for both the terminal (`FirstStartAtTerminal`) and the daemon.
  `ProjectProposal` and the detection moved to `app-model` (`EcosystemDetector`).
- **This machine's copy is what starts.** At the question, `StagedProject` reads the checkout's `project.yml` and each file it
  names once, into `$XDG_CONFIG_HOME/porelo/projects/.staging-*`; only that is parsed, shown and hashed. The hash covers
  each file with its relative name. A named file outside the file's directory, a link on the way to a file, or one over
  1 MiB is refused. A yes moves the copy to `projects/NAME/`, which `ProjectSource` answers as `RECORDED`, and the
  checkout's path is not registered, so `-p NAME` and the daemon's `Start` read the copy. A new file also goes into the
  checkout, and is not what starts. A stopped process can leave a `.staging-*` behind: owner-only, and no project reads it.
- **Same file, same project.** `ProjectApprovals` keeps each project's upstream: the checkout's origin, or its own path when
  it has no usable one. A checkout whose upstream and hash match uses the project with no question: a second clone, or a
  git worktree. A changed file from that upstream asks again, and a yes replaces the copy. Another upstream, or a name
  that is another local project's, is `NAME_TAKEN` and goes on in `default`, saying so. The name is checked again at the yes.
  A second checkout with no usable origin is therefore not the same project.
- `WorkOrigin.placedNaming` finds an organization's project first, then an approved one whose upstream this is. A project
  this machine merely ran is not found by origin. After a No, `WorkOrigin.withoutApproved` goes to `default` and not to the
  copy the person just did not agree to.
- A question on a file already there prints the summary, then the whole file and each named file, made printable. The
  reply's `file` carries the same text, under `--- <name> ---` lines. A file Porelo generates holds no `mail`, `builds` or
  `credentials`, and an online class, which only a host's own forge as origin gives, is listed.
- The reply of `FirstStart` gained `marked: []string`, the hosts only the checkout names, in no curated set, and a yes is
  no default for them. `FirstStartRefused(reason, detail)` is the named refusal of both methods.
- A checkout with no usable `origin` writes its path as the repository's upstream, as `default` does, and is found by its
  file.
- Not built: a start in an organization's repository does not name the `porelo project init ... --project ...` command
  for what is missing, since it runs no detection there to know that.
- **The approval's comparison** is `RepositoryAddress.sameUpstream`: host, port and path, with a local path never equal to a
  remote address; ssh and https of one host and path stay equal. `same`, which drops the port, stays as it was for its
  other callers. A person's own `git remote` setting (`insteadOf`, a differing push URL) is trusted, as `.git/config` is not
  committed, and the start still uses the stored copy.
- **One yes at a time.** A file lock per project, `projects/.approving-NAME.lock`, is held over the check, the install and the
  record, for the terminal and the daemon alike. The record is written first and made true by the last rename; if that
  fails, the old copy, the old record and a new file's place in the checkout are put back.
- **What a person put there stays.** A reapproval drops the files the old copy was approved with and keeps what else lies in
  `projects/NAME/`, such as `<repository>/.porelo.yml`. The hash covers only the project's file and what it names, so such a
  file asks nothing.
- `project.name: 123` is `NO_NAME` at the look, as the reader takes it and a start by name does not. The reader is unchanged.
- **Not in the question: a repository's `porelo.yml`.** The question covers `project.yml` and the files it names. `porelo.yml`
  is read as `ImageChoice` says: in `default` from the person's checkout, also after a No; for an approved project from the
  mirror's default branch, also after a yes, ahead of the copy's own `image`. So "a repository decides nothing by itself"
  and "a `project.yml` ... decides nothing" in Decided hold for `project.yml` only. Whether `porelo.yml` should be part of
  it is a question for the operator.
- Known limit: a process killed between the two renames of a reapproval leaves the old copy as `projects/.replaced-*` and no
  copy in its place. A start in that moment also finds no file. Both are put right by asking again, but the old copy is not
  moved back by itself.
- Known limit: a parent directory swapped for a link while the question waits can redirect a read. That needs a process
  of the same account, as the socket race in [security](../../doc/security.md) does, so it is left.
- Known limit: `porelo project remove` leaves the approval's record. It names no project a start would find, but if that
  name is later written by hand, a yes from the recorded upstream may replace that file.

A last reading added two things: a port is compared by its number, so `022` and `22` are one; and a file a new copy names where
a file of the machine's own stands, with other content, refuses the yes in words, as otherwise it would replace that file.

## What The Plugin Must Answer

What it found with its source, and each host, as `Detection` holds them.
