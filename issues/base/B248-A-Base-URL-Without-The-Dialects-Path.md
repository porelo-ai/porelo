# B248 — A Base URL Without The Dialect's Path

**Status:** Idea.

**What must be true.** An agent definition can say that the agent adds the dialect's path to its base URL itself,
so Porelo hands it the broker's address alone; an agent that says nothing is handed the address with the path, as
today.

## Why

Measured from porelo-agy on Antigravity CLI 1.4.5: given `GOOGLE_GEMINI_BASE_URL=http://127.0.0.1:18080/v1beta`, it
sends `POST /v1beta/v1beta/models/...`, since its client adds `v1beta` itself; Oh My Pi needs the path in the URL.
Read in today's code: `CredentialWiring` sets `base_url_env` to `route.endpointFor(...)`, the address with the
dialect's path, for every agent; the broker forwards the path it receives unchanged (`VaultProxy`), so only the
variable's value differs between the two kinds of agent.
