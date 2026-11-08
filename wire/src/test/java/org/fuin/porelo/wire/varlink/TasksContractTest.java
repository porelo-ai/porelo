package org.fuin.porelo.wire.varlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The daemon's contract, carried by porelo-wire for a client outside porelo to read. */
class TasksContractTest {

    private static final String FOLDER = "varlink/org.fuin.porelo.Tasks1/";

    @TempDir Path dir;

    private static String read(final String name) throws IOException {
        try (InputStream in = TasksContractTest.class.getResourceAsStream("/" + FOLDER + name)) {
            assertThat(in).as("%s on porelo-wire's class path", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** A class path of its own: each map one folder, each entry a file in the contract's place. */
    @SafeVarargs
    private ClassLoader loader(final Map<String, String>... roots) throws IOException {
        final URL[] urls = new URL[roots.length];
        for (int i = 0; i < roots.length; i++) {
            final Path root = dir.resolve("root" + i);
            for (final Map.Entry<String, String> file : roots[i].entrySet()) {
                final Path path = root.resolve(FOLDER + file.getKey());
                Files.createDirectories(path.getParent());
                Files.writeString(path, file.getValue());
            }
            urls[i] = root.toUri().toURL();
        }
        return new URLClassLoader(urls, null);
    }

    @Test
    void everyPartTheListNamesIsCarriedAndTheHeaderComesFirst() throws IOException {
        final List<String> parts = TasksContract.parts();

        assertThat(parts).first().isEqualTo("00-header");
        for (final String part : parts) {
            assertThat(read(part + ".varlink")).as(part).isNotBlank();
        }
        assertThat(TasksContract.description())
                .startsWith(read("00-header.varlink"))
                .contains("interface org.fuin.porelo.Tasks1");
    }

    @Test
    void everyPartCarriedIsNamedOnceInTheListAndEndsItsLastLine() throws IOException {
        // A part left off the list would ship and never be served; a part named twice would serve
        // the interface twice.
        final Path folder;
        try {
            folder =
                    Path.of(TasksContractTest.class.getResource("/" + FOLDER + "parts").toURI())
                            .getParent();
        } catch (final java.net.URISyntaxException ex) {
            throw new IllegalStateException(ex);
        }
        final TreeSet<String> carried;
        try (Stream<Path> files = Files.list(folder)) {
            carried =
                    files.map(file -> file.getFileName().toString())
                            .filter(name -> name.endsWith(".varlink"))
                            .map(name -> name.substring(0, name.length() - ".varlink".length()))
                            .collect(Collectors.toCollection(TreeSet::new));
        }
        final List<String> parts = TasksContract.parts();

        assertThat(parts).doesNotHaveDuplicates();
        assertThat(new TreeSet<>(parts)).isEqualTo(carried);
        for (final String part : parts) {
            assertThat(read(part + ".varlink")).as(part).endsWith("\n");
        }
    }

    @Test
    void aCommentAfterAPartsNameIsNotPartOfIt() throws IOException {
        final ClassLoader loader =
                loader(
                        Map.of(
                                "parts", "# the parts\n00-header   # the interface\n10-a\n",
                                "00-header.varlink", "interface org.fuin.porelo.Tasks1\n",
                                "10-a.varlink", "method A() -> ()\n"));

        assertThat(TasksContract.parts(loader)).containsExactly("00-header", "10-a");
        assertThat(TasksContract.description(loader))
                .isEqualTo("interface org.fuin.porelo.Tasks1\nmethod A() -> ()\n");
    }

    @Test
    void aListNamingAPartTwiceIsRefused() throws IOException {
        final ClassLoader loader =
                loader(
                        Map.of(
                                "parts", "00-header\n10-a\n00-header\n",
                                "00-header.varlink", "interface org.fuin.porelo.Tasks1\n",
                                "10-a.varlink", "method A() -> ()\n"));

        assertThatThrownBy(() -> TasksContract.parts(loader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'00-header'")
                .hasMessageContaining("twice");
    }

    @Test
    void aPartWithoutItsLastLinesEndStillEndsBeforeTheNextPart() throws IOException {
        final ClassLoader loader =
                loader(
                        Map.of(
                                "parts", "00-header\n10-a\n",
                                "00-header.varlink", "interface org.fuin.porelo.Tasks1",
                                "10-a.varlink", "method A() -> ()\n"));

        assertThat(TasksContract.description(loader))
                .isEqualTo("interface org.fuin.porelo.Tasks1\nmethod A() -> ()\n");
    }

    @Test
    void aContractCarriedTwiceOnTheClassPathIsRefused() throws IOException {
        // An old porelo-wire, or parts left in a module's classes from before they moved, would
        // otherwise be served in place of the current contract without anybody seeing it.
        final Map<String, String> contract =
                Map.of(
                        "parts", "00-header\n",
                        "00-header.varlink", "interface org.fuin.porelo.Tasks1\n");
        final ClassLoader loader =
                loader(Map.of("00-header.varlink", "interface org.fuin.porelo.Tasks1\n"), contract);

        assertThatThrownBy(() -> TasksContract.description(loader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("00-header.varlink")
                .hasMessageContaining("more than once");
    }
}
