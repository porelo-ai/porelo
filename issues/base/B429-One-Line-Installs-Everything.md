# B429 — One Line Installs Everything

**Status:** Backlog.
**Size:** L - crosses repositories, with an agreed order and one outward removal on the operator's word.

**What must be true.** On Debian and Ubuntu, `sudo apt update && sudo apt install 'porelo*' keepassxc` installs all of
Porelo and updates it the same way, on every published channel, and stays true: nothing meant only for a test can be
installed by it, and the build says so when the line stops working.

## Why

- **The test agent is in the `snapshots` index.** `.github/workflows/build.yml` publishes `agents/stub/target/*` into every
  channel but `releases`, under the name `porelo-agent-stub`, so the line installs the acceptance suite's agent as if it
  were a real one. The published `releases` index does not hold it; `snapshots` does (both indexes read). The comment
  in `agents/stub/pom.xml` says it is never published, which is not so.
- **Nothing checks that the channel installs as one.** The publish step checks that each uploaded file is indexed, one
  package at a time, and each repository publishes into the same channel on its own, so two packages that conflict are
  seen only by the person who types the line.

- **A machine that holds the old GitHub reader stays refused even after the package leaves the index**, as measured: the
  glob also selects the installed `porelo-build-github`, and it conflicts with `porelo-adapter-github`. `apt-get upgrade`
  moves Porelo on, keeps the old reader and brings no adapter. A first `sudo apt purge porelo-build-github` makes the line
  pass. A check on a fresh machine does not see this case, so the page says it.

## Plan

It touches `.github/workflows/build.yml`, `agents/stub/pom.xml`, `dist/package-check` (`Main`, a new command beside `Install`),
`doc/getting-started.md` and `doc/running.md`. **Red first:** the new check against today's `snapshots` index.


**The stub leaves the channels, and the suite keeps it.**

- `acceptance/legs`' `Deploy`, `dist/package-check`'s `Install` and `dist/dist-check` read `agents/stub/target`, built from
  the same checkout, and nothing there needs the published package. They do not change, and `agents/stub` keeps packaging
  itself.
- `.github/workflows/build.yml` stops uploading it to the channel; the comment in `agents/stub/pom.xml` is put right.
- The one reader found of the published package is the interface's integration test, which takes the newest stub from the
  channel into an account with no root. It needs another place first, agreed with its repository: a distribution of its own
  in the same Debian repository, such as `test`, and a directory of its own in the RPM one, which `apt install 'porelo*'`
  does not see unless a person adds it. That reader accepts this on three conditions:
  - An `.rpm` stays checked against metadata from the repository, never fetched unchecked.
  - The new place gets the stub from every Build, since the reader takes the newest published.
  - The upload to `snapshots` goes only after a leased leg has run green against the new place.
- Then the stub is removed from the `snapshots` index, on the operator's word, since that is an outward change.

**The check.**

- A command of `dist/package-check`, in Java, run on a GitHub runner with no rented machine: for each of `releases` and
  `snapshots`, it adds the channel as the getting-started page does, and runs `apt-get -s install 'porelo*'`.
- It fails on a non-zero exit, printing what apt said, and on any package the list of what must never be offered names.
  That list holds `porelo-agent-stub` and is itself checked: it must name a package this repository builds.
- It runs at the end of the Publish job, and on a daily schedule, since another repository's publish can break the line
  and no build of this one would see it.

**What the line brings.** `'porelo*'` brings every agent, the Matrix homeserver and the message transports, as meant. No package starts
a service by itself, read in each repository's packaging: `porelod` and `porelo-matrix-homeserver` are user units installed and
not enabled, and no Debian package holds an install script.

**The document.** `doc/getting-started.md` says the same line, once the check is green on `releases`; before that the line
would be untrue. Its removal lines change with it.

`doc/running.md` names `porelo-build-github` as the GitHub reader; it then names `porelo-adapter-github`, which needs a Porelo
that speaks the reader's newer interface.

## Order

1. The stub's new place, agreed and in use by its reader.
2. The stub gone from the upload, then from the index.
3. The other package that conflicts gone from `snapshots`, which that package's repository owns. From `releases` only once
   the adapter and a Porelo speaking its interface are released there, or that channel has no GitHub reader.
4. The check on, once it can be green.
5. The document.

## Acceptance

- `apt-get -s install 'porelo*'` passes on both published indexes, and its output holds no `porelo-agent-stub`.
- The check has been seen red: today's `snapshots` index fails it, by a conflict between two packages.
- A re-run of an old build that publishes the old reader again turns the check red.
- The check fails when apt cannot be asked or the index cannot be read, and says what it asked and what it got.
- The acceptance suite, `package-check` and the interface's integration test still find the stub.

## To be checked

- Whether the RPM repository indexes a directory of its own without changing its layout. The promise covers apt alone, and
  [B435](README.md#b435) holds rpm's, but the stub's reader takes an `.rpm` as well.
- Whether such a directory's metadata is signed like the channel's, and whether `dnf --repofrompath` with the key and
  `repo_gpgcheck` checks it.
- That `apt-get -s` on a runner resolves against the distribution's packages as a person's machine would, and which
  Ubuntu version the runner must have.
- Whether anything else takes the published stub; the search covered the repositories on this machine, and found only
  that one reader and no `lease --package porelo-agent-stub`, which the comment in `build.yml` names.
