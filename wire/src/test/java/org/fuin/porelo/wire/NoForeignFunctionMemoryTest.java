package org.fuin.porelo.wire;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@code porelo-wire} makes no use of the Foreign Function &amp; Memory API.
 *
 * <p>The three OCI hooks are built {@code --static --libc=musl}. Spike S5 showed that any FFM use
 * fails in a static image, because {@code Linker.defaultLookup()} dlopens {@code libc.so.6} on its
 * first downcall. This module is on the hooks classpath, so the constraint extends to it.
 *
 * <p>A violation is invisible at build time: the native image links, and the hook fails at
 * container-create time - on the fail-closed path, where a failure stops the container from
 * starting at all.
 */
class NoForeignFunctionMemoryTest {

    @Test
    void noClassMayDependOnTheForeignFunctionAndMemoryApi() {

        // What ships alone: a test's own helper, such as the one that sets the umask, is never in
        // the hooks' image.
        final JavaClasses classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("org.fuin.porelo.wire");

        final ArchRule rule =
                noClasses()
                        .should()
                        .dependOnClassesThat()
                        .resideInAPackage("java.lang.foreign..")
                        .because(
                                "porelo-wire is linked statically against musl and FFM dlopens libc.so.6 (S5)")
                        .allowEmptyShould(true);

        rule.check(classes);
    }
}
