<!-- @formatter:off -->
<!-- noinspection -->
<!-- Prevents auto format, for JetBrains IDE File > Settings > Editor > Code Style (Formatter Tab) > Turn formatter on/off with markers in code comments  -->

<!-- This file is automatically generate by logchange tool 🌳 🪓 => 🪵 -->
<!-- Visit https://github.com/logchange/logchange and leave a star 🌟 -->
<!-- !!! ⚠️ DO NOT MODIFY THIS FILE, YOUR CHANGES WILL BE LOST ⚠️ !!! -->


[0.5.0] - 2026-10-17
--------------------

### Added (10 changes)

- An agent of another vendor is found by one description file in agents.d naming its executable, under the vendor's own names, beside the porelo-agent files 
- porelo doctor colours a failure red, a warning yellow and what is fine green on a terminal, beside the same words, and not when piped or with NO_COLOR set 
- porelo doctor --json says every finding as data - check, state, detail, remedy - from the same checks as the text 
- The FAQ says why a devcontainer is no cage for an agent, and how its file still serves as the image's recipe 
- The .deb and the .rpm declare the C library and zlib their binaries need, glibc 2.34 and zlib 1.2.2, and the package build refuses a binary needing more or a floor above Debian 13's glibc 2.41 
- porelo vault authorize makes the entry for a provider with a grant, from its URLs and scopes and the client id of the agent that signs in, vault put asks a grant for its client secret by name, and a start refused for want of a credential names the command that gets it 
- The package check installs the packages in Debian 13 and Fedora 43 too, the oldest systems supported, beside Ubuntu 26.04 and Fedora 44 
- An elevator pitch page, with a picture of what Porelo keeps an agent from 
- porelo task stop --all stops every task as panic does, and each stop of everything is one line in stops.log, also answered by the daemon's Panic 
- Approve refuses by name, as BranchExists, a branch that holds earlier work and suggests the next free one, and Start refuses by name, as EarlierWorkWaits, a task whose name's earlier work waits at the gate 

### Changed (27 changes)

- The repository key is installed only after its fingerprint is checked, in the documentation and in porelo-setup.sh 
- vault lock and vault passphrase retry a keyring that did not answer, and a kept passphrase that no longer opens the vault is dropped and asked again at a terminal 
- porelo vault login on an agent already signed in offers at a terminal to import what it has, log in again or cancel, instead of naming the commands to type 
- The daemon's journal says 'vault: R decrypted, H from memory' instead of vault reads and held, and a move asked while another waits its turn is merged into it rather than run again behind it 
- porelo task start with several agents installed and none named lists them and asks which, instead of asking for --agent 
- porelo task start without the agent's credential offers to run the command it would name - vault authorize, vault login or vault put - right there, and goes on once the credential is in place 
- clear offers to stop tasks holding a vault token and to clear what only --force clears, vault remove to forget a token only its service revokes, and credentials forget to remove the secret it leaves, each no by default 
- gate protect offers to append its check to somebody else's pre-push hook, and gate restore to move an existing mirror aside, where each named what to do 
- porelo task start at a host this machine never met shows the keys it offers and asks whether to trust one, instead of naming trust-host; --no-input and --yes for scripts 
- The provider, the agent for vault authorize and the project for shield egress, talk pass and talk hold are offered by number at a terminal where an option was missing 
- prune and clear ask whether to remove what they list, gate approve offers the next free branch, and project unfollow offers to stop running tasks or destroy unreviewed work, where each said what to type 
- A task from before a restart offers to copy its workspace to ./recovered and then to remove it, at task resume and task attach 
- porelo task start without -r lists the repositories the project works in and asks which, at a terminal 
- task start offers the next free name where earlier work of its name waits at the gate, and a shell where there is no agent or no gate for one 
- credentials check, vault login and vault authorize store a missing secret through vault put at a terminal, an ssh key from a path asked 
- porelo task remove offers at a terminal what it would name - stop the task, push or discard its work, start it to rescue its work, discard what nobody recorded - with --no-input and --yes for scripts 
- vault unlock with no vault offers vault init, vault import with nothing to import offers the agent's login, and a start offers to move a credential stored under the agent's name to its provider's or to refresh an older copy 
- An offline project never connects - its upstream and --upstream are refused, and it is followed only from a file - and any project can be followed from a bundle or a directory, which is never fetched in the background 
- The offline picture and security.md say that an offline task's agent still reaches its AI provider through the host, and only the code and its definition move as a file 
- Builds on the porelo-parent snapshot and the porelo-buildtools snapshot it manages, as every Porelo repository now does between releases 
- Every task image Porelo builds has python3 where its base has a package manager, for agents that evaluate Python; about 40 MB on ubuntu:24.04 
- The project takes the name Porelo - the commands porelo and porelod, the packages, the artifacts org.fuin.porelo, the varlink interfaces, paths, variables and names all say porelo; nothing of the earlier name's is read or kept, so a machine is set up afresh 
- Every workflow run is titled by its workflow, its branch or tag and its commit's subject, and the shared-rules workflow is named Shared rules check 
- A refusal shows what it pointed at - the tasks there are, the projects followed, why a followed project is not in force, the egress sets - and vault passphrase keeps the new passphrase where the old one was cached 
- A task's terminal feels like the one outside - Esc within 10 ms, Shift+Enter, focus, truecolour, the clipboard, links, the title and the wheel - and an account's own tmux.conf in $XDG_CONFIG_HOME/porelo is read in every task it starts 
- The daemon says in its journal which mailbox directory it cannot watch and why, for example when the inotify watches run out 
- At a terminal, a command that needs a shut vault asks for the passphrase itself and carries on; a task's start, run and resume keep it open as porelo vault unlock does, and vault login asks before the login 

### Removed (1 change)

- The exec:java@deploy execution is gone, which called a command the build tools no longer have; installing on a machine is the legs module's deploy, as AGENTS.md and doc/build.md now say 

### Fixed (16 changes)

- The account install finds the deb where the build leaves it since dist/ was grouped, dist/dist-deb/target, instead of saying there is none 
- Two follows at once that lend one credential no longer take away each other's key socket 
- A second copy of a held message is recorded as a repeat instead of being held again, so releasing them never hands the agent one message twice 
- Closing the message watch waits for its notice pass to finish, so a stopping daemon does not take the mail directories from under a move 
- The egress log reader no longer leaves a netlink socket open each time the kernel refuses its bind 
- A firewall hook whose nft hangs refuses the container within its own limit and says in the hook log that it timed out 
- The setup script and the documentation check the package repository's key against Porelo's own, which signs the packages, no longer against the key retired with the rename 
- prune no longer offers the build lock of a project that exists, nor anything of a project whose image build holds its lock 
- A start refused before it made anything answers its own refusal again - over the socket it arrived as Failed saying its container was no task Porelo made, and a failure while cleaning up never replaces the refusal 
- porelo doctor no longer calls the SELinux policy missing where SELinux is built into the kernel but not running, as under WSL2; it looks for a mounted selinuxfs, not its empty mount point 
- Shift+PageDown scrolls a task's session back down after Shift+PageUp, as in a plain terminal 
- task list names the containers named like tasks that it does not list, and why, instead of saying there are no tasks 
- Two tasks started at once no longer remove each other's tokens from the vault, so both can come back after a reboot 
- task status, task logs and the daemon's List and Watch say what a container named like a task without a recorded id is, instead of that there is no such task 
- A vault write that fails after its new content is on disk no longer leaves that content, encrypted, in a second file beside the vault 
- A wake types only into rest that holds over two looks, and presses Enter once more when its line stays in the agent's input box, saying a wake that still did not land as not delivered 


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



