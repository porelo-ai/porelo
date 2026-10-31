package org.fuin.porelo.vault;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.project.AccessFile;

/** Resolves the persons of an authenticated access file from that same repository commit. */
public final class AccessPersons {

    private AccessPersons() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * A person answered from their card, or revoked when the card could not be trusted.
     *
     * @param login Their access-file login.
     * @param ssh Their verified key, or empty when revoked.
     * @param role Their project role.
     * @param hosts Their declared hosts.
     * @param expires The card's expiry, or empty when revoked.
     * @param revoked Whether the card is missing, invalid, untrusted or revoked.
     * @param locked Whether the project has locked them out.
     * @param refusal Why the person is revoked, or empty.
     */
    public record Person(
            String login,
            String ssh,
            String role,
            List<String> hosts,
            String expires,
            boolean revoked,
            boolean locked,
            String refusal) {
        /** Canonical constructor; the answer cannot be changed afterwards. */
        public Person {
            hosts = List.copyOf(hosts);
        }

        /**
         * @return Whether the person may participate in this project.
         */
        public boolean admitted() {
            return !revoked && !locked;
        }
    }

    /**
     * Checks each card independently; a bad card revokes only its person.
     *
     * @param runner How git runs.
     * @param repository The repository holding the verified access commit.
     * @param commit The verified commit, never a moving ref or working tree.
     * @param access The access file read from that commit.
     * @return Persons in access-file order, with a reason for each revocation.
     */
    public static List<Person> read(
            final CommandRunner runner,
            final Path repository,
            final String commit,
            final AccessFile access) {
        final Set<String> revoked = Set.copyOf(access.revoked());
        final List<Person> persons = new ArrayList<>();
        for (final AccessFile.Person person : access.persons().values()) {
            persons.add(check(runner, repository, commit, person, revoked));
        }
        return List.copyOf(persons);
    }

    private static Person check(
            final CommandRunner runner,
            final Path repository,
            final String commit,
            final AccessFile.Person person,
            final Set<String> revoked) {
        try {
            final String path = "cards/" + person.login() + ".card";
            final PersonCard card = card(runner, repository, commit, path);
            if (!card.login().equals(person.login())) {
                throw new CardException("the card belongs to another login");
            }
            if (revoked.contains(card.fingerprint())
                    || !card.predecessor().isEmpty()
                            && revoked.contains(PersonCard.fingerprint(card.predecessor()))) {
                throw new CardException("the card carries a revoked key");
            }
            final List<PersonCard> earlier =
                    card.fingerprint().equals(person.fingerprint())
                            ? List.of()
                            : history(runner, repository, commit, path);
            PersonCardChain.check(card, earlier, person.fingerprint(), revoked::contains);
            return new Person(
                    person.login(),
                    card.key(),
                    person.role(),
                    person.hosts(),
                    card.expires().toString(),
                    false,
                    person.locked(),
                    "");
        } catch (final RuntimeException ex) {
            // A card's parser or git may echo untrusted bytes; no such bytes belong on stderr.
            return new Person(
                    person.login(),
                    "",
                    person.role(),
                    person.hosts(),
                    "",
                    true,
                    person.locked(),
                    "card missing, invalid, untrusted or revoked");
        }
    }

    private static PersonCard card(
            final CommandRunner runner,
            final Path repository,
            final String commit,
            final String path) {
        final CommandResult shown =
                runner.run(
                        Command.of(
                                "git", "-C", repository.toString(), "show", commit + ":" + path));
        if (!shown.successful()) {
            throw new CardException("cannot read the card at the access commit");
        }
        return PersonCard.read(shown.standardOutput(), path);
    }

    private static List<PersonCard> history(
            final CommandRunner runner,
            final Path repository,
            final String commit,
            final String path) {
        final CommandResult log =
                runner.run(
                        Command.of(
                                "git",
                                "-C",
                                repository.toString(),
                                "log",
                                "--max-count=" + (PersonCardChain.MAX_STEPS + 1),
                                "--format=%H",
                                "--full-history",
                                commit,
                                "--",
                                path));
        if (!log.successful()) {
            throw new CardException("cannot read the card's history");
        }
        final List<String> revisions =
                log.standardOutput().lines().limit(PersonCardChain.MAX_STEPS + 1L).toList();
        if (revisions.size() > PersonCardChain.MAX_STEPS) {
            throw new CardException(
                    "the card's history exceeds " + PersonCardChain.MAX_STEPS + " entries");
        }
        final List<PersonCard> earlier = new ArrayList<>();
        for (final String revision : revisions) {
            if (!revision.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
                throw new CardException("the card's history does not name commits");
            }
            try {
                earlier.add(card(runner, repository, revision, path));
            } catch (final CardException ex) {
                // A malformed historical version proves no link, but cannot poison a valid one.
            }
        }
        return earlier;
    }
}
