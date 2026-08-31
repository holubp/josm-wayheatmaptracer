package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

import javax.imageio.ImageIO;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler;

/** Pure bounded PNG decoder and v0.20.0-compatible content classifier. */
public final class TileDecoderClassifier {
    /** Native source tile dimension preserved from v0.20.0. */
    public static final int TILE_SIZE = 512;
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};

    /** Creates the stateless v0.20-compatible classifier. */
    public TileDecoderClassifier() {
        // Stateless.
    }

    /**
     * Decodes and classifies one successful HTTP response body.
     *
     * @param address tile address supplying palette semantics
     * @param contentType sanitized response content type
     * @param body bounded encoded response
     * @return deterministic decoded/classified tile
     */
    public DecodedTile decodeAndClassify(ManagedTileAddress address, String contentType, byte[] body) {
        if (body == null || body.length == 0) {
            return failure(TileFetchStatus.DECODE_ERROR, "empty-body", "The tile response was empty.");
        }
        String type = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        boolean pngMagic = hasPngMagic(body);
        if ((type.startsWith("text/") || type.equals("application/json") || type.equals("application/xml"))
            || (!type.isEmpty() && !type.equals("image/png") && !type.equals("application/octet-stream"))) {
            return failure(TileFetchStatus.CONTENT_TYPE_ERROR, "content-type", "The tile response was not PNG imagery.");
        }
        if (!pngMagic) {
            return failure(TileFetchStatus.CONTENT_TYPE_ERROR, "png-magic", "The tile response was not PNG imagery.");
        }
        if (body.length < 24 || body[12] != 'I' || body[13] != 'H' || body[14] != 'D' || body[15] != 'R') {
            return failure(TileFetchStatus.DECODE_ERROR, "png-header", "The tile PNG header was invalid.");
        }
        long encodedWidth = unsignedInt(body, 16);
        long encodedHeight = unsignedInt(body, 20);
        if (encodedWidth != TILE_SIZE || encodedHeight != TILE_SIZE) {
            TileQuality quality = new TileQuality("bad-dimensions", (int) Math.min(Integer.MAX_VALUE, encodedWidth),
                (int) Math.min(Integer.MAX_VALUE, encodedHeight), 0.0, 0.0, 0, sha256(body));
            return new DecodedTile(TileFetchStatus.BAD_DIMENSIONS, null, quality,
                "bad-dimensions", "The tile dimensions were not 512x512.");
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(body));
        } catch (IOException ex) {
            return failure(TileFetchStatus.DECODE_ERROR, "decode", "The tile image could not be decoded.");
        }
        if (image == null) {
            return failure(TileFetchStatus.DECODE_ERROR, "decode", "The tile image could not be decoded.");
        }
        if (image.getWidth() != TILE_SIZE || image.getHeight() != TILE_SIZE) {
            TileQuality quality = new TileQuality("bad-dimensions", image.getWidth(), image.getHeight(),
                0.0, 0.0, 0, sha256(body));
            return new DecodedTile(TileFetchStatus.BAD_DIMENSIONS, image, quality,
                "bad-dimensions", "The tile dimensions were not 512x512.");
        }
        int nonTransparent = 0;
        int heatPixels = 0;
        Set<Integer> sampledColors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y += 8) {
            for (int x = 0; x < image.getWidth(); x += 8) {
                int argb = image.getRGB(x, y);
                if (((argb >>> 24) & 0xff) > 16) {
                    nonTransparent++;
                }
                int red = (argb >>> 16) & 0xff;
                int green = (argb >>> 8) & 0xff;
                int blue = argb & 0xff;
                if (RenderedHeatmapSampler.colorIntensity(red, green, blue, address.color()) > 0.16) {
                    heatPixels++;
                }
                sampledColors.add(argb);
            }
        }
        int samples = Math.max(1, (image.getWidth() / 8) * (image.getHeight() / 8));
        double opaqueRatio = nonTransparent / (double) samples;
        double heatCoverage = heatPixels / (double) samples;
        boolean placeholder = opaqueRatio > 0.92 && heatCoverage < 0.003 && sampledColors.size() <= 24;
        String label = placeholder ? "placeholder-suspected" : heatCoverage == 0.0 ? "empty-valid" : "valid";
        TileQuality quality = new TileQuality(label, image.getWidth(), image.getHeight(), opaqueRatio,
            heatCoverage, sampledColors.size(), sha256(body));
        if (placeholder) {
            return new DecodedTile(TileFetchStatus.PLACEHOLDER_SUSPECTED, image, quality,
                "placeholder", "The tile looked like an authentication or error placeholder.");
        }
        return new DecodedTile(TileFetchStatus.SUCCESS_NETWORK, image, quality, "", "");
    }

    private DecodedTile failure(TileFetchStatus status, String code, String message) {
        return new DecodedTile(status, null, TileQuality.unavailable(code), code, message);
    }

    private boolean hasPngMagic(byte[] body) {
        if (body.length < PNG_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            if (body[i] != PNG_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private long unsignedInt(byte[] bytes, int offset) {
        return ((long) bytes[offset] & 0xffL) << 24
            | ((long) bytes[offset + 1] & 0xffL) << 16
            | ((long) bytes[offset + 2] & 0xffL) << 8
            | ((long) bytes[offset + 3] & 0xffL);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }
}
