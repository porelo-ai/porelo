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
     * Where a host is reached.
     *
     * @param address A host name or an IP address.
     * @param sshPort The port its ssh listens on.
     * @param hostKey The key its ssh answers with, as {@code known_hosts} holds it.
     */
    record Reach(String address, int sshPort, String hostKey) {}

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
     * Returns whether a login is an admin of a host with a key of its own: a backup key alone holds
     * no such place.
     *
     * @param host The host's name.
     * @param login The login.
     * @return Whether it is one.
     */
    boolean isHostAdmin(String host, String login);

    /**
     * Returns who may act as a project's admin: its members with that role, none of them locked.
     *
     * @param project The project's name.
     * @return The logins, or empty when there is no such project.
     */
    Optional<List<String>> projectAdmins(String project);

    /**
     * Returns where a host is reached.
     *
     * @param host The host's name.
     * @return Where, or empty when its admins have not said yet, or there is no such host.
     */
    Optional<Reach> reach(String host);

    /**
     * Returns the hosts carrying a project this login may act in.
     *
     * @param login The login.
     * @return The hosts' names, sorted.
     */
    List<String> hostsFor(String login);

    /**
     * Returns the one host carrying a project.
     *
     * @param project The project's name.
     * @return The host's name, or empty when none carries it.
     */
    Optional<String> hostOf(String project);

    /**
     * Returns the address of a project's repository. It is a pointer, which nothing here opens.
     *
     * @param project The project's name.
     * @return The address, or empty until one is filed.
     */
    Optional<String> getProjectRepository(String project);

    /**
     * Returns what the read refused, the latest last.
     *
     * @return The refusals.
     */
    List<Organization.Refusal> getRefusals();
}
