# B260 — Only Porelo's Own Source Installs An Agent

**Status:** Backlog.
**Size:** M - the setup script's install and catalogue, and `InstallablePackages`, bound to Porelo's package source on
both families, measured in Ubuntu and Fedora containers.
**Estimate:** 180 min, no like issue measured.

**What must be true.** What the setup script's `--with` installs, and what its `--list` and the daemon's `Installable`
offer, comes from Porelo's own package source and never from another source configured on the machine, on apt and on
dnf. On Fedora with dnf5 the catalogue names every agent the source offers.

## Why

A host's play installs every agent the catalogue lists ([B258](README.md#b258)), as root. The catalogue reads the
providers of `porelo-agent` from every configured source, so a package from another source that declares it would be
listed and installed.

A first attempt checked the package manager's candidate by parsing `apt-cache policy` and `dnf repoquery
--latest-limit=1`. Two reviews found that bypassable:
- apt downloads the first source of the candidate version, so a foreign source with the same name and version earlier
  in the list wins while a Porelo line further down passes the check;
- signed or negative pin priorities and `(phased N%)` headers let a Porelo line count for a foreign candidate;
- dnf's solver honours repository `priority=`, which `repoquery --latest-limit=1` does not, so a foreign repository
  with a lower version and a higher priority wins `dnf install`;
- a `[porelo]` repository with several base URLs, a metalink or a mirrorlist passed a prefix check.

So the install itself is bound to the source, rather than the installer's choice predicted.

Measured by a review in a `fedora:44` container, and true before any of this: dnf5's `repoquery --qf '%{name}'`
writes no newline between records, so the catalogue reads all names as one and finds no agent at all on Fedora 43
and 44, and it reads versions and summaries wrong.

## To be decided in the analysis

- On dnf: `--from-repo=porelo` on dnf5 and `repository-packages porelo install` on dnf4, or another way that takes
  the named packages from Porelo's repository and their dependencies from the distribution.
- On apt: the URIs `apt-get install --print-uris` would fetch for the named package, checked against Porelo's source;
  or a pin that keeps every other source's copy of such a package out.
- How the catalogue and `Installable` read only Porelo's source, the same way in shell and Java.

## Tests

Red first against the reviewers' cases: a foreign source with the same version earlier; a foreign source with a
higher version or priority; a pinned or phased version header; several base URLs, a metalink and a mirrorlist; a
look-alike address on both families; dnf5's output without newlines.
