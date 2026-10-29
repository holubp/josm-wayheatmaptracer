package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.ExistingWayNodeOccurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBPipeline.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyPreciseGeometry.ProjectedPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyTopologyPlanner.Occurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticTraceEngine;

/** Actual frozen-source Legacy then native B execution, without dataset or viewport mutation. */
public final class LegacyBProductionRunner {
    /** Source indexes are local to these curves; frozen ranges use absolute selected-way occurrences. */
    public record Input(TraceRequest request, NetworkSnapshot network, EvidenceSnapshot evidence, String fieldName,
            List<ProjectedPoint> originalProjected, List<MetricPoint> originalMetric, String projectionCode,
            List<Occurrence> occurrences, List<OccurrenceRange> frozenRanges, boolean simplify,
            double projectedTolerance) {
        public Input {
            new LegacyBAssessor.AssessmentContext(request, network, evidence, fieldName, List.of());
            originalProjected = List.copyOf(originalProjected); originalMetric = List.copyOf(originalMetric);
            occurrences = List.copyOf(occurrences);
            if (projectionCode == null || !Double.isFinite(projectedTolerance) || projectedTolerance < 0
                    || originalProjected.size() != originalMetric.size() || occurrences.size() != originalMetric.size()
                    || originalMetric.size() != request.selectedRange().lastIndex() - request.selectedRange().firstIndex() + 1) {
                throw new IllegalArgumentException("Complete original curves and occurrence facts are required");
            }
            frozenRanges = normalizedFrozen(frozenRanges, request.selectedRange());
            var projection = Projections.getProjectionByCode(projectionCode);
            if (projection == null || !(network.primitives().get(request.selectedWayKey()) instanceof DetachedWay way)
                    || way.deleted() || request.selectedRange().lastIndex() >= way.nodeKeys().size()) {
                throw new IllegalArgumentException("Captured original source or projection is unavailable");
            }
            var seen = new HashSet<PrimitiveKey>();
            for (int local = 0; local < originalMetric.size(); local++) {
                int absolute = request.selectedRange().firstIndex() + local;
                var key = way.nodeKeys().get(absolute);
                if (!seen.add(key) || Collections.frequency(way.nodeKeys(), key) != 1
                        || !(network.primitives().get(key) instanceof DetachedNode node) || node.deleted()
                        || !originalMetric.get(local).equals(evidence.coordinateFrame().toMetric(node.coordinate()))
                        || occurrences.get(local).sourceIndex() != local) {
                    throw new IllegalArgumentException("Original occurrence geometry differs from captured authority");
                }
                var projected = projection.latlon2eastNorth(new LatLon(node.coordinate().latitudeDegrees(),
                        node.coordinate().longitudeDegrees()));
                if (projected.distance(new EastNorth(originalProjected.get(local).east(),
                        originalProjected.get(local).north())) > 1e-7) {
                    throw new IllegalArgumentException("Original projected geometry differs from captured authority");
                }
                boolean fixed = network.closure().protectedExistingNodeKeys().contains(key) || !node.tags().isEmpty();
                boolean topology = local == 0 || local == originalMetric.size() - 1 || !node.tags().isEmpty()
                        || network.incomingReferrerWatches().getOrDefault(key, Set.of()).stream()
                                .anyMatch(referrer -> !referrer.equals(way.key()));
                if (fixed && !occurrences.get(local).fixed() || topology && !occurrences.get(local).topologyAnchor()) {
                    throw new IllegalArgumentException("Captured occurrence policy omitted original protection");
                }
            }
        }
    }

    public enum Rejection { PROTECTED_OCCURRENCE_LOST, UNREPRESENTABLE_ASSIGNMENT }

    /** Immutable copies of native Legacy output, including alternatives omitted by the parent cap. */
    public record LegacyAlternative(String id, String detector, String nativeId, int nativeRank, double score,
            List<RasterPoint> rasterPoints, List<Double> offsetsPx, CandidateEvidence evidence,
            List<String> safetyWarnings, List<ProjectedPoint> rawProjected, List<ProjectedPoint> finalProjected,
            Optional<Seed> seed, Optional<Rejection> rejection) {
        public LegacyAlternative {
            rasterPoints = List.copyOf(rasterPoints); offsetsPx = List.copyOf(offsetsPx);
            safetyWarnings = List.copyOf(safetyWarnings); rawProjected = List.copyOf(rawProjected);
            finalProjected = List.copyOf(finalProjected); Objects.requireNonNull(seed); Objects.requireNonNull(rejection);
        }
    }

    /** Native hypotheses are never rewritten when local quality excludes them from assembly. */
    public record SectionQuality(String sectionId, String hypothesisId, FinalGeometryEvaluator.Result quality,
            boolean eligible, Optional<Boolean> editAuthority) {
        public SectionQuality {
            Objects.requireNonNull(editAuthority);
            if (eligible && editAuthority.isPresent() && !editAuthority.orElseThrow())
                throw new IllegalArgumentException("An unauthorized refinement cannot be eligible");
        }
    }

    /** Separate deterministic geometry-work ceiling; these units are never counted as B solver scores. */
    private static final class AuthorityWork {
        private static final long LIMIT = 8_000_000;
        private long used;
        private boolean contactProofLimited;
        private org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion.YMembershipIndex index;
        private void charge(long units) {
            if (units < 0) throw new IllegalArgumentException("Negative geometry work charge");
            if (units > LIMIT - used) throw new AuthorityWorkLimit();
            used += units;
        }
    }
    private static final class AuthorityWorkLimit extends RuntimeException { }

    private static boolean insideEditAuthority(Input input, List<MetricPoint> points,
            CancellationProbe cancellation, AttemptMemoryLedger.Owner owner, AuthorityWork work) {
        var region = input.network().closure().editRegion();
        if (work.index == null) work.index = region.prepareYMembership(cancellation, work::charge, owner);
        for (int index = 1; index < points.size(); index++) {
            cancellation.checkpoint();
            if (!region.containsSegmentWithBoundedEvents(points.get(index - 1), points.get(index),
                    cancellation, work::charge, owner, work.index)) return false;
        }
        return true;
    }

    public record Result(String detector, int profileCount, List<LegacyAlternative> alternatives,
            List<Seed> seeds, Batch batch, List<LegacyBAssessor.AssessedCandidate> assessments,
            FinalGeometryEvaluator.Result originalQuality, List<SectionQuality> sectionQualities,
            TraceBudgets refinementBudget, boolean bInferenceAllowed, Reason unavailableReason,
            boolean contactProofLimited) {
        public Result {
            alternatives = List.copyOf(alternatives); seeds = List.copyOf(seeds);
            assessments = List.copyOf(assessments); sectionQualities = List.copyOf(sectionQualities);
            Objects.requireNonNull(refinementBudget); Objects.requireNonNull(unavailableReason);
        }
    }

    /** Actual native Legacy discovery, before any B invocation or global parent admission. */
    public record Discovery(Input input, String detector, int profileCount, List<LegacyAlternative> alternatives,
            List<Seed> seeds, List<Section> sections) {
        public Discovery {
            Objects.requireNonNull(input); Objects.requireNonNull(detector);
            if (profileCount < 0) throw new IllegalArgumentException("Negative native profile count");
            alternatives = List.copyOf(alternatives); seeds = List.copyOf(seeds); sections = List.copyOf(sections);
        }
    }

    public Accounted<Result> runVisible(Input input, LegacyVisibleProfileSampler.Input source,
            CancellationProbe cancellation, AttemptMemoryLedger.Owner owner, Deadline deadline) {
        return runVisible(input, source, input.request().budgets(), cancellation, owner, deadline);
    }

    /** Available work is caller-owned remaining work across source choices, never a fresh enlarged budget. */
    public Accounted<Result> runVisible(Input input, LegacyVisibleProfileSampler.Input source, TraceBudgets available,
            CancellationProbe cancellation, AttemptMemoryLedger.Owner owner, Deadline deadline) {
        requireBudget(input.request().budgets(), available);
        var discovered = discoverVisible(input, source, cancellation, owner, deadline);
        return refine(discovered, discovered.value().seeds().stream().limit(2).map(Seed::id).toList(),
                available, cancellation, owner, deadline);
    }

    public Accounted<Discovery> discoverVisible(Input input, LegacyVisibleProfileSampler.Input source,
            CancellationProbe cancellation, AttemptMemoryLedger.Owner owner, Deadline deadline) {
        requireDetector(source.detectors());
        if (!source.anchors().stream().map(LegacyVisibleProfileSampler.Anchor::source).toList().equals(input.originalProjected())
                || !source.raster().projectionCode().equals(input.projectionCode())
                || !source.raster().sourceIdentity().equals(input.evidence().sourceIdentity())) {
            throw new IllegalArgumentException("Frozen visible sampler does not own the captured original source");
        }
        var sampler = new LegacyVisibleProfileSampler();
        long pixels = Math.multiplyExact((long) source.raster().width(), source.raster().height());
        long samples = 4L * source.options().halfWidthPx() * 6 / source.options().stepPx() + 4;
        return discover(input, source.detectors().get(0), source.options().trackerNormalizationRasterPx(),
                () -> sampler.sample(source, deadline.probe(cancellation)).get(0).profiles(),
                points -> sampler.project(source, points), nativeWorkingBytes(pixels, source.anchors().size(), samples),
                cancellation, owner, deadline);
    }

    public Accounted<Result> runManaged(Input input, LegacyManagedProfileSampler.Input source,
            CancellationProbe cancellation, AttemptMemoryLedger.Owner owner, Deadline deadline) {
        return runManaged(input, source, input.request().budgets(), cancellation, owner, deadline);
    }

    public Accounted<Result> runManaged(Input input, LegacyManagedProfileSampler.Input source, TraceBudgets available,
            CancellationProbe cancellation, AttemptMemoryLedger.Owner owner, Deadline deadline) {
        requireBudget(input.request().budgets(), available);
        var discovered = discoverManaged(input, source, cancellation, owner, deadline);
        return refine(discovered, discovered.value().seeds().stream().limit(2).map(Seed::id).toList(),
                available, cancellation, owner, deadline);
    }

    public Accounted<Discovery> discoverManaged(Input input, LegacyManagedProfileSampler.Input source,
            CancellationProbe cancellation, AttemptMemoryLedger.Owner owner, Deadline deadline) {
        requireDetector(source.detectors());
        if (!source.sourcePolyline().equals(input.originalProjected()) || !source.projectionCode().equals(input.projectionCode())) {
            throw new IllegalArgumentException("Frozen managed sampler does not own the captured original source");
        }
        var sampler = new LegacyManagedProfileSampler();
        long pixels = 0;
        for (var mosaic : source.mosaics().values()) pixels = Math.addExact(pixels,
                Math.multiplyExact((long) mosaic.width(), mosaic.height()));
        double length = 0;
        for (int i = 1; i < input.originalMetric().size(); i++)
            length += input.originalMetric().get(i - 1).distanceTo(input.originalMetric().get(i));
        long profiles = Math.max(2, (long) Math.ceil(length / source.inferenceParameters().sampleStepMeters()) + 2);
        long samples = 4L * source.inferenceParameters().halfWidthPx() * 6
                / source.inferenceParameters().stepPx() + 4;
        return discover(input, source.detectors().get(0), source.trackerNormalizationRasterPx(),
                () -> sampler.sample(source, deadline.probe(cancellation)).get(0).profiles(),
                points -> sampler.project(source, source.detectors().get(0), points),
                nativeWorkingBytes(pixels, profiles, samples), cancellation, owner, deadline);
    }

    private Accounted<Discovery> discover(Input input, String detector, double normalization,
            Supplier<List<RenderedHeatmapSampler.CrossSectionProfile>> sample,
            Function<List<RasterPoint>,List<ProjectedPoint>> project, long workingBytes,
            CancellationProbe cancellation, AttemptMemoryLedger.Owner attemptOwner, Deadline deadline) {
        cancellation.checkpoint();
        var resultOwner = attemptOwner.child("legacy-discovery-result");
        var legacyOwner = resultOwner.child("legacy-sampling-and-tracking");
        try {
            List<RenderedHeatmapSampler.CrossSectionProfile> profiles;
            try (var reservation = legacyOwner.reserve(workingBytes)) {
                profiles = sample.get(); reservation.adopt(profiles);
            }
            var adapter = new LegacySeedAdapter();
            var nativeAlternatives = adapter.track(profiles, normalization, deadline.probe(cancellation));
            var alternatives = new ArrayList<LegacyAlternative>();
            var seeds = new ArrayList<Seed>();
            var sections = new ArrayList<Section>();
            var facts = effectiveOccurrences(input);
            var fixed = facts.stream().filter(Occurrence::fixed).map(Occurrence::sourceIndex).toList();
            var cuts = new TreeSet<Integer>();
            for (var fact : facts) if (fact.fixed() || fact.topologyAnchor())
                cuts.add(input.request().selectedRange().firstIndex() + fact.sourceIndex());
            for (int rank = 0; rank < nativeAlternatives.size(); rank++) {
                cancellation.checkpoint();
                var nativeAlternative = nativeAlternatives.get(rank);
                var raster = nativeAlternative.screenPoints().stream().map(p -> new RasterPoint(p.x, p.y)).toList();
                var projected = project.apply(raster);
                String id = "legacy/" + detector + "/" + rank + "/" + nativeAlternative.id();
                Optional<Seed> seed = Optional.empty(); Optional<Rejection> rejection = Optional.empty();
                List<ProjectedPoint> finalProjected = List.of();
                try {
                    var finished = LegacyPreciseGeometry.finish(input.originalProjected(), projected, fixed,
                            input.simplify(), input.projectedTolerance());
                    var frozen = restoreFrozen(input, finished);
                    finalProjected = LegacyTopologyPlanner.constrain(input.originalProjected(), frozen, facts);
                    var assignments = LegacyTopologyPlanner.preciseAssignments(input.originalProjected(), finalProjected, facts);
                    var mapping = exactMapping(assignments, finalProjected, input.request().selectedRange().firstIndex());
                    var metric = metricPoints(input, finalProjected, assignments, mapping);
                    seed = Optional.of(new Seed(id, metric, mapping));
                    seeds.add(seed.orElseThrow());
                    sections.addAll(new LegacyBReferenceFactory().partition(seed.orElseThrow(),
                            input.request().selectedRange(), cuts, input.frozenRanges()));
                } catch (LegacyPreciseGeometry.ProtectedOccurrenceLostException missing) {
                    rejection = Optional.of(Rejection.PROTECTED_OCCURRENCE_LOST);
                } catch (UnrepresentableAssignment missing) {
                    rejection = Optional.of(Rejection.UNREPRESENTABLE_ASSIGNMENT);
                }
                var alternative = new LegacyAlternative(id, detector, nativeAlternative.id(), rank,
                        nativeAlternative.score(), raster, nativeAlternative.offsetsPx(), nativeAlternative.evidence(),
                        nativeAlternative.safetyWarnings(), projected, finalProjected, seed, rejection);
                own(resultOwner, alternative, 512L + 192L * (raster.size() + finalProjected.size()));
                alternatives.add(alternative);
            }
            int profileCount = profiles.size();
            legacyOwner.close();
            var discovery = new Discovery(input, detector, profileCount, alternatives, seeds, sections);
            own(resultOwner, discovery, 1024L + 128L * (alternatives.size() + seeds.size() + sections.size()));
            return new Accounted<>(discovery, resultOwner);
        } catch (RuntimeException | Error failure) {
            resultOwner.close(); throw failure;
        }
    }

    /** Consumes one owned discovery exactly once; omitted parents retain factual diagnostics but do no B work. */
    public Accounted<Result> refine(Accounted<Discovery> discovery, List<String> admittedParentIds,
            TraceBudgets available, CancellationProbe cancellation, AttemptMemoryLedger.Owner attemptOwner,
            Deadline deadline) {
        return refine(discovery, admittedParentIds, available, true, cancellation, attemptOwner, deadline);
    }

    public Accounted<Result> refine(Accounted<Discovery> discovery, List<String> admittedParentIds,
            TraceBudgets available, boolean bInferenceAllowed, CancellationProbe cancellation,
            AttemptMemoryLedger.Owner attemptOwner, Deadline deadline) {
        return refine(discovery, admittedParentIds, available, bInferenceAllowed, Reason.WORK_BUDGET,
                cancellation, attemptOwner, deadline);
    }

    public Accounted<Result> refine(Accounted<Discovery> discovery, List<String> admittedParentIds,
            TraceBudgets available, boolean bInferenceAllowed, Reason unavailableReason, CancellationProbe cancellation,
            AttemptMemoryLedger.Owner attemptOwner, Deadline deadline) {
        if (unavailableReason != Reason.WORK_BUDGET && unavailableReason != Reason.DEADLINE && unavailableReason != Reason.RESOURCE_LIMIT)
            throw new IllegalArgumentException("Invalid root refinement allocation reason");
        var nativeDiscovery = discovery.value(); var input = nativeDiscovery.input();
        requireBudget(input.request().budgets(), available);
        var admitted = List.copyOf(admittedParentIds);
        var admittedSet = new LinkedHashSet<>(admitted);
        var selectedSeeds = nativeDiscovery.seeds().stream().filter(seed -> admittedSet.contains(seed.id())).toList();
        if (admitted.size() > 2 || admittedSet.size() != admitted.size()
                || !selectedSeeds.stream().map(Seed::id).toList().equals(admitted))
            throw new IllegalArgumentException("Parent admission must name at most two discovered parents in native order");
        var resultOwner = attemptOwner.child("legacy-b-production-result");
        try {
            discovery.owner().transferTo(resultOwner); discovery.owner().close();
            cancellation.checkpoint();
            var detector = nativeDiscovery.detector(); int profileCount = nativeDiscovery.profileCount();
            var alternatives = nativeDiscovery.alternatives(); var seeds = nativeDiscovery.seeds();
            var sections = nativeDiscovery.sections().stream().filter(section -> admittedSet.contains(section.parentSeedId())).toList();
            var facts = effectiveOccurrences(input);
            var sectionQualities = new ArrayList<SectionQuality>();
            var engine = new ProbabilisticTraceEngine(input.fieldName());
            var image = ImageCostField.fromEvidence(input.evidence(), input.fieldName());
            var authorityWork = new AuthorityWork();
            SectionRunner sectionRunner = (section, remaining, probe) -> {
                var reference = new LegacyBReferenceFactory().prepare(section, input.request(), input.evidence(),
                        input.network(), remaining, probe);
                var nativeRun = engine.traceRefinedWithUsage(reference, input.network(), probe, resultOwner);
                long authorityStart = authorityWork.used;
                boolean authorityLimited = false;
                try {
                    var eligible = new LinkedHashSet<String>();
                    for (var hypothesis : nativeRun.value().result().hypotheses()) {
                        cancellation.checkpoint();
                        var mapping = Map.of(section.originalRange().firstIndex(), 0,
                                section.originalRange().lastIndex(), hypothesis.points().size() - 1);
                        var identities = identities(input, section.id() + "/" + hypothesis.id(), hypothesis.points(), mapping);
                        var incidents = incidents(input,hypothesis.points(),mapping,identities,section.originalRange(),
                                cancellation,resultOwner,authorityWork);
                        authorityLimited |= incidents.proofLimited();
                        var protectedPoints = Map.of(0, section.reference().get(0), hypothesis.points().size() - 1,
                                section.reference().get(section.reference().size() - 1));
                        var quality = new FinalGeometryEvaluator().evaluate(new FinalGeometryEvaluator.Request(
                                section.id() + "/" + hypothesis.id(), hypothesis.points(), identities, image,
                                input.evidence().resolution().effectivePitchMeters(), protectedPoints, incidents.geometry(),
                                false, nativeRun.value().result().status() == TraceHypothesisSet.Status.AMBIGUOUS,
                                nativeRun.value().result().truncated(), false).withSharedContacts(incidents.contacts())
                                .withInheritedProperCrossings(incidents.inheritedCrossings()), cancellation);
                        boolean usable = quality.disposition() != FinalGeometryEvaluator.Disposition.HARD_BLOCKED
                                && quality.directlySupportedLengthMeters() > 0 && !incidents.proofLimited();
                        Optional<Boolean> authority = Optional.empty();
                        if (usable) {
                            try {
                                authority = Optional.of(insideEditAuthority(input, hypothesis.points(), cancellation,
                                        resultOwner, authorityWork));
                                usable = authority.orElseThrow();
                            } catch (AuthorityWorkLimit | AttemptMemoryLedger.ResourceLimitException limit) {
                                usable = false;
                                authorityLimited = true;
                            }
                        }
                        var row = new SectionQuality(section.id(), hypothesis.id(), quality, usable, authority);
                        own(resultOwner, row, 256L + 64L * quality.findings().size()); sectionQualities.add(row);
                        if (usable) eligible.add(hypothesis.id());
                        if (authorityLimited) break;
                    }
                    nativeRun.owner().transferTo(resultOwner);
                    return new SectionExecution(nativeRun.value(), Optional.of(reference), eligible,
                            authorityWork.used - authorityStart, authorityLimited);
                } finally { nativeRun.owner().close(); }
            };
            var pipeline = new LegacyBPipeline(deadline);
            var nativeBatch = bInferenceAllowed ? pipeline.refine(selectedSeeds, sections, sectionRunner, available, cancellation)
                    : pipeline.retainLegacy(selectedSeeds, sections, available, unavailableReason, cancellation);
            var reconstructed = new ArrayList<Candidate>();
            for (var candidate : nativeBatch.candidates()) reconstructed.add(LegacyBOccurrenceReconstruction
                    .reuseAvailableVertices(input, candidate, cancellation, resultOwner));
            var batch = new Batch(reconstructed, nativeBatch.sections(), nativeBatch.usage(),
                    nativeBatch.truncated() || seeds.size() > admitted.size(),
                    nativeBatch.omittedParents() + seeds.size() - admitted.size());
            var assessments = new ArrayList<LegacyBAssessor.AssessedCandidate>();
            for (var candidate : batch.candidates()) {
                cancellation.checkpoint();
                var ids = identities(input, candidate.id(), candidate.points(), candidate.originalToVertex());
                var incidents = incidents(input,candidate.points(),candidate.originalToVertex(),ids,
                        input.request().selectedRange(),cancellation,resultOwner,authorityWork);
                var context = new LegacyBAssessor.AssessmentContext(input.request(), input.network(), input.evidence(),
                        input.fieldName(), incidents.geometry(), incidents.contacts(),incidents.inheritedCrossings());
                var assessed = new LegacyBAssessor().assess(new Batch(List.of(candidate), batch.sections(),
                        batch.usage(), batch.truncated(), batch.omittedParents()), context, cancellation);
                boolean noSignalFallback = candidate.sections().stream().noneMatch(section -> section.source() == Source.B)
                        && alternatives.stream().filter(parent -> parent.id().equals(candidate.parentSeedId()))
                                .findFirst().orElseThrow().evidence().supportedProfiles() == 0;
                for (var row : assessed) {
                    if (noSignalFallback) row = blockNoSignalFallback(row);
                    if (incidents.proofLimited()) row = new LegacyBAssessor.AssessedCandidate(row.candidate(),row.pointIds(),
                            row.assignments(),blockContextProof(row.quality(),row.candidate().points().size()),
                            row.sections(),row.directlySupportedLengthMeters());
                    assessments.add(row);
                }
            }
            var originalMapping = new TreeMap<Integer,Integer>();
            var originalProtected = new TreeMap<Integer,MetricPoint>();
            for (var fact : facts) {
                originalMapping.put(input.request().selectedRange().firstIndex() + fact.sourceIndex(), fact.sourceIndex());
                if (fact.fixed()) originalProtected.put(fact.sourceIndex(), input.originalMetric().get(fact.sourceIndex()));
            }
            var originalIds = identities(input, "original", input.originalMetric(), originalMapping);
            var originalIncidents = incidents(input,input.originalMetric(),originalMapping,originalIds,
                    input.request().selectedRange(),cancellation,resultOwner,authorityWork);
            var originalQuality = new FinalGeometryEvaluator().evaluate(new FinalGeometryEvaluator.Request("original",
                    input.originalMetric(), originalIds, image, input.evidence().resolution().effectivePitchMeters(),
                    originalProtected, originalIncidents.geometry(), false, false, false, false)
                    .withSharedContacts(originalIncidents.contacts())
                    .withInheritedProperCrossings(originalIncidents.inheritedCrossings()), cancellation);
            if (originalIncidents.proofLimited()) originalQuality = blockContextProof(originalQuality,input.originalMetric().size());
            var result = new Result(detector, profileCount, alternatives, seeds, batch, assessments, originalQuality,
                    sectionQualities, available, bInferenceAllowed, unavailableReason,authorityWork.contactProofLimited);
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                    "legacyB.contactProofLimited",authorityWork.contactProofLimited ? 1 : 0);
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                    "legacyB.geometryWorkUnits",authorityWork.used);
            long points = batch.candidates().stream().mapToLong(candidate -> candidate.points().size()).sum();
            own(resultOwner, result, 1024L + 1024L * points + 64L * originalQuality.findings().size());
            cancellation.checkpoint();
            return new Accounted<>(result, resultOwner);
        } catch (RuntimeException | Error failure) {
            resultOwner.close(); throw failure;
        }
    }

    private record ContextEdge(PrimitiveKey wayKey, int edgeIndex) { }
    private record Incidents(List<List<MetricPoint>> geometry, Map<Integer,Set<ExistingWayNodeOccurrence>> contacts,
            List<ContextEdge> contextEdges, Set<Integer> inheritedCrossings, boolean proofLimited) { }

    private static FinalGeometryEvaluator.Result blockContextProof(FinalGeometryEvaluator.Result quality, int points) {
        var findings = new ArrayList<>(quality.findings());
        findings.add(new FinalGeometryEvaluator.Finding(FinalGeometryEvaluator.FindingCode.CONTEXT_PROOF_UNAVAILABLE,
                FinalGeometryEvaluator.Severity.HARD_BLOCK,0,points-1,0));
        return new FinalGeometryEvaluator.Result(quality.id(),FinalGeometryEvaluator.Disposition.HARD_BLOCKED,
                findings,quality.totalLengthMeters(),quality.directlySupportedLengthMeters(),
                quality.worstUnsupportedSpanMeters(),quality.meanImageCenterCost(),quality.bendPreservingRoughness());
    }

    /** Zero native support is an admission failure, not an invitation to confirm empty imagery. */
    private static LegacyBAssessor.AssessedCandidate blockNoSignalFallback(LegacyBAssessor.AssessedCandidate row) {
        var quality = row.quality();
        var findings = new ArrayList<>(quality.findings());
        findings.removeIf(finding -> finding.code() == FinalGeometryEvaluator.FindingCode.INSUFFICIENT_DIRECT_SUPPORT);
        findings.add(new FinalGeometryEvaluator.Finding(FinalGeometryEvaluator.FindingCode.INSUFFICIENT_DIRECT_SUPPORT,
                FinalGeometryEvaluator.Severity.HARD_BLOCK, 0, row.candidate().points().size() - 1, 0));
        var blocked = new FinalGeometryEvaluator.Result(quality.id(), FinalGeometryEvaluator.Disposition.HARD_BLOCKED,
                findings, quality.totalLengthMeters(), quality.directlySupportedLengthMeters(),
                quality.worstUnsupportedSpanMeters(), quality.meanImageCenterCost(), quality.bendPreservingRoughness());
        return new LegacyBAssessor.AssessedCandidate(row.candidate(), row.pointIds(), row.assignments(), blocked,
                row.sections(), row.directlySupportedLengthMeters());
    }

    private static Incidents incidents(Input input, List<MetricPoint> points, Map<Integer,Integer> mapping,
            List<FinalRoutePointId> identities, OccurrenceRange replacementRange, CancellationProbe cancellation,
            AttemptMemoryLedger.Owner owner, AuthorityWork work) {
        var selected = (DetachedWay) input.network().primitives().get(input.request().selectedWayKey());
        var positions = new HashMap<PrimitiveKey,MetricPoint>();
        var identityByNode = new HashMap<PrimitiveKey,ExistingWayNodeOccurrence>();
        mapping.forEach((occurrence, vertex) -> {
            var key = selected.nodeKeys().get(occurrence); positions.put(key, points.get(vertex));
            identityByNode.put(key, (ExistingWayNodeOccurrence) identities.get(vertex));
        });
        var geometry = new ArrayList<List<MetricPoint>>();
        var contacts = new LinkedHashMap<Integer,Set<ExistingWayNodeOccurrence>>();
        var contextEdges = new ArrayList<ContextEdge>();
        var inheritedRows = new LinkedHashSet<Integer>();
        Set<PrimitiveKey> inherited = Set.of(); boolean proofLimited = false;
        if (input.network().semanticWitness() != null) {
            try { inherited = inheritedContacts(input,points,mapping,replacementRange,cancellation,owner,work); }
            catch (AuthorityWorkLimit | AttemptMemoryLedger.ResourceLimitException unavailable) {
                proofLimited = true; work.contactProofLimited = true;
            }
        }
        for (var primitive : input.network().primitives().values().stream()
                .sorted(Comparator.comparing(value -> value.key().toString())).toList()) {
            if (!(primitive instanceof DetachedWay way) || way.deleted() || way.key().equals(selected.key())) continue;
            var nodes = new ArrayList<DetachedNode>();
            for (var key : way.nodeKeys()) {
                cancellation.checkpoint();
                if (!(input.network().primitives().get(key) instanceof DetachedNode node) || node.deleted())
                    throw new IllegalArgumentException("Captured incident geometry is incomplete");
                nodes.add(node);
            }
            var frame = input.evidence().coordinateFrame();
            for (int edge = 1; edge < nodes.size(); edge++) {
                cancellation.checkpoint();
                var first = nodes.get(edge - 1); var second = nodes.get(edge);
                var start = positions.containsKey(first.key()) ? frame.toGeographic(positions.get(first.key()))
                        : first.coordinate();
                var end = positions.containsKey(second.key()) ? frame.toGeographic(positions.get(second.key()))
                        : second.coordinate();
                var clipped = CertifiedContextSegmentClipper.clip(start, end, frame);
                if (clipped.isEmpty()) continue;
                var segment = clipped.orElseThrow();
                var shared = new LinkedHashSet<ExistingWayNodeOccurrence>();
                if (segment.originalStart() && identityByNode.containsKey(first.key())) shared.add(identityByNode.get(first.key()));
                if (segment.originalEnd() && identityByNode.containsKey(second.key())) shared.add(identityByNode.get(second.key()));
                var metricStart = segment.originalStart() && positions.containsKey(first.key())
                        ? positions.get(first.key()) : segment.start();
                var metricEnd = segment.originalEnd() && positions.containsKey(second.key())
                        ? positions.get(second.key()) : segment.end();
                int index = geometry.size(); geometry.add(List.of(metricStart, metricEnd));
                contextEdges.add(new ContextEdge(way.key(),edge-1));
                if (inherited.contains(way.key()) && segment.originalStart() && segment.originalEnd()) inheritedRows.add(index);
                if (!shared.isEmpty()) contacts.put(index, Set.copyOf(shared));
            }
        }
        return new Incidents(List.copyOf(geometry),Map.copyOf(contacts),List.copyOf(contextEdges),Set.copyOf(inheritedRows),proofLimited);
    }

    /** Builds proof-only detached geometry; no live primitive or edit authority is created. */
    private static Set<PrimitiveKey> inheritedContacts(Input input,List<MetricPoint> points,Map<Integer,Integer> mapping,
            OccurrenceRange range,CancellationProbe cancellation,AttemptMemoryLedger.Owner owner,AuthorityWork work) {
        var before = input.network(); var way = (DetachedWay) before.primitives().get(input.request().selectedWayKey());
        if (!Objects.equals(mapping.get(range.firstIndex()),0)
                || !Objects.equals(mapping.get(range.lastIndex()),points.size()-1))
            throw new IllegalArgumentException("Contact proposal lacks its exact boundary occurrences");
        try (var scratch = owner.child("legacy-b-contact-proposal")) {
            work.charge(before.primitives().size()+(long)points.size()+way.nodeKeys().size());
            scratch.reserve(4096L+80L*before.primitives().size()+256L*points.size()+16L*way.nodeKeys().size());
            var after = new LinkedHashMap<>(before.primitives());
            var existing = new HashMap<Integer,PrimitiveKey>();
            mapping.forEach((occurrence,vertex) -> {
                if (occurrence<range.firstIndex() || occurrence>range.lastIndex() || existing.put(vertex,way.nodeKeys().get(occurrence))!=null)
                    throw new IllegalArgumentException("Contact proposal occurrence identity is inconsistent");
            });
            var sequence = new ArrayList<>(way.nodeKeys().subList(0,range.firstIndex()));
            long generated = 1;
            for (int vertex=0;vertex<points.size();vertex++) {
                cancellation.checkpoint(); work.charge(1);
                var key = existing.get(vertex);
                if (key==null) {
                    do { key=PrimitiveKey.planned(PrimitiveKey.Type.NODE,generated++); work.charge(1); }
                    while (after.containsKey(key));
                }
                var old = after.get(key); var point = points.get(vertex);
                if (!(old instanceof DetachedNode node) || !point.equals(input.evidence().coordinateFrame().toMetric(node.coordinate()))) {
                    GeographicPoint coordinate;
                    try { coordinate = input.evidence().coordinateFrame().toGeographic(point); }
                    catch (IllegalArgumentException uncertifiedPoint) { return Set.of(); }
                    after.put(key,new DetachedNode(key,coordinate,old instanceof DetachedNode node ? node.tags() : Map.of(),false,true));
                }
                sequence.add(key);
            }
            sequence.addAll(way.nodeKeys().subList(range.lastIndex()+1,way.nodeKeys().size()));
            after.put(way.key(),new DetachedWay(way.key(),sequence,way.tags(),false,true));
            return InheritedNonTransportContacts.prove(before,after,way.key(),input.request().selectedRange(),
                    input.evidence(),input.request().permissions().ordinaryRadiusMeters(),cancellation,work::charge,scratch);
        }
    }

    private static List<FinalRoutePointId> identities(Input input, String id, List<MetricPoint> points,
            Map<Integer,Integer> mapping) {
        var result = new ArrayList<FinalRoutePointId>();
        for (int vertex = 0; vertex < points.size(); vertex++) result.add(new FinalRoutePointId.GeneratedCandidatePoint(id, vertex));
        var way = (DetachedWay) input.network().primitives().get(input.request().selectedWayKey());
        mapping.forEach((occurrence, vertex) -> result.set(vertex,
                new ExistingWayNodeOccurrence(way.key(), way.nodeKeys().get(occurrence), occurrence)));
        return List.copyOf(result);
    }

    private static List<MetricPoint> metricPoints(Input input, List<ProjectedPoint> projected,
            List<LegacyTopologyPlanner.Assignment> assignments, Map<Integer,Integer> mapping) {
        var fixed = new HashMap<Integer,Integer>();
        for (var assignment : assignments) if (assignment.fixed()) fixed.put(mapping.get(
                input.request().selectedRange().firstIndex() + assignment.sourceIndex()), assignment.sourceIndex());
        var projection = Projections.getProjectionByCode(input.projectionCode());
        var result = new ArrayList<MetricPoint>();
        for (int vertex = 0; vertex < projected.size(); vertex++) {
            if (fixed.containsKey(vertex)) result.add(input.originalMetric().get(fixed.get(vertex)));
            else {
                var point = projected.get(vertex);
                var ll = projection.eastNorth2latlon(new EastNorth(point.east(), point.north()));
                result.add(input.evidence().coordinateFrame().toMetric(new GeographicPoint(ll.lat(), ll.lon())));
            }
        }
        return List.copyOf(result);
    }

    private static Map<Integer,Integer> exactMapping(List<LegacyTopologyPlanner.Assignment> assignments,
            List<ProjectedPoint> points, int start) {
        var result = new TreeMap<Integer,Integer>(); int previous = -1;
        for (var assignment : assignments) {
            int found = -1;
            for (int vertex = previous + 1; vertex < points.size(); vertex++) {
                if (points.get(vertex).equals(assignment.target())) { found = vertex; break; }
            }
            if (found < 0) throw new UnrepresentableAssignment();
            result.put(start + assignment.sourceIndex(), found); previous = found;
        }
        return result;
    }

    private static List<ProjectedPoint> restoreFrozen(Input input, LegacyPreciseGeometry.Result finished) {
        var points = new ArrayList<ProjectedPoint>(); int cursor = 0;
        for (var range : input.frozenRanges()) {
            int first = range.firstIndex() - input.request().selectedRange().firstIndex();
            int last = range.lastIndex() - input.request().selectedRange().firstIndex();
            Integer from = finished.originalToVertex().get(first), to = finished.originalToVertex().get(last);
            if (from == null || to == null) throw new UnrepresentableAssignment();
            points.addAll(finished.points().subList(cursor, from));
            points.addAll(input.originalProjected().subList(first, last + 1)); cursor = to + 1;
        }
        points.addAll(finished.points().subList(cursor, finished.points().size()));
        return List.copyOf(points);
    }

    private static List<Occurrence> effectiveOccurrences(Input input) {
        return input.occurrences().stream().map(fact -> new Occurrence(fact.sourceIndex(), fact.fixed()
                || input.frozenRanges().stream().anyMatch(range -> range.firstIndex() <= input.request().selectedRange().firstIndex()
                        + fact.sourceIndex() && range.lastIndex() >= input.request().selectedRange().firstIndex()
                        + fact.sourceIndex()), fact.topologyAnchor())).toList();
    }

    private static List<OccurrenceRange> normalizedFrozen(List<OccurrenceRange> ranges, OccurrenceRange selected) {
        var result = new ArrayList<OccurrenceRange>();
        for (var range : ranges.stream().sorted(Comparator.comparingInt(OccurrenceRange::firstIndex)).toList()) {
            if (range.firstIndex() < selected.firstIndex() || range.lastIndex() > selected.lastIndex())
                throw new IllegalArgumentException("Frozen geometry lies outside the original selection");
            if (!result.isEmpty() && result.get(result.size() - 1).lastIndex() >= range.firstIndex()) {
                var prior = result.remove(result.size() - 1);
                result.add(new OccurrenceRange(prior.firstIndex(), Math.max(prior.lastIndex(), range.lastIndex())));
            } else result.add(range);
        }
        return List.copyOf(result);
    }

    private static void requireDetector(List<String> detectors) {
        if (detectors.size() != 1) throw new IllegalArgumentException("One factual scalar mapping is required per invocation");
    }

    private static void requireBudget(TraceBudgets root, TraceBudgets available) {
        if (available.maximumStatesPerProfile() > root.maximumStatesPerProfile()
                || available.maximumPairVisits() > root.maximumPairVisits()
                || available.maximumTransitions() > root.maximumTransitions()
                || available.maximumRawAlternatives() > root.maximumRawAlternatives()
                || available.maximumDistinctAlternatives() > root.maximumDistinctAlternatives())
            throw new IllegalArgumentException("Available work cannot enlarge the original frozen budget");
    }

    private static long nativeWorkingBytes(long pixels, long profiles, long samples) {
        // Covers image reconstruction/copies and conservative per-sample native profile/tracker scratch.
        return Math.addExact(Math.multiplyExact(pixels, 12L),
                Math.multiplyExact(Math.multiplyExact(profiles, samples), 4096L));
    }

    private static void own(AttemptMemoryLedger.Owner owner, Object value, long bytes) {
        try (var reservation = owner.reserve(bytes)) { reservation.adopt(value); }
    }

    private static final class UnrepresentableAssignment extends IllegalStateException { }
}
