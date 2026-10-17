# B196 — The Fedora Repository Checked By Its Key

**Status:** Idea.

**What must be true.** On Fedora, as on Debian and Ubuntu, a machine takes Porelo's packages only from a repository
whose signature checks against Porelo's package key. Today `porelo-setup.sh` writes `/etc/yum.repos.d/porelo.repo` with
`gpgcheck=0` and no `repo_gpgcheck`, so dnf installs whatever that address serves. Yet the RPM repository's metadata
is signed by `porelo-packages` (`repodata/repomd.xml.asc`), and on Debian and Ubuntu the key is checked against the
fingerprint the script pins before apt trusts the repository. The Fedora repository gets the same treatment: the key
fetched, checked against that pinned fingerprint, imported, and the repository written to check against it. Found on
2026-10-17, while Fedora's VM moved to Porelo.
