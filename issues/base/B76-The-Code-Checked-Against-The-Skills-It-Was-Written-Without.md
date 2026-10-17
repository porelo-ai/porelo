# B76 — The Code Checked Against The Skills It Was Written Without

**Status:** Implementing.
**Size:** L - no open findings and every module read; left: `apps/*` and `daemon` read again whole, and one rule for the kit's bounds.

**What must be true.** Every Java module has been read against the skills that apply to it, and
each finding is either fixed with a test that was watched to fail, or declined with the reason
written down.

## Why

Every agent opens this issue in its own repository; this is Porelo's.

`AGENTS.md` names the skills this repository expects - **GraalVM**, and the **Java** set - and says
where they come from: `https://fuinorg.jfrog.io/artifactory/agent-skills/`. **Nothing had been
written with them.** They were not installed in the harness, and the section that asks for them
sits a thousand lines into a file an agent reads for the rule it needs.

Measured on the first module that was checked, `porelo-release`, written the same day without them:
reading it against `java-code-review` and `security-audit` found **two defects that its 104 tests
had not**, both fixed in `4a0e933`:

- **A credential went wherever a pom property pointed.** `GITHUB_TOKEN` was sent to the configured
  address, so a typo or a hostile change to one property sent a CI token to another host. It now goes
  to `https://api.github.com` only.
- **A fault answered the question.** An unexpected exception left the JVM with exit 1, which the
  callers read as "a person must look" or "they disagree". It now exits 2, unanswered.

**Tests that pass are not evidence that the code was reviewed** - they are evidence that it does what
its author thought of. The skills are a second author's list of what to think of.

### How to get them

**The harness here does not load them**, and this session could not write to its skills
directory. Reading is enough: download each package as `AGENTS.md` *How to get them* says, check its
SHA-256 against the repository's, unpack it anywhere, and read `SKILL.md`.

**The versions read against from now on**, each zip checked against its SHA-256 before it is unpacked; a newer
version taken while this is open is named in the commit that reads with it:

    graal               2026.910.3  b24ec6272fb13490e32977c21d4543869a7e85ed474fdc0ff34022fbddd88bf6
    java-code-review    2026.906.3  802f86339442ed9fa9d5bca14b6d16e1f6420ae92a047b18bba11239e32c698b
    test-quality        2026.906.3  9c5d23ad7a6d9c6f5c3679f3cc44a7fcc2fb3cc1c0f0d0ba4a2c0f7812428bad
    security-audit      2026.906.3  a46fd1be07bd539b955291537dea0f24f65a2f8b15d40d50a6a9098bbd992747
    concurrency-review  2026.906.3  3a0aab612f25483a3fe777eb18fbcc434e936b19298708bf88beff8b22d79e56
    clean-code          2026.906.3  5cf777a24630298b4b801c934f599722978c21aeac53d61683243908fc041c7b
    solid-principles    2026.906.3  bd47cdab9126dde76cf1703472167a1c88c85306a4e0263a8f2db4c462740cf5

The readings listed below were made before versions were recorded; which they used is not on record.

## The shape

**Every Java module in this repository**, read against:

    java-code-review    null safety, exceptions, collections, resources, API shape
    test-quality        what the tests prove, not how many there are
    security-audit      secrets, input from outside, what reaches another host
    concurrency-review  the daemon, the proxies, the watchers - shared state and threads
    clean-code, solid-principles
    graal               the modules built as native images: metadata, class initialization,
                        resources - the properties this project has learnt the expensive way

### Done so far

    porelo-release      java-code-review, test-quality, security-audit                  4a0e933
    porelo-supervisor   java-code-review, security-audit, concurrency-review,
                       test-quality, clean-code, solid-principles                      the commit below
    porelo-vault        the same six, and graal for its FFM                             the commit below
    porelod             concurrency-review, security-audit, java-code-review,
                       test-quality, graal                                             (this commit)
    porelo-hooks,       security-audit, graal, java-code-review, test-quality,
    porelo-shield       concurrency-review                                              (this commit)
    porelo-gate         security-audit, java-code-review, concurrency-review,
                       test-quality                                                    ac40a26
    porelo-agent-api,   security-audit, java-code-review, concurrency-review,
    porelo-agent-stub   test-quality                                                    ac40a26
    porelo-core         security-audit, java-code-review, concurrency-review,
                       test-quality, graal                                             ac40a26
    porelo-clearance,   security-audit, java-code-review, concurrency-review,
    porelo-runtime,     test-quality                                                    ac40a26
    porelo-wire
    porelo (app)        security-audit, java-code-review, concurrency-review,
                       test-quality                                                    ac40a26
    the acceptance     test-quality, security-audit, java-code-review,
    kit and suite      concurrency-review                                              ac40a26
    porelo-build-api   security-audit, java-code-review, concurrency-review,
                       test-quality, at 2026.906.3                                     2026-10-17, its fixes
    porelo-build-stub  the same four, at 2026.906.3                                    (this commit)
    porelo-dist-       test-quality, security-audit, java-code-review,
    package-check      at 2026.906.3                                                   2026-10-17, its fix
    porelo-acceptance- test-quality, security-audit, java-code-review,
    legs               at 2026.906.3                                                   (this commit)
    porelod, again     security-audit, java-code-review, concurrency-review,
                       test-quality at 2026.906.3, graal at 2026.910.3                 00db32ee
    porelo-app-vault   security-audit, java-code-review, concurrency-review,
                       test-quality, at 2026.906.3                                     808dc051
    porelo-app-gate    security-audit, java-code-review, concurrency-review,
                       test-quality, at 2026.906.3                                     (this commit)

"The commit below" is the one that also lets a leg's image cache report never fail the leg.

The order is settled: credentials and threads first, so a finding there never waits behind a style
note - `supervisor`, `vault`, `daemon`, `hooks` and `shield`, `gate`, `clearance`/`runtime`/`wire`,
`core`, the agent modules, `app` by area, the acceptance kit. The tooling modules are read in
`porelo-buildtools`, where they now live.

### `porelo-supervisor`

Fixed, each with a test watched to fail:

- **The real key could reach the broker's log in full.** A request target that is not a valid address
  made the address parser repeat it - after the key had been put in. It is parsed before the key is
  added, and refused with a 400 that repeats nothing.
- **A token request in another spelling went out with the real credential.** `grant_type=refresh%5Ftoken`,
  a JSON `\u005f` and a gzipped body passed the refusal. The body is now read as it came, form-decoded,
  JSON-unescaped, and unpacked when it is `gzip` or `deflate`.
- **The single flight held only for a purchase that succeeded.** Callers waiting behind a refusal each
  asked again; a grant the service had ended was spent once per request. A refusal is answered from
  memory for five seconds, an ended grant until it is authorized anew.
- **An expiry of zero, a negative one or a huge one** bought a token per request or threw out of the
  exchange unanswered; it is treated as unstated.
- **A device flow with an interval of nothing** polled without a pause and never ran out of time; it
  waits at least a second.
- **A reason from elsewhere was written into the error answer's JSON as it came**; it is quoted.
- Three Javadoc blocks sat on the wrong member.

Declined, with the reason:

- **The relay's accept loop does not back off** when accepting keeps failing. That happens only when
  the process runs out of file descriptors, a relay lives per task in that task's process, and no test
  can bring it about without exhausting the machine's descriptors.
- **`TokenSource` throws `TokenPurchase.Refused`.** `Refused` and `Ended` are the vocabulary every
  source of a token answers in; moving them renames across modules and changes no behaviour.
- **`VaultProxy` is one class for the whole exchange.** Splitting the answer path out would make it
  testable without a socket; the socket tests prove it as it is, and the split is a refactoring for
  its own sake now.
- **A `zstd` or `br` request body is not read** for a token request: the JDK cannot unpack them, an
  agent compresses its requests that way, and an answer carrying a token is still withheld.

Where the skills and this repository differ, the repository's rule holds: test methods are named as
sentences; absence is JSpecify's `@Nullable` checked by NullAway, not `Optional`; comments say why,
with the measurement; an empty catch is accepted when it says why.

### `porelo-vault`

Fixed, each with a test watched to fail:

- **A credential stored while a device unlocked was lost.** `enroll`, `revoke` and `used` read the
  vault, changed it and wrote it back without the lock `update` holds; `used` runs on every device
  unlock. Every read-modify-write now goes through one locked method.
- **A passphrase beyond ASCII typed at the star prompt** became one character per byte, so it opened
  nothing the same passphrase made any other way. The bytes of a character are read as one, and a
  backspace takes back a character.
- **A truncated RSA key file** threw a stack trace, or - cut inside its last number - was padded with
  zeros and accepted, and the forge then refused every signature. The DER reader checks every length
  against what is left.
- **A field length near the largest int** overflowed the OpenSSH reader's bounds check.
- **A vault entry printed its secret** through the record's own `toString`; it says its kind and length.
- A Javadoc block sat on the wrong method.

Moved, with the reason:

- **Copies of the passphrase and keys left in memory** - the keyring's strings and native buffers, a
  device key and the master key on paths that throw - are [B23](README.md#b23)'s, which owns what a managed runtime
  can and cannot wipe; none of it can be observed from a test.

Declined, with the reason:

- **A header with many passphrase slots** makes opening run Argon2 once per slot, past the cost cap.
  Writing such a header needs the account the vault belongs to, which can already do worse.

Three tests that did not prove their guard, each now seen red with its guard undone, read against
`test-quality` and `concurrency-review` 2026.906.3:

- **The keyring's session-link case never removed the link.** It leaves for a new, empty session keyring of
  its own process before reading, so only the search of `@u` can find the key; the operator's session keyring
  is not touched.
- **The failed-write case threw before anything was written.** A seam now fails after the new content is
  durable and before it replaces the vault - which found that **a failed replacement left every credential,
  encrypted, in a `.vault*.tmp` beside the vault**, one per failure. The temporary file is deleted on any
  failure.
- **Nothing tested two processes against the file lock.** A second JVM holds the lock, and an update waits
  until it lets go.

### `porelod`

Fixed, each with a test watched to fail:

- **Follow records written by two threads of the daemon at once** - a follow over the socket and the
  follow pass - threw, because a file lock does not wait for a thread of the same process. The socket
  answered "null", or the pass dropped its record. A lock per directory now goes around the file lock.
- **A log tail counted its position by encoding decoded text again**: one byte that was not UTF-8 put
  the position past the end, and the log was sent again every 200 ms; a line longer than one read was
  never passed. Counted in bytes now, and a read that is one long line goes as it is.
- **`Talk` replayed the machine's whole message history** to every client that connected; the
  contract says it is live only.
- **A message to `''` or `'..'`** reached directories that exist and made a mailbox there; a name that
  is no task's is `NoSuchTask`, as everywhere.
- **A URL, an upstream or a host from the socket** reached git and ssh-keyscan as an argument, and one
  starting with a dash is an option there (`--upload-pack=...` is a command git runs). Such a value is
  `InvalidParameter` before any program sees it.
- The test that a second daemon takes over a stale socket never met one; it makes one now.

Fixed without a test: the pending-work bundle is written in an owner-only directory of its own, not
in a name freed in the shared `/tmp`. Racing for that name needs a second user, which no test here has.

Declined, with the reason:

- **`RestoreBackup` takes any bundle the client names.** The socket is the account's own, so the
  client is the account, which can restore any bundle it has at the terminal too; the restore's
  `HOLDS_WORK` refusal still protects work nobody reviewed.

Closed in `wire`: **a stream noticed a client had gone only when it next sent**, so a client that
reconnected left `Watch` and `Prompts` asking podman every half second for the daemon's life. A stream
now asks between its waits whether its client is still there - a read that does not wait - and ends.

### `porelo-gate`

Fixed, each with a test watched to fail:

- **What was approved was the ref as it stood at the approval, not what was reviewed.** The task's gate stays
  up while a person reads, so a push made after the review reached the upstream unseen, and one landing
  between the forward and the clean-up was deleted. `approve` and `approveSigned` pin the commit, take the one
  reviewed (`--commit`, `Approve`'s `commit`) and refuse when the ref has moved; the ref is removed only while
  it still holds what went.
- **Every task could read every other task's work.** The mirror is the project's; served whole, one agent
  listed and fetched another's unreviewed ref. A task's gate hides all incoming refs but its own and its
  rescue - by name, so `task-1` does not reveal `task-10`.
- **A ref name ending in a Unicode space** compared equal to the task's own ref after Java's trimming, and git
  made a second ref of it. Only the line end is taken off, as receive-pack does.
- **A tag or a blob pushed to an incoming ref** made the pending list throw for every task of the project. It
  is listed as "not a commit", and a date no instant can hold no longer throws.
- **A workflow whose path git quotes** - a byte beyond ASCII, a `"` - ranked ordinary. Paths are read with `-z`.
- **A git that hung held its request for ever**: the limit started only once its output had closed. It runs
  from the start, and git is ended whatever ends the request.
- **A request body was read without a limit**; beyond a gigabyte it is refused before it is held.
- **A refused ref name reached the log as sent**, with line breaks and escape sequences; control characters are
  written out.

Declined, with the reason:

- **No limit on how slowly a request's headers arrive.** A gate serves one task on loopback; only the
  network fallback binds more widely, and the JDK server's limit is process-wide and unmeasured here.
- **A response is held whole in memory.** It is a fetch of the project's own mirror, whose size is the
  repository's; streaming it is a rewrite of the server for a case not seen.

### The agent modules, and one more in `porelo-supervisor`

Fixed, each with a test watched to fail:

- **The real API key could go to a host the agent chose.** The broker forwarded to the upstream with the agent's
  request target appended as it came: `@other:<port>/x` made the upstream's host the user part, and
  `.attacker.example/x` extended it. Only a path is forwarded, and only to the upstream's own host and port.
- **A broken adapter could hang every task start or break the lookup for all agents.** A word with no line end
  held the readiness wait for ever, the handshake had no limit, and a connection failure escaped the list as
  another kind of exception, leaving the agents started before it running. The wait reads what is there, the
  handshake is bounded, every failure is the agent's own, and its client and socket are cleaned up.
  **The bound itself was first a defect**, found by the Pi agent's suite under load: the handshake waited on a
  virtual thread, so on a machine of two processors an agent that answered in a tenth of a second was given up
  on, and the executor was never closed. It waits on a platform thread of its own, ended with each handshake.
- **`mode: 0644` installed a file as 0420**, YAML reading it in octal; a mode must be written as a string.
- **An install target, a reason or a packaged target could start another build instruction** - `;`, a line
  break, a space. Each is a plain path, or one line.
- **A provider file of a person's own could take a packaged provider over without a word**; `porelo providers`
  says which file was set aside for which.

Declined, with the reason:

- **A provider's `authQuery` map is not copied**, and **a session id may begin with `-`**: the map is never
  changed after reading, and the id is quoted and stays inside the container it came from.

### `porelo-core`

Fixed, each with a test watched to fail:

- **An upstream in the project file could run a command on the host.** It reached `git fetch`, `clone` and
  `push` as written, and `--upload-pack=<command>` ran at the next task start (reproduced). The project file
  refuses an upstream or `base_image` that starts with `-`, uses `ext::` or `fd::`, or holds a space or a line
  break; and the gate puts `--end-of-options` before every address, so no other way in can.
- **`snippet_file` read any file the person can** - an absolute path, `../`, a link - into the image or an error
  on screen. It is read from the repository and nowhere else.
- **A transport's name under `mail.transports` became a path** that clearing a project deleted under; it is
  named as a peer's transport is.
- **A command's time limit did not hold**: only the child was ended, and its output was waited for without
  a limit while a grandchild held it open. The descendants are ended at the limit, and the output is taken
  after a short grace. **A child that did not read its input** blocked the caller, or failed a command that had
  succeeded. **Output was collected without a limit**; its end is kept, with how much was cut.
- **A credential covered whatever began like its match** - `https://github.com` covered
  `https://github.com.evil.example`. It covers its host and what is below it.

Declined, with the reason:

- **`prctl` is called without saying it is variadic.** Undefined by the ABI, it is right on both architectures
  Porelo builds for; changing the downcall changes its native-image registration, which CI checks, for no
  behaviour that differs here.

### `porelo-clearance`, `porelo-runtime` and `porelo-wire`

Fixed, each with a test watched to fail:

- **A person's answer from a client was undone by the notification running out.** The timeout wrote over the
  ALLOW given from an interface while the notification waited: the firewall was open, the hub said it was not,
  and after a resume it was never allowed again. An answer from elsewhere stands; one at the notification
  itself came last, and stands too.
- **The name the agent queried went into the desktop notification as it came** - markup, a right-to-left
  override that made it read as another host, a length that pushed the warning out of view. Markup is escaped,
  control and format characters written out, and a long name cut in the middle, the address kept.
- **An agent could keep a person answering one question after another**, one per port. A run asks at most 20;
  beyond, a new destination stays blocked unasked, recorded as `limit`, and a client can still answer it.
- **A report reached every subscriber with whatever its sender added**, `verdict` and `source` included, and
  read like an answer. It carries what was blocked and nothing else.
- **Two projects could share a container name** - `web` with task `app-x`, `web-app` with task `x` - and a
  start brought back the other project's task with its workspace and egress. The project label decides whose
  it is, at a start and among a task's peers.
- **The JSON reader** overflowed the stack on deep nesting (now refused past 256), read `1e999` as Infinity and
  wrote it back as no JSON, accepted a key given twice when its first value was `null`, and a `\u` escape with
  a sign.
- **A stream's check whether its client was still there buffered without limit**; past a message's limit the
  client is answered as gone. Clearance's own `Subscribe` ends with its client too.
- **A label went between single quotes as it came** in a task window's script; it is quoted as a word.

Fixed without a test, with the reason:

- **The desktop prompt registered a signal handler per question and never removed it.** It is closed when the
  question is done; showing it takes a session bus, which neither the build nor CI has.
- **The clearance journal was readable by others until its first line was written.** It is created owner-only;
  the window cannot be observed after the fact.

Declined, with the reason:

- **A client that half-closes its side after a streaming call is answered as gone.** No client here does it,
  and the end of input is the one sign a stream has.

### `porelo-hooks` and `porelo-shield`

Fixed, each with a test watched to fail:

- **A domain in an egress set file reached the resolver's configuration unchecked.** A project's
  domains were checked and a set's were not, so `"#"` in a set became `server=/#/...`: every name
  resolved, and every answer opened its ports with nobody asked. A set's domains are checked as a
  project's are, by the one check both now share.
- **The firewall hook answered any stage but `createRuntime` as done**, a missing one too, so a
  descriptor without the stage or with another started the container with no ruleset. Only
  `poststop` has nothing to do; anything else refuses the container.
- **An allowed address could be a range or more than an address.** The sets take intervals, so
  `0.0.0.0/0` opened everything, and nft reads a `;` in its arguments as a further command. Only one
  literal address is allowed or withdrawn.
- **A hook caught only exceptions**: an `Error` left no line in the hook log and refused the container
  even for a hook that only reports. Everything is caught at that boundary now.

Also: the resolver's class comment claimed it closed DNS exfiltration, which `doc/security.md` rightly
says it does not; `doc/reach.md` promised ports beyond 80 and 443 that no project key offers; two
Javadoc blocks sat on the wrong member; two unused imports.

Fixed since, read against `java-code-review`, `concurrency-review` and `test-quality` 2026.906.3, its test seen
red with the fix undone:

- **A hung `nft` was killed by the runtime's timeout before the hook's**, and the hook log said nothing about why.
  The cause was worse than the order of two limits: the hook read nft's whole output before it waited, so its own
  30 seconds never started while nft held its output open. Output and input now go on threads of their own beside
  the wait, the hook's limit is 20 seconds against the runtime's 30, and the refusal says it timed out. The program
  comes through a seam; the test runs a stand-in that sleeps with its output open.

Fixed since, read against `java-code-review` and `test-quality` 2026.906.3, its test seen red with the fix undone:

- **A refused bind left the netlink socket open**, its descriptor and its arena, once per attempt. Both are
  released before the refusal is thrown. The test makes the kernel refuse a bind for real - a second socket asking
  for the port another holds gets `EADDRINUSE` - and counts `/proc/self/fd`. `NflogReader`, its one caller, already
  closes the socket when its own setup fails after it.

### `porelo` (the command line and the daemon's logic)

Read by area - the task's start, its messages, projects and following, the gate's commands, clearing. Fixed, each
with a test watched to fail:

- **A follow kept the key it pinned when the follow was not applied**, so a refused or failed follow still
  trusted the signer it named. The pins are put back. A followed address that git would read as an option is
  refused, and the fetch puts `--end-of-options` before it.
- **The signature on a followed project was checked against any project's lines**, and on the ref rather
  than the commit that was then applied. It is checked against the project's own lines, on the commit.
- **What a task wrote reached the person's terminal as written**: names of held messages, a transport's
  error, a review's file names. Terminal controls are shown escaped wherever `talk`, `gate pending` and
  `gate review` print them.
- **A task's box could be made to reach outside it.** A message file that was a link was read through it; a
  box whose directories the task replaced with links led the host to read, sign and delete, or write,
  wherever its account can. Links are not followed, and nothing is taken from or put into a box that holds
  one. A message is read up to a limit, its role from the bytes signed, not from a second read.
- **Two writers changed one record at once.** The daemon and the command line both pass a box, record a
  message and decide a held one; a project is reconciled, a task resumed and an image built from either.
  Each now holds a lock the other respects, in one process and across them.
- **Projects whose names meet shared what was theirs**: `web` with `app-api` and `web-app` with `api` had one
  deploy key and one container name, so one project's start brought back the other's task and clearing one
  deleted the other's key. A key is named by project and repository apart, a key kept under the old name
  goes only to the repository it was made for, and a container is taken by its project label.
- **A start at an unknown host was checked against the project file only**, not against `--upstream`; a
  host was known by any entry that named it among others, or on another port; `host:path` without a user
  was not seen as ssh. Each is asked as the start will connect.
- **A refused start left an account in the conversation**, and a failed start left its container behind.
  The account is made after the last refusal, and a failed start removes what it made.
- **A mirror was seeded from the directory the command ran in** when that was another project's checkout.
  It is seeded only from this project's own.
- **Clearing the account stopped at the first project that held work**, with the ones before it already
  gone, and a project whose removal failed still lost its follow and its keys. Clearing the account is all
  or nothing, and a failed project keeps what belongs to it.
- **The ssh command and the credential helper given to git were joined unquoted**, so a state directory
  with a space broke every fetch and a key path with `;` ran as shell. Each path is one shell word.

Fixed since, read against `java-code-review`, `concurrency-review` and `test-quality` 2026.906.3, each test seen
red with the fix undone:

- **A second copy of a held message was held a second time**, and a person who released both made the agent read
  it twice: only what was delivered counted as seen, and a held message was recorded without its id. Its id is
  recorded now, where it could be read, and counts as seen; the copy is recorded as a repeat, "a copy of it is held
  already".
- **A directory the message watch could not watch was skipped without a word** - when the machine's inotify watches
  run out, or one it cannot read - and its messages simply took the timed pass's minute. The daemon's journal names
  it and why, and `doc/messages.md` says when messages move.
- **Two lends for one label took each other's socket.** A credential lent for a follow was served at
  `git-<label>.sock`, and each lend removed what lay there first, so a follow by the daemon and one at the command
  line at once left one fetch without its key. Each lend has a socket of its own. Read as the finding meant: the
  "pruning" was that removal; nothing prunes the broker's expired tokens in the product.
- **Two tasks starting at once could remove each other's kept tokens**, found reading the above. `TaskSecrets.keep`
  pruned the entries of containers missing from a list taken before the vault was opened, so a start beside it that
  kept its tokens in between lost them as a gone task's, and could not come back after a reboot. The containers that
  exist are asked under the vault's lock; the test sees the lock held when they are asked.

### The acceptance kit and suite

Read for what makes a suite lie: a step that passes when what it checks did not happen. Fixed, each with a test
watched to fail where it can be one without a machine, and measured on the machine where it cannot:

- **The check that a passphrase was not echoed could not fail.** The kit's terminals have echo off at the
  machine's end, so a prompt that left echo on showed nothing either. The vault's steps type at a terminal
  that echoes, as a person's does.
- **A firewall check passed when the firewall could not be read**, and a port probe that could not run read
  as closed. The scenario shows the address entered the set first, says so when the set cannot be read, and
  tells a probe that did not run from a closed port.
- **A task counted as started on its container line alone**, so a start that failed after it passed. The
  start's exit status is part of it.
- **The summary counted undefined, pending and ambiguous scenarios as passed**, and only failed ones were
  annotated: a renamed kit step made an agent repository's scenarios undefined, and the summary said every one
  passed. They are counted apart and annotated.
- **`its output mentions one of` passed for anything** when the list held an empty entry; it is refused.
- **Failure messages printed whole outputs**, where a secret typed or put a moment before can be. They pass
  through the scenario's redaction, the terminal's timeout too.
- **One account that could not be reached left every later account's projects behind** at cleanup. Each is
  cleaned up on its own. The reboot request's own answer was lost to a backgrounded list. A security class and
  a package name reached a shell unquoted.

Declined, with the reason:

- **`is a snapshot that the next build supersedes` passes for a release.** The agent repositories run that
  step on every leg, a release's too, where there is no next snapshot; it says `RELEASE` and the version.

Fixed since, found when the suite first ran on a fresh account and seen red with the fix undone:

- **The deploy said it installed the stub build reader and installed nothing.** The reader goes up over SCP, which
  drops the execute bit, and the install ran only for an executable file - so it ended without a word, and the
  builds scenario failed on a fresh account. An account set up before had a reader from earlier, which hid it.
  It is installed whenever the file is there; `install -m 0755` sets the bit.

Fixed since, each test seen red with the fix undone:

- **A remote command had no time limit**: one hung `podman exec` waited for the job's limit and lost every report.
  Every command the kit runs on a machine is stopped there after 30 minutes (`timeout -k 10`), and a 124 that late
  is said as that limit, not as the scenario's own `timeout`, which ends sooner. Its ssh client, `porelo-buildtools`'
  `Ssh.run`, also read a command's output to the end before its errors, so a command blocked writing errors never
  ended; fixed there, and taken here with its next release.
- **`ends within N seconds` asserted only that the limit was not reached**, so a start refused at once passed it.
  Ended means finished: exit 0, else the step says the exit code and what it said.

### `porelo-build-api` (`builds/api`)

Read on 2026-10-17 against security-audit, java-code-review, concurrency-review and test-quality, each at 2026.906.3
with the SHA-256 above. Fixed, each with a test seen failing without its fix:

- **A reader that had ended was asked for ever.** A question past its 120 seconds kills the reader's process, and the
  watch "tries again later" - with the same ended reader, so every later round said "Porelo cannot read builds" until
  the task was started again. A reader whose process has ended is started again, handshake and all, at the next
  question.
- **Fifty full logs did not fit one answer.** 50 jobs of 64 KiB are 4.4 MB in base64, past the 4 MiB a varlink
  message may hold, so the answer was refused in every round for as long as the commit was watched. A build carries
  at most 2 MiB of logs; past 32 full ones each keeps an equal share of its end. `porelo-build-github` takes the cut
  with its next build against this API.

Read and left as they are:

- **The token crosses the socket in every call.** The socket is bound in the task's directory under the runtime
  directory, which is the account's alone (0700), before it is made owner-only, so the moment between bind and
  `chmod` is no opening. `Target` never prints the token.
- **A reader's own failure reaches the task as the reader's message.** A reader is a package the operator installs,
  trusted as one ([the decision](../../doc/decisions.md#build-readers-are-packages-behind-a-versioned-api)); what it
  says when it fails is its own.
- **One caller at a time.** A question and its answer share one connection, and the watch asks from one thread; the
  class says so instead of locking.

### `porelo-build-stub` (`builds/stub`)

Read on 2026-10-17 against the same four skills. Nothing to fix: it answers from a file it reads again at every call,
keeps no state between calls, so the server's threads share nothing, and it is never installed but for the tests.

### `porelo-dist-package-check` (`dist/package-check`)

Read on 2026-10-17 against test-quality, security-audit and java-code-review, each at 2026.906.3. Fixed, with a test
seen failing without its fix:

- **A command that hung held the check to the job's own limit.** An image pull or an install in a container that did
  not end ran until the job's twenty minutes, and the report of every check after it was lost with the job. Each
  command now ends at five minutes - the whole check took 109 to 133 seconds in four green builds - with exit 124 and
  a line saying so, and the checks after it run and report.

Read and left as they are:

- **A version the build did not write** - a run number that is not a number - throws out of `VersionOrder`. The
  version comes from this tree's own build, which writes it.
- **The file list is read from `dpkg-deb -c` by columns**, so a path with a space would be split. No packaged path
  has one, and the payload comparison would fail loudly, not pass.
- **The containers mount `target/` with `:Z`**, which relabels those directories for SELinux. They hold only what the
  build made.

### `porelo-acceptance-legs` (`acceptance/legs`)

Read on 2026-10-17 against test-quality, security-audit and java-code-review, each at 2026.906.3. `Streamed` and
`CpuSteal` are from the same day and were read with it. Fixed, with a test seen failing without its fix:

- **The restart grant was root for anything.** The acceptance accounts were let run `systemd-run`, `systemctl` and
  `reboot` as root with any arguments, beside a comment saying a suite that can reboot cannot do anything else as root -
  but `systemd-run` and `systemctl` run whatever they are given. The grant now names the two commands the suite asks,
  each with its arguments: `systemd-run --on-active=1s /sbin/reboot` and `reboot`; `visudo` still checks it on the
  machine, and the same lines parse there.

Read and left as they are:

- **The failsafe reports are parsed with the JDK's default XML reader**, which would follow a document type. They are
  failsafe's own files, written here; a document type can only stand at their top, which failsafe writes.
- **The binaries come back as a tar from the rented machine** and are unpacked here. GNU tar strips `../` and leading
  `/`, the machine is one this run made from this tree, and every name is checked on arrival.
- **The tree's tar is left in the temporary directory** by `Main.archiveOf`. A runner is thrown away after the job; on a
  developer's machine it is one file per leg.
- **An account install stops at its first failure (`set -e`) and leaves its unpacked package** in a `mktemp`
  directory. The deploy says where it stopped.

### `porelod` (`daemon`), read again

Read on 2026-10-17 whole, for what changed since `ac40a26` - the methods split by area into `TaskMethods`,
`ProjectMethods`, `EgressMethods`, `VaultMethods`, `GateMethods`, `AgentMethods`, `MessagingMethods` and
`MachineMethods`, and `SelfReplacement` - against security-audit, java-code-review, concurrency-review and
test-quality, each at 2026.906.3, and graal at 2026.910.3, with the SHA-256 above. Fixed, each with a test seen
failing without its fix:

- **A backup of a project's other repository could not be deleted.** `Backups` lists a second repository's bundles
  under that repository's record, and `DeleteBackup` looked only under the project's own, so it answered
  `NO_SUCH_BACKUP` for a bundle the listing had just offered. It looks under every record the project's backups are
  kept in; still only a path a record names is deleted.
- **A start given an upstream was checked against another host.** `Start` asked whether this machine had met the host
  of the project file's upstream, while the gate fetches from the one given: a start at a known host was refused as
  `UnknownHostKey` for a host it never reaches, and one at a host never met passed and failed later with exit 69 instead
  of the typed refusal. The launch and the daemon now ask one question, `StartCheck.unknownHost` with the upstream.

Read and left as they are:

- **Paths and names from the socket reach the account's own files** - a bundle to restore, a backup record's key, a
  project file for `Prepare`. The socket is owner-only, so its client is the account, which can do the same at the
  terminal; `DeleteBackup` still deletes only a path a record names.
- **Each streaming method keeps its state on its connection's own thread** (`Watch`, `Tail`, `Talk`, `Prompts`,
  `Authorizations`), and `Prompts`' subscriptions are virtual threads it interrupts when its client goes; the channel
  is interruptible, so an interrupted subscription ends its blocking read.
- **`Talk` reads every mailbox's whole record every two seconds per client.** It is a cost, not a wrong answer; the
  records are small and a client that listens is a person's console.
- **`Decide` waits on the task's clearance watcher without a limit.** The watcher is Porelo's own, on a socket in the
  task's state, and every connection has a platform thread of its own, so a slow one holds only its caller.
- **`SelfReplacement` restarts the unit whose name ends the process's control group.** A daemon started by hand runs in
  a scope, not a service, and is only told; reading the group text is a seam the tests drive.
- **`WriteDestination` stages under one fixed name per destination**, so two writes of one name at once can fail one of
  them with an error; nothing is half-written, and the file is the account's own.
- **The native image registers the interface description by a resource glob**, the one resource the daemon reads; the
  daemon adds no FFM downcall of its own.
- Javadoc blocks sit above the wrong member in `PoreloDaemon` (`read`, `request`, `text`, `flag`, `main`); style
  only, left.

### `porelo-app-vault` (`apps/app-vault`)

Read on 2026-10-17 whole - all of it is new under this path since `ac40a26` - against security-audit,
java-code-review, concurrency-review and test-quality, each at 2026.906.3. Fixed, each with a test seen failing
without its fix:

- **A sign-in whose renewal failed was renewed again at every request.** The broker's `LoginRenewal` remembered
  nothing of a refusal, so each request of the task opened the vault - a key derivation - and sent the ended refresh
  token to the service again, one after another behind its lock. A failure is answered from memory for five seconds,
  then asked again, so a sign-in made anew reaches a running broker.
- **A grant authorized through the daemon was lost when a device's share had opened the vault.** Keeping it asked for
  the passphrase alone, which the daemon has no terminal for: the person granted at the service, the stream said
  `failed`, and the refresh token was gone. It is kept with whatever opens the vault, the share first, then the
  prompt.
- **The private key `vault put` converted could stay on disk in plain text.** When overwriting it failed, the removal
  the comment promised was skipped, and the directory's removal failed behind it. It is removed either way.
- **An unlock with a share answered `SHARE_REJECTED` with the vault open.** The share was held before "last used" was
  written, and a failure there was reported as a refused device. It answers `UNLOCKED`, and the detail says the last
  use could not be written.

Read and left as they are:

- **A provider reached with a key is never offered `vault put` by `vault authorize`.** The branch that offers it
  cannot run - an earlier return takes every provider without a grant - so such a start ends with exit 2 and advice
  meant for a grant. The offer is asked at a terminal only, which no unit test has; the refusal itself is right.
- **A credential's name is not checked before it names a route's token file and a variable**
  (`VaultServeCommand.routeTokenFile`, `CredentialWiring.variableSuffix`). The name comes from the project file or
  the run, and the broker refuses one the vault does not hold before any file is written, so the vault's own names
  bound it; checking the names narrows the project file's format, which other repositories read, so it is agreed
  first.
- **`KernelKeyring.store` casts a lifetime to `int` seconds**, so `vault unlock --for` or `UnlockWithShare` with years
  wraps it; that is the `vault` module's, read with it.
- **The broker's token files are owner-only in owner-only directories, and the token is logged abbreviated**; a route
  that is not https, or a header holding `|`, is refused with exit 2.
- **`LoginRenewal` takes its own monitor first and the vault's file lock second**, never the other way round, so two
  brokers cannot deadlock; the vault's lock spans processes.
- **Two brokers raising the same authorization question share one staging name**, so one move can fail. The question
  is the same either way and the failure is passed over on purpose.
- **`vault remove` revokes a grant at the service before the vault update**, so a failed update leaves a revoked grant,
  which the broker answers as ended: it fails closed.
- **`Keyslots` tells refusals apart by words in the vault's exception message.** Fragile, and right today; the words
  are the vault module's, tested there.
- **A device name or a credential name is printed as the account gave it**; the input is the account's own.
- `AgentLogin` has Javadoc above the wrong member and a comment that says `BROWSER` is not set while the code sets it;
  style only, left.

### `porelo-app-gate` (`apps/app-gate`)

Read on 2026-10-17 whole - all of it is new under this path since `ac40a26` - with the daemon's `GateMethods`, against
security-audit, java-code-review, concurrency-review and test-quality, each at 2026.906.3. Fixed, each with a test seen
failing without its fix:

- **Protecting a repository again wrote over a hook of the person's own.** Once the check was appended to their
  pre-push hook, the file carried the check's marker, and the next `gate protect` took it for Porelo's and replaced
  it with its two lines. A hook the check was appended to is left as it is.
- **Removing the check could delete the person's whole hook.** When the file had lost its last line end, the appended
  block was not recognized, and the marker in it made `--remove` delete the file. The block is recognized at the
  file's end too, and a hook holding an appended block is never deleted.
- **A restore over the daemon could leave no mirror at all.** `RestoreBackup` deleted the mirror before it knew the
  bundle was usable: forced over unreviewed work with a file that was no bundle, it failed and left nothing - and
  without force, a mirror with nothing waiting lost its branches. The mirror is moved aside, put back when the
  restore fails, and removed only once the bundle has restored, as `gate restore` already moved it.
- **The pre-push check let a push through unchecked when the far side held a commit this clone lacked.** A force
  push, or one made without fetching, gives the hook a range git cannot count; the check answered "nothing an agent
  wrote". It then counts what no tracking ref of that remote holds, as for a first push.
- **`gate review` compared with `main` by default**, while the daemon's `Review` and the contract use the mirror's
  `HEAD`. On a repository whose branch is not `main` the patch was the last commit's alone and the log the whole
  history, so a person approved commits the review never showed. The default is `HEAD`.

Fixed without a test, since it only reads wrong: the pre-push check prints an agent's subject and address with their
terminal controls written out, as `gate review` does; the push is refused either way.

Read and left as they are:

- **`gate approve` exits 0 when every branch it offered was taken**, which needs `nextFreeBranch` to keep naming
  taken branches through its fallback; nothing reaches that without a seam over the gate, and nothing was forwarded.
- **`gate checkout --approve` exits 64 after a wrong name is typed at "which one?"** while its sentence is the one for
  nothing waiting. Changing it changes an exit code, so it is not built.
- **`against`, `branch` and `name` from the socket reach git** after `--end-of-options`, in a refspec git checks
  itself, or through `rev-parse --verify`, which takes no option; `name` must be a pending push.
- **`PendingBundle` writes the whole bundle before comparing it with its limit.** It goes into an owner-only
  directory of its own and is bounded by the project's own mirror; only the reply is limited.
- **A checkout's branch is read, then pushed into**: a person moving it between is their own act, and the push
  refuses what does not fast-forward.
- **`gate serve` takes its token from the environment and prints it abbreviated**; an address other than loopback is
  the documented fallback.
- **`gate check` exits 0 when the agents cannot be asked.** It is an accident-catcher, says so loudly, and its class
  says it is not a control.
- **Each command is one process and the daemon builds a gate per call**, so the area holds no shared state; the
  gate's own locking is the gate module's.

## Plan

Every finding listed above is fixed or declined (2026-10-16), and the modules added since `ac40a26` are read (2026-10-17),
each with its row in *Done so far*. What was left was those modules:
`builds/api` and `builds/stub` (the build interface, about 1100 lines), `dist/package-check` (about 1000) and
`acceptance/legs` (about 1500), all added on 2026-10-08. `apps/*` and `daemon` have changed by many thousand
lines since their reading; they are read again only where a finding below touches them.

In order, credentials and threads first, one commit per module:

1. **The new modules**, read against the same skills: `builds/api` and `builds/stub` (security-audit,
   java-code-review, concurrency-review, test-quality), `dist/package-check` and `acceptance/legs`
   (test-quality, security-audit, java-code-review). Each gets its row in *Done so far* and a section here.

2. **`apps/*` and `daemon` read again whole** (decided 2026-10-17), for what changed since `ac40a26`: against
   security-audit, java-code-review, concurrency-review and test-quality, and graal where they are built native.
   One commit per area, credentials and threads first: `daemon`, then `app-vault`, `app-gate`, `app-task`,
   `app-model`, `app-base`, `app-project`, `app-egress`, `app-messaging` and `app`. Each gets its row in *Done so far*.
3. **One rule for the kit's bounds** (decided 2026-10-17), for every repository that uses the kit:
   - **The ready bound** (`ready_within_seconds`, the step *"reaches work without being asked anything"*) counts
     from the session existing, so it never covers an image build. An agent declares about four times its
     measured time to the ready screen. The kit refuses a declared bound above its cap by name, so a bound that grew
     fails at once instead of making a stuck run cost minutes. Claude Code's measured 15.4 to 15.9 s gives 60 s
     (porelo-claude-code [CC32](https://github.com/porelo-ai/porelo-claude-code/blob/main/issues/README.md#cc32)),
     where 300 s let five stuck scenarios fill a 30-minute step.
   - **The unattended run** (*"... ends within N seconds"*) follows the same rule: N is about four times the run as
     measured, and the kit refuses an N above its cap.
   - **The caps are set from what the agent repositories measure** - each one's time to ready and its unattended run,
     from its last VM run - and are written beside the steps in `ReadySteps`, each with its measurement.
   - **The named run** in `WaitingSteps` gets `timeout -k 10` as the others have, so a start that does not end on the
     stop is killed ten seconds later; today it holds the step.
   - Red first in `ReadyStepsTest`: a description with a bound above the cap, and an N above it, are refused naming
     the cap; the named run's command carries `-k 10`.
   - The agent repositories then change their manifests' `ready_within_seconds` and their features' N in their own
     commits, against the kit released with this.

Every fix keeps the rule above: a test watched to fail with the fix undone.

## Acceptance

- A list of the modules, each with the skills it was read against and the commit that closed it.
- **Every fix carries a test proven to fail** by undoing the fix, as this repository already requires.
- **Where a skill and this repository disagree, the repository's measurement wins and the
  disagreement is written here** - for example `test-quality` asks for `@DisplayName`, and this
  repository names test methods as sentences.
- A skill's claim that this build's own behavior contradicts is recorded with the measurement. The
  first one: `porelo-release` guarded against the JDK HTTP client forwarding `Authorization` across a
  redirect; measured on JDK 25, it drops the header when the host changes, and a test now holds it
  to that.
- **Seen to fail:** each fix's test, run with the fix undone, goes red; a module listed above
  without its skills and closing commit means this issue is not done.
