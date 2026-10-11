# B63 — A Share That Is Not Stored At All

**Status:** Analyzed.
**Size:** XL - a measurement, FIDO2 at the machine, TPM2, and what the node proves are four pieces; split as the plan
says.

**What must be true.** An operator whose device unlocks the vault can rely on that device being
worth more than the account it runs under: code running as them cannot obtain the share without
something only a person can supply, such as a touch on a token.

## Why

Split off when the keyslots were built. That requirement is finished and gone; what it settled is
in [doc/credentials.md](../../doc/credentials.md). It made a device a way into the vault; this
makes a device worth more than the account it runs under.

A keyslot's share is 32 bytes the device keeps in its platform's keystore. On the two platforms most
operators actually use, that keystore is **user-scoped**: a Secret Service keyring that unlocks at
login on Linux, DPAPI keyed to the logon on Windows. **Any process running as that user can ask for
the share.** So on a desktop a keyslot protects against a stolen file and against another account -
and not at all against the thing people picture when they hear "a device unlocks it", which is code
running as them right now.

Sokar says this out loud rather than implying otherwise: `sokar vault devices` prints *"any process
running as you can ask for it"* next to a `USER_SCOPED` slot. **This issue is how that sentence gets
to say something better.**

## The shape

1. **The share is not stored.** It is derived at unlock time and cleared afterwards, so there is
   nothing in any keystore for same-user code to ask for.
2. **Releasing it needs something code cannot supply**: a touch on a FIDO2 token, or a PIN for a
   TPM2 object. That is the whole point; a derivation that happens silently is the stored share with
   extra steps.
3. **The same token produces the same share every time**, on that machine and after a reboot -
   otherwise the keyslot it opened stops opening. What the device stores is the *inputs* (a
   credential id and a salt, or a sealed object's handle), never the output.
4. **Losing the token loses that slot and nothing else.** The passphrase is the recovery credential
   and every other device keeps working, exactly as the keyslots already promise.
5. **What the node records is what actually protected the unlock**, not what a client claimed. Today
   `storage` is a word the device sends and Sokar writes down; a slot that says `FIDO2` because a
   client typed `FIDO2` is worth no more than a `USER_SCOPED` one and reads as worth more. **Sokar
   proves what protected an unlock** - for a device, by evidence the node checks, such as a TPM2
   attestation - and **until it can prove it, the interface and the CLI show a `FIDO2` or `TPM2`
   slot no differently from a `USER_SCOPED` one.** A claim is recorded, never displayed as worth
   more than it is.

### What belongs to whom

**The node side is Sokar's**: deriving from a token at the machine, the `--fido2` and `--tpm2`
enrolments, and whatever verification of `storage` turns out to be possible.

**The device side is the interface's** (`sokar-frontend`): a phone or a laptop that holds a share
for a *remote* machine has its own keystore story, and on iOS and Android it is already
application-scoped. This issue does not tell that side what to do beyond point 5: the interface
shows an unproven `FIDO2` or `TPM2` slot as it shows a `USER_SCOPED` one. What evidence a device
sends is agreed on the channel once the node side exists, because it decides what a client may claim.

### Notes

**Nothing here has been measured.** What was measured is keyslots, not tokens: no FIDO2
token and no TPM2 device has been near this code. The libraries exist on Linux - `systemd-cryptenroll`
does exactly this for LUKS, and libfido2 and tpm2-tss are packaged - so the shape is known to work;
what is not known is how it behaves in this codebase, whether a native image can call those
libraries without carrying half of them, and what a token that is absent looks like from Java. The
first commit against this issue should be a measurement, not a design.

**Why this was not part of the keyslots.** That requirement is about where the master key lives and
how many credentials may unwrap it, and it was complete without a single token. This is about what one credential is worth,
which is a different question with different failure modes - and bundling them would have meant
neither shipping until a token was on somebody's desk.

## Plan

Read in today's code. `Keyslot` (`vault`) records `storage` as one of `USER_SCOPED`, `APPLICATION_SCOPED`, `FIDO2`
and `TPM2`, recorded and not verified. `VaultFile.enroll(opener, share, name, storage)` wraps the master key with a
share, and `Opener.share(byte[])` opens with one. A device enrolls through the daemon's `EnrollDevice` and unlocks
through `UnlockWithShare` (`30-vault.varlink`); `VaultDevicesCommand` (`apps/app-vault`) prints what each storage is
worth. No command enrolls the machine itself with a token. `SystemdCredential` (`vault`) already reaches a TPM
through `systemd-creds` for the unlock cache, and `KernelKeyring` shows how a C library is called through FFM in the
dynamically linked binaries.

It splits into four issues:

1. **The measurement** (S): libfido2 through FFM from a JVM and from the native image, with a token present, absent
   and untouched; whether the hmac-secret of one credential and salt gives the same 32 bytes after a reboot. The
   results go into this issue; nothing is built.
2. **FIDO2 at the machine** (L): `sokar vault enroll --fido2` in `apps/app-vault`. The keyslot header gains the
   credential id and the salt, readable while locked (`Keyslot`, `VaultHeader`); the share is derived, used and
   cleared. Unlock tries a FIDO2 slot when a token is there, with a stated touch timeout, and names the token when it
   is absent. Red first: a unit test with a token behind an interface asserting the header holds the inputs and no
   share, and the sentence for an absent token. The checks with a real token are made at a desk; no leg has one.
3. **TPM2** (L): the same with a sealed object and a PIN.
4. **What the node proves** (L): a slot the node enrolled itself records what it did, which is proof, since the
   node derived the share. A `FIDO2` or `TPM2` claimed through `EnrollDevice` counts only with evidence the node
   checks - a TPM2 attestation of the sealed object, a FIDO2 attestation of the credential - and is otherwise
   recorded as claimed and shown exactly as a `USER_SCOPED` slot. `30-vault.varlink` gains whether the storage is
   proven, so the interface shows the same; a contract change agreed with the interface on the channel, and a
   dependency on `sokar-frontend`. Red first, before any proof exists: `VaultDevicesCommand` prints a claimed
   `FIDO2` slot with the `USER_SCOPED` sentence.

## Acceptance

- **A machine with a token enrolls itself.** `sokar vault enroll --fido2` at the machine derives a
  share from the token, wraps the master key with it, and stores nothing but the inputs. This is the
  case that needs no interface and no second device, and it is the one to build first.
- **The unlock fails with the token absent**, and says so in a sentence naming the token rather than
  reporting a rejected share.
- **The unlock fails with the token present and nobody touching it**, within a stated timeout. A
  test that passes because the tester's token happened to be in a touched state proves nothing.
- **The share never reaches the filesystem or a keyring** - asserted the way enrollment already is, by
  searching the machine's state for its bytes after an unlock.
- **A reboot changes nothing**: the same token opens the same slot.
- **`sokar vault devices` says what a FIDO2 or TPM2 slot is worth only when the node proved it**: a slot the node
  enrolled itself, or one whose evidence it checked. A slot that only claims `FIDO2` or `TPM2` reads exactly as a
  `USER_SCOPED` one, in the CLI and in what the interface is sent.
- **Seen to fail:** the unlock test with the token removed, and the one with the token present and
  untouched, go red when the unlock succeeds or the timeout is exceeded; the search of the
  machine's state after an unlock goes red when the share's bytes are found; the reboot test goes
  red when the same token no longer opens the slot; the devices test goes red when a claimed, unproven `FIDO2`
  slot is shown differently from a `USER_SCOPED` one.

## To be checked

- **How a token behaves in this codebase** (Agent Core's): whether a native image can call libfido2 and tpm2-tss
  without carrying half of them, and what a token that is absent looks like from Java - measured first, as the
  Notes say.
- **Whether TPM2 goes through `systemd-creds`** (Agent Core's), which Sokar already runs, or through tpm2-tss: the
  first needs no library in the image, the second asks for a PIN itself.
