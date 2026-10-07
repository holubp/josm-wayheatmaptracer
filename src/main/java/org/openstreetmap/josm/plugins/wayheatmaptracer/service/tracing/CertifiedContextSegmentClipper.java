package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.math.BigDecimal;
import java.util.Optional;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DistortionCertificate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;

/** Certified, read-only projection of contextual OSM segments for final topology checks. */
final class CertifiedContextSegmentClipper {
    // The final collision classifier uses 1e-8 metres on coordinate comparisons.
    // Keep a wider exclusion guard for binary64 operations and frame admission (1e-12 degrees).
    private static final double EXCLUSION_GUARD_METERS = 1e-5;
    private static final double FRAME_ADMISSION_DEGREES = 1e-12;

    private CertifiedContextSegmentClipper() { }

    record ClippedSegment(MetricPoint start, MetricPoint end,
            boolean originalStart, boolean originalEnd) { }

    /** Exact binary64-input chord in physical predicate units, independent of clipping. */
    static final class ExactChord {
        private final ExactPoint start;
        private final ExactPoint end;
        private final BigDecimal lengthSquared;
        private final BigDecimal minNorth;
        private final BigDecimal maxNorth;

        ExactChord(ExactPoint start, ExactPoint end) {
            this.start = start;
            this.end = end;
            this.lengthSquared = distanceSquared(start, end);
            this.minNorth = min(start.north(), end.north());
            this.maxNorth = max(start.north(), end.north());
        }

        ExactPoint start() { return start; }
        ExactPoint end() { return end; }
        BigDecimal minNorth() { return minNorth; }
        BigDecimal maxNorth() { return maxNorth; }
        BigDecimal lengthSquared() { return lengthSquared; }

        ExactChord shifted(BigDecimal eastShift) {
            return new ExactChord(start.shifted(eastShift), end.shifted(eastShift));
        }
    }

    record ExactPoint(BigDecimal east, BigDecimal north) {
        ExactPoint shifted(BigDecimal eastShift) {
            return new ExactPoint(east.add(eastShift), north);
        }
    }

    enum OriginalContact { NONE, CROSSING, VERTEX_TOUCH, COLLINEAR_OVERLAP,
        PROXIMITY_TOUCH }

    enum EndpointRole { A, B, C, D }
    enum WitnessKind { EXACT, TOLERANT, NEAR, ZERO_OVERLAP }

    /** One tested original endpoint on one longitude branch; ownership is never inferred later. */
    record EndpointWitness(int branchShift, EndpointRole endpoint, WitnessKind kind,
            boolean owned) { }

    /** All independent reasons a pair may contact, rather than one early-return label. */
    record ContactEvidence(boolean properCrossing, boolean positiveOverlap,
            boolean uncertainty, boolean ownedExactIncidence,
            java.util.List<EndpointWitness> witnesses) {
        ContactEvidence {
            witnesses = java.util.List.copyOf(witnesses);
        }

        ContactEvidence union(ContactEvidence other) {
            java.util.List<EndpointWitness> combined = new java.util.ArrayList<>(witnesses);
            combined.addAll(other.witnesses());
            return new ContactEvidence(properCrossing || other.properCrossing(),
                    positiveOverlap || other.positiveOverlap(),
                    uncertainty || other.uncertainty(),
                    ownedExactIncidence || other.ownedExactIncidence(), combined);
        }

        boolean hasAnyContact() {
            return properCrossing || positiveOverlap || uncertainty || !witnesses.isEmpty();
        }

        boolean soleOwnedContact() {
            return ownedExactIncidence && !properCrossing && !positiveOverlap && !uncertainty
                    && !witnesses.isEmpty() && witnesses.stream().allMatch(EndpointWitness::owned);
        }

        OriginalContact displayDefect() {
            if (properCrossing) return OriginalContact.CROSSING;
            if (positiveOverlap) return OriginalContact.COLLINEAR_OVERLAP;
            if (uncertainty) return OriginalContact.PROXIMITY_TOUCH;
            if (witnesses.isEmpty()) return OriginalContact.NONE;
            return witnesses.stream().allMatch(w -> w.kind() == WitnessKind.EXACT
                    || w.kind() == WitnessKind.ZERO_OVERLAP)
                    ? OriginalContact.VERTEX_TOUCH : OriginalContact.PROXIMITY_TOUCH;
        }
    }

    private static final BigDecimal FULL_TURN = BigDecimal.valueOf(360);
    private static final BigDecimal HALF_TURN = BigDecimal.valueOf(180);
    private static final BigDecimal COORDINATE_EPSILON = new BigDecimal("0.00000001");
    private static final BigDecimal AREA_EPSILON = new BigDecimal("0.00000001");
    // StrictFrameArithmetic: one subtraction, one IEEE remainder, then two
    // multiplications. With |raw longitude difference| <= 360 degrees,
    // |normalised difference| <= 180 degrees, scale <= 6.4e6 m/rad and
    // round-to-nearest u=2^-53, the coordinate error is below 2e-8 m.
    // 5e-8 m leaves margin for the latitude path and subnormal absolute error.
    private static final BigDecimal PROJECTION_ERROR_METERS = new BigDecimal("0.00000005");
    private static final BigDecimal COORDINATE_ENVELOPE = COORDINATE_EPSILON.add(
            PROJECTION_ERROR_METERS.multiply(BigDecimal.valueOf(2)));
    // A shorter original arc starts in [-180,180] degrees from origin and ends
    // in [-360,360]; shifting context by +/-360 reaches [-720,720]. Its maximum
    // separation from an unshifted changed endpoint is 1080 degrees east and
    // 180 degrees north. At <=6.4e6 m/rad, the pair span sum is <141e6 m.
    // The area error below is <=4*5e-8*141e6+8*(5e-8)^2 <29 m^2; 64 m^2 is
    // an outward bound for the exact latitude-box rejection before three branches.
    private static final BigDecimal GLOBAL_AREA_UPPER = BigDecimal.valueOf(64);
    // StrictFrameArithmetic's exact binary64 radians-per-degree factor. The predicate
    // treats this and the certificate's two binary64 scales as exact coefficients.
    private static final BigDecimal RADIANS_PER_DEGREE =
            new BigDecimal(0x1.1df46a2529d39p-6);

    /**
     * Unwraps the original shorter arc and scales exact input values without calling
     * the frame's domain-limited toMetric on either contextual endpoint.
     */
    static ExactChord originalChord(GeographicPoint start, GeographicPoint end,
            LocalMetricFrame frame) {
        BigDecimal originLongitude = exact(frame.origin().longitudeDegrees());
        BigDecimal originLatitude = exact(frame.origin().latitudeDegrees());
        BigDecimal longitudeStart = branch(exact(start.longitudeDegrees())
                .subtract(originLongitude));
        BigDecimal longitudeDelta = branch(exact(end.longitudeDegrees())
                .subtract(exact(start.longitudeDegrees())));
        if (longitudeDelta.abs().compareTo(HALF_TURN) == 0) {
            throw new InvalidContextGeometryException("Context longitude arc is ambiguous");
        }
        BigDecimal eastScale = RADIANS_PER_DEGREE.multiply(
                exact(frame.distortionCertificate().eastMetersPerRadian()));
        BigDecimal northScale = RADIANS_PER_DEGREE.multiply(
                exact(frame.distortionCertificate().northMetersPerRadian()));
        return new ExactChord(
                new ExactPoint(longitudeStart.multiply(eastScale),
                        exact(start.latitudeDegrees()).subtract(originLatitude)
                                .multiply(northScale)),
                new ExactPoint(longitudeStart.add(longitudeDelta).multiply(eastScale),
                        exact(end.latitudeDegrees()).subtract(originLatitude)
                                .multiply(northScale)));
    }

    /** Display-only classification; never use this lossy label for an OSM exemption. */
    static OriginalContact originalContact(ExactChord changed, ExactChord context,
            LocalMetricFrame frame) {
        return originalContactEvidence(changed, null, null, context, null, null, frame)
                .displayDefect();
    }

    /** Retains every branch and endpoint contact for the sole-shared-node decision. */
    static ContactEvidence originalContactEvidence(ExactChord changed,
            PrimitiveKey changedStart, PrimitiveKey changedEnd, ExactChord context,
            PrimitiveKey contextStart, PrimitiveKey contextEnd, LocalMetricFrame frame) {
        BigDecimal northGap = max(changed.minNorth(), context.minNorth())
                .subtract(min(changed.maxNorth(), context.maxNorth()));
        if (beyondBoth(northGap, changed, context, GLOBAL_AREA_UPPER)) {
            return new ContactEvidence(false, false, false, false, java.util.List.of());
        }
        BigDecimal fullTurnMetres = FULL_TURN.multiply(RADIANS_PER_DEGREE)
                .multiply(exact(frame.distortionCertificate().eastMetersPerRadian()));
        ContactEvidence result = new ContactEvidence(false, false, false, false,
                java.util.List.of());
        for (int shift = -1; shift <= 1; shift++) {
            ExactChord branch = context.shifted(fullTurnMetres.multiply(BigDecimal.valueOf(shift)));
            ContactEvidence contact = branchEvidence(changed, changedStart, changedEnd,
                    branch, contextStart, contextEnd, shift);
            result = result.union(contact);
        }
        return result;
    }

    static boolean originalContinuationReverses(ExactChord earlier, ExactChord later) {
        BigDecimal ax = earlier.end().east().subtract(earlier.start().east());
        BigDecimal ay = earlier.end().north().subtract(earlier.start().north());
        BigDecimal bx = later.end().east().subtract(later.start().east());
        BigDecimal by = later.end().north().subtract(later.start().north());
        BigDecimal product = earlier.lengthSquared().multiply(later.lengthSquared());
        if (product.compareTo(new BigDecimal("0.000000000000000000000001")) <= 0) return true;
        BigDecimal dot = ax.multiply(bx).add(ay.multiply(by));
        return dot.signum() < 0 && dot.pow(2).multiply(BigDecimal.valueOf(16))
                .compareTo(product) > 0;
    }

    private static boolean anyContactOnBranch(ExactChord first, ExactChord second) {
        return branchEvidence(first, null, null, second, null, null, 0).hasAnyContact();
    }

    private static ContactEvidence branchEvidence(ExactChord first, PrimitiveKey firstStart,
            PrimitiveKey firstEnd, ExactChord second, PrimitiveKey secondStart,
            PrimitiveKey secondEnd, int branchShift) {
        ExactPoint a = first.start(), b = first.end();
        ExactPoint c = second.start(), d = second.end();
        // A positive axis-aligned box gap is a lower bound on the pair distance.
        // Reject only when it exceeds both exact outward tolerance envelopes.
        BigDecimal eastGap = max(min(a.east(), b.east()), min(c.east(), d.east()))
                .subtract(min(max(a.east(), b.east()), max(c.east(), d.east())));
        BigDecimal northGap = max(min(a.north(), b.north()), min(c.north(), d.north()))
                .subtract(min(max(a.north(), b.north()), max(c.north(), d.north())));
        BigDecimal span = max(max(a.east(), b.east()), max(c.east(), d.east()))
                .subtract(min(min(a.east(), b.east()), min(c.east(), d.east())))
                .add(max(max(a.north(), b.north()), max(c.north(), d.north()))
                        .subtract(min(min(a.north(), b.north()), min(c.north(), d.north()))));
        BigDecimal areaUpper = AREA_EPSILON.add(PROJECTION_ERROR_METERS
                .multiply(BigDecimal.valueOf(4)).multiply(span))
                .add(PROJECTION_ERROR_METERS.pow(2).multiply(BigDecimal.valueOf(8)));
        if (beyondBoth(eastGap, first, second, areaUpper)
                || beyondBoth(northGap, first, second, areaUpper)) {
            return new ContactEvidence(false, false, false, false, java.util.List.of());
        }
        BigDecimal abC = orientation(a, b, c), abD = orientation(a, b, d);
        BigDecimal cdA = orientation(c, d, a), cdB = orientation(c, d, b);
        boolean crossing = opposite(abC, abD) && opposite(cdA, cdB);
        boolean exactCollinear = abC.signum() == 0 && abD.signum() == 0
                && cdA.signum() == 0 && cdB.signum() == 0;
        boolean tolerantCollinear = withinArea(abC, a, b, c)
                && withinArea(abD, a, b, d) && withinArea(cdA, c, d, a)
                && withinArea(cdB, c, d, b);
        boolean ownsA = owned(firstStart, a, secondStart, c)
                || owned(firstStart, a, secondEnd, d);
        boolean ownsB = owned(firstEnd, b, secondStart, c)
                || owned(firstEnd, b, secondEnd, d);
        boolean ownsC = owned(secondStart, c, firstStart, a)
                || owned(secondStart, c, firstEnd, b);
        boolean ownsD = owned(secondEnd, d, firstStart, a)
                || owned(secondEnd, d, firstEnd, b);
        boolean ownedIncidence = ownsA || ownsB || ownsC || ownsD;
        java.util.List<EndpointWitness> witnesses = new java.util.ArrayList<>();
        boolean positiveOverlap = false;
        boolean uncertainty = false;

        if (exactCollinear || tolerantCollinear) {
            boolean[] axes = candidateAxes(first, second);
            for (int axis = 0; axis < axes.length; axis++) {
                if (!axes[axis]) continue;
                BigDecimal aValue = axisValue(a, axis), bValue = axisValue(b, axis);
                BigDecimal cValue = axisValue(c, axis), dValue = axisValue(d, axis);
                BigDecimal singleton = max(min(aValue, bValue), min(cValue, dValue));
                BigDecimal overlap = min(max(aValue, bValue), max(cValue, dValue))
                        .subtract(singleton);
                if (overlap.signum() > 0) {
                    positiveOverlap = true;
                } else if (overlap.signum() == 0) {
                    boolean ownedZero = ownedIncidence && sharedAxisCoordinate(
                            singleton, axis, a, b, c, d, ownsA, ownsB, ownsC, ownsD);
                    witnesses.add(new EndpointWitness(branchShift, zeroOverlapRole(
                            singleton, axis, a, b, c, d, ownsA, ownsB, ownsC, ownsD),
                            WitnessKind.ZERO_OVERLAP, ownedZero));
                    if (ownedZero && !stableZeroOverlap(axis, a, b, c, d,
                            ownsA, ownsB, ownsC, ownsD)) uncertainty = true;
                } else if (overlap.compareTo(COORDINATE_ENVELOPE.negate()) >= 0) {
                    witnesses.add(new EndpointWitness(branchShift, EndpointRole.A,
                            WitnessKind.ZERO_OVERLAP, false));
                }
            }
        }

        addEndpointWitnesses(witnesses, branchShift, EndpointRole.A, a, c, d, ownsA);
        addEndpointWitnesses(witnesses, branchShift, EndpointRole.B, b, c, d, ownsB);
        addEndpointWitnesses(witnesses, branchShift, EndpointRole.C, c, a, b, ownsC);
        addEndpointWitnesses(witnesses, branchShift, EndpointRole.D, d, a, b, ownsD);
        if ((first.lengthSquared().signum() == 0 || second.lengthSquared().signum() == 0)
                && (positiveOverlap || !witnesses.isEmpty())) uncertainty = true;
        return new ContactEvidence(crossing, positiveOverlap, uncertainty,
                ownedIncidence, witnesses);
    }

    private static boolean owned(PrimitiveKey firstKey, ExactPoint first,
            PrimitiveKey secondKey, ExactPoint second) {
        return firstKey != null && firstKey.type() == PrimitiveKey.Type.NODE
                && firstKey.identityKind() == PrimitiveKey.IdentityKind.OSM_UNIQUE
                && firstKey.equals(secondKey)
                && first.east().compareTo(second.east()) == 0
                && first.north().compareTo(second.north()) == 0;
    }

    private static boolean[] candidateAxes(ExactChord first, ExactChord second) {
        boolean[] axes = new boolean[2];
        includeDominantAxes(first, axes);
        includeDominantAxes(second, axes);
        return axes;
    }

    private static void includeDominantAxes(ExactChord chord, boolean[] axes) {
        BigDecimal east = chord.end().east().subtract(chord.start().east()).abs();
        BigDecimal north = chord.end().north().subtract(chord.start().north()).abs();
        BigDecimal difference = east.subtract(north);
        BigDecimal uncertainty = PROJECTION_ERROR_METERS.multiply(BigDecimal.valueOf(4));
        if (difference.compareTo(uncertainty) > 0) axes[0] = true;
        else if (difference.compareTo(uncertainty.negate()) < 0) axes[1] = true;
        else { axes[0] = true; axes[1] = true; }
    }

    private static BigDecimal axisValue(ExactPoint point, int axis) {
        return axis == 0 ? point.east() : point.north();
    }

    private static boolean sharedAxisCoordinate(BigDecimal singleton, int axis,
            ExactPoint a, ExactPoint b, ExactPoint c, ExactPoint d,
            boolean ownsA, boolean ownsB, boolean ownsC, boolean ownsD) {
        return ownsA && axisValue(a, axis).compareTo(singleton) == 0
                || ownsB && axisValue(b, axis).compareTo(singleton) == 0
                || ownsC && axisValue(c, axis).compareTo(singleton) == 0
                || ownsD && axisValue(d, axis).compareTo(singleton) == 0;
    }

    private static EndpointRole zeroOverlapRole(BigDecimal singleton, int axis,
            ExactPoint a, ExactPoint b, ExactPoint c, ExactPoint d,
            boolean ownsA, boolean ownsB, boolean ownsC, boolean ownsD) {
        if (ownsA && axisValue(a, axis).compareTo(singleton) == 0) return EndpointRole.A;
        if (ownsB && axisValue(b, axis).compareTo(singleton) == 0) return EndpointRole.B;
        if (ownsC && axisValue(c, axis).compareTo(singleton) == 0) return EndpointRole.C;
        if (ownsD && axisValue(d, axis).compareTo(singleton) == 0) return EndpointRole.D;
        if (axisValue(a, axis).compareTo(singleton) == 0) return EndpointRole.A;
        if (axisValue(b, axis).compareTo(singleton) == 0) return EndpointRole.B;
        if (axisValue(c, axis).compareTo(singleton) == 0) return EndpointRole.C;
        return EndpointRole.D;
    }

    private static boolean stableZeroOverlap(int axis, ExactPoint a, ExactPoint b,
            ExactPoint c, ExactPoint d, boolean ownsA, boolean ownsB,
            boolean ownsC, boolean ownsD) {
        ExactPoint sharedFirst = ownsA ? a : ownsB ? b : null;
        ExactPoint sharedSecond = ownsC ? c : ownsD ? d : null;
        if (sharedFirst == null || sharedSecond == null) return false;
        BigDecimal firstDelta = axisValue(ownsA ? b : a, axis)
                .subtract(axisValue(sharedFirst, axis));
        BigDecimal secondDelta = axisValue(ownsC ? d : c, axis)
                .subtract(axisValue(sharedSecond, axis));
        BigDecimal signMargin = PROJECTION_ERROR_METERS.multiply(BigDecimal.valueOf(4));
        return (firstDelta.signum() == 0 || firstDelta.abs().compareTo(signMargin) > 0)
                && (secondDelta.signum() == 0 || secondDelta.abs().compareTo(signMargin) > 0);
    }

    private static void addEndpointWitnesses(java.util.List<EndpointWitness> witnesses,
            int branchShift, EndpointRole role, ExactPoint point, ExactPoint targetStart,
            ExactPoint targetEnd, boolean owned) {
        if (onSegment(targetStart, targetEnd, point)) {
            witnesses.add(new EndpointWitness(branchShift, role, WitnessKind.EXACT, owned));
        }
        if (tolerantOnSegment(targetStart, targetEnd, point)) {
            witnesses.add(new EndpointWitness(branchShift, role, WitnessKind.TOLERANT, owned));
        }
        if (near(point, targetStart, targetEnd)) {
            witnesses.add(new EndpointWitness(branchShift, role, WitnessKind.NEAR, owned));
        }
    }

    private static boolean near(ExactPoint point, ExactPoint first, ExactPoint second) {
        BigDecimal lengthSquared = distanceSquared(first, second);
        BigDecimal twiceCoordinate = COORDINATE_ENVELOPE.multiply(BigDecimal.valueOf(2));
        if (lengthSquared.signum() == 0) {
            return distanceSquared(point, first).compareTo(twiceCoordinate.pow(2)) <= 0;
        }
        BigDecimal dx = second.east().subtract(first.east());
        BigDecimal dy = second.north().subtract(first.north());
        BigDecimal px = point.east().subtract(first.east());
        BigDecimal py = point.north().subtract(first.north());
        BigDecimal projection = px.multiply(dx).add(py.multiply(dy));
        // (u+v)^2 <= 2u^2 + 2v^2 avoids a rounded square root altogether.
        BigDecimal boundSquared = toleranceBoundSquared(lengthSquared,
                areaEnvelope(first, second, point));
        if (projection.signum() <= 0) {
            return distanceSquared(point, first).multiply(lengthSquared)
                    .compareTo(boundSquared) <= 0;
        }
        if (projection.compareTo(lengthSquared) >= 0) {
            return distanceSquared(point, second).multiply(lengthSquared)
                    .compareTo(boundSquared) <= 0;
        }
        return orientation(first, second, point).pow(2)
                .compareTo(boundSquared) <= 0;
    }

    private static boolean beyondBoth(BigDecimal gap, ExactChord first, ExactChord second,
            BigDecimal areaUpper) {
        // The historical collinear branch can conservatively flag a zero-length
        // edge even when its non-dominant axis is separated. Keep that refusal.
        if (first.lengthSquared().signum() == 0 || second.lengthSquared().signum() == 0) {
            return false;
        }
        return gap.signum() > 0 && beyond(gap, first.lengthSquared(), areaUpper)
                && beyond(gap, second.lengthSquared(), areaUpper);
    }

    private static boolean beyond(BigDecimal gap, BigDecimal lengthSquared,
            BigDecimal areaUpper) {
        if (lengthSquared.signum() == 0) {
            return gap.compareTo(COORDINATE_ENVELOPE.multiply(BigDecimal.valueOf(2))) > 0;
        }
        return gap.pow(2).multiply(lengthSquared)
                .compareTo(toleranceBoundSquared(lengthSquared, areaUpper)) > 0;
    }

    private static BigDecimal toleranceBoundSquared(BigDecimal lengthSquared,
            BigDecimal areaAllowance) {
        return areaAllowance.pow(2).multiply(BigDecimal.valueOf(2))
                .add(COORDINATE_ENVELOPE.pow(2).multiply(BigDecimal.valueOf(8))
                        .multiply(lengthSquared));
    }

    private static boolean onSegment(ExactPoint a, ExactPoint b, ExactPoint point) {
        return orientation(a, b, point).signum() == 0
                && point.east().compareTo(min(a.east(), b.east())) >= 0
                && point.east().compareTo(max(a.east(), b.east())) <= 0
                && point.north().compareTo(min(a.north(), b.north())) >= 0
                && point.north().compareTo(max(a.north(), b.north())) <= 0;
    }

    private static boolean tolerantOnSegment(ExactPoint a, ExactPoint b,
            ExactPoint point) {
        return withinArea(orientation(a, b, point), a, b, point)
                && point.east().compareTo(min(a.east(), b.east())
                        .subtract(COORDINATE_ENVELOPE)) >= 0
                && point.east().compareTo(max(a.east(), b.east())
                        .add(COORDINATE_ENVELOPE)) <= 0
                && point.north().compareTo(min(a.north(), b.north())
                        .subtract(COORDINATE_ENVELOPE)) >= 0
                && point.north().compareTo(max(a.north(), b.north())
                        .add(COORDINATE_ENVELOPE)) <= 0;
    }

    private static boolean withinArea(BigDecimal value, ExactPoint a, ExactPoint b,
            ExactPoint c) {
        return value.abs().compareTo(areaEnvelope(a, b, c)) <= 0;
    }

    private static BigDecimal areaEnvelope(ExactPoint a, ExactPoint b, ExactPoint c) {
        BigDecimal extent = b.east().subtract(a.east()).abs()
                .add(b.north().subtract(a.north()).abs())
                .add(c.east().subtract(a.east()).abs())
                .add(c.north().subtract(a.north()).abs());
        return AREA_EPSILON.add(PROJECTION_ERROR_METERS.multiply(BigDecimal.valueOf(2))
                .multiply(extent)).add(PROJECTION_ERROR_METERS.pow(2)
                        .multiply(BigDecimal.valueOf(8)));
    }

    private static BigDecimal orientation(ExactPoint a, ExactPoint b, ExactPoint c) {
        return b.east().subtract(a.east()).multiply(c.north().subtract(a.north()))
                .subtract(b.north().subtract(a.north()).multiply(c.east().subtract(a.east())));
    }

    private static BigDecimal distanceSquared(ExactPoint a, ExactPoint b) {
        return a.east().subtract(b.east()).pow(2)
                .add(a.north().subtract(b.north()).pow(2));
    }

    private static boolean opposite(BigDecimal first, BigDecimal second) {
        return first.signum() * second.signum() < 0;
    }

    private static BigDecimal min(BigDecimal a, BigDecimal b) { return a.min(b); }
    private static BigDecimal max(BigDecimal a, BigDecimal b) { return a.max(b); }
    private static BigDecimal exact(double value) { return new BigDecimal(value); }

    private static BigDecimal branch(BigDecimal longitude) {
        BigDecimal value = longitude.remainder(FULL_TURN);
        if (value.compareTo(HALF_TURN) > 0) value = value.subtract(FULL_TURN);
        else if (value.compareTo(HALF_TURN.negate()) <= 0) value = value.add(FULL_TURN);
        return value;
    }

    static final class InvalidContextGeometryException extends IllegalArgumentException {
        InvalidContextGeometryException(String message) { super(message); }
    }

    /** The changed segment must stay clear of the clipped-away certificate exterior. */
    static void requireChangedInside(MetricPoint start, MetricPoint end, LocalMetricFrame frame) {
        DistortionCertificate certificate = frame.distortionCertificate();
        MetricPoint southWest = frame.toMetric(certificate.southWest());
        MetricPoint northEast = frame.toMetric(certificate.northEast());
        double length = start.distanceTo(end);
        if (!(length > 0.0) || !Double.isFinite(length)) {
            throw new InvalidContextGeometryException("Changed segment has no certifiable extent");
        }
        // In the collinear branch the classifier treats an orientation below 1e-8 m^2
        // as zero. A short segment therefore needs a wider spatial inset than 1e-8 m.
        double guard = Math.max(EXCLUSION_GUARD_METERS, 4e-8 / length);
        if (!insideInset(start, southWest, northEast, guard)
                || !insideInset(end, southWest, northEast, guard)) {
            throw new InvalidContextGeometryException(
                    "Changed geometry is too near the certified frame edge for contextual clipping");
        }
    }

    private static boolean insideInset(MetricPoint point, MetricPoint southWest,
            MetricPoint northEast, double guard) {
        return point.xMeters() >= southWest.xMeters() + guard
                && point.xMeters() <= northEast.xMeters() - guard
                && point.yMeters() >= southWest.yMeters() + guard
                && point.yMeters() <= northEast.yMeters() - guard;
    }

    /**
     * Returns the in-certificate part of an unchanged geographic segment. Empty is returned
     * only when the complete segment misses a guarded rectangle around the certificate.
     * A near-boundary miss cannot be proved irrelevant to the metric collision classifier.
     */
    static Optional<ClippedSegment> clip(GeographicPoint start, GeographicPoint end,
            LocalMetricFrame frame) {
        if (start == null || end == null || frame == null) {
            throw new InvalidContextGeometryException("Context segment or frame is incomplete");
        }
        DistortionCertificate certificate = frame.distortionCertificate();
        double west = certificate.southWest().longitudeDegrees();
        double span = positiveSpan(west, certificate.northEast().longitudeDegrees());
        double south = certificate.southWest().latitudeDegrees();
        double north = certificate.northEast().latitudeDegrees();
        if (!(span > 0.0 && span <= 180.0 && north > south)) {
            throw new InvalidContextGeometryException("Context certificate has no rectangular area");
        }
        double rawDelta = end.longitudeDegrees() - start.longitudeDegrees();
        if (Math.abs(Math.abs(rawDelta % 360.0) - 180.0) <= FRAME_ADMISSION_DEGREES) {
            throw new InvalidContextGeometryException("Context longitude arc is ambiguous");
        }
        double startX = normalizeDelta(start.longitudeDegrees() - west);
        double deltaX = normalizeDelta(rawDelta);
        double endY = end.latitudeDegrees();
        double startY = start.latitudeDegrees();
        double eastScale = certificate.eastMetersPerRadian() * Math.PI / 180.0;
        double northScale = certificate.northMetersPerRadian() * Math.PI / 180.0;
        double guardX = Math.max(2.0 * FRAME_ADMISSION_DEGREES,
                EXCLUSION_GUARD_METERS / eastScale);
        double guardY = Math.max(2.0 * FRAME_ADMISSION_DEGREES,
                EXCLUSION_GUARD_METERS / northScale);
        ClippedSegment result = null;
        boolean guardedHit = false;
        for (double shift : new double[] {-360.0, 0.0, 360.0}) {
            double x = startX + shift;
            double[] guarded = interval(x, startY, x + deltaX, endY,
                    -guardX, span + guardX, south - guardY, north + guardY);
            if (guarded != null) {
                guardedHit = true;
            }
            double[] exact = interval(x, startY, x + deltaX, endY,
                    0.0, span, south, north);
            if (exact == null) {
                continue;
            }
            if (result != null) {
                throw new InvalidContextGeometryException("Context segment has ambiguous longitude branch");
            }
            double low = exact[0];
            double high = exact[1];
            GeographicPoint admittedStart = admitted(start, low == 0.0, x + low * deltaX,
                    startY + low * (endY - startY), west, span, south, north, certificate);
            GeographicPoint admittedEnd = admitted(end, high == 1.0, x + high * deltaX,
                    startY + high * (endY - startY), west, span, south, north, certificate);
            try {
                result = new ClippedSegment(frame.toMetric(admittedStart),
                        frame.toMetric(admittedEnd), low == 0.0, high == 1.0);
            } catch (IllegalArgumentException invalid) {
                throw new InvalidContextGeometryException("Context clipping exceeds certified frame");
            }
        }
        if (result != null) {
            return Optional.of(result);
        }
        if (guardedHit) {
            throw new InvalidContextGeometryException(
                    "Context segment is too near the certificate edge to exclude");
        }
        if (!exactExterior(start, end, frame)) {
            throw new InvalidContextGeometryException(
                    "Context rectangle exclusion is numerically uncertain");
        }
        return Optional.empty();
    }

    /** The floating interval miss is accepted only after an exact guarded exterior proof. */
    private static boolean exactExterior(GeographicPoint start, GeographicPoint end,
            LocalMetricFrame frame) {
        ExactChord source = originalChord(start, end, frame);
        DistortionCertificate certificate = frame.distortionCertificate();
        ExactChord diagonal = originalChord(certificate.southWest(),
                certificate.northEast(), frame);
        ExactPoint southWest = diagonal.start(), northEast = diagonal.end();
        ExactPoint northWest = new ExactPoint(southWest.east(), northEast.north());
        ExactPoint southEast = new ExactPoint(northEast.east(), southWest.north());
        ExactChord[] edges = {
            new ExactChord(southWest, southEast), new ExactChord(southEast, northEast),
            new ExactChord(northEast, northWest), new ExactChord(northWest, southWest)
        };
        BigDecimal fullTurnMetres = FULL_TURN.multiply(RADIANS_PER_DEGREE)
                .multiply(exact(certificate.eastMetersPerRadian()));
        for (int shift = -1; shift <= 1; shift++) {
            ExactChord branch = source.shifted(fullTurnMetres.multiply(BigDecimal.valueOf(shift)));
            if (insideRectangle(branch.start(), southWest, northEast)
                    || insideRectangle(branch.end(), southWest, northEast)) return false;
            for (ExactChord edge : edges) {
                if (anyContactOnBranch(branch, edge)) return false;
            }
        }
        return true;
    }

    private static boolean insideRectangle(ExactPoint point, ExactPoint southWest,
            ExactPoint northEast) {
        return point.east().compareTo(southWest.east()) >= 0
                && point.east().compareTo(northEast.east()) <= 0
                && point.north().compareTo(southWest.north()) >= 0
                && point.north().compareTo(northEast.north()) <= 0;
    }

    private static GeographicPoint admitted(GeographicPoint original, boolean originalEndpoint,
            double x, double y, double west, double span, double south, double north,
            DistortionCertificate certificate) {
        if (originalEndpoint) return original;
        double boundedX = Math.max(0.0, Math.min(span, x));
        double boundedY = Math.max(south, Math.min(north, y));
        double longitude = boundedX == 0.0 ? certificate.southWest().longitudeDegrees()
                : boundedX == span ? certificate.northEast().longitudeDegrees()
                : normalize(west + boundedX);
        return new GeographicPoint(boundedY, longitude);
    }

    private static double[] interval(double startX, double startY, double endX, double endY,
            double minX, double maxX, double minY, double maxY) {
        double[] range = {0.0, 1.0};
        if (!axis(startX, endX - startX, minX, maxX, range)
                || !axis(startY, endY - startY, minY, maxY, range)) return null;
        return range[0] <= range[1] ? range : null;
    }

    private static boolean axis(double origin, double delta, double min, double max,
            double[] range) {
        if (delta == 0.0) return origin >= min && origin <= max;
        double first = (min - origin) / delta;
        double second = (max - origin) / delta;
        range[0] = Math.max(range[0], Math.min(first, second));
        range[1] = Math.min(range[1], Math.max(first, second));
        return range[0] <= range[1];
    }

    private static double positiveSpan(double west, double east) {
        double value = (east - west) % 360.0;
        return value < 0.0 ? value + 360.0 : value;
    }

    private static double normalizeDelta(double value) {
        double result = value % 360.0;
        if (result > 180.0) result -= 360.0;
        else if (result <= -180.0) result += 360.0;
        return result;
    }

    private static double normalize(double value) {
        double result = normalizeDelta(value);
        return result == -180.0 ? 180.0 : result;
    }
}
