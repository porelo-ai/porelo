# B213 — The Setup Script Shipped As A Package

**Status:** Accepted.

**What must be true.** A machine that has added Porelo's apt repository by its pinned key can get the setup script
through that repository, as a package `porelo-setup`, and run it from where the package put it. Nothing of the script
then comes over a path only HTTPS vouches for: apt checks the package as it checks `porelo` itself.

## Why

A host a playbook brings up runs `porelo-setup.sh` as root. Today it is fetched from Artifactory as
`porelo-setup-latest.sh`, and its `.sha256` comes from the same server, so nothing proves the download against
that server. The packages beside it are checked: the apt repository is signed with the key whose fingerprint
`doc/getting-started.md` names, and the script itself checks that fingerprint before it adds the repository.

So a caller who adds the repository first, by the pinned fingerprint as the README's manual lines do, can get the
script by the same check. That needs no private key in any CI secret, and nothing new to verify. The operator chose
this way over a detached signature on 2026-10-19; the signature stays with [B99](README.md#b99).

## The shape proposed

- A package `porelo-setup`, `.deb` and `.rpm`, holding the script at a fixed path, e.g.
  `/usr/share/porelo/setup/porelo-setup.sh`, built from `dist/dist-setup` like the other packages and published
  beside them.
- The script, run from there on a machine whose repository is already set, finds the key and the source line in
  place and changes nothing of them, as it already does on a second run.
- `doc/getting-started.md`, `doc/running.md` and the README name the way: the key by its fingerprint, the
  repository, `apt install porelo-setup`, then the script.
- The one-line download stays for a person on a machine with nothing; it is no worse than today.

## To be checked

- **Fedora:** the rpm repository is `gpgcheck=0`, so `dnf install porelo-setup` is checked by nothing but HTTPS
  until [B99](README.md#b99) signs the RPMs, or until the repository's metadata is signed (`repo_gpgcheck=1`), which is not measured.
- **Which package depends on which:** whether `porelo-setup` depends on nothing, since the script installs `porelo`
  itself, and whether the script's version must match the `porelo` it installs.
- **The script's own options** when run from the package: whether `--distribution` defaults to the channel the
  package came from.
