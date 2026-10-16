# B100 — A Task Can Be Looked At

**Status:** Estimated.
**Size:** XL - an image layer, a published port Porelo has never made, a credential, [B95](README.md#b95)'s container and the
interface; splits in three below.

**What must be true.** A person sees and drives what a task runs on its virtual screen, through a
loopback-only view; nothing of the box runs on their computer.

## Why

Written beside [B95](README.md#b95) (a container to try waiting work in). The interface's half is in `porelo-frontend`.

A person tests the interface on their own computer with the guided walk: the agent answers in a panel and changes
the code live, with hot reload and Dart's MCP server. An agent in a Porelo task cannot do that - it reaches neither
the host's files nor its tools - and the person cannot reach what it built to try it.

## The shape agreed

- **Everything stays in the box.** The project's image carries what the work needs - for the interface the Flutter
  SDK and the Dart MCP server, the hosts they fetch from declared. The agent starts the app itself, on a virtual
  screen inside the task (Xvfb), and drives it with its own tools there: hot reload, MCP, the guided walk's panel and
  walk file, all local to the task.
- **Porelo offers that virtual screen as a view**: a VNC stream published on the host's loopback only, reached from a
  person's computer through ssh as the daemon is. Pixels and input only; no code from the box runs on the person's
  computer.
- **Switched on per project**, in `project.yml`: a project that does not ask for the view has no VNC server in its
  image and nothing to connect to.
- **Porelo's relay asks for a strong token before anything reaches the VNC server.** Being on loopback is not
  enough, and VNC's own password check is weak, so nothing relies on it: a connection that does not present the
  token first is closed having reached nothing.
- **Not a web build in the person's browser**: that would run the agent's JavaScript with the person's network - a
  way out past the egress policy for unreviewed work.
- **The same view on a review container** ([B95](README.md#b95), `gate try`): the person tries the pending push itself - what would
  be approved, not the agent's working tree - then approves or rejects.
- **The interface** opens it from "View" on a task and on a review container, first in an external VNC viewer;
  embedding it is later.

## Plan

Read in the code on 2026-10-11: no container of Porelo's publishes a port. A task reaches the host's loopback
through pasta's `--map-host-loopback` (`runtime/.../LoopbackMapping.java`), the other way round; the ruleset a
task gets (`TaskRunner.rulesetFor`) opens nothing inbound. Nothing in the tree names a virtual screen or VNC.
A person's computer reaches the daemon through ssh (`DaemonConnectCommand`, `porelo daemon connect`).

**Split in three**, each L or smaller:

1. **This issue: the view of a task** (L).
2. **A new issue: the agent's tools on the virtual screen** (M): what a project's image needs for the guided walk
   (the Flutter SDK, the Dart MCP server, Xvfb), written as an image snippet and an egress set for their hosts,
   with a walk in a task as its acceptance. Mostly the project's and the interface's, little of Porelo's code.
3. **The same view on a review container** (S), folded into the IDE issue [B95](README.md#b95) splits off, once `gate try`
   exists.

Steps for the view of a task:

1. **Not a published port**: the VNC server in the task listens on a Unix socket in a directory mounted from the
   task's state directory, as the screen file is (`ScreenFile`). Nothing is bound in the network at all, so
   nothing is reached past the host's loopback, and the firewall and the hooks need no rule.
2. **The daemon relays it**: `method View(task) -> (address, token)` in `15-task.varlink` starts a relay on the
   host's loopback for that socket, ended with the connection, with a one-time token of 256 random bits. The relay
   reads the token as the first bytes of every connection, compares it in constant time, and only then opens the
   VNC server's socket; a connection with a wrong or missing token is closed before the socket is opened. The VNC
   server's own password is not what protects it. A person's computer forwards that loopback port through ssh, as
   for the daemon.
3. **Switched on per project**: `view: true` in `project.yml`, which adds the VNC server and Xvfb to the
   image's layers; a project without it has no socket and `View` answers `NoView`.
4. **`porelo task view <task>`** puts an external viewer behind the token: it presents the token to the relay and
   gives the viewer a port to connect to, and prints the forward to set up.

Red first: a test of the container's specification that finds a published port or a bound address; a relay test
that connects without the token, or with a wrong one, and finds the VNC server's socket opened; then a scenario
that connects without the token and is refused, and one with it that reads the first screen.

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
- The view is authenticated beyond being on loopback, by the relay's token, before anything reaches the VNC
  server. Seen to fail: a viewer without the token, or with a wrong one, reaches the VNC server's socket at all -
  even when it would then pass the VNC server's own password.
- The view exists only for a project that switches it on in `project.yml`. Seen to fail: a task of a project
  without `view: true` answers `View` with an address.

## To be checked

- Which VNC server goes into the image, and whether it can listen on a Unix socket only (Agent Core's, measured).
- How `porelo task view` gives an external viewer, which cannot send the token itself, a port no one else on the
  host can use (Agent Core's): a bridge on the person's own computer's loopback, or one on the host behind the ssh
  forward.
