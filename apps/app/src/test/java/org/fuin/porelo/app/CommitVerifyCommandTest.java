package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
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
    void refuses_a_plain_task_key_whose_certifier_line_negates_git_after_an_escaped_quote()
            throws Exception {
        final Path records = records(certify(task, certifier, PRINCIPAL, "-1m:+7d"), "fix-login");
        final String plain = commit(task, "by the plain key", base);
        Files.writeString(
                certifiers,
                "task:demo:* cert-authority,namespaces=\"git,a\\\",!git,\\\"\" "
                        + publicKey(certifier)
                        + "\n");

        assertThat(
                        verify(
                                push(base, plain, "refs/heads/main"),
                                "--certificates",
                                records.toString()))
                .as("ssh-keygen keeps \\\" inside the quotes, so !git is in the list: %s", err)
                .isEqualTo(65);
        assertThat(err.toString()).contains("which is no certifier for " + PRINCIPAL);
    }

    @Test
    void accepts_a_plain_task_key_whose_certifier_line_quotes_an_escaped_quote_and_a_space()
            throws Exception {
        final Path records = records(certify(task, certifier, PRINCIPAL, "-1m:+7d"), "fix-login");
        final String plain = commit(task, "by the plain key", base);
        Files.writeString(
                certifiers,
                "task:demo:* cert-authority,namespaces=\"x\\\" y,git\" "
                        + publicKey(certifier)
                        + "\n");

        assertThat(
                        verify(
                                push(base, plain, "refs/heads/main"),
                                "--certificates",
                                records.toString()))
                .as("ssh-keygen reads this line and admits git: %s", err)
                .isZero();
    }

    @Test
    void accepts_a_certifier_line_valid_after_a_time_ending_in_any_utc_suffix_ssh_keygen_reads()
            throws Exception {
        final Path records = records(certify(task, certifier, PRINCIPAL, "-1m:+7d"), "fix-login");
        final String plain = commit(task, "by the plain key", base);
        for (final String time : List.of("20200101z", "20200101UTC", "202001011200utc")) {
            Files.writeString(
                    certifiers,
                    "task:demo:* cert-authority,valid-after=\""
                            + time
                            + "\" "
                            + publicKey(certifier)
                            + "\n");

            assertThat(
                            verify(
                                    push(base, plain, "refs/heads/main"),
                                    "--certificates",
                                    records.toString()))
                    .as("ssh-keygen holds a line valid after %s as in force: %s", time, err)
                    .isZero();
        }
        Files.writeString(
                certifiers,
                "task:demo:* cert-authority,valid-after=\"20990101utc\" "
                        + publicKey(certifier)
                        + "\n");

        assertThat(
                        verify(
                                push(base, plain, "refs/heads/main"),
                                "--certificates",
                                records.toString()))
                .as("a line valid only after its arrival certifies nothing, whatever its suffix")
                .isEqualTo(65);
    }

    @Test
    void refuses_a_commit_whose_signature_covers_a_signature_header_git_leaves_out()
            throws Exception {
        final String raw = withHeaders(rawOf(commit(null, "crafted", base)), "gpgsig-sha256 x");
        final String crafted =
                store(withHeaders(raw, signatureHeader("gpgsig", sign(person, raw))));

        assertThat(verify(push(base, crafted, "refs/heads/main")))
                .as("git verifies the commit without its gpgsig-sha256 header, and so refuses it")
                .isEqualTo(65);
        assertThat(err.toString()).startsWith(crafted + " ");
    }

    /**
     * Git leaves out every header whose name starts with {@code gpgsig}, with its continuation
     * lines, so Porelo must decide each such commit as {@code git verify-commit} does.
     */
    @Test
    void decides_a_commit_carrying_a_header_starting_with_gpgsig_as_git_does() throws Exception {
        for (final String header :
                List.of("gpgsig\n alone, continued", "gpgsig-other x", "gpgsigfoo y\n z")) {
            final String raw = withHeaders(rawOf(commit(null, "over", base)), header);
            final String over =
                    store(withHeaders(raw, signatureHeader("gpgsig", sign(person, raw))));
            final String rest = rawOf(commit(null, "beside", base));
            final String beside =
                    store(withHeaders(rest, header, signatureHeader("gpgsig", sign(person, rest))));

            assertThat(gitVerifies(over)).as("git refuses a signature over %s", header).isFalse();
            assertThat(verify(push(base, over, "refs/heads/main")))
                    .as("a signature over %s is refused as git refuses it: %s", header, err)
                    .isEqualTo(65);
            assertThat(gitVerifies(beside))
                    .as("git accepts %s beside the signature", header)
                    .isTrue();
            assertThat(verify(push(base, beside, "refs/heads/main")))
                    .as(
                            "%s beside a signature over the rest is accepted as git does: %s",
                            header, err)
                    .isZero();
        }
    }

    /**
     * ssh-keygen reads a principal list in quotes, which may hold spaces, and ends it at the
     * closing quote even without a space behind it; such a signers file is no invalid one.
     */
    @Test
    void accepts_a_person_whose_principal_ssh_keygen_reads_in_quotes() throws Exception {
        final String byPerson = commit(person, "by alice", base);
        for (final String principal :
                List.of("\"Alice Smith\" ", "\"Alice Smith\"", "Al\"ice Smith\" ")) {
            Files.writeString(
                    signers, principal + "namespaces=\"git\" " + publicKey(person) + "\n");

            assertThat(gitVerifies(byPerson)).as("git accepts %s", principal).isTrue();
            assertThat(verify(push(base, byPerson, "refs/heads/main"), "--json"))
                    .as("ssh-keygen and git accept %s as a principal: %s", principal, err)
                    .isZero();
            assertThat(((Map<?, ?>) verdicts().getFirst().get("signer")).get("principal"))
                    .as("the principal is named as ssh-keygen reads it")
                    .isEqualTo("Alice Smith");
        }
    }

    /**
     * Git asks ssh-keygen which principals a key's line names and verifies by each, so a line
     * naming none is refused, and of several lines for one key the first in force decides.
     */
    @Test
    void decides_a_person_by_the_principals_git_finds_for_the_key() throws Exception {
        final String byPerson = commit(person, "by alice", base);
        final String key = publicKey(person);
        for (final String refused :
                List.of(
                        "!alice " + key,
                        "alice,!alice " + key,
                        "\"\" " + key,
                        "!alice " + key + "\nbob " + key,
                        "alice-mail namespaces=\"mail\" " + key + "\nbob " + key)) {
            Files.writeString(signers, refused + "\n");

            assertThat(gitVerifies(byPerson)).as("git refuses %s", refused).isFalse();
            assertThat(verify(push(base, byPerson, "refs/heads/main")))
                    .as("%s is refused as git refuses it: %s", refused, err)
                    .isEqualTo(65);
        }
        for (final String accepted :
                List.of(
                        "alice-future valid-after=\"20990101Z\" " + key + "\nbob " + key,
                        "bob " + key + "\nalice " + key,
                        "x,bob namespaces=\"mail\" " + key + "\nbob " + key)) {
            Files.writeString(signers, accepted + "\n");

            assertThat(gitVerifies(byPerson)).as("git accepts %s", accepted).isTrue();
            assertThat(verify(push(base, byPerson, "refs/heads/main"), "--json"))
                    .as("%s is accepted as git accepts it: %s", accepted, err)
                    .isZero();
            assertThat(((Map<?, ?>) verdicts().getFirst().get("signer")).get("principal"))
                    .as("the signer is the principal git verified by")
                    .isEqualTo("bob");
        }
    }

    /** A card's end date is on the line from its first day: a key ends then, and not before. */
    @Test
    void holds_a_person_whose_line_ends_ahead_and_refuses_one_whose_line_has_ended()
            throws Exception {
        final String byPerson = commit(person, "by alice", base);
        final String key = publicKey(person);
        for (final String ahead : List.of("20990101Z", "20990101000000Z", "209901010000utc")) {
            Files.writeString(
                    signers,
                    "demo namespaces=\"git\",valid-after=\"20200101Z\",valid-before=\""
                            + ahead
                            + "\" "
                            + key
                            + "\n");

            assertThat(gitVerifies(byPerson)).as("git accepts %s", ahead).isTrue();
            assertThat(verify(push(base, byPerson, "refs/heads/main")))
                    .as("in force until %s: %s", ahead, err)
                    .isZero();
        }
        for (final String ended : List.of("20200102Z", "20200102000000Z")) {
            Files.writeString(
                    signers,
                    "demo namespaces=\"git\",valid-after=\"20200101Z\",valid-before=\""
                            + ended
                            + "\" "
                            + key
                            + "\n");

            assertThat(verify(push(base, byPerson, "refs/heads/main")))
                    .as("ended %s", ended)
                    .isEqualTo(65);
            assertThat(err.toString()).contains("no signer in force");
        }
    }

    /**
     * ssh-keygen takes only a space or a tab as a blank in an allowed-signers line, so no other
     * blank may part a principal from its key or vanish from the front of a principal.
     */
    @Test
    void reads_only_a_space_or_a_tab_as_a_blank_as_ssh_keygen_does() throws Exception {
        final String byPerson = commit(person, "by alice", base);
        final String enQuad = Character.toString(0x2000);
        for (final String blank : List.of("\u000b", "\u000c", enQuad)) {
            Files.writeString(signers, "alice" + blank + publicKey(person) + "\n");

            assertThat(gitVerifies(byPerson)).as("git refuses %s as a blank", blank).isFalse();
            assertThat(verify(push(base, byPerson, "refs/heads/main")))
                    .as("a line parted by %s is not accepted: %s", blank.codePointAt(0), err)
                    .isNotZero();
        }
        Files.writeString(signers, "alice " + publicKey(person) + "\n");
        final Path certificate = certify(task, certifier, PRINCIPAL, "-1m:+7d");
        final String byTask = commit(certificate, "by the task", base);
        Files.writeString(
                certifiers,
                enQuad
                        + "task:demo:* cert-authority,namespaces=\"git\" "
                        + publicKey(certifier)
                        + "\n");

        assertThat(verify(push(base, byTask, "refs/heads/main")))
                .as("ssh-keygen reads the principal with its leading space: %s", err)
                .isEqualTo(65);
    }

    /** A certifier's principal list in quotes is handed to ssh-keygen as it reads it. */
    @Test
    void accepts_a_plain_task_key_whose_certifier_principals_ssh_keygen_reads_in_quotes()
            throws Exception {
        final Path records = records(certify(task, certifier, PRINCIPAL, "-1m:+7d"), "fix-login");
        final String plain = commit(task, "by the plain key", base);
        for (final String principals :
                List.of("\"other name,task:demo:*\" ", "other,\"task:demo:*\"")) {
            Files.writeString(
                    certifiers,
                    principals
                            + "cert-authority,namespaces=\"git\" "
                            + publicKey(certifier)
                            + "\n");

            assertThat(
                            verify(
                                    push(base, plain, "refs/heads/main"),
                                    "--certificates",
                                    records.toString()))
                    .as("ssh-keygen reads %s as certifying %s: %s", principals, PRINCIPAL, err)
                    .isZero();
        }
    }

    /**
     * ssh-keygen rolls a day past a month's end and a second of 60 or 61 over into the next, and
     * holds a certifier valid from that instant; it refuses any other field out of range.
     */
    @Test
    void holds_a_certifier_valid_after_a_time_ssh_keygen_rolls_over_from_that_instant()
            throws Exception {
        final Map<String, String> rolled =
                Map.of(
                        "20210229", "2021-03-01T00:00:00Z",
                        "20200431", "2020-05-01T00:00:00Z",
                        "20200231", "2020-03-02T00:00:00Z",
                        "20200101235960Z", "2020-01-02T00:00:00Z",
                        "20200101235961Z", "2020-01-02T00:00:01Z",
                        "20201231235960", "2021-01-01T00:00:00Z",
                        "20210229UTC", "2021-03-01T00:00:00Z");
        for (final Map.Entry<String, String> each : rolled.entrySet()) {
            final AllowedSigner line = certifierValidAfter(each.getKey());
            final Instant from = Instant.parse(each.getValue());

            assertThat(HostArrivals.inForce(line, from))
                    .as("ssh-keygen holds a line valid after %s from %s", each.getKey(), from)
                    .isTrue();
            assertThat(HostArrivals.inForce(line, from.minusSeconds(1)))
                    .as("and not a second before %s", from)
                    .isFalse();
        }
        for (final String refused :
                List.of(
                        "20200132",
                        "20200100",
                        "20201301",
                        "20200001",
                        "20200101240000Z",
                        "20200101236000",
                        "20200101235962Z",
                        "19700101000000Z")) {
            assertThat(
                            HostArrivals.inForce(
                                    certifierValidAfter(refused),
                                    Instant.parse("2030-01-01T00:00:00Z")))
                    .as("ssh-keygen refuses a line valid after %s", refused)
                    .isFalse();
        }
    }

    private AllowedSigner certifierValidAfter(String time) throws Exception {
        return java.util.Objects.requireNonNull(
                AllowedSigner.parse(
                        "task:demo:* cert-authority,valid-after=\""
                                + time
                                + "\" "
                                + publicKey(certifier)));
    }

    @Test
    void accepts_a_certifier_line_valid_after_a_time_ssh_keygen_rolls_over() throws Exception {
        final Path records = records(certify(task, certifier, PRINCIPAL, "-1m:+7d"), "fix-login");
        final String plain = commit(task, "by the plain key", base);
        for (final String time : List.of("20210229", "20200431", "20200101235960Z")) {
            Files.writeString(
                    certifiers,
                    "task:demo:* cert-authority,valid-after=\""
                            + time
                            + "\" "
                            + publicKey(certifier)
                            + "\n");

            assertThat(
                            verify(
                                    push(base, plain, "refs/heads/main"),
                                    "--certificates",
                                    records.toString()))
                    .as("ssh-keygen holds a line valid after %s as in force: %s", time, err)
                    .isZero();
        }
    }

    @Test
    void accepts_a_commit_signed_for_both_object_formats() throws Exception {
        final String raw = rawOf(commit(null, "signed twice", base));
        final String signature = sign(person, raw);
        final String both =
                store(
                        withHeaders(
                                raw,
                                signatureHeader("gpgsig", signature),
                                signatureHeader("gpgsig-sha256", signature)));

        assertThat(verify(push(base, both, "refs/heads/main")))
                .as("git verifies the commit without either signature header: %s", err)
                .isZero();
    }

    @Test
    void cuts_a_long_reason_without_splitting_a_character() {
        final String reason = "a".repeat(16 * 1024 - 1) + "😀b";

        final Map<?, ?> answer =
                (Map<?, ?>)
                        Json.parse(
                                new HostArrivals.Answer(Instant.EPOCH, List.of(), reason).json());

        assertThat((String) answer.get("reason"))
                .as("half a character is no text a strict UTF-8 reader of the answer accepts")
                .isEqualTo("a".repeat(16 * 1024 - 1));
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

    private String rawOf(String commit) {
        return runner.runOrFail(
                        Command.of(
                                        "git",
                                        "--git-dir",
                                        forge.toString(),
                                        "cat-file",
                                        "commit",
                                        commit)
                                .withEnvironment(quiet()))
                .standardOutput();
    }

    /** The headers placed after the commit's own, before the blank line. */
    private static String withHeaders(String raw, String... headers) {
        final int end = raw.indexOf("\n\n");
        return raw.substring(0, end + 1) + String.join("\n", headers) + raw.substring(end);
    }

    /** A signature as git writes it into a header, each further line indented by one space. */
    private static String signatureHeader(String name, String armored) {
        return name + " " + armored.strip().replace("\n", "\n ");
    }

    private String sign(Path key, String payload) {
        return runner.runOrFail(
                        Command.of(
                                        "ssh-keygen",
                                        "-q",
                                        "-Y",
                                        "sign",
                                        "-n",
                                        "git",
                                        "-f",
                                        key.toString())
                                .withInput(payload))
                .standardOutput();
    }

    /** Whether git itself verifies a commit by the signers file. */
    private boolean gitVerifies(String commit) {
        return runner.run(
                        Command.of(
                                        "git",
                                        "--git-dir",
                                        forge.toString(),
                                        "-c",
                                        "gpg.format=ssh",
                                        "-c",
                                        "gpg.ssh.allowedSignersFile=" + signers,
                                        "verify-commit",
                                        commit)
                                .withEnvironment(quiet()))
                .successful();
    }

    private String store(String raw) {
        return runner.runOrFail(
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
