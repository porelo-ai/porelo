package org.fuin.porelo.app;

import java.util.List;
import org.fuin.porelo.agent.api.AgentDefinition;
import org.fuin.porelo.runtime.PinnedBinary;
import org.fuin.porelo.runtime.Podman;

/**
 * What every start of an agent, unattended or attended, runs as its program: the absolute path its
 * image recorded at build, checked as root first, never the bare name.
 *
 * <p>What the agent writes during a task must never pick what Porelo starts as that agent next
 * time. A binary the agent's user could write over is refused, and its adapter must install it as
 * root.
 */
public final class AgentBinary {

    /** What a check of an agent's binary answers. */
    public sealed interface Checked permits Pinned, Refused {}

    /**
     * The agent may be started, by its checked path.
     *
     * @param agent The agent's name.
     * @param pinned The path its image recorded, checked.
     */
    public record Pinned(String agent, PinnedBinary.Pinned pinned) implements Checked {

        /**
         * Returns the agent's command, running its checked path.
         *
         * @param command The command as the agent builds it, starting with its binary.
         * @return The command, starting with the absolute path.
         * @throws Refusal If it starts with any other program, which nothing has checked.
         */
        public List<String> running(final List<String> command) {
            try {
                return pinned.running(command);
            } catch (IllegalArgumentException ex) {
                throw new Refusal(
                        new Refused(
                                agent,
                                PinnedBinary.Reason.UNCHECKED,
                                command.isEmpty() ? "" : command.getFirst()));
            }
        }
    }

    /**
     * The agent's start is refused.
     *
     * @param agent The agent's name.
     * @param reason Why.
     * @param path The path the reason is about: the binary, a link it passes or a directory above
     *     either; "" where the check gave none.
     */
    public record Refused(String agent, PinnedBinary.Reason reason, String path)
            implements Checked {

        /**
         * Returns the refusal in a person's words.
         *
         * @return The words, naming the agent and the path.
         */
        public String said() {
            return "the agent "
                    + agent
                    + " is not started: "
                    + (path.isEmpty() ? "its binary" : path)
                    + " "
                    + reason.said()
                    + (reason == PinnedBinary.Reason.CHANGEABLE
                                    || reason == PinnedBinary.Reason.MOVED
                            ? " - its adapter must install its binary as root"
                            : "");
        }
    }

    /** A refused start, thrown where the caller's answer has no room for a refusal. */
    public static final class Refusal extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        private final transient Refused refused;

        /**
         * Constructor.
         *
         * @param refused The refusal.
         */
        public Refusal(final Refused refused) {
            super(refused.said());
            this.refused = refused;
        }

        /**
         * Returns the refusal.
         *
         * @return The named value.
         */
        public Refused refused() {
            return refused;
        }
    }

    private AgentBinary() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Checks an agent's binary in a running task, as the container's root.
     *
     * @param podman Runs the check.
     * @param container The task.
     * @param definition The agent.
     * @return Its checked path, or the refusal.
     */
    public static Checked check(
            final Podman podman, final String container, final AgentDefinition definition) {
        return switch (podman.pinnedBinary(container, definition.binary())) {
            case PinnedBinary.Pinned pinned -> new Pinned(definition.name(), pinned);
            case PinnedBinary.Unpinned unpinned ->
                    new Refused(definition.name(), unpinned.reason(), unpinned.path());
        };
    }

    /**
     * Returns an agent's command running its checked path, or throws its refusal.
     *
     * @param podman Runs the check.
     * @param container The task.
     * @param definition The agent.
     * @param command The command as built, starting with the agent's binary.
     * @return The command, starting with the absolute path.
     * @throws Refusal When the start is refused.
     */
    public static List<String> started(
            final Podman podman,
            final String container,
            final AgentDefinition definition,
            final List<String> command) {
        return switch (check(podman, container, definition)) {
            case Pinned pinned -> pinned.running(command);
            case Refused refused -> throw new Refusal(refused);
        };
    }
}
