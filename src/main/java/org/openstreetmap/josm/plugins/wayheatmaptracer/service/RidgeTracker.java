package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateEvidence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/**
 * Links cross-section heatmap peaks into one or more longitudinal ridge candidates.
 *
 * <p>The tracker treats every cross-section peak as a noisy observation of lateral offset from the selected
 * way. Dynamic programming keeps candidates continuous, anchor solving stabilizes reliable intervals, and
 * a final source-pixel-aware smoother suppresses aliasing wiggles without turning sustained bends into
 * straight lines.</p>
 */
public final class RidgeTracker {
    /** Creates a legacy ridge tracker with no retained mutable state. */
    public RidgeTracker() {
        // Stateless service.
    }

    /**
     * Tracks ridge candidates using a one-raster-pixel source-pixel fallback.
     *
     * @param profiles sampled cross-sections along the source way
     * @return candidate centerlines sorted by score
     */
    public List<CenterlineCandidate> track(List<RenderedHeatmapSampler.CrossSectionProfile> profiles) {
        return track(profiles, 1.0);
    }

    /**
     * Tracks ridge candidates with source-resolution-aware penalties.
     *
     * @param profiles sampled cross-sections along the source way
     * @param sourcePixelSizePx source heatmap pixel size expressed in sampled raster pixels
     * @return candidate centerlines sorted by score
     */
    public List<CenterlineCandidate> track(List<RenderedHeatmapSampler.CrossSectionProfile> profiles, double sourcePixelSizePx) {
        return track(profiles, sourcePixelSizePx, CancellationProbe.NONE);
    }

    /** Tracks identical Legacy candidates, with cancellation at profile traversal boundaries. */
    public List<CenterlineCandidate> track(List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
            double sourcePixelSizePx, CancellationProbe cancellation) {
        return track(profiles, sourcePixelSizePx, sourcePixelSizePx, cancellation, LegacyTrackerPolicy.ORIGINAL_V02);
    }

    /** Tracks candidates with explicitly separated Legacy normalization and native-source continuity scales. */
    public List<CenterlineCandidate> track(List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
            double trackerNormalizationRasterPx, double nativeSourcePitchRasterPx,
            CancellationProbe cancellation, LegacyTrackerPolicy policy) {
        java.util.Objects.requireNonNull(policy, "Legacy tracker policy is required");
        if (policy.isParked()) {
            throw new IllegalArgumentException("Parked Legacy tracker policy");
        }
        if (policy.requiresNativePitch()
                && (!Double.isFinite(nativeSourcePitchRasterPx) || nativeSourcePitchRasterPx <= 0.0)) {
            throw new IllegalArgumentException("Factual native source pitch must be finite and positive");
        }
        return trackWithPolicy(profiles, trackerNormalizationRasterPx, nativeSourcePitchRasterPx,
            cancellation, policy);
    }

    private List<CenterlineCandidate> trackWithPolicy(
            List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
            double trackerNormalizationRasterPx, double nativeSourcePitchRasterPx,
            CancellationProbe cancellation, LegacyTrackerPolicy policy) {
        java.util.Objects.requireNonNull(cancellation, "Cancellation probe is required");
        cancellation.checkpoint();
        profiles = cancellableProfiles(profiles, cancellation);
        if (profiles.isEmpty()) {
            return List.of();
        }
        double sourcePixel = Double.isFinite(trackerNormalizationRasterPx) && trackerNormalizationRasterPx > 0.0
            ? trackerNormalizationRasterPx : 1.0;
        ContinuitySupportPolicy continuity = new ContinuitySupportPolicy(
            policy, sourcePixel, nativeSourcePitchRasterPx);
        profiles = cancellableProfiles(suppressLongitudinalNoise(profiles, continuity, cancellation), cancellation);

        List<Double> seeds = collectSeedOffsets(profiles);
        List<CenterlineCandidate> candidates = new ArrayList<>();
        int candidateIndex = 1;
        double edgeLimitPx = profileEdgeLimit(profiles);
        for (double seed : seeds) {
            State state = bestPathForSeed(profiles, seed, sourcePixel, continuity);
            PathResult anchored = anchoredPath(profiles, state, sourcePixel, edgeLimitPx, continuity);
            List<Double> offsets = anchored.offsets();
            List<Double> intensities = anchored.intensities();

            List<Double> smoothedOffsets = smoothOffsets(profiles, offsets, intensities, sourcePixel,
                nativeSourcePitchRasterPx, policy);
            // One reference snapshot, never an offset/path copy per pair. Every
            // overlapping proposal is capped against the completed historical path.
            List<Double> completedHistoricalOffsets = policy.localizesPairedChord()
                ? List.copyOf(smoothedOffsets) : smoothedOffsets;
            List<Point2D.Double> points = new ArrayList<>();
            for (int i = 0; i < profiles.size(); i++) {
                RenderedHeatmapSampler.CrossSectionProfile profile = profiles.get(i);
                if (policy.localizesPairedChord() && i > 0 && i + 2 < profiles.size()) {
                    localizePairedChord(profiles, offsets, completedHistoricalOffsets, smoothedOffsets, i, continuity);
                }
                if (policy.localizesFineCenters() && i > 0 && i + 1 < profiles.size()) {
                    localizeFineCenter(profiles, offsets, smoothedOffsets, i, continuity);
                }
                double offset = smoothedOffsets.get(i);
                points.add(new Point2D.Double(
                    profile.anchorScreen().x + profile.normalScreen().x * offset,
                    profile.anchorScreen().y + profile.normalScreen().y * offset
                ));
            }
            candidates.add(new CenterlineCandidate("ridge-" + candidateIndex++, anchored.score(), points, smoothedOffsets)
                .withEvidence(evidenceFor(profiles, smoothedOffsets, intensities, continuity)));
        }

        cancellation.checkpoint();
        return deduplicate(candidates).stream()
            .sorted(Comparator.comparingDouble(CenterlineCandidate::score).reversed())
            .toList();
    }

    private static List<RenderedHeatmapSampler.CrossSectionProfile> cancellableProfiles(
            List<RenderedHeatmapSampler.CrossSectionProfile> profiles, CancellationProbe cancellation) {
        if (cancellation == CancellationProbe.NONE) { return profiles; }
        return new java.util.AbstractList<>() {
            @Override public RenderedHeatmapSampler.CrossSectionProfile get(int index) {
                cancellation.checkpoint();
                return profiles.get(index);
            }
            @Override public int size() {
                cancellation.checkpoint();
                return profiles.size();
            }
        };
    }

    private List<RenderedHeatmapSampler.CrossSectionProfile> suppressLongitudinalNoise(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        ContinuitySupportPolicy continuity
    ) {
        return suppressLongitudinalNoise(profiles, continuity, CancellationProbe.NONE);
    }

    private List<RenderedHeatmapSampler.CrossSectionProfile> suppressLongitudinalNoise(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        ContinuitySupportPolicy continuity,
        CancellationProbe cancellation
    ) {
        if (profiles.size() < 4) {
            return profiles;
        }
        List<RenderedHeatmapSampler.CrossSectionProfile> filtered = new ArrayList<>(profiles.size());
        for (int i = 0; i < profiles.size(); i++) {
            RenderedHeatmapSampler.CrossSectionProfile profile = profiles.get(i);
            List<RenderedHeatmapSampler.CrossSectionPeak> peaks = profile.peaks();
            if (peaks.size() <= 1) {
                filtered.add(profile);
                continue;
            }
            double strongest = peaks.stream()
                .mapToDouble(RenderedHeatmapSampler.CrossSectionPeak::intensity)
                .max()
                .orElse(0.0);
            int profileIndex = i;
            boolean hasPersistentCompetitor = peaks.stream()
                .anyMatch(peak -> longitudinalSupport(profiles, profileIndex, peak, continuity)
                    >= requiredLongitudinalSupport(profiles.size(), profileIndex));
            List<RenderedHeatmapSampler.CrossSectionPeak> retained = new ArrayList<>();
            for (RenderedHeatmapSampler.CrossSectionPeak peak : peaks) {
                int support = longitudinalSupport(profiles, i, peak, continuity);
                boolean persistent = support >= requiredLongitudinalSupport(profiles.size(), i);
                boolean dominantWithoutAlternative = !hasPersistentCompetitor && peak.intensity() >= strongest * 0.98;
                boolean broadSyntheticCenter = peak.syntheticCenter() && peak.supportWidthPx() >= 8.0 && support >= 1;
                boolean gradientBacked = peak.gradientStrength() >= 0.18 && peak.gradientBalance() >= 0.35 && support >= 1;
                if (persistent || dominantWithoutAlternative || broadSyntheticCenter || gradientBacked) {
                    retained.add(peak);
                }
            }
            if (retained.isEmpty()) {
                retained.add(peaks.stream()
                    .max(Comparator.comparingDouble(RenderedHeatmapSampler.CrossSectionPeak::intensity))
                    .orElse(peaks.get(0)));
            }
            filtered.add(new RenderedHeatmapSampler.CrossSectionProfile(
                profile.samplingAnchor(),
                profile.normalScreen(),
                retained,
                profile.anchorWithinRaster(),
                profile.intensitySamples(),
                profile.projectedLateralTransform()
            ));
        }
        if (continuity.policy().correctsSyntheticPositions()) {
            rejectDominatedSyntheticCenters(filtered, continuity, cancellation);
        }
        return filtered;
    }

    private void rejectDominatedSyntheticCenters(List<RenderedHeatmapSampler.CrossSectionProfile> ordinary,
            ContinuitySupportPolicy continuity, CancellationProbe cancellation) {
        RenderedHeatmapSampler.CrossSectionPeak[] owners = null;
        // All coherence decisions use the complete ordinary retained stage.
        // Store borrowed owners only: no duplicated profile/peak bank, and no
        // later deletion may change another row's owner eligibility.
        for (int i = 0; i < ordinary.size(); i++) {
            cancellation.checkpoint();
            var profile = ordinary.get(i);
            if (!profile.anchorWithinRaster()) { continue; }
            var peaks = profile.peaks();
            boolean synthetic = false;
            for (int j = 0; j < peaks.size(); j++) {
                cancellation.checkpoint();
                if (peaks.get(j).syntheticCenter()) { synthetic = true; break; }
            }
            if (!synthetic) { continue; }
            if (owners == null) { owners = new RenderedHeatmapSampler.CrossSectionPeak[ordinary.size()]; }
            for (int j = 0; j < peaks.size(); j++) {
                cancellation.checkpoint();
                var peak = peaks.get(j);
                if (!peak.syntheticCenter() && Double.isFinite(peak.intensity())
                        && scalarSupportAt(profile, peak.offsetPx()) != null
                        && longitudinalSupport(ordinary, i, peak, continuity) >= 2
                        && (owners[i] == null || peak.intensity() > owners[i].intensity())) {
                    owners[i] = peak;
                }
            }
        }
        if (owners == null) { return; }
        for (int i = 0; i < ordinary.size(); i++) {
            cancellation.checkpoint();
            if (owners[i] == null) { continue; }
            var profile = ordinary.get(i);
            var observed = scalarSupportAt(profile, owners[i].offsetPx());
            if (observed == null) { continue; }
            var peaks = profile.peaks();
            List<RenderedHeatmapSampler.CrossSectionPeak> retained = null;
            for (int j = 0; j < peaks.size(); j++) {
                cancellation.checkpoint();
                var peak = peaks.get(j);
                var center = peak.syntheticCenter() ? scalarSupportAt(profile, peak.offsetPx()) : null;
                boolean reject = center != null && rawPositionCanBeRejected(profile, peak.offsetPx(), center)
                    && observed.raw() > center.raw() && observed.b3() > center.b3() && observed.b5() > center.b5();
                if (reject && retained == null) { retained = new ArrayList<>(peaks.subList(0, j)); }
                if (!reject && retained != null) { retained.add(peak); }
            }
            if (retained != null) {
                // At least the ordinary retained observed owner remains.
                ordinary.set(i, new RenderedHeatmapSampler.CrossSectionProfile(profile.samplingAnchor(),
                    profile.normalScreen(), retained, profile.anchorWithinRaster(), profile.intensitySamples(),
                    profile.projectedLateralTransform()));
            }
        }
    }

    private boolean rawPositionCanBeRejected(RenderedHeatmapSampler.CrossSectionProfile profile,
            double offset, ScalarSupport center) {
        var samples = profile.intensitySamples();
        int low = 0, high = samples.size() - 1;
        if (!(offset > samples.get(low).offsetPx() && offset < samples.get(high).offsetPx())) { return false; }
        while (low + 1 < high) {
            int middle = low + (high - low) / 2;
            double value = samples.get(middle).offsetPx();
            if (!Double.isFinite(value)) { return false; }
            if (value < offset) { low = middle; } else { high = middle; }
        }
        if (samples.get(high).offsetPx() == offset) { low = high - 1; high++; }
        if (low < 0 || high >= samples.size()) { return false; }
        var left = samples.get(low); var right = samples.get(high);
        if (!left.insideRaster() || !right.insideRaster()
                || !(left.offsetPx() < offset && right.offsetPx() > offset)
                || !Double.isFinite(left.offsetPx()) || !Double.isFinite(right.offsetPx())) { return false; }
        var a = new ScalarSupport(left.nativeIntensity(), left.lightFilteredIntensity(), left.standardFilteredIntensity());
        var b = new ScalarSupport(right.nativeIntensity(), right.lightFilteredIntensity(), right.standardFilteredIntensity());
        // Arbitrarily faint positive maxima/flat cores are positional evidence.
        // An exactly zero raw plateau is no measured signal, not a protected core.
        return a.valid() && b.valid() && (center.raw() == 0.0 || a.raw() > center.raw() || b.raw() > center.raw());
    }

    private int requiredLongitudinalSupport(int profileCount, int profileIndex) {
        if (profileIndex == 0 || profileIndex == profileCount - 1) {
            return 1;
        }
        return profileCount <= 4 ? 1 : 2;
    }

    private int longitudinalSupport(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        int profileIndex,
        RenderedHeatmapSampler.CrossSectionPeak peak,
        ContinuitySupportPolicy continuity
    ) {
        int support = 0;
        int start = Math.max(0, profileIndex - 2);
        int end = Math.min(profiles.size() - 1, profileIndex + 2);
        for (int i = start; i <= end; i++) {
            if (i == profileIndex) {
                continue;
            }
            if (continuity.policy().usesNativeSupport()
                    ? hasReciprocalNearbyPeak(profiles.get(profileIndex), profiles.get(i), peak, continuity)
                    : hasNearbyPeak(profiles.get(i), peak, continuity)) {
                support++;
            }
        }
        return support;
    }

    /**
     * Uses the complete input peak sets of this support stage. Suppression reads
     * its unchanged input; later anchor/evidence stages cannot borrow a peak
     * removed by suppression. Both directions must have one unique nearest
     * peak, and each peak owns its own physical tolerance.
     */
    private boolean hasReciprocalNearbyPeak(
        RenderedHeatmapSampler.CrossSectionProfile source,
        RenderedHeatmapSampler.CrossSectionProfile neighbor,
        RenderedHeatmapSampler.CrossSectionPeak target,
        ContinuitySupportPolicy continuity
    ) {
        var nearest = uniqueNearestPeak(neighbor.peaks(), target.offsetPx());
        if (nearest == null || uniqueNearestPeak(source.peaks(), nearest.offsetPx()) != target) {
            return false;
        }
        double distance = Math.abs(nearest.offsetPx() - target.offsetPx());
        if (!(distance <= continuity.longitudinalTolerance(target.supportWidthPx())
                && distance <= continuity.longitudinalTolerance(nearest.supportWidthPx()))) {
            return false;
        }
        boolean intensityBacked = nearest.intensity() >= Math.max(0.10, target.intensity() * 0.35);
        boolean gradientBacked = Math.min(nearest.gradientStrength(), target.gradientStrength()) >= 0.12
            && Math.min(nearest.gradientBalance(), target.gradientBalance()) >= 0.25;
        return intensityBacked || gradientBacked;
    }

    private RenderedHeatmapSampler.CrossSectionPeak uniqueNearestPeak(
        List<RenderedHeatmapSampler.CrossSectionPeak> peaks,
        double offset
    ) {
        if (!Double.isFinite(offset)) {
            return null;
        }
        RenderedHeatmapSampler.CrossSectionPeak nearest = null;
        double minimum = Double.POSITIVE_INFINITY;
        boolean tied = false;
        for (var peak : peaks) {
            double distance = Math.abs(peak.offsetPx() - offset);
            if (distance < minimum) {
                nearest = peak;
                minimum = distance;
                tied = false;
            } else if (distance == minimum) {
                tied = true;
            }
        }
        return tied ? null : nearest;
    }

    private boolean hasNearbyPeak(
        RenderedHeatmapSampler.CrossSectionProfile profile,
        RenderedHeatmapSampler.CrossSectionPeak target,
        ContinuitySupportPolicy continuity
    ) {
        double tolerance = continuity.longitudinalTolerance(target.supportWidthPx());
        double minimumIntensity = Math.max(0.10, target.intensity() * 0.35);
        for (RenderedHeatmapSampler.CrossSectionPeak peak : profile.peaks()) {
            boolean intensityBacked = peak.intensity() >= minimumIntensity;
            boolean gradientBacked = Math.min(peak.gradientStrength(), target.gradientStrength()) >= 0.12
                && Math.min(peak.gradientBalance(), target.gradientBalance()) >= 0.25;
            if (Math.abs(peak.offsetPx() - target.offsetPx()) <= tolerance && (intensityBacked || gradientBacked)) {
                return true;
            }
        }
        return false;
    }

    private State bestPathForSeed(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        double seed,
        double sourcePixelSizePx,
        ContinuitySupportPolicy continuity
    ) {
        double edgeLimitPx = profileEdgeLimit(profiles);
        List<State> states = List.of(new State(seed, 0.0, 0.0, List.of(), List.of()));
        for (RenderedHeatmapSampler.CrossSectionProfile profile : profiles) {
            if (isUnsupportedProfile(profile)) {
                List<State> nextStates = new ArrayList<>();
                for (State previous : states) {
                    nextStates.add(append(previous,
                        new RenderedHeatmapSampler.CrossSectionPeak(previous.offset(), 0.0), continuity));
                }
                states = nextStates;
                continue;
            }
            List<RenderedHeatmapSampler.CrossSectionPeak> peaks = profile.peaks();
            List<State> nextStates = new ArrayList<>();
            for (RenderedHeatmapSampler.CrossSectionPeak peak : peaks) {
                State best = null;
                for (State previous : states) {
                    State candidate = append(previous, peak, profile, Double.NaN, 0.0,
                        sourcePixelSizePx, edgeLimitPx, continuity);
                    if (best == null || candidate.score() > best.score()) {
                        best = candidate;
                    }
                }
                nextStates.add(best);
            }
            states = nextStates;
        }
        return states.stream()
            .max(Comparator.comparingDouble(State::score))
            .orElseThrow(() -> new IllegalStateException("No ridge path state was produced."));
    }

    private State append(State previous, RenderedHeatmapSampler.CrossSectionPeak peak,
            ContinuitySupportPolicy continuity) {
        return append(previous, peak, null, Double.NaN, 0.0, 1.0, Double.NaN, continuity);
    }

    private State append(
        State previous,
        RenderedHeatmapSampler.CrossSectionPeak peak,
        RenderedHeatmapSampler.CrossSectionProfile profile,
        double targetOffset,
        double targetWeight,
        double sourcePixelSizePx,
        double edgeLimitPx,
        ContinuitySupportPolicy continuity
    ) {
        double delta = peak.offsetPx() - previous.offset();
        double acceleration = delta - previous.delta();
        double evidence = peak.intensity() * 2.25
            + peak.gradientStrength() * (0.22 + 0.18 * peak.gradientBalance())
            + peak.nativeFilteredAgreement() * 0.12
            + peak.scaleAgreement() * 0.06;
        double continuityPenalty = Math.abs(delta) * 0.16;
        double curvaturePenalty = Math.abs(acceleration) * 0.12;
        double localPenalty = profile == null ? 0.0 : localPeakPenalty(profile, peak, sourcePixelSizePx, edgeLimitPx);
        double targetPenalty = Double.isFinite(targetOffset)
            ? Math.abs(peak.offsetPx() - targetOffset) / Math.max(1.0, sourcePixelSizePx) * targetWeight
            : 0.0;
        List<Double> offsets = new ArrayList<>(previous.offsets());
        List<Double> intensities = new ArrayList<>(previous.intensities());
        offsets.add(peak.offsetPx());
        intensities.add(peak.intensity());
        return new State(
            peak.offsetPx(),
            delta,
            previous.score() + evidence - continuityPenalty - curvaturePenalty - localPenalty - targetPenalty,
            offsets,
            intensities
        );
    }

    private PathResult anchoredPath(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        State base,
        double sourcePixelSizePx,
        double edgeLimitPx,
        ContinuitySupportPolicy continuity
    ) {
        List<Anchor> anchors = reliableAnchors(profiles, sourcePixelSizePx, edgeLimitPx, continuity);
        if (anchors.size() < 2) {
            return new PathResult(base.score(), base.offsets(), base.intensities());
        }

        List<Double> offsets = new ArrayList<>(base.offsets());
        List<Double> intensities = new ArrayList<>(base.intensities());
        double score = base.score();
        for (int i = 0; i < anchors.size() - 1; i++) {
            Anchor left = anchors.get(i);
            Anchor right = anchors.get(i + 1);
            if (right.index() <= left.index()) {
                continue;
            }
            IntervalPath interval = solveAnchoredInterval(profiles, left, right,
                sourcePixelSizePx, edgeLimitPx, continuity);
            if (interval.offsets().isEmpty()) {
                continue;
            }
            for (int profileIndex = left.index(); profileIndex <= right.index(); profileIndex++) {
                int local = profileIndex - left.index();
                offsets.set(profileIndex, interval.offsets().get(local));
                intensities.set(profileIndex, interval.intensities().get(local));
            }
            score += interval.scoreBonus();
        }
        return new PathResult(score, offsets, intensities);
    }

    private IntervalPath solveAnchoredInterval(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        Anchor left,
        Anchor right,
        double sourcePixelSizePx,
        double edgeLimitPx,
        ContinuitySupportPolicy continuity
    ) {
        if (right.index() == left.index()) {
            return new IntervalPath(0.0, List.of(left.offsetPx()), List.of(left.intensity()));
        }
        List<State> states = List.of(new State(left.offsetPx(), 0.0, left.quality(), List.of(left.offsetPx()), List.of(left.intensity())));
        int span = right.index() - left.index();
        for (int index = left.index() + 1; index <= right.index(); index++) {
            double fraction = (double) (index - left.index()) / span;
            double target = left.offsetPx() + (right.offsetPx() - left.offsetPx()) * fraction;
            boolean endpoint = index == right.index();
            List<RenderedHeatmapSampler.CrossSectionPeak> peaks = endpoint
                ? List.of(right.peak())
                : intervalPeaks(profiles.get(index), target);
            List<State> nextStates = new ArrayList<>();
            for (RenderedHeatmapSampler.CrossSectionPeak peak : peaks) {
                State best = null;
                for (State previous : states) {
                    State candidate = append(previous, peak, profiles.get(index), target,
                        intervalTargetWeight(span, peak, profiles.get(index), sourcePixelSizePx),
                        sourcePixelSizePx, edgeLimitPx, continuity);
                    if (best == null || candidate.score() > best.score()) {
                        best = candidate;
                    }
                }
                nextStates.add(best);
            }
            states = nextStates;
        }
        State best = states.stream()
            .max(Comparator.comparingDouble(State::score))
            .orElseThrow(() -> new IllegalStateException("No anchored interval state was produced."));
        return new IntervalPath(best.score(), best.offsets(), best.intensities());
    }

    private double intervalTargetWeight(
        int span,
        RenderedHeatmapSampler.CrossSectionPeak peak,
        RenderedHeatmapSampler.CrossSectionProfile profile,
        double sourcePixelSizePx
    ) {
        if (span >= 18 && peak.intensity() >= strongestIntensity(profile) * 0.98 && longitudinalSupportNearby(profile, peak)) {
            return 0.04;
        }
        if (span >= 10 && peak.intensity() >= 0.62) {
            return 0.08;
        }
        return span <= 4 ? 0.36 : 0.20;
    }

    private List<RenderedHeatmapSampler.CrossSectionPeak> intervalPeaks(
        RenderedHeatmapSampler.CrossSectionProfile profile,
        double targetOffset
    ) {
        if (isUnsupportedProfile(profile)) {
            return List.of(new RenderedHeatmapSampler.CrossSectionPeak(targetOffset, 0.0, 0.0, true,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0));
        }
        return profile.peaks();
    }

    private List<Anchor> reliableAnchors(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        double sourcePixelSizePx,
        double edgeLimitPx,
        ContinuitySupportPolicy continuity
    ) {
        List<Anchor> anchors = new ArrayList<>();
        for (int i = 0; i < profiles.size(); i++) {
            RenderedHeatmapSampler.CrossSectionProfile profile = profiles.get(i);
            Anchor anchor = reliableAnchor(profiles, i, sourcePixelSizePx, edgeLimitPx, continuity);
            if (anchor == null) {
                continue;
            }
            if (!anchors.isEmpty()) {
                Anchor previous = anchors.get(anchors.size() - 1);
                if (i - previous.index() <= 2 && Math.abs(anchor.offsetPx() - previous.offsetPx())
                        > sourcePixelSizePx * 1.35) {
                    continue;
                }
            }
            anchors.add(anchor);
        }
        return anchors;
    }

    private Anchor reliableAnchor(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        int index,
        double sourcePixelSizePx,
        double edgeLimitPx,
        ContinuitySupportPolicy continuity
    ) {
        RenderedHeatmapSampler.CrossSectionProfile profile = profiles.get(index);
        if (isUnsupportedProfile(profile)) {
            return null;
        }
        List<RenderedHeatmapSampler.CrossSectionPeak> candidates = profile.peaks().stream()
            .filter(peak -> peak.intensity() >= 0.30)
            .filter(peak -> peak.supportWidthPx() >= Math.max(6.0, sourcePixelSizePx * 0.20))
            .filter(peak -> !isEdgePeak(peak, sourcePixelSizePx, edgeLimitPx))
            .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        RenderedHeatmapSampler.CrossSectionPeak best = candidates.stream()
            .max(Comparator.comparingDouble(peak -> anchorQuality(profiles, index, peak, sourcePixelSizePx, edgeLimitPx, continuity)))
            .orElse(null);
        if (best == null) {
            return null;
        }
        double quality = anchorQuality(profiles, index, best, sourcePixelSizePx, edgeLimitPx, continuity);
        int support = longitudinalSupport(profiles, index, best, continuity);
        if (support < requiredAnchorSupport(profiles.size(), index)) {
            return null;
        }
        double secondQuality = candidates.stream()
            .filter(peak -> peak != best)
            .mapToDouble(peak -> anchorQuality(profiles, index, peak, sourcePixelSizePx, edgeLimitPx, continuity))
            .max()
            .orElse(Double.NEGATIVE_INFINITY);
        boolean dominant = secondQuality == Double.NEGATIVE_INFINITY || quality >= secondQuality + 0.16;
        boolean strongEnough = best.intensity() >= 0.42 || best.prominence() >= 0.16 || best.gradientStrength() >= 0.45;
        if (!dominant && !(strongEnough && quality >= secondQuality + 0.08)) {
            return null;
        }
        return new Anchor(index, best, quality);
    }

    private int requiredAnchorSupport(int profileCount, int profileIndex) {
        if (profileIndex <= 1 || profileIndex >= profileCount - 2) {
            return 1;
        }
        return profileCount <= 8 ? 1 : 2;
    }

    private double anchorQuality(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        int index,
        RenderedHeatmapSampler.CrossSectionPeak peak,
        double sourcePixelSizePx,
        double edgeLimitPx,
        ContinuitySupportPolicy continuity
    ) {
        double supportWidthReward = Math.min(0.34, peak.supportWidthPx() / Math.max(1.0, sourcePixelSizePx) * 0.08);
        double supportReward = Math.min(0.28, longitudinalSupport(profiles, index, peak, continuity) * 0.10);
        double gradientReward = peak.gradientStrength() * (0.16 + 0.10 * peak.gradientBalance());
        double nativeFilteredReward = peak.nativeFilteredAgreement() * 0.14;
        double scaleReward = peak.scaleAgreement() * 0.08;
        double edgePenalty = isEdgePeak(peak, sourcePixelSizePx, edgeLimitPx) ? 0.55 : 0.0;
        double narrowPenalty = peak.supportWidthPx() <= sourcePixelSizePx * 0.15 ? 0.28 : 0.0;
        return peak.intensity() + peak.prominence() * 0.18 + supportWidthReward + supportReward + gradientReward
            + nativeFilteredReward + scaleReward
            - edgePenalty - narrowPenalty;
    }

    private double localPeakPenalty(
        RenderedHeatmapSampler.CrossSectionProfile profile,
        RenderedHeatmapSampler.CrossSectionPeak peak,
        double sourcePixelSizePx,
        double edgeLimitPx
    ) {
        double strongest = strongestIntensity(profile);
        double regret = Math.max(0.0, strongest - peak.intensity());
        double penalty = regret * 4.2;
        if (isEdgePeak(peak, sourcePixelSizePx, edgeLimitPx)) {
            penalty += 0.65 + regret * 2.0;
        }
        if (peak.supportWidthPx() <= sourcePixelSizePx * 0.15 && strongest > peak.intensity() + 0.03) {
            penalty += 0.30;
        }
        penalty += (1.0 - peak.nativeFilteredAgreement()) * 0.18;
        penalty += (1.0 - peak.scaleAgreement()) * 0.10;
        penalty += Math.min(0.12, peak.centerUncertaintyPx() / Math.max(1.0, sourcePixelSizePx) * 0.03);
        return penalty;
    }

    private double strongestIntensity(RenderedHeatmapSampler.CrossSectionProfile profile) {
        return profile.peaks().stream()
            .mapToDouble(RenderedHeatmapSampler.CrossSectionPeak::intensity)
            .max()
            .orElse(0.0);
    }

    private boolean longitudinalSupportNearby(
        RenderedHeatmapSampler.CrossSectionProfile profile,
        RenderedHeatmapSampler.CrossSectionPeak peak
    ) {
        return profile.peaks().stream()
            .anyMatch(candidate -> Math.abs(candidate.offsetPx() - peak.offsetPx()) <= Math.max(4.0, peak.supportWidthPx()));
    }

    private boolean isEdgePeak(RenderedHeatmapSampler.CrossSectionPeak peak, double sourcePixelSizePx, double edgeLimitPx) {
        if (!Double.isFinite(edgeLimitPx) || edgeLimitPx <= 0.0) {
            return false;
        }
        return Math.abs(peak.offsetPx()) >= edgeLimitPx * 0.88
            && peak.supportWidthPx() <= Math.max(3.0, sourcePixelSizePx * 0.30);
    }

    private double profileEdgeLimit(List<RenderedHeatmapSampler.CrossSectionProfile> profiles) {
        return profiles.stream()
            .flatMap(profile -> profile.peaks().stream())
            .mapToDouble(peak -> Math.abs(peak.offsetPx()))
            .max()
            .orElse(Double.NaN);
    }

    private List<Double> collectSeedOffsets(List<RenderedHeatmapSampler.CrossSectionProfile> profiles) {
        Map<Integer, Double> clusters = new LinkedHashMap<>();
        int informativeProfiles = 0;
        for (RenderedHeatmapSampler.CrossSectionProfile profile : profiles) {
            boolean informative = false;
            for (RenderedHeatmapSampler.CrossSectionPeak peak : profile.peaks()) {
                if (peak.intensity() < 0.20) {
                    continue;
                }
                informative = true;
                int bucket = (int) Math.round(peak.offsetPx() / 4.0);
                clusters.merge(bucket, peak.intensity(), Double::sum);
            }
            if (informative && ++informativeProfiles >= 12) {
                break;
            }
        }
        if (clusters.isEmpty()) {
            return List.of(0.0);
        }
        return clusters.entrySet().stream()
            .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
            .limit(3)
            .map(entry -> entry.getKey() * 4.0)
            .toList();
    }

    private List<CenterlineCandidate> deduplicate(List<CenterlineCandidate> candidates) {
        List<CenterlineCandidate> distinct = new ArrayList<>();
        for (CenterlineCandidate candidate : candidates) {
            boolean duplicate = distinct.stream().anyMatch(existing -> averageOffsetDistance(existing, candidate) < 3.0);
            if (!duplicate) {
                distinct.add(candidate);
            }
        }
        return distinct;
    }

    private double averageOffsetDistance(CenterlineCandidate left, CenterlineCandidate right) {
        int count = Math.min(left.offsetsPx().size(), right.offsetsPx().size());
        if (count == 0) {
            return Double.MAX_VALUE;
        }
        double sum = 0.0;
        for (int i = 0; i < count; i++) {
            sum += Math.abs(left.offsetsPx().get(i) - right.offsetsPx().get(i));
        }
        return sum / count;
    }

    private List<Double> smoothOffsets(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        List<Double> offsets,
        List<Double> intensities,
        double sourcePixelSizePx,
        double nativeSourcePitchRasterPx,
        LegacyTrackerPolicy policy
    ) {
        if (offsets.size() < 3) {
            return offsets;
        }
        double sourcePixel = Math.max(1.0, sourcePixelSizePx);
        double aliasDeltaThreshold = Math.max(4.0, sourcePixel * 0.18);
        double aliasResidualThreshold = Math.max(5.0, sourcePixel * 0.24);
        double plateauSupportThreshold = Math.max(36.0, sourcePixel * 1.75);
        List<Double> current = new ArrayList<>(offsets);
        for (int pass = 0; pass < 4; pass++) {
            List<Double> baseline = movingAverage(current, 7);
            List<Double> next = new ArrayList<>(current);
            for (int i = 1; i < current.size() - 1; i++) {
                double intensity = intensities.get(i);
                double leftDelta = current.get(i) - current.get(i - 1);
                double rightDelta = current.get(i + 1) - current.get(i);
                double neighborAverage = (current.get(i - 1) + current.get(i + 1)) / 2.0;
                double residual = Math.abs(current.get(i) - neighborAverage);
                boolean alternating = Math.abs(leftDelta) >= aliasDeltaThreshold
                    && Math.abs(rightDelta) >= aliasDeltaThreshold
                    && Math.signum(leftDelta) != Math.signum(rightDelta)
                    && residual >= aliasResidualThreshold;
                RenderedHeatmapSampler.CrossSectionPeak peak = selectedPeak(profiles.get(i), current.get(i));
                boolean broadPlateau = peak.supportWidthPx() >= plateauSupportThreshold && intensity >= 0.45;
                int localFlipCount = localDeltaSignFlips(current, i, 3, Math.max(3.0, sourcePixel * 0.14));
                double highFrequencyResidual = Math.abs(current.get(i) - baseline.get(i));
                boolean subSourcePixelWander = localFlipCount >= 2
                    && highFrequencyResidual >= aliasResidualThreshold
                    && Math.abs(leftDelta) <= sourcePixel * 1.25
                    && Math.abs(rightDelta) <= sourcePixel * 1.25;
                boolean broadPlateauWander = broadPlateau
                    && (localFlipCount >= 2 || subSourcePixelWander)
                    && highFrequencyResidual >= aliasResidualThreshold;
                double lowConfidenceSmoothing = 0.45 * clamp01((0.55 - intensity) / 0.55);
                double unstablePeakSmoothing = 0.18 * clamp01((0.78 - peak.scaleAgreement()) / 0.78);
                double aliasingSmoothing = alternating ? (intensity >= 0.80 ? 0.70 : 0.55) : 0.0;
                double plateauSmoothing = broadPlateauWander ? 0.70 : 0.0;
                double smoothing = Math.max(lowConfidenceSmoothing, unstablePeakSmoothing);
                smoothing = Math.max(smoothing, aliasingSmoothing);
                smoothing = Math.max(smoothing, plateauSmoothing);
                if (smoothing <= 0.01) {
                    continue;
                }
                double target = plateauSmoothing > aliasingSmoothing ? baseline.get(i) : neighborAverage;
                double proposal = current.get(i) * (1.0 - smoothing) + target * smoothing;
                if (policy.guardsSmoothingGeometry()) {
                    // Proposals remain historical. Compare against accepted-so-far
                    // geometry so simultaneous individually safe moves cannot add
                    // energy together. Only the three affected vertices are read.
                    double before = localCenterlineEnergy(profiles, next, i, nativeSourcePitchRasterPx);
                    next.set(i, proposal);
                    double after = localCenterlineEnergy(profiles, next, i, nativeSourcePitchRasterPx);
                    if (!Double.isFinite(before) || !Double.isFinite(after) || after > before) {
                        next.set(i, current.get(i));
                    }
                } else {
                    next.set(i, proposal);
                }
            }
            current = next;
        }
        return current;
    }

    /** Raster-space bending energy, with factual native pitch as the length floor. */
    private double localCenterlineEnergy(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        List<Double> offsets,
        int changedIndex,
        double nativeSourcePitchRasterPx
    ) {
        int firstCenter = Math.max(1, changedIndex - 1);
        int lastCenter = Math.min(offsets.size() - 2, changedIndex + 1);
        double energy = 0.0;
        for (int center = firstCenter; center <= lastCenter; center++) {
            Point2D.Double before = centerlinePoint(profiles.get(center - 1), offsets.get(center - 1));
            Point2D.Double middle = centerlinePoint(profiles.get(center), offsets.get(center));
            Point2D.Double after = centerlinePoint(profiles.get(center + 1), offsets.get(center + 1));
            double leftX = middle.x - before.x;
            double leftY = middle.y - before.y;
            double rightX = after.x - middle.x;
            double rightY = after.y - middle.y;
            double leftLength = Math.hypot(leftX, leftY);
            double rightLength = Math.hypot(rightX, rightY);
            double denominator = Math.max(leftLength + rightLength, nativeSourcePitchRasterPx);
            if (!Double.isFinite(denominator) || denominator <= 0.0
                || !Double.isFinite(leftLength) || !Double.isFinite(rightLength)
                || leftLength <= 0.0 || rightLength <= 0.0) {
                return Double.NaN;
            }
            double curvatureX = rightX / rightLength - leftX / leftLength;
            double curvatureY = rightY / rightLength - leftY / leftLength;
            energy += (curvatureX * curvatureX + curvatureY * curvatureY) / denominator;
        }
        return energy;
    }

    /** One bounded same-peak localization correction, after the historical smoother. */
    private void localizeFineCenter(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        List<Double> selectedOffsets,
        List<Double> completedOffsets,
        int index,
        ContinuitySupportPolicy continuity
    ) {
        var profile = profiles.get(index);
        if (!profile.anchorWithinRaster()) {
            return;
        }
        var peak = uniqueNearestPeak(profile.peaks(), selectedOffsets.get(index));
        double current = completedOffsets.get(index);
        if (peak == null || peak.syntheticCenter() || !Double.isFinite(peak.supportWidthPx())
                || !(peak.supportWidthPx() > 0.0) || !Double.isFinite(peak.intensity())
                || uniqueNearestPeak(profile.peaks(), current) != peak) {
            return;
        }
        double center = peak.rawCenterPx();
        double bound = Math.min(peak.centerUncertaintyPx(), continuity.nativeSourcePitchRasterPx() * 0.5);
        if (!Double.isFinite(center) || !Double.isFinite(peak.centerUncertaintyPx())
                || !Double.isFinite(bound) || bound <= 0.0
                || center != peak.lightFilteredCenterPx() || center != peak.standardFilteredCenterPx()
                || !(peak.nativeFilteredAgreement() >= 0.55 && peak.nativeFilteredAgreement() <= 1.0)
                || !(peak.scaleAgreement() >= 0.90 && peak.scaleAgreement() <= 1.0)
                || uniqueNearestPeak(profile.peaks(), center) != peak
                || longitudinalSupport(profiles, index, peak, continuity)
                    < requiredLongitudinalSupport(profiles.size(), index)) {
            return;
        }
        double proposed = current + Math.max(-bound, Math.min(bound, center - current));
        if (proposed == current || uniqueNearestPeak(profile.peaks(), proposed) != peak) {
            return;
        }
        ScalarSupport oldSupport = scalarSupportAt(profile, current);
        ScalarSupport newSupport = scalarSupportAt(profile, proposed);
        ScalarSupport core = scalarSupportAt(profile, center);
        double pitch = continuity.nativeSourcePitchRasterPx();
        ScalarSupport left = scalarSupportAt(profile, center - pitch);
        ScalarSupport right = scalarSupportAt(profile, center + pitch);
        // Measured two-sided local maximum inside the decision window. A missing
        // or censored bracket cannot authorize a position, even if its peak exists.
        if (oldSupport == null || newSupport == null || core == null || left == null || right == null
                || !(core.raw() > 0.0) || !(core.raw() > peak.noiseFloor())
                || !(left.raw() < core.raw()) || !(right.raw() < core.raw())
                || !core.dominates(left) || !core.dominates(right)
                || !newSupport.dominates(oldSupport) || !core.dominates(newSupport)) {
            return;
        }
        GeometryMetrics before = localGeometryMetrics(profiles, completedOffsets, index, pitch);
        if (before == null) {
            return;
        }
        completedOffsets.set(index, proposed);
        GeometryMetrics after = localGeometryMetrics(profiles, completedOffsets, index, pitch);
        if (after == null || after.energy() > before.energy() || after.maximumTurn() > before.maximumTurn()) {
            completedOffsets.set(index, current);
        }
    }

    /** One atomic neighboring-pair proposal; no runtime search or per-pair path copy. */
    private boolean localizePairedChord(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        List<Double> selectedOffsets,
        List<Double> historicalOffsets,
        List<Double> completedOffsets,
        int index,
        ContinuitySupportPolicy continuity
    ) {
        var leftProfile = profiles.get(index);
        var rightProfile = profiles.get(index + 1);
        var leftPeak = pairedPeak(profiles, selectedOffsets, completedOffsets, index, continuity);
        var rightPeak = pairedPeak(profiles, selectedOffsets, completedOffsets, index + 1, continuity);
        if (leftPeak == null || rightPeak == null
                || uniqueNearestPeak(rightProfile.peaks(), leftPeak.offsetPx()) != rightPeak
                || uniqueNearestPeak(leftProfile.peaks(), rightPeak.offsetPx()) != leftPeak
                || !hasReciprocalNearbyPeak(leftProfile, rightProfile, leftPeak, continuity)) {
            return false;
        }
        var chordStart = centerlinePoint(profiles.get(index - 1), completedOffsets.get(index - 1));
        var chordEnd = centerlinePoint(profiles.get(index + 2), completedOffsets.get(index + 2));
        var leftIntersection = chordIntersection(leftProfile, chordStart, chordEnd);
        var rightIntersection = chordIntersection(rightProfile, chordStart, chordEnd);
        if (leftIntersection == null || rightIntersection == null
                || !(leftIntersection.fraction() < rightIntersection.fraction())) {
            return false;
        }
        double pitch = continuity.nativeSourcePitchRasterPx();
        double leftBound = Math.min(leftPeak.centerUncertaintyPx(), pitch * 0.5);
        double rightBound = Math.min(rightPeak.centerUncertaintyPx(), pitch * 0.5);
        double leftHistorical = historicalOffsets.get(index), rightHistorical = historicalOffsets.get(index + 1);
        Double oldLeft = completedOffsets.get(index), oldRight = completedOffsets.get(index + 1);
        Double proposedLeft = Math.max(leftHistorical - leftBound,
            Math.min(leftHistorical + leftBound, leftIntersection.offset()));
        Double proposedRight = Math.max(rightHistorical - rightBound,
            Math.min(rightHistorical + rightBound, rightIntersection.offset()));
        if (continuity.policy().correctsSyntheticPositions()) {
            proposedLeft = canonicalOffsetForPoint(leftProfile, oldLeft, proposedLeft);
            proposedRight = canonicalOffsetForPoint(rightProfile, oldRight, proposedRight);
        }
        if (!Double.isFinite(proposedLeft) || !Double.isFinite(proposedRight)
                || (proposedLeft.doubleValue() == oldLeft && proposedRight.doubleValue() == oldRight)
                || !pairedScalarEvidence(leftProfile, leftPeak, oldLeft, proposedLeft, pitch)
                || !pairedScalarEvidence(rightProfile, rightPeak, oldRight, proposedRight, pitch)) {
            return false;
        }
        GeometryMetrics before = geometryMetrics(profiles, completedOffsets, index - 1, index + 2, pitch);
        if (before == null) {
            return false;
        }
        boolean accepted = false;
        try {
            completedOffsets.set(index, proposedLeft);
            completedOffsets.set(index + 1, proposedRight);
            GeometryMetrics after = geometryMetrics(profiles, completedOffsets, index - 1, index + 2, pitch);
            accepted = after != null && after.energy() <= before.energy() && after.maximumTurn() <= before.maximumTurn();
            return accepted;
        } finally {
            // Includes cancellation/failure during geometry reads: never leave
            // half a pair committed, and restore the original Double identities.
            if (!accepted) {
                completedOffsets.set(index, oldLeft);
                completedOffsets.set(index + 1, oldRight);
            }
        }
    }

    private Double canonicalOffsetForPoint(RenderedHeatmapSampler.CrossSectionProfile profile,
            Double current, Double proposed) {
        var a = centerlinePoint(profile, current);
        var b = centerlinePoint(profile, proposed);
        return Double.isFinite(a.x) && Double.isFinite(a.y) && a.x == b.x && a.y == b.y ? current : proposed;
    }

    private RenderedHeatmapSampler.CrossSectionPeak pairedPeak(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles, List<Double> selectedOffsets,
        List<Double> currentOffsets, int index, ContinuitySupportPolicy continuity
    ) {
        var profile = profiles.get(index);
        var peak = uniqueNearestPeak(profile.peaks(), selectedOffsets.get(index));
        if (!profile.anchorWithinRaster() || peak == null || peak.syntheticCenter()
                || !Double.isFinite(peak.supportWidthPx()) || peak.supportWidthPx() <= 0.0
                || !Double.isFinite(peak.intensity()) || peak.intensity() < 0.0 || peak.intensity() > 1.0
                || !Double.isFinite(peak.noiseFloor()) || peak.noiseFloor() < 0.0 || peak.noiseFloor() > 1.0
                || !Double.isFinite(peak.centerUncertaintyPx()) || peak.centerUncertaintyPx() <= 0.0
                || !Double.isFinite(peak.rawCenterPx())
                || peak.rawCenterPx() != peak.lightFilteredCenterPx() || peak.rawCenterPx() != peak.standardFilteredCenterPx()
                || !(peak.nativeFilteredAgreement() >= 0.55 && peak.nativeFilteredAgreement() <= 1.0)
                || !(peak.scaleAgreement() >= 0.90 && peak.scaleAgreement() <= 1.0)
                || uniqueNearestPeak(profile.peaks(), currentOffsets.get(index)) != peak
                || uniqueNearestPeak(profile.peaks(), peak.rawCenterPx()) != peak
                || longitudinalSupport(profiles, index, peak, continuity) < requiredLongitudinalSupport(profiles.size(), index)) {
            return null;
        }
        return peak;
    }

    private boolean pairedScalarEvidence(RenderedHeatmapSampler.CrossSectionProfile profile,
            RenderedHeatmapSampler.CrossSectionPeak peak, double current, double proposed, double pitch) {
        if (uniqueNearestPeak(profile.peaks(), proposed) != peak) {
            return false;
        }
        var oldSupport = scalarSupportAt(profile, current);
        var newSupport = scalarSupportAt(profile, proposed);
        var core = scalarSupportAt(profile, peak.rawCenterPx());
        var left = scalarSupportAt(profile, peak.rawCenterPx() - pitch);
        var right = scalarSupportAt(profile, peak.rawCenterPx() + pitch);
        return oldSupport != null && newSupport != null && core != null && left != null && right != null
            && core.raw() > 0.0 && core.raw() > peak.noiseFloor()
            && left.raw() < core.raw() && right.raw() < core.raw()
            && core.dominates(left) && core.dominates(right)
            && newSupport.dominates(oldSupport) && core.dominates(newSupport);
    }

    private ChordIntersection chordIntersection(RenderedHeatmapSampler.CrossSectionProfile profile,
            Point2D.Double start, Point2D.Double end) {
        var anchor = profile.anchorScreen();
        var normal = profile.normalScreen();
        double dx = end.x - start.x, dy = end.y - start.y;
        double denominator = normal.x * dy - normal.y * dx;
        double scale = Math.hypot(normal.x, normal.y) * Math.hypot(dx, dy);
        if (!Double.isFinite(scale) || scale <= 0.0 || !Double.isFinite(denominator)
                || Math.abs(denominator) <= Math.ulp(scale) * 8.0) {
            return null;
        }
        double ax = start.x - anchor.x, ay = start.y - anchor.y;
        double offset = (ax * dy - ay * dx) / denominator;
        double fraction = (ax * normal.y - ay * normal.x) / denominator;
        return Double.isFinite(offset) && Double.isFinite(fraction) && fraction >= 0.0 && fraction <= 1.0
            ? new ChordIntersection(offset, fraction) : null;
    }

    private record ChordIntersection(double offset, double fraction) { }

    /** Scalar-only interpolation between adjacent measured samples; never across invalid support. */
    private ScalarSupport scalarSupportAt(RenderedHeatmapSampler.CrossSectionProfile profile, double offset) {
        var samples = profile.intensitySamples();
        if (!Double.isFinite(offset) || samples.size() < 3) {
            return null;
        }
        int low = 0, high = samples.size() - 1;
        if (!(offset >= samples.get(low).offsetPx() && offset <= samples.get(high).offsetPx())) {
            return null;
        }
        while (low + 1 < high) {
            int middle = low + (high - low) / 2;
            double value = samples.get(middle).offsetPx();
            if (!Double.isFinite(value)) {
                return null;
            }
            if (value < offset) { low = middle; } else { high = middle; }
        }
        var left = samples.get(low);
        var right = samples.get(high);
        double width = right.offsetPx() - left.offsetPx();
        if (!left.insideRaster() || !right.insideRaster() || !Double.isFinite(width) || width <= 0.0) {
            return null;
        }
        var a = new ScalarSupport(left.nativeIntensity(), left.lightFilteredIntensity(), left.standardFilteredIntensity());
        var b = new ScalarSupport(right.nativeIntensity(), right.lightFilteredIntensity(), right.standardFilteredIntensity());
        if (!a.valid() || !b.valid()) {
            return null;
        }
        double fraction = (offset - left.offsetPx()) / width;
        var result = new ScalarSupport(a.raw() + (b.raw() - a.raw()) * fraction,
                a.b3() + (b.b3() - a.b3()) * fraction, a.b5() + (b.b5() - a.b5()) * fraction);
        return result.valid() && fraction >= 0.0 && fraction <= 1.0 ? result : null;
    }

    private GeometryMetrics localGeometryMetrics(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        List<Double> offsets, int changedIndex, double pitch
    ) {
        return geometryMetrics(profiles, offsets, changedIndex - 1, changedIndex + 1, pitch);
    }

    private GeometryMetrics geometryMetrics(List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
            List<Double> offsets, int firstCenter, int lastCenter, double pitch) {
        double energy = 0.0, maximumTurn = 0.0;
        for (int i = Math.max(1, firstCenter); i <= Math.min(offsets.size() - 2, lastCenter); i++) {
            var a = centerlinePoint(profiles.get(i - 1), offsets.get(i - 1));
            var b = centerlinePoint(profiles.get(i), offsets.get(i));
            var c = centerlinePoint(profiles.get(i + 1), offsets.get(i + 1));
            double ux = b.x - a.x, uy = b.y - a.y, vx = c.x - b.x, vy = c.y - b.y;
            double left = Math.hypot(ux, uy), right = Math.hypot(vx, vy);
            double denominator = Math.max(left + right, pitch);
            if (!Double.isFinite(denominator) || left <= 0.0 || right <= 0.0) {
                return null;
            }
            double dx = vx / right - ux / left, dy = vy / right - uy / left;
            energy += (dx * dx + dy * dy) / denominator;
            maximumTurn = Math.max(maximumTurn, Math.abs(Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)));
        }
        return Double.isFinite(energy) && Double.isFinite(maximumTurn) ? new GeometryMetrics(energy, maximumTurn) : null;
    }

    private record ScalarSupport(double raw, double b3, double b5) {
        boolean valid() {
            return Double.isFinite(raw) && raw >= 0.0 && raw <= 1.0
                && Double.isFinite(b3) && b3 >= 0.0 && b3 <= 1.0
                && Double.isFinite(b5) && b5 >= 0.0 && b5 <= 1.0;
        }
        boolean dominates(ScalarSupport other) {
            return raw >= other.raw && b3 >= other.b3 && b5 >= other.b5;
        }
    }

    private record GeometryMetrics(double energy, double maximumTurn) { }

    private Point2D.Double centerlinePoint(RenderedHeatmapSampler.CrossSectionProfile profile, double offset) {
        Point2D.Double anchor = profile.anchorScreen();
        Point2D.Double normal = profile.normalScreen();
        return new Point2D.Double(anchor.x + normal.x * offset, anchor.y + normal.y * offset);
    }

    private List<Double> movingAverage(List<Double> offsets, int window) {
        int radius = Math.max(1, window / 2);
        List<Double> smoothed = new ArrayList<>(offsets.size());
        for (int i = 0; i < offsets.size(); i++) {
            int start = Math.max(0, i - radius);
            int end = Math.min(offsets.size() - 1, i + radius);
            double total = 0.0;
            for (int j = start; j <= end; j++) {
                total += offsets.get(j);
            }
            smoothed.add(total / (end - start + 1));
        }
        return smoothed;
    }

    private int localDeltaSignFlips(List<Double> offsets, int center, int radius, double thresholdPx) {
        int start = Math.max(1, center - radius);
        int end = Math.min(offsets.size() - 1, center + radius);
        int flips = 0;
        Double previous = null;
        for (int i = start; i <= end; i++) {
            double delta = offsets.get(i) - offsets.get(i - 1);
            if (Math.abs(delta) < thresholdPx) {
                continue;
            }
            double sign = Math.signum(delta);
            if (previous != null && sign != previous) {
                flips++;
            }
            previous = sign;
        }
        return flips;
    }

    private double clamp01(double value) {
        if (value <= 0.0) {
            return 0.0;
        }
        if (value >= 1.0) {
            return 1.0;
        }
        return value;
    }

    private CandidateEvidence evidenceFor(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        List<Double> offsets,
        List<Double> intensities,
        ContinuitySupportPolicy continuity
    ) {
        int supported = 0;
        int empty = 0;
        int maxEmpty = 0;
        int currentEmpty = 0;
        double total = 0.0;
        double ambiguity = 0.0;
        double prominenceTotal = 0.0;
        double gradientTotal = 0.0;
        double stabilityTotal = 0.0;
        for (int i = 0; i < profiles.size(); i++) {
            RenderedHeatmapSampler.CrossSectionProfile profile = profiles.get(i);
            double intensity = i < intensities.size() ? intensities.get(i) : 0.0;
            if (intensity > 0.0) {
                supported++;
                total += intensity;
                prominenceTotal += strongestProminence(profile);
                RenderedHeatmapSampler.CrossSectionPeak strongest = selectedPeak(profile, i < offsets.size() ? offsets.get(i) : 0.0);
                gradientTotal += strongest.gradientStrength();
                stabilityTotal += Math.min(1.0, longitudinalSupport(profiles, i, strongest, continuity) / 2.0);
                currentEmpty = 0;
            } else {
                currentEmpty++;
                maxEmpty = Math.max(maxEmpty, currentEmpty);
            }
            if (profile.peaks().isEmpty()) {
                empty++;
            } else if (isUnsupportedProfile(profile)) {
                empty++;
            } else {
                ambiguity += Math.max(0, profile.peaks().size() - 1);
            }
        }
        double mean = supported == 0 ? 0.0 : total / supported;
        double supportRatio = profiles.isEmpty() ? 0.0 : (double) supported / profiles.size();
        double ambiguityPenalty = profiles.isEmpty() ? 0.0 : ambiguity / profiles.size();
        double meanProminence = supported == 0 ? 0.0 : prominenceTotal / supported;
        double meanGradient = supported == 0 ? 0.0 : gradientTotal / supported;
        double longitudinalStability = supported == 0 ? 0.0 : stabilityTotal / supported;
        double snr = (mean * 0.58 + meanProminence * 0.82 + meanGradient * 0.28) * supportRatio
            * (0.75 + 0.25 * longitudinalStability) / (1.0 + ambiguityPenalty);
        return new CandidateEvidence("", profiles.size(), supported, empty, maxEmpty,
            total, mean, meanGradient, longitudinalStability, snr, ambiguityPenalty, List.of());
    }

    private double strongestProminence(RenderedHeatmapSampler.CrossSectionProfile profile) {
        return profile.peaks().stream()
            .mapToDouble(RenderedHeatmapSampler.CrossSectionPeak::prominence)
            .max()
            .orElse(0.0);
    }

    private RenderedHeatmapSampler.CrossSectionPeak selectedPeak(RenderedHeatmapSampler.CrossSectionProfile profile, double offset) {
        return profile.peaks().stream()
            .min(Comparator.comparingDouble(peak -> Math.abs(peak.offsetPx() - offset)))
            .orElse(new RenderedHeatmapSampler.CrossSectionPeak(0.0, 0.0));
    }

    private boolean isUnsupportedProfile(RenderedHeatmapSampler.CrossSectionProfile profile) {
        return profile.peaks().isEmpty()
            || profile.peaks().stream().allMatch(this::isUnsupportedFallbackPeak);
    }

    private boolean isUnsupportedFallbackPeak(RenderedHeatmapSampler.CrossSectionPeak peak) {
        return peak.syntheticCenter()
            && peak.supportWidthPx() <= 0.0
            && peak.prominence() <= 0.0
            && peak.maxProfileIntensity() <= 0.0;
    }

    private record State(
        double offset,
        double delta,
        double score,
        List<Double> offsets,
        List<Double> intensities
    ) {
    }

    private record Anchor(int index, RenderedHeatmapSampler.CrossSectionPeak peak, double quality) {
        double offsetPx() {
            return peak.offsetPx();
        }

        double intensity() {
            return peak.intensity();
        }
    }

    private record ContinuitySupportPolicy(
        LegacyTrackerPolicy policy,
        double trackerNormalizationRasterPx,
        double nativeSourcePitchRasterPx
    ) {
        double longitudinalTolerance(double supportWidthRasterPx) {
            if (policy.usesNativeSupport()) {
                double pitch = nativeSourcePitchRasterPx;
                return Math.max(pitch, Math.min(2.0 * pitch,
                    Math.max(pitch / 3.0, supportWidthRasterPx) * 1.5));
            }
            return Math.max(6.0, Math.min(12.0, Math.max(2.0, supportWidthRasterPx) * 1.5));
        }
    }

    private record PathResult(double score, List<Double> offsets, List<Double> intensities) {
    }

    private record IntervalPath(double scoreBonus, List<Double> offsets, List<Double> intensities) {
    }
}
