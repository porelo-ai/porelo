package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@code porelo card verify}, against a real {@code ssh-keygen} revocation list. Harm
 * guarded: a host that takes a card on its word gives an account to whoever wrote the file, and one
 * that misses a revoked key lets a thief hand a stolen identity on.
 */
class CardVerifyCommandTest {

    private static final LocalDate MADE = LocalDate.parse("2026-10-30");

    private static final LocalDate ENDS = LocalDate.parse("2027-10-30");

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private final SigningKey first = SigningKey.generate("first");

    private final SigningKey second = SigningKey.generate("second");

    @TempDir Path dir;

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new ProcessCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
    }

    private int verify(final String... args) {
        final CommandLine cmd = PoreloCli.commandLine(context());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        final List<String> all = new ArrayList<>(List.of("card", "verify"));
        all.addAll(List.of(args));
        return cmd.execute(all.toArray(String[]::new));
    }

    private Path card(final String file, final String content) throws Exception {
        return Files.writeString(dir.resolve(file), content);
    }

    private static String pin(final SigningKey key) {
        return PersonCard.fingerprint(PersonCard.keyLine(key));
    }

    /** A revocation list made by {@code ssh-keygen -k}, as a host keeps one. */
    private Path revoked(final SigningKey key) throws Exception {
        final Path pub = Files.writeString(dir.resolve("revoked.pub"), PersonCard.keyLine(key));
        final Path krl = dir.resolve("revoked.krl");
        new ProcessCommandRunner()
                .runOrFail(
                        Command.of("ssh-keygen", "-q", "-k", "-f", krl.toString(), pub.toString()));
        return krl;
    }

    @Test
    void a_card_signed_by_its_pinned_key_is_answered_with_its_fields() throws Exception {
        final Path file =
                card(
                        "alice.card",
                        PersonCard.write(
                                "alice", "Alice", "alice@example.org", first, MADE, ENDS, null));

        assertThat(verify(file.toString(), "--pinned", pin(first), "--json"))
                .as(err.toString())
                .isZero();
        final Map<?, ?> answer = (Map<?, ?>) Json.parse(out.toString());
        assertThat(answer.get("login")).isEqualTo("alice");
        assertThat(answer.get("name")).isEqualTo("Alice");
        assertThat(answer.get("email")).isEqualTo("alice@example.org");
        assertThat(answer.get("ssh")).isEqualTo(PersonCard.keyLine(first));
        assertThat(answer.get("expires")).isEqualTo("2027-10-30");
        assertThat(answer.get("revoked")).isEqualTo(false);
        assertThat(answer.get("chain")).isEqualTo(List.of(pin(first)));
    }

    @Test
    void a_change_of_key_signed_by_both_is_accepted_against_the_old_pin() throws Exception {
        final Path file =
                card(
                        "alice.card",
                        PersonCard.write("alice", "Alice", "", second, MADE, ENDS, first));

        assertThat(verify(file.toString(), "--pinned", pin(first), "--json"))
                .as(err.toString())
                .isZero();
        assertThat(((Map<?, ?>) Json.parse(out.toString())).get("chain"))
                .isEqualTo(List.of(pin(first), pin(second)));
    }

    @Test
    void a_change_signed_by_one_key_alone_is_refused_with_65() throws Exception {
        final String both = PersonCard.write("alice", "Alice", "", second, MADE, ENDS, first);
        final Path file =
                card(
                        "alice.card",
                        both.substring(0, both.indexOf("-----BEGIN", both.indexOf("-----END"))));

        assertThat(verify(file.toString(), "--pinned", pin(first), "--json")).isEqualTo(65);
        assertThat(err.toString()).contains("not signed");
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void a_chain_through_a_revoked_key_is_refused_with_65() throws Exception {
        final Path file =
                card(
                        "alice.card",
                        PersonCard.write("alice", "Alice", "", second, MADE, ENDS, first));

        assertThat(
                        verify(
                                file.toString(),
                                "--pinned",
                                pin(first),
                                "--krl",
                                revoked(first).toString(),
                                "--json"))
                .isEqualTo(65);
        assertThat(err.toString()).contains(pin(first)).contains("revoked");
    }

    @Test
    void an_empty_or_missing_revocation_list_is_unreadable_not_skipped() throws Exception {
        final Path own =
                card("alice.card", PersonCard.write("alice", "Alice", "", first, MADE, ENDS, null));

        assertThat(verify(own.toString(), "--pinned", pin(first), "--krl", "")).isEqualTo(66);
        assertThat(
                        verify(
                                own.toString(),
                                "--pinned",
                                pin(first),
                                "--krl",
                                dir.resolve("none.krl").toString()))
                .isEqualTo(66);
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void a_revocation_list_ssh_keygen_cannot_read_is_unreadable() throws Exception {
        final Path own =
                card("alice.card", PersonCard.write("alice", "Alice", "", first, MADE, ENDS, null));
        final Path bad = Files.writeString(dir.resolve("bad.krl"), "not a revocation list\n");

        assertThat(verify(own.toString(), "--pinned", pin(first), "--krl", bad.toString()))
                .isEqualTo(66);
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void a_card_whose_own_key_is_revoked_answers_revoked() throws Exception {
        final Path file =
                card("alice.card", PersonCard.write("alice", "Alice", "", first, MADE, ENDS, null));

        assertThat(
                        verify(
                                file.toString(),
                                "--pinned",
                                pin(first),
                                "--krl",
                                revoked(first).toString(),
                                "--json"))
                .as(err.toString())
                .isZero();
        final Map<?, ?> answer = (Map<?, ?>) Json.parse(out.toString());
        assertThat(answer.get("revoked")).isEqualTo(true);
        assertThat(answer.get("ssh")).isEqualTo(PersonCard.keyLine(first));
    }

    @Test
    void a_chain_of_two_changes_takes_the_earlier_card() throws Exception {
        final SigningKey third = SigningKey.generate("third");
        final Path middle =
                card(
                        "middle.card",
                        PersonCard.write("alice", "Alice", "", second, MADE, ENDS, first));
        final Path last =
                card(
                        "alice.card",
                        PersonCard.write("alice", "Alice", "", third, MADE, ENDS, second));

        assertThat(verify(last.toString(), "--pinned", pin(first), "--json")).isEqualTo(65);
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        assertThat(
                        verify(
                                last.toString(),
                                "--pinned",
                                pin(first),
                                "--earlier",
                                middle.toString(),
                                "--json"))
                .as(err.toString())
                .isZero();
        assertThat(((Map<?, ?>) Json.parse(out.toString())).get("chain"))
                .isEqualTo(List.of(pin(first), pin(second), pin(third)));
    }
}
