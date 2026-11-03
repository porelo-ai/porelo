# B419 — A Card Renewed With Its Key

**Status:** Review.
**Size:** S - one command on the rotation's path, two fields in `identity show`.

**What must be true, by the operator's word of 2026-11-03.** A card lasts 30 days, and renewing it is one click in the
window. `porelo identity renew` files a new card of the same key, valid 30 days from today, signed through the agent. A card
past its end may be renewed too: from its end a host refuses its key, so otherwise an ended card would be a locked door.
`identity show` answers `days_left`, negative once past, and `renew_due`, true from 7 days before the end.

## Acceptance

- A renewal files a card of the same key with a new term, kept here and taken by a reader.
- A card past its end is renewed the same way.
- The same id again answers the same renewal; a key the agent does not serve renews nothing, `no-key`.
- `days_left` and `renew_due` are right before, at and past the seventh day.
