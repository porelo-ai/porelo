package org.fuin.porelo.organization;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The changes of the new structure. The adapter checks the acting person's role and secures the
 * change, and a change it refuses throws {@link OrganizationQuestions.Refused}. Everything is plain
 * data: a card comes already signed, and the adapter signs its own part through the agent of
 * whoever acts, which serves the acting key.
 *
 * <p>The id is looked for first: asked again with an id that already made its change, the answer is
 * that change, with {@code again} set, although the store has moved past the base.
 */
public interface OrganizationWrites {

    /**
     * The person who acts: the one whose key their agent serves and whose card is accepted.
     *
     * @param login Their login.
     * @param key The key their agent serves, {@code ssh-ed25519 AAAA...}.
     */
    record Acting(String login, String key) {}

    /**
     * What every change takes.
     *
     * @param acting Who acts.
     * @param base The revision it was made against, or {@code null} where nothing was accepted yet.
     * @param id Names the change: asked again, the same answer.
     */
    record Change(Acting acting, @Nullable String base, String id) {

        private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

        /**
         * Refuses an id no change may carry.
         *
         * @throws IllegalArgumentException When it is one, naming no part of it.
         */
        public Change {
            if (!ID.matcher(id).matches()) {
                throw new IllegalArgumentException("an id is 1 to 64 of A-Za-z0-9._-");
            }
        }
    }

    /**
     * What a change made.
     *
     * @param revision The revision it stands at.
     * @param id Its id.
     * @param again Whether this id had already made it, the revision being the one from then.
     */
    record Written(String revision, String id, boolean again) {}

    /**
     * Fills the organization's empty repository: the acting person becomes its only owner.
     *
     * @param change Who acts, against which state, under which id.
     * @param card The acting person's card, already signed by their agent.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    Written createOrganization(Change change, String card) throws IOException;

    /**
     * Files the acting person's first card.
     *
     * @param change Who acts, against which state, under which id.
     * @param card The card, already signed by their agent.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    Written addCard(Change change, String card) throws IOException;

    /**
     * Changes the acting person's card, or hands it on to a new key.
     *
     * @param change Who acts, against which state, under which id.
     * @param card The card, already signed by their agent, and by the old key when the key changes;
     *     where no card stands for the login, because the identity source no longer lists the old
     *     key, its word for the new key stands in for the old key's signature.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    Written changeCard(Change change, String card) throws IOException;

    /**
     * Withdraws a card: the person's own, or anyone's for an owner.
     *
     * @param change Who acts, against which state, under which id.
     * @param login Whose card.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    Written revokeCard(Change change, String login) throws IOException;

    /**
     * Puts a new card in place of a lost key's, which revokes that key; for an owner alone.
     *
     * @param change Who acts, against which state, under which id.
     * @param login Whose card.
     * @param card The new card, already signed by its own key.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    Written replaceCard(Change change, String login, String card) throws IOException;

    /**
     * Makes a person an owner, with the key on their card.
     *
     * @param change Who acts, against which state, under which id.
     * @param login The person.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    Written addOrganizationOwner(Change change, String login) throws IOException;

    /**
     * Takes an owner's place away.
     *
     * @param change Who acts, against which state, under which id.
     * @param login The owner.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    Written removeOrganizationOwner(Change change, String login) throws IOException;

    /**
     * Lists a person as an organization admin: an owner's. The first listing starts the roles.
     *
     * @param change Who acts.
     * @param login The person, whose card stands.
     * @return What it made.
     * @throws IOException When something unexpected happened; the change may have been made.
     */
    Written addOrganizationAdmin(Change change, String login) throws IOException;

    /**
     * Takes a person off the organization admins: an owner's. The list may stand empty.
     *
     * @param change Who acts.
     * @param login The person.
     * @return What it made.
     * @throws IOException When something unexpected happened; the change may have been made.
     */
    Written removeOrganizationAdmin(Change change, String login) throws IOException;

    /**
     * Makes a project, with its first admin as a member; the name {@code default} is refused.
     *
     * @param change Who acts, against which state, under which id.
     * @param project Its name.
     * @param admin Its first admin.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    Written addProject(Change change, String project, String admin) throws IOException;

    /**
     * Removes a project.
     *
     * @param change Who acts, against which state, under which id.
     * @param project Its name.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    Written removeProject(Change change, String project) throws IOException;

    /**
     * Names a project's first admin again, who also becomes a member with that role.
     *
     * @param change Who acts, against which state, under which id.
     * @param project Its name.
     * @param admin The admin.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    Written changeProjectAdmin(Change change, String project, String admin) throws IOException;

    /**
     * Files the address of a project's repository.
     *
     * @param change Who acts, against which state, under which id.
     * @param project Its name.
     * @param address The address.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    Written changeProjectRepository(Change change, String project, String address)
            throws IOException;

    /**
     * Finds what an id filed for a project's address, whatever the address or the person's role is
     * by now.
     *
     * @param change Who acts, under which id.
     * @param project Its name.
     * @return What the id filed, or empty when it filed nothing.
     * @throws OrganizationQuestions.Refused {@code usage} when the id named another change.
     * @throws IOException When the organization cannot be read.
     */
    java.util.Optional<Written> addressFiled(Change change, String project) throws IOException;

    /**
     * Adds a member; an owner, an admin alone.
     *
     * @param change Who acts, against which state, under which id.
     * @param project Its name.
     * @param login The person.
     * @param role {@code admin} or {@code developer}.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    Written addProjectMember(Change change, String project, String login, String role)
            throws IOException;

    /**
     * Changes a member's role; an owner, only to or from admin.
     *
     * @param change Who acts, against which state, under which id.
     * @param project Its name.
     * @param login The member.
     * @param role {@code admin} or {@code developer}.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    Written changeProjectMemberRole(Change change, String project, String login, String role)
            throws IOException;

    /**
     * Removes a member; an owner, an admin alone.
     *
     * @param change Who acts, against which state, under which id.
     * @param project Its name.
     * @param login The member.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    Written removeProjectMember(Change change, String project, String login) throws IOException;

    /**
     * Locks a member: listed, but may nothing.
     *
     * @param change Who acts, against which state, under which id.
     * @param project Its name.
     * @param login The member.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    Written lockProjectMember(Change change, String project, String login) throws IOException;

    /**
     * Unlocks a member.
     *
     * @param change Who acts, against which state, under which id.
     * @param project Its name.
     * @param login The member.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    Written unlockProjectMember(Change change, String project, String login) throws IOException;

    /**
     * Makes a host with its first admin.
     *
     * @param change Who acts, against which state, under which id.
     * @param host Its name.
     * @param admin Its first admin.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    Written addHost(Change change, String host, String admin) throws IOException;

    /**
     * Removes a host.
     *
     * @param change Who acts, against which state, under which id.
     * @param host Its name.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    Written removeHost(Change change, String host) throws IOException;

    /**
     * Files a host's card.
     *
     * @param change Who acts, against which state, under which id.
     * @param host Its name.
     * @param card The card, already signed by the acting admin's agent.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    Written changeHost(Change change, String host, String card) throws IOException;

    /**
     * Adds a host's admin.
     *
     * @param change Who acts, against which state, under which id.
     * @param host Its name.
     * @param login The person.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    Written addHostAdmin(Change change, String host, String login) throws IOException;

    /**
     * Removes a host's admin.
     *
     * @param change Who acts, against which state, under which id.
     * @param host Its name.
     * @param login The admin.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    Written removeHostAdmin(Change change, String host, String login) throws IOException;

    /**
     * Puts a project on a host.
     *
     * @param change Who acts, against which state, under which id.
     * @param host Its name.
     * @param project The project.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    default Written addProjectToHost(Change change, String host, String project)
            throws IOException {
        return addProjectToHost(change, host, project, List.of());
    }

    /**
     * Puts a project on a host, whole or by some of its repositories. Repositories named are added
     * to those the host carries of it already.
     *
     * @param change Who acts, against which state, under which id.
     * @param host Its name.
     * @param project The project.
     * @param repositories The repositories to carry, or none for the whole project.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    Written addProjectToHost(Change change, String host, String project, List<String> repositories)
            throws IOException;

    /**
     * Takes a project off a host.
     *
     * @param change Who acts, against which state, under which id.
     * @param host Its name.
     * @param project The project.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    default Written removeProjectFromHost(Change change, String host, String project)
            throws IOException {
        return removeProjectFromHost(change, host, project, List.of());
    }

    /**
     * Takes a project off a host, whole or some of the repositories it carries of it. Taking off
     * the last of them takes the project off.
     *
     * @param change Who acts, against which state, under which id.
     * @param host Its name.
     * @param project The project.
     * @param repositories The repositories to take off, or none for the whole project.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    Written removeProjectFromHost(
            Change change, String host, String project, List<String> repositories)
            throws IOException;

    /**
     * Files a backup key's public half for the place the acting person holds.
     *
     * @param change Who acts, against which state, under which id.
     * @param host The host, or {@code null} for the organization's owner.
     * @param key The backup key.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    Written addBackupKey(Change change, @Nullable String host, String key) throws IOException;

    /**
     * Replaces the key a backup key backs, the acting key being that backup key.
     *
     * @param change Who acts, against which state, under which id.
     * @param host The host, or {@code null} for the organization's owner.
     * @param card The new card, already signed by its own key.
     * @return What the change made, or made before under this id.
     * @throws IOException When something unexpected happened; the change may have been made, so ask
     *     again with the same id.
     */
    Written replaceKeyWithBackup(Change change, @Nullable String host, String card)
            throws IOException;
}
