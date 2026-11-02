package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.agent.api.Agent;
import org.fuin.porelo.agent.api.AgentDefinition;
import org.fuin.porelo.agent.api.AgentDefinitionJson;
import org.fuin.porelo.agent.api.AgentDefinitionReader;
import org.fuin.porelo.agent.api.AgentDirectory;
import org.fuin.porelo.agent.api.AgentProtocol;
import org.fuin.porelo.agent.api.AgentServer;
import org.fuin.porelo.agent.api.Finder;
import org.fuin.porelo.agent.api.Finding;
import org.fuin.porelo.agent.api.InstalledAgents;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.project.Egress;
import org.fuin.porelo.core.project.Limits;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which agent a start on a repository works with: {@code --agent}, the repository's, the machine's.
 */
class AgentChoiceTest {

    private static Repository workedWith(final String agent) {
        return new Repository(
                "backend",
                null,
                "",
                Egress.none(),
                Limits.Declared.none(),
                List.of(),
                null,
                agent,
                null);
    }

    @Test
    void theRepositorysAgentIsTakenWhenNoneIsNamed() {
        assertThat(AgentChoice.wanted(null, workedWith("helper")))
                .as("the project file's agent for the repository, said as its source")
                .isEqualTo(new AgentChoice.Wanted("helper", AgentChoice.Source.REPOSITORY));
    }

    @Test
    void agentWinsOverTheRepositorysAgent() {
        assertThat(AgentChoice.wanted("worker", workedWith("helper")))
                .isEqualTo(new AgentChoice.Wanted("worker", AgentChoice.Source.OPTION));
    }

    @Test
    void withoutEitherTheMachineDecides() {
        assertThat(AgentChoice.wanted(null, new Repository("site", null)))
                .isEqualTo(new AgentChoice.Wanted(null, AgentChoice.Source.MACHINE));
        assertThat(AgentChoice.wanted(null, null))
                .isEqualTo(new AgentChoice.Wanted(null, AgentChoice.Source.MACHINE));
    }

    @Test
    void aRepositorysAgentThatIsNotInstalledRefusesTheStartAndSaysHowToGetIt(
            @TempDir final Path dir) throws Exception {

        // Taking whatever is installed instead would start another agent than the project file
        // names, and its commits would go by the wrong name.
        final Path agents = Files.createDirectories(dir.resolve("agents"));
        try (InstalledAgents installed =
                new InstalledAgents(new AgentDirectory(List.of(agents)), dir.resolve("run"))) {

            final AgentChoice.Choice choice =
                    AgentChoice.considerAgent(context(dir), installed, null, workedWith("helper"));

            assertThat(choice.refusal()).isEqualTo(AgentChoice.AgentRefusal.UNKNOWN_AGENT);
            assertThat(choice.source()).isEqualTo(AgentChoice.Source.REPOSITORY);
            assertThat(choice.detail())
                    .as("the agent, the repository, what to install and the way around it")
                    .contains("'helper'")
                    .contains("'backend'")
                    .contains("install " + AgentDirectory.PREFIX + "helper")
                    .contains("--agent");
        }
    }

    private static PoreloContext context(final Path dir) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    default -> null;
                                },
                        dir);
        // Relative socket paths fit the Unix limit even when the checkout's path is long.
        return new PoreloContext(
                new FakeCommandRunner(),
                new PoreloPaths(
                        new XdgPaths(
                                xdg.config(),
                                xdg.data(),
                                xdg.state(),
                                Path.of("").toAbsolutePath().relativize(xdg.runtime()),
                                xdg.home()),
                        dir.resolve("bin")),
                command -> 0);
    }

    /** A test agent saying what a file beside its definition says it finds. */
    public static final class Reporting {

        /**
         * Serves the agent.
         *
         * @param args Definition file, serve verb and socket path.
         * @throws IOException If a file cannot be read.
         */
        public static void main(final String[] args) throws IOException {
            final AgentDefinition definition =
                    AgentDefinitionReader.read(
                            new StringReader(Files.readString(Path.of(args[0]))), args[0]);
            final String said = Files.readString(Path.of(args[0] + ".finds")).strip();
            if (said.equals("older") || said.equals("errors")) {
                older(definition, args[2], said.equals("errors"));
                return;
            }
            try (AgentServer server =
                    new AgentServer(
                            new Agent() {
                                @Override
                                public AgentDefinition definition() {
                                    return definition;
                                }

                                @Override
                                public Finder finder() {
                                    return directory -> findings(said);
                                }
                            },
                            Path.of(args[2]))) {
                System.out.println("ready " + args[2]);
                System.out.flush();
                server.serve();
            }
        }

        /** Serves only what an agent built before {@code Find} serves, answering its version. */
        private static void older(
                final AgentDefinition definition, final String socket, final boolean errors) {
            try (VarlinkServer server =
                    new VarlinkServer(Path.of(socket), AgentProtocol.INTERFACE)) {
                server.method(
                        "Describe",
                        (parameters, replies) ->
                                replies.last(
                                        Map.of(
                                                "protocolVersion",
                                                AgentProtocol.LOWEST,
                                                "definition",
                                                AgentDefinitionJson.write(definition))));
                if (errors) {
                    server.method(
                            "Find",
                            (parameters, replies) -> {
                                throw new org.fuin.porelo.wire.varlink.VarlinkException(
                                        "org.fuin.porelo.Agent1.Failed",
                                        java.util.Map.of("reason", "value-that-must-not-be-told"));
                            });
                }
                System.out.println("ready " + socket);
                System.out.flush();
                server.run();
            }
        }

        private static List<Finding> findings(final String said) throws IOException {
            if (said.equals("hangs")) {
                try {
                    Thread.sleep(java.time.Duration.ofHours(1));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            final List<Finding> findings = new ArrayList<>();
            for (final String one : said.split(";")) {
                final String[] fields = one.split(",", -1);
                findings.add(
                        new Finding(
                                Finding.What.SIGN_IN,
                                fields[4].isEmpty() ? null : fields[4],
                                Finding.SignedIn.valueOf(fields[0]),
                                fields[1].isEmpty() ? null : Boolean.valueOf(fields[1]),
                                fields[2].isEmpty() ? null : fields[2],
                                fields[3].isEmpty() ? null : Finding.Kind.valueOf(fields[3])));
            }
            return findings;
        }
    }

    static void provider(final PoreloContext context, final String name, final String dialect)
            throws IOException {
        final Path providers =
                Files.createDirectories(context.paths().xdg().data().resolve("providers"));
        Files.writeString(
                providers.resolve(name + ".yaml"),
                """
                name: %s
                upstream: https://%s.example.com
                dialects: { %s: "" }
                auth_header: { _default: Authorization }
                auth_prefix: { _default: "Bearer " }
                """
                        .formatted(name, name, dialect));
    }

    static void installed(final PoreloContext context, final String name, final String finds)
            throws IOException {
        final Path agents = Files.createDirectories(context.paths().xdg().data().resolve("agents"));
        final Path definition = agents.resolve(name + ".yaml");
        Files.writeString(
                definition,
                """
                name: %s
                binary: %s-cli
                config_dir: "~/.%s"
                git_identity: { name: Reporting, email: reporting@example.com }
                headless: { prompt_flag: "-p" }
                allowed_domains: [agent.example.com]
                provider:
                  dialect: spoken
                  default: alpha
                  endpoint: url
                  base_url_env: REPORTING_BASE_URL
                  token_env: { _default: REPORTING_KEY }
                """
                        .formatted(name, name, name));
        Files.writeString(Path.of(definition + ".finds"), finds);
        final Path executable = agents.resolve(AgentDirectory.PREFIX + name);
        Files.writeString(
                executable,
                "#!/bin/sh\nexec '"
                        + Path.of(System.getProperty("java.home"), "bin", "java")
                        + "' -cp '"
                        + System.getProperty("java.class.path")
                        + "' '"
                        + Reporting.class.getName()
                        + "' '"
                        + definition
                        + "' \"$@\"\n");
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
    }

    private static PoreloContext withProviders(final Path dir) throws IOException {
        final PoreloContext context = context(dir);
        provider(context, "alpha", "spoken");
        provider(context, "beta", "spoken");
        provider(context, "gamma", "another");
        return context;
    }

    static final String SIGNED_IN_BETA = "SIGNED_IN,true,beta,SUBSCRIPTION,/home/x/.tool/in";

    static final String NOT_SIGNED_IN = "NOT_SIGNED_IN,,,,/home/x/.tool/empty";

    private static final String CANNOT_SAY = "CANNOT_SAY,,,,";

    @Test
    void theOneFoundSignedInIsChosenAndItsProviderAndWhereAreKept(@TempDir final Path dir)
            throws Exception {

        final PoreloContext context = withProviders(dir);
        installed(context, "first", NOT_SIGNED_IN);
        installed(context, "second", SIGNED_IN_BETA);

        try (InstalledAgents agents = context.agents()) {
            final AgentChoice.Choice choice =
                    AgentChoice.considerAgent(context, agents, null, null);

            assertThat(choice.refusal()).isNull();
            assertThat(choice.agent().name()).isEqualTo("second");
            assertThat(choice.source()).isEqualTo(AgentChoice.Source.SIGNED_IN);
            assertThat(choice.provider())
                    .as("the provider it is signed in to, not its default")
                    .isEqualTo("beta");
            assertThat(choice.chosen().path()).isEqualTo("/home/x/.tool/in");
        }
    }

    @Test
    void cannotSayAndNotSignedInStillRefuseForSomebodyToChoose(@TempDir final Path dir)
            throws Exception {

        // Taking the one that said "not signed in" would read the other's silence as a no.
        final PoreloContext context = withProviders(dir);
        installed(context, "first", CANNOT_SAY);
        installed(context, "second", NOT_SIGNED_IN);

        try (InstalledAgents agents = context.agents()) {
            final AgentChoice.Choice choice =
                    AgentChoice.considerAgent(context, agents, null, null);

            assertThat(choice.refusal()).isEqualTo(AgentChoice.AgentRefusal.SEVERAL_AGENTS);
            assertThat(choice.agent()).isNull();
        }
    }

    @Test
    void severalSignedInRefuseAndAreNamedFirst(@TempDir final Path dir) throws Exception {

        final PoreloContext context = withProviders(dir);
        installed(context, "first", NOT_SIGNED_IN);
        installed(context, "second", SIGNED_IN_BETA);
        installed(context, "third", SIGNED_IN_BETA);

        try (InstalledAgents agents = context.agents()) {
            final AgentChoice.Choice choice =
                    AgentChoice.considerAgent(context, agents, null, null);

            assertThat(choice.refusal()).isEqualTo(AgentChoice.AgentRefusal.SEVERAL_AGENTS);
            assertThat(choice.signedIn()).containsExactly("second", "third");
            assertThat(choice.found().stream().map(AgentChoice.Found::agent))
                    .containsExactly("second", "third", "first");
        }
    }

    @Test
    void aNameWinsOverWhatIsFoundAndNobodyIsAsked(@TempDir final Path dir) throws Exception {

        final PoreloContext context = withProviders(dir);
        installed(context, "first", NOT_SIGNED_IN);
        installed(context, "second", SIGNED_IN_BETA);

        try (InstalledAgents agents = context.agents()) {
            assertThat(AgentChoice.considerAgent(context, agents, "first", null).agent().name())
                    .isEqualTo("first");
            final AgentChoice.Choice byProject =
                    AgentChoice.considerAgent(context, agents, null, workedWith("first"));
            assertThat(byProject.agent().name()).isEqualTo("first");
            assertThat(byProject.found()).as("asked only when no name decides").isEmpty();
            assertThat(byProject.provider()).as("so it gives no provider").isNull();
        }
    }

    @Test
    void anAgentBuiltBeforeFindIsChosenAsBeforeAndNeverReadAsNotSignedIn(@TempDir final Path dir)
            throws Exception {

        final PoreloContext context = withProviders(dir);
        installed(context, "only", "older");

        try (InstalledAgents agents = context.agents()) {
            final AgentChoice.Choice choice =
                    AgentChoice.considerAgent(context, agents, null, null);

            assertThat(choice.agent().name()).isEqualTo("only");
            assertThat(choice.source()).isEqualTo(AgentChoice.Source.MACHINE);
            assertThat(choice.chosen().state()).isEqualTo(Finding.SignedIn.CANNOT_SAY);
            assertThat(choice.chosen().warning()).as("an old agent is no failure").isNull();
            assertThat(choice.line(dir)).contains("cannot say");
        }
    }

    @Test
    void aFindThatFailsIsCannotSayWithAWarningAndStopsNoStart(@TempDir final Path dir)
            throws Exception {

        final PoreloContext context = withProviders(dir);
        installed(context, "only", "errors");

        try (InstalledAgents agents = context.agents()) {
            final AgentChoice.Choice choice =
                    AgentChoice.considerAgent(context, agents, null, null);

            assertThat(choice.agent().name()).isEqualTo("only");
            assertThat(choice.chosen().state()).isEqualTo(Finding.SignedIn.CANNOT_SAY);
            assertThat(choice.chosen().warning())
                    .as("said, without what the agent said, which may hold a value")
                    .contains("'only'")
                    .doesNotContain("value-that-must-not-be-told");
        }
    }

    @Test
    void aFindThatHangsIsCannotSayAndTheAgentStillAnswersTheRestOfTheStart(@TempDir final Path dir)
            throws Exception {

        // A dead network home hangs a read: a start that used to work must go on, and goes on over
        // the agent's one connection.
        final PoreloContext context = withProviders(dir);
        installed(context, "only", "hangs");

        try (InstalledAgents agents = context.agents()) {
            final AgentChoice.Choice choice =
                    AgentChoice.considerAgent(context, agents, null, null);

            assertThat(choice.chosen().state()).isEqualTo(Finding.SignedIn.CANNOT_SAY);
            assertThat(choice.chosen().warning()).isNotNull();
            assertThat(choice.agent().extractCredential(dir))
                    .as("the same agent answers after it")
                    .isEmpty();
        }
    }

    @Test
    void aProviderTheAgentCannotDriveIsDroppedAndTheAgentStaysSignedIn(@TempDir final Path dir)
            throws Exception {

        final PoreloContext context = withProviders(dir);
        installed(context, "wrong-dialect", "SIGNED_IN,true,gamma,API_KEY,/home/x/a");
        installed(context, "unknown", "NOT_SIGNED_IN,,../elsewhere,,");

        try (InstalledAgents agents = context.agents()) {
            final AgentChoice.Choice choice =
                    AgentChoice.considerAgent(context, agents, null, null);

            assertThat(choice.agent().name()).isEqualTo("wrong-dialect");
            assertThat(choice.provider()).as("kept out of the decision").isNull();
            assertThat(choice.foundBy("unknown").provider())
                    .as("said without a sign-in, it gives nothing")
                    .isNull();
        }
    }

    @Test
    void aProviderNotDeclaredHereIsDroppedAndTheAgentStaysSignedIn(@TempDir final Path dir)
            throws Exception {

        final PoreloContext context = withProviders(dir);
        installed(context, "only", "SIGNED_IN,true,../elsewhere,API_KEY,/home/x/a");

        try (InstalledAgents agents = context.agents()) {
            final AgentChoice.Choice choice =
                    AgentChoice.considerAgent(context, agents, null, null);

            assertThat(choice.source()).isEqualTo(AgentChoice.Source.SIGNED_IN);
            assertThat(choice.provider()).as("it comes from outside, and names nothing").isNull();
        }
    }

    @Test
    void aPickedAgentIsSaidWithTheProviderFoundSinceThePersonDidNotPickThat(@TempDir final Path dir)
            throws Exception {

        final PoreloContext context = withProviders(dir);
        installed(context, "first", SIGNED_IN_BETA);
        installed(context, "second", "SIGNED_IN,true,alpha,API_KEY,/home/x/a");

        try (InstalledAgents agents = context.agents()) {
            final AgentChoice.Choice choice =
                    AgentChoice.considerAgent(context, agents, null, null);

            assertThat(choice.picked("first"))
                    .isEqualTo("agent first, provider beta (subscription)");
            assertThat(choice.picked("nobody")).as("nothing to add for one not found").isNull();
        }
    }

    @Test
    void theProviderComesFromTheFirstRouteTheImportCanTake(@TempDir final Path dir)
            throws Exception {

        final PoreloContext context = withProviders(dir);
        installed(
                context,
                "only",
                "SIGNED_IN,false,alpha,API_KEY,/home/x/settings;" + SIGNED_IN_BETA);

        try (InstalledAgents agents = context.agents()) {
            final AgentChoice.Choice choice =
                    AgentChoice.considerAgent(context, agents, null, null);

            assertThat(choice.provider()).isEqualTo("beta");
            assertThat(choice.chosen().importable()).isTrue();
        }
    }

    @Test
    void signedInOnlyByARouteTheImportCannotTakeCountsAndOffersNoImport(@TempDir final Path dir)
            throws Exception {

        final PoreloContext context = withProviders(dir);
        installed(context, "first", NOT_SIGNED_IN);
        installed(context, "second", "SIGNED_IN,false,,,/home/x/settings");

        try (InstalledAgents agents = context.agents()) {
            final AgentChoice.Choice choice =
                    AgentChoice.considerAgent(context, agents, null, null);

            assertThat(choice.agent().name()).isEqualTo("second");
            assertThat(AgentChoice.importCommand(choice.chosen(), "alpha", "alpha")).isEmpty();
        }
    }

    private static AgentChoice.Found signedIn(final @Nullable String provider) {
        return new AgentChoice.Found(
                "tool", Finding.SignedIn.SIGNED_IN, true, provider, null, null, List.of(), null);
    }

    @Test
    void anImportIsOfferedOnlyWhereItStoresWhatTheTaskLooksFor() {

        // Offered otherwise, it would store a credential under a name the task then does not find.
        assertThat(AgentChoice.importCommand(signedIn("beta"), "beta", "alpha"))
                .containsExactly("vault", "import", "tool", "--provider", "beta");
        assertThat(AgentChoice.importCommand(signedIn("beta"), "alpha", "alpha")).isEmpty();
        assertThat(AgentChoice.importCommand(signedIn(null), "alpha", "alpha"))
                .containsExactly("vault", "import", "tool");
        assertThat(AgentChoice.importCommand(signedIn(null), "beta", "alpha")).isEmpty();
    }

    @Test
    void theStartChecksAnswerNamesTheChoiceAndUsesTheProviderFound(@TempDir final Path dir)
            throws Exception {

        final PoreloContext context = withProviders(dir);
        installed(context, "first", NOT_SIGNED_IN);
        installed(context, "second", SIGNED_IN_BETA);

        final Map<String, Object> answer =
                StartCheck.check(context, (Path) null, null, null, null).asMap();

        assertThat(answer)
                .containsEntry("agent", "second")
                .containsEntry("provider", "beta")
                .containsEntry("agentSource", "SIGNED_IN")
                .containsEntry("agentFoundIn", "/home/x/.tool/in");
    }

    @Test
    void aProviderFlagWinsOverTheProviderFound(@TempDir final Path dir) throws Exception {

        final PoreloContext context = withProviders(dir);
        installed(context, "only", SIGNED_IN_BETA);

        assertThat(StartCheck.check(context, (Path) null, null, "alpha", null).provider())
                .isEqualTo("alpha");
    }

    @Test
    void severalSignedInReachAClientAsAList(@TempDir final Path dir) throws Exception {

        final PoreloContext context = withProviders(dir);
        installed(context, "first", SIGNED_IN_BETA);
        installed(context, "second", SIGNED_IN_BETA);

        final StartCheck.Result result = StartCheck.check(context, (Path) null, null, null, null);

        assertThat(result.outcome()).isEqualTo(StartCheck.Outcome.SEVERAL_AGENTS);
        assertThat(result.signedIn()).containsExactly("first", "second");
    }
}
