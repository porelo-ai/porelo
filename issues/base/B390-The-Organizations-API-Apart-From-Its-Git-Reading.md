# B390 — The Organization's API Apart From Its Git Reading

**Status:** Implementing.
**Size:** L - a new module group, about 4300 lines moved out of `vault`, and every command that holds a `RegistryState`
moved onto the API.

**What must be true.** Porelo asks where it keeps its organization through an API that says nothing of Git, and today's
registry reading is the first implementation behind it. Nothing a person, a host or an interface sees changes: the
same commands, the same answers, the same refusals, the same trust records, no new check.

## Plan

Measured in today's code: every command that decides something holds a `RegistryState`, which is Git-shaped (`commit`,
`blobs`, `changes`), so the API replaces that type at the callers, and the reading itself moves unchanged.

- **New group `organization/`**, as `agents/` is one: `organization/api` (`porelo-organization-api`), which needs `wire`
  alone, and `organization/git` (`porelo-organization-git`), which needs the API, `vault` and `core`. A README for each,
  a `package-info.java` with `@NullMarked`, and the root's module, dependency management and index.
- **In the API, plain data only:** no `Path`, no commit, no `CommandRunner`, no callback, so a store could stand behind
  a process boundary later. `OrganizationStore`, opened by the organization's address or checkout; `IdentitySource`,
  today's `ForgeDirectory` under that name, answering yes, no or cannot say; records for persons, owners, hosts and
  refusals, each answer naming the revision it rests on.
- **The API holds what is asked today:** whether a key is someone's and not revoked, with and without a login; who else
  holds a key; organization owners; host admins; a host's reach and its projects with their admins; persons; a person's
  and a host's card; refusals and warnings as data; who lacks a backup key; the host's offline check. Nothing is added
  for what has no source today, such as a project's members.
- **Into `organization/git`, unchanged:** `RegistryHistory`, `RegistryState`, `RegistryOwners`, `RegistryAccess`,
  `RegistryFiles`, `RegistryRepository`, `RegistryVerifier`, `CommitSigner`, `TrustRecords`, `HttpForgeDirectory`, with
  their tests, and the store part of `RegistryReadCommand` (`read`, `known`, `origin`, the trust record).
- **Stay in `vault`:** `PersonCard`, `PersonCardChain`, `HostCard`, `SshSignature` and the classes they need. Messages,
  task certificates and `card verify` use them with no store, and they need Bouncy Castle through `SigningKey.verify`,
  which the API must not. The API carries a card as its text.
- **Stay in the commands:** this machine's revocation list (`IdentityFacts.revoked`'s `ssh-keygen -Q`), which is no
  store's; printing; and the several registries a machine may have read, asked one store at a time.
- **Callers moved one by one, each a commit, the suite green after each:** `IdentityFacts`, `IdentityShowCommand`,
  `RegistryReadCommand`, `RegistryVerifyCommand`, `RegistryJoinableCommand`, `ProjectAccessCommand`, `SignerHandover`.
  What writes (`RegistryFileCommand`, `IdentityBackupKeyCommand`, `IdentityChange`'s filing) keeps its Git and is
  moved separately.
- Red first: a test that no class outside `organization/git` imports `RegistryState`, `RegistryHistory` or
  `TrustRecords`, with an allowance for the writers named above, which shrinks to none when they move.

## Acceptance

- Every existing test that reads the registry passes, its assertions unchanged; only its imports move.
- `organization/api` compiles with `wire` alone, and a test fails on an API type that names Git.
- `registry read --json`, `registry verify --json` and `registry joinable --json` and the trust record answer as before
  on a fixture kept from before the API, compared in a test: every value and every list's order. An object's field
  order is left out, because today it differs from one run of the same code to the next (`Map.of`, measured); the
  text of `registry read` and what it warns of are compared byte for byte.
- The native image builds and `porelo registry read` runs from it.

## To be checked

- Whether snakeyaml's parser needs metadata when reached from the new module; it is already in the image through core.
