# B06 — Remote Access

**Status:** Implementing.
**Size:** M - both shapes are built; left are an ssh hop and a socket check in the acceptance suite, and a doc line.

**What must be true.** Tasks on another machine are usable over an encrypted tunnel, without the
daemon ever binding a network port.

## Why

Remote access is a tunnelling problem. The daemon keeps its private socket; the
client reaches it through an existing encrypted channel. No new listener, no
certificates, no tokens.

**Where the other half lives.** The client's decisions are the interface's, recorded in porelo-frontend's `doc/decisions.md`: a unix socket forward, the interface managing `ssh` itself, and a cut stream shown as a disconnection rather than an empty machine. Nothing here blocks them, and the reconnection criteria below can point at those decisions instead of waiting on them.

## What was measured

**The tunnel carries the socket directly.** This requirement's central question is answered, so
the posture is settled and nothing has to bridge. Against a daemon on another machine:

```
ssh -L /tmp/porelod-remote.sock:/run/user/1001/porelo/porelod.sock user@host -N
```

A client then opens the forwarded path instead of `$XDG_RUNTIME_DIR/porelo/porelod.sock` and
nothing else changes — same calls, same replies, same code. Streaming survives it: watching the
task list through the forward, a task stopped on the far machine was reported 0.4 s later. SSH
creates the local endpoint owner-only, so the forwarded socket is no more exposed than the
original.

The consequence, written down because it is a trade and not a free win: reaching a Porelo needs
a shell on its machine. There is no browser and no other client, and the frontend's
corresponding requirement dropped its browser criterion because of this.

## Measured: the second shape

**The second shape exists now.** `porelo daemon connect` puts this process's standard input and
output onto the daemon's socket and interprets nothing in between, so
`ssh host porelo daemon connect` speaks varlink straight down the ssh session: no socket file on
the client, nothing to tear down, and the connection lives exactly as long as the ssh command.
Measured against a running daemon by piping one call in and reading the reply out; the ssh leg
could not be measured on this machine, which runs no sshd, so what is proven is the bridge, not
the hop.

**Bytes, not lines.** Varlink frames are NUL-separated JSON and a stream is answered over time, so
the bridge copies raw bytes and flushes on every read. A line-buffered one would hold a reply until
the next arrived, which for a fleet watch means holding it until something changes - exactly the
case an interface has to show promptly.

**One client can carry several streams, and each costs a connection.** A fleet watch and two log
tails were run at once against one daemon, each through its own bridge, while both logs were
appended to: all three delivered, none blocked another. That is the answer to the question, and it
is also the difference between the two shapes - `ssh -L` forwards one socket and a client opens as
many connections through it as it likes, while `connect` is one connection per invocation, so
three streams are three ssh sessions. Neither is wrong; the second trades sessions for having no
socket file and no lifetime to manage.

**The 64 KB chunk is not a limit, it is a rate.** A 1.5 MB log tailed as 24 replies, largest
68 KB, all 30,000 lines delivered and none lost. But the tail loop sleeps 200 ms between chunks,
so a backlog drains at roughly 320 KB/s however fast the transport is: opening a large log is
slow by construction rather than by bandwidth. That is a decision to revisit deliberately - the
sleep is there so a live tail does not spin - not a bug to fix in passing.

## Notes

**A clearance prompt waits 60 seconds, decided 2026-10-11.** Nothing is measured for it. An expired question
is recorded, replaced on screen by a notice saying the destination stays blocked, and can still be answered
afterwards through `Decide`; whoever wants a longer wait sets it with `shield watch --timeout`.

Notifications are the part this constrains that is not yet settled; the interface's requirement for them in
[its own set](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md) is retired:
a forwarded socket delivers events to a client that is connected, which is not the same as
reaching a person whose client is closed.

## Plan

Read in the code at `30c8169b`:

- **Both shapes are built.** The daemon listens only on `UnixDomainSocketAddress`, through
  `SocketContext.openUnixSocket` (`wire/.../SocketContext.java`) and `VarlinkServer`
  (`wire/.../varlink/VarlinkServer.java`). `porelo daemon connect` is `DaemonConnectCommand`
  (`apps/app/.../DaemonConnectCommand.java`), with `DaemonConnectCommandTest`. `doc/running.md` already describes it.
- **The one inet listener is not the daemon's.** `SocketRelay` (`supervisor/.../SocketRelay.java`) binds an
  `InetSocketAddress` inside a task's network namespace, to reach the broker. So the check looks at the daemon's own
  process, never at every Porelo process on the machine.
- **The hop this file could not measure is available.** Every machine the acceptance suite runs on has its own sshd
  (`credentials-commands.feature` relies on it), and scenarios already speak varlink through `porelo daemon connect`
  (`task-unattended.feature`, `credential-several.feature`). `ssh localhost` on the machine is a real ssh hop.
- **What stays as it is.** The tail's rate is `TAIL_INTERVAL` (200 ms) with `MAX_CHUNK` (64 KiB) in `PoreloDaemon`;
  the clearance prompt's wait is `shield watch --timeout` (`ShieldWatchCommand`, `DesktopPrompt`), 60 s by default,
  which stays as decided 2026-10-11. Neither is changed here.
- **The client half is the interface's.** Showing a cut tunnel as a disconnection and reconnecting without a
  restart are porelo-frontend's, by its `doc/decisions.md`; the last two criteria are checked there.

Steps:

1. A scenario in `acceptance/suite/.../machine/machine.feature`, red first: with the daemon running, `ss -ltnup` and
   `ss -lunp` filtered by the daemon's pid list nothing, and `ss -lxp` lists its socket. It goes red when the daemon
   owns any TCP or UDP listener.
2. A scenario for the hop: `Tasks1.Watch` sent through `ssh localhost porelo daemon connect`, a task started and
   stopped on the machine, both reported on the client side; then the ssh client is killed under the watch, and the
   client side ends with an error, never with an empty task list. The same once through `ssh -L` with a socket
   forward and `List` over the forwarded path.
3. The measurements written into this file; `doc/running.md`'s section on another machine names both shapes'
   costs (a socket file and its lifetime against one ssh session per stream).

## Acceptance

- A remote machine's tasks are usable with no configuration on that machine beyond
  existing remote access. Seen to fail: a client reaching a daemon through `ssh -L` or
  `ssh host porelo daemon connect` needs any further setup on the far machine.
- The daemon binds no network interface in any configuration. Seen to fail: a check of the daemon's
  open sockets finds anything but its unix socket.
- A clearance question nobody answers in time is not lost: the wait stays 60 s, an expired question is answered
  later through `Decide`, and `shield watch --timeout` sets a longer wait. Decided 2026-10-11, so nothing is
  measured for it. Seen to fail: an expired question can no longer be answered through `Decide`.
- Tunnel loss is shown as disconnection, never as an empty fleet. Seen to fail: cutting the tunnel
  under a fleet watch leaves the client showing no tasks without saying it is disconnected.
- Reconnection recovers without restarting the client. Seen to fail: after the tunnel comes back,
  the client shows nothing new until it is restarted.

## To be checked

- **What a client should do when a stream is cut** (Agent Frontend's). The daemon's streams fail loudly rather than
  ending quietly, which is the honest half; reconnecting and resuming a watch is the interface's, and it constrains
  what "reconnection recovers" above can mean.
- **Whether an interface should manage the tunnel itself** (Agent Frontend's). Spawning `ssh` and owning its
  lifetime is friendlier and puts key handling and process supervision inside the client; requiring a tunnel that
  is already up keeps it out of the credential business. porelo-frontend's `doc/decisions.md` records managing
  `ssh` itself.
- **Whether a forward is verified before a flow depends on it** (Agent Core's). `doc/credentials.md` records that a
  port collision lies - ssh binds `[::1]` when `127.0.0.1` is taken and says nothing - and a login then completes
  against whatever is listening. Nothing checks. The cheaper answer is a device-code flow, which removes the forward
  rather than checking it, and is only available where the provider offers one.
- **Which of the two shapes an interface should use** (Agent Frontend's). Both exist and both work; the trade is
  measured above.
