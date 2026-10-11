# B100 — A Task Can Be Looked At

**Status:** Analyzed.
**Size:** XL - an image layer, a published port Sokar has never made, a credential, [B95](README.md#b95)'s container and the
interface; splits in three below.

**What must be true.** A person sees and drives what a task runs on its virtual screen, through a
loopback-only view; nothing of the box runs on their computer.

## Why

Written beside [B95](README.md#b95) (a container to try waiting work in). The interface's half is in `sokar-frontend`.

A person tests the interface on their own computer with the guided walk: the agent answers in a panel and changes
the code live, with hot reload and Dart's MCP server. An agent in a Sokar task cannot do that - it reaches neither
the host's files nor its tools - and the person cannot reach what it built to try it.

## The shape agreed

- **Everything stays in the box.** The project's image carries what the work needs - for the interface the Flutter
  SDK and the Dart MCP server, the hosts they fetch from declared. The agent starts the app itself, on a virtual
  screen inside the task (Xvfb), and drives it with its own tools there: hot reload, MCP, the guided walk's panel and
  walk file, all local to the task.
- **Sokar offers that virtual screen as a view**: a VNC stream published on the host's loopback only, reached from a
  person's computer through ssh as the daemon is. Pixels and input only; no code from the box runs on the person's
  computer.
- **Not a web build in the person's browser**: that would run the agent's JavaScript with the person's network - a
  way out past the egress policy for unreviewed work.
- **The same view on a review container** ([B95](README.md#b95), `gate try`): the person tries the pending push itself - what would
  be approved, not the agent's working tree - then approves or rejects.
- **The interface** opens it from "View" on a task and on a review container, first in an external VNC viewer;
  embedding it is later.

## Plan

Read in the code on 2026-10-11: no container of Sokar's publishes a port. A task reaches the host's loopback
through pasta's `--map-host-loopback` (`runtime/.../LoopbackMapping.java`), the other way round; the ruleset a
task gets (`TaskRunner.rulesetFor`) opens nothing inbound. Nothing in the tree names a virtual screen or VNC.
A person's computer reaches the daemon through ssh (`DaemonConnectCommand`, `sokar daemon connect`).

**Split in three**, each L or smaller:

1. **This issue: the view of a task** (L).
2. **A new issue: the agent's tools on the virtual screen** (M): what a project's image needs for the guided walk
   (the Flutter SDK, the Dart MCP server, Xvfb), written as an image snippet and an egress set for their hosts,
   with a walk in a task as its acceptance. Mostly the project's and the interface's, little of Sokar's code.
3. **The same view on a review container** (S), folded into the IDE issue [B95](README.md#b95) splits off, once `gate try`
   exists.

Steps for the view of a task:

1. **Not a published port**: the VNC server in the task listens on a Unix socket in a directory mounted from the
   task's state directory, as the screen file is (`ScreenFile`). Nothing is bound in the network at all, so
   nothing is reached past the host's loopback, and the firewall and the hooks need no rule.
2. **The daemon relays it**: `method View(task) -> (address, password)` in `15-task.varlink` starts a relay on the
   host's loopback for that socket, ended with the connection, and a one-time password the VNC server is given
   through its password file. A person's computer forwards that loopback port through ssh, as for the daemon.
3. **Switched on per project**: `view: true` in the project file, which adds the VNC server and Xvfb to the
   image's layers; a project without it has no socket and `View` answers `NoView`.
4. **`sokar task view <task>`** prints the forward and the password, for an external viewer.

Red first: a test of the container's specification that finds a published port or a bound address; then a
scenario that connects without the password and is refused, and one with it that reads the first screen.

## Acceptance

- An agent in a task starts the app on a virtual screen inside the task and drives it with its own
  tools there - hot reload, MCP, the guided walk's panel and walk file - with nothing reached on the
  host. Seen to fail: the walk cannot run in a task whose image carries the Flutter SDK and the Dart
  MCP server.
- The virtual screen is offered as a VNC stream published on the host's loopback only, and a person
  reaches it from their own computer through ssh as the daemon is reached. Seen to fail: a check of
  the published port finds it bound to anything but loopback.
- Only pixels and input cross; no code from the box runs on the person's computer, and no web build
  is served to their browser. Seen to fail: anything other than the VNC stream is offered for the
  view.
- The same view is offered on a review container ([B95](README.md#b95), `gate try`), showing the pending push rather
  than the agent's working tree. Seen to fail: the view on a review container shows the working
  tree's state rather than the pushed commit.
- The view is authenticated beyond being on loopback. Seen to fail: a viewer without the credential
  connects.

## To be checked

- How the view is switched on: per project in `project.yml` (the plan's leaning), or per task at its start
  (the operator's).
- Whether a one-time VNC password is enough beyond loopback, given that VNC's own password check is weak; the relay
  could ask for a token first instead (the operator's).
- Which VNC server goes into the image, and whether it can listen on a Unix socket only (Agent Core's, measured).
