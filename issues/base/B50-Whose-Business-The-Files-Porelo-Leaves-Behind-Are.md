# B50 — Whose Business The Files Porelo Leaves Behind Are

**Status:** Estimated.
**Size:** M - several modules (paths, gate, registry, doctor), a correction of existing machines, and acceptance runs
under two umasks.

**What must be true.** Every file Porelo writes is readable by whoever it concerns, and by nobody
else - including on machines that already ran tasks.

## Why

Found by asking of every file a package writes at runtime not *"is it a secret"* but *"whose
business is it"* - a question that came from the interface's own review and that nothing here had
asked.

### What happens today

Measured on the Ubuntu test VM, on a machine that had run real tasks:

    drwxrwxr-x  ~/.local/share/porelo
    drwxrwxr-x  ~/.local/share/porelo/mirrors/<project>.git
    -rw-rw-r--  ~/.local/share/porelo/mirrors/<project>.git/config, packed-refs, HEAD, …
    -rw-rw-r--  ~/.local/share/porelo/projects/<name>
    -rw-rw-r--  ~/.local/share/porelo/upstream/<name>

**Nobody chose those modes. They are the shell's umask**, which on this machine happens to be 002.

What is in them is not incidental:

- **The gate mirror holds the work an agent pushed and nobody has reviewed yet.** That is the thing
  the pre-push guard and `gate checkout` exist to keep from leaving unseen, and it
  is the whole source of every project this machine has touched.
- **The registry** names every project and the path to its project file.
- **The upstream records** say what each project's upstream is and how far behind it has fallen.

### Why this is not already covered

**Porelo asks this question elsewhere and answers it deliberately.** The runtime directory is
`0700`, with a comment saying the directory *is* the access control and that a window in which it
is traversable is the whole hole. The vault file is `0600`. The proxy and ssh-agent sockets are
permissive **inside** a `0700` directory, and that is reasoned about at length.

The durable directory beside them got the umask.

**What covers it today is not ours.** This machine's home is `0750`, so nobody else traverses it.
Change the home's mode, add a second person to the user's group, or run with `umask 022` on a
machine where `/home/<user>` is `0755`, and the cover is gone. **A protection you did not set is
not one you can rely on**, and the inconsistency is the tell: the same question was asked and
answered two directories away.


## The shape

1. **Porelo sets the mode of every directory it creates**, rather than inheriting one. The durable
   directory holds work and source, so it is owner-only, for the same reason the runtime directory
   already is.
2. **A file written into it is owner-only too.** A `0700` directory with `0664` files inside is
   safe only while the directory holds; defence that depends on exactly one thing is how the
   umask got in.
3. **An existing installation is corrected, not just new ones.** This is the part that makes it
   work rather than a line in a constructor: every machine that has run a task already has these
   directories at whatever mode it had then, and a fix that only applies to a fresh install leaves
   every real machine as it is.
4. **The correction is visible.** Silently changing modes under an operator is its own surprise -
   `porelo doctor` is where this belongs: it says what is wrong, and the fix is something a person
   runs knowing what it does.
5. **A review checkout is owner-only too.** `~/porelo/reviews` sits in the home so a person can open
   it in their own editor, not so others can read it: it holds the same unreviewed work as the
   mirror. Sharing a review is a deliberate copy a person makes, never a mode Porelo leaves open.
6. **Nothing is loosened to make this work.** The sockets are permissive on purpose, inside a
   locked directory, and that reasoning is measured and stays. This requirement is about the
   directories nobody reasoned about.

## Plan

Read in today's code. `XdgPaths` (`core`) names the data and state directories and sets no mode. `ProjectPaths`
(`apps/app-base`) writes `projects/` and `upstream/`; `GitGate` (`gate`) makes a mirror under `mirrors/` with
`git init --bare` or `git clone --bare`; `ProjectInventory` and `BackupRestore` resolve the same paths. Owner-only
is already set where somebody thought of it: `HandIns`, `TaskBuilds`, `TaskState`, `TaskHelpers`, `NodeIdentity`,
`HostKey`, `VaultFile`, and the socket directories in `VaultProxy` and `SshAgentServer`.

Steps, in order:

1. Red first: an acceptance scenario that runs a task under `umask 002` and one under `umask 022`, checks its work
   out with `porelo gate checkout`, then finds nothing under the data and state directories or `~/porelo/reviews` with
   `find -perm /077`. It fails today on `mirrors/`, `projects/`, `upstream/` and the review checkout.
2. One helper in `core` beside `XdgPaths`: create every missing component below a root with `rwx------` as the
   creation attribute, one component at a time, and write a file with `rw-------`. Creating each component with the
   attribute leaves no window, which answers the race in `createDirectories`.
3. Route the durable writers through it: the data and state directories, `projects/`, `upstream/`, `mirrors/`, and
   `~/porelo/reviews` (`GatePaths.reviewCheckout`, checked out into by `GateCheckoutCommand`): `reviews/` and each
   checkout's directory are created owner-only before git writes into them, and the working tree, which
   `core.sharedRepository` does not cover, is tightened by a walk once the checkout is written.
4. Mirrors: `git init --bare --shared=0600`, and `git config core.sharedRepository 0600` after a clone. git then
   writes files `0600` and directories `0700` whatever the umask, which answers what git creates underneath.
5. `porelo doctor` gains a probe that walks the data and state directories and `~/porelo/reviews` (not the runtime
   directory's sockets) and fails, naming each path readable by group or others. At a terminal it offers the remedy
   as a decision, as other probes do: it tightens the named paths and reports what it changed. That is the
   correction of an older machine.
6. Tests: the helper under a missing parent; `doctor` with a loosened path named; a machine at the old modes
   corrected and reported; the proxy's socket keeps its mode inside its `0700` directory.
7. A second acceptance account reads nothing under the first account's data directory, since the suite already
   runs several accounts on one machine.

## Acceptance

- On a machine that has run a task, every directory and file Porelo created under its data
  directory is owner-only, asserted against a real run rather than against a constructor.
- A review checked out into `~/porelo/reviews` is owner-only, the directory and every file in it, under either umask.
- A machine that ran tasks under an older Porelo is corrected, and the correction is reported rather
  than performed silently.
- `porelo doctor` fails, and names the paths, when any of them is readable by anyone else.
- The socket directories keep the modes they have, with their existing reasoning intact - a test
  asserts the permissive socket inside the `0700` directory still is what it is.
- Setting `umask 022` before a run changes nothing about the result.
- **Seen to fail:** the real-run test goes red when any directory or file under the data directory
  or `~/porelo/reviews` is readable by group or others, including a run under `umask 002` or `022`; a test that starts
  from a data directory at the old modes goes red when it is not corrected or the correction is not
  reported; `porelo doctor`'s test goes red when a loosened path is not named; the socket test goes
  red when the socket's mode changes.

## To be checked

- **What `podman build` writes into the build directory** (Agent Core's): measured after a build, and whether it
  needs a pass afterwards or a directory outside the data directory.
- **Whether anything under the data directory is bind-mounted into a task** (Agent Core's). A container's account
  maps to a subordinate uid, so an owner-only file there would be unreadable to it; a search of the mounts answers
  it before step 3.
