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
    private final AlignmentResult original;
    private final TileFetchCoordinator coordinator;
    private final Runnable hostCurrent;
    private final RefinementSourcePlanner.Settings settings;
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
        var source=boundOriginalView(data,selection,owner.captured());
        var attempts=new ArrayList<DetectorAttempt>();
        for(int i=0;i<candidates.size();i++) {
            var run=interval==null?this.nativeChoices.get(i).owner():owner;
            String mapping=mapping(run);
            attempts.add(new DetectorAttempt(run.captured().palette(),mapping,run.request().engine(),DetectorAttemptStatus.APPLICABLE,List.of(candidates.get(i).id()),"",""));
        }
        original=new AlignmentResult(selection,null,candidates,source,selected.finalPreviewPoints(),List.of(),null,null,attempts,candidates);
        settings=RefinementSourcePlanner.Settings.capture(owner.captured().managedRaster()!=null,config.heatmap(),owner.captured().alternativeMappings());
        observer=LegacyReviewOwner.observeNative(data,selection,config);
        try{
            observer.captured(original,null,null);
            // Close the validation/listener-installation window. Subsequent publication checks use
            // this observer's monotonic dataset stamp plus the original source/configuration guard.
            owner.captured().originalReceipt().orElseThrow().requireCurrent(data);requireAuthorityCurrent();
        }catch(RuntimeException failure){observer.close();throw failure;}
    }
    private static List<org.openstreetmap.josm.data.coor.EastNorth> boundOriginalView(DataSet data,SelectionContext selection,LiveBPreviewService.Captured captured) {
        var receipt=captured.originalReceipt().orElseThrow(()->new IllegalArgumentException("Original native receipt unavailable"));
        if(receipt.snapshot()!=captured.network()||!receipt.specification().equals(captured.specification())
                ||!org.openstreetmap.josm.data.projection.ProjectionRegistry.getProjection().toCode().equals(captured.projectionCode())
                ||selection.way().getDataSet()!=data||selection.way().getUniqueId()!=captured.specification().selectedWayKey().id()
                ||selection.startIndex()!=captured.specification().selectedRange().firstIndex()||selection.endIndex()!=captured.specification().selectedRange().lastIndex())
            throw new IllegalArgumentException("Original native view differs from its exact captured selection/projection");
        receipt.requireCurrent(data);
        var live=selection.way().getNodes();var source=captured.sourceGeographic();
        if(source.size()!=selection.segmentNodes().size())throw new IllegalArgumentException("Original native occurrence count changed");
        for(int i=0;i<source.size();i++) {
            var node=selection.segmentNodes().get(i);
            if(live.get(selection.startIndex()+i)!=node||!source.get(i).equals(new GeographicPoint(node.lat(),node.lon())))
                throw new IllegalArgumentException("Original native occurrence/coordinate changed");
        }
        // Cached EN is an observed view representation. Only exact original geographic authority
        // above permits retaining it; no tolerance or renewed OSM snapshot is introduced.
        return selection.segmentNodes().stream().map(org.openstreetmap.josm.data.osm.Node::getEastNorth).toList();
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
    private static String intervalChoiceId(IntervalSelection selection) {
        var assessment=selection.assessment();
        return "native-composition-"+assessment.plan().map(AlignmentEditPlan::canonicalHash)
            .or(()->assessment.noChangeProof().map(FixedIntervalEditPlanComposer.CompositeNoChangeProof::compositionIdentity))
            .orElseGet(()->"unavailable:"+ownerIdentity(selection.owner())+":"+new TreeMap<>(selection.routeChoices()));
    }
    public boolean partitionedRoot(){return interval!=null;}
    void selectInterval(IntervalSelection selection) {
        requireAuthorityCurrent();
        if(interval==null||intervalSources.stream().noneMatch(run->run==selection.owner()))
            throw new IllegalArgumentException("Foreign interval source selection");
        var candidate=intervalCandidate(selection,Projections.getProjectionByCode(selection.owner().captured().projectionCode()));
        var choice=new LegacyReviewOwner.Choice(intervalChoiceId(selection),candidate);
        interval=selection;selected=candidate;choices=List.of(choice);
    }
    public IntervalSelection retainedIntervalSelection(String choice) {
        if(interval==null||!choices.get(0).id().equals(choice))
            throw new IllegalArgumentException("Stored interval selection unavailable");
        return interval;
    }

    /** Restore the exact detector and interval routes that produced the saved child. */
    void restoreHistorySelection(Candidate candidate) {
        String choice=candidate.id().value();
        if(choiceId(selected).equals(choice))return;
        if(interval==null) {
            select(candidateForChoice(choice));
            return;
        }
        var state=candidate.payload().rootReviewState().orElseThrow();
        var assessment=state.intervalAssessment().orElseThrow();
        var run=intervalSources.stream()
                .filter(source->ownerIdentity(source).equals(state.sourceOwnerIdentity()))
                .findFirst().orElseThrow(()->new IllegalStateException("Stored interval detector unavailable"));
        var previous=new IntervalSelection(run,state.routeChoices(),assessment);
        if(!intervalChoiceId(previous).equals(choice)) {
            throw new IllegalStateException("Stored interval history choice differs from its composition");
        }
        selectInterval(previous);
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
    public String sourceIdentity(){return selectionForPreparation().owner().evidence().sourceIdentity();}
    public String projectionCode(){return observer.projectionCode();}
    public void close(){observer.close();}
    private record SelectedRoot(LegacyReviewOwner.Choice choice,LiveBPreviewService.PreviewChoice full,
            IntervalSelection interval) {
        LiveBPreviewService.Computed owner(){return full==null?interval.owner():full.owner();}
    }
    private SelectedRoot selectionForPreparation() {
        if(interval!=null)return new SelectedRoot(choices.get(0),null,interval);
        for(int i=0;i<choices.size();i++) {
            var choice=choices.get(i);
            if(choice.candidate()==selected)return new SelectedRoot(choice,nativeChoices.get(i),null);
        }
        throw new IllegalStateException("Native root selection unavailable");
    }
    NativeRefinementSourceContext source(){return new NativeRefinementSourceContext(selectionForPreparation().owner().captured(),config,coordinator);}

    /** Worker preparation binds only the selected route or interval composition to its original acquisition. */
    public PreparedRefinementRoot prepare(GeometryWorkContext work) {
        return prepare(work,(request,context)->{throw new UnsupportedOperationException("visible-refinement-capture-unavailable");});
    }
    public PreparedRefinementRoot prepare(GeometryWorkContext work,VisibleRefinementCapture.Port visibleCapture) {
        if(SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Native root preparation requires worker thread");
        var binding=new SelectedRoot[1];
        onEdt(()->{requireAuthorityCurrent();binding[0]=selectionForPreparation();});
        var selection=binding[0];var run=selection.owner();var capture=run.captured();
        var choice=selection.choice();var composition=selection.interval();
        var fullRouteIndex=selection.full()==null?OptionalInt.empty():OptionalInt.of(selection.full().localRouteIndex());
        var memory=work.owner().child("original-native-root");
        try(var scope=org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.PostprocessingValidationWork.open(work);
                var scratch=work.reserve("native-root-preparation-scratch",RefinementLimits.defaults().maxPayloadBytes())) {
            work.visit(GeometryWorkContext.Kind.BIND,1);
            var assessment=composition==null?new ModernSingleWayEditPlanAdapter().assess(run,fullRouteIndex.orElseThrow()):null;
            var plan=composition==null?assessment.plan().orElse(null):composition.assessment().plan().orElse(null);
            if(plan==null&&(composition==null?assessment.availability()!=ModernSingleWayEditPlanAdapter.ApplyAvailability.NO_CHANGE:composition.assessment().noChangeProof().isEmpty()))
                throw new IllegalStateException("Native root plan/proof unavailable");
            var receipt=capture.originalReceipt().orElseThrow(()->new IllegalStateException("Original native capture receipt unavailable"));
            var context=new NativeRefinementSourceContext(capture,config,coordinator,visibleCapture);
            var hashes=new IdentityHashMap<Object,String>();
            work.reserveOn(memory,"native-original-hash-cache",32768).adopt(hashes);
            var source=NativeRefinementSourceContext.retain(run,settings,context,work,memory,hashes);
            context.bindOriginalInputHash(source.inputByteHash());
            var nativeFacts=new IdentityHashMap<Object,Long>();
            nativeFacts.put(hashes,32768L);
            nativeFacts.putAll(source.charges());
            var route=fullRouteIndex.isPresent()?run.pipeline().routes().get(fullRouteIndex.getAsInt()):null;
            nativeFacts.putAll(RefinementRootAuthority.detachedCharges(run.request(),route,run.options(),run.counters(),run.settingsResolutionJson()));
            if(composition!=null)addIntervalCharges(composition,nativeFacts,work);
            nativeFacts.put(run,512L);
            run.producedEvidenceProof().ifPresent(proof->nativeFacts.put(proof,256L));
            // The original review owns full inference diagnostics and unused raw palettes.
            nativeFacts.putAll(RefinementRootAuthority.detachedCharges(capture.specification(),capture.disconnectionSource(),capture.sourceGeographic(),capture.sourceMetric(),capture.outputGrid(),capture.alternativeMappings()));
            addCaptureCharges(capture,source.source().aggregate(),nativeFacts);
            capture.visibleInputReceipt().ifPresent(proof->nativeFacts.put(proof,proof.metadataBytes()));
            var authority=RefinementRootAuthority.fromNativeProduction(run,config,run.evidence().sourceIdentity(),source,work,memory,hashes)
                .withRetainedFacts(nativeFacts).withNativeAcquisitions(capture,List.of(run),Map.of(run,source),work,memory,hashes);
            for(var item:authority.charges().entrySet())admit(memory,work,item.getKey(),item.getValue());
            var way=(DetachedWay)capture.network().primitives().get(run.request().selectedWayKey());
            var nodes=way.nodeKeys().stream().map(k->{var p=((DetachedNode)capture.network().primitives().get(k)).coordinate();return new RefinementPayload.SourceNode(k.id(),p.latitudeDegrees(),p.longitudeDegrees());}).toList();
            var range=run.request().selectedRange();
            var provenance=new RefinementPayload.SourceSelection(way.key().id(),range.firstIndex(),range.lastIndex(),nodes,way.nodeKeys().subList(range.firstIndex(),range.lastIndex()+1).stream().map(PrimitiveKey::id).toList());
            var quality=route==null?NativeRefinementSourceContext.intervalQuality(run,composition.assessment(),work):route.quality();
            var request=source.source();
            var ids=route==null?NativeRefinementSourceContext.intervalPointIds(composition.assessment()):route.pointIds();
            RefinementPayload.NoChangeReview noChange=plan!=null?null:composition==null
                ?new RefinementPayload.RootNoChangeReview(run,fullRouteIndex.orElseThrow(),assessment,source)
                :new RefinementPayload.RootIntervalNoChangeReview(run,composition.routeChoices(),composition.assessment(),source,quality);
            var finalCandidate=noChange==null?choice.candidate():noChange.finalCandidate(choice.candidate());
            var attempt=new DetectorAttempt(capture.palette(),mapping(run),engine(),DetectorAttemptStatus.APPLICABLE,List.of(finalCandidate.id()),"","");
            var single=new AlignmentResult(original.selection(),null,List.of(finalCandidate),original.sourcePolyline(),finalCandidate.finalPreviewPoints(),List.of(),null,null,List.of(attempt),List.of(finalCandidate));
            var originalPayload=RefinementPayload.captureOriginal(single,provenance,RefinementLimits.defaults().maxPayloadBytes());
            String identity=composition==null?selection.full().reviewIdentity():ownerIdentity(run)+":"+(plan==null?composition.assessment().noChangeProof().orElseThrow().compositionIdentity():plan.routeIdentity());
            var rootState=new RefinementPayload.RootReviewState(ownerIdentity(run),fullRouteIndex,composition==null?Map.of():composition.routeChoices(),plan==null?"no-change:"+finalCandidate.id():plan.routeIdentity(),Optional.ofNullable(composition==null?null:composition.assessment()));
            var payload=(noChange==null?RefinementPayload.captureNative(single,originalPayload,new RefinementPayload.NativeReview(plan,quality,Optional.empty(),List.of(request),OptionalInt.empty(),Map.of(request,source),List.of(),Optional.of(ids)),RefinementLimits.defaults().maxPayloadBytes())
                :RefinementPayload.captureRootNoChange(single,originalPayload,noChange,RefinementLimits.defaults().maxPayloadBytes())).withRootReviewState(rootState);
            var points=finalCandidate.finalPreviewPoints().stream().map(p->{var ll=Projections.getProjectionByCode(capture.projectionCode()).eastNorth2latlon(p);return capture.outputGrid().coordinateFrame().toMetric(new GeographicPoint(ll.lat(),ll.lon()));}).toList();
            var arcs=LegacyRefinementBootstrap.correspondence(capture.network(),run.request().selectedWayKey(),run.request().selectedRange(),capture.sourceMetric(),capture.projectionCode(),capture.outputGrid().coordinateFrame(),finalCandidate,points);
            var candidate=new Candidate(new CandidateId(choice.id()),payload,arcs,identity,finalCandidate.id(),capture.palette());
            for(var charge:payload.publicationCharges().entrySet())admit(memory,work,charge.getKey(),charge.getValue());
            var stage=Stage.root(engine(),List.of(candidate),candidate.id());
            var retained=new IdentityHashMap<Object,Long>(authority.charges());
            retained.putAll(payload.charges());
            if(retained.values().stream().mapToLong(Long::longValue).sum()>RefinementLimits.defaults().maxPayloadBytes())
                throw new RefinementSession.Refused(RefusalReason.PAYLOAD_LIMIT);
            work.reserveOn(memory,"native-root-stage",1056L).adopt(stage);
            onEdt(()->{
                requireAuthorityCurrent();
                if(selected!=choice.candidate())throw new IllegalStateException("Native root selection changed during preparation");
            });
            work.visit(GeometryWorkContext.Kind.BIND,1);
            return new PreparedRefinementRoot(new LegacyRefinementBootstrap.Root(authority,stage,receipt),settings,single,context,memory,RefinementLimits.defaults().maxPayloadBytes());
        } catch(RuntimeException|Error failure){memory.close();throw failure;}
    }
    private static void admit(AttemptMemoryLedger.Owner owner,GeometryWorkContext work,Object identity,long bytes) {
        work.visit(GeometryWorkContext.Kind.BIND,1);
        try{owner.retainIfAbsent(identity);}catch(IllegalArgumentException absent){work.reserveOn(owner,"native-root-metadata",bytes).adopt(identity);}
    }
    private static void addCaptureCharges(LiveBPreviewService.Captured capture,boolean aggregate,Map<Object,Long> charges) {
        long metadata=8192L;
        if(capture.raster()!=null) {
            charges.put(capture,Math.addExact(metadata,4L*capture.raster().width()*capture.raster().height()));
            return;
        }
        charges.put(capture,metadata);
        var rasters=aggregate?capture.sourceRasters().palettes().values():List.of(capture.managedRaster());
        for(var raster:rasters) {
            long pixels=(long)raster.image().getWidth()*raster.image().getHeight();
            charges.put(raster,Math.addExact(4096L,pixels));
            charges.put(raster.image(),4L*pixels);
        }
    }
    private static void addIntervalCharges(IntervalSelection composition,Map<Object,Long> charges,GeometryWorkContext work) {
        var batch=composition.owner().intervalBatch();
        charges.put(batch,2048L+32L*batch.runs().size());
        for(var row:composition.assessment().intervals()) {
            work.visit(GeometryWorkContext.Kind.BIND,1);
            var intervalRun=batch.runs().get(row.intervalIndex());
            charges.put(intervalRun,256L);
            var route=intervalRun.routes().isEmpty()?null:intervalRun.routes().get(row.routeIndex());
            charges.putAll(RefinementRootAuthority.detachedCharges(intervalRun.interval(),intervalRun.request(),intervalRun.usage(),route));
        }
    }
    static long nativeReceiptBytes(CorridorExecutionReceipt receipt) {
        return nativeReceiptBytes(receipt,new RefinementRootAuthority.Inventory.Traversal(org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE,Long.MAX_VALUE,false),RefinementLimits.defaults().maxPayloadBytes());
    }
    static long nativeReceiptBytes(CorridorExecutionReceipt receipt,RefinementRootAuthority.Inventory.Traversal traversal,long maximumBytes) {
        var inventory=new RefinementRootAuthority.Inventory(traversal,Map.of(),maximumBytes);
        inventory.visit(receipt.requestHash());inventory.visit(receipt.evidenceHash());inventory.visit(receipt.networkHash());inventory.visit(receipt.resultHash());
        inventory.visit(receipt.networkSnapshotId());inventory.visit(receipt.settingsHash());inventory.visit(receipt.certificateStatus());inventory.visit(receipt.certificateIdentity());
        inventory.visit(receipt.sampling());inventory.visit(receipt.tracks());inventory.visit(receipt.totals());
        var values=inventory.ownedValues();
        long bytes=4096;for(long value:values.values())bytes=Math.addExact(bytes,value);
        for(var provenance:receipt.candidates().values()){traversal.allocate(1);bytes=Math.addExact(bytes,Math.addExact(1024L,8L*(provenance.hypothesisId().length()+provenance.trackId().length())));}
        if(receipt.originalCostPolicies().isPresent())bytes=Math.addExact(bytes,receipt.originalCostPolicyMetadataBytes().orElseThrow(()->new IllegalArgumentException("Native original cost ownership unavailable")));
        return bytes;
    }
    private static CenterlineCandidate intervalCandidate(IntervalSelection selection,org.openstreetmap.josm.data.projection.Projection projection) {
        if(selection.assessment().noChangeProof().isPresent()) {
            var proof=selection.assessment().noChangeProof().orElseThrow();proof.requireMatches(selection.owner().intervalBatch(),selection.routeChoices(),selection.owner().captured().network());
            var points=proof.finalSelection().selectedGeometry().stream().map(g->projection.latlon2eastNorth(new LatLon(g.latitudeDegrees(),g.longitudeDegrees()))).toList();
            var assignments=new LinkedHashMap<Long,org.openstreetmap.josm.data.coor.EastNorth>();proof.finalSelection().assignments().forEach((id,g)->assignments.put(id,projection.latlon2eastNorth(new LatLon(g.latitudeDegrees(),g.longitudeDegrees()))));
            return new CenterlineCandidate("native-interval-root",1,List.of(),List.of()).withFinalPreviewGeometry(points,assignments);
        }
        if(selection.assessment().plan().isEmpty()) {
            var points=selection.assessment().selectedWayPreview().stream()
                .map(g->projection.latlon2eastNorth(new LatLon(g.latitudeDegrees(),g.longitudeDegrees()))).toList();
            return new CenterlineCandidate("native-interval-root",0,List.of(),List.of())
                .withFinalPreviewGeometry(points,Map.of()).withSafetyWarnings(List.of("Native interval plan unavailable"));
        }
        var plan=selection.assessment().plan().orElseThrow();
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
