# Writing a transport

A transport is a package on the host that carries signed messages between Porelo installations. Porelo
finds it, asks it to describe itself, and runs it as a command; it never runs inside a task. This page is
for whoever writes one; what a project's conversation is for the people using it is in
[messages between tasks](messages.md#where-the-conversation-lives).

## What every transport answers

**Every verb starts from a chosen environment**, as the message filter does: the search path, the account, its home,
shell and temporary directory, its locale, time zone, XDG directories and user bus, and nothing else of the caller's.
Where the account sets none of `LANG`, `LC_ALL` and `LC_CTYPE`, it gets `LANG=C.UTF-8`, so an argument or a path
holding non-ASCII characters reaches it whole; a locale the account sets, even `C`, passes unchanged.

- **`describe`** reads nothing and prints one JSON object: `scheme`; `poll`; `confirms` (`handover`, `receipt`
  or `read`); `max_bytes`; `credentials` (for example `[{"name":"PORELO_MATRIX_ACCESS_TOKEN","as":"env"}]`);
  `hosts`; `attests`; and, if it keeps a conversation, `lifecycle` (the verbs below it offers) and `takes`
  (`["login"]` to be told the person's login); and `"secrets": "input"` when it takes its secrets on its
  standard input (below). A transport whose description cannot be read is not polled and attests nothing.
- **`send <file> <sig> --to <rest>`** hands one message over. `<rest>` is the peer's address after its first
  colon, never a path Porelo invented. It prints its receipt as one JSON object, `{"reference": "..."}`.
- **`poll --into <dir>`** writes what arrived into that directory, each message with its detached signature as
  `<message file name>.sig`, and prints how many. A message without a signature gets no `.sig` and is held.
- **`read <reference>`**, for a transport that confirms `read`, marks a message read with the reading task's
  secrets, printing nothing; posting twice is still 0.
- **`receipt <reference> --by <address>`** prints `{"state": "read" | "delivered" | "unknown", "at": ...}`, exit
  0 for all three.

`describe` may give `waitsOn`: one line of at most 80 characters naming what `setup` waits on,
for example `"the room's homeserver"`. Porelo shows it before calling the transport, falling back to
`the <scheme> transport` when it is absent or empty. A transport's last nonempty line on standard
error is shown as the cause when `setup` or `enroll` fails, with terminal controls removed and cut
to 300 characters. That line must never hold a secret.

While a verb runs, each line it writes on standard error reaches a person at a terminal, or the step of a task's start,
as it comes. Porelo removes control characters and withholds the secrets it handed over, a line at a time. It cannot know
a secret the transport makes while it runs, or one written in two pieces, so never write those on standard error.

`describe` may also announce options Porelo then passes, each as `true`; an option a transport does not
announce is never passed, since an unknown one is its 64:

| Announced | What Porelo then does |
|---|---|
| `"shown": true` | passes `--shown <file>` to `send`: a file beside the message, written by the host from the checked message, holding the line a person is shown for it, with the sending task's mark in front, as `[unverified] ...`, where it has one |
| `"mention": true` | passes `--mention <account>` to `send`: the account of the task or person the message is for, so the conversation shows whom it is meant for; never in a direct chat |
| `"persons": true` | passes `--persons` to `poll`: hand over what people say in the conversation as well |
| `"direct": true` | also polls each task's direct chats, with `poll --into <dir> --direct` and that task's secrets, and hands what arrived to that task alone |
| `"waits": true` | passes `--wait <seconds>` to `poll` (at most 30): the server may hold its answer open until something arrives |

Nothing is read from standard input but what Porelo hands a transport: a lifecycle verb's settings, and its
secrets (below). Messages are files. A transport never
changes a message's bytes, never decides whether a message may be sent, never reads another transport's queue,
and never creates a directory: it delivers into the `tmp/` Porelo made and renames, and refuses or defers when
something is missing.

**Exit codes:** 0 done; 64 usage; 65 not carriable; 75 temporary (the message is retried); 76 answer not
understood; 77 refused by the server; 78 configuration missing. Every code but 0 and 75 is a refusal, said once.

**`attests`** lists facts a transport proves about a message from something the host cannot see afterwards; it is
`[]` for a transport that only carries bytes. A transport that claims a fact and hands over a message without it
has the message held; one that claims nothing has such a file ignored.

## A transport that keeps a conversation

Such a transport offers a lifecycle. Every verb takes the project's settings (what `project.yml` says under
`mail.transports.<scheme>`) and the secrets Porelo kept for it, never as arguments, prints one JSON object, and
exits with the codes above. **A setting named `*_file` is a path, never a value.** A setting that carries its
value inline has another name, as a CA's text in `ca_certificate`. Any `secrets` it prints are kept in the account's vault and handed back; they are never
shown, logged or put into a task.

**How they are handed over.** A transport that says `"secrets": "input"` in `describe` gets on standard input one
object, `{"settings": {...}, "secrets": {...}}`, and nothing secret in its environment: `settings` for a lifecycle
verb (`{}` when the project names none), `secrets` wherever Porelo kept some - for `poll`, `read`, `receipt` and
`send` too - as the whole map the transport printed, key for key. Standard input is closed once written. Once its
input holds `secrets`, the transport reads no secret from its environment. A transport that does not say so gets
the settings alone on standard input and its secrets as environment variables, where another process of the account
can read them in `/proc/<pid>/environ`; `porelo doctor` names it. That way stays until every published transport
takes its secrets on its input, and goes in a change of its own.

**A secret of the person's, borrowed for one verb.** A credential in `describe` may name the verbs it is lent on, and
Porelo keeps no list of them:

    "credentials": [{"name": "PORELO_MATRIX_REGISTRATION_TOKEN", "as": "env", "lent": ["setup", "enroll"]}]

On such a verb Porelo looks in the person's vault under the name lower-cased, less `PORELO_`, with `_` as `-`:
`matrix-registration-token`, with `.<server>` after it, the host of `mail.transports.<scheme>.homeserver`, or that
setting whole when it is a Unix socket, as `matrix-registration-token.unix:/run/room.sock`, or `http:`, the host and
the port when it is plain `http` on this machine's loopback, as `matrix-registration-token.http:127.0.0.1:8008`. Only that
entry is lent, never a plain one: a project's file names the homeserver, so a plain entry would go to whatever server
it chose. What it finds goes into that verb's `secrets` under `name`, and is not kept: a value the verb prints back
under `secrets` or `account` is dropped, and it stays where the person put it with `porelo vault put`.

- **Only a plain `https` homeserver, a Unix socket or plain `http` on loopback names a server:** an address with a user
  part, a query, a fragment or a backslash, or one over `http` anywhere else, lends nothing, since a client could read it
  as another host, and plain `http` crosses the wire in the clear. A socket is `unix:` and an absolute path of plain
  characters, spelled the one way; the token goes only to that path.
- **Loopback is `127.0.0.0/8` in dotted decimal, `localhost` or `[::1]`**, each spelled the one way: `http://127.1`,
  `[0:0:0:0:0:0:0:1]` or an octet with a leading zero lends nothing. Its name keeps the port, `80` when the address
  names none, since another port on loopback is another listener, and begins with `http:`, so it is never the name of an
  `https` server on the same host: a token stored for `https://127.0.0.1` does not reach `http://127.0.0.1:8008`, nor
  the other way round. Like a socket, loopback proves nothing about who listens: store one only for a port your own
  homeserver holds.
- **A socket proves nothing about who listens:** `https` proves the server, and a path only names a file, so the token
  reaches whoever holds it. Store one only for a path only you can bind. The room's name plays no part, since it says
  nothing of where a socket leads.
- **An `https` name leaves out the port:** two homeservers on one host share an entry, so they are lent the same token.
- **Nothing found:** `secrets` goes without it, and the transport refuses as it would have (78).
- **A shut or unreadable vault:** 77, and the transport is not run.
- **An entry with no `lent`, or one that is not a list of strings, lends nothing**; nor does a name other than a
  variable's (`[A-Z][A-Z0-9_]*`), or one whose entry does not begin with the transport's scheme and `-`, so a transport
  cannot borrow the person's other credentials by naming them.
- **Only a transport that says `"secrets": "input"` is lent anything**: in its environment, another process of the
  account would read the person's credential too.

| Verb | When Porelo runs it | Given | Prints |
|---|---|---|---|
| `setup --project P` | before the first task of the project starts, and whenever its secrets are missing | the account's secrets | `secrets` (the project's), `account` (the account's), `conversation`, `reaches`, and on a shared server `login`, `address`, `room`, `admitted` |
| `enroll --project P --task T` | when a task starts, before its container exists; `T` is `porelo-<P>-<task>`, the task's own name, whatever its container is called, and `retire` gets the same | account and project secrets | `secrets` (the task's), `address` |
| `retire --project P --task T` | when the task is removed; and when it is stopped with `task stop` or the daemon's `Stop`, on a transport whose kept task secrets name a non-empty `PORELO_MATRIX_LOGIN` (a central homeserver, where the next start's `enroll` brings the account back), never in a bulk stop; on such a transport also by `porelod` for a task whose container ended without a stop (it exited, crashed, or the machine was off), at the daemon's start, once a minute and when the vault opens, while no start of it is unfinished | account, project and task secrets | `{}` |
| `join --project P --person N [--reset] [--token] [--display NAME]` | on `porelo talk join` | account and project secrets | `login` (for an interface, answered once), `shown` (for the command line); with `--token`, `secrets` holding the person's access token as its one entry and no password anywhere, which `talk join --into-vault` keeps, and `login.room`, the room's id, which it prints; `--display` is the name the room shows, checked by the transport |
| `part --project P --person N` | on `porelo talk remove`; `N`, as `join`'s, is always a login, `[a-z][a-z0-9-]{0,31}` | account and project secrets | `{"removed":"<account>"}`, `{"kept":"<account>","why":"<words>"}`, or `{}` when nothing remains |
| `members --project P` | on the daemon's `RoomMembers` | account and project secrets on stdin; requires `"secrets": "input"` | `{"room":"<room>","members":[{"id":"<account>","shown":"<name>","state":"join\|invite","kind":"task\|person\|helper\|other","task":"<container>"}]}`; `task` only for a task; `members: []` with no room before setup |
| `clear [--project P]` | when a project is cleared from the machine, with `--project`; when the account's vault is cleared, without | the account's secrets, and the project's with `--project` | `removed` |
| `settings` | when a project file is checked | nothing: no secrets, no network | `refused`, `warnings` |
| `admit --project P --machine M` | on `porelo talk admit`, on the machine whose server holds the conversation | account and project secrets | `admission` (what machine `M` needs to reach the conversation) and `secrets` (what it keeps as secrets), answered once, never kept here |
| `dismiss --project P --machine M` | on `porelo talk dismiss` | account and project secrets | `removed`: every account of M taken out of every project's conversation on this server, and M's registration refused; the project only names the server |

- **Every map of secrets is complete** for the verbs that use it: a project's are what `poll` reads and what
  `send` uses for Porelo's own helper answers, a task's are what its `send`, `read` and `receipt` read, and the
  account's go only to lifecycle verbs, including `join` and `part`, never to `poll` or `send`.
- **`setup` is run again and again** and keeps what exists. Porelo runs one `setup` of a transport's account at a
  time, reading the account's secrets inside, so starts at once give the transport the secret the first one kept. A server whose first account exists while Porelo holds
  no account secrets is refused (78), not guessed around.
- **`reaches`** is every host the project's messages reach; Porelo lets the transport reach those and nothing else.
  For an offline project Porelo adds `--loopback-only` to `setup`, `enroll`, `join`, `part` and `members`, and the transport refuses
  (78) before it contacts anything but loopback.
- **`--login LOGIN`** is added to `setup`, `enroll`, `retire`, `join`, `members` and `clear` for a transport that `takes`
  it; `admit` and `dismiss` carry the other machine's name instead, and no login is added. `LOGIN` is this account's
  login on the host, which is the person's, always, never the `login` a `setup` answered; Porelo refuses (78) before
  running the transport when it is not a lowercase letter followed by at most 31 lowercase letters, digits or `-`
  (`[a-z][a-z0-9-]{0,31}`), the rule of every other login of Porelo. No verb is given this machine's name.
- **`part` never receives `--login`**, even when the transport announces it. A successful part (exit 0)
  removes the person from Porelo's members list, including when the account stays for the reason in `why`,
  or nothing remains. A failure leaves the list unchanged: 75 temporary, 77 refused, 78 configuration
  missing, with the transport's own words. If `lifecycle` does not list `part`, removal is refused (69)
  naming the transport, without running a lifecycle verb.
- **`"admitted": false`** from `setup`, exiting 0 with its `account` secrets, means a person still has to let this
  machine in; Porelo starts no task of the project until it is true, and absent means true.
- **`members` is a live read**, never setup, enrollment or an invitation. Exit 75 means the homeserver is
  unreachable; 78 means settings or secrets are missing. Its answer is at most 1 MiB of UTF-8 and 1,024
  members; room and member ids are at most 1,024 characters, shown names 256, container names 64 and logins
  32. Required fields, known states and kinds, and control-free strings are checked; unknown fields are
  not returned. Duplicate member ids or task containers are refused. A task must be recorded on this machine
  as belonging to this project. A transport that takes `login` must include it on every task member;
  task members with another login are omitted. A supplied credential echoed in an answer is refused.
- **`--persons`** gives `poll` every admitted person's login from the project's members, comma separated
  and sorted, of either role; it is also their login on the homeserver. With no admitted person left it is
  given empty, `--persons ""`, and the room keeps no person but its maker. `poll` is given `--persons` for
  a transport whose `takes` list names `persons` or that announces `"persons": true`. Porelo has no verb of its own to
  invite or remove a person.
- A verb a transport does not list is never run.

Porelo polls each conversation once per pass with the project's secrets, hands what arrived to every task of that
project on this machine but the one that posted it, drops what no task here is to read, runs `read` with a task's secrets once its agent has moved a
message into `inbox/cur`, and asks `receipt` about what a task sent, by the recipient's address.

With `--persons`, a person's words arrive unsigned as `<time>--<scheme>-person-<id>.json`, holding `sender` (the
person's account), `body`, `to` (the accounts mentioned), and optionally `eventId`, `direct` and `account`. The
sender must be a person the project's members admit, whether they joined manually or the room's maker keeps them in it:
`account` names that person's host account, only for a sender who is a person's own account on the project's server,
and Porelo takes the words when it is a person neither locked nor revoked among its members who did not join under another account,
from a sender that is neither a task's account nor this machine's. It then keeps the sender as that person's account,
apart from who joined, so a task can answer them while its members name them, and not a moment longer;
`porelo talk members` lists admitted persons with their known accounts. A manual join, a remembered account and a
read receipt count only while the members admit that login. A locked, revoked or unnamed login is
neither heard nor answered, and `talk join`, including `--reset`, refuses it before any transport call. Its records
stay for a later unlock. An unreadable admission decision admits nobody. A project of this machine alone keeps its manual joins.
A room message with an empty `to` reaches every task.
When the line names only the room's helper accounts, the transport instead includes `"helpers": true` and an
empty `to`: exactly the JSON boolean `true` means no task reads it. Any other value, or no field, keeps the
ordinary delivery behavior.

Porelo answers such a line itself, once, through the transport's `send`, using the project's secrets and
conversation. It signs a message from `porelo` to the person's account with a key made for that answer alone, which
no task accepts, and passes this line as `--shown`, mentioning the person with `--mention`, when the transport announces those
options: "This is Porelo's technical account; it passes your messages on to the tasks and does not answer
itself. Mention a task to reach it." A failed answer is reported and never queued or retried; the person file
and the answer's files are removed either way. The transport's poll must omit its own relay's events so an
answer never returns to the tasks.
