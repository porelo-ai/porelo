# Time limits

Every time limit Porelo keeps is a default times one of four factors, so a slow network or a slow
machine is met by one setting rather than a new build. Each factor is 1.0 unless the person sets it under
`limits:` in [the account's settings](configuration.md).

| Factor | What its limits wait for |
|---|---|
| `services` | an answer from outside: a forge, an API, a download, a homeserver, another Porelo program |
| `machines` | a program, container or machine starting, stopping or running a command |
| `polling` | how long until something is looked at again, and how long an answer is kept before it is asked again |
| `retries` | how often a failed step is tried again, and the pause before each try |

- **`services` and `machines`**: each limit is its default times the factor, rounded up to a whole
  millisecond; 2.0 doubles a timeout.
- **`polling`**: each interval is its default times the factor; 2.0 looks half as often.
- **`retries`**: the number of tries is the default times the factor, rounded up and never less than one;
  each pause is the default pause times the factor.
- **A task image's apt download timeout and retries follow `services` and `retries`**, so an image built
  under other factors reads as stale.
- **A setting that is a person's decision is never multiplied**: an agent's run limit, how long a card or a
  token holds, how long records are kept.
- **Sizes, counts and protocol values are not limits of time** and follow no factor.

## The limits line

The four factors travel as one text, the limits line:

```
services=1.0,machines=1.0,polling=1.0,retries=1.0
```

- **Comma-separated `name=value`**: each of the four names at most once, in any order, with no spaces. A
  name left out is 1.0, and an empty line is all four at 1.0.
- **A value is a decimal from 0.1 to 100** with a dot and at most two digits after it: `1`, `1.5`, `0.25`.
  No sign, exponent, comma or leading zero.
- **Refused before anything is done**: an unknown name, a name given twice, a part that is no `name=value`,
  and a value that is no such number or lies outside the range. A program refuses with exit code 64 and
  `--limits: <what is wrong>`.
- **The line is written with all four**, in the order above, each with at least one digit after its dot.

- **`porelo-host` takes the factors from `/etc/porelo-host/host.yml`**, the host configuration its role writes
  with the line under `limits:`, never from a person's file: a missing file is all four at 1.0, and a file it
  cannot take stops it non-zero, naming the file and what is wrong.
- **A program takes `--limits <line>` before its mode or verb**: `<program> --limits <line> serve <socket>`. An
  agent program, a build reader and an ecosystem adapter are handed the factors by the shared main they run on,
  through their `withLimits`, before they serve; one that keeps no time limits of its own needs nothing. A program
  started without the option takes all four at 1.0, so it still runs alone.

`org.fuin.porelo.wire.LimitFactors` in `porelo-wire` reads, writes and applies the line for every Java
program. A program in another language keeps to the grammar above; the cases it is held to, each good line
with its four factors and its written form and each refused line with what its refusal names, are published
beside that class in `porelo-wire` as `limit-factors-cases.json`.
