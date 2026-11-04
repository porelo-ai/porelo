# B435 — One Line Installs Everything On Fedora And RHEL

**Status:** Idea.

**What must be true.** On Fedora and RHEL, `sudo dnf install 'porelo*'` installs all of Porelo, on every published channel, and
stays true: the build says so when the line stops working, as [B429](README.md#b429) does for apt.

## To be checked

- Whether a runner or a container can resolve `dnf` against a channel without a rented machine.
- What the line needs beyond apt's: repository metadata is signed and the RPMs are not yet.
