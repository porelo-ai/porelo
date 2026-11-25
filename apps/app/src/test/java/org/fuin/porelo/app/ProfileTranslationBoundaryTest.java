package org.fuin.porelo.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.fuin.porelo.build.api.review.SarifReading.read;

import com.contrastsecurity.sarif.SarifSchema210;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.fuin.porelo.build.api.review.ProfileCheck;
import org.fuin.porelo.build.api.review.SarifReading;
import org.fuin.porelo.build.api.review.SarifRefused;
import org.fuin.porelo.gate.review.ProfileTranslator;
import org.junit.jupiter.api.Test;

/**
 * Guards the one way a review document crosses into Porelo's records and the one way out. Harm: a
 * second reader that skips the profile's check, or a second writer whose documents drift from the
 * profile, so that what Porelo keeps and shows depends on which path a document took.
 */
class ProfileTranslationBoundaryTest {

    /** A profile document written: its version put as a map's key. */
    private static final Pattern WRITES = Pattern.compile("\"porelo/profileVersion\"\\s*,");

    /** The one writer, by its path in the repository. */
    private static final String WRITER =
            "gate/src/main/java/org/fuin/porelo/gate/review/ReviewSarif.java";

    /** A call of the hardened reader or of the profile's check, from outside their own class. */
    private static final DescribedPredicate<JavaAccess<?>> READS =
            DescribedPredicate.describe(
                    "SarifReading.read or ProfileCheck.check",
                    access ->
                            access.getTargetOwner().isEquivalentTo(SarifReading.class)
                                            && "read".equals(access.getName())
                                            && !access.getOriginOwner()
                                                    .isEquivalentTo(SarifReading.class)
                                    || access.getTargetOwner().isEquivalentTo(ProfileCheck.class)
                                            && "check".equals(access.getName())
                                            && !access.getOriginOwner()
                                                    .isEquivalentTo(ProfileCheck.class));

    @Test
    void onlyTheProfileTranslatorReadsADocumentFromOutside() {
        readers().check(porelo());
    }

    @Test
    void onlyReviewSarifWritesAProfileDocument() throws IOException {
        assertThat(writers())
                .as("the main sources that put the profile version as a key, but ReviewSarif")
                .filteredOn(path -> !path.equals(WRITER))
                .isEmpty();
    }

    @Test
    void theWritingRuleFindsReviewSarifAndAPlantedWriter() throws IOException {
        assertThat(writers())
                .as("the main sources that put the profile version as a key")
                .contains(WRITER);
        assertThat(
                        WRITES.matcher(
                                        "root.put(\"properties\", Map.of(\"porelo/profileVersion\" ,"
                                                + " \"2\"));")
                                .find())
                .as("a planted writer")
                .isTrue();
    }

    @Test
    void theReadingRuleFindsTheOneClassThatReads() {
        assertThatThrownBy(() -> noClasses().should().accessTargetWhere(READS).check(porelo()))
                .as("the reading rule without its one exemption")
                .hasMessageContaining(ProfileTranslator.class.getName());
    }

    @Test
    void theRuleFindsAPlantedReaderAndChecker() {
        final JavaClasses planted =
                new ClassFileImporter()
                        .importClasses(Reads.class, ReadsByStaticImport.class, Checks.class);
        assertThatThrownBy(() -> readers().check(planted))
                .as("the reading rule on classes that read or check past the translator")
                .hasMessageContaining(Reads.class.getSimpleName())
                .hasMessageContaining(ReadsByStaticImport.class.getSimpleName())
                .hasMessageContaining(Checks.class.getSimpleName());
    }

    /** Every module's main source that writes a profile document, by its path in the repository. */
    private static List<String> writers() throws IOException {
        // apps/app is two levels below the repository's root.
        final Path root = Path.of("").toAbsolutePath().getParent().getParent();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .map(path -> root.relativize(path).toString())
                    .filter(path -> path.contains("src/main/java/"))
                    .filter(
                            path ->
                                    !path.contains("/target/")
                                            && !path.startsWith("target/")
                                            && !path.startsWith("."))
                    .filter(path -> WRITES.matcher(text(root.resolve(path))).find())
                    .sorted()
                    .toList();
        }
    }

    private static String text(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static JavaClasses porelo() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.fuin.porelo");
    }

    private static ArchRule readers() {
        return noClasses()
                .that(DescribedPredicate.not(nestedIn(ProfileTranslator.class)))
                .should()
                .accessTargetWhere(READS)
                .because("a document from outside is read and checked by ProfileTranslator alone");
    }

    private static DescribedPredicate<JavaClass> nestedIn(final Class<?> owner) {
        return DescribedPredicate.describe(
                owner.getSimpleName() + " or a class nested in it",
                type ->
                        type.isEquivalentTo(owner)
                                || type.getName().startsWith(owner.getName() + "$"));
    }

    /** A class that reads a document past the translator. */
    static final class Reads {

        SarifSchema210 take(final byte[] document) throws SarifRefused {
            return SarifReading.read(document);
        }
    }

    /** A class that reads one through a static import, which no search for the class name sees. */
    static final class ReadsByStaticImport {

        SarifSchema210 take(final byte[] document) throws SarifRefused {
            return read(document);
        }
    }

    /** A class that checks a document past the translator. */
    static final class Checks {

        SarifSchema210 take(final SarifSchema210 document) throws SarifRefused {
            return ProfileCheck.check(ProfileCheck.Channel.TASK, document);
        }
    }
}
