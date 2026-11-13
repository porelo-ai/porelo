package org.fuin.porelo.core.project.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.StringReader;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.SnippetFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every kind of fault in a project file is refused, at its line and its key: a file Porelo cannot
 * use whole must never run in part, and a person must find what to change without guessing.
 */
class ProjectFileFaultsTest {

    /** Lines 1 to 5; every case below adds from line 6 or rewrites the whole. */
    private static final String BASE =
            """
            project:
              name: shop
              security_class: guarded
            image:
              from: ubuntu:24.04
            """;

    private static final String ONLINE =
            """
            project:
              name: shop
              security_class: online
              central: git@github.com:you/shop.git
            image:
              from: ubuntu:24.04
            """;

    private static final String OFFLINE =
            """
            project:
              name: shop
              security_class: offline
            image:
              from: ubuntu:24.04
            """;

    private static List<String> faults(final String yaml) {
        final ProjectFileException refused =
                catchThrowableOfType(
                        ProjectFileException.class,
                        () -> ProjectFileReader.read(new StringReader(yaml), "project.yml", null));
        assertThat(refused).as("the refusal of:\n" + yaml).isNotNull();
        return refused.faults();
    }

    private static Arguments refused(
            final String yaml, final int line, final String path, final String what) {
        return Arguments.of(yaml, line, path, what);
    }

    static Stream<Arguments> faultsOfAProjectFile() {
        return Stream.of(
                // Keys that are not settings where they stand.
                refused(
                        BASE + "mail:\n  upstream: x\n",
                        7,
                        "mail.upstream",
                        "belongs under 'project:'"),
                refused(BASE + "flavour: x\n", 6, "flavour", "may be older than the file"),
                refused(
                        BASE.replace("  from:", "  base_image:"),
                        5,
                        "image.base_image",
                        "write 'from' instead"),
                refused(
                        BASE + "  package_sources: [\"http://a.example/\"]\n",
                        6,
                        "image.package_sources",
                        "belongs under 'build:'"),
                refused(
                        BASE + "repositories:\n  web:\n    limits:\n      memory: 1g\n",
                        8,
                        "repositories.web.limits",
                        "the project's alone"),
                refused(
                        BASE + "unread_work_may_leave: true\n",
                        6,
                        "unread_work_may_leave",
                        "is not a setting"),
                // Shapes.
                refused(
                        BASE + "egress:\n  - maven\n",
                        6,
                        "egress",
                        "must be a mapping, not a list"),
                refused(BASE + "egress:\n  sets: maven\n", 7, "egress.sets", "must be a list"),
                refused(
                        BASE.replace("name: shop", "name:\n    first: shop"),
                        2,
                        "project.name",
                        "must be a single value, not a mapping"),
                refused(BASE + "limits:\n  pids: many\n", 7, "limits.pids", "whole number"),
                refused(BASE + "limits:\n  pids: \"2048\"\n", 7, "limits.pids", "whole number"),
                refused(
                        BASE + "parameters:\n  type:\n    required: maybe\n",
                        8,
                        "parameters.type.required",
                        "true or false"),
                refused(
                        BASE + "egress:\n  sets: [maven]\n  sets: [go]\n",
                        8,
                        "egress.sets",
                        "written twice"),
                // Required.
                refused("image:\n  from: ubuntu:24.04\n", 1, "project", "'project' is required"),
                refused(
                        "project:\n  name: shop\n  security_class: guarded\n",
                        1,
                        "image",
                        "'image' is required"),
                refused(
                        BASE.replace("  name: shop\n", ""),
                        1,
                        "project.name",
                        "'project.name' is required"),
                refused(
                        BASE.replace("  security_class: guarded\n", ""),
                        1,
                        "project.security_class",
                        "is required"),
                refused(
                        BASE.replace("  from: ubuntu:24.04\n", "  run: RUN true\n"),
                        4,
                        "image.from",
                        "'image.from' is required"),
                // The project.
                refused(
                        BASE.replace("name: shop", "name: Shop"),
                        2,
                        "project.name",
                        "Invalid project name"),
                refused(
                        BASE.replace("guarded", "careful"),
                        3,
                        "project.security_class",
                        "expected one of: offline, guarded, online"),
                refused(
                        BASE + "  nickname: \"two words\"\n".replace("  nickname", "nickname"),
                        6,
                        "nickname",
                        "belongs under 'project:'"),
                refused(
                        BASE.replace(
                                "  security_class: guarded\n",
                                "  security_class: guarded\n  nickname: 1st\n"),
                        4,
                        "project.nickname",
                        "a letter first"),
                refused(
                        BASE.replace(
                                "  security_class: guarded\n",
                                "  security_class: guarded\n  review: maybe\n"),
                        4,
                        "project.review",
                        "is required or optional"),
                refused(
                        BASE.replace(
                                "  security_class: guarded\n",
                                "  security_class: guarded\n  central: \"--upload-pack=touch /tmp/x\"\n"),
                        4,
                        "project.central",
                        "as an option or a command"),
                refused(
                        BASE.replace("from: ubuntu:24.04", "from: \"-v /:/host ubuntu\""),
                        5,
                        "image.from",
                        "as an option or a command"),
                // The class.
                refused(
                        BASE + "builds:\n  forge: github\n  credential: gh\n",
                        6,
                        "builds",
                        "'builds' is for online projects only"),
                refused(
                        ONLINE + "builds:\n  forge: Git Hub\n  credential: gh\n",
                        8,
                        "builds.forge",
                        "must name a build reader"),
                refused(
                        ONLINE + "builds:\n  forge: github\n",
                        7,
                        "builds.credential",
                        "must name the vault entry"),
                refused(
                        ONLINE + "builds:\n  forge: github\n  credential: gh\n  logs: some\n",
                        10,
                        "builds.logs",
                        "is one of failure, all"),
                refused(
                        ONLINE
                                + "builds:\n  forge: github\n  credential: gh\n  api: http://forge.example/\n",
                        10,
                        "builds.api",
                        "must be an https:// address"),
                refused(
                        ONLINE.replace("  central: git@github.com:you/shop.git\n", ""),
                        1,
                        "project.central",
                        "is online, so it needs its central address"),
                refused(
                        ONLINE + "repositories:\n  web: {}\n",
                        8,
                        "repositories.web.central",
                        "needs its central address"),
                refused(
                        OFFLINE.replace(
                                "  security_class: offline\n",
                                "  security_class: offline\n  central: git@github.com:you/shop.git\n"),
                        4,
                        "project.central",
                        "is offline, so it names no central address"),
                refused(
                        OFFLINE
                                + "repositories:\n  web:\n    central: git@github.com:you/web.git\n",
                        8,
                        "repositories.web.central",
                        "names no central address either"),
                refused(
                        OFFLINE + "egress:\n  sets: [maven]\n",
                        6,
                        "egress",
                        "can declare no egress beyond this host"),
                refused(
                        OFFLINE + "credentials:\n  search: brave-search\n",
                        6,
                        "credentials",
                        "can declare no 'credentials'"),
                // Repositories.
                refused(
                        BASE
                                + "repositories:\n  web:\n    issues: [B]\n  api:\n    issues: [P, B]\n",
                        10,
                        "repositories.api.issues",
                        "is named by both 'web' and 'api'"),
                refused(
                        BASE + "repositories:\n  web:\n    issues: [b]\n",
                        8,
                        "repositories.web.issues",
                        "an issue prefix is one or more capital letters"),
                refused(
                        BASE
                                + "repositories:\n  web:\n    nickname: Pi\n  api:\n    nickname: PI\n",
                        9,
                        "repositories.api",
                        "would both go by 'Agent PI'"),
                refused(
                        BASE + "repositories:\n  Planner: {}\n",
                        7,
                        "repositories.Planner",
                        "Invalid repository name"),
                refused(
                        BASE + "repositories:\n  shop: {}\n",
                        7,
                        "repositories.shop",
                        "write project.name as 'shop-project'"),
                refused(
                        BASE + "repositories:\n  web:\n    agent: Some_Agent\n",
                        8,
                        "repositories.web.agent",
                        "an agent is named as 'porelo agents' lists it"),
                refused(
                        BASE + "repositories:\n  web:\n    review: maybe\n",
                        8,
                        "repositories.web.review",
                        "is required or optional"),
                refused(
                        BASE + "repositories:\n  web:\n    maturity: ancient\n",
                        8,
                        "repositories.web.maturity",
                        "is one of crazy-chicken, early-bird, stable, deprecated"),
                refused(
                        BASE + "repositories:\n  web:\n    central: \"ext::sh -c touch% /tmp/x\"\n",
                        8,
                        "repositories.web.central",
                        "as an option or a command"),
                refused(
                        BASE + "repositories:\n  web:\n    branch:\n      template: \"{task}\"\n",
                        8,
                        "repositories.web.branch",
                        "names a template and no pattern"),
                // Egress.
                refused(
                        BASE
                                + "egress:\n  domains: [nexus.example]\n  host_services: [nexus.example]\n",
                        8,
                        "egress.host_services",
                        "appears in both 'egress.domains' and 'egress.host_services'"),
                refused(
                        BASE + "egress:\n  sets: [Maven]\n",
                        7,
                        "egress.sets",
                        "Invalid egress set name"),
                refused(
                        BASE + "egress:\n  domains: [\"a.example\\nserver=/x/\"]\n",
                        7,
                        "egress.domains",
                        "expected a host name"),
                refused(
                        BASE + "egress:\n  refused: [localhost]\n",
                        7,
                        "egress.refused",
                        "expected a host name"),
                // Limits.
                refused(BASE + "limits:\n  pids: 0\n", 7, "limits.pids", "leaves nothing to run"),
                refused(
                        BASE + "limits:\n  hand_in: lots\n",
                        7,
                        "limits.hand_in",
                        "must be a size such as 64m"),
                refused(
                        BASE + "limits:\n  hand_in: \"0\"\n",
                        7,
                        "limits.hand_in",
                        "lets no file in"),
                refused(
                        BASE + "limits:\n  hand_in: 99999999999999999g\n",
                        7,
                        "limits.hand_in",
                        "is too large"),
                refused(BASE + "limits:\n  subagents: -1\n", 7, "limits.subagents", "of 0 or more"),
                refused(BASE + "limits:\n  ideas: -1\n", 7, "limits.ideas", "of 0 or more"),
                // Mail.
                refused(
                        BASE + "mail:\n  peers:\n    \"a b\":\n      address: \"matrix:\"\n",
                        8,
                        "mail.peers.a b",
                        "a peer name is letters"),
                refused(
                        BASE + "mail:\n  peers:\n    reviewer:\n      trust: external\n",
                        8,
                        "mail.peers.reviewer.address",
                        "is required"),
                refused(
                        BASE + "mail:\n  peers:\n    reviewer:\n      address: nowhere\n",
                        9,
                        "mail.peers.reviewer.address",
                        "'<transport>:<address>'"),
                refused(
                        BASE
                                + "mail:\n  peers:\n    reviewer:\n      address: \"matrix:\"\n      trust: friend\n",
                        10,
                        "mail.peers.reviewer.trust",
                        "is one of vouched, external"),
                refused(
                        BASE
                                + "mail:\n  peers:\n    reviewer:\n      address: \"matrix:\"\n      per_day: 0\n",
                        10,
                        "mail.peers.reviewer.per_day",
                        "at least 1"),
                refused(
                        BASE
                                + "mail:\n  peers:\n    reviewer:\n      address: \"matrix:\"\n      mode: maybe\n",
                        10,
                        "mail.peers.reviewer.mode",
                        "is one of allow, deny, off, prompt"),
                refused(
                        BASE + "mail:\n  rules:\n    room: loud\n",
                        8,
                        "mail.rules.room",
                        "is one of allow"),
                refused(
                        BASE + "mail:\n  outgoing_filter: quiet\n",
                        7,
                        "mail.outgoing_filter",
                        "is one of blocking, reporting"),
                refused(
                        BASE + "mail:\n  transports:\n    \"../x\": {}\n",
                        8,
                        "mail.transports.../x",
                        "is not a transport's name"),
                refused(
                        BASE
                                + "mail:\n  transports:\n    matrix:\n      homeserver:\n        url: x\n",
                        9,
                        // At its key's line, not the line of what stands under it.
                        "mail.transports.matrix.homeserver",
                        "must be a single value"),
                // Parameters.
                refused(
                        BASE + "parameters:\n  Type: {}\n",
                        7,
                        "parameters.Type",
                        "is no parameter name"),
                refused(
                        BASE + "parameters:\n  type:\n    values: [a]\n    pattern: a\n",
                        7,
                        "parameters.type",
                        "names both 'values' and 'pattern'"),
                refused(
                        BASE + "parameters:\n  type:\n    values: []\n",
                        8,
                        "parameters.type.values",
                        "names no value"),
                refused(
                        BASE + "parameters:\n  type:\n    pattern: \"[a\"\n",
                        8,
                        "parameters.type.pattern",
                        "is no regular expression"),
                refused(
                        BASE + "parameters:\n  type:\n    env: PATH\n",
                        8,
                        "parameters.type.env",
                        "a variable name Porelo does not set itself"),
                refused(
                        BASE + "parameters:\n  a:\n    env: X\n  b:\n    env: X\n",
                        10,
                        "parameters.b.env",
                        "both name the variable X"),
                refused(
                        BASE + "parameters:\n  type:\n    values: [a]\n    default: b\n",
                        9,
                        "parameters.type.default",
                        "is not allowed by its own declaration"),
                // Branch.
                refused(
                        BASE + "branch:\n  template: \"{task}\"\n",
                        6,
                        "branch",
                        "names a template and no pattern"),
                refused(
                        BASE + "branch:\n  pattern: \"(\"\n",
                        7,
                        "branch.pattern",
                        "is not a regular expression"),
                refused(
                        BASE + "branch:\n  template: \"{task\"\n  pattern: x\n",
                        7,
                        "branch.template",
                        "holds a brace that is not a {placeholder}"),
                // Credentials and sources.
                refused(
                        BASE + "credentials:\n  Search: brave\n",
                        7,
                        "credentials.Search",
                        "both are names"),
                // Both would reach the task as PORELO_TOKEN_A_B, one token under the other's name.
                refused(
                        BASE + "credentials:\n  a-b: brave\n  a_b: brave\n",
                        8,
                        "credentials.a_b",
                        "become one variable"),
                refused(
                        BASE + "build:\n  package_sources: []\n",
                        7,
                        "build.package_sources",
                        "with nothing in it"),
                refused(
                        BASE + "build:\n  package_sources: [\"ftp://mirror.example/\"]\n",
                        7,
                        "build.package_sources",
                        "is not an http or https URL"),
                // The image.
                refused(BASE + "  env:\n    PATH: /tmp\n", 7, "image.env.PATH", "is set by Porelo"),
                refused(
                        BASE + "  env:\n    PORELO_TOKEN: x\n",
                        7,
                        "image.env.PORELO_TOKEN",
                        "is set by Porelo"),
                refused(
                        BASE + "  env:\n    \"A-B\": x\n",
                        7,
                        "image.env.A-B",
                        "is no variable name"),
                refused(
                        BASE + "  env:\n    A: \"x\\nRUN rm -rf /\"\n",
                        7,
                        "image.env.A",
                        "holds a control character"),
                refused(
                        BASE + "  run: RUN true\n  run_file: part\n",
                        6,
                        "image.run",
                        "'image.run' and 'image.run_file' are mutually exclusive"),
                refused(
                        BASE + "  run_file: ../../.ssh/id_ed25519\n",
                        6,
                        "image.run_file",
                        "must name a file of the repository"),
                refused(
                        BASE + "  run_file: /etc/shadow\n",
                        6,
                        "image.run_file",
                        "must name a file of the repository"));
    }

    @ParameterizedTest
    @MethodSource("faultsOfAProjectFile")
    void aFaultIsRefusedAtItsLineAndItsKey(
            final String yaml, final int line, final String path, final String what) {

        assertThat(faults(yaml))
                .as("the faults of:\n" + yaml)
                .anySatisfy(
                        fault ->
                                assertThat(fault)
                                        .startsWith("project.yml:" + line + ": ")
                                        .contains("'" + path + "'")
                                        .contains(what));
    }

    static Stream<Arguments> instructionsRunRefuses() {
        return Stream.of(
                Arguments.of("FROM", 2),
                Arguments.of("COPY", 3),
                Arguments.of("ADD", 4),
                Arguments.of("USER", 5),
                Arguments.of("ENTRYPOINT", 6),
                Arguments.of("HEALTHCHECK", 7));
    }

    @ParameterizedTest
    @MethodSource("instructionsRunRefuses")
    void runRefusesEveryInstructionButRunArgAndEnvWithItsLine(
            final String instruction, final int line) {

        // A FROM, COPY or USER would change what the image is, not what is in it.
        final String run =
                """
                ARG VERSION=1
                FROM alpine
                COPY . /x
                ADD https://example.org/x /x
                USER root
                ENTRYPOINT ["/bin/sh"]
                HEALTHCHECK CMD true
                """;
        final String yaml = BASE + "  run: |\n" + run.indent(4);

        assertThat(faults(yaml))
                .as("the faults of:\n" + yaml)
                .anySatisfy(
                        fault ->
                                assertThat(fault)
                                        .startsWith("project.yml:6: ")
                                        .contains(
                                                "'image.run' line "
                                                        + line
                                                        + ": "
                                                        + instruction
                                                        + " is not allowed"));
    }

    private static final String DIGEST = "sha256:" + "0123456789abcdef".repeat(4);

    @Test
    void runTakesACopyFromAnImagePinnedByItsDigest() {

        // It copies from an image fixed before the line is written, never from the repository.
        final String yaml =
                BASE
                        + "  run: |\n"
                        + ("COPY --from=ghcr.io/matrix-construct/tuwunel@"
                                        + DIGEST
                                        + " /usr/bin/tuwunel /usr/local/bin/tuwunel\n"
                                        + "copy --chmod=0755 \\\n"
                                        + "    --from=registry.example:5000/tools/jq:1.7@"
                                        + DIGEST
                                        + " \\\n"
                                        + "    /usr/bin/jq /usr/local/bin/jq\n")
                                .indent(4);

        assertThat(
                        ProjectFileReader.read(new StringReader(yaml), "project.yml", null)
                                .image()
                                .run())
                .as("the run lines taken from:\n" + yaml)
                .contains("COPY --from=ghcr.io/matrix-construct/tuwunel@" + DIGEST)
                .contains("/usr/bin/jq /usr/local/bin/jq");
    }

    static Stream<Arguments> copiesRunRefuses() {
        return Stream.of(
                Arguments.of("no image", "COPY . /src"),
                Arguments.of("an image by its tag", "COPY --from=alpine:3.20 /bin/busybox /x"),
                Arguments.of("a stage", "COPY --from=builder /out /x"),
                Arguments.of("a short digest", "COPY --from=alpine@sha256:0123abcd /x /x"),
                Arguments.of(
                        "a digest in capitals",
                        "COPY --from=alpine@" + DIGEST.toUpperCase(Locale.ROOT) + " /x /x"),
                Arguments.of("a variable", "COPY --from=${IMAGE} /x /x"),
                Arguments.of(
                        "two images", "COPY --from=alpine@" + DIGEST + " --from=builder /x /x"),
                Arguments.of(
                        "a here-document",
                        "COPY --from=alpine@" + DIGEST + " <<EOF /x\nanything\nEOF"),
                Arguments.of(
                        "the image on a line it does not continue to",
                        "COPY /x /x\nRUN echo --from=alpine@" + DIGEST));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("copiesRunRefuses")
    void runRefusesACopyFromAnythingButOnePinnedImage(final String what, final String run) {

        final String yaml = BASE + "  run: |\n" + run.indent(4);

        assertThat(faults(yaml))
                .as("the faults of " + what + ":\n" + yaml)
                .anySatisfy(
                        fault ->
                                assertThat(fault)
                                        .startsWith("project.yml:6: ")
                                        .contains(
                                                "'image.run' line 1: COPY is not allowed but as"
                                                        + " one COPY --from=<image>@sha256:<digest>"));
    }

    @Test
    void runTakesContinuedLinesCommentsAndAHereDocument() {

        final String yaml =
                BASE
                        + """
                          run: |
                            # syntax comment
                            ARG VERSION=1
                            ENV TOOL=/opt/tool
                            RUN apt-get update \\
                                && apt-get install -y jq
                            RUN <<EOF
                            COPY is a word of the script here
                            EOF
                        """;

        assertThat(
                        ProjectFileReader.read(new StringReader(yaml), "project.yml", null)
                                .image()
                                .run())
                .as("the run lines taken")
                .contains("RUN apt-get update");
    }

    @Test
    void theOperatorsFileWithAnImageAtTwoPlacesIsRefused() {

        // A generated file wrote the image at the top and under the repository, and the entry under
        // the repository beat the repository's own porelo.yml. A repository's image is its own
        // file's.
        final String yaml =
                """
                project:
                  name: "porelo-project"
                  security_class: "guarded"
                repositories:
                  porelo:
                    upstream: "git@github.com:you/porelo.git"
                    image:
                      from: "ubuntu:24.04"
                image:
                  from: "ubuntu:24.04"
                """;

        assertThat(faults(yaml))
                .as("the faults of the file with two images")
                .singleElement()
                .satisfies(
                        fault ->
                                assertThat(fault)
                                        .startsWith("project.yml:7: ")
                                        .contains("'repositories.porelo.image'")
                                        .contains("in its own porelo.yml"));
    }

    @Test
    void everyFaultOfAFileIsSaidAtOnce() {

        final String yaml = BASE.replace("name: shop", "name: Shop") + "limits:\n  pids: 0\n";

        assertThat(faults(yaml))
                .as("both faults")
                .hasSize(2)
                .anySatisfy(fault -> assertThat(fault).startsWith("project.yml:2: "))
                .anySatisfy(fault -> assertThat(fault).startsWith("project.yml:7: "));
    }

    @Test
    void twoNicknamesAlikeOnceTheirSpacesAreCollapsedGoByOneName() {

        // A reader that collapses spaces, as most displays do, would see one name for both.
        final String yaml =
                BASE
                        + "repositories:\n  web:\n    nickname: Sea Lion\n  api:\n    nickname: sea  lion\n";

        assertThat(faults(yaml))
                .as("the double space refused, and both repositories said to go by one name")
                .anySatisfy(fault -> assertThat(fault).contains("repositories.api.nickname"))
                .anySatisfy(
                        fault ->
                                assertThat(fault)
                                        .contains("'web' and 'api'")
                                        .contains("would both go by"));
    }

    @Test
    void aRunFileIsReadThroughTheRepositorysOwnFilesAndChecked() {

        final SnippetFiles files =
                (named, origin) -> {
                    assertThat(named).as("the file asked for").isEqualTo("Containerfile.part");
                    return "RUN true\nUSER root\n";
                };
        final String yaml = BASE + "  run_file: Containerfile.part\n";

        assertThatThrownBy(
                        () -> ProjectFileReader.read(new StringReader(yaml), "project.yml", files))
                .as("a run file holding USER")
                .isInstanceOf(ProjectFileException.class)
                .hasMessageContaining(
                        "project.yml:6: 'image.run_file' line 2: USER is not allowed");
    }

    @Test
    void aRunFileTheRepositoryDoesNotHoldIsRefused() {

        final SnippetFiles files =
                (named, origin) -> {
                    throw new ProjectException(origin + ": no image snippet at " + named);
                };
        final String yaml = BASE + "  run_file: missing.part\n";

        assertThatThrownBy(
                        () -> ProjectFileReader.read(new StringReader(yaml), "project.yml", files))
                .as("a run file that is not there")
                .isInstanceOf(ProjectFileException.class)
                .hasMessageContaining("project.yml:6: 'image.run_file' cannot be used");
    }

    @Test
    void textThatIsNotAMappingOrNotYamlIsRefusedWithItsLine() {

        assertThatThrownBy(() -> ProjectFileReader.read(new StringReader(""), "project.yml", null))
                .as("an empty file")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("is empty or is not a YAML mapping");
        assertThatThrownBy(
                        () ->
                                ProjectFileReader.read(
                                        new StringReader("project:\n  name: [a\n"),
                                        "project.yml",
                                        null))
                .as("text that is not YAML")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("Cannot parse project.yml at line");
        assertThat(faults("- project\n"))
                .as("a list as the whole file")
                .singleElement()
                .satisfies(fault -> assertThat(fault).startsWith("project.yml:1: "));
    }

    @Test
    void aTagNamingAClassIsNeverConstructed() {

        // Nothing is constructed from a tag: the node tree is bound by hand, onto fixed types, and
        // a global tag is refused before that.
        final String global =
                BASE.replace("name: shop", "name: !!javax.script.ScriptEngineManager [x]");
        final String local = BASE.replace("name: shop", "name: !thing [x]");

        assertThatThrownBy(
                        () -> ProjectFileReader.read(new StringReader(global), "project.yml", null))
                .as("a class named by a global tag")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("Cannot parse project.yml at line 2");
        assertThat(faults(local))
                .as("a value with a local tag")
                .singleElement()
                .satisfies(
                        fault ->
                                assertThat(fault)
                                        .startsWith("project.yml:2: ")
                                        .contains("'project.name'"));
    }

    @Test
    void aRepositorysFileHoldsOnlyItsImage() {

        assertThatThrownBy(
                        () ->
                                RepositoryFileReader.read(
                                        new StringReader(
                                                "image:\n  from: ubuntu:24.04\negress:\n  sets: [maven]\n"),
                                        "porelo.yml",
                                        null))
                .as("egress in a repository's file")
                .isInstanceOf(ProjectFileException.class)
                .hasMessageContaining(
                        "porelo.yml:3: 'egress' cannot be set here; only image may be set here");
        assertThatThrownBy(
                        () ->
                                RepositoryFileReader.read(
                                        new StringReader("image:\n  snippet: RUN true\n"),
                                        "porelo.yml",
                                        null))
                .as("a key of the image this Porelo does not know")
                .isInstanceOf(ProjectFileException.class)
                .hasMessageContaining(
                        "porelo.yml:2: 'image.snippet' is not a setting; write 'run' instead");
        assertThatThrownBy(
                        () ->
                                RepositoryFileReader.read(
                                        new StringReader("image:\n  env:\n    HOME: /root\n"),
                                        "porelo.yml",
                                        null))
                .as("a variable Porelo sets")
                .isInstanceOf(ProjectFileException.class)
                .hasMessageContaining(
                        "porelo.yml:3: 'image.env.HOME' is set by Porelo to /home/agent");
        assertThatThrownBy(
                        () ->
                                RepositoryFileReader.read(
                                        new StringReader("{}\n"), "porelo.yml", null))
                .as("a repository's file without an image")
                .isInstanceOf(ProjectFileException.class)
                .hasMessageContaining("porelo.yml:1: 'image' is required");
    }
}
