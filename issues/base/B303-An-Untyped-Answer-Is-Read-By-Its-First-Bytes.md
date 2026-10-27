# B303 — An Untyped Answer Is Read By Its First Bytes

**Status:** Review: pushed and published as `0.5.1~snapshot.343` (Build 40531815214 on 3342902f).
**Size:** S - one module: `VaultProxy`'s refusal and its answer, `EventFraming`, their tests, and the answers' rows in
`doc/reach.md` and `doc/credentials.md`.

**What must be true.** A provider's answer that names no `Content-Type` reaches the agent when it has the framing a
typed one has - JSON, or server-sent events whose every line is a field and every `data:` is JSON - checked for a
credential as a typed one is; any other untyped answer is withheld, or cut off where it stops being so. A withheld
answer is one the agent's client does not retry, and it names the provider's status, so a refusal costs one request.

## Why

Measured in porelo-codex [CX01](https://github.com/porelo-ai/porelo-codex/blob/main/issues/README.md#cx01)'s live
ChatGPT run on `.341`: `POST /backend-api/codex/responses` answered `200` with a body and no `Content-Type`, 30
times, and each was withheld as `no content-type`. A `401` from the same endpoint carries `application/json`. Codex
CLI 0.163.0 retries the `502` the broker answers 30 times per turn. Measured offline against a stand-in with the
CLI's default retries: `403` and `422` cost 6 requests, and only `400` costs one. No answer reached the container.

Read in porelo at 2142fe60: `VaultProxy.whyUnreadable` withholds any body whose media type is not in
`UNDERSTOOD_TYPES`, an empty one included, and `writeResponse` answers it `502`. `CredentialScan` does not depend on
the type: it searches every byte for the JSON member names, escapes included, and an SSE `data:` line carries the
same JSON. A declared type is believed unchecked today. The broker asks upstream for `Accept-Encoding: identity`, so
a compressed answer is not what was seen.

## Decided

- **An untyped answer is held to a typed one's framing, not searched for credential names written bare.** Three
  readings each found spellings a bare-name search missed - case, quotes, percent-encoding, a separator split across
  reads - and no list of spellings is complete. Framing closes the class: a form, YAML or INI line is no event field.
- An untyped answer that opens as JSON gets exactly the checks a typed `application/json` one gets.

## Acceptance

- In porelo-codex's next ChatGPT walk, the first turn's answer reaches the agent, and `vault.log` has no `withheld`
  line for it.
- Against a local stand-in with Codex CLI 0.163.0, a withheld answer costs one request per turn.
- Seen to fail first: an untyped SSE `200` is withheld today, and a withheld answer is a `502`.

## Built

- `whyUnreadable` takes the first bytes: with no `Content-Type`, after a byte order mark and JSON whitespace, a body
  passes on when it opens a JSON object whose first member is quoted (`{"` or `{}`), an array of objects, arrays or
  strings, or an event field (`event:`, `data:`, `id:`, `retry:`, `:`); `[tokens]` and `{access_token: x}` open
  neither and are withheld as `no content-type`. A first read too short to tell reads on within the peek's bound.
- `EventFraming` is a filter between the provider and the agent for an untyped answer that opens as events: it
  passes on only lines it has judged. A comment or an `event:`, `id:` or `retry:` field is held whole to its end and
  refused if it names `access_token`, `refresh_token` or `id_token` in any case or with `%5F`; a `data:` line is held
  until its value opens as JSON as above, or is `[DONE]`, then passed as it arrives; any other line, or a held line
  over 4 KiB, refuses. The proxy reads on until the filter has passed something, so a refusal there is still a
  `400`; later it cuts the answer off. The credential scan reads what the filter passes.
- A withheld answer, unreadable or carrying a credential, is `400` with the reason and `upstream <status>`, or `502`
  when the provider answered `5xx` or `429`, which is transient.
- Docs: the credential row of `doc/reach.md` and *What the proxy withholds* in `doc/credentials.md`; a changelog
  entry.
- Red first in `VaultProxyTest`: an untyped event stream withheld, and every withheld answer `502` or `403`; after the
  fourth reading, a comment naming a token reached the agent when no line break followed it or a read ended before
  it, and `[tokens]` and `{access_token: x}` passed as JSON. Seen red with each guard removed - a held line passed as
  it came, any `{` taken as JSON, and the framing off let a form value, a YAML key and a token in a comment through and did not
  cut a stream that turned into a form; the name check off let a token in a comment and an event name through; any
  `[` taken as JSON let an INI body through; the read-on off withheld a stream whose first read was `ev`; `429` as
  not transient became a `400`.
- Left as it is: an untyped answer that sends only part of its opening and then nothing holds its request until the
  provider sends more or closes; nothing reaches the agent meanwhile.
