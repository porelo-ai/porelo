package org.fuin.porelo.app;

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
import java.util.Set;
import org.fuin.porelo.gate.review.Role;
import org.junit.jupiter.api.Test;

/**
 * Keeps what a task's role decides in {@link TaskReviewRole} alone: every other part asks it, so a
 * {@code switch} or {@code ==} over {@link Role} anywhere else in the apps fails here.
 */
class ReviewRoleBranchTest {

    /** What a branch over the role compiles to: its constants, or a switch's ordinal table. */
    private static final Set<String> BRANCHING = Set.of("AUTHOR", "REVIEWER", "ordinal", "values");

    private static final DescribedPredicate<JavaAccess<?>> ON_ROLE =
            DescribedPredicate.describe(
                    "a role's constant, ordinal or values",
                    access ->
                            access.getTargetOwner().isEquivalentTo(Role.class)
                                    && BRANCHING.contains(access.getName()));

    private static final DescribedPredicate<JavaClass> THE_ONE_PLACE =
            DescribedPredicate.describe(
                    "TaskReviewRole or a class nested in it",
                    type ->
                            type.isEquivalentTo(TaskReviewRole.class)
                                    || type.getName()
                                            .startsWith(TaskReviewRole.class.getName() + "$"));

    @Test
    void noClassOfTheAppsButTaskReviewRoleBranchesOnARole() {
        rule().check(apps());
    }

    @Test
    void theRuleFindsTheOneClassThatDoesBranch() {
        final JavaClasses apps = apps();
        assertThat(apps.contain(TaskReviewRole.class)).as("TaskReviewRole is imported").isTrue();
        assertThatThrownBy(() -> anywhere().check(apps))
                .as("the rule without its one exemption, on the apps")
                .hasMessageContaining(TaskReviewRole.class.getName());
    }

    @Test
    void theRuleFindsAPlantedSwitchAndComparison() {
        final JavaClasses planted =
                new ClassFileImporter().importClasses(Switches.class, Compares.class);
        assertThatThrownBy(() -> rule().check(planted))
                .as("the rule on classes that branch on a role")
                .hasMessageContaining(Switches.class.getSimpleName())
                .hasMessageContaining(Compares.class.getSimpleName());
    }

    private static JavaClasses apps() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.fuin.porelo.app");
    }

    private static ArchRule rule() {
        return noClasses()
                .that(DescribedPredicate.not(THE_ONE_PLACE))
                .should()
                .accessTargetWhere(ON_ROLE)
                .because("a role's rights are TaskReviewRole's answers; every other part asks it");
    }

    private static ArchRule anywhere() {
        return noClasses().should().accessTargetWhere(ON_ROLE);
    }

    /** Branches on a role with a switch, as no class of the apps may. */
    static final class Switches {
        static boolean pushes(final Role role) {
            return switch (role) {
                case AUTHOR -> true;
                case REVIEWER -> false;
            };
        }
    }

    /** Branches on a role with {@code ==}, as no class of the apps may. */
    static final class Compares {
        static boolean pushes(final Role role) {
            return role == Role.AUTHOR;
        }
    }
}
