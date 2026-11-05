package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.junit.jupiter.api.Test;

/** Tests for {@link ProjectReader}. */
class ProjectReaderTest {

    private static final String MINIMAL =
            """
            project:
              name: "uc"
              description: "Ultimate Container"
              security_class: "online"
              upstream: "git@github.com:example/uc.git"

            image:
              from: "ubuntu:24.04"
            """;

    private static Project read(String yaml) {
        return ProjectReader.read(new StringReader(yaml), "test.yml");
    }

    @Test
    void readsAProjectDefinition() {

        final Project project = read(MINIMAL);

        assertThat(project.name()).isEqualTo("uc");
        assertThat(project.description()).isEqualTo("Ultimate Container");
        assertThat(project.securityClass()).isEqualTo(SecurityClass.ONLINE);
        assertThat(project.baseImage()).isEqualTo("ubuntu:24.04");
        assertThat(project.imageName()).isEqualTo("porelo/uc");
        assertThat(project.upstream()).isEqualTo("git@github.com:example/uc.git");
    }

    @Test
    void anOnlineProjectWithoutAnUpstreamIsRefused() {

        // Online puts the agent's own remote at the upstream, so without one the class means
        // nothing. Caught here rather than as a git error inside a container.
        assertThatThrownBy(
                        () ->
                                read(
                                        """
                project:
                  name: "uc"
                  security_class: "online"
                image:
                  from: "ubuntu:24.04"
                """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("is online, so it needs an upstream");
    }

    @Test
    void theOtherClassesDoNotNeedAnUpstream() {

        // A guarded project keeps the gate in the path, and an approved push names the upstream on
        // the command line. Requiring it here would make the common case depend on the rare.
        assertThat(
                        read("""
                project:
                  name: "uc"
                  security_class: "guarded"
                image:
                  from: "ubuntu:24.04"
                """)
                                .upstream())
                .isNull();
    }

    @Test
    void refusesSectionsItDoesNotKnowWithTheirLines() {

        // A section this Porelo does not know is no setting at all, so the file is refused rather
        // than read as if it applied.
        assertThatThrownBy(
                        () ->
                                read(
                                        MINIMAL
                                                + """
                git:
                  upstream_url: "https://example.com/x.git"
                """))
                .as("an unknown section is refused at its line")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining(":" + (MINIMAL.lines().count() + 1) + ":")
                .hasMessageContaining("'git' is not a setting this Porelo knows");
    }

    @Test
    void descriptionIsOptional() {

        final Project project =
                read(
                        """
                project:
                  name: "uc"
                  security_class: "offline"
                image:
                  from: "ubuntu:24.04"
                """);

        assertThat(project.description()).isEmpty();
    }

    @Test
    void rejectsAMissingSection() {

        assertThatThrownBy(() -> read("project:\n  name: uc\n  security_class: offline\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'image' is required");
    }

    @Test
    void rejectsAMissingKey() {

        assertThatThrownBy(
                        () ->
                                read(
                                        """
                project:
                  name: "uc"
                image:
                  from: "ubuntu:24.04"
                """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("project.security_class");
    }

    @Test
    void rejectsAnUnknownSecurityClass() {

        assertThatThrownBy(() -> read(MINIMAL.replace("online", "yolo")))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("offline, guarded, online");
    }

    @Test
    void rejectsANameThatWillNotSurviveTheLayersBelow() {

        assertThatThrownBy(() -> read(MINIMAL.replace("\"uc\"", "\"Ultimate Container\"")))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("Invalid project name");
    }

    @Test
    void rejectsDuplicateKeys() {

        // A duplicate silently wins in most YAML readers. In a security policy that is a bug.
        assertThatThrownBy(
                        () ->
                                read(
                                        MINIMAL.replace(
                                                "image:\n  from", "image:\n  from: \"a\"\n  from")))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'image.from' is written twice");
    }

    @Test
    void reportsTheOriginOfTheProblem() {

        assertThatThrownBy(
                        () -> ProjectReader.read(new StringReader("[]"), "/etc/porelo/project.yml"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("/etc/porelo/project.yml");
    }

    @Test
    void readsDeclaredLimits() {
        final Project project =
                ProjectReader.read(
                        new java.io.StringReader(
                                """
                project:
                  name: "demo"
                  security_class: "guarded"
                limits:
                  memory: "2g"
                  cpus: "1.5"
                  pids: 512
                image:
                  from: "ubuntu:24.04"
                """),
                        "test");

        assertThat(project.limits().memory()).isEqualTo("2g");
        assertThat(project.limits().cpus()).isEqualTo("1.5");
        assertThat(project.limits().pids()).isEqualTo(512);
    }

    @Test
    void readsTheLargestFileATaskTakesAsASize() {
        final Project project =
                ProjectReader.read(
                        new java.io.StringReader(
                                """
                project:
                  name: "demo"
                  security_class: "guarded"
                limits:
                  hand_in: "1g"
                image:
                  from: "ubuntu:24.04"
                """),
                        "test");

        assertThat(project.limits().handIn()).isEqualTo(1024L * 1024 * 1024);
        assertThat(project.limits().memory())
                .as("the other keys keep their defaults")
                .isEqualTo("8g");
    }

    @Test
    void refusesAHandInLimitThatIsNoSize() {
        assertThatThrownBy(
                        () ->
                                ProjectReader.read(
                                        new java.io.StringReader(
                                                """
                project:
                  name: "demo"
                  security_class: "guarded"
                limits:
                  hand_in: "lots"
                image:
                  from: "ubuntu:24.04"
                """),
                                        "test"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("limits.hand_in")
                .hasMessageContaining("lots");
    }

    @Test
    void keepsTheDefaultCpuLimitWhenOnlyOtherLimitsAreDeclared() {
        final Project project = read(MINIMAL + "\nlimits:\n  memory: 2g\n");
        assertThat(project.limits().cpus())
                .as("declaring memory alone must not remove the CPU bound")
                .isEqualTo("4");
    }

    @Test
    void fallsBackToTheDefaultLimits() {

        // The case that matters: a project file written before limits existed must still be
        // limited, not silently unlimited.
        final Project project =
                ProjectReader.read(
                        new java.io.StringReader(
                                """
                project:
                  name: "demo"
                  security_class: "guarded"
                image:
                  from: "ubuntu:24.04"
                """),
                        "test");

        assertThat(project.limits()).isEqualTo(Limits.defaults());
    }

    @Test
    void readsWhereAptShouldFetchFrom() {

        final Project project =
                read(
                        """
                project:
                  name: "uc"
                  security_class: "guarded"
                image:
                  from: "ubuntu:24.04"
                build:
                  package_sources:
                    - "http://mirror.corp.example/ubuntu/"
                    - "http://archive.ubuntu.com/ubuntu/"
                """);

        assertThat(project.declaresPackageSources()).isTrue();
        assertThat(project.effectivePackageSources())
                .containsExactly(
                        "http://mirror.corp.example/ubuntu/", "http://archive.ubuntu.com/ubuntu/");
    }

    @Test
    void readsTheImagesFromRunAndEnv() {

        // What a task's image is built from, as root: read wrongly, the image is not the one asked
        // for and nothing says so.
        final Project project =
                read(
                        """
                project:
                  name: "uc"
                  security_class: "guarded"
                image:
                  from: "debian:13"
                  env:
                    JAVA_HOME: "/opt/java"
                    TOOL_HOME: "/opt/tool"
                  run: |
                    RUN apt-get install -y make
                """);

        assertThat(project.baseImage()).as("from").isEqualTo("debian:13");
        assertThat(project.imageSnippet()).as("run").isEqualTo("RUN apt-get install -y make\n");
        assertThat(project.imageEnv())
                .as("env, in the order written")
                .containsExactly(
                        java.util.Map.entry("JAVA_HOME", "/opt/java"),
                        java.util.Map.entry("TOOL_HOME", "/opt/tool"));
        assertThat(ImageSource.of(project).env())
                .as("the project's variables are a source of the task's image")
                .isEqualTo(project.imageEnv());
    }

    @Test
    void namingNoSourcesIsNotTheSameAsNamingTheDefault() {

        // The distinction is load-bearing: a base image with no apt is refused for a project that
        // asked for sources and left alone for one that asked for nothing.
        final Project project = read(MINIMAL);

        assertThat(project.declaresPackageSources()).isFalse();
        assertThat(project.effectivePackageSources())
                .isEqualTo(Project.DEFAULT_PACKAGE_SOURCES)
                .allMatch(
                        source -> source.startsWith("http://"),
                        "Ubuntu's mirrors serve no TLS; an https default never answers");
    }

    @Test
    void refusesSourcesThatCannotBeFetchedFrom() {

        assertThatThrownBy(
                        () ->
                                read(
                                        """
                project:
                  name: "uc"
                  security_class: "guarded"
                image:
                  from: "ubuntu:24.04"
                build:
                  package_sources: ["ftp://mirror.corp.example/ubuntu/"]
                """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("is not an http or https URL");

        assertThatThrownBy(
                        () ->
                                read(
                                        """
                project:
                  name: "uc"
                  security_class: "guarded"
                image:
                  from: "ubuntu:24.04"
                build:
                  package_sources: []
                """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("Remove the key to use the default");
    }

    @Test
    void readsWhatTheProjectMayReach() {

        final Project project =
                read(
                        """
                project:
                  name: "uc"
                  security_class: "guarded"
                egress:
                  sets: [maven, nodejs]
                  domains: ["nexus.corp.example"]
                image:
                  from: "ubuntu:24.04"
                """);

        assertThat(project.egress().sets()).containsExactly("maven", "nodejs");
        assertThat(project.egress().domains()).containsExactly("nexus.corp.example");
    }

    @Test
    void aProjectThatDeclaresNothingReachesNothing() {

        // Absence is deny, not the generous default. A project file that says nothing about egress
        // must not acquire reachability because a later release added a set.
        assertThat(read(MINIMAL).egress().isEmpty()).isTrue();
    }

    @Test
    void anOfflineProjectCannotDeclareEgress() {

        assertThatThrownBy(
                        () ->
                                read(
                                        """
                project:
                  name: "uc"
                  security_class: "offline"
                egress:
                  sets: [maven]
                image:
                  from: "ubuntu:24.04"
                """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("offline")
                .hasMessageContaining("no egress");
    }

    @Test
    void refusesADomainThatWouldWriteResolverConfiguration() {

        // Each domain becomes a 'server=' and an 'nftset=' line in the container's dnsmasq
        // configuration. A value carrying a newline is not a bad host name - it is extra
        // configuration, written by whoever can edit this file.
        final String[] hostile = {
            "evil.example\\nserver=/#/8.8.8.8",
            "evil.example/../..",
            "*.example.com",
            "http://evil.example",
            "evil example",
            "UPPER.example.com"
        };

        for (final String domain : hostile) {
            assertThatThrownBy(
                            () ->
                                    read(
                                            """
                    project:
                      name: "uc"
                      security_class: "guarded"
                    egress:
                      domains: ["%s"]
                    image:
                      from: "ubuntu:24.04"
                    """
                                                    .formatted(domain)))
                    .as("domain %s", domain)
                    .isInstanceOf(ProjectException.class)
                    .hasMessageContaining("in 'egress.domains', expected a host name");
        }
    }

    @Test
    void refusesASetNameThatIsNotOne() {

        assertThatThrownBy(
                        () ->
                                read(
                                        """
                project:
                  name: "uc"
                  security_class: "guarded"
                egress:
                  sets: ["../../etc/passwd"]
                image:
                  from: "ubuntu:24.04"
                """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("Invalid egress set name");
    }

    @Test
    void refusesAScalarWhereAListWasMeant() {

        // 'sets: maven' is a plausible typo for 'sets: [maven]', and silently accepting it would
        // make the file's meaning depend on YAML rather than on what it says.
        assertThatThrownBy(
                        () ->
                                read(
                                        """
                project:
                  name: "uc"
                  security_class: "guarded"
                egress:
                  sets: maven
                image:
                  from: "ubuntu:24.04"
                """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("must be a list");
    }

    @Test
    void letsAProjectOptOutOfTheMemoryCapOnPurpose() {
        final Project project =
                ProjectReader.read(
                        new java.io.StringReader(
                                """
                project:
                  name: "demo"
                  security_class: "guarded"
                limits:
                  memory: "none"
                image:
                  from: "ubuntu:24.04"
                """),
                        "test");

        assertThat(project.limits().memory()).isNull();
        assertThat(project.limits().pids()).isEqualTo(2048);
    }

    @Test
    void readsTheCredentialsEveryTaskOfTheProjectHolds() {
        final Project project =
                read(
                        MINIMAL
                                + """
                credentials:
                  search: brave-search
                  forge-app: forge
                """);

        assertThat(project.credentials())
                .containsExactly(
                        java.util.Map.entry("search", "brave-search"),
                        java.util.Map.entry("forge-app", "forge"));
        assertThat(read(MINIMAL).credentials()).isEmpty();
    }

    @Test
    void anOfflineProjectNamesNoUpstream() {
        // The host cloned an offline project's mirror from it on the first start: offline then
        // meant "offline after the first start". It never connects; a repository comes in as a
        // file.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                read(
                                        MINIMAL.replace(
                                                "security_class: \"online\"",
                                                "security_class: \"offline\"")))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("is offline, so it names no upstream")
                .hasMessageContaining("porelo gate restore");
    }

    @Test
    void anOfflineProjectsFurtherRepositoryNamesNoUpstream() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                read(
                                        MINIMAL.replace(
                                                                "security_class: \"online\"",
                                                                "security_class: \"offline\"")
                                                        .replace(
                                                                "  upstream: \"git@github.com:example/uc.git\"\n",
                                                                "")
                                                + """
                repositories:
                  backend:
                    upstream: "git@github.com:example/backend.git"
                """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("backend")
                .hasMessageContaining("names no upstream");
    }

    @Test
    void anOfflineProjectWithoutAnUpstreamIsRead() {
        assertThat(
                        read(MINIMAL.replace(
                                                "security_class: \"online\"",
                                                "security_class: \"offline\"")
                                        .replace(
                                                "  upstream: \"git@github.com:example/uc.git\"\n",
                                                ""))
                                .upstream())
                .isNull();
    }

    @Test
    void anOfflineProjectDeclaresNoCredentials() {
        // The broker would reach the destination for the task, and an offline project reaches
        // nothing.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                read(
                                        MINIMAL.replace(
                                                                "security_class: \"online\"",
                                                                "security_class: \"offline\"")
                                                        .replace(
                                                                "  upstream: \"git@github.com:example/uc.git\"\n",
                                                                "")
                                                + """
                credentials:
                  search: brave-search
                """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("can declare no 'credentials'");
    }

    @Test
    void anUpstreamOrImageThatGitOrPodmanWouldTakeForAnOptionIsRefused() {

        // An upstream reached 'git fetch' as it was written: '--upload-pack=<command>' ran that
        // command on the host at the next task start, and 'ext::' does the same by design.
        for (final String upstream :
                java.util.List.of(
                        "--upload-pack=touch /tmp/owned;false",
                        "ext::sh -c id",
                        "fd::3",
                        "git@host:repo\n--upload-pack=x")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () ->
                                    read(
                                            MINIMAL.replace(
                                                    "git@github.com:example/uc.git",
                                                    upstream.replace("\n", "\\n"))))
                    .as(upstream)
                    .isInstanceOf(ProjectException.class)
                    .hasMessageContaining("upstream");
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> read(MINIMAL.replace("ubuntu:24.04", "--privileged")))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("image.from");
    }

    @Test
    void aRepositorysUpstreamIsHeldToTheSameRule() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new Repository("work", "--upload-pack=x"))
                .isInstanceOf(ProjectException.class);
        assertThat(new Repository("work", "https://github.com/o/r.git").upstream())
                .isEqualTo("https://github.com/o/r.git");
        assertThat(new Repository("work", "/srv/git/r.git").upstream()).isEqualTo("/srv/git/r.git");
    }

    @Test
    void aSnippetFileIsReadFromTheRepositoryAndNowhereElse(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {

        // Resolved as written, an absolute path, '../' or a link read any file the person can - a
        // key included - into the image the agent runs in, or into an error on their screen.
        final java.nio.file.Path repository =
                java.nio.file.Files.createDirectories(dir.resolve("repository"));
        java.nio.file.Files.writeString(dir.resolve("outside.txt"), "RUN echo secret");
        java.nio.file.Files.writeString(repository.resolve("snippet.txt"), "RUN echo inside");
        java.nio.file.Files.createSymbolicLink(
                repository.resolve("link.txt"), dir.resolve("outside.txt"));
        final java.nio.file.Path file = repository.resolve("project.yml");
        for (final String named :
                java.util.List.of(
                        "../outside.txt", dir.resolve("outside.txt").toString(), "link.txt")) {
            java.nio.file.Files.writeString(
                    file,
                    MINIMAL.replace(
                            "from: \"ubuntu:24.04\"",
                            "from: \"ubuntu:24.04\"\n  run_file: \"" + named + "\""));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> ProjectReader.read(file))
                    .as(named)
                    .isInstanceOf(ProjectException.class)
                    .hasMessageContaining("run_file");
        }
        java.nio.file.Files.writeString(
                file,
                MINIMAL.replace(
                        "from: \"ubuntu:24.04\"",
                        "from: \"ubuntu:24.04\"\n  run_file: \"snippet.txt\""));
        assertThat(ProjectReader.read(file).imageSnippet()).isEqualTo("RUN echo inside");
    }

    private static Project reviewed(final String project, final String repository) {
        return ProjectReader.read(
                new java.io.StringReader(
                        """
                        project:
                          name: "demo"
                          security_class: "online"
                          upstream: "git@forge.example:demo/demo.git"
                        %s
                        image:
                          from: "ubuntu:24.04"
                        repositories:
                          first:
                            upstream: "git@forge.example:demo/first.git"
                        %s
                        """
                                .formatted(project, repository)),
                "project.yml");
    }

    @Test
    void aFileThatSaysNothingRequiresAReviewAndNamesNoCentralSource() {
        final Project read = reviewed("", "");

        assertThat(read.review()).isEqualTo("required");
        assertThat(read.repositories().getFirst().review()).isEqualTo("required");
        assertThat(read.repositories().getFirst().central()).isNull();
    }

    @Test
    void aReviewAndACentralSourceAreRead() {
        final Project read =
                reviewed(
                        "  review: \"optional\"",
                        "    review: \"optional\"\n"
                                + "    central: \"git@github.com:acme/first.git\"");

        assertThat(read.review()).isEqualTo("optional");
        assertThat(read.repositories().getFirst().review()).isEqualTo("optional");
        assertThat(read.repositories().getFirst().central())
                .isEqualTo("git@github.com:acme/first.git");
    }

    @Test
    void anyOtherReviewIsRefusedNamingTheKey() {
        assertThatThrownBy(() -> reviewed("  review: \"sometimes\"", ""))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("project.review");
        assertThatThrownBy(() -> reviewed("", "    review: \"sometimes\""))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("repositories.first.review");
    }

    @Test
    void aCentralSourceEndingInAnotherRepositoryIsRefused() {
        assertThatThrownBy(() -> reviewed("", "    central: \"git@github.com:acme/other.git\""))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("repositories.first.central");
    }
}
