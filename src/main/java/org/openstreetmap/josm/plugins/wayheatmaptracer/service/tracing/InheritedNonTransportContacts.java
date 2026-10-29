package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NonTransportSemanticWitness;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;

/** Independent complete-contact proof for the narrow inherited non-transport exception. */
public final class InheritedNonTransportContacts {
    private InheritedNonTransportContacts() { }

    /** Lazy original chords, bound to one contacts invocation's immutable inputs. */
    static final class ContextChordCache {
        private static final int MAX_SLOTS = 128;
        private static final long MAX_BYTES = 256L * 1024L;
        private static final long WRAPPER_BYTES = AttemptMemoryLedger.objectBytes(48);
        private final DetachedWay context;
        private final Map<PrimitiveKey, DetachedPrimitive> values;
        private final LocalMetricFrame frame;
        private final long byteBudget;
        private final CertifiedContextSegmentClipper.ExactChord[] slots;
        private long retainedBytes;

        private ContextChordCache(DetachedWay context, Map<PrimitiveKey, DetachedPrimitive> values,
                LocalMetricFrame frame, long byteBudget, int slotCount) {
            this.context = context;
            this.values = values;
            this.frame = frame;
            this.byteBudget = byteBudget;
            this.slots = new CertifiedContextSegmentClipper.ExactChord[slotCount];
            this.retainedBytes = WRAPPER_BYTES + AttemptMemoryLedger.referenceArrayBytes(slotCount);
        }

        static ContextChordCache create(DetachedWay context,
                Map<PrimitiveKey, DetachedPrimitive> values, LocalMetricFrame frame, long byteBudget) {
            long boundedBytes = Math.min(byteBudget, MAX_BYTES);
            if (boundedBytes < WRAPPER_BYTES
                    + AttemptMemoryLedger.referenceArrayBytes(1)) return null;
            int count = (int) Math.min(Math.min(MAX_SLOTS, context.nodeKeys().size() - 1L),
                    (boundedBytes - WRAPPER_BYTES - AttemptMemoryLedger.referenceArrayBytes(0)) / Long.BYTES);
            return new ContextChordCache(context, values, frame, boundedBytes, count);
        }

        CertifiedContextSegmentClipper.ExactChord chord(int occurrence) {
            if (occurrence >= 0 && occurrence < slots.length && slots[occurrence] != null) {
                return slots[occurrence];
            }
            // Compute only when this occurrence is naturally visited; later malformed
            // arcs must not supersede an earlier uncertain-contact refusal.
            var chord = CertifiedContextSegmentClipper.originalChord(
                    geographic(values, context.nodeKeys().get(occurrence)),
                    geographic(values, context.nodeKeys().get(occurrence + 1)), frame);
            if (occurrence >= 0 && occurrence < slots.length) {
                long bytes = chordBytes(chord);
                if (bytes <= byteBudget - retainedBytes) {
                    slots[occurrence] = chord;
                    retainedBytes += bytes;
                }
            }
            return chord;
        }

        private static long chordBytes(CertifiedContextSegmentClipper.ExactChord chord) {
            try {
                long bytes = AttemptMemoryLedger.objectBytes(80)
                        + 2L * AttemptMemoryLedger.objectBytes(16);
                bytes = Math.addExact(bytes, decimalBytes(chord.start().east()));
                bytes = Math.addExact(bytes, decimalBytes(chord.start().north()));
                bytes = Math.addExact(bytes, decimalBytes(chord.end().east()));
                bytes = Math.addExact(bytes, decimalBytes(chord.end().north()));
                bytes = Math.addExact(bytes, decimalBytes(chord.lengthSquared()));
                // Count both north-bound references again, conservatively including
                // decimal, bigint and magnitude-array payload even when shared.
                bytes = Math.addExact(bytes, decimalBytes(chord.minNorth()));
                return Math.addExact(bytes, decimalBytes(chord.maxNorth()));
            } catch (ArithmeticException overflow) {
                return Long.MAX_VALUE; // Refuse retention, never the geometric pair.
            }
        }

        private static long decimalBytes(BigDecimal value) {
            long words = ((long) value.unscaledValue().bitLength() + 1L + 31L) / 32L;
            return Math.addExact(2L * AttemptMemoryLedger.objectBytes(32),
                    AttemptMemoryLedger.arrayBytes(words, Integer.BYTES));
        }

        int slotCount() { return slots.length; }
        long retainedBytes() { return retainedBytes; }
    }

    private record Fraction(BigDecimal numerator, BigDecimal denominator) implements Comparable<Fraction> {
        Fraction {
            if (denominator.signum() == 0) throw new IllegalArgumentException("Ambiguous contact fraction");
            if (denominator.signum() < 0) {
                numerator = numerator.negate();
                denominator = denominator.negate();
            }
        }
        @Override public int compareTo(Fraction other) {
            return numerator.multiply(other.denominator()).compareTo(other.numerator().multiply(denominator));
        }
        Fraction withIndex(int index) {
            return new Fraction(numerator.add(denominator.multiply(BigDecimal.valueOf(index))), denominator);
        }
        Fraction subtract(Fraction other) {
            return new Fraction(numerator.multiply(other.denominator()).subtract(other.numerator().multiply(denominator)),
                    denominator.multiply(other.denominator()));
        }
        Fraction squared() {
            return new Fraction(numerator.multiply(numerator), denominator.multiply(denominator));
        }
        Fraction add(Fraction other) {
            return new Fraction(numerator.multiply(other.denominator()).add(other.numerator().multiply(denominator)),
                    denominator.multiply(other.denominator()));
        }
        Fraction scaled(BigDecimal multiplier) {
            return new Fraction(numerator.multiply(multiplier), denominator);
        }
    }
    private record ExactIntersection(Fraction east, Fraction north) { }
    private record Event(PrimitiveKey context, int contextIndex, Fraction contextFraction,
            int selectedIndex, Fraction selectedFraction, ExactIntersection point) { }
    private record Pair(Event original, Event proposed) { }
    private static final Set<String> DESCRIPTIVE = Set.of("name", "name:en", "source", "source:date",
            "note", "fixme", "description", "ref", "wikidata", "wikipedia");

    /** Returns only fully proved unchanged context ways; any missing proof keeps the hard gate. */
    static Set<PrimitiveKey> prove(NetworkSnapshot before,
            Map<PrimitiveKey, DetachedPrimitive> after, PrimitiveKey selected,
            OccurrenceRange sourceRange, EvidenceSnapshot evidence, double searchHalfWidthMeters) {
        return prove(before,after,selected,sourceRange,evidence,searchHalfWidthMeters,ProofWork.UNBOUNDED);
    }

    /**
     * Runs the same narrow complete-contact proof under the caller's attempt limits. Refusal
     * propagates without granting an exemption; all invocation scratch is released on every exit.
     * The caller still owns final geometry validation and any returned context identities.
     */
    public static Set<PrimitiveKey> prove(NetworkSnapshot before,
            Map<PrimitiveKey, DetachedPrimitive> after, PrimitiveKey selected,
            OccurrenceRange sourceRange, EvidenceSnapshot evidence, double searchHalfWidthMeters,
            CancellationProbe cancellation, java.util.function.LongConsumer geometryWork,
            AttemptMemoryLedger.Owner owner) {
        java.util.Objects.requireNonNull(cancellation);
        java.util.Objects.requireNonNull(geometryWork);
        java.util.Objects.requireNonNull(owner);
        try (var scratch = owner.child("inherited-contact-proof")) {
            return prove(before,after,selected,sourceRange,evidence,searchHalfWidthMeters,
                    new ProofWork(cancellation,geometryWork,scratch));
        }
    }

    private record ProofWork(CancellationProbe cancellation, java.util.function.LongConsumer units,
            AttemptMemoryLedger.Owner owner) {
        private static final ProofWork UNBOUNDED = new ProofWork(CancellationProbe.NONE,ignored -> { },null);
        void checkpoint() { if (owner != null) { cancellation.checkpoint(); units.accept(1); } }
        void retain(long bytes) { if (owner != null) owner.reserve(bytes); }
        AttemptMemoryLedger.Reservation temporary(long bytes) { return owner == null ? null : owner.reserve(bytes); }
    }

    private static <T> Comparator<T> bounded(Comparator<T> comparator, ProofWork work) {
        return (first,second) -> { work.checkpoint(); return comparator.compare(first,second); };
    }

    private static Set<PrimitiveKey> prove(NetworkSnapshot before,
            Map<PrimitiveKey, DetachedPrimitive> after, PrimitiveKey selected,
            OccurrenceRange sourceRange, EvidenceSnapshot evidence, double searchHalfWidthMeters, ProofWork work) {
        work.checkpoint();
        NonTransportSemanticWitness witness = before.semanticWitness();
        if (witness == null || !witness.matches(before) || !Double.isFinite(searchHalfWidthMeters)
                || searchHalfWidthMeters <= 0) return Set.of();
        DetachedWay source = (DetachedWay) before.primitives().get(selected);
        DetachedWay proposed = (DetachedWay) after.get(selected);
        int first = proposed.nodeKeys().indexOf(source.nodeKeys().get(sourceRange.firstIndex()));
        int last = proposed.nodeKeys().indexOf(source.nodeKeys().get(sourceRange.lastIndex()));
        if (first < 0 || last <= first) return Set.of();
        work.retain(Math.addExact(4096L,Math.multiplyExact(80L,before.primitives().size())));
        Set<PrimitiveKey> accepted = new LinkedHashSet<>();
        List<Pair> completePairs = new ArrayList<>();
        for (DetachedPrimitive primitive : before.primitives().values().stream()
                .sorted(bounded(Comparator.comparing(DetachedPrimitive::key),work)).toList()) {
            work.checkpoint();
            if (!(primitive instanceof DetachedWay context) || context.key().equals(selected)
                    || !eligible(context, before, witness,work)
                    || !unchanged(context, before, after,work)) continue;
            List<Event> oldEvents = contacts(source, sourceRange, context, before.primitives(), evidence,work);
            List<Event> newEvents = contacts(proposed, new OccurrenceRange(first, last), context, after, evidence,work);
            if (oldEvents == null || newEvents == null || oldEvents.isEmpty()
                    || oldEvents.size() != newEvents.size()) continue;
            List<Pair> pairs = new ArrayList<>();
            boolean valid = true;
            for (int index = 0; index < oldEvents.size(); index++) {
                work.checkpoint();
                Event old = oldEvents.get(index), next = newEvents.get(index);
                if (old.contextIndex() != next.contextIndex()
                        || !withinPhysicalBound(old.point(), next.point(), evidence, searchHalfWidthMeters)) {
                    valid = false;
                    break;
                }
                work.retain(256);
                pairs.add(new Pair(old, next));
            }
            // The occurrence and order on the context way are independent of selected-way order.
            if (valid) {
                List<Pair> oldOrder = pairs.stream().sorted(bounded(Comparator
                        .comparing((Pair pair) -> contextPosition(pair.original())),work)).toList();
                List<Pair> newOrder = pairs.stream().sorted(bounded(Comparator
                        .comparing((Pair pair) -> contextPosition(pair.proposed())),work)).toList();
                valid = oldOrder.equals(newOrder) && uniquePositions(oldOrder, true,work)
                        && uniquePositions(pairs, false,work);
            }
            if (valid) {
                accepted.add(context.key());
                completePairs.addAll(pairs);
            }
        }
        // Do not permit eligible features to swap their order along the selected path.
        work.checkpoint();
        List<Pair> oldOrder = completePairs.stream().sorted(bounded(Comparator
                .comparing((Pair pair) -> selectedPosition(pair.original()))
                .thenComparing(pair -> pair.original().context())
                .thenComparingInt(pair -> pair.original().contextIndex()),work)).toList();
        List<Pair> newOrder = completePairs.stream().sorted(bounded(Comparator
                .comparing((Pair pair) -> selectedPosition(pair.proposed()))
                .thenComparing(pair -> pair.proposed().context())
                .thenComparingInt(pair -> pair.proposed().contextIndex()),work)).toList();
        if (!oldOrder.equals(newOrder)) return Set.of();
        return Set.copyOf(accepted);
    }

    private static boolean uniquePositions(List<Pair> pairs, boolean context, ProofWork work) {
        for (int i = 1; i < pairs.size(); i++) {
            work.checkpoint();
            Event previous = pairs.get(i - 1).original(), current = pairs.get(i).original();
            Event previousNew = pairs.get(i - 1).proposed(), currentNew = pairs.get(i).proposed();
            if ((context ? contextPosition(previous) : selectedPosition(previous))
                    .compareTo(context ? contextPosition(current) : selectedPosition(current)) == 0
                    || (context ? contextPosition(previousNew) : selectedPosition(previousNew))
                    .compareTo(context ? contextPosition(currentNew) : selectedPosition(currentNew)) == 0) return false;
        }
        return true;
    }

    private static Fraction selectedPosition(Event event) {
        return event.selectedFraction().withIndex(event.selectedIndex());
    }
    private static Fraction contextPosition(Event event) {
        return event.contextFraction().withIndex(event.contextIndex());
    }

    private static boolean withinPhysicalBound(ExactIntersection first, ExactIntersection second,
            EvidenceSnapshot evidence, double physicalBound) {
        // The certificate bounds |metric/physical - 1|, hence physical <= metric/(1-error).
        // Original-chord coordinates and intersection fractions are exact rational values.
        // Compare squared rationals directly: no rounded intersection, sqrt, epsilon or division
        // can under-enclose the contact shift at the frozen physical admission boundary.
        BigDecimal error = new BigDecimal(evidence.coordinateFrame().distortionCertificate().maximumRelativeDistanceError());
        BigDecimal allowedMetric = new BigDecimal(physicalBound).multiply(BigDecimal.ONE.subtract(error));
        if (allowedMetric.signum() <= 0) return false;
        Fraction distanceSquared = first.east().subtract(second.east()).squared()
                .add(first.north().subtract(second.north()).squared());
        return distanceSquared.compareTo(new Fraction(allowedMetric.multiply(allowedMetric), BigDecimal.ONE)) <= 0;
    }

    private static boolean insideDecision(ExactIntersection point, EvidenceSnapshot evidence, ProofWork work) {
        // Exact winding over the declared binary64 polygon vertices; no rounded intersection
        // or MetricRegion's display tolerance may turn an outside/boundary contact into proof.
        for (List<MetricPoint> polygon : evidence.decisionRegion().polygons()) {
            work.checkpoint();
            int winding = 0;
            boolean boundary = false;
            for (int i = 0; i < polygon.size(); i++) {
                work.checkpoint();
                MetricPoint a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
                BigDecimal ax = new BigDecimal(a.xMeters()), ay = new BigDecimal(a.yMeters());
                BigDecimal bx = new BigDecimal(b.xMeters()), by = new BigDecimal(b.yMeters());
                Fraction px = point.east().subtract(new Fraction(ax, BigDecimal.ONE));
                Fraction py = point.north().subtract(new Fraction(ay, BigDecimal.ONE));
                int orientation = py.scaled(bx.subtract(ax)).subtract(px.scaled(by.subtract(ay))).numerator().signum();
                int firstY = point.north().compareTo(new Fraction(ay, BigDecimal.ONE));
                int secondY = point.north().compareTo(new Fraction(by, BigDecimal.ONE));
                if (orientation == 0 && point.east().compareTo(new Fraction(ax.min(bx), BigDecimal.ONE)) >= 0
                        && point.east().compareTo(new Fraction(ax.max(bx), BigDecimal.ONE)) <= 0
                        && firstY * secondY <= 0) boundary = true;
                if (firstY >= 0 && secondY < 0 && orientation > 0) winding++;
                else if (firstY < 0 && secondY >= 0 && orientation < 0) winding--;
            }
            if (!boundary && winding != 0) return true;
        }
        return false;
    }

    static boolean unchanged(DetachedWay way, NetworkSnapshot before,
            Map<PrimitiveKey, DetachedPrimitive> after) {
        return unchanged(way,before,after,ProofWork.UNBOUNDED);
    }

    private static boolean unchanged(DetachedWay way, NetworkSnapshot before,
            Map<PrimitiveKey, DetachedPrimitive> after, ProofWork work) {
        return way.equals(before.primitives().get(way.key())) && way.equals(after.get(way.key()))
                && way.nodeKeys().stream().peek(node -> work.checkpoint()).allMatch(node ->
                before.primitives().get(node) instanceof DetachedNode
                && before.primitives().get(node).equals(after.get(node)));
    }

    private static boolean onlyTags(Map<String, String> tags, Set<String> semantic, ProofWork work) {
        return tags.keySet().stream().peek(key -> work.checkpoint()).allMatch(key -> DESCRIPTIVE.contains(key)
                || key.startsWith("name:") || semantic.contains(key));
    }

    private static boolean eligible(DetachedWay way, NetworkSnapshot before,
            NonTransportSemanticWitness witness, ProofWork work) {
        boolean closed = way.nodeKeys().size() >= 4
                && way.nodeKeys().get(0).equals(way.nodeKeys().get(way.nodeKeys().size() - 1));
        boolean rock = closed && "bare_rock".equals(way.tags().get("natural"))
                && onlyTags(way.tags(), Set.of("natural", "area"),work)
                && !"no".equals(way.tags().get("area"));
        boolean cliff = !closed && "cliff".equals(way.tags().get("natural"))
                && onlyTags(way.tags(), Set.of("natural"),work);
        boolean ridge = !closed && "ridge".equals(way.tags().get("natural"))
                && onlyTags(way.tags(), Set.of("natural"),work);
        boolean forest = closed && onlyTags(way.tags(), Set.of(),work);
        if (!rock && !cliff && !ridge && !forest) return false;
        Set<PrimitiveKey> watches = before.incomingReferrerWatches().get(way.key());
        if (watches == null) return false;
        work.retain(Math.multiplyExact(64L,watches.size()));
        Set<PrimitiveKey> parents = watches.stream().peek(key -> work.checkpoint()).filter(key -> key.type() == PrimitiveKey.Type.RELATION)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (parents.isEmpty()) return rock || cliff || ridge;
        // Only an exact complete forest outer provides positive parent semantics. Other parent
        // types (including route/restriction and unknown ancestry) remain hard-blocking.
        if (!forest || parents.size() != 1) return false;
        var relation = witness.relations().get(parents.iterator().next());
        return relation != null && relation.complete() && !relation.deleted()
                && relation.parentWatches().isEmpty()
                && relation.members().stream().peek(member -> work.checkpoint()).allMatch(NonTransportSemanticWitness.Member::complete)
                && relation.members().stream().peek(member -> work.checkpoint()).allMatch(member -> member.key().type() == PrimitiveKey.Type.WAY
                        && ("outer".equals(member.role()) || "inner".equals(member.role())))
                && "multipolygon".equals(relation.tags().get("type"))
                && "forest".equals(relation.tags().get("landuse"))
                && onlyTags(relation.tags(), Set.of("type", "landuse"),work)
                && relation.members().stream().peek(member -> work.checkpoint()).filter(member -> member.key().equals(way.key())).count() == 1
                && relation.members().stream().peek(member -> work.checkpoint()).anyMatch(member -> member.key().equals(way.key())
                        && "outer".equals(member.role()));
    }

    /** Null means that even one contact is uncertain, remote, a touch, or an overlap. */
    private static List<Event> contacts(DetachedWay selected, OccurrenceRange range,
            DetachedWay context, Map<PrimitiveKey, DetachedPrimitive> values, EvidenceSnapshot evidence) {
        return contacts(selected,range,context,values,evidence,ProofWork.UNBOUNDED);
    }

    private static List<Event> contacts(DetachedWay selected, OccurrenceRange range,
            DetachedWay context, Map<PrimitiveKey, DetachedPrimitive> values, EvidenceSnapshot evidence, ProofWork work) {
        // The lazy cache already has a byte ceiling; reserve that ceiling only while it is live.
        try (var cacheBytes = work.temporary(ContextChordCache.MAX_BYTES)) {
        List<Event> events = new ArrayList<>();
        ContextChordCache contextChords = ContextChordCache.create(context, values,
                evidence.coordinateFrame(), ContextChordCache.MAX_BYTES);
        for (int i = range.firstIndex(); i < range.lastIndex(); i++) {
            work.checkpoint();
            PrimitiveKey aKey = selected.nodeKeys().get(i), bKey = selected.nodeKeys().get(i + 1);
            GeographicPoint a = geographic(values, aKey), b = geographic(values, bKey);
            var selectedChord = CertifiedContextSegmentClipper.originalChord(a, b, evidence.coordinateFrame());
            for (int j = 0; j < context.nodeKeys().size() - 1; j++) {
                work.checkpoint();
                PrimitiveKey cKey = context.nodeKeys().get(j), dKey = context.nodeKeys().get(j + 1);
                GeographicPoint c = geographic(values, cKey), d = geographic(values, dKey);
                var contextChord = contextChords == null
                        ? CertifiedContextSegmentClipper.originalChord(c, d, evidence.coordinateFrame())
                        : contextChords.chord(j);
                var contact = CertifiedContextSegmentClipper.originalContactEvidence(selectedChord,
                        aKey, bKey, contextChord, cKey, dKey, evidence.coordinateFrame());
                if (!contact.hasAnyContact()) continue;
                if (!contact.properCrossing() || contact.positiveOverlap() || contact.uncertainty()
                        || !contact.witnesses().isEmpty() || contact.ownedExactIncidence()) return null;
                try {
                    MetricPoint am = evidence.coordinateFrame().toMetric(a);
                    MetricPoint bm = evidence.coordinateFrame().toMetric(b);
                    MetricPoint cm = evidence.coordinateFrame().toMetric(c);
                    MetricPoint dm = evidence.coordinateFrame().toMetric(d);
                    // Do not exempt a long, only partially certified contextual chord.
                    CertifiedContextSegmentClipper.requireChangedInside(am, bm, evidence.coordinateFrame());
                    CertifiedContextSegmentClipper.requireChangedInside(cm, dm, evidence.coordinateFrame());
                    var ax = selectedChord.start().east();
                    var ay = selectedChord.start().north();
                    var ex = selectedChord.end().east().subtract(ax);
                    var ey = selectedChord.end().north().subtract(ay);
                    var fx = contextChord.end().east().subtract(contextChord.start().east());
                    var fy = contextChord.end().north().subtract(contextChord.start().north());
                    var gx = contextChord.start().east().subtract(ax);
                    var gy = contextChord.start().north().subtract(ay);
                    BigDecimal divisor = ex.multiply(fy).subtract(ey.multiply(fx));
                    Fraction exactT = new Fraction(gx.multiply(fy).subtract(gy.multiply(fx)), divisor);
                    Fraction exactU = new Fraction(gx.multiply(ey).subtract(gy.multiply(ex)), divisor);
                    Fraction guard = new Fraction(new BigDecimal(1e-8), BigDecimal.ONE);
                    Fraction upperGuard = new Fraction(BigDecimal.ONE.subtract(new BigDecimal(1e-8)), BigDecimal.ONE);
                    if (exactT.compareTo(guard) <= 0 || exactT.compareTo(upperGuard) >= 0
                            || exactU.compareTo(guard) <= 0 || exactU.compareTo(upperGuard) >= 0) return null;
                    ExactIntersection point = new ExactIntersection(
                            new Fraction(ax.multiply(exactT.denominator()).add(ex.multiply(exactT.numerator())), exactT.denominator()),
                            new Fraction(ay.multiply(exactT.denominator()).add(ey.multiply(exactT.numerator())), exactT.denominator()));
                    if (!insideDecision(point, evidence,work)) return null;
                    work.retain(4096);
                    events.add(new Event(context.key(), j, exactU, i, exactT, point));
                } catch (IllegalArgumentException invalidCertificate) {
                    return null;
                }
            }
        }
        return events;
        }
    }

    private static GeographicPoint geographic(Map<PrimitiveKey, DetachedPrimitive> values, PrimitiveKey key) {
        return ((DetachedNode) values.get(key)).coordinate();
    }
}
