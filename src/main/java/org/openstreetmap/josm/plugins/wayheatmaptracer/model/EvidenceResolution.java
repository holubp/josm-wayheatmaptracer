package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;
import java.util.OptionalDouble;

/** Physical source, input-rendered, and optional resampled-grid resolution metadata. */
public record EvidenceResolution(Kind kind, OptionalDouble nativePitchMeters, double renderedPitchMeters,
    List<PitchSample> spatialPitchSamples, OptionalDouble resampledPitchMeters) {
    /** Whether native source resolution was observed. */
    public enum Kind { NATIVE_SOURCE, RENDERED_ONLY }

    /** Physical source resolution sampled at one measured route chainage. */
    public record PitchSample(double chainageMeters, OptionalDouble nativePitchMeters,
        double renderedPitchMeters) {
        /** Validates one finite physical sample. */
        public PitchSample {
            if (!Double.isFinite(chainageMeters) || chainageMeters < 0.0 || nativePitchMeters == null
                || nativePitchMeters.isPresent() && (!Double.isFinite(nativePitchMeters.getAsDouble())
                    || nativePitchMeters.getAsDouble() <= 0.0)
                || !Double.isFinite(renderedPitchMeters) || renderedPitchMeters <= 0.0) {
                throw new IllegalArgumentException("Resolution sample is inconsistent");
            }
        }

        /** Returns the source pitch when known, otherwise the input rendered pitch. */
        public double effectivePitchMeters() {
            return nativePitchMeters.orElse(renderedPitchMeters);
        }
    }

    /** Validates physical pitches, spatial identity, chainage origin, and optional output pitch. */
    public EvidenceResolution {
        if (kind == null || nativePitchMeters == null || resampledPitchMeters == null
            || !Double.isFinite(renderedPitchMeters) || renderedPitchMeters <= 0.0
            || kind == Kind.NATIVE_SOURCE && nativePitchMeters.isEmpty()
            || kind == Kind.RENDERED_ONLY && nativePitchMeters.isPresent()
            || nativePitchMeters.isPresent() && (!Double.isFinite(nativePitchMeters.getAsDouble())
                || nativePitchMeters.getAsDouble() <= 0.0)
            || resampledPitchMeters.isPresent()
                && (!Double.isFinite(resampledPitchMeters.getAsDouble())
                    || resampledPitchMeters.getAsDouble() <= 0.0)
            || spatialPitchSamples == null || spatialPitchSamples.isEmpty()) {
            throw new IllegalArgumentException("Evidence resolution is inconsistent");
        }
        spatialPitchSamples = List.copyOf(spatialPitchSamples);
        if (spatialPitchSamples.get(0).chainageMeters() != 0.0
            || !same(nativePitchMeters, spatialPitchSamples.get(0).nativePitchMeters())
            || Double.doubleToLongBits(renderedPitchMeters)
                != Double.doubleToLongBits(spatialPitchSamples.get(0).renderedPitchMeters())) {
            throw new IllegalArgumentException("Representative pitch must equal the chainage-zero sample");
        }
        double previous = -1.0;
        for (PitchSample sample : spatialPitchSamples) {
            if (sample.chainageMeters() <= previous
                || kind == Kind.NATIVE_SOURCE != sample.nativePitchMeters().isPresent()) {
                throw new IllegalArgumentException("Resolution samples must be ordered and match resolution kind");
            }
            previous = sample.chainageMeters();
        }
    }

    /** Constructs source metadata without a separate resampled output grid. */
    public EvidenceResolution(Kind kind, OptionalDouble nativePitchMeters, double renderedPitchMeters,
            List<PitchSample> spatialPitchSamples) {
        this(kind, nativePitchMeters, renderedPitchMeters, spatialPitchSamples, OptionalDouble.empty());
    }

    /** Constructs a spatially constant source resolution without a resampled output grid. */
    public EvidenceResolution(Kind kind, OptionalDouble nativePitchMeters, double renderedPitchMeters) {
        this(kind, nativePitchMeters, renderedPitchMeters,
            List.of(new PitchSample(0.0, nativePitchMeters, renderedPitchMeters)), OptionalDouble.empty());
    }

    /** Creates metadata with independently known native and input-rendered pitches. */
    public static EvidenceResolution nativeSource(double nativePitchMeters, double renderedPitchMeters) {
        return new EvidenceResolution(Kind.NATIVE_SOURCE, OptionalDouble.of(nativePitchMeters), renderedPitchMeters);
    }

    /** Creates metadata where only input-rendered pitch is known. */
    public static EvidenceResolution renderedOnly(double renderedPitchMeters) {
        return new EvidenceResolution(Kind.RENDERED_ONLY, OptionalDouble.empty(), renderedPitchMeters);
    }

    /** Returns a copy that records the chosen output-grid pitch without changing source uncertainty. */
    public EvidenceResolution resampledTo(double outputPitchMeters) {
        return new EvidenceResolution(kind, nativePitchMeters, renderedPitchMeters,
                spatialPitchSamples, OptionalDouble.of(outputPitchMeters));
    }

    /** Returns the pitch of the raster carried by the snapshot, after resampling when present. */
    public double outputRasterPitchMeters() {
        return resampledPitchMeters.orElse(renderedPitchMeters);
    }

    /** Returns the representative factual source uncertainty used for bounded computation. */
    public double effectivePitchMeters() {
        return nativePitchMeters.orElse(renderedPitchMeters);
    }

    /** Returns the linearly interpolated factual source pitch at measured route chainage. */
    public double effectivePitchMetersAt(double chainageMeters) {
        if (!Double.isFinite(chainageMeters) || chainageMeters < 0.0 || !covers(chainageMeters)) {
            throw new IllegalArgumentException("Chainage lies outside the declared resolution support");
        }
        if (spatialPitchSamples.size() == 1) {
            return spatialPitchSamples.get(0).effectivePitchMeters();
        }
        for (int index = 1; index < spatialPitchSamples.size(); index++) {
            PitchSample right = spatialPitchSamples.get(index);
            if (chainageMeters <= right.chainageMeters()) {
                PitchSample left = spatialPitchSamples.get(index - 1);
                double fraction = (chainageMeters - left.chainageMeters())
                    / (right.chainageMeters() - left.chainageMeters());
                return left.effectivePitchMeters()
                    + fraction * (right.effectivePitchMeters() - left.effectivePitchMeters());
            }
        }
        return spatialPitchSamples.get(spatialPitchSamples.size() - 1).effectivePitchMeters();
    }

    /** Returns whether the resolution declaration covers the complete measured profile chainage. */
    public boolean covers(ProfileChainage chainage) {
        return chainage != null && covers(chainage.sourceOriginGroundMeters()
            + chainage.cumulativeGroundMeters().get(chainage.cumulativeGroundMeters().size() - 1));
    }

    private boolean covers(double chainageMeters) {
        return spatialPitchSamples.size() == 1
            || chainageMeters <= spatialPitchSamples.get(spatialPitchSamples.size() - 1).chainageMeters() + 1e-12;
    }

    /** Returns whether native-resolution quality claims require review. */
    public boolean requiresResolutionReview() {
        return kind == Kind.RENDERED_ONLY;
    }

    private static boolean same(OptionalDouble left, OptionalDouble right) {
        return left.isPresent() == right.isPresent()
            && (left.isEmpty() || Double.doubleToLongBits(left.getAsDouble())
                == Double.doubleToLongBits(right.getAsDouble()));
    }
}
