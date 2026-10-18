# B23 — Secrets In This Process's Memory

**Status:** Review. Green in porelo's Build 39083121366 on `842ff109`, on both rented legs, 2026-10-18.
**Size:** M - the daemon's hardening is one contract change (`Tasks1.Process`), the deploy's check and two acceptance
scenarios, a few hours; the passphrase path is S on its own; a credential as bytes was dropped on 2026-10-11.

**What must be true.** A credential's plaintext exists in as few places, and for as short a time, as the platform
allows - and what cannot be achieved is written down rather than implied.

**A credential stays a `String` in memory, decided 2026-10-11.** Turning it into bytes is dropped: whoever reads the
daemon's memory runs as the same uid that reads the vault file and the keyring, and core dumps of the processes
that hold a secret are turned off by step 1. It is recorded as an accepted risk in
[doc/decisions.md](../../doc/decisions.md#secrets-in-the-daemons-memory-stay-strings), which also states that
erasure is not achieved in a managed runtime, and why. This issue covers steps 1 and 2.

## Why

Nothing here blocks anything; it is written down so the ceiling is known rather than rediscovered, and so the
partial fix already made is not mistaken for the whole thing.

### What was fixed, and what it leaves

Storing anything in the vault used to build a single `String` holding **every credential it holds,
in plaintext** - `Json.write(document(entries)).getBytes(UTF_8)`. A `String` cannot be cleared,
lives until the collector reaches it, and appears in any heap dump.

That one is gone: the document is written into a buffer the vault owns and overwritten once the
bytes are encrypted, and the byte array is wiped too.

**What it leaves is most of the surface.** The read path parses JSON into `String`s,
`VaultEntry.value()` is a `String`, and every caller that looks at a credential holds one. So the
worst single copy is gone and the general property is not.

### Found by a first-install test, 2026-09-08: hardening that is never applied

`porelo doctor` ends with three lines that are not probes:

```
dumpable            1
no new privileges   false
hardening covers    the whole process
```

**`ProcessHardening` is fully implemented, has unit tests, and has no caller anywhere** - not in
code, not in scripts, not in configuration. `disableDumping()` and `refuseNewPrivileges()` are
never called, and none of the processes that hold a secret touch the class: not the broker
(`vault serve`), which reads the real credential per request; not the ssh-agent (`vault agent`),
which holds the signing key seed; not the relay.

So **`dumpable 1` means the process holding a credential can be ptraced and core-dumped by
anything running as that user.** A core dump is exactly the artifact this requirement is about -
it is a heap dump under another name, written automatically, often to a place nobody remembers
configuring.

#### Two separable defects

**The report misleads.** Every other line `doctor` prints is a probe carrying one next action, and
a failing probe that names none cannot be constructed. These three carry nothing to do about them,
and they are not even describing one thing: the third is a *capability* - if hardening were
applied, it would cover the whole process rather than one thread - while the first two are
*current state*, and the current state is "not hardened". `dumpable 1` reads as a finding and
`hardening covers the whole process` reads as reassurance, on adjacent lines, about different
questions.

**Or the hardening should be applied**, which is the substantive half. `disableDumping()` in the
broker is one line and makes a core dump of the process holding the real credential impossible.

#### What it is worth, stated the same way as the rest of this requirement

Reading another process's memory needs the same uid that can already read the vault file and the
passphrase in the kernel keyring, so this is **defence in depth and crash artifacts, not a
boundary somebody is being kept outside of**. That is the same conclusion this requirement reaches
about the whole subject.

But it is the cheapest thing on this page by a wide margin, and unlike the rest it needs no
refactor: three call sites, in the three processes that hold something worth dumping.

### The ceiling, which is the reason this is not simply "do the rest"

**In a managed runtime a secret cannot be reliably erased.** A moving collector copies objects, and
the original is left behind untouched; strings are immutable in Java as they are in Dart and
JavaScript. Growing a buffer copies its contents for the same reason.

So the achievable goal is **fewer copies and shorter lifetimes**, never erasure. A requirement
promising otherwise would be the kind of claim this project's own notes warn about, and any test of
it would be asserting something the platform does not offer.

### Found by [B76](README.md#b76)'s reading of `porelo-vault`

- `KernelKeyring` makes a `String` of the passphrase when storing and reading it, and leaves its
  native buffers unzeroed when the arena closes.
- `VaultFile` does not clear a device's wrapping key after enrolling, nor the master key when
  `enroll`, `revoke` or a change in `update` throws.

## Plan

Read in the code at `30c8169b`. Built: the vault document is written into a buffer the vault owns and wiped. Two
steps are open, in this order, cheapest first.

**1. Hardening applied (S, its own commit).** `ProcessHardening` (`core/.../hardening/ProcessHardening.java`) has
`disableDumping()` and `dropPrivilegeEscalation()`, and still no caller; only `DoctorCommand` reads `dumpable()` and
`noNewPrivileges()`. Call both first thing in `VaultServeCommand.call`, `VaultAgentCommand.call` and
`VaultRelayCommand.call` (`apps/app-vault/...`), and in the daemon's start (`DaemonCommand`, `PoreloDaemon`), which
now holds the vault's reads. `doctor` then reports the hardening of those processes, read from
`/proc/<pid>/status` of each running one, not of itself - or drops the three lines (below). Red first: an
acceptance step reading `Dumpable` of the running broker and daemon, which reads `1` today.

**Built, 2026-10-17, for step 1:** `vault serve` (the broker, every credential of a task) and `vault agent` (the ssh
key's seed) call `ProcessHardening.disableDumping()` before they read the first secret; the relay carries bytes only.
`task-left-running.feature` checks it on the running broker: its entries under `/proc` belong to root, which is how
the kernel shows a process that is not dumpable. `PR_SET_NO_NEW_PRIVS` is not set: it holds for the calling thread
and its children only, so in a JVM it covers less than it says. Doctor's three lines are unchanged: `doctor --json`
carries them; that is still open (below).

**Built, 2026-10-17, the daemon:** `PoreloDaemon.main` calls `disableDumping()` before anything reads the vault, and
exits 70 if the flag does not hold. Its entries under `/proc/<pid>` then belong to root, so the two checks that read
them ask elsewhere:

- **What the daemon runs** is asked of the daemon: `Tasks1.Process` (`50-machine.varlink`, `MachineMethods`, a new
  method, nothing else changed) answers its pid and where `/proc/self/exe` points, which a process reads of itself
  whatever its dumpable flag. The deploy's `runsItsOwn` takes the executable only when the pid equals the unit's
  `MainPID`, so a daemon started by hand that still holds the socket is not taken for the unit's.
- **That it binds no network port** is asked of the kernel, not of the daemon, decided 2026-10-18 (the plan had the
  daemon report its own network view): `ss -ltun --cgroup` names the control group each socket was made in, the
  daemon's unit has a group of its own, and the scenario counts the listeners in it. That is better than a list the
  daemon makes of its own sockets: the guard does not depend on the code it guards, and it also counts a socket of
  anything else the unit runs. It refuses to judge on a machine where `ss` does not name a control group on every
  listener, as an older kernel or `ss` would not. Measured on the developer machine with the daemon on the JVM in a
  user unit: with the hardening, `readlink /proc/<pid>/exe` and `ss -p` show its own user nothing, and with a loopback
  `ServerSocket` planted in `main` the check counts 1, where the old `pid=` filter counted 0.

`machine.feature` holds both: the port scenario above, and a scenario reading the owner of the running daemon's
`/proc/<pid>/environ`, the same way `task-left-running.feature` reads the broker's. Neither has run on a VM yet.

**2. The passphrase path closed (S).** `KernelKeyring` (`vault/.../KernelKeyring.java`) turns the passphrase into a
`String` when storing (`new String(passphrase).getBytes`) and when reading; encode from the `char[]` through a
`CharBuffer` instead, and zero the native segments before the arena closes. `VaultFile.enroll`, `revoke` and
`update` clear the wrapping key and the master key in `finally`. Red first: a test that reads the buffers after
`enroll` throws.

**Dropped, 2026-10-11: a credential as bytes.** `VaultEntry.value()` stays a `String`, and so do its callers and
the broker's HTTP header. The accepted risk, with what would change it, is in
[doc/decisions.md](../../doc/decisions.md#secrets-in-the-daemons-memory-stay-strings); that entry is also the
documentation that erasure is not achieved, and why.

## Acceptance

- No structure holds more than one credential's plaintext at a time. Seen to fail: a test that stores two
  credentials and inspects the serialized vault document's buffer finds both values in one `String`.
- Every buffer holding plaintext is overwritten when the work that needed it is done. Seen to fail: a test reading
  the buffer after the operation finds the plaintext still in it.
- Where a guard cannot be tested, the code says so rather than looking covered.
- The documentation states that erasure is not achieved, and why, and records a credential kept as a `String` in
  memory as an accepted risk. Seen to fail: `doc/decisions.md` has no entry giving its exposure, why it stays and
  what would change it.
- **A process holding a credential cannot be core-dumped**, and nothing reports hardening it does
  not apply. Seen to fail: the entries under `/proc/<pid>` of the running daemon, broker or ssh-agent belong to
  their own user rather than root (`/proc/<pid>/status` has no dumpable field; root ownership is how the kernel
  shows the flag), or `doctor` reports hardening those processes do not have.

## To be checked

- **Whether `doctor` should report hardening at all** or report the broker's and the daemon's state
  as read from `/proc`. Printing the state of its own process, which nothing hardens, is how a line that looks
  like a warning survives for months.
- **The daemon's two `machine.feature` scenarios and an account deploy on the VMs**, with the native `porelod`: the
  environment owned by root, `network listeners: 0`, and `daemon runs` naming the account's binary.
