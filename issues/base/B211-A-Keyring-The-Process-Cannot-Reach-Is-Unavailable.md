# B211 — A Keyring The Process Cannot Reach Is Unavailable

**Status:** Backlog. Estimated S on 2026-10-19.
**Size:** S - one probe in `KernelKeyring` with its native-image metadata, the reason it gives used where callers
say why nothing is cached, and tests; one run in a container to show it.

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

## Plan

1. **The probe.** `KernelKeyring.available()` stays the one question callers ask, and answers true only when
   libkeyutils loads **and** `keyctl_get_keyring_ID(KEY_SPEC_USER_KEYRING, 0)` returns an id. The call changes
   nothing: with `create` 0 it makes no keyring. It runs once per process and its answer is kept.
   - A refusal (-1) with ENOSYS, EPERM or EACCES means unavailable. Any other errno means unavailable too, since the
     keyring is not shown usable, and the errno is kept for the reason.
   - `KernelKeyring.unavailable()` gives the reason as a sentence, or empty: "libkeyutils is not installed", or "the
     kernel refuses the keyring here (errno 38), as a container's default seccomp profile does".
2. **Its native-image metadata.** The call is a downcall of a shape the vault does not register yet, `jint(jint,
   jint)` with the call state captured. It is added to `porelo-vault`'s `reachability-metadata.json`, and
   `-Pnative,ffm-check` proves it as it proves the others.
3. **The reason where it is said.** The callers that tell a person nothing can be cached take the sentence from
   `unavailable()` instead of naming libkeyutils: `vault unlock`, `vault init`, `vault lock`, `vault import`,
   `vault passphrase`, `doctor`'s keyring probe (its remedy then says what the reason calls for), and
   `PoreloContext`'s asking. `VaultShare`, `VaultClearing` and the daemon only branch on it, and stay as they are.
4. **Tests.**
   - A unit test of the mapping from a call's result and errno to available or the reason, seen red first: ENOSYS
     taken as available today.
   - The 19 test classes that `assumeTrue(KernelKeyring.available())` then skip where the keyring cannot be reached,
     with no change to them.
5. **One run in a container:** `./mvnw -pl vault -am verify` inside the image the draft `porelo.yml` describes, under
   podman's default seccomp profile. Today it gives 9 errors. After this, `KernelKeyringTest`'s tests are skipped,
   and the module is green.
6. **Docs and changelog:** `doc/credentials.md` says that in a container the keyring may be refused, and that every
   vault command then asks for the passphrase. A changelog line comes with it.

## Acceptance

- Under podman's default seccomp profile, `KernelKeyring.available()` is false, and `unavailable()` names errno 38.
  On the host it is true, as before. Seen red first: true in the container today.
- `./mvnw -pl vault -am verify` in the draft image is green, with the keyring tests skipped.
- `porelo doctor` in such a container says the keyring is refused, not that libkeyutils is missing.
- The native executable makes the probe: the FFM check is green with the new shape, and red without it.

## To be checked

- **Whether EPERM or EACCES is met anywhere real**, such as a user namespace without its own keyring, or only
  ENOSYS. The plan treats all three alike, so the answer changes no code, only the sentence.
