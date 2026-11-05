package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
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
            return switch (state) {
                case READY -> Probe.ok(name, detail);
                case MISSING -> Probe.degraded(name, detail, next);
                case CANNOT_TELL -> Probe.unknown(name, detail, next);
            };
        }
    }

    private KeePassXcNeeds() {}

    /**
     * Reads the needs on this machine.
     *
     * @param context The machine.
     * @param program The {@code keepassxc-cli} to ask.
     * @param running Whether KeePassXC runs.
     * @return The needs in the order a person meets them.
     */
    static List<Need> read(
            final PoreloContext context, final String program, final BooleanSupplier running) {
        final List<Need> needs = new ArrayList<>();
        final Need extension = extension(context, program);
        needs.add(extension);
        if (extension.state() != State.MISSING || !extension.detail().contains("not installed")) {
            needs.add(settings(context));
            needs.add(
                    running.getAsBoolean()
                            ? new Need("keepassxc-running", State.READY, "it runs", "")
                            : new Need(
                                    "keepassxc-running",
                                    State.MISSING,
                                    "KeePassXC is not running",
                                    "start it" + OWN_AGENT));
        }
        needs.add(agent(context.environment().apply("SSH_AUTH_SOCK")));
        return List.copyOf(needs);
    }

    /**
     * Tells whether a KeePassXC of this account runs, by its name alone: asking it would lock its
     * databases.
     *
     * @return Whether one runs.
     */
    static boolean runs() {
        final Optional<String> me = ProcessHandle.current().info().user();
        return ProcessHandle.allProcesses()
                .map(ProcessHandle::info)
                .filter(info -> info.user().equals(me))
                .map(ProcessHandle.Info::command)
                .flatMap(Optional::stream)
                .anyMatch(KeePassXcNeeds::isKeePassXc);
    }

    /**
     * Tells KeePassXC by the name of its program, under a wrapper's name too, from its tools.
     *
     * @param command The program.
     * @return Whether it is KeePassXC itself.
     */
    static boolean isKeePassXc(final String command) {
        final String name = command.substring(command.lastIndexOf('/') + 1);
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
        final int enabled = words.indexOf("Enabled extensions:");
        if (!said.successful() || enabled < 0) {
            return new Need(EXTENSION, State.CANNOT_TELL, tell, "look in KeePassXC's own About");
        }
        final boolean listed =
                words.substring(enabled)
                        .lines()
                        .skip(1)
                        .takeWhile(line -> line.startsWith("- "))
                        .anyMatch(line -> line.strip().equals("- SSH Agent"));
        return listed
                ? new Need(EXTENSION, State.READY, "it has its SSH agent extension", "")
                : new Need(
                        EXTENSION,
                        State.MISSING,
                        "this KeePassXC has no SSH agent extension",
                        INSTALL + OWN_AGENT);
    }

    private static Need notInstalled(final String program) {
        return new Need(
                EXTENSION,
                State.MISSING,
                AnswerText.printable(program) + " is not installed",
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
