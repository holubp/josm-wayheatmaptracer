package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.IntervalTraceBatch;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.FixedIntervalEditPlanComposer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.ManualJunctionEligibility;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;

/** Produces the named, checksummed frozen inputs consumed by strict production replay. */
public final class Format15ProductionBundleFactory {
    private static final String INTERVAL_INDEX_ARTIFACT = "interval-production.json";
    private static final String INTERVAL_PREVIEW_ARTIFACT = "private/interval-composed-preview.json";
    public static final String PLAN_AVAILABILITY_ARTIFACT = "plan-availability.json";

    /** Stable terminal plan/apply states for coordinate-free live-attempt diagnostics. */
    public enum PlanAvailabilityStatus {
        PLAN_AVAILABLE, REVIEW_REQUIRED_UNCONFIRMED, REVIEW_REQUIRED_CONFIRMED,
        NO_CHANGE, UNAVAILABLE, CANCELLED, FAILED
    }

    /** Allow-listed adapter reason codes; no free-form detail enters this receipt. */
    public enum PlanAvailabilityReason {
        PLAN_AVAILABLE, NO_CHANGE, CLEANUP_UNAVAILABLE_FOR_ENGINE, PRECISE_SHAPE_REQUIRED,
        SOURCE_LINEAGE_UNAVAILABLE, CANDIDATE_ASSIGNMENTS_UNAVAILABLE, MANUAL_JUNCTION,
        FINAL_TOPOLOGY_CROSSING, FINAL_TOPOLOGY_VERTEX_TOUCH,
        FINAL_TOPOLOGY_COLLINEAR_OVERLAP, FINAL_TOPOLOGY_CONTINUATION,
        FINAL_GEOMETRY_BLOCKED, PLAN_UNAVAILABLE, ATTEMPT_CANCELLED, ATTEMPT_FAILED
    }

    /** Typed plan availability carried independently of private geometry and arbitrary detail. */
    public record PlanAvailability(PlanAvailabilityStatus status, PlanAvailabilityReason reason,
            ModernSingleWayEditPlanAdapter.NoChangeProof noChangeProof) {
        public PlanAvailability(PlanAvailabilityStatus status, PlanAvailabilityReason reason) {
            this(status, reason, null);
        }

        public PlanAvailability {
            if (status == null || reason == null) {
                throw new IllegalArgumentException("Plan availability receipt is incomplete");
            }
            boolean valid = switch (status) {
                case PLAN_AVAILABLE -> reason == PlanAvailabilityReason.PLAN_AVAILABLE;
                case REVIEW_REQUIRED_UNCONFIRMED, REVIEW_REQUIRED_CONFIRMED ->
                        reason == PlanAvailabilityReason.PLAN_AVAILABLE;
                case NO_CHANGE -> reason == PlanAvailabilityReason.NO_CHANGE
                        && noChangeProof != null;
                case UNAVAILABLE -> reason != PlanAvailabilityReason.PLAN_AVAILABLE
                        && reason != PlanAvailabilityReason.NO_CHANGE
                        && reason != PlanAvailabilityReason.ATTEMPT_CANCELLED
                        && reason != PlanAvailabilityReason.ATTEMPT_FAILED;
                case CANCELLED -> reason == PlanAvailabilityReason.ATTEMPT_CANCELLED;
                case FAILED -> reason == PlanAvailabilityReason.ATTEMPT_FAILED;
            };
            if (!valid || status != PlanAvailabilityStatus.NO_CHANGE && noChangeProof != null) {
                throw new IllegalArgumentException("Plan availability reason contradicts status");
            }
        }

        public static PlanAvailability available() {
            return new PlanAvailability(PlanAvailabilityStatus.PLAN_AVAILABLE,
                    PlanAvailabilityReason.PLAN_AVAILABLE);
        }

        public static PlanAvailability reviewRequired(boolean confirmed,
                ModernSingleWayEditPlanAdapter.ApplyAvailability availability) {
            if (availability != ModernSingleWayEditPlanAdapter.ApplyAvailability.PLAN_AVAILABLE) {
                throw new IllegalArgumentException("Review confirmation requires an available exact plan");
            }
            return new PlanAvailability(confirmed ? PlanAvailabilityStatus.REVIEW_REQUIRED_CONFIRMED
                    : PlanAvailabilityStatus.REVIEW_REQUIRED_UNCONFIRMED,
                    PlanAvailabilityReason.PLAN_AVAILABLE);
        }

        public static PlanAvailability noChange() {
            throw new IllegalArgumentException("NO_CHANGE requires an adapter-bound selected-route proof");
        }

        /** Accepts only a genuine adapter assessment with its private source/route witness. */
        public static PlanAvailability noChange(ModernSingleWayEditPlanAdapter.Assessment assessment) {
            if (assessment == null || assessment.availability()
                    != ModernSingleWayEditPlanAdapter.ApplyAvailability.NO_CHANGE
                    || assessment.noChangeWatch().isEmpty() || assessment.noChangeProof().isEmpty()) {
                throw new IllegalArgumentException("NO_CHANGE requires an adapter-bound selected-route proof");
            }
            return new PlanAvailability(PlanAvailabilityStatus.NO_CHANGE,
                    PlanAvailabilityReason.NO_CHANGE, assessment.noChangeProof().orElseThrow());
        }

        public static PlanAvailability unavailable(
                ModernSingleWayEditPlanAdapter.ApplyAvailability availability) {
            if (availability == null || availability == ModernSingleWayEditPlanAdapter.ApplyAvailability.PLAN_AVAILABLE
                    || availability == ModernSingleWayEditPlanAdapter.ApplyAvailability.NO_CHANGE) {
                throw new IllegalArgumentException("Unavailable plan requires an unavailable adapter reason");
            }
            return new PlanAvailability(PlanAvailabilityStatus.UNAVAILABLE,
                    PlanAvailabilityReason.valueOf(availability.name()));
        }

        public static PlanAvailability cancelled() {
            return new PlanAvailability(PlanAvailabilityStatus.CANCELLED,
                    PlanAvailabilityReason.ATTEMPT_CANCELLED);
        }

        public static PlanAvailability failed() {
            return new PlanAvailability(PlanAvailabilityStatus.FAILED,
                    PlanAvailabilityReason.ATTEMPT_FAILED);
        }
    }

    /** Serializes only stable enum names; coordinates, identities and free-form errors are excluded. */
    public static Format15Artifact planAvailabilityArtifact(PlanAvailability availability) {
        if (availability == null) throw new IllegalArgumentException("Plan availability is required");
        String json = "{\"schema\":1,\"status\":" + quote(availability.status().name())
                + ",\"reason\":" + quote(availability.reason().name()) + "}\n";
        return Format15Artifact.text(PLAN_AVAILABILITY_ARTIFACT, json);
    }

    /** Typed per-interval failure/no-op reasons exported by the additive interval artifact. */
    public enum IntervalReason {
        VALIDATED, NO_PRODUCTION_ROUTE, NO_GEOMETRY_CHANGE, LOCAL_IMAGE_SUPPORT,
        LOCAL_ROUTE_BLOCKED, LOCAL_CONNECTOR_SUPPORT, T_LOCAL_PRECOMMAND_EVIDENCE,
        GLOBAL_FINAL_VALIDATION, RESOURCE_LIMIT, CANCELLED, LOCAL_FAILURE
    }

    /** Allowed terminal states for a partitioned production diagnostic artifact. */
    public enum IntervalArtifactStatus {
        PRODUCED, PREVIEW, REVIEWED, CONFIRMED, APPLIED, APPLIED_AFTER_REVIEW,
        CANCELLED, RESOURCE_LIMIT, FAILED
    }

    /** Safe source lineage values that can be exported without owner objects or raw metadata. */
    public sealed interface IntervalSourceReceipt
            permits ManagedTileSourceReceipt, VisibleLayerSourceReceipt { }

    /** Numeric receipt for one managed tile generation. */
    public record ManagedTileSourceReceipt(long generation, int zoom, String sourceIdentityHash)
            implements IntervalSourceReceipt {
        public ManagedTileSourceReceipt {
            if (generation < 0 || zoom < 0 || zoom > 22) {
                throw new IllegalArgumentException("Managed source receipt is outside its numeric range");
            }
            Format15Safety.requiredHash(sourceIdentityHash, "managed source identity hash");
        }
    }

    /** Numeric receipt for one visible-layer publication revision. */
    public record VisibleLayerSourceReceipt(Long revision, Integer zoom, String sourceIdentityHash)
            implements IntervalSourceReceipt {
        public VisibleLayerSourceReceipt {
            if ((revision != null && revision < 0) || (zoom != null && (zoom < 0 || zoom > 22))) {
                throw new IllegalArgumentException("Visible source receipt is outside its numeric range");
            }
            if (sourceIdentityHash != null) {
                Format15Safety.requiredHash(sourceIdentityHash, "visible source identity hash");
            }
        }

        /** Explicit source observation where this renderer did not provide a receipt or tile zoom. */
        public static VisibleLayerSourceReceipt unavailable() {
            return new VisibleLayerSourceReceipt(null, null, null);
        }
    }

    private Format15ProductionBundleFactory() {
    }

    /**
     * Explicitly declares that the caller computed this bundle using the current
     * named numerical policies. The caller owns that provenance assertion; this
     * method cannot establish the backend of arbitrary historic output objects.
     * Old factory entry points deliberately do not add this declaration.
     */
    public static Format15Bundle withCurrentNumericalPolicy(Format15Bundle bundle,
            FrozenReplayInput input, TrackerMode computedEngine) {
        if (bundle == null || input == null
                || !bundle.parameterHash().equals(input.request().parameterHash())) {
            throw new IllegalArgumentException("numerical-policy-binding-invalid");
        }
        Format15Artifact frozen = bundle.artifacts().get("frozen-input.bin");
        String inputHash = frozen == null ? null : frozen.sha256();
        if (frozen != null) {
            var trace = numericalReceiptMetadata(bundle.artifact("trace-request.json"));
            if (!input.request().engine().name().equals(trace.get("engine"))) {
                throw new IllegalArgumentException("numerical-policy-binding-invalid");
            }
        } else if (bundle.artifactNames().contains(INTERVAL_INDEX_ARTIFACT)) {
            var index = numericalReceiptMetadata(bundle.artifact(INTERVAL_INDEX_ARTIFACT));
            Object digest = index.get("sharedInputHash");
            if (digest instanceof String value) inputHash = Format15Safety.requiredHash(value, "sharedInputHash");
            else if (digest != null) throw new IllegalArgumentException("numerical-policy-binding-invalid");
        }
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>(bundle.artifacts());
        artifacts.put(Format15NumericalPolicyReceipt.ARTIFACT,
            Format15NumericalPolicyReceipt.current(bundle.sourceIdentityHash(), inputHash,
                input.request().parameterHash(), input.request().engine(), computedEngine));
        return new Format15Bundle(bundle.buildIdentity(), bundle.sourceIdentityHash(), bundle.parameterHash(), artifacts);
    }

    private static Map<String, Object> numericalReceiptMetadata(Format15Artifact artifact) {
        try {
            return Format15ArchiveReader.parseObject(artifact.bytes(), artifact.name());
        } catch (Format15ArchiveException invalid) {
            throw new IllegalArgumentException("numerical-policy-binding-invalid");
        }
    }

    /** Creates a Format-15 archive payload for scalar/final replay only; it does not claim raster or edit-plan replay. */
    public static Format15Bundle create(String buildIdentity, FrozenReplayInput input) {
        if (buildIdentity == null || buildIdentity.isBlank() || input == null) {
            throw new IllegalArgumentException("Build identity and frozen replay input are required");
        }
        byte[] frozenInput = FrozenReplayCodec.encode(input);
        String inputHash = Format15Safety.sha256(frozenInput);
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>();
        artifacts.put("frozen-input.bin",
            Format15Artifact.binary("frozen-input.bin", frozenInput));
        String identities = "{\"codecVersion\":" + FrozenReplayCodec.encodedVersion(frozenInput)
            + ",\"inputHash\":" + quote(inputHash) + ",\"evidenceHash\":"
            + quote(input.evidence().canonicalHash()) + ",\"networkHash\":"
            + quote(input.network().canonicalHash()) + ",\"parameterHash\":"
            + quote(input.request().parameterHash()) + "}\n";
        artifacts.put("frozen-input-identities.json",
            Format15Artifact.text("frozen-input-identities.json", identities));
        artifacts.put("trace-request.json", Format15Artifact.text("trace-request.json",
            "{\"engine\":" + quote(input.request().engine().name()) + ",\"fieldName\":"
                + quote(input.options().fieldName()) + ",\"frozenInputHash\":"
                + quote(inputHash) + "}\n"));
        artifacts.put("evidence-frame.json", Format15Artifact.text("evidence-frame.json",
            "{\"snapshotId\":" + quote(input.evidence().snapshotId())
                + ",\"contentHash\":" + quote(input.evidence().canonicalHash()) + "}\n"));
        return new Format15Bundle(buildIdentity, inputHash,
            input.request().parameterHash(), artifacts);
    }

    /** Captures the result actually produced by the live pipeline, without running inference again. */
    public static Format15Bundle createLive(String buildIdentity, FrozenReplayInput input,
            ModernTracePipeline.Result actual, String status, String sourceLineage,
            int routeIndex, AlignmentEditPlan plan, boolean reviewed, boolean applied) {
        return createLive(buildIdentity, input, actual, status, sourceLineage,
            routeIndex, plan, reviewed, applied, Map.of());
    }

    /** Captures worker counters beside the frozen input and actual output. */
    public static Format15Bundle createLive(String buildIdentity, FrozenReplayInput input,
            ModernTracePipeline.Result actual, String status, String sourceLineage,
            int routeIndex, AlignmentEditPlan plan, boolean reviewed, boolean applied,
            Map<String, Number> counters) {
        return createLive(buildIdentity, input, actual, status, sourceLineage, routeIndex,
                plan, reviewed, applied, counters, null);
    }

    /** Adds the capture/plan junction decision to the live attempt without changing replay inputs. */
    public static Format15Bundle createLive(String buildIdentity, FrozenReplayInput input,
            ModernTracePipeline.Result actual, String status, String sourceLineage,
            int routeIndex, AlignmentEditPlan plan, boolean reviewed, boolean applied,
            Map<String, Number> counters, ManualJunctionEligibility.Reason manualJunctionReason) {
        return createLiveWithPlanAvailability(buildIdentity, input, actual, status, sourceLineage,
                routeIndex, plan, reviewed, applied, counters, manualJunctionReason,
                inferPlanAvailability(status, plan, reviewed));
    }

    /** Adds an explicitly typed plan state to the actual live output without changing replay inputs. */
    public static Format15Bundle createLiveWithPlanAvailability(String buildIdentity,
            FrozenReplayInput input, ModernTracePipeline.Result actual, String status,
            String sourceLineage, int routeIndex, AlignmentEditPlan plan, boolean reviewed,
            boolean applied, Map<String, Number> counters,
            ManualJunctionEligibility.Reason manualJunctionReason,
            PlanAvailability planAvailability) {
        return createLiveWithPlanAvailability(buildIdentity, input, actual, status,
                sourceLineage, routeIndex, plan, reviewed, applied, counters,
                manualJunctionReason, planAvailability, null);
    }

    /** Binds an adapter-issued no-write result to the same selected route and live recapture. */
    public static Format15Bundle createLiveWithPlanAvailability(String buildIdentity,
            FrozenReplayInput input, ModernTracePipeline.Result actual, String status,
            String sourceLineage, int routeIndex, AlignmentEditPlan plan, boolean reviewed,
            boolean applied, Map<String, Number> counters,
            ManualJunctionEligibility.Reason manualJunctionReason,
            PlanAvailability planAvailability, NetworkSnapshot currentNoChangeNetwork) {
        if (actual == null || status == null || status.isBlank()
                || sourceLineage == null || sourceLineage.isBlank()
                || planAvailability == null
                || input == null
                || routeIndex < -1 || routeIndex >= actual.routes().size()
                || (routeIndex == -1 && !actual.routes().isEmpty())
                || (routeIndex == -1 && (plan != null || reviewed || applied))
                || ((reviewed || applied) && plan == null)) {
            throw new IllegalArgumentException("Live diagnostic attempt is incomplete");
        }
        requirePlanAvailabilityBinding(status, plan, reviewed, applied, planAvailability);
        if (planAvailability.status() == PlanAvailabilityStatus.NO_CHANGE) {
            planAvailability.noChangeProof().requireMatches(input.request(), input.evidence(),
                    input.network(), input.options(), actual, routeIndex, currentNoChangeNetwork);
        } else if (currentNoChangeNetwork != null) {
            throw new IllegalArgumentException("Current no-change network is unrelated to this plan state");
        }
        if ((planAvailability.status() == PlanAvailabilityStatus.PLAN_AVAILABLE
                || planAvailability.status() == PlanAvailabilityStatus.REVIEW_REQUIRED_UNCONFIRMED
                || planAvailability.status() == PlanAvailabilityStatus.REVIEW_REQUIRED_CONFIRMED)
                && plan == null) {
            throw new IllegalArgumentException("Available plan diagnostics require the exact edit plan");
        }
        if (planAvailability.status() == PlanAvailabilityStatus.PLAN_AVAILABLE
                && plan != null && !plan.validation().applicable()
                || (planAvailability.status() == PlanAvailabilityStatus.REVIEW_REQUIRED_UNCONFIRMED
                    || planAvailability.status() == PlanAvailabilityStatus.REVIEW_REQUIRED_CONFIRMED)
                    && plan != null && !plan.validation().reviewRequired()) {
            throw new IllegalArgumentException("Plan availability differs from final plan validation");
        }
        Format15Safety.requireSafeExportedMetadata(status);
        Format15Safety.requireSafeExportedMetadata(sourceLineage);
        if (plan != null && (!plan.selectedWayKey().equals(input.request().selectedWayKey())
                || !plan.selectedRange().equals(input.request().selectedRange())
                || !plan.before().canonicalHash().equals(input.network().canonicalHash())
                || !plan.evidenceHash().equals(input.evidence().canonicalHash())
                || !plan.settingsHash().equals(input.request().settingsHash())
                || !plan.parameterHash().equals(input.request().parameterHash())
                || !plan.routeIdentity().equals(actual.routes().get(routeIndex).hypothesis().id())
                || applied && plan.validation().disposition()
                    == org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport.Disposition.HARD_BLOCKED)) {
            throw new IllegalArgumentException("Live diagnostic plan differs from the computed route or input");
        }
        Format15Bundle base = create(buildIdentity, input);
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>(base.artifacts());
        Format15ReplayRunner.Result finalResult = new Format15ReplayRunner.Result(
            ReplayLevel.FINAL_GEOMETRY, input.request().engine(), input.request().engine(),
            actual.inference(), actual.routes(), input.canonicalHash());
        FinalReplayExpectation expectation = FinalReplayExpectation.capture(buildIdentity,
            input, finalResult);
        artifacts.put(FinalReplayExpectation.ARTIFACT_NAME, Format15Artifact.binary(
            FinalReplayExpectation.ARTIFACT_NAME, expectation.bytes()));
        artifacts.put("original-geometry.json", Format15Artifact.text("original-geometry.json",
            originalGeometry(input.network(), input.request().selectedWayKey(),
                input.request().selectedRange().firstIndex(),
                input.request().selectedRange().lastIndex())));
        if (routeIndex >= 0) {
            ModernTracePipeline.Route route = actual.routes().get(routeIndex);
            artifacts.put("raw-route.json", Format15Artifact.text("raw-route.json",
                metricGeometry(route.rawHypothesis().points())));
            artifacts.put("final-route.json", Format15Artifact.text("final-route.json",
                metricGeometry(route.hypothesis().points())));
            if (reviewed) {
                String reviewedGeometry = geographicWays(plan.finalPreviewWays());
                artifacts.put("reviewed-route.json", Format15Artifact.text("reviewed-route.json",
                    reviewedGeometry));
                artifacts.put("reviewed-route-identity.json", Format15Artifact.text(
                    "reviewed-route-identity.json", "{\"editPlanHash\":" + quote(plan.canonicalHash())
                        + ",\"reviewedGeometryHash\":" + quote(Format15Safety.sha256(
                            reviewedGeometry.getBytes(StandardCharsets.UTF_8))) + "}\n"));
            }
        }
        if (plan != null) {
            artifacts.put("frozen-edit-plan.bin", Format15Artifact.binary("frozen-edit-plan.bin",
                FrozenReplayCodec.encodeEditPlan(plan)));
            artifacts.put("planned-geometry.json", Format15Artifact.text("planned-geometry.json",
                geographicWays(plan.finalPreviewWays())));
            artifacts.put("edit-plan-identity.json", Format15Artifact.text("edit-plan-identity.json",
                "{\"planHash\":" + quote(plan.canonicalHash()) + ",\"beforeHash\":"
                    + quote(plan.before().canonicalHash()) + ",\"afterHash\":"
                    + quote(plan.after().canonicalHash()) + "}\n"));
            if (applied) {
                artifacts.put("applied-geometry.json", Format15Artifact.text("applied-geometry.json",
                    geographicWays(plan.finalPreviewWays())));
            }
        }
        artifacts.put("performance-counters.json", Format15Artifact.text(
            "performance-counters.json", performanceCounters(actual, counters)));
        artifacts.put(PLAN_AVAILABILITY_ARTIFACT, planAvailabilityArtifact(planAvailability));
        artifacts.put("attempt-status.json", Format15Artifact.text("attempt-status.json",
            "{\"status\":" + quote(status) + ",\"sourceLineage\":"
                + quote(sourceLineage) + ",\"routeIndex\":" + routeIndex
                + ",\"manualJunctionReason\":" + (manualJunctionReason == null
                    ? "null" : quote(manualJunctionReason.name()))
                + ",\"reviewed\":" + reviewed + ",\"applied\":" + applied
                + ",\"privateData\":true,\"capabilities\":{\"SCALAR_INFERENCE\":true,"
                + "\"FINAL_GEOMETRY\":true,\"RASTER_INFERENCE\":false,"
                + "\"FULL_EDIT_PLAN\":false}}\n"));
        addFinalOutputComponents(artifacts, expectation, finalResult);
        return new Format15Bundle(base.buildIdentity(), base.sourceIdentityHash(),
            base.parameterHash(), artifacts);
    }

    private static void requirePlanAvailabilityBinding(String attemptStatus, AlignmentEditPlan plan,
            boolean reviewed, boolean applied, PlanAvailability availability) {
        String terminalStatus = attemptStatus.toLowerCase(java.util.Locale.ROOT);
        PlanAvailabilityStatus state = availability.status();
        if (terminalStatus.equals("no-change") && state != PlanAvailabilityStatus.NO_CHANGE
                || state == PlanAvailabilityStatus.NO_CHANGE
                    && (plan != null || reviewed || applied)) {
            throw new IllegalArgumentException("NO_CHANGE requires an adapter-bound selected-route proof");
        }
        if (terminalStatus.equals("cancelled") || terminalStatus.equals("failed")) {
            PlanAvailabilityStatus expected = terminalStatus.equals("cancelled")
                    ? PlanAvailabilityStatus.CANCELLED : PlanAvailabilityStatus.FAILED;
            if (state != expected || plan != null || reviewed || applied) {
                throw new IllegalArgumentException("Terminal attempt state cannot carry edit or apply artifacts");
            }
            return;
        }
        if (state == PlanAvailabilityStatus.CANCELLED || state == PlanAvailabilityStatus.FAILED) {
            throw new IllegalArgumentException("Terminal availability must match the attempt state");
        }
        boolean appliedStatus = terminalStatus.equals("applied")
                || terminalStatus.equals("applied-after-review");
        if (applied != appliedStatus) {
            throw new IllegalArgumentException("Applied state differs from the attempt status");
        }
        if (applied && state != PlanAvailabilityStatus.PLAN_AVAILABLE
                && state != PlanAvailabilityStatus.REVIEW_REQUIRED_CONFIRMED) {
            throw new IllegalArgumentException("Apply requires an available or confirmed exact plan");
        }
        if ((terminalStatus.equals("confirmed") || terminalStatus.equals("applied-after-review"))
                && !reviewed) {
            throw new IllegalArgumentException("Confirmed attempt must carry review confirmation");
        }
        if (reviewed != (state == PlanAvailabilityStatus.REVIEW_REQUIRED_CONFIRMED)
                && state != PlanAvailabilityStatus.REVIEW_REQUIRED_UNCONFIRMED) {
            throw new IllegalArgumentException("Review flag differs from typed plan availability");
        }
        if (state == PlanAvailabilityStatus.REVIEW_REQUIRED_UNCONFIRMED && reviewed) {
            throw new IllegalArgumentException("Unconfirmed review state cannot be marked reviewed");
        }
        if (plan == null) {
            if (state == PlanAvailabilityStatus.PLAN_AVAILABLE
                    || state == PlanAvailabilityStatus.REVIEW_REQUIRED_UNCONFIRMED
                    || state == PlanAvailabilityStatus.REVIEW_REQUIRED_CONFIRMED) {
                throw new IllegalArgumentException("Available plan diagnostics require the exact edit plan");
            }
            if (state == PlanAvailabilityStatus.UNAVAILABLE
                    && isInspectableBlockReason(availability.reason())) {
                throw new IllegalArgumentException("Inspectable block reason requires its exact blocked plan");
            }
            return;
        }
        var disposition = plan.validation().disposition();
        switch (state) {
            case PLAN_AVAILABLE -> {
                if (disposition != org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport
                        .Disposition.APPLICABLE) {
                    throw new IllegalArgumentException("Available state requires an applicable exact plan");
                }
            }
            case REVIEW_REQUIRED_UNCONFIRMED, REVIEW_REQUIRED_CONFIRMED -> {
                if (disposition != org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport
                        .Disposition.REVIEW_REQUIRED
                        || availability.reason() != PlanAvailabilityReason.PLAN_AVAILABLE) {
                    throw new IllegalArgumentException("Review state requires an exact review-required plan");
                }
            }
            case UNAVAILABLE -> {
                if (reviewed || applied || disposition != org.openstreetmap.josm.plugins.wayheatmaptracer.model
                        .ValidationReport.Disposition.HARD_BLOCKED
                        || !blockedPlanMatchesReason(plan, availability.reason())) {
                    throw new IllegalArgumentException("Unavailable plan must be an inspectable blocked preview");
                }
            }
            case NO_CHANGE, CANCELLED, FAILED -> throw new IllegalArgumentException(
                    "Terminal availability cannot carry an edit plan");
        }
    }

    private static boolean isInspectableBlockReason(PlanAvailabilityReason reason) {
        return reason == PlanAvailabilityReason.FINAL_TOPOLOGY_CROSSING
                || reason == PlanAvailabilityReason.FINAL_TOPOLOGY_VERTEX_TOUCH
                || reason == PlanAvailabilityReason.FINAL_TOPOLOGY_COLLINEAR_OVERLAP
                || reason == PlanAvailabilityReason.FINAL_TOPOLOGY_CONTINUATION
                || reason == PlanAvailabilityReason.FINAL_GEOMETRY_BLOCKED;
    }

    private static boolean blockedPlanMatchesReason(AlignmentEditPlan plan, PlanAvailabilityReason reason) {
        return isInspectableBlockReason(reason)
                && blockedPlanAvailability(plan).name().equals(reason.name());
    }

    private static PlanAvailability inferPlanAvailability(String status, AlignmentEditPlan plan,
            boolean reviewed) {
        if ("cancelled".equalsIgnoreCase(status)) return PlanAvailability.cancelled();
        if ("failed".equalsIgnoreCase(status)) return PlanAvailability.failed();
        if (plan == null) return PlanAvailability.unavailable(
                ModernSingleWayEditPlanAdapter.ApplyAvailability.PLAN_UNAVAILABLE);
        return switch (plan.validation().disposition()) {
            case APPLICABLE -> PlanAvailability.available();
            case REVIEW_REQUIRED -> PlanAvailability.reviewRequired(reviewed,
                    ModernSingleWayEditPlanAdapter.ApplyAvailability.PLAN_AVAILABLE);
            case HARD_BLOCKED -> PlanAvailability.unavailable(blockedPlanAvailability(plan));
        };
    }

    private static ModernSingleWayEditPlanAdapter.ApplyAvailability blockedPlanAvailability(
            AlignmentEditPlan plan) {
        List<String> findings = plan.validation().findingCodes();
        if (findings.contains("final-topology:CROSSING")) {
            return ModernSingleWayEditPlanAdapter.ApplyAvailability.FINAL_TOPOLOGY_CROSSING;
        }
        if (findings.contains("final-topology:VERTEX_TOUCH")) {
            return ModernSingleWayEditPlanAdapter.ApplyAvailability.FINAL_TOPOLOGY_VERTEX_TOUCH;
        }
        if (findings.contains("final-topology:COLLINEAR_OVERLAP")) {
            return ModernSingleWayEditPlanAdapter.ApplyAvailability.FINAL_TOPOLOGY_COLLINEAR_OVERLAP;
        }
        if (findings.contains("final-topology:CONTINUATION")) {
            return ModernSingleWayEditPlanAdapter.ApplyAvailability.FINAL_TOPOLOGY_CONTINUATION;
        }
        return ModernSingleWayEditPlanAdapter.ApplyAvailability.FINAL_GEOMETRY_BLOCKED;
    }

    /**
     * Serializes the existing per-interval production routes and their already composed preview.
     * The caller must pass the numeric receipt that accompanied acquisition; the detached batch
     * deliberately does not synthesize that external receipt. The preview and point provenance
     * geometry are private evidence. When the original capture authority and bounded shared
     * input are available, it also emits strict interval replay input. The full-selection
     * request in that input supplies lineage; inference is replayed only for the recorded
     * intervals. This entry point never grants {@code FULL_EDIT_PLAN} capability.
     *
     * <p>The live worker interface is: call this after production interval tracing and
     * {@link FixedIntervalEditPlanComposer#compose(IntervalTraceBatch, Map)} with the same batch
     * and route-choice map, then pass the actual safe source receipt, UI status, and the exact
     * composed plan identity shown at review/apply. Pass {@code null} identities before review
     * or apply. Receipt constructors accept only source kind, numeric generation/revision/zoom,
     * and a SHA-256 identity; owner objects and arbitrary strings are never serialized.</p>
     */
    public static Format15Bundle createLiveIntervals(String buildIdentity,
            IntervalTraceBatch batch, FixedIntervalEditPlanComposer.Assessment assessment,
            Map<Integer, Integer> routeChoices, IntervalSourceReceipt sourceReceipt,
            IntervalArtifactStatus status,
            String reviewedPlanIdentity, String appliedPlanIdentity) {
        if (buildIdentity == null || buildIdentity.isBlank() || batch == null || assessment == null
                || routeChoices == null || sourceReceipt == null || status == null
                || assessment.intervals().size() != batch.runs().size()) {
            throw new IllegalArgumentException("Interval production artifact inputs are incomplete");
        }
        Format15Safety.requireSafeExportedMetadata(buildIdentity);
        if (!batch.fullRequest().selectedWayKey().equals(batch.partition().selectedWayKey())
                || !batch.fullRequest().selectedRange().equals(batch.partition().selectedRange())
                || !batch.fullRequest().networkContentHash().equals(batch.network().canonicalHash())
                || !batch.fullRequest().evidenceContentHash().equals(batch.evidence().canonicalHash())) {
            throw new IllegalArgumentException("Interval batch source lineage is inconsistent");
        }
        if (routeChoices.keySet().stream().anyMatch(index -> index == null || index < 0
                    || index >= batch.runs().size())
                || routeChoices.values().stream().anyMatch(index -> index == null || index < 0)) {
            throw new IllegalArgumentException("Interval route choice is outside the batch");
        }

        AlignmentEditPlan plan = assessment.plan().orElse(null);
        String planIdentity = plan == null ? null : plan.canonicalHash();
        if (plan != null && (!plan.selectedWayKey().equals(batch.fullRequest().selectedWayKey())
                || !plan.selectedRange().equals(batch.fullRequest().selectedRange())
                || !plan.before().canonicalHash().equals(batch.network().canonicalHash())
                || !plan.evidenceHash().equals(batch.evidence().canonicalHash())
                || !plan.settingsHash().equals(batch.fullRequest().settingsHash())
                || !plan.parameterHash().equals(batch.fullRequest().parameterHash())
                || !plan.routeIdentity().equals(composedRouteIdentity(batch, assessment, routeChoices)))) {
            throw new IllegalArgumentException("Composed interval plan differs from its frozen source");
        }
        boolean identityStatusValid = switch (status) {
            case PRODUCED, PREVIEW, CANCELLED, RESOURCE_LIMIT, FAILED ->
                reviewedPlanIdentity == null && appliedPlanIdentity == null;
            case REVIEWED, CONFIRMED -> planIdentity != null
                    && planIdentity.equals(reviewedPlanIdentity) && appliedPlanIdentity == null;
            case APPLIED -> planIdentity != null && planIdentity.equals(appliedPlanIdentity)
                    && (reviewedPlanIdentity == null || planIdentity.equals(reviewedPlanIdentity));
            case APPLIED_AFTER_REVIEW -> planIdentity != null
                    && planIdentity.equals(reviewedPlanIdentity)
                    && planIdentity.equals(appliedPlanIdentity);
        };
        if (!identityStatusValid
                || reviewedPlanIdentity != null && !reviewedPlanIdentity.equals(planIdentity)
                || appliedPlanIdentity != null && !appliedPlanIdentity.equals(planIdentity)
                || appliedPlanIdentity != null && !assessment.applyAvailable()) {
            throw new IllegalArgumentException("Interval review or applied identity differs from the composed plan");
        }
        if (reviewedPlanIdentity != null) Format15Safety.requireSafeExportedMetadata(reviewedPlanIdentity);
        if (appliedPlanIdentity != null) Format15Safety.requireSafeExportedMetadata(appliedPlanIdentity);

        Map<PrimitiveKey, List<GeographicPoint>> previewWays = plan == null
                ? Map.of(batch.fullRequest().selectedWayKey(), assessment.selectedWayPreview())
                : plan.finalPreviewWays();
        String preview = geographicWays(previewWays);
        String previewHash = Format15Safety.sha256(preview.getBytes(StandardCharsets.UTF_8));
        byte[] frozenIntervals = null;
        String sharedInputHash = null;
        if (batch.authoritySpecification() != null) {
            try {
                var reproducedPartition = SelectedWayIntervalPartitioner.partition(
                        batch.network(), batch.authoritySpecification());
                if (FrozenReplayCodec.partitionProofHash(reproducedPartition).equals(
                        FrozenReplayCodec.partitionProofHash(batch.partition()))) {
                    var encoded = FrozenIntervalReplayCodec.encodeWithIdentity(batch, assessment,
                            routeChoices, previewHash);
                    frozenIntervals = encoded.bytes();
                    sharedInputHash = encoded.sharedInputHash();
                }
            } catch (IllegalArgumentException budgetOrUnsafeInput) {
                // The existing artifact remains useful, but cannot advertise executable replay.
            }
        }
        String receiptJson = intervalSourceReceiptJson(sourceReceipt);
        StringBuilder index = new StringBuilder("{\"schema\":1,\"artifactKind\":\"INTERVAL_PRODUCTION\"")
                .append(",\"sharedInputHash\":").append(sharedInputHash == null ? "null" : quote(sharedInputHash))
                .append(",\"sourceReceipt\":").append(receiptJson)
                .append(",\"networkHash\":").append(quote(batch.network().canonicalHash()))
                .append(",\"evidenceHash\":").append(quote(batch.evidence().canonicalHash()))
                .append(",\"networkSnapshotId\":").append(quote(batch.network().snapshotId()))
                .append(",\"evidenceSnapshotId\":").append(quote(batch.evidence().snapshotId()))
                .append(",\"parameterHash\":").append(quote(batch.fullRequest().parameterHash()))
                .append(",\"partitionProofHash\":")
                .append(quote(FrozenReplayCodec.partitionProofHash(batch.partition())))
                .append(",\"status\":").append(quote(status.name()))
                .append(",\"planIdentity\":").append(planIdentity == null ? "null" : quote(planIdentity))
                .append(",\"previewArtifact\":").append(quote(INTERVAL_PREVIEW_ARTIFACT))
                .append(",\"previewSha256\":").append(quote(previewHash))
                .append(",\"reviewedPlanIdentity\":")
                .append(reviewedPlanIdentity == null ? "null" : quote(reviewedPlanIdentity))
                .append(",\"appliedPlanIdentity\":")
                .append(appliedPlanIdentity == null ? "null" : quote(appliedPlanIdentity))
                .append(",\"applyAvailable\":").append(assessment.applyAvailable())
                .append(",\"privateData\":true,\"capabilities\":{\"INTERVAL_PRODUCTION_ARTIFACT\":true,\"STRICT_INTERVAL_PRODUCTION\":")
                .append(frozenIntervals != null)
                .append(",\"SCALAR_INFERENCE\":false,\"FINAL_GEOMETRY\":false,\"RASTER_INFERENCE\":false,\"FULL_EDIT_PLAN\":false}")
                .append(",\"intervals\":[");
        for (int i = 0; i < batch.runs().size(); i++) {
            if (i > 0) index.append(',');
            IntervalTraceBatch.IntervalRun run = batch.runs().get(i);
            FixedIntervalEditPlanComposer.IntervalAssessment result = assessment.intervals().get(i);
            int selectedRouteChoice = routeChoices.getOrDefault(i, 0);
            if (run.routes().isEmpty() && routeChoices.containsKey(i)
                    || !run.routes().isEmpty() && selectedRouteChoice >= run.routes().size()) {
                throw new IllegalArgumentException("Interval route choice is outside production alternatives");
            }
            int routeIndex = run.routes().isEmpty() ? -1 : selectedRouteChoice;
            String routeIdentity = routeIndex < 0 ? "unavailable"
                    : run.routes().get(routeIndex).hypothesis().id();
            if (result.intervalIndex() != i || result.routeIndex() < 0
                    || result.routeIndex() >= Math.max(1, run.routes().size())
                    || result.routeIdentity() == null) {
                throw new IllegalArgumentException("Composer assessment differs from the selected production route");
            }
            String assessmentIdentity = run.routes().isEmpty() ? "unavailable"
                    : run.routes().get(result.routeIndex()).hypothesis().id();
            if (!routeIdentity.equals(assessmentIdentity) || !routeIdentity.equals(result.routeIdentity())) {
                throw new IllegalArgumentException("Composer assessment differs from the selected production route");
            }
            Format15Safety.requireSafeExportedMetadata(routeIdentity);
            IntervalReason reason = intervalReason(result.reason());
            index.append("{\"intervalIndex\":").append(i)
                    .append(",\"occurrenceRange\":").append(rangeJson(run.interval().range()))
                    .append(",\"traceRange\":").append(rangeJson(run.interval().traceRange()))
                    .append(",\"chosenRouteIndex\":").append(routeIndex)
                    .append(",\"chosenRouteIdentity\":").append(quote(routeIdentity))
                    .append(",\"alternativesTruncated\":").append(run.alternativesTruncated())
                    .append(",\"disposition\":").append(quote(result.disposition().name()))
                    .append(",\"reason\":").append(quote(reason.name()))
                    .append(",\"alternatives\":[");
            for (int route = 0; route < run.routes().size(); route++) {
                if (route > 0) index.append(',');
                String id = run.routes().get(route).hypothesis().id();
                Format15Safety.requireSafeExportedMetadata(id);
                index.append("{\"index\":").append(route)
                        .append(",\"identity\":").append(quote(id)).append('}');
            }
            index.append("]}");
        }
        byte[] indexBytes = index.append("]}\n").toString().getBytes(StandardCharsets.UTF_8);
        String sourceHash = intervalSourceHash(batch.network().canonicalHash(),
                batch.evidence().canonicalHash(), sourceReceipt);
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>();
        artifacts.put(INTERVAL_INDEX_ARTIFACT,
                Format15Artifact.text(INTERVAL_INDEX_ARTIFACT,
                        new String(indexBytes, StandardCharsets.UTF_8)));
        artifacts.put(INTERVAL_PREVIEW_ARTIFACT,
                Format15Artifact.text(INTERVAL_PREVIEW_ARTIFACT, preview));
        artifacts.put(PLAN_AVAILABILITY_ARTIFACT,
                planAvailabilityArtifact(intervalPlanAvailability(assessment, status)));
        if (frozenIntervals != null) {
            artifacts.put(FrozenIntervalReplayCodec.ARTIFACT,
                    Format15Artifact.binary(FrozenIntervalReplayCodec.ARTIFACT, frozenIntervals));
        }
        if (plan != null && System.getProperty("wayheatmaptracer.benchmark.receipt") != null) {
            String name = "private/interval-frozen-edit-plan.bin";
            artifacts.put(name, Format15Artifact.binary(name, FrozenReplayCodec.encodeEditPlan(plan)));
        }
        artifacts.put("private/interval-point-provenance.json", Format15Artifact.text(
                "private/interval-point-provenance.json",
                intervalPointProvenanceJson(batch, assessment, plan, previewWays)));
        for (int i = 0; i < batch.runs().size(); i++) {
            String name = "private/interval-" + i + "-routes.json";
            artifacts.put(name, Format15Artifact.text(name,
                    intervalRoutesJson(batch, routeChoices, i)));
        }
        addIntervalOutputComponents(artifacts, buildIdentity, batch, frozenIntervals != null);
        return new Format15Bundle(buildIdentity, sourceHash, batch.fullRequest().parameterHash(), artifacts);
    }

    private static PlanAvailability intervalPlanAvailability(
            FixedIntervalEditPlanComposer.Assessment assessment, IntervalArtifactStatus status) {
        if (status == IntervalArtifactStatus.CANCELLED) return PlanAvailability.cancelled();
        if (status == IntervalArtifactStatus.FAILED) return PlanAvailability.failed();
        if (!assessment.applyAvailable()) {
            // The interval composer has no adapter ApplyAvailability enum. Keep the
            // exact per-interval findings in its index and report the generic fact here.
            return PlanAvailability.unavailable(
                    ModernSingleWayEditPlanAdapter.ApplyAvailability.PLAN_UNAVAILABLE);
        }
        AlignmentEditPlan plan = assessment.plan().orElseThrow();
        if (plan.validation().reviewRequired()) {
            boolean confirmed = status == IntervalArtifactStatus.CONFIRMED
                    || status == IntervalArtifactStatus.APPLIED_AFTER_REVIEW;
            return PlanAvailability.reviewRequired(confirmed,
                    ModernSingleWayEditPlanAdapter.ApplyAvailability.PLAN_AVAILABLE);
        }
        return PlanAvailability.available();
    }

    private static void addIntervalOutputComponents(Map<String, Format15Artifact> artifacts,
            String buildIdentity, IntervalTraceBatch batch, boolean strictReplayAvailable) {
        long existingBytes = artifacts.values().stream().mapToLong(Format15Artifact::sizeBytes).sum();
        String name = "private/interval-output-components-summary.json";
        // Malformed/private metadata remains an error even when optional detail cannot fit.
        for (var run : batch.runs()) {
            FinalOutputComponentsCodec.validateOutputMetadata(run.result().inference(), run.result().routes());
        }
        if (artifacts.size() >= Format15Safety.MAX_ARTIFACTS) return;
        String unavailable = "{\"schema\":1,\"status\":\"UNAVAILABLE_BUDGET\",\"intervals\":[]}\n";
        List<String> metrics = new ArrayList<>();
        try {
            ReplayOutputAdmission.Budget shared = new ReplayOutputAdmission.Budget(existingBytes);
            for (var run : batch.runs()) shared.output(run.result().inference(), run.result().routes());
            for (var run : batch.runs()) metrics.add(FinalOutputComponentsCodec.summaryJson(
                    FinalOutputComponentsCodec.qualitySummary(IntervalFinalOutputComponentsCodec.result(run, "0".repeat(64))))
                    .stripTrailing());
        } catch (ReplayOutputAdmission.BudgetExceeded budget) {
            appendOptionalSummary(artifacts, name, unavailable);
            return;
        }
        String quality = String.join(",", metrics);
        String budgetSummary = "{\"schema\":1,\"status\":\"UNAVAILABLE_BUDGET\",\"intervals\":[" + quality + "]}\n";
        int summaryBytes = typedSummaryBytes(budgetSummary);
        if (!optionalSummaryFits(artifacts, summaryBytes)) {
            appendOptionalSummary(artifacts, name, unavailable);
            return;
        }
        int maximum = optionalPayloadAllowance(artifacts, summaryBytes);
        IntervalFinalOutputComponentsCodec.Encoded encoded = strictReplayAvailable && maximum > 0
                ? IntervalFinalOutputComponentsCodec.encode(buildIdentity, batch, maximum, existingBytes + 4L * summaryBytes)
                : new IntervalFinalOutputComponentsCodec.Encoded(strictReplayAvailable
                        ? IntervalFinalOutputComponentsCodec.Availability.UNAVAILABLE_BUDGET
                        : IntervalFinalOutputComponentsCodec.Availability.UNAVAILABLE, null, List.of());
        if (encoded.availability() == IntervalFinalOutputComponentsCodec.Availability.AVAILABLE) {
            artifacts.put(IntervalFinalOutputComponentsCodec.ARTIFACT,
                    Format15Artifact.binary(IntervalFinalOutputComponentsCodec.ARTIFACT, encoded.bytes()));
        }
        appendOptionalSummary(artifacts, name, "{\"schema\":1,\"status\":" + quote(encoded.availability().name())
                + ",\"intervals\":[" + quality + "]}\n");
    }

    static IntervalReason intervalReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Interval assessment reason is missing");
        }
        String code = reason.split(":", 2)[0];
        try {
            return IntervalReason.valueOf(code);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Interval assessment reason is not a known typed reason", exception);
        }
    }

    static String intervalSourceHash(String networkHash, String evidenceHash,
            IntervalSourceReceipt receipt) {
        return Format15Safety.sha256(networkHash + ":" + evidenceHash + ":"
                + intervalSourceReceiptJson(receipt));
    }

    static String intervalSourceReceiptJson(IntervalSourceReceipt receipt) {
        if (receipt instanceof ManagedTileSourceReceipt managed) {
            return "{\"kind\":\"MANAGED_TILES\",\"generation\":" + managed.generation()
                    + ",\"zoom\":" + managed.zoom() + ",\"sourceIdentityHash\":"
                    + quote(managed.sourceIdentityHash()) + "}";
        }
        VisibleLayerSourceReceipt visible = (VisibleLayerSourceReceipt) receipt;
        return "{\"kind\":\"VISIBLE_RENDERED_LAYER\",\"revision\":"
                + (visible.revision() == null ? "null" : visible.revision())
                + ",\"zoom\":" + (visible.zoom() == null ? "null" : visible.zoom())
                + ",\"sourceIdentityHash\":" + (visible.sourceIdentityHash() == null
                        ? "null" : quote(visible.sourceIdentityHash())) + "}";
    }

    static String intervalRoutesJson(IntervalTraceBatch batch,
            Map<Integer, Integer> routeChoices, int intervalIndex) {
        IntervalTraceBatch.IntervalRun run = batch.runs().get(intervalIndex);
        int chosen = run.routes().isEmpty() ? -1 : routeChoices.getOrDefault(intervalIndex, 0);
        StringBuilder json = new StringBuilder("{\"schema\":1,\"intervalIndex\":")
                .append(intervalIndex).append(",\"occurrenceRange\":")
                .append(rangeJson(run.interval().range())).append(",\"traceRange\":")
                .append(rangeJson(run.interval().traceRange())).append(",\"alternatives\":[");
        for (int routeIndex = 0; routeIndex < run.routes().size(); routeIndex++) {
            if (routeIndex > 0) json.append(',');
            ModernTracePipeline.Route route = run.routes().get(routeIndex);
            String identity = route.hypothesis().id();
            Format15Safety.requireSafeExportedMetadata(identity);
            json.append("{\"index\":").append(routeIndex)
                    .append(",\"identity\":").append(quote(identity))
                    .append(",\"chosen\":").append(chosen == routeIndex)
                    .append(",\"points\":[");
            for (int pointIndex = 0; pointIndex < route.pointIds().size(); pointIndex++) {
                if (pointIndex > 0) json.append(',');
                FinalRoutePointId pointId = route.pointIds().get(pointIndex);
                MetricPoint point = route.assignments().get(pointId);
                if (point == null || !Double.isFinite(point.xMeters())
                        || !Double.isFinite(point.yMeters())) {
                    throw new IllegalArgumentException("Interval route point assignment is incomplete");
                }
                json.append("{\"pointId\":").append(pointIdentityJson(pointId, null, false))
                        .append(",\"xMeters\":").append(point.xMeters())
                        .append(",\"yMeters\":").append(point.yMeters()).append('}');
            }
            json.append("]}");
        }
        return json.append("]}\n").toString();
    }

    /** Emits a complete ordered point-to-interval map for every final preview way. */
    static String intervalPointProvenanceJson(IntervalTraceBatch batch,
            FixedIntervalEditPlanComposer.Assessment assessment, AlignmentEditPlan plan,
            Map<PrimitiveKey, List<GeographicPoint>> previewWays) {
        DetachedWay originalSelected = (DetachedWay) batch.network().primitives()
                .get(batch.fullRequest().selectedWayKey());
        Map<PrimitiveKey, FinalRoutePointId> idsByNode = new LinkedHashMap<>();
        for (int occurrence = 0; occurrence < originalSelected.nodeKeys().size(); occurrence++) {
            PrimitiveKey node = originalSelected.nodeKeys().get(occurrence);
            idsByNode.put(node, new FinalRoutePointId.ExistingWayNodeOccurrence(
                    originalSelected.key(), node, occurrence));
        }
        for (FinalRoutePointId id : assessment.assignments().keySet()) {
            if (id instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing) {
                idsByNode.put(existing.nodeKey(), existing);
            } else if (id instanceof FinalRoutePointId.GeneratedCandidatePoint generated) {
                idsByNode.put(PrimitiveKey.planned(PrimitiveKey.Type.NODE,
                        generated.originalPointIndex()), generated);
            }
        }
        Map<FinalRoutePointId, Integer> ownerByPoint = new LinkedHashMap<>();
        for (int intervalIndex = 0; intervalIndex < batch.runs().size(); intervalIndex++) {
            IntervalTraceBatch.IntervalRun run = batch.runs().get(intervalIndex);
            for (int occurrence = run.interval().range().firstIndex();
                    occurrence <= run.interval().range().lastIndex(); occurrence++) {
                PrimitiveKey node = originalSelected.nodeKeys().get(occurrence);
                ownerByPoint.put(new FinalRoutePointId.ExistingWayNodeOccurrence(
                        originalSelected.key(), node, occurrence), intervalIndex);
            }
            int routeIndex = run.routes().isEmpty() ? -1
                    : routeChoicesForAssessment(batch, assessment, intervalIndex);
            if (routeIndex < 0) continue;
            ModernTracePipeline.Route route = run.routes().get(routeIndex);
            for (FinalRoutePointId sourceId : route.pointIds()) {
                FinalRoutePointId composedId = composedPointId(intervalIndex, route, sourceId);
                if (composedId instanceof FinalRoutePointId.GeneratedCandidatePoint) {
                    ownerByPoint.put(composedId, intervalIndex);
                } else if (composedId instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing
                        && run.interval().range().firstIndex() <= existing.originalOccurrenceIndex()
                        && existing.originalOccurrenceIndex() <= run.interval().range().lastIndex()) {
                    ownerByPoint.putIfAbsent(composedId, intervalIndex);
                }
            }
        }

        NetworkSnapshot finalNetwork = plan == null ? batch.network() : plan.after();
        StringBuilder json = new StringBuilder("{\"schema\":1,\"coordinateSpace\":\"geographic-degrees\",\"points\":[");
        boolean first = true;
        for (PrimitiveKey wayKey : new java.util.TreeSet<>(previewWays.keySet())) {
            DetachedWay way = (DetachedWay) finalNetwork.primitives().get(wayKey);
            List<GeographicPoint> points = previewWays.get(wayKey);
            if (way == null || way.nodeKeys().size() != points.size()) {
                throw new IllegalArgumentException("Final preview provenance does not match its way sequence");
            }
            for (int sequence = 0; sequence < points.size(); sequence++) {
                if (!first) json.append(',');
                first = false;
                PrimitiveKey nodeKey = way.nodeKeys().get(sequence);
                FinalRoutePointId pointId = idsByNode.get(nodeKey);
                GeographicPoint coordinate = points.get(sequence);
                Integer owner = pointId == null ? null : ownerByPoint.get(pointId);
                if (pointId instanceof FinalRoutePointId.GeneratedCandidatePoint && owner == null) {
                    throw new IllegalArgumentException("Generated final point has no interval owner");
                }
                // Frozen-locus junction reattachment can retain the old receiver coordinate
                // as a plan-owned shape node. It is not a slide-interval point.
                boolean topologyShapeNode = pointId == null && plan != null
                        && isProvenFrozenReceiverShapeNode(batch, plan, wayKey, nodeKey);
                json.append("{\"sequence\":").append(sequence)
                        .append(",\"wayKey\":").append(quote(wayKey.toString()))
                        .append(",\"pointId\":").append(pointIdentityJson(pointId, nodeKey,
                                topologyShapeNode))
                        .append(",\"ownerInterval\":");
                json.append(owner == null ? "null" : owner.toString())
                        .append(",\"latitude\":").append(coordinate.latitudeDegrees())
                        .append(",\"longitude\":").append(coordinate.longitudeDegrees()).append('}');
            }
        }
        return json.append("]}\n").toString();
    }

    private static boolean isProvenFrozenReceiverShapeNode(IntervalTraceBatch batch,
            AlignmentEditPlan plan, PrimitiveKey receiverWayKey, PrimitiveKey shapeNodeKey) {
        if (receiverWayKey.equals(batch.fullRequest().selectedWayKey())
                || receiverWayKey.type() != PrimitiveKey.Type.WAY
                || shapeNodeKey.identityKind() != PrimitiveKey.IdentityKind.PLAN_LOCAL
                || plan.permissions().junctionPolicy() != JunctionPolicy.REATTACH
                || plan.permissions().reconstructIncidentWays()) {
            return false;
        }
        DetachedPrimitive created = plan.createdPrimitives().get(shapeNodeKey);
        if (!(created instanceof DetachedNode shape) || !shape.tags().isEmpty()
                || !plan.after().internalIncomingReferrers().getOrDefault(shapeNodeKey, Set.of())
                        .equals(Set.of(receiverWayKey))) {
            return false;
        }
        DetachedPrimitive originalReceiver = batch.network().primitives().get(receiverWayKey);
        if (!(originalReceiver instanceof DetachedWay)
                || !plan.finalPreviewWays().containsKey(receiverWayKey)) {
            return false;
        }
        DetachedWay selectedBefore = (DetachedWay) batch.network().primitives()
                .get(batch.fullRequest().selectedWayKey());
        DetachedWay selectedAfter = (DetachedWay) plan.after().primitives()
                .get(batch.fullRequest().selectedWayKey());
        Map<PrimitiveKey, Set<PrimitiveKey>> beforeReferrers = batch.network().internalIncomingReferrers();
        Map<PrimitiveKey, Set<PrimitiveKey>> afterReferrers = plan.after().internalIncomingReferrers();
        for (PrimitiveKey junction : selectedBefore.nodeKeys()) {
            Set<PrimitiveKey> oldRefs = beforeReferrers.getOrDefault(junction, Set.of());
            if (!oldRefs.contains(batch.fullRequest().selectedWayKey())
                    || !oldRefs.contains(receiverWayKey)) {
                continue;
            }
            DetachedPrimitive originalPrimitive = batch.network().primitives().get(junction);
            DetachedPrimitive proposedPrimitive = plan.after().primitives().get(junction);
            if (originalPrimitive instanceof DetachedNode original
                    && proposedPrimitive instanceof DetachedNode proposed
                    && !original.coordinate().equals(proposed.coordinate())
                    && original.coordinate().equals(shape.coordinate())
                    && selectedAfter.nodeKeys().contains(junction)
                    && afterReferrers.getOrDefault(junction, Set.of())
                            .contains(batch.fullRequest().selectedWayKey())
                    && afterReferrers.getOrDefault(junction, Set.of()).contains(receiverWayKey)) {
                return true;
            }
        }
        return false;
    }

    private static int routeChoicesForAssessment(IntervalTraceBatch batch,
            FixedIntervalEditPlanComposer.Assessment assessment, int intervalIndex) {
        FixedIntervalEditPlanComposer.IntervalAssessment result = assessment.intervals().get(intervalIndex);
        if (result.routeIndex() < 0 || result.routeIndex() >= batch.runs().get(intervalIndex).routes().size()) {
            throw new IllegalArgumentException("Interval provenance has no matching chosen route");
        }
        return result.routeIndex();
    }

    private static String composedRouteIdentity(IntervalTraceBatch batch,
            FixedIntervalEditPlanComposer.Assessment assessment, Map<Integer, Integer> routeChoices) {
        String parts = java.util.stream.IntStream.range(0, batch.runs().size())
                .mapToObj(index -> {
                    FixedIntervalEditPlanComposer.IntervalAssessment interval =
                            assessment.intervals().get(index);
                    boolean frozen = interval.disposition()
                            == FixedIntervalEditPlanComposer.Disposition.FROZEN_LOCAL_FAILURE
                            || interval.disposition() == FixedIntervalEditPlanComposer.Disposition.UNCHANGED_NOOP
                            || batch.runs().get(index).routes().isEmpty();
                    int choice = routeChoices.getOrDefault(index, 0);
                    if (!batch.runs().get(index).routes().isEmpty()
                            && (choice < 0 || choice >= batch.runs().get(index).routes().size())) {
                        throw new IllegalArgumentException("Interval route choice is outside production alternatives");
                    }
                    String identity = frozen ? "frozen"
                            : batch.runs().get(index).routes()
                                    .get(choice).hypothesis().id();
                    return index + "=" + identity;
                }).collect(java.util.stream.Collectors.joining(";"));
        return "interval-composite:" + parts;
    }

    private static FinalRoutePointId composedPointId(int intervalIndex,
            ModernTracePipeline.Route route, FinalRoutePointId sourceId) {
        if (sourceId instanceof FinalRoutePointId.GeneratedCandidatePoint generated) {
            if (generated.originalPointIndex() >= 1_000_000) {
                throw new IllegalArgumentException("Interval generated point budget exceeded");
            }
            int pointIndex = Math.addExact(Math.multiplyExact(intervalIndex, 1_000_000),
                    generated.originalPointIndex());
            return new FinalRoutePointId.GeneratedCandidatePoint("interval-" + intervalIndex
                    + ":" + route.hypothesis().id(), pointIndex);
        }
        return sourceId;
    }

    private static String pointIdentityJson(FinalRoutePointId id, PrimitiveKey fallbackNode,
            boolean topologyShapeNode) {
        if (id instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing) {
            return "{\"kind\":\"EXISTING_WAY_NODE_OCCURRENCE\",\"wayKey\":"
                    + quote(existing.wayKey().toString()) + ",\"nodeKey\":"
                    + quote(existing.nodeKey().toString()) + ",\"occurrenceIndex\":"
                    + existing.originalOccurrenceIndex() + "}";
        }
        if (id instanceof FinalRoutePointId.GeneratedCandidatePoint generated) {
            Format15Safety.requireSafeExportedMetadata(generated.candidateId());
            return "{\"kind\":\"GENERATED_CANDIDATE_POINT\",\"candidateId\":"
                    + quote(generated.candidateId()) + ",\"originalPointIndex\":"
                    + generated.originalPointIndex() + "}";
        }
        if (fallbackNode != null && fallbackNode.identityKind() == PrimitiveKey.IdentityKind.OSM_UNIQUE) {
            return "{\"kind\":\"EXISTING_NODE\",\"nodeKey\":"
                    + quote(fallbackNode.toString()) + "}";
        }
        if (topologyShapeNode) {
            return "{\"kind\":\"PLAN_LOCAL_TOPOLOGY_SHAPE_NODE\",\"nodeKey\":"
                    + quote(fallbackNode.toString()) + "}";
        }
        throw new IllegalArgumentException("Final preview point has no candidate-owned identity");
    }

    private static String rangeJson(OccurrenceRange range) {
        return "{\"first\":" + range.firstIndex() + ",\"last\":" + range.lastIndex() + "}";
    }

    private static String performanceCounters(ModernTracePipeline.Result actual,
            Map<String, Number> counters) {
        if (counters == null || counters.size() > 128) {
            throw new IllegalArgumentException("Modern counter inventory exceeds budget");
        }
        StringBuilder json = new StringBuilder("{\"evaluatedStates\":")
            .append(actual.inference().evaluatedStates())
            .append(",\"evaluatedTransitions\":")
            .append(actual.inference().evaluatedTransitions())
            .append(",\"routes\":").append(actual.routes().size())
            .append(",\"hypotheses\":").append(actual.inference().hypotheses().size())
            .append(",\"inferenceStatus\":").append(quote(actual.inference().status().name()))
            .append(",\"alternativesTruncated\":").append(actual.inference().alternativesTruncated())
            .append(",\"logicalWorkAvailability\":\"AVAILABLE\"")
            .append(",\"workerCountersAvailable\":")
            .append(!counters.isEmpty())
            .append(",\"physicalCounters\":{\"inference.extensionDescriptors\":")
            .append(physicalCounterJson(counters, "inference.extensionDescriptors"))
            .append(",\"inference.ancestryRecordsAllocated\":")
            .append(physicalCounterJson(counters, "inference.ancestryRecordsAllocated"))
            .append('}')
            .append(",\"counters\":{");
        boolean first = true;
        for (Map.Entry<String, Number> counter : new java.util.TreeMap<>(counters).entrySet()) {
            String name = counter.getKey();
            Number value = counter.getValue();
            if (name == null || !name.matches("[A-Za-z][A-Za-z0-9.]{0,80}")
                    || value == null || !Double.isFinite(value.doubleValue())) {
                throw new IllegalArgumentException("Modern counter value is invalid");
            }
            if (!first) json.append(',');
            first = false;
            json.append(quote(name)).append(':').append(value);
        }
        return json.append("}}\n").toString();
    }

    private static String physicalCounterJson(Map<String, Number> counters, String name) {
        Number value = counters.get(name);
        if (value == null) return "{\"availability\":\"UNAVAILABLE\"}";
        if (!Double.isFinite(value.doubleValue())) {
            throw new IllegalArgumentException("Modern physical counter value is invalid");
        }
        return "{\"availability\":\"AVAILABLE\",\"value\":" + value + "}";
    }

    /** Emits a truthful terminal attempt even when capture failed before detached input existed. */
    public static Format15Bundle createUnavailableLive(String buildIdentity, String status,
            String sourceLineage, String attemptIdentity) {
        return createUnavailableLive(buildIdentity, status, sourceLineage, attemptIdentity, null);
    }

    /** Adds a bounded manual-junction reason when capture fails before detached input exists. */
    public static Format15Bundle createUnavailableLive(String buildIdentity, String status,
            String sourceLineage, String attemptIdentity,
            ManualJunctionEligibility.Reason manualJunctionReason) {
        return createUnavailableLiveWithPlanAvailability(buildIdentity, status, sourceLineage,
                attemptIdentity, manualJunctionReason, inferUnavailablePlanAvailability(status));
    }

    /** Emits an unavailable terminal attempt with a caller-supplied typed terminal reason. */
    public static Format15Bundle createUnavailableLiveWithPlanAvailability(String buildIdentity,
            String status, String sourceLineage, String attemptIdentity,
            PlanAvailability planAvailability) {
        return createUnavailableLiveWithPlanAvailability(buildIdentity, status, sourceLineage,
                attemptIdentity, null, planAvailability);
    }

    /** Adds a bounded manual-junction reason and typed plan disposition before detached input exists. */
    public static Format15Bundle createUnavailableLiveWithPlanAvailability(String buildIdentity,
            String status, String sourceLineage, String attemptIdentity,
            ManualJunctionEligibility.Reason manualJunctionReason,
            PlanAvailability planAvailability) {
        if (status == null || status.isBlank() || sourceLineage == null
                || sourceLineage.isBlank() || attemptIdentity == null || attemptIdentity.isBlank()
                || planAvailability == null) {
            throw new IllegalArgumentException("Unavailable attempt metadata is incomplete");
        }
        PlanAvailability expectedAvailability = inferUnavailablePlanAvailability(status);
        if (!expectedAvailability.equals(planAvailability)) {
            throw new IllegalArgumentException("Unavailable attempt status differs from plan disposition");
        }
        Format15Safety.requireSafeExportedMetadata(sourceLineage);
        Format15Safety.requireSafeExportedMetadata(attemptIdentity);
        Format15Safety.requireSafeExportedMetadata(status);
        String identity = Format15Safety.sha256(attemptIdentity);
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>();
        artifacts.put("attempt-status.json", Format15Artifact.text("attempt-status.json",
            "{\"status\":" + quote(status) + ",\"sourceLineage\":"
                + quote(sourceLineage) + ",\"attemptIdentityHash\":" + quote(identity)
                + ",\"manualJunctionReason\":" + (manualJunctionReason == null
                    ? "null" : quote(manualJunctionReason.name()))
                + ",\"privateData\":true,\"capabilities\":{\"SCALAR_INFERENCE\":false,"
                + "\"FINAL_GEOMETRY\":false,\"RASTER_INFERENCE\":false,"
                + "\"FULL_EDIT_PLAN\":false}}\n"));
        artifacts.put(PLAN_AVAILABILITY_ARTIFACT, planAvailabilityArtifact(planAvailability));
        return new Format15Bundle(buildIdentity, identity, identity, artifacts);
    }

    private static PlanAvailability inferUnavailablePlanAvailability(String status) {
        if ("cancelled".equalsIgnoreCase(status)) return PlanAvailability.cancelled();
        if ("failed".equalsIgnoreCase(status)) return PlanAvailability.failed();
        return PlanAvailability.unavailable(
                ModernSingleWayEditPlanAdapter.ApplyAvailability.PLAN_UNAVAILABLE);
    }

    private static String originalGeometry(NetworkSnapshot network, PrimitiveKey key,
            int first, int last) {
        DetachedWay way = (DetachedWay) network.primitives().get(key);
        if (way == null || last >= way.nodeKeys().size()) {
            throw new IllegalArgumentException("Selected original geometry is unavailable");
        }
        StringBuilder json = new StringBuilder("{\"coordinateSpace\":\"geographic-degrees\",\"points\":[");
        for (int index = first; index <= last; index++) {
            if (index > first) json.append(',');
            DetachedNode node = (DetachedNode) network.primitives().get(way.nodeKeys().get(index));
            GeographicPoint point = node.coordinate();
            json.append('[').append(point.latitudeDegrees()).append(',')
                .append(point.longitudeDegrees()).append(']');
        }
        return json.append("]}\n").toString();
    }

    private static String metricGeometry(List<MetricPoint> points) {
        StringBuilder json = new StringBuilder("{\"coordinateSpace\":\"local-meters\",\"points\":[");
        for (int index = 0; index < points.size(); index++) {
            if (index > 0) json.append(',');
            MetricPoint point = points.get(index);
            json.append('[').append(point.xMeters()).append(',').append(point.yMeters()).append(']');
        }
        return json.append("]}\n").toString();
    }

    static String geographicWays(Map<PrimitiveKey, List<GeographicPoint>> ways) {
        StringBuilder json = new StringBuilder("{\"coordinateSpace\":\"geographic-degrees\",\"ways\":{");
        boolean firstWay = true;
        for (PrimitiveKey key : new java.util.TreeSet<>(ways.keySet())) {
            if (!firstWay) json.append(',');
            firstWay = false;
            json.append(quote(key.toString())).append(':').append('[');
            List<GeographicPoint> points = ways.get(key);
            for (int index = 0; index < points.size(); index++) {
                if (index > 0) json.append(',');
                GeographicPoint point = points.get(index);
                json.append('[').append(point.latitudeDegrees()).append(',')
                    .append(point.longitudeDegrees()).append(']');
            }
            json.append(']');
        }
        return json.append("}}\n").toString();
    }

    /** Creates a frozen bundle that records one actual scalar production result. */
    public static Format15Bundle createWithExpectedScalarOutput(String buildIdentity,
            FrozenReplayInput input, TrackerMode requestedEngine) {
        ScalarReplayExpectation.requireBuildIdentityFitsEnvelope(buildIdentity);
        Format15Bundle base = create(buildIdentity, input);
        Format15ReplayRunner.Result actual = Format15ReplayRunner.replay(input,
            ReplayLevel.SCALAR_INFERENCE, requestedEngine);
        ScalarReplayExpectation expectation = ScalarReplayExpectation.capture(
            base.buildIdentity(), input, actual);
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>(base.artifacts());
        artifacts.put(ScalarReplayExpectation.ARTIFACT_NAME, Format15Artifact.binary(
            ScalarReplayExpectation.ARTIFACT_NAME, expectation.bytes()));
        return withCurrentNumericalPolicy(new Format15Bundle(base.buildIdentity(), base.sourceIdentityHash(),
            base.parameterHash(), artifacts), input, requestedEngine);
    }

    /** Creates a frozen bundle that records one actual final-geometry production result. */
    public static Format15Bundle createWithExpectedFinalOutput(String buildIdentity,
            FrozenReplayInput input, TrackerMode requestedEngine) {
        ScalarReplayExpectation.requireBuildIdentityFitsEnvelope(buildIdentity);
        Format15Bundle base = create(buildIdentity, input);
        Format15ReplayRunner.Result actual = Format15ReplayRunner.replay(input,
            ReplayLevel.FINAL_GEOMETRY, requestedEngine);
        FinalReplayExpectation expectation = FinalReplayExpectation.capture(
            base.buildIdentity(), input, actual);
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>(base.artifacts());
        artifacts.put(FinalReplayExpectation.ARTIFACT_NAME, Format15Artifact.binary(
            FinalReplayExpectation.ARTIFACT_NAME, expectation.bytes()));
        addFinalOutputComponents(artifacts, expectation, actual);
        return withCurrentNumericalPolicy(new Format15Bundle(base.buildIdentity(), base.sourceIdentityHash(),
            base.parameterHash(), artifacts), input, requestedEngine);
    }

    private static void addFinalOutputComponents(Map<String, Format15Artifact> artifacts,
            FinalReplayExpectation expectation, Format15ReplayRunner.Result actual) {
        FinalOutputComponentsCodec.validate(expectation.componentsBinding(), actual);
        long existingBytes = artifacts.values().stream().mapToLong(Format15Artifact::sizeBytes).sum();
        String name = "private/final-output-components-summary.json";
        if (artifacts.size() >= Format15Safety.MAX_ARTIFACTS) return;
        String quality = FinalOutputComponentsCodec.summaryJson(FinalOutputComponentsCodec.qualitySummary(actual)).stripTrailing();
        String budgetSummary = "{\"status\":\"UNAVAILABLE_BUDGET\",\"quality\":" + quality + "}\n";
        int summaryBytes = typedSummaryBytes(budgetSummary);
        if (!optionalSummaryFits(artifacts, summaryBytes)) {
            appendOptionalSummary(artifacts, name, "{\"status\":\"UNAVAILABLE_BUDGET\"}\n");
            return;
        }
        int maximum = optionalPayloadAllowance(artifacts, summaryBytes);
        FinalOutputComponentsCodec.Encoded encoded = maximum > 0
                ? FinalOutputComponentsCodec.encode(expectation.componentsBinding(), actual,
                        maximum, existingBytes + 4L * summaryBytes)
                : new FinalOutputComponentsCodec.Encoded(FinalOutputComponentsCodec.Availability.UNAVAILABLE_BUDGET,
                        null, Map.of(), FinalOutputComponentsCodec.qualitySummary(actual));
        if (encoded.availability() == FinalOutputComponentsCodec.Availability.AVAILABLE) {
            artifacts.put(FinalOutputComponentsCodec.ARTIFACT,
                    Format15Artifact.binary(FinalOutputComponentsCodec.ARTIFACT, encoded.bytes()));
        }
        appendOptionalSummary(artifacts, name, "{\"status\":" + quote(encoded.availability().name())
                + ",\"quality\":" + quality + "}\n");
    }

    private static int optionalPayloadAllowance(Map<String, Format15Artifact> artifacts, int reservedSummaryBytes) {
        long existing = artifacts.values().stream().mapToLong(Format15Artifact::sizeBytes).sum();
        return optionalPayloadAllowance(artifacts.size(), existing, reservedSummaryBytes);
    }

    static int optionalPayloadAllowance(int mandatoryCount, long mandatoryBytes, int reservedSummaryBytes) {
        if (mandatoryCount < 0 || mandatoryCount > Format15Safety.MAX_ARTIFACTS || mandatoryBytes < 0
                || mandatoryBytes > Format15Safety.MAX_TOTAL_BYTES || reservedSummaryBytes < 0) {
            throw new IllegalArgumentException("optional-budget-invalid");
        }
        if (mandatoryCount + 2 > Format15Safety.MAX_ARTIFACTS) return 0;
        return (int) Math.max(0, Math.min(Format15Safety.MAX_ARTIFACT_BYTES,
                Format15Safety.MAX_TOTAL_BYTES - mandatoryBytes - reservedSummaryBytes));
    }

    private static boolean optionalSummaryFits(Map<String, Format15Artifact> artifacts, int bytes) {
        long existing = artifacts.values().stream().mapToLong(Format15Artifact::sizeBytes).sum();
        return artifacts.size() < Format15Safety.MAX_ARTIFACTS && bytes <= Format15Safety.MAX_ARTIFACT_BYTES
                && existing + 4L * bytes <= Format15Safety.MAX_TOTAL_BYTES;
    }

    private static void appendOptionalSummary(Map<String, Format15Artifact> artifacts, String name, String text) {
        if (optionalSummaryFits(artifacts, typedSummaryBytes(text))) artifacts.put(name, Format15Artifact.text(name, text));
    }

    private static int typedSummaryBytes(String text) {
        // This projection contains only fixed keys, enum names and numeric literals.
        // Size it without allocating UTF-8 copies before optional admission.
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) > 0x7f) throw new IllegalArgumentException("typed-summary-invalid");
        }
        return text.length();
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') {
                result.append('\\');
            }
            if (character < 0x20) {
                result.append(String.format("\\u%04x", (int) character));
            } else {
                result.append(character);
            }
        }
        return result.append('"').toString();
    }
}
