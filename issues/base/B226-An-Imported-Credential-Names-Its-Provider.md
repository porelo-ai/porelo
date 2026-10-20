# B226 — An Imported Credential Names Its Provider

**Status:** Analyzed; blocked by [B224](README.md#b224).
**Size:** M - small in code, an optional parameter through four classes and a flag, but a contract every agent
repository reads.

**What must be true.** `vault import` from an agent whose store holds sign-ins for several providers imports the one
the person names, and stores it under that provider. Today `ExtractCredential` takes no provider and
`CredentialImport` stores what it returns under the agent's default (`SelectedProvider.choose(..., null)`), so an
agent signed in to two providers cannot say which credential it returned; the provider becomes an optional parameter
under [B224](README.md#b224)'s rule.

## Plan

Read in the code on 2026-10-20 (`agents/api`, `apps/app-vault`).

1. **The contract**: `ExtractCredential(configDirectory, provider: ?string)`; the answer gains `provider: ?string`,
   the one the credential is for. `CredentialExtractor.extract(Path, @Nullable String provider)` with the
   one-argument form as its default, so an existing agent compiles unchanged; `AgentServer` (`:46`) passes it on.
   Added under [B224](README.md#b224)'s rule: an older agent ignores the parameter and answers no provider.
2. **`InstalledAgent.extractCredential`** (`:366`) sends the provider only when one is given.
3. **`CredentialImport`** (`:113`, `:126`): with `--provider`, asks for it and stores under it; an answer naming
   another provider, or none from an agent asked for one, is refused rather than stored under the wrong name.
   Without `--provider`, an answer that names its provider is stored under that one, else under
   `SelectedProvider.choose(..., null)` as today.
4. **`vault import --provider <name>`**, completed from the providers the agent can drive; `doc/credentials.md`.

**Red first:** `CredentialImportTest` with a fake agent holding two providers' sign-ins: `--provider openrouter`
stores the OpenRouter credential under `openrouter`, where today it lands under the default; an agent answering
another provider than the one asked is refused, nothing stored.

## Acceptance

- `vault import --agent <agent> --provider <name>` stores that provider's credential under its name, and the
  other provider's credential is untouched.
- An agent built before the parameter is imported as today.
