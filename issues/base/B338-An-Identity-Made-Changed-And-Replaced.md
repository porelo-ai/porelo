# B338 — An Identity Made, Changed And Replaced

**Status:** Review. Accepted.
**Size:** L (operator, 2026-10-31) - grew from M (estimated 90 minutes from the median of M builds:
the identity verbs and their publishing to the registry and each host) when `identity show --json` was added for the
interface's identity, with its registry and forge fields.

**What must be true.** The contract Frontend builds against is `sprint-6m-b338-contract.md` (Agent Core,
2026-10-31, operator decisions of that day); where this text and it once differed, the contract holds. No private key
is ever read, written or kept by Porelo here: every card is signed through the person's own agent, the one
`SSH_AUTH_SOCK` names, such as KeePassXC.

`porelo identity create --login --name [--email] --key FINGERPRINT|PUBFILE --json` makes the person's first card for a
key their agent already serves, valid 30 days, and keeps it at `$XDG_CONFIG_HOME/porelo/identity/{key.pub,card}`. It
answers `{login, fingerprint, key, card, agent: "holds", forge, next: ["file-card"]}`, the forge's listing of the key
in `forge.key_listed` and never a refusal. It refuses 65 `{"reason", "words"}` with `identity-exists`,
`key-not-in-agent`, `key-not-ed25519`, `key-on-another-card` (with `login`) or `revoked` (with `who_may`); a new key
made into KeePassXC (`--keepassxc DATABASE [--entry]`) is [B356](README.md#b356)'s and refused `key-store-missing`
until then. `porelo identity keys --json` lists the keys the agent serves, each `{fingerprint, key, comment, usable,
reason, key_listed}`, so the person picks the one `create --key` takes; 69 `agent-absent` without an agent.

`porelo identity rotate --json` makes a card for a successor key the agent serves, signed by both keys
([B337](README.md#b337)), the old one through the agent too. `porelo identity revoke --lost --json` makes a card for
a new key without the old one's signature and names the admins who must accept it: the registry's owners, each project
admin whose access file names the person (who takes it with `project access replace`), and each host admin whose host's
access file names them as a project admin; `--dry-run` answers the places `planned` and writes nothing. The places are
read from the registry ([B339](README.md#b339)): the registry itself, each access repository naming them, and each
host those access files name for them or whose own access file names them as a project admin; each host is reached by
its admin's Porelo bringing the new state. Both keep the operation at `$XDG_STATE_HOME/porelo/identity/ops/ID.json`
until every place is `done` and answer `{"id", "action", "fingerprint", "predecessor", "published": [{"where",
"status": "done|pending|refused", "why", "who_may"}]}`, so a person sees where the new card has not arrived yet;
`identity publish --id` retries the places not done, `identity status --id` only reads. A rotation with a revoked key
is refused 65 `revoked` with `who_may`.

`porelo identity show --json` answers who the person on this machine is, public values only: `login`, `name`,
`email` (null when the card has none), `fingerprint`, `key`, `created`, `expires`, `predecessor` (its fingerprint or
null), `card` (its text as filed), `agent` (`holds|not-held|absent`) and `agent_holds_key`, `revoked` (the revocation
list or a registry read here names the key), `card_refused` (null, or why the card does not verify, as `card verify`
says it), `registry`: one `{address, filed: "done|pending|refused|absent", revision, may_file, who_may: [{login, name,
email}]}` per registry this machine reads (`why` added when refused), `forge`: `{login, key_listed: true|false|null,
keys_url, account_url}`, null when no registry is read, and `pending`: `[{id, action}]` of the operations not
finished. It exits 0 whenever a card is kept, whatever its state; 65 `{"reason": "no-identity", "words"}` only when
none is; 66 for an unreadable file. `sole_without_backup` is not answered here: [B346](README.md#b346) adds it.

Part of the design the operator accepted in `porelo-project` on 2026-10-30, *A Person Joins With One Click*: one Ed25519
SSH key per person, the state signed in Git, everything between a person and a host over SSH.

## Acceptance

- `identity create --key` makes a card for a key the agent serves, signed through the agent, that `card verify`
  accepts, and refuses each reason above with exit 65; `--keepassxc` is refused `key-store-missing`.
- `identity keys` answers each key the agent serves, with whether `create --key` takes it and why not.
- `identity rotate` commits a card signed by both keys and answers each place the registry names.
- `identity revoke --lost` answers each place `pending` with the admins named, until one accepts it.
- A place that refuses is answered `refused` with why; the others are still published.
- `identity show --json` answers each field above for a filed card whose key the agent holds, answers a card whose
  key, registry or trust is missing with exit 0 and the field that says so, and exits 65 `no-identity` without a card.
- Seen to fail first: there are no identity verbs today.

## Done

- `porelo identity show`, `keys`, `create --key`, `rotate`, `revoke --lost`, `publish`, `status`, and `porelo project
  access replace`, as `doc/commands.md` says them. Every signature is the agent's; no private key is read or kept.
- Every card made is checked as `card verify` checks it before it is kept or filed; an agent that does not sign, or
  signs with another key or with bytes that are none, is 65 `key-not-in-agent` with its typed answer in every verb.
- Registries, contacts and owners come from one reading (`IdentityFacts`) in every verb: a trust record that cannot be
  read is 66, never left out; a refused card gives no contact; backup owners are never in `who_may`.
- The registry push goes to the address the registry was read from, never to `origin` by name, from a scratch
  repository borrowing the checkout's objects: no push URL, rewrite, credential helper, `core.sshCommand`, hook or
  other setting of the checkout takes part; `protocol.allow=never` with `https` and `ssh` alone, no redirect. It is
  refused (`origin-moved`, `checkout-moved` at the start of the place's `why`) when the checkout's origin is another,
  the checkout is a link, or its `HEAD` is other than the commit read plus this card filed on it alone.
- An operation record is taken only when its `id` is its name, its card verifies, is of the keys it names and of the
  login kept here (else 66 `operation-damaged`); `begin` refuses an id another action keeps; a card recorded but not
  kept (a run cut off between the two) is kept by the next run of the id or by `publish`, from the record, never
  made again. The `identity` and `ops` directories are set `rwx------` and refused, on read as on write, when they or
  a directory between them and the XDG home are links or another account's. No URL is printed or kept with its user
  part (see the rule below).
- Every git on the person's checkout runs through `RegistryGit.git`, the one way `identity`, `registry read` and
  `registry file` reach it: `GIT_NO_LAZY_FETCH=1`, no hooks or fsmonitor, `core.sshCommand=ssh`, no credential
  helper, no redirect, `protocol.allow=never`. The vault's own clone of a checkout (`RegistryRepository`) runs with
  `GIT_NO_LAZY_FETCH=1`, no fsmonitor, `core.sshCommand=ssh` and no credential helper too; it keeps
  `protocol.file.allow=always`, which its fetch from the checkout needs. A filing found at `HEAD` is pushed only
  when its one change is the card at mode 100644 and the commit verifies (in Java, no program run) as signed by the
  card's own key.
- Every directory under the XDG config and state homes read by the identity verbs, `IdentityFacts` and `project
  access replace` goes through one check (`PrivateDirectory.found`, `make` on write), from the home down: a link
  or another account's directory is 66, never read as nothing kept.
- Trust records are read in one place (`TrustRecords.read`), by `IdentityFacts` and `project access replace` alike:
  each `<sha256>.json` with its `.where.json`, both regular files of at most 64 MiB read without following a link,
  the name the hash of the origin, or 66; `registry read` answers as before.
- `project access replace` refuses a person revoked at `--base`, a key of another person (in their chain: every
  key `access.yml` pinned for them along its last 1000 first-parent changes, each in a commit signed by a project
  signer as the follow checks one, and each key a version of their card
  hands on to from one, a version counting when it is theirs, signed as a card must be and handing on from a key of
  the chain) and a new key or predecessor in `revoked.krl` or a registry read here; a lost key's card revokes every
  key of the person's chain at `--base`. A past card version that is no regular file, larger than 64 KiB or does not
  read is skipped; more than 64 versions refuse only the replacement of that card's own person, naming the bound.
- One rule, `Userinfo` in the vault, leaves the user part out of every text and origin these verbs print or keep
  (`RegistryOrigin.of` and `withoutUserinfo` included): in each word with an `@`, all from its start up to and
  including its last `@` goes, but for a `scheme://` the word begins with; nothing else is parsed, so it
  over-redacts by design (a mail address keeps its domain, of two URLs in a word only the last stays). Every refusal of
  `project access replace` is printed through it. The access file's persons are read for the identity verbs through
  `RegistryGit.sealing`, as every other git there.
- Not claimed: a linked or foreign `$XDG_CONFIG_HOME/porelo/revoked.krl`, `project-signers`,
  `$XDG_STATE_HOME/porelo/follow` or the registry state written by `registry read` are read or written as Porelo found
  them before this issue (operator, 2026-10-31); the identity verbs' own directories are checked, and the rest is
  [B360](README.md#b360). Who can push to an access repository can make a person's `replace` refuse (65, naming why) by
  breaking that person's card file; it never makes `replace` revoke or pin a wrong key. A pin counts only when its
  commit is signed by a signer pinned here now, so one signed by a signer handed over since is not in the chain. On the
  person's checkout the lazy-fetch test turns red only with all three of `GIT_NO_LAZY_FETCH`, `core.sshCommand=ssh` and
  `protocol.allow=never` removed; each alone stops it (the vault clone's test turns red with `GIT_NO_LAZY_FETCH` alone).
  Pins older than the last 1000 changes of `access.yml` are not in a chain. With `$XDG_STATE_HOME/porelo` a link,
  `rotate` is refused 66 by the operation directory as well, so its test alone does not show the trust records' check;
  `identity keys` shows it. A filing `registry file` makes in two commits (a refused copy removed first) is not
  recognised and is `checkout-moved`. A push over `https` needs a forge that takes it without a credential helper (the
  checkout's are not used, Porelo's forge token is not lent yet); `ssh` with the agent is the way that works. The owner
  check of the identity directories is not tested (it needs a second account). The commit pushed is the one checked, but
  to the checkout's own branch name: the trust record keeps no branch of the registry. The `place.why` words carry
  `origin-moved` and `checkout-moved` as their first word; the place has no `reason` field, which would change the
  contract. Signers of an access commit are still checked against `revoked.krl` alone, not against the registries.
