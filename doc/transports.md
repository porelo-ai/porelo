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
  (`["machine"]` to be told this machine's name); and `"secrets": "input"` when it takes its secrets on its
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

`describe` may also announce options Porelo then passes, each as `true`; an option a transport does not
announce is never passed, since an unknown one is its 64:

| Announced | What Porelo then does |
|---|---|
| `"shown": true` | passes `--shown <file>` to `send`: a file beside the message, written by the host from the checked message, holding the line a person is shown for it |
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
exits with the codes above. **A setting named `*_file` is a path, never a value**: `porelo project inventory` hands
such paths to the play that prepares a host (`files`), and nothing else of the settings. A setting that carries its
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

| Verb | When Porelo runs it | Given | Prints |
|---|---|---|---|
| `setup --project P` | before the first task of the project starts, and whenever its secrets are missing | the account's secrets | `secrets` (the project's), `account` (the account's), `conversation`, `reaches`, and on a shared server `machine`, `address`, `room`, `admitted` |
| `enroll --project P --task T` | when a task starts, before its container exists | account and project secrets | `secrets` (the task's), `address` |
| `retire --project P --task T` | when the task is removed | account, project and task secrets | `{}` |
| `join --project P --person N [--reset] [--token] [--display NAME]` | on `porelo talk join` | account and project secrets | `login` (for an interface, answered once), `shown` (for the command line); with `--token`, `secrets` holding the person's access token as its one entry and no password anywhere, which `talk join --into-vault` keeps, and `login.room`, the room's id, which it prints; `--display` is the name the room shows, checked by the transport |
| `part --project P --person N` | on `porelo talk remove` | account and project secrets | `{"removed":"<account>"}`, `{"kept":"<account>","why":"<words>"}`, or `{}` when nothing remains |
| `clear [--project P]` | when a project is cleared from the machine, with `--project`; when the account's vault is cleared, without | the account's secrets, and the project's with `--project` | `removed` |
| `settings` | when a project file is checked | nothing: no secrets, no network | `refused`, `warnings` |
| `admit --project P --machine M` | on `porelo talk admit`, on the machine whose server holds the conversation | account and project secrets | `admission` (what machine `M` needs to reach the conversation) and `secrets` (what it keeps as secrets), answered once, never kept here |
| `dismiss --project P --machine M` | on `porelo talk dismiss` | account and project secrets | `removed`: every account of M taken out of every project's conversation on this server, and M's registration refused; the project only names the server |

- **Every map of secrets is complete** for the verbs that use it: a project's are what `poll` reads and what
  `send` uses for Porelo's own helper answers, a task's are what its `send`, `read` and `receipt` read, and the
  account's go only to lifecycle verbs, including `join` and `part`, never to `poll` or `send`.
- **`setup` is run again and again** and keeps what exists. A server whose first account exists while Porelo holds
  no account secrets is refused (78), not guessed around.
- **`reaches`** is every host the project's messages reach; Porelo lets the transport reach those and nothing else.
  For an offline project Porelo adds `--loopback-only` to `setup`, `enroll`, `join` and `part`, and the transport refuses
  (78) before it contacts anything but loopback.
- **`--machine NAME`** is added to `setup`, `enroll`, `retire`, `join` and `clear` for a transport that `takes`
  it; `admit` and `dismiss` carry the other machine's name instead, and this machine's is never added. The transport keeps the name it first used, so a renamed machine keeps its accounts, and refuses (78) a name
  another machine already holds.
- **`part` never receives `--machine`**, even when the transport announces it. A successful part (exit 0)
  removes the person from Porelo's members list, including when the account stays for the reason in `why`,
  or nothing remains. A failure leaves the list unchanged: 75 temporary, 77 refused, 78 configuration
  missing, with the transport's own words. If `lifecycle` does not list `part`, removal is refused (69)
  naming the transport, without running a lifecycle verb.
- **`"admitted": false`** from `setup`, exiting 0 with its `account` secrets, means a person still has to let this
  machine in; Porelo starts no task of the project until it is true, and absent means true.
- A verb a transport does not list is never run.

Porelo polls each conversation once per pass with the project's secrets, hands what arrived to every task of that
project on this machine but the one that posted it, drops what no task here is to read, runs `read` with a task's secrets once its agent has moved a
message into `inbox/cur`, and asks `receipt` about what a task sent, by the recipient's address.

With `--persons`, a person's words arrive unsigned as `<time>--<scheme>-person-<id>.json`, holding `sender`
(the person's account), `body`, `to` (the accounts mentioned), and optionally `eventId` and `direct`. The sender
must be somebody who joined the project's conversation. A room message with an empty `to` reaches every task.
When the line names only the room's helper accounts, the transport instead includes `"helpers": true` and an
empty `to`: exactly the JSON boolean `true` means no task reads it. Any other value, or no field, keeps the
ordinary delivery behavior.

Porelo answers such a line itself, once, through the transport's `send`, using the project's secrets and
conversation. It signs a message from `porelo` to the person's account with the normal host message key and
passes this line as `--shown`, mentioning the person with `--mention`, when the transport announces those
options: "This is Porelo's technical account; it passes your messages on to the tasks and does not answer
itself. Mention a task to reach it." A failed answer is reported and never queued or retried; the person file
and the answer's files are removed either way. The transport's poll must omit its own relay's events so an
answer never returns to the tasks.
