# [B575](README.md#b575) — The Wizard's Host Key Is Not Asked Again

**Status:** Implementing. Filed 2026-11-09 under ST290, from the fourth move.
**Size:** S - one ssh call that does not take the pinned known-hosts file, and its test.

**What must be true.** After the setup wizard compared a host's key with the person and stored it, no later step of
the setup asks for that key again (an OpenSSH askpass dialog or a terminal prompt).

## Plan

Read in the code: porelo pins a host's key through `PinnedHostSsh`, and other ssh calls set their own
`UserKnownHostsFile`/`StrictHostKeyChecking` (`RemoteStart`, `OrgFetchCommand`, `ForgeIdentity`, `FollowCredential`,
`IdentityProposeCommand`). One of the calls after the compare reaches the host without the pinned file and so asks.

- Find the call the wizard's next steps make after the compare (the host's first contact by the placing); route it
  through `PinnedHostSsh` or give it the pinned file and `StrictHostKeyChecking=yes`, so an unknown key is refused,
  never asked.
- Red first: a test of that step with an askpass program that records being called, against a host whose key the
  wizard stored: today it is asked.

## To be checked

- Which call it is: the window's placing calls more than one porelo command; read their order on a fresh machine.
