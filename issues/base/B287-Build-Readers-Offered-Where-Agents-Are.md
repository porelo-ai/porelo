# B287 — Build Readers Offered Where Agents Are

**Status:** Idea.

**What must be true.** `porelo-setup.sh --list` and the daemon's `Installable` offer the installed and installable
build readers beside agents, transports and homeservers, so whoever prepares a machine sees which forges it can read
without knowing the packages' names.

## The shape

- **porelo-adapters:** each reader package declares `Provides: porelo-build-reader` (the Debian `control` file, the
  RPM's `provides`).
- **porelo:** `catalogue()` in `dist/dist-setup/porelo-setup.sh` and `InstallablePackages` read that virtual name too,
  as kind `build-reader`; an added value of `Installable.kind`, a string in `Tasks1`, so not breaking.
- **Chosen, not always installed:** a reader is installed with `--with`, as it is today, since the forge is the
  project's choice.

## To be checked

- Ask the interface's agent whether its list of what can be installed takes an unknown `kind` as it is, or needs `build-reader`
  first.
