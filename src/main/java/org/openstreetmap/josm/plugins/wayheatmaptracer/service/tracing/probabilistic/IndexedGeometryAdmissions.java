package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters;

/** Lazy, attempt-owned admission answers indexed by adjacent profile and state pair. */
final class IndexedGeometryAdmissions implements TransitionAdmission {
    private final AttemptMemoryLedger.Owner owner;
    private final List<InferenceProfile> profiles;
    private final MetricRegion region;
    private final int[] pairOffsets;
    /** zero is unobserved, one is rejected, and two is admitted. */
    private final byte[] answers;
    private long calculations;
    private long reuses;

    private IndexedGeometryAdmissions(AttemptMemoryLedger.Owner owner,
            List<InferenceProfile> profiles, MetricRegion region, int[] pairOffsets, byte[] answers) {
        this.owner = owner;
        this.profiles = profiles;
        this.region = region;
        this.pairOffsets = pairOffsets;
        this.answers = answers;
    }

    /** Returns null when the indexed cache is ineligible or its initial reservation is refused. */
    static IndexedGeometryAdmissions createIfEligible(List<InferenceProfile> profiles,
            MetricRegion region, long maximumPairVisits, AttemptMemoryLedger.Owner owner) {
        if (profiles == null || profiles.size() < 2 || region == null || owner == null) return null;
        boolean hasWideProfile = false;
        long pairCount = 0L;
        for (int index = 0; index < profiles.size(); index++) {
            int states = profiles.get(index).cells().size();
            hasWideProfile |= states >= 4;
            if (index + 1 < profiles.size()) {
                pairCount = checkedPairCount(pairCount, states,
                        profiles.get(index + 1).cells().size(), maximumPairVisits);
                if (pairCount < 0L) return null;
            }
        }
        if (!hasWideProfile || pairCount <= 0L) return null;
        long bytes = retainedBytes(profiles.size(), pairCount);
        AttemptMemoryLedger.Reservation reservation;
        try {
            reservation = owner.reserve(bytes);
        } catch (AttemptMemoryLedger.ResourceLimitException refused) {
            return null;
        }
        try {
            int[] offsets = new int[profiles.size()];
            int offset = 0;
            for (int index = 0; index + 1 < profiles.size(); index++) {
                offsets[index] = offset;
                offset += profiles.get(index).cells().size() * profiles.get(index + 1).cells().size();
            }
            offsets[profiles.size() - 1] = offset;
            byte[] answers = new byte[(int) pairCount];
            IndexedGeometryAdmissions indexed = new IndexedGeometryAdmissions(owner,
                    profiles, region, offsets, answers);
            reservation.adopt(indexed);
            return indexed;
        } catch (RuntimeException | Error failure) {
            reservation.close();
            throw failure;
        }
    }

    static long retainedBytes(int profileCount, long adjacentPairCount) {
        return AttemptMemoryLedger.objectBytes(56)
                + AttemptMemoryLedger.arrayBytes(profileCount, Integer.BYTES)
                + AttemptMemoryLedger.arrayBytes(adjacentPairCount, Byte.BYTES);
    }

    /** Returns the checked total, or -1 when either the work budget or index range is exceeded. */
    static long checkedPairCount(long current, int startStates, int endStates, long maximumPairVisits) {
        if (current < 0L || current > Integer.MAX_VALUE || startStates < 0 || endStates < 0
                || maximumPairVisits < 0L) return -1L;
        long adjacentPairs = (long) startStates * endStates;
        if (adjacentPairs > maximumPairVisits - current
                || adjacentPairs > Integer.MAX_VALUE - current) return -1L;
        return current + adjacentPairs;
    }

    @Override
    public boolean allowed(int startProfile, int startState, int endState,
            List<InferenceProfile> requestedProfiles, MetricRegion requestedRegion) {
        if (owner.isClosed()) {
            throw new IllegalStateException("memory owner is closed: transition-admission-memo");
        }
        if (requestedProfiles != profiles || requestedRegion != region
                || startProfile < 0 || startProfile + 1 >= profiles.size()
                || startState < 0 || startState >= profiles.get(startProfile).cells().size()
                || endState < 0 || endState >= profiles.get(startProfile + 1).cells().size()) {
            return evaluate(startProfile, startState, endState, requestedProfiles, requestedRegion);
        }
        int width = profiles.get(startProfile + 1).cells().size();
        int index = pairOffsets[startProfile] + startState * width + endState;
        byte cached = answers[index];
        if (cached != 0) {
            reuses++;
            return cached == 2;
        }
        boolean admitted = evaluate(startProfile, startState, endState, profiles, region);
        answers[index] = admitted ? (byte) 2 : (byte) 1;
        calculations++;
        return admitted;
    }

    private static boolean evaluate(int startProfile, int startState, int endState,
            List<InferenceProfile> profiles, MetricRegion decisionRegion) {
        if (decisionRegion == null) return true;
        MetricPoint start = profiles.get(startProfile).point(startState);
        MetricPoint end = profiles.get(startProfile + 1).point(endState);
        return decisionRegion.contains(start) && decisionRegion.contains(end)
                && decisionRegion.containsSegment(start, end);
    }

    @Override
    public void finish() {
        ModernDiagnosticCounters.add("optimization.geometryAdmission.indexedCalculations", calculations);
        ModernDiagnosticCounters.add("optimization.geometryAdmission.indexedReuses", reuses);
    }
}
