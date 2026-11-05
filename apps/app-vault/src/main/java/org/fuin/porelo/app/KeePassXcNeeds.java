package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.jspecify.annotations.Nullable;

/**
 * What making a key into KeePassXC needs, read without changing anything and before any password is
 * asked. Nothing here opens a database or asks KeePassXC to do anything.
 */
final class KeePassXcNeeds {

    /** The name of the need that says whether KeePassXC is installed with its agent extension. */
    static final String EXTENSION = "keepassxc-extension";

    /** The name of the need that says whether an agent answers. */
    static final String AGENT = "ssh-agent";

    private static final String INSTALL = "install KeePassXC from the package keepassxc-full";

    private static final String ABSENT = " is not installed";

    private static final String OWN_AGENT = ", or keep your key in an ssh-agent of your own";

    /** How a need stands. */
    enum State {
        /** It is met. */
        READY,
        /** It is not met, and making a key cannot work. */
        MISSING,
        /** Nothing can say; making a key goes on, and this is said. */
        CANNOT_TELL
    }

    /**
     * One need.
     *
     * @param name What it is called.
     * @param state How it stands.
     * @param detail What was found.
     * @param next What a person does about it.
     */
    record Need(String name, State state, String detail, String next) {

        /**
         * This need as {@code doctor} shows it, which never makes a machine not ready: a person
         * with an agent of their own and no KeePassXC can run tasks.
         *
         * @return The probe.
         */
        Probe probe() {
            if (absent()) {
                return Probe.ok(name, detail + ", which only --keepassxc needs");
            }
            return switch (state) {
                case READY -> Probe.ok(name, detail);
                case MISSING -> Probe.degraded(name, detail, next);
                case CANNOT_TELL -> Probe.unknown(name, detail, next);
            };
        }

        /**
         * Tells whether this says KeePassXC is not installed at all.
         *
         * @return Whether it does.
         */
        boolean absent() {
            return EXTENSION.equals(name) && state == State.MISSING && detail.endsWith(ABSENT);
        }
    }

    private KeePassXcNeeds() {}

    /**
     * Reads the needs on this machine.
     *
     * @param context The machine.
     * @param program The {@code keepassxc-cli} to ask.
     * @param running Whether KeePassXC runs.
     * @return The needs in the order a person meets them; without KeePassXC, only that it is not
     *     there.
     */
    static List<Need> read(
            final PoreloContext context,
            final String program,
            final Function<PoreloContext, State> running) {
        final List<Need> needs = new ArrayList<>();
        final Need extension = extension(context, program);
        needs.add(extension);
        if (extension.absent()) {
            return List.copyOf(needs);
        }
        needs.add(settings(context));
        needs.add(
                switch (running.apply(context)) {
                    case READY -> new Need("keepassxc-running", State.READY, "it runs", "");
                    case MISSING ->
                            new Need(
                                    "keepassxc-running",
                                    State.MISSING,
                                    "KeePassXC is not running",
                                    "start it" + OWN_AGENT);
                    case CANNOT_TELL ->
                            new Need(
                                    "keepassxc-running",
                                    State.CANNOT_TELL,
                                    "cannot tell whether KeePassXC runs",
                                    "see that it does");
                });
        needs.add(agent(context.environment().apply("SSH_AUTH_SOCK")));
        return List.copyOf(needs);
    }

    /**
     * Tells whether a KeePassXC of this account runs, by its process's name alone. Its own answer
     * would lock its databases, and it keeps other processes from reading where its program lies.
     *
     * @param context The machine.
     * @return How it stands.
     */
    static State runs(final PoreloContext context) {
        final CommandResult listed;
        try {
            listed =
                    context.runner()
                            .run(
                                    Command.of("ps", "-x", "-o", "comm=")
                                            .withEnvironment(Map.of("LC_ALL", "C")));
        } catch (CommandException ex) {
            return State.CANNOT_TELL;
        }
        if (!listed.successful()) {
            return State.CANNOT_TELL;
        }
        return listed.standardOutput().lines().anyMatch(KeePassXcNeeds::isKeePassXc)
                ? State.READY
                : State.MISSING;
    }

    /**
     * Tells KeePassXC by the name of its process, under a wrapper's name too, from its tools.
     *
     * @param command The name, or the program.
     * @return Whether it is KeePassXC itself.
     */
    static boolean isKeePassXc(final String command) {
        final String name = command.strip().substring(command.strip().lastIndexOf('/') + 1);
        return name.contains("keepassxc") && !name.contains("-cli") && !name.contains("-proxy");
    }

    private static Need extension(final PoreloContext context, final String program) {
        final String tell = "cannot tell whether KeePassXC has its SSH agent extension";
        final CommandResult said;
        try {
            said =
                    context.runner()
                            .run(
                                    Command.of(program, "--debug-info")
                                            .withEnvironment(Map.of("LC_ALL", "C")));
        } catch (CommandException ex) {
            return notInstalled(program);
        }
        if (said.exitCode() == 126 || said.exitCode() == 127) {
            return notInstalled(program);
        }
        final String words = said.standardOutput() + said.standardError();
        // KeePassXC's own language setting outranks the locale, and only the list's lines stay
        // alike.
        if (said.successful() && words.lines().anyMatch(KeePassXcNeeds::namesTheAgent)) {
            return new Need(EXTENSION, State.READY, "it has its SSH agent extension", "");
        }
        if (!said.successful() || !words.contains("Enabled extensions:")) {
            return new Need(EXTENSION, State.CANNOT_TELL, tell, "look in KeePassXC's own About");
        }
        return new Need(
                EXTENSION,
                State.MISSING,
                "this KeePassXC has no SSH agent extension",
                INSTALL + OWN_AGENT);
    }

    private static boolean namesTheAgent(final String line) {
        return line.startsWith("- ") && line.toUpperCase(java.util.Locale.ROOT).contains("SSH");
    }

    private static Need notInstalled(final String program) {
        return new Need(
                EXTENSION,
                State.MISSING,
                AnswerText.printable(program) + ABSENT,
                INSTALL + OWN_AGENT);
    }

    /** KeePassXC's own settings; other install forms keep them elsewhere, so absent is unknown. */
    private static Need settings(final PoreloContext context) {
        final String name = "keepassxc-settings";
        final String turnOn = "in KeePassXC: Settings, SSH Agent, Enable SSH Agent integration";
        final @Nullable Path file = settingsFile(context);
        final List<String> lines;
        try {
            lines =
                    file == null || !Files.isRegularFile(file)
                            ? List.of()
                            : Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return cannotTell(name, turnOn);
        }
        if (lines.isEmpty()) {
            return cannotTell(name, turnOn);
        }
        return enabled(lines)
                ? new Need(name, State.READY, "KeePassXC's agent integration is on", "")
                : new Need(name, State.MISSING, "KeePassXC's agent integration is off", turnOn);
    }

    private static Need cannotTell(final String name, final String turnOn) {
        return new Need(
                name,
                State.CANNOT_TELL,
                "cannot tell whether KeePassXC's agent integration is on: its settings were not"
                        + " found here",
                "see that it is on, " + turnOn);
    }

    private static @Nullable Path settingsFile(final PoreloContext context) {
        final String config = context.environment().apply("XDG_CONFIG_HOME");
        if (config != null && config.startsWith("/")) {
            return Path.of(config, "keepassxc", "keepassxc.ini");
        }
        final String home = context.environment().apply("HOME");
        return home != null && home.startsWith("/")
                ? Path.of(home, ".config", "keepassxc", "keepassxc.ini")
                : null;
    }

    private static boolean enabled(final List<String> lines) {
        boolean inside = false;
        for (final String line : lines) {
            final String kept = line.strip();
            if (kept.startsWith("[")) {
                inside = kept.equals("[SSHAgent]");
            } else if (inside && kept.equals("Enabled=true")) {
                return true;
            }
        }
        return false;
    }

    private static Need agent(final @Nullable String socket) {
        final String start = "start an ssh-agent, and name it in SSH_AUTH_SOCK";
        if (socket == null || socket.isEmpty()) {
            return new Need(AGENT, State.MISSING, "SSH_AUTH_SOCK names no agent", start);
        }
        return KeePassXcKey.answers(socket)
                ? new Need(AGENT, State.READY, "an agent answers at SSH_AUTH_SOCK", "")
                : new Need(AGENT, State.MISSING, "no agent answers at SSH_AUTH_SOCK", start);
    }
}
