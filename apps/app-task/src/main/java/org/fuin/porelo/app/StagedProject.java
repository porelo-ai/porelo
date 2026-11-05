package org.fuin.porelo.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.SnippetFiles;
import org.fuin.porelo.vault.RegistryText;
import org.fuin.porelo.wire.Directories;
import org.jspecify.annotations.Nullable;

/**
 * A checkout's project file and the files it names, read once onto this machine.
 *
 * <p><strong>Parsed, shown and hashed from here and nowhere else.</strong> A checkout's files can
 * change between any two reads of them, so nothing after the one read looks at them again, and a
 * yes moves this very copy into the project's place, which is what a start reads.
 *
 * <p>What is here is owner-only, lies under the configuration directory so a move into the
 * project's place is one rename, and goes when the question is over.
 */
final class StagedProject {

    /** The project's file in a staged copy, and in the project's place. */
    static final String FILE_NAME = "project.yml";

    private final Path place;

    private final Set<String> named;

    private StagedProject(final Path place, final Set<String> named) {
        this.place = place;
        this.named = named;
    }

    /** Why a checkout's files cannot be staged, in words safe to show. */
    static final class Unreadable extends Exception {

        private static final long serialVersionUID = 1L;

        Unreadable(final String words) {
            super(words);
        }
    }

    /**
     * Stages a file already written by Porelo.
     *
     * @param projects The directory of projects' places.
     * @param text The project file.
     * @return The copy.
     * @throws IOException When it cannot be written.
     */
    static StagedProject of(final Path projects, final String text) throws IOException {
        final Path place = place(projects);
        Files.writeString(place.resolve(FILE_NAME), text, StandardCharsets.UTF_8);
        return new StagedProject(place, Set.of());
    }

    /**
     * Reads a checkout's project file and each file it names, once.
     *
     * @param projects The directory of projects' places.
     * @param file The checkout's project file.
     * @return The copy.
     * @throws IOException When the copy cannot be written.
     * @throws Unreadable When a file is a link, lies outside, is too large or does not read.
     */
    static StagedProject read(final Path projects, final Path file) throws IOException, Unreadable {
        final byte[] bytes = once(file);
        if (bytes == null) {
            throw new Unreadable(
                    RegistryText.printable(file.toString())
                            + " is a link, is no file, or is too large to be read");
        }
        final Path place = place(projects);
        final Set<String> named = new LinkedHashSet<>();
        final Path base = java.util.Objects.requireNonNull(file.getParent(), "a file has a place");
        try {
            Files.write(place.resolve(FILE_NAME), bytes);
            try {
                // Only to learn what it names: each is read here once, and from here on is the
                // staged file. What counts is the parse of the copy, below.
                ProjectReader.read(
                        new StringReader(new String(bytes, StandardCharsets.UTF_8)),
                        FILE_NAME,
                        (name, origin) -> stageNamed(base, place, name, named));
                ProjectReader.read(place.resolve(FILE_NAME));
            } catch (ProjectException ex) {
                throw new Unreadable(
                        "it does not read as a project file: "
                                + RegistryText.printable(String.valueOf(ex.getMessage())));
            } catch (UncheckedIOException ex) {
                throw ex.getCause();
            }
            return new StagedProject(place, named);
        } catch (IOException | Unreadable | RuntimeException ex) {
            remove(place);
            throw ex;
        }
    }

    private static String stageNamed(
            final Path base, final Path place, final String name, final Set<String> named) {
        final Path relative = Path.of(name).normalize();
        if (relative.isAbsolute() || relative.startsWith("..") || relative.toString().isEmpty()) {
            throw new ProjectException(
                    "a file it names must lie beside it in the checkout, not " + name);
        }
        Path walked = base;
        for (final Path part : relative) {
            walked = walked.resolve(part);
            if (Files.isSymbolicLink(walked)) {
                throw new ProjectException("a file it names goes through a link: " + name);
            }
        }
        final byte[] bytes;
        try {
            bytes = once(walked);
            if (bytes == null) {
                throw new ProjectException("a file it names is no file or is too large: " + name);
            }
            final Path staged = place.resolve(relative);
            Directories.create(staged.getParent());
            Files.write(staged, bytes);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        named.add(relative.toString());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** A link, a directory or a very large file is no file; the limit holds for what is read. */
    private static byte @Nullable [] once(final Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            final byte[] bytes = input.readNBytes(ProjectApprovals.MOST_BYTES + 1);
            return bytes.length > ProjectApprovals.MOST_BYTES ? null : bytes;
        }
    }

    private static Path place(final Path projects) throws IOException {
        Directories.create(projects);
        return Files.createTempDirectory(projects, ".staging-");
    }

    /**
     * Returns the hash a yes names.
     *
     * @return It.
     * @throws IOException When the copy cannot be read.
     */
    String hash() throws IOException {
        return ProjectApprovals.hash(place, files());
    }

    /**
     * Tells whether a project's place holds just what this copy does. What else lies there, such as
     * a person's override, is no part of what was approved.
     *
     * @param target The project's place.
     * @return {@code true} when each of this copy's files is there with the same bytes.
     */
    boolean sameAs(final Path target) {
        try {
            return ProjectApprovals.hash(target, files()).equals(hash());
        } catch (IOException ex) {
            return false;
        }
    }

    private Set<String> files() {
        final Set<String> files = new LinkedHashSet<>(named);
        files.add(FILE_NAME);
        return files;
    }

    /**
     * Returns the project's name as a start will read it, or {@code null} when it is no text: the
     * reader takes {@code name: 123}, and a start by name refuses it.
     *
     * @return The name it declares, or {@code null}.
     * @throws IOException When the copy cannot be read.
     */
    @Nullable String declaredName() throws IOException {
        try (Reader reader = Files.newBufferedReader(place.resolve(FILE_NAME))) {
            return ProjectReader.name(reader, FILE_NAME);
        } catch (ProjectException ex) {
            return null;
        }
    }

    /**
     * Returns the project the copy describes.
     *
     * @return It.
     */
    Project project() {
        return ProjectReader.read(place.resolve(FILE_NAME));
    }

    /**
     * Returns every line of the copy, each file under its name, safe for a terminal.
     *
     * @return The project file, then each file it names.
     * @throws IOException When the copy cannot be read.
     */
    String text() throws IOException {
        final StringBuilder text = new StringBuilder(lines(place.resolve(FILE_NAME)));
        for (final String name : named) {
            text.append("--- ")
                    .append(RegistryText.printable(name))
                    .append(" ---\n")
                    .append(lines(place.resolve(name)));
        }
        return text.toString();
    }

    private static String lines(final Path file) throws IOException {
        // Bytes that are not text must not end the read of what a person is asked about.
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .lines()
                .map(line -> RegistryText.printable(line) + "\n")
                .collect(Collectors.joining());
    }

    /** The last step of a yes: when it fails, the yes did not happen. */
    @FunctionalInterface
    interface Commit {

        /**
         * Runs.
         *
         * @throws IOException When it cannot.
         */
        void run() throws IOException;
    }

    /**
     * Makes this copy what a project's place holds, and runs what must happen with it. The files
     * the old copy was approved with go; what a person put beside them, such as an override, stays.
     *
     * @param target The project's place.
     * @param commit What happens with it.
     * @throws IOException When either fails; what was there then stays there.
     */
    void install(final Path target, final Commit commit) throws IOException {
        final Path root =
                java.util.Objects.requireNonNull(target.getParent(), "a place has a root");
        final Path aside = root.resolve(".replaced-" + UUID.randomUUID());
        final boolean replacing = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (replacing) {
            keepWhatAPersonPut(target);
            Files.move(target, aside, StandardCopyOption.ATOMIC_MOVE);
        }
        try {
            Files.move(place, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            if (replacing) {
                Files.move(aside, target, StandardCopyOption.ATOMIC_MOVE);
            }
            throw ex;
        }
        try {
            commit.run();
        } catch (IOException ex) {
            Files.move(target, place, StandardCopyOption.ATOMIC_MOVE);
            if (replacing) {
                Files.move(aside, target, StandardCopyOption.ATOMIC_MOVE);
            }
            throw ex;
        }
        if (replacing) {
            remove(aside);
        }
    }

    private void keepWhatAPersonPut(final Path target) throws IOException {
        final Set<Path> approved = approvedIn(target);
        final List<Path> all;
        try (Stream<Path> walk = Files.walk(target)) {
            all = walk.filter(each -> !Files.isDirectory(each, LinkOption.NOFOLLOW_LINKS)).toList();
        }
        for (final Path each : all) {
            final Path relative = target.relativize(each);
            final Path here = place.resolve(relative);
            if (!approved.contains(relative) && !Files.exists(here, LinkOption.NOFOLLOW_LINKS)) {
                Directories.create(java.util.Objects.requireNonNull(here.getParent(), "a parent"));
                Files.copy(
                        each, here, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    /** What an approved copy holds by its own file's word. */
    private static Set<Path> approvedIn(final Path target) {
        final Set<Path> approved = new LinkedHashSet<>(Set.of(Path.of(FILE_NAME)));
        final Path file = target.resolve(FILE_NAME);
        try (Reader reader = Files.newBufferedReader(file)) {
            final SnippetFiles beside = SnippetFiles.beside(file);
            ProjectReader.read(
                    reader,
                    FILE_NAME,
                    (name, origin) -> {
                        approved.add(Path.of(name).normalize());
                        return beside.read(name, origin);
                    });
        } catch (IOException | ProjectException ex) {
            // A copy that no longer reads names nothing more: what it held stays beside the new.
            return approved;
        }
        return approved;
    }

    /** Ends a question that took nothing. */
    void discard() {
        remove(place);
    }

    private static void remove(final Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            final List<Path> all = walk.sorted(Comparator.reverseOrder()).toList();
            for (final Path each : all) {
                Files.deleteIfExists(each);
            }
        } catch (IOException ex) {
            // Owner-only, and the person's own repository's text: nothing to fail a start over.
            return;
        }
    }
}
