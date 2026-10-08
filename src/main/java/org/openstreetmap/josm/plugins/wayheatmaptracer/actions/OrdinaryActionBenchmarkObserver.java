package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import java.io.IOException;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.DiagnosticsRegistry;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentJob;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileRuntime;

/**
 * Opt-in observation of the real JOSM action. The clock stops only after the
 * production preview dialog has been made visible. Diagnostic export follows
 * the clock, so private archive I/O is not charged to normal preview latency.
 */
public final class OrdinaryActionBenchmarkObserver {
    private static final String OUTPUT_PROPERTY = "wayheatmaptracer.benchmark.receipt";
    private static long startedNanos;
    private static long cancelRequestedNanos;

    private OrdinaryActionBenchmarkObserver() { }

    static void actionStarted() {
        if (System.getProperty(OUTPUT_PROPERTY) != null) {
            startedNanos = System.nanoTime();
            cancelRequestedNanos = 0;
        }
    }

    /** Marks the host's actual request to close the progress window. */
    public static void cancellationRequested() {
        if ("cancel".equals(System.getProperty("wayheatmaptracer.benchmark.mode"))) {
            cancelRequestedNanos = System.nanoTime();
        }
    }

    static void actionCancelled(AlignmentJob.Attempt<?> attempt) {
        String destination = System.getProperty(OUTPUT_PROPERTY);
        if (destination == null || !"cancel".equals(
                System.getProperty("wayheatmaptracer.benchmark.mode"))) return;
        long terminal = System.nanoTime();
        if (cancelRequestedNanos <= startedNanos || terminal <= cancelRequestedNanos
                || attempt == null || attempt.state() != AlignmentJob.State.CANCELLED) {
            throw new IllegalStateException("Benchmark cancellation was not terminal");
        }
        Path receipt = Path.of(destination).toAbsolutePath().normalize();
        Path archive = receipt.resolveSibling(receipt.getFileName() + ".format15.zip");
        try {
            Files.createDirectories(receipt.getParent());
            DiagnosticsRegistry.writeLatest(archive.toFile());
            String json = "{\"producer\":\"AlignWayAction.production-cancel-v1\","
                    + "\"nonce\":\"" + requiredToken("wayheatmaptracer.benchmark.nonce")
                    + "\",\"caseId\":\"" + requiredToken("wayheatmaptracer.benchmark.case")
                    + "\",\"version\":\"" + requiredToken("wayheatmaptracer.benchmark.version")
                    + "\",\"terminalState\":\"CANCELLED\",\"noPreviewPublication\":true,"
                    + "\"cancelNanos\":" + (terminal - cancelRequestedNanos)
                    + ",\"diagnosticsSha256\":\"" + fileHash(archive)
                    + "\",\"diagnosticsFile\":\"" + archive.getFileName() + "\"}\n";
            writeReceipt(receipt, json);
        } catch (Exception failure) {
            throw new IllegalStateException("Benchmark cancellation receipt could not be written", failure);
        }
    }

    static void previewReady(String kind, LiveBPreviewService.Computed computed,
            Supplier<List<GeographicPoint>> geometrySupplier) {
        String destination = System.getProperty(OUTPUT_PROPERTY);
        if (destination == null) return;
        long stoppedNanos = System.nanoTime();
        List<GeographicPoint> finalGeometry = geometrySupplier.get();
        if (startedNanos <= 0 || stoppedNanos <= startedNanos || finalGeometry == null
                || finalGeometry.size() < 2) {
            throw new IllegalStateException("Benchmark preview lacked action start or final geometry");
        }
        Path receipt = Path.of(destination).toAbsolutePath().normalize();
        Path archive = receipt.resolveSibling(receipt.getFileName() + ".format15.zip");
        Path tiles = receipt.resolveSibling(receipt.getFileName() + ".tiles.json");
        try {
            Files.createDirectories(receipt.getParent());
            DiagnosticsRegistry.writeLatest(archive.toFile());
            Files.writeString(tiles, ManagedTileRuntime.diagnosticsJsonIfInitialized(),
                    StandardCharsets.UTF_8);
            String json = "{\"producer\":\"AlignWayAction.production-preview-v1\","
                    + "\"nonce\":\"" + requiredToken("wayheatmaptracer.benchmark.nonce")
                    + "\",\"caseId\":\"" + requiredToken("wayheatmaptracer.benchmark.case")
                    + "\",\"version\":\"" + requiredToken("wayheatmaptracer.benchmark.version")
                    + "\",\"kind\":\"" + kind + "\",\"totalNanos\":"
                    + (stoppedNanos - startedNanos) + ",\"finalGeometrySha256\":\""
                    + geometryHash(finalGeometry) + "\",\"selectedRasterSha256\":\""
                    + rasterHash(computed) + "\",\"diagnosticsSha256\":\""
                    + fileHash(archive) + "\",\"diagnosticsFile\":\""
                    + archive.getFileName() + "\",\"tileDiagnosticsSha256\":\""
                    + fileHash(tiles) + "\",\"tileDiagnosticsFile\":\""
                    + tiles.getFileName() + "\"}\n";
            writeReceipt(receipt, json);
        } catch (Exception failure) {
            throw new IllegalStateException("Benchmark receipt could not be written", failure);
        } finally {
            startedNanos = 0;
        }
    }

    private static String requiredToken(String key) {
        String value = System.getProperty(key, "");
        if (!value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalStateException("Benchmark run binding is missing or invalid");
        }
        return value;
    }

    private static void writeReceipt(Path receipt, String json) throws IOException {
        Path temporary = Files.createTempFile(receipt.getParent(), ".benchmark-", ".tmp");
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            Files.move(temporary, receipt, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String geometryHash(List<GeographicPoint> points) {
        MessageDigest digest = digest();
        addLong(digest, points.size());
        for (GeographicPoint point : points) {
            addLong(digest, Double.doubleToRawLongBits(point.latitudeDegrees()));
            addLong(digest, Double.doubleToRawLongBits(point.longitudeDegrees()));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String rasterHash(LiveBPreviewService.Computed computed) {
        if (computed.captured().managedRaster() == null) {
            throw new IllegalStateException("Benchmark requires managed source raster");
        }
        BufferedImage image = computed.captured().managedRaster().image();
        boolean[] validity = computed.captured().managedRaster().validity();
        MessageDigest digest = digest();
        addLong(digest, image.getWidth());
        addLong(digest, image.getHeight());
        for (int pixel : image.getRGB(0, 0, image.getWidth(), image.getHeight(), null,
                0, image.getWidth())) addLong(digest, pixel);
        for (boolean valid : validity) digest.update((byte) (valid ? 1 : 0));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String fileHash(Path path) throws IOException {
        return HexFormat.of().formatHex(digest().digest(Files.readAllBytes(path)));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void addLong(MessageDigest digest, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            digest.update((byte) (value >>> shift));
        }
    }
}
