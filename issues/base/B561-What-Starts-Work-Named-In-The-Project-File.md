# [B561](README.md#b561) — What Starts Work Named In The Project File

**Status:** Backlog. Filed 2026-11-09 as porelo's part of `porelo-project` ST294, which the operator accepted on
2026-11-09; nothing is built before the operator's word.
**Size:** S - one section more in the project file's model, its schema, its check and its page; one module.
**Blocked by:** -.

**What must be true.** The signed project file chooses the project's planning adapter and says which working states
start an agent's work, and nothing else may say it:

```yaml
planning:
  adapter: stories-git
  credential:                            # what porelod hands the adapter as the token of each call
    kind: github-app                     # or token: the vault entry's value as it is
    entry: porelo-project-planning-app   # the vault entry; for github-app the app's private key
    app: 123456                          # github-app only: the app's id
    installation: 7890123                # github-app only: its installation on the store's owner
  settings: { repository: porelo-project, stories: stories }   # passed to the adapter unread
  work:
    In analysis:
      from: [Accepted]
      done: Analyzed
      per: story
      modes: [UNATTENDED, AGENT]
      order: "Analyse {story}. When done, end with a last line 'Outcome: done' or 'Outcome: blocked - <why>'."
      model: { anthropic: claude-opus-5-5 }
  coordinator:
    order: "Oversee {story} and its tasks {tasks}."
```

A file whose `planning` is malformed is refused with the key and why. A repository's `porelo.yml` cannot set it.
The state names are the adapter's, from its one workflow of stories and issues (`stories/workflow.yml` for
stories-git); they are checked against it where porelod asks it ([B562](README.md#b562)), not here. A working state,
a key of `work`, is entered only by starting its work, never by a move ([B564](README.md#b564)).

## Plan

Read in `core/project/file` (`Mcp`, `Sections`, `ProjectFile`, `ProjectFileReader`, `ProjectFileWriter`,
`ProjectFileCheck`), `core/project/ProjectSchema`, `Project`, `ModelDefault`.

- New `core/project/file/Planning.java` (adapter, credential as `PlanningCredential` (kind `GITHUB_APP | TOKEN`,
  entry, app, installation), settings as an unread map, work, coordinator) and
  `PlanningWork.java` (from, done, per `STORY | REPOSITORY`, modes `UNATTENDED` and `AGENT` (porelo's names: the operator working
  with the agent in its own session is `AGENT`; `SHELL`, a terminal with no agent, is refused there), order,
  model by provider), on the pattern of `Mcp`.
- `Sections`: `planning` is the project file's alone, refused in `porelo.yml` as `mcp` is; its keys listed.
- `ProjectFile` gains `planning`; `ProjectFileReader` and `ProjectFileWriter` read and write it; `Project` exposes it.
- `ProjectFileCheck`: adapter a plain name; `credential.kind` one of the two, `entry` a vault entry's name, `app`
  and `installation` whole numbers, required for `github-app` and refused for `token`; `from` not empty and not naming the state itself; `done` not one of
  `from` nor the state itself; `per` and `modes` from their sets; `order` not empty, with no placeholder but
  `{story}`, `{issues}`, `{tasks}`; a model one word per provider; `settings` bounded in size as
  `extensions.<adapter>` is.
- `ProjectSchema` and `doc/project-file.md`: the section, and that a state name is the adapter's.
- Red first: `ProjectFileReaderTest` reads the section above into its records; today the key is refused as unknown.

## Acceptance

- The section reads, writes back unchanged and is answered by `Project`; each malformed key is refused by name; the
  same section in a `porelo.yml` is refused.

