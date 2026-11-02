package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards what a program reads of the registry verbs. Harm: moving where the organization is read
 * from changes an answer an interface or a host parses, or a trust record a machine already keeps,
 * without any test that names a field noticing. Each answer is compared with the one kept beside a
 * fixed, signed registry; {@link #make_the_fixture} made both, with the code as it was before the
 * organization's API.
 */
class RegistryAnswersFixedTest {

    private static final String DEMO = "ssh://git@example.org/demo.git";

    @TempDir Path dir;

    private RegistryCheckout registry;

    @BeforeEach
    void checkout() throws IOException {
        registry = new RegistryCheckout(dir);
    }

    @AfterEach
    void tidyUp() throws Exception {
        registry.close();
        PoreloContext.forgetAsked();
    }

    /** The registry the fixture holds, in the checkout, and the forge's answers about it. */
    private void fixed() throws IOException, URISyntaxException {
        registry.git("fetch", "-q", fixture("registry.bundle").toString(), "main");
        registry.git("reset", "-q", "--hard", "FETCH_HEAD");
        answered(registry, Files.readString(fixture("keys.txt")));
    }

    private static void answered(final RegistryCheckout registry, final String keys) {
        registry.forge.owners.put("michael", true);
        for (final String line : keys.split("\n")) {
            final String[] fields = line.split(" ", 2);
            registry.forge.keys.put(fields[0], List.of(fields[1]));
        }
    }

    private static Path fixture(final String name) throws URISyntaxException {
        return Path.of(
                Objects.requireNonNull(
                                RegistryAnswersFixedTest.class.getResource(
                                        "/registry-answers/" + name),
                                name)
                        .toURI());
    }

    private static String kept(final String name) throws IOException, URISyntaxException {
        return Files.readString(fixture(name), StandardCharsets.UTF_8);
    }

    /**
     * An answer as a program reads it: every value and every list's order, not the order of an
     * object's fields, which differs from one run of the same code to the next.
     */
    private static Object parsed(final String answer) {
        return Json.parse(answer);
    }

    /** A git that fetches the access repository from disk, and no configuration of this machine. */
    private static PoreloContext serving(final RegistryCheckout registry, final Path access) {
        final CommandRunner local =
                command -> {
                    final List<String> arguments = new ArrayList<>();
                    for (final String argument : command.arguments()) {
                        arguments.add(argument.equals(DEMO) ? access.toString() : argument);
                    }
                    final Map<String, String> environment =
                            new HashMap<>(
                                    command.inheritsEnvironment()
                                            ? Map.of(
                                                    "GIT_CONFIG_GLOBAL",
                                                    "/dev/null",
                                                    "GIT_CONFIG_NOSYSTEM",
                                                    "1")
                                            : Map.of());
                    environment.putAll(command.environment());
                    return new ProcessCommandRunner()
                            .run(
                                    new Command(
                                            arguments,
                                            command.workingDirectory(),
                                            environment,
                                            command.input(),
                                            command.inheritsEnvironment(),
                                            command.binaryInput()));
                };
        return new PoreloContext(local, registry.paths, args -> 0, registry.environment());
    }

    private Path accessRepository() throws URISyntaxException {
        final Path access = dir.resolve("demo-access");
        registry.runner.runOrFail(
                Command.of(
                        "git",
                        "clone",
                        "-q",
                        "-b",
                        "main",
                        fixture("access.bundle").toString(),
                        access.toString()));
        return access;
    }

    @Test
    void registry_read_as_json_and_the_trust_record_are_those_kept() throws Exception {
        fixed();

        assertThat(registry.registry("read", registry.repo.toString(), "--json")).isZero();

        assertThat(parsed(registry.out.toString())).isEqualTo(parsed(kept("read.json")));
        assertThat(registry.err.toString()).isEqualTo(kept("read.err"));
        assertThat(parsed(Files.readString(registry.record())))
                .isEqualTo(parsed(kept("record.json")));
    }

    @Test
    void registry_read_as_text_is_that_kept() throws Exception {
        fixed();

        assertThat(registry.registry("read", registry.repo.toString())).isZero();

        assertThat(registry.out.toString()).isEqualTo(kept("read.txt"));
    }

    @Test
    void registry_verify_as_json_is_that_kept() throws Exception {
        assertThat(
                        registry.registry(
                                "verify",
                                "--repository",
                                fixture("verify.bundle").toString(),
                                "--host",
                                "wharf6",
                                "--pinned",
                                kept("pinned.txt").strip(),
                                "--json"))
                .as(registry.err.toString())
                .isZero();

        assertThat(parsed(registry.out.toString())).isEqualTo(parsed(kept("verify.json")));
    }

    /**
     * Makes the fixture anew: only when what a program reads is meant to change, as a change named
     * and answered for; never to make the comparisons above pass.
     */
    @Test
    @EnabledIfSystemProperty(named = "registry.answers.make", matches = ".+")
    void make_the_fixture() throws Exception {
        final Path to =
                Files.createDirectories(Path.of(System.getProperty("registry.answers.make")));
        final String pinned = registry.pin("hank");
        registry.founded();
        registry.write("hosts/wharf6/card", registry.hostCard("wharf6", "hank"));
        registry.write("hosts/wharf6/access.yml", registry.access("demo"));
        registry.commit("hank");
        registry.write("people/alice.card", longLived("alice"));
        registry.commit("alice");
        registry.write("people/bob.card", longLived("bob"));
        registry.commit("bob");
        registry.write("people/alice-new.card", longLived("alice-new"));
        registry.commit("alice-new");
        registry.git("bundle", "create", "-q", to.resolve("verify.bundle").toString(), "--all");
        // A commit the rule refuses, and a host's access file the read therefore does not know.
        registry.write("hosts/other/access.yml", registry.access("other"));
        registry.commit("bob");
        registry.git("bundle", "create", "-q", to.resolve("registry.bundle").toString(), "--all");
        final String keys =
                String.join(
                                "\n",
                                List.of(
                                        "michael " + registry.key("michael"),
                                        "alice " + registry.key("alice"),
                                        "bob " + registry.key("bob")))
                        + "\n";
        Files.writeString(to.resolve("keys.txt"), keys);
        Files.writeString(to.resolve("pinned.txt"), pinned + "\n");

        final Path access = Files.createDirectory(dir.resolve("access-made"));
        madeAccess(access);
        registry.runner.runOrFail(
                Command.of(
                        "git",
                        "-C",
                        access.toString(),
                        "bundle",
                        "create",
                        "-q",
                        to.resolve("access.bundle").toString(),
                        "--all"));

        // Each answer from a checkout of the bundles alone, as the comparisons above read them.
        answers(to, keys);
    }

    private void madeAccess(final Path access) throws IOException {
        made(access, "init", "-q", "-b", "main");
        Files.writeString(
                access.resolve("access.yml"),
                "source: git@example.org:acme/demo.git\ntransports:\n  matrix: {homeserver:"
                        + " \"https://wharf6.example.org:8448\", room:"
                        + " \"#demo:wharf6.example.org\"}\npersons:\n"
                        + person("alice", "admin")
                        + person("bob", "developer"));
        Files.createDirectories(access.resolve("cards"));
        Files.writeString(access.resolve("cards/alice.card"), longLived("alice"));
        Files.writeString(access.resolve("cards/bob.card"), longLived("bob"));
        made(access, "add", "-A");
        made(
                access,
                "-c",
                "user.name=Access",
                "-c",
                "user.email=access@example.org",
                "-c",
                "gpg.format=ssh",
                "-c",
                "user.signingkey=" + registry.keyFiles.get("alice"),
                "commit",
                "-q",
                "-S",
                "-m",
                "access");
    }

    private void made(final Path repository, final String... arguments) {
        final List<String> all = new ArrayList<>(List.of("git", "-C", repository.toString()));
        all.addAll(List.of(arguments));
        registry.runner.runOrFail(
                Command.of(all)
                        .withEnvironment(
                                Map.of(
                                        "GIT_CONFIG_GLOBAL",
                                        "/dev/null",
                                        "GIT_CONFIG_NOSYSTEM",
                                        "1")));
    }

    private String person(final String login, final String role) {
        return "  "
                + login
                + ":\n    fingerprint: \""
                + registry.pin(login)
                + "\"\n    role: "
                + role
                + "\n    hosts: [wharf6]\n";
    }

    /** A card that outlives this comparison: the fixture must not expire. */
    private String longLived(final String login) {
        return PersonCard.write(
                login,
                "Alice",
                "",
                registry.keys.get(login),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2999, 1, 1),
                null);
    }

    private void answers(final Path to, final String keys) throws Exception {
        final String bundle = to.resolve("registry.bundle").toString();
        final Path read = Files.createDirectory(dir.resolve("read"));
        try (RegistryCheckout again = new RegistryCheckout(read)) {
            again.git("fetch", "-q", bundle, "main");
            again.git("reset", "-q", "--hard", "FETCH_HEAD");
            answered(again, keys);
            assertThat(again.registry("read", again.repo.toString(), "--json")).isZero();
            Files.writeString(to.resolve("read.json"), again.out.toString());
            Files.writeString(to.resolve("read.err"), again.err.toString());
            Files.writeString(to.resolve("record.json"), Files.readString(again.record()));
        }
        final Path text = Files.createDirectory(dir.resolve("text"));
        try (RegistryCheckout again = new RegistryCheckout(text)) {
            again.git("fetch", "-q", bundle, "main");
            again.git("reset", "-q", "--hard", "FETCH_HEAD");
            answered(again, keys);
            assertThat(again.registry("read", again.repo.toString())).isZero();
            Files.writeString(to.resolve("read.txt"), again.out.toString());
        }
        final Path joinable = Files.createDirectory(dir.resolve("joinable"));
        try (RegistryCheckout again = new RegistryCheckout(joinable)) {
            again.git("fetch", "-q", bundle, "main");
            again.git("reset", "-q", "--hard", "FETCH_HEAD");
            answered(again, keys);
            final Path access = joinable.resolve("demo-access");
            again.runner.runOrFail(
                    Command.of(
                            "git",
                            "clone",
                            "-q",
                            "-b",
                            "main",
                            to.resolve("access.bundle").toString(),
                            access.toString()));
            assertThat(
                            again.registry(
                                    serving(again, access),
                                    "joinable",
                                    again.repo.toString(),
                                    "--login",
                                    "bob",
                                    "--json"))
                    .as(again.err.toString())
                    .isZero();
            Files.writeString(to.resolve("joinable.json"), again.out.toString());
            Files.writeString(to.resolve("joinable.err"), again.err.toString());
        }
        assertThat(
                        registry.registry(
                                "verify",
                                "--repository",
                                to.resolve("verify.bundle").toString(),
                                "--host",
                                "wharf6",
                                "--pinned",
                                Files.readString(to.resolve("pinned.txt")).strip(),
                                "--json"))
                .as(registry.err.toString())
                .isZero();
        Files.writeString(to.resolve("verify.json"), registry.out.toString());
    }
}
