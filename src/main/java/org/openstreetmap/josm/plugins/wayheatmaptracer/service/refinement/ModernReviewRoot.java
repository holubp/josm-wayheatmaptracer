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
    private final IntervalSelection interval;
    private final List<LegacyReviewOwner.Choice> choices;
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
        if(!Set.of(TrackerMode.CORRIDOR_AWARE,TrackerMode.PROBABILISTIC).contains(owner.request().engine())
                ||owner.request().engine()!=config.heatmap().trackerMode()||owner.request().geometryMode()!=config.heatmap().alignmentMode())
            throw new IllegalArgumentException("Native root engine or mode differs from original capture");
        for(var choice:this.nativeChoices)if(choice.owner().captured()!=owner.captured()||choice.owner().request().engine()!=owner.request().engine())
            throw new IllegalArgumentException("Native root choices have foreign owners");
        hostCurrent.run();
        var projection=Projections.getProjectionByCode(owner.captured().projectionCode());
        var candidates=interval==null?this.nativeChoices.stream().map(LiveBPreviewService.PreviewChoice::candidate).toList():
            List.of(new CenterlineCandidate("native-interval-root",1,List.of(),List.of()).withFinalPreviewPoints(interval.assessment().selectedWayPreview().stream().map(p->projection.latlon2eastNorth(new LatLon(p.latitudeDegrees(),p.longitudeDegrees()))).toList()));
        selected=candidates.get(0);
        choices=java.util.stream.IntStream.range(0,candidates.size()).mapToObj(i->new LegacyReviewOwner.Choice("native-choice-"+i,candidates.get(i))).toList();
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
    public void select(CenterlineCandidate candidate){observer.select(candidate);selected=candidate;}
    public String choiceId(CenterlineCandidate candidate){return choices.stream().filter(c->c.candidate()==candidate).findFirst().orElseThrow(()->new IllegalArgumentException("Foreign native choice")).id();}
    public CenterlineCandidate candidateForChoice(String id){return choices.stream().filter(c->c.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown native choice")).candidate();}
    public void requireOriginalAttemptCurrent(){observer.requireOriginalAttemptCurrent();hostCurrent.run();}
    public void requireAuthorityCurrent(){requireOriginalAttemptCurrent();source().requireCurrent();}
    public boolean invalidated(){return observer.invalidated();}
    public String sourceIdentity(){return sourceIdentity;}
    public void close(){observer.close();}
    private LiveBPreviewService.Computed firstOwner(){return interval==null?nativeChoices.get(0).owner():interval.owner();}
    private NativeRefinementSourceContext source(){return new NativeRefinementSourceContext(firstOwner().captured(),config,coordinator);}

    /** Worker preparation retains factual native plans/evidence, without inference or new capture. */
    public PreparedRefinementRoot prepare(GeometryWorkContext work) {
        if(SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Native root preparation requires worker thread");
        final String[] selectedId=new String[1];onEdt(()->{requireAuthorityCurrent();selectedId[0]=choiceId(selected);});
        var memory=work.owner().child("original-native-root");
        try(var scratch=work.reserve("native-root-preparation-scratch",RefinementLimits.defaults().maxPayloadBytes())) {
            work.visit(GeometryWorkContext.Kind.BIND,1);
            var primary=firstOwner();var capture=primary.captured();
            var receipt=capture.originalReceipt().orElseThrow(()->new IllegalStateException("Original native capture receipt unavailable"));
            var context=source();
            var retainedSources=new IdentityHashMap<LiveBPreviewService.Computed,RetainedSmoothingSource>();
            var primarySource=NativeRefinementSourceContext.retain(primary,settings,context,work,memory);retainedSources.put(primary,primarySource);
            var authority=new RefinementRootAuthority(capture.network(),primary.request(),primary.evidence(),config,capture.projectionCode(),sourceIdentity,capture.intervalPartition(),primarySource,work,memory);
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
                var quality=interval==null?run.pipeline().routes().get(nativeChoices.get(index).localRouteIndex()).quality():NativeRefinementSourceContext.intervalQuality(run,interval.assessment());
                var source=retainedSources.computeIfAbsent(run,r->NativeRefinementSourceContext.retain(r,settings,context,work,memory));
                var request=source.source();
                var ids=interval==null?run.pipeline().routes().get(nativeChoices.get(index).localRouteIndex()).pointIds():List.copyOf(interval.assessment().assignments().keySet());
                var nativeReview=new RefinementPayload.NativeReview(plan,quality,Optional.empty(),List.of(request),OptionalInt.empty(),Map.of(request,source),List.of(),Optional.of(ids));
                var single=new AlignmentResult(original.selection(),null,List.of(choice.candidate()),original.sourcePolyline(),choice.candidate().finalPreviewPoints(),List.of(),null,null,original.detectorAttempts().stream().filter(a->a.candidateIds().contains(choice.candidate().id())).toList(),List.of(choice.candidate()));
                String identity=interval==null?nativeChoices.get(index).reviewIdentity():plan.routeIdentity();
                var rootState=new RefinementPayload.RootReviewState(identity,interval==null?OptionalInt.of(nativeChoices.get(index).localRouteIndex()):OptionalInt.empty(),interval==null?Map.of():interval.routeChoices(),plan.routeIdentity(),Optional.ofNullable(interval==null?null:interval.assessment()));
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
            return new PreparedRefinementRoot(new LegacyRefinementBootstrap.Root(authority,stage,receipt),settings,original,source(),memory,RefinementLimits.defaults().maxPayloadBytes());
        } catch(RuntimeException|Error failure){memory.close();throw failure;}
    }
    private static void admit(AttemptMemoryLedger.Owner owner,GeometryWorkContext work,Object identity,long bytes) {
        work.visit(GeometryWorkContext.Kind.BIND,1);
        try{owner.retainIfAbsent(identity);}catch(IllegalArgumentException absent){work.reserveOn(owner,"native-root-metadata",bytes).adopt(identity);}
    }
    private static void onEdt(Runnable action) {
        if(SwingUtilities.isEventDispatchThread()){action.run();return;}
        try{SwingUtilities.invokeAndWait(action);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Native root preparation interrupted");}
        catch(java.lang.reflect.InvocationTargetException failure){if(failure.getCause() instanceof RuntimeException runtime)throw runtime;if(failure.getCause() instanceof Error error)throw error;throw new IllegalStateException("Native root validation failed");}
    }
}
