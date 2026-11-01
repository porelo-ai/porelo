# B360 — The Person's Own Files Read Only As Themselves

**Status:** Idea.
**Size:** M, proposed - the directory check the identity verbs use, applied to every file Porelo reads or writes
under the person's configuration and state directories.

**What must be true.** The identity verbs ([B338](README.md#b338)) read and write their own directories only when
each, from the XDG home down, is a directory of the person's own account and no link. The rest of Porelo reads
and writes the person's files as it finds them. [B338](README.md#b338) leaves these out:

- `$XDG_CONFIG_HOME/porelo/revoked.krl`, followed through a link by the configuration gate and `ssh-keygen -Q`;
- `$XDG_CONFIG_HOME/porelo/project-signers`, read without refusing a link;
- `$XDG_STATE_HOME/porelo/follow`, whose followed projects and clones are read through links;
- the registry state `porelo registry read` writes.

Each is read or written only when its directories pass the same check, and a file that does not is refused with 66
naming it, never followed. As for the registry's trust record
([decision](../../doc/decisions.md#a-registrys-trust-record-is-checked-not-hardened-against-whoever-writes-the-state-directory)),
whoever can write these directories can change Porelo's configuration and binaries too, so this is a guard against
mistakes and a misplaced link, not against such an attacker.

## Acceptance

- A linked `revoked.krl` or `project-signers`, or one in a directory of another account, is refused with 66 by every
  command that reads it, and nothing reads its target.
- A linked `follow` directory or followed clone is refused with 66 by `project follow`, `project access replace` and
  the identity verbs.
- `registry read` writes its state only into directories that pass the check.
- Seen to fail first: each is followed today.
