package org.fuin.porelo.acceptance.suite;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.machines.Ssh;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The stub git forge, run here as a scenario runs it on the machine: real git fetches a commit by
 * its id over https from it with the token, trusting its authority through the account's added
 * certificates, and is refused without the token.
 */
@Timeout(120)
class StubGitForgeTest {

    private static final String TOKEN = "stub-git-forge-test-value";

    private static final String URL_PATH = "/shop/app/pull/42";

    @TempDir Path home;

    private @Nullable StubGitForge forge;

    @AfterEach
    void stopTheForge() throws IOException {
        if (forge != null) {
            forge.stop();
        }
    }

    @Test
    void aCommitIsFetchedByItsIdWithTheTokenAndRefusedWithoutIt() throws Exception {
        final Path repository = repository();
        final StubGitForge started = new StubGitForge(new Local(home));
        forge = started;
        started.start(TOKEN);
        final String url = started.origin() + URL_PATH;

        final ForgeStub.Facts facts =
                started.serve(url, "\"" + repository + "\"", "review-head", "main");

        assertThat(facts.repository())
                .as("the repository at the pull request's own host and port")
                .isEqualTo(started.origin() + "/shop/app");
        assertThat(facts.head())
                .as("the head branch's commit")
                .isEqualTo(git(repository, "rev-parse", "review-head").strip());
        assertThat(facts.base())
                .as("the base branch's commit")
                .isEqualTo(git(repository, "rev-parse", "main").strip());
        assertThat(facts.mergeBase()).as("where the head left the base").isEqualTo(facts.base());

        final Path mirror = home.resolve("mirror.git");
        git(home, "init", "-q", "--bare", mirror.toString());
        final Result refused = fetch(mirror, facts, null);
        final Result fetched = fetch(mirror, facts, TOKEN);

        assertThat(refused.status()).as("a fetch without the token: %s", refused.err()).isNotZero();
        assertThat(fetched.status()).as("a fetch with the token: %s", fetched.err()).isZero();
        assertThat(git(mirror, "cat-file", "-t", facts.head()).strip())
                .as("the head fetched by its id")
                .isEqualTo("commit");
        assertThat(Files.readString(home.resolve("config/porelo/ca-certificates.pem")))
                .as("the forge's authority, added where Porelo's git trusts it")
                .contains("BEGIN CERTIFICATE");
    }

    @Test
    void stoppingPutsTheAccountsAuthoritiesBackAndLeavesNothing() throws Exception {
        final Path authorities = home.resolve("config/porelo/ca-certificates.pem");
        Files.createDirectories(authorities.getParent());
        final String before = Files.readString(caOfItsOwn());
        Files.writeString(authorities, before);
        final StubGitForge started = new StubGitForge(new Local(home));
        forge = started;
        started.start(TOKEN);
        assertThat(Files.readString(authorities))
                .as("the forge's authority added after the account's own")
                .startsWith(before)
                .isNotEqualTo(before);

        started.stop();
        forge = null;

        assertThat(Files.readString(authorities))
                .as("the account's authorities as they were")
                .isEqualTo(before);
        assertThat(home.resolve("porelo-stub-git-forge")).as("its work directory").doesNotExist();
    }

    /** A repository as a scenario's setup makes one: a base on main and a head branch on it. */
    private Path repository() throws Exception {
        final Path repository = home.resolve("work");
        git(home, "init", "-q", "-b", "main", repository.toString());
        commit(repository, "the base");
        git(repository, "checkout", "-q", "-b", "review-head");
        commit(repository, "the head");
        return repository;
    }

    private static void commit(final Path repository, final String message) throws Exception {
        git(
                repository,
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.org",
                "commit",
                "-q",
                "--allow-empty",
                "-m",
                message);
    }

    /** A throwaway authority of the account's own, made apart from the forge's. */
    private Path caOfItsOwn() throws Exception {
        final Path pem = home.resolve("own-ca.pem");
        final Process made =
                new ProcessBuilder(
                                "openssl",
                                "req",
                                "-x509",
                                "-newkey",
                                "ec",
                                "-pkeyopt",
                                "ec_paramgen_curve:P-256",
                                "-nodes",
                                "-days",
                                "1",
                                "-subj",
                                "/CN=own",
                                "-keyout",
                                home.resolve("own-ca.key").toString(),
                                "-out",
                                pem.toString())
                        .redirectErrorStream(true)
                        .start();
        final String said =
                new String(made.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(made.waitFor()).as("openssl: %s", said).isZero();
        return pem;
    }

    /**
     * Fetches the head by its id as Porelo's pin does, its credential in git's environment alone.
     */
    private Result fetch(
            final Path mirror, final ForgeStub.Facts facts, final @Nullable String token)
            throws Exception {
        final List<String> command =
                new ArrayList<>(
                        List.of(
                                "git",
                                "--git-dir",
                                mirror.toString(),
                                "fetch",
                                "--quiet",
                                "--no-tags",
                                "--no-write-fetch-head",
                                facts.repository(),
                                facts.head()));
        final ProcessBuilder builder = new ProcessBuilder(command);
        final Map<String, String> environment = builder.environment();
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_CONFIG_GLOBAL", "/dev/null");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put(
                "GIT_SSL_CAINFO", home.resolve("config/porelo/ca-certificates.pem").toString());
        if (token != null) {
            environment.put("GIT_CONFIG_COUNT", "1");
            environment.put("GIT_CONFIG_KEY_0", "http.extraHeader");
            environment.put(
                    "GIT_CONFIG_VALUE_0",
                    "Authorization: Basic "
                            + Base64.getEncoder()
                                    .encodeToString(
                                            ("x-access-token:" + token)
                                                    .getBytes(StandardCharsets.UTF_8)));
        }
        final Process process = builder.start();
        process.getOutputStream().close();
        final String out =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        final String err =
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("git fetch ends").isTrue();
        return new Result(process.exitValue(), out, err);
    }

    private static String git(final Path directory, final String... arguments) throws Exception {
        final List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        final ProcessBuilder builder =
                new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        final Process process = builder.start();
        final String said =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("git %s: %s", command, said).isZero();
        return said;
    }

    private record Result(int status, String out, String err) {}

    /**
     * Runs a scenario's commands here, in a home of the test's own, as the machine's shell would.
     */
    private record Local(Path home) implements ForgeStub.Script {

        @Override
        public Ssh.Output run(final String command) throws IOException {
            return run(command, "");
        }

        @Override
        public Ssh.Output run(final String command, final String stdin) throws IOException {
            final ProcessBuilder builder = new ProcessBuilder("bash", "-c", command);
            final Map<String, String> environment = builder.environment();
            environment.put("HOME", home.toString());
            environment.put("XDG_CONFIG_HOME", home.resolve("config").toString());
            environment.put("GIT_CONFIG_NOSYSTEM", "1");
            environment.put("GIT_CONFIG_GLOBAL", "/dev/null");
            final Process process = builder.start();
            try (OutputStream in = process.getOutputStream()) {
                in.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
            final String out =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            final String err =
                    new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            try {
                return new Ssh.Output(out, err, process.waitFor());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", ex);
            }
        }
    }
}
