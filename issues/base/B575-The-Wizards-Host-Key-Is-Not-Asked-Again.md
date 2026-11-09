# [B575](README.md#b575) — The Wizard's Host Key Is Not Asked Again

**Status:** Review. Filed 2026-11-09 under ST290, from the fourth move.
**Size:** S - one ssh call that does not take the pinned known-hosts file, and its test.

**What must be true.** After the setup wizard compared a host's key with the person and stored it, no later step of
the setup asks for that key again (an OpenSSH askpass dialog or a terminal prompt).

## Plan

The call missing Porelo's pinned file is `identity propose`'s `timeout 12s ssh -T ... git@HOST` in
`IdentityProposeCommand.sshLogin`. It used OpenSSH's default known-hosts files instead of the record written by
`HostKeys.trust` (`credentials trust-host` and the daemon's `TrustHostKey`). It already used `BatchMode=yes` and
`StrictHostKeyChecking=yes`: the missing file explains a lost login, but does not establish the source of an askpass
dialog.

- Give identity discovery the same Porelo known-hosts file, keep strict checking and batch mode, and name an unknown
  or changed host key in its notes rather than silently returning no login.
- Exercise the CLI with a stand-in `ssh` on PATH that records its arguments and selected environment, and an askpass
  stand-in that records any invocation. Cover the confirmed key, an unknown key and a changed key, red first; remove
  batch mode deliberately to prove the dialog guard.
- The other connection paths carry their policy: host unlock and host refresh use `PinnedHostSsh`; `RemoteStart`,
  `OrgFetchCommand`, `ForgeIdentity` and `FollowCredential` all supply their known-hosts file, strict checking and
  batch mode. `TakeProject` uses `ProjectTaking` and `OrganizationAccess.lent`, which preserves
  `FollowCredential`'s `GIT_SSH_COMMAND` through the Git process's chosen environment; `setup --organization` uses
  the same lending through `OrgAccess`.

## As built

- Identity discovery shares `PinnedHostSsh`'s strict SSH options: no account or system SSH configuration, global
  known-hosts files, host-key command, DNS keys, connection reuse or agent forwarding. Its chosen environment holds
  only `PATH`, `SSH_ASKPASS_REQUIRE=never` and the selected `SSH_AUTH_SOCK`, when present.
- The known-hosts path is quoted for OpenSSH, including spaces; a double quote or newline in it is refused before
  SSH starts. Discovery uses the organization's configured SSH port, otherwise 22, and passes IPv6 hosts without
  URL brackets. Notes name the SSH host and port; only an unknown key gets a `credentials trust-host` instruction.
- `FollowCredential.sshCommand` and `ForgeIdentity.ask` share these options too, including with no lent key.
  The former preserves both shell and OpenSSH path quoting; the latter uses a chosen environment with the leased
  credential. `PinnedHostSsh` keeps its host-key algorithm restriction and temporary pin cleanup.
- `IdentityProposeSshTest` asks real `ssh -G` to interpret discovery's options against a fixture with additional
  trust, forwarding, a proxy and a connection master, using a state path with consecutive spaces. It also checks
  the selected environment, unsafe paths, configured SSH port, IPv6 and the different notes for unknown and
  changed keys. The new assertions failed on behavior before the fixes; the related fetch and credential probe
  have their own effective-options tests.
- `doc/commands.md` and `changelog/unreleased/identity-proposal-pinned-host.yml` describe the discovery policy.

## To be checked

- The setup interface's own SSH calls (first login, key comparison, tunnel and host setup) are outside these
  command tests. Reproducing its dialog and checking those calls remains an interface test.
