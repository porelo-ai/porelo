# B428 — What The Interface Can Ask Before Creating An Organization

**Status:** Backlog.
**Size:** L - many modules and a command an interface builds on, against a forge's real answers. Built in the order of its plan, each part on its own.

**What must be true.** An interface can ask Porelo, changing nothing on the machine or the forge, whether an organization
can be created at an address, and gets each need's state and its next action in one answer, so a person is led through
the needs in order and the last step is reached only when it can work.

## Why

A new person meets each need only as a refusal, in the order the code checks them. Most needs can be asked today only by
trying the write. The interface owns the order and the words; this issue is what it cannot ask yet.

The normal path starts with keys a person already has, made or kept by hand in their own key store, and often with an
organization they already have; nothing here assumes Porelo made the key. Answers name the key by its fingerprint, and a
person may keep a separate key for logging in.

## Making the card says what it does

A person with no key is pointed at `porelo identity create --keepassxc`, and a person with their own key at `--key`; no
script does it for them, and no second key is made. For that to be enough:

- **Before it starts, `identity create` says what it will do:** one key, where it will lie, and that no file is written
  for it.
- **At its end, it prints the public key and the page to add it:** at GitHub, `https://github.com/settings/ssh/new`.
- **`identity show` without `--json` prints the public key too.** Today only `--json` answers it.
- **Before `--keepassxc` asks for any password, it reads and names what is missing, changing nothing.** Today it checks only
  for a terminal and that `SSH_AUTH_SOCK` is set, then `keepassxc-cli` asks for the master password at each step, up to three
  times, and only then does the person learn that KeePassXC is not running. It reads:
  - whether `keepassxc-cli --debug-info` lists the SSH agent extension, which a minimal build lacks;
  - whether KeePassXC's own settings, `$XDG_CONFIG_HOME/keepassxc/keepassxc.ini`, say `[SSHAgent] Enabled=true`. Other install
    forms keep them elsewhere, so a file not found is "cannot tell", which is said and does not refuse;
  - whether KeePassXC runs;
  - whether the agent answers at `SSH_AUTH_SOCK`.
- **`porelo doctor` shows the same lines**, each naming its next action, and they do not make a machine not ready: a person
  with their own agent and no KeePassXC stays ready.

## What exists and what is missing

Read in the code, nothing run.

| The need | Today | To add |
|---|---|---|
| A key the agent serves | `identity keys`; `identity show`'s `agent_holds_key` | - |
| A card | `identity show` | - |
| An ssh host trusted, for an ssh address | `credentials trust-host` | - |
| The token in the vault | the vault's state, which the interface reads | - |
| The card's key listed at the forge for its login | `identity show`'s `forge.key_listed`, null while the machine has no organization (`IdentityFacts.forgeAnswer`) | The answer for an address not yet settled |
| A token the forge accepts, and whose it is | `HttpForgeDirectory.account()`, used only by `identity propose` | Accepted, refused or not reached, and whether its login is the card's |
| The token's rights | Nothing reads them | The rights it lacks, named |
| The person owns the organization | Asked only inside the walk of the first commit (`TreeWalk.founding`) | The answer before anything is committed, including an address under a personal account |
| The repository can be read | `credentials check`, but with the account's own git config, so it can pass where `org create` fails | The same call `org create` makes |
| The repository is empty | Only in `TreeRepository.founding`, inside `org create` | The answer on its own; `credentials check` says "read" for a filled one too |
| The push will be taken | Only by pushing | The answer without a push, or "not known until created" |

## The rights it names, at GitHub

The default is a fine-grained token whose resource owner is the organization, with access to all its repositories, since
`project open` makes a repository and pushes to it at once. A new person starts with only a GitHub account, so its steps
come first. Read in the code and in GitHub's permissions page, not yet run:

| Act | Calls with the token | Needs |
|---|---|---|
| Any git read or push, gate approval included | git over https | Contents: write |
| A push touching `.github/workflows` | git over https | Workflows: write; the forge refuses without it |
| Founding and reading the organization | `GET /orgs/<org>/memberships/<login>` | Members: read |
| `project open`, for a project with no repository | `POST /orgs/<org>/repos` | Administration: write |
| `org add-read-key` | `GET`, `POST /repos/<org>/<repo>/keys`, with a token on standard input, not the vault's | Administration: write |
| A person's keys | `<login>.keys`, `/users/<login>/ssh_signing_keys` | No token |

A classic token also works with `repo` and `read:org`, but is no longer the default.

The rights alone do not suffice:

- **The token's account** must be the card's login and an active owner of the organization (`activeAdmin`), or the forge
  cannot say who founded it.
- **The card's key** must be Ed25519 and listed for that login, as an authentication or a signing key; an ssh address
  also needs the authentication listing.
- **A key listed only as a signing key is confirmed by a call without a token**, `/users/<login>/ssh_signing_keys`, since
  the public `<login>.keys` page lists authentication keys alone. If the forge refuses it, as under a rate limit, the
  listing proves nothing and the founding stops with a message that points at the token. The answer must say which it
  was. An https address needs no authentication key at all.
- **The vault's `git.token.<host>` goes to every https repository on that host**, so a token bound to one organization
  fails on a private repository elsewhere; `credentials declare` for the organization's address keeps them apart.

## Plan

1. **Founding at https.** `OrgCreateCommand` allows it, and says that a machine follows an organization by an ssh key of its own, so
   none can follow this one, and how to move: `setup --organization ADDRESS --replace`. The interface offers ssh first.
2. **The card's words.** `IdentityCreateCommand`: the plan first, the public key and the page last. `IdentityShowCommand`: the public
   key without `--json`.
3. **KeePassXC's needs.** A class of its own beside `KeePassXcKey`, read by `IdentityCreateCommand` before any password and by
   `DoctorCommand` as lines that do not make a machine not ready.
4. **The answer before founding.** `porelo org create ADDRESS --check [--json]` in `OrgCreateCommand`, with the needs in the table above,
   each by the same call the founding makes: `TreeRepository` for reading and for empty, `HttpForgeDirectory` for the account and
   the ownership, `IdentityFacts.forgeAnswer` for the listing, at the address asked and not the machine's organization. It writes
   nothing, and a right nothing can read says "not known until created".
5. **The login's pattern.** `[a-z][a-z0-9-]{0,31}` is checked in `PersonCard`, `IdentityCreateCommand`, `HostUnlockCommand`,
   `HostVaultShare` and `TransportLifecycle`. It widens to GitHub's rule after that is read in GitHub's own words. On a host a
   login is also an account's name, whose own limit may stop it there, so the host's repository is asked first. A howto says the
   pattern until then.

**Red first,** for each part: its refusal or answer against a stand-in forge.

## Acceptance

- Each state of each need above, against a stand-in forge, gives its own answer with its next action.
- Asking changes nothing: no commit, no push, no file written, and the token appears in no answer.
- When every need says ready, `org create` succeeds against the same stand-in; when one says not ready, `org create`
  refuses for that same reason.
- With each KeePassXC need missing in turn, `identity create --keepassxc` names it before any password is asked, and
  KeePassXC's state and files are unchanged.
- `identity create` run with `--keepassxc` and with `--key` says its plan first and ends with the public key and the page;
  the private half appears nowhere.

## To be checked

- Whether a fine-grained token's permissions can be read at all, and if not, what stands in: each call's own answer. A
  non-owner's fine-grained token answered the membership calls with 403 in [B414](README.md#b414)'s measurement, and an
  owner's is being measured.
- Whether the permissions in the table are enough, by a real run.
- Which rights `org add-read-key` needs, by a real run.
- How to tell that KeePassXC runs without changing it: `keepassxc --lock`, which `KeePassXcKey` uses later, tells a running one
  from none but locks its databases. Also what `--debug-info` prints on a full and on a minimal build, by a real run.
- Which page `identity create` names when the machine has no organization yet, so no forge is known: GitHub's, or GitHub's
  beside a sentence for any other forge. `--json` keeps answering the same fields, the public key added.
- Whether that call should send the person's token, which would spend their own limit instead of the address's.
- Whether the repository API's `permissions` answers the push question for a person's token.
- What [B414](README.md#b414)'s change to who a first read trusts does to Members: read; the table changes with it.
