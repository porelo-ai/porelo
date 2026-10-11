# B23 — Secrets In This Process's Memory

**Status:** Implementing.
**Size:** L - hardening is a few lines, but a credential as bytes changes every caller in vault, supervisor and apps.

**What must be true.** A credential's plaintext exists in as few places, and for as short a time, as the platform
allows - and what cannot be achieved is written down rather than implied.

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

`sokar doctor` ends with three lines that are not probes:

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

### Found by B76's reading of `sokar-vault`

- `KernelKeyring` makes a `String` of the passphrase when storing and reading it, and leaves its
  native buffers unzeroed when the arena closes.
- `VaultFile` does not clear a device's wrapping key after enrolling, nor the master key when
  `enroll`, `revoke` or a change in `update` throws.

## Plan

Read in the code at `30c8169b`. Built: the vault document is written into a buffer the vault owns and wiped. The
rest is open, in this order, cheapest first.

**1. Hardening applied (S, its own commit).** `ProcessHardening` (`core/.../hardening/ProcessHardening.java`) has
`disableDumping()` and `dropPrivilegeEscalation()`, and still no caller; only `DoctorCommand` reads `dumpable()` and
`noNewPrivileges()`. Call both first thing in `VaultServeCommand.call`, `VaultAgentCommand.call` and
`VaultRelayCommand.call` (`apps/app-vault/...`), and in the daemon's start (`DaemonCommand`, `SokarDaemon`), which
now holds the vault's reads. `doctor` then reports the hardening of those processes, read from
`/proc/<pid>/status` of each running one, not of itself - or drops the three lines (below). Red first: an
acceptance step reading `Dumpable` of the running broker and daemon, which reads `1` today.

**2. The passphrase path closed (S).** `KernelKeyring` (`vault/.../KernelKeyring.java`) turns the passphrase into a
`String` when storing (`new String(passphrase).getBytes`) and when reading; encode from the `char[]` through a
`CharBuffer` instead, and zero the native segments before the arena closes. `VaultFile.enroll`, `revoke` and
`update` clear the wrapping key and the master key in `finally`. Red first: a test that reads the buffers after
`enroll` throws.

**3. A credential as bytes (L).** The one place this already holds is the passphrase: `char[]` from
`Console.readPassword` through `PassphraseTiers` to the kernel keyring, with the byte buffers wiped. `VaultEntry`
(`vault/.../VaultEntry.java`) is `record VaultEntry(String value, ...)`; it becomes a holder of a `byte[]` that it
hands out as a copy and can wipe, and the JSON reading in `VaultFile` parses values into it without a `String`.
About thirty-five callers read `.value()` - the broker (`VaultProxy`, `TokenPurchase`, `DeviceGrant`, `CodeGrant`
in `supervisor/...`), the wiring, the import, the listing - and each holds the bytes only as long as it needs them.
The broker's last copy is the HTTP header: `java.net.http` takes a `String`, so that path keeps one and says so in
the code and in `doc/credentials.md`. Red first: an architecture test that finds `VaultEntry.value` typed `String`.

**4. The documentation** (`doc/credentials.md`): erasure is not achieved in a managed runtime, and why; what is
achieved is fewer copies and shorter lifetimes.

**The cost is spread rather than deep.** Changing `VaultEntry` touches every caller that reads a credential, and each
has to be careful in a way it currently is not; that is why step 3 is done on purpose rather than alongside
something else.

## Acceptance

- No structure holds more than one credential's plaintext at a time. Seen to fail: a test that stores two
  credentials and inspects the serialized vault document's buffer finds both values in one `String`.
- A credential's value is not an immutable object on the paths that read, write and broker it. Seen to fail: a
  check of `VaultEntry` and its callers finds the value typed `String`.
- Every buffer holding plaintext is overwritten when the work that needed it is done. Seen to fail: a test reading
  the buffer after the operation finds the plaintext still in it.
- Where a guard cannot be tested, the code says so rather than looking covered.
- The documentation states that erasure is not achieved, and why.
- **A process holding a credential cannot be core-dumped**, and nothing reports hardening it does
  not apply. Seen to fail: `/proc/<pid>/status` of the broker, the ssh-agent or the relay shows dumpable `1`, or
  `doctor` reports hardening those processes do not have.

## To be checked

- **Whether `doctor` should report hardening at all** (Agent Core's), or report the broker's and the daemon's state
  as read from `/proc`. Printing the state of its own process, which nothing hardens, is how a line that looks
  like a warning survives for months.
- **Whether step 3 is worth it against the threat it addresses** (the operator's). A heap dump of the daemon
  requires the ability to read the daemon's memory - the same uid that can read the vault file and the passphrase
  in the keyring. The case for it is defence in depth and crash artifacts, which step 1 already covers for core
  dumps.
