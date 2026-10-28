package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.List;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ContextContactAssessment;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;

/** Human review details from exactly the graph and evidence displayed by the immutable plan. */
public final class ModernPlanReview {
    private ModernPlanReview() { }
    public static String intervalCandidateIdentity(
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.IntervalTraceBatch batch,
            java.util.Map<Integer, Integer> choices) {
        return "intervals:" + batch.options().sourceTier() + ":"
                + batch.fullRequest().evidenceContentHash() + ":" + batch.runs().size() + ":"
                + java.util.stream.IntStream.range(0, batch.runs().size())
                        .mapToObj(index -> index + "=" + choices.getOrDefault(index, 0))
                        .reduce((left, right) -> left + "," + right).orElse("");
    }
    public static List<ContextContactAssessment> contacts(AlignmentEditPlan plan,
            EvidenceSnapshot evidence, double radius) {
        return ModernSingleWayEditPlanAdapter.assessFinalTopology(plan, evidence, radius).contacts();
    }
    public static String summary(AlignmentEditPlan plan, EvidenceSnapshot evidence, double radius) {
        if (plan == null) return "";
        StringBuilder text = new StringBuilder();
        int count = plan.disconnectedOccurrenceReplacements().size();
        if (count > 0) {
            int connections = plan.permissions().sharedNodeDisconnectionIntent().originalNodeKeys().values()
                    .stream().mapToInt(node -> (int) plan.disconnectionSource().incomingReferrerWatches()
                            .get(node).stream().filter(key -> key.type()
                                    == org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.Type.WAY
                                    && !key.equals(plan.selectedWayKey())).count()).sum();
            text.append("This slide disconnects the selected way at ").append(count)
                    .append(count == 1 ? " shared node." : " shared nodes.")
                    .append(" Connections removed: ").append(connections).append('\n');
        }
        for (var contact : contacts(plan, evidence, radius)) {
            if (contact.disposition() != ContextContactAssessment.Disposition.REVIEW_REQUIRED) continue;
            var context = (DetachedWay) plan.before().primitives().get(contact.contextWayKey());
            boolean waterway = context.tags().containsKey("waterway");
            text.append(waterway ? (switch (contact.contactType()) {
                        case CROSSING -> "The proposed path crosses a stream. Check the crossing before applying. ";
                        case VERTEX_TOUCH -> "The proposed path touches a waterway. Check the contact before applying. ";
                        case COLLINEAR_OVERLAP -> "The proposed path overlaps a waterway. Check the overlap before applying. ";
                    }) + "Verify bridge, culvert, tunnel or ford handling."
                    : switch (contact.contactType()) {
                        case CROSSING -> "The proposed path crosses an independent way. Check the crossing before applying.";
                        case VERTEX_TOUCH -> "The proposed path touches an independent way without connecting to it.";
                        case COLLINEAR_OVERLAP -> "The proposed path overlaps an independent way without connecting to it.";
                    }).append('\n');
        }
        return text.toString();
    }
}
