# B269 — The Forge Reached With The Person's Token Alone

**Status:** Analyzed. porelo's part of porelo-project
[PJ69](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj69).
**Size:** M - a contract change within `Tasks1` (a retired method), about fourteen classes in four modules, four
pages and an acceptance feature; mostly removal.

**What must be true.** porelo neither makes nor names a deploy key. A machine reaches a project's repositories with
the person's own token from its vault, as `git.token.<host>`. Following, clearing, unfollowing and `doctor` say
nothing of deploy keys, and the pages describe the token as the one way to a forge.

## Why

The operator decided in porelo-project's issue above that deploy keys per machine and repository go entirely. A company seldom lets a person
create them, and the person's token, which the interface held to make them, already gives the same access.

Read in porelo: the token path is there today. `FollowCredential` lends every git command the host runs against a
remote, the follow, the mirror's fetch and the gate's push, a token from the vault through git's own credential
helper, as `git.token.<host>` or `git.token` for an `https` URL (`GitCredentialNames.candidatesFor`). What depends on
deploy keys is:
- making them: `porelo credentials deploy-key` and the daemon's call for it;
- forgetting and naming them: `project unfollow`, `project clear` and `porelo clear`, `vault clear`, and
  `project default`;
- `doctor`'s report of a deploy key unused for 45 days: **not built**. It is [B153](README.md#b153)'s, Estimated, and
  `doc/decisions.md`'s index row *Unused access is reported* already names it;
- `doc/project-file.md`'s *Deploy keys*, `doc/commands.md`, and two decisions in `doc/decisions.md`.

Read in today's code, beside the above:
- **`DeployKeys`** (`app-model`) makes, holds and forgets keys as vault entries `deploy:<project>:<repository>` (and
  the older `deploy-<project>-<repository>`), each with a declaration in `credentials.yml`. Fourteen main classes
  name it, among them `TransportLifecycle`, which takes the machine's name from `DeployKeys.hostName()`.
- **The daemon:** the method `DeployKey` and the error `DeployKeyRefused` in `20-project.varlink`; the type
  `MachineDeployKey` in `10-common.varlink`, carried as `keys` by `RemoveFromDefault`, `Unfollow`, `Clear` and
  `VaultClear`.
- **An ssh URL never takes a token**: `CredentialDeclarations.checkPossible` refuses one, and `FollowCredential` lends
  a declared key, else `git.ssh.<host>`, `git.ssh` or `ssh.default` from the vault. An `https` URL takes
  `git.token.<host>` or `git.token` through git's credential helper.
- **`doc/decisions.md`'s** *Clearing names what only a person's credentials can remove* says the machine holds no
  forge token. With the person's token in its vault that is no longer true.

## Decided

- **Porelo makes no deploy key any more.** `porelo credentials deploy-key` goes. The daemon's `DeployKey` stays in
  `Tasks1`, since removing a method breaks the interface. It is described as retired and answers every call with
  `DeployKeyRefused`: "Porelo makes no deploy keys; store your forge token as git.token.<host> and follow the
  repository's https address".
- **Keys made earlier are still forgotten and named once.** `unfollow`, `clear`, `vault clear` and `project default
  remove` keep forgetting the private halves they find and naming each to remove at the forge. A key left in the
  vault would otherwise be removable only by hand. `MachineDeployKey` and the `keys` fields stay for that.
- **A project's repositories reached by token are written as `https` URLs.** A follow of an `ssh` URL with no key in
  the vault is refused before it starts. The refusal names the `https` form and `git.token.<host>`, rather than
  git's own "Permission denied (publickey)".
- **The machine's name** moves from `DeployKeys.hostName()` to a class of its own in `app-base` (`MachineName`),
  since transports and clearing need it without deploy keys.

## Plan

1. **Remove** `CredentialsDeployKeyCommand` and its registration in `CredentialsCommand`, and `DeployKeys.make` with
   the `DeployKey` handler's making. **Keep** `held` and `forget` as `LeftDeployKeys` (`app-model`).
2. **The daemon:** `20-project.varlink` describes `DeployKey` as retired. Its handler raises `DeployKeyRefused` with
   the words above, and `InterfaceDescriptionTest` keeps holding the part.
3. **The follow's refusal:** `ForgeIdentity` and `UpstreamProbe.refused`, for an `ssh` upstream with no key in the
   vault, name the `https` URL and `git.token.<host>`.
4. **`doc/`:**
   - `doc/project-file.md`'s *Deploy keys* becomes *Reaching a private repository*: the token, its rights (read
     the project's repository, write the work repositories), the `https` address;
   - `doc/commands.md` loses the `credentials deploy-key` row and paragraph, and says leftover keys are named;
   - `doc/credentials.md` names the token as the way to the forge;
   - `doc/decisions.md`: the clearing entry no longer says the machine holds no forge token. It still names a key
     made earlier, and removes nothing at the forge, since that needs the forge's *Administration* right, which the
     token no longer has. *Unused access* drops deploy keys from its index row.
5. **Tests, red first:**
   - `porelo credentials deploy-key` is an unknown command, and the daemon's `DeployKey` answers `DeployKeyRefused`
     with the words; today both make a key;
   - a follow of an `ssh` URL with no key names the `https` form and `git.token.<host>`;
   - `DeployKeysTest`'s forgetting cases stay green against `LeftDeployKeys`;
   - `project-enroll.feature`: the deploy-key scenarios are replaced by a follow over `https` with `git.token.<host>`
     against the suite's local forge.
6. A changelog line under *Removed*.

- **Build estimate=180**, like porelo [B258](README.md#b258) (M, contract text, docs and tests through several
  modules), with removal instead of new code.

## Acceptance

- No command or daemon call makes a deploy key; the retired call says what to do instead.
- A machine follows, fetches and pushes a private repository over `https` with only `git.token.<host>` in its vault.
- A key made by an earlier version is forgotten by clearing and named once for removal at the forge.

## Open questions

- **Does a person's own ssh key in the vault stay a way to a forge** (`git.ssh.<host>`, `ssh` URLs), beside the token?
  Proposed: **yes**. It is today's code and harms nothing, and it is not a deploy key: Porelo neither makes nor names
  it. [PJ69](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj69)'s "only the person's token for now" would remove it too; then `ssh` URLs are refused at the follow, and
  `FollowCredential`'s key path goes with the deploy keys.

## To be checked

- [B153](README.md#b153) (Estimated) reports deploy keys unused for 45 days. With no keys made, that part goes; its
  plan must be read again before it is built.
- **Read:** porelo-frontend calls `DeployKey` today (`FleetBackend.deployKey`), and adds and removes keys at the forge
  (`default_at_forge.dart`). Once this ships it gets `DeployKeyRefused`, so its [PJ69](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj69) part ships first or with this,
  agreed with Agent Frontend before building.
