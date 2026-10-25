# B286 — The Host's Git Trusts The Account's Authorities

**Status:** Implementing.
**Size:** S - one place in `app-model` that every host-side git environment comes from, a bundle file of Porelo's own,
and its tests; no contract another repository reads.

**What must be true.** Every git command Porelo runs on the host against a remote - following a project, measuring
how far a mirror is behind, the gate pushing approved work - trusts the authorities in the account's
`~/.config/porelo/ca-certificates.pem` besides the system's roots, so an upstream on a forge signed by the host's own
authority is reached, and GitHub still is.

## Plan

- **Where:** `FollowCredential` (`apps/app-model`) is where every host-side git environment is made: `policyFor` and
  each factory feed `environment()`, which `GitGate` and `UpstreamDistance` read through `GitCredentials.Lease`
  (`VaultGitCredentials`), and `UpstreamProbe`, `ForgeIdentity` and `Reconcile` read directly. Today `policyFor` sets
  only `GIT_SSH_COMMAND`, so git over https uses its own bundle alone and the forge's certificate is refused.
- **The bundle:** git's `GIT_SSL_CAINFO` replaces the bundle rather than adding to it, so an https URL gets a
  combined bundle of Porelo's own - the system bundle (`/etc/ssl/certs/ca-certificates.crt` or
  `/etc/pki/tls/certs/ca-bundle.crt`) followed by the account's file - written owner-only and atomically, rewritten
  when its content would differ. No account file, an ssh URL or a local path: no variable, as today.
- **Fail closed:** an account file that holds no certificate is refused as `TrustedCertificates` refuses it; an
  account file with no system bundle to combine it with is refused naming the places looked at, never answered with
  the account's authority alone.
- **Red first:** real `git ls-remote` against a local HTTPS server signed by a test authority, with the environment
  Porelo makes for that URL: today it ends in `SSL certificate problem`.
- **Docs:** `doc/credentials.md`, *An authority of your own*, says the host's git trusts the file too.

## Acceptance

- The red-first test fails on today's code and passes after; with no account file the same git is still refused.
- The bundle holds every certificate of the system bundle and of the account's file, counted from the files.
- A lent token, a lent key and nothing lent each carry the variable for an https URL; an ssh URL carries none.
- On the VM, a project following an https upstream signed by a test authority fetches and the gate pushes to it.
