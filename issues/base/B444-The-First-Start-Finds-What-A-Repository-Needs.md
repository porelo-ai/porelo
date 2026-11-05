# B444 — The First Start Finds What A Repository Needs

**Status:** Backlog.
**Size:** - until the open questions are answered.

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

## Open

What the plugin's answer must hold for the question: what it found, with its source, and each host. The plugins' repository
proposes it.
