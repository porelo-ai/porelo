package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.project.ProjectSigners;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Guards a project's backup key: filed among the signers by the one signer, used for the one change
 * it may make, and refused for anything else, by the verb and by the follow alike.
 */
// Every test drives git and an ssh-agent, either of which can wait for ever on a mistake.
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ProjectBackupKeyTest {
    @TempDir Path dir;
    private RegistryCheckout keys;
    private Path origin;
    private Path work;
    private boolean terminal = true;

    /** How often the verb ran ssh-keygen on the terminal. */
    private int keygens;

    @BeforeEach
    void followed() throws Exception {
        keys = new RegistryCheckout(dir);
        origin = dir.resolve("demo.git");
        work = dir.resolve("work");
        git(dir, "init", "-q", "--bare", "-b", "main", origin.toString());
        git(dir, "init", "-q", "-b", "main", work.toString());
        git(work, "config", "user.name", "Admin");
        git(work, "config", "user.email", "admin@example.org");
        git(work, "config", "gpg.format", "ssh");
        commit("first", "michael", "    - \"" + keys.key("michael") + "\"\n");
        assertThat(follow(keys.key("michael")).outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
    }

    @AfterEach
    void tidyUp() throws Exception {
        keys.close();
        PoreloContext.forgetAsked();
    }

    private String git(final Path where, final String... args) {
        final List<String> all = new ArrayList<>(List.of("git", "-C", where.toString()));
        all.addAll(List.of(args));
        final CommandResult result = keys.runner.run(Command.of(all));
        assertThat(result.successful()).as(all + ": " + result.standardError()).isTrue();
        return result.standardOutput().strip();
    }

    private String file(final String description, final String signers) {
        return """
        project:
          name: demo
          description: %s
          security_class: guarded
          signers:
        %simage:
          base_image: ubuntu:24.04
        """
                .formatted(description, signers);
    }

    /** Commits a project file in the person's clone, signed by a key file, and pushes it. */
    private String commit(final String description, final String signer, final String signers)
            throws Exception {
        return commitText(file(description, signers), keys.keyFiles.get(signer));
    }

    private String commitText(final String text, final Path signingKey) throws Exception {
        if (keys.runner
                .run(
                        Command.of(
                                "git",
                                "-C",
                                origin.toString(),
                                "rev-parse",
                                "-q",
                                "--verify",
                                "main"))
                .successful()) {
            git(work, "fetch", "-q", origin.toString(), "main");
            git(work, "reset", "-q", "--hard", "FETCH_HEAD");
        }
        Files.writeString(work.resolve("project.yml"), text);
        git(work, "add", "project.yml");
        git(work, "-c", "user.signingkey=" + signingKey, "commit", "-q", "-S", "-m", "change");
        git(work, "push", "-q", origin.toString(), "HEAD:main");
        return git(work, "rev-parse", "HEAD");
    }

    private Reconcile.Result follow(final String signedBy) throws Exception {
        return new FollowSignedBy(keys.context())
                .follow("demo", origin.toString(), signedBy, false, false)
                .result();
    }

    private String head() {
        return git(origin, "rev-parse", "main");
    }

    /** Runs {@code porelo identity backup-key ...}; ssh-keygen gets an empty passphrase. */
    private int backupKey(final String... args) {
        keys.out.getBuffer().setLength(0);
        keys.err.getBuffer().setLength(0);
        final CommandLine cli =
                PoreloCli.commandLine(
                        keys.context(
                                command -> {
                                    keygens++;
                                    final List<String> keygen = new ArrayList<>(command);
                                    keygen.addAll(List.of("-N", ""));
                                    return keys.runner.run(Command.of(keygen)).exitCode();
                                }));
        cli.setOut(new PrintWriter(keys.out));
        cli.setErr(new PrintWriter(keys.err));
        final IdentityBackupKeyCommand verb =
                (IdentityBackupKeyCommand)
                        cli.getSubcommands()
                                .get("identity")
                                .getSubcommands()
                                .get("backup-key")
                                .getCommand();
        verb.forges = (address, token) -> keys.forge;
        verb.terminal = () -> terminal;
        final List<String> all = new ArrayList<>(List.of("identity", "backup-key"));
        all.addAll(List.of(args));
        return cli.execute(all.toArray(String[]::new));
    }

    private List<Object> soleWithoutBackup() {
        keys.out.getBuffer().setLength(0);
        final CommandLine cli = PoreloCli.commandLine(keys.context());
        cli.setOut(new PrintWriter(keys.out));
        cli.setErr(new PrintWriter(keys.err));
        ((IdentityShowCommand)
                                cli.getSubcommands()
                                        .get("identity")
                                        .getSubcommands()
                                        .get("show")
                                        .getCommand())
                        .forges =
                (address, token) -> keys.forge;
        assertThat(cli.execute("identity", "show", "--json")).isZero();
        return new ArrayList<>((List<?>) keys.answer().get("sole_without_backup"));
    }

    private Path made() throws Exception {
        IdentityRun.keep(keys, keys.card("michael", "michael"));
        keys.agent("michael");
        final Path file = dir.resolve("backup");
        assertThat(backupKey("--project", "demo", "--out", file.toString(), "--id", "bk-1"))
                .as(keys.err.toString())
                .isZero();
        assertThat(follow(null).outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        return file;
    }

    @Test
    void the_one_signer_files_a_backup_key_and_is_no_longer_named_alone() throws Exception {
        IdentityRun.keep(keys, keys.card("michael", "michael"));
        keys.agent("michael");
        assertThat(soleWithoutBackup())
                .containsExactly(
                        Map.of(
                                "role",
                                "project-admin",
                                "scope",
                                Map.of("kind", "project", "name", "demo")));
        final Path file = dir.resolve("backup");

        assertThat(
                        backupKey(
                                "--project",
                                "demo",
                                "--out",
                                file.toString(),
                                "--id",
                                "bk-1",
                                "--json"))
                .as(keys.err.toString())
                .isZero();
        final Map<?, ?> answer = keys.answer();
        assertThat(answer.get("scope")).isEqualTo(Map.of("kind", "project", "name", "demo"));
        assertThat(answer.get("pushed")).isEqualTo(true);
        assertThat(answer.get("revision")).isEqualTo(head());
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                .isEqualTo("rw-------");
        final List<ProjectSigners.Signer> entries =
                ProjectSigners.entries(git(origin, "show", "main:project.yml"), "project.yml");
        assertThat(entries).hasSize(2);
        assertThat(entries.getLast().backup()).isTrue();
        assertThat(entries.getLast().backs()).isEqualTo(keys.pin("michael"));
        assertThat(entries.getLast().fingerprint()).isEqualTo(answer.get("fingerprint"));
        assertThat(entries.getLast().since()).matches("\\d{4}-\\d{2}-\\d{2}");

        final Path again = dir.resolve("again");
        assertThat(
                        backupKey(
                                "--project",
                                "demo",
                                "--out",
                                again.toString(),
                                "--id",
                                "bk-1",
                                "--json"))
                .as("the same id answers the same commit")
                .isZero();
        assertThat(keys.answer().get("revision")).isEqualTo(answer.get("revision"));
        assertThat(again).as("and makes no second key").doesNotExist();

        final Reconcile.Result applied = follow(null);
        assertThat(applied.outcome()).as(applied.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(
                        backupKey(
                                "--project",
                                "demo",
                                "--out",
                                again.toString(),
                                "--id",
                                "bk-1",
                                "--json"))
                .as("the same id answers the same commit once the follow took it: " + keys.err)
                .isZero();
        assertThat(keys.answer().get("revision")).isEqualTo(answer.get("revision"));
        assertThat(keys.answer().get("fingerprint")).isEqualTo(answer.get("fingerprint"));
        assertThat(again).doesNotExist();
        assertThat(soleWithoutBackup()).isEmpty();
        assertThat(
                        new SignerHandover(
                                        keys.runner, keys.paths.projects().configurationSigners())
                                .pinned("demo"))
                .as("a backup key is pinned for no change")
                .containsExactly(keys.key("michael"));
        assertThat(backupKey("--project", "demo", "--out", again.toString(), "--json"))
                .isEqualTo(65);
        assertThat(keys.answer().get("reason")).isEqualTo("backup-exists");
        assertThat(again).doesNotExist();
    }

    @Test
    void nobody_but_the_one_signer_and_nothing_without_a_terminal_or_over_a_file()
            throws Exception {
        IdentityRun.keep(keys, keys.card("alice", "alice"));
        keys.agent("michael");
        final String out = dir.resolve("a").toString();
        assertThat(backupKey("--project", "demo", "--out", out, "--json")).isEqualTo(65);
        assertThat(keys.answer().get("reason")).isEqualTo("not-sole");

        IdentityRun.keep(keys, keys.card("michael", "michael"));
        final Path taken = Files.writeString(dir.resolve("taken"), "mine");
        assertThat(backupKey("--project", "demo", "--out", taken.toString(), "--json"))
                .isEqualTo(65);
        assertThat(keys.answer().get("reason")).isEqualTo("file-exists");
        assertThat(Files.readString(taken)).isEqualTo("mine");
        terminal = false;
        assertThat(backupKey("--project", "demo", "--out", out, "--json")).isEqualTo(65);
        assertThat(keys.answer().get("reason")).isEqualTo("no-terminal");
        terminal = true;
        assertThat(backupKey("--project", "demo", "--out", out, "--base", "0".repeat(40), "--json"))
                .isEqualTo(65);
        assertThat(keys.answer().get("reason")).isEqualTo("base-moved");
        assertThat(backupKey("--project", "other", "--out", out, "--json"))
                .as("a project not followed")
                .isEqualTo(66);
        keys.close();
        keys.agent("alice");
        assertThat(backupKey("--project", "demo", "--out", out, "--json")).isEqualTo(65);
        assertThat(keys.answer().get("reason")).isEqualTo("not-signer");
        assertThat(dir.resolve("a")).doesNotExist();

        commit(
                "two signers",
                "michael",
                "    - \"" + keys.key("michael") + "\"\n    - \"" + keys.key("bob") + "\"\n");
        assertThat(follow(null).outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(backupKey("--project", "demo", "--out", out, "--json")).isEqualTo(65);
        assertThat(keys.answer().get("reason")).isEqualTo("not-sole");
    }

    @Test
    void the_backup_key_replaces_the_key_it_backs_and_the_follow_takes_it() throws Exception {
        final Path file = made();
        keys.close();
        final Path card =
                Files.writeString(dir.resolve("new.card"), keys.card("michael", "alice-new"));

        assertThat(
                        backupKey(
                                "--project",
                                "demo",
                                "--use",
                                file.toString(),
                                "--card",
                                card.toString(),
                                "--id",
                                "lost-1",
                                "--json"))
                .as(keys.err.toString())
                .isZero();
        final Map<?, ?> answer = keys.answer();
        assertThat(answer.get("replaced")).isEqualTo(keys.pin("michael"));
        assertThat(answer.get("by")).isEqualTo(keys.pin("alice-new"));
        assertThat(answer.get("pushed")).isEqualTo(true);
        assertThat(answer.get("revision")).isEqualTo(head());

        final Reconcile.Result applied = follow(null);
        assertThat(applied.outcome()).as(applied.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(
                        new SignerHandover(
                                        keys.runner, keys.paths.projects().configurationSigners())
                                .pinned("demo"))
                .as("the old key is retired, the new one signs")
                .containsExactly(keys.key("alice-new"));
        assertThat(
                        backupKey(
                                "--project",
                                "demo",
                                "--use",
                                file.toString(),
                                "--card",
                                card.toString(),
                                "--id",
                                "lost-1",
                                "--json"))
                .as("the same id answers the same commit once the follow took it: " + keys.err)
                .isZero();
        assertThat(keys.answer()).isEqualTo(answer);
        assertThat(
                        ProjectSigners.entries(
                                        git(origin, "show", "main:project.yml"), "project.yml")
                                .getLast()
                                .backs())
                .as("the backup key stays, behind the new key")
                .isEqualTo(keys.pin("alice-new"));
    }

    @Test
    void a_key_that_is_not_the_backup_or_does_not_open_or_is_revoked_replaces_nothing()
            throws Exception {
        final Path file = made();
        final Path card =
                Files.writeString(dir.resolve("new.card"), keys.card("michael", "alice-new"));
        final String before = head();
        assertThat(
                        backupKey(
                                "--project",
                                "demo",
                                "--use",
                                keys.keyFiles.get("bob").toString(),
                                "--card",
                                card.toString(),
                                "--id",
                                "lost-2",
                                "--json"))
                .isEqualTo(65);
        assertThat(keys.answer().get("reason")).isEqualTo("not-the-backup");
        final Path locked = IdentityBackupKeyCommandTest.misread(file, dir.resolve("locked"));
        assertThat(
                        backupKey(
                                "--project",
                                "demo",
                                "--use",
                                locked.toString(),
                                "--card",
                                card.toString(),
                                "--id",
                                "lost-3",
                                "--json"))
                .isEqualTo(65);
        assertThat(keys.answer().get("reason")).isEqualTo("wrong-passphrase");
        final Path other = Files.writeString(dir.resolve("other.card"), keys.card("bob", "bob"));
        assertThat(
                        backupKey(
                                "--project",
                                "demo",
                                "--use",
                                file.toString(),
                                "--card",
                                other.toString(),
                                "--id",
                                "lost-4",
                                "--json"))
                .isEqualTo(65);
        assertThat(keys.answer().get("reason")).isEqualTo("card-login");
        IdentityRun.revoke(keys, "alice-new");
        assertThat(
                        backupKey(
                                "--project",
                                "demo",
                                "--use",
                                file.toString(),
                                "--card",
                                card.toString(),
                                "--id",
                                "lost-5",
                                "--json"))
                .isEqualTo(65);
        assertThat(keys.answer().get("reason")).isEqualTo("revoked");
        assertThat(head()).as("nothing was pushed").isEqualTo(before);
    }

    @Test
    void an_id_on_a_commit_the_follow_never_checked_answers_no_backup_key() throws Exception {
        IdentityRun.keep(keys, keys.card("michael", "michael"));
        keys.agent("michael");
        git(work, "fetch", "-q", origin.toString(), "main");
        git(work, "reset", "-q", "--hard", "FETCH_HEAD");
        Files.writeString(work.resolve("README.md"), "no protected file\n");
        git(work, "add", "README.md");
        final String digest =
                "sha256:"
                        + java.util.HexFormat.of()
                                .formatHex(
                                        java.security.MessageDigest.getInstance("SHA-256")
                                                .digest(
                                                        ("make\ndemo\n" + keys.pin("michael"))
                                                                .getBytes(
                                                                        java.nio.charset
                                                                                .StandardCharsets
                                                                                .UTF_8)));
        git(
                work,
                "commit",
                "-q",
                "-m",
                "unsigned\n\nPorelo-Change-Id: bk-1\nPorelo-Change-Digest: " + digest);
        git(work, "push", "-q", origin.toString(), "HEAD:main");
        final Path file = dir.resolve("backup");

        final int code =
                backupKey("--project", "demo", "--out", file.toString(), "--id", "bk-1", "--json");

        assertThat(code).as("no backup key is answered as filed: " + keys.out).isEqualTo(65);
        assertThat(file).doesNotExist();
        assertThat(ProjectSigners.backups(entriesAtHead())).isEmpty();
    }

    private static String digest(final String... parts) throws Exception {
        return "sha256:"
                + java.util.HexFormat.of()
                        .formatHex(
                                java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                String.join("\n", parts)
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8)));
    }

    /**
     * A project "early" whose first commit, signed by the first signer, names the first signers;
     * whose second, unsigned and before the first follow, makes the change an id names; and whose
     * tip, signed by the tip's signer, only changes the description. The first follow takes the tip
     * signed by that signer, and checks no commit before it.
     */
    private Path early(
            final String first,
            final String firstSigner,
            final String planted,
            final String message,
            final String tipSigner)
            throws Exception {
        final Path repository = dir.resolve("early.git");
        final Path forger = dir.resolve("early-forger");
        git(dir, "init", "-q", "--bare", "-b", "main", repository.toString());
        git(dir, "init", "-q", "-b", "main", forger.toString());
        git(forger, "config", "user.name", "Forger");
        git(forger, "config", "user.email", "forger@example.org");
        git(forger, "config", "gpg.format", "ssh");
        Files.writeString(forger.resolve("project.yml"), first);
        git(forger, "add", "project.yml");
        git(
                forger,
                "-c",
                "user.signingkey=" + keys.keyFiles.get(firstSigner),
                "commit",
                "-q",
                "-S",
                "-m",
                "first");
        Files.writeString(forger.resolve("project.yml"), planted);
        git(forger, "add", "project.yml");
        git(forger, "commit", "-q", "-m", message);
        Files.writeString(
                forger.resolve("project.yml"),
                planted.replace("description: early", "description: followed"));
        git(forger, "add", "project.yml");
        git(
                forger,
                "-c",
                "user.signingkey=" + keys.keyFiles.get(tipSigner),
                "commit",
                "-q",
                "-S",
                "-m",
                "the tip the first follow takes");
        git(forger, "push", "-q", repository.toString(), "HEAD:main");
        final Reconcile.Result followed =
                new FollowSignedBy(keys.context())
                        .follow("early", repository.toString(), keys.key(tipSigner), false, false)
                        .result();
        assertThat(followed.outcome()).as(followed.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        return repository;
    }

    private String early(final String signers) {
        return file("early", signers).replace("name: demo", "name: early");
    }

    @Test
    void an_id_on_an_unsigned_commit_before_the_first_follow_answers_no_backup_key()
            throws Exception {
        final String backup =
                "    - key: \"%s\"\n      backup: true\n      backs: \"%s\"\n      since: \"2026-01-01\"\n"
                        .formatted(keys.key("bob"), keys.pin("michael"));
        early(
                early("    - \"" + keys.key("michael") + "\"\n"),
                "michael",
                early("    - \"" + keys.key("michael") + "\"\n" + backup),
                "unsigned\n\nPorelo-Change-Id: bk-1\nPorelo-Change-Digest: "
                        + digest("make", "early", keys.pin("michael")),
                "michael");
        IdentityRun.keep(keys, keys.card("michael", "michael"));
        keys.agent("michael");
        final Path file = dir.resolve("backup");

        final int code =
                backupKey("--project", "early", "--out", file.toString(), "--id", "bk-1", "--json");

        assertThat(code)
                .as("an unsigned commit never checked files no backup key: " + keys.out)
                .isEqualTo(65);
        assertThat(file).doesNotExist();
    }

    @Test
    void an_id_on_an_unsigned_commit_before_the_first_follow_answers_no_replacement()
            throws Exception {
        final String backup =
                "    - key: \"%s\"\n      backup: true\n      backs: \"%s\"\n      since: \"2026-01-01\"\n";
        final String first =
                early(
                        "    - \""
                                + keys.key("michael")
                                + "\"\n"
                                + backup.formatted(keys.key("bob"), keys.pin("michael")));
        early(
                first,
                "michael",
                ProjectSigners.replacing(
                        first, keys.key("michael"), keys.key("alice-new"), keys.key("bob")),
                "unsigned\n\nPorelo-Change-Id: lost-1\nPorelo-Change-Digest: "
                        + digest("replace", "early", keys.pin("bob"), keys.pin("alice-new")),
                "alice-new");
        IdentityRun.keep(keys, keys.card("michael", "michael"));
        final Path card =
                Files.writeString(dir.resolve("new.card"), keys.card("michael", "alice-new"));

        final int code =
                backupKey(
                        "--project",
                        "early",
                        "--use",
                        keys.keyFiles.get("bob").toString(),
                        "--card",
                        card.toString(),
                        "--id",
                        "lost-1",
                        "--json");

        assertThat(code)
                .as("an unsigned commit never checked replaced no key: " + keys.out)
                .isEqualTo(65);
    }

    private List<ProjectSigners.Signer> entriesAtHead() {
        return ProjectSigners.entries(git(origin, "show", "main:project.yml"), "project.yml");
    }

    @Test
    void a_backup_key_an_access_file_admits_certifies_no_task() throws Exception {
        git(work, "fetch", "-q", origin.toString(), "main");
        git(work, "reset", "-q", "--hard", "FETCH_HEAD");
        Files.writeString(
                work.resolve("project.yml"),
                file(
                                "first",
                                "    - \""
                                        + keys.key("michael")
                                        + "\"\n    - key: \""
                                        + keys.key("bob")
                                        + "\"\n      backup: true\n      backs: \""
                                        + keys.pin("michael")
                                        + "\"\n")
                        + "access: {file: \"access.yml\"}\n");
        Files.createDirectories(work.resolve("cards"));
        Files.writeString(work.resolve("cards/alice.card"), keys.card("alice", "alice"));
        Files.writeString(work.resolve("cards/bob.card"), keys.card("bob", "bob"));
        Files.writeString(
                work.resolve("access.yml"),
                "persons:\n  alice:\n    fingerprint: \""
                        + keys.pin("alice")
                        + "\"\n    role: admin\n    hosts: [example-host]\n  bob:\n"
                        + "    fingerprint: \""
                        + keys.pin("bob")
                        + "\"\n    role: developer\n    hosts: [example-host]\n");
        git(work, "add", "project.yml", "access.yml", "cards");
        git(
                work,
                "-c",
                "user.signingkey=" + keys.keyFiles.get("michael"),
                "commit",
                "-q",
                "-S",
                "-m",
                "access beside the project file");
        git(work, "push", "-q", origin.toString(), "HEAD:main");
        final Reconcile.Result applied = follow(null);
        assertThat(applied.outcome()).as(applied.detail()).isEqualTo(Reconcile.Outcome.APPLIED);

        final List<String> certifiers =
                TaskCertifying.certifiers(
                        keys.context(), GateSupport.byName(keys.context(), "demo"));

        assertThat(certifiers)
                .as("bob's key is the signers' backup key, so it certifies no task")
                .containsExactly(keys.key("alice"));
    }

    @Test
    void a_refused_make_keeps_no_key_file_and_a_base_must_be_a_commit() throws Exception {
        commitText(
                file("first", "")
                        .replace("  signers:\n", "  signers: [\"" + keys.key("michael") + "\"]\n"),
                keys.keyFiles.get("michael"));
        assertThat(follow(null).outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        IdentityRun.keep(keys, keys.card("michael", "michael"));
        keys.agent("michael");
        final Path file = dir.resolve("backup");

        assertThat(backupKey("--project", "demo", "--out", file.toString(), "--json"))
                .as("a flow list of signers takes no entry written as text")
                .isEqualTo(65);
        assertThat(keygens).as("the key was made").isEqualTo(1);
        assertThat(file).as("a key never filed is not kept").doesNotExist();
        assertThat(Path.of(file + ".pub")).doesNotExist();

        assertThat(backupKey("--project", "demo", "--out", file.toString(), "--base", "HEAD"))
                .as("a --base that is no commit's full hash")
                .isEqualTo(64);
    }

    @Test
    void the_follow_refuses_a_backup_key_a_registry_read_here_revoked() throws Exception {
        final Path file = made();
        final String backupKey =
                org.fuin.porelo.vault.OpenSshPrivateKey.publicKey(Files.readString(file));
        revokedInRegistry(file, "bob");
        final String inForce = head();
        final String tip =
                commitText(
                        ProjectSigners.replacing(
                                git(origin, "show", "main:project.yml"),
                                keys.key("michael"),
                                keys.key("alice-new"),
                                backupKey),
                        file);

        final Reconcile.Result refused = follow(null);

        assertThat(refused.outcome()).as(refused.detail()).isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
        assertThat(refused.commit()).as("the commit in force stays").isEqualTo(inForce);
        assertThat(refused.refused()).isEqualTo(tip);
        assertThat(refused.detail()).contains("revoked by a registry");
        assertThat(
                        new SignerHandover(
                                        keys.runner, keys.paths.projects().configurationSigners())
                                .pinned("demo"))
                .as("the key the revoked backup key names is never pinned")
                .containsExactly(keys.key("michael"));
    }

    @Test
    void a_rewrite_takes_no_backup_key_from_the_commit_in_force_before_it() throws Exception {
        final Path file = made();
        final String backupKey =
                org.fuin.porelo.vault.OpenSshPrivateKey.publicKey(Files.readString(file));
        final String backed = git(origin, "show", "main:project.yml");
        final String first = git(origin, "rev-parse", "main^");
        // An unsigned parent nobody checks adds bob; the tip, signed by the backup key, only
        // replaces michael against that parent.
        final String parent =
                backed.replace("description: first", "description: rewritten")
                        .replace(
                                "    - \"" + keys.key("michael") + "\"\n",
                                "    - \""
                                        + keys.key("michael")
                                        + "\"\n    - \""
                                        + keys.key("bob")
                                        + "\"\n");
        git(work, "fetch", "-q", origin.toString(), "main");
        git(work, "reset", "-q", "--hard", first);
        Files.writeString(work.resolve("project.yml"), parent);
        git(work, "add", "project.yml");
        git(work, "commit", "-q", "-m", "unsigned, so nobody vouches for it");
        Files.writeString(
                work.resolve("project.yml"),
                ProjectSigners.replacing(
                        parent, keys.key("michael"), keys.key("alice-new"), backupKey));
        git(work, "add", "project.yml");
        git(work, "-c", "user.signingkey=" + file, "commit", "-q", "-S", "-m", "rewritten");
        git(work, "push", "-q", "--force", origin.toString(), "HEAD:main");

        final Reconcile.Result refused =
                new FollowSignedBy(keys.context())
                        .follow("demo", origin.toString(), null, false, true)
                        .result();

        assertThat(refused.outcome())
                .as("a rewrite is signed by an ordinary signer: " + refused.detail())
                .isNotEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(
                        new SignerHandover(
                                        keys.runner, keys.paths.projects().configurationSigners())
                                .pinned("demo"))
                .as("the key the unchecked parent adds is never pinned")
                .containsExactly(keys.key("michael"));
    }

    @Test
    void the_follow_refuses_a_backup_key_that_puts_a_key_a_registry_revoked_in_place()
            throws Exception {
        final Path file = made();
        final String backupKey =
                org.fuin.porelo.vault.OpenSshPrivateKey.publicKey(Files.readString(file));
        revokedInRegistry(keys.keyFiles.get("alice-new"), "bob");
        final String inForce = head();
        final String tip =
                commitText(
                        ProjectSigners.replacing(
                                git(origin, "show", "main:project.yml"),
                                keys.key("michael"),
                                keys.key("alice-new"),
                                backupKey),
                        file);

        final Reconcile.Result refused = follow(null);

        assertThat(refused.outcome()).as(refused.detail()).isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
        assertThat(refused.commit()).as("the commit in force stays").isEqualTo(inForce);
        assertThat(refused.refused()).isEqualTo(tip);
        assertThat(
                        new SignerHandover(
                                        keys.runner, keys.paths.projects().configurationSigners())
                                .pinned("demo"))
                .as("the revoked key is never pinned, and michael's is not retired")
                .containsExactly(keys.key("michael"));
    }

    @Test
    void the_follow_refuses_a_backup_key_that_puts_a_key_the_revocation_list_names_in_place()
            throws Exception {
        final Path file = made();
        final String backupKey =
                org.fuin.porelo.vault.OpenSshPrivateKey.publicKey(Files.readString(file));
        IdentityRun.revoke(keys, "alice-new");
        final String tip =
                commitText(
                        ProjectSigners.replacing(
                                git(origin, "show", "main:project.yml"),
                                keys.key("michael"),
                                keys.key("alice-new"),
                                backupKey),
                        file);

        final Reconcile.Result refused = follow(null);

        assertThat(refused.outcome()).as(refused.detail()).isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
        assertThat(refused.refused()).isEqualTo(tip);
        assertThat(
                        new SignerHandover(
                                        keys.runner, keys.paths.projects().configurationSigners())
                                .pinned("demo"))
                .as("michael's key is not retired for a revoked one")
                .containsExactly(keys.key("michael"));
    }

    @Test
    void the_follow_refuses_a_signer_a_registry_read_here_revoked() throws Exception {
        final String signers =
                "    - \"" + keys.key("michael") + "\"\n    - \"" + keys.key("bob") + "\"\n";
        final String inForce = commit("two signers", "michael", signers);
        assertThat(follow(null).outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        revokedInRegistry(keys.keyFiles.get("bob"), "alice");
        final String tip = commit("changed by a revoked key", "bob", signers);

        final Reconcile.Result refused = follow(null);

        assertThat(refused.outcome()).as(refused.detail()).isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
        assertThat(refused.commit()).as("the commit in force stays").isEqualTo(inForce);
        assertThat(refused.refused()).isEqualTo(tip);
        assertThat(refused.detail()).contains("revoked by a registry");
    }

    @Test
    void a_signer_a_registry_read_here_revoked_is_never_pinned_on_an_unchanged_tip()
            throws Exception {
        revokedInRegistry(keys.keyFiles.get("bob"), "alice");

        final Reconcile.Result unchanged = follow(keys.key("bob"));

        assertThat(unchanged.outcome())
                .as(unchanged.detail())
                .isEqualTo(Reconcile.Outcome.UNCHANGED);
        assertThat(Files.readString(keys.paths.projects().configurationSigners()))
                .as("pinned, a revoked key would sign the access file on this machine")
                .doesNotContain(keys.key("bob").split(" ")[1]);
    }

    /**
     * Revokes the key of a file in the registry this machine reads, as a registry revokes a key:
     * its login's card replaced by an owner, with a card carrying the replacement login's key.
     */
    private void revokedInRegistry(final Path file, final String replacement) throws Exception {
        final String backupKey =
                org.fuin.porelo.vault.OpenSshPrivateKey.publicKey(Files.readString(file));
        keys.founded();
        final org.fuin.porelo.vault.SigningKey lost =
                new org.fuin.porelo.vault.SigningKey(
                        java.util.Base64.getDecoder()
                                .decode(
                                        org.fuin.porelo.vault.OpenSshPrivateKey.seedBase64(
                                                Files.readString(file))),
                        "test");
        keys.forge.keys.put("kept", List.of(backupKey));
        keys.write(
                "people/kept.card",
                org.fuin.porelo.vault.PersonCard.write(
                        "kept",
                        "Kept",
                        "",
                        lost,
                        java.time.LocalDate.of(2026, 1, 1),
                        java.time.LocalDate.of(2030, 1, 1),
                        null));
        keys.keyFiles.put("kept", file);
        keys.commit("kept");
        keys.write("people/kept.card", keys.card("kept", replacement));
        keys.commit("michael");
        keys.forge.keys.put("kept", List.of(backupKey, keys.key(replacement)));
        assertThat(keys.registry("read", keys.repo.toString(), "--json"))
                .as(keys.err.toString())
                .isZero();
        assertThat(
                        org.fuin.porelo.vault.TrustRecords.read(keys.paths.xdg().state())
                                .getFirst()
                                .state()
                                .revoked())
                .as("the registry read here revokes the key")
                .contains(ProjectSigners.fingerprint(backupKey));
    }

    @Test
    void the_follow_refuses_a_backup_signed_commit_that_changes_anything_else() throws Exception {
        final Path file = made();
        final String inForce = head();
        final String backed = git(origin, "show", "main:project.yml");
        final String tip =
                commitText(backed.replace("description: first", "description: other"), file);

        final Reconcile.Result refused = follow(null);

        assertThat(refused.outcome()).as(refused.detail()).isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
        assertThat(refused.commit()).as("the commit in force stays").isEqualTo(inForce);
        assertThat(refused.refused()).as("the commit is named").isEqualTo(tip);
        assertThat(refused.refusedFile()).isEqualTo("project.yml");
        assertThat(refused.detail()).contains(tip, "backup key", "beyond project.signers");
    }

    @Test
    void a_first_follow_takes_no_backup_key_from_the_unchecked_commit_before_the_tip()
            throws Exception {
        final Path planted = plantedBackup("planted");

        final Reconcile.Result refused =
                new FollowSignedBy(keys.context())
                        .follow("planted", planted.toString(), keys.key("michael"), false, false)
                        .result();

        assertThat(refused.outcome())
                .as("a backup key no commit in force names signs nothing: " + refused.detail())
                .isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
        assertThat(
                        new SignerHandover(
                                        keys.runner, keys.paths.projects().configurationSigners())
                                .pinned("planted"))
                .as("the planted key is never pinned")
                .doesNotContain(keys.key("alice-new"));
    }

    /**
     * A repository whose unsigned first commit names michael and a backup key bob holds, and whose
     * tip, signed by bob, replaces michael by alice-new.
     */
    private Path plantedBackup(final String name) throws Exception {
        final Path planted = dir.resolve(name + ".git");
        final Path forger = dir.resolve(name + "-forger");
        git(dir, "init", "-q", "--bare", "-b", "main", planted.toString());
        git(dir, "init", "-q", "-b", "main", forger.toString());
        git(forger, "config", "user.name", "Forger");
        git(forger, "config", "user.email", "forger@example.org");
        git(forger, "config", "gpg.format", "ssh");
        final String backupOf =
                "    - key: \"%s\"\n      backup: true\n      backs: \"%s\"\n      since: \"2026-01-01\"\n";
        Files.writeString(
                forger.resolve("project.yml"),
                file(
                                "planted",
                                "    - \""
                                        + keys.key("michael")
                                        + "\"\n"
                                        + backupOf.formatted(keys.key("bob"), keys.pin("michael")))
                        .replace("name: demo", "name: " + name));
        git(forger, "add", "project.yml");
        git(forger, "commit", "-q", "-m", "unsigned, so nobody vouches for it");
        Files.writeString(
                forger.resolve("project.yml"),
                file(
                                "planted",
                                "    - \""
                                        + keys.key("alice-new")
                                        + "\"\n"
                                        + backupOf.formatted(
                                                keys.key("bob"), keys.pin("alice-new")))
                        .replace("name: demo", "name: " + name));
        git(forger, "add", "project.yml");
        git(
                forger,
                "-c",
                "user.signingkey=" + keys.keyFiles.get("bob"),
                "commit",
                "-q",
                "-S",
                "-m",
                "signed by the planted backup key");
        git(forger, "push", "-q", planted.toString(), "HEAD:main");
        return planted;
    }

    @Test
    void the_follow_refuses_a_backup_signed_commit_that_adds_a_signer() throws Exception {
        final Path file = made();
        final String backed = git(origin, "show", "main:project.yml");
        final String tip =
                commitText(
                        backed.replace(
                                "    - \"" + keys.key("michael") + "\"\n",
                                "    - \""
                                        + keys.key("michael")
                                        + "\"\n    - \""
                                        + keys.key("bob")
                                        + "\"\n"),
                        file);

        final Reconcile.Result refused = follow(null);

        assertThat(refused.outcome()).as(refused.detail()).isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
        assertThat(refused.refused()).isEqualTo(tip);
        assertThat(refused.detail()).contains("beyond replacing");
    }

    private List<String> pinned() {
        return new SignerHandover(keys.runner, keys.paths.projects().configurationSigners())
                .pinned("demo");
    }

    @Test
    void a_backup_key_a_person_names_at_the_follow_signs_only_what_a_backup_key_may()
            throws Exception {
        final Path file = made();
        final String backupKey =
                org.fuin.porelo.vault.OpenSshPrivateKey.publicKey(Files.readString(file));
        final String inForce = head();
        final String tip =
                commitText(
                        git(origin, "show", "main:project.yml")
                                .replace("description: first", "description: other"),
                        file);

        final Reconcile.Result refused = follow(backupKey);

        assertThat(refused.outcome())
                .as(
                        "named or not, a backup key changes nothing but the key it backs: "
                                + refused.detail())
                .isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
        assertThat(refused.commit()).as("the commit in force stays").isEqualTo(inForce);
        assertThat(refused.refused()).isEqualTo(tip);
        assertThat(pinned())
                .as("the backup key is never pinned")
                .containsExactly(keys.key("michael"));
    }

    @Test
    void a_backup_key_a_person_names_at_the_follow_is_never_pinned() throws Exception {
        final Path file = made();
        final String backupKey =
                org.fuin.porelo.vault.OpenSshPrivateKey.publicKey(Files.readString(file));
        commitText(
                git(origin, "show", "main:project.yml")
                        .replace("description: first", "description: other"),
                keys.keyFiles.get("michael"));

        final Reconcile.Result applied = follow(backupKey);

        assertThat(applied.outcome()).as(applied.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(pinned())
                .as("pinned, the backup key would sign any later change and the access file")
                .containsExactly(keys.key("michael"));
    }

    @Test
    void a_backup_key_a_person_names_on_an_unchanged_tip_is_never_pinned() throws Exception {
        final Path file = made();
        final String backupKey =
                org.fuin.porelo.vault.OpenSshPrivateKey.publicKey(Files.readString(file));

        final Reconcile.Result unchanged = follow(backupKey);

        assertThat(unchanged.outcome())
                .as(unchanged.detail())
                .isEqualTo(Reconcile.Outcome.UNCHANGED);
        assertThat(Files.readString(keys.paths.projects().configurationSigners()))
                .as("pinned, the backup key would sign any later change and the access file")
                .doesNotContain(backupKey.split(" ")[1]);
    }

    @Test
    void a_backup_key_a_person_names_across_a_signer_change_is_never_kept_as_retired()
            throws Exception {
        final Path file = made();
        final String backupKey =
                org.fuin.porelo.vault.OpenSshPrivateKey.publicKey(Files.readString(file));
        commitText(
                git(origin, "show", "main:project.yml")
                        .replace(
                                "    - \"" + keys.key("michael") + "\"\n",
                                "    - \""
                                        + keys.key("michael")
                                        + "\"\n    - \""
                                        + keys.key("alice-new")
                                        + "\"\n"),
                keys.keyFiles.get("michael"));

        final Reconcile.Result applied = follow(backupKey);

        assertThat(applied.outcome()).as(applied.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(Files.readString(keys.paths.projects().configurationSigners()))
                .as("a retired backup key still verifies every commit made before its date")
                .doesNotContain(backupKey.split(" ")[1]);
    }

    @Test
    void a_commit_that_names_its_own_signer_a_backup_key_is_judged_as_signed_by_one()
            throws Exception {
        final String inForce = head();
        final String tip =
                commit(
                        "other",
                        "bob",
                        "    - \""
                                + keys.key("michael")
                                + "\"\n    - key: \""
                                + keys.key("bob")
                                + "\"\n      backup: true\n      backs: \""
                                + keys.pin("michael")
                                + "\"\n      since: \"2026-01-01\"\n");

        final Reconcile.Result refused = follow(keys.key("bob"));

        assertThat(refused.outcome())
                .as(
                        "a backup key that files itself changes more than the key it backs: "
                                + refused.detail())
                .isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
        assertThat(refused.commit()).as("the commit in force stays").isEqualTo(inForce);
        assertThat(refused.refused()).isEqualTo(tip);
    }

    @Test
    void a_refusal_never_names_the_projects_address_with_its_userinfo() throws Exception {
        IdentityRun.keep(keys, keys.card("michael", "michael"));
        keys.agent("michael");
        final Path record = keys.paths.projects().followed().resolve("demo.json");
        Files.writeString(
                record,
                Files.readString(record)
                        .replace(
                                origin.toString(),
                                "https://secret-token@forge.invalid/org/demo.git"));
        final Path file = dir.resolve("backup");

        final int code = backupKey("--project", "demo", "--out", file.toString(), "--json");

        assertThat(code).as(keys.err.toString()).isNotZero();
        assertThat(keys.err.toString() + keys.out)
                .as("the address is named, its secret never")
                .contains("forge.invalid")
                .doesNotContain("secret-token");
    }

    @Test
    void a_signer_whose_own_key_is_revoked_makes_no_backup_key() throws Exception {
        IdentityRun.keep(keys, keys.card("michael", "michael"));
        keys.agent("michael");
        IdentityRun.revoke(keys, "michael");
        final String before = head();
        final Path file = dir.resolve("backup");

        assertThat(backupKey("--project", "demo", "--out", file.toString(), "--json"))
                .as(keys.err.toString())
                .isEqualTo(65);

        assertThat(keys.answer().get("reason")).isEqualTo("revoked");
        assertThat(keygens).as("no key is made").isZero();
        assertThat(file).doesNotExist();
        assertThat(head()).isEqualTo(before);
    }

    @Test
    void a_trust_record_that_cannot_be_read_ends_a_make_for_a_project_in_66() throws Exception {
        IdentityRun.keep(keys, keys.card("michael", "michael"));
        keys.agent("michael");
        final Path record =
                org.fuin.porelo.vault.TrustRecords.file(
                        keys.paths.xdg().state(), "https://forge.example.org/acme/registry.git");
        Files.createDirectories(record.getParent());
        Files.writeString(record, "{");
        final Path file = dir.resolve("backup");

        assertThat(backupKey("--project", "demo", "--out", file.toString(), "--json"))
                .as(keys.err.toString())
                .isEqualTo(66);

        assertThat(keygens).as("no key is made").isZero();
        assertThat(file).doesNotExist();
    }
}
