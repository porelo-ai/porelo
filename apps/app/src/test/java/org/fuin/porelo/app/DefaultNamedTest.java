package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which repositories of {@code default} a project of the organization names. */
class DefaultNamedTest {

    private static final String ORIGIN = "https://forge.example/acme/shop.git";

    @TempDir Path root;

    private PoreloContext context;

    private Path checkout;

    @BeforeEach
    void machine() throws Exception {
        context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(XdgPaths.of(key -> null, root), root.resolve("bin")),
                        args -> 0,
                        key -> null);
        checkout = Files.createDirectories(root.resolve("work/shop")).toRealPath();
        PlacedFixture.place(
                context,
                "acme",
                "project:\n  name: acme\n  security_class: guarded\n"
                        + "image:\n  from: ubuntu:24.04\n"
                        + "repositories:\n  storefront:\n    upstream: \""
                        + ORIGIN
                        + "\"\n");
    }

    // Harm: a repository the organization's project took keeps its tasks in 'default', unoffered.
    @Test
    void aCheckoutWhoseOriginTheOrganizationNamesIsOffered() throws Exception {
        new DefaultProject(context).add(checkout.toString(), "shop", checkout.toString(), ORIGIN);

        assertThat(DefaultNamed.all(context))
                .containsExactly(
                        new DefaultNamed.Named("shop", checkout.toString(), "acme", "storefront"));
        assertThat(DefaultNamed.of(context, "shop"))
                .extracting(DefaultNamed.Named::removal)
                .isEqualTo("porelo project default remove shop");
    }

    // Harm: a repository nobody else names would be offered for removal, and its work lose its
    // home.
    @Test
    void aRepositoryNoProjectNamesIsNotOffered() throws Exception {
        new DefaultProject(context)
                .add(
                        checkout.toString(),
                        "shop",
                        checkout.toString(),
                        "https://forge.example/someone/else.git");
        new DefaultProject(context).add("https://forge.example/acme/other.git", null, "");

        assertThat(DefaultNamed.all(context)).isEmpty();
        assertThat(DefaultNamed.of(context, "shop")).isNull();
        assertThat(new DefaultProject(context).entries()).hasSize(2);
    }
}
