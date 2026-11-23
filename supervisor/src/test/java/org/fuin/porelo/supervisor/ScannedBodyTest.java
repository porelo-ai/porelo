package org.fuin.porelo.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link ScannedBody}: what it hands on before it has judged the end, and how it reads
 * the framing an agent chose.
 */
class ScannedBodyTest {

    private static final long LIMIT = 1024 * 1024;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "grant_type=refresh_token",
                "grant_type=refresh%5Ftoken",
                "grant_type=refresh\\u005ftoken"
            })
    void aGrantThatTheLastByteCompletesIsRefusedBeforeThatByteIsHandedOn(final String grant)
            throws IOException {

        // Handed on as soon as it was read, the last byte would complete the request upstream
        // before the scan had seen the grant it ends.
        final byte[] body = padded(grant);
        final ScannedBody read = body(body, body.length, null);
        final ByteArrayOutputStream handed = new ByteArrayOutputStream();

        assertThatThrownBy(() -> copy(read, handed)).isInstanceOf(ScannedBody.Refused.class);
        assertThat(read.verdict()).isEqualTo(VaultProxy.Reading.MINTS);
        assertThat(handed.size()).isLessThan(body.length);
    }

    @Test
    void aCleanBodyIsHandedOnWholeAndExactly() throws IOException {

        final byte[] body = padded("grant_type=authorization_code");
        final ByteArrayOutputStream handed = new ByteArrayOutputStream();
        copy(body(body, body.length, null), handed);

        assertThat(handed.toByteArray()).isEqualTo(body);
    }

    @Test
    void chunksWithExtensionsAndTrailersAreReadAsTheirData() throws IOException {

        final String chunked = "5;name=value\r\nhello\r\n6\r\n world\r\n0\r\nx-trailer: t\r\n\r\n";
        final ByteArrayOutputStream handed = new ByteArrayOutputStream();
        copy(body(chunked.getBytes(StandardCharsets.US_ASCII), -1, null), handed);

        assertThat(handed.toString(StandardCharsets.US_ASCII)).isEqualTo("hello world");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "5\r\nhello\r\n",
                "5\r\nhello\r\n0\r\n",
                "5\r\nhelloXX6\r\n world\r\n0\r\n\r\n",
                "-5\r\nhello\r\n0\r\n\r\n",
                "+5\r\nhello\r\n0\r\n\r\n",
                "10000000000000000\r\n"
            })
    void aChunkedBodyThatBreaksItsFramingIsRefused(final String chunked) {

        final ScannedBody read = body(chunked.getBytes(StandardCharsets.US_ASCII), -1, null);

        assertThatThrownBy(() -> copy(read, OutputStreamSink.INSTANCE))
                .isInstanceOf(ScannedBody.Refused.class);
        assertThat(read.verdict()).isEqualTo(VaultProxy.Reading.BROKEN);
    }

    @Test
    void aBodyShorterThanItsLengthIsRefusedNotHandedOnShort() {

        final ScannedBody read = body("short".getBytes(StandardCharsets.US_ASCII), 10, null);

        assertThatThrownBy(() -> copy(read, OutputStreamSink.INSTANCE))
                .isInstanceOf(ScannedBody.Refused.class);
        assertThat(read.verdict()).isEqualTo(VaultProxy.Reading.BROKEN);
    }

    @Test
    void aBodyThatUnpacksPastTheBoundIsRefusedWhileItIsRead() throws IOException {

        final ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(zipped)) {
            gzip.write(new byte[(int) LIMIT + 1]);
        }
        final ScannedBody read = body(zipped.toByteArray(), zipped.size(), "gzip");

        assertThatThrownBy(() -> copy(read, OutputStreamSink.INSTANCE))
                .isInstanceOf(ScannedBody.Refused.class);
        assertThat(read.verdict()).isEqualTo(VaultProxy.Reading.TOO_LARGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gzip", "deflate", "zstd"})
    void bytesAfterACodedBodyAreRefusedRatherThanPassedOnUnread(final String coding)
            throws IOException {

        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(code(coding, "{}".getBytes(StandardCharsets.US_ASCII)));
        body.writeBytes("&more".getBytes(StandardCharsets.US_ASCII));
        final ScannedBody read = body(body.toByteArray(), body.size(), coding);

        assertThatThrownBy(() -> copy(read, OutputStreamSink.INSTANCE))
                .isInstanceOf(ScannedBody.Refused.class);
        assertThat(read.verdict()).isEqualTo(VaultProxy.Reading.UNREADABLE);
    }

    @Test
    void aGzipMemberWhoseHeaderDoesNotCheckIsRefusedRatherThanPassedOnUnread() throws IOException {

        // Its header names a checksum, and the checksum is wrong. A decoder that gives up on such
        // a member at the end of the one before and calls it the end passes it on unread; one that
        // does not check the header's sum - Python's - reads the grant inside it.
        final byte[] grant = "grant_type=client_credentials".getBytes(StandardCharsets.US_ASCII);
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(code("gzip", "{}".getBytes(StandardCharsets.US_ASCII)));
        final byte[] header = {0x1f, (byte) 0x8b, 8, 2, 0, 0, 0, 0, 0, (byte) 0xff};
        final java.util.zip.CRC32 sum = new java.util.zip.CRC32();
        sum.update(header);
        final int wrong = (int) sum.getValue() & 0xffff ^ 0x5a5a;
        body.writeBytes(header);
        body.write(wrong & 0xff);
        body.write(wrong >>> 8);
        final java.util.zip.Deflater raw = new java.util.zip.Deflater(9, true);
        raw.setInput(grant);
        raw.finish();
        final byte[] deflated = new byte[256];
        body.write(deflated, 0, raw.deflate(deflated));
        raw.end();
        final java.util.zip.CRC32 data = new java.util.zip.CRC32();
        data.update(grant);
        littleEndian(body, data.getValue());
        littleEndian(body, grant.length);
        final ScannedBody read = body(body.toByteArray(), body.size(), "gzip");

        assertThatThrownBy(() -> copy(read, OutputStreamSink.INSTANCE))
                .isInstanceOf(ScannedBody.Refused.class);
        assertThat(read.verdict()).isEqualTo(VaultProxy.Reading.UNREADABLE);
    }

    @Test
    void severalWholeGzipMembersAreReadAsOneBody() throws IOException {

        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(code("gzip", "{\"a\":".getBytes(StandardCharsets.US_ASCII)));
        body.writeBytes(code("gzip", new byte[0]));
        body.writeBytes(code("gzip", "1}".getBytes(StandardCharsets.US_ASCII)));
        final ByteArrayOutputStream handed = new ByteArrayOutputStream();
        copy(body(body.toByteArray(), body.size(), "gzip"), handed);
        assertThat(handed.toByteArray()).isEqualTo(body.toByteArray());

        body.writeBytes(
                code("gzip", "grant_type=refresh_token".getBytes(StandardCharsets.US_ASCII)));
        final ScannedBody read = body(body.toByteArray(), body.size(), "gzip");
        assertThatThrownBy(() -> copy(read, OutputStreamSink.INSTANCE))
                .isInstanceOf(ScannedBody.Refused.class);
        assertThat(read.verdict()).isEqualTo(VaultProxy.Reading.MINTS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gzip", "deflate"})
    void aGrantThatADecoderYieldsOnlyAfterTheLastCodedByteIsRefusedBeforeThatByteIsHandedOn(
            final String coding) throws IOException {

        // The whole coded body is read before the decoder hands out the part of its output that
        // holds the grant: only the last byte, still held, keeps the destination from completing.
        final byte[] plain =
                ("pad=" + "x".repeat(4 * ScannedBody.CHUNK) + "&grant_type=client_credentials")
                        .getBytes(StandardCharsets.US_ASCII);
        final byte[] body = code(coding, plain);
        assertThat(body.length).isLessThan(ScannedBody.CHUNK / 8);
        final ScannedBody read = body(body, body.length, coding);
        final ByteArrayOutputStream handed = new ByteArrayOutputStream();

        assertThatThrownBy(() -> copy(read, handed)).isInstanceOf(ScannedBody.Refused.class);
        assertThat(read.verdict()).isEqualTo(VaultProxy.Reading.MINTS);
        assertThat(handed.size()).isLessThan(body.length);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gzip", "zstd"})
    void aBodyThatUnpacksToFarMoreThanItCarriesIsRefusedLongBeforeTheBound(final String coding)
            throws IOException {

        // A few kilobytes that unpack to megabytes: each byte the agent sends may cost no more
        // than a fixed amount of decoding and scanning, whatever the bound on the whole body.
        final byte[] coded = code(coding, new byte[16 * 1024 * 1024]);
        final ScannedBody read =
                new ScannedBody(
                        new ByteArrayInputStream(coded),
                        coded.length,
                        coding,
                        VaultProxy.BODY_LIMIT,
                        ZstdFrames.WINDOW_LIMIT);

        assertThatThrownBy(() -> copy(read, OutputStreamSink.INSTANCE))
                .isInstanceOf(ScannedBody.Refused.class)
                .hasMessageContaining("times");
        assertThat(read.verdict()).isEqualTo(VaultProxy.Reading.TOO_LARGE);
    }

    @Test
    void textThatCompressesWellIsStillHandedOn() throws IOException {

        final StringBuilder records = new StringBuilder();
        for (int i = 0; i < 64 * 1024; i++) {
            records.append("{\"id\":").append(i).append(",\"name\":\"value\",\"list\":[1,2]}\n");
        }
        final byte[] plain = records.toString().getBytes(StandardCharsets.US_ASCII);
        for (final String coding : new String[] {"gzip", "deflate", "zstd"}) {
            final byte[] coded = code(coding, plain);
            final ByteArrayOutputStream handed = new ByteArrayOutputStream();
            copy(
                    new ScannedBody(
                            new ByteArrayInputStream(coded),
                            coded.length,
                            coding,
                            VaultProxy.BODY_LIMIT,
                            ZstdFrames.WINDOW_LIMIT),
                    handed);
            assertThat(handed.toByteArray()).as(coding).isEqualTo(coded);
        }
    }

    @Test
    void aZstdFrameThatNamesAWindowPastTheBoundIsRefused() {

        // A frame header alone: magic, a descriptor without a single segment, and a window
        // descriptor naming 2^(10+21) bytes.
        final byte[] frame = {
            (byte) 0x28, (byte) 0xB5, (byte) 0x2F, (byte) 0xFD, 0x00, (byte) (21 << 3), 0, 0, 0
        };
        final ScannedBody read = body(frame, frame.length, "zstd");

        assertThatThrownBy(() -> copy(read, OutputStreamSink.INSTANCE))
                .isInstanceOf(ScannedBody.Refused.class);
        assertThat(read.verdict()).isEqualTo(VaultProxy.Reading.UNSUPPORTED);
    }

    @Test
    void aZstdBodyOfSeveralFramesIsReadToItsEnd() throws IOException {

        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(zstd("{\"a\":".getBytes(StandardCharsets.US_ASCII)));
        body.writeBytes(zstd("1}".getBytes(StandardCharsets.US_ASCII)));
        final ByteArrayOutputStream handed = new ByteArrayOutputStream();
        copy(body(body.toByteArray(), body.size(), "zstd"), handed);
        assertThat(handed.toByteArray()).isEqualTo(body.toByteArray());

        final ByteArrayOutputStream minting = new ByteArrayOutputStream();
        minting.writeBytes(zstd("{\"a\":1}".getBytes(StandardCharsets.US_ASCII)));
        minting.writeBytes(zstd("grant_type=refresh_token".getBytes(StandardCharsets.US_ASCII)));
        final ScannedBody read = body(minting.toByteArray(), minting.size(), "zstd");
        assertThatThrownBy(() -> copy(read, OutputStreamSink.INSTANCE))
                .isInstanceOf(ScannedBody.Refused.class);
        assertThat(read.verdict()).isEqualTo(VaultProxy.Reading.MINTS);
    }

    @Test
    void theScanFindsAGrantOnlyWhereThePatternWould() {

        assertThat(GrantScan.names("grant_type = \"refresh_token\"")).isTrue();
        assertThat(GrantScan.names("grant_type=refresh+token")).isFalse();
        assertThat(GrantScan.names("grant_type%3Drefresh_token")).isTrue();
        assertThat(GrantScan.names("grant_type=%zz&grant_type=refresh%5ftoken")).isTrue();
        assertThat(GrantScan.names("grant\\u005ftype=refresh_token")).isTrue();
        // An escaped backslash before the escape: it reads as "grant\_type", which asks nothing.
        assertThat(GrantScan.names("grant\\\\u005ftype=refresh_token")).isFalse();
        assertThat(GrantScan.names("grant\\u005Gtype=refresh_token")).isFalse();
        assertThat(GrantScan.names("grant_types=refresh_token")).isFalse();
        assertThat(GrantScan.names("grant_type=Refresh_token")).isFalse();
    }

    private static ScannedBody body(final byte[] bytes, final long length, final String coding) {
        return new ScannedBody(
                new ByteArrayInputStream(bytes), length, coding, LIMIT, ZstdFrames.WINDOW_LIMIT);
    }

    private static byte[] padded(final String grant) {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(
                ("pad=" + "x".repeat(3 * ScannedBody.CHUNK) + "&")
                        .getBytes(StandardCharsets.US_ASCII));
        body.writeBytes(grant.getBytes(StandardCharsets.US_ASCII));
        return body.toByteArray();
    }

    private static void copy(final ScannedBody from, final java.io.OutputStream to)
            throws IOException {
        final byte[] buffer = new byte[8192];
        int read;
        while ((read = from.read(buffer)) != -1) {
            to.write(buffer, 0, read);
        }
    }

    private static byte[] code(final String coding, final byte[] plain) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        switch (coding) {
            case "gzip" -> {
                try (java.io.OutputStream gzip = new java.util.zip.GZIPOutputStream(out)) {
                    gzip.write(plain);
                }
            }
            case "deflate" -> {
                try (java.io.OutputStream deflate = new java.util.zip.DeflaterOutputStream(out)) {
                    deflate.write(plain);
                }
            }
            default -> out.writeBytes(zstd(plain));
        }
        return out.toByteArray();
    }

    private static void littleEndian(final ByteArrayOutputStream out, final long value) {
        for (int i = 0; i < 4; i++) {
            out.write((int) (value >>> 8 * i) & 0xff);
        }
    }

    private static byte[] zstd(final byte[] plain) {
        final io.airlift.compress.v3.zstd.ZstdCompressor compressor =
                io.airlift.compress.v3.zstd.ZstdCompressor.create();
        final byte[] out = new byte[compressor.maxCompressedLength(plain.length)];
        final int size = compressor.compress(plain, 0, plain.length, out, 0, out.length);
        return java.util.Arrays.copyOf(out, size);
    }

    /** Where bytes go that nobody reads. */
    private static final class OutputStreamSink {
        static final java.io.OutputStream INSTANCE = java.io.OutputStream.nullOutputStream();
    }
}
