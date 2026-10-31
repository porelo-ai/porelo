# B351 — A Host Card Signed Through The SSH Agent

**Status:** Review. Backlog (operator, 2026-10-31), sprint 6m.
**Size:** S, proposed - one option on `card make-host` naming the agent's key by fingerprint, and the card signed
through the agent as a person card is.

**What must be true.** `porelo card make-host` signs a host card through `ssh-agent` with the host admin's key, as
`ssh-keygen -Y sign` does with a key the agent holds: KeePassXC's agent included. No private key file is read and
none is written. `--key` names the public key, which picks the agent's key to sign with. An agent that holds no such
key, or refuses to sign, is refused, naming the key's fingerprint. An unencrypted key file still works as before.

**Why.** A host admin whose key lives only in KeePassXC cannot sign their host's card today without writing the
private key to disk, and that breaks the promise of one key per person, kept where they keep it. For the sprint 6m
test, walk6m's host card is signed with a throwaway host-admin key instead.

Filed at the Coordinator's word (2026-10-30), when [B349](README.md#b349) was found to sign from a key file alone. Not
planned in a sprint; whether it belongs in 6m is the operator's decision.

## Done

- `porelo card make-host ... --agent-key FINGERPRINT|PUBFILE` signs the host card through the ssh-agent that
  `SSH_AUTH_SOCK` names. FINGERPRINT is `SHA256:` and 43 characters; anything else is a `.pub` file whose one key
  picks the agent's key by its fingerprint. No private key file is read or written.
- `--key FILE`, an unencrypted Ed25519 OpenSSH key file, works as before. Exactly one of `--key` and `--agent-key`
  is given; both or neither is refused with 64, and so is a `SHA256:` value that is no fingerprint.
- Refused with 65, naming the key's fingerprint: `SSH_AUTH_SOCK` unset or no agent answering at it, an agent that
  does not hold the key, a key that is not Ed25519, an agent that does not sign. The agent's comment for a key is
  never shown. A `.pub` file that cannot be read is 66; one that is no text, holds other than one key or is no key's
  line is 65.
- The card is the same bytes whichever way it is signed, Ed25519 being deterministic, and passes `card verify-host`
  as a file-signed one does. `HostCard.write` takes any `AgentKey`.
- A card goes out only once it verifies as signed by the named key, whatever the agent answered; every key file, `.pub`
  or signing key, is read as itself, a regular file and at most 16 KiB, never through a link; every refusal's words,
  file names among them, are shown without their control and format characters.
- Tests: `CardHostCommandTest` (the agent signing by fingerprint and by `.pub` file the very card the key file
  signs, which verifies; an agent not holding the key; no agent, unset and not answering; both options and neither;
  a `.pub` file that cannot be read; a card checked against another key; a key file named through a link; one too
  large to be a key, 65; a file name's escape sequence), each seen red first. The
  full suite runs green, and four narrow readings were answered.
- Not claimed: tested against OpenSSH's `ssh-agent` only, not KeePassXC's agent; the non-Ed25519 key and the agent
  refusing to sign are refused in code but have no test of their own.

## Acceptance

- With the host admin's Ed25519 key only in their ssh-agent, `porelo card make-host ... --agent-key SHA256:...`
  (or `--agent-key admin.pub`) writes a card that `porelo card verify-host --signer SHA256:...` accepts, and no
  private key file is touched.
- An agent that does not hold the key, or no agent at all, is refused with 65 naming the key's fingerprint, and
  nothing is written to standard output.
- `--key` and `--agent-key` together, or neither, is refused with 64.
