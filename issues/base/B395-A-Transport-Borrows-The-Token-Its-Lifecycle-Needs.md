# B395 — A Transport Borrows The Token Its Lifecycle Needs

```json
{
  "$schema": "issue-1.json",
  "id": "B395",
  "title": "A Transport Borrows The Token Its Lifecycle Needs",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 119,
  "blockedBy": [],
  "covers": "A transport says in `describe` which secret of the person's a lifecycle verb needs, and Porelo lends it from the vault for that verb alone."
}
```

**Why this size.** a field in `describe`'s reading, one look in the vault where a lifecycle verb runs, and tests.

**What must be true.** A transport that needs a secret of the person's for a lifecycle verb says so in `describe`, and
Porelo lends it for that verb alone, from the person's vault, without Porelo naming any transport or verb. A homeserver
elsewhere's registration token is the first such secret: it stays in the vault, and is never kept with the transport's own
secrets.

## What holds today

Read in the code. `TransportDescription` reads `lifecycle`, `takes` and `waitsOn` from `describe`, and nothing of its
`credentials`. A transport that needs the registration token can only read it from a file of its own.

## Plan

1. **`TransportDescription`** reads `credentials`. An entry with `lent`, a list of verbs, is a lent credential:

       "credentials": [{"name": "PORELO_MATRIX_REGISTRATION_TOKEN", "as": "env", "lent": ["setup", "enroll"]}]

   An entry with no `lent`, or one that is not a list of strings, lends nothing. Porelo keeps no list of verbs.
2. **On a verb an entry lists**, Porelo looks in the person's vault under a name derived from `name`: lower-cased, less
   `PORELO_`, `_` as `-`, so `matrix-registration-token`, with `.<server>` after it, the host of
   `mail.transports.<scheme>.homeserver`. Only that entry is lent, never a plain one: a project's file names the
   homeserver, so a plain entry would go to whatever server a project admin names.
3. **What it finds** goes into the verb's `secrets` under `name`, on the transport's standard input as its other secrets
   do (`TransportInput`). It is not stored with the transport's secrets, and goes no further than that one run.
4. **Nothing found:** `secrets` goes without it, and the transport refuses as it does today (78). **A shut vault:** 77,
   before the transport is called.

## Acceptance

- Red first: on a verb `lent` lists, the transport's input holds the value under `name`; on a verb it does not list, it
  does not, and the vault is not asked.
- Only the entry named with `.<server>` is lent. With a plain entry alone, or with one for another server, `secrets` has
  no such name, and the value reaches no transport.
- A shut vault answers 77, and the transport is not run.
- A transport that lists no `lent` runs exactly as today.
- The value reaches no log, no output, no argument and no file: a test runs a verb with a recognizable value and looks
  for it in what was printed, in the recorded command lines and under the state directory.

## Checked

- A transport that prints a borrowed value elsewhere than under `secrets` or `account` is not filtered: it already holds the
  value and could send it anywhere, so the filter only keeps Porelo from storing it.
- `redact` leaves a value under 8 characters in a failing transport's error, and one the transport echoes JSON-escaped; a
  registration token is longer and has no such characters, and the transport is the person's own install.
- A scheme that begins an existing entry's name, such as a transport called `git`, could borrow `git-token.<server>`; the
  person installs the transport, and it still gets only the entry for its server.
- A transport listing `retire` in `lent` says on every removal with a shut vault that its account was not retired. The removal
  goes on; no published transport lists it.
