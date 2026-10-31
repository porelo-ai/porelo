package org.fuin.porelo.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * Starts a task on a host from the person's own machine, certifying its key there.
 *
 * <p>The host's Porelo runs the start with {@code --certify-stdin}: it makes the task's key, asks
 * on one line of its output for a certificate ({@link StdinCertifier}), and reads it from its
 * input. Here the person's own agent signs it - the agent is never forwarded - and the rest of the
 * host's output is shown as it comes. What is certified is checked first: an Ed25519 key, the one
 * principal of the task asked for, and at most {@link TaskCertification#VALIDITY}.
 */
final class RemoteStart {

    /** How an SSH connection to a host is opened for a start: no forwarding, no shared master. */
    static final List<String> SSH =
            List.of(
                    "ssh",
                    "-T",
                    "-o",
                    "ForwardAgent=no",
                    "-o",
                    "ClearAllForwardings=yes",
                    "-o",
                    "ControlPath=none",
                    "-o",
                    "ControlMaster=no",
                    "-o",
                    "StrictHostKeyChecking=yes",
                    "-o",
                    "BatchMode=yes",
                    // ClearAllForwardings leaves X forwarding to the person's ssh configuration.
                    "-o",
                    "ForwardX11=no");

    /**
     * What {@code --host} takes: neither part may begin as one of ssh's options, and the host is a
     * name or an address as ssh takes it on its command line, without brackets.
     */
    static final String HOST = "[A-Za-z0-9][A-Za-z0-9._-]*@[A-Za-z0-9][A-Za-z0-9.:-]*";

    /**
     * What a project's name may be, as the project file enforces it: ssh-keygen reads a comma in a
     * principal as the end of one principal and the start of another.
     */
    static final String PROJECT = "[a-z0-9][a-z0-9-]{0,62}";

    /** The longest line of the host's output shown; a longer one is cut there. */
    private static final int MAX_LINE = 16 * 1024;

    private static final Pattern ED25519 = Pattern.compile("ssh-ed25519 [A-Za-z0-9+/]+=*");

    private static final String REQUEST = "{\"" + StdinCertifier.REQUEST + "\":";

    private final List<String> connection;

    private final Path agent;

    /**
     * Constructor.
     *
     * @param connection The command that reaches the host, the remote command left out.
     * @param agent The person's agent.
     */
    RemoteStart(List<String> connection, Path agent) {
        this.connection = List.copyOf(connection);
        this.agent = agent;
    }

    /**
     * Runs a start on the host.
     *
     * @param arguments The host's Porelo arguments, {@code task run ...}.
     * @param project The task's project, which the certificate must name.
     * @param task The task's name, which the certificate must name.
     * @param out Where the host's output goes.
     * @param err Where its errors and refusals go.
     * @return The host's exit code, or 65 when its request was not certified.
     * @throws IOException If the connection cannot be run.
     */
    int run(List<String> arguments, String project, String task, PrintWriter out, PrintWriter err)
            throws IOException {
        final List<String> command = new ArrayList<>(connection);
        command.add(remoteCommand(arguments));
        final Process process = new ProcessBuilder(command).start();
        final Thread errors =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try (BufferedReader said =
                                            new BufferedReader(
                                                    new InputStreamReader(
                                                            process.getErrorStream(),
                                                            StandardCharsets.UTF_8))) {
                                        String line;
                                        while ((line = line(said)) != null) {
                                            synchronized (err) {
                                                err.println(printable(line));
                                                err.flush();
                                            }
                                        }
                                    } catch (IOException ex) {
                                        // The connection is gone; its exit code says so.
                                    }
                                });
        boolean refused = false;
        final Writer answer =
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        try (BufferedReader lines =
                new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            boolean asked = false;
            String line;
            while ((line = line(lines)) != null) {
                if (asked || !line.startsWith(REQUEST)) {
                    out.println(printable(line));
                    out.flush();
                    continue;
                }
                // Asked once: a request that does not read is refused, never shown, since the
                // host waits for an answer to it.
                asked = true;
                String certificate = "";
                try {
                    certificate = certify(request(line), project, task);
                } catch (TaskCertification.Refused ex) {
                    refused = true;
                    synchronized (err) {
                        err.println("porelo: not certified: " + ex.getMessage());
                        err.flush();
                    }
                }
                try {
                    answer.write(certificate + "\n");
                    answer.flush();
                } catch (IOException ex) {
                    // The host has gone; its exit code says so, and a refusal stays one.
                }
            }
        } finally {
            try {
                answer.close();
            } catch (IOException ex) {
                // As above: the host closed its input first.
            }
        }
        try {
            final int code = process.waitFor();
            errors.join();
            return refused ? 65 : code;
        } catch (InterruptedException ex) {
            process.destroy();
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    /**
     * The next line of the host's output, cut at {@link #MAX_LINE}, or {@code null} at its end. A
     * host that never ends a line holds no more of this machine's memory than one line's worth.
     */
    private static @Nullable String line(BufferedReader in) throws IOException {
        final StringBuilder line = new StringBuilder();
        boolean cut = false;
        int read;
        while ((read = in.read()) != -1 && read != '\n') {
            if (line.length() < MAX_LINE) {
                line.append((char) read);
            } else {
                cut = true;
            }
        }
        if (read == -1 && line.isEmpty()) {
            return null;
        }
        // A cut between the two halves of a character would leave half of one.
        if (cut && Character.isHighSurrogate(line.charAt(line.length() - 1))) {
            line.setLength(line.length() - 1);
        }
        return line.toString();
    }

    /** The host's request, read from the line that begins as one. */
    private static Map<?, ?> request(String line) {
        try {
            if (Json.parse(line) instanceof Map<?, ?> map
                    && map.get(StdinCertifier.REQUEST) instanceof Map<?, ?> request) {
                return request;
            }
        } catch (RuntimeException ex) {
            // Below: what does not read is no request this certifies.
        }
        throw new TaskCertification.Refused("the host's request does not read");
    }

    /** Checks what the host asks to have certified, and has the agent certify it. */
    private String certify(Map<?, ?> request, String project, String task) {
        final String key = String.valueOf(request.get("key"));
        final String principal = String.valueOf(request.get("principal"));
        final String keyId = String.valueOf(request.get("key_id"));
        final Instant before;
        try {
            before = Instant.parse(String.valueOf(request.get("valid_before")));
        } catch (java.time.format.DateTimeParseException ex) {
            throw new TaskCertification.Refused("the host asked for no valid end");
        }
        if (!ED25519.matcher(key).matches()) {
            throw new TaskCertification.Refused(
                    "the host asked to certify a key that is not Ed25519");
        }
        // The project too: a host asking for another project's task would be certified for it.
        // Both are checked by name, since ssh-keygen reads a comma as a second principal.
        if (!project.matches(PROJECT)
                || org.fuin.porelo.runtime.ContainerName.refusal(project, task).isPresent()
                || !principal.equals("task:" + project + ":" + task)) {
            throw new TaskCertification.Refused(
                    "the host asked for "
                            + printable(principal)
                            + ", not for task "
                            + task
                            + " of "
                            + printable(project));
        }
        if (!before.isAfter(Instant.now())) {
            throw new TaskCertification.Refused("the host asked for an end already past");
        }
        if (before.isAfter(
                Instant.now().plus(TaskCertification.VALIDITY).plus(Duration.ofMinutes(5)))) {
            throw new TaskCertification.Refused(
                    "the host asked for a certificate longer than "
                            + TaskCertification.VALIDITY.toDays()
                            + " days");
        }
        // The host names the keys its project lets certify; none named is no leave to sign with
        // whatever key the agent holds first.
        final List<String> certifiers = new ArrayList<>();
        if (request.get("certifiers") instanceof List<?> listed) {
            for (final Object each : listed) {
                final String certifier = String.valueOf(each);
                if (!ED25519.matcher(certifier).matches()) {
                    throw new TaskCertification.Refused(
                            "the host named a certifier that is not an Ed25519 key");
                }
                certifiers.add(certifier);
            }
        }
        // A task without a project is certified by the agent's first Ed25519 key, as at a start
        // here.
        final boolean projectless = TaskCertification.NO_PROJECT.equals(project);
        if (certifiers.isEmpty() && !projectless) {
            throw new TaskCertification.Refused(
                    "the host named no key of the project's access file to certify with");
        }
        final String held =
                TaskCertifying.agentHolds(agent, certifiers.isEmpty() ? null : certifiers);
        if (held == null) {
            throw new TaskCertification.Refused(
                    "your agent holds no key the project's access file lets certify (ssh-add it)");
        }
        return new AgentCertifier(held, agent).certify(key, principal, printable(keyId), before);
    }

    /**
     * The person's agent as the context names it, or {@code null} when none is named or the name is
     * no path.
     */
    static @Nullable Path agent(PoreloContext context) {
        final String socket = context.environment().apply("SSH_AUTH_SOCK");
        if (socket == null || socket.isBlank()) {
            return null;
        }
        try {
            return Path.of(socket);
        } catch (java.nio.file.InvalidPathException ex) {
            return null;
        }
    }

    /** One word per argument, each quoted for the host's shell, which ssh hands it to. */
    static String remoteCommand(List<String> arguments) {
        final StringBuilder command = new StringBuilder("porelo");
        for (final String argument : arguments) {
            command.append(" '").append(argument.replace("'", "'\\''")).append('\'');
        }
        return command.toString();
    }

    private static String printable(String text) {
        return text.replaceAll("[\\p{Cc}\\p{Cf}\\p{Cs}\\p{Zl}\\p{Zp}]", "?");
    }
}
