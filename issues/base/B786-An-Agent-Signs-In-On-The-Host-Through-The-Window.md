# B786 — An Agent Signs In on the Host Through the Window

```json
{
  "$schema": "issue-1.json",
  "id": "B786",
  "title": "An Agent Signs In on the Host Through the Window",
  "state": "Idea",
  "size": "L",
  "priority": "High",
  "rank": 3,
  "blockedBy": [],
  "covers": "A person signs an agent in on a host from the window alone: the host's daemon starts the agent's own login there, hands the window the link or code the agent prints, takes back a pasted code, and answers signed in once the host's vault holds what the login made",
  "openQuestions": 1
}
```

**Why this size.** L: the sign-in container's terminal read by the daemon instead of attached by a person, a
bounded relay of what the agent declares it prints, a way back for a pasted code, two added methods on the contract,
with their tests and `doc/`. Estimate: 114 minutes (Core's median for L).

**What must be true.** On a host the person reaches only through the window, they sign an agent in there with
nothing but the window: they see the link (and code) the agent's login prints, confirm it in their own browser,
paste a code back where the login asks for one, and the window shows the agent signed in. What the login makes is
Porelo's own sign-in on the host, renewed there by the broker and nowhere else.

Filed 2026-11-15 under ST364, on the operator's decision of the two-machines question (variant C).

## Why

`AgentSignInStart` already runs an agent's login on the daemon's machine in a container of its own and stores what
it makes renewable (`LoginCredentials`), but a person must attach to its terminal on that machine. A window on the
person's computer has no terminal on the host. With rotating sign-ins no longer carried ([B785](README.md#b785)), signing in on the
host is the only way, so the window must be able to do it alone.

## Plan

1. `AgentSignInStart` gains an added `relay: ?bool`: the daemon reads the login container's terminal itself and
   nobody attaches; `attach` is empty then. A loopback flow is refused as over SSH today (`LOOPBACK_OVER_SSH`), not served by a
   forwarded redirect port: its redirect would carry the authorization code through the person's computer to a port
   on the host.
2. The login runs in the container's pty, as an interactive CLI needs. From its output, control sequences removed
   and read line by line, the daemon takes only what the definition declares under `login.relay`: `link` (a pattern
   whose first group is the link), `code` (device-code: whose first group is the code) and, for paste-code,
   `answer: line` (the code is typed back as one line); nothing else of the output crosses. An agent whose
   definition declares no `relay` is refused for a relayed start (`UNSUPPORTED`). A link is
   answered only as `https` to a host the agent or its provider declares, bounded in length; a code only in the
   declared alphabet and length.
3. A new `AgentSignInWatch(agent) -> (state, link: ?string, code: ?string, paste: ?bool)` answers, with `more` as
   it changes, `preparing`, `waiting-for-you` (the link, a device code's code, and `paste` when the login waits for
   a code typed back), `storing` or `ended`; never a token. `AgentSignInWait` stays the verdict.
4. A new `AgentSignInAnswer(agent, code)` types a pasted code into the login's terminal: bounded, printable, one
   line, once per prompt; never logged, never in an answer, an event or an error.
5. `AgentSignInWait` answers as today: stored or why not, read from the host's vault.
6. `40-agent.varlink` and `doc/` describe the relay; `porelo vault login` on the host is unchanged.

## Red-first test

- With a fake agent whose login prints a device link and code: `AgentSignInWatch` answers `waiting-for-you` with exactly the
  link and code, and nothing else of the output. Fails today: no such method. The stub agent's definition declares its `relay`.
- A link to a host the definition does not declare, or not `https`: never answered.
- A paste-code login: `paste` true, then `AgentSignInAnswer` reaches the login's input once; a second answer, a control
  character or an overlong code is refused; the code is in no log or answer.
- A relayed start of a loopback login: `LOOPBACK_OVER_SSH`.
- After the fake login writes its sign-in: `AgentSignInWait` answers stored, and the entry is kept renewable.

## Acceptance

- The red-first tests pass, each seen red first.
- On the rented host, from the window alone, Claude Code signs in by its link and pasted code, and a task started
  there runs with that sign-in.

## Open questions

1. The keys' values per agent, measured with each pinned CLI run without a terminal: Agent Smith's CC75, CX28,
   AG30, PI67, OM65 (the shape above is given to them 2026-11-15); the rented-host acceptance needs CC75's.
2. The window's steps: settled with Agent Frontend 2026-11-15 (F584, F585); its names are taken here.
