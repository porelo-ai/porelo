# B428 — What The Interface Can Ask Before Creating An Organization

**Status:** Idea.

**What must be true.** An interface can ask Porelo, changing nothing on the machine or the forge, whether an organization
can be created at an address, and gets each need's state and its next action in one answer, so a person is led through
the needs in order and the last step is reached only when it can work.

## Why

A new person meets each need only as a refusal, in the order the code checks them. Most needs can be asked today only by
trying the write. The interface owns the order and the words; this issue is what it cannot ask yet.

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

## The rights it names, at GitHub, for a classic token

| Act | Calls with the token | Needs |
|---|---|---|
| `org create` | `git ls-remote`, `git push`; the memberships of the founder | `repo`, `read:org` |
| `project open` | the same reads and pushes; `POST /orgs/<org>/repos` for a project with no repository | `repo`, `read:org` |
| `org add-read-key` | `GET` and `POST /repos/<org>/<repo>/keys`, with a token on standard input, not the vault's | `repo` expected: GitHub's page names no scope, and it is not measured |
| A person's keys | `<login>.keys`, `/users/<login>/ssh_signing_keys` | No token |

The rights alone do not suffice: the token's account must be the card's login and an active owner of the organization
(`activeAdmin`), or the forge cannot say who founded it, and the card's key must be listed for that login.

## Acceptance

- Each state of each need above, against a stand-in forge, gives its own answer with its next action.
- Asking changes nothing: no commit, no push, no file written, and the token appears in no answer.
- When every need says ready, `org create` succeeds against the same stand-in; when one says not ready, `org create`
  refuses for that same reason.

## To be checked

- Whether the forge says a classic token's scopes in a response header, and what stands in for that with a fine-grained
  token, which answered the membership calls with 403 in [B414](README.md#b414)'s measurement.
- Which rights `org add-read-key` needs, by a real run.
- Whether the repository API's `permissions` answers the push question for a person's token.
- What [B414](README.md#b414)'s change to who a first read trusts does to `read:org`; the table changes with it.
