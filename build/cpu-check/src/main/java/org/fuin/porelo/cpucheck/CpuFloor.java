package org.fuin.porelo.cpucheck;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Checks that a native image asks for exactly x86-64 v1, the baseline every x86-64 CPU has.
 *
 * <p>native-image's default target is the build host's generation, x86-64 v3 on any recent machine:
 * the binary then refuses to start on an older CPU or on a virtual machine that hides AVX2, and
 * says so only when an operator runs it. {@code -march=x86-64} in the shared build arguments lowers
 * that; this proves it did, for every image, by reading the list the image carries for its own
 * startup check ("required by the image: [...]").
 *
 * <p><strong>The exact set, not "nothing beyond it"</strong>: a list that changes in either
 * direction means the target changed - a new GraalVM that reads {@code x86-64} differently, or an
 * image built another way - and that is for a person to look at before it ships.
 *
 * <p>A binary with no such list is a failure, not a pass: a check that finds nothing to check
 * proves nothing.
 *
 * <p>It also refuses an image built by GraalVM CE 25.1 up to but not including 25.6, whose Base64
 * decoder can write past its buffer on a malformed string (CVE-2026-114938). The builder is read
 * from the {@code com.oracle.svm.core.VM} string every image carries; an image whose builder it
 * cannot read or place is refused too.
 */
public final class CpuFloor {

    /** x86-64 v1: what every x86-64 CPU has. */
    static final Set<String> BASELINE = Set.of("CX8", "CMOV", "FXSR", "MMX", "SSE", "SSE2");

    private static final Pattern REQUIRED =
            Pattern.compile("required by the image: \\[([A-Z0-9_, ]*)\\]");

    private static final Pattern BUILDER =
            Pattern.compile("com\\.oracle\\.svm\\.core\\.VM=([\\x20-\\x7e]+)");

    /** A GraalVM CE version, its minor left out by a first release such as {@code 25+37.1}. */
    private static final Pattern GRAALVM_CE =
            Pattern.compile(
                    "GraalVM CE (\\d{1,9})(?:\\.(\\d{1,9}))?(?:\\.\\d{1,9})*(?:\\+\\d{1,9}(?:\\.\\d{1,9})*)?");

    /** The first GraalVM CE release whose images carry the overflowing decoder. */
    private static final Release FIRST_OPEN = new Release(25, 1);

    /** The first GraalVM CE release after them, with the fixed decoder. */
    private static final Release FIRST_FIXED = new Release(25, 6);

    private CpuFloor() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Checks the named images in one directory.
     *
     * <p>Throws rather than exiting: this runs inside Maven's own JVM, and an exit would end Maven.
     *
     * @param args the directory the images are in, and their names separated by commas
     */
    public static void main(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: <directory> <image>[,<image>...]");
        }
        final String failure = check(Path.of(args[0]), List.of(args[1].split(",")), System.out);
        if (failure != null) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * Checks each image.
     *
     * @param directory where the images are
     * @param images their file names
     * @param out where each image's result is reported
     * @return why the check failed, or {@code null} when every image asks for exactly the baseline
     *     and was built by a GraalVM CE outside 25.1 to 25.5
     */
    static @Nullable String check(Path directory, List<String> images, PrintStream out) {
        final List<String> failures = new ArrayList<>();
        for (final String image : images) {
            final Path binary = directory.resolve(image.strip());
            final String builderFailure = checkBuilder(binary, out);
            if (builderFailure != null) {
                failures.add(builderFailure);
            }
            final Set<String> required = required(binary);
            if (required == null) {
                failures.add(
                        binary
                                + ": no 'required by the image' list - not a native image, or not one this check can read");
                continue;
            }
            if (required.equals(BASELINE)) {
                out.println(
                        "    "
                                + binary.getFileName()
                                + " runs on any x86-64 CPU "
                                + new TreeSet<>(required));
            } else {
                final Set<String> beyond = new TreeSet<>(required);
                beyond.removeAll(BASELINE);
                final Set<String> missing = new TreeSet<>(BASELINE);
                missing.removeAll(required);
                failures.add(
                        binary
                                + " is not built for x86-64 v1: needs "
                                + beyond
                                + " beyond it, lacks "
                                + missing
                                + " of it - is -march=x86-64 still in the native build arguments?");
            }
        }
        return failures.isEmpty() ? null : String.join("\n", failures);
    }

    private static @Nullable String checkBuilder(Path binary, PrintStream out) {
        final Set<String> builders = builders(binary);
        final Release release = builders.size() == 1 ? release(builders.iterator().next()) : null;
        if (release == null) {
            return binary
                    + ": no readable builder - found "
                    + builders
                    + ", not one 'com.oracle.svm.core.VM=GraalVM CE <version>' string, so it cannot"
                    + " be shown not to be GraalVM 25.1 to 25.5";
        }
        final String builder = builders.iterator().next();
        if (release.compareTo(FIRST_OPEN) >= 0 && release.compareTo(FIRST_FIXED) < 0) {
            return binary
                    + " was built by "
                    + builder
                    + ", whose Base64 decoder overflows on a malformed string (CVE-2026-114938):"
                    + " build it with GraalVM CE 25.6 or later";
        }
        out.println("    " + binary.getFileName() + " built by " + builder);
        return null;
    }

    /**
     * Reads which builders an image names.
     *
     * @param binary the image
     * @return every distinct {@code com.oracle.svm.core.VM} value, such as {@code GraalVM CE
     *     25.6.5.1+2.1}; a real image names exactly one
     */
    static Set<String> builders(Path binary) {
        final Matcher matcher = BUILDER.matcher(latin1(binary));
        final Set<String> builders = new LinkedHashSet<>();
        while (matcher.find()) {
            builders.add(matcher.group(1));
        }
        return builders;
    }

    private static @Nullable Release release(String builder) {
        final Matcher matcher = GRAALVM_CE.matcher(builder);
        if (!matcher.matches()) {
            return null;
        }
        final String minor = matcher.group(2);
        return new Release(
                Integer.parseInt(matcher.group(1)), minor == null ? 0 : Integer.parseInt(minor));
    }

    /** A GraalVM CE release line, such as 25.6. */
    private record Release(int major, int minor) implements Comparable<Release> {
        @Override
        public int compareTo(Release other) {
            return major != other.major
                    ? Integer.compare(major, other.major)
                    : Integer.compare(minor, other.minor);
        }
    }

    private static String latin1(Path binary) {
        try {
            // Latin-1 maps every byte to one char, so an ASCII string is found wherever it sits.
            return new String(Files.readAllBytes(binary), StandardCharsets.ISO_8859_1);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Reads the CPU features an image checks for at startup.
     *
     * @param binary the image
     * @return the features, or {@code null} when the image carries no list or disagrees with itself
     */
    static @Nullable Set<String> required(Path binary) {
        final Matcher matcher = REQUIRED.matcher(latin1(binary));
        final Set<String> lists = new LinkedHashSet<>();
        while (matcher.find()) {
            lists.add(matcher.group(1));
        }
        if (lists.size() != 1) {
            return null;
        }
        return new LinkedHashSet<>(Arrays.asList(lists.iterator().next().split(", ")));
    }
}
