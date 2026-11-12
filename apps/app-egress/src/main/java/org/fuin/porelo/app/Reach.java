package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.fuin.porelo.core.project.Limits;
import org.fuin.porelo.gate.GitHttpServer;
import org.fuin.porelo.shield.NftRuleset;
import org.fuin.porelo.supervisor.RepositoryProxy;
import org.fuin.porelo.supervisor.VaultProxy;
import org.fuin.porelo.wire.GrantedNames;
import org.jspecify.annotations.Nullable;

/**
 * What a task on this machine can reach, hold, spend and produce, and what bounds each.
 *
 * <p>The rows are the ones {@code doc/reach.md} states, in its words, with every number read off
 * the constant the code enforces; a test holds each row to the page, so a client of the daemon and
 * an operator reading the page are told the same thing. A task's own answer adds what its run was
 * widened or narrowed to.
 */
public final class Reach {

    /** Which of the four questions a row answers. */
    public enum Part {
        /** What a task can connect to. */
        REACH,
        /** What a task has in its hands. */
        HOLD,
        /** What a task can use up. */
        SPEND,
        /** What a task can leave behind. */
        PRODUCE
    }

    /** What kind of guard a row is. */
    public enum Kind {
        /** Holds even against an agent that tries to get round it. */
        CONTROL,
        /** Stops the ordinary mistake and nothing more. */
        ACCIDENT_CATCHER,
        /** A path left open, named so nobody counts it as contained. */
        NOT_BOUNDED
    }

    /**
     * One row: a field added here is a field added to {@code ReachRow} in the contract.
     *
     * @param part Which question it answers.
     * @param subject What is bounded, as the page names it.
     * @param bound The bound, as the page states it, Markdown included.
     * @param kind What kind of guard it is.
     * @param machine What this machine's own setting adds, or {@code null} where it has none.
     * @param task The task's own bound where it differs from the machine's, or {@code null}.
     */
    public record Row(
            Part part,
            String subject,
            String bound,
            Kind kind,
            @Nullable String machine,
            @Nullable String task) {

        private Row(Part part, String subject, String bound, Kind kind) {
            this(part, subject, bound, kind, null, null);
        }

        private Row withMachine(@Nullable String value) {
            return new Row(part, subject, bound, kind, value, task);
        }

        private Row withTask(@Nullable String value) {
            return new Row(part, subject, bound, kind, machine, value);
        }
    }

    private static final String UNDECLARED = "A name the project did not declare";

    private static final String DECLARED = "A declared host";

    private static final String UNASKED = "A reach for something undeclared";

    private static final String TRUST = "Additional TLS trust";

    private final PoreloContext context;

    /**
     * Constructor.
     *
     * @param context The machine.
     */
    public Reach(PoreloContext context) {
        this.context = context;
    }

    /**
     * Returns every row with this machine's values.
     *
     * @return The rows, in the page's order: its tables' rows, then the paths it leaves open.
     */
    public List<Row> machine() {
        final String authority = authority();
        final List<Row> rows = new ArrayList<>();
        for (final Row row : rows()) {
            rows.add(TRUST.equals(row.subject()) ? row.withMachine(authority) : row);
        }
        return List.copyOf(rows);
    }

    /**
     * Returns what this machine's authority file adds, by the rule a start applies to it.
     *
     * @return The machine's value of the trust row, or {@code null} without a file.
     */
    private @Nullable String authority() {
        final Path file = context.paths().taskCaCertificates();
        boolean accepted;
        try {
            accepted =
                    TaskCertificates.standing(
                                    Files.readAttributes(
                                            file,
                                            PosixFileAttributes.class,
                                            LinkOption.NOFOLLOW_LINKS))
                            == TaskCertificates.Standing.ACCEPTED;
        } catch (NoSuchFileException ex) {
            return null;
        } catch (IOException ex) {
            // A start cannot read it either, and refuses.
            accepted = false;
        }
        return accepted
                ? "This machine's `"
                        + TaskCertificates.FILE
                        + "` holds an authority, copied into each task it starts"
                : "This machine's `"
                        + TaskCertificates.FILE
                        + "` is there, but a task start is refused until it is a regular file"
                        + " owned by this account alone, mode 600";
    }

    /**
     * Returns every row for one task, with its own bound where its run was widened or narrowed.
     *
     * @param container The task's container name.
     * @return The rows, or empty when there is no such task.
     */
    public Optional<List<Row>> of(String container) {
        final TaskInventory.Task task =
                new TaskInventory(context)
                        .tasks().stream()
                                .filter(candidate -> candidate.name().equals(container))
                                .findFirst()
                                .orElse(null);
        if (task == null) {
            return Optional.empty();
        }
        final Path runtime = context.paths().tasks().containerState(container);
        // The saved copy when a restart emptied the runtime directory: its grants and its authority
        // are put back at the next start, as TaskInventory reads its agent and mode.
        final Path state =
                !task.running()
                                && !Files.isDirectory(runtime)
                                && new TaskState(context).saved(container)
                        ? context.paths().tasks().taskRecord(container)
                        : runtime;
        // What WidenTask granted and NarrowTask has not taken back: the run's own, never the
        // file's.
        final List<String> granted = GrantedNames.all(state);
        final String names = and(granted.stream().map(name -> "`" + name + "`").toList());
        final List<Row> rows = new ArrayList<>();
        for (final Row row : machine()) {
            rows.add(
                    switch (row.subject()) {
                        case UNDECLARED ->
                                granted.isEmpty()
                                        ? row
                                        : row.withTask(
                                                row.bound()
                                                        + ", except what this run was granted: "
                                                        + names);
                        case DECLARED ->
                                granted.isEmpty()
                                        ? row
                                        : row.withTask(
                                                row.bound()
                                                        + "; this run was also granted "
                                                        + names);
                        case UNASKED -> row.withTask(clearance(task.clearance()));
                        case TRUST ->
                                Files.isRegularFile(state.resolve(TaskCertificates.FILE))
                                        ? row.withTask(
                                                "This task holds the host authority from `"
                                                        + TaskCertificates.FILE
                                                        + "`, read-only")
                                        : row;
                        default -> row;
                    });
        }
        return Optional.of(List.copyOf(rows));
    }

    /**
     * Returns what a task's clearance mode makes of an undeclared reach.
     *
     * @param mode prompt, allow, deny or off, or {@code null} when none was recorded.
     * @return The task's bound, or {@code null} where it asks as the machine does.
     */
    private static @Nullable String clearance(@Nullable String mode) {
        if (mode == null) {
            return null;
        }
        return switch (mode) {
            case "allow" ->
                    "Let through without asking: this task's clearance is `allow`, so nobody is"
                            + " asked about an undeclared reach";
            case "deny" -> "Blocked, and nobody is asked: this task's clearance is `deny`";
            case "off" ->
                    "Blocked, and nobody is asked: this task's clearance is `off`, so it runs no"
                            + " watcher";
            default -> null;
        };
    }

    /** The page's rows with the code's numbers; a change here changes {@code doc/reach.md}. */
    private static List<Row> rows() {
        return List.of(
                new Row(
                        Part.REACH,
                        UNDECLARED,
                        "Does not resolve through the task's resolver",
                        Kind.CONTROL),
                new Row(
                        Part.REACH,
                        DECLARED,
                        "Reachable on ports "
                                + and(
                                        NftRuleset.DEFAULT_PORTS.stream()
                                                .map(String::valueOf)
                                                .toList())
                                + " only",
                        Kind.CONTROL),
                new Row(
                        Part.REACH,
                        "The upstream",
                        "Never reached by a task, in any class: an online task's gate fetches from"
                                + " it and passes the task's own branch on, from the host; no port"
                                + " to it is open in a container",
                        Kind.CONTROL),
                new Row(
                        Part.REACH,
                        "An empty `egress` section",
                        "Deny, never a default that widens with a release",
                        Kind.CONTROL),
                new Row(
                        Part.REACH,
                        "A `project.yml` or an adapter's answer about a checkout",
                        "Opens nothing and chooses no package until a person says yes at a"
                                + " terminal to that text, read once; a start reads this machine's"
                                + " copy of it, and a change in the checkout asks again; an"
                                + " organization's project takes its signed file. A repository's"
                                + " `porelo.yml` is not part of that question",
                        Kind.CONTROL),
                new Row(
                        Part.REACH,
                        UNASKED,
                        "Blocked, and raises a clearance prompt: the closest thing on the machine"
                                + " to a hijack alarm",
                        Kind.CONTROL),
                new Row(
                        Part.REACH,
                        "The git gate",
                        "One address and one port on this machine, never \"the machine\"",
                        Kind.CONTROL),
                new Row(
                        Part.REACH,
                        "A declared `egress.host_services` name",
                        "Reaches the host's loopback on TCP "
                                + NftRuleset.HOST_SERVICE_PORT
                                + " only, beside the gate's port, including offline; the gate's"
                                + " fallback address is used outside pasta",
                        Kind.CONTROL),
                new Row(
                        Part.REACH,
                        "Another task",
                        "No path: separate containers, no shared workspace. Messages between tasks"
                                + " go through the filter and the peer table, both on the host",
                        Kind.CONTROL),
                new Row(
                        Part.REACH,
                        "The host's files",
                        "Nothing outside what Porelo itself mounts or writes",
                        Kind.CONTROL),
                new Row(
                        Part.HOLD,
                        "The host vault share",
                        "Kept by the daemon in locked native memory until lock, daemon exit or"
                                + " reboot; never mounted into a task. Host helpers request"
                                + " temporary copies over the account's owner-only socket",
                        Kind.CONTROL),
                new Row(
                        Part.HOLD,
                        "The provider credential",
                        "Never in the container. The vault holds it; the task gets a phantom"
                                + " token, worthless anywhere else, that dies with the task",
                        Kind.CONTROL),
                new Row(
                        Part.HOLD,
                        "Credential headers from the task",
                        and(
                                        VaultProxy.CREDENTIAL_HEADERS.stream()
                                                .sorted()
                                                .map(header -> "`" + header + "`")
                                                .toList())
                                + " are dropped before anything is forwarded",
                        Kind.CONTROL),
                new Row(
                        Part.HOLD,
                        "A credential in the provider's answer",
                        "The first "
                                + VaultProxy.PEEK / 1024
                                + " KB of every answer are examined for `access_token`,"
                                + " `refresh_token` and `id_token` as JSON fields, before any of it"
                                + " is handed over; the rest is examined as it streams, and an"
                                + " answer that carries one later is cut off there. An answer with"
                                + " no type passes only as JSON or as events whose every line is a"
                                + " field and whose every `data:` is JSON, and is withheld or cut"
                                + " off otherwise; a withheld answer is a `400` naming the"
                                + " provider's status, which a client does not retry, or a `502`"
                                + " when the provider itself answered `5xx` or `429`",
                        Kind.CONTROL),
                new Row(
                        Part.HOLD,
                        "A request for a token of its own",
                        "A "
                                + String.join(
                                        " or ",
                                        VaultProxy.REFUSED_GRANTS.stream()
                                                .map(grant -> "`" + grant + "`")
                                                .toList())
                                + " grant is refused, in the query and in the whole body decoded -"
                                + " gzip, deflate or zstd - and a body coded otherwise, coded"
                                + " twice, beyond the limit or not decoding is refused rather than"
                                + " forwarded unread",
                        Kind.CONTROL),
                new Row(
                        Part.HOLD,
                        "A package repository's credential",
                        "Never in the container, nor any token for it: the task's repository"
                                + " broker adds it on the host to each request, and the task reads"
                                + " the repository at `127.0.0.1:"
                                + TaskWiring.REPOSITORY_PORT
                                + "/<name>/`",
                        Kind.CONTROL),
                new Row(
                        Part.HOLD,
                        "A request to a package repository",
                        and(
                                        RepositoryProxy.METHODS.stream()
                                                .sorted()
                                                .map(method -> "`" + method + "`")
                                                .toList())
                                + " only, under the prefix `tool_repositories` declares: a path"
                                + " with a `.` or `..` segment, plain or encoded, a path"
                                + " parameter, an encoded slash, backslash or percent, or an empty"
                                + " segment is refused before the repository; a range is never"
                                + " asked for; a redirect is followed while it stays"
                                + " under the prefix, at most "
                                + RepositoryProxy.REDIRECTS
                                + " times, and answered `502` otherwise; every request is logged"
                                + " without its headers",
                        Kind.CONTROL),
                new Row(
                        Part.HOLD,
                        "A package repository's answer",
                        "Text, and whatever is typed JSON, YAML or XML, is examined as it"
                                + " streams for the credential, as stored and Base64-encoded, plain,"
                                + " JSON-escaped or percent-encoded, and cut off before it; text the"
                                + " repository compresses, or sends in a charset other than UTF-8,"
                                + " US-ASCII or ISO-8859-1, is withheld. Only the headers a build"
                                + " tool reads come back. A binary answer, such as a jar, passes"
                                + " unread: the prefix the person declares is the bound",
                        Kind.CONTROL),
                new Row(
                        Part.HOLD,
                        "A key for the upstream",
                        "Never in the container, nor a socket that answers with it, in any class:"
                                + " the host lends it to the gate",
                        Kind.CONTROL),
                new Row(
                        Part.HOLD,
                        "A guarded task's builds on a host",
                        "When the host names them for the project, the host reads its own forge"
                                + " and hands only build verdicts and failed-job logs into"
                                + " `/porelo/files`. The forge credential stays on the host; this"
                                + " opens no task egress",
                        Kind.CONTROL),
                new Row(
                        Part.HOLD,
                        "The workspace's `.git/hooks` and `.git/config`",
                        "Stay inside the container, where no host-side `git` runs them",
                        Kind.CONTROL),
                new Row(
                        Part.HOLD,
                        TRUST,
                        "Only the host authority explicitly placed in the account's owner-only `"
                                + TaskCertificates.FILE
                                + "` is copied into a task, read-only; the host clients'"
                                + " `ca-certificates.pem` stays on the host. The system store and"
                                + " Node gain that trust; egress rules still bound the"
                                + " destinations",
                        Kind.CONTROL),
                new Row(
                        Part.SPEND,
                        "A task's CPU",
                        "**"
                                + Limits.DEFAULT_CPUS
                                + " CPUs** by default, replaced by `limits.cpus` or removed by `"
                                + Limits.NONE
                                + "`; the quota throttles CPU time. `OMP_NUM_THREADS` hints the"
                                + " limit to `nproc`, rounded down to at least one unless the image"
                                + " or project sets it. Image builds pass the same hint as a build"
                                + " argument to their `RUN` steps; `getconf` and `sysconf` still"
                                + " read the host's cores",
                        Kind.CONTROL),
                new Row(
                        Part.SPEND,
                        "Its gate on the host",
                        GitHttpServer.AT_ONCE
                                + " requests at once, `503` beyond; a push spooled to disk, never"
                                + " held; the gate and its `git` in a scope of 1 GiB memory, 256"
                                + " tasks and half the default CPU weight",
                        Kind.CONTROL),
                new Row(
                        Part.PRODUCE,
                        "Work, in `guarded`",
                        "Leaves only when a person approves it at the gate; nothing the agent can"
                                + " reach calls approve. On the host's forge that approval opens or"
                                + " reuses a pull request as the task's account. An amendment"
                                + " replaces only the gate's recorded last push for that task, with"
                                + " a lease on the live head",
                        Kind.CONTROL),
                new Row(
                        Part.PRODUCE,
                        "Work, in `offline`",
                        "No Git forwarding: stays in the host's Git mirror until a person exports a"
                                + " bundle or fetches it; model calls still reach the model provider"
                                + " through the broker, and declared `egress.host_services` reach"
                                + " the host on TCP "
                                + NftRuleset.HOST_SERVICE_PORT,
                        Kind.CONTROL),
                new Row(
                        Part.PRODUCE,
                        "What the agent installed",
                        "Counted with `podman diff` before removal, and dies with the container",
                        Kind.CONTROL),
                new Row(
                        Part.PRODUCE,
                        "What it reached for",
                        "Journaled per task, and kept after the task",
                        Kind.CONTROL),
                new Row(
                        Part.PRODUCE,
                        "A declaration of how work ended over MCP",
                        "Only the task bound to that socket may declare DONE or BLOCKED, never a child or"
                                + " another project's task. The endpoint must allow `Done`; the daemon keeps its"
                                + " outcome, time and a reason of at most "
                                + TaskDone.MOST_WHY
                                + " UTF-8 bytes, one line with no control characters, in its private durable"
                                + " record outside every task mount. BLOCKED requires a non-blank reason. The"
                                + " reason reaches the task's reader, never another agent's prompt; task"
                                + " endpoints without Logs or Tail receive no `ended` in List or Watch. A later"
                                + " start clears the declaration",
                        Kind.CONTROL),
                new Row(
                        Part.PRODUCE,
                        "The file a task hands back",
                        "Only for a task started with `--result`, and only the one file of that name in"
                                + " `/run/porelo/result/`: moved out of the agent's reach before it is looked"
                                + " at, never through a link, and kept only when it is a regular file of at"
                                + " most "
                                + TaskResult.MOST
                                + " bytes of UTF-8 text. The host keeps it, owner-only and outside every task"
                                + " mount, until the next start of the task's name, and answers it only over"
                                + " the person's socket",
                        Kind.CONTROL),
                new Row(
                        Part.PRODUCE,
                        "`gate protect`'s hook",
                        "Fires on a first, an ordinary and a force push. Four things go round it:"
                                + " `--no-verify`, a `core.hooksPath` pointing elsewhere, a fresh"
                                + " clone, and a client built on a git library",
                        Kind.ACCIDENT_CATCHER),
                new Row(
                        Part.PRODUCE,
                        "`gate check`'s author test",
                        "The author can be spoofed",
                        Kind.ACCIDENT_CATCHER),
                new Row(
                        Part.REACH,
                        "The model provider",
                        "Every task has a working route to its provider, with a request body of up"
                                + " to **"
                                + VaultProxy.BODY_LIMIT / (1024 * 1024)
                                + " MB**. Porelo records request metadata but raises no alert.",
                        Kind.NOT_BOUNDED),
                new Row(
                        Part.REACH,
                        "Every declared host is a second such channel",
                        "Data fits in a URL to a forge the work clones from.",
                        Kind.NOT_BOUNDED),
                new Row(
                        Part.REACH,
                        "A DNS query sent past the resolver",
                        "Still gets an answer, though the connection does not.",
                        Kind.NOT_BOUNDED),
                new Row(
                        Part.SPEND,
                        "A credential that cannot be read can still be spent",
                        "The phantom token can do whatever the real credential can at the"
                                + " provider: use tokens, spend money. Parsing is bounded to "
                                + VaultProxy.USAGE_LIMIT / (1024 * 1024)
                                + " MiB per body or event and runs off the relay; a failed reading"
                                + " cannot stop an answer.",
                        Kind.NOT_BOUNDED),
                new Row(
                        Part.PRODUCE,
                        "The work itself",
                        "A plausible but harmful change on the branch the task may write is"
                                + " stopped by nothing above, only by a person reading the patch.",
                        Kind.NOT_BOUNDED));
    }

    /** Joins items as the page lists them: "a", "a and b", "a, b and c". */
    private static String and(List<String> items) {
        if (items.size() < 2) {
            return String.join("", items);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.getLast();
    }
}
