package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link ConfigurationGate}, against real git and real ssh keys.
 *
 * <p>Not with a fake runner: what is being checked is whether this machine agrees with git about
 * what a signature means, and a fake would answer that question with itself. The commit object's
 * canonicalisation is git's, and so is the verdict.
 */
class ConfigurationGateTest {

    private final CommandRunner runner =
            command ->
                    new ProcessCommandRunner()
                            .run(
                                    command.withEnvironment(
                                            java.util.Map.of(
                                                    "GIT_CONFIG_GLOBAL",
                                                    "/dev/null",
                                                    "GIT_CONFIG_NOSYSTEM",
                                                    "1")));

    private void run(final Path where, final String... command) {
        final java.util.List<String> all = new java.util.ArrayList<>();
        all.add("git");
        all.add("-C");
        all.add(where.toString());
        all.addAll(java.util.List.of(command));
        final var result = runner.run(Command.of(all));
        assertThat(result.exitCode())
                .as(String.join(" ", all) + " -> " + result.standardError())
                .isZero();
    }

    /** A key pair, and the allowed_signers line that pins it. */
    private Path key(final Path dir, final String name) {
        runner.run(
                Command.of(
                        "ssh-keygen",
                        "-q",
                        "-t",
                        "ed25519",
                        "-N",
                        "",
                        "-C",
                        name,
                        "-f",
                        dir.resolve(name).toString()));
        return dir.resolve(name);
    }

    private Path pin(final Path dir, final String principal, final Path key) throws IOException {
        final String pub = Files.readString(Path.of(key + ".pub")).strip();
        final String[] fields = pub.split("\\s+");
        final Path signers = dir.resolve("configuration_signers");
        Files.writeString(signers, principal + " " + fields[0] + " " + fields[1] + "\n");
        return signers;
    }

    /** A repository with one commit, signed by that key or not signed at all. */
    private Path repository(final Path dir, final Path signingKey) throws IOException {
        final Path repo = dir.resolve("project");
        Files.createDirectories(repo);
        run(repo, "init", "-q", ".");
        run(repo, "config", "user.email", "operator@example.org");
        run(repo, "config", "user.name", "Operator");
        Files.writeString(repo.resolve("project.yml"), "project:\n  name: demo\n");
        run(repo, "add", "project.yml");
        if (signingKey == null) {
            run(repo, "commit", "-q", "--no-gpg-sign", "-m", "configuration");
        } else {
            run(repo, "config", "gpg.format", "ssh");
            run(repo, "config", "user.signingkey", signingKey.toString());
            run(repo, "commit", "-q", "-S", "-m", "configuration");
        }
        return repo;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "corrupt\n"})
    void a_damaged_krl_never_authorizes_a_signature(final String content, @TempDir final Path dir)
            throws IOException {
        // OpenSSH's text revocation fallback treats an empty file as revoking nothing.
        final Path key = key(dir, "operator");
        final Path repo = repository(dir, key);
        final Path krl = dir.resolve("revoked.krl");
        Files.writeString(krl, content);

        final ConfigurationGate.Verdict verdict =
                new ConfigurationGate(runner, pin(dir, "demo", key), krl).verify(repo, "HEAD");

        assertThat(verdict.outcome())
                .as(verdict.detail())
                .isEqualTo(ConfigurationGate.Outcome.UNREADABLE);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void an_inaccessible_anchor_is_unreadable_rather_than_absent(
            final boolean denied, @TempDir final Path dir) throws IOException {
        // A broken trust file needs repair, rather than silently becoming an unconfigured account.
        final Path key = key(dir, "operator");
        final Path repo = repository(dir, key);
        final Path policy = Files.createDirectory(dir.resolve("policy"));
        final Path signers = pin(policy, "demo", key);
        final var permissions = Files.getPosixFilePermissions(policy);
        try {
            if (denied) {
                Files.setPosixFilePermissions(policy, java.util.Set.of());
            } else {
                Files.delete(signers);
                Files.createDirectory(signers);
            }
            final var verdict = new ConfigurationGate(runner, signers).verify(repo, "HEAD");
            assertThat(verdict.outcome())
                    .as(verdict.detail())
                    .isEqualTo(ConfigurationGate.Outcome.UNREADABLE);
        } finally {
            Files.setPosixFilePermissions(policy, permissions);
        }
    }

    @Test
    void applies_what_the_pinned_key_signed(@TempDir final Path dir) throws IOException {
        final Path key = key(dir, "operator");
        final Path repo = repository(dir, key);

        final ConfigurationGate.Verdict verdict =
                new ConfigurationGate(runner, pin(dir, "operator", key)).verify(repo, "HEAD");

        assertThat(verdict.outcome()).isEqualTo(ConfigurationGate.Outcome.VERIFIED);
        assertThat(verdict.mayApply()).isTrue();
        assertThat(verdict.signer()).isEqualTo("operator");
        assertThat(verdict.commit()).hasSize(40);
    }

    @Test
    void refuses_a_commit_nobody_signed(@TempDir final Path dir) throws IOException {
        final Path key = key(dir, "operator");
        final Path repo = repository(dir, null);

        final ConfigurationGate.Verdict verdict =
                new ConfigurationGate(runner, pin(dir, "operator", key)).verify(repo, "HEAD");

        assertThat(verdict.outcome()).isEqualTo(ConfigurationGate.Outcome.NOT_SIGNED);
        assertThat(verdict.detail()).contains("no signature");
        assertThat(verdict.commit()).as("it still says which commit it refused").hasSize(40);
    }

    /**
     * Being signed is not the test; being signed by the right key is. A valid signature by anybody
     * else is the case this exists for.
     */
    @Test
    void refuses_a_good_signature_by_a_key_that_is_not_pinned(@TempDir final Path dir)
            throws IOException {
        final Path pinned = key(dir, "operator");
        final Path somebody = key(dir, "somebody-else");
        final Path repo = repository(dir, somebody);

        final ConfigurationGate.Verdict verdict =
                new ConfigurationGate(runner, pin(dir, "operator", pinned)).verify(repo, "HEAD");

        assertThat(verdict.outcome()).isEqualTo(ConfigurationGate.Outcome.UNKNOWN_KEY);
        assertThat(verdict.mayApply()).isFalse();
        assertThat(verdict.detail()).contains("not pinned on this machine");
    }

    /** A machine that was never given an anchor verifies nothing, and says so as its own state. */
    @Test
    void a_machine_with_nothing_pinned_applies_nothing(@TempDir final Path dir) throws IOException {
        final Path repo = repository(dir, key(dir, "operator"));

        final ConfigurationGate.Verdict verdict =
                new ConfigurationGate(runner, dir.resolve("never-written")).verify(repo, "HEAD");

        assertThat(verdict.outcome()).isEqualTo(ConfigurationGate.Outcome.NO_ANCHOR);
        assertThat(verdict.detail()).contains("no key is pinned");
    }

    @Test
    void says_so_when_the_commit_is_not_there(@TempDir final Path dir) throws IOException {
        final Path key = key(dir, "operator");
        final Path repo = repository(dir, key);

        final ConfigurationGate.Verdict verdict =
                new ConfigurationGate(runner, pin(dir, "operator", key))
                        .verify(repo, "no-such-ref");

        assertThat(verdict.outcome()).isEqualTo(ConfigurationGate.Outcome.UNREADABLE);
    }

    @Test
    void aKeyPinnedForOneProjectDoesNotSignForAnother(@TempDir final Path dir) throws IOException {

        // Every project was checked against one file, and any principal in it was accepted: a key
        // pinned for a public project verified a commit served for another one.
        final Path theirs = key(dir, "alpha");
        final Path ours = key(dir, "beta");
        final Path signers = pin(dir, "alpha", theirs);
        final String[] fields = Files.readString(Path.of(ours + ".pub")).strip().split("\\s+");
        Files.writeString(
                signers,
                "beta " + fields[0] + " " + fields[1] + "\n",
                java.nio.file.StandardOpenOption.APPEND);
        final Path repo = repository(dir, theirs);

        assertThat(new ConfigurationGate(runner, signers).verify(repo, "HEAD", "beta").outcome())
                .isEqualTo(ConfigurationGate.Outcome.UNKNOWN_KEY);
        assertThat(new ConfigurationGate(runner, signers).verify(repo, "HEAD", "alpha").outcome())
                .isEqualTo(ConfigurationGate.Outcome.VERIFIED);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"valid-before", "Valid-Before", "VALID-BEFORE"})
    void a_retired_entry_is_history_and_never_authority(
            final String option, @TempDir final Path dir) throws IOException {
        // A valid-before in the future must still never authorize accepting a new commit.
        final Path key = key(dir, "operator");
        final Path repo = repository(dir, key);
        final Path signers = pin(dir, "demo", key);
        Files.writeString(
                signers,
                Files.readString(signers)
                        .replace(
                                "demo ",
                                "demo namespaces=\"git\"," + option + "=\"29990101000000Z\" "));

        final var verdict = new ConfigurationGate(runner, signers).verify(repo, "HEAD", "demo");

        assertThat(verdict.outcome()).isEqualTo(ConfigurationGate.Outcome.UNKNOWN_KEY);
        assertThat(PinnedSigners.of(signers)).isEmpty();
        final var historical =
                runner.run(
                        Command.of(
                                "git",
                                "-C",
                                repo.toString(),
                                "-c",
                                "gpg.format=ssh",
                                "-c",
                                "gpg.ssh.allowedSignersFile=" + signers,
                                "verify-commit",
                                "HEAD"));
        assertThat(historical.successful()).as(historical.standardError()).isTrue();
    }

    @Test
    void active_options_are_read_and_preserved(@TempDir final Path dir) throws IOException {
        // Options must not shift the public key fields or lose their namespace restriction.
        final Path key = key(dir, "operator");
        final Path repo = repository(dir, key);
        final Path signers = pin(dir, "demo", key);
        Files.writeString(
                signers,
                Files.readString(signers).replace("demo ", "demo namespaces=\"git,ssh\" "));
        assertThat(new ConfigurationGate(runner, signers).verify(repo, "HEAD", "demo").mayApply())
                .isTrue();
        assertThat(PinnedSigners.of(signers).get("demo")).hasSize(1);
        Files.writeString(signers, Files.readString(signers).replace("git,ssh", "ssh"));
        assertThat(new ConfigurationGate(runner, signers).verify(repo, "HEAD", "demo").mayApply())
                .isFalse();
    }

    @Test
    void a_revoked_key_in_force_is_refused(@TempDir final Path dir) throws IOException {
        // A valid signature and an active pin cannot override revocation.
        final Path key = key(dir, "operator");
        final Path repo = repository(dir, key);
        final Path signers = pin(dir, "demo", key);
        final Path krl = dir.resolve("revoked.krl");
        final var made =
                runner.run(
                        Command.of("ssh-keygen", "-q", "-k", "-f", krl.toString(), key + ".pub"));
        assertThat(made.successful()).as(made.standardError()).isTrue();

        final var verdict =
                new ConfigurationGate(runner, signers, krl).verify(repo, "HEAD", "demo");

        assertThat(verdict.outcome()).isEqualTo(ConfigurationGate.Outcome.UNKNOWN_KEY);
        assertThat(verdict.commit()).hasSize(40);
        assertThat(verdict.signer()).startsWith("SHA256:");
    }

    @Test
    void an_unreadable_configured_revocation_list_refuses(@TempDir final Path dir)
            throws IOException {
        // A broken policy input must not silently disable revocations.
        final Path key = key(dir, "operator");
        final Path repo = repository(dir, key);
        final Path signers = pin(dir, "demo", key);
        final var verdict =
                new ConfigurationGate(runner, signers, dir.resolve("missing.krl"))
                        .verify(repo, "HEAD");
        assertThat(verdict.outcome()).isEqualTo(ConfigurationGate.Outcome.UNREADABLE);
        assertThat(verdict.commit()).hasSize(40);
        assertThat(verdict.detail()).contains("revocation list");
    }
}
