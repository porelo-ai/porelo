package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;
import static org.fuin.porelo.host.ProjectFixture.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class HostProjectMeasurementsTest {
    @TempDir Path root;
    ProjectFixture host;
    final Clock clock = Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC);
    final List<String> readers = new ArrayList<>();
    String applied =
            "{\"revoked\":[],\"persons\":[{\"login\":\"alice\",\"revoked\":false,\"expires\":null},{\"login\":\"bob\",\"revoked\":false,\"expires\":null}],\"projects\":[{\"project\":\"alpha\",\"members\":[{\"login\":\"alice\",\"locked\":false},{\"login\":\"bob\",\"locked\":false}]}]}";

    @BeforeEach
    void start() throws Exception {
        host = new ProjectFixture(root);
        host.add();
        host.readKeys();
        host.clear();
        host.write("/etc/porelo-host/organization/applied.json", applied);
        host.program =
                argv -> {
                    if (argv.getFirst().equals("/usr/bin/getent")
                            && List.of("alice", "bob").contains(argv.getLast())) {
                        return new HostPrograms.Result(
                                0,
                                argv.getLast()
                                        + ":x:"
                                        + (argv.getLast().equals("alice") ? "1001" : "1002")
                                        + ":1000::/home/test:/bin/false\n");
                    }
                    if (argv.getFirst().equals("/usr/bin/setfacl")) {
                        try {
                            final Path stage = Path.of(argv.getLast());
                            assertThat((int) Files.getAttribute(stage, "unix:mode") & 0777)
                                    .isEqualTo(0600);
                            assertThat(Files.getAttribute(stage, "unix:uid"))
                                    .isEqualTo(host.files.owner);
                        } catch (Exception ex) {
                            throw new AssertionError(ex);
                        }
                        readers.clear();
                        readers.addAll(List.of(argv.get(2).split(",")));
                        return new HostPrograms.Result(0, "");
                    }
                    return null;
                };
    }

    void measure() throws Exception {
        new ProjectMeasurements(host.files, host.runtime, clock).measure("forge", "alpha");
    }

    Path answer() {
        return host.files.etc.resolve("organization/central/alpha.json");
    }

    // Failed reader replacement cannot preserve a departed member's readable central answer.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"prepare-getent", "prepare-acl", "measure-getent", "measure-acl"})
    void failedReaderReplacementRemovesTheOldAnswer(String failure) throws Exception {
        measure();
        final var before = host.program;
        host.program =
                argv -> {
                    if (argv.getFirst()
                            .equals(
                                    failure.endsWith("getent")
                                            ? "/usr/bin/getent"
                                            : "/usr/bin/setfacl"))
                        return new HostPrograms.Result(1, "");
                    return before.apply(argv);
                };
        final var measurements = new ProjectMeasurements(host.files, host.runtime, clock);
        assertThatThrownBy(
                        () -> {
                            if (failure.startsWith("prepare"))
                                measurements.prepare(List.of("alpha"));
                            else measure();
                        })
                .isInstanceOf(HostRefusal.class);
        assertThat(answer()).as("failure revokes the old answer").doesNotExist();
    }

    // A failed new measurement must not leave an old successful central answer visible.
    @Test
    void aFailedFinalAclRemovesTheEarlierCentralAnswer() throws Exception {
        measure();
        final var before = host.program;
        final var asked = new java.util.concurrent.atomic.AtomicBoolean();
        host.program =
                argv -> {
                    if (argv.contains("ls-remote")) asked.set(true);
                    if (argv.getFirst().equals("/usr/bin/setfacl") && asked.get())
                        return new HostPrograms.Result(1, "");
                    return before.apply(argv);
                };
        assertThatThrownBy(this::measure).isInstanceOf(HostRefusal.class);
        assertThat(asked).as("failure follows the new central measurement").isTrue();
        assertThat(answer()).as("a failed measurement leaves no stale success").doesNotExist();
    }

    @Test
    void theExactFollowRecordGoesOnlyToUnlockedMembers() throws Exception {
        measure();
        assertThat(Files.readString(answer()))
                .isEqualTo(
                        "{\"project\": \"alpha\", \"read_at\": \"2026-11-03T00:00:00Z\", \"repositories\": [{\"repository\": \"code\", \"state\": \"up-to-date\"}]}\n");
        assertThat(readers).containsExactly("user:1001:r--", "user:1002:r--");
        assertThat((int) Files.getAttribute(answer().getParent(), "unix:mode") & 0777)
                .isEqualTo(0711);
        assertThat((int) Files.getAttribute(answer(), "unix:mode") & 0777).isEqualTo(0600);
        host.write(
                "/etc/porelo-host/organization/applied.json",
                applied.replace("\"alice\",\"locked\":false", "\"alice\",\"locked\":true"));
        measure();
        assertThat(readers).containsExactly("user:1002:r--");
    }

    @Test
    void oldReadersAreRevokedBeforeAskingCentral() throws Exception {
        measure();
        host.write(
                "/etc/porelo-host/organization/applied.json",
                applied.replace("\"alice\",\"locked\":false", "\"alice\",\"locked\":true"));
        final var previous = host.program;
        host.program =
                argv -> {
                    if (argv.contains("ls-remote")) {
                        assertThat(readers).containsExactly("user:1002:r--");
                    }
                    return previous.apply(argv);
                };
        measure();
    }

    @Test
    void aFailedQuestionPublishesNoVouchForAnyRepository() throws Exception {
        host.program =
                argv -> {
                    if (argv.contains("test")) {
                        throw new HostRefusal(69, "fixture unavailable");
                    }
                    if (argv.getFirst().equals("/usr/bin/getent")) {
                        return new HostPrograms.Result(2, "");
                    }
                    return null;
                };
        measure();
        assertThat(Files.readString(answer()))
                .isEqualTo(
                        "{\"project\": \"alpha\", \"read_at\": \"2026-11-03T00:00:00Z\", \"repositories\": []}\n");
    }

    @Test
    void anInterruptedPublicationIsRemovedBeforePublishingAgain() throws Exception {
        measure();
        final Path left = answer().getParent().resolve(".porelo-host-person.abc123");
        Files.writeString(left, "half\n");
        ProjectFixture.mode(left, 0600);
        measure();
        assertThat(left).doesNotExist();
    }

    @Test
    void anImpossibleAheadCountCannotVouchForWork() throws Exception {
        host.localMain = "b".repeat(40);
        host.ahead = "0\n";
        measure();
        assertThat(Files.readString(answer())).endsWith("\"repositories\": []}\n");
    }

    @Test
    void pendingOrLocallyLockedMembersReceiveNoAcl() throws Exception {
        host.write(
                "/etc/porelo-host/people/alice.conf", person("member=host-admin\n", false, true));
        host.write("/etc/porelo-host/people/bob.conf", person("member=host-admin\n", true, false));
        measure();
        assertThat(readers).isEmpty();
        assertThat(host.commands).noneMatch(argv -> argv.contains("setfacl"));
    }

    @Test
    void theWholeMeasurementHasOneBudgetRatherThanOnePerGitCommand() throws Exception {
        final var now = new java.util.concurrent.atomic.AtomicReference<>(clock.instant());
        final Clock moving =
                new Clock() {
                    @Override
                    public ZoneId getZone() {
                        return ZoneOffset.UTC;
                    }

                    @Override
                    public Clock withZone(ZoneId zone) {
                        return this;
                    }

                    @Override
                    public Instant instant() {
                        return now.get();
                    }
                };
        host.localMain = "b".repeat(40);
        final var runtime =
                new ProjectRuntime(
                        host.files,
                        (argv, input, env, timeout) -> {
                            if (argv.contains("ls-remote")) {
                                // Earlier repositories and the central question used most of the
                                // pass.
                                now.set(now.get().plusSeconds(110));
                            } else if (argv.contains("rev-parse")) {
                                assertThat(timeout).isLessThanOrEqualTo(Duration.ofSeconds(10));
                                now.set(now.get().plusSeconds(11));
                                throw new java.io.IOException(
                                        "question exceeded its remaining budget");
                            }
                            return host.runtime.run(argv, input, env, timeout);
                        },
                        Path::of,
                        path -> true,
                        (socket, token) -> {
                            throw new AssertionError();
                        });
        new ProjectMeasurements(host.files, runtime, moving).measure("forge", "alpha");
        assertThat(Files.readString(answer())).endsWith("\"repositories\": []}\n");
        assertThat(host.commands).noneMatch(argv -> argv.contains("merge-base"));
    }

    @Test
    void aLinkedOrganizationDirectoryIsRefusedBeforeReadingAppliedBytes() throws Exception {
        final Path organization = host.files.etc.resolve("organization");
        final Path elsewhere = organization.resolveSibling("elsewhere");
        Files.move(organization, elsewhere);
        Files.createSymbolicLink(organization, elsewhere.getFileName());
        assertThatThrownBy(this::measure)
                .isInstanceOf(HostRefusal.class)
                .hasMessageContaining("not a directory root's alone");
        assertThat(host.commands).isEmpty();
    }

    @Test
    void theCliCanPublishTwiceWithRealMemberAcls() throws Exception {
        final int mappedUid = (int) Files.getAttribute(root, "unix:uid");
        host.write(
                "/etc/porelo-host/organization/applied.json",
                applied.replace("\"bob\",\"locked\":false", "\"bob\",\"locked\":true"));
        final var previous = host.program;
        host.program =
                argv -> {
                    if (argv.getFirst().equals("/usr/bin/getent")
                            && argv.getLast().equals("alice")) {
                        return new HostPrograms.Result(
                                0, "alice:x:" + mappedUid + ":1000::/home/test:/bin/false\n");
                    }
                    if (argv.getFirst().equals("/usr/bin/setfacl")) {
                        try {
                            return new HostPrograms(Duration.ofSeconds(10), 65536, Path::of)
                                    .run(argv, new byte[0], ProjectRuntime.SAFE_ENV);
                        } catch (java.io.IOException ex) {
                            throw new AssertionError(ex);
                        }
                    }
                    return previous.apply(argv);
                };
        assertThat(host.run("measure-central", "alpha").code()).isZero();
        assertThat((int) Files.getAttribute(answer(), "unix:mode") & 0777).isEqualTo(0640);
        assertThat(host.run("measure-central", "alpha").code()).isZero();
        final var acl =
                new HostPrograms(Duration.ofSeconds(10), 65536, Path::of)
                        .run(
                                List.of("/usr/bin/getfacl", "-cpn", answer().toString()),
                                new byte[0],
                                ProjectRuntime.SAFE_ENV);
        assertThat(acl.output()).contains("user:" + mappedUid + ":r--", "mask::r--", "other::---");
    }

    @Test
    void aBatchSharesItsDeadlineAcrossProjects() throws Exception {
        host.write(
                "/etc/porelo-host/organization/applied.json",
                "{\"revoked\":[],\"persons\":[{\"login\":\"alice\",\"revoked\":false,\"expires\":null},{\"login\":\"bob\",\"revoked\":false,\"expires\":null}],\"projects\":[{\"project\":\"alpha\",\"members\":[]},{\"project\":\"beta\",\"members\":[]}]}");
        final var now = new java.util.concurrent.atomic.AtomicReference<>(clock.instant());
        final Clock moving =
                new Clock() {
                    @Override
                    public ZoneId getZone() {
                        return ZoneOffset.UTC;
                    }

                    @Override
                    public Clock withZone(ZoneId zone) {
                        return this;
                    }

                    @Override
                    public Instant instant() {
                        return now.get();
                    }
                };
        final var previous = host.program;
        host.program =
                argv -> {
                    if (argv.contains("ls-remote")) {
                        now.set(now.get().plusSeconds(121));
                    }
                    return previous.apply(argv);
                };
        new ProjectMeasurements(host.files, host.runtime, moving)
                .measure("forge", List.of("alpha", "beta"));
        assertThat(Files.readString(answer())).endsWith("\"repositories\": []}\n");
        assertThat(Files.readString(answer().resolveSibling("beta.json")))
                .endsWith("\"repositories\": []}\n");
        assertThat(host.commands)
                .noneMatch(argv -> argv.stream().anyMatch(arg -> arg.contains("/beta")));
    }

    @Test
    void localPublicationUsesThePersonsCommandDeadline() throws Exception {
        measure();
        for (int index = 0; index < host.commands.size(); index++) {
            final var argv = host.commands.get(index);
            if (argv.getFirst().equals("/usr/bin/setfacl")) {
                assertThat(host.timeouts.get(index)).isEqualTo(Duration.ofSeconds(60));
            }
            if (argv.getFirst().equals("/usr/bin/getent")
                    && List.of("alice", "bob").contains(argv.getLast())) {
                assertThat(host.timeouts.get(index)).isEqualTo(Duration.ofSeconds(60));
            }
        }
    }
}
