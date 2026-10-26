# B295 — A Task Without A Gate Knows Its Host

**Status:** Idea.

**What must be true.** A task started without a gate (`--no-gate`, or a shell without a workspace) refuses a project
that declares host services in one sentence naming the cause, and its clearance watcher still never asks about the
host's address, under pasta or not.

## Why

Found reading porelo [B233](README.md#b233)'s rework. `TaskLaunch` asks podman for the gate's address only when the task
has a workspace; without one, `TaskWiring` carries none. Then:
- `TaskRunner.start` refuses a project with `egress.host_services` through `Objects.requireNonNull`, so the person
  reads `porelo: java.lang.NullPointerException: declared host services require the gate's address` and exit 70.
- No `host-address` reaches the task's state, so the watcher knows only that IPv4 link-local addresses are never asked
  about. Under pasta the host is one of them; on a fallback where podman answers the host's LAN address, a drop to
  that address is asked about and an Allow opens every port the host listens on.

## To be checked

- Whether a task without a gate can reach host services at all, or the refusal is the right answer and only its
  wording changes.
- What finding the host's address costs without a workspace: `Podman.hostAddressFromContainer` starts one throwaway
  container against the project's base image.
