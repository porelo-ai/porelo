# [B509](README.md#b509) — A Varlink Number Read Exactly

**Status:** Analyzed.
**Size:** S - one parser call in `porelo-wire`, the numbers its readers take, and a red test on each side.

**What must be true.** A number in a varlink message reaches its reader as it was sent. An `int` of 1024 is 1024, not
1024.0, and an int64 above 2^53, such as a byte count or a time in nanoseconds, keeps every digit. That holds for the
daemon reading a call and for any client of `VarlinkClient` reading a reply, porelo-mcp among them.

## Why

Agent MCP found it while generating porelo-mcp's tools (2026-11-08). porelo-mcp passes replies through unchanged, so
it hands on whatever the client gives it.

## Analysis

Read in the code on 2026-11-08:

- `VarlinkConnection.parse` (`wire/src/main/java/org/fuin/porelo/wire/varlink/VarlinkConnection.java:121`) reads
  every message with `Json.parse`, which reads every number as a `Double`. The same parse serves both directions.
- `Json.parseDecimals` already reads exactly, as a `BigDecimal` checked against JSON's own number syntax. The supervisor
  uses it to rewrite a request without rounding it.
- `Json` writes any `Number` with its own `toString`. A `Double` read and written back, as in a value passed through, is
  sent as `1024.0`.
- No main code in `apps`, `daemon` or `wire` asks `instanceof Double`. Readers go through `Number`, so a `BigDecimal`
  reads the same where it fits.
- Not urgent: JSON Schema counts `1024.0` as an integer, so no client refuses it today.

## Plan

- `VarlinkConnection.parse` reads with `Json.parseDecimals`.
- Every reader of a number from a call or a reply is checked to take a `Number` and to refuse a fraction where it wants
  a whole one, as `Wire.whole` does for the ecosystems.
- **Red first:**
  - a client reading a reply of `{"n": 9007199254740993}` gets 9007199254740993;
  - a reply of `{"n": 1024}` passed through and written again is `1024`;
  - a daemon method reading an int parameter of 1024 gets a whole number.

## To be checked

- Whether `porelo-frontend` and the window read the daemon's numbers on their own side. That is Dart's parser, not
  this one.
