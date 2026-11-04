# B388 — A Registry Owner Check With An App Token

**Status:** Review.
**Severity:** high.
**Size:** S, proposed - the registry takes a token declared in a variable or a file, and an organisation's App
installation token is taken as one that sees every membership.

**What must be true.** The registry's owner check works with a GitHub App installation token that a program hands it
for one call, without a personal token in Porelo's vault.

The test tokens of the test walk moved to a GitHub App (2026-11-01): its installation token lives an hour
and is held outside the vault. The owner check took its token only from the vault
(`git.token.github.com`, or a declared vault entry), and it took a 404 for a membership as "cannot say" unless
`/user/memberships/orgs/ORG` showed an active admin, which an installation token, having no account, cannot show.

## Acceptance

- A token declared for the origin with `--env NAME` or a file asks ownership and is never printed; a declaration for
  the host alone is not sent to the host on another port; seen to fail first.
- An installation token whose installation reaches the organisation's repositories answers a 404 as a no; one of
  another organisation, or one that reaches no repository, still cannot say; seen to fail first.

The registry commands are gone; organization access keeps the token and membership checks.

## Done

- `OrganizationAccess.forgeToken` takes a token declared for the origin in a variable or a file before the vault, with
  the vault's rule for a port (`OrganizationAccessTest`; the earlier registry test was red first; the port rule seen
  red under a mutant).
- `HttpForgeDirectory` asks `/installation/repositories?per_page=1` after a 404 from a token that is no organisation
  admin, and takes the 404 as a no when the first repository's owner is the organisation (`HttpForgeDirectoryTest`,
  red first; the owner comparison seen red under a mutant). The App needs Members: read.
- The interface passed the test walk's token as `PORELO_GITHUB_APP_TOKEN` for each `registry` call during that walk; a
  local script declares it with `porelo credentials declare https://github.com/<test-organization>/
  --kind=token --env PORELO_GITHUB_APP_TOKEN`. Built during a stopped test walk with its unit tests only; the
  reading follows after the walk.
