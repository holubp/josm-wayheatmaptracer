package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Exact observed-row provenance, accounting and composition independent of shape scoring. */
class CorridorObservedShoulderAdmissionTest {
    private static final int ROW=10;
    private record Case(CorridorTrack track,List<CorridorProfile> profiles,LongitudinalCorridorTube tube,
        Map<String,BandScaleEvidence> scales,Map<String,CorridorTrack> children,Optional<SparseCorridorBundle> bundle) { }

    @Test void directRestrictionPreservesOrderedBitsAndSurvivesBudgetCopiesAndGapMasks() {
        var f=CorridorGapDomainTest.fixture(); var c=direct(); var optimizer=CorridorGapDomainTest.guarded();
        var old=original(optimizer,c,f.context(),JunctionContext.empty(),TraceBudgets.defaults());
        var d=optimizer.prepareObservedShoulderDomain(old,c.children(),c.bundle()); var plan=d.observedPlan().orElseThrow();
        var expected=old.states().get(ROW).stream().filter(v->v>=-.8 && v<=-.2).toList();
        assertFalse(expected.isEmpty()); assertTrue(expected.size()<old.states().get(ROW).size());
        assertEquals(expected.stream().map(Double::doubleToRawLongBits).toList(),d.states().get(ROW).stream().map(Double::doubleToRawLongBits).toList());
        assertSame(old.states().get(9),d.states().get(9)); assertSame(old.track(),d.track()); assertSame(old.tube(),d.tube());
        assertEquals(old.budgets().maximumPairVisits()-old.states().get(ROW).size(),d.budgets().maximumPairVisits());
        assertEquals(old.states().get(ROW).size(),plan.work().statesExamined());
        assertEquals(old.states().get(ROW).size()-expected.size(),plan.work().removedStates());
        var copy=CorridorCenterlineOptimizer.withBudgets(d,d.budgets());
        assertSame(plan,copy.observedPlan().orElseThrow()); assertSame(d.states(),copy.states()); plan.requireBinding(copy);
        var expanded=optimizer.prepareGapDomain(copy,7.01);
        assertEquals(List.of(-.5,.5),expanded.states().get(12)); assertEquals(expected,expanded.states().get(ROW));
        plan.requireBinding(expanded); expanded.gapPlan().orElseThrow().requireBinding(expanded);
        var result=optimizer.optimizeOriginalDomain(expanded);
        assertFalse(result.offsetsPx().isEmpty()); assertTrue(result.offsetsPx().get(ROW)>=-.8 && result.offsetsPx().get(ROW)<=-.2);
        assertEquals(plan.work(),optimizer.preparedWork().observedWork().orElseThrow());
        assertEquals(old.states().stream().mapToLong(List::size).sum(),optimizer.preparedWork().originalStates());
        assertTrue(optimizer.hardGeometryWork().metricCacheReservedBytes()>=plan.work().retainedBytes()+expanded.gapPlan().orElseThrow().work().retainedBytes());
        assertThrows(IllegalArgumentException.class,()->plan.requireBinding(old));
        assertThrows(IllegalArgumentException.class,()->plan.requireBinding(original(optimizer,c,f.context(),JunctionContext.empty(),TraceBudgets.defaults())));
        assertThrows(UnsupportedOperationException.class,()->d.states().get(ROW).clear());
    }

    @Test void directAndMultiChildMeasuredParentsRequireExactContributorUnionIncludingHalfOccupancy() {
        for(boolean multi:List.of(false,true)) {
            var c=parent(multi); var optimizer=CorridorGapDomainTest.guarded();
            var old=original(optimizer,c,CorridorGapDomainTest.fixture().context(),JunctionContext.empty(),TraceBudgets.defaults());
            var d=optimizer.prepareObservedShoulderDomain(old,c.children(),c.bundle());
            assertEquals(CorridorObservedShoulderDomain.Disposition.RESTRICTED,row(d).disposition());
            assertEquals(multi?1:.5,c.bundle().orElseThrow().points().get(ROW).occupancy());
            assertTrue(d.states().get(ROW).stream().allMatch(v->v>=-.8 && v<=(multi?.5:-.2)));
        }
    }

    @Test void syntheticParentCoarseMergeCannotUseElementaryFinePersistenceException() {
        for(boolean multi:List.of(false,true)) {
            var c=parent(multi); var band=c.track().points().get(ROW).band(); var slices=new ArrayList<>(c.tube().slices()); var s=slices.get(ROW);
            slices.set(ROW,new CorridorTubeSlice(ROW,s.distanceMeters(),2,0,2,0,2,0,.2,0,"public-parent-merge",0,
                band.coreMinPx(),band.coreMaxPx(),band.shoulderMinPx(),band.shoulderMaxPx(),.2,.9,false,true,
                band.centerOffsetPx(),band.centerOffsetPx(),band.centerOffsetPx(),true));
            var scales=Map.of(CorridorCenterlineOptimizer.scaleEvidenceKey(ROW,band.id()),new BandScaleEvidence(.8,9999,1,List.of(0,1),false,true));
            assertUnchanged(new Case(c.track(),c.profiles(),new LongitudinalCorridorTube(slices),scales,c.children(),c.bundle()),
                JunctionContext.empty(),CorridorGapDomainTest.fixture().context());
        }
    }

    @Test void observedAdmissionExactWorkBudgetBoundaryRetainsActualWorkAndTypedResourceOutcome() {
        var c=direct(); var context=CorridorGapDomainTest.fixture().context(); var inventory=CorridorGapDomainTest.guarded();
        var reference=original(inventory,c,context,JunctionContext.empty(),TraceBudgets.defaults());
        long checks=reference.states().get(ROW).size(); assertTrue(checks>1);
        for(long cap:List.of(checks,checks-1,checks+1)) {
            var optimizer=CorridorGapDomainTest.guarded(); var domain=original(optimizer,c,context,JunctionContext.empty(),
                new TraceBudgets(96,cap,128_000_000,32,4));
            if(cap<=checks) {
                assertThrows(CorridorResourceLimitException.class,()->optimizer.prepareObservedShoulderDomain(domain,c.children(),c.bundle()),
                    "Exact membership exhaustion is resource refusal, not a zero-budget binding exception");
                var work=optimizer.preparedWork().observedWork().orElseThrow();
                assertEquals(cap,work.statesExamined()); assertEquals(0,optimizer.preparedWork().solverStarts());
                assertEquals("RESOURCE_LIMIT",optimizer.preparedWork().outcome());
                if(cap==checks) {
                    assertEquals(CorridorObservedShoulderDomain.Disposition.PREPARED,work.outcome(),"Restriction completed; optimizer admission exhausted");
                    assertEquals(domain.states().size(),work.rowsExamined()); assertEquals(domain.states().size(),work.rows().size());
                    assertEquals(1,work.restrictedRows()); assertTrue(work.removedStates()>0);
                } else assertEquals(CorridorObservedShoulderDomain.Disposition.RESOURCE_LIMIT,work.outcome(),"Restriction itself exhausted mid-row");
            } else {
                var result=optimizer.prepareObservedShoulderDomain(domain,c.children(),c.bundle());
                assertEquals(1,result.budgets().maximumPairVisits());
                assertEquals(checks,result.observedPlan().orElseThrow().work().statesExamined());
                assertEquals(1,result.observedPlan().orElseThrow().work().restrictedRows());
            }
        }
    }

    @Test void parentChildScaleConflictAndWrongMeasuredUnionFailClosed() {
        for(int variant=0;variant<3;variant++) {
            var c=parent(false); var b=c.bundle().orElseThrow(); var p=b.points().get(ROW);
            Map<String,BandScaleEvidence> scales=Map.of();
            if(variant==0)scales=Map.of(CorridorCenterlineOptimizer.scaleEvidenceKey(ROW,c.children().get("a").points().get(ROW).band().id()),
                new BandScaleEvidence(.8,Double.NaN,Double.NaN,List.of(0),true,false));
            else {
                var changed=new SparseCorridorBundlePoint(ROW,p.support(),p.directContributorTrackIds(),p.predictedContributorTrackIds(),
                    p.centerOffsetPx(),p.uncertaintyPx(),p.shoulderMinPx()-(variant==1?.1:0),p.shoulderMaxPx(),
                    p.coreMinPx()+(variant==2?.01:0),p.coreMaxPx(),p.occupancy(),p.contributorAgreement());
                b=new SparseCorridorBundle(b.id(),b.childTrackIds(),b.classification(),Map.of(ROW,changed),b.unionSupportRatio(),
                    b.jointSupportRatio(),b.valleyPersistence(),b.tangentAgreement(),b.orderStability(),b.robustSeparationPx(),b.reason());
            }
            assertUnchanged(new Case(c.track(),c.profiles(),c.tube(),scales,c.children(),Optional.of(b)),JunctionContext.empty(),CorridorGapDomainTest.fixture().context());
        }
    }

    @Test void persistenceCannotAuthorizeRestrictionWhenFineCoreOrShoulderIdentityDiffers() {
        for(boolean core:List.of(false,true)) {
            var c=direct(); var slices=new ArrayList<>(c.tube().slices()); var s=slices.get(ROW); var b=c.track().points().get(ROW).band();
            slices.set(ROW,new CorridorTubeSlice(ROW,s.distanceMeters(),2,0,2,0,2,0,.2,0,"public",0,
                b.coreMinPx()-(core?.01:0),b.coreMaxPx(),b.shoulderMinPx()-(core?0:.01),b.shoulderMaxPx(),.2,.9,false,true,-.5,-.5,-.5,true));
            var scales=Map.of(CorridorCenterlineOptimizer.scaleEvidenceKey(ROW,b.id()),new BandScaleEvidence(.8,9999,1,List.of(0,1),false,true));
            assertUnchanged(new Case(c.track(),c.profiles(),new LongitudinalCorridorTube(slices),scales,c.children(),c.bundle()),
                JunctionContext.empty(),CorridorGapDomainTest.fixture().context());
        }
    }

    @Test void sameTokenObservedRowMutationAndStaleGapStateIndexesFailBeforeNativeCosts() throws Exception {
        for(boolean gap:List.of(false,true)) {
            var c=direct(); var optimizer=CorridorGapDomainTest.guarded();
            var old=original(optimizer,c,CorridorGapDomainTest.fixture().context(),JunctionContext.empty(),TraceBudgets.defaults());
            var restricted=optimizer.prepareObservedShoulderDomain(old,c.children(),c.bundle());
            if(gap)restricted=optimizer.prepareGapDomain(restricted,7.01);
            var forged=CorridorCenterlineOptimizer.withBudgets(restricted,restricted.budgets());
            var rows=new ArrayList<>(forged.states()); int changed=gap?12:ROW;
            var offsets=new ArrayList<>(rows.get(changed));
            if(gap)Collections.reverse(offsets); else offsets.set(0,Math.nextUp(offsets.get(0)));
            rows.set(changed,List.copyOf(offsets));
            var statesField=CorridorCenterlineOptimizer.OriginalDomain.class.getDeclaredField("states"); statesField.setAccessible(true);
            statesField.set(forged,List.copyOf(rows));
            assertSame(restricted.token(),forged.token());
            assertThrows(IllegalArgumentException.class,()->optimizer.optimizeOriginalDomain(forged));
            assertEquals(0,optimizer.preparedWork().solverStarts()); assertEquals(0,optimizer.hardGeometryWork().profileCostEvaluations());
        }
    }

    @Test void missingWrongPredictedDuplicatedAndCensoredParentContributorsCannotAuthorizeRestriction() {
        for(int variant=0;variant<7;variant++) {
            var c=parent(false); var children=new LinkedHashMap<>(c.children()); var bundle=c.bundle().orElseThrow(); var point=bundle.points().get(ROW);
            if(variant==0)children.remove("a");
            if(variant==1)children.put("a",new CorridorTrack("wrong",children.get("a").points(),1,1,false,List.of(),""));
            if(variant==2 || variant==3) {
                point=new SparseCorridorBundlePoint(ROW,CorridorPointSupport.DIRECT_UNION,
                    variant==3?List.of("a","a"):List.of("a"),variant==2?List.of("b"):List.of(),
                    point.centerOffsetPx(),point.uncertaintyPx(),point.shoulderMinPx(),point.shoulderMaxPx(),point.coreMinPx(),point.coreMaxPx(),.5,1);
                bundle=new SparseCorridorBundle(bundle.id(),bundle.childTrackIds(),"combined",Map.of(ROW,point),1,0,0,1,1,0,"public");
            }
            if(variant==4)bundle=new SparseCorridorBundle("other",bundle.childTrackIds(),"combined",bundle.points(),1,0,0,1,1,0,"public");
            if(variant>=5) {
                var child=children.get("a"); var b=child.points().get(ROW).band();
                var altered=copyBand(b,b.id(),b.shoulderMinPx(),b.shoulderMaxPx(),false,
                    variant==5?CorridorBand.BoundaryCompleteness.CORE_CENSORED:CorridorBand.BoundaryCompleteness.COMPLETE);
                var cp=new CorridorTrackPoint(variant==6?ROW+1:ROW,altered,false);
                children.put("a",new CorridorTrack("a",Map.of(ROW,cp),1,1,false,List.of(),""));
            }
            var changed=new Case(c.track(),c.profiles(),c.tube(),c.scales(),children,Optional.of(bundle));
            assertUnchanged(changed,JunctionContext.empty(),CorridorGapDomainTest.fixture().context());
        }
    }

    @Test void conflictCensorMotionBoundaryProtectedAndFixedRowsStayExact() {
        var f=CorridorGapDomainTest.fixture();
        for(int variant=0;variant<7;variant++) {
            var c=direct(); var slices=new ArrayList<>(c.tube().slices()); var s=slices.get(ROW); var band=c.track().points().get(ROW).band();
            slices.set(ROW,slice(s,variant==0?.01:0,variant==1,variant==2,variant==3?band.shoulderMinPx():2));
            var scales=variant==2?Map.of(CorridorCenterlineOptimizer.scaleEvidenceKey(ROW,band.id()),
                new BandScaleEvidence(.8,99,1,List.of(0,1),false,true)):c.scales();
            if(variant==4)scales=Map.of(CorridorCenterlineOptimizer.scaleEvidenceKey(ROW,band.id()),new BandScaleEvidence(.8,99,1,List.of(0,1),true,false));
            c=new Case(c.track(),c.profiles(),new LongitudinalCorridorTube(slices),scales,c.children(),c.bundle());
            var junctions=variant==5?new JunctionContext(List.of(new EndpointConstraint(ROW,0,true,false,0,0,0))):JunctionContext.empty();
            var context=variant==6?CorridorGapDomainTest.context(f.evidence(),List.of(15.0)):f.context();
            // Exact fine-child merge (variant2) is permitted despite a distant non-authoritative coarse center.
            if(variant==2) {
                var optimizer=CorridorGapDomainTest.guarded(); var old=original(optimizer,c,context,junctions,TraceBudgets.defaults());
                assertEquals(CorridorObservedShoulderDomain.Disposition.RESTRICTED,row(optimizer.prepareObservedShoulderDomain(old,c.children(),c.bundle())).disposition());
            } else assertUnchanged(c,junctions,context);
        }
    }

    @Test void noCertificateIsTypedSkipWithExactImmutableStatesAndNoMembershipOrRetainedRows() {
        var c=direct(); var optimizer=CorridorGapDomainTest.guarded();
        var old=original(optimizer,c,null,JunctionContext.empty(),TraceBudgets.defaults());
        var d=optimizer.prepareObservedShoulderDomain(old,c.children(),c.bundle()); var work=d.observedPlan().orElseThrow().work();
        assertSame(old.states(),d.states()); assertEquals(0,work.statesExamined()); assertEquals(0,work.retainedBytes());
        assertEquals(CorridorObservedShoulderDomain.Disposition.SKIPPED_NO_BOUND_SOURCE_AUTHORITY,work.outcome());
        assertEquals(old.budgets(),d.budgets());
    }

    @Test void observedOnlyInferenceRejectsForeignOptimizerFrameBeforeAnyCosts() {
        var c=direct(); var optimizer=CorridorGapDomainTest.guarded();
        var old=original(optimizer,c,CorridorGapDomainTest.fixture().context(),JunctionContext.empty(),TraceBudgets.defaults());
        var restricted=optimizer.prepareObservedShoulderDomain(old,c.children(),c.bundle());
        var wrong=new CorridorCenterlineOptimizer().withLocalHardBacktrackGuard(
            RasterMetricTransform.metricGrid(new MetricPoint(100,100),0,1.5,-1.5,0),CancellationProbe.NONE);
        assertThrows(IllegalArgumentException.class,()->wrong.optimizeOriginalDomain(restricted));
        assertEquals(0,wrong.preparedWork().solverStarts());
        assertEquals(0,wrong.hardGeometryWork().profileCostEvaluations());
    }

    @Test void actualCensoredMissingUnsupportedApproachAndNonfiniteEvidenceRemainUnchanged() throws Exception {
        var c=direct(); var optimizer=CorridorGapDomainTest.guarded();
        var original=original(optimizer,c,CorridorGapDomainTest.fixture().context(),JunctionContext.empty(),TraceBudgets.defaults());
        for(int variant=0;variant<7;variant++) {
            var profiles=new ArrayList<>(original.profiles()); var points=new LinkedHashMap<>(original.track().points());
            var slices=new ArrayList<>(original.tube().slices()); var approaches=original.approaches();
            var p=profiles.get(ROW); var band=points.get(ROW).band();
            if(variant==0) {
                var censored=new CorridorBand(band.id(),band.centerOffsetPx(),band.shoulderMinPx(),band.shoulderMaxPx(),band.coreMinPx(),band.coreMaxPx(),
                    band.nestedCentersPx(),band.peakIntensity(),band.noiseFloor(),band.valleyRatio(),band.gradientStrength(),band.gradientBalance(),band.scaleAgreement(),
                    band.signalExistenceConfidence(),band.localizationConfidence(),band.uncertaintyPx(),false,List.of(),CorridorBand.BoundaryCompleteness.SHOULDER_CENSORED,CorridorBand.BoundarySide.RIGHT);
                points.put(ROW,new CorridorTrackPoint(ROW,censored,false));
                profiles.set(ROW,new CorridorProfile(ROW,p.source(),List.of(censored),p.maxIntensity(),p.noiseFloor(),p.prominence(),true));
            }
            if(variant==1)profiles.set(ROW,new CorridorProfile(ROW,p.source(),List.of(),p.maxIntensity(),p.noiseFloor(),p.prominence(),true));
            if(variant==2)profiles.set(ROW,new CorridorProfile(ROW,p.source(),p.bands(),p.maxIntensity(),p.noiseFloor(),p.prominence(),false));
            if(variant==3)approaches=new EndpointApproachModel(List.of(new EndpointApproachModel.EndpointApproach(0,1,ROW,true,"public-guide",
                List.of(new EndpointApproachModel.GuideTarget(ROW,0,1,false)))));
            if(variant==4 || variant==5) {
                var s=slices.get(ROW);
                slices.set(ROW,new CorridorTubeSlice(ROW,s.distanceMeters(),variant==4?Double.NaN:s.centerOffsetPx(),0,s.localCenterOffsetPx(),0,
                    s.stabilityCenterOffsetPx(),0,s.stabilityUncertaintyPx(),0,"public",0,s.coreMinPx(),s.coreMaxPx(),s.shoulderMinPx(),s.shoulderMaxPx(),
                    s.uncertaintyPx(),s.confidence(),false,false,s.rawCenterPx(),s.lightCenterPx(),s.standardCenterPx(),variant!=5));
            }
            if(variant==6)points.put(ROW,new CorridorTrackPoint(ROW,band,false,CorridorPointSupport.BOUNDED_INTERPOLATION));
            var track=new CorridorTrack(original.track().id(),points,1,1,false,List.of(),"");
            var changed=copyDomain(original,original.states(),profiles,track,new LongitudinalCorridorTube(slices),approaches);
            var result=optimizer.prepareObservedShoulderDomain(changed,Map.of(track.id(),track),Optional.empty());
            assertEquals(original.states(),result.states(),"Unsafe variant "+variant);
            assertEquals(0,result.observedPlan().orElseThrow().work().statesExamined());
        }
    }

    @Test void everyNonfiniteBandScalarOrNestedCenterRefusesDirectAndParentChildAuthority() throws Exception {
        var fields=Arrays.stream(CorridorBand.class.getRecordComponents()).filter(c->c.getType()==double.class || c.getName().equals("nestedCentersPx")).toList();
        for(boolean parent:List.of(false,true))for(var field:fields) {
            var c=parent?parent(false):direct(); var optimizer=CorridorGapDomainTest.guarded();
            var original=original(optimizer,c,CorridorGapDomainTest.fixture().context(),JunctionContext.empty(),TraceBudgets.defaults());
            var child=parent?c.children().get("a"):c.track(); var band=child.points().get(ROW).band();
            var components=CorridorBand.class.getRecordComponents(); var types=Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);
            var args=new Object[components.length];
            for(int i=0;i<args.length;i++)args[i]=components[i].getName().equals(field.getName())?
                (field.getType()==double.class?Double.NaN:List.of(Double.NaN)):components[i].getAccessor().invoke(band);
            var invalid=CorridorBand.class.getDeclaredConstructor(types).newInstance(args);
            var profiles=new ArrayList<>(original.profiles()); var p=profiles.get(ROW);
            profiles.set(ROW,new CorridorProfile(ROW,p.source(),p.bands().stream().map(b->b.equals(band)?invalid:b).toList(),p.maxIntensity(),p.noiseFloor(),p.prominence(),true));
            var points=new LinkedHashMap<>(child.points()); points.put(ROW,new CorridorTrackPoint(ROW,invalid,false));
            var changedChild=new CorridorTrack(child.id(),points,child.score(),child.supportRatio(),child.parent(),child.childTrackIds(),child.groupingDecision());
            var children=new LinkedHashMap<>(c.children()); children.put(changedChild.id(),changedChild);
            var changed=copyDomain(original,original.states(),profiles,parent?c.track():changedChild,original.tube(),original.approaches());
            var result=optimizer.prepareObservedShoulderDomain(changed,children,c.bundle());
            assertEquals(original.states(),result.states(),"Nonfinite "+field.getName()+" parent="+parent);
            assertEquals(0,result.observedPlan().orElseThrow().work().statesExamined(),"No authority from nonfinite "+field.getName()+" parent="+parent);
            if(!parent)assertEquals(CorridorObservedShoulderDomain.Disposition.INELIGIBLE_NONFINITE,row(result).disposition());
        }
    }

    @Test void cancellationAndWorkRefusalKeepActualPartialMembershipWithoutStartingSolver() {
        var c=direct(); var context=CorridorGapDomainTest.fixture().context(); var plain=CorridorGapDomainTest.guarded();
        var d=original(plain,c,context,JunctionContext.empty(),TraceBudgets.defaults());
        var count=new AtomicInteger(); var spent=new ArrayList<CorridorObservedShoulderDomain.Work>();
        // Inventory30 checkpoints + row0..10 checkpoints + one completed membership.
        assertThrows(CancellationException.class,()->CorridorObservedShoulderDomain.restrict(d,c.children(),c.bundle(),()->count.incrementAndGet()==43,spent::add));
        var partial=spent.get(spent.size()-1); assertEquals(1,partial.statesExamined()); assertEquals(11,partial.rowsExamined());
        assertEquals(10,partial.rows().size(),"Completed row diagnostics exclude the currently examined partial row");
        assertEquals(CorridorObservedShoulderDomain.Disposition.CANCELLED,partial.outcome());
        var tiny=CorridorCenterlineOptimizer.withBudgets(d,new TraceBudgets(96,1,128_000_000,32,4));
        assertThrows(CorridorResourceLimitException.class,()->plain.prepareObservedShoulderDomain(tiny,c.children(),c.bundle()));
        assertEquals(1,plain.preparedWork().observedWork().orElseThrow().statesExamined());
        assertEquals(0,plain.preparedWork().solverStarts());
    }

    @Test void emptyIntersectionRefusesWithoutInventingAStateAndMemoryRefusalDoesNoMembership() throws Exception {
        var c=direct(); var optimizer=CorridorGapDomainTest.guarded();
        var old=original(optimizer,c,CorridorGapDomainTest.fixture().context(),JunctionContext.empty(),TraceBudgets.defaults());
        var rows=new ArrayList<>(old.states()); rows.set(ROW,List.of(2.0,3.0));
        var empty=copyStates(old,rows); var refused=optimizer.prepareObservedShoulderDomain(empty,c.children(),c.bundle());
        assertEquals(rows,refused.states());
        assertEquals(CorridorObservedShoulderDomain.Disposition.REFUSED_EMPTY_DOMAIN,row(refused).disposition());
        assertEquals(2,refused.observedPlan().orElseThrow().work().statesExamined());
        assertEquals(0,refused.observedPlan().orElseThrow().work().restrictedRows());
        var large=new ArrayList<List<Double>>();
        for(int i=0;i<old.states().size();i++)large.add(java.util.stream.IntStream.range(0,400).mapToObj(v->v/100.0).toList());
        var huge=copyStates(old,large);
        assertThrows(CorridorResourceLimitException.class,()->optimizer.prepareObservedShoulderDomain(huge,c.children(),c.bundle()));
        assertEquals(0,optimizer.preparedWork().observedWork().orElseThrow().statesExamined());
        assertEquals(0,optimizer.preparedWork().solverStarts());
    }

    private static CorridorCenterlineOptimizer.OriginalDomain copyStates(CorridorCenterlineOptimizer.OriginalDomain d,List<List<Double>> states) throws Exception {
        return copyDomain(d,states,d.profiles(),d.track(),d.tube(),d.approaches());
    }
    private static CorridorCenterlineOptimizer.OriginalDomain copyDomain(CorridorCenterlineOptimizer.OriginalDomain d,List<List<Double>> states,
        List<CorridorProfile> profiles,CorridorTrack track,LongitudinalCorridorTube tube,EndpointApproachModel approaches) throws Exception {
        var ctor=Arrays.stream(CorridorCenterlineOptimizer.OriginalDomain.class.getDeclaredConstructors()).filter(c->c.getParameterCount()==13).findFirst().orElseThrow();
        ctor.setAccessible(true);
        var work=CorridorCenterlineOptimizer.OriginalDomain.class.getDeclaredField("generationWork"); work.setAccessible(true);
        return (CorridorCenterlineOptimizer.OriginalDomain)ctor.newInstance(track,profiles,d.sourcePixel(),d.junctions(),d.scales(),
            tube,approaches,states,d.budgets(),d.context(),d.token(),work.get(d),null);
    }

    private static void assertUnchanged(Case c,JunctionContext junctions,CorridorWorkingSchedule.BoundContext context) {
        var optimizer=CorridorGapDomainTest.guarded(); var old=original(optimizer,c,context,junctions,TraceBudgets.defaults());
        var d=optimizer.prepareObservedShoulderDomain(old,c.children(),c.bundle());
        assertEquals(old.states(),d.states()); assertEquals(0,d.observedPlan().orElseThrow().work().statesExamined());
    }
    private static CorridorObservedShoulderDomain.Row row(CorridorCenterlineOptimizer.OriginalDomain d) {
        return d.observedPlan().orElseThrow().work().rows().stream().filter(r->r.originalRow()==ROW).findFirst().orElseThrow();
    }
    private static CorridorCenterlineOptimizer.OriginalDomain original(CorridorCenterlineOptimizer optimizer,Case c,
        CorridorWorkingSchedule.BoundContext context,JunctionContext junctions,TraceBudgets budgets) {
        return optimizer.prepareOriginalDomain(c.track(),c.profiles(),1,junctions,c.scales(),c.tube(),budgets,context);
    }
    private static Case direct() {
        var f=CorridorGapDomainTest.fixture(); var slices=new ArrayList<>(f.tube().slices());
        slices.set(ROW,slice(slices.get(ROW),0,false,false,2));
        return new Case(f.track(),f.profiles(),new LongitudinalCorridorTube(slices),Map.of(),Map.of(f.track().id(),f.track()),Optional.empty());
    }
    private static CorridorTubeSlice slice(CorridorTubeSlice s,double motion,boolean conflict,boolean parent,double center) {
        var band=CorridorGapDomainTest.fixture().track().points().get(ROW).band();
        return new CorridorTubeSlice(s.profileIndex(),s.distanceMeters(),center,0,center,0,center,0,.2,motion,"public",0,
            band.coreMinPx(),band.coreMaxPx(),band.shoulderMinPx(),band.shoulderMaxPx(),.2,.9,conflict,parent,-.5,-.5,-.5,true);
    }
    private static Case parent(boolean multi) {
        var c=direct(); var child=c.track().points().get(ROW).band();
        var second=copyBand(child,"other-child",-.1,.5,false,CorridorBand.BoundaryCompleteness.COMPLETE);
        var parent=new CorridorBand("parent-band",multi?-.15:child.centerOffsetPx(),-.8,multi?.5:-.2,child.coreMinPx(),multi?second.coreMaxPx():child.coreMaxPx(),
            List.of(child.centerOffsetPx()),.9,.01,0,.9,.9,.2,.9,.9,.2,true,multi?List.of(child.id(),second.id()):List.of(child.id()));
        var a=new CorridorTrack("a",Map.of(ROW,new CorridorTrackPoint(ROW,child,false)),1,1,false,List.of(),"");
        var b=new CorridorTrack("b",multi?Map.of(ROW,new CorridorTrackPoint(ROW,second,false)):Map.of(),1,1,false,List.of(),"");
        var points=new LinkedHashMap<>(c.track().points()); points.put(ROW,new CorridorTrackPoint(ROW,parent,false));
        var track=new CorridorTrack("parent",points,1,1,true,List.of("a","b"),"combined");
        var profile=c.profiles().get(ROW); var profiles=new ArrayList<>(c.profiles());
        profiles.set(ROW,new CorridorProfile(ROW,profile.source(),multi?List.of(child,second):List.of(child),profile.maxIntensity(),profile.noiseFloor(),profile.prominence(),true));
        var point=new SparseCorridorBundlePoint(ROW,CorridorPointSupport.DIRECT_UNION,multi?List.of("a","b"):List.of("a"),List.of(),
            parent.centerOffsetPx(),.2,parent.shoulderMinPx(),parent.shoulderMaxPx(),parent.coreMinPx(),parent.coreMaxPx(),multi?1:.5,1);
        var bundle=new SparseCorridorBundle("parent",List.of("a","b"),"combined",Map.of(ROW,point),1,multi?1:0,0,1,1,0,"public");
        return new Case(track,profiles,c.tube(),Map.of(),Map.of("a",a,"b",b),Optional.of(bundle));
    }
    private static CorridorBand copyBand(CorridorBand b,String id,double low,double high,boolean parent,CorridorBand.BoundaryCompleteness completeness) {
        double center=(low+high)/2;
        return new CorridorBand(id,center,low,high,center-.1,center+.1,List.of(center),b.peakIntensity(),b.noiseFloor(),b.valleyRatio(),
            b.gradientStrength(),b.gradientBalance(),b.scaleAgreement(),b.signalExistenceConfidence(),b.localizationConfidence(),b.uncertaintyPx(),
            parent,parent?List.of(b.id()):List.of(),completeness,completeness==CorridorBand.BoundaryCompleteness.COMPLETE?CorridorBand.BoundarySide.NONE:CorridorBand.BoundarySide.RIGHT);
    }
}
