# B30 — Credentials The Broker Has To Fetch

**Status:** Analyzed.
**Size:** L - three pieces in the broker and the vault, each with a stand-in service, and one run on a rented machine.

**What must be true.** A task uses a credential authenticated by a key rather than a secret -
`private_key_jwt`, or mTLS client authentication - with the broker performing the signing on the host, so
neither the key nor the token it buys is ever inside the container; and a `client_credentials` purchase is
seen to succeed end to end, not only in unit tests.

## Plan

Read in the code at `30c8169b`. The `client_credentials` run is built now. No service is known that needs a
key-based kind, so after step 1 this issue rests, and steps 2 and 3 are taken up when one is.

**What is there.** `TokenPurchase` (`supervisor/.../TokenPurchase.java`, kind `client-credentials`) buys a token on
the host, per task and in memory, once however many requests wait, again `MARGIN` (60 s) before its end; it is a
`TokenSource`, as `TokenExchange` and the grants of [B31](README.md#b31) are. `VaultProxy` makes the outbound request with
`java.net.http.HttpClient`. The vault already signs with a key it holds (`SigningKey`, `StoredKey`, `RsaKey`,
`SshAgentServer` in `vault/...`), which is the pattern `private_key_jwt` needs. The only acceptance scenario
(`credential-several.feature`) proves an unreachable authorization server refuses the start.

**One mechanism or two: two.** `private_key_jwt` (RFC 7523) authenticates the client at the token endpoint, so it is
a way of buying a token - a `TokenSource`. mTLS client authentication happens in the TLS handshake of every
request the broker makes, so it is the `SSLContext` of the broker's client for that destination, whatever token is
attached. They share only the key in the vault; mTLS can also authenticate the token request (RFC 8705), which is
the second one applied to `TokenPurchase`'s client.

Steps, each its own commit:

1. **`client_credentials` seen end to end (M).** A stand-in authorization server and resource on the machine's
   loopback in the acceptance kit (`Grants.secure` allows plain http there), issuing tokens of a short lifetime; a
   task calls the resource for longer than two lifetimes. Seen red first by a lifetime shorter than `MARGIN`.
2. **`private_key_jwt` (M).** A kind `private-key-jwt`: the key in the vault, `client_id`, `token_url`, `scopes`,
   `alg` as settings. A `TokenSource` signing a short-lived assertion (`iss`, `sub`, `aud`, `jti`, `exp`) per
   purchase with the vault's key and posting `client_assertion`. Red first: `TokenPurchaseTest`'s twin against a
   fake token endpoint that checks the signature.
3. **mTLS (M).** A kind or a setting naming a client certificate and key in the vault; `VaultProxy` builds a client
   per destination with an `SSLContext` holding them, never written to disk. Red first: a stand-in server requiring
   a client certificate, reached without one.

Each step's scenario scans the container's environment, files and process arguments for the key, the assertion and
the bought token; `CredentialScan` already keeps a token field in a response from reaching the container.

## Acceptance

- A task authenticates against a service that takes `private_key_jwt`, for longer than one bought token
  lasts, without the key leaving the vault - the way commit signing already works - and nothing in the
  container performs an exchange. Seen to fail: the key, the signed assertion or the bought token in the
  container's environment, filesystem or any command line that created it.
- The same for mTLS client authentication, or a stated reason why it cannot pass through a broker that
  terminates TLS itself.
- On a rented machine, a task holding a `client_credentials` entry reaches a service through an
  authorization server the broker trusts, and keeps working past the bought token's end. Seen to fail: a
  503 naming the authorization server, or a second exchange before the first token is a minute from its end.

## To be checked

- **What a task that loses its bought token mid-run sees** (Agent Core's). A purchase failing at start is refused
  before a container exists; one failing during the run cannot be. [B31](README.md#b31)'s grants raise "authorization needed" for the
  same case; a bought token has nobody to ask.
- **Whether a stored credential that carries an end and nothing to renew it with should be named before a task needs
  it** (Agent Core's): a `vault check` for the kinds that carry the date.
