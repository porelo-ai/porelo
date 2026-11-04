# B360 — The Person's Own Files Read Only As Themselves

**Status:** Idea.
**Size:** M, proposed - the directory check the identity verbs use, applied to every file Porelo reads or writes
under the person's configuration and state directories.

**What must be true.** The identity verbs (`identity create`, `rotate`, `revoke`) read and write their own directories only when
each, from the XDG home down, is a directory of the person's own account and no link. The rest of Porelo reads
and writes the person's files as it finds them. The identity verbs leave these out:

- `$XDG_CONFIG_HOME/porelo/revoked.krl`, followed through a link by `Revocations` and `ssh-keygen -Q`;
- `$XDG_CONFIG_HOME/porelo/organization.yml`, read by the organization commands;
- `$XDG_STATE_HOME/porelo/organization-projects`, whose project records and clones lack the identity directory guard;
- the organization's trust state under `$XDG_STATE_HOME/porelo/organization`.

`project-signers`, manual follows and `registry read` are gone; these four replace them, and the directory guard
does not cover them yet.

Each is read or written only when its directories pass the same check, and a file that does not is refused with 66
naming it, never followed. As for the organization's trust record
([decision][trust-record-decision]),
whoever can write these directories can change Porelo's configuration and binaries too, so this is a guard against
mistakes and a misplaced link, not against such an attacker.

## Acceptance

- A linked `revoked.krl` or `organization.yml`, or one in a directory of another account, is refused with 66 by every
  command that reads it, and nothing reads its target.
- A linked `organization-projects` directory or project clone is refused with 66 by the organization commands and
  the identity verbs.
- The organization's trust state is written only into directories that pass the check.
- Seen to fail first: each is followed today.

[trust-record-decision]:
../../doc/decisions.md#an-organizations-trust-record-is-checked-not-hardened-against-whoever-writes-the-state-directory
