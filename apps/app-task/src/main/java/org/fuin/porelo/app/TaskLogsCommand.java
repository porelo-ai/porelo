package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.fuin.porelo.runtime.ContainerName;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Shows what a task's host-side helpers wrote.
 *
 * <p><strong>These are the node's logs, not the container's.</strong> The gate, the credential
 * broker, the relay, the resolver and the clearance watcher all run on this machine; what the agent
 * itself printed went to whoever was attached. So this is where an operator looks when a task
 * started and then did nothing.
 *
 * <p><strong>Why it had to exist.</strong> {@code Logs} and {@code Tail} have been on the contract
 * for a while, so an interface on another machine could read these and a person sitting at the
 * machine could not - they had to know the path under {@code $XDG_RUNTIME_DIR}. That is backwards
 * for a tool whose first audience is at the terminal.
 *
 * <p>Which files exist is asked rather than assumed: a task with no gate has no {@code gate.log},
 * and one run with {@code --clearance off} has no {@code clearance.log}.
 */
@Command(
        name = "logs",
        mixinStandardHelpOptions = true,
        description = "Shows what a task's helpers on this machine wrote.")
public class TaskLogsCommand implements Callable<Integer>, PoreloFactory.ContextAware, Suggests {

    /** How often a followed log is re-read. */
    private static final long FOLLOW_INTERVAL = 500;

    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "TASK",
            description =
                    "Container name, as shown by 'porelo task list', or the task's own. Default: the task"
                            + " started from the checkout you stand in.")
    private @Nullable String container;

    @Parameters(
            index = "1",
            arity = "0..1",
            paramLabel = "LOG",
            description = "Which log. Leave it out to list what this task has.")
    private @Nullable String log;

    @Option(
            names = {"-f", "--follow"},
            description = "Keeps printing as the log grows. Ends with Ctrl-C.")
    private boolean follow;

    @Option(
            names = "--zip",
            arity = "0..1",
            paramLabel = "FILE",
            fallbackValue = "",
            description =
                    "Writes every log of the task, and what Porelo says about it, into one archive readable by"
                            + " you only. Default FILE: porelo-logs-<task>-<UTC time>.zip here. An existing file is refused.")
    private @Nullable String zip;

    @Option(
            names = "--with-agent-output",
            description =
                    "With --zip: also what the agent itself printed (task.log), which may hold confidential"
                            + " data.")
    private boolean withAgentOutput;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    @Override
    public List<String> candidates() {
        // Every task: a stopped one still has its logs, and reading them is most of why somebody
        // looks at a task that is no longer running.
        return TaskCandidates.all(context);
    }

    @Override
    public String candidateLabel() {
        return "tasks";
    }

    @Override
    public Integer call() throws IOException {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        // One word may be the log of the task started here: 'porelo task logs gate.log' in its
        // checkout.
        if (container != null
                && log == null
                && !TaskTarget.spelled(context, container).startsWith("porelo-")) {
            final java.util.List<String> started =
                    TaskTarget.fromCheckout(context, java.nio.file.Path.of("."));
            if (started != null && started.size() == 1) {
                log = container;
                container = null;
            }
        }
        final TaskTarget.Found found =
                TaskTarget.resolve(
                        context, container, java.nio.file.Path.of("."), TaskTarget.console(out));
        if (found.container() == null) {
            err.println("porelo: " + found.refusal());
            if ((Object) this instanceof Suggests suggests) {
                Suggests.offer(err, suggests);
            }
            err.flush();
            return found.code();
        }
        container = found.container();
        if (!ContainerName.isTask(container)) {
            err.println("porelo: '" + container + "' is not a task of this machine");
            Suggests.offer(err, this);
            err.flush();
            return 64;
        }

        final List<TaskInventory.Log> logs = new TaskInventory(context).logs(container);

        // A name of the right shape that names nothing is not a task with no logs. Reported:
        // 'task logs porelo-does-not' answered "either nothing wrote one, or the machine has
        // restarted", which describes a task that exists - about one that never did.
        //
        // Existing OR having logs, not existing alone: a container somebody removed by hand can
        // leave its state directory behind, and those logs are worth reading precisely then.
        final boolean known =
                context.podman().poreloTasks().stream()
                        .anyMatch(task -> task.name().equals(container));
        if (!known
                && new TaskInventory(context)
                        .unrecognized(java.util.List.of())
                        .contains(container)) {
            err.println("porelo: " + TaskInventory.unrecognizedNote(container));
            err.flush();
            return 69;
        }
        if (!known && logs.isEmpty()) {
            err.println("porelo: there is no task called " + container);
            Suggests.offer(err, this);
            err.flush();
            return 69;
        }

        if (zip != null) {
            return archive(out, err, container, logs);
        }
        if (withAgentOutput) {
            err.println("porelo: --with-agent-output goes with --zip");
            err.flush();
            return 64;
        }

        if (log == null) {
            if (logs.isEmpty()) {
                // Two different reasons, and neither is an error: a task that started nothing
                // worth logging, or one whose state directory went with a restart.
                out.println(
                        "no logs for "
                                + container
                                + " - either nothing wrote one, or the"
                                + " machine has restarted since it ran");
                out.flush();
                return 0;
            }
            out.printf("%-18s %10s  %s%n", "LOG", "BYTES", "CHANGED");
            for (final TaskInventory.Log each : logs) {
                out.printf("%-18s %10d  %s%n", each.name(), each.bytes(), each.at());
                // Only the names that do not say what they hold. The same sentence the interface
                // gets, from the same place - two sources for one line would disagree eventually.
                if (each.what() != null) {
                    out.printf("%-18s %s%n", "", each.what());
                }
            }
            out.println();
            out.println("Read one with 'porelo task logs " + container + " <LOG>'.");
            out.flush();
            return 0;
        }

        // Only a name this task actually has. Not a path check with an exception for '..' - the
        // set of legal answers is known, so anything outside it is refused without reasoning
        // about traversal at all.
        if (logs.stream().noneMatch(each -> each.name().equals(log))) {
            err.println("porelo: " + container + " has no log called " + log);
            if (!logs.isEmpty()) {
                err.println(
                        "porelo: it has: "
                                + String.join(
                                        ", ", logs.stream().map(TaskInventory.Log::name).toList()));
            }
            err.flush();
            return 69;
        }

        final Path file = context.paths().tasks().containerState(container).resolve(log);
        long position = print(file, 0, out);
        while (follow) {
            try {
                Thread.sleep(FOLLOW_INTERVAL);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return 0;
            }
            position = print(file, position, out);
        }
        return 0;
    }

    /**
     * Prints what the file has gained, and returns where to carry on from.
     *
     * <p>By byte position rather than by line count: a log an agent is writing to grows between two
     * reads, and counting lines would reprint whatever arrived in between.
     *
     * @param file The log.
     * @param from Where the last read stopped.
     * @param out Where to write.
     * @return The new position.
     * @throws IOException If the log cannot be read.
     */
    static long print(Path file, long from, PrintWriter out) throws IOException {
        if (!Files.isRegularFile(file)) {
            return from;
        }
        final long size = Files.size(file);
        if (size < from) {
            // Shorter than it was: something truncated or replaced it, so start again rather than
            // seek past the end and print nothing for ever.
            return print(file, 0, out);
        }
        if (size == from) {
            // Not a correctness guard, and no test can tell it from its absence: reading zero
            // bytes prints nothing either way. It is here so that following an idle log does not
            // open and close a channel twice a second for as long as somebody watches it.
            return from;
        }
        try (var channel =
                java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ)) {
            channel.position(from);
            // In blocks, never the whole rest at once: a log past 2 GiB did not fit one buffer. A
            // character cut at
            // a block's end is carried into the next; one cut at the file's end is printed as
            // unreadable, as before.
            final java.nio.charset.CharsetDecoder decoder =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
            final java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(64 * 1024);
            final java.nio.CharBuffer chars = java.nio.CharBuffer.allocate(64 * 1024);
            long read = 0;
            while (read < size - from) {
                bytes.limit(
                        (int) Math.min(bytes.capacity(), bytes.position() + (size - from - read)));
                final int got = channel.read(bytes);
                if (got <= 0) {
                    break;
                }
                read += got;
                bytes.flip();
                decoder.decode(bytes, chars, false);
                bytes.compact();
                chars.flip();
                out.append(chars);
                chars.clear();
            }
            bytes.flip();
            decoder.decode(bytes, chars, true);
            decoder.flush(chars);
            chars.flip();
            out.append(chars);
            out.flush();
            return from + read;
        }
    }

    /**
     * Writes the archive {@code --zip} asks for.
     *
     * @param out Where its path is said.
     * @param err Where why not is said.
     * @param container The task.
     * @param logs The task's logs.
     * @return 0 when it was written.
     */
    private int archive(
            final PrintWriter out,
            final PrintWriter err,
            final String container,
            final List<TaskInventory.Log> logs) {
        final String name = container.substring("porelo-".length());
        final java.nio.file.Path target =
                zip == null || zip.isBlank()
                        ? java.nio.file.Path.of(
                                TaskLogArchive.defaultName(name, java.time.Instant.now()))
                        : java.nio.file.Path.of(zip);
        try {
            final List<String> kept = archive(context, container, target, withAgentOutput);
            if (logs.isEmpty()) {
                // Not an error: a restart took them, or nothing wrote one. What Porelo says about
                // the task is in it.
                out.println(
                        "no logs for "
                                + container
                                + " - either nothing wrote one, or the machine has restarted"
                                + " since it ran; the archive holds what Porelo says about the task");
            }
            out.println(
                    "written   "
                            + target.toAbsolutePath()
                            + " ("
                            + kept.size()
                            + " files, readable by you only)");
            out.flush();
            return 0;
        } catch (final java.nio.file.FileAlreadyExistsException ex) {
            err.println(
                    "porelo: "
                            + target
                            + " is already there; nothing was written. Name another file.");
        } catch (final IOException ex) {
            err.println("porelo: the archive could not be written: " + ex.getMessage());
        }
        err.flush();
        return 70;
    }

    /**
     * Writes a task's archive: its logs and what Porelo says about it. The CLI and the daemon both
     * write it here.
     *
     * @param context The machine.
     * @param container The task.
     * @param target Where; must not exist yet.
     * @param withAgentOutput Whether what the agent printed goes in.
     * @return The file names in the archive.
     * @throws IOException If it cannot be written, {@link java.nio.file.FileAlreadyExistsException}
     *     for a target that is there.
     */
    public static List<String> archive(
            final PoreloContext context,
            final String container,
            final java.nio.file.Path target,
            final boolean withAgentOutput)
            throws IOException {
        final java.nio.file.Path state = context.paths().tasks().containerState(container);
        return TaskLogArchive.write(
                target,
                container.substring("porelo-".length()),
                new TaskInventory(context)
                        .logs(container).stream().map(each -> state.resolve(each.name())).toList(),
                said(context, container),
                withAgentOutput);
    }

    /**
     * Returns what Porelo says about a task, rendered by the commands that say it, in this process.
     *
     * @param context The machine.
     * @param container The task.
     * @return File name to text, in order.
     */
    static java.util.Map<String, String> said(final PoreloContext context, final String container) {
        final java.util.Map<String, String> said = new java.util.LinkedHashMap<>();
        said.put("status.txt", rendered(context, new TaskStatusCommand(), container));
        said.put("files.txt", rendered(context, new TaskFilesCommand(), container));
        said.put("talk.txt", rendered(context, new TalkLogCommand(), container));
        return said;
    }

    private static String rendered(
            final PoreloContext context, final Object command, final String container) {
        final java.io.StringWriter text = new java.io.StringWriter();
        final PrintWriter writer = new PrintWriter(text, true);
        // The same code a person runs, so the archive cannot say something the command would not.
        // Its refusal is kept
        // as well: a stopped task has no answer from its container, and an empty file would hide
        // why.
        final int code =
                new picocli.CommandLine(command, new PoreloFactory(context))
                        .setOut(writer)
                        .setErr(writer)
                        .execute(container);
        writer.flush();
        return text + (code == 0 ? "" : "(exit " + code + ")\n");
    }
}
