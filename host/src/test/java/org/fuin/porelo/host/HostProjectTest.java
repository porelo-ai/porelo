package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;
import static org.fuin.porelo.host.ProjectFixture.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.*;
import java.util.*;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class HostProjectTest {
    @TempDir Path root;
    ProjectFixture host;

    @BeforeEach
    void start() throws Exception {
        host = new ProjectFixture(root);
    }

    @AfterEach
    void stop() throws Exception {
        host.close();
    }

    private void refused(Answer answer, int code, String message) {
        assertThat(answer.code())
                .as("the exit code is the one the man page's codes by verb give: %s", answer.err())
                .isEqualTo(code);
        assertThat(answer.err())
                .as("the follow recognizes a project refusal by this prefix")
                .startsWith("porelo-host: ")
                .contains(message);
        assertThat(answer.out()).as("a refusal prints no success record").isEmpty();
    }

    private void cleanRefusal(Answer answer, int code, String message) {
        refused(answer, code, message);
        host.noMutation();
        host.noCommands();
    }

    private Answer add() {
        return host.run("add", "alpha", "alice", "--signed-by", KEY);
    }

    private Answer remove() {
        return host.runInput("remove", CENTRAL_TOKEN + "\n", "alpha");
    }

    private Answer migrate(String address) {
        return host.runInput(
                "migrate", Json.write(Map.of("address", address, "token", CENTRAL_TOKEN)), "alpha");
    }

    private Path alice() {
        return host.records.person("alice");
    }

    private Path record() {
        return host.records.record("alpha");
    }

    private void registeredKeys() throws Exception {
        host.add();
        host.readKeys();
        host.clear();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"../escape", "A", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "alpha-"})
    void invalidProjectNamesChangeNothing(String project) {
        cleanRefusal(host.run("add", project, "alice", "--signed-by", KEY), 64, "project");
        assertThat(filesExist("projects"))
                .as("invalid input creates no record directory")
                .isFalse();
    }

    @Test
    void aNonrootCallerIsRefusedBeforeArgumentsOrFiles() {
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        assertThat(
                        PoreloHost.run(
                                new String[] {"project", "nonsense"},
                                Map.of(),
                                () -> 1000,
                                new PrintWriter(out),
                                new PrintWriter(err)))
                .as("the project verbs refuse nonroot with 69 before parsing")
                .isEqualTo(69);
        assertThat(err.toString()).as("frame names the root boundary").contains("root");
        host.noCommands();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "symlink", "writable", "readable", "directory", "owner"})
    void anUntrustedPersonChangesNothing(String defect) throws Exception {
        if (Set.of("missing", "symlink", "directory").contains(defect)) {
            Files.delete(alice());
        }
        switch (defect) {
            case "symlink" -> Files.createSymbolicLink(alice(), host.records.person("bob"));
            case "directory" -> Files.createDirectory(alice());
            case "writable" -> mode(alice(), 0666);
            case "readable" -> mode(alice(), 0644);
            case "owner" -> {
                final int owner = host.files.owner;
                final ProjectFiles wrongOwner =
                        new ProjectFiles(
                                root,
                                owner,
                                path -> Files.deleteIfExists(path),
                                path -> {
                                    final Map<String, Object> attributes =
                                            new HashMap<>(
                                                    Files.readAttributes(
                                                            path,
                                                            "unix:uid,mode",
                                                            LinkOption.NOFOLLOW_LINKS));
                                    if (path.equals(alice())) {
                                        attributes.put("uid", owner + 1);
                                    }
                                    return attributes;
                                });
                host.files = wrongOwner;
                cleanRefusal(add(), 69, "root");
                return;
            }
            default -> {}
        }
        cleanRefusal(
                add(),
                defect.equals("missing") ? 65 : 69,
                defect.equals("missing") ? "no person" : "root");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aLockedPersonOrOneWithoutMembershipCannotOwnAProject(boolean locked) throws Exception {
        Files.writeString(alice(), person(locked ? "member=host-admin\n" : "", locked, false));
        cleanRefusal(add(), 65, "alice");
    }

    @Test
    void aPurgeBeforeTheLockIsRechecked() {
        host.locking =
                (path, fd) -> {
                    Files.delete(alice());
                    return ProjectLock.acquire(path, fd);
                };
        cleanRefusal(add(), 65, "no person");
    }

    @Test
    void anExistingRegistrationIsNeverOverwritten() throws Exception {
        host.directories("/etc/porelo-host/projects");
        Files.writeString(record(), "original\n");
        cleanRefusal(add(), 65, "exists");
        assertThat(Files.readString(record()))
                .as("the original pin survives")
                .isEqualTo("original\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"organization", "user", "publisher", "admin"})
    void aForgeIdentityCollisionChangesNothing(String kind) {
        switch (kind) {
            case "organization" -> host.organizations.add("alpha");
            case "user" -> host.users.add("alpha");
            case "publisher" -> host.users.add("porelo-publisher-alpha");
            default -> host.users.remove("alice");
        }
        refused(add(), 65, kind);
        host.noMutation();
        assertThat(host.commands)
                .as("a name collision creates no publisher")
                .noneMatch(argv -> argv.contains("create"));
    }

    @Test
    void registrationCreatesOnlyItsOwnerAndPackagePublisher() throws Exception {
        final Answer answer = add();
        assertThat(answer.code()).as(answer.err()).isZero();
        assertThat(answer.out())
                .as("stdout is byte-compatible compact JSON")
                .isEqualTo(
                        "{\"project\":\"alpha\",\"admin\":\"alice\",\"organization\":\"alpha\",\"publisher\":\"porelo-publisher-alpha\"}\n");
        assertThat(Files.readString(record()))
                .as("the durable pin carries no credential")
                .isEqualTo(
                        "admin=alice\nsigned_by=" + KEY + "\npublisher=porelo-publisher-alpha\n");
        assertThat(host.teams)
                .as("Owners, developers, gates, agents and publishers are isolated")
                .hasSize(5);
        assertThat(host.users)
                .as("no task identity is created")
                .containsExactlyInAnyOrder("alice", "bob", "porelo-publisher-alpha");
        assertThat(host.secrets)
                .as("publisher and package-read tokens have separate secrets")
                .containsEntry("alpha/PORELO_PUBLISHER_TOKEN", PUBLISHER_TOKEN)
                .containsEntry("alpha/PORELO_PACKAGES_READ_TOKEN", READ_TOKEN);
        assertThat((int) Files.getAttribute(record(), "unix:mode") & 0777)
                .as("registration is private")
                .isEqualTo(0600);
    }

    @Test
    void unknownRemovalNeverReachesTheForge() {
        cleanRefusal(host.run("remove", "unknown"), 65, "unknown");
    }

    @ParameterizedTest
    @ValueSource(strings = {"refs/heads/task", "refs/tags/task"})
    void unpushedWorkOnAnyRefKeepsTheProject(String reference) throws Exception {
        registeredKeys();
        host.localCommits += "b".repeat(40) + "\n";
        refused(remove(), 65, "unpushed");
        host.noMutation();
        assertThat(host.commands)
                .as("the comparison asks every ref, including %s", reference)
                .anyMatch(argv -> argv.contains("rev-list") && argv.contains("--all"));
        host.centralCommits = host.localCommits;
        assertThat(remove().code()).as("only work at central permits removal").isZero();
        assertThat(host.organizations).as("the selected organization goes").doesNotContain("alpha");
        assertThat(host.users)
                .as("other people's accounts survive")
                .contains("alice", "bob")
                .doesNotContain("porelo-publisher-alpha");
    }

    @Test
    void aRepositoryWithoutItsCentralAddressIsKept() throws Exception {
        registeredKeys();
        host.repositories.get("alpha/code").put("original_url", "");
        refused(remove(), 69, "central address");
        host.noMutation();
    }

    @ParameterizedTest
    @ValueSource(ints = {503, 302})
    void aFailedOrRedirectedForgeChangesNothing(int status) {
        host.replies.put("GET /orgs/alpha", new Reply(status, Map.of("token", ADMIN_TOKEN)));
        refused(add(), status == 503 ? 69 : 65, "HTTP " + status);
        host.noMutation();
    }

    @ParameterizedTest
    @ValueSource(strings = {"symlink", "writable", "readable"})
    void untrustedRecordDirectoriesAreRefusedLocally(String defect) throws Exception {
        final Path directory = host.files.etc.resolve("people");
        if (defect.equals("symlink")) {
            Files.move(directory, host.files.etc.resolve("other"));
            Files.createSymbolicLink(directory, host.files.etc.resolve("other"));
        } else {
            mode(directory, defect.equals("writable") ? 0777 : 0755);
        }
        cleanRefusal(add(), 69, "root");
    }

    @ParameterizedTest
    @ValueSource(ints = {0664, 0646})
    void aForgeConfigurationOthersMayWriteIsRefusedBeforeTheForge(int mode) throws Exception {
        // Not private, so readable by all, but never writable by anyone but root.
        mode(host.records.conf(), mode);
        cleanRefusal(add(), 69, "root");
    }

    @ParameterizedTest
    @CsvSource({"admin,../alice", "signed_by,bad", "publisher,porelo-publisher-beta"})
    void aCorruptRegistrationDeletesNoIdentity(String field, String value) throws Exception {
        host.add();
        final var data =
                new LinkedHashMap<>(
                        Map.of(
                                "admin",
                                "alice",
                                "signed_by",
                                KEY,
                                "publisher",
                                "porelo-publisher-alpha"));
        data.put(field, value);
        Files.writeString(
                record(),
                data.entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue() + "\n")
                        .collect(java.util.stream.Collectors.joining()));
        host.clear();
        cleanRefusal(remove(), 65, field);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "\"not an organization\""})
    void anAmbiguousSuccessfulLookupDoesNotFreeAName(String json) {
        host.replies.put("GET /orgs/alpha", new Reply(200, Json.parseExact(json)));
        refused(add(), 65, "forge");
        host.noMutation();
    }

    @ParameterizedTest
    @ValueSource(strings = {"repeat", "shape", "name", "package", "missing-source"})
    void incompleteRepositoryOrPackageChecksPreventDeletion(String defect) throws Exception {
        host.add();
        host.clear();
        switch (defect) {
            case "repeat" ->
                    host.pages.put("/orgs/alpha/repos", List.of(List.of(Map.of("name", "code"))));
            case "shape" ->
                    host.replies.put(
                            "GET /orgs/alpha/repos", new Reply(200, Map.of("name", "code")));
            case "name" -> host.repositories.put("alpha/escape", Map.of("name", "../other"));
            case "package" ->
                    host.packages.put("alpha", List.of(Map.of("type", "maven", "name", "demo")));
            default -> {
                host.repositories.put("alpha/code", Map.of("name", "code"));
                host.pushFailure = 69;
            }
        }
        refused(remove(), defect.equals("missing-source") ? 69 : 65, "");
        host.noMutation();
    }

    @Test
    void laterRepositoryPagesCannotHideWork() throws Exception {
        registeredKeys();
        host.pages.put(
                "/orgs/alpha/repos",
                List.of(
                        List.of(Map.of("name", "code", "original_url", ADDRESS)),
                        List.of(Map.of("name", "unknown-source")),
                        List.of()));
        refused(remove(), 69, "unknown-source");
        host.noMutation();
        assertThat(host.calls)
                .as("page two is read before deleting anything")
                .anyMatch(call -> call.path().endsWith("page=2"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "POST /orgs/alpha/teams",
                "PUT /orgs/alpha/actions/secrets/PORELO_PUBLISHER_TOKEN",
                "PUT /orgs/alpha/actions/secrets/PORELO_PACKAGES_READ_TOKEN"
            })
    void failedRegistrationRemovesItsPartialIdentities(String failure) {
        host.replies.put(failure, new Reply(503, Map.of()));
        refused(add(), 69, "HTTP 503");
        assertThat(host.organizations).as("an unrecorded organization is rolled back").isEmpty();
        assertThat(host.users)
                .as("a generated publisher is rolled back")
                .containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void aConcurrentRegistrationCannotReplaceThePin() throws Exception {
        host.beforeCall =
                call -> {
                    if (call.path().endsWith("PORELO_PACKAGES_READ_TOKEN")) {
                        try {
                            Files.writeString(record(), "other writer\n");
                        } catch (IOException ex) {
                            throw new AssertionError(ex);
                        }
                    }
                };
        refused(add(), 65, "exists");
        assertThat(Files.readString(record()))
                .as("no-replace link keeps the other writer's pin")
                .isEqualTo("other writer\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "admin-token", "write:package", "read:package"})
    void failedAccountAndTokenCommandsNeverPublishCredentials(String failure) {
        host.program =
                argv ->
                        argv.contains(failure)
                                        || failure.equals("admin-token")
                                                && argv.contains(ProjectRuntime.ADMIN_SCOPES)
                                ? new HostPrograms.Result(
                                        1, ADMIN_TOKEN + "discarded-random-password")
                                : null;
        refused(add(), 69, "Forgejo made no");
        assertThat(Files.exists(record())).as("failure publishes no registration").isFalse();
    }

    @Test
    void registrationSendsThePoolKeyOnlyWhenPresent() throws Exception {
        host.directories("/var/lib/porelo-host/pool");
        host.write("/var/lib/porelo-host/pool/run-key", "fixture run key\n");
        host.add();
        assertThat(host.secrets)
                .as("only the run key's Actions secret carries it")
                .containsEntry("alpha/PORELO_POOL_SSH", "fixture run key\n");
    }

    @Test
    void aReadableRunKeyRollsBackRegistration() throws Exception {
        host.directories("/var/lib/porelo-host/pool");
        host.write("/var/lib/porelo-host/pool/run-key", "fixture run key\n");
        mode(host.files.at("/var/lib/porelo-host/pool/run-key"), 0644);
        refused(add(), 69, "root");
        assertThat(host.organizations).as("no organization survives a refused key").isEmpty();
    }

    @Test
    void aFailedRunKeySecretRollsBackBothIdentities() throws Exception {
        host.directories("/var/lib/porelo-host/pool");
        host.write("/var/lib/porelo-host/pool/run-key", "fixture run key\n");
        host.replies.put(
                "PUT /orgs/alpha/actions/secrets/PORELO_POOL_SSH", new Reply(503, Map.of()));
        refused(add(), 69, "HTTP 503");
        assertThat(host.organizations).as("organization is removed").isEmpty();
        assertThat(host.users).as("publisher is removed").doesNotContain("porelo-publisher-alpha");
    }

    @Test
    void cleanupAttemptsBothIdentitiesAndNamesEveryLeftover() {
        host.replies.put(
                "PUT /orgs/alpha/actions/secrets/PORELO_PACKAGES_READ_TOKEN",
                new Reply(503, Map.of()));
        host.replies.put("DELETE /orgs/alpha", new Reply(503, Map.of()));
        host.replies.put("DELETE /admin/users/porelo-publisher-alpha", new Reply(503, Map.of()));
        refused(add(), 69, "cleanup left organization alpha, publisher porelo-publisher-alpha");
        assertThat(host.calls)
                .as("failure of the first rollback cannot skip the second")
                .anyMatch(
                        call ->
                                call.method().equals("DELETE")
                                        && call.path().endsWith("porelo-publisher-alpha"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0777", "0000"})
    void recordDirectoriesHaveExplicitPrivateModes(String mask) throws Exception {
        final List<String> argv =
                List.of(
                        "/bin/sh",
                        "-c",
                        "umask " + mask + "; exec \"$@\" 2>&1",
                        "umask-test",
                        Path.of(System.getProperty("java.home"), "bin/java").toString(),
                        "-XX:-UsePerfData",
                        "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                        "--enable-native-access=ALL-UNNAMED",
                        "-cp",
                        System.getProperty(
                                "surefire.test.class.path", System.getProperty("java.class.path")),
                        ProjectUmaskChild.class.getName(),
                        root.resolve("child").toString(),
                        mask);
        final var result =
                new HostPrograms(java.time.Duration.ofSeconds(30), 65536, Path::of)
                        .run(argv, new byte[0], ProjectRuntime.SAFE_ENV);
        assertThat(result.exitCode()).as(result.output()).isZero();
    }

    @Test
    void aCanonicalSignerIsStoredExactly() throws Exception {
        host.add();
        assertThat(Files.readString(record()))
                .as("the pin is never normalized or commented")
                .contains("signed_by=" + KEY + "\n");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ssh-rsa AAAA",
                "ssh-ed25519 AAAA",
                "ssh-ed25519 A===",
                "",
                "ssh-ed25519 bad comment"
            })
    void invalidSigningKeysNeverReachTheForge(String key) {
        cleanRefusal(host.run("add", "alpha", "alice", "--signed-by", key), 64, "signing key");
    }

    // project remove's codes by verb: unpushed work and write failures pass through, an
    // interrupted push check is 64, and any other failure is a dependency's.
    @ParameterizedTest
    @CsvSource({"65,65", "73,73", "75,64", "130,64", "143,64", "1,69", "69,69"})
    void gatheredPushFailuresKeepTheirContractCode(int exit, int expected) throws Exception {
        host.add();
        host.repositories.put("alpha/code", Map.of("name", "code"));
        host.pushFailure = exit;
        host.clear();
        refused(remove(), expected, "gathered push check exited " + exit);
        host.noMutation();
    }

    @Test
    void missingTokenInputIsAnInputRefusal() {
        host.add();
        host.repositories.put("alpha/code", Map.of("name", "code"));
        host.clear();
        refused(host.run("remove", "alpha"), 64, "no valid central forge token");
        host.noMutation();
    }

    @ParameterizedTest
    @ValueSource(strings = {"publish", "stdout"})
    void writeFailuresHaveTheirOwnExitCodeWithoutData(String operation) throws Exception {
        if (operation.equals("publish")) {
            host.beforeCall =
                    call -> {
                        if (call.path().endsWith("/PORELO_PACKAGES_READ_TOKEN")
                                || call.method().equals("DELETE")
                                        && call.path().equals("/api/v1/orgs/alpha")) {
                            try {
                                mode(
                                        record().getParent(),
                                        call.method().equals("DELETE") ? 0700 : 0500);
                            } catch (IOException ex) {
                                throw new java.io.UncheckedIOException(ex);
                            }
                        }
                    };
            try {
                refused(add(), 73, "cannot write");
            } finally {
                mode(record().getParent(), 0700);
            }
            assertThat(host.organizations)
                    .as("publication failure rolls back the organization")
                    .isEmpty();
            assertThat(host.users).doesNotContain("porelo-publisher-alpha");
        } else {
            final var broken =
                    new java.io.Writer() {
                        public void write(char[] chars, int offset, int length) throws IOException {
                            throw new IOException(CENTRAL_TOKEN);
                        }

                        public void flush() {}

                        public void close() {}
                    };
            final var err = new StringWriter();
            assertThat(
                            host.project()
                                    .run(
                                            "add",
                                            List.of("alpha", "alice", "--signed-by", KEY),
                                            java.io.InputStream.nullInputStream(),
                                            new PrintWriter(broken),
                                            new PrintWriter(err)))
                    .as("stdout failure keeps EX_CANTCREAT")
                    .isEqualTo(73);
            assertThat(err.toString()).contains("cannot write").doesNotContain(CENTRAL_TOKEN);
        }
    }

    @Test
    void anUnreadableHttpReplyIsADependencyRefusal() throws Exception {
        host.unavailable = true;
        refused(add(), 69, "did not answer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "one\ntwo\n", "one\r\n", "one\n\n"})
    void aPipedTokenMustBeOneLine(String input) {
        host.add();
        host.repositories.put("alpha/code", Map.of("name", "code"));
        host.clear();
        refused(host.runInput("remove", input, "alpha"), 64, "no valid central forge token");
    }

    @Test
    void aOneLinePipedTokenReachesTheGatheredPushAndRemovalSucceeds() {
        host.add();
        host.repositories.put("alpha/code", Map.of("name", "code"));
        final Answer answer = host.runInput("remove", CENTRAL_TOKEN + "\n", "alpha");
        assertThat(answer.code()).as(answer.err()).isZero();
        assertThat(host.pushInput).isEqualTo(CENTRAL_TOKEN + "\n");
        assertThat(Files.exists(record())).isFalse();
    }

    @Test
    void migrationRequiresTheReadDirectoryFromUp() throws Exception {
        host.add();
        final Path read = host.files.etc.resolve("organization/read");
        Files.delete(read);
        host.clear();
        refused(migrate(ADDRESS), 69, read + " is missing; run up first");
        assertThat(Files.exists(read)).isFalse();
        host.noMutation();
        assertThat(host.centralCalls).isEmpty();
    }

    @Test
    void invalidUtf8InThePoolRunKeyRollsBackRegistration() throws Exception {
        host.directories("/var/lib/porelo-host/pool");
        final Path key = host.files.at("/var/lib/porelo-host/pool/run-key");
        Files.write(key, new byte[] {(byte) 0xc3, 0x28});
        mode(key, 0600);
        refused(add(), 69, "UTF-8");
        assertThat(host.secrets).doesNotContainKey("alpha/PORELO_POOL_SSH");
        assertThat(host.organizations).isEmpty();
        assertThat(host.users).doesNotContain("porelo-publisher-alpha");
        assertThat(Files.exists(record())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://forge.example.org:0/acme/code.git",
                "ssh://git@forge.example.org:0/acme/code.git"
            })
    void migrationRefusesPortZeroBeforeCreatingReadRecords(String address) {
        host.add();
        host.clear();
        refused(migrate(address), 65, "credential-free forge repository address");
        assertThat(Files.exists(host.manifest())).isFalse();
        assertThat(host.centralCalls).isEmpty();
        host.noMutation();
    }

    @Test
    void interruptedAddRollsBackBothIdentitiesAndRestoresTheInterrupt() {
        host.rejectInterruptedRequests = true;
        host.beforeCall =
                call -> {
                    if (call.path().endsWith("/PORELO_PACKAGES_READ_TOKEN")) {
                        Thread.currentThread().interrupt();
                        throw new HostRefusal(69, "fixture interrupted request");
                    }
                };
        try {
            refused(add(), 69, "fixture interrupted request");
            assertThat(host.organizations)
                    .as("interrupted rollback deletes the organization")
                    .isEmpty();
            assertThat(host.users).doesNotContain("porelo-publisher-alpha");
            assertThat(Thread.currentThread().isInterrupted())
                    .as("the caller retains its interrupt")
                    .isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aProductionReadOverTheLimitHasTheSameRefusalAsAnIsolatedRead() throws Exception {
        final Path path = root.resolve("oversized");
        Files.writeString(path, "too much data");
        // Model root's attributes without privileges; / selects the production trust anchor.
        final ProjectFiles production =
                new ProjectFiles(
                        Path.of("/"),
                        host.files.owner,
                        item -> Files.deleteIfExists(item),
                        item ->
                                Map.of(
                                        "uid",
                                        host.files.owner,
                                        "mode",
                                        item.equals(path) ? 0100600 : 0040700));
        assertThatThrownBy(() -> production.read(path, false, 1))
                .isInstanceOfSatisfying(
                        HostRefusal.class, error -> assertThat(error.exitCode()).isEqualTo(69))
                .hasMessage(path + " exceeds read limit");
    }

    @Test
    void aFailedReadKeyDeletionIsADependencyRefusal() throws Exception {
        registeredKeys();
        host.files =
                new ProjectFiles(
                        root,
                        host.files.owner,
                        path -> {
                            if (path.equals(host.keyPath())) {
                                throw new IOException("fixture deletion failure");
                            }
                            Files.deleteIfExists(path);
                        });
        refused(remove(), 69, "dependency or file read failed");
        assertThat(Files.exists(host.manifest()))
                .as("failed cleanup retains recovery metadata")
                .isTrue();
    }

    @Test
    void anyUnlockedMemberMayRegisterWithCanonicalMembershipOrder() throws Exception {
        Files.writeString(alice(), person("member=beta developer\n", false, false));
        host.add();
        assertThat(Files.readString(alice()))
                .as("registration adds its admin role without widening other roles")
                .startsWith("member=alpha project-admin\nmember=beta developer\n");
    }

    @Test
    void theOrganizationHasTheExactTeamAccessAndWorkflowSettings() {
        host.add();
        final var developers =
                host.teams.values().stream()
                        .filter(team -> team.get("name").equals("developers"))
                        .findFirst()
                        .orElseThrow();
        assertThat((Map<?, ?>) developers.get("units_map"))
                .as("the declared team units determine access")
                .isEqualTo(
                        Map.of(
                                "repo.code",
                                "write",
                                "repo.pulls",
                                "write",
                                "repo.issues",
                                "write",
                                "repo.actions",
                                "read",
                                "repo.packages",
                                "read"));
        assertThat(developers)
                .as("members cannot create repositories")
                .containsEntry("can_create_org_repo", false)
                .containsEntry("includes_all_repositories", true);
        assertThat(host.variables)
                .as("project variables are substituted")
                .containsEntry("alpha/PROJECT", "alpha");
    }

    @Test
    void removingAProjectPreservesALockedPersonsCard() throws Exception {
        host.add();
        Files.writeString(
                alice(),
                person("member=alpha project-admin\n", true, false)
                        + "reason=key\nlocked_on=2026-11-09\n");
        assertThat(remove().code()).as("locked memberships do not block safe removal").isZero();
        assertThat(Files.readString(alice()))
                .as("locking is the follow's decision")
                .isEqualTo(person("", true, false) + "reason=key\nlocked_on=2026-11-09\n");
    }

    @Test
    void removalDropsOnlyThisProjectsMemberships() throws Exception {
        host.add();
        Files.writeString(
                alice(),
                person(
                        "member=host-admin\nmember=alpha project-admin\nmember=beta developer\n",
                        false,
                        false));
        assertThat(remove().code()).as("safe project removal succeeds").isZero();
        assertThat(Files.readString(alice()))
                .as("unrelated memberships and lock state survive")
                .isEqualTo(person("member=host-admin\nmember=beta developer\n", false, false));
    }

    @Test
    void corruptPeopleRefuseRemovalBeforeDeletingAnything() throws Exception {
        host.add();
        Files.writeString(host.records.person("bob"), "corrupt\n");
        host.clear();
        cleanRefusal(remove(), 65, "malformed");
    }

    @Test
    void localAdministrationUsesOnlyThePrivateUnixSocket() throws Exception {
        host.realSocket = true;
        refused(add(), 69, "did not answer");
        assertThat(Files.exists(record())).isFalse();
        assertThat(host.organizations).isEmpty();
        assertThat(host.calls).as("no request-level stand-in was used").isEmpty();
        assertThat(host.centralCalls).isEmpty();
    }

    @Test
    void localAdministrationUsesTheInjectedUnixSocketTransport() {
        host.add();
        assertThat(host.calls)
                .as("local administration is given exactly the private Unix socket path")
                .isNotEmpty();
        assertThat(host.centralCalls).as("no local administrator token reaches TCP").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"projects\":[],\"revoked\":[]}", "malformed"})
    void anUnnamedOrMalformedOrganizationCannotRegisterAProject(String applied) throws Exception {
        Files.writeString(host.files.etc.resolve("organization/applied.json"), applied);
        cleanRefusal(add(), 65, "organization");
    }

    @Test
    void aRevokedAdminRegistersNoProject() throws Exception {
        final Path applied = host.files.etc.resolve("organization/applied.json");
        Files.writeString(
                applied,
                Files.readString(applied).replace("\"revoked\":[]", "\"revoked\":[\"alice\"]"));
        cleanRefusal(add(), 65, "revoked");
    }

    @Test
    void removalDeletesOnlyItsMembershipAndSignerPublications() throws Exception {
        host.add();
        host.directories("/etc/porelo-host/signers");
        for (String path :
                List.of(
                        "projects/alpha.people.json",
                        "signers/alpha.json",
                        "signers/alpha.allowed",
                        "signers/beta.allowed")) {
            Files.writeString(host.files.etc.resolve(path), "published\n");
            mode(host.files.etc.resolve(path), 0644);
        }
        assertThat(remove().code()).as("project removal succeeds").isZero();
        assertThat(Files.exists(host.files.etc.resolve("signers/alpha.allowed")))
                .as("own signer publication goes")
                .isFalse();
        assertThat(Files.exists(host.files.etc.resolve("signers/beta.allowed")))
                .as("other project's publication stays")
                .isTrue();
    }

    @Test
    void untrustedArrivalsPreventEveryDeletion() throws Exception {
        host.add();
        host.directories("/var/lib/porelo-host/arrivals/alpha");
        host.write("/var/lib/porelo-host/arrivals/alpha/code.jsonl", "verdict\n");
        mode(host.files.at("/var/lib/porelo-host/arrivals/alpha/code.jsonl"), 0666);
        host.clear();
        refused(remove(), 69, "root");
        host.noMutation();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void removalPreservesThePendingLockState(boolean locked) throws Exception {
        host.add();
        Files.writeString(alice(), person("member=alpha project-admin\n", locked, true));
        assertThat(remove().code())
                .as("safe removal succeeds while locking remains pending")
                .isZero();
        assertThat(Files.readString(alice()))
                .as("the pending marker stays last")
                .isEqualTo(person("", locked, true));
    }

    @Test
    void aPendingAdminCannotRegister() throws Exception {
        Files.writeString(alice(), person("member=host-admin\n", false, true));
        cleanRefusal(add(), 65, "locked");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/", "relative", "/home/../forge"})
    void anUnusableForgeHomeIsRefused(String home) {
        host.home = home;
        refused(add(), 69, "usable absolute home");
    }

    @Test
    void registrationRequiresTheFetchedProjectAdminRole() throws Exception {
        final Path applied = host.files.etc.resolve("organization/applied.json");
        Files.writeString(
                applied,
                Files.readString(applied).replace("\"role\":\"admin\"", "\"role\":\"developer\""));
        cleanRefusal(add(), 65, "names no project");
    }

    @Test
    void theFollowCanLendItsExistingFlock() throws Exception {
        try (ProjectLock lock = ProjectLock.acquire(host.records.conf(), null)) {
            final int descriptor = descriptor(host.records.conf());
            assertThat(
                            host.run(
                                            "add",
                                            "alpha",
                                            "alice",
                                            "--signed-by",
                                            KEY,
                                            "--lock-fd",
                                            Integer.toString(descriptor))
                                    .code())
                    .as("the same open description does not deadlock")
                    .isZero();
        }
    }

    @Test
    void anUnrelatedDescriptorCannotBypassTheProjectLock() throws Exception {
        try (ProjectLock lock = ProjectLock.acquire(host.records.person("bob"), null)) {
            cleanRefusal(
                    host.run(
                            "add",
                            "alpha",
                            "alice",
                            "--signed-by",
                            KEY,
                            "--lock-fd",
                            Integer.toString(descriptor(host.records.person("bob")))),
                    65,
                    "not forge.conf");
        }
    }

    @Test
    void everyLocalCommitMustBeReachableAtCentral() throws Exception {
        registeredKeys();
        host.localCommits += "c".repeat(40) + "\n";
        refused(remove(), 65, "unpushed");
        host.noMutation();
    }

    @Test
    void anUnreachableCentralCannotProveWorkWasPushed() throws Exception {
        registeredKeys();
        host.gitFailure = 1;
        refused(remove(), 69, "read-only Git");
        host.noMutation();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void migrationUsesOnlyStdinAndMemoryForItsToken(boolean missingPublic) throws Exception {
        host.add();
        if (missingPublic) {
            host.readKeys();
            Files.delete(host.keyPath());
            Files.delete(Path.of(host.keyPath() + ".pub"));
            final var generated =
                    new HostPrograms(java.time.Duration.ofSeconds(30), 65536, Path::of)
                            .run(
                                    List.of(
                                            "/usr/bin/ssh-keygen",
                                            "-q",
                                            "-t",
                                            "ed25519",
                                            "-N",
                                            "",
                                            "-C",
                                            "",
                                            "-f",
                                            host.keyPath().toString()),
                                    new byte[0],
                                    ProjectRuntime.SAFE_ENV);
            assertThat(generated.exitCode())
                    .as("the recovery case uses a real private key")
                    .isZero();
            host.realKeygen = true;
            host.centralKeys.clear();
            Files.delete(Path.of(host.keyPath() + ".pub"));
            Files.delete(host.manifest());
            host.repositories.clear();
        }
        final byte[] privateBefore =
                missingPublic ? Files.readAllBytes(host.keyPath()) : new byte[0];
        final Answer answer = migrate(ADDRESS);
        assertThat(answer.code()).as(answer.err()).isZero();
        assertThat(answer.out()).as("migration prints no secret or success token").isEmpty();
        assertThat(Files.readString(host.manifest()))
                .as("manifest contains only recovery metadata")
                .doesNotContain(CENTRAL_TOKEN);
        if (missingPublic) {
            try {
                assertThat(Files.readAllBytes(host.keyPath()))
                        .as("recovery preserves the private key")
                        .isEqualTo(privateBefore);
                assertThat(host.centralKeys.getFirst().get("key"))
                        .isEqualTo(Files.readString(Path.of(host.keyPath() + ".pub")).strip());
            } finally {
                Arrays.fill(privateBefore, (byte) 0);
            }
        }
        assertThat(host.commands.stream().anyMatch(argv -> argv.contains("-y")))
                .as("a missing public half is recovered with ssh-keygen -y")
                .isEqualTo(missingPublic);
        assertThat(host.mergeStyle).as("main is fast-forward-only").containsKey("alpha/code");
        assertThat(host.protectedBranches)
                .as("main protection was installed")
                .containsKey("alpha/code");
    }

    @Test
    void migrationReplacesOnlyThisHostsStaleReadKey() throws Exception {
        registeredKeys();
        host.centralKeys.getFirst().put("key", "ssh-ed25519 a-different-earlier-public-key");
        assertThat(remove().code()).as("removal reports the retained central key").isZero();
        host.add();
        assertThat(migrate(ADDRESS).code()).as("the project can be migrated again").isZero();
        assertThat(host.centralCalls)
                .as("the old key is deleted before replacement")
                .contains("DELETE /api/v1/repos/acme/code/keys/1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"another host", "a person"})
    void migrationLeavesUnrelatedDeployKeys(String title) {
        host.add();
        host.centralKeys.add(
                new LinkedHashMap<>(
                        Map.of("id", 7L, "title", title, "key", "unrelated", "read_only", true)));
        assertThat(migrate(ADDRESS).code()).as("unrelated keys do not prevent migration").isZero();
        assertThat(host.centralKeys)
                .as("another host or person's key is retained")
                .anyMatch(key -> title.equals(key.get("title")));
    }

    @Test
    void aWritableKeyUnderOurTitleIsKeptAndRefused() {
        host.add();
        host.centralKeys.add(
                new LinkedHashMap<>(
                        Map.of(
                                "id",
                                7L,
                                "title",
                                "porelo host build-host project alpha",
                                "key",
                                "unrelated",
                                "read_only",
                                false)));
        refused(migrate(ADDRESS), 65, "write access");
        assertThat(host.centralCalls)
                .as("no delete of a writable key")
                .noneMatch(call -> call.startsWith("DELETE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"repositories.json", "Repositories.json.git", "repositories.json.git"})
    void aRepositoryCannotOverwriteTheManifest(String name) {
        host.add();
        refused(migrate("https://forge.example.org/acme/" + name), 65, "no forge repository");
        assertThat(Files.exists(host.manifest()))
                .as("the manifest is not used as a private key")
                .isFalse();
    }

    @Test
    void registrationPreservesTheFollowAdmittedMembership() throws Exception {
        Files.writeString(
                alice(),
                person("member=alpha project-admin\nmember=beta developer\n", false, false));
        host.add();
        assertThat(Files.readString(alice()))
                .as("admission is not duplicated or narrowed")
                .isEqualTo(
                        person(
                                "member=alpha project-admin\nmember=beta developer\n",
                                false,
                                false));
    }

    @Test
    void localGitUsesTheForgeAccountsUserManager() throws Exception {
        registeredKeys();
        host.runtime.read("forge", "alpha", "Code", "rev-list", "--all");
        assertThat(host.commands)
                .as("root never runs git on the forge's own folder")
                .anyMatch(
                        argv ->
                                argv.contains("/usr/sbin/runuser")
                                        && argv.contains("HOME=/home/forge")
                                        && argv.contains(
                                                "DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1234/bus")
                                        && argv.contains(
                                                "--git-dir=/var/lib/gitea/git/repositories/alpha/code.git"));
    }

    @Test
    void migrationNeverSendsTheTokenToAnotherForge() {
        host.add();
        host.clear();
        refused(migrate("https://elsewhere.example.org/acme/code.git"), 65, "differs");
        assertThat(host.centralCalls).as("origin is checked before central HTTP").isEmpty();
        assertThat(Files.exists(host.manifest().getParent()))
                .as("refusal creates no recovery directory")
                .isFalse();
    }

    @Test
    void aLinkedReadKeyCannotBeUsedToCompareCommits() throws Exception {
        registeredKeys();
        Files.delete(host.keyPath());
        Files.createSymbolicLink(host.keyPath(), alice());
        refused(remove(), 69, "plain file");
        host.noMutation();
    }

    @Test
    void removalResumesAfterOnlyTheOrganizationWasDeleted() throws Exception {
        host.add();
        host.replies.put("DELETE /admin/users/porelo-publisher-alpha", new Reply(503, Map.of()));
        refused(remove(), 69, "HTTP 503");
        assertThat(Files.readString(record()))
                .as("the durable removal marker permits retry")
                .endsWith("removing=true\n");
        host.replies.clear();
        assertThat(remove().code())
                .as("retry deletes the remaining publisher and registration")
                .isZero();
        assertThat(Files.exists(record())).as("completed retry removes the record").isFalse();
    }

    @Test
    void cleanupKeepsTheManifestUntilEveryPrivateFileIsGone() throws Exception {
        registeredKeys();
        final ProjectFiles interrupted =
                new ProjectFiles(
                        root,
                        host.files.owner,
                        path -> {
                            if (path.equals(Path.of(host.keyPath() + ".pub"))) {
                                throw new IOException("interrupted cleanup");
                            }
                            Files.deleteIfExists(path);
                        });
        assertThatThrownBy(() -> new ProjectReadKeys(interrupted, host.runtime).drop("alpha"))
                .as("the second key deletion interrupts cleanup before the manifest goes")
                .isInstanceOf(IOException.class);
        assertThat(Files.exists(host.manifest()))
                .as("the retry still knows every remaining private file")
                .isTrue();
        assertThat(Files.exists(host.keyPath()))
                .as("the private half already deleted is not required on retry")
                .isFalse();
        new ProjectReadKeys(host.files, host.runtime).drop("alpha");
        assertThat(Files.exists(host.manifest().getParent()))
                .as("cleanup can resume with a missing private half")
                .isFalse();
    }

    @Test
    void aBrokenPrivateKeyRefusesRecoveryWithoutDisclosingBytes() throws Exception {
        registeredKeys();
        Files.delete(Path.of(host.keyPath() + ".pub"));
        host.gitFailure = 1;
        refused(migrate(ADDRESS), 69, "public read key could not be recovered");
        assertThat(Files.readString(host.keyPath()))
                .as("the failed recovery keeps the private half")
                .isEqualTo("fixture private material");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {".repositories-interrupted", "notes", ".repositories", ".other-staging"})
    void unknownReadDirectoryResiduePreventsPartialDeletion(String residue) throws Exception {
        registeredKeys();
        Files.writeString(host.keyPath().getParent().resolve(residue), "residue\n");
        mode(host.keyPath().getParent().resolve(residue), 0600);
        final Answer answer = remove();
        if (residue.startsWith(".repositories-")) {
            assertThat(answer.code()).as(answer.err()).isZero();
        } else {
            refused(answer, 73, "unknown file");
            host.noMutation();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "same,up-to-date",
        "ahead,ahead",
        "diverged,moved-on",
        "no-local,cannot-say",
        "no-central,cannot-say",
        "unreachable,cannot-say"
    })
    void centralStateTellsHowMainStands(String shape, String expected) throws Exception {
        registeredKeys();
        switch (shape) {
            case "ahead" -> host.localMain = "b".repeat(40);
            case "diverged" -> {
                host.localMain = "b".repeat(40);
                host.ancestor = 1;
            }
            case "no-local" -> host.localMain = null;
            case "no-central" -> host.centralMain = null;
            case "unreachable" -> host.gitFailure = 1;
            default -> {}
        }
        final Answer answer = host.run("central-state", "alpha");
        assertThat(answer.code()).as(answer.err()).isZero();
        assertThat(answer.out())
                .as("compact central-state format is stable")
                .contains("\"state\":\"" + expected + "\"")
                .startsWith("{\"project\":\"alpha\",\"repositories\":[{\"repository\":\"code\",");
    }

    @Test
    void aRepositoryWithoutAReadKeyCannotSay() throws Exception {
        registeredKeys();
        host.repositories.put("alpha/unrecorded", Map.of("name", "unrecorded"));
        assertThat(host.run("central-state", "alpha").out())
                .as("lost credentials never mean up-to-date")
                .contains(
                        "{\"repository\":\"unrecorded\",\"state\":\"cannot-say\",\"why\":\"no-read-key\"}");
    }

    @Test
    void tellingCentralStateMakesNoAdminTokenOrApiCall() throws Exception {
        registeredKeys();
        host.run("central-state", "alpha");
        assertThat(host.calls).as("the read path needs no admin API").isEmpty();
        assertThat(host.commands)
                .as("the read path mints no credential")
                .noneMatch(argv -> argv.contains("generate-access-token"));
    }

    @Test
    void centralMainUsesTheReadKeyAndAThirtySecondDeadline() throws Exception {
        registeredKeys();
        host.run("central-state", "alpha");
        final int index =
                java.util.stream.IntStream.range(0, host.commands.size())
                        .filter(i -> host.commands.get(i).contains("ls-remote"))
                        .findFirst()
                        .orElseThrow();
        assertThat(host.timeouts.get(index))
                .as("following central cannot hang indefinitely")
                .isEqualTo(java.time.Duration.ofSeconds(30));
        assertThat(host.environments.get(index).get("GIT_SSH_COMMAND"))
                .as("central is reached only by its pinned read identity")
                .contains(
                        "IdentityAgent=none",
                        "StrictHostKeyChecking=yes",
                        host.keyPath().toString());
    }

    @Test
    void takingOffOneRepositoryDeletesItsKeysInOrder() throws Exception {
        registeredKeys();
        final Answer answer = host.off(CENTRAL_TOKEN);
        assertThat(answer.code()).as(answer.err()).isZero();
        assertThat(host.centralCalls)
                .as("central deletion is confirmed before local deletion")
                .containsExactly(
                        "DELETE /api/v1/repos/acme/code/keys/1",
                        "GET /api/v1/repos/acme/code/keys/1");
        assertThat(Files.readString(host.manifest()))
                .as("the durable manifest loses only this repository")
                .isEqualTo("[]\n");
        assertThat(host.organizations)
                .as("taking off a repository leaves the project")
                .contains("alpha");
    }

    @Test
    void takingOffWithoutATokenNamesTheDeployKeyForItsOwner() throws Exception {
        registeredKeys();
        final Answer answer = host.off(null);
        assertThat(answer.code()).as(answer.err()).isZero();
        assertThat(answer.out())
                .as("the owner receives the exact key title and address")
                .contains(
                        "\"left_at_central\":[{\"address\":\""
                                + ADDRESS
                                + "\",\"title\":\"porelo host build-host project alpha\"}]");
        assertThat(host.centralCalls).as("no token means no central API call").isEmpty();
    }

    @Test
    void takingOffKeepsARepositoryWithUnpushedWork() throws Exception {
        registeredKeys();
        host.localCommits += "b".repeat(40) + "\n";
        refused(host.off(CENTRAL_TOKEN), 65, "unpushed");
        host.noMutation();
    }

    @Test
    void anUnconfirmedDeployKeyDeletionKeepsTheLocalReadKey() throws Exception {
        registeredKeys();
        host.holdDeployKey = true;
        refused(host.off(CENTRAL_TOKEN), 69, "still holds the read key");
        assertThat(Files.exists(host.keyPath()))
                .as("read identity survives an unconfirmed remote deletion")
                .isTrue();
        host.noMutation();
    }

    @Test
    void takingOffSendsNoTokenToAnotherForge() throws Exception {
        registeredKeys();
        Files.writeString(
                host.files.etc.resolve("organization/organization.yml"),
                "settings:\n  repository: https://elsewhere.example.org/acme/org.git\n");
        refused(host.off(CENTRAL_TOKEN), 65, "differs");
        assertThat(host.centralCalls)
                .as("organization origin is checked before sending credentials")
                .isEmpty();
    }

    @Test
    void aRenamedRepositoryIsNotTakenOffThroughItsOldName() throws Exception {
        registeredKeys();
        host.repositories.get("alpha/code").put("name", "renamed");
        refused(host.off(null), 69, "another repository");
        host.noMutation();
    }

    // A failed private cleanup must not retain readable provenance for a removed repository.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publicArrivalsAreRevokedBeforePrivateCleanup(boolean wholeProject) throws Exception {
        registeredKeys();
        final Path ledger = host.files.at("/var/lib/porelo-host/arrivals/alpha/code.jsonl");
        host.directories("/var/lib/porelo-host/arrivals/alpha");
        host.write("/var/lib/porelo-host/arrivals/alpha/code.jsonl", "old\n");
        host.directories("/etc/porelo-host/organization/arrivals/alpha");
        host.write("/etc/porelo-host/organization/arrivals/alpha/code.jsonl", "old\n");
        final Path published = host.files.etc.resolve("organization/arrivals/alpha/code.jsonl");
        host.files =
                new ProjectFiles(
                        host.root,
                        host.files.owner,
                        path -> {
                            if (path.equals(ledger)) {
                                assertThat(published)
                                        .as(
                                                "public history is gone before private cleanup can fail")
                                        .doesNotExist();
                                throw new java.io.IOException("injected private cleanup failure");
                            }
                            Files.deleteIfExists(path);
                        });
        assertThat(wholeProject ? remove().code() : host.off(null).code()).isNotZero();
    }

    @Test
    void takingOffRemovesOnlyThatRepositorysArrivals() throws Exception {
        // A removed repository must not leave published provenance for a later replacement.
        registeredKeys();
        host.directories("/var/lib/porelo-host/arrivals/alpha");
        host.write("/var/lib/porelo-host/arrivals/alpha/code.jsonl", "own verdict\n");
        host.write("/var/lib/porelo-host/arrivals/alpha/other.jsonl", "other verdict\n");
        host.directories("/etc/porelo-host/organization/arrivals/alpha");
        host.write("/etc/porelo-host/organization/arrivals/alpha/code.jsonl", "own verdict\n");
        host.write("/etc/porelo-host/organization/arrivals/alpha/other.jsonl", "other verdict\n");
        assertThat(host.off(null).code()).as("taking off succeeds").isZero();
        assertThat(Files.exists(host.files.at("/var/lib/porelo-host/arrivals/alpha/code.jsonl")))
                .as("own arrivals go")
                .isFalse();
        assertThat(Files.exists(host.files.at("/var/lib/porelo-host/arrivals/alpha/other.jsonl")))
                .as("other arrivals remain")
                .isTrue();
        assertThat(host.files.etc.resolve("organization/arrivals/alpha/code.jsonl"))
                .as("a removed repository has no public arrivals")
                .doesNotExist();
        assertThat(host.files.etc.resolve("organization/arrivals/alpha/other.jsonl"))
                .as("other repositories keep their public arrivals")
                .exists();
    }

    @Test
    void removingAProjectDeletesOnlyItsPublishedArrivals() throws Exception {
        // A departed project's readable history must not survive its removal or erase a peer's.
        host.add();
        for (String project : List.of("alpha", "beta")) {
            host.directories("/etc/porelo-host/organization/arrivals/" + project);
            host.write(
                    "/etc/porelo-host/organization/arrivals/" + project + "/code.jsonl",
                    "verdict\n");
        }
        assertThat(remove().code()).as("project removal succeeds").isZero();
        assertThat(host.files.etc.resolve("organization/arrivals/alpha"))
                .as("a removed project has no public arrivals")
                .doesNotExist();
        assertThat(host.files.etc.resolve("organization/arrivals/beta/code.jsonl"))
                .as("other projects keep their public arrivals")
                .exists();
    }

    private boolean filesExist(String relative) {
        return Files.exists(host.files.etc.resolve(relative));
    }

    @Test
    void packageIdentitiesAreEncodedAsSinglePathSegmentsBeforeDeletion() throws Exception {
        host.add();
        host.packages.put(
                "alpha",
                List.of(Map.of("type", "maven", "name", "org.example:*", "version", "1.0+test")));
        assertThat(remove().code()).as("a package identity is validated before removal").isZero();
        assertThat(host.calls)
                .as(
                        "a star, a colon and a plus in a package identity are percent-encoded in its path segment")
                .anyMatch(
                        call ->
                                call.method().equals("DELETE")
                                        && call.path()
                                                .equals(
                                                        "/api/v1/packages/alpha/maven/org.example%3A%2A/1.0%2Btest"));
    }

    private static int descriptor(Path file) throws IOException {
        try (var paths = Files.list(Path.of("/proc/self/fd"))) {
            return paths.filter(
                            path -> {
                                try {
                                    return Files.isSameFile(path, file);
                                } catch (IOException ex) {
                                    return false;
                                }
                            })
                    .mapToInt(path -> Integer.parseInt(path.getFileName().toString()))
                    .findFirst()
                    .orElseThrow();
        }
    }
}
