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
import java.util.Set;
import org.fuin.porelo.app.TaskReviewRole;
import org.fuin.porelo.gate.review.Role;
import org.junit.jupiter.api.Test;

/**
 * Keeps what a task's role decides in {@link TaskReviewRole} alone: every other part asks it, so a
 * {@code switch} or {@code ==} over {@link Role} in any main class of Porelo, the daemon included,
 * fails here. It lives in the daemon's tests because only the daemon sees every module.
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
                    "TaskReviewRole, Role itself or a class nested in either",
                    type -> within(type, TaskReviewRole.class) || within(type, Role.class));

    @Test
    void noMainClassButTaskReviewRoleBranchesOnARole() {
        rule().check(porelo());
    }

    @Test
    void theImportHoldsTheDaemonTheGateAndTheApps() {
        final JavaClasses porelo = porelo();
        assertThat(porelo.contain(GateMethods.class)).as("a daemon class is imported").isTrue();
        assertThat(porelo.contain(Role.class)).as("a gate class is imported").isTrue();
        assertThat(porelo.contain(TaskReviewRole.class)).as("an app class is imported").isTrue();
    }

    @Test
    void theRuleFindsTheOneClassThatDoesBranch() {
        assertThatThrownBy(() -> anywhere().check(porelo()))
                .as("the rule without its exemptions, on Porelo's main classes")
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
                .accessTargetWhere(ON_ROLE)
                .because("a role's rights are TaskReviewRole's answers; every other part asks it");
    }

    private static ArchRule anywhere() {
        return noClasses().should().accessTargetWhere(ON_ROLE);
    }

    /** Branches on a role with a switch, as no main class may. */
    static final class Switches {
        static boolean pushes(final Role role) {
            return switch (role) {
                case AUTHOR -> true;
                case REVIEWER -> false;
            };
        }
    }

    /** Branches on a role with {@code ==}, as no main class may. */
    static final class Compares {
        static boolean pushes(final Role role) {
            return role == Role.AUTHOR;
        }
    }
}
