# B186 — One Line For One Dropped Connection

**Status:** Review.
**Size:** S - the kernel's own time for each logged packet, an immediate flush, a retry folded into the line of its
attempt, tests with captured datagrams, and a check on the VM.

**What must be true.** Each connection attempt the firewall stops is one line in the task's `events.jsonl`, stamped
with the time the packet was stopped. A packet the client sends again for the same attempt does not make a second
line.

## Why

A security analysis on a test machine found every drop written twice, with the same time to the nanosecond and the
same fields (22 lines for 11 drops). The task's `hooks.log` shows one reader per start, so it is not two readers.

## What it is

Read in today's code:

- **One log rule:** the ruleset logs a drop in exactly one rule (`NftRuleset`: `log prefix ... group <n>` before the
  output chain's policy).
- **One event per message:** `NflogMessage.parse` makes one event per netlink message in a datagram. That is correct.
- **One time per datagram:** `NflogReader` stamps every event of a datagram with one `Instant.now()`, taken when the
  datagram was received.
- **No batching settings:** `NflogReader` binds the group (`NFULNL_CFG_CMD_BIND`) and sets neither a queue threshold
  nor a flush timeout. The rule sets no `queue-threshold` either. So the kernel's defaults for an NFLOG group apply:
  it holds up to 100 packets for up to a second and sends them in one datagram.
- **Too few fields:** an event records the destination, the port and the protocol, not the source port.

So the two lines are two packets - most likely a TCP SYN and its retransmission after about a second, both still
held when the batch went out. They were stamped with one time and described with fields that cannot tell them apart.
Nothing is written twice. Each line says less than it should: the time is not the packet's, and a retry looks like a
second connection.

## Plan

- **The packet's own time:** the event takes the kernel's timestamp (`NFULA_TIMESTAMP`) where the message carries
  one, and the time of receipt only where it does not.
- **No holding back:** the rule gets `queue-threshold 1`, so the kernel sends each logged packet at once. The prompt
  then sees a stopped connection when it happens, not up to a second later.
- **A retry is not a new attempt:** the event records the source port. The reader takes a packet with the same
  protocol, destination, port and source port within ten seconds as part of the first. The clearance watcher already
  asks once per destination; this only changes the record.

Steps:

1. **Red first:**
   - `NflogMessageTest`: a datagram with two messages for one source port gives two events with their own timestamps.
   - `RetryFoldTest`: the second within ten seconds is not a new line, and one from another source port is.
2. **Code:** the attribute in `NflogMessage`, the source port in `BlockedConnection`, `queue-threshold 1` in
   `NftRuleset`, and the folding where the reader writes. `doc/getting-started.md` describes a line of
   `events.jsonl`.
3. **On the VM:** in a stub task, a connection attempt that retries for a few seconds gives one line.
4. Reactor, documents and the suite on the VM (`not @restart`).

## As built

- **The kernel's time and the source port:** `NflogMessage` reads `NFULA_TIMESTAMP` where it is there, else it uses
  the time of receipt. `BlockedConnection` carries `sourcePort`, and its JSON has it where it is known.
- **An immediate flush:** the log rule has `queue-threshold 1`, and the golden rulesets follow. In the output path
  the kernel often stamps no time, so this is also what makes the time of receipt the packet's.
- **Retries:** differently from the plan, a retry is left out, not counted. A line is appended the moment the first
  packet is read, because the clearance watcher reads it then, and a count would mean rewriting a line already
  written.
  - `RetryFold` in `shield` takes the first packet of an attempt (protocol, destination, port and source port) and
    leaves out a repeat within ten seconds.
  - It remembers at most 4096 attempts, so a flood of new ports cannot grow it.
  - `shield read --all` still reports every packet.
- **Acceptance:** `task-guarded.feature`'s probe of 1.1.1.1:443, which retries for five seconds, leaves one line.

