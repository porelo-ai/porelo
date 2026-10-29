# B270 — A Forge Token Stored By The Forge's Own Login

**Status:** Accepted. The interface ships first with the pasted token, and this then opens the device-code login in the same terminals.

**What must be true.** A person gets their forge token into a machine's vault, as `git.token.<host>`, by the forge's
own login in a terminal on that machine: a device code shown there, approved in any browser. The person does not make
a token at the forge's web page and paste it.

## Why

With deploy keys gone ([B269](README.md#b269)), git over `https` takes the person's token from the machine's vault.
Today the only way in is `porelo vault put git.token.<host>`, with a token the person made at the forge by hand.
The interface would open
the login in a terminal on the machine, as it does for an agent's sign-in.

Read in the code: `porelo vault authorize` already runs a device-code flow, but only for an `oauth-device` service
entry. Its grant is kept hidden as `grant/<name>` and spent by the broker for a task; it is never stored as
`git.token.<host>` and never lent to the host's own git.

## Decided

- **Whose application: both.** Porelo registers an OAuth application at each forge as the default, its client id
  shipped with Porelo. A person or company may name their own application's client id in the vault entry, which then
  takes precedence.
- **Which kind: an OAuth App**, not a GitHub App, with the scopes git and the forge's API need: **`repo` by default**.
  `workflow` is asked for only when the person asks for it at the login: a token that may change a repository's
  workflows can change what its CI runs with its secrets, and a push of approved work that touches
  `.github/workflows/` without it is refused by the forge, which the gate then names.
- **An accepted risk, written into `doc/decisions.md` when this is built:** the exposure is a token that reaches
  every repository the person can, does not expire until revoked, and sits in each machine's vault. It stays because
  it is simpler and works the same at every forge with a device flow. What would change the answer: a token leak, or
  a company that requires repository-scoped tokens, which the person's own application then covers.
