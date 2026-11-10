# [B544](README.md#b544) — The Gathered Push And Pull Take The Central Token From The Vault

**Status:** Review. Filed 2026-11-09 from the porelo-host CLI proposal the operator took (a module `host/` of porelo,
built by Agent Core and read by Agent Machines; built all at once: every issue of it on one branch, one hand-over, its real test
move 5). Priority to be set by the operator; nothing built before the operator's word after move 4.
**Size:** M.
**Blocked by:** [B543](README.md#b543).

**What must be true.** `porelo host push` and `porelo host pull` do what `porelo-host-push` and `--pull` do, reading the central token from
the person's vault (the passphrase only when it is shut) instead of a hidden prompt on stdin; a missing entry is
refused naming the vault key and how to store it. The host keeps only `porelo-host project check-central` ([B546](README.md#b546)) for
`down`.

## Why

Steps 3 (push, pull) and 3a. The proposal (the operator's rule: what cannot run in user mode and is not done by a person is `porelo-host`'s;
the person it acts for is `SUDO_USER`, never an argument) places every host script's verb; porelo-host's collection
change is Machines' own, in porelo-host: the collection installs the `porelo-host` package, its units, timers,
sockets and sudo rules call it, and every host script goes. The contract between the two: porelo publishes the
`porelo-host` package with every verb of these issues, at `/usr/sbin/porelo-host`, before the collection pins it. `host/` is built by Agent Core and read by Agent Machines, the domain owner of the host scripts it replaces.


## As built

- `porelo host push <project> [<repository>...]` and `porelo host pull <project> [<repository>...]` run as the person. Root's checked `/etc/porelo-host/forge.conf` derives `<forge>.central` and `<forge>.person`. Missing vault state, non-interactively shut vaults and missing entries in readable vaults refuse before any process runs. Argument files are never expanded.
- Repository syntax, duplicate arguments and trusted programs are checked before opening the vault. Interactive passphrase prompts wait until account/configuration, repository selection and CA checks succeed. A passphrase is kept for the command only; device-only and host-share vaults name their available unlock route, and a stale cached opener can be replaced at a terminal.
- The only privileged verb is the existing `forge issue-token`, taking the person from `SUDO_USER` and checking `SUDO_UID`. Sudo runs with a chosen minimal environment (`PATH=/usr/sbin:/usr/bin`, `LC_ALL=C`) and cwd `/`, as the other privileged calls do. The central credential never reaches it. Its exact sudo rule and role are documented in the section-8 manual.
- The person's private push configuration is checked on pinned, no-follow, nonblocking descriptors, with a 64 KiB and ten-second budget. Descriptor downcalls and metadata live in core, covered by its existing FFM-check profile; flags are selected for x86-64 and AArch64 and unsupported architectures refuse. Numeric identity comes from `getuid`; file ownership comparisons preserve unsigned Linux UIDs.
- Both gathered transfers and the removal check use `RootOwnedAuthority`, extracted from the removal check's component walk. Root-owned CA links and ancestor links are resolved with the same root-ownership, directory/file permission, trust-anchor and link-depth checks. Git receives the resolved regular readable bundle path.
- Central credential occurrences in actual configuration values refuse before local token rotation. Local credential occurrences refuse after issuance. A fresh local token remains usable if its vault update fails; mutable buffers are cleared on replacement and every exit. A silent issuer failure still names the failed operation. Host forge vault entry names are recognized when stored.
- Git receives only its destination's scoped header in a chosen environment. Redirects, proxies, helpers, hooks, automatic GC and background maintenance are disabled. Process output is bounded to 16 MiB per stream and a shared two-second post-exit read budget; incomplete output is withheld. Intentional shutdown stops the owned process tree without reporting a remote Git failure.
- Push stays plain `main:main`, stopping at the first refusal and returning missing central commits as local `central/main`. Pull changes only that local mirror branch, including rewritten central history. Neither approved main is forced. The root-only `project check-central` remains the removal check; `down` is separate.
- Unit tests cover preflight ordering, credentials, ownership, selection and failure outcomes. Narrow integrations exercise actual bundle links, descriptors, FIFO refusal, Git refs, inherited pipes, process shutdown and complete output capture. A throwing stdin fixture and existing argument files guard the real CLI boundaries.
