package org.fuin.porelo.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link SignedArrivals}, with commits git signed through certificates. */
class SignedArrivalsTest {

    private static final String PRINCIPAL = "task:demo:fix-1a2b";

    @TempDir Path dir;

    private Path mirror;

    private Path work;

    private String base;

    @BeforeEach
    void repositories() throws Exception {
        keygen("person");
        keygen("task");
        keygen("stranger");
        mirror = dir.resolve("mirror.git");
        work = dir.resolve("work");
        run(dir, "git", "init", "-q", "--bare", mirror.toString());
        run(dir, "git", "init", "-q", "-b", "main", work.toString());
        base = commit("base", null, null);
        run(work, "git", "push", "-q", mirror.toString(), "main:refs/heads/main");
        Files.writeString(
                dir.resolve("allowed"),
                "task:demo:* cert-authority,namespaces=\"git\" "
                        + Files.readString(dir.resolve("person.pub")));
    }

    @Test
    void a_commit_signed_through_its_task_certificate_is_accepted_while_it_holds()
            throws Exception {
        certify("person", PRINCIPAL, "+1d");
        final String signed = commit("work", "task-cert.pub", null);

        assertThat(arrivals(Instant.now()).judge(base, signed)).isNull();
    }

    @Test
    void an_unsigned_commit_a_plain_key_or_another_principal_is_refused_naming_the_commit()
            throws Exception {
        final String unsigned = commit("unsigned", null, null);
        assertThat(arrivals(Instant.now()).judge(base, unsigned))
                .contains(unsigned)
                .contains("not signed");

        final String plain = commit("plain", "task.pub", null);
        assertThat(arrivals(Instant.now()).judge(unsigned, plain)).contains(plain);

        certify("person", "task:demo:another", "+1d");
        final String other = commit("other", "task-cert.pub", null);
        assertThat(arrivals(Instant.now()).judge(plain, other)).contains(other);

        Files.delete(dir.resolve("task-cert.pub"));
        certify("stranger", PRINCIPAL, "+1d");
        final String stranger = commit("stranger", "task-cert.pub", null);
        assertThat(arrivals(Instant.now()).judge(other, stranger)).contains(stranger);
    }

    @Test
    void a_commit_is_judged_by_its_arrival_not_by_the_date_it_carries() throws Exception {
        certify("person", PRINCIPAL, "-1h:+1h");
        // Dated inside the certificate's hour, arriving after it: refused, naming the expiry.
        final String dated = commit("late", "task-cert.pub", Instant.now().toString());

        assertThat(arrivals(Instant.now()).judge(base, dated)).isNull();
        assertThat(arrivals(Instant.now().plusSeconds(2 * 3600)).judge(base, dated))
                .contains(dated)
                .contains("expired");

        // Dated long before the certificate, arriving while it holds: accepted.
        final String old = commit("old", "task-cert.pub", "2001-01-01T00:00:00Z");
        assertThat(arrivals(Instant.now()).judge(dated, old)).isNull();
    }

    @Test
    void only_commits_new_to_the_mirror_are_judged() throws Exception {
        certify("person", PRINCIPAL, "+1d");
        final String signed = commit("work", "task-cert.pub", null);

        assertThat(arrivals(Instant.now()).judge(SignedArrivals.ZERO, signed))
                .as("the unsigned base is the mirror's own branch, not this push's")
                .isNull();

        final String unsigned = commit("unsigned", null, null);
        final String again = commit("again", "task-cert.pub", null);
        assertThat(arrivals(Instant.now()).judge(signed, again))
                .as("an unsigned commit below a signed tip is still judged")
                .contains(unsigned);
    }

    private SignedArrivals arrivals(final Instant now) {
        return new SignedArrivals(
                mirror,
                PRINCIPAL,
                dir.resolve("allowed"),
                new GitSubprocess(),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    /** Commits in the work tree and pushes it to the mirror as the task's incoming ref. */
    private String commit(final String message, final String signingKey, final String date)
            throws Exception {
        Files.writeString(work.resolve(message + ".txt"), message);
        run(work, "git", "add", message + ".txt");
        final List<String> command =
                new ArrayList<>(
                        List.of(
                                "git",
                                "-c",
                                "user.name=Task",
                                "-c",
                                "user.email=task@example.org",
                                "-c",
                                "gpg.format=ssh"));
        if (signingKey != null) {
            command.addAll(
                    List.of(
                            "-c",
                            "user.signingkey=" + dir.resolve(signingKey),
                            "commit",
                            "-q",
                            "-S"));
        } else {
            command.addAll(List.of("-c", "commit.gpgsign=false", "commit", "-q"));
        }
        if (date != null) {
            command.add("--date=" + date);
        }
        command.addAll(List.of("-m", message));
        final ProcessBuilder builder = new ProcessBuilder(command).directory(work.toFile());
        if (date != null) {
            builder.environment().put("GIT_COMMITTER_DATE", date);
        }
        assertThat(builder.redirectErrorStream(true).start().waitFor()).as(message).isZero();
        final String head = output(work, "git", "rev-parse", "HEAD");
        run(work, "git", "push", "-q", "-f", mirror.toString(), "HEAD:refs/porelo/incoming/fix");
        return head;
    }

    private void keygen(final String name) throws Exception {
        run(
                dir,
                "ssh-keygen",
                "-q",
                "-t",
                "ed25519",
                "-N",
                "",
                "-C",
                name,
                "-f",
                dir.resolve(name).toString());
    }

    private void certify(final String ca, final String principal, final String validity)
            throws Exception {
        run(
                dir,
                "ssh-keygen",
                "-q",
                "-s",
                dir.resolve(ca).toString(),
                "-I",
                "porelo task fix-1a2b by alice",
                "-n",
                principal,
                "-V",
                validity,
                dir.resolve("task.pub").toString());
    }

    private static void run(final Path in, final String... command) throws Exception {
        final Process process =
                new ProcessBuilder(command)
                        .directory(in.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        assertThat(process.waitFor()).as(String.join(" ", command)).isZero();
    }

    private static String output(final Path in, final String... command) throws Exception {
        final Process process = new ProcessBuilder(command).directory(in.toFile()).start();
        final String out = new String(process.getInputStream().readAllBytes()).strip();
        assertThat(process.waitFor()).isZero();
        return out;
    }
}
