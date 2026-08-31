package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** Generation-scoped positive disk cache with validated reads and atomic writes. */
public final class ManagedTileCache {
    private final Path root;
    private final TileDecoderClassifier classifier;
    private final int maximumBodyBytes;

    /**
     * Creates a cache rooted at the plugin managed-source tile directory.
     *
     * @param root cache root retaining the legacy generation layout
     * @param classifier shared validated tile classifier
     */
    public ManagedTileCache(Path root, TileDecoderClassifier classifier) {
        this(root, classifier, TileReliabilityPolicy.defaults().maximumBodyBytes());
    }

    /**
     * Creates a cache with an explicit encoded-entry limit.
     *
     * @param root cache root retaining the legacy generation layout
     * @param classifier shared validated tile classifier
     * @param maximumBodyBytes maximum encoded cache entry size
     */
    public ManagedTileCache(Path root, TileDecoderClassifier classifier, int maximumBodyBytes) {
        this.root = root;
        this.classifier = classifier;
        this.maximumBodyBytes = maximumBodyBytes;
    }

    /**
     * Reads and validates an existing v0.20.0-compatible cache entry.
     *
     * @param request tile identity and generation
     * @return validated cache result, or empty when absent/corrupt
     */
    public Optional<TileFetchResult> read(TileRequest request) {
        Path file = path(request.generation(), request.address());
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            if (Files.size(file) > maximumBodyBytes) {
                Files.deleteIfExists(file);
                return Optional.empty();
            }
            byte[] bytes = Files.readAllBytes(file);
            DecodedTile decoded = classifier.decodeAndClassify(request.address(), "image/png", bytes);
            if (!decoded.usable()) {
                Files.deleteIfExists(file);
                return Optional.empty();
            }
            return Optional.of(new TileFetchResult(request.address(), request.generation(), request.purpose(),
                TileFetchStatus.SUCCESS_DISK_CACHE, decoded.image(), bytes, 200, "image/png", null, 0,
                null, decoded.quality(), "", ""));
        } catch (IOException ex) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // A later request can retry validation; never expose path details.
            }
            return Optional.empty();
        }
    }

    /**
     * Atomically writes one already validated positive tile.
     *
     * @param request tile identity and generation
     * @param bytes validated PNG bytes
     */
    public void write(TileRequest request, byte[] bytes) {
        Path target = path(request.generation(), request.address());
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".whtr-", ".tmp");
            try {
                Files.write(temporary, bytes);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException ex) {
            // Cache failure must not turn a valid network tile into an alignment failure.
        }
    }

    /**
     * Returns the credential-free legacy-compatible cache path.
     *
     * @param generation cache generation
     * @param address tile address
     * @return cache file path
     */
    public Path path(ManagedTileGeneration generation, ManagedTileAddress address) {
        return root.resolve("cache-" + generation.value()).resolve(address.activity()).resolve(address.color())
            .resolve(Integer.toString(address.zoom())).resolve(Integer.toString(address.x()))
            .resolve(address.y() + ".png");
    }
}
