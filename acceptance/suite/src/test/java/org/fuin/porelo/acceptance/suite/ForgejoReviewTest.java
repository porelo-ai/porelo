package org.fuin.porelo.acceptance.suite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.X509TrustManager;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The Forgejo conversation's requests and recorded response shapes, without a server. */
class ForgejoReviewTest {

    private static final String TOKEN = "forgejo-test-value-never-shown";
    private static final URI ORIGIN = URI.create("https://127.0.0.1:3443/");
    private static final String REPOSITORY = "/api/v1/repos/tester/";
    private static final String COMMENT =
            """
            {"id":30,"path":"src/Login.java","position":28,
             "user":{"login":"tester"},"body":"answer"}
            """;

    @TempDir Path dir;

    @Test
    void everyStepOfTheForgejoFeatureHasItsGlue() {
        final byte exit =
                io.cucumber.core.cli.Main.run(
                        new String[] {
                            "--dry-run",
                            "--tags",
                            "@forgejo",
                            "--glue",
                            "org.fuin.porelo.acceptance",
                            "--plugin",
                            "summary",
                            "classpath:org/fuin/porelo/acceptance/gate/pr-review-forgejo.feature"
                        },
                        Thread.currentThread().getContextClassLoader());
        assertThat(exit).as("undefined or ambiguous Forgejo steps").isZero();
    }

    @Test
    void theStubsCommentOnForgejoIsOneLineWithNoSuggestion() {
        // Forgejo takes no comment on several lines and no suggestion: either would be refused.
        final String file =
                StubReview.singleLine(
                        "https://127.0.0.1:3443/owner/scratch/pulls/1", "a".repeat(40), "Why?");
        assertThat(file).startsWith("submit submitted-comment.sarif.json\n");
        final Map<?, ?> document =
                (Map<?, ?>)
                        org.fuin.porelo.wire.Json.parse(
                                file.substring(file.indexOf('\n') + 1).strip());
        final List<?> results =
                (List<?>) ((Map<?, ?>) ((List<?>) document.get("runs")).getFirst()).get("results");
        assertThat(results).as("one comment").hasSize(1);
        final Map<?, ?> comment = (Map<?, ?>) results.getFirst();
        assertThat(comment.containsKey("fixes")).as("no suggestion").isFalse();
        assertThat(comment.get("message")).isEqualTo(Map.of("text", "Why?"));
        final Map<?, ?> region =
                (Map<?, ?>)
                        ((Map<?, ?>)
                                        ((Map<?, ?>)
                                                        ((List<?>) comment.get("locations"))
                                                                .getFirst())
                                                .get("physicalLocation"))
                                .get("region");
        assertThat(region.get("endLine")).as("one line").isEqualTo(region.get("startLine"));
    }

    @Test
    void theForgejoFeatureKeepsTheGitHubThreadTexts() throws IOException {
        final String directory = "/org/fuin/porelo/acceptance/gate/";
        final String forgejo = resource(directory + "pr-review-forgejo.feature");
        final List<String> github =
                resource(directory + "pr-review-github.feature")
                        .lines()
                        .map(String::strip)
                        .filter(line -> line.startsWith("|"))
                        .toList();
        assertThat(forgejo).startsWith("@slow @forgejo\n");
        assertThat(forgejo.lines().map(String::strip).filter(line -> line.startsWith("|")).toList())
                .containsExactlyElementsOf(github);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "PORELO_FORGEJO_URL",
                "PORELO_FORGEJO_TEST_TOKEN",
                "PORELO_FORGEJO_TEST_OWNER",
                "PORELO_FORGEJO_CA"
            })
    void aMissingOrBlankVariableFailsInsteadOfSkipping(String variable) {
        final Map<String, String> environment =
                new LinkedHashMap<>(
                        Map.of(
                                "PORELO_FORGEJO_URL",
                                ORIGIN.toString(),
                                "PORELO_FORGEJO_TEST_TOKEN",
                                TOKEN,
                                "PORELO_FORGEJO_TEST_OWNER",
                                "tester",
                                "PORELO_FORGEJO_CA",
                                "target/ca.pem"));
        environment.remove(variable);
        assertThatThrownBy(() -> ForgejoScratch.Identity.of(environment::get))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(variable)
                .hasMessageNotContaining(TOKEN);
        environment.put(variable, "  ");
        assertThatThrownBy(() -> ForgejoScratch.Identity.of(environment::get))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(variable)
                .hasMessageNotContaining(TOKEN);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://127.0.0.1:3443/",
                "https://user@localhost/",
                "https://localhost/?query",
                "https://localhost/#fragment",
                "https://localhost/subpath/"
            })
    void anUnsafeInstanceAddressIsRefused(String address) {
        assertThatThrownBy(
                        () ->
                                new ForgejoScratch.Identity(
                                        URI.create(address),
                                        TOKEN,
                                        "tester",
                                        dir.resolve("ca.pem")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("HTTPS origin");
    }

    @Test
    void aMissingOrMalformedCaFailsBeforeAnyRequest() throws IOException {
        assertThatThrownBy(() -> new ForgejoScratch(identity(), dir.resolve("checkout")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("CA");
        Files.writeString(dir.resolve("ca.pem"), "not a certificate");
        assertThatThrownBy(() -> new ForgejoScratch(identity(), dir.resolve("checkout")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("CA");
    }

    @Test
    void theClientTrustsExactlyTheProvidedCaAndKeepsHostnameChecks() throws Exception {
        try (InputStream pem =
                ForgejoReviewTest.class.getResourceAsStream(
                        "/org/fuin/porelo/acceptance/forgejo/test-ca.pem")) {
            Files.copy(java.util.Objects.requireNonNull(pem, "test CA"), identity().ca());
        }
        final X509Certificate ca;
        try (InputStream pem = Files.newInputStream(identity().ca())) {
            ca = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(pem);
        }
        final X509TrustManager manager = ForgejoScratch.trustManager(identity().ca());
        assertThat(manager.getAcceptedIssuers()).containsExactly(ca);
        manager.checkServerTrusted(new X509Certificate[] {ca}, "EC");
        try (HttpClient client = ForgejoScratch.client(identity())) {
            assertThat(client.sslParameters().getEndpointIdentificationAlgorithm())
                    .isEqualTo("HTTPS");
            assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
        }
        Files.writeString(identity().ca(), Files.readString(identity().ca()).repeat(2));
        assertThatThrownBy(() -> ForgejoScratch.trustManager(identity().ca()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("CA");
    }

    @Test
    void theIdentityAndFailuresNeverShowTheTokenOrGitHeader() {
        assertThat(identity().toString()).doesNotContain(TOKEN);
        assertThat(
                        ForgejoScratch.hide(
                                "Authorization: Basic " + basic() + " token " + TOKEN, identity()))
                .doesNotContain(TOKEN, basic());
    }

    @Test
    void gitUsesOnlyTheInstancesHeaderAndCaAndNeverAnArgumentForTheToken() {
        final Map<String, String> environment = ForgejoScratch.gitEnvironment(identity());
        assertThat(environment)
                .containsEntry("GIT_CONFIG_KEY_0", "http.https://127.0.0.1:3443/.extraHeader")
                .containsEntry("GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic())
                .containsEntry("GIT_SSL_CAINFO", dir.resolve("ca.pem").toAbsolutePath().toString())
                .containsEntry("GIT_SSL_VERIFY", "true")
                .containsEntry("GIT_CONFIG_KEY_1", "credential.helper")
                .containsEntry("GIT_CONFIG_VALUE_1", "")
                .containsEntry("GIT_CONFIG_KEY_2", "http.followRedirects")
                .containsEntry("GIT_CONFIG_VALUE_2", "false")
                .containsEntry("GIT_TERMINAL_PROMPT", "0");
        final List<String> command =
                GitHubScratch.gitCommand(
                        dir, List.of("push", "-q", ORIGIN + "tester/scratch.git", "main"));
        assertThat(command)
                .noneMatch(argument -> argument.contains(TOKEN) || argument.contains(basic()));
    }

    @Test
    void createsTheOwnersRepositoryAndPullRequestAndDeletesIt() throws Exception {
        final Recorded recorded = opened();
        final List<List<String>> git = new ArrayList<>();
        final ForgejoScratch scratch = scratch(recorded, git);
        scratch.open();
        final HttpRequest create = recorded.requests.get(1);
        final Map<?, ?> repository = (Map<?, ?>) Json.parseExact(body(create));
        final String name = String.valueOf(repository.get("name"));
        assertThat(create.method()).isEqualTo("POST");
        assertThat(create.uri().getPath()).isEqualTo("/api/v1/user/repos");
        assertThat(repository.get("private")).isEqualTo(true);
        assertThat(repository.get("auto_init")).isEqualTo(false);
        assertThat(repository.get("default_branch")).isEqualTo("main");
        for (final HttpRequest branch : recorded.requests.subList(2, 4)) {
            assertThat(branch.method()).isEqualTo("GET");
            assertThat(branch.uri().getPath())
                    .as("the pull request waits until its head branch is listed")
                    .isEqualTo(REPOSITORY + name + "/branches/review-head");
        }
        final HttpRequest pull = recorded.requests.get(4);
        assertThat(pull.method()).isEqualTo("POST");
        assertThat(pull.uri().getPath()).isEqualTo(REPOSITORY + name + "/pulls");
        final Map<?, ?> opened = (Map<?, ?>) Json.parseExact(body(pull));
        assertThat(opened.get("head")).isEqualTo("review-head");
        assertThat(opened.get("base")).isEqualTo("main");
        assertThat(scratch.url()).isEqualTo(ORIGIN + "tester/" + name + "/pulls/7");
        assertThat(scratch.base()).isEqualTo("a".repeat(40));
        assertThat(Files.readString(dir.resolve("checkout").resolve(GitHubScratch.CHANGED)))
                .isEqualTo(GitHubScratch.lines(true));
        assertThat(git)
                .anySatisfy(
                        arguments ->
                                assertThat(arguments)
                                        .containsExactly(
                                                "push",
                                                "-q",
                                                ORIGIN + "tester/" + name + ".git",
                                                "review-head"));
        recorded.add(204, "");
        scratch.delete();
        assertThat(recorded.requests.getLast().method()).isEqualTo("DELETE");
        assertThat(recorded.requests.getLast().uri().getPath()).isEqualTo(REPOSITORY + name);
        assertThat(dir.resolve("checkout")).doesNotExist();
        assertThat(recorded.responses).isEmpty();
        for (final HttpRequest request : recorded.requests) {
            assertThat(request.uri().toString()).doesNotContain(TOKEN, basic());
            assertThat(request.headers().firstValue("Authorization")).contains("token " + TOKEN);
            assertThat(request.uri().getScheme()).isEqualTo("https");
        }
    }

    @Test
    void aTokenForAnotherOwnerCannotCreateARepository() {
        final Recorded recorded = new Recorded();
        recorded.add(200, "{\"login\":\"someone-else\"}");
        assertThatThrownBy(() -> scratch(recorded, new ArrayList<>()).open())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("test owner");
        assertThat(recorded.requests).hasSize(1);
    }

    @Test
    void readsEveryReviewPageAndEachReviewsCommentsOnceBeforeSortingByCommentId() throws Exception {
        final Recorded recorded = opened();
        final ForgejoScratch scratch = scratch(recorded, new ArrayList<>());
        scratch.open();
        recorded.add(200, "[{\"id\":9}]");
        recorded.add(200, "[{\"id\":8}]");
        recorded.add(200, "[]");
        recorded.add(200, "[" + COMMENT + "]");
        recorded.add(
                200,
                "[{\"id\":10,\"path\":\"src/Login.java\",\"position\":28,"
                        + "\"user\":{\"login\":\"tester\"},\"body\":\"root\"}]");
        final List<ForgejoScratch.Comment> comments = scratch.comments();
        assertThat(comments).extracting(ForgejoScratch.Comment::id).containsExactly(10L, 30L);
        assertThat(recorded.requests.subList(5, 10))
                .extracting(request -> request.uri().getRawQuery())
                .as("reviews are paged; a review's comments, which Forgejo does not page, are not")
                .containsExactly(
                        "limit=50&page=1", "limit=50&page=2", "limit=50&page=3", null, null);
        assertThat(recorded.requests.get(8).uri().getPath())
                .endsWith("/pulls/7/reviews/9/comments");
        assertThat(recorded.requests.get(9).uri().getPath())
                .endsWith("/pulls/7/reviews/8/comments");
        assertThat(recorded.responses).isEmpty();
    }

    @Test
    void answersWithANewCommentAtTheSamePathAndLine() throws Exception {
        final Recorded recorded = opened();
        final ForgejoScratch scratch = scratch(recorded, new ArrayList<>());
        scratch.open();
        recorded.add(200, "[{\"id\":9}]");
        recorded.add(200, "[]");
        recorded.add(200, "[" + COMMENT + "]");
        recorded.add(200, "[]");
        recorded.add(200, "{\"id\":10}");
        scratch.answer("Why here?");
        final HttpRequest answer = recorded.requests.getLast();
        assertThat(answer.method()).isEqualTo("POST");
        assertThat(answer.uri().getPath()).endsWith("/pulls/7/reviews");
        assertThat(Json.parseExact(body(answer)))
                .isEqualTo(
                        Json.parseExact(
                                """
                                {"event":"COMMENT","comments":[
                                  {"path":"src/Login.java","new_position":28,"old_position":0,"body":"Why here?"}]}
                                """));
    }

    @Test
    void groupsByPathAndLineRatherThanInventingReplyIds() {
        final List<ForgejoScratch.Comment> comments =
                ForgejoScratch.comments(
                        """
                        [{"id":30,"path":"src/Login.java","position":28,"user":{"login":"tester"},"body":"answer"},
                         {"id":40,"path":"src/Other.java","position":28,"user":{"login":"tester"},"body":"other path"},
                         {"id":10,"path":"src/Login.java","position":28,"user":{"login":"tester"},"body":"root"},
                         {"id":20,"path":"src/Login.java","position":29,"user":{"login":"tester"},"body":"other line"}]
                        """);
        assertThat(ForgejoScratch.thread(comments, new ForgejoScratch.Anchor("src/Login.java", 28)))
                .extracting(ForgejoScratch.Comment::body)
                .containsExactly("root", "answer");
        assertThatThrownBy(() -> ForgejoScratch.root(comments))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("3 threads");
    }

    @Test
    void commentsOnOneLineHaveOneThreadDespiteHavingNoReplyIds() {
        final List<ForgejoScratch.Comment> comments =
                ForgejoScratch.comments(
                        "["
                                + COMMENT
                                + ","
                                + COMMENT.replace("30", "10").replace("answer", "root")
                                + "]");
        assertThat(ForgejoScratch.root(comments))
                .isEqualTo(new ForgejoScratch.Anchor("src/Login.java", 28));
        assertThat(ForgejoScratch.thread(comments, ForgejoScratch.root(comments)))
                .extracting(ForgejoScratch.Comment::body)
                .containsExactly("root", "answer");
    }

    @Test
    void aFailedApiCallRedactsItsAnswerAndCleanupStillRemovesTheCheckout() throws Exception {
        final Recorded recorded = opened();
        final ForgejoScratch scratch = scratch(recorded, new ArrayList<>());
        scratch.open();
        recorded.add(403, "refused " + TOKEN + " " + basic());
        assertThatThrownBy(scratch::delete)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("answered 403")
                .hasMessageNotContainingAny(TOKEN, basic());
        assertThat(dir.resolve("checkout")).doesNotExist();
    }

    @Test
    void appendsAndRemovesExactlyItsCaLeavingLaterAdditions() throws Exception {
        final Path store = dir.resolve("config/porelo/ca-certificates.pem");
        Files.createDirectories(store.getParent());
        final String original = "an existing authority without a final newline";
        Files.writeString(store, original);
        final String pem = testCa();
        final Path receipt = dir.resolve("target/receipt.json");

        certificate("append", receipt, pem, dir.resolve("config").toString());
        assertThat(Files.readString(store)).isEqualTo(original + "\n" + pem);
        Files.writeString(store, "a later authority\n", StandardOpenOption.APPEND);
        certificate("remove", receipt, "", dir.resolve("config").toString());

        assertThat(Files.readString(store)).isEqualTo(original + "a later authority\n");
        assertThat(receipt).doesNotExist();
    }

    @Test
    void anExistingCaIsNeitherAppendedNorRemoved() throws Exception {
        final Path store = dir.resolve("config/porelo/ca-certificates.pem");
        Files.createDirectories(store.getParent());
        final String original = "existing\r\n" + testCa().replace("\n", "\r\n") + "after\n";
        Files.writeString(store, original);
        Files.setPosixFilePermissions(store, PosixFilePermissions.fromString("rw-r-----"));
        final Path receipt = dir.resolve("target/receipt.json");

        certificate("append", receipt, testCa(), dir.resolve("config").toString());
        assertThat(Files.readString(store)).isEqualTo(original);
        certificate("remove", receipt, "", dir.resolve("config").toString());

        assertThat(Files.readString(store)).isEqualTo(original);
        assertThat(Files.getPosixFilePermissions(store))
                .isEqualTo(PosixFilePermissions.fromString("rw-r-----"));
    }

    @Test
    void createsAPrivateCertificateFileAndLeavesItAbsentAgain() throws Exception {
        final Path receipt = dir.resolve("target/receipt.json");
        final Path store = dir.resolve("config/porelo/ca-certificates.pem");

        certificate("append", receipt, testCa(), dir.resolve("config").toString());
        assertThat(Files.readString(store)).isEqualTo(testCa());
        assertThat(Files.getPosixFilePermissions(store))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        certificate("remove", receipt, "", dir.resolve("config").toString());

        assertThat(store).doesNotExist();
        assertThat(receipt).doesNotExist();
    }

    @Test
    void aFileItCreatedStaysWhenAnotherAuthorityWasAddedLater() throws Exception {
        final Path receipt = dir.resolve("target/receipt.json");
        final Path store = dir.resolve("config/porelo/ca-certificates.pem");
        certificate("append", receipt, testCa(), dir.resolve("config").toString());
        Files.writeString(store, "another authority\n", StandardOpenOption.APPEND);

        certificate("remove", receipt, "", dir.resolve("config").toString());

        assertThat(store).hasContent("another authority\n");
    }

    @Test
    void changedCertificateLinesAreNotRemoved() throws Exception {
        final Path receipt = dir.resolve("target/receipt.json");
        final Path store = dir.resolve("config/porelo/ca-certificates.pem");
        certificate("append", receipt, testCa(), dir.resolve("config").toString());
        final String changed = Files.readString(store).replace("CERTIFICATE", "CHANGED");
        Files.writeString(store, changed);

        assertThatThrownBy(
                        () -> certificate("remove", receipt, "", dir.resolve("config").toString()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("refusing to remove other lines");

        assertThat(store).hasContent(changed);
        assertThat(receipt).exists();
    }

    @Test
    void aRelativeXdgRootUsesTheAccountsHome() throws Exception {
        final Path receipt = dir.resolve("target/receipt.json");
        certificate("append", receipt, testCa(), "relative-config");

        assertThat(dir.resolve(".config/porelo/ca-certificates.pem")).hasContent(testCa());
        assertThat(dir.resolve("relative-config")).doesNotExist();
        certificate("remove", receipt, "", "relative-config");
        assertThat(dir.resolve(".config/porelo/ca-certificates.pem")).doesNotExist();
    }

    private void certificate(String operation, Path receipt, String pem, String config)
            throws Exception {
        final ProcessBuilder builder =
                new ProcessBuilder(
                        "bash",
                        "-c",
                        ForgejoReviewSteps.certificateCommand(operation, receipt.toString()));
        builder.environment().put("HOME", dir.toString());
        builder.environment().put("XDG_CONFIG_HOME", config);
        builder.redirectErrorStream(true);
        final Process process = builder.start();
        try (var input = process.getOutputStream()) {
            input.write(pem.getBytes(StandardCharsets.US_ASCII));
        }
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS))
                    .as("certificate operation finishes")
                    .isTrue();
            assertThat(process.exitValue())
                    .as(
                            "certificate operation: %s",
                            new String(
                                    process.getInputStream().readAllBytes(),
                                    StandardCharsets.UTF_8))
                    .isZero();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String testCa() throws IOException {
        return resource("/org/fuin/porelo/acceptance/forgejo/test-ca.pem");
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = ForgejoReviewTest.class.getResourceAsStream(path)) {
            return new String(
                    java.util.Objects.requireNonNull(input, path).readAllBytes(),
                    StandardCharsets.UTF_8);
        }
    }

    private ForgejoScratch.Identity identity() {
        return new ForgejoScratch.Identity(ORIGIN, TOKEN, "tester", dir.resolve("ca.pem"));
    }

    private ForgejoScratch scratch(Recorded recorded, List<List<String>> git) {
        return new ForgejoScratch(
                identity(),
                dir.resolve("checkout"),
                recorded,
                arguments -> {
                    git.add(List.copyOf(arguments));
                    return arguments.getFirst().equals("rev-parse") ? "a".repeat(40) : "";
                });
    }

    private static Recorded opened() {
        final Recorded recorded = new Recorded();
        recorded.add(200, "{\"login\":\"tester\"}");
        recorded.add(201, "{\"name\":\"scratch\"}");
        // The pushed head branch is not listed at once, then is.
        recorded.add(404, "{\"message\":\"The target couldn't be found.\"}");
        recorded.add(200, "{\"name\":\"review-head\"}");
        recorded.add(201, "{\"number\":7}");
        return recorded;
    }

    private static String basic() {
        return Base64.getEncoder()
                .encodeToString(("tester:" + TOKEN).getBytes(StandardCharsets.UTF_8));
    }

    private static String body(HttpRequest request) {
        final StringBuilder text = new StringBuilder();
        request.bodyPublisher()
                .orElseThrow()
                .subscribe(
                        new Flow.Subscriber<ByteBuffer>() {
                            @Override
                            public void onSubscribe(Flow.Subscription subscription) {
                                subscription.request(Long.MAX_VALUE);
                            }

                            @Override
                            public void onNext(ByteBuffer bytes) {
                                text.append(StandardCharsets.UTF_8.decode(bytes));
                            }

                            @Override
                            public void onError(Throwable failure) {
                                throw new AssertionError(failure);
                            }

                            @Override
                            public void onComplete() {}
                        });
        return text.toString();
    }

    private static final class Recorded implements ForgejoScratch.Exchange {
        private final List<HttpRequest> requests = new ArrayList<>();
        private final ArrayDeque<ForgejoScratch.Reply> responses = new ArrayDeque<>();

        void add(int status, String body) {
            responses.add(new ForgejoScratch.Reply(status, body));
        }

        @Override
        public ForgejoScratch.Reply send(HttpRequest request) {
            requests.add(request);
            return responses.removeFirst();
        }
    }
}
