# B443 — What A Task's Image Was Built With

**Status:** Implementing.
**Size:** M - a record at build, a field over the daemon, and `project show` for Default.

**What must be true.** The daemon answers for a task which settings its image was built with: base image, packages and tools,
the agent and its version, the security class, and where each comes from, `project.yml` or Porelo's settings for Default. An
interface can show that from one call, and it is what the image was built with, not what the settings say now.

## Why

A person looking at a task wants to know whether Maven and Java are in it. Today they can only read the image with `podman`.

Read in the code, what the daemon gives today:

- For a task: its `securityClass`, its `agent` and its `commit`, and `imageDrift` when the image changed since the container
  was created. It gives no base image, no packages or tools, no agent version, and no source for any of them.
- The image's name, only from the prepare call.
- The image holds the label `org.fuin.porelo.recipe`, a fingerprint of the recipe (`Containerfile`). It tells two recipes apart
  and cannot be read back, so what went in is lost after the build.

So the project's file answers only for what it says now, which may not be what the image was built from. The recipe has to be
recorded at build, on the image or beside it, and an image built before that has to say it does not know.

## `porelo project show default`

It answers "no such project", while `project list` lists Default and `project default` shows its repositories: `ProjectShowCommand`
looks in the organization and the machine's own project files, and Default is in neither. Fix it here: answer Default from
Porelo's settings, its organization fields `null` as for a project of this machine alone, since an interface asks where
the settings come from by that call.

## Plan

After the next test. The window's button is its own issue in its repository.
