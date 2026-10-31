package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** A push to a host's forge, each new commit judged by its signature when it arrives. */
class CommitVerifyCommandTest {

    private static final String ZERO = "0".repeat(40);

    private static final String PRINCIPAL = "task:demo:fix-login";

    @TempDir Path dir;

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private Path forge;

    private Path person;

    private Path certifier;

    private Path task;

    private Path signers;

    private Path certifiers;

    private String base;

    @BeforeEach
    void forge() throws Exception {
        forge = dir.resolve("forge.git");
        git(dir, "init", "-q", "--bare", "-b", "main", forge.toString());
        person = keygen("alice");
        certifier = keygen("certifier");
        task = keygen("task");
        signers = dir.resolve("signers");
        Files.writeString(signers, "demo namespaces=\"git\" " + publicKey(person) + "\n");
        certifiers = dir.resolve("certifiers");
        Files.writeString(
                certifiers,
                "task:demo:* cert-authority,namespaces=\"git\" " + publicKey(certifier) + "\n");
        base = commit(person, "base");
        git(forge, "update-ref", "refs/heads/main", base);
    }

    @Test
    void accepts_a_person_in_force_and_a_task_by_its_certificate() throws Exception {
        final String byPerson = commit(person, "by alice", base);
        final Path certificate = certify(task, certifier, PRINCIPAL, "-1m:+7d");
        final String byTask = commit(certificate, "by the task", byPerson);

        assertThat(verify(push(base, byTask, "refs/heads/main"), "--json"))
                .as(err.toString())
                .isZero();
        final Map<?, ?> answer = answer();
        assertThat(answer.get("accepted")).isEqualTo(true);
        assertThat(verdicts())
                .extracting(each -> each.get("commit"), each -> each.get("verdict"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(byTask, "accepted"),
                        org.assertj.core.groups.Tuple.tuple(byPerson, "accepted"));
        final Map<?, ?> taskSigner = (Map<?, ?>) verdicts().get(0).get("signer");
        assertThat(taskSigner.get("kind")).isEqualTo("task");
        assertThat(taskSigner.get("principal")).isEqualTo(PRINCIPAL);
        assertThat(taskSigner.get("certificate")).isEqualTo("certificate");
        assertThat(taskSigner.get("fingerprint")).isEqualTo(fingerprint(task));
        assertThat(taskSigner.get("signed_by")).isEqualTo(fingerprint(certifier));
        final Map<?, ?> personSigner = (Map<?, ?>) verdicts().get(1).get("signer");
        assertThat(personSigner.get("kind")).isEqualTo("person");
        assertThat(personSigner.get("fingerprint")).isEqualTo(fingerprint(person));
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void refuses_an_unsigned_commit() throws Exception {
        final String unsigned = commit(null, "unsigned", base);

        assertThat(verify(push(base, unsigned, "refs/heads/main"), "--json")).isEqualTo(65);
        assertThat(err.toString()).isEqualTo(unsigned + " carries no signature\n");
        assertThat(verdicts().getFirst().get("verdict")).isEqualTo("refused");
        assertThat(verdicts().getFirst().get("signer")).isNull();
    }

    @Test
    void refuses_a_gpg_signature() throws Exception {
        final String tree = git(forge, "rev-parse", base + "^{tree}");
        final String raw =
                "tree "
                        + tree
                        + "\nparent "
                        + base
                        + "\nauthor A <a@example.org> 1700000000 +0000\n"
                        + "committer A <a@example.org> 1700000000 +0000\n"
                        + "gpgsig -----BEGIN PGP SIGNATURE-----\n \n iQEzBAABCAAdFiEE\n"
                        + " -----END PGP SIGNATURE-----\n\nsigned elsewhere\n";
        final String foreign =
                runner.runOrFail(
                                Command.of(
                                                "git",
                                                "--git-dir",
                                                forge.toString(),
                                                "hash-object",
                                                "-t",
                                                "commit",
                                                "-w",
                                                "--stdin")
                                        .withInput(raw)
                                        .withEnvironment(quiet()))
                        .trimmedOutput();

        assertThat(verify(push(base, foreign, "refs/heads/main"))).isEqualTo(65);
        assertThat(err.toString())
                .isEqualTo(foreign + " carries a GPG or X.509 signature, not an SSH one\n");
    }

    @Test
    void refuses_a_key_that_is_not_in_force() throws Exception {
        final Path stranger = keygen("stranger");
        final String foreign = commit(stranger, "by a stranger", base);

        assertThat(verify(push(base, foreign, "refs/heads/main"))).isEqualTo(65);
        assertThat(err.toString())
                .startsWith(foreign + " signed by " + fingerprint(stranger))
                .contains("no signer in force");
    }

    @Test
    void refuses_a_key_the_krl_revokes() throws Exception {
        final Path krl = dir.resolve("revoked.krl");
        runner.runOrFail(
                Command.of("ssh-keygen", "-q", "-k", "-f", krl.toString(), person + ".pub"));
        final String revoked = commit(person, "after the revocation", base);

        assertThat(verify(push(base, revoked, "refs/heads/main"), "--krl", krl.toString()))
                .isEqualTo(65);
        assertThat(err.toString())
                .isEqualTo(
                        revoked + " its key " + fingerprint(person) + " is revoked by the KRL\n");
    }

    @Test
    void refuses_a_task_certificate_expired_at_arrival_though_the_commit_is_dated_inside_it()
            throws Exception {
        final Path certificate =
                certify(task, certifier, PRINCIPAL, "20200101000000Z:20200102000000Z");
        final String late = commitAt(certificate, "2020-01-01T12:00:00Z", base);

        assertThat(verify(push(base, late, "refs/heads/main"), "--json")).isEqualTo(65);
        assertThat(err.toString())
                .startsWith(late + " its certificate for " + PRINCIPAL + " is not valid at its")
                .contains("(valid from 2020-01-01T00:00:00Z to 2020-01-02T00:00:00Z)");
        assertThat(((Map<?, ?>) verdicts().getFirst().get("signer")).get("kind")).isEqualTo("task");
    }

    @Test
    void refuses_a_plain_task_key_without_a_record_of_its_certificate() throws Exception {
        final Path other = keygen("other-task");
        final Path records =
                records(certify(other, certifier, "task:demo:other", "-1m:+7d"), "other");
        final String plain = commit(task, "by the plain key", base);

        assertThat(
                        verify(
                                push(base, plain, "refs/heads/main"),
                                "--certificates",
                                records.toString()))
                .isEqualTo(65);
        assertThat(err.toString())
                .startsWith(plain + " signed by " + fingerprint(task))
                .contains("no task key with a recorded certificate");
    }

    @Test
    void accepts_a_plain_task_key_whose_recorded_certificate_holds() throws Exception {
        final Path records = records(certify(task, certifier, PRINCIPAL, "-1m:+7d"), "fix-login");
        final String plain = commit(task, "by the plain key", base);

        assertThat(
                        verify(
                                push(base, plain, "refs/heads/main"),
                                "--certificates",
                                records.toString(),
                                "--json"))
                .as(err.toString())
                .isZero();
        final Map<?, ?> signer = (Map<?, ?>) verdicts().getFirst().get("signer");
        assertThat(signer.get("certificate")).isEqualTo("recorded");
        assertThat(signer.get("principal")).isEqualTo(PRINCIPAL);
    }

    @Test
    void refuses_a_plain_task_key_whose_recorded_certificate_no_certifier_issued()
            throws Exception {
        final Path stranger = keygen("stranger-certifier");
        final Path records = records(certify(task, stranger, PRINCIPAL, "-1m:+7d"), "fix-login");
        final String plain = commit(task, "by the plain key", base);

        assertThat(
                        verify(
                                push(base, plain, "refs/heads/main"),
                                "--certificates",
                                records.toString()))
                .isEqualTo(65);
        assertThat(err.toString())
                .contains("whose recorded certificate is not accepted")
                .contains("signed by " + fingerprint(stranger) + ", which is no certifier");
    }

    @Test
    void judges_a_merge_commit_itself() throws Exception {
        final String left = commit(person, "left", base);
        final String right = commit(person, "right", base);
        final String merge = commit(null, "merge", left, right);

        assertThat(verify(push(base, merge, "refs/heads/main"), "--json")).isEqualTo(65);
        assertThat(err.toString()).isEqualTo(merge + " carries no signature\n");
        assertThat(verdicts()).extracting(each -> each.get("commit")).contains(left, right, merge);
    }

    @Test
    void judges_nothing_for_a_deleted_ref() throws Exception {
        assertThat(verify(push(base, ZERO, "refs/heads/main"), "--json"))
                .as(err.toString())
                .isZero();
        assertThat(answer().get("accepted")).isEqualTo(true);
        assertThat(verdicts()).isEmpty();
    }

    @Test
    void names_every_refused_commit_at_once() throws Exception {
        final String unsigned = commit(null, "unsigned", base);
        final String good = commit(person, "good", unsigned);
        final String stranger = commit(keygen("stranger"), "stranger", good);
        final String side = commit(null, "side", base);

        assertThat(
                        verify(
                                push(base, stranger, "refs/heads/main")
                                        + push(ZERO, side, "refs/heads/side"),
                                "--json"))
                .isEqualTo(65);
        assertThat(err.toString().lines().map(line -> line.split(" ")[0]))
                .containsExactly(stranger, unsigned, side);
        assertThat(verdicts())
                .filteredOn(each -> "accepted".equals(each.get("verdict")))
                .extracting(each -> each.get("commit"))
                .containsExactly(good);
    }

    @Test
    void refuses_a_push_of_more_commits_than_it_judges() throws Exception {
        final StringBuilder stream = new StringBuilder();
        for (int at = 1; at <= HostArrivals.MAX_COMMITS + 1; at++) {
            stream.append("commit refs/heads/many\nmark :")
                    .append(at)
                    .append("\ncommitter A <a@example.org> 1700000000 +0000\ndata 0\n");
            stream.append(at == 1 ? "from " + base + "\n" : "from :" + (at - 1) + "\n")
                    .append('\n');
        }
        runner.runOrFail(
                Command.of("git", "--git-dir", forge.toString(), "fast-import", "--quiet")
                        .withInput(stream.toString())
                        .withEnvironment(quiet()));
        final String tip = git(forge, "rev-parse", "refs/heads/many");
        git(forge, "update-ref", "-d", "refs/heads/many");

        assertThat(verify(push(base, tip, "refs/heads/main"), "--json")).isEqualTo(65);
        assertThat(err.toString())
                .isEqualTo(
                        "porelo: the push brings more than 10000 new commits; push fewer at"
                                + " once\n");
        assertThat(verdicts()).isEmpty();
    }

    @Test
    void refuses_hook_input_whose_ids_are_not_hex_before_git_sees_them() throws Exception {
        assertThat(verify(base + " --all refs/heads/main\n")).isEqualTo(65);
        assertThat(err.toString())
                .isEqualTo("porelo: line 1 of the hook's input is not '<old> <new> <ref>'\n");
    }

    @Test
    void answers_66_for_a_named_file_that_is_not_there() throws Exception {
        assertThat(verify("", "--krl", dir.resolve("missing.krl").toString())).isEqualTo(66);
        assertThat(err.toString()).startsWith("porelo: ").contains("missing.krl");
    }

    @Test
    void refuses_a_plain_task_key_whose_certifier_line_is_valid_only_after_its_arrival()
            throws Exception {
        final Path records = records(certify(task, certifier, PRINCIPAL, "-1m:+7d"), "fix-login");
        final String plain = commit(task, "by the plain key", base);
        Files.writeString(
                certifiers,
                "task:demo:* cert-authority,valid-after=\"20200101\" "
                        + publicKey(certifier)
                        + "\n");
        assertThat(
                        verify(
                                push(base, plain, "refs/heads/main"),
                                "--certificates",
                                records.toString()))
                .as("a certifier line valid since 2020 holds: %s", err)
                .isZero();

        Files.writeString(
                certifiers,
                "task:demo:* cert-authority,valid-after=\"20990101Z\" "
                        + publicKey(certifier)
                        + "\n");

        assertThat(
                        verify(
                                push(base, plain, "refs/heads/main"),
                                "--certificates",
                                records.toString()))
                .as("a certifier line not yet valid certifies nothing on the plain key's path")
                .isEqualTo(65);
        assertThat(err.toString())
                .contains("whose recorded certificate is not accepted")
                .contains("which is no certifier for " + PRINCIPAL);
    }

    @Test
    void refuses_a_plain_task_key_whose_certifier_line_negates_the_git_namespace()
            throws Exception {
        final Path records = records(certify(task, certifier, PRINCIPAL, "-1m:+7d"), "fix-login");
        final String plain = commit(task, "by the plain key", base);
        Files.writeString(
                certifiers,
                "task:demo:* cert-authority,namespaces=\"git,!git\" "
                        + publicKey(certifier)
                        + "\n");

        assertThat(
                        verify(
                                push(base, plain, "refs/heads/main"),
                                "--certificates",
                                records.toString()))
                .as("ssh-keygen reads git,!git as refusing git, so the plain key's path must too")
                .isEqualTo(65);
        assertThat(err.toString()).contains("which is no certifier for " + PRINCIPAL);
    }

    @Test
    void refuses_a_task_whose_container_name_would_be_too_long() throws Exception {
        // porelo-demo- and 53 characters: one more than a container name may have.
        final String principal = "task:demo:" + "a".repeat(53);
        final Path certificate = certify(task, certifier, principal, "-1m:+7d");
        final String byTask = commit(certificate, "by the task", base);

        assertThat(verify(push(base, byTask, "refs/heads/main")))
                .as("task start could never have made this task")
                .isEqualTo(65);
        assertThat(err.toString()).contains("must name exactly one principal");
    }

    @Test
    void answers_66_for_a_recorded_certificate_that_is_broken_json_and_writes_nothing()
            throws Exception {
        final Path records = dir.resolve("broken-records");
        Files.writeString(records, "{\"certificate\": \n");

        assertThat(verify("", "--certificates", records.toString()))
                .as("a named file that cannot be read as what it must be")
                .isEqualTo(66);
        assertThat(err.toString()).startsWith("porelo: ").contains("is not a JSON record");
        assertThat(dir.resolve("no-home")).as("no failure log under a state home").doesNotExist();
    }

    @Test
    void answers_70_when_ssh_keygen_cannot_be_run() throws Exception {
        final Path krl = dir.resolve("revoked.krl");
        runner.runOrFail(
                Command.of(
                        "ssh-keygen", "-q", "-k", "-f", krl.toString(), keygen("other") + ".pub"));
        final CommandRunner missing =
                command -> {
                    if ("ssh-keygen".equals(command.program())) {
                        throw new CommandException(
                                command, new java.io.IOException("no ssh-keygen here"));
                    }
                    return runner.run(command);
                };

        assertThat(verifyWith(missing, Map.of(), "", "--krl", krl.toString()))
                .as("ssh-keygen that cannot be run is 70, not a file that cannot be read")
                .isEqualTo(70);
        assertThat(err.toString()).contains("could not be run");
    }

    @Test
    void runs_every_ssh_keygen_and_git_from_the_chosen_environment() throws Exception {
        final Path krl = dir.resolve("revoked.krl");
        runner.runOrFail(
                Command.of(
                        "ssh-keygen", "-q", "-k", "-f", krl.toString(), keygen("other") + ".pub"));
        final String byPerson = commit(person, "by alice", base);
        final List<Command> ran = new ArrayList<>();
        final CommandRunner recording =
                command -> {
                    ran.add(command);
                    return runner.run(command);
                };

        assertThat(
                        verifyWith(
                                recording,
                                Map.of(),
                                push(base, byPerson, "refs/heads/main"),
                                "--krl",
                                krl.toString()))
                .as(err.toString())
                .isZero();
        assertThat(ran)
                .as("the KRL and both allowed-signers files are checked by ssh-keygen too")
                .filteredOn(each -> "ssh-keygen".equals(each.program()))
                .extracting(each -> each.arguments().get(1))
                .contains("-Q", "-Y");
        assertThat(ran)
                .filteredOn(Command::inheritsEnvironment)
                .as("nothing of the account's environment reaches git or ssh-keygen")
                .isEmpty();
    }

    @Test
    void answers_70_when_its_scratch_files_cannot_be_written() throws Exception {
        final String byPerson = commit(person, "by alice", base);

        assertThat(
                        verifyWith(
                                runner,
                                Map.of("TMPDIR", dir.resolve("missing").toString()),
                                push(base, byPerson, "refs/heads/main")))
                .as("ssh-keygen cannot be run without the files it reads")
                .isEqualTo(70);
        assertThat(err.toString()).contains("scratch");
    }

    @Test
    void refuses_an_empty_repository_as_wrong_use() {
        out.getBuffer().setLength(0);
        assertThat(verify("", "--repository", ""))
                .as("an empty path would be the working directory")
                .isEqualTo(64);
    }

    @Test
    void accepts_a_signed_commit_of_a_sha256_repository() throws Exception {
        forge = dir.resolve("forge-sha256.git");
        git(dir, "init", "-q", "--bare", "--object-format=sha256", "-b", "main", forge.toString());
        final String root = commit(person, "base");
        git(forge, "update-ref", "refs/heads/main", root);
        final String byPerson = commit(person, "by alice", root);
        assertThat(byPerson).hasSize(64);

        assertThat(verify(push(root, byPerson, "refs/heads/main"), "--json"))
                .as(err.toString())
                .isZero();
        assertThat(verdicts().getFirst().get("verdict")).isEqualTo("accepted");
    }

    @Test
    void answers_a_refused_certificates_principal_printable() throws Exception {
        final String principal = "task:demo:fix\u001b]0;owned\u0007";
        final Path certificate = certify(task, certifier, principal, "-1m:+7d");
        final String byTask = commit(certificate, "by the task", base);

        assertThat(verify(push(base, byTask, "refs/heads/main"), "--json")).isEqualTo(65);
        final Map<?, ?> signer = (Map<?, ?>) verdicts().getFirst().get("signer");
        assertThat(signer.get("principal"))
                .as("a terminal escape in a certificate reaches the host's record defused")
                .isEqualTo("task:demo:fix?]0;owned?");
    }

    /** The verb in the forge's own pre-receive hook, as git runs it for a push. */
    @Test
    void judges_a_quarantined_push_in_the_forges_hook_without_a_home() throws Exception {
        final Path hook = forge.resolve("hooks").resolve("pre-receive");
        Files.createDirectories(hook.getParent());
        Files.writeString(
                hook,
                "#!/bin/sh\nexec '"
                        + Path.of(System.getProperty("java.home"), "bin", "java")
                        + "' '-Duser.home="
                        + dir.resolve("no-home")
                        + "' -cp '"
                        + System.getProperty("java.class.path")
                        + "' "
                        + PoreloCli.class.getName()
                        + " commit verify --repository '"
                        + forge
                        + "' --signers '"
                        + signers
                        + "' --certifiers '"
                        + certifiers
                        + "'\n");
        Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwx------"));
        final Path work = dir.resolve("work");
        git(dir, "init", "-q", "-b", "main", work.toString());
        git(work, "fetch", "-q", forge.toString(), "main");
        final String signed = commitIn(work, person, "signed", base);
        final String unsigned = commitIn(work, null, "unsigned", signed);

        final CommandResult refused = pushFrom(work, unsigned);
        assertThat(refused.exitCode()).as(refused.standardError()).isNotZero();
        assertThat(refused.standardError()).contains(unsigned + " carries no signature");
        assertThat(git(forge, "rev-parse", "refs/heads/main")).isEqualTo(base);
        assertThat(
                        runner.run(
                                        Command.of(
                                                        "git",
                                                        "--git-dir",
                                                        forge.toString(),
                                                        "cat-file",
                                                        "-e",
                                                        unsigned)
                                                .withEnvironment(quiet()))
                                .successful())
                .as("a refused push leaves nothing behind")
                .isFalse();

        final CommandResult accepted = pushFrom(work, signed);
        assertThat(accepted.exitCode()).as(accepted.standardError()).isZero();
        assertThat(git(forge, "rev-parse", "refs/heads/main")).isEqualTo(signed);
        assertThat(dir.resolve("no-home")).as("no home was made").doesNotExist();
    }

    private CommandResult pushFrom(Path work, String commit) {
        return runner.run(
                Command.of("git", "push", forge.toString(), commit + ":refs/heads/main")
                        .in(work)
                        .withChosenEnvironment(
                                Map.of(
                                        "PATH", System.getenv("PATH"),
                                        "LC_ALL", "C",
                                        "GIT_CONFIG_GLOBAL", "/dev/null",
                                        "GIT_CONFIG_NOSYSTEM", "1")));
    }

    private int verify(String input, String... extra) {
        return verifyWith(runner, Map.of(), input, extra);
    }

    private int verifyWith(
            CommandRunner using, Map<String, String> more, String input, String... extra) {
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        final PoreloPaths paths =
                new PoreloPaths(
                        XdgPaths.of(name -> null, dir.resolve("no-home")), dir.resolve("bin"));
        final Map<String, String> environment = new HashMap<>();
        environment.put("PATH", System.getenv("PATH"));
        environment.putAll(more);
        final PoreloContext context =
                new PoreloContext(
                        using,
                        paths,
                        args -> 0,
                        environment::get,
                        false,
                        VaultPrompt.NOBODY,
                        new StandardInput(
                                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                                false));
        final CommandLine cli = PoreloCli.commandLine(context);
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        final List<String> args =
                new ArrayList<>(
                        List.of(
                                "commit",
                                "verify",
                                "--signers",
                                signers.toString(),
                                "--certifiers",
                                certifiers.toString()));
        if (!List.of(extra).contains("--repository")) {
            args.addAll(List.of("--repository", forge.toString()));
        }
        args.addAll(List.of(extra));
        return cli.execute(args.toArray(String[]::new));
    }

    private static String push(String old, String now, String ref) {
        return old + " " + now + " " + ref + "\n";
    }

    private Map<?, ?> answer() {
        return (Map<?, ?>) Json.parse(out.toString());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> verdicts() {
        return ((List<?>) answer().get("commits"))
                .stream().map(each -> (Map<String, Object>) each).toList();
    }

    private Path keygen(String name) {
        final Path path = dir.resolve(name);
        runner.runOrFail(
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
                        path.toString()));
        return path;
    }

    private String publicKey(Path key) throws Exception {
        final String[] fields = Files.readString(Path.of(key + ".pub")).strip().split(" ");
        return fields[0] + " " + fields[1];
    }

    private String fingerprint(Path key) throws Exception {
        return SignedBy.fingerprintOf(publicKey(key));
    }

    /** Certifies a key, its certificate beside it as {@code <key>-cert.pub}. */
    private Path certify(Path key, Path by, String principal, String validity) {
        final Path copy = dir.resolve(key.getFileName() + "-" + System.nanoTime());
        runner.runOrFail(Command.of("cp", key.toString(), copy.toString()));
        runner.runOrFail(Command.of("cp", key + ".pub", copy + ".pub"));
        runner.runOrFail(
                Command.of(
                        "ssh-keygen",
                        "-q",
                        "-s",
                        by.toString(),
                        "-I",
                        "porelo task " + principal.substring(principal.lastIndexOf(':') + 1),
                        "-n",
                        principal,
                        "-V",
                        validity,
                        copy + ".pub"));
        return Path.of(copy + "-cert.pub");
    }

    /** The host's records, one line as {@code porelo-host-person task --show} answers it. */
    private Path records(Path certificate, String name) throws Exception {
        final String[] fields = Files.readString(certificate).strip().split(" ");
        final Map<String, Object> record = new LinkedHashMap<>();
        record.put("task", name);
        record.put("login", "alice");
        record.put("certificate", fields[0] + " " + fields[1]);
        final Path file = dir.resolve("records-" + System.nanoTime());
        Files.writeString(file, Json.write(record) + "\n");
        return file;
    }

    private String commit(Path signer, String message, String... parents) {
        return commitIn(forge, signer, message, parents);
    }

    private String commitAt(Path signer, String date, String... parents) {
        return create(forge, signer, "dated " + date, date, parents);
    }

    private String commitIn(Path repository, Path signer, String message, String... parents) {
        return create(repository, signer, message, "2026-01-01T00:00:00Z", parents);
    }

    private String create(
            Path repository, Path signer, String message, String date, String... parents) {
        final List<String> all = new ArrayList<>(List.of("git"));
        if (signer != null) {
            all.addAll(List.of("-c", "gpg.format=ssh", "-c", "user.signingkey=" + signer));
        }
        final int tree = all.size() + 1;
        all.addAll(List.of("commit-tree", "<the empty tree>"));
        for (final String parent : parents) {
            all.addAll(List.of("-p", parent));
        }
        if (signer != null) {
            all.add("-S");
        }
        all.addAll(List.of("-m", message));
        final Map<String, String> environment = new HashMap<>(quiet());
        environment.put("GIT_AUTHOR_DATE", date);
        environment.put("GIT_COMMITTER_DATE", date);
        // The empty tree's id depends on the repository's object format.
        all.set(
                tree,
                runner.runOrFail(
                                Command.of(
                                                "git",
                                                "--git-dir",
                                                gitDir(repository).toString(),
                                                "hash-object",
                                                "-t",
                                                "tree",
                                                "-w",
                                                "--stdin")
                                        .withInput("")
                                        .withEnvironment(quiet()))
                        .trimmedOutput());
        return runner.runOrFail(
                        Command.of(all)
                                .withEnvironment(environment)
                                .withEnvironment(Map.of("GIT_DIR", gitDir(repository).toString())))
                .trimmedOutput();
    }

    private static Path gitDir(Path repository) {
        return Files.isDirectory(repository.resolve(".git"))
                ? repository.resolve(".git")
                : repository;
    }

    private String git(Path in, String... args) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(List.of(args));
        return runner.runOrFail(Command.of(all).in(in).withEnvironment(quiet())).trimmedOutput();
    }

    private static Map<String, String> quiet() {
        return Map.of(
                "GIT_CONFIG_GLOBAL", "/dev/null",
                "GIT_CONFIG_NOSYSTEM", "1",
                "LC_ALL", "C",
                "GIT_AUTHOR_NAME", "A",
                "GIT_AUTHOR_EMAIL", "a@example.org",
                "GIT_COMMITTER_NAME", "A",
                "GIT_COMMITTER_EMAIL", "a@example.org");
    }
}
