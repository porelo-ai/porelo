# B421 — A Host's Keys Shown Before They Are Trusted

**Status:** Implementing.
**Size:** XS - one option on `credentials trust-host`.

**What must be true, by the operator's word of 2026-11-03.** The one-person start needs no terminal. `porelo credentials
trust-host HOST[:PORT] --json`, without `--fingerprint`, answers the host's keys and fingerprints and records nothing. A window
shows one and asks, then calls it again with `--fingerprint`, which records it.
