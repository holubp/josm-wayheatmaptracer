package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;

/** Runs a bounded replay callback only after capability and frozen-identity validation. */
public final class Format15ReplayRunner {
    private Format15ReplayRunner() {
    }

    /** Callback used by production engines or detached test commands during replay. */
    @FunctionalInterface
    public interface ReplayAction<T> {
        /** Executes against a local, immutable, no-network context. */
        T run(Format15ReplayContext context) throws Exception;
    }

    /** Validates the requested level and hashes before invoking the supplied replay operation. */
    public static <T> T replay(Format15Archive archive, ReplayLevel requiredLevel,
        String expectedSourceIdentityHash, String expectedParameterHash, ReplayAction<T> action)
        throws Exception {
        if (archive == null || requiredLevel == null || action == null) {
            throw new IllegalArgumentException("Replay archive, level, and action are required");
        }
        if (!archive.capability().supports(requiredLevel)) {
            throw new ReplayMismatchException("Replay archive lacks required capability: " + requiredLevel);
        }
        if (!archive.sourceIdentityHash().equals(expectedSourceIdentityHash)
            || !archive.parameterHash().equals(expectedParameterHash)) {
            throw new ReplayMismatchException("Replay source or parameter identity does not match");
        }
        return action.run(new Format15ReplayContext(archive));
    }
}
