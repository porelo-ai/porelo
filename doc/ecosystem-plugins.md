# Writing a build-tool plugin

A build-tool plugin recognizes one tool's settings and returns the settings files that tool needs in a task.
It is a native executable installed separately from Porelo. Porelo discovers it at run time through the
[ecosystem API](https://github.com/porelo-ai/porelo/tree/main/ecosystems/api).

A plugin runs normally on the developer's machine as the developer's account. It must open no connection,
never run the project's code, and write nothing itself. These are obligations of the plugin contract;
Porelo does not enforce them with a network namespace or a sandbox. Install only plugins you trust with
the files that account can read. The plugin process starts with an empty environment.

## Installing a plugin

Install the executable under its ecosystem name, matching `[a-z][a-z0-9-]{0,62}`, in one of these directories:

1. `$XDG_DATA_HOME/porelo/ecosystems`, under its usual default when unset.
2. `/usr/libexec/porelo/ecosystems` for a machine package.

Discovery considers executable regular files only and sorts names within each directory. The first directory
wins for each name, even if its plugin is broken. A broken plugin remains in the discovery answer with its
name and the reason it was refused; the other plugins are still checked. Remove or replace a broken local
copy to use the packaged copy of that name.

Discovery calls each executable with `describe`. Its standard output is a JSON object containing
`protocolVersion` and `ecosystem`. The ecosystem must equal the executable's file name. A description has
ten seconds to finish and may contain at most 64 KB. A protocol version below `EcosystemProtocol.LOWEST`
is refused; a newer version is accepted because Porelo asks only methods it knows.

`porelo ecosystems --json` lists discovery as `ecosystems`, each with `name`, `path`, and `refusal`,
null when usable. `porelo project init CHECKOUT --json` asks every usable plugin to detect settings in
the checkout and the account's home. It names refused and unreadable plugins without stopping the others.
A plugin that does not recognize the checkout contributes no findings.

The proposal groups findings by part and key. Matching values retain every source; disagreements stay
out of the draft. Unknown findings, unplaced material, and tools without a generic rule remain in the
answer. `host.<host>` rules become egress domains and `package.<name>` tools become image installation
lines. Other tools stay as comments for a person to place. `credential.<id>` names a credential needed
in the vault, never its value. The draft is checked by the project reader before it is printed.

Detection saves no proposal and writes no checkout file. Plugin communication uses temporary sockets,
removed after each call. With `--project`, the answer includes the project repository's revision;
`--yes --base REVISION` files the repository row as one signed commit. Filing writes YAML anew, so
comments do not survive and the answer retains the sources. No daemon operation is involved.

## At a first start

`porelo task start` with no project named, in a checkout on the `default` path with no `project.yml`, asks the same
plugins and takes the same draft, with the project named `<repository>-project`, since a project named like its
repository would read that repository as its own, where no task works. Nothing a plugin found is written or opened until a person
says yes at a terminal; the file then goes into the checkout, and this machine's copy of it is what starts. The daemon's `FirstStart` and `ConfirmFirstStart` answer the same for an interface on the
same computer.

A plugin runs no code and is bounded by a time and a size, and its answer is still input from a stranger's repository:
what it says goes through the project reader, and a person reads it before it counts. An unknown finding is said
with its `note`, which should name why and where, such as the parent POM that is not in the local repository; the
file is then written without it. A `host.<host>` that only the checkout names and no egress set holds is marked,
and the question then defaults to no.

## Implementing the contract

Build against `org.fuin.porelo:porelo-ecosystem-api` and `org.fuin.porelo:porelo-wire`, importing
`org.fuin.porelo:porelo-bom` for their versions. The API and wire library use Apache-2.0, so a plugin may
choose its own license. Implement `Ecosystem` and pass it to `EcosystemMain.run` from the executable's main
method. That supplies `describe` and `serve <socket>`. Build the executable with GraalVM Native Image.

`serve` listens on the Unix socket Porelo names, prints `ready <socket>` after it listens, and serves
`org.fuin.porelo.Ecosystem1`. Porelo ends the process when it closes the client. The
[published interface](https://github.com/porelo-ai/porelo/blob/main/ecosystems/api/src/main/resources/org/fuin/porelo/ecosystem/api/org.fuin.porelo.Ecosystem1.varlink)
is the wire contract; the API's server and client implement it together.

`Describe()` returns the protocol version and ecosystem. Both discovery and the running client's handshake
check them. The interface only grows: optional additions let older plugins keep working, and a method
missing from an older plugin means it cannot say, never that it found nothing. A breaking change raises
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

`Configure(repositories)` receives each declared repository's name and URL inside the task. It returns
`files`, each with a `path`, `content`, and `ownerOnly`. A path is relative to the task user's home, with
no empty segment, `.` or `..`, quote, backslash, or control character. Porelo places these files; the plugin
writes nothing. There is no checkout directory in this question because the workspace stays inside the
container. Return settings whenever a repository is declared, without inspecting a workspace on the host.

A directory with nothing of this tool answers `NotRecognized`. A file the plugin needs but cannot read
answers `Unreadable(detail)`, naming the file and reason without exposing credentials. Neither refusal
is an empty detection.

## Testing a plugin

The [stub](https://github.com/porelo-ai/porelo/tree/main/ecosystems/stub) answers from `answers.json` beside
its executable, read again on every call. Its `detect` member holds `findings` and `unplaced`; its
`configure` member holds `files`. A `refuse` member holds a `reason` and `detail`. The acceptance installer
places this executable in the account's ecosystem directory under `stub-ecosystem`.

A plugin's tests should exercise its server through `InstalledEcosystem`, check findings' files and lines,
and verify that unknown values, unreadable files, and credentials obey the contract. Discovery tests should
also check that its `Describe` names the installed executable.
