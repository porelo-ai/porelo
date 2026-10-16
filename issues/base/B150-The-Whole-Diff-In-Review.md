# B150 — The Whole Diff In Review

**Status:** Analyzed.
**Size:** M - one contract change on the daemon's `Review` and its server side; the CLI already prints the whole diff.

**What must be true.** A review shows the whole diff, or says plainly that and where it was cut.

## Why

A person approves what they saw; a cut diff that does not say so lets unseen changes pass.

**Guideline points it answers:** AISVS 9.2.2. From a review of security guidelines, 2026-10-09.

## The shape

Checked 2026-10-09: `porelo gate review` and the daemon's review print the whole diff; nothing cuts it. What is open is the wire: whether a diff larger than a message may be is cut, refused or sent whole, and that an interface says it when it shows less.

## Plan

**Today (read 2026-10-11).** `GitGate.rankedReview` returns the whole patch; `porelo gate review` prints it whole; the
daemon's `Review` (`GateMethods`) puts it whole into `diff`. A varlink message may be at most 4 MiB
(`VarlinkConnection.MAX_MESSAGE`); a larger reply fails at the receiving side as "A varlink message exceeded ..."
bytes - the interface gets an error, not a cut diff, and nothing says why. `PendingBundle` already refuses above its
limit, by name (`BundleTooLarge`).

**Steps:**

1. `GateMethods` measures the reply. Above a bound under the message limit, with room for `log` and `files`, the diff
   is cut at a file boundary and the reply says so: `cut: ?ReviewCut` - the files shown, of how many, and the command
   that shows them all (`porelo gate review <name>`). `files` is never cut, so every file is listed.
2. `35-gate.varlink`: `Review` gains `cut`, and its comment says an interface that shows less than `diff` says so.
3. `doc/how-it-works.md` (the gate) and `doc/commands.md` say it.

Red first: a `GateMethods` test with a push larger than the bound - the reply fits the message limit, `cut` names the
files left out, and every file is in `files`.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- Whether a review above the limit is cut at a file boundary with a pointer to the command, or read in parts by a
  method of its own (Agent Core's; how it is shown is Agent Frontend's).
