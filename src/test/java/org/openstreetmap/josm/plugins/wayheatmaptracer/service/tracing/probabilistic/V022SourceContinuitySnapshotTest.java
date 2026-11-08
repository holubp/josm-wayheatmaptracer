package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Public scalar feature/evaluation tests only. Never constructs an inference engine. */
class V022SourceContinuitySnapshotTest {
    static final List<MetricPoint> SOURCE=List.of(new MetricPoint(0,0),new MetricPoint(20,0));
    static EvidenceSnapshot scene(){return scene(0);}
    static EvidenceSnapshot scene(int variant) {
        int w=81,h=81;double[] values=new double[w*h];boolean[] valid=new boolean[w*h];Arrays.fill(valid,true);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            double lateral=-20+y*.5,longitudinal=-10+x*.5,centre=variant==1?.4*(longitudinal-10):3;
            values[y*w+x]=.02+.7*StrictMath.exp(-.5*StrictMath.pow((lateral-centre)/.8,2))+.5*StrictMath.exp(-.5*StrictMath.pow((lateral+centre)/.8,2));
            if(variant==2&&longitudinal>=8&&longitudinal<=12)values[y*w+x]=.02;
            if(variant==3&&(longitudinal<-.5||longitudinal>20.5))valid[y*w+x]=false;
            values[y*w+x]=Math.min(1,values[y*w+x]);
        }
        var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"continuity-public",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false);
        var field=new ScalarEvidenceField(w,h,values,valid,lineage);
        return new EvidenceSnapshot("continuity-public",LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),new GeographicPoint(-1,-1),new GeographicPoint(1,1)),
            new RasterMetricTransform("continuity-public",RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,new MetricPoint(-10,-20),.5,0,0,.5,1),EvidenceResolution.nativeSource(1,.5),
            MetricRegion.rectangle(-5,-10,25,10),MetricRegion.rectangle(-9,-19,29,19),Map.of("scalar",field),"continuity-public");
    }
    @Test void actualPublicFactoryPublishesSourceReceiptOnlyWhenExplicitlyRequested() {
        var evidence=scene();var f=new ProbabilisticProfileFactory();var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var result=f.createWithContinuity(SOURCE,f.profileChainage(SOURCE,2),10,false,evidence,evidence.fields().get("scalar"),EvidenceModelParameters.defaults(),CancellationProbe.NONE,owner);
            assertNotNull(result.snapshot(),"Explicit source producer must retain actual source facts");assertEquals(11,result.profiles().size());
        }finally{assertEquals(0,ledger.currentBytes());}
    }
    @Test void actualFactoryBuilderObservationChainBindsOriginalNativeCellsCostsAndIndices() {
        var evidence=scene();var f=new ProbabilisticProfileFactory();var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var produced=f.createWithContinuity(SOURCE,f.profileChainage(SOURCE,2),10,false,evidence,evidence.fields().get("scalar"),EvidenceModelParameters.defaults(),CancellationProbe.NONE,owner);
            List<ProbabilisticStateBuilder.OriginalDomain> originals=new ArrayList<>();List<ProbabilisticStateLattice> lattices=new ArrayList<>();List<InferenceProfile> evaluated=new ArrayList<>();
            for(int row=0;row<produced.profiles().size();row++) {
                var source=produced.profiles().get(row);var domain=new ProbabilisticStateBuilder().buildOriginalDomain(produced.snapshot(),row,96,CancellationProbe.NONE,owner);originals.add(domain);
                var lattice=domain.lattice();lattices.add(lattice);
                evaluated.add(new ProbabilisticObservationModel().evaluate(source,lattice,EvidenceModelParameters.defaults(),true,owner));
            }
            var bound=produced.snapshot().bind(originals,lattices,evaluated,CancellationProbe.NONE,owner);assertEquals(11,bound.rows().size());
            for(int row=0;row<11;row++) {
                var states=bound.rows().get(row).states();assertEquals(lattices.get(row).cells().size(),states.size());
                for(int state=0;state<states.size();state++) {
                    var actual=states.get(state);assertEquals(state,actual.originalIndex());assertSame(lattices.get(row).cells().get(state),actual.originalCell());
                    assertEquals(Double.doubleToRawLongBits(evaluated.get(row).unaryCost(state)),actual.unaryCostBits());assertEquals(evaluated.get(row).point(state),actual.point());
                }
                var source=produced.profiles().get(row);for(var band:bound.rows().get(row).bands()) {
                    var mode=source.modes().get(band.ref().localOrdinal());
                    var expected=new ArrayList<Integer>();var neighbours=new ArrayList<Integer>();for(int state=0;state<states.size();state++) {double d=states.get(state).originalCell().offsetMeters();if(d>=mode.coreMinimumMeters()&&d<=mode.coreMaximumMeters())expected.add(state);if(d>=Math.max(source.minimumOffsetMeters(),mode.coreMinimumMeters()-source.sourcePitchMeters())&&d<=Math.min(source.maximumOffsetMeters(),mode.coreMaximumMeters()+source.sourcePitchMeters()))neighbours.add(state);}
                    assertEquals(expected,band.coreIndices());assertEquals(neighbours,band.neighbourIndices());
                }
            }
        }finally{assertEquals(0,ledger.currentBytes());}
    }
    static SourceContinuitySnapshot.Produced produce(EvidenceSnapshot evidence,List<MetricPoint> source,double width,AttemptMemoryLedger.Owner owner,CancellationProbe cancellation) {
        var f=new ProbabilisticProfileFactory();return f.createWithContinuity(source,f.profileChainage(source,2),width,false,evidence,evidence.fields().get("scalar"),EvidenceModelParameters.defaults(),cancellation,owner);
    }
    @Test void ordinaryProfilesAndAllExistingFactoryWorkCountersRemainEqual() {
        var evidence=scene();var f=new ProbabilisticProfileFactory();List<ProbabilisticProfile> old;Map<String,Number> ordinary;
        var before=AttemptMemoryLedger.production();org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.begin();
        try(var owner=before.rootOwner()) {old=f.create(SOURCE,f.profileChainage(SOURCE,2),10,false,evidence,evidence.fields().get("scalar"),EvidenceModelParameters.defaults(),CancellationProbe.NONE,true,owner);ordinary=org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.snapshot();}
        finally{org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.end();assertEquals(0,before.currentBytes());}
        var ledger=AttemptMemoryLedger.production();org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.begin();
        try(var owner=ledger.rootOwner()) {
            var produced=produce(evidence,SOURCE,10,owner,CancellationProbe.NONE);assertEquals(old,produced.profiles());
            assertEquals(ordinary,org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.snapshot(),"Snapshot observer must not add sampling/feature/association work");
            var s=produced.snapshot();assertSame(evidence,s.evidence());assertSame(evidence.fields().get("scalar"),s.field());assertEquals(10,s.configuredHalfWidthMeters());
            assertTrue(s.adjacentRelations().stream().anyMatch(r->r.status()==SourceContinuitySnapshot.IntervalStatus.EVALUATED_CENTRE_LINK&&r.support().orElseThrow()>0));
            assertTrue(s.adjacentRelations().stream().filter(r->r.status()==SourceContinuitySnapshot.IntervalStatus.NOT_EVALUATED).allMatch(r->r.support().isEmpty()&&!r.mutualBest()));
            assertFalse(s.hasPersistentCertificates());assertEquals("SOURCE_ASSOCIATION_UNAVAILABLE",s.associationCapability(),"Positive centre links never certify an off-centre valley or persistent carrier");
        }finally{org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.end();assertEquals(0,ledger.currentBytes());}
    }
    @Test void actualCrossingGapUnknownAndCensoredObservationsNeverInventPortsOrGapCertificates() {
        for(int variant=0;variant<4;variant++) {
            var ledger=AttemptMemoryLedger.production();
            try(var owner=ledger.rootOwner()) {
                var produced=produce(scene(variant),SOURCE,variant==0?3:10,owner,CancellationProbe.NONE);var s=produced.snapshot();
                assertFalse(s.hasPersistentCertificates());assertEquals("SOURCE_ASSOCIATION_UNAVAILABLE",s.associationCapability());
                if(variant==0)assertTrue(s.profiles().stream().anyMatch(p->!p.censoredModes().isEmpty()),"Real boundary-censored observations retained");
                if(variant==1)assertTrue(s.profiles().stream().anyMatch(p->p.modes().size()>1),"Actual crossing rivals retained without supplied port certificates");
                if(variant==2)assertTrue(s.profiles().stream().anyMatch(p->p.modes().isEmpty()),"Actual missing-row evidence is not a gap certificate");
                if(variant==3)assertTrue(s.profiles().stream().flatMap(p->p.modes().stream()).anyMatch(m->m.orientationSupport().status()!=ImageOrientationSupport.Status.MEASURED_TWO_SIDED),()->"Real unknown-direction modes: "+s.profiles().stream().map(p->p.modes().stream().map(m->m.orientationSupport().status()).toList()).toList());
                for(var relation:s.adjacentRelations())if(!relation.mutualBest()){assertEquals(SourceContinuitySnapshot.IntervalStatus.NOT_EVALUATED,relation.status());assertTrue(relation.support().isEmpty());}
            }finally{assertEquals(0,ledger.currentBytes());}
        }
    }
    @Test void rotatedActualProfilesBindInTheirFactualFrameAndSourceCopiesStayImmutable() {
        var source=new ArrayList<>(List.of(new MetricPoint(0,0),new MetricPoint(10,0),new MetricPoint(10,10)));var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var produced=produce(scene(),source,10,owner,CancellationProbe.NONE);var s=produced.snapshot();source.clear();assertEquals(3,s.sourcePolyline().size());
            assertTrue(s.profiles().stream().map(ProbabilisticProfile::normalUnit).distinct().count()>1);
            List<ProbabilisticStateBuilder.OriginalDomain> originals=new ArrayList<>();List<ProbabilisticStateLattice> lattices=new ArrayList<>();List<InferenceProfile> profiles=new ArrayList<>();
            for(int row=0;row<s.profiles().size();row++){var p=s.profiles().get(row);var domain=new ProbabilisticStateBuilder().buildOriginalDomain(s,row,96,CancellationProbe.NONE,owner);originals.add(domain);var l=domain.lattice();lattices.add(l);profiles.add(new ProbabilisticObservationModel().evaluate(p,l,EvidenceModelParameters.defaults(),true,owner));}
            var bound=s.bind(originals,lattices,profiles,CancellationProbe.NONE,owner);var first=bound.rows().get(0).states().get(0);double[] copy=profiles.get(0).unaryCosts();copy[0]+=10;
            assertEquals(Double.doubleToRawLongBits(profiles.get(0).unaryCost(0)),first.unaryCostBits());
            assertThrows(UnsupportedOperationException.class,()->bound.rows().clear());assertThrows(UnsupportedOperationException.class,()->bound.rows().get(0).states().clear());assertThrows(UnsupportedOperationException.class,()->s.profiles().clear());
            for(int r=0;r<profiles.size();r++)for(var state:bound.rows().get(r).states())assertEquals(profiles.get(r).point(state.originalIndex()),state.point());
            var foreign=new ArrayList<>(profiles);Collections.swap(foreign,0,1);assertThrows(IllegalArgumentException.class,()->s.bind(originals,lattices,foreign,CancellationProbe.NONE,owner));
            var cut=new ArrayList<>(profiles);cut.set(0,profiles.get(0).retainedView(new int[]{0},owner));assertThrows(IllegalArgumentException.class,()->s.bind(originals,lattices,cut,CancellationProbe.NONE,owner));
        }finally{assertEquals(0,ledger.currentBytes());}
    }
    @Test void captureCancellationMemoryAndClosedScopePublishNoPartialAuthority() {
        var evidence=scene();for(int kind=0;kind<2;kind++) {
            var ledger=new AttemptMemoryLedger(kind==0?AttemptMemoryLedger.MAX_BYTES:64);int failure=kind;var checks=new java.util.concurrent.atomic.AtomicInteger();
            try(var owner=ledger.rootOwner()) {
                if(failure==0)assertThrows(java.util.concurrent.CancellationException.class,()->produce(evidence,SOURCE,10,owner,()->checks.incrementAndGet()>=40));
                else assertThrows(ProbabilisticInference.MemoryLimit.class,()->produce(evidence,SOURCE,10,owner,CancellationProbe.NONE));
                assertEquals(0,ledger.currentBytes());
            }finally{assertEquals(0,ledger.currentBytes());}
        }
        var ledger=AttemptMemoryLedger.production();SourceContinuitySnapshot snapshot;
        try(var owner=ledger.rootOwner()){snapshot=produce(evidence,SOURCE,10,owner,CancellationProbe.NONE).snapshot();}
        assertThrows(IllegalStateException.class,snapshot::profiles);try(var other=AttemptMemoryLedger.production().rootOwner()){assertThrows(IllegalStateException.class,()->snapshot.bind(List.of(),List.of(),CancellationProbe.NONE,other));}assertEquals(0,ledger.currentBytes());
    }
    @Test void binderRejectsForeignCellIdentityAndReleasesCancelledOrRefusedCopies() {
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var s=produce(scene(),SOURCE,10,owner,CancellationProbe.NONE).snapshot();List<ProbabilisticStateBuilder.OriginalDomain> originals=new ArrayList<>();List<ProbabilisticStateLattice> lattices=new ArrayList<>();List<InferenceProfile> actual=new ArrayList<>();
            for(int row=0;row<s.profiles().size();row++){var p=s.profiles().get(row);var domain=new ProbabilisticStateBuilder().buildOriginalDomain(s,row,96,CancellationProbe.NONE,owner);originals.add(domain);var l=domain.lattice();lattices.add(l);actual.add(new ProbabilisticObservationModel().evaluate(p,l,EvidenceModelParameters.defaults(),true,owner));}
            var foreign=new ArrayList<>(lattices);var original=lattices.get(0);var copied=original.cells().stream().map(c->new LateralStateCell(c.offsetMeters(),c.quadratureWidthMeters(),c.exactAnchor(),c.mandatory(),c.branchLabel())).toList();
            foreign.set(0,new ProbabilisticStateLattice(copied,original.components(),original.actualPitchMeters(),original.resolutionLimited()));
            long before=ledger.currentBytes();assertThrows(IllegalArgumentException.class,()->s.bind(originals,foreign,actual,CancellationProbe.NONE,owner));assertEquals(before,ledger.currentBytes());
            for(boolean cancel:List.of(false,true)) {
                var binding=new AttemptMemoryLedger(cancel?AttemptMemoryLedger.MAX_BYTES:32);
                try(var scope=binding.rootOwner()) {
                    if(cancel)assertThrows(java.util.concurrent.CancellationException.class,()->s.bind(originals,lattices,actual,()->true,scope));
                    else assertThrows(ProbabilisticInference.MemoryLimit.class,()->s.bind(originals,lattices,actual,CancellationProbe.NONE,scope));
                    assertEquals(0,binding.currentBytes());
                }
            }
            SourceContinuitySnapshot.BoundReceipt receipt;try(var scope=owner.child("binding-lifetime")){receipt=s.bind(originals,lattices,actual,CancellationProbe.NONE,scope);assertFalse(receipt.rows().isEmpty());}
            assertThrows(IllegalStateException.class,receipt::rows);
        }finally{assertEquals(0,ledger.currentBytes());}
    }
    @Test void selectionCopyPrecedesProducerCallbacksAndMatchesTheSampledSource() {
        var evidence=scene();var mutable=new ArrayList<>(SOURCE);var f=new ProbabilisticProfileFactory();var chainage=f.profileChainage(mutable,2);
        var ledger=AttemptMemoryLedger.production();var checkpoints=new java.util.concurrent.atomic.AtomicInteger();
        try(var owner=ledger.rootOwner()) {
            // Deterministic stand-in for a caller edit while the producer is working.
            CancellationProbe callback=()->{if(checkpoints.incrementAndGet()==2)mutable.set(1,new MetricPoint(30,0));return false;};
            var produced=f.createWithContinuity(mutable,chainage,10,false,evidence,evidence.fields().get("scalar"),EvidenceModelParameters.defaults(),callback,owner);
            assertEquals(SOURCE,produced.snapshot().sourcePolyline());assertEquals(11,produced.profiles().size());
            assertEquals(new MetricPoint(20,0),produced.profiles().get(10).anchor());assertEquals(new MetricPoint(30,0),mutable.get(1));
        }finally{assertEquals(0,ledger.currentBytes());}
    }
    @Test void pairedGenuineRetainedDomainsCannotRenumberOriginalIndexAsZero() {
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var snapshot=produce(scene(),SOURCE,10,owner,CancellationProbe.NONE).snapshot();List<ProbabilisticStateBuilder.OriginalDomain> domains=new ArrayList<>();List<ProbabilisticStateLattice> original=new ArrayList<>();List<InferenceProfile> evaluated=new ArrayList<>();
            for(int r=0;r<snapshot.profiles().size();r++){var p=snapshot.profiles().get(r);var domain=new ProbabilisticStateBuilder().buildOriginalDomain(snapshot,r,96,CancellationProbe.NONE,owner);domains.add(domain);var l=domain.lattice();original.add(l);evaluated.add(new ProbabilisticObservationModel().evaluate(p,l,EvidenceModelParameters.defaults(),true,owner));}
            int row=1,k=1;var lattice=original.get(row);assertTrue(lattice.cells().size()>k);
            var retainedLattices=new ArrayList<>(original);retainedLattices.set(row,new ProbabilisticStateLattice(List.of(lattice.cells().get(k)),lattice.components(),lattice.actualPitchMeters(),lattice.resolutionLimited()));
            var retainedEvaluated=new ArrayList<>(evaluated);retainedEvaluated.set(row,evaluated.get(row).retainedView(new int[]{k},owner));
            long before=ledger.currentBytes();assertThrows(IllegalArgumentException.class,()->snapshot.bind(domains,retainedLattices,retainedEvaluated,CancellationProbe.NONE,owner),"Paired genuine views must not publish original cell1 as index0");assertEquals(before,ledger.currentBytes());
            assertThrows(IllegalArgumentException.class,()->snapshot.bind(original,evaluated,CancellationProbe.NONE,owner),"No-handle entry cannot mint provenance from matching arrays");assertEquals(before,ledger.currentBytes());
        }finally{assertEquals(0,ledger.currentBytes());}
    }
    @Test void actualProducerDomainsKeepCompleteLatticesAndGenuineSingletonAnchors() {
        var evidence=scene();var factory=new ProbabilisticProfileFactory();var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var snapshot=factory.createWithContinuity(SOURCE,factory.profileChainage(SOURCE,2),10,true,evidence,evidence.fields().get("scalar"),EvidenceModelParameters.defaults(),CancellationProbe.NONE,owner).snapshot();
            List<ProbabilisticStateBuilder.OriginalDomain> domains=new ArrayList<>();List<ProbabilisticStateLattice> lattices=new ArrayList<>();List<InferenceProfile> evaluated=new ArrayList<>();
            for(int row=0;row<snapshot.profiles().size();row++) {
                var source=snapshot.profiles().get(row);var builder=new ProbabilisticStateBuilder();var domain=builder.buildOriginalDomain(snapshot,row,96,CancellationProbe.NONE,owner);domains.add(domain);var lattice=domain.lattice();lattices.add(lattice);
                assertEquals(row,domain.sourceRow());assertEquals(96,domain.maximumStates());assertEquals(ProbabilisticStateBuilder.baselineStateSamplingPolicyVersion(),domain.samplingPolicyVersion());
                var historical=builder.build(source,96,owner).lattice().orElseThrow();assertEquals(historical,lattice,"Opt-in witness preserves historical lattice values and measure");
                evaluated.add(new ProbabilisticObservationModel().evaluate(source,lattice,EvidenceModelParameters.defaults(),true,owner));
            }
            var receipt=snapshot.bind(domains,lattices,evaluated,CancellationProbe.NONE,owner);assertTrue(receipt.rows().get(1).states().size()>1);
            for(int row:List.of(0,10)){var states=receipt.rows().get(row).states();assertEquals(1,states.size());assertEquals(0,states.get(0).originalIndex());assertTrue(states.get(0).originalCell().exactAnchor());assertEquals(snapshot.profiles().get(row).anchor(),states.get(0).point());}
            var wrong=new ArrayList<>(domains);Collections.swap(wrong,0,1);long before=ledger.currentBytes();assertThrows(IllegalArgumentException.class,()->snapshot.bind(wrong,lattices,evaluated,CancellationProbe.NONE,owner));assertEquals(before,ledger.currentBytes());
        }finally{assertEquals(0,ledger.currentBytes());}
    }
    @Test void originalDomainProducerRefusalCancellationMemoryAndClosedScopePublishNoHandle() {
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var snapshot=produce(scene(),SOURCE,10,owner,CancellationProbe.NONE).snapshot();var builder=new ProbabilisticStateBuilder();long before=ledger.currentBytes();
            var refusal=assertThrows(ProbabilisticStateBuilder.OriginalDomainUnavailableException.class,()->builder.buildOriginalDomain(snapshot,1,1,CancellationProbe.NONE,owner));
            assertEquals(StateSpaceBuildResult.Status.STATE_LIMIT,refusal.status());assertEquals(before,ledger.currentBytes());
            var checkpoints=new java.util.concurrent.atomic.AtomicInteger();assertThrows(java.util.concurrent.CancellationException.class,()->builder.buildOriginalDomain(snapshot,1,96,()->checkpoints.incrementAndGet()>=3,owner));assertEquals(before,ledger.currentBytes());
            var tiny=new AttemptMemoryLedger(32);try(var scope=tiny.rootOwner()){assertThrows(ProbabilisticInference.MemoryLimit.class,()->builder.buildOriginalDomain(snapshot,1,96,CancellationProbe.NONE,scope));assertEquals(0,tiny.currentBytes());}
            ProbabilisticStateBuilder.OriginalDomain closed;try(var scope=owner.child("producer-domain-lifetime")){closed=builder.buildOriginalDomain(snapshot,1,96,CancellationProbe.NONE,scope);assertTrue(closed.lattice().cells().size()>1);}
            assertThrows(IllegalStateException.class,closed::lattice);assertEquals(before,ledger.currentBytes());
        }finally{assertEquals(0,ledger.currentBytes());}
    }
}
