# B222 — A Task's Tool Settings Written At Its Start

**Status:** Analyzed; blocked by [B220](README.md#b220) and [B219](README.md#b219).
**Size:** M - one hook in the task start beside the agent's files, its refusals and tests, and an acceptance step with
the stub plugin; no new contract beyond [B220](README.md#b220)'s.

**What must be true.** A task's start asks the build-tool plugins to `configure` it and places the settings they write
- Maven's `settings.xml`, an `.npmrc`, a `pip.conf` - into the container, so the tool reaches a private repository
through the broker with no credential in the task. Porelo's share of
[PJ52](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj52).

## Plan

Read in the code on 2026-10-20 (`apps/app-task`).

1. **Where**: `TaskLaunch` places the agent's files at `:1424` (`placeAgentFiles`, `:1869`), after the container,
   the relay and the workspace are up. A `ToolSettings.place(project, container, runner)` runs right after it, only
   when the project declares a repository under [B219](README.md#b219)'s key; a project without one starts exactly as
   today.
2. **What a plugin is given**: each declared repository's name and its URL inside the task ([B219](README.md#b219)'s
   `http://127.0.0.1:9420/<name>/`), nothing else - no workspace, which is never read from the host, and no
   credential, which the plugin never holds.
3. **What comes back**: files relative to the task user's home (`/home/agent`, `Containerfile`'s `useradd`), made
   absolute and checked by `ContainerFile`'s own rules; a path leaving the home, or one the agent's files already
   took, is refused naming both plugins or the plugin and the agent. Placed with `TaskRunner.place` (`:561`), as the
   agent's are: owned by `agent`, `0600` when the plugin says owner-only.
4. **Fail closed**: a plugin that fails or times out stops the start with its name and its answer; a start never
   goes on with some tools configured and others not. The start prints one line per file placed, with the plugin's
   name.
5. **Resume**: `TaskControl.resumeHeld` reuses the container and places nothing again (`:680`), so the files are
   there; a start after a reboot (`saved.restore`) gets them as the agent's files get them.
6. `doc/credentials.md`'s section from [B219](README.md#b219) says where the settings come from.

**Red first:** `ToolSettingsTest` with a fake plugin: its file lands at the absolute path under the home; a file
outside the home and a file colliding with an agent's are refused; a failing plugin stops the start with nothing
placed.

## Acceptance

- In a task of a project declaring a repository, with the stub plugin installed, the stub's settings file is in the
  task user's home with the broker's URL in it and no credential.
- A project declaring no repository asks no plugin.
- A plugin that fails stops the start, named.

## To be checked

- Whether `saved.restore` after a reboot recreates the container, and so whether the agent's files and these are
  placed again there or survive with it.
