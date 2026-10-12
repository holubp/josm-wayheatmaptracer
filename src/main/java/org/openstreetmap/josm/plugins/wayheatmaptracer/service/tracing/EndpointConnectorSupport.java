package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;

/** Bounded direct-support admission for midpoint samples of one connector. */
final class EndpointConnectorSupport {
    private final double stepMeters;
    private final double allowanceMeters;
    private final double terminalAllowanceMeters;
    private boolean ambiguousPrefix = true;
    private boolean directlyObserved;
    private double unsupportedMeters;

    EndpointConnectorSupport(double stepMeters, double pitchMeters, double terminalUncertaintyMeters) {
        if (!Double.isFinite(stepMeters) || stepMeters <= 0.0
                || !Double.isFinite(pitchMeters) || pitchMeters <= 0.0
                || !Double.isFinite(terminalUncertaintyMeters) || terminalUncertaintyMeters < 0.0) {
            throw new IllegalArgumentException("Connector sampling scales must be finite and bounded");
        }
        this.stepMeters = stepMeters;
        this.allowanceMeters = Math.max(2.0, pitchMeters);
        this.terminalAllowanceMeters = allowanceMeters + terminalUncertaintyMeters;
        if (!Double.isFinite(terminalAllowanceMeters)) {
            throw new IllegalArgumentException("Connector uncertainty allowance must be finite");
        }
    }

    boolean accept(ObservationOwnership ownership, boolean clearOfReceiver) {
        boolean direct = ownership == ObservationOwnership.DIRECT_TWO_SIDED && clearOfReceiver;
        ambiguousPrefix &= ownership == ObservationOwnership.DIRECT_AMBIGUOUS && clearOfReceiver;
        directlyObserved |= direct;
        unsupportedMeters = direct ? 0.0 : unsupportedMeters + stepMeters;
        return unsupportedMeters <= allowanceMeters
                || !directlyObserved && ambiguousPrefix && unsupportedMeters <= terminalAllowanceMeters;
    }

    boolean directlyObserved() {
        return directlyObserved;
    }
}
