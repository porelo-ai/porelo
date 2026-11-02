<!-- @formatter:off -->
<!-- noinspection -->
<!-- Prevents auto format, for JetBrains IDE File > Settings > Editor > Code Style (Formatter Tab) > Turn formatter on/off with markers in code comments  -->

<!-- This file is automatically generate by logchange tool 🌳 🪓 => 🪵 -->
<!-- Visit https://github.com/logchange/logchange and leave a star 🌟 -->
<!-- !!! ⚠️ DO NOT MODIFY THIS FILE, YOUR CHANGES WILL BE LOST ⚠️ !!! -->


[0.4.1] - 2026-10-08
--------------------

### Added (6 changes)

- A task's gate serves four requests at once and answers the next at once with 503, spools a push to disk instead of holding it, and runs with its git in a scope of 1 GiB memory, 256 tasks and half the default CPU weight 
- Initial public version 
- ci/leg-build.sh is what a rented machine builds of this tree, for the legs and for the machine images' proof, and the build checks every module it names against the reactor 
- The daemon's journal says how long each move of messages took, what started it and how the vault was read 
- An online task's gate fetches from the upstream before the agent fetches, at most once every five seconds, and refuses any branch but the task's own, a tag or a deletion 
- The stub agent's working mode takes 'load': at every step a streamed request through its provider's socket and 8 MiB written to its disk 

### Changed (5 changes)

- A push that changes only documents - Markdown, mkdocs.yml, doc/ and issues/ - starts no build and leases no machine; the Shared rules workflow checks it 
- The build checks every page and file for a citation of an issue that goes stale, and the documentation chapter for a page off its navigation or a link the site cannot follow, with the shared build tools 0.4.1 
- The message filter runs without its 200 ms settle, since every message is put in place by rename 
- A screen read costs a file, not a process in the task: the task's root writes the agent's screen when it changes into a directory the agent cannot write, and a listing reads it there 
- The daemon's conversations hold what they read of the vault while vault.bin is unchanged and open, instead of decrypting it for every lookup; read once a move are the projects too 

### Fixed (23 changes)

- Opening the vault fetches at once every followed project that waited for it, and sends the messages that waited, rather than at the next round 
- A conversation's long-poll answer is one line in the daemon's journal 
- The build helper asks the forge again within seconds of the vault changing after it refused a credential, instead of after ten minutes 
- An online task whose project names a forge says why its builds are not followed when the helper that follows them never started or has ended 
- An online task whose builds cannot be read before its first push - the vault shut, its entry missing, the forge refusing - says why in the task's builds and the helper's log, rather than nothing 
- A followed project whose file names no upstream gets a deploy key for its own repository, for the address this machine follows it from 
- Removing an online task, or starting a new one of its name, drops what it left at its gate; a new guarded or offline task is refused while work of an earlier one of its name waits there 
- Stopping a task stops its helpers with every process they started, so a build reader no longer outlives its task 
- What a leg installs and fetches is the tree's own, ci/leg-install.sh and ci/leg-binaries, and the build checks every path in them against the modules and their image names 
- A task listing reads the attached agents' screens several at once, so List no longer takes about 100 ms for every running task 
- The daemon's waits on a transport rest five seconds when the transport came back at once with nothing, so a task with no account, or a shut vault, no longer keeps a core busy 
- What waited for the vault moves as soon as it opens, also right after the daemon started, rather than at the first timed pass a minute on 
- A message a task writes, and one a transport hands in, moves at once - filter, send, delivery - instead of waiting for every conversation's transport to be asked; the read marks follow each delivery 
- The daemon watches for messages on an account whose mail directory comes after its start, and a person's message written with talk say is noticed at once 
- An online task whose workspace fetched nothing from its upstream does not start: it says git's own words and the ssh key lent, and the container is kept for a look; a resumed task with work starts as before 
- An online task whose upstream is an ssh address reaches that host on the port the address names, 22 for git@host, so it fetches and pushes - and no other host gets that port 
- A task brought back after a restart starts again; the screen's directory the restart emptied is made again before its container starts 
- Review over the daemon without a base compares with the mirror's default branch, as its contract says, instead of showing only the push's last commit 
- A review at the gate shows what the task changed since its work and the branch last met, no longer the upstream's newer work as removed when another task's start had moved the mirror on 
- Stopping or removing a task whose record is gone answers that it is no task, rather than failing with a stack trace in failures.log 
- How far a repository's mirror is behind its upstream is measured with the repository's credential from the vault, so a private upstream over ssh no longer answers Permission denied 
- A task's workspace gives up on a remote that does not answer within 60 seconds and says so, instead of holding the launch for five minutes without a word 
- In a task's workspace the branch tracks the task's own place, so git status says up to date right after a push and ahead by N before one 


