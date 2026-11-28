package org.fuin.porelo.cpucheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CpuFloorTest {

    private static final String V1 = "CX8, CMOV, FXSR, MMX, SSE, SSE2";

    /**
     * What GraalVM 25 builds by default on a current machine, measured from porelo's own binaries.
     */
    private static final String V3 =
            V1 + ", SSE3, SSSE3, SSE4_1, SSE4_2, POPCNT, LZCNT, AVX, AVX2, BMI1, BMI2, FMA, F16C";

    /** The builder string GraalVM writes into every image, measured from porelo's own binaries. */
    private static final String FIXED = "GraalVM CE 25.6.5.1+2.1";

    @TempDir Path directory;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    @Test
    void passesAnImageThatAsksForTheBaseline() throws IOException {
        image("porelo", V1);

        assertThat(check("porelo")).isNull();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("porelo runs on any x86-64 CPU");
    }

    @Test
    void failsNamingEachFeatureBeyondTheBaseline() throws IOException {
        image("porelod", V3);

        assertThat(check("porelod"))
                .contains("porelod is not built for x86-64 v1: needs [AVX, AVX2, BMI1")
                .contains("SSE4_2")
                .contains("lacks []")
                .contains("-march=x86-64");
    }

    @Test
    void checksEveryImageNotOnlyTheFirst() throws IOException {
        image("porelo", V1);
        image("porelo-hook-nft", V3);
        Files.writeString(directory.resolve("script"), "#!/bin/sh\n");

        assertThat(check("script", "porelo", "porelo-hook-nft"))
                .contains("script: no 'required by the image' list")
                .contains("porelo-hook-nft is not built")
                .doesNotContain("porelo is not built");
    }

    @Test
    void failsAnImageBuiltForV2() throws IOException {
        // v2 has no AVX: a check for "no v3" alone would let this through.
        image("porelo", V1 + ", SSE3, SSSE3, SSE4_1, SSE4_2, POPCNT");

        assertThat(check("porelo"))
                .contains("needs [POPCNT, SSE3, SSE4_1, SSE4_2, SSSE3] beyond it");
    }

    @Test
    void failsAnImageThatAsksForLessThanTheBaseline() throws IOException {
        // Less is also a changed target: the exact set, not "nothing beyond it".
        image("porelo", "CX8, CMOV, FXSR, MMX, SSE");

        assertThat(check("porelo")).contains("needs [] beyond it, lacks [SSE2]");
    }

    @Test
    void anImageWithoutTheListFails() throws IOException {
        Files.write(
                directory.resolve("script"),
                "#!/bin/sh\necho not an image\n".getBytes(StandardCharsets.US_ASCII));

        assertThat(check("script")).contains("no 'required by the image' list");
    }

    @Test
    void readsTheListAmongBinaryBytes() throws IOException {
        final byte[] noise = {0, (byte) 0xff, (byte) 0xc3, 0x7f, 0};
        final byte[] message =
                ("The current machine does not support all of the following CPU features that are required by the image: ["
                                + V1
                                + "].")
                        .getBytes(StandardCharsets.US_ASCII);
        final byte[] binary = new byte[noise.length * 2 + message.length];
        System.arraycopy(noise, 0, binary, 0, noise.length);
        System.arraycopy(message, 0, binary, noise.length, message.length);
        System.arraycopy(noise, 0, binary, noise.length + message.length, noise.length);
        Files.write(directory.resolve("porelo"), binary);

        assertThat(CpuFloor.required(directory.resolve("porelo")))
                .containsExactly("CX8", "CMOV", "FXSR", "MMX", "SSE", "SSE2");
    }

    @Test
    void twoDifferentListsAreNotAnAnswer() throws IOException {
        Files.writeString(
                directory.resolve("porelo"),
                "required by the image: [" + V1 + "]\0required by the image: [" + V3 + "]");

        assertThat(check("porelo")).contains("no 'required by the image' list");
    }

    @Test
    void failsAnImageBuiltByAGraalVmOpenToTheBase64Overflow() throws IOException {
        image("porelo", V1, "GraalVM CE 25.5.5.0.3+1.1");

        assertThat(check("porelo"))
                .as("an image from 25.1 to 25.5 carries the overflowing decoder and must not ship")
                .contains("porelo was built by GraalVM CE 25.5.5.0.3+1.1")
                .contains("25.6");
    }

    @Test
    void failsEveryReleaseFromTheFirstOpenOneToTheLastOpenOne() throws IOException {
        image("first", V1, "GraalVM CE 25.1.0.0.1+1.1");
        image("last", V1, "GraalVM CE 25.5.9.9.9+9.9");

        assertThat(check("first", "last"))
                .as("both ends of the open range are refused")
                .contains("first was built by GraalVM CE 25.1.0.0.1+1.1")
                .contains("last was built by GraalVM CE 25.5.9.9.9+9.9");
    }

    @Test
    void passesAnImageBuiltByTheFixedGraalVm() throws IOException {
        image("porelo", V1, FIXED);

        assertThat(check("porelo")).as("25.6 is the fixed release").isNull();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .as("a passing image names its builder")
                .contains("built by " + FIXED);
    }

    @Test
    void failsAnImageWithoutABuilder() throws IOException {
        Files.writeString(
                directory.resolve("porelo"), "\0\0required by the image: [" + V1 + "].\0");

        assertThat(check("porelo"))
                .as("a builder that cannot be read is refused, never assumed fixed")
                .contains("porelo: no readable builder");
    }

    @Test
    void failsAnImageFromABuilderItCannotPlace() throws IOException {
        image("porelo", V1, "Oracle GraalVM 25.0.1+8.1");

        assertThat(check("porelo"))
                .as("only a GraalVM CE version can be placed against the open range")
                .contains("porelo: no readable builder - found [Oracle GraalVM 25.0.1+8.1]");
    }

    @Test
    void twoDifferentBuildersAreNotAnAnswer() throws IOException {
        Files.writeString(
                directory.resolve("porelo"),
                "required by the image: ["
                        + V1
                        + "]\0com.oracle.svm.core.VM="
                        + FIXED
                        + "\0com.oracle.svm.core.VM=GraalVM CE 25.5.5.0.3+1.1\0");

        assertThat(check("porelo"))
                .as("two builders are refused, naming both")
                .contains(
                        "porelo: no readable builder - found ["
                                + FIXED
                                + ", GraalVM CE 25.5.5.0.3+1.1]");
    }

    @Test
    void passesAFirstReleaseThatLeavesOutItsMinor() throws IOException {
        image("porelo", V1, "GraalVM CE 26+35.1");

        assertThat(check("porelo"))
                .as("a release with no minor is a .0, outside the open range")
                .isNull();
    }

    @Test
    void refusesAVersionTooLongToReadNamingIt() throws IOException {
        image("porelo", V1, "GraalVM CE 999999999999.1.0");
        image("porelod", V1, FIXED);

        assertThat(check("porelo", "porelod"))
                .as(
                        "a number too long to read is a builder it cannot place, and the next image is still checked")
                .contains("porelo: no readable builder - found [GraalVM CE 999999999999.1.0]")
                .doesNotContain("porelod");
    }

    private String check(String... images) {
        return CpuFloor.check(
                directory, List.of(images), new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    private void image(String name, String features) throws IOException {
        image(name, features, FIXED);
    }

    private void image(String name, String features, String builder) throws IOException {
        Files.writeString(
                directory.resolve(name),
                "\0\0required by the image: ["
                        + features
                        + "].\0com.oracle.svm.core.VM="
                        + builder
                        + "\0");
    }
}
