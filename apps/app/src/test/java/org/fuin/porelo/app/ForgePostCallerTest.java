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
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.ForgeReview;
import org.fuin.porelo.gate.review.ProfileTranslator;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.gate.review.WriteOutcome;
import org.junit.jupiter.api.Test;

/**
 * Keeps a forge post in {@link ForgeWrite} alone, so every post to a pull request is the one
 * submission of a released item, checked against its pinned commit and sent once.
 */
class ForgePostCallerTest {

    private static final DescribedPredicate<JavaAccess<?>> POST =
            DescribedPredicate.describe(
                    "ForgeReview.post",
                    access ->
                            access.getTargetOwner().isAssignableTo(ForgeReview.class)
                                    && "post".equals(access.getName()));

    private static final DescribedPredicate<JavaClass> THE_ONE_PLACE =
            DescribedPredicate.describe(
                    "ForgeWrite or a class nested in it",
                    type ->
                            type.isEquivalentTo(ForgeWrite.class)
                                    || type.getName().startsWith(ForgeWrite.class.getName() + "$"));

    @Test
    void noClassOfPoreloButForgeWritePostsToAForge() {
        rule().check(porelo());
    }

    @Test
    void theScanCoversPoreloBeyondTheApps() {
        assertThat(porelo().contain(ProfileTranslator.class))
                .as("a class of the gate, outside the apps' package, is imported")
                .isTrue();
    }

    @Test
    void theRuleFindsTheOneClassThatDoesPost() {
        final JavaClasses porelo = porelo();
        assertThat(porelo.contain(ForgeWrite.class)).as("ForgeWrite is imported").isTrue();
        assertThatThrownBy(() -> anywhere().check(porelo))
                .as("the rule without its one exemption, on every Porelo class")
                .hasMessageContaining(ForgeWrite.class.getName());
    }

    @Test
    void theRuleFindsAPlantedPost() {
        final JavaClasses planted = new ClassFileImporter().importClasses(Posts.class);
        assertThatThrownBy(() -> rule().check(planted))
                .as("the rule on a class that posts")
                .hasMessageContaining(Posts.class.getSimpleName());
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
                .accessTargetWhere(POST)
                .because("a post is ForgeWrite's: once, against the item's pinned commit");
    }

    private static ArchRule anywhere() {
        return noClasses().should().accessTargetWhere(POST);
    }

    /** A class that posts past ForgeWrite. */
    static final class Posts {

        WriteOutcome post(
                final ForgeReview forge, final PullRequestRef ref, final ForgeCredential lent) {
            return forge.post(ref, new byte[0], lent);
        }
    }
}
