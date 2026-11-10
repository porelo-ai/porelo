# B270 — A Forge Token Stored By The Forge's Own Login

```json
{
  "$schema": "issue-1.json",
  "id": "B270",
  "title": "A Forge Token Stored By The Forge's Own Login",
  "state": "Accepted",
  "size": "-",
  "priority": "Medium",
  "rank": 8,
  "blockedBy": [],
  "covers": "A person gets their forge token into a machine's vault as `git.token.<host>` by the forge's own device-code login in a terminal there, never by pasting a token made by hand; at GitHub a public GitHub App with user access tokens and their refresh, after the trial moves"
}
```

To be built after the trial moves. The interface ships first with the pasted token, and this then
opens the device-code login in the same terminals.

**What must be true.** A person gets their forge token into a machine's vault, as `git.token.<host>`, by the forge's
own login in a terminal on that machine: a device code shown there, approved in any browser. The person does not make
a token at the forge's web page and paste it.

## Why

With deploy keys gone ([B269](README.md#b269)), git over `https` takes the person's token from the machine's vault.
Today the only way in is `porelo vault put git.token.<host>`, with a token the person made at the forge by hand.
The interface would open
the login in a terminal on the machine, as it does for an agent's sign-in.

Read in the code: `porelo vault authorize` runs a device-code flow for an `oauth-device` service entry and a
redirect flow for an `oauth-code` entry. Its grant is kept hidden as `grant/<name>` and spent by the broker for a task;
it is never stored as
`git.token.<host>` and ## Decided

The operator decided on 2026-11-08, replacing the OAuth App decided before:

- **A public GitHub App, used through the device flow and user access tokens.** It is registered for `porelo-ai` by
  the operator, after a trial with a test app. Both come after the trial moves. Its client id ships with Porelo.
- **A person's or company's own app:** they may name their own app's client id in the vault entry, which then takes
  the place of Porelo's (the operator, 2026-11-08).
- **What a user access token is, as GitHub documents it** (to be measured in the trial, not taken as read):
  - it acts only where the app is installed, and only with what both the app's permissions and the person allow;
  - it expires after eight hours and comes with a refresh token;
  - the device flow is a setting the app has to have turned on.
- **So the vault keeps the refresh token** beside `git.token.<host>`, hidden as `grant/<name>` is, and renews the
  token before it is lent. A renewal that fails says so and asks the person to log in again; it never falls back to
  an older token.
- **The app's permissions:** contents and pull requests read and write, metadata read. Workflows only when the person
  asks for it at the login. A token that may change a repository's workflows can change what its CI runs with its
  secrets, and a push of approved work that touches `.github/workflows/` without it is refused by the forge, which
  the gate then names.
- **Forgejo and other forges** keep their OAuth application with a device flow where they have one. This decision is
  GitHub's.

## To be checked

- In the trial with the test app: the device flow, the token's lifetime, a renewal, and a push into a repository where
  the app is not installed, with the words GitHub answers.
- The accepted risk to write into `doc/decisions.md` when this is built. It is narrower than the OAuth App's (only where
  the app is installed, eight hours), but a refresh token in each machine's vault still renews it.
