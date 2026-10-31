package org.fuin.porelo.vault;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.project.AccessFile;
import org.junit.jupiter.api.Test;

/** Guards against rejecting valid renewals or admitting a card carrying a revoked predecessor. */
class AccessPersonsTest {

    private static String card(String login, String name, SigningKey key, SigningKey predecessor) {
        return PersonCard.write(
                login,
                name,
                "",
                key,
                LocalDate.parse("2026-10-30"),
                LocalDate.parse("2027-10-30"),
                predecessor);
    }

    private static String pin(SigningKey key) {
        return PersonCard.fingerprint(PersonCard.keyLine(key));
    }

    // Harm guarded: card history must not cause unbounded git processes or signature checks.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {60, 66, 1000})
    void cardHistoryIsBoundedEvenWhenOnlyTwoKeysChanged(final int renewals) {
        final SigningKey first = SigningKey.generate("first");
        final SigningKey middle = SigningKey.generate("middle");
        final SigningKey last = SigningKey.generate("last");
        final Map<String, String> versions = new LinkedHashMap<>();
        final List<String> history = new ArrayList<>();
        for (int revision = 1; revision <= renewals; revision++) {
            final String hash = "%040x".formatted(revision);
            history.add(hash);
            versions.put(hash, card("alice", "Alice " + revision, last, middle));
        }
        final String handover = "b".repeat(40);
        history.add(handover);
        versions.put(handover, card("alice", "Alice", middle, first));
        final List<String> shown = new ArrayList<>();
        final CommandRunner git =
                command -> {
                    final List<String> args = command.arguments();
                    if (args.get(3).equals("log")) {
                        // Git limits file revisions, which can be renewals with no change of key.
                        final int count =
                                args.stream()
                                        .filter(arg -> arg.startsWith("--max-count="))
                                        .mapToInt(
                                                arg ->
                                                        Integer.parseInt(
                                                                arg.substring(
                                                                        "--max-count=".length())))
                                        .findFirst()
                                        .orElse(history.size());
                        return new CommandResult(
                                command,
                                0,
                                String.join("\n", history.stream().limit(count).toList()),
                                "");
                    }
                    final String hash = args.get(4).split(":")[0];
                    shown.add(hash);
                    final String body = versions.get(hash);
                    return new CommandResult(
                            command, body == null ? 1 : 0, body == null ? "" : body, "");
                };
        final AccessFile access =
                new AccessFile(
                        null,
                        Map.of(
                                "alice",
                                new AccessFile.Person(
                                        "alice",
                                        pin(first),
                                        "developer",
                                        List.of("example-host"),
                                        false)),
                        List.of(),
                        Map.of());

        final List<AccessPersons.Person> persons =
                AccessPersons.read(git, Path.of("repository"), history.getFirst(), access);

        assertThat(persons).as("renewing a card does not add a change of key").hasSize(1);
        if (history.size() > 64) {
            assertThat(persons.getFirst().revoked())
                    .as("a history beyond the chain's cap admits nobody")
                    .isTrue();
            assertThat(shown).as("overflow is detected before loading historical cards").hasSize(1);
            return;
        }
        assertThat(persons.getFirst().revoked())
                .as("a bounded history proves both changes")
                .isFalse();
        assertThat(persons.getFirst().ssh())
                .as("the current key remains admitted")
                .isEqualTo(PersonCard.keyLine(last));
    }

    @Test
    void aNewPinCannotMakeACardCarryARevokedPredecessor() {
        final SigningKey old = SigningKey.generate("old");
        final SigningKey current = SigningKey.generate("current");
        final String body = card("alice", "Alice", current, old);
        final AccessFile access =
                new AccessFile(
                        null,
                        Map.of(
                                "alice",
                                new AccessFile.Person(
                                        "alice",
                                        pin(current),
                                        "admin",
                                        List.of("example-host"),
                                        false)),
                        List.of(pin(old)),
                        Map.of());
        final CommandRunner git = command -> new CommandResult(command, 0, body, "");

        final AccessPersons.Person person =
                AccessPersons.read(git, Path.of("repository"), "a".repeat(40), access).getFirst();

        assertThat(person.revoked())
                .as("no card may carry a fingerprint the access file revokes")
                .isTrue();
        assertThat(person.ssh()).as("a refused card cannot certify tasks").isEmpty();
    }
}
