package org.fuin.porelo.agent.api;

import java.util.List;

/**
 * What an agent module provides.
 *
 * <p>Discovered with {@link java.util.ServiceLoader}, so nothing in Porelo names an implementation.
 * Every method but {@link #definition()} has a default drawn from that definition, which is what
 * makes a well-behaved agent a YAML file and six lines of Java.
 *
 * <p><strong>An agent that needs behavior the definition cannot express overrides a method
 * here.</strong> It does not add a branch elsewhere: a branch outside this package would be
 * invisible to the next agent, which would fall into its {@code else} and be quietly wrong.
 */
public interface Agent {

    /**
     * Returns everything about this agent that is data.
     *
     * @return The definition.
     */
    AgentDefinition definition();

    /**
     * Returns the agent's short name.
     *
     * @return Name.
     */
    default String name() {
        return definition().name();
    }

    /**
     * Returns how this agent's credential is read out of its config directory.
     *
     * @return Extractor. The default finds nothing, because an agent that stores its credential in
     *     a shape the agent API does not know about must say so rather than appear unauthenticated.
     */
    default CredentialExtractor credentialExtractor() {
        return CredentialExtractor.none();
    }

    /**
     * Returns how this agent says what it finds of itself on this machine.
     *
     * @return Finder. The default cannot tell, which is never read as not signed in.
     */
    default Finder finder() {
        return Finder.cannotSay();
    }

    /**
     * Returns what this agent needs placed in a container before it will run.
     *
     * @return Setup. The default places nothing, which is right for an agent that starts clean.
     */
    default ContainerSetup containerSetup() {
        return ContainerSetup.none();
    }

    /**
     * Returns how this agent's output is presented.
     *
     * @return Formatter, plain by default.
     */
    default LogFormatter logFormatter() {
        return LogFormatter.plain();
    }

    /**
     * Reads one line of this agent's output for the end of its run.
     *
     * <p>Each agent ends its output its own way, so the agent reads it: Porelo asks line by line
     * from the end, and the first line this answers for is how the run ended.
     *
     * @param line One line of the agent's raw output.
     * @return How the run ended, or {@code null} for a line that is not its end.
     */
    default @org.jspecify.annotations.Nullable AgentEnd ended(String line) {
        return null;
    }

    /**
     * Reads one line of this agent's output for the tool calls it starts or ends.
     *
     * <p>Per line and without state: Porelo joins a start to its end by the event's id, and a
     * background job's start and end by its job id. A tool this agent does not know is {@link
     * ToolEvent.Kind#OTHER}, never left out, and a shell call's command line is passed as the
     * stream gives it, since Porelo masks it before anything is stored.
     *
     * @param line One line of the agent's raw output.
     * @return The line's tool events in their order; the default reads none.
     */
    default List<ToolEvent> toolEvents(String line) {
        return List.of();
    }

    /**
     * Returns the container-build fragments this agent contributes, in order.
     *
     * @return Image layer.
     */
    default ImageLayer imageLayer() {
        return definition().imageLayer();
    }

    /**
     * Builds the command line for a non-interactive run.
     *
     * @param request What to run.
     * @return Command and arguments.
     */
    default List<String> headlessCommand(RunRequest request) {
        return HeadlessCommandBuilder.build(definition(), request);
    }
}
