package org.fuin.porelo.app;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.fuin.porelo.wire.varlink.TasksContract;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.jspecify.annotations.Nullable;

/**
 * Asks a daemon for a proposal for a repository of {@code default}, and gives it the person's
 * answer.
 *
 * <p>An older daemon has neither method and answers MethodNotFound, which reads as {@link
 * CannotPropose}: this machine cannot propose, never an error. No command asks yet.
 */
public final class ProposalClient {

    /** What a daemon without the methods answers. */
    private static final String METHOD_NOT_FOUND = "org.varlink.service.MethodNotFound";

    private static final String NOT_PROPOSABLE = TasksContract.INTERFACE + ".NotProposable";

    private ProposalClient() {}

    /** What a daemon answered. */
    public sealed interface Answer permits Proposed, Taken, NotProposable, CannotPropose {}

    /**
     * The merged proposal, the last reply of {@code ProposeProject}.
     *
     * @param proposal Its {@code Proposal}.
     */
    public record Proposed(Map<String, Object> proposal) implements Answer {}

    /**
     * The answer recorded.
     *
     * @param repository The repository as it is now.
     * @param draft The project.yml draft, under {@code TOOLS_AND_DRAFT} alone.
     */
    public record Taken(Map<String, Object> repository, @Nullable String draft) implements Answer {}

    /**
     * The daemon cannot read this repository.
     *
     * @param reason {@code no-checkout} or {@code not-default}.
     */
    public record NotProposable(String reason) implements Answer {}

    /** The daemon cannot propose at all: an older one, without the methods. */
    public record CannotPropose() implements Answer {}

    /**
     * Asks for a proposal, handing each adapter's answer over as it comes.
     *
     * @param client The daemon.
     * @param project The project.
     * @param repository The repository's name in it.
     * @param adapter Takes each adapter's answer.
     * @return The proposal, or why there is none.
     */
    public static Answer propose(
            final VarlinkClient client,
            final String project,
            final String repository,
            final Consumer<Map<String, Object>> adapter) {
        final Map<String, Object>[] last = newLast();
        try {
            client.callMore(
                    TasksContract.INTERFACE + ".ProposeProject",
                    Map.of("project", project, "repository", repository),
                    reply -> {
                        if (reply.get("adapter") instanceof Map<?, ?> one) {
                            adapter.accept(copy(one));
                        }
                        if (reply.get("proposal") instanceof Map<?, ?> proposal) {
                            last[0] = copy(proposal);
                        }
                        return true;
                    });
        } catch (VarlinkException ex) {
            return refusal(ex);
        }
        if (last[0] == null) {
            throw new VarlinkException("the proposal's stream ended without a proposal");
        }
        return new Proposed(last[0]);
    }

    /**
     * Gives the person's answer to a proposal.
     *
     * @param client The daemon.
     * @param project The project.
     * @param repository The repository's name in it.
     * @param choice {@code TOOLS}, {@code TOOLS_AND_DRAFT} or {@code NONE}.
     * @return The answer recorded, or why not.
     */
    public static Answer take(
            final VarlinkClient client,
            final String project,
            final String repository,
            final String choice) {
        final Map<String, Object> reply;
        try {
            reply =
                    client.call(
                            TasksContract.INTERFACE + ".TakeProposal",
                            Map.of("project", project, "repository", repository, "choice", choice));
        } catch (VarlinkException ex) {
            return refusal(ex);
        }
        return new Taken(
                reply.get("repository") instanceof Map<?, ?> row ? copy(row) : Map.of(),
                reply.get("draft") instanceof String draft ? draft : null);
    }

    /**
     * Reads an older daemon's MethodNotFound as one that cannot propose, never an error, and a
     * repository it cannot read by its reason; any other error stays one.
     */
    private static Answer refusal(final VarlinkException ex) {
        if (METHOD_NOT_FOUND.equals(ex.getErrorName())) {
            return new CannotPropose();
        }
        if (NOT_PROPOSABLE.equals(ex.getErrorName())) {
            final Map<String, Object> parameters = ex.getParameters();
            return new NotProposable(
                    String.valueOf(parameters == null ? "" : parameters.get("reason")));
        }
        throw ex;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object>[] newLast() {
        return (Map<String, Object>[]) new Map<?, ?>[1];
    }

    private static Map<String, Object> copy(final Map<?, ?> map) {
        final Map<String, Object> copied = new LinkedHashMap<>();
        map.forEach((key, value) -> copied.put(String.valueOf(key), value));
        return copied;
    }
}
