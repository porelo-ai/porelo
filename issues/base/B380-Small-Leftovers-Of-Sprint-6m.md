# B380 — Small Leftovers Of Sprint 6m

**Status:** Idea.
**Size:** -, for michi to place - one gathering issue for the small edge cases sprint 6m's readings and fixes left,
as michi decided on 2026-10-31: none of them reaches another person's key, card, socket, mailbox or container, shows
or lends a credential, trusts a key wrongly or loses data. Each one placed is split out with its own size.

**What must be true.** Each point below holds, or is decided not to be worth it.

- An scp address without a user, `host:a@b:c`, is cut like `user:password@host:path` to `b:c`, though git connects to
  `host` with the path `a@b:c`; the two spellings cannot be told apart from the address alone. Found by the fix of
  [B375](README.md#b375).
- Low: if `podman create` succeeds but recording its id fails, the container Porelo made stays without a record;
  `task remove` cannot reach it and the name stays taken. Found while fixing [B378](README.md#b378).
- Low: with `--no-gate`, `/workspace` is never a git checkout, so a stopped no-gate task's census reads as unknown
  and `task remove` needs `--force` (read in `UnhandedWork.CENSUS`, not measured). Found while fixing
  [B378](README.md#b378).
- Medium for the tests only: `TransportConversationsTest` and `MachineTransportsTest` still expect the machine's
  own login to be one a transport takes, so they fail under a login such as `Michi` or `runner_1`. Found while
  fixing [B379](README.md#b379).
- Low: whether a task made earlier under a name beginning `porelo-` still starts again is not measured; a start
  now refuses such a name. Found while fixing [B379](README.md#b379).
- Low: a person recorded as joined under a name outside the rule, by a join before that check, can no longer be
  removed through `talk remove`; the transport never made such an account. Found while fixing
  [B379](README.md#b379).
