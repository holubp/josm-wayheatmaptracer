package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/**
 * Per-solve, bounded, first-use cache for exact decision-region admission checks.
 *
 * <p>The caller remains responsible for performing the check at its original traversal site.
 * Evicted entries are deliberately recomputed rather than changing the inference state space.</p>
 */
final class TransitionAdmissionMemo implements TransitionAdmission {
    private static final long KEY_BYTES = AttemptMemoryLedger.objectBytes(16);
    private static final long PROBE_BYTES = AttemptMemoryLedger.objectBytes(16);
    private static final long ENTRY_BYTES = 2L * AttemptMemoryLedger.objectBytes(48)
            + AttemptMemoryLedger.objectBytes(16) + 2L * AttemptMemoryLedger.objectBytes(40);
    private final int capacity;
    private final Map<Key, Boolean> values;
    private final Key lookupKey;
    private final AttemptMemoryLedger.Owner memoryOwner;
    private final Map<Key, EntryOwnership> ownerships;
    private AttemptMemoryLedger.MemoryLease valuesTableLease;
    private AttemptMemoryLedger.MemoryLease ownershipsTableLease;
    private int tableCapacity;

    TransitionAdmissionMemo(int capacity) {
        this(capacity, null);
    }

    TransitionAdmissionMemo(int capacity, AttemptMemoryLedger.Owner memoryOwner) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Admission memo capacity must be positive");
        }
        this.capacity = capacity;
        if (memoryOwner == null) {
            this.memoryOwner = null;
            this.values = new LinkedHashMap<>(capacity + 1, 0.75f, true);
            this.ownerships = null;
            this.lookupKey = new Key();
        } else {
            AttemptMemoryLedger.Owner storage = memoryOwner.child("memo-storage");
            this.memoryOwner = storage;
            try {
                this.values = ProbabilisticInference.allocated(storage,
                        AttemptMemoryLedger.objectBytes(48), "transition admission memo",
                        () -> new LinkedHashMap<>(16, 0.75f, true));
                this.ownerships = ProbabilisticInference.allocated(storage,
                        AttemptMemoryLedger.objectBytes(48), "transition admission memo",
                        () -> new LinkedHashMap<>(16, 0.75f, true));
                this.lookupKey = ProbabilisticInference.allocated(storage, PROBE_BYTES,
                        "transition admission memo", Key::new);
            } catch (RuntimeException | Error exception) {
                storage.close();
                throw exception;
            }
        }
    }

    @Override
    public boolean allowed(int startProfile, int startState, int endState,
        List<InferenceProfile> profiles, MetricRegion decisionRegion) {
        if (decisionRegion == null) {
            return true;
        }
        return allowed(startProfile, startState, endState, 0, () -> {
            MetricPoint start = profiles.get(startProfile).point(startState);
            MetricPoint end = profiles.get(startProfile + 1).point(endState);
            return decisionRegion.contains(start) && decisionRegion.contains(end)
                && decisionRegion.containsSegment(start, end);
        });
    }

    boolean allowed(int startProfile, int startState, int endState, int discriminator,
        BooleanSupplier predicate) {
        if (memoryOwner != null && memoryOwner.isClosed()) {
            throw new IllegalStateException("memory owner is closed: memo-storage");
        }
        lookupKey.set(startProfile, startState, endState, discriminator);
        if (memoryOwner == null) {
            Boolean cached = values.get(lookupKey);
            if (cached != null) return cached;
            boolean value = predicate.getAsBoolean();
            rememberUnaccounted(new Key(startProfile, startState, endState, discriminator), value);
            return value;
        }
        Boolean cached = values.get(lookupKey);
        if (cached != null) {
            ownerships.get(lookupKey);
            return cached;
        }
        AttemptMemoryLedger.Reservation keyReservation = reserve(KEY_BYTES);
        AttemptMemoryLedger.Reservation entryReservation = null;
        TableGrowth tableGrowth = null;
        try {
            Key key = new Key(startProfile, startState, endState, discriminator);
            entryReservation = reserve(ENTRY_BYTES);
            tableGrowth = reserveTableGrowth();
            boolean value = predicate.getAsBoolean();
            Key evicted = values.size() == capacity ? values.keySet().iterator().next() : null;
            AttemptMemoryLedger.MemoryLease keyLease = keyReservation.adopt(key);
            EntryOwnership ownership = new EntryOwnership(keyLease);
            ownership.entryLease = entryReservation.adopt(ownership);
            values.put(key, value);
            ownerships.put(key, ownership);
            if (tableGrowth != null) tableGrowth.commit();
            if (evicted != null) {
                values.remove(evicted);
                EntryOwnership removed = ownerships.remove(evicted);
                if (removed != null) removed.close();
            }
            return value;
        } catch (RuntimeException | Error exception) {
            keyReservation.close();
            if (entryReservation != null) entryReservation.close();
            if (tableGrowth != null) tableGrowth.close();
            throw exception;
        }
    }

    private AttemptMemoryLedger.Reservation reserve(long bytes) {
        try {
            return memoryOwner.reserve(bytes);
        } catch (AttemptMemoryLedger.ResourceLimitException exception) {
            throw new ProbabilisticInference.MemoryLimit("transition admission memo", exception);
        }
    }

    private void rememberUnaccounted(Key key, boolean value) {
        if (values.size() == capacity) values.remove(values.keySet().iterator().next());
        values.put(key, value);
    }

    private TableGrowth reserveTableGrowth() {
        int requestedSize = values.size() + 1;
        if (tableCapacity > 0 && requestedSize <= tableCapacity * 3 / 4) {
            return null;
        }
        int nextCapacity = tableCapacity == 0 ? 16 : Math.multiplyExact(tableCapacity, 2);
        while (requestedSize > nextCapacity * 3 / 4) {
            nextCapacity = Math.multiplyExact(nextCapacity, 2);
        }
        AttemptMemoryLedger.Reservation valuesReservation = reserve(
                AttemptMemoryLedger.referenceArrayBytes(nextCapacity));
        AttemptMemoryLedger.Reservation ownershipsReservation;
        try {
            ownershipsReservation = reserve(AttemptMemoryLedger.referenceArrayBytes(nextCapacity));
        } catch (RuntimeException | Error exception) {
            valuesReservation.close();
            throw exception;
        }
        return new TableGrowth(nextCapacity,
                valuesReservation.adopt(new TableCharge()),
                ownershipsReservation.adopt(new TableCharge()),
                valuesTableLease, ownershipsTableLease);
    }

    private final class TableGrowth implements AutoCloseable {
        private final int capacity;
        private final AttemptMemoryLedger.MemoryLease nextValues;
        private final AttemptMemoryLedger.MemoryLease nextOwnerships;
        private final AttemptMemoryLedger.MemoryLease previousValues;
        private final AttemptMemoryLedger.MemoryLease previousOwnerships;
        private boolean committed;

        private TableGrowth(int capacity, AttemptMemoryLedger.MemoryLease nextValues,
                AttemptMemoryLedger.MemoryLease nextOwnerships,
                AttemptMemoryLedger.MemoryLease previousValues,
                AttemptMemoryLedger.MemoryLease previousOwnerships) {
            this.capacity = capacity;
            this.nextValues = nextValues;
            this.nextOwnerships = nextOwnerships;
            this.previousValues = previousValues;
            this.previousOwnerships = previousOwnerships;
        }

        private void commit() {
            tableCapacity = capacity;
            valuesTableLease = nextValues;
            ownershipsTableLease = nextOwnerships;
            committed = true;
            if (previousValues != null) previousValues.close();
            if (previousOwnerships != null) previousOwnerships.close();
        }

        @Override
        public void close() {
            if (!committed) {
                nextValues.close();
                nextOwnerships.close();
            }
        }
    }

    private static final class EntryOwnership implements AutoCloseable {
        private final AttemptMemoryLedger.MemoryLease keyLease;
        private AttemptMemoryLedger.MemoryLease entryLease;
        private EntryOwnership(AttemptMemoryLedger.MemoryLease keyLease) {
            this.keyLease = keyLease;
        }
        @Override public void close() {
            keyLease.close();
            entryLease.close();
        }
    }

    private static final class TableCharge { }

    int size() {
        return values.size();
    }

    private static final class Key {
        private int profile;
        private int startState;
        private int endState;
        private int discriminator;

        private Key() { }

        private Key(int profile, int startState, int endState, int discriminator) {
            set(profile, startState, endState, discriminator);
        }

        private void set(int profile, int startState, int endState, int discriminator) {
            this.profile = profile;
            this.startState = startState;
            this.endState = endState;
            this.discriminator = discriminator;
        }

        @Override
        public int hashCode() {
            int result = Integer.hashCode(profile);
            result = 31 * result + Integer.hashCode(startState);
            result = 31 * result + Integer.hashCode(endState);
            return 31 * result + Integer.hashCode(discriminator);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return profile == key.profile && startState == key.startState
                    && endState == key.endState && discriminator == key.discriminator;
        }
    }
}

/** Shared first-use decision contract for adjacent-profile geometry authorities. */
interface TransitionAdmission {
    boolean allowed(int startProfile, int startState, int endState,
            List<InferenceProfile> profiles, MetricRegion decisionRegion);

    /** Publishes bounded aggregate diagnostics after this inference attempt finishes. */
    default void finish() { }
}
