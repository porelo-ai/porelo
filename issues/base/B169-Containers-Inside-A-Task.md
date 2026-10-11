# B169 — Containers Inside A Task

**Status:** Analyzed.
**Size:** L - an assessment to write and measure first, which decides whether anything is built; the build after it
touches the task's hardening, the image, the project file, `doctor` and the acceptance suite.

**What must be true.** A build inside a task can start containers of its own - rootless podman in the task - and they
are held by the same firewall and resolver as the task. Before it is built, an assessment says whether Sokar's
guarantees still hold.

## Why

Many Java builds start containers for their tests, and such a build cannot run in a task today. Comparable tools allow
containers inside their sandbox.

## The shape

- First the assessment: what nested user namespaces open, whether a nested container can leave the task's network
  namespace or reach the host's, what it means for seccomp and capabilities, and whether a kernel of the task's own
  ([B59](README.md#b59)) is the better ground.
- Only then the build: opt-in per project, said by `--dry-run` and `sokar doctor`, off by default.

## Plan

Today a task runs with every capability dropped and no new privileges (`runtime/.../ContainerSpec.java`,
`--cap-drop`, `--security-opt`), under the default seccomp profile, so `unshare -U` and `newuidmap` fail inside it.
The firewall and the resolver live in the task's network namespace (`shield/.../EgressPolicy.java`, nft rules entered
with `nsenter --net`), so a nested container in that same namespace is held by them; one with a namespace of its own
is not.

1. **The assessment, measured on the acceptance machines (both legs), written as a section of `doc/decisions.md`**,
   one row per guarantee of `doc/reach.md` (reach, hold, spend, produce):
   - what a task needs to start a rootless nested container: user namespaces (`unshare`, a seccomp profile allowing
     it), `/dev/fuse` or the vfs driver, `newuidmap` with a subordinate range, and no `--cap-drop` of what those need;
   - whether `--network=host` inside the task (the task's own namespace) keeps every nested packet under the task's
     nft rules and resolver, and whether any other network mode (slirp, pasta, a bridge) can be refused from inside;
   - what a nested container's processes can see of the host (`/proc`, cgroups) and whether the per-task limits
     (`core/.../project/Limits.java`) still count them;
   - whether a task's own kernel ([B59](README.md#b59)) gives the same with less opened.
2. **Red first, if built:** a scenario in `acceptance/suite/.../task/task-builds.feature` whose nested container
   resolves a declared host and is refused an undeclared one; red today because podman cannot start in a task.
3. **The build:** `image.nested_containers: true` in `core/.../project/ProjectReader.java` and `ProjectSchema`, off by
   default; `ContainerSpec` adds only what the assessment found necessary, and a `containers.conf` in the image
   forces `network_mode = "host"` and the vfs or fuse-overlayfs driver; `Containerfile` installs podman when asked.
   `--dry-run` and `sokar doctor` name what the project opened.
4. **Docs:** `doc/reach.md` and `doc/project-file.md` say what a project opens by it.

## Acceptance

- The assessment, written, with each guarantee of `doc/reach.md` checked against nested containers.
- If built: seen to fail first, then green - a container started in a task reaches a declared host and is refused an
  undeclared one, exactly like the task.

## To be checked

- Whether the assessment ends in "not without [B59](README.md#b59)" (the operator's, once the assessment is written).
- Whether a project of the class `guarded` may open it at all, or only a less strict class (the operator's).
