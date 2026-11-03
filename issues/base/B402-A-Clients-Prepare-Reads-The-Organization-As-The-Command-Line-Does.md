# B402 — A Client's Prepare Reads The Organization As The Command Line Does

**Status:** Review.
**Size:** S - one call before `Preparation.prepare` in one daemon method, its contract's words and a test over the socket.

**What must be true.** A client's `Prepare` of a project its organization names builds from what a read of the organization
judges now, as `porelo task prepare` does. Today the daemon's `Prepare` takes `project` as the path of a project file and
reads nothing, so it builds from what the last start placed, and a project not placed yet cannot be prepared from a client.

Found by the reading of the cut on 2026-11-02. Older than the cut as a path, and the cut made the command line differ.

## Plan

`TaskMethods`' `Prepare` calls `ProjectTaking.take` for a project given by name before `Preparation.prepare`, with the
same refusals as a start and `ProjectSource.require`'s check of the name. A path answers for the project `Projects` reports
it under, and one it does not report is refused, so no path passes around the reading. `15-task.varlink` says so. Test in `PoreloDaemonTest`: a project not placed yet is placed by `Prepare`, and a refused file answers its reason.

## Declined from the reading

- A file that exists but does not read answers `NO_SUCH_PROJECT` rather than `FAILED`, from `Preparation.prepare`: older than
  this, and it builds nothing, so only a word is off.
- `Prepare` has no `repository` as `porelo task prepare` has: this issue is about what is read, and a new parameter is a
  contract of its own.
- No test here shows a project being placed over the socket: placing is `ProjectTaking.taken`, which `ProjectTakingTest` holds,
  and the socket's tests were seen to fail when the call to it was taken out.
