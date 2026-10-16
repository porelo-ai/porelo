# B56 — Programs Porelo Runs That Could Be Calls

**Status:** Analyzed.
**Size:** S - completing the table and a test that keeps it complete is one module; each replacement is an issue of its
own.

**What must be true.** Every external program Porelo starts either has a written reason to stay a program or is
replaced by a call proven on a JVM and in the native image.

## Why

Nice to have; nothing waits on it. It is to be checked again later rather than scheduled, because what Java and
GraalVM can call natively moves with each release.

Porelo starts external programs where it needs something from the operating system. Each one is a
process, a command line, output parsed as text, and a program that has to be installed in the
version expected. Some could be a function call instead: through the Foreign Function & Memory API
(`java.lang.foreign`, standard since Java 22), or through GraalVM's C interface
(`org.graalvm.nativeimage.c`, which exists only in a native image). What follows is what was found on
2026-09-13.

## What already is a call

FFM is used in four places, all in the dynamically linked binaries. GraalVM's C interface is used
nowhere.

| Class | Call | Why a call |
|---|---|---|
| `ProcessHardening` | `prctl(2)` | Per thread, and verified by reading the value back |
| `Exec` | `execvp(3)` | Hands a terminal to a shell without Porelo sitting in between |
| `NetlinkSocket` | `socket(2)` with `AF_NETLINK` | The JDK has no netlink |
| `KernelKeyring` | `libkeyutils` | The vault's unlock cache |

## The constraint that decides most of it

**The three OCI hooks cannot use FFM.** They are built `--static --libc=musl`, and FFM's default
lookup opens `libc.so.6` on its first downcall, which fails in a static image. `NoForeignFunctionMemoryTest`
in `hooks` and in `wire` enforces it, because a violation links and then fails at container start —
on the fail-closed path. GraalVM's C interface binds when the image is linked instead, so it is the
only candidate inside a hook; whether it works against static musl is unmeasured.

## The programs, and what was found

| Program | Started by | Could be | Found so far |
|---|---|---|---|
| `nsenter` | the three hooks, `EgressPolicy` | `setns(2)` | Entering a network namespace is per thread and allowed in a multi-threaded process. The program that has to run inside it — `nft`, `dnsmasq` — must start in that namespace, and whether a child started from Java inherits the calling thread's namespace in a native image is unmeasured. In a hook only the C interface applies. |
| `nft` | `NftHook` | `libnftables` | A C library with the same ruleset text as input. A static hook would have to link it and its dependencies statically. |
| `podman` | `Podman`, task control | podman's REST API on the user's socket | Not a native call at all: podman is Go and has no C API. The JDK already speaks HTTP over a unix socket; it would make the user's `podman.socket` a dependency. |
| `podman unshare` | `EgressPolicy` | — | Joining a rootless user namespace needs a single-threaded process, which neither a JVM nor a native image is. Stays a program. |
| `git` | the gate, `GitSubprocess` | JGit, or libgit2 through FFM | JGit is plain Java and needs no native call. Whatever replaces it, the smart-HTTP path is binary and has to stay in bytes end to end. |
| `systemd-creds` | `SystemdCredential` | — | No public library call for encrypting a credential was found. |
| `tar` | `AgentStaging` | plain Java | Gzip is in the JDK and a tar reader is small. No native call needed. |
| `dnsmasq` | the resolver | — | A long-running server, a program by nature. |
| `sh -c` | `CommandPassphrase` | — | The operator's passphrase command is a shell command by contract. |
| `newuidmap`, `newgidmap` | podman, indirectly | — | Setuid helpers. The privilege is the point, and no call replaces it. |

## Not in scope

**Shell strings the Hetzner driver sends over ssh** (`Leg`, `AgentLeg`). They run on another machine,
which no call in the driver's process can reach. The way to less shell there is to keep the logic
in the driver and send plain commands, or to make the step a `porelo` subcommand. That is done for
the two cases there were - the rootless check under the unit's properties (`UnitProperties`) and the
agent's description (`AgentDescription`); another case would be an
issue of its own ([index](README.md)).

## Plan

A search of the main sources on 2026-10-11 for `ProcessBuilder` and `Command.of` found programs the table does not
list. The first criterion fails today on them:

| Program | Started by | Could be |
|---|---|---|
| `stty` | `Asterisks` (vault) | `tcsetattr(3)` through FFM, in the dynamically linked binaries |
| `systemctl --user` | `SelfReplacement` (daemon), `SetupCommand` | the user manager's D-Bus; the JDK has none |
| `systemd-run` | `Scope` (core) | the same |
| `ssh-keygen` | `VaultPutCommand` | plain Java reads an OpenSSH key; an encrypted one needs bcrypt-pbkdf |
| `ssh` | `ForgeIdentity` | stays: the operator's ssh configuration is the point |
| `kill` | `RunningClearance`, `RunningEgress` | `ProcessHandle.destroy()` for TERM; `-HUP` only through `kill(2)` |
| `rpm`, `dnf`, `apt-cache` | `InstallablePackages` | stays: the package database is theirs |
| agent, build and helper binaries | `InstalledAgent`, `InstalledBuildReader`, `TaskRunner` | stay: Porelo's own |

`tmux` and `chmod` run inside a container through `podman exec`, so they are out of scope as the ssh strings are.

Steps, in order:

1. Red first: a test in `apps/app`, beside `AgentIsolationTest`, which already scans all sources, collects every
   literal program name given to `ProcessBuilder` or `Command.of` in main code and fails on one not in its list. It
   fails today on the programs above.
2. Add the rows above to the table, each with its reason or candidate, and the names to the test's list.
3. Each replacement is an issue of its own. The cheapest first: `tar` in `AgentStaging` as plain Java, then `kill`
   through `ProcessHandle`.

## Acceptance

- Every external program Porelo starts appears here, either with the reason it stays a program or
  replaced by a call. Seen to fail: a search of the sources for process starts finds a program not in the table.
- A replacement is proven on a JVM and in the shipped native image, on both acceptance legs, before
  the program is removed. Seen to fail: the acceptance suite on either leg, run against the native image, goes red
  when the replacing call is broken.
- Nothing in `hooks` or `wire` uses FFM while they are built static, and the test still says so. Seen to fail:
  `NoForeignFunctionMemoryTest` goes red when an FFM use is added to `hooks` or `wire`.

## To be checked

To be checked again with each Java and GraalVM release (Agent Core's):

- **FFM in a static image**: whether a later GraalVM still needs `libc.so.6` for the default lookup.
- **GraalVM's C interface against static musl**, from inside a hook.
- **`setns(2)` and a child process** in a native image: whether the child starts in the namespace of the thread
  that entered it.
- **podman's REST API against its CLI**: what depending on `podman.socket` costs an operator.
