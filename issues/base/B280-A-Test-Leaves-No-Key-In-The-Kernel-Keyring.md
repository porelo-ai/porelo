# B280 — A Test Leaves No Key In The Kernel Keyring

**Status:** Review: published since `0.5.1~snapshot.329`; green on the VM in its acceptance suite.
**Size:** S - a JUnit extension in the test code of `apps/app` and `vault`, the tests it finds leaking, and one validated prefix override in `VaultPaths`.

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

1. **`VaultPaths.vaultKeyringKey()`** takes its prefix from `porelo.keyring.prefix`, defaulting to `porelo`.
   Only `[a-z0-9-]{1,64}` is accepted; every other value falls back to `porelo`. The share key still appends `:share`.
   This is the only main-code change, with one Javadoc line explaining that tests keep their caches apart from real
   cached passphrases. `PoreloPathsTest` holds the unset default, configured prefix, and invalid-value fallback,
   restoring the system property in `finally` and running in isolation while it changes that JVM-wide setting.
2. **`KeyringLeakGuard`** sets the property once per JVM before any test runs, to `porelo-test-<pid>` unless it is
   already set. An invalid value supplied at test-JVM startup is refused rather than falling back to real account
   keys. It captures the valid prefix and watches only user-key descriptions starting with `<prefix>:vault:`.
   Before each test it records that exact reference set from `/proc/keys`; afterwards it forgets new keys and fails,
   naming every leak. No other key is read through the keyring API, forgotten or reported. Unavailable keyrings or
   unreadable listings disable the guard. Both modules keep JUnit autodetection and their service registrations.
3. **The leaking tests** forget their key in `finally`, as `VaultPassphraseStdinTest` does. Tests with independent
   key names, including `KernelKeyringTest`, stay unchanged.
4. **Red first:** the configured-prefix path test fails against the old fixed prefix. A real `porelo:vault:<random>`
   key stored during a guarded test must survive the callbacks without being reported, then be forgotten by its own
   fixture's `finally`. A real key under this JVM's prefix must be forgotten and reported. Synthetic listings assert
   the exact reference set, excluding real-vault keys and other JVM prefixes. Invalid-value validation is also seen
   to fail when deliberately disabled.
5. **Validation:** format changed modules and run both complete module suites twice. Record separate `/proc/keys`
   counts for `porelo:vault:` and `porelo-test-` before, between and after. Report environment failures without
   treating failure-continuation mode as a green suite.

## Acceptance

- Real vaults keep `porelo:vault:<hash>` and `porelo:vault:<hash>:share` when the property is unset or invalid.
- A configured valid prefix applies to both vault key names; concurrent test JVMs use their own PID prefix by default.
- A real-vault key created during a test remains cached and unreported; a leaked key under the guard's own prefix is
  forgotten and named in a failing test. Other test JVMs' keys are also excluded from the reference set.
- Two complete runs leave both the account's real-vault key count and its test-prefix key count unchanged.

## Implementation findings

- `porelo-testing` already supplies unpublished fixtures to both modules. The guard and its tests live once in
  `testing/src/keyring-test/java`, added to each module's test sources by the existing build-helper plugin. Putting
  them in the fixture jar would require a dependency back to `vault`, whose tests already depend on that jar.
  JUnit's EngineTestKit is absent from these modules' test classpaths, so the guard's tests call its callbacks with
  a fake `ExtensionContext`.
- `KernelKeyring.forget()` searches by description and unlinks the key from the user keyring; it exposes no operation
  to invalidate a serial. `/proc/keys` can retain an unlinked key until kernel garbage collection. A real test that
  forgot its key reproduced a false leak report, so the guard calls `forget()` on every new description and reports
  `CLEARED` and `UNKNOWN`; `NOTHING_CACHED` means the test already removed it.
- The named `VaultClearingTest` already has an `@AfterEach` that forgets its temporary vault's key in this checkout.
  With the guard registered, the unchanged test passes.
- The full suites found 31 leaking cases: three in `VaultInitCommandTest`, two in `BuildTargetTest`, and 26 in
  `BuildStatusCommandTest` including parameterized cases. The first two classes now forget each cached passphrase in
  the test's `finally`; the build-status class forgets it in the shared command helper's `finally`.
- An account-wide delta cannot identify the process that created a key. A concurrent build or real unlock can add
  one while a test runs, so the guard owns only its captured process prefix; property changes inside path tests must
  never change that captured reference set.
