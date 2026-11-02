package org.fuin.porelo.organization;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The questions of the new structure, each answered at one revision, so that several answers rest
 * on one state. Each adapter answers each question itself; the questions are the contract.
 */
public interface OrganizationQuestions {

    /**
     * Returns the revision these answers rest on.
     *
     * @return It, or {@code null} when nothing could be accepted yet.
     */
    @Nullable String revision();

    /**
     * Returns whether a login holds the organization's owner place with a key of its own: a backup
     * key alone holds none.
     *
     * @param login The login.
     * @return Whether it is an owner.
     */
    boolean isOrganizationOwner(String login);

    /**
     * Returns the role a login may act in within a project. A locked member is listed, but may
     * nothing, so it holds none here.
     *
     * @param project The project's name.
     * @param login The login.
     * @return The role, {@code admin} or {@code developer}; empty for anyone else, and for a
     *     project that does not exist.
     */
    Optional<String> isProjectMember(String project, String login);

    /**
     * Returns what the read refused, the latest last.
     *
     * @return The refusals.
     */
    List<Organization.Refusal> getRefusals();
}
