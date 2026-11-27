package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Holds the interface description against what the daemon actually answers.
 *
 * <p>The description exists so that an interface can be written against it without reading this
 * daemon's Java, which is only worth anything if it cannot drift. A method registered and not
 * described is one no interface author will know exists; a method described and not registered is
 * one they will call and be refused by. Both are caught here rather than by whoever is building the
 * frontend.
 *
 * <p>Deliberately not a parser: varlink's grammar is not what is at risk. What is at risk is the
 * list of names, and that is what this compares.
 */
class InterfaceDescriptionTest {

    @Test
    void aFullPatchCommandPreservesTheAbsenceOfAComparisonBase(@TempDir Path dir) throws Exception {
        final PoreloContext context = context(dir);
        final Path file = dir.resolve("project.yml");
        Files.writeString(
                file,
                """
                project: { name: bound, security_class: guarded }
                image: { from: 'ubuntu:24.04' }
                repositories:
                  app: { upstream: 'https://forge.example.org/team/app.git' }
                """);
        Files.createDirectories(context.paths().projects().projectRegistry());
        Files.writeString(
                context.paths().projects().projectRegistry().resolve("bound"), file + "\n");

        final List<String> command =
                GateMethods.reviewCommand(
                        Map.of("project", "bound", "repository", "app"),
                        context,
                        "writer",
                        null,
                        "a".repeat(40));

        assertThat(command)
                .as("the CLI cannot choose a newly available default base")
                .containsSequence("--against", "")
                .containsSequence("--commit", "a".repeat(40))
                .as("spelled as the CLI's own full view and the checkout's command spell them")
                .containsSequence("review", "--project", "bound", "--repository", "app");
    }

    @Test
    void reviewNamesItsCommitAndApprovalKeepsTheOptionalWireParameter() {
        final String description = PoreloDaemon.description();
        final String review =
                description.substring(
                        description.indexOf("method Review("),
                        description.indexOf("type ReviewCut"));
        final String approval =
                description.substring(
                        description.indexOf("method Approve("),
                        description.indexOf("method PendingBundle("));
        assertThat(review)
                .as("clients can bind the evidence while accepting older daemons")
                .contains("commit: ?string");
        assertThat(approval)
                .as("older callers receive a typed refusal rather than a schema break")
                .contains("commit: ?string");
        assertThat(description)
                .as("the refusal provides the revision to review")
                .contains("error CommitNotNamed(name: string, waiting: string)");
    }

    @Test
    void workRecordsDeclareAnOptionalUpdateTime() {
        // Clients order kept records by their own write time and still accept older records.
        final Matcher record =
                Pattern.compile("(?ms)^type TaskRecord \\(\\n(.*?)^\\)")
                        .matcher(PoreloDaemon.description());

        assertThat(record.find()).as("the work record type is declared").isTrue();
        assertThat(record.group(1))
                .as("new writes expose their time without requiring it of older records")
                .contains("updatedAt: ?string");
    }

    @Test
    void bothStartMethodsAndTheRecordTakeWorkAsAList() {
        // A task works on several issues and phases in their order, the first the gate's.
        for (String declared : List.of("method Start", "method CanStart", "type TaskRecord")) {
            final Matcher declaration =
                    Pattern.compile("(?ms)^" + declared + " ?\\(\\n(.*?)^\\)")
                            .matcher(PoreloDaemon.description());
            assertThat(declaration.find()).as("%s is declared", declared).isTrue();
            assertThat(declaration.group(1))
                    .as("%s takes a list of issue and phase", declared)
                    .contains("work: ?[](issue: string, phase: string)");
        }
    }

    @Test
    void bothStartMethodsAcceptAnOptionalShortSpelling() {
        // A client must be able to preflight exactly the request it starts.
        for (String method : List.of("Start", "CanStart")) {
            final Matcher declaration =
                    Pattern.compile("(?ms)^method " + method + "\\(\\n(.*?)^\\)")
                            .matcher(PoreloDaemon.description());
            assertThat(declaration.find()).as("the method is declared").isTrue();
            assertThat(declaration.group(1))
                    .as("the optional spelling crosses the contract")
                    .contains("short: ?string");
        }
    }

    @Test
    void bothStartMethodsAskForEveryRepositoryAndCanStartNamesItsRefusal() {
        // A client preflights a start that reads every repository as it starts it.
        for (String method : List.of("Start", "CanStart")) {
            final Matcher declaration =
                    Pattern.compile("(?ms)^method " + method + "\\(\\n(.*?)^\\)")
                            .matcher(PoreloDaemon.description());
            assertThat(declaration.find()).as("the method is declared").isTrue();
            assertThat(declaration.group(1)).contains("allRepositories: ?bool");
        }
        final Matcher outcomes =
                Pattern.compile("(?ms)^type StartOutcome \\(\\n(.*?)^\\)")
                        .matcher(PoreloDaemon.description());
        assertThat(outcomes.find()).isTrue();
        assertThat(outcomes.group(1)).contains("ALL_REPOSITORIES_REFUSED,");
    }

    @Test
    void aTakenContainerCarriesTheRequestedAndOptionalProvenOwner() {
        assertThat(PoreloDaemon.description())
                .contains(
                        "error ContainerNameTaken(container: string, project: string, task: string, ownerProject: ?string, ownerTask: ?string)");
    }

    @Test
    void aProjectDeclaresItsOptionalHostFileError() {
        // Clients must distinguish an unreadable host file from a project the host does not carry.
        final Matcher project =
                Pattern.compile("(?ms)^type Project \\(\\n(.*?)^\\)")
                        .matcher(PoreloDaemon.description());
        assertThat(project.find()).as("the project type is declared").isTrue();
        assertThat(project.group(1))
                .as("the per-project error is optional on the wire")
                .contains("hostFileError: ?string");
    }

    // Clients must be able to receive live progress without requiring it from older hosts.
    @Test
    void projectProgressFieldsAreOptionalOnTheWireAndMatchTheRecord() {
        final Matcher project =
                Pattern.compile("(?ms)^type Project \\(\\n(.*?)^\\)")
                        .matcher(PoreloDaemon.description());
        assertThat(project.find()).as("the project contract exists").isTrue();
        assertThat(project.group(1))
                .contains(
                        "hostTakingDone: ?int",
                        "hostTakingTotal: ?int",
                        "hostTakingSince: ?string");
        assertThat(
                        java.util.Arrays.stream(
                                        org.fuin.porelo.app.ProjectInventory.Summary.class
                                                .getRecordComponents())
                                .map(java.lang.reflect.RecordComponent::getName)
                                .toList())
                .as("the daemon's record carries each contract field")
                .contains("hostTakingDone", "hostTakingTotal", "hostTakingSince")
                .doesNotContain(
                        "taking_pid",
                        "taking_pid_start",
                        "taking_boot",
                        "hostTakingPid",
                        "hostTakingPidStart",
                        "hostTakingBoot");
        assertThat(project.group(1))
                .doesNotContain(
                        "taking_pid",
                        "taking_pid_start",
                        "taking_boot",
                        "hostTakingPid",
                        "hostTakingBoot");
    }

    @Test
    void aCredentialSaysWhetherNothingEndsIt() {
        final Matcher credential =
                Pattern.compile("(?ms)^type Credential \\(\\n(.*?)^\\)")
                        .matcher(PoreloDaemon.description());
        assertThat(credential.find()).as("the credential type is declared").isTrue();
        assertThat(names(Pattern.compile("(?m)^\\s*([a-z][A-Za-z0-9]*)\\s*:"), credential.group(1)))
                .contains("noExpiry");
        assertThat(credential.group(1)).contains("noExpiry: bool");
    }

    @Test
    void missingBuildsCredentialsAreDeclaredForTheStartAndTask() {
        // Clients must learn the remedy and restart requirement from typed fields, never prose.
        final String description = PoreloDaemon.description();
        final Matcher missing =
                Pattern.compile("(?ms)^type MissingCredential \\(\\n(.*?)^\\)")
                        .matcher(description);
        assertThat(missing.find()).as("the missing credential type is declared").isTrue();
        assertThat(names(Pattern.compile("(?m)^\\s*([a-z][A-Za-z0-9]*)\\s*:"), missing.group(1)))
                .containsExactlyInAnyOrder(
                        "for", "credential", "forge", "storeCommand", "afterStore");
        assertThat(description)
                .contains("missing: []MissingCredential", "buildsCredential: ?MissingCredential");
    }

    @Test
    void arrivalsDistinguishAnUnavailablePublicationFromAnEmptyHistory() {
        // A client must not hide judged work when the host cannot supply its publication.
        assertThat(PoreloDaemon.description())
                .as("arrivals expose a named unavailable state and the recorded rows")
                .contains("method Arrivals(project: string, repository: string)")
                .contains("entries: []object")
                .contains("cannot-say");
    }

    private static PoreloContext context(Path dir) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    /** {@code method Name(...)} at the start of a line, which is the only place one may appear. */
    private static final Pattern METHOD =
            Pattern.compile("(?m)^method\\s+([A-Za-z][A-Za-z0-9]*)\\s*\\(");

    /** {@code error Name(...)}, same shape. */
    private static final Pattern ERROR =
            Pattern.compile("(?m)^error\\s+([A-Za-z][A-Za-z0-9]*)\\s*\\(");

    /** {@code type Name (} at the start of a line. */
    private static final Pattern TYPE =
            Pattern.compile("(?m)^type\\s+([A-Za-z][A-Za-z0-9]*)\\s*\\(");

    /** A field's type: {@code name: Thing}, {@code name: []Thing}, {@code name: ?Thing}. */
    private static final Pattern REFERENCE =
            Pattern.compile(
                    "(?m)^\\s*[a-z][A-Za-z0-9_]*\\s*:\\s*\\??(?:\\[\\])?\\??([A-Za-z][A-Za-z0-9]*)");

    /** What varlink defines itself, so a reference to one is not a reference to anything here. */
    private static final Set<String> BUILT_IN = Set.of("string", "int", "float", "bool", "object");

    /** The one form a marker takes: true alone, or false and a reason of at most 200 characters. */
    private static final Pattern MARKER = Pattern.compile("# x-mcp: (?:true|false \\S.{0,199})");

    /**
     * Says what is wrong with the description's {@code x-mcp} markers: each is a line of its own
     * form, the last comment line directly above a method, and no method has two.
     */
    private static List<String> markerFaults(final String description) {
        final List<String> faults = new java.util.ArrayList<>();
        final String[] lines = description.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].contains("x-mcp")) {
                continue;
            }
            if (!MARKER.matcher(lines[i]).matches()) {
                faults.add("line " + (i + 1) + " is no marker of the form: " + lines[i]);
            } else if (i + 1 >= lines.length || !METHOD.matcher(lines[i + 1]).lookingAt()) {
                faults.add("line " + (i + 1) + " is not the last line above a method");
            }
        }
        return faults;
    }

    @Test
    void everyMarkerIsOfItsFormAndLastAboveItsMethod() {
        assertThat(markerFaults(PoreloDaemon.description())).isEmpty();
        assertThat(PoreloDaemon.description())
                .contains("# x-mcp: true\nmethod McpMethods()")
                .contains("# x-mcp: false the person's own judgement\nmethod Approve(");
    }

    @Test
    void aMarkerOutOfItsFormOrPlaceIsFound() {
        assertThat(markerFaults("# x-mcp: false\nmethod A() -> ()\n"))
                .as("false without a reason")
                .singleElement()
                .asString()
                .contains("no marker of the form");
        assertThat(markerFaults("# x-mcp: maybe\nmethod A() -> ()\n")).hasSize(1);
        assertThat(markerFaults("  # x-mcp: true \nmethod A() -> ()\n"))
                .as("anything around the marker")
                .hasSize(1);
        assertThat(markerFaults("# x-mcp: true\n# Lists.\nmethod A() -> ()\n"))
                .as("not last above the method")
                .singleElement()
                .asString()
                .contains("not the last line");
        assertThat(markerFaults("# x-mcp: true\n# x-mcp: true\nmethod A() -> ()\n"))
                .as("two markers")
                .hasSize(1);
        assertThat(markerFaults("# x-mcp: false " + "r".repeat(201) + "\nmethod A() -> ()\n"))
                .as("a reason over 200 characters")
                .hasSize(1);
    }

    @Test
    void approveRejectAndLandedMaySayAnEndCouldNotBeKept() {
        final String description = PoreloDaemon.description();
        for (final String method : List.of("Approve", "Reject", "Landed")) {
            final Matcher reply =
                    Pattern.compile("(?ms)^method " + method + "\\(.*?\\)\\s*->\\s*\\((.*?)\\n\\n")
                            .matcher(description);
            assertThat(reply.find()).as(method).isTrue();
            assertThat(reply.group(1)).as(method + "'s reply").contains("endUnkept: ?string");
        }
    }

    @Test
    void roomMembersDeclaresTheProjectedFieldsAndDistinctFailures() {
        // A client needs current room members and typed failures to offer the correct next action.
        final String description = PoreloDaemon.description();
        assertThat(description)
                .as("the live membership signature and its failures")
                .contains(
                        "method RoomMembers(project: string) -> (room: string, members: []RoomMember, paged: ?RoomMembersPaged)",
                        "type RoomMembersPaged (",
                        "method RoomMembersPage(",
                        "error RoomMembersReadingGone(project: string, reading: string)",
                        "error NoTransport(project: string, transport: string)",
                        "error NoRoom(project: string)",
                        "error HomeserverUnreachable(project: string, transport: string, message: string, code: int)");
        final Matcher member =
                Pattern.compile("(?ms)^type RoomMember \\(\\n(.*?)^\\)").matcher(description);
        assertThat(member.find()).as("the member type is declared").isTrue();
        assertThat(names(Pattern.compile("(?m)^\\s*([a-z][A-Za-z0-9]*)\\s*:"), member.group(1)))
                .as("the fields sent for a task member")
                .isEqualTo(
                        new org.fuin.porelo.app.RoomMembers.Member(
                                        "@task", "Task", "join", "task", "porelo-p-api")
                                .asMap()
                                .keySet());
    }

    @Test
    void buildMetadataIsOptionalInTheTasks1Contract() {
        // Clients need typed optional metadata while older adapters must remain readable.
        final String description = PoreloDaemon.description();
        assertThat(
                        description.substring(
                                description.indexOf("type BuildRun ("),
                                description.indexOf("# One file handed")))
                .as("the new run and job fields are optional")
                .contains(
                        "event: ?string",
                        "id: ?string",
                        "run: ?string",
                        "failedTests: ?[]string",
                        "url: ?string");
    }

    @Test
    void aTasksReportsAreDeclaredWithBothMethodsTheRowAndTheirFields() {
        // The planner and the window read each report by these names and keep the last number.
        final String description = PoreloDaemon.description();
        assertThat(description)
                .as("the task reports back and is told its number; the person reads after one")
                .contains(
                        "method Report(task: string, kind: string, text: string) -> (seq: int)",
                        "method Reports(task: string, after: ?int)"
                                + " -> (reports: []TaskReport, last: int, more: bool)",
                        "error NotAllowed(message: string)",
                        "error NotItsOwn(task: string, parent: string)");
        final Matcher report =
                Pattern.compile("(?ms)^type TaskReport \\(\\n(.*?)^\\)").matcher(description);
        assertThat(report.find()).as("the contract declares TaskReport").isTrue();
        assertThat(names(Pattern.compile("(?m)^\\s*([a-z][A-Za-z0-9]*)\\s*:"), report.group(1)))
                .as("its fields")
                .containsExactlyInAnyOrder("seq", "kind", "text", "at");
        final Matcher task =
                Pattern.compile("(?ms)^type Task \\(\\n(.*?)^\\)").matcher(description);
        assertThat(task.find()).as("the contract declares Task").isTrue();
        assertThat(task.group(1))
                .as("every row carries the number of its last report")
                .contains("reports: int")
                .doesNotContain("ideas");
        assertThat(description)
                .as("nothing of the ideas is left")
                .doesNotContainPattern("(?i)\\b\\w*idea(s|HeldBack)?\\b");
    }

    @Test
    void memberRemovalDeclaresItsInputsOutcomesAndMissingMemberError() {
        // Clients need every outcome and a named missing-member refusal to implement revocation.
        final String description = PoreloDaemon.description();
        assertThat(description)
                .as("removal declares the complete wire signature")
                .contains(
                        "method RemoveMessageMember(project: string, person: string) -> (user: string, kept: bool, why: string)");
        assertThat(description)
                .as("missing membership has its own declared error")
                .contains("error NoSuchMember(person: string)");
    }

    @Test
    void namesNoTypeItDoesNotDeclare() throws IOException {

        // A description is only worth anything if somebody can write a client from it alone, and a
        // field whose type is named but never declared stops exactly that - the reader has to come
        // and read this daemon's Java, which is the thing the file exists to avoid. Found worth
        // guarding when a new named type was added for an agent's commit identity: nothing here
        // would have noticed if it had been referenced and never written down.
        final String description = PoreloDaemon.description();
        final Set<String> declared = new TreeSet<>();
        final Matcher types = TYPE.matcher(description);
        while (types.find()) {
            declared.add(types.group(1));
        }
        // Enums are declared the same way, so this list is every name the file introduces.
        final Set<String> referenced = new TreeSet<>();
        final Matcher fields = REFERENCE.matcher(description);
        while (fields.find()) {
            if (!BUILT_IN.contains(fields.group(1))) {
                referenced.add(fields.group(1));
            }
        }

        assertThat(referenced).isNotEmpty();
        assertThat(declared).containsAll(referenced);
    }

    @Test
    void describesEveryMethodItAnswersAndAnswersEveryMethodItDescribes(@TempDir Path dir)
            throws IOException {

        try (VarlinkServer server =
                PoreloDaemon.serving(context(dir), dir.resolve("porelod.sock"))) {

            final Set<String> registered =
                    new TreeSet<>(
                            server.methodNames().stream()
                                    .filter(name -> name.startsWith(PoreloDaemon.INTERFACE + "."))
                                    .map(
                                            name ->
                                                    name.substring(
                                                            PoreloDaemon.INTERFACE.length() + 1))
                                    .toList());

            assertThat(names(METHOD, PoreloDaemon.description()))
                    .as("methods in " + PoreloDaemon.INTERFACE + ".varlink versus those registered")
                    .isEqualTo(registered);
        }
    }

    @Test
    void describesEveryReviewRefusalAsAnErrorOfItsOwnName() {
        // The comment methods answer a review's refusals by these names, built from the enum and
        // never written in the daemon's source, so the source reading below cannot see them.
        final Set<String> described = names(ERROR, PoreloDaemon.description());
        final List<String> missing =
                java.util.Arrays.stream(org.fuin.porelo.gate.review.ReviewError.values())
                        .map(org.fuin.porelo.gate.review.ReviewError::wireName)
                        .filter(name -> !described.contains(name))
                        .toList();
        assertThat(org.fuin.porelo.gate.review.ReviewError.values()).hasSize(11);
        assertThat(missing).as("review refusals not described as errors").isEmpty();
        assertThat(PoreloDaemon.description())
                .contains("error TextRefused(rule: string)")
                .contains("error TooLarge(limit: string)");
    }

    @Test
    void theCommentMethodsTakeAndAnswerTheAgreedFields() {
        final String description = PoreloDaemon.description();
        assertThat(description)
                .containsPattern(
                        "method Comment\\((?s:.*?)side: string,(?s:.*?)startLine: int,(?s:.*?)endLine:"
                                + " int,(?s:.*?)replacement: \\?string,(?s:.*?)id: \\?string\\s*\\)"
                                + " -> \\((?s:.*?)guid: string,\\s*thread: string\\s*\\)")
                .containsPattern(
                        "method Comments\\((?s:.*?)cursor: \\?string\\s*\\) -> \\((?s:.*?)sarif:"
                                + " string,(?s:.*?)cursor: \\?string\\s*\\)")
                .containsPattern(
                        "method ResolveThread\\((?s:.*?)reopen: \\?bool\\s*\\) -> \\((?s:.*?)thread:"
                                + " string,(?s:.*?)state: string\\s*\\)");
    }

    @Test
    void theReviewSummaryAnswersItsCountsAndEachRunsWord() {
        // The interface and the plugin show these fields as the daemon names them, never their own.
        assertThat(PoreloDaemon.description())
                .containsPattern(
                        "method ReviewSummary\\(\\s*project: string,\\s*repository: string,"
                                + "(?s:.*?)name: string,(?s:.*?)commit: string\\s*\\) -> \\("
                                + "(?s:.*?)openFindings: int,(?s:.*?)openThreads: int,"
                                + "(?s:.*?)runs: \\[\\]ReviewRun\\s*\\)")
                .containsPattern(
                        "type ReviewRun \\(\\s*name: string,(?s:.*?)completeness: string,"
                                + "(?s:.*?)findings: int\\s*\\)");
    }

    @Test
    void aRunsPartsPlaceAndWhatIsOpenThereAreDeclaredAsThePlannerReadsThem() {
        // The planner reads these names as the contract gives them; a renamed field is a planner
        // that reads nothing.
        final String description = PoreloDaemon.description();
        assertThat(description)
                .as("Start and CanStart take the four parts beside the place, and no role")
                .containsPattern(
                        "method Start\\((?s:.*?)rights: \\?\\[\\]Right,(?s:.*?)readLanded:"
                                + " \\?\\[\\]string,(?s:.*?)writeAs: \\?\\(author, review\\),"
                                + "(?s:.*?)projectServices: \\?bool,(?s:.*?)place:"
                                + " \\?Place\\s*\\) -> \\(")
                .containsPattern(
                        "method CanStart\\((?s:.*?)rights: \\?\\[\\]Right,(?s:.*?)readLanded:"
                                + " \\?\\[\\]string,(?s:.*?)writeAs: \\?\\(author, review\\),"
                                + "(?s:.*?)projectServices: \\?bool,(?s:.*?)place:"
                                + " \\?Place\\s*\\) -> \\(")
                .doesNotContainPattern("(?m)^\\s*role: \\?\\(AUTHOR, REVIEWER\\),")
                .doesNotContainPattern("(?m)^\\s*reads: \\?\\[\\]string,")
                .contains(
                        "type Right (push, reply, openThreads, resolveThreads, start, stop, done,"
                                + " report, reject)")
                .as("a place is a gate push, a pull request or the task's own pull request")
                .containsPattern(
                        "type Place \\((?s:.*?)gatePush: \\?GatePush,(?s:.*?)pullRequest:"
                                + " \\?string,(?s:.*?)ownPullRequest: \\?bool\\s*\\)")
                .containsPattern(
                        "type GatePush \\((?s:.*?)repository: \\?string,(?s:.*?)name: string\\s*\\)")
                .as(
                        "a task and its record carry its workspace, its four parts, place and held items")
                .containsPattern(
                        "(?m)^type Task \\((?:(?!\\n\\))[\\s\\S])*?workspace: \\(own, foreign,"
                                + " readOnly, allRepositories, none\\),"
                                + "(?:(?!\\n\\))[\\s\\S])*?rights: \\[\\]Right,"
                                + "(?:(?!\\n\\))[\\s\\S])*?readLanded: \\[\\]string,"
                                + "(?:(?!\\n\\))[\\s\\S])*?writeAs: \\(author, review\\),"
                                + "(?:(?!\\n\\))[\\s\\S])*?projectServices: bool,"
                                + "(?:(?!\\n\\))[\\s\\S])*?place: \\?Place,"
                                + "(?:(?!\\n\\))[\\s\\S])*?held: \\?int\\s*\\n\\)")
                .doesNotContainPattern("(?m)^\\s*role: \\?string,")
                .containsPattern(
                        "type TaskRecord \\((?s:.*?)workspace: \\(own, foreign, readOnly,"
                                + " allRepositories, none\\),\\s*rights: \\[\\]Right,\\s*readLanded:"
                                + " \\[\\]string,\\s*writeAs: \\(author, review\\),\\s*"
                                + "projectServices: bool,(?s:.*?)place: \\?Place,"
                                + "(?s:.*?)held: \\?int\\s*\\)")
                .as("ReviewOpen answers the decision, the points with their times and the counts")
                .containsPattern(
                        "method ReviewOpen\\(\\s*project: string,\\s*place: Place,"
                                + "(?:\\s*#[^\\n]*\\n)*\\s*task: \\?string"
                                + "\\s*\\) -> \\((?s:.*?)head: \\?string,(?s:.*?)decision: \\(OPEN,"
                                + " RETURNED, REJECTED, LANDED, APPROVED, MERGED, CLOSED\\),"
                                + "(?s:.*?)mergedCommit: \\?string,(?s:.*?)mergedHead: \\?string,"
                                + "(?s:.*?)decisionSince: \\?string,(?s:.*?)points:"
                                + " \\[\\]OpenPoint,(?s:.*?)findings: int,(?s:.*?)held: int,"
                                + "(?s:.*?)complete: bool,(?s:.*?)reason: \\?string,"
                                + "(?s:.*?)version: string\\s*\\)")
                .containsPattern(
                        "type OpenPoint \\(\\s*thread: string,(?s:.*?)path: \\?string,"
                                + "(?s:.*?)line: \\?int,(?s:.*?)lastBy: string,(?s:.*?)lastAt:"
                                + " string\\s*\\)")
                .as("the refusals of a run's place and parts")
                .contains(
                        "error UnknownForge(host: string)",
                        "error NoPullRequest(branch: string)",
                        "error NotTheTasksPush(head: string)",
                        "error StartRuleBroken(rule: string, detail: string)",
                        "error RightsChanged()")
                .doesNotContain("error RoleNotAllowed")
                .doesNotContain("error RightMissing")
                .containsPattern(
                        "error PlaceUnreadable\\(\\s*reason: \\(NoSuchPush, NotInProject,"
                                + " MovedSinceReview, MissingForgeToken, ForgeAdapterMissing,"
                                + " Unreachable, HeadNotFetched,\\s*ForgeFileUnreadable\\),\\s*detail:"
                                + " string\\s*\\)");
    }

    @Test
    void heldItemsForgeInstancesAndAPullRequestsReasonsAreDeclaredAsTheirClientsReadThem() {
        // The window, the IDE plugin and the planner build against these names before the
        // methods answer; a renamed field is a client that reads nothing.
        final String description = PoreloDaemon.description();
        assertThat(description)
                .as("held items are listed by task or for the whole machine, in pages")
                .containsPattern(
                        "method HeldItems\\(\\s*(?s:.*?)task: \\?string,(?s:.*?)cursor: \\?string"
                                + "\\s*\\) -> \\(\\s*items: \\[\\]HeldItem,(?s:.*?)cursor: \\?string,"
                                + "(?s:.*?)unreadable: \\?\\[\\]string\\s*\\)")
                .containsPattern(
                        "type HeldItem \\(\\s*(?s:.*?)id: string,(?s:.*?)task: string,(?s:.*?)place:"
                                + " string,\\s*direction: \\(outgoing, incoming\\),(?s:.*?)destination:"
                                + " string,(?s:.*?)operation: \\(comment, reply, resolve\\),(?s:.*?)path:"
                                + " \\?string,(?s:.*?)side: \\?\\(old, new\\),(?s:.*?)startLine:"
                                + " \\?int,(?s:.*?)endLine: \\?int,(?s:.*?)thread:"
                                + " \\?string,(?s:.*?)suggestion: \\?string,(?s:.*?)commit:"
                                + " string,(?s:.*?)text: \\?string,(?s:.*?)digest: string,(?s:.*?)from:"
                                + " \\?string,(?s:.*?)mark: \\?\\(unsigned\\),(?s:.*?)at:"
                                + " string,(?s:.*?)run: int\\s*\\)")
                .as("a decision names the digest it was shown and answers nothing")
                .containsPattern(
                        "method DecideItem\\(\\s*task: string,\\s*id: string,(?s:.*?)digest:"
                                + " string,\\s*decision: \\(release, refuse, pass, dismiss\\)\\s*\\)"
                                + " -> \\(\\)")
                .contains(
                        "error NoSuchItem(id: string)",
                        "error DigestChanged(id: string)",
                        "error AlreadyDecided(id: string)",
                        "error Lapsed(id: string)")
                .as("forge instances and the adapters installed")
                .contains(
                        "method Forges() -> (forges: []ForgeInstance, adapters: []string)",
                        "method ForgeSet(host: string, adapter: string, comments: ?(EXTENDED,"
                                + " TRUSTED)) -> ()",
                        "error AdapterNotInstalled(adapter: string)",
                        "error ForgeFileUnreadable(reason: string)")
                .containsPattern(
                        "type ForgeInstance \\(\\s*(?s:.*?)host: string,(?s:.*?)adapter:"
                                + " string,(?s:.*?)comments: \\?string,(?s:.*?)unreadable: \\?string"
                                + "\\s*\\)")
                .as("a pull request's reasons and its refusal")
                .contains(
                        "error NoSuchPullRequest(url: string)", "error HeadNotInGate(head: string)")
                .containsPattern(
                        "RateLimited(?s:.*?)PagePartial(?s:.*?)PageRefused(?s:.*?)reason: \\?string");
    }

    @Test
    void describesEveryErrorItCanRaise(@TempDir Path dir) {
        // The names the daemon throws, gathered from its own source rather than from a list kept
        // beside it: a list would be the third place to forget.
        assertThat(names(ERROR, PoreloDaemon.description()))
                .as("errors described versus those thrown")
                .containsAll(thrownErrorNames());
    }

    @Test
    void servesTheDescriptionToAClientThatAsks(@TempDir Path dir) throws Exception {
        // The point of the file is that a client can read the contract off a running daemon, so the
        // reading is what is tested - not that the file is on the classpath.
        try (VarlinkServer server =
                PoreloDaemon.serving(context(dir), dir.resolve("porelod.sock"))) {
            Thread.ofVirtual().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                final Map<String, Object> reply =
                        client.call(
                                "org.varlink.service.GetInterfaceDescription",
                                Map.of("interface", PoreloDaemon.INTERFACE));
                assertThat(String.valueOf(reply.get("description")))
                        .isEqualTo(PoreloDaemon.description());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Projects", "WatchProjects"})
    void projectConversationsCarryExactlyTheFieldsTheContractDeclares(
            String method, @TempDir Path dir) throws Exception {
        // A missing wire field leaves clients unable to unblock messages as the contract promises.
        final PoreloContext context = context(dir);
        final Path projectFile = dir.resolve("project.yml");
        Files.writeString(
                projectFile,
                """
                project:
                  name: p
                  security_class: guarded
                image:
                  from: "ubuntu:24.04"
                mail:
                  peers:
                    reviewer: { address: "room:", trust: vouched }
                """);
        Files.createDirectories(context.paths().projects().projectRegistry());
        Files.writeString(
                context.paths().projects().projectRegistry().resolve("p"), projectFile + "\n");
        Files.createDirectories(context.paths().vault().vaultFile().getParent());
        Files.writeString(context.paths().vault().vaultFile(), "sealed");

        try (VarlinkServer server = PoreloDaemon.serving(context, dir.resolve("porelod.sock"))) {
            Thread.ofVirtual().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                final String description =
                        (String)
                                client.call(
                                                "org.varlink.service.GetInterfaceDescription",
                                                Map.of("interface", PoreloDaemon.INTERFACE))
                                        .get("description");
                final Matcher type =
                        Pattern.compile("(?ms)^type ProjectMessages \\(\\n(.*?)^\\)")
                                .matcher(description);
                assertThat(type.find()).as("the contract declares ProjectMessages").isTrue();
                final Set<String> declared =
                        names(Pattern.compile("(?m)^\\s*([a-z][A-Za-z0-9]*)\\s*:"), type.group(1));
                assertThat(declared)
                        .as("the contract exposes both the wait and its actionable cause")
                        .contains("waits", "waitsOn", "vault");

                final Map<String, Object> reply =
                        client.call(PoreloDaemon.INTERFACE + "." + method, Map.of());
                final Matcher projectType =
                        Pattern.compile("(?ms)^type Project \\(\\n(.*?)^\\)").matcher(description);
                assertThat(projectType.find()).as("the contract declares Project").isTrue();
                assertThat(
                                names(
                                        Pattern.compile("(?m)^\\s*([a-z][A-Za-z0-9]*)\\s*:"),
                                        projectType.group(1)))
                        .as("a project carries recorded use for clients to interpret")
                        .contains("lastUsed");
                assertThat((List<?>) reply.get("projects"))
                        .as("every project sends its recorded use even when there is none")
                        .allSatisfy(
                                project ->
                                        assertThat(((Map<?, ?>) project).get("lastUsed"))
                                                .as("no recorded task or message means no last use")
                                                .isEqualTo(""));
                final List<?> conversations =
                        ((List<?>) reply.get("projects"))
                                .stream()
                                        .map(project -> ((Map<?, ?>) project).get("messages"))
                                        .filter(java.util.Objects::nonNull)
                                        .toList();

                assertThat(conversations)
                        .as(
                                "the fixture supplies a conversation so the field check cannot pass vacuously")
                        .hasSize(1)
                        .allSatisfy(
                                messages ->
                                        assertThat(((Map<?, ?>) messages).keySet())
                                                .as(
                                                        method
                                                                + " conversation fields versus ProjectMessages")
                                                .isEqualTo(declared));
            }
        }
    }

    @Test
    void theRepositorysSettingsAreAnsweredInTheFieldsTheContractDeclares(@TempDir Path dir)
            throws Exception {

        // A repository's own settings, served before its logic so a window can build against it.
        final PoreloContext context = context(dir);
        try (VarlinkServer server = PoreloDaemon.serving(context, dir.resolve("porelod.sock"))) {
            Thread.ofVirtual().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                final var canStart = client.call(PoreloDaemon.INTERFACE + ".CanStart", Map.of());
                assertThat(canStart)
                        .containsEntry("settingsToAllow", List.of())
                        .containsEntry("settingsHash", "")
                        .containsEntry("file", "")
                        .containsEntry("commit", "");
                final var allow =
                        client.call(
                                PoreloDaemon.INTERFACE + ".AllowSettings",
                                Map.of("repository", "shop", "hash", "0".repeat(64)));
                assertThat(allow)
                        .as("nothing is ever to be allowed before step (2)")
                        .containsEntry("outcome", "CHANGED");
            }
        }
        assertThat(PoreloDaemon.description())
                .contains("SETTINGS_NOT_ALLOWED", "SETTINGS_UNSIGNED", "fileName: string")
                .contains("removed: []RemovedSetting");
    }

    @Test
    void anEmptyProjectAllowsTheDefaultProjectsSettingsAsCanStartReadsIt(@TempDir Path dir)
            throws Exception {
        // CanStart reads an empty project as none; the yes to what it showed must too.
        final PoreloContext context = context(dir);
        final Path checkout = Files.createDirectories(dir.resolve("shop"));
        Files.writeString(checkout.resolve("porelo.yml"), "image:\n  run: \"RUN echo one\"\n");
        final org.fuin.porelo.app.DefaultProject fallback =
                new org.fuin.porelo.app.DefaultProject(context);
        fallback.add("https://repo.example.org/shop.git", "shop", checkout.toString());
        final Path file = fallback.file();
        final String hash =
                org.fuin.porelo.app.RepositoryOwnSettings.of(
                                context,
                                file,
                                org.fuin.porelo.core.project.ProjectReader.read(file)
                                        .repository("shop"))
                        .hash();
        try (VarlinkServer server = PoreloDaemon.serving(context, dir.resolve("porelod.sock"))) {
            Thread.ofVirtual().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                assertThat(
                                client.call(
                                        PoreloDaemon.INTERFACE + ".AllowSettings",
                                        Map.of("repository", "shop", "hash", hash, "project", "")))
                        .containsEntry("outcome", "ALLOWED");
            }
        }
    }

    @Test
    void taskActivityCarriesTheFieldsTheContractDeclares(@TempDir Path dir) throws Exception {
        // Clients need both measurements to show quiet and ended runs without guessing.
        final PoreloContext context = context(dir);
        ((FakeCommandRunner) context.runner())
                .answering("ps", "porelo-uc-shell-2\tUp 4 minutes\t1788500000\t0\tuc\tguarded\n");
        final Matcher taskType =
                Pattern.compile("(?ms)^type Task \\(\\n(.*?)^\\)")
                        .matcher(PoreloDaemon.description());
        assertThat(taskType.find()).as("the contract declares Task").isTrue();
        assertThat(names(Pattern.compile("(?m)^\\s*([a-z][A-Za-z0-9]*)\\s*:"), taskType.group(1)))
                .as("both task activity measurements are declared")
                .contains(
                        "lastWrote",
                        "inboxWaiting",
                        "model",
                        "modelLabel",
                        "modelFrom",
                        "task",
                        "short",
                        "display",
                        "name");
        try (VarlinkServer server = PoreloDaemon.serving(context, dir.resolve("porelod.sock"))) {
            Thread.ofVirtual().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                final var reply = client.call(PoreloDaemon.INTERFACE + ".List", Map.of());
                assertThat((List<?>) reply.get("tasks"))
                        .as("the fixture lists a task so the wire check is not vacuous")
                        .hasSize(1)
                        .allSatisfy(
                                task -> {
                                    assertThat(((Map<?, ?>) task).get("lastWrote"))
                                            .as("no log has no write time")
                                            .isEqualTo("");
                                    assertThat(
                                                    ((Number)
                                                                    ((Map<?, ?>) task)
                                                                            .get("inboxWaiting"))
                                                            .longValue())
                                            .as("no mailbox has no waiting messages")
                                            .isEqualTo(0L);
                                });
            }
        }
    }

    @Test
    void reachRowsCarryExactlyTheFieldsTheContractAndTheRecordDeclare(@TempDir Path dir)
            throws Exception {
        // A row field in the record and not the contract is a value no client knows it is sent.
        final Matcher rowType =
                Pattern.compile("(?ms)^type ReachRow \\(\\n(.*?)^\\)")
                        .matcher(PoreloDaemon.description());
        assertThat(rowType.find()).as("the contract declares ReachRow").isTrue();
        final Set<String> declared =
                names(Pattern.compile("(?m)^\\s*([a-z][A-Za-z0-9]*)\\s*:"), rowType.group(1));
        assertThat(declared)
                .as("ReachRow versus the record the daemon answers it from")
                .isEqualTo(
                        java.util.Arrays.stream(
                                        org.fuin.porelo.app.Reach.Row.class.getRecordComponents())
                                .map(java.lang.reflect.RecordComponent::getName)
                                .collect(java.util.stream.Collectors.toSet()));
        final PoreloContext context = context(dir);
        try (VarlinkServer server = PoreloDaemon.serving(context, dir.resolve("porelod.sock"))) {
            Thread.ofVirtual().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                final List<?> rows =
                        (List<?>)
                                client.call(PoreloDaemon.INTERFACE + ".Reach", Map.of())
                                        .get("rows");
                assertThat(rows)
                        .as("every row the machine has goes over the wire")
                        .hasSize(new org.fuin.porelo.app.Reach(context).machine().size())
                        .allSatisfy(
                                row ->
                                        assertThat(new TreeSet<Object>(((Map<?, ?>) row).keySet()))
                                                .as("a machine row's fields")
                                                .containsAll(
                                                        List.of("part", "subject", "bound", "kind"))
                                                .isSubsetOf(declared));
                assertThatThrownBy(
                                () ->
                                        client.call(
                                                PoreloDaemon.INTERFACE + ".Reach",
                                                Map.of("task", "porelo-uc-shell-9")))
                        .as("a task that does not exist is refused, not answered as the machine")
                        .isInstanceOfSatisfying(
                                org.fuin.porelo.wire.varlink.VarlinkException.class,
                                ex ->
                                        assertThat(ex.getErrorName())
                                                .isEqualTo(PoreloDaemon.INTERFACE + ".NoSuchTask"));
            }
        }
    }

    @Test
    void repositoryRowsCarryExactlyTheFieldsTheContractDeclares() {
        // A mismatched contract can hide a host's withheld carrying decision from its client.
        final Matcher repositoryType =
                Pattern.compile("(?ms)^type Repository \\(\\n(.*?)^\\)")
                        .matcher(PoreloDaemon.description());
        assertThat(repositoryType.find()).as("the contract declares Repository").isTrue();
        final org.fuin.porelo.app.ProjectInventory.RepositorySummary every =
                new org.fuin.porelo.app.ProjectInventory.RepositorySummary(
                        "backend",
                        false,
                        "",
                        "",
                        0,
                        new org.fuin.porelo.gate.UpstreamDistance.Distance(
                                0,
                                java.time.Instant.parse("2026-11-09T10:00:00Z"),
                                org.fuin.porelo.gate.UpstreamDistance.Reason.MEASURED,
                                null,
                                List.of(),
                                new org.fuin.porelo.gate.UpstreamDistance.Central(
                                        "cannot-say",
                                        1,
                                        java.time.Instant.parse("2026-11-09T10:00:00Z"),
                                        "unreachable")),
                        List.of(),
                        new org.fuin.porelo.app.ProjectInventory.ResolvedLimits(
                                "", "", 0, "", "", ""),
                        "",
                        "",
                        "Agent Backend",
                        true,
                        0,
                        0,
                        "taken",
                        "copy installed");

        assertThat(
                        names(
                                Pattern.compile("(?m)^\\s*([a-z][A-Za-z0-9]*)\\s*:"),
                                repositoryType.group(1)))
                .as("Repository versus a row with every optional field present")
                .isEqualTo(every.asMap().keySet());
    }

    @Test
    void artifactRowsCarryExactlyTheFieldsTheContractDeclaresTheirOwnerAmongThem() {
        // An owner the contract does not carry is lost on the way to every client.
        final Matcher artifactType =
                Pattern.compile("(?ms)^type InstallArtifact \\(\\n(.*?)^\\)")
                        .matcher(PoreloDaemon.description());
        assertThat(artifactType.find()).as("the contract declares InstallArtifact").isTrue();
        final Map<String, Object> row =
                PoreloDaemon.artifact(
                        new org.fuin.porelo.agent.api.InstallArtifact(
                                "https://example.test/tool.tar.gz",
                                "a".repeat(64),
                                "/usr/local/share/tool/tool.tar.gz",
                                "0644",
                                false,
                                null,
                                null,
                                org.fuin.porelo.agent.api.InstallArtifact.Owner.ROOT));

        assertThat(
                        names(
                                Pattern.compile("(?m)^\\s*([a-z][A-Za-z0-9]*)\\s*:"),
                                artifactType.group(1)))
                .as("InstallArtifact versus a root artifact's row")
                .isEqualTo(row.keySet())
                .contains("owner");
    }

    @Test
    void namesItselfWithAVersionAndReportsTheBuildItIs(@TempDir Path dir) throws Exception {
        // Two different versions, deliberately. The digit on the interface name is the
        // compatibility promise a client picks by; the build version is what it shows a person.
        // Confusing them is how a frontend ends up gating a feature on a release number.
        assertThat(PoreloDaemon.INTERFACE)
                .as("varlink interfaces carry their compatibility number, as Clearance1 does")
                .matches(".*[0-9]$");

        try (VarlinkServer server =
                PoreloDaemon.serving(context(dir), dir.resolve("porelod.sock"))) {
            Thread.ofVirtual().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                final Map<String, Object> info =
                        client.call("org.varlink.service.GetInfo", Map.of());
                assertThat(info.get("version"))
                        .as("the build version, not a literal somebody has to remember to bump")
                        .isEqualTo(org.fuin.porelo.app.PoreloVersion.version())
                        .isNotEqualTo("unknown");
                assertThat(new java.util.ArrayList<Object>((List<?>) info.get("interfaces")))
                        .contains(PoreloDaemon.INTERFACE);
            }
        }
    }

    @Test
    void everyMethodRepliesWithAStruct() {

        // Found on Authorizations, and Prompts and Talk had it too: varlink's grammar wants '->
        // (field: type, ...)', and a strict parser refuses '-> SomeType' - and with it the whole
        // description.
        assertThat(
                        java.util.regex.Pattern.compile(
                                        "(?m)^method\\s+\\w+\\([^)]*\\)\\s*->\\s*[A-Za-z]")
                                .matcher(PoreloDaemon.description())
                                .results()
                                .map(java.util.regex.MatchResult::group)
                                .toList())
                .as("methods whose reply is not a struct")
                .isEmpty();
    }

    @Test
    void noTypeMethodOrErrorIsDeclaredTwice() {

        // Found on build 215: a second 'type Grant' beside the egress one. varlink allows one
        // definition per name; a strict parser refuses the whole description, and a lenient one
        // reads one of the two fields' sets with the other's.
        final String description = PoreloDaemon.description();
        for (final Pattern pattern : List.of(TYPE, METHOD, ERROR)) {
            final java.util.List<String> all = new java.util.ArrayList<>();
            final Matcher matcher = pattern.matcher(description);
            while (matcher.find()) {
                all.add(matcher.group(1));
            }
            assertThat(all).as("declared twice").doesNotHaveDuplicates();
        }
    }

    private static Set<String> names(Pattern pattern, String idl) {
        final Set<String> found = new TreeSet<>();
        final Matcher matcher = pattern.matcher(idl);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    /**
     * Returns the error names {@code PoreloDaemon} raises, read out of its source.
     *
     * <p>Reading the source is not elegant and is the reason this works: any other way of listing
     * them is a list that a new error can be added without touching.
     */
    private static Set<String> thrownErrorNames() {
        final String text;
        try {
            text = daemonSources();
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read the daemon's sources", ex);
        }
        final Set<String> found = new TreeSet<>();
        // Anchored on the throw, not on the concatenation: INTERFACE is also glued to the resource
        // name, and the clearance interface is concatenated the same way - both of which this
        // matched before it was narrowed.
        final Matcher matcher =
                Pattern.compile(
                                "new VarlinkException\\(\\s*INTERFACE\\s*\\+\\s*\"\\.([A-Za-z][A-Za-z0-9]*)\"")
                        .matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        // Raised by the server for anything unexpected, so it is never written in this source.
        found.add("Failed");
        assertThat(found).as("errors found in the daemon's sources").isNotEmpty();
        return found;
    }

    @Test
    void describesEveryParameterNameTheDaemonReads() throws IOException {
        // Parameter names are where an IDL quietly stops being true: they are strings on both sides
        // and nothing connects them. Every name the daemon reads out of a call must appear in the
        // description - which also refuses a name varlink cannot express, such as one with a hyphen
        // in it, because it could never have been written there.
        final String idl = PoreloDaemon.description();
        final String source = daemonSources();
        final Matcher matcher =
                Pattern.compile(
                                "(?:text|flag|empty)\\(parameters, \"([^\"]+)\"\\)"
                                        + "|parameters\\.get\\(\"([^\"]+)\"\\)")
                        .matcher(source);
        final List<String> missing = new java.util.ArrayList<>();
        while (matcher.find()) {
            final String name = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
            // On a line of its own, or inline in a signature such as 'method LogArchive(task:
            // string, withAgentOutput: ?bool)'.
            if (!Pattern.compile("(?m)(?:^\\s*|[(,]\\s*)" + Pattern.quote(name) + ":")
                    .matcher(idl)
                    .find()) {
                missing.add(name);
            }
        }
        assertThat(missing).as("parameters read by the daemon but not described").isEmpty();
    }

    /**
     * Returns the daemon's sources as one text: {@link PoreloDaemon} and every area's methods, each
     * in a file of its own.
     *
     * @return Their text, in a fixed order.
     * @throws IOException If one cannot be read.
     */
    private static String daemonSources() throws IOException {
        try (var files =
                java.nio.file.Files.list(Path.of("src/main/java/org/fuin/porelo/daemon"))) {
            final List<Path> sources =
                    files.filter(file -> file.toString().endsWith(".java")).sorted().toList();
            final StringBuilder text = new StringBuilder();
            for (final Path file : sources) {
                text.append(java.nio.file.Files.readString(file)).append('\n');
            }
            return text.toString();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "15-task, Task",
        "20-project, Project",
        "25-egress, Egress",
        "30-vault, Vault",
        "35-gate, Gate",
        "40-agent, Agent",
        "45-messaging, Messaging",
        "50-machine, Machine",
        "55-events, Event"
    })
    void eachAreaRegistersExactlyTheMethodsOfItsOwnPart(String part, String area, @TempDir Path dir)
            throws Exception {

        // The point of one file per area is that an area's change stays in its files: a method
        // registered by one area and described in another's part would be the two files that have
        // to change together again.
        final PoreloContext context = context(dir);
        final VarlinkServer server =
                new VarlinkServer(dir.resolve("porelod.sock"), PoreloDaemon.INTERFACE);
        final Class<?> registrar = Class.forName("org.fuin.porelo.daemon." + area + "Methods");
        final List<Class<?>> signature =
                new java.util.ArrayList<>(
                        List.of(
                                VarlinkServer.class,
                                PoreloContext.class,
                                org.fuin.porelo.app.TaskInventory.class,
                                org.fuin.porelo.app.TaskControl.class));
        final List<Object> arguments =
                new java.util.ArrayList<>(
                        List.of(
                                server,
                                context,
                                org.fuin.porelo.app.TaskInventory.deriving(context),
                                new org.fuin.porelo.app.TaskControl(context)));
        if (Set.of("Task", "Agent").contains(area)) {
            signature.add(org.fuin.porelo.app.AgentSignInRuns.class);
            arguments.add(org.fuin.porelo.app.AgentSignInRuns.of(context));
        }
        // The areas that keep readings release them on the server's sweep.
        final ReadingSweep sweep = new ReadingSweep(java.time.Duration.ofMinutes(1));
        try {
            if (Set.of("Task", "Gate", "Messaging").contains(area)) {
                signature.add(ReadingSweep.class);
                arguments.add(sweep);
            }
            if ("Gate".equals(area)) {
                signature.add(org.fuin.porelo.app.CommentIntake.Parts.class);
                arguments.add(org.fuin.porelo.app.CommentIntake.Parts.of(context));
                signature.add(ReviewParts.class);
                arguments.add(ReviewParts.of(context, new org.fuin.porelo.app.HeldRuns()));
            }
            registrar
                    .getDeclaredMethod("register", signature.toArray(Class<?>[]::new))
                    .invoke(null, arguments.toArray());
        } finally {
            sweep.close();
        }
        final Set<String> registered =
                new TreeSet<>(
                        server.methodNames().stream()
                                .filter(name -> name.startsWith(PoreloDaemon.INTERFACE + "."))
                                .map(name -> name.substring(PoreloDaemon.INTERFACE.length() + 1))
                                .toList());

        final String text;
        try (java.io.InputStream in =
                PoreloDaemon.class.getResourceAsStream(
                        "/varlink/" + PoreloDaemon.INTERFACE + "/" + part + ".varlink")) {
            text =
                    new String(
                            java.util.Objects.requireNonNull(in, part).readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
        }
        assertThat(registered)
                .as(area + "Methods versus " + part)
                .isNotEmpty()
                .isEqualTo(names(METHOD, text));
    }

    @Test
    void theHeaderAndTheCommonPartDescribeNoMethod() throws IOException {
        for (final String part : List.of("00-header", "10-common")) {
            try (java.io.InputStream in =
                    PoreloDaemon.class.getResourceAsStream(
                            "/varlink/" + PoreloDaemon.INTERFACE + "/" + part + ".varlink")) {
                assertThat(
                                names(
                                        METHOD,
                                        new String(
                                                java.util.Objects.requireNonNull(in, part)
                                                        .readAllBytes(),
                                                java.nio.charset.StandardCharsets.UTF_8)))
                        .as(part)
                        .isEmpty();
            }
        }
    }
}
