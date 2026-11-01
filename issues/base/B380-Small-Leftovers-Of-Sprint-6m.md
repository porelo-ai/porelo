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
- Medium: `identity create --keepassxc` asks the master password three times, once for the entry and once for each
  attachment. Found while building [B356](README.md#b356).
- Medium: when the entry is added but an attachment import is refused, the empty entry stays in the database; the
  answer says to remove it. Found while building [B356](README.md#b356).
- Low: a person who never types the master password has keepassxc-cli killed after the process runner's ten
  minutes; with neither an agent nor a terminal the answer is `key-not-in-agent`, not `no-terminal`; a re-run with
  the same `--id` answers without `entry`; with `/proc` mounted `hidepid` the answer is `key-store-refused` with
  words that do not say why. Found while building [B356](README.md#b356).
- Medium: Porelo reads a lone `\r` as a line end in an allowed-signers file, but ssh-keygen reads
  `alice\rssh-ed25519 K` as one line, so Porelo refuses a file git reads. Found while fixing [B376](README.md#b376).
- Low: a line ssh-keygen cannot parse is skipped by git, while Porelo refuses the whole signers file (66); a line
  Java's `strip()` turns into a comment, such as `U+2000#x ssh-ed25519 K`, is skipped by Porelo but read by
  ssh-keygen as the principal `U+2000#x`. Both fail closed. Found while fixing [B376](README.md#b376).
- Low: a task's certificate is refused when a backup key of the project's signers certified it, but a message's
  sender is believed through `MessageSigners.certifiers`, which takes every admitted person's key, a backup key
  among them, should one ever be admitted as a person's key. Left when the task-key messages met the backup keys
  of [B377](README.md#b377).
