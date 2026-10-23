# B269 — The Forge Reached With The Person's Token Alone

**Status:** Accepted. porelo's part of porelo-project
[PJ69](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj69).

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
- `doctor`'s report of a deploy key unused for 45 days;
- `doc/project-file.md`'s *Deploy keys*, `doc/commands.md`, and two decisions in `doc/decisions.md`.

## To be decided in the analysis

- Whether a project's repositories are then written as `https` URLs, since an ssh URL needs a key and never takes a
  token, or whether an ssh key in the vault stays a way for a person who wants one.
- What becomes of deploy keys already made: whether clearing still forgets their private halves and names them for
  removal once, or leaves that to the person.
- What changes in the daemon's interface when its deploy-key call goes.
