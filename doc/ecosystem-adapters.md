# Writing an ecosystem adapter

An ecosystem adapter recognizes one tool's settings, may look up the exact versions of what they ask when a project
file is made, and returns the settings files that tool needs in a task.
It is a native executable installed separately from Porelo. Porelo discovers it at run time through the
[ecosystem API](https://github.com/porelo-ai/porelo/tree/main/ecosystems/api).

An adapter runs normally on the developer's machine as the developer's account. It opens a connection in
`Resolve` alone, which Porelo asks only when a project file is made, and in nothing else: `Describe`, `Detect`
and `Configure` open none. It never runs the project's code and writes nothing itself. These are obligations of
the adapter contract; Porelo does not enforce them with a network namespace or a sandbox. Install only adapters
you trust with the files that account can read. The adapter process starts with an empty environment; the one
started for `Resolve` gets the person's proxy settings and nothing else.

## Installing an adapter

Install the executable under its ecosystem name, matching `[a-z][a-z0-9-]{0,62}`, in one of these directories:

1. `$XDG_DATA_HOME/porelo/ecosystems`, under its usual default when unset.
2. `/usr/libexec/porelo/ecosystems` for a machine package.

Discovery considers executable regular files only and sorts names within each directory. The first directory
wins for each name, even if its adapter is broken. A broken adapter remains in the discovery answer with its
name and the reason it was refused; the other adapters are still checked. Remove or replace a broken local
copy to use the packaged copy of that name.

Discovery calls each executable with `describe`. Its standard output is a JSON object containing
`protocolVersion` and `ecosystem`. The ecosystem must equal the executable's file name. A description has
ten seconds to finish and may contain at most 64 KB. A protocol version below `EcosystemProtocol.LOWEST`
is refused; a newer version is accepted because Porelo asks only methods it knows.

`porelo ecosystems --json` lists discovery as `ecosystems`, each with `name`, `path`, and `refusal`,
null when usable. `porelo project init CHECKOUT --json` asks every usable adapter to detect settings in
the checkout and the account's home. It names refused and unreadable adapters without stopping the others.
An adapter that does not recognize the checkout contributes no findings.
The JSON answer keeps `plugins` for discovery outcomes and `plugin` for the source of each finding
and unplaced item; these field names remain stable for existing readers.

The proposal groups findings by part and key. Matching values retain every source; disagreements stay
out of the draft. Unknown findings, unplaced material, and tools without a generic rule remain in the
answer. `host.<host>` rules become egress domains and `package.<name>` tools become image installation
lines. `run.<name>` gives image lines and `env.<NAME>` a variable of the image, as `Resolve` below says. Other
tools stay as comments for a person to place. `credential.<id>` names a credential needed in the vault, never
its value. The draft is checked by the project reader before it is printed.

Detection saves no proposal and writes no checkout file. Adapter communication uses temporary sockets,
removed after each call. With `--project`, the answer includes the project repository's revision;
`--yes --base REVISION` files the repository row as one signed commit. Filing writes YAML anew, so
comments do not survive and the answer retains the sources. No daemon operation is involved.

`project init` has each adapter look up what it read (`Resolve`) before the draft is made, through the proxy
settings of the shell it runs in. An adapter that cannot say, fails or takes too long leaves what it detected, and
the answer's `notes` says so; a `run.<name>` or `env.<NAME>` the draft cannot hold is listed in `refused`.

## At a first start

`porelo task start` with no project named, in a checkout on the `default` path with no `project.yml`, asks the same
adapters and takes the same draft, with the project named `<repository>-project`, since a project named like its
repository would read that repository as its own, where no task works. Nothing an adapter found is written or opened until a person
says yes at a terminal; the file then goes into the checkout, and this machine's copy of it is what starts. The daemon's `FirstStart` and `ConfirmFirstStart` answer the same for an interface on the
same computer.

What an adapter says for a person reaches the question, at a terminal and through `FirstStart`: each `unplaced` thing, with its
adapter, file and line, among `notes`, as is what it says of a host; a tool's `note` stands beside it. A terminal says one
line, what was found and how many hosts and notes there are, and under it only what bears on trust: each image line and
each host only the checkout names. `porelo task start --notes` says each finding with its file and note, each host, and
each note whole. These are not part of what a yes
names, as they change nothing written. A value from no file, such as a tool's built-in default, is said by its `note` in a draft's
comment: that is written, so it is part of what a yes names.

An adapter runs no code and is bounded by a time and a size, and its answer is still input from a stranger's repository:
what it says goes through the project reader, and a person reads it before it counts. An unknown finding is said
with its `note`, which should name why and where, such as the parent POM that is not in the local repository; the
file is then written without it. A `host.<host>` that only the checkout names and no egress set holds is marked,
and the question then defaults to no.

Where the file would be made, each adapter looks up what it read before the question: at a terminal, through the
proxy settings of the shell, and through `FirstStart` with those the interface sends in its `proxy` field, since
the daemon holds no shell's. A start with a file, a start with `--no-input`, `--yes` or `--dry-run`, and a start
with nobody at a terminal look nothing up. The lines an adapter fetched run as root of the image's build, so the
question shows each of them whole, as `image line`, and each variable as `image env`, for a new file too; they are
part of the file a yes names. A lookup that failed is said among `notes`. A yes meets the lookup its question
made, so what is written is what the person read; a yes after that lookup is no longer kept looks up anew, and a
changed answer is then a changed file.

## Implementing the contract

Build against `org.fuin.porelo:porelo-ecosystem-api` and `org.fuin.porelo:porelo-wire`, importing
`org.fuin.porelo:porelo-bom` for their versions. The API and wire library use Apache-2.0, so an adapter may
choose its own license. Implement `Ecosystem` and pass it to `EcosystemMain.run` from the executable's main
method. That supplies `describe` and `serve <socket>`. Build the executable with GraalVM Native Image.

`serve` listens on the Unix socket Porelo names, prints `ready <socket>` after it listens, and serves
`org.fuin.porelo.Ecosystem1`. Porelo ends the process when it closes the client. The
[published interface](https://github.com/porelo-ai/porelo/blob/main/ecosystems/api/src/main/resources/org/fuin/porelo/ecosystem/api/org.fuin.porelo.Ecosystem1.varlink)
is the wire contract; the API's server and client implement it together.

`Describe()` returns the protocol version and ecosystem. Both discovery and the running client's handshake
check them. The interface only grows: optional additions let older adapters keep working, and a method
missing from an older adapter means it cannot say, never that it found nothing. A breaking change raises
the lowest accepted version.

`Detect(directory, home)` reads settings in the given checkout and the person's home. It returns `findings`
and `unplaced`. Each finding has a `part` of `TOOLS` or `RULES`, a `key`, `value`, `file`, `line`, `origin`,
`unknown`, and `note`. `YOURS` paths are relative to the home; `CHECKOUT` paths are relative to the checkout.
`MACHINE` covers values from neither, such as a tool's default or a parent in a local repository; its path
may be absolute outside the home, or empty with line zero when there is no file. A path contains no `..`.
A line is zero when the file has no line for the value.

If a value cannot be read without running code, set `unknown` to true and leave `value` empty. Name a
credential by its id only, never by its value. Report material that cannot be placed as `unplaced`, with
its file, line, and note. Do not execute a build file to learn what it would do.

`Resolve(directory, home, findings)` looks up, once, the exact versions of what `Detect` read: the newest stable
release a project asks, its download URL and its digest. Porelo calls it only when a project file is made, never
for a start with a file, and gives it this adapter's own `Detect` findings for the directory and home, and no other
adapter's. It returns `findings`, which replace those whole. Two kinds of key are placed by their prefix alone:

- `run.<name>`: Containerfile lines for the project's `image.run`, joined in the order of the names after the
  package lines. Only `RUN`, `ARG`, `ENV` and a `COPY` from an image pinned by its digest are allowed, by the same
  rule the project file is held to; a key whose lines break it is left out whole, and the question says so.
- `env.<NAME>`: a variable's value for the project's `image.env`. A name Porelo sets in every task is refused,
  `PATH` and `PORELO_*` among them, so a tool's commands are linked into `/usr/local/bin` instead.

Two adapters giving one such key two values disagree, and neither is written. The process asked is started for
`Resolve` alone, with `HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY` and their lower-case forms where the person has them
set, and no other variable. A proxy's address can hold a credential: an answer that repeats one is not used, and a
proxy setting never appears in a finding, a note, a log line or an answer. An adapter keeps each request within
5 seconds (`EcosystemProtocol.RESOLVE_REQUEST_SECONDS`), and Porelo waits 20 seconds for the whole call
(`RESOLVE_CALL_SECONDS`); both are first figures, not yet measured. A value it cannot look up in that time is
unknown. An adapter that cannot say answers `MethodNotFound`, which `Ecosystem.resolve`'s default does; on that,
on a refusal, a failure or a timeout, what `Detect` found stands, with a note naming what was asked and never the
adapter's own words.

`Configure(repositories)` receives each declared repository's name and URL inside the task. It returns
`files`, each with a `path`, `content`, and `ownerOnly`. A path is relative to the task user's home, with
no empty segment, `.` or `..`, quote, backslash, or control character. Porelo places these files; the adapter
writes nothing. There is no checkout directory in this question because the workspace stays inside the
container. Return settings whenever a repository is declared, without inspecting a workspace on the host.

A directory with nothing of this tool answers `NotRecognized`. A file the adapter needs but cannot read
answers `Unreadable(detail)`, naming the file and reason without exposing credentials. Neither refusal
is an empty detection.

## Testing an adapter

The [stub](https://github.com/porelo-ai/porelo/tree/main/ecosystems/stub) answers from `answers.json` beside
its executable, read again on every call. Its `detect` member holds `findings` and `unplaced`; its
`resolve` member holds `findings`, and without it the stub cannot say; its `configure` member holds `files`. It
opens no connection. A `refuse` member holds a `reason` and `detail`. The acceptance installer
places this executable in the account's ecosystem directory under `stub-ecosystem`.

An adapter's tests should exercise its server through `InstalledEcosystem`, check findings' files and lines,
and verify that unknown values, unreadable files, and credentials obey the contract. Discovery tests should
also check that its `Describe` names the installed executable.
