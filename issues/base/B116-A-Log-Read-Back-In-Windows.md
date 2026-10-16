# B116 — A Log Read Back In Windows

**Status:** Analyzed.
**Size:** M - one daemon method and its reader, but `Tail` is a contract the interface reads.

**What must be true.** A client that started reading a task's log from its end can read what lies before, a window
at a time, and no single line of a log can make a reply as large as the log itself, because the daemon caps a
line's length and says how much it cut.

## Plan

Read in the code on 2026-10-11 (`daemon/.../TaskMethods.java`, `PoreloDaemon.java`, `15-task.varlink`):

- `Tail(task, log, last?, formatted?)` answers `lines` and `offset`, the byte position its first line starts at -
  already there "so a client can later ask for what lies before it", but nothing takes a position to read back
  from. `startOfLast` finds where the last N lines start, reading back in 64 KiB blocks.
- `read` sends at most `MAX_CHUNK` (64 KiB) per reply; a line longer than that goes as several pieces, each
  sent as a line of its own, whole across replies and with nothing to say where it was cut. So no reply is as
  large as the log, but a client shows one line of many megabytes as many lines.
- A small fault on the way: `read`'s Javadoc sits above `startOfLast`, and `startOfLast` has none of its own
  for what `read` does.

Steps:

1. **`before: ?int`** on `Tail`: with `last`, the lines that end at that offset - `startOfLast` generalised to an
   end position (`startOfLast(log, lines, end)`), and a read of `[start, end)` only. Answered once, never
   streamed: refused as `InvalidParameter` with `more`. Red first: `LogFromItsEndTest` walking a log of
   several megabytes back in windows of 100 lines, comparing with the file, and ending at offset 0.
2. **A line's cap**: `MAX_LINE` (64 KiB, the chunk's size), and a line beyond it is sent cut - its beginning
   kept, unless decided otherwise below - with `cut: ?[]LineCut` in the reply: the line's index and the bytes
   left out. The rest of the long line is skipped, not sent as more lines; a streaming `Tail` passes to its end.
   Red first: a log of one line of 8 MB, answered as one line with its cut named.
3. `startOfLast` counts a cut line as one line, so a window never splits one.
4. The varlink file's comments say both; `InterfaceDescriptionTest` holds the contract.

## Acceptance

- `Tail` given an offset and a count answers the lines that end at that offset, with the offset its first line
  starts at, so repeated calls walk back to the log's first line. Seen to fail: a window that repeats or skips a
  line at its edge, or a walk back over a log of several megabytes that does not end at offset 0.
- A log holding one line of many megabytes is answered with that line cut at the daemon's cap, and the reply
  names the length it cut. Seen to fail: a reply carrying the whole line, or a cut line with no length beside it.

## To be checked

- Whether a cut line keeps its beginning and end with the middle left out, or its beginning only (the
  interface's agent's, which shows it).
- Whether a formatted `Tail` (through the agent) applies the cap before the agent formats a line or after
  (Agent Core's).
