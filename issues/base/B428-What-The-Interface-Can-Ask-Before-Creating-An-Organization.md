# B428 — What The Interface Can Ask Before Creating An Organization

**Status:** Idea.

**What must be true.** An interface can ask Porelo, changing nothing on the machine or the forge, whether an organization
can be created at an address, and gets each need's state and its next action in one answer, so a person is led through
the needs in order and the last step is reached only when it can work.

## Why

A new person meets each need only as a refusal, in the order the code checks them. Most needs can be asked today only by
trying the write. The interface owns the order and the words; this issue is what it cannot ask yet.

The normal path starts with keys a person already has, made or kept by hand in their own key store, and often with an
organization they already have; nothing here assumes Porelo made the key. Answers name the key by its fingerprint, and a
person may keep a separate key for logging in.

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

## Acceptance

- Each state of each need above, against a stand-in forge, gives its own answer with its next action.
- Asking changes nothing: no commit, no push, no file written, and the token appears in no answer.
- When every need says ready, `org create` succeeds against the same stand-in; when one says not ready, `org create`
  refuses for that same reason.

## To be checked

- Whether a fine-grained token's permissions can be read at all, and if not, what stands in: each call's own answer. A
  non-owner's fine-grained token answered the membership calls with 403 in [B414](README.md#b414)'s measurement, and an
  owner's is being measured.
- Whether the permissions in the table are enough, by a real run.
- Which rights `org add-read-key` needs, by a real run.
- Whether that call should send the person's token, which would spend their own limit instead of the address's.
- Whether the repository API's `permissions` answers the push question for a person's token.
- What [B414](README.md#b414)'s change to who a first read trusts does to Members: read; the table changes with it.
