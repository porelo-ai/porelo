# Decisions

The choices Porelo is built on, each with why it holds and, for an accepted risk, the exposure, why it stays and what
would change the answer.

| Subject | What holds | Link |
|---|---|---|
| **Tasks and containers** | | |
| A task has a CPU quota and may still read the host's cores | accepted: `getconf` and `sysconf` see the host; the quota throttles, and pids and memory remain bounded | [entry](#a-task-has-a-cpu-quota-and-may-still-read-the-hosts-cores) |
| A task is the container whose id Porelo recorded | never by name or label; a container with no record is acted on by nothing | [entry](#a-task-is-the-container-whose-id-porelo-recorded) |
| A start at a terminal goes into a running task | as `attach` does; an option of creation is refused by name; a script still gets 65 | [entry](#a-start-at-a-terminal-goes-into-a-running-task) |
| A host playbook reads Porelo as a contract | `vault status` by exit code, `--json` answers; the machine file holds a host's keys, and the host installs every agent and starts no task | [entry](#a-host-playbook-reads-porelo-as-a-contract) |
| Waiting for a person is never inferred from silence | working and idle are observed from output; waiting only where something declares it; no status channel out of the container | [entry](#waiting-for-a-person-is-never-inferred-from-silence) |
| Nothing comes back by itself after a reboot | only `start` brings a task back; no surprise restarts, no vault prompt at boot | [entry](#nothing-comes-back-by-itself-after-a-reboot) |
| Only the last session is recorded, and a failed continuation is forgotten | a failed continuation is forgotten and the next start is fresh (not yet built) | [entry](#only-the-last-session-is-recorded-and-a-failed-continuation-is-forgotten) |
| A session id is an identifier, not a credential | it may go on a command line; it authorizes nothing | [entry](#a-session-id-is-an-identifier-not-a-credential) |
| An image's readiness is judged against the project file alone | computed from the project file's image fields and Porelo's base recipe, without an agent's layers; a task with another agent rebuilds only those layers | [entry](#an-images-readiness-is-judged-against-the-project-file-alone) |
| A file handed to a task goes to /porelo/files, and only in | a root-owned directory of its own in every task; a base image with `/porelo` is refused; nothing comes out | [entry](#a-file-handed-to-a-task-goes-to-porelofiles-and-only-in) |
| A task learns of handed-in files from Porelo, not from its prompt | one guide to all of Porelo in the task, and a line at the prompt when a file arrives | [entry](#a-task-learns-of-handed-in-files-from-porelo-not-from-its-prompt) |
| Every class pushes through its gate | no key, socket or route to the upstream in any container; an online task's gate passes its own branch on at once | [entry](#every-class-pushes-through-its-gate) |
| A build is read from the host, never by the task | an online task is handed its builds' verdicts and logs; no forge token, route or verb in it | [entry](#a-build-is-read-from-the-host-never-by-the-task) |
| Build readers are packages behind a versioned API | `porelo-build-api` and `org.fuin.porelo.Build2`; shipped as packages from `porelo-adapters`; nothing in `porelo` names a forge | [entry](#build-readers-are-packages-behind-a-versioned-api) |
| A stop of every task keeps its tokens | `task stop --all` and `panic` stop as `task stop` does; revoking is `vault lock` or `vault remove`, a step of its own | [entry](#a-stop-of-every-task-keeps-its-tokens) |
| Nested containers never in a guarded project | `guarded` never sets them; only on a task's own kernel where the assessment says so | [entry](#nested-containers-never-in-a-guarded-project) |
| A devcontainer file's features are not fetched | its image or Dockerfile is used, each feature named as not installed; nothing runs at build that the project file does not name | [entry](#a-devcontainer-files-features-are-not-fetched) |
| A repository's image comes from porelo.yml, taken whole, and its build keeps the host's network | superseded for what wins: a repository's file and the override now add to the project's image key by key, as porelo-project's decision "A repository of its own stays in Default and says what it needs" sets; `image` only, and the build's network, still hold | [entry](#a-repositorys-image-comes-from-poreloyml-taken-whole-and-its-build-keeps-the-hosts-network) |
| A first start takes no project.yml without a yes | a person's yes to the text at a terminal; this machine's own copy of it is what starts; same upstream and same hash is the same project; no comparison to an adapter; only on the `default` path | [entry](#a-first-start-takes-no-projectyml-without-a-yes) |
| A project's own file is judged by its newest change alone | accepted: an admin's signature on the newest change to `project.yml` is what counts, an older one is refused, and a file it names needs none, so a snippet file gets around `egress` while the image is built | [entry](#a-projects-own-file-is-judged-by-its-newest-change-alone) |
| Several attempts share one credential | not split or queued; a reached limit said per attempt; five attempts by default | [entry](#several-attempts-share-one-credential) |
| A reviewer task reviews only its own project | another project's waiting work is never read | [entry](#a-reviewer-task-reviews-only-its-own-project) |
| A time limit does not cut off a person who is watching | a start may shorten the limit, never lengthen it; an attached person is warned and the stop waits for their detach (not yet built) | [entry](#a-time-limit-does-not-cut-off-a-person-who-is-watching) |
| What carries an agent's words is kept for a period | `task.log` and a session's recording cut after 30 days, even while the task lives; a setting | [entry](#what-carries-an-agents-words-is-kept-for-a-period) |
| A kernel of its own for a task is measured before it is decided | adopted only on measured answers: it works, the ruleset still fails closed, its cost | [entry](#a-kernel-of-its-own-for-a-task-is-measured-before-it-is-decided) |
| A view of a task is guarded by Porelo's token, not by VNC's password | switched on per project, only through Porelo's relay, a strong one-time token before a byte reaches VNC | [entry](#a-view-of-a-task-is-guarded-by-porelos-token-not-by-vncs-password) |
| A run left to itself comes back from a restart stopped | its session kept and shown as interrupted; only a task somebody drove comes back with `--restarted` | [entry](#a-run-left-to-itself-comes-back-from-a-restart-stopped) |
| A task's terminal is tmux, and a person's own settings come after Porelo's | `$XDG_CONFIG_HOME/porelo/tmux.conf` read after Porelo's; `history-limit` and `default-terminal` set again after it | [entry](#a-tasks-terminal-is-tmux-and-a-persons-own-settings-come-after-porelos) |
| **The machine and the daemon** | | |
| Declaring a host service opens the host's loopback 443 | accepted: every task of a project naming a host service reaches whatever listens there; one port, by signed declaration, including offline | [entry](#declaring-a-host-service-opens-the-hosts-loopback-443) |
| A weaker machine is reported, not refused | `DEGRADED` exits zero and starts tasks; only `MISSING` fails | [entry](#a-weaker-machine-is-reported-not-refused) |
| Doctor diagnoses and never repairs | each check names the next action; nothing runs a fix as root | [entry](#doctor-diagnoses-and-never-repairs) |
| Owner-only by one umask, not by each writer | every program sets `077` first; what another uid reads says its own mode | [entry](#owner-only-by-one-umask-not-by-each-writer) |
| The daemon restarts itself after an update; the package does not | it sees its binary replaced and asks the account's systemd; tasks keep running | [entry](#the-daemon-restarts-itself-after-an-update-the-package-does-not) |
| Porelo offers what it would tell a person to type | one `Offer` helper: the command's own code, the precondition checked again, a destructive step defaults to no, nothing asked without a terminal | [entry](#porelo-offers-what-it-would-tell-a-person-to-type) |
| Account-wide settings live in one file | `$XDG_CONFIG_HOME/porelo/config.yml`; missing means every default, an unknown key refused by name | [entry](#account-wide-settings-live-in-one-file) |
| Unused access is reported, and locked only on request | a device slot unused for 45 days named by `doctor`; locking is a setting, off by default | [entry](#unused-access-is-reported-and-locked-only-on-request) |
| The event log is anchored in the user's journal and outlives a clearing | each line's hash in the user's journal, sent off the machine only by a setting; `porelo clear` keeps it | [entry](#the-event-log-is-anchored-in-the-users-journal-and-outlives-a-clearing) |
| The daemon listens on no network port; another computer reaches it through ssh | `ssh -L` onto its socket or `ssh HOST porelo daemon connect`; a forward that breaks is repaired, not routed around | [entry](#the-daemon-listens-on-no-network-port-another-computer-reaches-it-through-ssh) |
| An upgrade reloads a loaded SELinux module, and doctor names an older one | the rpm reloads `porelo_socket` where it is loaded; `doctor` asks the kernel what the loaded one grants | [entry](#an-upgrade-reloads-a-loaded-selinux-module-and-doctor-names-an-older-one) |
| The daemon serves on platform threads, and a hang is read from its thread dump | connections and long-lived passes on platform threads; `SIGQUIT` dumps them to the journal; virtual threads kept for measuring | [entry](#the-daemon-serves-on-platform-threads-and-a-hang-is-read-from-its-thread-dump) |
| A daemon at rest runs a loop only for what can be waited on | no loop for an exited task or one without an account; under 5 % of one core with exited tasks; about 1 % per project with a transport is left | [entry](#a-daemon-at-rest-runs-a-loop-only-for-what-can-be-waited-on) |
| **Egress, the shield and the review** | | |
| Port 53 to the upstream is the resolver's alone | matched by the resolver's uid 0 in the namespace; the agent's direct query is dropped and logged | [entry](#port-53-to-the-upstream-is-the-resolvers-alone) |
| Porelo does not claim to detect a prompt injection | it bounds what a convinced agent is worth; each guard says control or accident-catcher | [entry](#porelo-does-not-claim-to-detect-a-prompt-injection) |
| The provider channel is documented, not bounded and not alerted on | an open path by construction; stated, recorded, no alert | [entry](#the-provider-channel-is-documented-not-bounded-and-not-alerted-on) |
| What is dangerous by kind is a fixed list that ships with Porelo | never in the repository it judges, never per project | [entry](#what-is-dangerous-by-kind-is-a-fixed-list-that-ships-with-porelo) |
| The instruction is shown, never compared | the review shows what was asked and matches no file against it | [entry](#the-instruction-is-shown-never-compared) |
| An MCP server reaches a task only over HTTP, through the gateway on the host | no stdio server on the host; one in the container is named as not covered; only new or changed tools held (not yet built) | [entry](#an-mcp-server-reaches-a-task-only-over-http-through-the-gateway-on-the-host) |
| An alert either only tells or stops the task; it never freezes it | per project: tell a person, the default, or also stop the task and revoke its tokens | [entry](#an-alert-either-only-tells-or-stops-the-task-it-never-freezes-it) |
| A false secret finding is acknowledged on the host, never in the repository | an owner-only list beside the project, each entry lapsing when its text changes; the tree is scanned, not its history | [entry](#a-false-secret-finding-is-acknowledged-on-the-host-never-in-the-repository) |
| A new dependency with a finding waits for a person | the approve asks for each, bound to the reviewed commit, and without a terminal is refused; Maven Central and npm first | [entry](#a-new-dependency-with-a-finding-waits-for-a-person) |
| An approve lands as a merge carrying provenance; a fast-forward is a project's choice | models, agent and provider from the broker, never the agent; a fast-forward gives up the provenance (not yet built) | [entry](#an-approve-lands-as-a-merge-carrying-provenance-a-fast-forward-is-a-projects-choice) |
| The owner approves alone unless a project asks for a second person | four eyes is a project setting; the approve then needs another signer's signature | [entry](#the-owner-approves-alone-unless-a-project-asks-for-a-second-person) |
| Work is reviewed on the machine, never on an upstream branch | in `guarded`, waiting work is read on the machine (`gate review`, `gate checkout`); `porelo approve` brings a checkout's task back to its checkout, `porelo gate approve` forwards approved work and opens a pull request on the host's forge; `online` sends work to the forge before review | [entry](#work-is-reviewed-on-the-machine-never-on-an-upstream-branch) |
| No editor is attached to a task's container | no editor vendor's hosts declared, no editor server copied in; `gate checkout` is the way to look at the work | [entry](#no-editor-is-attached-to-a-tasks-container) |
| The pre-push guard catches an accident, not the owner of the machine | four ways round it and a spoofable author are accepted; `--no-verify` is the named way past | [entry](#the-pre-push-guard-catches-an-accident-not-the-owner-of-the-machine) |
| A clearance question waits 60 seconds, and an expired one can still be answered | the silence refuses and is recorded; `Decide` answers it later; `shield watch --timeout` waits longer | [entry](#a-clearance-question-waits-60-seconds-and-an-expired-one-can-still-be-answered) |
| **The vault and credentials** | | |
| A provider is a declaration; an agent is a package | providers are YAML data paired with agents by dialect; the vault is keyed by provider | [entry](#a-provider-is-a-declaration-an-agent-is-a-package) |
| A service that is not a model provider is a destination | its own kind and file, not a widened provider; a provider resolves as one too | [entry](#a-service-that-is-not-a-model-provider-is-a-destination) |
| The project declares credentials, and the run adds | a run adds but never withdraws or redirects; never in an agent's definition | [entry](#the-project-declares-credentials-and-the-run-adds) |
| The route is picked by the token | one broker, a route per credential; the token, not the path, decides where a key goes | [entry](#the-route-is-picked-by-the-token) |
| Where a key goes is declared, never inferred | `auth_header` or `auth_query` per service; only a consumer whose endpoint can be configured; no TLS interception | [entry](#where-a-key-goes-is-declared-never-inferred) |
| A subscription's own backend is served as a provider | accepted: `chatgpt` reaches an undocumented route that can change; a request carries the account beside the token, from the stored sign-in | [entry](#a-subscriptions-own-backend-is-served-as-a-provider) |
| A Google sign-in is served from the sign-in's own backend | accepted: `google-sign-in` reaches an undocumented route, and Google's terms call third-party use of the sign-in a breach that may suspend the account; the client's secret is read from the installed CLI, never published | [entry](#a-google-sign-in-is-served-from-the-sign-ins-own-backend) |
| A sign-in passes through plaintext on its way to the vault | accepted: the login container's layer and the host's copy may reach disk or swap, for the moment of a login; tasks stay phantom-only | [entry](#a-sign-in-passes-through-plaintext-on-its-way-to-the-vault) |
| One credential type reaches one variable | `token_env` maps a type to one variable; changed in the reader when an agent needs two | [entry](#one-credential-type-reaches-one-variable) |
| A bought token is per task and kept in memory | never on disk, never shared; costs one exchange per task per hour | [entry](#a-bought-token-is-per-task-and-kept-in-memory) |
| A purchase that fails at start is a missing key | unattended and agent runs are refused before a container exists; a shell warns | [entry](#a-purchase-that-fails-at-start-is-a-missing-key) |
| An authorization a person grants is kept in the vault and spent by the broker | granted once on the host (`oauth-device` or `oauth-code`), registered once where offered, spent per task in memory, recorded in `grants.log`, and revoked at the service before removal | [entry](#an-authorization-a-person-grants-is-kept-in-the-vault-and-spent-by-the-broker) |
| Porelo does not replace a stored key at its provider | no `vault rotate`; per-provider code is what the split between agents and providers avoids | [entry](#porelo-does-not-replace-a-stored-key-at-its-provider) |
| A task's own tokens are kept in the vault | gate and phantom token under `task/<container>/`; a start after a reboot needs the vault | [entry](#a-tasks-own-tokens-are-kept-in-the-vault) |
| Clearing the vault is `clear`, not `delete` | `vault clear` lists, then acts on `--yes`, like the other clears; `remove` is for one entry | [entry](#clearing-the-vault-is-clear-not-delete) |
| A vault `PORELO_VAULT` names clears only itself | its clear leaves the account's transport state and own homeserver, and lists nothing for them | [entry](#a-vault-porelo_vault-names-clears-only-itself) |
| A cached passphrase is reported gone only when it is gone | `vault lock` answers cleared, nothing cached or unknown, and exits non-zero on unknown | [entry](#a-cached-passphrase-is-reported-gone-only-when-it-is-gone) |
| The passphrase cache does not outlive a login, and a foreign keyring reads as empty | one mechanism, the kernel keyring; another user namespace would read it as empty | [entry](#the-passphrase-cache-does-not-outlive-a-login-and-a-foreign-keyring-reads-as-empty) |
| Secrets in the daemon's memory stay strings | accepted: whoever reads that memory reads the vault file and the keyring too | [entry](#secrets-in-the-daemons-memory-stay-strings) |
| Doctor reports the daemon's hardening, not its own | read from the owner of the running daemon's `/proc/<pid>/environ` | [entry](#doctor-reports-the-daemons-hardening-not-its-own) |
| A task's spending is capped in tokens, and in money where a price is known | tokens for every provider, the streamed count asked for; money from the reported cost, else the dated list price; no price refuses the money ceiling, never $0; under a subscription it caps list-price value (not yet built) | [entry](#a-tasks-spending-is-capped-in-tokens-and-in-money-where-a-price-is-known) |
| A credential is entered only in a terminal | no daemon method takes a provider credential or passphrase; only a device's keyslot share crosses the socket | [entry](#a-credential-is-entered-only-in-a-terminal) |
| A credential with no expiry is stored and named | accepted, never refused; the store says so and the list marks it (not yet built) | [entry](#a-credential-with-no-expiry-is-stored-and-named) |
| A storage class Porelo cannot prove is shown as user-scoped | stronger only by a slot the node enrolled or evidence it checks; a client's claim is recorded and shown as user-scoped (not yet built) | [entry](#a-storage-class-porelo-cannot-prove-is-shown-as-user-scoped) |
| An organization is judged path by path, and a first card by the forge's public key list | each path by the signers its rule names, never the commit's signer alone; a first card trusted from `<forge>/<login>.keys`, not the forge's API; written by plain signed Git | [entry](#an-organization-is-judged-path-by-path-and-a-first-card-by-the-forges-public-key-list) |
| The organization adapter is an executable of its own | asked across a process boundary through `organization/api`; trusting it is the user's choice, and Porelo vouches for its Git implementation | [entry](#the-organization-adapter-is-an-executable-of-its-own) |
| A new key goes straight into KeePassXC, and a running KeePassXC is locked to serve it | accepted: no key file and no master password in Porelo; every database the running KeePassXC has open is locked, and the others stay locked until the person unlocks them | [entry](#a-new-key-goes-straight-into-keepassxc-and-a-running-keepassxc-is-locked-to-serve-it) |
| An organization's trust record is checked, not hardened against whoever writes the state directory | accepted: a record that fails a check is discarded and the registry walked again from its founding commit; a writer of `$XDG_STATE_HOME` can change Porelo's config and binaries too | [entry](#an-organizations-trust-record-is-checked-not-hardened-against-whoever-writes-the-state-directory) |
| A backup key replaces only the card of the person who filed it | it replaces that login's key and nothing else, revoking the old one; an owner's backup signs `owners.yml` and the new key the card; nobody may name a backup for someone else | [entry](#a-backup-key-replaces-only-the-card-of-the-person-who-filed-it) |
| An owner handing on a key changes the owners first | two commits in one push: the old key signs `owners.yml` with the new key, the new key signs the card; no rule lets a non-owner's key change `owners.yml` | [entry](#an-owner-handing-on-a-key-changes-the-owners-first) |
| An owner does not move a host's pin | superseded for the organization admin: an owner not listed moves no pin, and a pin that stands follows only a chain the old key signed, a backup that key filed, or an organization admin | [entry](#an-owner-does-not-move-a-hosts-pin) |
| An organization has four roles, each answering for the one below | owner, organization admin, machine admin, project admin; none does another's work unless listed for it too | [entry](#an-organization-has-four-roles-each-answering-for-the-one-below) |
| The roles start with their list | a reader walks the whole history, so what was signed before the list stays judged by the rules from before; absent, owners do the organization admin's work, and `org create` lists the founder | [entry](#the-roles-start-with-their-list) |
| A host's pin follows the organization admins, and root stays with its machine admins | a dismissed admin stays replaceable with no step of theirs, at the cost that one stolen key of that role reaches every server's pin, visibly | [entry](#a-hosts-pin-follows-the-organization-admins-and-root-stays-with-its-machine-admins) |
| Withdrawing a card revokes its key | the key signs nothing more and cannot file the card again; the only owner's card cannot be withdrawn | [entry](#withdrawing-a-card-revokes-its-key) |
| A founder's key that left the forge refuses the organization on a new machine | a new machine answers `refused-state` naming the key; a machine that read it while listed goes on; recovery is the key back on the forge, or a new organization | [entry](#a-founders-key-that-left-the-forge-refuses-the-organization-on-a-new-machine) |
| A project's lock does not bind an organization owner | accepted: an owner may name any admin, and an admin may unlock, each the organization admin's once the roles stand; every step is a signed commit | [entry](#a-projects-lock-does-not-bind-an-organization-owner) |
| Every organization store passes one set of checks | `organization/contract` holds what any store must guarantee, and each adapter runs it against itself through a fixture; `organization/git` keeps its own offline answer and does not run them | [entry](#every-organization-store-passes-one-set-of-checks) |
| A past commit is judged by the whole chain of its key, read to the tip | a key handed on keeps its commits on a new machine; a lost key's stay refused, and an owner adds the project's admins again | [entry](#a-past-commit-is-judged-by-the-whole-chain-of-its-key-read-to-the-tip) |
| **Projects and following** | | |
| One agent works in one repository | a task holds one repository, so an agent that spans several is one per repository, its commits authored by the name it goes by | [entry](#one-agent-works-in-one-repository) |
| A host carries a project whole or by some of its repositories | a repository is on one host at most, and a project carried whole keeps its repositories off every other host; each read key is deleted by whoever made it | [entry](#a-host-carries-a-project-whole-or-by-some-of-its-repositories) |
| A project file describes constraints, not instructions | no prompt or standing instruction belongs in `project.yml` | [entry](#a-project-file-describes-constraints-not-instructions) |
| A project is named, never pointed at | every command and call takes a project name; no path form exists | [entry](#a-project-is-named-never-pointed-at) |
| A signature counts when it arrives, never when it says it was made | commits judged at arrival by the gate and the forge's pre-receive hook, messages when the receiver takes them; a date the signer writes decides nothing | [entry](#a-signature-counts-when-it-arrives-never-when-it-says-it-was-made) |
| A host believes the forge about a member's first card | within `org fetch` it asks the forge's public key list, anonymously, and judges as a person's machine does; a forge with no such list leaves that person first-seen, so their commit waits | [entry](#a-host-believes-the-forge-about-a-members-first-card) |
| A single admin keeps an offline backup key | for a host's one admin and an organization's one owner; it may only replace the key it backs, signs nothing else and certifies no task | [entry](#a-single-admin-keeps-an-offline-backup-key) |
| Clearing names what only a person's credentials can remove, and never attempts it | keys made by earlier versions and `machine-signers` lines are named, not removed at the forge; git needs no administration right; the vault stays | [entry](#clearing-names-what-only-a-persons-credentials-can-remove-and-never-attempts-it) |
| A digest pins an image; a tag-only image in a project needs a person's consent once | a tag-only project waits for one consent, then the digest is kept and a change said at every start (not yet built) | [entry](#a-digest-pins-an-image-a-tag-only-image-in-a-project-needs-a-persons-consent-once) |
| What a build runs beside its secrets is fixed by its content | every action by commit with its version, Maven and GraalVM by checksum, Artifactory spoken to with curl; `check-actions` refuses a tag | [entry](#what-a-build-runs-beside-its-secrets-is-fixed-by-its-content) |
| An offline project never connects, and its agent still reaches its provider | no upstream or external egress, and once started no fetch; a start of one the organization names reads it first; declared `host_services` reach the host on TCP 443, and the agent's model calls go through the broker as in every class | [entry](#an-offline-project-never-connects-and-its-agent-still-reaches-its-provider) |
| **Messages** | | |
| A task holds no key and sees no address | one mailbox in the container; messages and commits signed on the host by the task's own certified key, never a login key | [entry](#a-task-holds-no-key-and-sees-no-address) |
| No model decides what a message may contain | rules accept or refuse, a person holds; a classifier may only ever hold | [entry](#no-model-decides-what-a-message-may-contain) |
| A project's message rules are set once, in its project file | by class (project, room, others), defaults allow, allow, deny; the host keeps only holding a peer | [entry](#a-projects-message-rules-are-set-once-in-its-project-file) |
| A task's message reaches the task it names | exact container, short task or Agent display name in this project here; people and unknown names reach no task; persons still see every message in the room | [entry](#a-tasks-message-reaches-the-task-it-names) |
| A person's words in the room reach every agent, unfiltered | what a joined person writes in the room reaches every task of the project, and in a direct chat that one task, without the content filter; an answer goes where it was asked | [entry](#a-persons-words-in-the-room-reach-every-agent-unfiltered) |
| The mailbox guide is the same for every task of one Porelo version | built from enforced constants only; whom a task reaches is in `agent-card.json` | [entry](#the-mailbox-guide-is-the-same-for-every-task-of-one-porelo-version) |
| Each host keeps its own record; a shared ordered record is given up | hash-chained per host; no shared ordered record across machines | [entry](#each-host-keeps-its-own-record-a-shared-ordered-record-is-given-up) |
| Refused originals live as long as the task | owner-only, no timer; the exposure is accepted while tasks are short-lived | [entry](#refused-originals-live-as-long-as-the-task) |
| A project's conversation is one room, and the room is the boundary | tasks that must not read each other belong in two projects | [entry](#a-projects-conversation-is-one-room-and-the-room-is-the-boundary) |
| Membership is not authorization; the host acts and the signature decides | the host acts for every task; peer delivery needs a key of that project's peer, while a joined person's account delivers as `ROLE_USER` | [entry](#membership-is-not-authorization-the-host-acts-and-the-signature-decides) |
| One account per task, deactivated with it | made at start, deactivated at removal; the signature, not the account, carries authorship | [entry](#one-account-per-task-deactivated-with-it) |
| Porelo knows transports; the transport knows Matrix | a generic lifecycle; no Matrix code or settings parsing in `porelo` | [entry](#porelo-knows-transports-the-transport-knows-matrix) |
| The homeserver is one endpoint Porelo names, and offline means loopback | the declared URL or the account's own; offline reaches loopback only | [entry](#the-homeserver-is-one-endpoint-porelo-names-and-offline-means-loopback) |
| A person joins by an account made for them | the login is printed once, no registration stays open, and the recorded account's words reach the project's tasks as `ROLE_USER` | [entry](#a-person-joins-by-an-account-made-for-them) |
| A machine on a shared server is admitted by a person | a person lets it in, from their client or with `talk admit` on the serving machine; a machine not yet let in starts no task | [entry](#a-machine-on-a-shared-server-is-admitted-by-a-person) |
| An admitted machine's token may lie in a file | accepted: `talk admit --into` writes it in the clear to a new owner-only file; the interface keeps it in its secrets file instead | [entry](#an-admitted-machines-token-may-lie-in-a-file) |
| A homeserver's registration token lies on the host that needs it | accepted, a deliberate exception to no token on a server: from the person's vault or asked once, kept in the host's vault | [entry](#a-homeservers-registration-token-lies-on-the-host-that-needs-it) |
| A socket's registration token is lent under the socket's path | `<entry>.unix:<path>`, derived and named by nobody; accepted: a path proves nothing about who listens | [entry](#a-sockets-registration-token-is-lent-under-the-sockets-path) |
| The keys of a project's store lie on the host for its life | accepted, a deliberate exception to no token on a server: root-only until `down`, limited to the store's bucket | [entry](#the-keys-of-a-projects-store-lie-on-the-host-for-its-life) |
| The signed project file names a shared server | homeserver, room, CA and token file come from the project file every machine follows; a machine sets none of them | [entry](#the-signed-project-file-names-a-shared-server) |
| Only a transport a peer names is polled | a transport no peer names is never polled; a failure is said once | [entry](#only-a-transport-a-peer-names-is-polled) |
| **The build and packages** | | |
| Porelo is split into modules by area, not into repositories | `app-<area>` modules with enforced dependencies; one build, one package, one contract on the wire | [entry](#porelo-is-split-into-modules-by-area-not-into-repositories) |
| The build tooling lives in its own repository; the acceptance kit stays | `porelo-buildtools` from Central at `porelo.buildtools.version`; the kit changes with the product | [entry](#the-build-tooling-lives-in-its-own-repository-the-acceptance-kit-stays) |
| Which machine types a leg rents | snapshots at the smallest disk; `cpx42` first for speed, three fallbacks; nothing enforces it | [entry](#which-machine-types-a-leg-rents) |
| A developer's build labels itself | `chcon` after each build where SELinux is on; no build path in the shipped policy (not yet built) | [entry](#a-developers-build-labels-itself) |
| A release waits for the misuse suite, and a found hole is never skipped | every misuse scenario ran and passed; a red one holds the release and is never tagged or filtered out | [entry](#a-release-waits-for-the-misuse-suite-and-a-found-hole-is-never-skipped) |
| Every Porelo repository gets the changelog check from its parent | bound once in `porelo-parent`; a repository not built with Maven runs it as a workflow step | [entry](#every-porelo-repository-gets-the-changelog-check-from-its-parent) |
| A leg runs only the unit tests that need its machine | unit tests on the build host, except those only the target can run, which fail it when skipped | [entry](#a-leg-runs-only-the-unit-tests-that-need-its-machine) |
| One key signs everything a machine installs from Porelo | packages, setup script and repository metadata with one key held by the owner; the publishing repositories switch on together (not yet built) | [entry](#one-key-signs-everything-a-machine-installs-from-porelo) |
| A new name starts afresh | nothing of the name before is read, kept or migrated; a machine is set up afresh; a released changelog entry keeps the name it was released under | [entry](#a-new-name-starts-afresh) |
| An agent is found by its description, and by Porelo's file name beside it | `agents.d/<name>.yaml` names any executable; `porelo-agent-*` is still read, so released agent packages keep working | [entry](#an-agent-is-found-by-its-description-and-by-porelos-file-name-beside-it) |
| A bound in the acceptance kit is about four times what was measured | the ready bound and an unattended run's N at most 120 s; a bound above the cap fails by name | [entry](#a-bound-in-the-acceptance-kit-is-about-four-times-what-was-measured) |
| **The documentation** | | |
| The issue index stays one table per section | a conflict there is resolved by keeping both lines; split, nobody could read it whole | [entry](#the-issue-index-stays-one-table-per-section) |
| The data protection page follows the federal ISDS concept template | in the template's chapters, each saying what Porelo provides and what the organisation regulates | [entry](#the-data-protection-page-follows-the-federal-isds-concept-template) |

## A host playbook reads Porelo as a contract

A playbook that puts a project on a server calls only Porelo's commands and never parses text meant for a person.
`vault status` answers by its exit code, 0 open, 3 shut, 4 none;
`agents` answers with `--json`; `task stop --all` answers by its exit code, 0 when nothing runs
any more, 70 otherwise. **Which keys go into a host's vault are the person's**, in their machine file on their drive, not
in `project.yml`, which is the project's. A key leaves only by its name and its path. **Bringing a host up and down is
kept apart from starting tasks**, since the person decides which work runs: the first play takes `porelo-setup.sh` from
the `porelo` package (`/usr/share/porelo/setup/porelo-setup.sh`, which keeps the channel the installed package came
from) after adding the repository by its pinned key, the machine file names no task and no agent, `up` installs every
agent the setup script's catalogue offers and starts nothing, the person starts each task by hand, and `down` stops
every task with `task stop --all` before `vault clear`.
A vault on a host nobody sits at is opened from outside: a person at `ssh -t`, or a program writing the passphrase to
`vault unlock --passphrase-stdin`. The contract is `doc/hosts.md`. **What would change it:** a second machine file of
another shape, which would bring a version of its own.

## A start at a terminal goes into a running task

`porelo task start` on a task that is running, typed by a person at a terminal, goes into its session through the code
`porelo task attach` runs: after a reboot the container is back with its agent's session gone, and someone who typed
`start` wants to work in it, not to be sent to a second command. An option that takes effect only when a task is
created or brought up (`--prompt`, `--model`, `--agent`, `--attach`, `--detach`, `--rm` and their kind) is refused
with 64, naming each, since it would be dropped without a word. Without a terminal, or with `--no-input`, a start
answers "is already running" and 65 as before, so a script never ends in a session; a playbook takes that as "already
so". `attach` stays the verb that never creates anything. **What would change it:** a start over the daemon that
should go in too, which has no terminal to go into.

## A task has a CPU quota and may still read the host's cores

A task gets a CPU quota of 4 by default, alongside 8 GB of memory and 2048 processes. The project's `limits.cpus`
replaces it, and `none` removes it. A fixed CPU count keeps worker pools sized for the fixed memory and process
bounds on any host. `OMP_NUM_THREADS` makes the limit, rounded down to at least one, visible to `nproc`,
unless the image or project supplies its own value. Image builds pass the same hint as an `OMP_NUM_THREADS` build
argument to their `RUN` steps, after the packages layer, covering the snippet and agent layers without persisting it
in the image's environment. CPU affinity on the build process does not reliably reach its `RUN` containers.
**The exposure:** `getconf` and `sysconf` still read the host's
cores. A tool sizing itself by those two runs more threads than the limit and is throttled by the quota, never
stopped by it; its pids and memory are still bounded. **Why it stays:** no task is pinned with `taskset` on every
`start` and `exec`. Each would be bound to named cores, sharing them badly between tasks, and every `exec` would need
the same pinning. **What would change the answer:** a host that delegates the `cpuset` controller to the account, or
a pids or memory limit that such a tool reaches before the quota slows it.

## A task is the container whose id Porelo recorded

Porelo records the id podman returns at create, in the task's state directory, before the container first starts, and
treats a container as a task only while its live id is that one. Every act on a task - and every context the CLI or
the daemon builds - goes by that id, and the OCI hooks compare the runtime's id with the record beside the sidecar
before giving a container a resolver and rules, or reaping helpers after it stops. Names and podman labels do not
decide it, since anything that runs as the account can give a container both: a container named like a task could
otherwise have tokens restored, the vault relay and the gate started in its namespace, its guide written, or another
task's helpers reaped. A container with no record is not a task, and nothing is adopted by name, since an adoption by
name is exactly the hole the record closes.

## A file handed to a task goes to /porelo/files, and only in

A file handed to a running task appears whole in `/porelo/files`, a directory that holds nothing else in every task
from its start, owned by root and readable by the agent, which cannot change or remove it. Not `/workspace`, where
the agent would commit it and the gate carry it out; not `/run/porelo/`, which is Porelo's machinery and holds the
credential socket. `/porelo` is Porelo's root: a base image that already has it is refused when the task's image is
built - which every preparation and every start of a changed image does - because a mixed directory would make
"everything in it was handed in" quietly false. The same holds in every security class, since it carries nothing
across a boundary that keeps tasks apart. Nothing takes a file out of a task: content leaving is what the gate is
for. Every hand-in is written down - who, when, which run, the name, the size and the sha256 - in the state
directory, so it outlives the task, and never the content, which is often the thing kept out of git on purpose.
The record says what Porelo did: a file copied in with `podman cp` is not in it.

## A task learns of handed-in files from Porelo, not from its prompt

Left to the person's prompt, an agent pushes and never looks at its red build unless somebody remembers to say so.
Agent definitions declare both ways a running agent is told something - `instructions`, the file it takes into its
standing instructions, and `at_rest`, the screen at which a line may be typed - and files, builds and the mailbox use
the same two:

- **One guide to all of Porelo in the task**, `/run/porelo/guide/README.md`: `/porelo/files` and what arrives there,
  the builds of a push among it, and the mailbox's text where the task has one - one file, since a definition has
  one `{file}`. Its shared beginning is the same for every task of one Porelo version, as the mailbox's guide is,
  with any values the task was started with (`parameters:`) appended after it, so a provider's prefix cache of the
  shared guide holds. It is mounted from a directory of its own made with the task: a task made before has no such
  mount, and keeps its mailbox's guide.
- **A line at the prompt** when a file arrives, a verdict among them, and the agent rests there: Porelo's own,
  naming the files, typed under the same lock, the same quiet and the same `waiting` rule as a message's, and tried
  at every pass until it lands.

`/porelo/files` is still the same path in every task, so a prompt may still name it. **What would change the
answer:** an agent that takes its instructions some other way than a file, or tells a person's question from its
rest by nothing on its screen.


## Every class pushes through its gate

An `online` task once cloned from its upstream and pushed there itself, through an ssh-agent socket that answered
the forge with the real key. The key never left the vault, but for as long as the task ran the agent could use it for
everything the key may do: push to `main`, push with force, set tags, delete branches, and with a person's key reach
every other repository it opens. Holding a key and being able to use it unchecked differ little. So every class works
against the gate on the host, and the container holds no key, no socket of one and no route to the upstream. The
classes differ in what the gate does with a push: `guarded` keeps it until a person approves it, `online` passes the
task's own branch on at once, as `porelo/<task>` or the name the project's branch scheme makes, with the key the
host lends, and fails the agent's push when the
forge refuses. Anything but that branch is refused at the gate; a push with force to it goes through, since an agent
that rebases needs it. Before the agent fetches, the gate fetches from the upstream, at most once every few seconds,
so an agent fetching in a loop cannot make the host flood the forge with a person's key. A gate that reads the
upstream from the host can also follow a repository on the same machine, which no container could reach.

## A build is read from the host, never by the task

An `online` task learns what the build of its own push did without reaching the forge: a helper on the host follows
every commit it pushes, asks the forge with a token from the vault, and hands the verdict and the jobs' logs into
`/porelo/files`. The task asks for nothing and holds nothing it could ask with. A route through the broker with a
phantom token would have let the agent use the forge's own tools, but **a forge token cannot be scoped to the
question**: one that reads a repository's builds reads its code and issues too, and an agent that holds a verb uses
it - opening an issue, closing a pull request - with none of it passing the gate. So the surface is a verdict and
logs, and nothing else. It is `online` only: in `guarded` the forge builds nothing before a person approves at the
gate, and waiting for that is waiting for a person, not a build. A log is text an outsider can influence, as is
everything a task reads; delivering it adds no new kind of input, and Porelo keeps no copy of it - only which builds it
delivered, with each log's size and hash. **The exposure:** one token's rate limit is shared by every task that reads
with it; the helper waits a limit out and says so in the verdict rather than reading as a failed build.

## Build readers are packages behind a versioned API

What a forge does differently in kind - GitHub sends a job's log behind a redirect to storage, another forge as an
archive or a trace per step - is code, and that code is not Porelo's. A forge is read by a **build reader**: an
executable built on the published `porelo-build-api`, speaking `org.fuin.porelo.Build2` over a socket with a protocol
version Porelo checks, installed under `/usr/libexec/porelo/builds/<forge>` and found at run time, as an agent is. They
live in `porelo-adapters`, each shipped as a package of its own - `porelo-adapter-github` and `porelo-adapter-forgejo` -
and nothing in `porelo` names a forge; the stub reader the tests use is the only one in this tree. The contract fixes
what every reader hands over, whatever its forge:
the verdict across a commit's runs, at most 50 jobs once it is final, and a job's log as plain text, its last 64 KiB,
with at most 2 MiB of logs in all. Its `build` operation accepts `none` for logs: the verdict and jobs are returned
without downloading logs. An incompatible installed reader is refused by name, with the build interface it
serves and the interface Porelo requires.
**The exposure:** a reader runs on the host with the forge token in each call; it is a package the operator installs,
and trusted as one.
## Waiting for a person is never inferred from silence

A task's activity is observed from outside where it can be, and declared where it cannot. Working and idle are read
from the output the host already writes for every task (the agent's log grows continuously while it works), with no
knowledge of any agent. Waiting for a person is said only by whatever is asking, such as a clearance prompt the
machine itself raised, or read from the agent's screen against rules the agent's package declares; where an agent
declares none, the answer is "this agent cannot tell us", never "not waiting". A timeout is not used: a quiet task
can be finished, stuck or rate-limited, and guessing "waiting" is wrong in the direction that costs most. No channel
from inside the container carries a status to the host either: that would be a way out beside the vault socket and
the gate, written into by the agent, and a status value does not justify it. An agent that
stops at a first-run dialog inside a task is treated as a defect in that agent's package, caught by each agent
repository's acceptance step that the agent reaches work without being asked anything.

## Nothing comes back by itself after a reboot

A task that a reboot took down comes back only through `start` - at the terminal or over the socket - never on its
own; `task list` shows that the machine took it down. On a shared machine nothing restarts by surprise, and nobody is
prompted for the vault at boot with nobody there to answer.

## Only the last session is recorded, and a failed continuation is forgotten

A task records only the session its next start continues. A run asked to continue a session that failed and named no
session of its own has the recorded one forgotten, so the next start is fresh and says so; nothing runs twice without
being asked. `remove`, including `remove --rescue`, forgets it: the transcript was inside a container that no longer
exists. **Not yet built:** a failed continuation that emits no session id does not yet forget the recorded session; only
task removal does.

## A session id is an identifier, not a credential

The id of an agent's session may be passed on a command line. It names a transcript on this machine and authorizes
nothing; what it leaks - that a task exists and roughly when it started - the container name leaks already. It is
recorded here so the rule keeping secrets off the command line is not re-argued against it.

## An image's readiness is judged against the project file alone

Whether a project's task image is ready (`ABSENT`, `READY`, `STALE` or `UNKNOWN`) is computed from a digest of the
project file's image-relevant fields and Porelo's base `Containerfile` recipe (`ImageLayers.none()`), without any
agent's layers, carried as a label on the image. **The exposure:** the image also contains the agent's layers, so an
image is really per project and agent, and `READY` overstates slightly when the next task names a different agent
than the image was built for. **Why it stays:** that task rebuilds the agent's layers itself, which is correct and
fast, so nothing runs with the wrong tooling. **What would change the answer:** an interface needing to say "ready,
built for claude", which would add the agent to the image's labels and to the comparison.

## A weaker machine is reported, not refused

`porelo doctor` reports a machine that works less well than it should as `DEGRADED`, names what it costs and exits
zero, and nothing refuses to start a task on it. **The exposure:** on a machine using slirp4netns rather than pasta,
a task's gate binds every interface and is reachable from this machine's network; only the per-task token keeps it
shut. **Why it stays:** the machine does run tasks, and refusing it is a policy decision rather than something a
probe can settle. **What would change the answer:** a project or an operator needing to say "not without pasta",
which would make it a refusal at `task start` declared by the project or the machine.

## Doctor diagnoses and never repairs

Neither `porelo doctor` nor the daemon's `Doctor` offers to run a fix. Installing packages or writing under `/etc` is
root on the node, which is exactly the power Porelo is built around not having. A repair over the daemon's socket
could not reach the case most worth fixing anyway: a machine that is not ready usually has no daemon to ask. Every
failed check names the next action instead, for a person to take.

## Owner-only by one umask, not by each writer

Every program of Porelo sets its umask to `077` first, so its files, git's and tar's are this account's alone. A mode at
each of two hundred places that write is a list the next writer is missing from, and it would not reach what git writes
in a working tree. **The cost:** what another uid must read says its own mode where it is written: a task's mounts, and
an agent's tree staged for a build, since a `COPY` keeps the mode it finds. podman sets its own `0022` inside a build's
`RUN` and a container, measured under `umask 077`. A file Porelo or a child writes elsewhere in a person's directories,
with `--into` for one, is owner-only too; sharing is a copy a person makes.

## The daemon restarts itself after an update; the package does not

The package starts nothing when it installs: a daemon is each account's own, and a root package script starting
another account's user service would be wrong about the session it runs in. So the daemon watches its own
executable; seen replaced twice (an update replaces the binary, then the unit file a moment later), it asks the
account's own systemd for `daemon-reload` and `restart --no-block` of the unit it runs in, read from its own control
group. Running tasks are not touched. A binary removed with nothing in its place is an uninstall, which nothing
restarts into, and a daemon started by hand is not restarted but says once that it was replaced; `porelo doctor`
names a daemon running another version than the command.

## Declaring a host service opens the host's loopback 443

**Accepted exposure:** whatever listens on the host's loopback TCP 443 is reachable by every task of a project
that names a service in `egress.host_services`, including an offline task. DNS answers each declared name with the
gate's address, and the firewall opens that address and port beside the gate. The firewall sees an address and a
port, not which service a TLS request names: a task that can reach one can ask for another on that listener.
Under pasta the address maps to loopback; otherwise it is the gate's probed fallback address and the service must
listen there too. No declaration adds no host-service rule; other ports keep their existing drops and logging.

**Why it stays:** the host's forge and registry are the reason the key exists. One port, opened by a declaration
in the signed project file, is a bounded grant a project can review. The names are answered locally, never
forwarded upstream or added to the ordinary egress allow set, which would also open port 80. TLS passes through
unchanged, with its declared name and the task's existing certificate trust.

**What would change the answer:** a second service on loopback 443 that should not be reachable by those tasks,
or SNI routing that could tell services apart and enforce a grant for each. Either calls for reviewing the
shared-port exposure rather than assuming a DNS name isolates a service.

## Port 53 to the upstream is the resolver's alone

The resolver runs inside the task's network namespace, so its own upstream queries pass the same output chain as the
agent's traffic. The two port-53 rules to the upstream resolvers therefore match the socket's owner as well as the
destination (`meta skuid 0`): the resolver runs as the namespace's root, the agent as its own user, with no
capabilities and no new privileges. Matching the destination alone would let every process in the task ask a public
resolver directly, carrying data out in query names with no drop and no log line, and confirming whether an
undeclared host exists. Other ways were weighed and not taken: matching the resolver's cgroup (where a hook-started
resolver lands under rootless podman is not known), pinning its source port below 1024 (covers UDP only), and moving
the upstream query out of the namespace (the largest change, and the resolver would no longer be self-contained in
the task). **The exposure:** a process that runs as the namespace's root - a command an operator runs with
`podman exec --user root` - can still ask the upstream directly, and a name under a declared domain is still
forwarded upstream, so a declared domain whose authoritative server the agent controls can carry data in the names
asked; the resolver logs those. **What would change the answer:** anything the agent can reach running as the
namespace's root.

## Porelo does not claim to detect a prompt injection

No guard in Porelo is presented as detecting an agent that was convinced by something it read: there is no filter
that separates instructions from content in a text meant for something that follows instructions, and a confident
answer that is sometimes wrong is worse than an honest "this system does not do that". What Porelo bounds instead is
what a convinced agent is worth, and every guard says whether it is a control or an accident-catcher.

## The provider channel is documented, not bounded and not alerted on

Every task has a working route to its model provider, carrying request bodies up to the broker's limit, and anything
the agent knows can be written into a prompt. No egress set closes it, because closing it closes the task. It is
stated as an open path in the operator documentation and the reach document, with the record of what a task sent.
There is no alert: a false alarm on a working task is worse than none. **What would change the answer:** a way to
bound what a request says rather than whom it goes to.

## What is dangerous by kind is a fixed list that ships with Porelo

The review's list of files dangerous by kind - CI definitions, build scripts, dependency manifests and lockfiles,
what runs on checkout, a file made executable, a symbolic link - is Porelo's and fixed. A list inside the repository
could be edited by the very change it is meant to catch; a list per project outside it is one more file nobody
maintains. **Not yet built:** changing the target of an existing symbolic link is not yet ranked as dangerous.

## The instruction is shown, never compared

The review shows what the task was asked at the top - "none" when it was started without one, "not known" when the
task is gone - and matches nothing against it. A signal present half the time, and a guess when it is, would be
leaned on wrongly.

## A provider is a declaration; an agent is a package

Knowledge is split by owner. How a session starts, what a fresh container must be told, which command line runs a
prompt and how its output is read belong to the **agent**, which is code and ships as a package. The upstream
endpoint, the auth header and prefix, the dialects served and how a credential is obtained and renewed belong to the
**provider**, because they are the same for every agent reaching it; a provider is therefore a YAML file found by a
directory scan, with nothing to execute and no package of its own. Whether an endpoint can be redirected belongs to
both: the provider must offer it and the agent must honor it, so an agent declares the shape of endpoint it can use
(a socket or a URL) and Porelo satisfies it. Agents and providers are paired by dialect, never by name. The vault is
keyed by provider, since a credential belongs to whoever the account is with; storing it per agent would keep the
same key twice under names that do not say what it is for.

## A service that is not a model provider is a destination

A forge, a search endpoint or an MCP server is a `destination` - its own kind and its own file, saying where the
service is and where its key goes - not a widened provider definition. A provider is which models it serves and in
which wire format; a service has no wire format in that sense, and a field meaningless for half its instances is one
nobody can trust anywhere. A provider resolves as a destination too and keeps its own file, so nothing that reads
providers changes.

## The project declares credentials, and the run adds

`project.yml` names the credentials every task of the project gets; a repeatable `--credential` on the run adds
more, and cannot withdraw or redirect one the project declared. That a project's credential is usable by anyone who
can start a task in it is stated in the documentation rather than left to be discovered. The agent's definition
never names one: a credential belongs to the project, not to the agent.

## The route is picked by the token

One broker serves every credential of a task, with a route per credential. Each credential gets its own task-scoped
token, and the token - never the request's path - decides which credential is attached and which destination the
request goes to, so a key held for one service cannot be sent to another at all. In the container each appears as
`PORELO_TOKEN_<NAME>` and `PORELO_URL_<NAME>`, beside the single provider's variables, so a task with one credential
changes nothing.

## Where a key goes is declared, never inferred

A stored key is presented where its service expects it, and that place is the service's fact: a header whose name
the provider or destination declares (`auth_header`, `auth_prefix`), or a query parameter (`auth_query`). Porelo
guesses neither, since a wrong header name fails like a wrong key and is the first thing such integrations get
wrong. A consumer is supported only when its endpoint can be configured, so it can be pointed at the proxy: a tool
with its host compiled in is not, because reaching it would mean intercepting TLS with a certificate authority in the
image, which Porelo does not do. A key carried in the request body is not rewritten.

## A subscription's own backend is served as a provider

The provider `chatgpt` serves a person's ChatGPT sign-in at `chatgpt.com/backend-api/codex`, the subscription's own
backend, not a documented API. Each request carries the sign-in's account as `ChatGPT-Account-Id` beside its token;
the provider declares it under `credential_headers`, the sign-in keeps it as a setting, and the broker sets it and
drops whatever the container sent under that name. **The exposure:** the route can change without notice, and
whether the provider's terms allow it was not checked. **Why it stays:** the agent that reaches it is mostly used with
that subscription, and the tokens stay on the host as every other sign-in's do. **What would change the answer:** a
change of the route, or a refusal by the provider.

## A sign-in passes through plaintext on its way to the vault

A sign-in made with `vault login` is written by the agent to its own files in a throwaway login container, copied
out into an owner-only temporary directory of the host, read there by the agent's extractor, stored in the vault
encrypted, and the copy deleted at once. Only the vault keeps it. **The exposure:** for the moment of a login the
plaintext lies in the container's writable layer and in the host's copy, and both may reach disk or swap. **Why it
stays:** it is the one way every agent's login takes, it lasts only that moment, and no task ever holds the real
sign-in; a task gets a phantom token. **What narrows it, not yet built:** the login's directory a `tmpfs` of the
container and the copy in `$XDG_RUNTIME_DIR`, both in memory, though a `tmpfs` can still be swapped out. **What would
change the answer:** an agent whose sign-in cannot be written to a file at all, or a machine where that moment is not
acceptable.

## A Google sign-in is served from the sign-in's own backend

The provider `google-sign-in` serves a person's Google sign-in at `daily-cloudcode-pa.googleapis.com`, the backend
Antigravity CLI's signed-in path reaches, not a documented API. It is renewed on the host with the client id and the
client secret of Antigravity's own installed-app client, which are read from the installed CLI at sign-in and kept
only in the vault, the secret as a hidden entry beside the refresh token. **The exposure:** the route can change
without notice; Google's terms call third-party access with Antigravity's sign-in a breach that may suspend the
account; and the client's secret is another vendor's credential. **Why it stays:** the sign-in is how the person's
subscription is used, the tokens stay on the host as every other sign-in's do, and the client's secret is never
written into a source, a setting, a log or a post. **What would change the answer:** a change of the route, a
refusal or a suspension by Google, or a documented API for the subscription.

## One credential type reaches one variable

An agent definition's `token_env` maps a credential type to a single variable, so an agent that reads the same token
from two variables cannot be described. **Why it stays:** no supported agent needs it. **What would change the
answer:** an agent that does; the fix then goes in the reader of `token_env`, not in each definition.

## A bought token is per task and kept in memory

A token the broker buys with a stored secret (`client_credentials`) is bought per task, held in memory, and never
written to disk or shared between tasks. A live token resting outside the vault, and every task depending on one
cache, cost more than one exchange per task per hour. **What would change the answer:** a service whose rate limit
makes per-task exchanges fail.

## A purchase that fails at start is a missing key

The launch tries the purchase once before anything exists. An unattended or agent run is refused, as for a
credential the vault does not hold; a shell task warns and starts, since a person is there to fix it.

## An authorization a person grants is kept in the vault and spent by the broker

A service where a task acts as a person (`oauth-device` or `oauth-code`) is authorized once on the host with
`porelo vault authorize` or the daemon's `Authorize` stream, never inside a container. Where the entry names only
`resource_url` and the service offers dynamic client registration, Porelo reads its metadata and registers a public
client once under the vault's lock, keeping the endpoints in the entry and the registration token hidden as
`registration/<name>`. The refresh token that comes back is kept hidden as `grant/<name>` with who granted it and
when; the broker buys each access token per task in memory and writes a rotated refresh token back to the vault.
When a service issues an access token with no expiry and no refresh token, that token is kept and attached as it is;
one with a stated expiry and no refresh token is refused at setup, since it would end mid-task. A start without a
grant (`AUTHORIZATION_NEEDED`) refuses an unattended or agent run and warns a shell without spending the grant, and
the daemon raises `Authorizations()` on its own stream once per credential until a grant lands. Whom a task acted as
is recorded with the task and in `$XDG_STATE_HOME/porelo/grants.log`, which outlives the task. `vault remove`
revokes the grant and deletes any client registration at the service before removing the entry, and keeps the entry
unless `--without-revoking` is given when the service cannot be told. Every OAuth endpoint must be `https`, or plain
`http` on loopback only.

## Porelo does not replace a stored key at its provider

There is no `vault rotate` that issues a new key at a provider and revokes the old one. For a bought credential
every exchange already rotates the token the broker attaches; issuing and revoking a stored key through each
provider's own API would be per-provider code in Porelo, which the split between agents and providers exists to
avoid. **What would change the answer:** a provider-neutral way to issue and revoke keys.

## A task's own tokens are kept in the vault

A task's gate token and its phantom provider token are kept in the vault, hidden under `task/<container>/`:
`vault list`, credential choices and provider listings leave them out, they go when the task is removed, and entries
of tasks that no longer exist are pruned whenever the vault is next opened for a task. The cost is that the first
start after a reboot needs the vault unlocked even for a task with no credential of its own.

## Clearing the vault is `clear`, not `delete`

The vault is removed by `porelo vault clear`, in the same shape as `porelo clear` and `porelo project clear`. Without
`--yes`, all three list what would go and remove nothing; at a terminal `porelo clear` offers to stop running tasks and
then to clear what it listed, while `vault clear` and `project clear` ask to be run again with `--yes`. `remove` stays
the verb for one entry (`vault remove NAME`). A fourth verb for the same act would be one more to learn for nothing it
says differently.

## A vault `PORELO_VAULT` names clears only itself

`PORELO_VAULT` exists so that a run, an acceptance run above all, does not read or write the account's own vault. Its
`vault clear` removes what that vault holds and the accounts a project made elsewhere with its tokens. It leaves what
the transports keep for the whole account, and the account's own homeserver, because those belong to the account's
own vault, which still holds their tokens: clearing them left that vault with accounts on a server that was gone.
Nothing is listed for the part it leaves. A `not removed` line would make the clear exit `1` for doing what it should,
and a status of its own would widen what the daemon answers.

## A cached passphrase is reported gone only when it is gone

Clearing the cached vault passphrase has three outcomes, not two: cleared, nothing cached, and unknown. Only "no such
key", "expired" and "revoked" count as absence, since a retry cannot change any of them and there is nothing for an
operator to act on; every other keyring failure, and a key that was found and could not be unlinked, is unknown, and
`vault lock` then exits non-zero so a script does not believe it locked anything. The read path keeps one verdict
for every failure: it falls through to asking for the passphrase and says nothing, because a cache that could not
answer costs nothing when the answer is being asked for anyway. The two paths share a search and not a verdict,
because on the forget path a failure rendered as absence is a false security claim.

## The passphrase cache does not outlive a login, and a foreign keyring reads as empty

The passphrase is cached in one place, the kernel keyring, with a prompt behind it; there is no chain of further
stores (a credentials service, a passphrase command). **The exposure:** a deployment that needs the cache to outlive
a login has no answer. And the kernel user keyring is per user namespace, so a process in a different one finds an
empty keyring and reports "nothing cached" while the passphrase is still cached in the operator's: the false
statement above, by a route the three outcomes do not catch. **Why it stays:** every reader is the CLI or the daemon
running as the operator, in the operator's namespace; the hooks do not touch the vault, and the shipped
`porelod.service` does not change the namespace. **What would change the answer:** a unit file with
`PrivateUsers=yes`, or a reader under a container runtime, which would need the passphrase carried by a path rather
than the keyring; or a deployment that needs the cache across logins, which would make a chain of stores worth its
explanation.

## Secrets in the daemon's memory stay strings

A credential read from the vault is a `String` in the daemon and the broker: `VaultEntry.value()`, every caller that
reads it, and the HTTP header the broker sends. It is not turned into bytes that could be wiped. The vault document
and the passphrase are the exceptions, held in buffers that are overwritten when the work is done: the vault's
master key is cleared in a `finally` at each open, also when an enrollment, a revocation or a change is refused, and
the passphrase cached in the kernel keyring is encoded and decoded without ever becoming a `String`. **The exposure:**
a `String` cannot be cleared; it lives until the collector reaches it, and a heap dump or a core dump shows it. Even
bytes would not be erased for certain, since a moving collector leaves the old copy behind. **Why it stays:** reading
another process's memory takes the same uid that already reads the vault file and the passphrase in the kernel
keyring, so wiping the copies in memory keeps nobody out who is not in already. A core dump is the copy that is
written somewhere without anybody asking; core dumps of the processes that hold a secret are turned off with
`prctl(PR_SET_DUMPABLE, 0)` (`ProcessHardening.disableDumping`), which the daemon, the broker and the ssh agent call
before they read a secret; the relay carries bytes only. **What would change the answer:** a process that holds secrets running under a uid of its own, so that
reading its memory is no longer the same as reading the vault; a core dump that cannot be turned off; or an HTTP
client that takes a header as bytes, which would make the change much cheaper than the thirty-odd callers it touches
today.

## Doctor reports the daemon's hardening, not its own

`porelo doctor` says whether the running daemon can be core-dumped, since that process holds the vault's secrets. It
used to print the flags of its own process, which holds none and which nothing hardens, so `dumpable 1` read as a
finding about nothing. The flag is read from outside: the kernel gives the entries under a non-dumpable process's
`/proc/<pid>` to root, and leaves the directory itself to the user, so the owner of `/proc/<pid>/environ` is what
counts. Reading the directory said "dumpable" of a daemon that was not, which the VM showed and the unit test, built
on the same wrong picture, did not.

## A project file describes constraints, not instructions

Everything in `project.yml` bounds what work in the project may do; nothing in it tells an agent what to do. A prompt
or a standing instruction is the opposite kind of thing: standing instructions live in the repository the agent works
on and are not Porelo's business.

## A project is named, never pointed at

Every command and every daemon call that takes a project takes its name, as `porelo project list` prints it, and no
command accepts a path to a project file, not even beside the name: two ways to say which project is how one
project's file comes to live in several places (a working directory, a configuration directory, the followed clone),
of which only one is verified. A name lets an interface that cannot see the machine's filesystem drive a task from
what `Projects()` told it, and the daemon and the CLI refuse the same input with the same words because one resolver
answers both. An unknown name is refused with the names the machine has; on `CanStart` it is an answer
(`NO_PROJECT_FILE`), not an error, since asking about a project that turns out not to exist is a fair question. Where
a running task already names its project (`shield egress --task`, `talk pass`, `talk hold`), the project is taken
from the task rather than asked for again.

## A signature counts when it arrives, never when it says it was made

A task's commit is judged when it arrives: at its first accepted push to the host's forge, by the pre-receive hook, or
for a task without a host forge at the gate's own receipt. A message is judged when the receiver takes it, by the
receiver's clock. The key or certificate must hold then. The time a commit or message names is written by whoever
signs it, so a key that has ended or was retired could otherwise date new work into the time it was valid. **The
cost:** a commit signed while its certificate held but pushed after it ended is refused; a running task's
certificate is renewed with `porelo task certify` before it ends.

## The organization adapter is an executable of its own

The organization adapter is an executable of its own on the machine where `porelo` runs, asked across a process boundary
through `organization/api`. **The exposure:** whoever installs an adapter trusts it with whom Porelo believes: its answers
decide who may sign, which keys hold and what a project's settings are. **Why it stays:** how an organization is kept is
the user's choice, and Porelo cannot check an adapter it did not write; it vouches for its Git implementation, which
passes `organization/contract`. **What would change the answer:** a way for Porelo to check an adapter's answers itself.

## A host believes the forge about a member's first card

An organization's `members.yml` pins no fingerprint, so a first card needs somebody to vouch for it. Without that, anyone who can
write to the organization's repository could file a first card under the login of a listed member who has none, and a host
would admit that key for that login. So within `porelo org fetch` the host asks the forge's public key list, anonymously, about
first-seen and refused cards, and judges by the same rules as a person's machine. The founder cannot be asked anonymously and is
taken from the commits under the host's pin, whose signature covers their parents. A forge with no such list leaves that person
first-seen, and their commit waits.

**The exposure:** whoever controls a login's account on the forge decides that login's first key, on a host as on a person's
machine, so the forge's account is trusted on the host too. **Why it stays:** pins in `members.yml` would change the layout and
every `project member add`, and would ask for a person's key at the moment they are added; first card wins would admit a stranger
under a listed login; and a person's machine already believes the forge on this. **What would change the answer:** an
organization that must not trust its forge's accounts. GitHub's anonymous limit of 60 calls an hour is kept low since only those
cards are asked about.

## A single admin keeps an offline backup key

A host with one host admin and an organization with one owner each keep a second key, made once as a file kept
offline, by `porelo identity backup-key`, and never put in the daily agent: a single key that is lost leaves nobody who may hand
the place on. The backup key is held to the one change that recovers: a commit it
signs may change only the entry it backs, and itself. It signs no other change and certifies no task.
`org` and `identity show` warn while one is missing. **The exposure:** whoever holds the
backup key can replace the key it backs and so take that place over, as with the main key; kept offline, it is
harder to reach.

## Clearing names what only a person's credentials can remove, and never attempts it

`porelo clear` and `porelo project clear` remove everything this machine holds for a project or the account, in one
confirmed step. Porelo makes no deploy keys (`DeployKey` in `Tasks1` is retired and answers `DeployKeyRefused`); a
deploy key made by an earlier version is still forgotten here and named once for a person to
remove at the forge. Clearing removes nothing at the forge: that needs the forge's administration right, which the
person's token for git does not need. This machine's line in a `machine-signers` an earlier version had a project list
is named too, though nothing reads that file any more; removing it needs the person's signed commit. The vault, the person's keys and the installed packages stay: clearing is not
uninstalling.

## A task holds no key and sees no address

Inside the container there is one mailbox directory and nothing else for messages: no socket, no credential, no
network. A recipient is a peer name; what it resolves to is on the host. Every message, like every commit, is signed
on the host with the task's own key, which Porelo makes for the task, holds outside the container and destroys with
the task, because a key a task can reach is a key it can copy. The signature says the installation vouched for that
task, not that the agent typed it. A person certifies the key for that one task through their own agent, so a
receiver traces it to who let the task act. It is not a person's login key: one lets a person connect, the other
says what one task may vouch for, and ending the task must not touch the person's key.

## No model decides what a message may contain

The message sluice is rules in a tool of its own, with no model: it accepts or refuses, and a person holds. A
classifier may be added and may then only hold a message, never release one. A hosted model would be a place every
conversation leaks to, and one reading every project's messages a bridge between them.

## A project's message rules are set once, in its project file

How closely messages are watched is the project's, set once in `project.yml` under `mail.rules` by class of peer -
the project's own tasks, its conversation (the room and its people), every other peer - and a peer may name its own
mode. Defaults: `project: allow`, `room: allow`, `others: deny`. A message the filter passes goes at once; only what
the filter flags waits for a person (a refusal in blocking mode, "would have refused it" in reporting mode). A mode
decided per developer and per peer on each host would make the same project behave differently on every machine and
ask a question nobody can answer well. The host keeps only the brake: holding a peer's messages, per project and
peer, where no task can change it. A mode set on the host is not read; `talk hold --mode` and `Moderate` with a mode
are refused and say where a mode is set. Kinds of message in the rules and a brake per task are not wanted.

## A task's message reaches the task it names

A task's message arriving through the project's conversation is handed only to the tasks its `metadata.to` names
on this machine, in this project. Matching is exact on the container name, the short task name or the
`Agent <Name>` display name. A message to `people`, to a person by name or to a name that identifies no task here
reaches no task here, without an answer to the sender. The persons still see every message in the room.
A task is never handed its own post back. A person's own words in the room keep their separate rule below.

## A person's words in the room reach every agent, unfiltered

Communication with a project's agents is through messaging. A person's words in the project's conversation reach
every task of the project on the machine; their `metadata.to` says whom they are meant for. A room
message that names only a helper account - the room's administrator or its relay, marked `"helpers": true` by the
transport - reaches no task and wakes none; the relay answers the person once in the room instead. A
person's messages are not filtered - only what the agents send is - because the filter guards what leaves a task, and
a person writing to their own agents is not that. They are taken only from somebody who joined the project's
conversation, arrive as a `ROLE_USER` message from that person, and are counted and recorded like any arrival. A
direct chat with one agent goes to that task alone. An answer goes where it was asked: `metadata.via` (`room` or
`direct`) is copied into the answer.

## The mailbox guide is the same for every task of one Porelo version

The text that tells an agent how its mailbox works (`/run/porelo/mail/README.md`) is built only from the constants the
host and the filter enforce, never from the task, its project or its peers. It ends the agent's system prompt, and a
provider caches a prompt by its beginning, so a text that varied per task would cost every task its cache. What does
vary - whom the task can reach - is a separate file, `/run/porelo/mail/agent-card.json`, names only and never an
address, rewritten at every pass, which the guide says to read before writing.

## Each host keeps its own record; a shared ordered record is given up

What happened to each message is a hash-chained log per host, tamper-evident locally and verifiable without a
network. A record shared and ordered across machines is given up: a room does not hash its parent, so each host's log
proves what it saw and nothing to anybody else. The head hash can be published later without a redesign. **What
would change the answer:** somebody other than the two peers needing to verify what passed and in which order.

## Refused originals live as long as the task

A message the filter refused is kept, readable only by the account running Porelo, for as long as the task whose
mailbox it belongs to, with no timer. **The exposure:** that directory holds secrets because the filter worked, so a
long-lived task accumulates every refused secret behind file permissions and nothing else - the same protection its
workspace has. **Why it stays:** one lifetime for everything a task owns is simpler to reason about. **What would
change the answer:** tasks living for months, or more refused messages than anybody can read.

## A project's conversation is one room, and the room is the boundary

A transport that keeps a conversation gives each project one: for Matrix one room, where every task of the project
posts and the people of the project are too. The room is the confidentiality boundary; two tasks that must not read
each other's messages belong in two projects. A project without such a transport is standalone, and its tasks do not
message each other; there is no built-in transport.

## Membership is not authorization; the host acts and the signature decides

No task touches the homeserver: its account's token stays in the host's vault and only the host-side transport posts
and polls for it, and nothing in the room triggers a host action (starting or stopping a task, changing configuration,
or approving work happen only at the host's command line or Varlink socket). The host joins a task's account to its own
project's room and no other. For messages between tasks and peers, what a task receives is decided by the SSH signature
on the message: by a task's own key, whose certificate, looked up by the key's fingerprint, one of the project's
certifiers issued and which is valid when the message arrives, never by room membership, and anything else is held and the operator told; Matrix peers are `external`, so their inbound content
goes through the filter. What a person writes from an account made for them by `porelo talk join` carries no SSH
signature and is delivered to the project's tasks as `ROLE_USER` because that account is recorded as a joined person on
the host.

## One account per task, deactivated with it

Each task gets an account of its own when it starts, made with a random password that is thrown away, and only its
access token lives in the vault until the task is removed; then the account is deactivated. Authorship is carried by
the signature, not by the account. On the account's own homeserver the provisioning account registers first and so is
its administrator; on a central one, which has no administrator Porelo holds, each machine acts only on the accounts it
made and keeps each task's password beside its token.

## Porelo knows transports; the transport knows Matrix

No Matrix client, room alias, registration flow, admin command or homeserver unit is in `porelo`. Porelo knows
transports, conversations, identities and opaque secrets, through a generic lifecycle (`setup`, `enroll`, `retire`,
`join`, `settings`) any transport may offer, so a second transport adds nothing to `porelo`. Project settings under
`mail.transports.<scheme>` are passed to the transport verbatim and never read by Porelo.

## The homeserver is one endpoint Porelo names, and offline means loopback

The homeserver is the project's declared URL, or the account's own on loopback: a user unit on a port Porelo picks and
keeps in the account's state, with no root, so two users never share a server's administrator. The host's egress for
a project's messages is what the transport's `setup` reports in `reaches`, and nothing else. An `offline` project's
conversation may reach nothing but loopback: Porelo passes `--loopback-only`, so the transport refuses before it
contacts anything else, and checks `reaches` after every `setup`, before it keeps anything the transport handed back.

## A person joins by an account made for them

`porelo talk join <project> <person>` (`JoinMessages`) has the provisioning account make a transport account for that
person, let it into the project's room and direct chats, record the account on the host, and return the login once
without storing the password; the person uses any Matrix client, and no registration stays open. What that account
writes in the room or a direct chat is delivered to the project's tasks as `ROLE_USER` without an SSH signature or
content filtering, while host actions (starting, stopping, configuring, or approving) remain at the host's command line
and Varlink socket.

## A machine on a shared server is admitted by a person

On a central homeserver a person makes the room, names it in the settings and lets each machine's account in from
their own client. Where the room is on one machine's own server, a person there runs `porelo talk admit` for another
machine, which hands that machine what it needs, and `porelo talk dismiss` to take it out; Porelo never admits a
machine on its own. Until a machine is let in, it starts no task of the project and says whom to let in and where.
Each machine holds its own provisioning account, so one can be revoked alone.

## An admitted machine's token may lie in a file

`porelo talk admit --into FILE` writes the admitted machine's registration token in the clear to a new file readable
by its owner only, for a person without an interface; the interface takes it from the daemon's `AdmitMachine`
straight into its secrets file and never writes it out. **The exposure:** the file holds a token that lets anybody on
the network path register accounts on this machine's server until it is dismissed. **Why it stays:** it is how a
person without an interface carries it to the other machine, like the VPN key on a host's disk; `talk dismiss` takes
it back at once. **What would change it:** an admission that never leaves the two machines' own stores.

## A homeserver's registration token lies on the host that needs it

A project whose homeserver is elsewhere needs that server's registration token on the host, or a task cannot enroll. It
comes along with `porelo host unlock`, from the person's vault or asked once, and lies in the host's vault. Decided on
2026-11-04, not built yet. **The exposure:** whoever opens that vault on the host can register accounts on that homeserver
until the token changes. **Why it stays:** a deliberate exception to no token on a server, since a task enrolls with nobody
there to ask. **What would change it:** a homeserver that admits a host without a token.

## A socket's registration token is lent under the socket's path

A host reaches a homeserver elsewhere over a tunnel bound to one socket, and that homeserver registers only with a token,
so a `unix:` homeserver must be lent one. Its entry is `<entry>.unix:<path>`, derived from the address as a server's is
from its host, so one server's token never reaches another. Not the room's server, which is a claim in the file and says
nothing of where the socket leads, and not a name in the project file, which would let the file choose the token.
**The exposure:** `https` proves the server and a path proves nothing, so whoever can bind that path receives the token.
**Why it stays:** the person stores it for that path alone, and the tunnel's socket lies where only they can bind. **What
would change it:** a transport that can tell who listens before it sends the token.

## The keys of a project's store lie on the host for its life

A host copies what a project keeps into the project's store by root's timer, each entry run as its account by `runuser`.
The store's keys lie in `/etc/porelo-host/store/` at `0600` until `down` removes them. Decided on 2026-11-04, not built yet.
**The exposure:** root on the host can read, write and delete what the store holds. **Why it stays:** a deliberate
exception to no token on a server, since the hourly copy and the last one at `down` run with nobody there, and the key is
limited to the store's bucket. **What would change it:** a copy that needs no key on the host.

## The signed project file names a shared server

A project's conversation on a shared server is named in its signed `project.yml`: the homeserver, the room and the CA's
text as `ca_certificate`, and every machine that starts the project reaches that name. A machine sets none of it for itself.

## Only a transport a peer names is polled

A daemon polls a transport only if a peer of one of the account's projects names its scheme, and says a
misconfiguration (77, 78) once per cause, not every cycle.

## Porelo is split into modules by area, not into repositories

So that more than one agent can work in `porelo` at once, `app` is modules by area (`app-<area>`), each registering its
own daemon methods, describing them in its own part of the one contract `org.fuin.porelo.Tasks1`, and adding file
locations in its own paths class; the modules' dependencies are the boundaries, so a reference the wrong way does not
compile. `porelo` stays one build, one package, one push: it is one process, one socket contract and one installation,
and one repository per command group would only move the bottleneck into ordering pushes. The areas keep one Java
package across jars, so no access had to be widened and no class renamed; a sub-package per area is a later step if
it ever pays. The contract stays one interface on the wire, written in parts. **The exposure:** `app-base` and
`app-model` are about a third of the code and still shared, so two agents still meet there; a change there is said
first, which makes the meeting visible rather than making it go away.

## The build tooling lives in its own repository; the acceptance kit stays

`porelo-machines`, the release tool and the FFM, CPU and package checks live in `porelo-buildtools` on their own
version line, and `porelo` takes them from Central at one property, `porelo.buildtools.version`. They are a separate
world with a command line as their contract. The acceptance kit stays in `porelo`, because its steps change with the
product: moved out, most product features would need a kit release first. The cost: a change that needs both is two
pushes in order, the tooling first.

## Which machine types a leg rents

A leg's snapshot is taken at the smallest disk on offer (40 GB), so every type in `Spec.DEFAULT_TYPES` can boot it
and the availability fallback can fire: types sell out within minutes of each other, and a list of one would stop
every leg on a single shortage. A full leg peaks at about 10% of that disk. The list is `cpx42`, `cx43`, `cpx32`,
`cx33`: `cpx42` first because the CI runner blocks while the rented machine builds, so a slower machine costs CI
minutes, the scarce resource. `cx23` boots the image and passes, and is left out because two cores stretch a leg to
about 25 minutes. **The exposure:** nothing enforces the small build; a `--type` written into a snapshot build, or
into one of the workflows that rent a machine, for speed would read in review as a performance fix and silently
shrink the fallback to one type. **What would change the answer:** a guard on those call sites, or a measurement of
`cx43`'s build time (same cores as `cpx42` at about a quarter of the price), which would make it a candidate for
first place as a change about money.

## The issue index stays one table per section

`issues/base/README.md` is not split per area. Two agents adding a requirement each meet in one line of it, and that
conflict is resolved by keeping both lines; splitting the index would trade that for a document nobody can read as a
whole.

## A stop of every task keeps its tokens

`porelo task stop --all` (and `porelo panic`) stops every task as `task stop` stops one, and so keeps each task's tokens in
the vault: a stopped task is brought back with its work, and a resume needs them. Ending what a token grants is a step
of its own - `porelo vault lock`, and `porelo vault remove` of an entry, which revokes at the service where it can. Folding
revocation into the stop would make the one command reached for in doubt destroy what a calm look afterwards would
keep.

## Porelo offers what it would tell a person to type

Where something is missing and a refusal would name the command that makes it good, Porelo asks at a terminal and does
it, through one helper (`Offer`), alike everywhere:

1. **The remedy is the command's own code,** run as that command or called as its function - never a copy of it.
2. **The precondition is checked again afterwards,** never taken on trust; a remedy that did not help ends in the
   refusal it replaced.
3. **A destructive step** - discarding work, deleting, leaving an account at a service, changing a person's own file -
   names what it touches first, defaults to no, and is never taken by `--yes`.
4. **Without a terminal, or with `--no-input`,** nothing is asked and the refusal is word for word as before; a script,
   the daemon and the helpers see no change.
5. **Several gaps at once** are asked one after another, and the command goes on once at the end.

What stays a message: where nobody can answer, a review or trust decision that must not be made in passing, and a plain
report.

## A credential is entered only in a terminal

No method of the daemon's contract takes a provider credential or the vault passphrase: a credential reaches the vault
by `vault put`, `vault login` or an import on the node - typed or pasted into a terminal at the node without echo, or
piped on standard input (`vault put`, `vault unlock --passphrase-stdin`). Only a device's 32-byte keyslot share
(`EnrollDevice`, `UnlockWithShare`) crosses the Varlink socket, because that share is generated and kept by the
client's keystore to wrap the master key and is never a credential a task or a provider sees. A contract that never
carries a credential is a property anyone can check by reading the interface description; a method that carried one
would make it a property every later log line, error and streamed reply has to keep. A forced-command ssh account
cannot open such a terminal and is not served: on such a machine an administrator stores the credential through another
account.

## A credential with no expiry is stored and named

A credential that carries no end is accepted, never refused: some providers issue nothing else. The store says it has
no expiry and the list of credentials marks it, because a long-lived key is the one secret whose leak does not decay,
and the person who could replace it with a shorter-lived one should see that every time they look. **Not yet built:**
storing and listing a credential do not yet say or mark that it has no expiry.

## A developer's build labels itself

On an SELinux machine only binaries labelled `porelo_exec_t` may bind Porelo's sockets. A binary from a build tree is
labelled by the build itself, with `chcon`, after each build, where SELinux is enabled and the policy loaded. A file
context for a build path would name in the shipped policy a path only one person has. **Not yet built:** the SELinux
policy still allows `unconfined_t` to bind Porelo's sockets without a `porelo_exec_t` type, and the build does not yet
run `chcon`.

## An MCP server reaches a task only over HTTP, through the gateway on the host

1. The gateway can attach a server's key, read its tool lists and calls and journal them only where the traffic crosses
   the host; a server over stdio has no such crossing.
2. A server over stdio is never started on the host, where it would run outside the task's firewall, limits and
   namespace with the person's rights. Where a project has one at all, it runs inside the task's container as the
   agent's own, and is named as not covered.
3. When a server's tool list changes, only the new or changed tools are held until a person accepts them; the unchanged
   ones stay usable. Holding the whole server would stop a task over a change it may never use and teach people to
   accept without reading.

**Not yet built:** the broker forwards MCP HTTP traffic without yet inspecting or journaling tool lists and calls or
holding new or changed tools for approval.

## Nested containers never in a guarded project

A container started inside a task opens user namespaces and more of the kernel to the agent. A `guarded` project
promises the least is opened, so it may never set it; and where the assessment finds the guarantees hold only on a
task's own kernel, nested containers are built only there.

## A devcontainer file's features are not fetched

A feature is a script from a registry, run as root during the image build and chosen by whoever edits the repository's
devcontainer file. Porelo takes the file's image, or builds its Dockerfile from the reviewed default branch, and names
each feature as not installed, so nothing runs at build time that the project file does not name.

## A repository's image comes from porelo.yml, taken whole, and its build keeps the host's network

*Superseded on 2026-11-06 for what wins: the operator decided in porelo-project's "A repository of its own stays in
Default and says what it needs" that a repository's `porelo.yml` and the local override add to the project's image key
by key - a value they set wins, their `run` lines follow the project's, their `env` joins it. The rest of this entry
still holds until that issue gives the repository's file more than `image`.*

A repository declares the toolchain its tasks need in `porelo.yml` at its root, instead of a devcontainer file whose
tools sit behind `features` Porelo does not run. The file may hold `image` only (`from`, `run` or `run_file`, and
`env`), and every other key - `egress`, `limits`, `build.package_sources`, the security class - and any variable Porelo
sets (`PATH`, `HOME`, `LANG`, `PORELO_*`) is refused by name, so a repository cannot widen what its own tasks may do.
What wins is the first source along a fixed order (the local `.porelo.yml`, `porelo.yml` on the mirror's default
branch, and the project's `image`; in `default`, the checkout's `.porelo.yml` and `porelo.yml`, then the default image),
taken whole - `from`, the `run` lines and `env` - so no image mixes lines of two sources; a repository's file names its
own `from`, and the source is named at the start. Every task image sets `LANG=C.UTF-8` so tools and the JVM read non-ASCII file names
without each repository repeating it.
**The exposure:** a repository's `run` lines run as root of the build's user namespace while the image is built, outside the task's firewall, with the
network an image build has, and is written by the repository's authors rather than only the project's signer. **Why it
stays:** `porelo.yml` is read only from the mirror's reviewed default branch (never `porelo/*`), the start fetches
before letting the mirror's file decide and refuses when the mirror names no existing default branch (while each gate
refresh updates the mirror's `HEAD` from the upstream's), the review ranks `porelo.yml` and `.porelo.yml` as dangerous,
and each key's source is printed at the start. **What would change the answer:** building the image within the
project's egress and trusted package sources.

## A first start takes no project.yml without a yes

*2026-11-05, the operator's.* `porelo task start` in a checkout with no project asks the installed ecosystem adapters and shows what
they found and each host it would open. A `project.yml` in a checkout this machine does not know gets the same question,
with the whole file and each file it names shown: it is an approval of that text, with no comparison to what an adapter
says.

A checkout's files change under whoever reads them, so an approval of a path does not hold what was approved. The file
and each file it names are read once onto this machine, and only that copy is parsed, shown and hashed. A yes moves it
into the project's place, where every start reads it, by `-p` and through the daemon too. A checkout's files only decide
whether to ask again. Nothing that was not read at the question can reach a start through it, and an edit behind a
running question is simply not what was approved.

Same file, same project: a checkout with the upstream the project was approved for and the same hash, a second clone or
a git worktree, uses it with no question. A checkout with no usable `origin` has its own path as upstream, so a second
one is not the same project. Another upstream takes over nothing: it is refused and goes on in `default`.

The question covers `project.yml` and the files it names, and nothing else a repository holds. A repository's `porelo.yml`
is not part of it. It is read as `ImageChoice` says and as the entry above states: in `default` from the person's
checkout, also after a no; for an approved project from the mirror's default branch, also after a yes, where it comes
behind a local override and ahead of the copy's own `image`.

Same upstream means same host, port and path, by ssh or https alike, and a path on this machine is never a remote address.
A person's own `git remote` setting (`insteadOf`, a differing push URL) is trusted, as `.git/config` is not committed, and
the start still uses the stored copy.

`--yes`, `--no-input`, `--dry-run` and no terminal write and open nothing. A host only the checkout names, in no curated
set, is marked and the question defaults to no. An organization's project comes first, and a `project.yml` in its
checkout decides nothing.

## A project's own file is judged by its newest change alone

A project's `project.yml` lies in its own repository, beside files developers change on their own. Porelo judges only the
newest commit on the first-parent line that changed that file: it must be signed by a key with an `admin` row. Another
file on top changes nothing. An unsigned change reads as refused until an admin signs the file anew, and that admin then
signs the whole file. Signing anew is an empty commit with the trailer `Porelo-Signs-Anew: project.yml`, which counts as the
file's newest change, so the file stays byte for byte; a trailer anyone can write only says which signature to judge, and one
whose signer is no admin is refused like that person's change. So does a file whose signer is no admin now: a removed, locked or demoted admin's signature counts for
nothing from that moment, since the signer writes a commit's date, and every new start of the project stops until another admin runs
`porelo project sign`, which shows them the file and signs only the revision they name. A machine keeps the date of the newest change it accepted, and refuses an older one, since anyone
with write access could otherwise bring back an older signed file, with no force push. A machine new to the project has
no such date. This is simpler than judging every commit that ever changed the file, and it is the operator's choice.

A file `project.yml` names, such as `image.run_file`, may change with no admin's signature. Nothing checks it.

**The exposure:** a developer's unsigned commit can change a run file, and it runs while the image is built, with the
account's network, outside the project's `egress`, on the machine of whoever next starts a task. Podman runs rootless, so
root in a `RUN` is root in the build's user namespace, not the machine's. A `RUN` is given none of the account's files or
environment, only what the Containerfile copies in. What it can do is fetch and send over the network, and leave what it
fetched in the image, which the running task then holds behind its firewall. **Why it stays:**
a person who can write to the repository is one the project gave that access, and signing every file a project file
names has no end. The operator took this on 2026-11-02, told of the exposure. **Not to be changed:** building the image
within the project's egress was weighed and rejected on 2026-11-08. What a person
builds into their image is their own risk, which Porelo neither can nor wants to take from them, and a person may bring
their own base image at any time, which Porelo does not see anyway.

## Several attempts share one credential

The attempts of one start may share one credential and so its rate limit. Porelo does not split or queue them; a
reached limit is said per attempt, naming the credential, so a stalled attempt is not read as a weaker one. Five
attempts per start is the default ceiling. N attempts use up a subscription's limit about N times as fast; the person
chose N.

## A reviewer task reviews only its own project

A task in the role reviewer reads the waiting work of other tasks of its own project only. Reading another project's
work would carry code across the line Porelo keeps between projects everywhere else.

## An approve lands as a merge carrying provenance; a fast-forward is a project's choice

The merge the host makes at an approve records the models, agent and provider that made the work, read from the
broker, never from the agent, with the reviewed commit untouched as its second parent. A branch protected for linear
history refuses a merge, so a project may choose a fast-forward; it then gives up the provenance in its history, and
the documentation says so. **Not yet built:** `gate approve` pushes the reviewed commit directly by default (or makes a
signed merge with `--signed`) without recording broker provenance or offering a project fast-forward setting.

## The owner approves alone unless a project asks for a second person

On a node the person who started a task is the one who approves. A project that needs separation of duties sets four
eyes, and its approve then needs another of its signers' signature over the reviewed commit. Without the setting a
person working alone is not slowed down.

## A time limit does not cut off a person who is watching

An unattended run ends at its project's limit; a start may shorten that limit, never lengthen it. A person attached to
the task holds the stop back: at the limit they are warned, and the run is stopped once they detach. A person at the
session is the oversight the limit stands in for. **Not yet built:** `project.yml` has no time limit yet, and an
unattended run kills the agent process when `--minutes` expires without checking whether a person is attached.

## Account-wide settings live in one file

Settings that belong to the account rather than to a project - the period after which unused access is reported and
whether it is locked, how long `task.log` is kept, the largest bundle a client may upload, where the event log is
copied - go into `$XDG_CONFIG_HOME/porelo/config.yml`. A missing file means every default, an unknown key is refused by
name, and one page lists every key with its default.

## Unused access is reported, and locked only on request

A device slot unused for 45 days is named by `doctor`, with the remedy. Locking it is a setting, off by
default: a lock surprises whoever comes back after a long absence, while a report leaves the decision with the person.

## The event log is anchored in the user's journal and outlives a clearing

An account's own file cannot be made tamper-proof against that account, so each line's hash also goes to the user's
systemd journal. Where the settings name a destination, each line is also sent off the machine; nothing leaves without
that setting. The log is the account's history, not a project's, so `porelo clear` of the account keeps it.

## What carries an agent's words is kept for a period

`task.log` holds the agent's own words and so may hold personal and confidential data. It is cut 30 days after it was
written, even while its task lives, and so is a session's recording; the account's settings may change the period.

## A task's spending is capped in tokens, and in money where a price is known

A token ceiling holds for every provider the broker forwards to: the broker reads the count from each answer, and on
a streamed Chat Completions request, whose answer carries none unless asked (`stream_options.include_usage`), it asks
for it, whatever the agent sent, so an agent cannot leave its own use uncounted. A ceiling in money may be set beside
it. It is counted from the cost the provider reports where it does, else at list price from Porelo's price table, which
is data carrying the date its prices were taken: a price changes without a release, and a stale one shows its date
rather than hiding. A model with no price refuses the money ceiling - at the start where that is known, else fail
closed at the first answer it cannot price - and is never counted as $0. Under a subscription the ceiling caps the
list-price value of what was used, not the money paid; there it bounds a looping agent, not a bill. Both ceilings
hold in the task's broker, which the agent cannot bypass. **Not yet built:** the broker does not yet count tokens,
inject `include_usage`, or enforce token or money ceilings.

## An alert either only tells or stops the task; it never freezes it

A paused task holds its session while its tokens' lifetimes run on, so a freeze contains nothing a stop does not and
leaves a half-open state to clean up. Each project chooses whether an alert only tells a person, the default, or also
stops the task and revokes its tokens.

## A false secret finding is acknowledged on the host, never in the repository

The agent writes the repository, so an acknowledgement it could write is one it could use to silence a finding. The
list is kept beside the project on the host, owner-only, each entry with a hash of the matched text so it lapses when
that text changes. The scan covers the tree a task checks out, not its history, and the documentation says so.

## A new dependency with a finding waits for a person

A name that is not in its registry, could not be checked, or is close to a well-known one is the shape a hallucinated
or squatted package takes; a mark in the review is not enough, because an approve can be given without reading it. The
approve asks for each such dependency and, without a terminal, is refused until the dependency is named, bound to the
reviewed commit. Maven Central and npm come first.

## A release waits for the misuse suite, and a found hole is never skipped

The guarantees are attacked as an agent would attack them - escaping the container, carrying data out through DNS,
allowed domains and the broker, bypassing the approval - and a release is made only when every such scenario ran and
passed. A scenario that finds a hole holds the release until the hole is closed; it is never tagged or filtered out to
let a release go.

## The data protection page follows the federal ISDS concept template

An organisation that writes an information security and data protection concept fills the Swiss federal template
chapter by chapter; a page in the same chapters lets it take Porelo's part without rearranging it. Each chapter says
what Porelo provides and what the organisation still regulates.

## Every Porelo repository gets the changelog check from its parent

The rule that a change brings a changelog entry holds for every repository, so the check is bound once in
`porelo-parent`, which every Maven repository of Porelo inherits, and configured where only Porelo reads it. A repository
whose product is not built with Maven runs the same check as a step of its own in its workflow.

## A leg runs only the unit tests that need its machine

A rented machine exists to deploy the product and run the acceptance suite against it. Unit tests run on the build
host, except those whose condition only the target has, such as SELinux; those run on the leg that has it and fail it
when skipped there. A leg is not made a second build host.

## A kernel of its own for a task is measured before it is decided

The second wall is worth something against kernel bugs, but its cost falls on the ruleset, the resolver, the hooks and
the sockets. Adoption is decided on measured answers: whether a kernel of its own works, whether the ruleset still
fails closed, and what it costs. Snapshot and restore of a task, its running agent with it, is a goal such a runtime
may make reachable.

## A storage class Porelo cannot prove is shown as user-scoped

What protected an unlock is shown as worth more only when Porelo can prove it, by a slot the node enrolled itself or by
evidence such as a TPM2 attestation the node checks. A claim a client merely sends is recorded, but the command line
and the interface show it exactly like a user-scoped slot: a label that reads stronger than it is does more harm than
an honest weaker one. **Not yet built:** `EnrollDevice` and `vault devices` still record and display the storage class
a client claims without proof instead of showing unproven claims as user-scoped.

## An organization is judged path by path, and a first card by the forge's public key list

Many people can write to an organization's repository, so a commit's signer alone proves nothing. Every read judges each
commit on the first-parent line path by path: a card is changed by its own key, and a change of key by its
predecessor too; `owners.yml` by an owner it names; a host's card and access file by a host admin `owners.yml` names
for that host; any other path is never read. A first card is trusted only when the central forge lists its key for
that login on the public `<forge>/<login>.keys` page. The page is read, not the forge's API, because Forgejo's API
asks for a token even for another person's public keys; until the forge has been asked, the card is `first-seen`.
A change commits with plain Git, signed through the person's agent, never through a forge's contents API: the
signed commit is the proof, and it is made where the key is.

## A new key goes straight into KeePassXC, and a running KeePassXC is locked to serve it

`--keepassxc` makes a person's new key in memory and hands it to `keepassxc-cli` over a pipe, so no key file is
written anywhere; a key held only by the agent is not enough, since it is lost at restart. `keepassxc-cli` reads the
master password on the person's terminal; Porelo never sees it. KeePassXC 2.7.10 does not serve an entry added while
its database is open, not even after its D-Bus `openDatabase` of that database; it serves it once the database is
locked and unlocked again, or when KeePassXC starts anew. So when the agent has not served the key after 3 seconds,
Porelo runs `keepassxc --lock`, asks KeePassXC to show the database, and the person unlocks it in KeePassXC's own
window. **The exposure:** `keepassxc --lock` locks every database the running KeePassXC has open, not only this one;
the others stay locked until the person unlocks them. **Why it stays:** unlocking through D-Bus
`openDatabase(file, password, keyfile)` would serve the key with no step by the person, but it needs the master
password in Porelo, which only `keepassxc-cli` reads. **What would change the answer:** a KeePassXC that serves an
entry added to an open database.

## An organization's trust record is checked, not hardened against whoever writes the state directory

A read keeps the state a walk of the organization ended with as a trust record in the person's
`$XDG_STATE_HOME/porelo/organization/`, owner-only, and the next read walks only the commits after it. Before that, the
record is checked whole: every field as a walk writes it, each file it names read again from the blob it names and
judged as the walk judges it, each change on the first-parent line, touching its path and signed by the signer named.
A record that fails any check, or does not read, is discarded, said so in one warning naming why, and the organization is
walked again from its founding commit; the fresh state replaces it. Slower, never weaker. A head that does not continue
the record's commit still refuses the read, since then the history, not the record, is what is wrong.
**The exposure:** the check is not a proof. What the walk knew at the time, such as which keys owned the organization
when an owner replaced a card, or what the forge said, is not in the history, so a hand-edited record can still pass
in ways a walk would not: one is the signer of an owners or access file, which the record check does not judge
against the owners before it. **Why it stays:** who can write the state directory can change the person's Porelo
configuration and binaries too, so a record hardened against them protects nothing they could not take another way;
the check is there against a damaged or stale record, and further ways a hand-edited record could pass are recorded as
Ideas, not fixed one by one. **What would change the answer:** a record that crosses a boundary the state directory
does not, such as one shared between machines or people, which would then have to be signed or proven against the
history in full.

**The organization's record of the new structure is under the same answer, and checked less.** The Git adapter keeps its own
under `$XDG_STATE_HOME/porelo/organization/`, owner-only. A tip that does not continue its commit refuses the read, and one
that does not read, names another address or is not owner-only is discarded with a warning and the store walked afresh. Its
fields and each card against its fingerprint are checked; the changes are not judged again from the history, so a hand-edited
record passes more easily than today's. The exposure and the reason are the ones above: who can write that directory can
change Porelo too. A stale answer rests on this file alone, without a fetch, so no security check may rest on one.

## A backup key replaces only the card of the person who filed it

*2026-11-02.* Filed under an owner's place in `owners.yml` or a host admin's in `host.yml`, a backup key replaces the key of the login it stands under and nothing else, and the old key is revoked from that commit on, as by an owner's replacement.

A host admin's backup key signs the commit holding the new card, the card signed by the new key alone. An owner's backup key signs no card: it puts the new key in `owners.yml`, and the new key then signs the card, in a second commit of the same push.

A host's admins may change `host.yml`, so a new backup entry must be signed by the key of the login it names. Otherwise one admin could name a key of their own as another's backup, and take that person's card.

## An owner handing on a key changes the owners first

*2026-11-02.* `owners.yml` holds an owner's key itself, so a card handed on alone would leave the old key signing as an owner and the new one unable to. One push holds two commits: the old key signs `owners.yml` with the new key in its place, then the new key signs the card. No rule lets a non-owner's key change `owners.yml`, and a reader judges each commit as any other. A push is taken whole or not at all, so a refused one leaves neither.

## An owner does not move a host's pin

*Superseded for the organization admin by [what follows it](#a-hosts-pin-follows-the-organization-admins-and-root-stays-with-its-machine-admins); it still holds for an owner not listed.*

*2026-11-02, the operator's.* A host's pin follows its admins only along what the pin itself vouches for: a chain the old key signed, or a commit signed for the pin, a backup key that key filed among them. An owner's replacement of an admin's card is neither, so it leaves the host at `refused-state`, naming the commit, until its admin renews the pin on the machine as root.

The other way, an owner may move a pin, is simpler for a person who lost their only key with no backup, but it gives every owner's key the whole fleet. A host cannot tell a stolen owner's key from an honest replacement by its look, and the pin exists to stop exactly that. So losing the only admin's key with no backup means going to the machine.

Only a card the forge vouched for reaches a host, as a key or in the pin. While the forge cannot say, anyone with write access could file a member's first card; the role stays answered, the key does not.

## An organization has four roles, each answering for the one below

*2026-11-03, the operator's.* An owner answers for owners and organization admins. An organization admin answers for cards, a project's card, machines, and the admins of projects and machines. A machine admin answers for the machine's card, what it carries, and taking Porelo off it, which also removes its entry. A project admin answers for developers, repositories and their address, and `project.yml`.

The roles do not stack. An owner does an organization admin's work only when listed, and may list themselves; a machine admin or project admin names no further admin of that level. The owner is rare on purpose: day-to-day work runs under admins, so the key that reaches the most is used the least. **What would change the answer:** an organization too small to keep them apart, which today lists one person in each.

## The roles start with their list

*2026-11-03, the operator's.* A reader walks an organization's history from its first commit, on a new machine too. A rule changed for every commit would refuse what owners and admins signed rightly before it, and everything resting on that. So `organization-admins.yml` is where the roles start: until a commit files it, owners do an organization admin's work and the rules from before hold; once it stands, only the file counts, and it cannot be removed. `org create` lists the founder.

A machine on an older build does not read the file and goes on with the rules from before, so it refuses every change an organization admin signs, as no key it knows of, until it is updated; the rest it goes on reading. A record kept by such a machine is read afresh once updated. **What would change the answer:** no organization left from before the list.

## A host's pin follows the organization admins, and root stays with its machine admins

*2026-11-03, the operator's.* A machine admin's change or replacement by an organization admin takes effect on a running server at its next follow, with no step of the former admin, who may be gone. A commit signed by a listed organization admin counts as signed for the pin, and the pin then holds that role's keys and the machine's admins. A commit that lists its own signer gains nothing by it in that commit.

Root stays with the machine's listed admins. An organization admin who needs root lists themselves as that machine's admin: a visible signed change, one server at a time.

**The cost, accepted.** One stolen key of this role reaches every server's pin and can make itself root there, visibly. One owner's key reaches as far, by one commit listing a key of its choice. An owner's key not listed reaches no pin by itself. A server whose follow fails keeps its old list for as long as that lasts. **What would change the answer:** the role's key held on hardware, or a change of the list needing two signatures, neither built.

## Withdrawing a card revokes its key

*2026-11-02.* A card removed by its own key or by an owner revokes that key from the commit on: it signs nothing more, and the card cannot be filed again with it, so coming back takes a new key. Past commits stay accepted. The only owner's card cannot be withdrawn: nothing could be accepted afterwards and nobody could put it right.

## A founder's key that left the forge refuses the organization on a new machine

The first `owners.yml` is proven by the identity source alone: it must list the founder's key. **The exposure:** when that key
leaves the forge with no card handed on, a new machine can prove no owner, and with no owner nothing else. It answers
`refused-state`, naming the key; it does not answer an empty organization. A machine that read it while the key was listed goes
on from its record. A forged first file answers the same until the real founder files theirs, which still founds it.
**Why it stays (the operator, 2026-11-02):** anything else would let somebody other than the forge's listed key found an
organization. Recovery is the key back on the forge for the walk, or a new organization, and `org create`'s words say to keep a
second owner or a backup key. **What would change the answer:** a root of trust that does not rest on the forge.

## Every organization store passes one set of checks

What keeps an organization is an adapter's own, so what it must guarantee is written as checks over the API, which need
neither `git` nor a class of an adapter. Each check has been seen to fail against a stand-in that breaks what it guards.

- **A change counts only signed by the one allowed to make it, wherever it lies in the history.** An unsigned change, or
  one signed by a key the place's rule does not allow, gives no role and no owner's place, under a later honest change as
  well, and the read names the refused revision and nothing before it. An admin has no right in another project and a
  locked admin none in their own; an owner names admins, adds nobody else and touches no lock.
- **A person's card stands against everybody else.** A card overwritten, removed or replaced by somebody who may not, or
  filed for its person by another key, holds nothing; nor does one the identity source denies, and a card squatted in
  another's name gives way to the person's own.
- **A revoked key signs nothing, wherever else it is held and after a read as well**, an owner's though the list of owners
  still names it, and an older card played back gives it nothing. The revocation lies in the history, so this holds from a
  machine's first read on.
- **A state that does not continue what this machine trusted is refused each time it is served**: an older one, a fork,
  or one without a revocation. A machine that has trusted nothing yet has nothing to compare, so a state rolled back
  before its first read is not noticed.
- **A change through the API is made by the one allowed, once, or not at all.** A person acts with the key on their own
  card, within the role they hold; a base the store moved past, a card of another login or key and the last owner or
  admin are refused, and a refusal leaves nothing a reader sees. An id asked again answers the change it made, is another
  person's id no answer, and is refused when it named another change. What a change says it made, a fresh reader accepts,
  and a change that would alter nothing is refused rather than answered as made.
- **What a replaced key did goes for a new reader, and what a handed-on key did stays.** An owner's replacement links
  nothing to the old key and the source no longer lists it, so members that admin added are gone for a new reader until an
  owner adds the admin again and the new key adds the rest; a reader that trusted the state before keeps them until
  then. A card the old key signed links the two, and nothing is lost. A new key's card
  with no word from the old one stands only where the source lists the new key alone, so no card stands for a new reader
  and the source decides, as for a first card; otherwise it is refused.
- **A host's offline check answers only for its pinned key.** It answers the host's admins, projects, persons and card
  under that key. It refuses a copy under another person's pin, one holding a change nobody allowed, also under a later
  change or with a stranger's key named beside the pin, an owner's change of the host's files, and one that does not continue
  the state the host applied last. The pin follows a new admin only through a commit it signed itself.
  A first-seen person's key reaches no host while the identity source cannot say: no row in `admins`, `persons` or
  `signing_keys`, and the role stays in `members`.

`organization/git-tree` runs the reading and the offline checks on real commits.

## A project's lock does not bind an organization owner

A locked member of a project is listed but may do nothing until one of the project's admins unlocks them, and a commit of
an owner to `members.yml` that changes a lock is refused. **The exposure:** an owner may add and remove a project's admins,
so an owner can remove a locked admin and add them back unlocked, or add themselves as an admin and sign the unlock in that
role. **Why it stays:** an owner must be able to name admins, so that a project whose admins all lost their keys can be
recovered, and a remembered lock would stop only the first way. Every step is a commit the owner signed, so it can be seen
and answered for, and the refusal still stops a plain edit of a lock. **What would change the answer:** a lock that must hold
against the organization's owners, which would narrow what an owner may do to recovery alone.

Once [the roles stand](#the-roles-start-with-their-list), an admin's row and lock are the organization admin's: that role may change
one directly, a project admin may not, and an owner not listed does neither. A developer's lock stays with the project's admins.

## A past commit is judged by the whole chain of its key, read to the tip

A read walks the organization's first-parent line twice. The first pass reads every change of key to the tip, whatever
revision was asked for. A step counts only where the new key signed the commit and both keys signed the card, for the login
whose card it is. The second pass judges rights in order, and asks the identity source about a key and every key it was handed
on to, so a person whose old key has left the forge keeps their past commits, and a new machine and an old one agree.

**A lost key is no such step.** When an owner replaces a card, nothing leads from the old key to the new one, so on a new
machine the old key's past commits stay refused if the forge no longer lists it. The recovery is a new signed commit: an owner
adds the project's admins again, and they add the rest. An address the old key filed comes back the same way, with
`project address` by an admin; an address written over a refused commit carries a comment naming its change, so that the
same address is still a change for a reader to judge.

## What a build runs beside its secrets is fixed by its content

A job that holds a signing key or a publishing token runs only what is named by its content. Every `uses:` names a
commit with its version beside it, and `porelo-release check-actions`, bound to `validate`, refuses a tag, a branch, a
commit without its version and an image without its digest. Maven comes through the wrapper's
`distributionSha256Sum`, GraalVM through `pinned-jdk`'s SHA-256, and the signing key is imported by a step of our own.
Artifactory is spoken to with `curl` over its REST interface (`.github/scripts/artifactory.sh`), so no client is
downloaded into the job that holds its token; each upload carries its checksums, which Artifactory checks. Dependabot
moves the pins weekly in one grouped pull request, merged by a person. The jobs run on `ubuntu-24.04` (and `proven` on
`ubuntu-26.04`); the image behind each runner name still changes weekly, and is out of scope. **What would change it:**
a step that needs a tool with no checksum to pin it by, which is then built from source or dropped, not trusted by its
name.

## A digest pins an image; a tag-only image in a project needs a person's consent once

A digest names exactly one image, so a signed project file that carries one pins its content; podman's signature
policy is an option a project may add. A project that names its image by tag only does not start until a
person allows it once; after that the digest is kept and a change of it is said at every start, so a moved tag never
changes what runs silently. **Not yet built:** a project with a tag-only `image.from` still builds and starts
without asking for one-time consent or recording the resolved digest.

## One key signs everything a machine installs from Porelo

Packages, the setup script and the Debian repository's metadata are signed with one key, so a machine trusts one
fingerprint. Its private half is held by the project's owner only and reaches CI only as a repository secret. The
repositories that publish to the same package repositories switch signing on with the same release, because a setup
that checks signatures refuses every repository that does not sign yet. **Not yet built:** RPM package signing is
still skipped (`gpgcheck=0` on Fedora) and `porelo-setup.sh` is published unsigned.

## A view of a task is guarded by Porelo's token, not by VNC's password

A task's screen is offered only to a project that switches it on in `project.yml`, and even then only through Porelo's
relay, which takes a strong one-time token before a byte reaches the VNC server. Loopback alone is no access control on
a machine with other accounts, and VNC's own password is too weak to rely on.

## A new name starts afresh

When the product took the name Porelo with 0.5.0, nothing was carried over from the name before: no state, setting,
vault, command, path or package of it is read or kept, and nothing is migrated, so a machine is set up afresh. Maven
Central keeps the old artifacts as they were, with no relocation, and a released changelog entry keeps the name it was
released under. A reader of the old paths would have stayed in the code for good, for machines that are set up again
in minutes.

## A run left to itself comes back from a restart stopped

A task started with a prompt and left to run is not started again by `porelo task start --restarted` or by anything
else after the machine restarted. It stays stopped, `task list` shows it as interrupted by the restart, and its
session is kept, so its next `task start` continues the conversation. A run nobody watches that came back by itself
would spend tokens and push work at a moment nobody chose. A task somebody drove at a terminal comes back with
`--restarted`, since that person is the one who asks for it.

## A task's terminal is tmux, and a person's own settings come after Porelo's

An agent runs in a tmux session in its container, because tmux carries reattaching, the screen Porelo reads for
`at_rest` and the lines it types to wake an agent. Porelo's settings make it feel like the terminal outside; what they
cannot close is written down in `running.md`. An account's own `$XDG_CONFIG_HOME/porelo/tmux.conf` is copied into each task
at its start, read-only for the agent, and read after Porelo's settings, so bindings, the prefix, the mouse and colours
are set once with no image rebuilt. What Porelo depends on, `history-limit` and `default-terminal`, is set again after
it, so a person's file cannot break it unawares. **What would change the answer:** something configuration cannot
reach, which would bring a relay without a terminal of its own to the operator as a proposal, with what it would lose.

## Work is reviewed on the machine, never on an upstream branch

In a `guarded` project, waiting work is read with `gate review` and `gate checkout` on the machine. For a task started
from a local checkout in `default`, approved work goes back to that checkout as `porelo/<task>` (`porelo approve`),
while `porelo gate approve` pushes approved work of a repository to its `upstream` branch. On the host's own forge,
the default is `porelo/<task>`, and approval opens or reuses a pull request to the default branch as the task's account.
The person reads the work at the gate before it leaves, then reviews and merges the pull request on the forge.
No `guarded` setting sends unreviewed work to a branch on the upstream. A team that wants work to reach the forge
before review uses [`online`](security.md#online),
against a private company forge or
[the host's own forge](https://github.com/porelo-ai/porelo-host/blob/main/doc/local-forge.md).

## No editor is attached to a task's container

An editor that attaches to a container installs a server in it and downloads that server first, and a task's egress
does not reach any editor vendor's hosts. Declaring them would widen what the agent may reach so that a person can
look at its work, and copying an editor server in or baking it into the image would put a vendor's program beside the
agent. Neither is done. `porelo gate checkout` opens waiting work as a copy whose only remote is the gate and in which
no hook runs, and the pre-push guard of `porelo gate protect` catches the hand push.

## The pre-push guard catches an accident, not the owner of the machine

`porelo gate protect` installs a pre-push hook that names the commits an agent authored and nobody approved.
**The exposure:** `git push --no-verify` skips it, a `core.hooksPath` pointing elsewhere disables it (`protect` says
the repository is then not protected), a fresh clone has no hook, and a client built on a git library may run no
hooks. The author it tests is preserved through a rebase but can be spoofed. **Why it stays:** it is the person's key,
checkout and computer; the guard makes the accidental push loud, and `--no-verify` is the named way past for a person
who means it. What the hook misses, the gate still records truthfully where it can: `gate pending` marks work the
upstream's default branch already has, and `reject` discards it only with `--force`. **What would change the
answer:** a way to see every push to the upstream, which would be the forge's, not Porelo's.

## An offline project never connects, and its agent still reaches its provider

An `offline` project makes no external Git or egress connection out, neither from the task nor from the host on its
behalf: it names no `upstream` or external `egress`, and a start takes no `--upstream`. A file written on this machine is
never fetched; one the organization names is read at its start as every other is, before its class is known, so a
machine with no route has its project written there. Its code comes in by `gate restore` and leaves by `gate backup`. Declared
`egress.host_services` still reach this host on TCP 443. Offline only after the first start, as it once was, is not
what a person choosing it for a machine without a route expects. **The exposure:** the agent's model calls still reach
its AI provider through the host's broker, as in every class, so the prompts and what the agent reads leave the machine
to the provider. **Why it stays:** without its model the agent does nothing; the class picture and `security.md` say so
plainly. **What would change the answer:** a model reached on the machine itself.

## An agent is found by its description, and by Porelo's file name beside it

A vendor's package registers an agent with one file, `agents.d/<name>.yaml`, naming the absolute path of its
executable, so the package, the program and its place keep the vendor's own names. Executables named `porelo-agent-*`
in Porelo's agent directories are scanned first (the account's `$XDG_DATA_HOME/porelo/agents` winning by file name over
`/usr/libexec/porelo/agents`), followed by `agents.d/*.yaml` descriptions (the account's
`$XDG_DATA_HOME/porelo/agents.d` winning by file name over `/usr/share/porelo/agents.d`), and the first executable that
reports a given agent name wins.

## A bound in the acceptance kit is about four times what was measured

The kit's ready bound (`ready_within_seconds`, counted from the session existing, so never covering an image build)
and the N of an unattended run's *"ends within N seconds"* are about four times the agent's measured time, and the kit
refuses either above its cap, 120 s, naming the cap. The caps are written beside the steps with the measurements they
come from. A bound that grew then fails at once, where a generous one let stuck scenarios fill a whole step.

## The daemon listens on no network port; another computer reaches it through ssh

The daemon binds only its Unix socket, and a check of the unit's control group finds no TCP or UDP listener. Another
computer reaches it as the account it runs for, in one of two shapes: `ssh -L` forwards a local socket file onto the
daemon's socket, and a client opens as many connections through it as it likes; `ssh HOST porelo daemon connect`
carries one connection down the ssh session, with no socket file to tear down, so each stream a client keeps open is
an ssh session of its own. No listener, certificate or token of Porelo's is added for it. **The exposure:** reaching a
Porelo needs a shell on its machine; there is no browser and no other client. **Why it stays:** an ssh tunnel is
already encrypted, already authenticated and already administered, and a forwarded socket is created owner-only, so
it is no more exposed than the original. **The forward is repaired, not replaced** (decided 2026-10-18): it is how
the interface and the IntelliJ plugin reach a remote machine, so when an enforcing SELinux reset it, the policy module
was fixed rather than the clients routed through `daemon connect`, and nothing is multiplexed over that bridge, which
stays for WSL, scripts and tests. **What would change the answer:** a client that cannot open an ssh session.

## An upgrade reloads a loaded SELinux module, and doctor names an older one

A package upgrade leaves a loaded policy module as it was, so a machine would keep the grants of the version it first
loaded. The rpm's `%post` reloads `porelo_socket` on an upgrade where it is loaded, leaves a machine that never loaded
it alone, and prints the command where `checkpolicy` is missing. `porelo doctor` cannot read the loaded module's
version: the module store is root's, and a module's version is not kept. It asks the kernel instead what the loaded
module grants (`/sys/fs/selinux/access`, which any process may ask), for every domain the package's module names, and
one refused reads as "the loaded module is older than this package's", with what fails, such as sshd for a socket
forward, and the reload command.

## The daemon serves on platform threads, and a hang is read from its thread dump

Every varlink connection and every long-lived background pass of the daemon runs on a platform thread, and an
agent's handshake waits on one, so work pinned in a native call cannot take the carriers a call on the socket needs.
`porelod` is built with thread dumps on, so `SIGQUIT` writes where each thread waits to the journal without stopping
it. `PORELO_THREADS=virtual` puts the connections back on virtual threads, kept so the choice can be measured again.
**The exposure:** a daemon once answered no call for minutes, and that virtual threads sharing two carriers caused it
is inferred, not measured. **Why it stays:** measured on two-CPU machines right after a `SIGKILL` with fifteen
projects followed, platform and virtual threads alike answered every bounded call well inside its bound, with no hang
either way, and at thirty working tasks the kind of thread made no difference: the daemon holds a few dozen threads
and its work is processes, which a virtual thread does not make faster. Where virtual threads fit, many long network
waits in plain Java, Porelo uses them, as the credential proxy does. **What would change the answer:** a hang whose
dump names the wait, or a measurement in which virtual threads gain.

## A daemon at rest runs a loop only for what can be waited on

A loop that waits on something outside the daemon rests when that thing came back without waiting, since a loop that
never waits turns a core on any kind of thread. A task's direct-chat loop runs only while its container runs and the
vault holds an account for it, and ends the moment that account cannot be read; a project's conversation loop runs
only while one of its tasks qualifies so. An exited task's messages wait for the timed pass. A daemon with exited
tasks and no account for them stays under 5 % of one core, and its threads at rest do not grow with every task ever
started. **The exposure:** a project whose file names a transport and that has no task still costs about 1 % of one
core, its own poll. **Why it stays:** it is one poll per project, not one per task, and below the bound. **What would
change the answer:** enough such projects on one machine for their polls to add up to the bound.

## A clearance question waits 60 seconds, and an expired one can still be answered

A clearance prompt nobody answers within 60 seconds refuses, and nothing is measured for that wait. The expired
question is recorded, replaced on screen by a notice that the destination stays blocked, and can still be answered
afterwards through `Decide`, from the interface while the task runs. Whoever wants a longer wait sets it with
`shield watch --timeout`.

## One agent works in one repository

A task holds one repository, so an agent whose work spans several becomes one per repository, and reading across them
is the coordinator's. A task's commits carry the name it goes by as their author, `Agent <nickname>` or `Agent
<repository>` at `agent@localhost`, not as a trailer, so who wrote a commit is read where git shows it.

## A host carries a project whole or by some of its repositories

*2026-11-06.* An entry of a host's `projects` names a project whole, or names some of its repositories. A repository is
on one host at most, and a project carried whole keeps each of its repositories off every other host, so the reader
refuses at its path a host file that would put one repository on two hosts. One project may be spread over several
hosts by its repositories. Whether a named repository exists is not knowable from the organization's files, since a
project's repositories are in its own `project.yml`: the host's follow refuses an unknown one. Each read key at the
central forge is deleted by whoever made it, by the id it kept: the host its per-repository keys, Porelo the
organization's (`org remove-read-key`).
