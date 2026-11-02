# B391 — The Organization's Writes In Its Git Adapter

**Status:** Backlog; blocked by [B390](README.md#b390).
**Size:** L - about 2200 lines of committing, signing and pushing moved from the commands into the adapter, with the
three commands that do it put on one write.

**What must be true.** A change to the organization is handed to the store with the acting person's agent, the revision
it was made against and an id, and the Git adapter alone commits and signs it. Nothing a person sees changes:
`registry file` still does not push, `identity rotate` and `identity publish` still do, and every refusal keeps its
words and its exit code.

## Plan

- **Writes in the API:** a person's card, a host's card and its removal, a host's access file, the owners file, each as
  today's whole text, with the `base` and `id` `registry file` already takes. Nothing finer is added: adding a host
  admin
  by itself has no writer today, and who may do it differs from who may today.
- **Into `organization/git`:** `RegistryCommit`, `RegistryGit`, `RegistryOrigin`, the committing part of
  `RegistryFileCommand` (`commit`, `signed`, `madeBefore`, the agent's `heldKey`), `IdentityChange`'s filing and push
  (`file`, `head`, `branch`, `filedOnto`, `signedBy`, `push`), and the owners-file text `IdentityBackupKeyCommand` edits
  itself.
- **What stops them moving, each cut first:** `PoreloContext` (given as runner, environment and paths), picocli,
  `IdentityFacts.revoked`'s machine list (handed in as a predicate), `IdentityChange`'s running of `RegistryFileCommand`
  through the command line (a direct call).
- **A card stays signed before it is filed:** a change of key takes the old and the new key's signatures through the
  agent, so `identity` keeps signing, and the store takes the signed text.
- **Stay out:** `ProjectAccessCommand` writes the access repository and `ProjectBackupKey` writes `project.yml`; neither
  is the organization's.
- Red first: the allowance of [B390](README.md#b390)'s import test shrinks to none, and a test that no class outside
  `organization/git` runs `git` on a registry checkout.

## Acceptance

- `RegistryFileCommandTest`, `IdentityRotateCommandTest`, `IdentityChangeTest` and the backup-key tests pass with their
  assertions unchanged.
- A filed commit is byte for byte what it was on the same input and time, compared in a test.
