package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.fuin.porelo.core.project.ImageSource;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.RepositoryImageReader;
import org.fuin.porelo.core.project.SnippetFiles;
import org.fuin.porelo.core.project.file.Image;
import org.fuin.porelo.core.project.file.RepositoryFile;
import org.fuin.porelo.core.project.file.RepositoryFileWriter;
import org.fuin.porelo.wire.Directories;
import org.jspecify.annotations.Nullable;

/**
 * The tools of a proposal, written as a repository's {@code .porelo.yml} where the image of {@value
 * DefaultProject#NAME} reads it: the lines and variables the adapters found, the base image left to
 * the project.
 *
 * <p><strong>Written in the format its reader expects</strong>, by {@link RepositoryFileWriter},
 * and read back through {@link RepositoryImageReader} before anything is written: a draft that does
 * not give the same tools is refused. Where each line and variable was read stands above them as a
 * comment, which the reader skips.
 *
 * <p><strong>Where its reader finds it.</strong> For a repository added from a checkout, into that
 * checkout, with a line in its {@code .git/info/exclude}, so no tracked file changes and {@code git
 * status} stays as it was. For one added by its address, which has no checkout, under {@code
 * $XDG_CONFIG_HOME/porelo/projects/default/<repository>/}, for the person alone. A file that is
 * there is never written over.
 *
 * <p><strong>Nothing builds from it unasked.</strong> Its lines run as root while the image is
 * built, and the adapters read them from the repository: a start asks for each as it asks for a
 * checkout's.
 */
public final class ToolsDraft {

    /** The file's name, the person's own beside a repository's {@code porelo.yml}. */
    static final String NAME = "." + ImageChoice.FILE;

    /** The line in a checkout's {@code .git/info/exclude} that keeps the draft out of git. */
    static final String EXCLUDED = "/" + NAME;

    /** What a comment may not hold: a line break would start a line of the file. */
    private static final Pattern HIDDEN = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");

    /** A draft names no file of lines: what it holds is written whole. */
    private static final SnippetFiles NO_FILES =
            (named, origin) -> {
                throw new ProjectException(origin + ": a tools draft names no run file");
            };

    /** A draft is never written over a file that is there. */
    public static final class Exists extends Exception {

        private static final long serialVersionUID = 1L;

        /**
         * Constructor.
         *
         * @param file The file that is there.
         */
        public Exists(final Path file) {
            super(file + " is there and is not written over");
        }
    }

    private final Image image;

    private final List<String> sources;

    /**
     * Constructor.
     *
     * @param image The tools, with no base image and no file of lines.
     * @param sources Each line and variable with the file it was read from, one comment each.
     */
    ToolsDraft(final Image image, final List<String> sources) {
        this.image = image;
        this.sources = List.copyOf(sources);
    }

    /**
     * Returns the tools a proposal's last answer put into its image.
     *
     * @param made The proposal, answered.
     * @return Its tools.
     */
    static ToolsDraft of(final ProjectProposal made) {
        final Image image = made.image();
        final List<String> sources = new ArrayList<>();
        final String run = Objects.requireNonNullElse(image.run(), "");
        for (final ProjectProposal.Placed each : made.tools()) {
            if (!each.what().startsWith("package.")) {
                continue;
            }
            final String name =
                    each.value().isEmpty()
                            ? each.what().substring("package.".length())
                            : each.value();
            if (run.contains("--no-install-recommends " + name + " &&")) {
                sources.add("package " + name + "  <- " + each.source());
            }
        }
        for (final ProjectProposal.Placed each : made.imageLines()) {
            sources.add(each.value() + "  <- " + each.source());
        }
        return new ToolsDraft(image, sources);
    }

    /**
     * Returns the tools.
     *
     * @return The image the draft adds to the project's.
     */
    Image image() {
        return image;
    }

    /**
     * Says whether the draft adds nothing to the image, and so is not written.
     *
     * @return {@code true} without a line or a variable.
     */
    boolean isEmpty() {
        return image.run() == null && image.env().isEmpty();
    }

    /**
     * Returns the file's text, read back before it is answered.
     *
     * @return The text.
     * @throws ProjectException If it would not read back as the same tools.
     */
    String text() {
        final StringBuilder text = new StringBuilder();
        text.append("# The tools Porelo proposed, from what the ecosystem adapters read.\n")
                .append("# They add to the project's image; a start asks before it builds them.\n");
        if (!sources.isEmpty()) {
            text.append("# Where each was read:\n");
            sources.forEach(
                    each ->
                            text.append("# ")
                                    .append(HIDDEN.matcher(each).replaceAll("?"))
                                    .append('\n'));
        }
        text.append(RepositoryFileWriter.write(new RepositoryFile(image)));
        readBack(text.toString(), image);
        return text.toString();
    }

    /**
     * Reads a draft back through the image's own reader.
     *
     * @param text The draft.
     * @param wanted The tools it is to give.
     * @throws ProjectException If it is refused, holds more than an image, or gives other tools.
     */
    static void readBack(final String text, final Image wanted) {
        final ImageSource read = RepositoryImageReader.read(text, "the tools draft", NO_FILES);
        if (!Objects.equals(read.baseImage(), wanted.from())
                || !Objects.equals(read.snippet(), wanted.run())
                || !read.env().equals(wanted.env())) {
            throw new ProjectException(
                    "The tools draft would not read back as the tools proposed; nothing is"
                            + " written");
        }
    }

    /**
     * Writes the draft where the image of a repository of {@value DefaultProject#NAME} reads it.
     *
     * @param context This machine.
     * @param entry The repository.
     * @return The file written, or {@code null} when the draft adds nothing.
     * @throws Exists When a file is there; nothing is written.
     * @throws IOException When it cannot be written.
     * @throws ProjectException If it would not read back; nothing is written.
     */
    @Nullable Path write(final PoreloContext context, final DefaultProject.Entry entry)
            throws Exists, IOException {
        if (isEmpty()) {
            return null;
        }
        final String text = text();
        if (entry.source() == DefaultProject.Source.CHECKOUT) {
            final Path checkout = Path.of(entry.checkout());
            final Path file = checkout.resolve(NAME);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new Exists(file);
            }
            // Excluded first: a file written and then not excluded would show in git status.
            exclude(checkout);
            place(file, text);
            return file;
        }
        final Path file = remote(context, entry.name());
        Directories.create(
                file.getParent(),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        place(file, text);
        return file;
    }

    /**
     * Returns where the draft of a repository with no checkout lies, whether or not it exists.
     *
     * @param context This machine.
     * @param repository The repository's name in {@value DefaultProject#NAME}.
     * @return The path.
     */
    static Path remote(final PoreloContext context, final String repository) {
        return ImageChoice.override(context, DefaultProject.NAME, repository);
    }

    /**
     * Writes a file whole that is not there, readable by the person alone: staged beside it and
     * linked to its name, which fails when anything has that name, a link among them.
     */
    private static void place(final Path file, final String text) throws Exists, IOException {
        final Path staged =
                Files.createTempFile(
                        file.getParent(),
                        NAME + ".",
                        ".new",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(staged, text, StandardCharsets.UTF_8);
            Files.createLink(file, staged);
        } catch (FileAlreadyExistsException ex) {
            throw new Exists(file);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /** Adds the draft's line to the checkout's excludes, where git reads them, once. */
    private static void exclude(final Path checkout) throws IOException {
        final Path exclude = excludeFile(checkout);
        if (exclude == null) {
            // Not a git checkout: nothing tracks the file, nothing is to keep it out of.
            return;
        }
        final String held =
                Files.isRegularFile(exclude)
                        ? Files.readString(exclude, StandardCharsets.UTF_8)
                        : "";
        if (held.lines().anyMatch(line -> line.strip().equals(EXCLUDED))) {
            return;
        }
        Directories.create(exclude.getParent());
        Files.writeString(
                exclude,
                (held.isEmpty() || held.endsWith("\n") ? "" : "\n") + EXCLUDED + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    /**
     * Returns the excludes git reads for a checkout: its own {@code .git}'s, or for a worktree or a
     * submodule, whose {@code .git} is a file, those of the directory it names, of the repository
     * the worktree shares.
     */
    private static @Nullable Path excludeFile(final Path checkout) throws IOException {
        final Path dotGit = checkout.resolve(".git");
        Path gitDir;
        if (Files.isDirectory(dotGit, LinkOption.NOFOLLOW_LINKS)) {
            gitDir = dotGit;
        } else if (Files.isRegularFile(dotGit, LinkOption.NOFOLLOW_LINKS)) {
            final String line = Files.readString(dotGit, StandardCharsets.UTF_8).strip();
            if (!line.startsWith("gitdir:")) {
                throw new IOException(dotGit + " names no git directory");
            }
            gitDir = checkout.resolve(line.substring("gitdir:".length()).strip()).normalize();
        } else {
            return null;
        }
        final Path common = gitDir.resolve("commondir");
        if (Files.isRegularFile(common)) {
            gitDir =
                    gitDir.resolve(Files.readString(common, StandardCharsets.UTF_8).strip())
                            .normalize();
        }
        return gitDir.resolve("info").resolve("exclude");
    }
}
