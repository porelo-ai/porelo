package org.fuin.porelo.daemon;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.fuin.porelo.app.RecordedTaskRights;
import org.fuin.porelo.gate.review.Right;
import org.fuin.porelo.gate.review.TaskParts;
import org.junit.jupiter.api.Test;

/**
 * Keeps what a task's parts decide in {@link TaskParts} alone: every other main class of Porelo,
 * the daemon included, asks it a question and never reads a part's raw value. A class that names a
 * kind of workspace or an account, reads the raw workspace, account or set of rights, walks the
 * rights by ordinal or values, or spells one of the old role words fails here. It lives in the
 * daemon's tests because only the daemon sees every module.
 */
class TaskPartsBranchTest {

    /** The record's accessors that hand out a raw part. */
    private static final Set<String> RAW = Set.of("workspace", "account", "rights");

    /** What a walk or a switch over the rights compiles to. */
    private static final Set<String> WALKING = Set.of("ordinal", "values", "valueOf");

    /** The words a run's role was named by before its parts, as a string literal. */
    private static final Pattern ROLE_WORD = Pattern.compile("\"(AUTHOR|REVIEWER)\"");

    private static final DescribedPredicate<JavaAccess<?>> ON_A_RAW_PART =
            DescribedPredicate.describe(
                    "a kind of workspace, an account, a raw part, or a walk over the rights",
                    access -> {
                        final JavaClass owner = access.getTargetOwner();
                        return owner.isEquivalentTo(TaskParts.Workspace.class)
                                || owner.isEquivalentTo(TaskParts.Account.class)
                                || owner.isEquivalentTo(TaskParts.class)
                                        && RAW.contains(access.getName())
                                || owner.isEquivalentTo(Right.class)
                                        && WALKING.contains(access.getName());
                    });

    private static final DescribedPredicate<JavaClass> THE_ONE_PLACE =
            DescribedPredicate.describe(
                    "TaskParts, Right or a class nested in either",
                    type -> within(type, TaskParts.class) || within(type, Right.class));

    @Test
    void noMainClassButTaskPartsReadsARawPart() {
        rule().check(porelo());
    }

    @Test
    void theImportHoldsTheDaemonTheGateAndTheApps() {
        final JavaClasses porelo = porelo();
        assertThat(porelo.contain(GateMethods.class)).as("a daemon class is imported").isTrue();
        assertThat(porelo.contain(TaskParts.class)).as("a gate class is imported").isTrue();
        assertThat(porelo.contain(RecordedTaskRights.class))
                .as("an app class is imported")
                .isTrue();
    }

    @Test
    void theRuleFindsTheOneClassThatDoesRead() {
        assertThatThrownBy(() -> anywhere().check(porelo()))
                .as("the rule without its exemptions, on Porelo's main classes")
                .hasMessageContaining(TaskParts.class.getName());
    }

    @Test
    void theRuleFindsAPlantedSwitchComparisonAndRawRead() {
        final JavaClasses planted =
                new ClassFileImporter()
                        .importClasses(Switches.class, Compares.class, ReadsRaw.class, Walks.class);
        assertThatThrownBy(() -> rule().check(planted))
                .as("the rule on classes that branch on a part's raw value")
                .hasMessageContaining(Switches.class.getSimpleName())
                .hasMessageContaining(Compares.class.getSimpleName())
                .hasMessageContaining(ReadsRaw.class.getSimpleName())
                .hasMessageContaining(Walks.class.getSimpleName());
    }

    @Test
    void noMainSourceSpellsARoleWord() throws IOException {
        assertThat(mainSources().stream().filter(TaskPartsBranchTest::spells).toList())
                .as("main sources that spell a role word")
                .isEmpty();
    }

    @Test
    void theSourceScanReadsEveryAreaAndFindsAPlantedWord() throws IOException {
        assertThat(mainSources())
                .as("the scan reads the daemon's, the gate's and the apps' main sources")
                .anyMatch(path -> path.endsWith("GateMethods.java"))
                .anyMatch(path -> path.endsWith("TaskParts.java"))
                .anyMatch(path -> path.endsWith("RecordedTaskRights.java"));
        assertThat(ROLE_WORD.matcher("if (\"REVIEWER\".equals(word)) {").find())
                .as("a planted comparison with a role word is found")
                .isTrue();
    }

    private static boolean spells(final Path source) {
        try {
            return ROLE_WORD.matcher(Files.readString(source, StandardCharsets.UTF_8)).find();
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    /** Every main Java source of the repository, found from the daemon's module. */
    private static List<Path> mainSources() throws IOException {
        final Path root = Path.of("").toAbsolutePath().getParent();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/src/main/java/"))
                    .filter(path -> !path.toString().contains("/target/"))
                    .filter(path -> !root.relativize(path).toString().startsWith("."))
                    .toList();
        }
    }

    private static boolean within(final JavaClass type, final Class<?> owner) {
        return type.isEquivalentTo(owner) || type.getName().startsWith(owner.getName() + "$");
    }

    private static JavaClasses porelo() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.fuin.porelo");
    }

    private static ArchRule rule() {
        return noClasses()
                .that(DescribedPredicate.not(THE_ONE_PLACE))
                .should()
                .accessTargetWhere(ON_A_RAW_PART)
                .because(
                        "what a task's parts decide are TaskParts' answers; every other part asks");
    }

    private static ArchRule anywhere() {
        return noClasses().should().accessTargetWhere(ON_A_RAW_PART);
    }

    /** Branches on the kind of workspace with a switch, as no main class may. */
    static final class Switches {
        static boolean gated(final TaskParts parts) {
            return switch (parts.workspace()) {
                case OWN, FOREIGN -> true;
                case READ_ONLY, ALL_REPOSITORIES, NONE -> false;
            };
        }
    }

    /** Compares an account with {@code ==}, as no main class may. */
    static final class Compares {
        static boolean opener(final TaskParts.Account account) {
            return account == TaskParts.Account.AUTHOR;
        }
    }

    /** Reads the raw set of rights, as no main class may. */
    static final class ReadsRaw {
        static boolean pushes(final TaskParts parts) {
            return parts.rights().contains(Right.PUSH);
        }
    }

    /** Walks the rights by their values, as no main class may. */
    static final class Walks {
        static int count() {
            return Right.values().length;
        }
    }
}
