package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Node;

/**
 * Immutable, fail-closed cleanup input built from a candidate's reconstructed final preview.
 *
 * <p>The context deliberately proves every retained non-protected preview point against exactly
 * one original cleanup profile. When final-preview reconstruction inserts a protected topology
 * anchor, sequence reconciliation may replace one adjacent raw-profile point with that exact
 * anchor in the cleanup-only geometry. The protected anchor consumes existing evidence only as an
 * immutable interval boundary; no scalar profile or chainage is synthesized. The raw candidate is
 * never changed.</p>
 *
 * @param geometry final-preview geometry aligned to {@code evidence}
 * @param protectedIndexes fixed, tagged, shared, endpoint, and junction point indexes
 * @param protectedIntervals independent point-index intervals bounded by protected anchors
 * @param evidence compact candidate-owned evidence reindexed to {@code geometry}
 * @param originalProfileIndexes original candidate profile indexes represented by each point
 * @param status construction result
 */
public record FinalPreviewCleanupContext(
    List<EastNorth> geometry,
    Set<Integer> protectedIndexes,
    List<ProtectedInterval> protectedIntervals,
    CandidateCleanupEvidence evidence,
    List<Integer> originalProfileIndexes,
    Status status
) {
    private static final double COORDINATE_TOLERANCE = 1e-7;
    private static final double MIN_SIGNAL = 1e-6;

    /** Copies all public collections and rejects incomplete context state. */
    public FinalPreviewCleanupContext {
        geometry = List.copyOf(geometry);
        protectedIndexes = Set.copyOf(protectedIndexes);
        protectedIntervals = List.copyOf(protectedIntervals);
        evidence = Objects.requireNonNull(evidence, "evidence");
        originalProfileIndexes = List.copyOf(originalProfileIndexes);
        status = Objects.requireNonNull(status, "status");
        if (status == Status.COMPLETE
            && (geometry.size() != originalProfileIndexes.size() || !evidence.eligible())) {
            throw new IllegalArgumentException("Complete cleanup context must have aligned evidence");
        }
    }

    /**
     * Constructs a context after final-preview topology reconstruction.
     *
     * @param candidate raw corridor-aware candidate with final-preview geometry
     * @param selection selected source segment
     * @param sourcePolyline immutable selected source geometry in node order
     * @return a complete context or a typed, non-cleanable context
     */
    public static FinalPreviewCleanupContext create(
        CenterlineCandidate candidate,
        SelectionContext selection,
        List<EastNorth> sourcePolyline
    ) {
        if (candidate == null || selection == null || sourcePolyline == null
            || sourcePolyline.size() != selection.segmentNodes().size()) {
            return rejected(Status.INVALID_INPUT);
        }
        List<EastNorth> finalPreview = candidate.finalPreviewPoints();
        List<EastNorth> rawProfiles = candidate.eastNorthPoints();
        CandidateCleanupEvidence rawEvidence = candidate.cleanupEvidence();
        if (!rawEvidence.eligible() && rawEvidence.profiles().isEmpty()) {
            return rejected(Status.INCOMPLETE_EVIDENCE);
        }
        if (finalPreview.size() < 3 || rawProfiles.size() != rawEvidence.profiles().size()
            || rawProfiles.size() != rawEvidence.samplingFrame().profiles().size()) {
            return rejected(Status.MISMATCHED_EVIDENCE);
        }
        for (EastNorth point : sourcePolyline) {
            if (!finite(point)) {
                return rejected(Status.INVALID_INPUT);
            }
        }

        Set<Integer> protectedIndexes = protectedIndexes(candidate, selection, sourcePolyline, finalPreview);
        if (protectedIndexes == null) {
            return rejected(Status.MISSING_PROTECTED_ANCHOR);
        }
        ProfileMapping mapping = mapProfiles(finalPreview, rawProfiles, protectedIndexes);
        if (mapping.status() != Status.COMPLETE) {
            return rejected(mapping.status());
        }
        List<EastNorth> cleanupGeometry = mapping.previewIndexes().stream()
            .map(finalPreview::get)
            .toList();
        Set<Integer> cleanupProtectedIndexes = remapProtectedIndexes(
            mapping.previewIndexes(), protectedIndexes);
        List<Integer> mapped = mapping.profileIndexes();
        Status evidenceStatus = validateMovableEvidence(mapped, cleanupProtectedIndexes, rawEvidence);
        if (evidenceStatus != Status.COMPLETE) {
            return rejected(evidenceStatus);
        }
        if (!rawEvidence.eligible()) {
            return rejected(Status.INCOMPLETE_EVIDENCE);
        }
        try {
            CandidateCleanupEvidence aligned = reindexedEvidence(
                mapped, cleanupGeometry, cleanupProtectedIndexes, rawProfiles, rawEvidence);
            List<ProtectedInterval> intervals = intervals(cleanupGeometry.size(), cleanupProtectedIndexes);
            if (intervals.isEmpty()) {
                return rejected(Status.INVALID_PROTECTED_INTERVALS);
            }
            return new FinalPreviewCleanupContext(cleanupGeometry, cleanupProtectedIndexes, intervals,
                aligned, mapped, Status.COMPLETE);
        } catch (IllegalArgumentException exception) {
            return rejected(Status.MISMATCHED_EVIDENCE);
        }
    }

    /**
     * Reports whether cleanup services may consume this context.
     *
     * @return true only for fully reconciled preview geometry and evidence
     */
    public boolean complete() {
        return status == Status.COMPLETE;
    }

    /**
     * Returns evidence reindexed to a simplifier-retained subset of this context geometry.
     *
     * @param retainedContextIndexes strictly increasing indexes returned by constrained reduction
     * @return complete evidence aligned one-to-one with the retained cleaned geometry
     * @throws IllegalArgumentException when indexes are not a strictly increasing context subset
     */
    public CandidateCleanupEvidence retainedEvidence(List<Integer> retainedContextIndexes) {
        if (!complete() || retainedContextIndexes == null || retainedContextIndexes.size() < 2) {
            throw new IllegalArgumentException("Retained cleanup evidence requires a complete non-empty context");
        }
        int previous = -1;
        List<CleanupSamplingProfile> samples = new ArrayList<>(retainedContextIndexes.size());
        List<CandidateCleanupProfile> rows = new ArrayList<>(retainedContextIndexes.size());
        for (int outputIndex = 0; outputIndex < retainedContextIndexes.size(); outputIndex++) {
            Integer contextIndex = retainedContextIndexes.get(outputIndex);
            if (contextIndex == null || contextIndex <= previous || contextIndex >= evidence.profiles().size()) {
                throw new IllegalArgumentException("Retained cleanup indexes must be strictly increasing and in range");
            }
            samples.add(copyProfile(outputIndex, evidence.samplingFrame().profiles().get(contextIndex)));
            rows.add(copyRow(outputIndex, evidence.profiles().get(contextIndex)));
            previous = contextIndex;
        }
        return CandidateCleanupEvidence.complete(new CleanupSamplingFrame(evidence.samplingFrame().detectorMode(),
            samples, evidence.samplingFrame().groundMetersPerRasterPixel()), rows);
    }

    private static FinalPreviewCleanupContext rejected(Status status) {
        return new FinalPreviewCleanupContext(List.of(), Set.of(), List.of(), CandidateCleanupEvidence.empty(),
            List.of(), status);
    }

    private static Set<Integer> protectedIndexes(
        CenterlineCandidate candidate,
        SelectionContext selection,
        List<EastNorth> source,
        List<EastNorth> finalPreview
    ) {
        Map<Long, EastNorth> proposed = candidate.proposedNodePositions();
        Set<Integer> result = new LinkedHashSet<>();
        int last = selection.segmentNodes().size() - 1;
        for (int sourceIndex = 0; sourceIndex <= last; sourceIndex++) {
            Node node = selection.segmentNodes().get(sourceIndex);
            if (!isProtected(selection, node, sourceIndex, last)) {
                continue;
            }
            EastNorth target = selection.fixedNodes().contains(node)
                ? source.get(sourceIndex) : proposed.get(node.getUniqueId());
            if (!finite(target)) {
                return null;
            }
            int previewIndex = uniqueCoordinateIndex(finalPreview, target);
            if (previewIndex < 0) {
                return null;
            }
            result.add(previewIndex);
        }
        return result.size() >= 2 ? Set.copyOf(result) : null;
    }

    private static boolean isProtected(SelectionContext selection, Node node, int index, int last) {
        return index == 0 || index == last || selection.fixedNodes().contains(node) || node.hasKeys()
            || node.getReferrers().stream().anyMatch(referrer -> referrer != selection.way());
    }

    private static int uniqueCoordinateIndex(List<EastNorth> points, EastNorth target) {
        int found = -1;
        for (int index = 0; index < points.size(); index++) {
            if (!samePoint(points.get(index), target)) {
                continue;
            }
            if (found >= 0) {
                return -1;
            }
            found = index;
        }
        return found;
    }

    private static ProfileMapping mapProfiles(
        List<EastNorth> finalPreview,
        List<EastNorth> rawProfiles,
        Set<Integer> protectedIndexes
    ) {
        if (finalPreview.size() <= rawProfiles.size()) {
            return mapProfilesWithoutInsertedAnchors(finalPreview, rawProfiles, protectedIndexes);
        }
        int previousExact = -1;
        for (int previewIndex = 0; previewIndex < finalPreview.size(); previewIndex++) {
            EastNorth point = finalPreview.get(previewIndex);
            if (!finite(point)) {
                return ProfileMapping.failed(Status.UNMAPPED_OR_DUPLICATE_PROFILE);
            }
            if (protectedIndexes.contains(previewIndex)) {
                continue;
            }
            int found = -1;
            for (int index = 0; index < rawProfiles.size(); index++) {
                if (samePoint(point, rawProfiles.get(index))) {
                    if (found >= 0) {
                        return ProfileMapping.failed(Status.UNMAPPED_OR_DUPLICATE_PROFILE);
                    }
                    found = index;
                }
            }
            if (found < 0) {
                return ProfileMapping.failed(Status.UNMAPPED_OR_DUPLICATE_PROFILE);
            }
            if (found <= previousExact) {
                return ProfileMapping.failed(Status.NON_MONOTONIC_PROFILE_MAPPING);
            }
            previousExact = found;
        }

        ProfileAlignment alignment = alignProfileSequences(finalPreview, rawProfiles, protectedIndexes);
        if (alignment == null || alignment.previewIndexes().size() < 3) {
            return ProfileMapping.failed(Status.UNMAPPED_OR_DUPLICATE_PROFILE);
        }
        return new ProfileMapping(alignment.previewIndexes(), alignment.profileIndexes(), Status.COMPLETE);
    }

    private static ProfileMapping mapProfilesWithoutInsertedAnchors(
        List<EastNorth> finalPreview,
        List<EastNorth> rawProfiles,
        Set<Integer> protectedIndexes
    ) {
        boolean[] consumed = new boolean[rawProfiles.size()];
        List<Integer> result = new ArrayList<>(Collections.nCopies(finalPreview.size(), -1));
        for (int previewIndex = 0; previewIndex < finalPreview.size(); previewIndex++) {
            if (protectedIndexes.contains(previewIndex)) {
                continue;
            }
            EastNorth point = finalPreview.get(previewIndex);
            if (!finite(point)) {
                return ProfileMapping.failed(Status.UNMAPPED_OR_DUPLICATE_PROFILE);
            }
            int found = uniqueMatchingProfile(rawProfiles, point);
            if (found < 0) {
                return ProfileMapping.failed(Status.UNMAPPED_OR_DUPLICATE_PROFILE);
            }
            consumed[found] = true;
            result.set(previewIndex, found);
        }
        int previous = -1;
        for (int previewIndex = 0; previewIndex < finalPreview.size(); previewIndex++) {
            if (!finite(finalPreview.get(previewIndex))) {
                return ProfileMapping.failed(Status.UNMAPPED_OR_DUPLICATE_PROFILE);
            }
            int nextRequired = nextRequiredProfile(result, previewIndex + 1, rawProfiles.size());
            int found = result.get(previewIndex);
            if (found < 0) {
                found = nearestAvailableProfile(rawProfiles, finalPreview.get(previewIndex), consumed,
                    previous + 1, nextRequired - 1, previewIndex, finalPreview.size());
                if (found < 0) {
                    return ProfileMapping.failed(Status.UNMAPPED_OR_DUPLICATE_PROFILE);
                }
                consumed[found] = true;
                result.set(previewIndex, found);
            }
            if (found <= previous || found >= nextRequired) {
                return ProfileMapping.failed(Status.NON_MONOTONIC_PROFILE_MAPPING);
            }
            previous = found;
        }
        return new ProfileMapping(identityIndexes(finalPreview.size()), result, Status.COMPLETE);
    }

    /** Returns the unique raw profile at a coordinate, or {@code -1} for absent/ambiguous matches. */
    private static int uniqueMatchingProfile(List<EastNorth> profiles, EastNorth point) {
        int found = -1;
        for (int index = 0; index < profiles.size(); index++) {
            if (!samePoint(point, profiles.get(index))) {
                continue;
            }
            if (found >= 0) {
                return -1;
            }
            found = index;
        }
        return found;
    }

    /** Builds stable identity indexes for a preview that needs no point replacement. */
    private static List<Integer> identityIndexes(int size) {
        List<Integer> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            result.add(index);
        }
        return List.copyOf(result);
    }

    /** Finds the next already-fixed raw profile mapping after a preview position. */
    private static int nextRequiredProfile(List<Integer> mappings, int start, int defaultValue) {
        for (int index = start; index < mappings.size(); index++) {
            int mapped = mappings.get(index);
            if (mapped >= 0) {
                return mapped;
            }
        }
        return defaultValue;
    }

    /** Selects an unused profile for a protected anchor within monotonic mapping bounds. */
    private static int nearestAvailableProfile(
        List<EastNorth> rawProfiles,
        EastNorth protectedPoint,
        boolean[] consumed,
        int minimum,
        int maximum,
        int previewIndex,
        int previewSize
    ) {
        int preferredByFraction = (int) Math.round(
            previewSize <= 1 ? 0.0 : (double) previewIndex * (rawProfiles.size() - 1) / (previewSize - 1));
        int exact = -1;
        int fallback = -1;
        for (int index = Math.max(0, minimum); index <= Math.min(maximum, rawProfiles.size() - 1); index++) {
            if (consumed[index]) {
                continue;
            }
            if (fallback < 0 || closerTo(index, fallback, preferredByFraction)) {
                fallback = index;
            }
            if (samePoint(protectedPoint, rawProfiles.get(index))
                && (exact < 0 || closerTo(index, exact, preferredByFraction))) {
                exact = index;
            }
        }
        return exact >= 0 ? exact : fallback;
    }

    /** Orders raw profile indexes by distance from a preferred longitudinal index. */
    private static boolean closerTo(int candidate, int current, int preferred) {
        int candidateDistance = Math.abs(candidate - preferred);
        int currentDistance = Math.abs(current - preferred);
        return candidateDistance < currentDistance
            || candidateDistance == currentDistance && candidate < current;
    }

    private record ProfileMapping(
        List<Integer> previewIndexes,
        List<Integer> profileIndexes,
        Status status
    ) {
        private ProfileMapping {
            previewIndexes = List.copyOf(previewIndexes);
            profileIndexes = List.copyOf(profileIndexes);
            status = Objects.requireNonNull(status, "status");
        }

        private static ProfileMapping failed(Status status) {
            return new ProfileMapping(List.of(), List.of(), status);
        }
    }

    /**
     * Aligns an anchor-augmented preview to retained profiles without synthesizing evidence.
     *
     * <p>The dynamic program minimizes removed movable preview points first and protected-anchor
     * distance second. Protected points cannot be removed; ordinary points can match only their
     * exact raw coordinate.</p>
     */
    private static ProfileAlignment alignProfileSequences(
        List<EastNorth> preview,
        List<EastNorth> profiles,
        Set<Integer> protectedIndexes
    ) {
        final int unreachable = Integer.MAX_VALUE / 4;
        final byte match = 1;
        final byte skipProfile = 2;
        final byte dropPreview = 3;
        int previewCount = preview.size();
        int profileCount = profiles.size();
        int[][] dropped = new int[previewCount + 1][profileCount + 1];
        double[][] protectedDistance = new double[previewCount + 1][profileCount + 1];
        byte[][] predecessor = new byte[previewCount + 1][profileCount + 1];
        for (int index = 0; index <= previewCount; index++) {
            Arrays.fill(dropped[index], unreachable);
            Arrays.fill(protectedDistance[index], Double.POSITIVE_INFINITY);
        }
        dropped[0][0] = 0;
        protectedDistance[0][0] = 0.0;
        for (int previewIndex = 0; previewIndex <= previewCount; previewIndex++) {
            for (int profileIndex = 0; profileIndex <= profileCount; profileIndex++) {
                if (dropped[previewIndex][profileIndex] == unreachable) {
                    continue;
                }
                if (profileIndex < profileCount) {
                    updateAlignment(dropped, protectedDistance, predecessor,
                        previewIndex, profileIndex + 1,
                        dropped[previewIndex][profileIndex], protectedDistance[previewIndex][profileIndex],
                        skipProfile);
                }
                if (previewIndex < previewCount && !protectedIndexes.contains(previewIndex)) {
                    updateAlignment(dropped, protectedDistance, predecessor,
                        previewIndex + 1, profileIndex,
                        dropped[previewIndex][profileIndex] + 1,
                        protectedDistance[previewIndex][profileIndex], dropPreview);
                }
                if (previewIndex < previewCount && profileIndex < profileCount
                    && (protectedIndexes.contains(previewIndex)
                        || samePoint(preview.get(previewIndex), profiles.get(profileIndex)))) {
                    double distance = protectedIndexes.contains(previewIndex)
                        ? preview.get(previewIndex).distance(profiles.get(profileIndex)) : 0.0;
                    if (Double.isFinite(distance)) {
                        double expected = previewCount <= 1 ? 0.0
                            : (double) previewIndex * (profileCount - 1) / (previewCount - 1);
                        double tieBreak = 1e-12 * Math.abs(profileIndex - expected);
                        updateAlignment(dropped, protectedDistance, predecessor,
                            previewIndex + 1, profileIndex + 1,
                            dropped[previewIndex][profileIndex],
                            protectedDistance[previewIndex][profileIndex] + distance * distance + tieBreak,
                            match);
                    }
                }
            }
        }
        if (dropped[previewCount][profileCount] == unreachable) {
            return null;
        }

        List<Integer> retainedPreview = new ArrayList<>();
        List<Integer> retainedProfiles = new ArrayList<>();
        int previewIndex = previewCount;
        int profileIndex = profileCount;
        while (previewIndex > 0 || profileIndex > 0) {
            byte operation = predecessor[previewIndex][profileIndex];
            if (operation == match) {
                retainedPreview.add(previewIndex - 1);
                retainedProfiles.add(profileIndex - 1);
                previewIndex--;
                profileIndex--;
            } else if (operation == skipProfile) {
                profileIndex--;
            } else if (operation == dropPreview) {
                previewIndex--;
            } else {
                return null;
            }
        }
        Collections.reverse(retainedPreview);
        Collections.reverse(retainedProfiles);
        return new ProfileAlignment(retainedPreview, retainedProfiles);
    }

    /** Retains the lexicographically best deterministic predecessor for one alignment state. */
    private static void updateAlignment(
        int[][] dropped,
        double[][] protectedDistance,
        byte[][] predecessor,
        int previewIndex,
        int profileIndex,
        int candidateDropped,
        double candidateDistance,
        byte candidateOperation
    ) {
        int existingDropped = dropped[previewIndex][profileIndex];
        double existingDistance = protectedDistance[previewIndex][profileIndex];
        if (candidateDropped < existingDropped
            || candidateDropped == existingDropped
                && (candidateDistance < existingDistance - 1e-12
                    || Math.abs(candidateDistance - existingDistance) <= 1e-12
                        && operationPriority(candidateOperation)
                            < operationPriority(predecessor[previewIndex][profileIndex]))) {
            dropped[previewIndex][profileIndex] = candidateDropped;
            protectedDistance[previewIndex][profileIndex] = candidateDistance;
            predecessor[previewIndex][profileIndex] = candidateOperation;
        }
    }

    /** Returns deterministic tie priority for a sequence-alignment operation. */
    private static int operationPriority(byte operation) {
        return switch (operation) {
            case 1 -> 0;
            case 2 -> 1;
            case 3 -> 2;
            default -> 3;
        };
    }

    private record ProfileAlignment(List<Integer> previewIndexes, List<Integer> profileIndexes) {
        private ProfileAlignment {
            previewIndexes = List.copyOf(previewIndexes);
            profileIndexes = List.copyOf(profileIndexes);
        }
    }

    /** Reindexes original protected preview occurrences after cleanup-only point replacement. */
    private static Set<Integer> remapProtectedIndexes(
        List<Integer> retainedPreviewIndexes,
        Set<Integer> originalProtectedIndexes
    ) {
        Set<Integer> result = new LinkedHashSet<>();
        for (int index = 0; index < retainedPreviewIndexes.size(); index++) {
            if (originalProtectedIndexes.contains(retainedPreviewIndexes.get(index))) {
                result.add(index);
            }
        }
        return Set.copyOf(result);
    }

    private static Status validateMovableEvidence(
        List<Integer> mapped,
        Set<Integer> protectedIndexes,
        CandidateCleanupEvidence evidence
    ) {
        for (int previewIndex = 0; previewIndex < mapped.size(); previewIndex++) {
            if (protectedIndexes.contains(previewIndex)) {
                continue;
            }
            int sourceIndex = mapped.get(previewIndex);
            CandidateCleanupProfile row = evidence.profiles().get(sourceIndex);
            CleanupSamplingProfile sample = evidence.samplingFrame().profiles().get(sourceIndex);
            if (row.provenance() == CleanupEvidenceProvenance.BOUNDED_INTERPOLATION) {
                return Status.INTERPOLATED_MOVABLE_POINT;
            }
            if (row.provenance() != CleanupEvidenceProvenance.DIRECT) {
                return Status.UNSUPPORTED_MOVABLE_POINT;
            }
            if (!sample.anchorWithinRaster()) {
                return Status.OFF_RASTER_MOVABLE_POINT;
            }
            if (!hasSignal(sample)) {
                return Status.NO_SIGNAL_MOVABLE_POINT;
            }
        }
        return Status.COMPLETE;
    }

    private static CandidateCleanupEvidence reindexedEvidence(
        List<Integer> indexes,
        List<EastNorth> geometry,
        Set<Integer> protectedIndexes,
        List<EastNorth> rawProfiles,
        CandidateCleanupEvidence source
    ) {
        List<CleanupSamplingProfile> samples = new ArrayList<>(indexes.size());
        List<CandidateCleanupProfile> rows = new ArrayList<>(indexes.size());
        for (int resultIndex = 0; resultIndex < indexes.size(); resultIndex++) {
            int sourceIndex = indexes.get(resultIndex);
            CleanupSamplingProfile profile = source.samplingFrame().profiles().get(sourceIndex);
            CandidateCleanupProfile row = source.profiles().get(sourceIndex);
            samples.add(copyProfile(resultIndex, profile));
            boolean boundaryOnly = protectedIndexes.contains(resultIndex)
                && !samePoint(geometry.get(resultIndex), rawProfiles.get(sourceIndex));
            rows.add(boundaryOnly ? boundaryOnlyRow(resultIndex, row) : copyRow(resultIndex, row));
        }
        return CandidateCleanupEvidence.complete(new CleanupSamplingFrame(source.samplingFrame().detectorMode(),
            samples, source.samplingFrame().groundMetersPerRasterPixel()), rows);
    }

    private static CleanupSamplingProfile copyProfile(int index, CleanupSamplingProfile source) {
        int count = source.sampleCount();
        double[] offsets = new double[count];
        double[] nativeIntensity = new double[count];
        double[] lightIntensity = new double[count];
        double[] standardIntensity = new double[count];
        boolean[] insideRaster = new boolean[count];
        for (int sample = 0; sample < count; sample++) {
            offsets[sample] = source.offsetPxAt(sample);
            nativeIntensity[sample] = source.nativeIntensityAt(sample);
            lightIntensity[sample] = source.lightFilteredIntensityAt(sample);
            standardIntensity[sample] = source.standardFilteredIntensityAt(sample);
            insideRaster[sample] = source.insideRasterAt(sample);
        }
        return new CleanupSamplingProfile(index, source.cumulativeGroundDistanceMeters(),
            source.anchorWithinRaster(), source.sourcePixelPitchRasterPx(), source.projectedLateralTransform(),
            offsets, nativeIntensity, lightIntensity, standardIntensity, insideRaster);
    }

    private static CandidateCleanupProfile copyRow(int index, CandidateCleanupProfile source) {
        return new CandidateCleanupProfile(index, source.selectedCoreMinPx(), source.selectedCoreMaxPx(),
            source.selectedShoulderMinPx(), source.selectedShoulderMaxPx(), source.tubeCenterOffsetPx(),
            source.tubeUncertaintyPx(), source.provenance(), source.motionSupport(), source.turnSupport(),
            source.scaleConflict());
    }

    /** Converts borrowed anchor evidence into a non-authorizing boundary-only row. */
    private static CandidateCleanupProfile boundaryOnlyRow(int index, CandidateCleanupProfile source) {
        return new CandidateCleanupProfile(index, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
            source.tubeCenterOffsetPx(), source.tubeUncertaintyPx(), CleanupEvidenceProvenance.UNSUPPORTED,
            0.0, 0.0, true);
    }

    private static List<ProtectedInterval> intervals(int pointCount, Set<Integer> protectedIndexes) {
        List<Integer> ordered = protectedIndexes.stream().sorted().toList();
        List<ProtectedInterval> result = new ArrayList<>();
        for (int index = 1; index < ordered.size(); index++) {
            int start = ordered.get(index - 1);
            int end = ordered.get(index);
            if (end <= start || end >= pointCount) {
                return List.of();
            }
            result.add(new ProtectedInterval(start, end));
        }
        return List.copyOf(result);
    }

    private static boolean hasSignal(CleanupSamplingProfile sample) {
        for (int index = 0; index < sample.sampleCount(); index++) {
            if (sample.insideRasterAt(index)
                && Math.max(sample.nativeIntensityAt(index), Math.max(sample.lightFilteredIntensityAt(index),
                    sample.standardFilteredIntensityAt(index))) > MIN_SIGNAL) {
                return true;
            }
        }
        return false;
    }

    private static boolean finite(EastNorth point) {
        return point != null && Double.isFinite(point.east()) && Double.isFinite(point.north());
    }

    private static boolean samePoint(EastNorth left, EastNorth right) {
        return finite(left) && finite(right) && left.distance(right) <= COORDINATE_TOLERANCE;
    }

    /**
     * Protected interval in final-preview point indexes.
     *
     * @param startIndex inclusive protected start index
     * @param endIndex inclusive protected end index
     */
    public record ProtectedInterval(int startIndex, int endIndex) {
        /** Validates inclusive interval ordering. */
        public ProtectedInterval {
            if (startIndex < 0 || endIndex <= startIndex) {
                throw new IllegalArgumentException("Protected cleanup interval is invalid");
            }
        }
    }

    /** Typed final-preview reconciliation result. */
    public enum Status {
        /** Every movable preview point has complete direct retained evidence. */
        COMPLETE,
        /** Candidate, selection, or source arguments were invalid. */
        INVALID_INPUT,
        /** Evidence is absent or was already marked incomplete. */
        INCOMPLETE_EVIDENCE,
        /** Candidate geometry and retained profile/evidence counts disagree. */
        MISMATCHED_EVIDENCE,
        /** A protected selected-node target was missing or did not occur exactly once. */
        MISSING_PROTECTED_ANCHOR,
        /** A preview point had no unique retained raw-profile coordinate. */
        UNMAPPED_OR_DUPLICATE_PROFILE,
        /** Reconciled profile indexes did not progress strictly along the preview. */
        NON_MONOTONIC_PROFILE_MAPPING,
        /** A movable point relies on bounded interpolation rather than direct evidence. */
        INTERPOLATED_MOVABLE_POINT,
        /** A movable point has no selected-corridor evidence. */
        UNSUPPORTED_MOVABLE_POINT,
        /** A movable point's source profile is outside the sampled raster. */
        OFF_RASTER_MOVABLE_POINT,
        /** A movable point has no retained scalar heatmap signal. */
        NO_SIGNAL_MOVABLE_POINT,
        /** Protected anchors could not form independent non-empty intervals. */
        INVALID_PROTECTED_INTERVALS
    }
}
