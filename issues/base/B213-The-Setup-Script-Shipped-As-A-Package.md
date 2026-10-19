# B213 — The Setup Script Shipped As A Package

**Status:** Analyzed.
**Size:** S - one more file in the `porelo` package, the script reading where it came from, docs, and a check in
`package-check`; no new module, no new upload.

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

## Plan

**Where the script goes: into the `porelo` package itself, not a package of its own.** Read on 2026-10-19 in `dist/`
and `build.yml`:
- the script installs `porelo` anyway, and `porelo`'s `Depends` already names what the script would install
  before it (podman 5, nftables, dnsmasq, git, util-linux), so `apt install porelo` on a machine with the
  repository set is the script's own first half;
- a script inside the package has the package's version by construction, which today Publish keeps by naming the
  upload `porelo-setup-<version>.sh` beside the packages;
- a package of its own (`porelo-setup`, `Architecture: all`, no `Depends`) needs a module for jdeb, one for the RPM,
  the upload and indexing steps twice more and `package-check` to cover it: M, for nothing the first way lacks.

So the issue's name stays, and the way is **the script at `/usr/share/porelo/setup/porelo-setup.sh` in `porelo`**,
with this order for a caller: the key by its fingerprint, the source line, `apt install porelo`, then the script.

1. **The script in both packages.** `dist-deb`'s jdeb takes `dist/dist-setup/porelo-setup.sh` at
   `/usr/share/porelo/setup/porelo-setup.sh`, mode 0755, root; `dist-rpm` maps the same. `package-check`'s
   `Parity` then proves both install it, and `LayoutTest` gains that it is byte for byte `dist/dist-setup`'s.
2. **The channel it came from.** The script defaults `--distribution` to `releases`. Run from the package it reads
   the package's own version (`dpkg-query -W -f '${Version}' porelo`, `rpm -q --qf '%{VERSION}' porelo`) and takes
   `snapshots` when that holds `~snapshot`, so a snapshot machine is not switched to releases by a second run. An
   explicit `--distribution` still wins. A test beside `PodmanFloorTest`, which already reads the script, runs the
   choice with a stand-in for the version query, seen red first: a snapshot version answered `releases`.
3. **Run from the package, it changes nothing it finds in place.** With the key, the source line and `porelo`
   already there, its repository step says each is already so, as on a second run today. `package-check`'s clean
   install gains one step: after installing the built `.deb`, run the packaged script with `--show` and see that it
   would write no key and no source line.
4. **Docs.** `doc/getting-started.md` and the README give the way for a machine a program prepares (the key by its
   fingerprint, the repository, `apt install porelo`, the script from `/usr/share/porelo/setup/`), beside the one
   line for a person, and `doc/hosts.md` names it for a playbook's first step. A changelog line.
5. **The download stays.** Publish keeps uploading `porelo-setup-<version>.sh` and `latest`: a person with nothing
   needs it, and [B99](README.md#b99) signs it later.

## Acceptance

- The built `.deb` and `.rpm` hold `/usr/share/porelo/setup/porelo-setup.sh`, byte for byte `dist/dist-setup`'s.
- On a clean Ubuntu with the repository added by the README's lines, `apt install porelo` and then the packaged
  script with `--user NAME` make the account as the downloaded one does, and a second run changes nothing.
- A snapshot install's packaged script keeps `snapshots`; seen red first.
- HO02's role takes this path on Ubuntu.

## To be checked

- **Fedora:** the rpm repository is `gpgcheck=0`, so `dnf install porelo-setup` is checked by nothing but HTTPS
  until [B99](README.md#b99) signs the RPMs, or until the repository's metadata is signed (`repo_gpgcheck=1`), which is not measured.
- **How the test runs the channel choice without root:** the choice as a function the script calls, run alone with
  `bash -c`, or the whole script under `--show` with a `PATH` holding stand-ins for `dpkg-query` and `rpm`.
- **Whether the operator prefers a package of its own** after all, for a machine that should get the script without
  Porelo: the plan above says why not, and the choice is the operator's.
