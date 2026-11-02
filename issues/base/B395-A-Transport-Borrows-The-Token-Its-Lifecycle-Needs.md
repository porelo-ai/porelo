# B395 — A Transport Borrows The Token Its Lifecycle Needs

**Status:** Backlog.
**Size:** S - a field in `describe`'s reading, one look in the vault where a lifecycle verb runs, and tests.

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
   `PORELO_`, `_` as `-`, so `matrix-registration-token`. First with `.<server>`, the host of
   `mail.transports.<scheme>.homeserver`, then without.
3. **What it finds** goes into the verb's `secrets` under `name`, on the transport's standard input as its other secrets
   do (`TransportInput`). It is not stored with the transport's secrets, and goes no further than that one run.
4. **Nothing found:** `secrets` goes without it, and the transport refuses as it does today (78). **A shut vault:** 77,
   before the transport is called.

## Acceptance

- Red first: on a verb `lent` lists, the transport's input holds the value under `name`; on a verb it does not list, it
  does not, and the vault is not asked.
- The entry named with `.<server>` wins over the one without; with neither, `secrets` has no such name.
- A shut vault answers 77, and the transport is not run.
- A transport that lists no `lent` runs exactly as today.
- The value reaches no log, no output, no argument and no file: a test runs a verb with a recognizable value and looks
  for it in what was printed, in the recorded command lines and under the state directory.
