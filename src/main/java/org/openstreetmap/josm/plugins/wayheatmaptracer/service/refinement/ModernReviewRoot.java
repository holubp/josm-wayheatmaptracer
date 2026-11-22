package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import java.util.function.LongSupplier;
import javax.swing.SwingUtilities;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;

/** Actual native production choices and original authority; never a synthetic Legacy run. */
public final class ModernReviewRoot implements RefinementReviewRoot {
    public record IntervalSelection(LiveBPreviewService.Computed owner,Map<Integer,Integer> routeChoices,
            FixedIntervalEditPlanComposer.Assessment assessment) {
        public IntervalSelection {
            Objects.requireNonNull(owner);Objects.requireNonNull(assessment);routeChoices=Map.copyOf(routeChoices);
            if(!owner.partitioned()||!new FixedIntervalEditPlanComposer().compose(owner.intervalBatch(),routeChoices).equals(assessment))
                throw new IllegalArgumentException("Interval selection differs from factual native composition");
        }
    }
    private final LegacyReviewOwner observer;
    private final AlignmentConfig config;
    private final List<LiveBPreviewService.PreviewChoice> nativeChoices;
    private IntervalSelection interval;
    private List<LegacyReviewOwner.Choice> choices;
    private List<LiveBPreviewService.Computed> intervalSources;
    private final Map<String,IntervalSelection> intervalSelections=new LinkedHashMap<>();
    private final AlignmentResult original;
    private final TileFetchCoordinator coordinator;
    private final Runnable hostCurrent;
    private final RefinementSourcePlanner.Settings settings;
    private final String sourceIdentity;
    private CenterlineCandidate selected;

    private ModernReviewRoot(DataSet data,SelectionContext selection,AlignmentConfig config,
            List<LiveBPreviewService.PreviewChoice> nativeChoices,IntervalSelection interval,
            TileFetchCoordinator coordinator,Runnable hostCurrent) {
        if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Native review requires EDT");
        this.config=Objects.requireNonNull(config);this.coordinator=coordinator;this.hostCurrent=Objects.requireNonNull(hostCurrent);
        this.nativeChoices=List.copyOf(nativeChoices);this.interval=interval;
        var owner=interval==null?this.nativeChoices.get(0).owner():interval.owner();
        intervalSources=interval==null?List.of():owner.productionRuns().stream().filter(LiveBPreviewService.Computed::partitioned).toList();
        if(!Set.of(TrackerMode.CORRIDOR_AWARE,TrackerMode.PROBABILISTIC).contains(owner.request().engine())
                ||owner.request().engine()!=config.heatmap().trackerMode()||owner.request().geometryMode()!=config.heatmap().alignmentMode())
            throw new IllegalArgumentException("Native root engine or mode differs from original capture");
        for(var choice:this.nativeChoices)if(choice.owner().captured()!=owner.captured()||choice.owner().request().engine()!=owner.request().engine())
            throw new IllegalArgumentException("Native root choices have foreign owners");
        hostCurrent.run();
        var projection=Projections.getProjectionByCode(owner.captured().projectionCode());
        var candidates=interval==null?this.nativeChoices.stream().map(LiveBPreviewService.PreviewChoice::candidate).toList():
            List.of(intervalCandidate(interval,projection));
        selected=candidates.get(0);
        choices=java.util.stream.IntStream.range(0,candidates.size()).mapToObj(i->new LegacyReviewOwner.Choice(interval==null?"native-choice-"+i:intervalChoiceId(interval),candidates.get(i))).toList();
        if(interval!=null)intervalSelections.put(choices.get(0).id(),interval);
        var source=owner.captured().sourceGeographic().stream().map(p->projection.latlon2eastNorth(new LatLon(p.latitudeDegrees(),p.longitudeDegrees()))).toList();
        var attempts=new ArrayList<DetectorAttempt>();
        for(int i=0;i<candidates.size();i++) {
            var run=interval==null?this.nativeChoices.get(i).owner():owner;
            String mapping=mapping(run);
            attempts.add(new DetectorAttempt(run.captured().palette(),mapping,run.request().engine(),DetectorAttemptStatus.APPLICABLE,List.of(candidates.get(i).id()),"",""));
        }
        original=new AlignmentResult(selection,null,candidates,source,selected.finalPreviewPoints(),List.of(),null,null,attempts,candidates);
        sourceIdentity=owner.evidence().sourceIdentity();
        settings=RefinementSourcePlanner.Settings.capture(owner.captured().managedRaster()!=null,config.heatmap(),owner.captured().alternativeMappings());
        observer=LegacyReviewOwner.observeNative(data,selection,config);
        try{observer.captured(original,null,null);requireAuthorityCurrent();}catch(RuntimeException failure){observer.close();throw failure;}
    }
    public static ModernReviewRoot fromFullChoices(DataSet data,SelectionContext selection,AlignmentConfig config,
            List<LiveBPreviewService.PreviewChoice> choices,TileFetchCoordinator coordinator,Runnable hostCurrent) {
        if(choices==null||choices.isEmpty())throw new IllegalArgumentException("Native root choices required");
        return new ModernReviewRoot(data,selection,config,choices,null,coordinator,hostCurrent);
    }
    public static ModernReviewRoot fromIntervalSelection(DataSet data,SelectionContext selection,AlignmentConfig config,
            IntervalSelection interval,TileFetchCoordinator coordinator,Runnable hostCurrent) {
        return new ModernReviewRoot(data,selection,config,List.of(),Objects.requireNonNull(interval),coordinator,hostCurrent);
    }
    public static ModernReviewRoot fromIntervalSelection(DataSet data,SelectionContext selection,AlignmentConfig config,
            IntervalSelection interval,List<LiveBPreviewService.Computed> sources,TileFetchCoordinator coordinator,Runnable hostCurrent) {
        var root=fromIntervalSelection(data,selection,config,interval,coordinator,hostCurrent);
        try{root.requireIntervalSources(sources);root.intervalSources=List.copyOf(sources);return root;}
        catch(RuntimeException failed){root.close();throw failed;}
    }
    private void requireIntervalSources(List<LiveBPreviewService.Computed> sources) {
        if(interval==null||sources==null||sources.isEmpty()||sources.stream().noneMatch(run->run==interval.owner()))throw new IllegalArgumentException("Native interval source owner unavailable");
        for(var run:sources)if(!run.partitioned()||run.captured()!=interval.owner().captured()||run.request().engine()!=engine())throw new IllegalArgumentException("Foreign interval source owner");
    }
    static String ownerIdentity(LiveBPreviewService.Computed owner){return owner.options().sourceTier()+":"+owner.request().evidenceContentHash();}
    static String intervalChoiceId(IntervalSelection selection){return "native-composition-"+selection.assessment().plan().orElseThrow(()->new IllegalArgumentException("Native interval plan unavailable")).canonicalHash();}
    public boolean partitionedRoot(){return interval!=null;}
    ModernReviewRoot preparationFor(IntervalSelection selection) {
        requireAuthorityCurrent();
        if(interval==null||intervalSources.stream().noneMatch(run->run==selection.owner()))throw new IllegalArgumentException("Foreign interval source selection");
        return fromIntervalSelection(dataSet(),selection(),config,selection,intervalSources,coordinator,hostCurrent);
    }
    void replaceUnpreparedInterval(IntervalSelection selection) {
        requireAuthorityCurrent();
        if(interval==null||intervalSources.stream().noneMatch(run->run==selection.owner()))throw new IllegalArgumentException("Foreign interval source selection");
        var candidate=intervalCandidate(selection,Projections.getProjectionByCode(selection.owner().captured().projectionCode()));
        interval=selection;selected=candidate;choices=List.of(new LegacyReviewOwner.Choice(intervalChoiceId(selection),candidate));
        intervalSelections.clear();intervalSelections.put(choices.get(0).id(),selection);
    }
    void registerPreparedInterval(IntervalSelection selection,Candidate candidate) {
        requireAuthorityCurrent();
        if(interval==null||intervalSources.stream().noneMatch(run->run==selection.owner())||!candidate.id().value().equals(intervalChoiceId(selection)))throw new IllegalArgumentException("Foreign materialized interval choice");
        var state=candidate.payload().rootReviewState().orElseThrow();
        if(!state.sourceOwnerIdentity().equals(ownerIdentity(selection.owner()))||!state.routeChoices().equals(selection.routeChoices())||!state.intervalAssessment().equals(Optional.of(selection.assessment())))throw new IllegalArgumentException("Materialized interval state differs");
        if(intervalSelections.containsKey(candidate.id().value()))return;
        var next=new ArrayList<>(choices);next.add(new LegacyReviewOwner.Choice(candidate.id().value(),candidate.payload().retainedProjectedCandidate()));choices=List.copyOf(next);
        intervalSelections.put(candidate.id().value(),selection);
    }
    void unregisterPreparedInterval(String choice) {
        choices=choices.stream().filter(value->!value.id().equals(choice)).toList();intervalSelections.remove(choice);
    }
    public IntervalSelection retainedIntervalSelection(String choice) {
        var selection=intervalSelections.get(choice);if(selection==null)throw new IllegalArgumentException("Stored interval selection unavailable");return selection;
    }
    static String mapping(LiveBPreviewService.Computed run) {
        String tier=run.options().sourceTier();
        return tier.equals("all-colors-combined")?tier:tier.startsWith("selected-mapping-")?tier.substring("selected-mapping-".length()):run.captured().palette();
    }
    public TrackerMode engine(){return config.heatmap().trackerMode();}
    public AlignmentConfig originalConfig(){return config;}
    public DataSet dataSet(){return observer.dataSet();}
    public SelectionContext selection(){return observer.selection();}
    public AlignmentResult originalResult(){return original;}
    public List<LegacyReviewOwner.Choice> choices(){return choices;}
    public CenterlineCandidate selectedCandidate(){return selected;}
    private CenterlineCandidate displayedNoChangeChoice;
    private List<org.openstreetmap.josm.data.coor.EastNorth> displayedNoChangeGeometry=List.of();
    /** Called after the ordinary selected assessment and its existing live no-change validation. */
    public void bindDisplayedSelection(LiveBPreviewService.PreviewChoice choice,ModernSingleWayEditPlanAdapter.Assessment assessment) {
        requireOriginalAttemptCurrent();
        if(interval!=null||selected!=choice.candidate()||nativeChoices.stream().noneMatch(retained->retained.owner()==choice.owner()
                &&retained.localRouteIndex()==choice.localRouteIndex()&&retained.candidate()==choice.candidate()))
            throw new IllegalArgumentException("Displayed native assessment belongs to another root choice");
        displayedNoChangeChoice=null;displayedNoChangeGeometry=List.of();
        if(assessment!=null&&assessment.availability()==ModernSingleWayEditPlanAdapter.ApplyAvailability.NO_CHANGE) {
            var proof=assessment.noChangeProof().orElseThrow(()->new IllegalArgumentException("Factual no-change proof unavailable"));var run=choice.owner();
            proof.requireOwner(run);proof.requireMatches(run.request(),run.evidence(),run.captured().network(),run.options(),run.pipeline(),choice.localRouteIndex(),run.captured().network());
            var projection=Projections.getProjectionByCode(run.captured().projectionCode());
            displayedNoChangeGeometry=proof.finalSelection().selectedGeometry().stream().map(point->projection.latlon2eastNorth(new LatLon(point.latitudeDegrees(),point.longitudeDegrees()))).toList();
            displayedNoChangeChoice=selected;
        }
    }
    @Override public List<org.openstreetmap.josm.data.coor.EastNorth> selectedFinalGeometry(){return displayedNoChangeChoice==selected?displayedNoChangeGeometry:selected.finalPreviewPoints();}
    public void select(CenterlineCandidate candidate){requireOriginalAttemptCurrent();choiceId(candidate);selected=candidate;}
    public String choiceId(CenterlineCandidate candidate){return choices.stream().filter(c->c.candidate()==candidate).findFirst().orElseThrow(()->new IllegalArgumentException("Foreign native choice")).id();}
    public CenterlineCandidate candidateForChoice(String id){return choices.stream().filter(c->c.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown native choice")).candidate();}
    public void requireOriginalAttemptCurrent(){observer.requireOriginalAttemptCurrent();hostCurrent.run();}
    public void requireAuthorityCurrent(){requireOriginalAttemptCurrent();source().requireCurrent();}
    public boolean invalidated(){return observer.invalidated();}
    public String sourceIdentity(){return sourceIdentity;}
    public String projectionCode(){return observer.projectionCode();}
    public void close(){observer.close();}
    private LiveBPreviewService.Computed firstOwner(){return interval==null?nativeChoices.get(0).owner():interval.owner();}
    NativeRefinementSourceContext source(){return new NativeRefinementSourceContext(firstOwner().captured(),config,coordinator);}

    /** Worker preparation retains factual native plans/evidence, without inference or new capture. */
    public PreparedRefinementRoot prepare(GeometryWorkContext work) {
        return prepare(work,(request,context)->{throw new UnsupportedOperationException("visible-refinement-capture-unavailable");});
    }
    public PreparedRefinementRoot prepare(GeometryWorkContext work,VisibleRefinementCapture.Port visibleCapture) {
        if(SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Native root preparation requires worker thread");
        final String[] selectedId=new String[1];onEdt(()->{requireAuthorityCurrent();selectedId[0]=choiceId(selected);});
        var memory=work.owner().child("original-native-root");
        try(var scope=org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.PostprocessingValidationWork.open(work);
                var scratch=work.reserve("native-root-preparation-scratch",RefinementLimits.defaults().maxPayloadBytes())) {
            work.visit(GeometryWorkContext.Kind.BIND,1);
            var primary=firstOwner();var capture=primary.captured();
            var receipt=capture.originalReceipt().orElseThrow(()->new IllegalStateException("Original native capture receipt unavailable"));
            var context=new NativeRefinementSourceContext(capture,config,coordinator,visibleCapture);
            var retainedSources=new IdentityHashMap<LiveBPreviewService.Computed,RetainedSmoothingSource>();
            var hashes=new IdentityHashMap<Object,String>();
            work.reserveOn(memory,"native-original-hash-cache",32768).adopt(hashes);
            var primarySource=NativeRefinementSourceContext.retain(primary,settings,context,work,memory,hashes);retainedSources.put(primary,primarySource);
            context.bindOriginalInputHash(primarySource.inputByteHash());
            var nativeFacts=new IdentityHashMap<Object,Long>();
            nativeFacts.put(hashes,32768L);
            var owners=Collections.newSetFromMap(new IdentityHashMap<LiveBPreviewService.Computed,Boolean>());
            if(interval!=null)owners.addAll(intervalSources);else nativeChoices.forEach(c->owners.add(c.owner()));
            // Admit each existing scalar backing as one group before the native result graph
            // inventories its individual components. Later source selection co-owns that group.
            for(var owner:owners)if(!retainedSources.containsKey(owner))
                retainedSources.put(owner,NativeRefinementSourceContext.retain(owner,settings,context,work,memory,hashes));
            retainedSources.values().forEach(source->nativeFacts.putAll(source.charges()));
            for(var owner:owners) {
                nativeFacts.putAll(RefinementRootAuthority.detachedCharges(owner.request(),owner.pipeline(),owner.options(),owner.counters(),owner.intervalBatch(),owner.settingsResolutionJson()));
                nativeFacts.put(owner,512L);
                owner.producedEvidenceProof().ifPresent(proof->nativeFacts.put(proof,256L));
                owner.corridorReceipt().ifPresent(nativeReceipt->nativeFacts.put(nativeReceipt,nativeReceiptBytes(nativeReceipt)));
            }
            nativeFacts.putAll(RefinementRootAuthority.detachedCharges(capture.specification(),capture.disconnectionSource(),capture.sourceGeographic(),capture.sourceMetric(),capture.outputGrid(),capture.alternativeMappings()));
            nativeFacts.put(capture,capturedBytes(capture));
            var authority=RefinementRootAuthority.fromNativeProduction(primary,config,sourceIdentity,primarySource,work,memory,hashes)
                .withRetainedFacts(nativeFacts).withNativeAcquisitions(capture,owners,retainedSources,work,memory,hashes);
            for(var item:authority.charges().entrySet())admit(memory,work,item.getKey(),item.getValue());
            var way=(DetachedWay)capture.network().primitives().get(primary.request().selectedWayKey());
            var nodes=way.nodeKeys().stream().map(k->{var p=((DetachedNode)capture.network().primitives().get(k)).coordinate();return new RefinementPayload.SourceNode(k.id(),p.latitudeDegrees(),p.longitudeDegrees());}).toList();
            var range=primary.request().selectedRange();
            var provenance=new RefinementPayload.SourceSelection(way.key().id(),range.firstIndex(),range.lastIndex(),nodes,way.nodeKeys().subList(range.firstIndex(),range.lastIndex()+1).stream().map(PrimitiveKey::id).toList());
            var originalPayload=RefinementPayload.captureOriginal(original,provenance,RefinementLimits.defaults().maxPayloadBytes());
            var candidates=new ArrayList<Candidate>();
            for(int index=0;index<choices.size();index++) {
                work.visit(GeometryWorkContext.Kind.BIND,1);
                var choice=choices.get(index);var run=interval==null?nativeChoices.get(index).owner():interval.owner();
                var plan=interval==null?new ModernSingleWayEditPlanAdapter().assess(run,nativeChoices.get(index).localRouteIndex()).plan().orElseThrow(()->new IllegalStateException("Native root plan unavailable")):interval.assessment().plan().orElseThrow(()->new IllegalStateException("Native interval plan unavailable"));
                var quality=interval==null?run.pipeline().routes().get(nativeChoices.get(index).localRouteIndex()).quality():NativeRefinementSourceContext.intervalQuality(run,interval.assessment(),work);
                var source=retainedSources.computeIfAbsent(run,r->NativeRefinementSourceContext.retain(r,settings,context,work,memory,hashes));
                var request=source.source();
                var ids=interval==null?run.pipeline().routes().get(nativeChoices.get(index).localRouteIndex()).pointIds():NativeRefinementSourceContext.intervalPointIds(interval.assessment());
                var nativeReview=new RefinementPayload.NativeReview(plan,quality,Optional.empty(),List.of(request),OptionalInt.empty(),Map.of(request,source),List.of(),Optional.of(ids));
                var single=new AlignmentResult(original.selection(),null,List.of(choice.candidate()),original.sourcePolyline(),choice.candidate().finalPreviewPoints(),List.of(),null,null,original.detectorAttempts().stream().filter(a->a.candidateIds().contains(choice.candidate().id())).toList(),List.of(choice.candidate()));
                String identity=interval==null?nativeChoices.get(index).reviewIdentity():ownerIdentity(run)+":"+plan.routeIdentity();
                var rootState=new RefinementPayload.RootReviewState(ownerIdentity(run),interval==null?OptionalInt.of(nativeChoices.get(index).localRouteIndex()):OptionalInt.empty(),interval==null?Map.of():interval.routeChoices(),plan.routeIdentity(),Optional.ofNullable(interval==null?null:interval.assessment()));
                var payload=RefinementPayload.captureNative(single,originalPayload,nativeReview,RefinementLimits.defaults().maxPayloadBytes()).withRootReviewState(rootState);
                var points=choice.candidate().finalPreviewPoints().stream().map(p->{var ll=Projections.getProjectionByCode(capture.projectionCode()).eastNorth2latlon(p);return capture.outputGrid().coordinateFrame().toMetric(new GeographicPoint(ll.lat(),ll.lon()));}).toList();
                var arcs=LegacyRefinementBootstrap.correspondence(capture.network(),run.request().selectedWayKey(),run.request().selectedRange(),capture.sourceMetric(),capture.projectionCode(),capture.outputGrid().coordinateFrame(),choice.candidate(),points);
                candidates.add(new Candidate(new CandidateId(choice.id()),payload,arcs,identity,choice.candidate().id(),capture.palette()));
                for(var charge:payload.publicationCharges().entrySet())admit(memory,work,charge.getKey(),charge.getValue());
            }
            var stage=Stage.root(engine(),candidates,new CandidateId(selectedId[0]));
            var retained=new IdentityHashMap<Object,Long>(authority.charges());
            candidates.forEach(c->retained.putAll(c.payload().charges()));
            if(candidates.size()>32||retained.values().stream().mapToLong(Long::longValue).sum()>RefinementLimits.defaults().maxPayloadBytes())throw new RefinementSession.Refused(RefusalReason.PAYLOAD_LIMIT);
            work.reserveOn(memory,"native-root-stage",1024L+32L*candidates.size()).adopt(stage);
            onEdt(this::requireAuthorityCurrent);work.visit(GeometryWorkContext.Kind.BIND,1);
            return new PreparedRefinementRoot(new LegacyRefinementBootstrap.Root(authority,stage,receipt),settings,original,context,memory,RefinementLimits.defaults().maxPayloadBytes());
        } catch(RuntimeException|Error failure){memory.close();throw failure;}
    }
    private static void admit(AttemptMemoryLedger.Owner owner,GeometryWorkContext work,Object identity,long bytes) {
        work.visit(GeometryWorkContext.Kind.BIND,1);
        try{owner.retainIfAbsent(identity);}catch(IllegalArgumentException absent){work.reserveOn(owner,"native-root-metadata",bytes).adopt(identity);}
    }
    private static long capturedBytes(LiveBPreviewService.Captured capture) {
        long bytes=8192;
        if(capture.raster()!=null)return Math.addExact(bytes,4L*capture.raster().width()*capture.raster().height());
        var rasters=capture.sourceRasters()==null?List.of(capture.managedRaster()):capture.sourceRasters().palettes().values();
        var images=Collections.newSetFromMap(new IdentityHashMap<java.awt.image.BufferedImage,Boolean>());
        for(var raster:rasters) {
            long pixels=(long)raster.image().getWidth()*raster.image().getHeight();
            bytes=Math.addExact(bytes,Math.addExact(4096L,pixels));
            if(images.add(raster.image()))bytes=Math.addExact(bytes,4*pixels);
        }
        return bytes;
    }
    static long nativeReceiptBytes(CorridorExecutionReceipt receipt) {
        var values=RefinementRootAuthority.detachedCharges(receipt.requestHash(),receipt.evidenceHash(),receipt.networkHash(),receipt.resultHash(),receipt.networkSnapshotId(),receipt.settingsHash(),receipt.certificateStatus(),receipt.certificateIdentity(),receipt.sampling(),receipt.tracks(),receipt.totals());
        long bytes=4096;for(long value:values.values())bytes=Math.addExact(bytes,value);
        for(var provenance:receipt.candidates().values())bytes=Math.addExact(bytes,Math.addExact(1024L,8L*(provenance.hypothesisId().length()+provenance.trackId().length())));
        if(receipt.originalCostPolicies().isPresent())bytes=Math.addExact(bytes,receipt.originalCostPolicyMetadataBytes().orElseThrow(()->new IllegalArgumentException("Native original cost ownership unavailable")));
        return bytes;
    }
    private static CenterlineCandidate intervalCandidate(IntervalSelection selection,org.openstreetmap.josm.data.projection.Projection projection) {
        var plan=selection.assessment().plan().orElseThrow(()->new IllegalArgumentException("Native interval plan unavailable"));
        var ids=NativeRefinementSourceContext.intervalPointIds(selection.assessment());
        var points=new ArrayList<org.openstreetmap.josm.data.coor.EastNorth>();
        var assignments=new LinkedHashMap<Long,org.openstreetmap.josm.data.coor.EastNorth>();
        for(var id:ids) {
            var key=id instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing
                ?plan.disconnectedOccurrenceReplacements().getOrDefault(existing.originalOccurrenceIndex(),existing.nodeKey())
                :PrimitiveKey.planned(PrimitiveKey.Type.NODE,((FinalRoutePointId.GeneratedCandidatePoint)id).originalPointIndex());
            var geographic=((DetachedNode)plan.after().primitives().get(key)).coordinate();
            var point=projection.latlon2eastNorth(new LatLon(geographic.latitudeDegrees(),geographic.longitudeDegrees()));points.add(point);
            if(id instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing)assignments.put(existing.nodeKey().id(),point);
        }
        return new CenterlineCandidate("native-interval-root",1,List.of(),List.of()).withFinalPreviewGeometry(points,assignments);
    }
    private static void onEdt(Runnable action) {
        if(SwingUtilities.isEventDispatchThread()){action.run();return;}
        try{SwingUtilities.invokeAndWait(action);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Native root preparation interrupted");}
        catch(java.lang.reflect.InvocationTargetException failure){if(failure.getCause() instanceof RuntimeException runtime)throw runtime;if(failure.getCause() instanceof Error error)throw error;throw new IllegalStateException("Native root validation failed");}
    }
}
