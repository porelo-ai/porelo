# B444 — The First Start Finds What A Repository Needs

**Status:** Implementing.
**Size:** L - the start, the daemon and a question at a terminal.

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
- A `project.yml` in a checkout this machine does not know gets the same yes or no at the start. Porelo keeps its hash locally and
  asks again when the file changes. It is an approval, with no comparison to what a plugin would say. A No or `--yes` starts as
  today. This holds only for the Default path: an organization's project takes its signed file, and a `project.yml` in its
  checkout decides nothing.
- The file's project name is `<repository>-project`. A project named like its repository is refused, because `OwnRepository` reads
  that repository as the project's own, where no task works.
- A name that is already another local project's, or `default`, is refused at the question, so a repository cannot take over a
  project this machine has.
- The writing path shares `ProjectProposal` and passes the name through its `project` parameter. Its own default for no name,
  `proposed` (`draft` for a repository of that name), stays for `project init`, whose tests and readers hold it.

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

## What The Plugin Must Answer

What it found with its source, and each host, as `Detection` holds them.
