package org.fuin.porelo.packagecheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("documents")
class DebianPublishingTest {

    @TempDir Path root;

    @Test
    void uploadsEachDebToItsOwnPoolDirectory() throws IOException {
        // Uploading the host package under porelo would disagree with the index and installer.
        final Commands.Result result = run("Publish the packages", "");

        assertThat(result.ok()).as(result.output()).isTrue();
        for (final String name : List.of("porelo", "porelo-host")) {
            assertThat(result.output().lines())
                    .as("each deb carries the indexing properties at its own path")
                    .contains(
                            "UPLOAD dist/dist-deb/target/"
                                    + name
                                    + "_1_amd64.deb porelo-dist-deb/pool/main/p/"
                                    + name
                                    + "/ deb.distribution=releases;deb.component=main;deb.architecture=amd64");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"porelo", "porelo-host"})
    void refusesToReplaceEitherPublishedReleaseBeforeUploadingAnything(String published)
            throws IOException {
        final Commands.Result result = run("Publish the packages", filename(published));

        assertThat(result.ok())
                .as("an existing release is immutable: %s", result.output())
                .isFalse();
        assertThat(result.output())
                .as("the release check precedes every upload")
                .contains("::error::" + published + " is published already")
                .doesNotContain("UPLOAD ");
    }

    @Test
    void acceptsAnIndexThatListsBothUploadedFiles() throws IOException {
        final Commands.Result result =
                run(
                        "The packages are indexed, not merely stored",
                        filename("porelo") + filename("porelo-host"));

        assertThat(result.ok()).as(result.output()).isTrue();
        assertThat(result.output())
                .as("each actual filename is awaited")
                .contains(filename("porelo").strip(), filename("porelo-host").strip());
    }

    @ParameterizedTest
    @ValueSource(strings = {"porelo", "porelo-host"})
    void failsWhenEitherUploadedFileIsAbsentFromTheIndex(String missing) throws IOException {
        final String present = missing.equals("porelo") ? "porelo-host" : "porelo";
        final Commands.Result result =
                run("The packages are indexed, not merely stored", filename(present));

        assertThat(result.ok()).as("stored is insufficient: %s", result.output()).isFalse();
        assertThat(result.output())
                .as("the missing package's own filename is diagnosed")
                .contains("never listed '" + filename(missing).strip() + "'");
    }

    private static String filename(String name) {
        return "Filename: pool/main/p/" + name + "/" + name + "_1_amd64.deb\n";
    }

    private Commands.Result run(String step, String index) throws IOException {
        final Path packages = Files.createDirectories(root.resolve("dist/dist-deb/target"));
        Files.createFile(packages.resolve("porelo_1_amd64.deb"));
        Files.createFile(packages.resolve("porelo-host_1_amd64.deb"));
        Files.writeString(root.resolve("Packages"), index);
        final Path scripts = Files.createDirectories(root.resolve(".github/scripts"));
        Files.writeString(
                scripts.resolve("artifactory.sh"),
                """
                art_login() { :; }
                art_put() { printf 'UPLOAD %s %s %s\\n' "$1" "$2" "${3-}"; }
                """);
        // Stand-ins keep this a shell contract test: no network, real package, upload or delay.
        final String standins =
                """
                cd "$1" || exit 1
                CHANNEL=releases JF_URL=https://example.invalid
                curl() {
                    case "$*" in
                        *repomd.xml*) echo primary ;;
                        *) cat Packages ;;
                    esac
                }
                dpkg-deb() {
                    case "$3" in
                        Package) name=${2##*/}; printf '%s\\n' "${name%%_*}" ;;
                        Version) echo 1 ;;
                        *) return 1 ;;
                    esac
                }
                sleep() { :; }
                """;
        return Commands.runMerged(
                List.of("bash", "-c", standins + step(step), "publish-check", root.toString()));
    }

    private static String step(String name) throws IOException {
        final Path repository = Path.of("").toAbsolutePath().getParent().getParent();
        final String workflow = Files.readString(repository.resolve(".github/workflows/build.yml"));
        final String fromStep =
                workflow.substring(workflow.indexOf("            - name: " + name + "\n"));
        final String fromScript =
                fromStep.substring(
                        fromStep.indexOf("              run: |\n")
                                + "              run: |\n".length());
        return fromScript
                .lines()
                .takeWhile(line -> line.isBlank() || line.startsWith("                  "))
                .map(line -> line.isBlank() ? "" : line.substring(18))
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
    }
}
