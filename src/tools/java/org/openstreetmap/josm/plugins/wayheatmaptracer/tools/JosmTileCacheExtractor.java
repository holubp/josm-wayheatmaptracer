package org.openstreetmap.josm.plugins.wayheatmaptracer.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.jcs3.auxiliary.disk.block.BlockDiskCache;
import org.apache.commons.jcs3.auxiliary.disk.block.BlockDiskCacheAttributes;
import org.apache.commons.jcs3.engine.behavior.ICacheElement;
import org.openstreetmap.josm.data.cache.BufferedImageCacheEntry;

public final class JosmTileCacheExtractor {
    private JosmTileCacheExtractor() {
    }

    public static void main(String[] args) throws Exception {
        Arguments cli = Arguments.parse(args);
        Path cacheDir = cli.cacheDir().toAbsolutePath().normalize();

        if (!Files.isDirectory(cacheDir)) {
            throw new IllegalArgumentException("Cache directory does not exist: " + cacheDir);
        }
        if (!Files.exists(cacheDir.resolve("TMS_BLOCK_v2.key")) || !Files.exists(cacheDir.resolve("TMS_BLOCK_v2.data"))) {
            throw new IllegalArgumentException("Cache directory does not contain TMS_BLOCK_v2.key and TMS_BLOCK_v2.data: " + cacheDir);
        }

        BlockDiskCache<String, BufferedImageCacheEntry> cache = openCache(cacheDir);
        try {
            if (cli.listPrefixes()) {
                listPrefixes(cache);
                return;
            }
            if (cli.prefix() == null || cli.prefix().isBlank()) {
                throw new IllegalArgumentException("Provide --prefix <layer-prefix> or use --list-prefixes first.");
            }
            extract(cache, cli);
        } finally {
            cache.dispose();
        }
    }

    private static BlockDiskCache<String, BufferedImageCacheEntry> openCache(Path cacheDir) {
        BlockDiskCacheAttributes attributes = new BlockDiskCacheAttributes();
        attributes.setDiskPath(cacheDir.toString());
        attributes.setCacheName("TMS_BLOCK_v2");
        attributes.setBlockSizeBytes(4096);
        attributes.setMaxKeySize((int) Math.max(1024, sizeKb(cacheDir.resolve("TMS_BLOCK_v2.data"))));
        return new BlockDiskCache<>(attributes);
    }

    private static long sizeKb(Path file) {
        try {
            return Math.max(1, Files.size(file) / 1024);
        } catch (IOException e) {
            return 1024;
        }
    }

    private static void listPrefixes(BlockDiskCache<String, BufferedImageCacheEntry> cache) throws IOException {
        Set<String> prefixes = new LinkedHashSet<>();
        for (String key : cache.getKeySet()) {
            prefixes.add(prefixOf(key));
        }
        prefixes.stream().sorted().forEach(System.out::println);
        System.out.println();
        System.out.println("Found " + prefixes.size() + " distinct TMS layer prefixes.");
    }

    private static void extract(BlockDiskCache<String, BufferedImageCacheEntry> cache, Arguments cli) throws IOException {
        Pattern filter = cli.regex()
            ? Pattern.compile(cli.prefix())
            : Pattern.compile("^" + Pattern.quote(cli.prefix()) + ":.*");
        Path outDir = cli.outDir().toAbsolutePath().normalize();
        Files.createDirectories(outDir);

        List<String> keys = new ArrayList<>();
        for (String key : cache.getKeySet()) {
            if (filter.matcher(key).matches()) {
                keys.add(key);
            }
        }
        keys.sort(Comparator.naturalOrder());

        int extracted = 0;
        int skipped = 0;
        for (String key : keys) {
            if (cli.limit() > 0 && extracted >= cli.limit()) {
                break;
            }
            ICacheElement<String, BufferedImageCacheEntry> element = cache.get(key);
            if (element == null || element.getVal() == null || element.getVal().getContent() == null) {
                skipped++;
                continue;
            }
            byte[] content = element.getVal().getContent();
            if (content.length == 0) {
                skipped++;
                continue;
            }

            String extension = detectExtension(content);
            Path target = outDir.resolve(sanitizeKey(key) + "." + extension);
            Files.write(target, content);
            extracted++;
        }

        System.out.println("Matched " + keys.size() + " cache keys.");
        System.out.println("Extracted " + extracted + " tile files to " + outDir);
        if (skipped > 0) {
            System.out.println("Skipped " + skipped + " keys with empty or unreadable content.");
        }
    }

    private static String prefixOf(String key) {
        int index = key.indexOf(':');
        return index >= 0 ? key.substring(0, index) : key;
    }

    private static String sanitizeKey(String key) {
        return key.replace(':', '/')
            .replaceAll("[\\\\/]+", "_")
            .replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String detectExtension(byte[] content) {
        if (content.length >= 8
            && (content[0] & 0xFF) == 0x89
            && content[1] == 0x50
            && content[2] == 0x4E
            && content[3] == 0x47) {
            return "png";
        }
        if (content.length >= 3
            && (content[0] & 0xFF) == 0xFF
            && (content[1] & 0xFF) == 0xD8
            && (content[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        return "bin";
    }

    private record Arguments(Path cacheDir, Path outDir, String prefix, boolean regex, int limit, boolean listPrefixes) {
        private static Arguments parse(String[] args) {
            Path cacheDir = null;
            Path outDir = Path.of("build", "extracted-tiles");
            String prefix = null;
            boolean regex = false;
            int limit = 200;
            boolean listPrefixes = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--cache-dir" -> cacheDir = Path.of(requireValue(args, ++i, arg));
                    case "--out-dir" -> outDir = Path.of(requireValue(args, ++i, arg));
                    case "--prefix" -> prefix = requireValue(args, ++i, arg);
                    case "--regex" -> regex = true;
                    case "--limit" -> limit = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--list-prefixes" -> listPrefixes = true;
                    case "--help" -> {
                        printHelpAndExit();
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            if (cacheDir == null) {
                throw new IllegalArgumentException("Missing required --cache-dir argument.");
            }
            return new Arguments(cacheDir, outDir, prefix, regex, limit, listPrefixes);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        private static void printHelpAndExit() {
            System.out.println("Usage:");
            System.out.println("  sh gradlew extractJosmTmsCache --args='--cache-dir <dir> --list-prefixes'");
            System.out.println("  sh gradlew extractJosmTmsCache --args='--cache-dir <dir> --prefix <layer-prefix> --out-dir <dir> [--limit 200]'");
            System.out.println("  sh gradlew extractJosmTmsCache --args='--cache-dir <dir> --prefix <regex> --regex --out-dir <dir>'");
            System.exit(0);
        }
    }
}
