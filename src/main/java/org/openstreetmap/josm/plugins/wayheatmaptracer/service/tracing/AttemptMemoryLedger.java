package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Attempt-scoped accounting for additional retained allocations owned by modern tracing work. */
public final class AttemptMemoryLedger {
    public static final long MAX_BYTES = 256L * 1024L * 1024L;

    private static final long OBJECT_HEADER_BYTES = 16;
    private static final long ARRAY_HEADER_BYTES = 16;
    private static final long ARRAY_ALIGNMENT_BYTES = 8;
    private static final long REFERENCE_BYTES = 8;

    private final long limitBytes;
    private final IdentityHashMap<Object, Charge> charges = new IdentityHashMap<>();
    private long currentBytes;
    private long peakBytes;
    private Owner root;

    /** Creates a testable ledger whose cap may be reduced, but never raised above production. */
    public AttemptMemoryLedger(long limitBytes) {
        if (limitBytes <= 0 || limitBytes > MAX_BYTES) {
            throw new IllegalArgumentException("limitBytes must be between 1 and " + MAX_BYTES);
        }
        this.limitBytes = limitBytes;
    }

    /** Creates a ledger with the ordinary per-attempt production limit. */
    public static AttemptMemoryLedger production() {
        return new AttemptMemoryLedger(MAX_BYTES);
    }

    public long limitBytes() {
        return limitBytes;
    }

    /** Returns the unique root owner for this attempt. */
    public synchronized Owner rootOwner() {
        if (root != null) {
            throw new IllegalStateException("attempt root owner already created");
        }
        root = new Owner(this, null, "attempt");
        return root;
    }

    /** Current additional retained bytes, excluding closed reservations and unowned identities. */
    public synchronized long currentBytes() {
        return currentBytes;
    }

    /** Highest additional retained byte count reached during this attempt. */
    public synchronized long peakBytes() {
        return peakBytes;
    }

    /** Conservative aligned charge for a primitive or reference array. */
    public static long arrayBytes(long length, long elementBytes) {
        if (length < 0 || elementBytes < 0) {
            throw new IllegalArgumentException("array dimensions and element size must be non-negative");
        }
        return alignedSize(ARRAY_HEADER_BYTES, Math.multiplyExact(length, elementBytes));
    }

    /** Conservative aligned charge for an object plus caller-supplied field storage. */
    public static long objectBytes(long fieldBytes) {
        if (fieldBytes < 0) {
            throw new IllegalArgumentException("fieldBytes must be non-negative");
        }
        return alignedSize(OBJECT_HEADER_BYTES, fieldBytes);
    }

    /** Conservative charge for an array of object references. */
    public static long referenceArrayBytes(long length) {
        return arrayBytes(length, REFERENCE_BYTES);
    }

    private static long alignedSize(long headerBytes, long payloadBytes) {
        long unaligned = Math.addExact(headerBytes, payloadBytes);
        long remainder = unaligned % ARRAY_ALIGNMENT_BYTES;
        return remainder == 0 ? unaligned
                : Math.addExact(unaligned, ARRAY_ALIGNMENT_BYTES - remainder);
    }

    private synchronized Reservation reserve(Owner owner, long bytes) {
        owner.ensureOpen();
        if (bytes < 0) {
            throw new IllegalArgumentException("reservation bytes must be non-negative");
        }
        if (bytes > limitBytes - currentBytes) {
            throw new ResourceLimitException(bytes, currentBytes, limitBytes);
        }
        currentBytes = Math.addExact(currentBytes, bytes);
        peakBytes = Math.max(peakBytes, currentBytes);
        Reservation reservation = new Reservation(this, owner, bytes);
        owner.reservations.add(reservation);
        return reservation;
    }

    private synchronized MemoryLease adopt(Reservation reservation, Object identity) {
        reservation.owner.ensureOpen();
        reservation.ensureActive();
        Objects.requireNonNull(identity, "newlyAllocatedIdentity");
        if (charges.containsKey(identity)) {
            throw new IllegalStateException("identity is already accounted by this attempt");
        }
        Charge charge = new Charge(reservation.bytes);
        MemoryLease lease = new MemoryLease(this, reservation.owner, identity, charge);
        charges.put(identity, charge);
        if (!reservation.owner.reservations.remove(reservation)) {
            charges.remove(identity);
            throw new IllegalStateException("reservation is not owned by its recorded owner");
        }
        reservation.state = Reservation.State.ADOPTED;
        reservation.owner.leases.put(identity, lease);
        return lease;
    }

    private synchronized void release(Reservation reservation) {
        if (reservation.state == Reservation.State.ADOPTED) {
            return;
        }
        reservation.ensureActive();
        if (!reservation.owner.reservations.remove(reservation)) {
            throw new IllegalStateException("reservation is not owned by its recorded owner");
        }
        subtract(reservation.bytes);
        reservation.state = Reservation.State.CLOSED;
    }

    private synchronized MemoryLease retain(Owner owner, Object identity) {
        owner.ensureOpen();
        Objects.requireNonNull(identity, "identity");
        Charge charge = charges.get(identity);
        if (charge == null) {
            throw new IllegalArgumentException("identity is not accounted by this attempt");
        }
        if (owner.leases.containsKey(identity)) {
            throw new IllegalStateException("owner already retains this identity");
        }
        charge.references = Math.addExact(charge.references, 1);
        MemoryLease lease = new MemoryLease(this, owner, identity, charge);
        owner.leases.put(identity, lease);
        return lease;
    }

    private synchronized MemoryLease retainIfAbsent(Owner owner, Object identity) {
        owner.ensureOpen();
        Objects.requireNonNull(identity, "identity");
        MemoryLease existing = owner.leases.get(identity);
        if (existing != null) {
            if (!existing.active || charges.get(identity) != existing.charge) {
                throw new IllegalStateException("owner contains an invalid memory lease");
            }
            return existing;
        }
        return retain(owner, identity);
    }

    private synchronized MemoryLease fork(MemoryLease lease, Owner coOwner) {
        lease.ensureActive();
        if (lease.ledger != coOwner.ledger) {
            throw new IllegalArgumentException("co-owner must belong to the same attempt ledger");
        }
        return retain(coOwner, lease.identity);
    }

    private synchronized void transfer(Owner source, Owner destination) {
        source.ensureOpen();
        destination.ensureOpen();
        if (source.ledger != destination.ledger) {
            throw new IllegalArgumentException("destination must belong to the same attempt ledger");
        }
        if (source == destination) {
            return;
        }
        for (Owner ancestor = destination; ancestor != null; ancestor = ancestor.parent) {
            if (ancestor == source) {
                throw new IllegalArgumentException("destination cannot be inside the source owner subtree");
            }
        }
        List<Owner> subtree = new ArrayList<>();
        collectOwners(source, subtree);
        IdentityHashMap<Object, List<MemoryLease>> incoming = new IdentityHashMap<>();
        for (Owner owner : subtree) {
            if (owner == destination) {
                continue;
            }
            for (MemoryLease lease : owner.leases.values()) {
                if (!lease.active || charges.get(lease.identity) != lease.charge) {
                    throw new IllegalStateException("source subtree contains an invalid memory lease");
                }
                incoming.computeIfAbsent(lease.identity, ignored -> new ArrayList<>()).add(lease);
            }
        }
        for (Object identity : incoming.keySet()) {
            MemoryLease existing = destination.leases.get(identity);
            if (existing != null && (!existing.active || charges.get(identity) != existing.charge)) {
                throw new IllegalStateException("destination contains an invalid memory lease");
            }
            List<MemoryLease> leases = incoming.get(identity);
            Charge charge = leases.get(0).charge;
            for (MemoryLease lease : leases) {
                if (lease.charge != charge) {
                    throw new IllegalStateException("identity has inconsistent retained-memory charges");
                }
            }
            int discarded = existing == null ? leases.size() - 1 : leases.size();
            if (charge.references <= discarded) {
                throw new IllegalStateException("memory lease reference count underflow during transfer");
            }
        }
        for (Owner owner : subtree) {
            if (owner == destination) {
                continue;
            }
            owner.leases.clear();
        }
        for (var entry : incoming.entrySet()) {
            Object identity = entry.getKey();
            List<MemoryLease> leases = entry.getValue();
            MemoryLease keep = destination.leases.get(identity);
            int discardedFrom = 0;
            if (keep == null) {
                keep = leases.get(0);
                keep.owner = destination;
                destination.leases.put(identity, keep);
                discardedFrom = 1;
            }
            for (int index = discardedFrom; index < leases.size(); index++) {
                MemoryLease discarded = leases.get(index);
                discarded.charge.references--;
                discarded.active = false;
            }
        }
    }

    private static void collectOwners(Owner owner, List<Owner> output) {
        output.add(owner);
        for (Owner child : owner.children) {
            collectOwners(child, output);
        }
    }

    private synchronized void close(Owner owner) {
        if (owner.closed) {
            return;
        }
        for (Owner child : List.copyOf(owner.children)) {
            close(child);
        }
        for (Reservation reservation : List.copyOf(owner.reservations)) {
            release(reservation);
        }
        for (MemoryLease lease : List.copyOf(owner.leases.values())) {
            close(lease);
        }
        owner.closed = true;
        owner.children.clear();
        if (owner.parent != null) {
            owner.parent.children.remove(owner);
        }
    }

    private synchronized void close(MemoryLease lease) {
        lease.ensureActive();
        Owner owner = lease.owner;
        Charge charge = charges.get(lease.identity);
        if (charge != lease.charge || owner.leases.remove(lease.identity) != lease) {
            throw new IllegalStateException("memory lease is not registered with its owner");
        }
        if (charge.references <= 0) {
            throw new IllegalStateException("memory lease reference count underflow");
        }
        charge.references--;
        lease.active = false;
        if (charge.references == 0) {
            charges.remove(lease.identity);
            subtract(charge.bytes);
        }
    }

    private void subtract(long bytes) {
        if (bytes < 0 || currentBytes < bytes) {
            throw new IllegalStateException("attempt memory accounting underflow");
        }
        currentBytes -= bytes;
    }

    /** A scoped accounting owner; closing it releases its subtree, reservations and leases. */
    public static final class Owner implements AutoCloseable {
        private final AttemptMemoryLedger ledger;
        private final Owner parent;
        private final String name;
        private final Set<Owner> children = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<Reservation> reservations = Collections.newSetFromMap(new IdentityHashMap<>());
        private final IdentityHashMap<Object, MemoryLease> leases = new IdentityHashMap<>();
        private boolean closed;

        private Owner(AttemptMemoryLedger ledger, Owner parent, String name) {
            this.ledger = ledger;
            this.parent = parent;
            this.name = name;
        }

        public Owner child(String childName) {
            Objects.requireNonNull(childName, "childName");
            if (childName.isBlank()) {
                throw new IllegalArgumentException("childName must not be blank");
            }
            synchronized (ledger) {
                ensureOpen();
                Owner child = new Owner(ledger, this, childName);
                children.add(child);
                return child;
            }
        }

        public Reservation reserve(long bytes) {
            return ledger.reserve(this, bytes);
        }

        /** Adds an uncharged co-ownership reference to an identity charged earlier in this attempt. */
        public MemoryLease retain(Object alreadyAccountedIdentity) {
            return ledger.retain(this, alreadyAccountedIdentity);
        }

        /** Co-owns an accounted identity once, returning this owner's existing lease on repeats. */
        public MemoryLease retainIfAbsent(Object alreadyAccountedIdentity) {
            return ledger.retainIfAbsent(this, alreadyAccountedIdentity);
        }

        /** Returns the current attempt-wide retained charge, regardless of which owner holds it. */
        public long currentBytes() {
            return ledger.currentBytes();
        }

        /** Returns the attempt-wide retained-byte high-water mark. */
        public long peakBytes() {
            return ledger.peakBytes();
        }

        /** Atomically moves all retained identities to another owner in the same attempt. */
        public void transferTo(Owner destination) {
            ledger.transfer(this, Objects.requireNonNull(destination, "destination"));
        }

        public boolean isClosed() {
            synchronized (ledger) {
                return closed;
            }
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("memory owner is closed: " + name);
            }
        }

        @Override
        public void close() {
            ledger.close(this);
        }
    }

    /** A temporary pre-allocation charge that can be released or atomically adopted by identity. */
    public static final class Reservation implements AutoCloseable {
        private final AttemptMemoryLedger ledger;
        private final Owner owner;
        private final long bytes;
        private State state = State.ACTIVE;

        private Reservation(AttemptMemoryLedger ledger, Owner owner, long bytes) {
            this.ledger = ledger;
            this.owner = owner;
            this.bytes = bytes;
        }

        public long bytes() {
            return bytes;
        }

        public MemoryLease adopt(Object newlyAllocatedIdentity) {
            return ledger.adopt(this, newlyAllocatedIdentity);
        }

        private void ensureActive() {
            if (state != State.ACTIVE) {
                throw new IllegalStateException("reservation is already closed or adopted");
            }
        }

        @Override
        public void close() {
            ledger.release(this);
        }

        private enum State {
            ACTIVE,
            ADOPTED,
            CLOSED
        }
    }

    /** One owner's reference to an identity-keyed retained allocation. */
    public static final class MemoryLease implements AutoCloseable {
        private final AttemptMemoryLedger ledger;
        private Owner owner;
        private final Object identity;
        private final Charge charge;
        private boolean active = true;

        private MemoryLease(AttemptMemoryLedger ledger, Owner owner, Object identity, Charge charge) {
            this.ledger = ledger;
            this.owner = owner;
            this.identity = identity;
            this.charge = charge;
        }

        public long bytes() {
            return charge.bytes;
        }

        public MemoryLease fork(Owner coOwner) {
            return ledger.fork(this, Objects.requireNonNull(coOwner, "coOwner"));
        }

        private void ensureActive() {
            if (!active) {
                throw new IllegalStateException("memory lease is already closed");
            }
        }

        @Override
        public void close() {
            ledger.close(this);
        }
    }

    private static final class Charge {
        private final long bytes;
        private int references = 1;

        private Charge(long bytes) {
            this.bytes = bytes;
        }
    }

    /** Typed refusal raised before the ledger admits a reservation that would exceed its cap. */
    public static final class ResourceLimitException extends RuntimeException {
        private final long requestedBytes;
        private final long currentBytes;
        private final long limitBytes;

        private ResourceLimitException(long requestedBytes, long currentBytes, long limitBytes) {
            super("attempt memory limit exceeded: current=" + currentBytes + ", requested="
                    + requestedBytes + ", limit=" + limitBytes);
            this.requestedBytes = requestedBytes;
            this.currentBytes = currentBytes;
            this.limitBytes = limitBytes;
        }

        public long requestedBytes() {
            return requestedBytes;
        }

        public long currentBytes() {
            return currentBytes;
        }

        public long limitBytes() {
            return limitBytes;
        }
    }
}
