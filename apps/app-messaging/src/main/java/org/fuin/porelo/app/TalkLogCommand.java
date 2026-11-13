package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Shows a task's conversation as it happened: what was written, sent, held, refused, delivered and
 * read, in order.
 *
 * <p>Read from the task's own record, line by line as it was written, so following it is following
 * the conversation. Nothing a message says is shown here - only what happened to it, with whom and
 * when; {@code talk read} shows a message itself. The daemon streams the same as {@code Talk}; this
 * is the terminal's way to the same answer.
 */
@Command(
        name = "log",
        mixinStandardHelpOptions = true,
        description =
                "Shows what happened to a task's messages, in order: written, sent, held, refused, delivered,"
                        + " read - with whom and when, never what they say.")
public class TalkLogCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<task>", description = "Container name of the task.")
    private String container;

    @Option(
            names = "--json",
            description = "Answers one JSON object per record line, without message text.")
    private boolean json;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws Exception {
        // By the container's name or the task's own, as every task command takes it.
        container = TaskTarget.spelled(context, container);
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(container));
        if (!mailbox.exists()) {
            err.println("porelo: " + container + " has no mailbox");
            err.flush();
            return 1;
        }
        final java.util.List<Map<String, Object>> entries = new MessageRecord(mailbox).entries();
        if (json) {
            mailbox.refuseLinks();
            final Set<String> announced = announced(mailbox);
            final List<Path> directories = messageDirectories(mailbox);
            for (final Map<String, Object> entry : entries) {
                out.println(Json.write(jsonEntry(entry, mailbox, directories, announced)));
            }
            out.flush();
            return 0;
        }
        if (entries.isEmpty()) {
            out.println("no message yet");
            out.flush();
            return 0;
        }
        out.printf("%-24s %-12s %-20s %-36s %s%n", "AT", "EVENT", "PEER", "MESSAGE", "DETAIL");
        for (final Map<String, Object> entry : entries) {
            out.printf(
                    "%-24s %-12s %-20s %-36s %s%n",
                    text(entry, "at"),
                    text(entry, "event"),
                    text(entry, "peer"),
                    text(entry, "message"),
                    text(entry, "detail"));
        }
        out.flush();
        return 0;
    }

    private static Set<String> announced(final Mailbox mailbox) throws IOException {
        try {
            return Set.copyOf(
                    Files.readAllLines(
                            mailbox.record().resolve(AgentWake.ANNOUNCED), StandardCharsets.UTF_8));
        } catch (final NoSuchFileException ex) {
            return Set.of();
        }
    }

    private static Map<String, @Nullable Object> jsonEntry(
            final Map<String, Object> entry,
            final Mailbox mailbox,
            final List<Path> directories,
            final Set<String> announced)
            throws IOException {
        final Map<String, @Nullable Object> line = new LinkedHashMap<>();
        for (final String column : List.of("at", "event", "peer", "message", "detail", "id")) {
            line.put(column, entry.getOrDefault(column, ""));
        }
        final String name = String.valueOf(entry.getOrDefault("message", ""));
        final String id = String.valueOf(entry.getOrDefault("id", ""));
        final Audience audience = audience(mailbox, directories, name, id);
        line.put("messageMissing", audience == null);
        line.put("to", audience == null ? null : audience.to());
        line.put("via", audience == null ? null : audience.via());
        line.put("all", audience == null ? null : audience.all());
        line.put("woken", announced.contains(name));
        return line;
    }

    private record Audience(List<String> to, @Nullable String via, boolean all) {}

    private static Audience audience(final Map<?, ?> metadata) throws IOException {
        final Object to = metadata.get("to");
        final List<?> names =
                to instanceof List<?> list ? list : to == null ? List.of() : List.of(to);
        if (!names.stream().allMatch(String.class::isInstance)) {
            throw new IOException("a recorded message has invalid recipient names");
        }
        final Object via = metadata.get("via");
        if (via != null && !"room".equals(via) && !"direct".equals(via)) {
            throw new IOException("a recorded message has an invalid route");
        }
        final Object all = metadata.get("all");
        if (all != null && !(all instanceof Boolean)) {
            throw new IOException("a recorded message has an invalid all flag");
        }
        return new Audience(
                names.stream().map(String.class::cast).toList(),
                via instanceof String route ? route : null,
                Boolean.TRUE.equals(all));
    }

    private static @Nullable Audience audience(
            final Mailbox mailbox, final List<Path> directories, final String name, final String id)
            throws IOException {
        if (name.isEmpty()) {
            return null;
        }
        // The record names a file, never a path into another mailbox or the account's files.
        if (!Path.of(name).getFileName().toString().equals(name)
                || name.equals(".")
                || name.equals("..")) {
            throw new IOException("the message record names a path instead of a file");
        }
        for (final Path directory : directories) {
            final Path file = directory.resolve(name);
            final boolean inbox =
                    directory.equals(mailbox.inboxNew()) || directory.equals(mailbox.inboxCur());
            if (inbox && !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            final byte[] bytes;
            try {
                // In the inbox only what Porelo delivered: anything in the container can write a
                // file there under a recorded name.
                bytes =
                        inbox
                                ? mailbox.readDelivered(file, MessageIntake.MAX_BYTES)
                                : MessageIntake.taskWritten(file);
                if (inbox && bytes == null) {
                    continue;
                }
            } catch (final NoSuchFileException ex) {
                // An agent may have moved a message from new to cur while the log was read.
                continue;
            }
            if (bytes == null) {
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException(
                            "a recorded message is not a regular file within the message size limit");
                }
                continue;
            }
            final Object parsed = Json.parse(new String(bytes, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> document)) {
                throw new IOException("a recorded message is not a JSON object");
            }
            if (!id.isEmpty() && !id.equals(document.get("messageId"))) {
                continue;
            }
            final Object metadata = document.get("metadata");
            if (metadata != null && !(metadata instanceof Map<?, ?>)) {
                throw new IOException("a recorded message has invalid metadata");
            }
            return audience(metadata instanceof Map<?, ?> given ? given : Map.of());
        }
        return null;
    }

    private static List<Path> messageDirectories(final Mailbox mailbox) throws IOException {
        final List<Path> directories =
                new ArrayList<>(
                        List.of(
                                mailbox.inboxNew(),
                                mailbox.inboxCur(),
                                mailbox.sent(),
                                mailbox.hold(),
                                mailbox.incoming(),
                                mailbox.accepted(),
                                mailbox.rejected(),
                                mailbox.error(),
                                mailbox.inbound(),
                                mailbox.person(),
                                mailbox.feedback(),
                                MessageLookup.answers(mailbox),
                                mailbox.checkIncoming(),
                                mailbox.checkAccepted(),
                                mailbox.checkRejected(),
                                mailbox.checkError(),
                                mailbox.checkFeedback()));
        final Path queues = mailbox.root().resolve("queue");
        if (Files.isDirectory(queues, LinkOption.NOFOLLOW_LINKS)) {
            try (var transports = Files.list(queues)) {
                for (final Path transport :
                        transports
                                .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                                .toList()) {
                    directories.add(transport.resolve("active"));
                    directories.add(transport.resolve("deferred"));
                }
            }
        }
        return directories;
    }

    private static String text(final Map<String, Object> entry, final String key) {
        // Escaped: a peer, a file name and a reason come from files a task or a filter wrote.
        final Object value = entry.get(key);
        return value == null || String.valueOf(value).isEmpty()
                ? "-"
                : TalkReadCommand.shown(String.valueOf(value));
    }
}
