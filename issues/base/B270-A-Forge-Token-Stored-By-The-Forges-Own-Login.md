# B270 — A Forge Token Stored By The Forge's Own Login

**Status:** Idea.

**What must be true.** A person gets their forge token into a machine's vault, as `git.token.<host>`, by the forge's
own login in a terminal on that machine: a device code shown there, approved in any browser. The person does not make
a token at the forge's web page and paste it.

## Why

With deploy keys gone ([B269](README.md#b269)), git over `https` takes the person's token from the machine's vault.
Today the only way in is `porelo vault put git.token.<host>`, with a token the person made at the forge by hand.
porelo-frontend's [F153](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f153) would open
the login in a terminal on the machine, as it does for an agent's sign-in.

Read in the code: `porelo vault authorize` already runs a device-code flow, but only for an `oauth-device` service
entry. Its grant is kept hidden as `grant/<name>` and spent by the broker for a task; it is never stored as
`git.token.<host>` and never lent to the host's own git.

## Open questions

- **Whose OAuth application the login uses**: one Porelo registers at each forge, whose client id ships with Porelo,
  or one the person or their company registers, named in the vault entry. A forge's device flow needs a registered
  application; which one decides who can revoke it and what the consent screen says.
- **Which rights the token asks for**: read for the project's own repository and write for its work repositories, as
  porelo-project [PJ69](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj69) says, and whether a forge can narrow a device-flow token that far.
