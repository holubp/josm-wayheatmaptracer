package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.Objects;

/**
 * Immutable, fully resolved product invocation for one modern attempt.
 *
 * <p>The resolved source is captured once. Later preference changes cannot silently change the
 * source used for capture, confirmation, or Apply.</p>
 */
public record ModernAlignmentInvocation(
    TrackerMode engine,
    AlignmentSourceMode requestedSourceMode,
    AlignmentSourceMode resolvedSourceMode,
    AlignmentConfig config,
    RecoverySettings recovery
) {
    /** Validates one detached invocation. */
    public ModernAlignmentInvocation {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(requestedSourceMode, "requestedSourceMode");
        Objects.requireNonNull(resolvedSourceMode, "resolvedSourceMode");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(recovery, "recovery");
        if (resolvedSourceMode == AlignmentSourceMode.AUTOMATIC) {
            throw new IllegalArgumentException("An invocation must contain a resolved source");
        }
    }

    /** Resolves a source from the saved policy and the actual attempt capabilities. */
    public static ModernAlignmentInvocation resolve(TracingSettings settings, AlignmentConfig config,
            boolean managedSourceSupported) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(config, "config");
        AlignmentSourceMode resolved = settings.sourceMode().resolve(
            config.heatmap().hasManagedAccessValues(), managedSourceSupported);
        return new ModernAlignmentInvocation(settings.engine(), settings.sourceMode(), resolved, config,
            settings.recovery());
    }
}
