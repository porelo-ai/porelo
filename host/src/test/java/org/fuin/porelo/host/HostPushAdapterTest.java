package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;
import static org.fuin.porelo.host.PushJudgementTest.*;
import static org.fuin.porelo.host.PushRequestTest.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards checked host paths, confinement, and durable arrivals with a synthetic installed tree. */
class HostPushAdapterTest {
    @TempDir Path directory;
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC);

    final class Fixture {
        final HostReadFixture tree;
        final List<List<String>> calls = new ArrayList<>();
        final long uid = ((Number) Files.getAttribute(directory, "unix:uid")).longValue();
        long accountUid = uid;
        final String home = "/home/forge";
        final Path quarantine;
        final HostPush push;
        HostPrograms.Result answer = accepted();
        String absent = "";
        final java.io.StringWriter errors = new java.io.StringWriter();
        Runnable inspect = () -> {};

        Fixture() throws Exception {
            tree = new HostReadFixture(directory.resolve("root"));
            tree.put("/etc/porelo-host/forge.conf", "account=forge\n");
            tree.put(
                    "/etc/porelo-host/projects/alpha.conf",
                    "admin=alice\nsigned_by="
                            + HostReadFixture.KEY
                            + "\npublisher=porelo-publisher-alpha\n");
            tree.put("/etc/subuid", "forge:100000:65536\n");
            tree.put("/etc/ssh/porelo-revoked.krl", "fixture KRL");
            tree.put("/etc/porelo-host/signers/alpha.allowed", "allowed\n");
            tree.put("/etc/porelo-host/signers/alpha.certifiers", "certifiers\n");
            quarantine =
                    tree.path(
                            home
                                    + "/.local/share/containers/storage/volumes/forgejo-data/_data/"
                                    + QUARANTINE.substring("/var/lib/gitea/".length()));
            Files.createDirectories(quarantine);
            final var runtime =
                    new ProjectRuntime(
                            tree.files(),
                            (argv, input, env, timeout) -> {
                                assertThat(env)
                                        .as("caller environment never reaches host programs")
                                        .isEqualTo(HostPrograms.ENVIRONMENT);
                                if (argv.getFirst().equals("/usr/bin/getent")) {
                                    assertThat(argv)
                                            .as("the service account comes from forge.conf")
                                            .containsExactly("/usr/bin/getent", "passwd", "forge");
                                    return new HostPrograms.Result(
                                            0,
                                            "forge:x:"
                                                    + accountUid
                                                    + ":"
                                                    + accountUid
                                                    + "::"
                                                    + home
                                                    + ":/bin/sh\n");
                                }
                                calls.add(List.copyOf(argv));
                                assertThat(timeout)
                                        .as("a verifier must not outlive the hook")
                                        .isEqualTo(Duration.ofSeconds(280));
                                assertThat(new String(input, StandardCharsets.US_ASCII))
                                        .as("the worker sees only hook refs")
                                        .isEqualTo(line(OLD, NEXT, "refs/heads/main"));
                                inspect.run();
                                return answer;
                            },
                            name -> {
                                if (name.equals(absent))
                                    throw new HostRefusal(
                                            69, name + " is missing from the fixed PATH");
                                return Path.of("/usr/bin", name);
                            },
                            org.fuin.porelo.organization.gittree.HttpForgeAdmin::new);
            push =
                    new HostPush(
                            tree.files(), runtime, CLOCK, new java.io.PrintWriter(errors, true));
        }

        String serve(byte[] request, long peer) throws Exception {
            final var output = new ByteArrayOutputStream();
            push.serve(new ByteArrayInputStream(request), output, peer);
            return output.toString(StandardCharsets.UTF_8);
        }

        Path ledger() {
            return tree.path("/var/lib/porelo-host/arrivals/alpha/app.jsonl");
        }
    }

    @Test
    void stagesCheckedFilesConfinesTheVerifierAndAppendsItsSignerDurablyBeforeAnswering()
            throws Exception {
        final var f = new Fixture();
        f.inspect =
                () -> {
                    try {
                        final var args = f.calls.getLast();
                        final String repository = f.quarantine.getParent().getParent().toString();
                        assertThat(args)
                                .as(
                                        "untrusted git runs as the forge, without network, writable host paths or home")
                                .contains(
                                        "User=forge",
                                        "PrivateNetwork=yes",
                                        "PrivateTmp=yes",
                                        "ProtectHome=tmpfs",
                                        "ProtectSystem=strict",
                                        "NoNewPrivileges=yes",
                                        "RuntimeMaxSec=270",
                                        "BindReadOnlyPaths=" + repository,
                                        "Environment=HOME=/tmp",
                                        "Environment=GIT_QUARANTINE_PATH=" + f.quarantine,
                                        "Environment=GIT_OBJECT_DIRECTORY=" + f.quarantine,
                                        "Environment=GIT_ALTERNATE_OBJECT_DIRECTORIES="
                                                + f.quarantine.getParent());
                        assertThat(args.getFirst())
                                .as("the worker launcher is checked through fixed PATH")
                                .isEqualTo("/usr/bin/systemd-run");
                        for (String property :
                                args.stream()
                                        .filter(
                                                value ->
                                                        value.contains("=")
                                                                && !value.startsWith("--"))
                                        .toList())
                            assertThat(args.get(args.indexOf(property) - 1))
                                    .as("systemd properties must not become command arguments")
                                    .isEqualTo("-p");
                        for (String name :
                                List.of("signers", "certifiers", "certificates", "revoked.krl")) {
                            final String value =
                                    args.stream()
                                            .filter(
                                                    arg ->
                                                            arg.startsWith(
                                                                    "LoadCredential=" + name + ":"))
                                            .findFirst()
                                            .orElseThrow();
                            final Path staged = Path.of(value.substring(value.indexOf(':') + 1));
                            assertThat((int) Files.getAttribute(staged, "unix:mode") & 0777)
                                    .as("staged trust material stays private")
                                    .isEqualTo(0600);
                            assertThat(Files.readString(staged))
                                    .as("the worker reads the checked files, without substitutions")
                                    .isEqualTo(
                                            switch (name) {
                                                case "signers" -> "allowed\n";
                                                case "certifiers" -> "certifiers\n";
                                                case "revoked.krl" -> "fixture KRL";
                                                default -> "";
                                            });
                        }
                    } catch (IOException ex) {
                        throw new java.io.UncheckedIOException(ex);
                    }
                };
        for (int index = 0; index < 2; index++) {
            assertThat(f.serve(request(BODY), f.uid))
                    .as("success is returned only after recording")
                    .endsWith("porelo: 1 new commit(s) judged and recorded\n")
                    .startsWith("HTTP/1.1 200 OK\r\n");
        }
        assertThat(Files.readAllLines(f.ledger()))
                .as("later pushes append without rewriting earlier arrivals")
                .hasSize(2);
        assertThat(HostJson.object(HostJson.parse(Files.readAllLines(f.ledger()).getFirst())))
                .as("the ledger carries verifiable provenance")
                .containsEntry("signer", commit().get("signer"));
        assertThat((int) Files.getAttribute(f.ledger(), "unix:mode") & 0777)
                .as("arrival records are root's alone")
                .isEqualTo(0600);
        try (var children = Files.list(f.tree.path("/run/porelo-host/push"))) {
            assertThat(children.toList())
                    .as("no per-push trust files survive the worker")
                    .isEmpty();
        }
    }

    @Test
    void checksThePeerBeforeReadingUntrustedHookBytes() throws Exception {
        final var f = new Fixture();
        assertThat(f.serve(new byte[0], f.uid + 1))
                .as("a non-forge peer is never treated as a hook")
                .startsWith("HTTP/1.1 403 Forbidden\r\n")
                .endsWith("porelo: only the forge's own hook asks for a push to be judged\n");
        assertThat(f.calls).as("peer refusal precedes git").isEmpty();
        assertThat(f.ledger()).as("peer refusal cannot record an arrival").doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "git",
                "repositories",
                "alpha",
                "app.git",
                "objects",
                "tmp_objdir-incoming-AbC123"
            })
    void refusesEverySymlinkBetweenTheForgeHomeAndItsQuarantine(String segment) throws Exception {
        final var f = new Fixture();
        Path link = f.quarantine;
        while (!link.getFileName().toString().equals(segment)) link = link.getParent();
        final Path moved = link.resolveSibling(segment + ".real");
        Files.move(link, moved);
        Files.createSymbolicLink(link, moved);
        assertThat(f.serve(request(BODY), f.uid))
                .as("a link cannot replace the repository being judged")
                .startsWith("HTTP/1.1 403 Forbidden\r\n")
                .endsWith(
                        "porelo: the push's directory "
                                + link
                                + " is no directory of the forge's\n");
        assertThat(f.calls).as("no worker runs on substituted paths").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"objects", "alternates", "quarantine"})
    void refusesObjectPathsThatDoNotBelongToTheNamedRepository(String field) throws Exception {
        final var f = new Fixture();
        final String changed =
                BODY.replaceFirst("(?m)^" + field + " [^\n]+", field + " /elsewhere");
        final String reason =
                switch (field) {
                    case "objects" -> "the hook's object directory is not its quarantine";
                    case "alternates" -> "the hook's alternate objects are not this repository's";
                    default -> "the hook names no quarantine of this repository";
                };
        assertThat(f.serve(request(changed), f.uid))
                .as("object directories cannot escape the repository")
                .startsWith("HTTP/1.1 403 Forbidden\r\n")
                .endsWith("porelo: " + reason + "\n");
        assertThat(f.calls).as("invalid object paths never reach git").isEmpty();
    }

    @Test
    void acceptsGitsDotSegmentsWithoutAcceptingDotDot() throws Exception {
        final var f = new Fixture();
        assertThat(
                        f.serve(
                                request(
                                        BODY.replace(
                                                REPOSITORY + "/objects",
                                                REPOSITORY + "/./objects")),
                                f.uid))
                .as("git's dot spelling names the same objects")
                .startsWith("HTTP/1.1 200 OK");
        assertThat(
                        f.serve(
                                request(
                                        BODY.replace(
                                                REPOSITORY + "/objects",
                                                REPOSITORY + "/./objects/../objects")),
                                f.uid))
                .as("parent segments must not widen the worker's read paths")
                .startsWith("HTTP/1.1 403 Forbidden");
    }

    @ParameterizedTest
    @ValueSource(strings = {"porelo", "systemd-run", "krl", "certifiers"})
    void missingJudgementDependenciesReturnServerFailureWithoutRecording(String missing)
            throws Exception {
        final var f = new Fixture();
        if (missing.equals("krl")) Files.delete(f.tree.path("/etc/ssh/porelo-revoked.krl"));
        else if (missing.equals("certifiers"))
            Files.delete(f.tree.path("/etc/porelo-host/signers/alpha.certifiers"));
        else f.absent = missing;
        assertThat(f.serve(request(BODY), f.uid))
                .as("missing trust material never permits a push")
                .startsWith("HTTP/1.1 500 Internal Server Error\r\n");
        assertThat(f.calls).as("a missing dependency stops before verification").isEmpty();
        assertThat(f.ledger()).as("an unjudged push must not be recorded").doesNotExist();
    }

    @Test
    void anUnregisteredProjectsDeletionIsStillRefused() throws Exception {
        final var f = new Fixture();
        Files.delete(f.tree.path("/etc/porelo-host/projects/alpha.conf"));
        assertThat(f.serve(request(FIELDS + "\n\n" + line(OLD, ZERO, "refs/heads/old")), f.uid))
                .as("deletions must not bypass project registration")
                .endsWith("porelo: project alpha is not registered on this host\n");
    }

    @Test
    void failedSignaturesNeverReachTheArrivalLedger() throws Exception {
        final var f = new Fixture();
        f.answer = new HostPrograms.Result(65, "", NEXT + " carries no signature\n");
        assertThat(f.serve(request(BODY), f.uid))
                .as("the hook must see the signature refusal")
                .startsWith("HTTP/1.1 403 Forbidden")
                .endsWith("porelo: " + NEXT + " carries no signature\n");
        assertThat(f.ledger()).as("refused commits cannot become accepted arrivals").doesNotExist();
    }

    @Test
    void aLedgerSymlinkCannotRedirectAnAcceptedArrival() throws Exception {
        final var f = new Fixture();
        final Path other = f.tree.put("/other", "preserve\n");
        Files.createDirectories(f.ledger().getParent());
        Files.createSymbolicLink(f.ledger(), other);
        assertThat(f.serve(request(BODY), f.uid))
                .as("unsafe publication must refuse the push")
                .startsWith("HTTP/1.1 500 Internal Server Error");
        assertThat(Files.readString(other))
                .as("a forged ledger must never overwrite another file")
                .isEqualTo("preserve\n");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void theContainerRootOwnsTheForgeVolumeWhetherSubuidNamesTheAccountOrItsUid(boolean numeric)
            throws Exception {
        final var f = new Fixture();
        f.accountUid = f.uid + 1;
        f.tree.put(
                "/etc/subuid",
                (numeric ? Long.toString(f.accountUid) : "forge") + ":" + f.uid + ":65536\n");
        assertThat(f.serve(request(BODY), f.accountUid))
                .as("podman's volume uid is the first subordinate uid")
                .startsWith("HTTP/1.1 200 OK");
        f.tree.put("/etc/subuid", "forge:" + (f.uid - 1) + ":65536\n");
        assertThat(f.serve(request(BODY), f.accountUid))
                .as("other uids in the range cannot substitute the pushed repository")
                .startsWith("HTTP/1.1 403 Forbidden")
                .contains("is no directory of the forge's");
    }

    @Test
    void onlyTheProjectsTaskCertificatesWithTwoDatesHoldingAtArrivalAreStaged() throws Exception {
        final var f = new Fixture();
        final var current =
                HostJson.object(
                        HostJson.parse(
                                HostReadFixture.task(
                                        "alice", "current", HostReadFixture.FINGERPRINT)));
        current.put("valid_after", "2026-10-30T12:00:00Z");
        current.put("valid_before", "2026-10-31T12:00:00Z");
        f.tree.put("/var/lib/porelo-host/tasks/alice/current.json", HostJson.write(current));
        for (String name : List.of("foreign", "ended", "future", "no-start")) {
            final var entry = new java.util.LinkedHashMap<>(current);
            entry.put("task", name);
            switch (name) {
                case "foreign" -> entry.put("principals", List.of("task:beta:foreign"));
                case "ended" -> entry.put("valid_before", "2026-10-30T12:00:00Z");
                case "future" -> entry.put("valid_after", "2026-10-30T12:00:01Z");
                default -> entry.put("valid_after", null);
            }
            f.tree.put("/var/lib/porelo-host/tasks/alice/" + name + ".json", HostJson.write(entry));
        }
        assertThat(f.push.certificates("alpha"))
                .as(
                        "an ended, future, undated or foreign task certificate cannot authorize this push")
                .isEqualTo(ProjectRecords.pythonJson(current, true) + "\n");
    }

    @Test
    void anOversizedRevocationListRefusesBeforeTheWorkerAndRemovesThePushDirectory()
            throws Exception {
        final var f = new Fixture();
        f.tree.put("/etc/ssh/porelo-revoked.krl", "a".repeat(PushRequest.MAXIMUM + 1));
        assertThat(f.serve(request(BODY), f.uid))
                .as("an unstageable revocation list cannot be omitted")
                .startsWith("HTTP/1.1 500 Internal Server Error")
                .endsWith(" is larger than a unit's credential\n");
        assertThat(f.calls).as("no worker can judge with a truncated KRL").isEmpty();
        try (var paths = Files.list(f.tree.path("/run/porelo-host/push"))) {
            assertThat(paths.toList())
                    .as("failed staging cannot leave per-push trust files")
                    .isEmpty();
        }
    }

    // Prevent account-wide task names from authorizing another project's branch.
    @Test
    void aPersonsTaskInAnotherProjectCannotAuthorizeThisProjectsRef() throws Exception {
        final var f = new Fixture();
        f.tree.person("alice", "member=alpha project-admin\nmember=beta project-admin\n", "");
        final var task =
                HostJson.object(
                        HostJson.parse(
                                HostReadFixture.task(
                                        "alice", "foreign", HostReadFixture.FINGERPRINT)));
        task.put("project", "beta");
        task.put("principals", List.of("task:beta:foreign"));
        f.tree.put("/var/lib/porelo-host/tasks/alice/foreign.json", HostJson.write(task));
        assertThat(
                        f.serve(
                                request(
                                        FIELDS.replace("pusher alice", "pusher porelo-alice")
                                                + "\n\n"
                                                + line(OLD, ZERO, "refs/heads/porelo/foreign")),
                                f.uid))
                .as("a task's name authorizes only its recorded project")
                .startsWith("HTTP/1.1 403 Forbidden");
        assertThat(f.calls).as("a foreign task cannot reach verification").isEmpty();
    }

    @Test
    void anEndedTaskRecordStillOwnsItsProjectsBranch() throws Exception {
        final var f = new Fixture();
        f.tree.person("alice", "member=alpha project-admin\n", "");
        final var task =
                HostJson.object(
                        HostJson.parse(
                                HostReadFixture.task(
                                        "alice", "ended", HostReadFixture.FINGERPRINT)));
        task.put("valid_before", "2026-10-01T00:00:00Z");
        f.tree.put("/var/lib/porelo-host/tasks/alice/ended.json", HostJson.write(task));
        assertThat(
                        f.serve(
                                request(
                                        FIELDS.replace("pusher alice", "pusher porelo-alice")
                                                + "\n\n"
                                                + line(OLD, ZERO, "refs/heads/porelo/ended")),
                                f.uid))
                .as("task end removes the token, while the retained record still names the branch")
                .startsWith("HTTP/1.1 200 OK");
    }

    // Prevent an accepted append from making the ledger unreadable on the next follow pass.
    @Test
    void anAppendThatWouldCrossTheLedgerReadBoundRefusesWithoutWriting() throws Exception {
        final var f = new Fixture();
        final String row =
                ProjectRecords.pythonJson(verified(accepted()).entries().getFirst(), true) + "\n";
        final int maximum = 16 * 1024 * 1024;
        f.tree.put(
                "/var/lib/porelo-host/arrivals/alpha/app.jsonl",
                " ".repeat(maximum - row.getBytes(StandardCharsets.UTF_8).length));
        assertThat(f.serve(request(BODY), f.uid))
                .as("an append ending exactly at the read limit is allowed")
                .startsWith("HTTP/1.1 200 OK");
        final byte[] before = f.tree.files().readBytes(f.ledger());
        assertThat(f.serve(request(BODY), f.uid))
                .as("a push must not make its own ledger unreadable")
                .startsWith("HTTP/1.1 500 Internal Server Error");
        assertThat(Files.readAllBytes(f.ledger()))
                .as("refusal leaves the complete old ledger untouched")
                .isEqualTo(before);
        assertThat(f.errors.toString())
                .as("the journal explains the ledger refusal")
                .contains("arrival", "limit");
    }

    // Prevent lock and disk failures from being blamed on the hook's HTTP input.
    @Test
    void aConfigurationLockFailureIsAHostFailureWithAJournalDiagnostic() throws Exception {
        final var f = new Fixture();
        final Path conf = f.tree.path("/etc/porelo-host/forge.conf");
        final var once = new java.util.concurrent.atomic.AtomicBoolean();
        f.tree.afterAttributes =
                path -> {
                    if (path.equals(conf) && once.compareAndSet(false, true)) {
                        try {
                            Files.delete(conf);
                        } catch (IOException ex) {
                            throw new java.io.UncheckedIOException(ex);
                        }
                    }
                };
        assertThat(f.serve(request(BODY), f.uid))
                .as("a configuration lock failure is not a request-read failure")
                .startsWith("HTTP/1.1 500 Internal Server Error")
                .contains("the host could not judge or record this push");
        assertThat(f.errors.toString())
                .as("every served 500 reaches the journal")
                .contains("could not judge or record");
    }

    @ParameterizedTest
    @ValueSource(strings = {"worker", "dependency", "input"})
    void everyServerFailureHasAStandardErrorDiagnostic(String failure) throws Exception {
        final var f = new Fixture();
        final var out = new ByteArrayOutputStream();
        if (failure.equals("worker")) f.answer = new HostPrograms.Result(0, "not JSON");
        if (failure.equals("dependency")) f.absent = "porelo";
        final java.io.InputStream input =
                failure.equals("input")
                        ? new java.io.InputStream() {
                            @Override
                            public int read() throws IOException {
                                throw new IOException("fixture read failure");
                            }
                        }
                        : new ByteArrayInputStream(request(BODY));
        f.push.serve(input, out, f.uid);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .as("the host fails closed")
                .startsWith("HTTP/1.1 500 Internal Server Error");
        assertThat(f.errors.toString())
                .as("every 500 must explain the failure on stderr: " + failure)
                .isNotBlank();
        if (failure.equals("input"))
            assertThat(out.toString(StandardCharsets.UTF_8))
                    .as("only request-read failures blame the request")
                    .contains("the hook's request could not be read");
    }
}
