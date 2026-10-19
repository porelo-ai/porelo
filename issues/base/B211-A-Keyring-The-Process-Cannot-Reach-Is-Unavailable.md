# B211 — A Keyring The Process Cannot Reach Is Unavailable

**Status:** Accepted, 2026-10-19.

**What must be true.** `KernelKeyring.available()` answers true only where this process can use the kernel keyring,
not where libkeyutils merely loads. Where the system call is refused, as under a container's default seccomp profile,
the keyring counts as unavailable: Porelo says so where it would cache, and the tests that need a keyring skip
instead of failing.

## Why

Measured on 2026-10-19, building porelo inside the image its draft `porelo.yml` describes, under podman's default
seccomp profile: `./mvnw -pl vault -am verify` gave 9 errors, all in `KernelKeyringTest`, "Cannot store the
passphrase in the keyring (errno=38)". errno 38 is ENOSYS: the profile refuses `add_key` and `keyctl`. With that
class left out, the module was green. `available()` asks only whether the library loads, so 19 test classes that
`assumeTrue(KernelKeyring.available())` run against a keyring the process cannot reach. porelo cannot run its own unit
tests in a Porelo task until this holds.

## The shape proposed

- `available()` probes the keyring once per process, by a call that changes nothing (reading the user keyring's id),
  and keeps the answer. ENOSYS or EPERM from it means unavailable.
- A test seen red first under a seccomp that denies `keyctl`, as podman's default does.
- Porelo's own answers where it caches a passphrase name the cause, so "unavailable" in a container reads as that,
  not as a missing library.
