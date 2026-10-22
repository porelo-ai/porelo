# B252 — A Sign-In Collected on No Disk

**Status:** Idea.

**What must be true.** A sign-in made in a login container reaches the vault without lying in plaintext on a disk
on the way: not in the container's own layer, not in a temporary directory of the host.

## Why

`AgentLogin` lets the agent write its sign-in to its own files in the throwaway login container, copies the config
directory out into a temporary directory of the host (`Files.createTempDirectory`, owner-only), lets the agent's
extractor read it, stores it in the vault and deletes the copy. Only the vault stores it, encrypted. But on the way
the plaintext lies in the container's writable layer, which is on disk, and in the host's temporary directory, which
may be on disk too, and both may reach swap.

It is an accepted risk for every agent alike (`doc/decisions.md`, "A sign-in passes through plaintext on its way to
the vault"); this narrows it. Raised for porelo-codex: Codex CLI keeps its sign-in in a keyring, or in a plaintext file it accepts only as
such. A keyring-only sign-in leaves nothing to collect today.

## The shape proposed

- The config directory a login container writes into is a `tmpfs` of the container, so the sign-in never reaches its
  layer.
- It is copied out into `$XDG_RUNTIME_DIR`, which is memory, not into the system's temporary directory.

## To be checked

- That the agents' logins still find their directory with it a `tmpfs` (a login that writes a symbolic link or a
  file elsewhere under the home would miss it).
- Whether swap is worth naming: a `tmpfs` can be swapped out, and only an encrypted swap or none closes that.
