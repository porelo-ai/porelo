package org.fuin.porelo.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Test for {@link PatchFiles}: a patch cut into whole files, each named by its path. */
class PatchFilesTest {

    @Test
    void aPatchIsCutIntoItsFilesAndNothingIsLost() {
        final String first =
                "diff --git a/one.txt b/one.txt\nindex 1..2 100644\n--- a/one.txt\n+++ b/one.txt\n"
                        + "@@ -1 +1 @@\n-before\n+diff --git a/not-a-file b/not-a-file\n";
        final String second =
                "diff --git a/dir/two b/two.txt b/dir/two b/two.txt\nnew file mode 100644\n";
        final String third = "diff --git a/bin.dat b/bin.dat\nBinary files differ\n";

        final PatchFiles.Split split = PatchFiles.split("\n" + first + second + third);

        assertThat(split.preamble()).isEqualTo("\n");
        assertThat(split.parts())
                .as("a content line that looks like a header is inside its file, after its + sign")
                .extracting(PatchFiles.Part::path, PatchFiles.Part::text)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("one.txt", first),
                        org.assertj.core.groups.Tuple.tuple("dir/two b/two.txt", second),
                        org.assertj.core.groups.Tuple.tuple("bin.dat", third));
    }

    @Test
    void aQuotedPathIsReadAsGitQuotedIt() {
        assertThat(
                        PatchFiles.path(
                                "diff --git \"a/caf\\303\\251\\t\\\"x\\\".txt\""
                                        + " \"b/caf\\303\\251\\t\\\"x\\\".txt\""))
                .isEqualTo("café\t\"x\".txt");
    }

    @Test
    void aHeaderOfAnUnknownShapeIsKeptRatherThanDropped() {
        assertThat(PatchFiles.path("diff --git something odd"))
                .isEqualTo("diff --git something odd");
        assertThat(PatchFiles.split("no header at all\n").parts()).isEqualTo(List.of());
    }

    @Test
    void aPatchIsPutInTheOrderGivenTheRestAfterInGitsOrder() {
        final String one = "diff --git a/one.txt b/one.txt\n@@ -1 +1 @@\n-a\n+b\n";
        final String quoted =
                "diff --git \"a/two \\\"x\\\".txt\" \"b/two \\\"x\\\".txt\"\ndeleted file mode"
                        + " 100644\n";
        final String binary = "diff --git a/bin.dat b/bin.dat\nBinary files differ\n";
        final String odd = "diff --git something odd\n";
        final String unnamed = "diff --git a/later.txt b/later.txt\n+x\n";

        final String ordered =
                PatchFiles.inOrder(
                        "pre\n" + one + quoted + odd + binary + unnamed,
                        List.of("bin.dat", "two \"x\".txt", "one.txt"));

        assertThat(ordered)
                .as("the named files in the order given, every other file after them as git had it")
                .isEqualTo("pre\n" + binary + quoted + one + odd + unnamed);
    }

    @Test
    void aPatchWithoutFilesIsKeptAsItIs() {
        assertThat(PatchFiles.inOrder("", List.of("one.txt"))).isEmpty();
        assertThat(PatchFiles.inOrder("odd\n", List.of())).isEqualTo("odd\n");
    }

    @Test
    void aPatchWithoutAFinalLineBreakKeepsItsLastLine() {
        final String patch = "diff --git a/x b/x\n+last";

        assertThat(PatchFiles.split(patch).parts().getFirst().text()).isEqualTo(patch);
    }
}
