package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.IntensitySample;

/** Forms sparse parent-corridor hypotheses without hiding elementary child alternatives. */
public final class CorridorGrouping {
    static final double COMBINED_VALLEY_RATIO = 0.65;
    static final double SEPARATE_VALLEY_RATIO = 0.40;
    static final double MIN_BUNDLE_UNION_SUPPORT = 0.70;
    static final double MAX_BUNDLE_TANGENT_DIFFERENCE_DEGREES = 20.0;
    static final double MIN_SEPARATE_CHILD_COVERAGE = 0.70;
    static final double MIN_SEPARATE_SPAN_METERS = 20.0;
    static final double MIN_SEPARATE_ORDER_STABILITY = 0.90;
    static final double MIN_SEPARATE_VALLEY_PERSISTENCE = 0.60;
    static final double MIN_SEPARATE_SOURCE_PIXELS = 1.5;
    static final int MAX_INTERPOLATION_PROFILES = 16;
    static final double MAX_INTERPOLATION_METERS = 20.0;
    private static final int MIN_PERSISTENT_PROFILES = 5;
    private static final double MIN_COMMON_SUPPORT_RATIO = 0.60;
    private static final double COMPLEMENTARY_JOINT_UNION_RATIO = 0.45;
    private static final double LOCAL_PREDICTION_HALF_WINDOW_METERS = 12.0;
    static final double MAX_BUNDLE_ENVELOPE_SOURCE_PIXELS = 10.0;
    private static final int HUBER_ITERATIONS = 2;

    /** Creates a stateless longitudinal grouping service. */
    public CorridorGrouping() {
    }

    /**
     * Groups tracks using a one-raster-pixel source pitch for compatibility tests.
     *
     * @param elementaryTracks elementary longitudinal tracks
     * @param profiles source corridor profiles
     * @return child tracks plus supported sparse parents and diagnostics
     */
    public GroupingResult group(List<CorridorTrack> elementaryTracks, List<CorridorProfile> profiles) {
        return group(elementaryTracks, profiles, 1.0);
    }

    /**
     * Groups all-pairs-compatible elementary tracks using longitudinal physical evidence.
     *
     * @param elementaryTracks elementary longitudinal tracks
     * @param profiles source corridor profiles
     * @param sourcePixelSizePx native source-pixel pitch in sampled-raster pixels
     * @return child tracks plus supported sparse parents and diagnostics
     */
    public GroupingResult group(
        List<CorridorTrack> elementaryTracks,
        List<CorridorProfile> profiles,
        double sourcePixelSizePx
    ) {
        double sourcePixel = validSourcePixel(sourcePixelSizePx);
        List<CorridorTrack> sorted = elementaryTracks.stream()
            .sorted(Comparator.comparingDouble(this::meanOffset).thenComparing(CorridorTrack::id)).toList();
        List<CorridorTrack> tracks = new ArrayList<>(sorted);
        List<GroupingDecision> decisions = new ArrayList<>();
        List<SparseCorridorBundle> bundles = new ArrayList<>();
        Map<TrackPair, PairEvidence> pairEvidence = new LinkedHashMap<>();
        for (int leftIndex = 0; leftIndex < sorted.size(); leftIndex++) {
            for (int rightIndex = leftIndex + 1; rightIndex < sorted.size(); rightIndex++) {
                CorridorTrack left = sorted.get(leftIndex);
                CorridorTrack right = sorted.get(rightIndex);
                PairEvidence evidence = pairEvidence(left, right, profiles, sourcePixel);
                pairEvidence.put(TrackPair.ordered(left.id(), right.id()), evidence);
                String decision = classify(evidence, sourcePixel);
                decisions.add(new GroupingDecision(left.id(), right.id(), evidence.commonProfiles(),
                    evidence.commonSupportRatio(), evidence.meanValleyRatio(), evidence.commonEnvelopeRatio(), decision,
                    evidence.unionSupportRatio(), evidence.jointSupportRatio(), evidence.valleyPersistence(),
                    evidence.tangentDifferenceDegrees(), evidence.orderStability(), evidence.robustSeparationPx(),
                    evidence.physicalSpanMeters(), evidence.reason()));
            }
        }
        int parentIndex = 1;
        for (List<CorridorTrack> children : compatibleGroups(sorted, pairEvidence, profiles, sourcePixel)) {
            GroupEvidence evidence = groupEvidence(children, pairEvidence, profiles, sourcePixel);
            String id = "bundle-" + parentIndex++;
            BundleResult result = bundle(id, children, profiles, sourcePixel, evidence);
            if (result != null) {
                bundles.add(result.bundle());
                tracks.add(result.parentTrack());
            }
        }
        return new GroupingResult(tracks, decisions, bundles);
    }

    private List<List<CorridorTrack>> compatibleGroups(
        List<CorridorTrack> sorted,
        Map<TrackPair, PairEvidence> pairEvidence,
        List<CorridorProfile> profiles,
        double sourcePixel
    ) {
        Map<String, List<CorridorTrack>> unique = new LinkedHashMap<>();
        for (int seedIndex = 0; seedIndex < sorted.size(); seedIndex++) {
            List<CorridorTrack> group = new ArrayList<>();
            group.add(sorted.get(seedIndex));
            for (CorridorTrack candidate : sorted) {
                if (group.contains(candidate) || group.stream().allMatch(member -> pairCompatible(
                    evidenceFor(member, candidate, pairEvidence), sourcePixel))) {
                    if (!group.contains(candidate)) {
                        group.add(candidate);
                    }
                }
            }
            group = group.stream().sorted(Comparator.comparingDouble(this::meanOffset)
                .thenComparing(CorridorTrack::id)).toList();
            if (group.size() >= 2 && unionSupport(group, profiles.size()) >= MIN_BUNDLE_UNION_SUPPORT) {
                String key = group.stream().map(CorridorTrack::id).sorted().collect(
                    java.util.stream.Collectors.joining("|"));
                unique.putIfAbsent(key, group);
            }
        }
        return List.copyOf(unique.values());
    }

    private boolean pairCompatible(PairEvidence evidence, double sourcePixel) {
        return evidence != null
            && !strongSeparation(evidence, sourcePixel)
            && evidence.tangentDifferenceDegrees() <= MAX_BUNDLE_TANGENT_DIFFERENCE_DEGREES
            && evidence.robustSeparationPx() <= MAX_BUNDLE_ENVELOPE_SOURCE_PIXELS * sourcePixel;
    }

    private boolean strongSeparation(PairEvidence evidence, double sourcePixel) {
        return independentlySeparate(evidence, sourcePixel);
    }

    private PairEvidence evidenceFor(
        CorridorTrack left,
        CorridorTrack right,
        Map<TrackPair, PairEvidence> evidence
    ) {
        return evidence.get(TrackPair.ordered(left.id(), right.id()));
    }

    private double unionSupport(List<CorridorTrack> tracks, int profileCount) {
        long union = tracks.stream().flatMap(track -> track.points().keySet().stream()).distinct().count();
        return profileCount == 0 ? 0.0 : union / (double) profileCount;
    }

    private PairEvidence pairEvidence(
        CorridorTrack left,
        CorridorTrack right,
        List<CorridorProfile> profiles,
        double sourcePixel
    ) {
        List<Integer> union = java.util.stream.Stream.concat(left.points().keySet().stream(),
            right.points().keySet().stream()).distinct().sorted().toList();
        if (union.isEmpty() || profiles.isEmpty()) {
            return PairEvidence.empty();
        }
        int first = union.get(0);
        int last = union.get(union.size() - 1);
        int spanProfiles = Math.max(1, last - first + 1);
        double physicalSpan = distanceMeters(profiles, first, last);
        List<Double> valleys = new ArrayList<>();
        List<Double> separations = new ArrayList<>();
        int commonEnvelope = 0;
        int stableOrder = 0;
        for (int profileIndex : union) {
            CorridorTrackPoint leftPoint = left.points().get(profileIndex);
            CorridorTrackPoint rightPoint = right.points().get(profileIndex);
            if (leftPoint == null || rightPoint == null) {
                continue;
            }
            CorridorBand leftBand = leftPoint.band();
            CorridorBand rightBand = rightPoint.band();
            valleys.add(valleyRatio(profiles.get(profileIndex), leftBand, rightBand));
            separations.add(Math.abs(rightBand.centerOffsetPx() - leftBand.centerOffsetPx()));
            if (envelopesTouch(leftBand, rightBand)) {
                commonEnvelope++;
            }
            if (leftBand.centerOffsetPx() <= rightBand.centerOffsetPx()) {
                stableOrder++;
            }
        }
        int common = valleys.size();
        int minimumSupport = Math.max(1, Math.min(left.points().size(), right.points().size()));
        double unionRatio = union.size() / (double) profiles.size();
        double jointRatio = common / (double) profiles.size();
        double leftCoverage = countInSpan(left, first, last) / (double) spanProfiles;
        double rightCoverage = countInSpan(right, first, last) / (double) spanProfiles;
        double valleyPersistence = common == 0 ? 0.0
            : valleys.stream().filter(value -> value <= SEPARATE_VALLEY_RATIO).count() / (double) common;
        double tangentDifference = tangentDifferenceDegrees(left, right, profiles, first, last);
        double separation = separations.isEmpty()
            ? Math.abs(meanOffset(right) - meanOffset(left)) : median(separations);
        double orderStability = common == 0 ? 0.0 : stableOrder / (double) common;
        boolean complementary = common / (double) Math.max(1, union.size()) < COMPLEMENTARY_JOINT_UNION_RATIO;
        String reason = complementary ? "complementary-child-union"
            : valleyPersistence >= MIN_SEPARATE_VALLEY_PERSISTENCE ? "persistent-parallel-modes"
            : "shared-envelope";
        return new PairEvidence(common, common / (double) minimumSupport,
            common == 0 ? 0.0 : valleys.stream().mapToDouble(Double::doubleValue).average().orElse(0.0),
            common == 0 ? 0.0 : commonEnvelope / (double) common,
            unionRatio, jointRatio, leftCoverage, rightCoverage, valleyPersistence, tangentDifference,
            orderStability, separation, physicalSpan, complementary, reason);
    }

    private String classify(PairEvidence evidence, double sourcePixel) {
        if (independentlySeparate(evidence, sourcePixel)) {
            return "separate";
        }
        boolean denseCommonEnvelope = evidence.commonProfiles() >= MIN_PERSISTENT_PROFILES
            && evidence.commonSupportRatio() >= MIN_COMMON_SUPPORT_RATIO
            && evidence.commonEnvelopeRatio() >= MIN_COMMON_SUPPORT_RATIO;
        if (denseCommonEnvelope) {
            if (evidence.meanValleyRatio() >= COMBINED_VALLEY_RATIO) {
                return "combined";
            }
            if (evidence.meanValleyRatio() > SEPARATE_VALLEY_RATIO) {
                return "ambiguous";
            }
            return evidence.unionSupportRatio() >= MIN_BUNDLE_UNION_SUPPORT ? "ambiguous" : "separate";
        }
        boolean coherentUnion = evidence.unionSupportRatio() >= MIN_BUNDLE_UNION_SUPPORT
            && evidence.tangentDifferenceDegrees() <= MAX_BUNDLE_TANGENT_DIFFERENCE_DEGREES;
        boolean intermittentSeparatedModes = coherentUnion
            && evidence.valleyPersistence() >= MIN_SEPARATE_VALLEY_PERSISTENCE
            && evidence.orderStability() >= MIN_SEPARATE_ORDER_STABILITY
            && evidence.robustSeparationPx() >= MIN_SEPARATE_SOURCE_PIXELS * sourcePixel;
        if (intermittentSeparatedModes) {
            return "ambiguous";
        }
        if (coherentUnion && (evidence.complementary()
            || evidence.valleyPersistence() < MIN_SEPARATE_VALLEY_PERSISTENCE)) {
            return "combined";
        }
        return coherentUnion ? "ambiguous" : "separate";
    }

    private boolean independentlySeparate(PairEvidence evidence, double sourcePixel) {
        return evidence.leftCoverageRatio() >= MIN_SEPARATE_CHILD_COVERAGE
            && evidence.rightCoverageRatio() >= MIN_SEPARATE_CHILD_COVERAGE
            && evidence.physicalSpanMeters() >= MIN_SEPARATE_SPAN_METERS
            && evidence.orderStability() >= MIN_SEPARATE_ORDER_STABILITY
            && evidence.valleyPersistence() >= MIN_SEPARATE_VALLEY_PERSISTENCE
            && evidence.robustSeparationPx() >= MIN_SEPARATE_SOURCE_PIXELS * sourcePixel;
    }

    private GroupEvidence groupEvidence(
        List<CorridorTrack> children,
        Map<TrackPair, PairEvidence> pairEvidence,
        List<CorridorProfile> profiles,
        double sourcePixel
    ) {
        List<PairEvidence> pairs = new ArrayList<>();
        for (int left = 0; left < children.size(); left++) {
            for (int right = left + 1; right < children.size(); right++) {
                pairs.add(evidenceFor(children.get(left), children.get(right), pairEvidence));
            }
        }
        long jointProfiles = java.util.stream.IntStream.range(0, profiles.size())
            .filter(profile -> children.stream().filter(track -> track.points().containsKey(profile)).count() >= 2)
            .count();
        double unionSupport = unionSupport(children, profiles.size());
        double jointSupport = profiles.isEmpty() ? 0.0 : jointProfiles / (double) profiles.size();
        double valleyPersistence = pairs.stream().filter(value -> value.commonProfiles() > 0)
            .mapToDouble(PairEvidence::valleyPersistence).average().orElse(0.0);
        double tangentDifference = pairs.stream().mapToDouble(PairEvidence::tangentDifferenceDegrees)
            .max().orElse(180.0);
        double orderStability = pairs.stream().filter(value -> value.commonProfiles() > 0)
            .mapToDouble(PairEvidence::orderStability).min().orElse(1.0);
        double separation = pairs.stream().mapToDouble(PairEvidence::robustSeparationPx).max().orElse(0.0);
        boolean ambiguous = pairs.stream().anyMatch(value -> "ambiguous".equals(classify(value, sourcePixel)));
        String classification = ambiguous ? "ambiguous" : "combined";
        String reason = children.size() > 2 ? "multi-track-complementary-union"
            : pairs.get(0).reason();
        return new GroupEvidence(classification, unionSupport, jointSupport, valleyPersistence,
            Math.max(0.0, 1.0 - tangentDifference / 180.0), orderStability, separation, reason);
    }

    private BundleResult bundle(
        String id,
        List<CorridorTrack> children,
        List<CorridorProfile> profiles,
        double sourcePixel,
        GroupEvidence evidence
    ) {
        Map<Integer, SparseCorridorBundlePoint> bundlePoints = new LinkedHashMap<>();
        Map<Integer, CorridorTrackPoint> parentPoints = new LinkedHashMap<>();
        for (int profileIndex = 0; profileIndex < profiles.size(); profileIndex++) {
            List<Contributor> contributors = new ArrayList<>();
            for (CorridorTrack child : children) {
                addContributor(contributors, child, profileIndex);
            }
            boolean direct = contributors.stream().anyMatch(Contributor::direct);
            if (contributors.size() < children.size()) {
                for (CorridorTrack child : children) {
                    addPredictedContributor(contributors, child, profileIndex, profiles);
                }
            }
            contributors = contributors.stream().collect(java.util.stream.Collectors.toMap(
                Contributor::trackId, value -> value, (first, second) -> first, LinkedHashMap::new))
                .values().stream().toList();
            if (contributors.isEmpty()) {
                continue;
            }
            CenterEstimate center = robustCenter(contributors, sourcePixel);
            double shoulderMin = contributors.stream().mapToDouble(value -> value.band().shoulderMinPx()).min()
                .orElse(center.centerOffsetPx() - sourcePixel);
            double shoulderMax = contributors.stream().mapToDouble(value -> value.band().shoulderMaxPx()).max()
                .orElse(center.centerOffsetPx() + sourcePixel);
            double coreMin = contributors.stream().mapToDouble(value -> value.band().coreMinPx()).min()
                .orElse(center.centerOffsetPx() - sourcePixel * 0.5);
            double coreMax = contributors.stream().mapToDouble(value -> value.band().coreMaxPx()).max()
                .orElse(center.centerOffsetPx() + sourcePixel * 0.5);
            List<String> directIds = contributors.stream().filter(Contributor::direct)
                .map(Contributor::trackId).sorted().toList();
            List<String> predictedIds = contributors.stream().filter(value -> !value.direct())
                .map(Contributor::trackId).sorted().toList();
            CorridorPointSupport support = direct ? CorridorPointSupport.DIRECT_UNION
                : CorridorPointSupport.BOUNDED_INTERPOLATION;
            double occupancy = contributors.size() / (double) children.size();
            SparseCorridorBundlePoint bundlePoint = new SparseCorridorBundlePoint(profileIndex, support,
                directIds, predictedIds, center.centerOffsetPx(), center.uncertaintyPx(), shoulderMin,
                shoulderMax, coreMin, coreMax, occupancy, center.agreement());
            bundlePoints.put(profileIndex, bundlePoint);
            CorridorBand parentBand = parentBand(id, profileIndex, contributors, bundlePoint,
                evidence.classification());
            parentPoints.put(profileIndex, new CorridorTrackPoint(profileIndex, parentBand,
                support == CorridorPointSupport.BOUNDED_INTERPOLATION, support));
        }
        if (bundlePoints.isEmpty()) {
            return null;
        }
        List<String> childIds = children.stream().map(CorridorTrack::id).sorted().toList();
        SparseCorridorBundle bundle = new SparseCorridorBundle(id, childIds, evidence.classification(),
            bundlePoints, evidence.unionSupportRatio(), evidence.jointSupportRatio(),
            evidence.valleyPersistence(), evidence.tangentAgreement(),
            evidence.orderStability(), evidence.robustSeparationPx(), evidence.reason());
        double score = children.stream().mapToDouble(CorridorTrack::score).average().orElse(0.0);
        CorridorTrack parent = new CorridorTrack(id, parentPoints, score,
            bundle.directUnionProfileCount() / (double) profiles.size(), true,
            childIds, evidence.classification());
        return new BundleResult(bundle, parent);
    }

    private CorridorBand parentBand(
        String id,
        int profileIndex,
        List<Contributor> contributors,
        SparseCorridorBundlePoint point,
        String decision
    ) {
        List<Contributor> direct = contributors.stream().filter(Contributor::direct).toList();
        double peak = direct.stream().mapToDouble(value -> value.band().peakIntensity()).max().orElse(0.0);
        double noise = direct.stream().mapToDouble(value -> value.band().noiseFloor()).average().orElse(0.0);
        double gradient = direct.stream().mapToDouble(value -> value.band().gradientStrength()).average().orElse(0.0);
        double balance = direct.stream().mapToDouble(value -> value.band().gradientBalance()).average().orElse(0.0);
        double scale = direct.stream().mapToDouble(value -> value.band().scaleAgreement()).average().orElse(0.0);
        double existence = direct.stream().mapToDouble(value -> value.band().signalExistenceConfidence())
            .average().orElse(0.0);
        double localization = direct.stream().mapToDouble(value -> value.band().localizationConfidence())
            .average().orElse(0.0) * point.contributorAgreement();
        return new CorridorBand(id + "-profile-" + profileIndex, point.centerOffsetPx(),
            point.shoulderMinPx(), point.shoulderMaxPx(), point.coreMinPx(), point.coreMaxPx(),
            contributors.stream().map(value -> value.band().centerOffsetPx()).toList(), peak, noise,
            decision.equals("combined") ? 1.0 : 0.5, gradient, balance, scale, existence, localization,
            point.uncertaintyPx(), true, contributors.stream().map(value -> value.band().id()).toList());
    }

    private void addContributor(List<Contributor> result, CorridorTrack track, int profileIndex) {
        CorridorTrackPoint point = track.points().get(profileIndex);
        if (point != null) {
            result.add(new Contributor(track.id(), point.band(), true, contributorWeight(point.band())));
        }
    }

    private void addPredictedContributor(
        List<Contributor> result,
        CorridorTrack track,
        int profileIndex,
        List<CorridorProfile> profiles
    ) {
        if (result.stream().anyMatch(value -> value.trackId().equals(track.id()))) {
            return;
        }
        Prediction prediction = boundedPrediction(track, profileIndex, profiles);
        if (prediction != null) {
            CorridorBand shifted = shiftBand(prediction.referenceBand(), prediction.centerOffsetPx());
            result.add(new Contributor(track.id(), shifted, false, contributorWeight(shifted)));
        }
    }

    private Prediction boundedPrediction(CorridorTrack track, int profileIndex, List<CorridorProfile> profiles) {
        CorridorTrackPoint before = track.points().values().stream()
            .filter(point -> point.profileIndex() < profileIndex)
            .max(Comparator.comparingInt(CorridorTrackPoint::profileIndex)).orElse(null);
        CorridorTrackPoint after = track.points().values().stream()
            .filter(point -> point.profileIndex() > profileIndex)
            .min(Comparator.comparingInt(CorridorTrackPoint::profileIndex)).orElse(null);
        if (before == null || after == null || after.profileIndex() - before.profileIndex() - 1 > MAX_INTERPOLATION_PROFILES
            || distanceMeters(profiles, before.profileIndex(), after.profileIndex()) > MAX_INTERPOLATION_METERS + 1e-9) {
            return null;
        }
        double targetDistance = profileDistance(profiles, profileIndex);
        Regression regression = robustRegression(track, profiles, targetDistance);
        if (!Double.isFinite(regression.centerOffsetPx())) {
            double fraction = (targetDistance - profileDistance(profiles, before.profileIndex()))
                / Math.max(1e-9, profileDistance(profiles, after.profileIndex())
                    - profileDistance(profiles, before.profileIndex()));
            return new Prediction(before.band().centerOffsetPx()
                + fraction * (after.band().centerOffsetPx() - before.band().centerOffsetPx()), before.band());
        }
        return new Prediction(regression.centerOffsetPx(), before.band());
    }

    private Regression robustRegression(CorridorTrack track, List<CorridorProfile> profiles, double targetDistance) {
        List<CorridorTrackPoint> window = track.points().values().stream()
            .filter(point -> Math.abs(profileDistance(profiles, point.profileIndex()) - targetDistance)
                <= LOCAL_PREDICTION_HALF_WINDOW_METERS)
            .toList();
        if (window.size() < 2) {
            return new Regression(Double.NaN, 0.0);
        }
        double[] weights = new double[window.size()];
        for (int index = 0; index < window.size(); index++) {
            CorridorTrackPoint point = window.get(index);
            double distance = Math.abs(profileDistance(profiles, point.profileIndex()) - targetDistance);
            weights[index] = contributorWeight(point.band())
                * Math.max(0.10, 1.0 - distance / LOCAL_PREDICTION_HALF_WINDOW_METERS);
        }
        Regression fit = weightedRegression(window, profiles, weights, targetDistance);
        for (int iteration = 0; iteration < HUBER_ITERATIONS; iteration++) {
            List<Double> residuals = new ArrayList<>();
            for (CorridorTrackPoint point : window) {
                double x = profileDistance(profiles, point.profileIndex()) - targetDistance;
                residuals.add(Math.abs(point.band().centerOffsetPx()
                    - (fit.centerOffsetPx() + fit.slopeOffsetPerMeter() * x)));
            }
            double scale = Math.max(1e-6, median(residuals));
            for (int index = 0; index < window.size(); index++) {
                double residual = residuals.get(index);
                double huber = residual <= 1.5 * scale ? 1.0 : 1.5 * scale / residual;
                weights[index] *= huber;
            }
            fit = weightedRegression(window, profiles, weights, targetDistance);
        }
        return fit;
    }

    private Regression weightedRegression(
        List<CorridorTrackPoint> points,
        List<CorridorProfile> profiles,
        double[] weights,
        double targetDistance
    ) {
        double totalWeight = 0.0;
        double meanX = 0.0;
        double meanY = 0.0;
        for (int index = 0; index < points.size(); index++) {
            double weight = weights[index];
            totalWeight += weight;
            meanX += weight * (profileDistance(profiles, points.get(index).profileIndex()) - targetDistance);
            meanY += weight * points.get(index).band().centerOffsetPx();
        }
        if (totalWeight <= 1e-12) {
            return new Regression(Double.NaN, 0.0);
        }
        meanX /= totalWeight;
        meanY /= totalWeight;
        double numerator = 0.0;
        double denominator = 0.0;
        for (int index = 0; index < points.size(); index++) {
            double x = profileDistance(profiles, points.get(index).profileIndex()) - targetDistance - meanX;
            numerator += weights[index] * x * (points.get(index).band().centerOffsetPx() - meanY);
            denominator += weights[index] * x * x;
        }
        double slope = denominator <= 1e-12 ? 0.0 : numerator / denominator;
        return new Regression(meanY - slope * meanX, slope);
    }

    private CenterEstimate robustCenter(List<Contributor> contributors, double sourcePixel) {
        List<Double> centers = contributors.stream().map(value -> value.band().centerOffsetPx()).sorted().toList();
        double center = (centers.get(0) + centers.get(centers.size() - 1)) / 2.0;
        List<Double> baseWeights = contributors.stream().map(Contributor::weight).sorted().toList();
        double weightCap = 2.0 * median(baseWeights);
        double scale = Math.max(0.5 * sourcePixel, medianAbsoluteDeviation(centers, center));
        for (int iteration = 0; iteration < HUBER_ITERATIONS; iteration++) {
            double weighted = 0.0;
            double total = 0.0;
            for (Contributor contributor : contributors) {
                double residual = Math.abs(contributor.band().centerOffsetPx() - center);
                double huber = residual <= 1.5 * scale ? 1.0 : 1.5 * scale / residual;
                double weight = Math.min(weightCap, contributor.weight()) * huber;
                weighted += weight * contributor.band().centerOffsetPx();
                total += weight;
            }
            if (total > 0.0) {
                center = weighted / total;
            }
            scale = Math.max(0.5 * sourcePixel, medianAbsoluteDeviation(centers, center));
        }
        double meanUncertainty = contributors.stream().mapToDouble(value -> value.band().uncertaintyPx())
            .average().orElse(sourcePixel);
        double uncertainty = Math.max(0.5 * sourcePixel, scale + meanUncertainty * 0.5);
        if (contributors.size() == 1) {
            uncertainty *= 1.75;
        } else if (contributors.stream().noneMatch(Contributor::direct)) {
            uncertainty *= 1.35;
        }
        double spread = centers.get(centers.size() - 1) - centers.get(0);
        double agreement = 1.0 / (1.0 + spread / Math.max(sourcePixel, uncertainty));
        return new CenterEstimate(center, uncertainty, agreement);
    }

    private double tangentDifferenceDegrees(
        CorridorTrack left,
        CorridorTrack right,
        List<CorridorProfile> profiles,
        int first,
        int last
    ) {
        double leftSlope = medianTrackSlope(left, profiles, first, last);
        double rightSlope = medianTrackSlope(right, profiles, first, last);
        if (!Double.isFinite(leftSlope) || !Double.isFinite(rightSlope)) {
            return 180.0;
        }
        return Math.toDegrees(Math.abs(Math.atan(leftSlope) - Math.atan(rightSlope)));
    }

    private double medianTrackSlope(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        int first,
        int last
    ) {
        List<CorridorTrackPoint> ordered = track.points().values().stream()
            .filter(point -> point.profileIndex() >= first && point.profileIndex() <= last)
            .sorted(Comparator.comparingInt(CorridorTrackPoint::profileIndex)).toList();
        List<Double> slopes = new ArrayList<>();
        for (int index = 1; index < ordered.size(); index++) {
            CorridorTrackPoint before = ordered.get(index - 1);
            CorridorTrackPoint after = ordered.get(index);
            double distance = distanceMeters(profiles, before.profileIndex(), after.profileIndex());
            if (distance > 1e-9 && distance <= MAX_INTERPOLATION_METERS + 1e-9) {
                slopes.add((after.band().centerOffsetPx() - before.band().centerOffsetPx()) / distance);
            }
        }
        return slopes.isEmpty() ? Double.NaN : median(slopes);
    }

    private CorridorBand shiftBand(CorridorBand source, double center) {
        double shift = center - source.centerOffsetPx();
        return new CorridorBand(source.id() + "-predicted", center, source.shoulderMinPx() + shift,
            source.shoulderMaxPx() + shift, source.coreMinPx() + shift, source.coreMaxPx() + shift,
            List.of(center), source.peakIntensity(), source.noiseFloor(), source.valleyRatio(),
            source.gradientStrength(), source.gradientBalance(), source.scaleAgreement(),
            source.signalExistenceConfidence(), source.localizationConfidence(), source.uncertaintyPx(),
            source.parentHypothesis(), source.childIds());
    }

    private double contributorWeight(CorridorBand band) {
        double weight = band.signalExistenceConfidence() * (0.25 + 0.75 * band.localizationConfidence());
        return Math.max(1e-6, weight);
    }

    private double valleyRatio(CorridorProfile profile, CorridorBand left, CorridorBand right) {
        double from = Math.min(left.centerOffsetPx(), right.centerOffsetPx());
        double to = Math.max(left.centerOffsetPx(), right.centerOffsetPx());
        List<IntensitySample> between = profile.source().intensitySamples().stream()
            .filter(IntensitySample::insideRaster)
            .filter(sample -> sample.offsetPx() >= from && sample.offsetPx() <= to).toList();
        if (between.isEmpty()) {
            return 0.0;
        }
        double valley = between.stream().mapToDouble(IntensitySample::standardFilteredIntensity).min().orElse(0.0);
        double weakerPeak = Math.min(left.peakIntensity(), right.peakIntensity());
        return weakerPeak <= 1e-9 ? 0.0 : clamp(valley / weakerPeak);
    }

    private boolean envelopesTouch(CorridorBand left, CorridorBand right) {
        double gap = Math.max(0.0, Math.max(left.shoulderMinPx(), right.shoulderMinPx())
            - Math.min(left.shoulderMaxPx(), right.shoulderMaxPx()));
        double width = Math.max(1.0, Math.min(left.shoulderWidthPx(), right.shoulderWidthPx()));
        return gap <= width * 0.5;
    }

    private int countInSpan(CorridorTrack track, int first, int last) {
        return (int) track.points().keySet().stream().filter(index -> index >= first && index <= last).count();
    }

    private double meanOffset(CorridorTrack track) {
        return track.points().values().stream().mapToDouble(point -> point.band().centerOffsetPx())
            .average().orElse(0.0);
    }

    private double profileDistance(List<CorridorProfile> profiles, int index) {
        return profiles.get(index).source().cumulativeGroundDistanceMeters();
    }

    private double distanceMeters(List<CorridorProfile> profiles, int left, int right) {
        return Math.abs(profileDistance(profiles, right) - profileDistance(profiles, left));
    }

    private double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0 ? (sorted.get(middle - 1) + sorted.get(middle)) / 2.0
            : sorted.get(middle);
    }

    private double medianAbsoluteDeviation(List<Double> values, double center) {
        return median(values.stream().map(value -> Math.abs(value - center)).toList());
    }

    private double validSourcePixel(double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException("Source pixel size must be finite and positive");
        }
        return value;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /**
     * Grouping output retaining children, sparse parents, and pair decisions.
     *
     * @param tracks elementary tracks plus accepted parent adapters
     * @param decisions all pairwise classification evidence
     * @param bundles sparse parent metadata keyed by parent id
     */
    public record GroupingResult(
        List<CorridorTrack> tracks,
        List<GroupingDecision> decisions,
        List<SparseCorridorBundle> bundles
    ) {
        /** Makes grouping result collections immutable. */
        public GroupingResult {
            tracks = List.copyOf(tracks);
            decisions = List.copyOf(decisions);
            bundles = List.copyOf(bundles);
        }
    }

    /**
     * Pairwise longitudinal evidence used for sparse grouping decisions.
     *
     * @param leftTrackId first child track id
     * @param rightTrackId second child track id
     * @param commonProfiles profiles with both child observations
     * @param commonSupportRatio joint support relative to the smaller child
     * @param meanValleyRatio mean normalized valley over joint profiles
     * @param commonEnvelopeRatio fraction of joint profiles with touching envelopes
     * @param decision combined, ambiguous, or separate classification
     * @param unionSupportRatio direct child-union support relative to all profiles
     * @param jointSupportRatio joint support relative to all profiles
     * @param valleyPersistence fraction of joint profiles with a deep valley
     * @param tangentDifferenceDegrees robust child tangent difference
     * @param orderStability fraction of joint profiles retaining lateral order
     * @param robustSeparationPx robust child separation in sampled-raster pixels
     * @param physicalSpanMeters child-union span in ground metres
     * @param reason machine-readable primary classification reason
     */
    public record GroupingDecision(
        String leftTrackId,
        String rightTrackId,
        int commonProfiles,
        double commonSupportRatio,
        double meanValleyRatio,
        double commonEnvelopeRatio,
        String decision,
        double unionSupportRatio,
        double jointSupportRatio,
        double valleyPersistence,
        double tangentDifferenceDegrees,
        double orderStability,
        double robustSeparationPx,
        double physicalSpanMeters,
        String reason
    ) {
    }

    private record PairEvidence(
        int commonProfiles,
        double commonSupportRatio,
        double meanValleyRatio,
        double commonEnvelopeRatio,
        double unionSupportRatio,
        double jointSupportRatio,
        double leftCoverageRatio,
        double rightCoverageRatio,
        double valleyPersistence,
        double tangentDifferenceDegrees,
        double orderStability,
        double robustSeparationPx,
        double physicalSpanMeters,
        boolean complementary,
        String reason
    ) {
        private static PairEvidence empty() {
            return new PairEvidence(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                0.0, 180.0, 0.0, 0.0, 0.0, false, "no-union-support");
        }
    }

    private record Contributor(String trackId, CorridorBand band, boolean direct, double weight) {
    }

    private record Prediction(double centerOffsetPx, CorridorBand referenceBand) {
    }

    private record Regression(double centerOffsetPx, double slopeOffsetPerMeter) {
    }

    private record CenterEstimate(double centerOffsetPx, double uncertaintyPx, double agreement) {
    }

    private record BundleResult(SparseCorridorBundle bundle, CorridorTrack parentTrack) {
    }

    private record GroupEvidence(
        String classification,
        double unionSupportRatio,
        double jointSupportRatio,
        double valleyPersistence,
        double tangentAgreement,
        double orderStability,
        double robustSeparationPx,
        String reason
    ) {
    }

    private record TrackPair(String leftId, String rightId) {
        private static TrackPair ordered(String first, String second) {
            return first.compareTo(second) <= 0 ? new TrackPair(first, second) : new TrackPair(second, first);
        }
    }
}
