# B280 — A Test Leaves No Key In The Kernel Keyring

**Status:** Analyzed.
**Size:** S - a JUnit extension in the test code of `apps/app` and `vault`, the tests it finds leaking, no main code.

**What must be true.** A run of porelo's tests leaves the account's kernel keyring as it found it, so repeated builds
on one machine never fill the account's key quota and stop its containers from starting.

## Why

On this development machine every agent's builds run as one account. Its kernel keyring held 200 of 200 keys, 196 of
them `porelo:vault:<hash>` passphrase caches without a timeout, one per vault path a test had made in a temporary
directory. With the quota full, `crun` refused every container ("create keyring: Disk quota exceeded") and
`KernelKeyringTest` failed with errno 122. One leaking test is
`VaultClearingTest.afterAClearInitStartsAsOnANewMachineAndTheOldPassphraseIsNotCached`, which runs `vault init`
against the real keyring and never forgets the key.

A cache without a timeout is what `vault unlock` promises (until logout or reboot), and one real vault holds one key,
so the product does not change; the tests do.

## Plan

1. **`KeyringLeakGuard`**, a JUnit 5 extension in the test code: before each test it reads the names of the
   account's `porelo:vault:` keys from `/proc/keys`; after the test it invalidates every such key that was not there
   before and fails the test, naming the key. Where `/proc/keys` cannot be read it does nothing. It is registered for
   every test of `apps/app` and `vault` by `junit.jupiter.extensions.autodetection.enabled` and a
   `META-INF/services` entry, so a new test cannot forget it.
2. **The leaking tests** the guard finds forget their key in `finally`, as `VaultPassphraseStdinTest` does.
3. **Red first:** with the guard registered and `VaultClearingTest` unchanged, that test fails naming its key; a test
   of the guard itself stores a key under `porelo:vault:` and is seen to fail, and is seen to leave nothing.

## Acceptance

- Two runs of the whole reactor leave the count of the account's `porelo:vault:` keys in `/proc/keys` unchanged.
- A test that stores such a key and does not forget it fails, naming the key, and the key is gone afterwards.
