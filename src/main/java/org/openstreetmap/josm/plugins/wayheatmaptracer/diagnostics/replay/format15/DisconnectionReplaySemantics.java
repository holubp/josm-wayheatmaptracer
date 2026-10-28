package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.util.ArrayList;
import java.util.List;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ModernPlanReview;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.PreviewReviewState;

/** Versioned factual planning output, recomputed rather than copied from the recorded plan. */
final class DisconnectionReplaySemantics {
    static final String ARTIFACT = "disconnection-review.json";
    private DisconnectionReplaySemantics() { }

    static List<String> recompute(FrozenReplayInput input, ModernTracePipeline.Result actual) {
        if (input.request().permissions().sharedNodeDisconnectionIntent().isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        var adapter = new ModernSingleWayEditPlanAdapter();
        for (int index = 0; index < actual.routes().size(); index++) {
            try {
                var plan = adapter.adaptFrozen(input.request(), input.evidence(), input.network(),
                        input.options(), actual, index, input.planSearchRadiusMeters());
                String candidate = input.options().sourceTier() + ":" + input.request().evidenceContentHash()
                        + ":" + index + ":" + actual.routes().get(index).hypothesis().id();
                result.add(review(plan, input.evidence(), input.planSearchRadiusMeters(), candidate));
            } catch (IllegalArgumentException unavailable) {
                // An unsupported/blocked route grants no replacement or confirmation authority.
                result.add("{\"schema\":1,\"status\":\"PLAN_UNAVAILABLE\",\"routeIdentity\":"
                        + quote(actual.routes().get(index).hypothesis().id()) + "}");
            }
        }
        return List.copyOf(result);
    }

    static String review(AlignmentEditPlan plan, EvidenceSnapshot evidence, double radius,
            String candidateIdentity) {
        var review = PreviewReviewState.fromEditPlan(candidateIdentity, plan);
        StringBuilder json = new StringBuilder("{\"schema\":1,\"status\":\"PLANNED\",\"planIdentity\":")
                .append(quote(plan.canonicalHash())).append(",\"sourceIdentity\":")
                .append(quote(plan.before().canonicalHash())).append(",\"initialSourceIdentity\":")
                .append(plan.disconnectionSource() == null ? "null" : quote(plan.disconnectionSource().canonicalHash()))
                .append(",\"geometryMode\":").append(plan.disconnectionGeometryMode() == null
                        ? "null" : quote(plan.disconnectionGeometryMode().name()))
                .append(",\"candidateIdentity\":").append(quote(candidateIdentity))
                .append(",\"searchRadiusMeters\":").append(radius)
                .append(",\"confirmationIdentity\":").append(quote(review.reviewHash()))
                .append(",\"confirmationRequired\":").append(review.disposition()
                        == org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport.Disposition.REVIEW_REQUIRED)
                .append(",\"disposition\":").append(quote(review.disposition().name()))
                .append(",\"occurrences\":[");
        boolean comma = false;
        for (var entry : new java.util.TreeMap<>(plan.disconnectedOccurrenceReplacements()).entrySet()) {
            if (comma) json.append(','); comma = true;
            json.append("{\"occurrence\":").append(entry.getKey()).append(",\"originalIdentity\":")
                    .append(quote(plan.permissions().sharedNodeDisconnectionIntent().originalNodeKeys()
                            .get(entry.getKey()).toString()))
                    .append(",\"replacementIdentity\":").append(quote(entry.getValue().toString())).append('}');
            bounded(json);
        }
        json.append("],\"proposedPorts\":["); comma = false;
        for (var entry : plan.after().proposedPortReplacements().entrySet().stream()
                .sorted(java.util.Comparator.comparing(e -> e.getKey().toString())).toList()) {
            if (comma) json.append(','); comma = true;
            json.append("{\"originalPort\":").append(quote(entry.getKey().toString()))
                    .append(",\"replacementIdentity\":").append(quote(entry.getValue().toString())).append('}');
            bounded(json);
        }
        json.append("],\"contacts\":["); comma = false;
        for (var contact : ModernPlanReview.contacts(plan, evidence, radius)) {
            if (comma) json.append(','); comma = true;
            json.append("{\"selectedWay\":").append(quote(contact.selectedWayKey().toString()))
                    .append(",\"contextWay\":").append(quote(contact.contextWayKey().toString()))
                    .append(",\"selectedSegment\":").append(contact.selectedSegmentIndex())
                    .append(",\"contextSegment\":").append(contact.contextSegmentIndex())
                    .append(",\"routeSegment\":").append(contact.routeSegmentIndex())
                    .append(",\"type\":").append(quote(contact.contactType().name()))
                    .append(",\"inherited\":").append(contact.inherited())
                    .append(",\"disposition\":").append(quote(contact.disposition().name()))
                    .append(",\"reason\":").append(quote(contact.reasonCode())).append(",\"locationMeters\":[");
            for (int i = 0; i < contact.location().size(); i++) {
                if (i > 0) json.append(','); var p = contact.location().get(i);
                json.append('[').append(p.xMeters()).append(',').append(p.yMeters()).append(']');
            }
            json.append("]}"); bounded(json);
        }
        return json.append("]}").toString();
    }

    static String artifact(List<String> reviews) {
        return "{\"schema\":1,\"routeReviews\":[" + String.join(",", reviews) + "]}\n";
    }
    static String intervalReview(org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.IntervalTraceBatch batch,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.FixedIntervalEditPlanComposer.Assessment assessment,
            java.util.Map<Integer, Integer> choices) {
        String candidate = ModernPlanReview.intervalCandidateIdentity(batch, choices);
        return "{\"schema\":1,\"unownedDisconnectionOccurrences\":"
                + assessment.unownedDisconnectionOccurrences() + ",\"review\":"
                + assessment.plan().map(plan -> review(plan, batch.evidence(),
                        batch.fullRequest().permissions().ordinaryRadiusMeters(), candidate)).orElse("null") + "}\n";
    }
    private static void bounded(StringBuilder value) {
        if (value.length() > 1_048_576)
            throw new IllegalArgumentException("Disconnection review exceeds artifact budget");
    }
    private static String quote(String value) { return ScalarReplayExpectation.quote(value); }
}
