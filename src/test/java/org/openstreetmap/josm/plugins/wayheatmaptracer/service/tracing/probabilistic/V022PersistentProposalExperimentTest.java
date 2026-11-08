package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.PersistentProposalExperiment.*;

class V022PersistentProposalExperimentTest {
    private record Fixture(List<Row> rows,List<CertifiedLink> links,double[][] unary,double[][][] adjacent) { }
    private static int ordinal(int row,int branch,boolean renamed){return renamed?(row*97+branch*13+7)%997:branch;}
    private static Fixture parallel(int count,boolean joined,boolean renamed) {
        var rows=new ArrayList<Row>();var links=new ArrayList<CertifiedLink>();
        double[][] unary=new double[count][];double[][][] adjacent=new double[count-1][][];
        for(int i=0;i<count;i++) {
            boolean endpoint=joined&&(i==0||i==count-1);
            double left=joined?8*StrictMath.pow(StrictMath.sin(StrictMath.PI*i/(count-1)),2):8;
            List<Double> states=endpoint?List.of(0.0):List.of(0.0,left);
            var bands=new ArrayList<Band>();
            bands.add(new Band(new BandRef(i,ordinal(i,0,renamed)),0,0,0,1,List.of(0),BoundaryStatus.OBSERVED));
            if(!endpoint)bands.add(new Band(new BandRef(i,ordinal(i,1,renamed)),left,left,0,1,List.of(1),BoundaryStatus.OBSERVED));
            if(renamed)Collections.reverse(bands);
            rows.add(new Row(i*1.56,new MetricPoint(i*1.56,0),new MetricPoint(0,1),bands,states,
                i==0||i==count-1?OptionalInt.of(0):OptionalInt.empty()));
            unary[i]=endpoint?new double[]{0}:new double[]{0,1};
            if(i>0)adjacent[i-1]=new double[unary[i-1].length][unary[i].length];
        }
        if(joined) {
            links.add(new CertifiedLink(new BandRef(0,ordinal(0,0,renamed)),new BandRef(1,ordinal(1,0,renamed)),LinkKind.OBSERVED_JUNCTION,Set.of(new StatePair(0,0)),Map.of()));
            links.add(new CertifiedLink(new BandRef(0,ordinal(0,0,renamed)),new BandRef(1,ordinal(1,1,renamed)),LinkKind.OBSERVED_JUNCTION,Set.of(new StatePair(0,1)),Map.of()));
            links.add(new CertifiedLink(new BandRef(count-2,ordinal(count-2,0,renamed)),new BandRef(count-1,ordinal(count-1,0,renamed)),LinkKind.OBSERVED_JUNCTION,Set.of(new StatePair(0,0)),Map.of()));
            links.add(new CertifiedLink(new BandRef(count-2,ordinal(count-2,1,renamed)),new BandRef(count-1,ordinal(count-1,0,renamed)),LinkKind.OBSERVED_JUNCTION,Set.of(new StatePair(1,0)),Map.of()));
        }
        return new Fixture(rows,links,unary,adjacent);
    }
    @Test void originalTwentyRowsRetainWholeLeftRivalWithoutInventingEndpointConnections() {
        var f=parallel(20,false,false);var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var context=new Context(TraceBudgets.defaults(),CancellationProbe.NONE,owner);
            var set=reserve(f.rows(),f.links(),context);
            assertEquals(2,set.reservations().size());assertFalse(set.overflow());
            var complete=set.reservations().stream().filter(r->r.status()==Status.COMPLETE).toList();
            assertEquals(1,complete.size());
            var left=set.reservations().stream().filter(r->r.status()!=Status.COMPLETE).findFirst().orElseThrow();
            assertEquals(20,left.bandTrace().size());
            assertTrue(left.rowsOfAllowedOriginalIndices().get(10).contains(1));
            assertThrows(IllegalArgumentException.class,()->firstOrderPath(left,f.unary(),f.adjacent(),context));
            assertEquals(Collections.nCopies(20,0),firstOrderPath(complete.get(0),f.unary(),f.adjacent(),context).originalStateIndices());
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void fortyRowJoinedForkReservesWeakerWholeArcBeforeCostsAndIgnoresLocalIdNames() {
        List<List<Integer>> original=null;
        for(boolean renamed:new boolean[]{false,true}) {
            var f=parallel(40,true,renamed);var ledger=AttemptMemoryLedger.production();
            try(var owner=ledger.rootOwner()) {
                var context=new Context(TraceBudgets.defaults(),CancellationProbe.NONE,owner);
                var set=reserve(f.rows(),f.links(),context);
                assertEquals(2,set.reservations().size());assertFalse(set.overflow());
                assertTrue(set.reservations().stream().allMatch(r->r.status()==Status.COMPLETE));
                var paths=set.reservations().stream().map(r->firstOrderPath(r,f.unary(),f.adjacent(),context)).toList();
                var left=paths.stream().max(Comparator.comparingDouble(PathResult::originalCost)).orElseThrow();
                assertTrue(left.originalCost()>0);assertEquals(0,left.originalStateIndices().get(0));assertEquals(0,left.originalStateIndices().get(39));
                for(int i=1;i<39;i++)if(f.rows().get(i).originalStateOffsetsM().get(1)>1)
                    assertEquals(1,left.originalStateIndices().get(i),"whole observed arc row"+i);
                var sequences=paths.stream().map(PathResult::originalStateIndices).sorted(Comparator.comparing(Object::toString)).toList();
                if(original==null)original=sequences;else assertEquals(original,sequences);
                assertArrayEquals(new double[]{0,1},f.unary()[20]);
            }
            assertEquals(0,ledger.currentBytes());
        }
    }
    private record Outcome(ProposalSet set,List<PathResult> paths,List<List<MetricPoint>> world,Work work,long peak) { }
    private static Outcome solve(Fixture f) {
        var ledger=AttemptMemoryLedger.production();Outcome result;
        try(var owner=ledger.rootOwner()) {
            var context=new Context(TraceBudgets.defaults(),CancellationProbe.NONE,owner);
            String unary=Arrays.deepToString(f.unary()),adjacent=Arrays.deepToString(f.adjacent());
            var set=reserve(f.rows(),f.links(),context);List<PathResult> paths=new ArrayList<>();List<List<MetricPoint>> world=new ArrayList<>();
            for(var reservation:set.reservations())if(reservation.status()==Status.COMPLETE) {
                var path=firstOrderPath(reservation,f.unary(),f.adjacent(),context);paths.add(path);
                List<MetricPoint> points=new ArrayList<>();
                for(int i=0;i<f.rows().size();i++) {
                    var r=f.rows().get(i);double offset=r.originalStateOffsetsM().get(path.originalStateIndices().get(i));
                    points.add(new MetricPoint(r.originalMetricAnchor().xMeters()+r.originalUnitNormal().xMeters()*offset,
                        r.originalMetricAnchor().yMeters()+r.originalUnitNormal().yMeters()*offset));
                }
                world.add(List.copyOf(points));
            }
            assertEquals(unary,Arrays.deepToString(f.unary()));assertEquals(adjacent,Arrays.deepToString(f.adjacent()));
            result=new Outcome(set,List.copyOf(paths),List.copyOf(world),context.snapshot(),ledger.peakBytes());
        }
        assertEquals(0,ledger.currentBytes());return result;
    }
    private static Fixture costs(List<Row> rows,List<CertifiedLink> links) {
        double[][] unary=new double[rows.size()][];double[][][] adjacent=new double[rows.size()-1][][];
        for(int i=0;i<rows.size();i++) {
            unary[i]=rows.get(i).originalStateOffsetsM().stream().mapToDouble(v->v==0?0:1).toArray();
            if(i>0)adjacent[i-1]=new double[unary[i-1].length][unary[i].length];
        }
        return new Fixture(List.copyOf(rows),List.copyOf(links),unary,adjacent);
    }
    private static Row bands(Row row,List<Band> bands){return new Row(row.chainageM(),row.originalMetricAnchor(),row.originalUnitNormal(),bands,row.originalStateOffsetsM(),row.fixedStateIndex());}
    private static Fixture missing(Fixture f,int first,int last) {
        var rows=new ArrayList<>(f.rows());
        for(int i=first;i<=last;i++)rows.set(i,bands(rows.get(i),rows.get(i).bands().stream().filter(b->b.ref().ordinal()==0).toList()));
        return costs(rows,f.links());
    }
    @Test void singleForcedPointCannotMasqueradeAsASustainedRival() {
        var f=parallel(20,false,false);var rows=new ArrayList<>(f.rows());
        for(int i=0;i<20;i++)if(i!=10)rows.set(i,bands(rows.get(i),List.of(rows.get(i).bands().get(0))));
        var out=solve(costs(rows,List.of()));assertEquals(1,out.set().reservations().size());assertEquals(1,out.paths().size());
    }
    @Test void shortUniqueLeftGapKeepsItsUnsupportedEnvelopeAndNeverVisitsCheapRight() {
        var f=missing(parallel(40,true,false),18,21);var out=solve(f);
        assertEquals(2,out.paths().size());
        var left=out.set().reservations().stream().filter(r->!r.gapRows().isEmpty()).findFirst().orElseThrow();
        assertEquals(Set.of(18,19,20,21),left.gapRows());assertEquals(Status.COMPLETE,left.status());
        for(int i:left.gapRows())assertEquals(List.of(1),left.rowsOfAllowedOriginalIndices().get(i));
        assertTrue(left.bandTrace().stream().noneMatch(ref->left.gapRows().contains(ref.row())),"gap is never direct evidence");
    }
    @Test void overlongCensoredAndEmptyEnvelopeGapsRemainIncomplete() {
        var base=parallel(40,true,false);
        var longGap=solve(missing(base,8,31));assertEquals(1,longGap.paths().size());
        assertTrue(longGap.set().reservations().stream().filter(r->r.status()!=Status.COMPLETE).allMatch(r->r.gapRows().isEmpty()));
        var rows=new ArrayList<>(base.rows());var old=rows.get(19);var left=old.bands().get(1);
        rows.set(19,bands(old,List.of(old.bands().get(0),new Band(left.ref(),left.coreMinM(),left.coreMaxM(),0,1,List.of(),BoundaryStatus.CENSORED))));
        var censored=solve(costs(rows,base.links()));assertEquals(1,censored.paths().size());
        assertTrue(censored.set().reservations().stream().anyMatch(r->r.status()==Status.INCOMPLETE_CLIPPED));
        rows=new ArrayList<>(missing(base,18,21).rows());old=rows.get(19);
        rows.set(19,new Row(old.chainageM(),old.originalMetricAnchor(),old.originalUnitNormal(),List.of(old.bands().get(0)),List.of(0.0),OptionalInt.empty()));
        var empty=solve(costs(rows,base.links()));assertEquals(1,empty.paths().size());
        assertTrue(empty.set().reservations().stream().noneMatch(r->r.status()==Status.COMPLETE&&!r.gapRows().isEmpty()));
        old=rows.get(19);rows.set(19,new Row(old.chainageM(),old.originalMetricAnchor(),old.originalUnitNormal(),List.of(),List.of(),OptionalInt.empty()));
        assertTrue(solve(costs(rows,base.links())).paths().isEmpty(),"An empty original-state row cannot produce any full path");
    }
    @Test void ambiguousFlanksNeverBecomeAGuessedGap() {
        var base=missing(parallel(40,true,false),18,21);var rows=new ArrayList<>(base.rows());var r=rows.get(22);double at=r.originalStateOffsetsM().get(1);
        var modes=new ArrayList<>(r.bands());modes.add(new Band(new BandRef(22,2),at+.4,at+.4,0,1,List.of(2),BoundaryStatus.OBSERVED));
        rows.set(22,new Row(r.chainageM(),r.originalMetricAnchor(),r.originalUnitNormal(),modes,List.of(0.0,at,at+.4),r.fixedStateIndex()));
        var out=solve(costs(rows,base.links()));
        assertTrue(out.set().unresolvedPorts().stream().anyMatch(s->s.startsWith("ambiguous-gap:")));
        assertTrue(out.set().reservations().stream().noneMatch(p->p.status()==Status.COMPLETE&&!p.gapRows().isEmpty()));
    }
    @Test void clippedLeftCannotAttachAFreeRightSuffixOrWidenBeyond25() {
        var rows=new ArrayList<Row>();
        for(int i=0;i<30;i++) {
            double left=i<=12?i*2:25;var modes=new ArrayList<Band>();modes.add(new Band(new BandRef(i,0),0,0,0,1,List.of(0),BoundaryStatus.OBSERVED));
            var states=i==0?List.of(0.0):List.of(0.0,left);
            if(i>0)modes.add(new Band(new BandRef(i,1),left,left,0,1,i<=12?List.of(1):List.of(),i<=12?BoundaryStatus.OBSERVED:BoundaryStatus.CLIPPED));
            rows.add(new Row(i*1.56,new MetricPoint(i*1.56,0),new MetricPoint(0,1),modes,states,i==0||i==29?OptionalInt.of(0):OptionalInt.empty()));
        }
        var out=solve(costs(rows,List.of()));assertEquals(1,out.paths().size());
        assertTrue(out.set().reservations().stream().anyMatch(r->r.status()==Status.INCOMPLETE_CLIPPED));
        assertTrue(out.set().reservations().stream().filter(r->r.status()==Status.INCOMPLETE_CLIPPED).allMatch(r->r.rowsOfAllowedOriginalIndices().get(29).isEmpty()));
        assertTrue(rows.stream().flatMap(r->r.originalStateOffsetsM().stream()).allMatch(x->Math.abs(x)<=25));
    }
    @Test void incompatibleRotatedNormalsAndAnInterveningRotatedGapNeverUseNumericOffsetEquality() {
        for(boolean gap:new boolean[]{false,true}) {
            var base=gap?missing(parallel(40,true,false),18,21):parallel(20,false,false);
            var rows=new ArrayList<>(base.rows());int at=gap?20:10;var r=rows.get(at);
            rows.set(at,new Row(r.chainageM(),r.originalMetricAnchor(),new MetricPoint(1,0),r.bands(),r.originalStateOffsetsM(),r.fixedStateIndex()));
            assertFalse(commonFrame(rows,gap?17:9,gap?22:11));
            var out=solve(costs(rows,base.links()));
            assertTrue(out.set().reservations().stream().noneMatch(p->p.status()==Status.COMPLETE));
            assertTrue(out.set().reservations().stream().noneMatch(p->p.gapRows().contains(at)));
            assertFalse(out.set().unresolvedPorts().isEmpty());
        }
    }
    private static Fixture hairpin(boolean gap) {
        List<Row> rows=new ArrayList<>();List<CertifiedLink> links=new ArrayList<>();
        for(int i=0;i<20;i++) {
            double x=i<=6?i*1.56:i<=12?6*1.56:(18-i)*1.56,y=i<=6?0:i<=12?(i-6)*1.56:6*1.56;
            MetricPoint normal=i<=6?new MetricPoint(0,1):i<=12?new MetricPoint(-1,0):new MetricPoint(0,-1);
            var band=new Band(new BandRef(i,0),0,0,0,1,List.of(0),BoundaryStatus.OBSERVED);
            rows.add(new Row(i*1.56,new MetricPoint(x,y),normal,gap&&i>=6&&i<=8?List.of():List.of(band),List.of(0.0),i==0||i==19?OptionalInt.of(0):OptionalInt.empty()));
            if(i>0&&(!gap||i<=5||i>=10))links.add(new CertifiedLink(new BandRef(i-1,0),band.ref(),LinkKind.DIRECT_CONTINUATION,Set.of(new StatePair(0,0)),Map.of()));
        }
        if(gap)links.add(new CertifiedLink(new BandRef(5,0),new BandRef(9,0),LinkKind.DIRECT_CONTINUATION,Set.of(new StatePair(0,0)),Map.of(6,Set.of(0),7,Set.of(0),8,Set.of(0))));
        return costs(rows,links);
    }
    @Test void actualCertifiedStatePairsPreserveSharpHairpinAndExplicitNonComparableGap() {
        for(boolean gap:new boolean[]{false,true}) {
            var f=hairpin(gap);assertFalse(commonFrame(f.rows(),0,19));var out=solve(f);
            if(gap)assertFalse(commonFrame(f.rows(),5,9),"The certified gap actually crosses incompatible normals");
            assertEquals(1,out.paths().size());assertEquals(Collections.nCopies(20,0),out.paths().get(0).originalStateIndices());
            assertEquals(f.rows().stream().map(Row::originalMetricAnchor).toList(),out.world().get(0));
            assertEquals(gap?Set.of(6,7,8):Set.of(),out.set().reservations().get(0).gapRows());
        }
    }
    private static Fixture crossing() {
        List<Row> rows=new ArrayList<>();List<CertifiedLink> links=new ArrayList<>();int[][] index=new int[20][2];
        for(int i=0;i<20;i++) {
            boolean endpoint=i==0||i==19;double a=endpoint?0:8*StrictMath.sin(2*StrictMath.PI*i/19);
            var states=endpoint?List.of(0.0):List.of(-Math.abs(a),Math.abs(a));var modes=new ArrayList<Band>();
            for(int branch=0;branch<(endpoint?1:2);branch++) {
                double value=branch==0?a:-a;int state=endpoint?0:value<0?0:1;index[i][branch]=state;
                modes.add(new Band(new BandRef(i,branch),value,value,0,1,List.of(state),BoundaryStatus.OBSERVED));
            }
            rows.add(new Row(i*1.56,new MetricPoint(i*1.56,0),new MetricPoint(0,1),modes,states,endpoint?OptionalInt.of(0):OptionalInt.empty()));
        }
        for(int i=1;i<20;i++)for(int branch=0;branch<2;branch++) {
            int from=i==1?0:branch,to=i==19?0:branch;
            links.add(new CertifiedLink(new BandRef(i-1,from),new BandRef(i,to),i==1||i==19?LinkKind.OBSERVED_JUNCTION:LinkKind.DIRECT_CONTINUATION,
                Set.of(new StatePair(index[i-1][from],index[i][to])),Map.of()));
        }
        return costs(rows,links);
    }
    @Test void certifiedCrossingPreservesBothObservedRelationsInsteadOfOrderedPairing() {
        var f=crossing();var out=solve(f);assertEquals(2,out.paths().size());
        for(int branch=0;branch<2;branch++) {
            List<MetricPoint> expected=new ArrayList<>();
            for(int i=0;i<20;i++)expected.add(new MetricPoint(i*1.56,i==0||i==19?0:(branch==0?1:-1)*8*StrictMath.sin(2*StrictMath.PI*i/19)));
            assertTrue(out.world().contains(expected),"source-owned crossing branch"+branch);
        }
    }
    private static Fixture reversed(Fixture f) {
        int n=f.rows().size();List<Row> rows=new ArrayList<>();List<CertifiedLink> links=new ArrayList<>();
        for(int i=0;i<n;i++) {
            var old=f.rows().get(n-1-i);int count=old.originalStateOffsetsM().size();var offsets=new ArrayList<Double>();
            for(int s=count-1;s>=0;s--)offsets.add(-old.originalStateOffsetsM().get(s));var modes=new ArrayList<Band>();
            for(var b:old.bands())modes.add(new Band(new BandRef(i,b.ref().ordinal()+100),-b.coreMaxM(),-b.coreMinM(),b.localizationSigmaM(),b.sourcePitchM(),
                b.originalCoreIndices().stream().map(s->count-1-s).toList(),b.boundaryStatus()));
            rows.add(new Row(f.rows().get(n-1).chainageM()-old.chainageM(),old.originalMetricAnchor(),
                new MetricPoint(-old.originalUnitNormal().xMeters(),-old.originalUnitNormal().yMeters()),modes,offsets,
                old.fixedStateIndex().isPresent()?OptionalInt.of(count-1-old.fixedStateIndex().getAsInt()):OptionalInt.empty()));
        }
        for(var link:f.links()) {
            Set<StatePair> pairs=new HashSet<>();for(var p:link.allowedOriginalStatePairs())pairs.add(new StatePair(f.rows().get(link.to().row()).originalStateOffsetsM().size()-1-p.toState(),
                f.rows().get(link.from().row()).originalStateOffsetsM().size()-1-p.fromState()));
            Map<Integer,Set<Integer>> gap=new HashMap<>();link.gapConstraints().forEach((r,states)->gap.put(n-1-r,states.stream().map(s->f.rows().get(r).originalStateOffsetsM().size()-1-s).collect(java.util.stream.Collectors.toSet())));
            links.add(new CertifiedLink(new BandRef(n-1-link.to().row(),link.to().ordinal()+100),new BandRef(n-1-link.from().row(),link.from().ordinal()+100),link.kind(),pairs,gap));
        }
        double[][] unary=new double[n][];double[][][] adjacent=new double[n-1][][];
        for(int r=0;r<n;r++) {
            int old=n-1-r;unary[r]=new double[f.unary()[old].length];for(int s=0;s<unary[r].length;s++)unary[r][s]=f.unary()[old][unary[r].length-1-s];
            if(r>0) {adjacent[r-1]=new double[unary[r-1].length][unary[r].length];for(int a=0;a<unary[r-1].length;a++)for(int b=0;b<unary[r].length;b++)
                adjacent[r-1][a][b]=f.adjacent()[old][unary[r].length-1-b][unary[r-1].length-1-a];}
        }
        return new Fixture(rows,links,unary,adjacent);
    }
    @Test void physicalReversalTransformsNormalsOffsetsAndSourcePairWitnesses() {
        for(var f:List.of(parallel(40,true,false),crossing(),hairpin(true))) {
            var forward=solve(f);var backward=solve(reversed(f));assertEquals(forward.paths().size(),backward.paths().size());
            for(var path:forward.world()) {var reverse=new ArrayList<>(path);Collections.reverse(reverse);assertTrue(backward.world().contains(reverse));}
        }
    }
    @Test void ninePersistentRivalsAreVisiblyUnresolvedWithoutCostEviction() {
        List<Row> rows=new ArrayList<>();
        for(int i=0;i<20;i++) {
            List<Band> modes=new ArrayList<>();List<Double> offsets=new ArrayList<>();
            for(int j=0;j<9;j++){double at=-20+j*5;offsets.add(at);modes.add(new Band(new BandRef(i,j),at,at,0,1,List.of(j),BoundaryStatus.OBSERVED));}
            rows.add(new Row(i*1.56,new MetricPoint(i*1.56,0),new MetricPoint(0,1),modes,offsets,i==0||i==19?OptionalInt.of(4):OptionalInt.empty()));
        }
        var out=solve(costs(rows,List.of()));assertTrue(out.set().overflow());assertTrue(out.paths().isEmpty());
        assertEquals(8,out.set().reservations().size());assertTrue(out.set().reservations().stream().allMatch(r->r.status()==Status.UNRESOLVED_CAPACITY));
        assertEquals(180,rows.stream().mapToInt(r->r.bands().size()).sum());
    }
    @Test void tiesRemainExplicitAndAStatePairCertificateActuallyConstrainsTheSolve() {
        List<Row> rows=new ArrayList<>();
        for(int i=0;i<20;i++)rows.add(new Row(i*1.56,new MetricPoint(i*1.56,0),new MetricPoint(0,1),
            List.of(new Band(new BandRef(i,0),-1,1,0,1,List.of(0,1,2),BoundaryStatus.OBSERVED)),List.of(-1.0,0.0,1.0),i==0||i==19?OptionalInt.of(1):OptionalInt.empty()));
        var f=costs(rows,List.of());for(var row:f.unary())Arrays.fill(row,0);
        var out=solve(f);assertTrue(out.paths().get(0).ambiguous());
        assertEquals(out.paths().get(0).originalStateIndices(),solve(f).paths().get(0).originalStateIndices());
        var certificate=new CertifiedLink(new BandRef(10,0),new BandRef(11,0),LinkKind.DIRECT_CONTINUATION,Set.of(new StatePair(2,2)),Map.of());
        var constrained=solve(new Fixture(rows,List.of(certificate),f.unary(),f.adjacent()));
        assertEquals(2,constrained.paths().get(0).originalStateIndices().get(10));assertEquals(2,constrained.paths().get(0).originalStateIndices().get(11));
        var duplicate=rows.stream().map(r->bands(r,List.of(r.bands().get(0),new Band(new BandRef(r.bands().get(0).ref().row(),1),-1,1,0,1,List.of(0,1,2),BoundaryStatus.OBSERVED)))).toList();
        var ambiguous=solve(costs(duplicate,List.of()));assertFalse(ambiguous.set().unresolvedPorts().isEmpty());assertTrue(ambiguous.paths().isEmpty());
    }
    @Test void cancellationWorkAndMemoryRefusalsPublishNoPartialOwnedOutput() {
        var f=parallel(20,false,false);
        for(int kind=0;kind<3;kind++) {
            var ledger=kind==2?new AttemptMemoryLedger(128):AttemptMemoryLedger.production();
            try(var owner=ledger.rootOwner()) {
                var calls=new AtomicInteger();CancellationProbe cancel=kind==0?()->calls.incrementAndGet()>25:CancellationProbe.NONE;
                var limits=kind==1?new TraceBudgets(96,1,128000000,32,8):TraceBudgets.defaults();
                var context=new Context(limits,cancel,owner);
                if(kind==0)assertThrows(CancellationException.class,()->reserve(f.rows(),f.links(),context));
                else if(kind==1)assertThrows(WorkRefusal.class,()->reserve(f.rows(),f.links(),context));
                else assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->reserve(f.rows(),f.links(),context));
                assertEquals(0,owner.currentBytes());
            }
            assertEquals(0,ledger.currentBytes());
        }
        var ledger=AttemptMemoryLedger.production();var cancelled=new AtomicBoolean();
        try(var owner=ledger.rootOwner()) {
            var context=new Context(TraceBudgets.defaults(),cancelled::get,owner);var set=reserve(f.rows(),f.links(),context);long before=owner.currentBytes();
            var complete=set.reservations().stream().filter(r->r.status()==Status.COMPLETE).findFirst().orElseThrow();cancelled.set(true);
            assertThrows(CancellationException.class,()->firstOrderPath(complete,f.unary(),f.adjacent(),context));assertEquals(before,owner.currentBytes());
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void aCertificateFlagCannotHideForeignPairsOrMissingGapStateConstraints() {
        var f=hairpin(true);var malformed=new ArrayList<>(f.links());
        malformed.set(malformed.size()-1,new CertifiedLink(new BandRef(5,0),new BandRef(9,0),LinkKind.DIRECT_CONTINUATION,Set.of(new StatePair(0,0)),Map.of(6,Set.of(0))));
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var context=new Context(TraceBudgets.defaults(),CancellationProbe.NONE,owner);
            assertThrows(IllegalArgumentException.class,()->reserve(f.rows(),malformed,context));assertEquals(0,owner.currentBytes());
            var bad=new CertifiedLink(new BandRef(0,0),new BandRef(1,0),LinkKind.DIRECT_CONTINUATION,Set.of(new StatePair(99,99)),Map.of());
            assertThrows(IllegalArgumentException.class,()->reserve(f.rows(),List.of(bad),context));assertEquals(0,owner.currentBytes());
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void moreThanEightRequiredPortHistoriesAreUnresolvedEvenWithOnlyFourArms() {
        List<Row> rows=new ArrayList<>();List<CertifiedLink> links=new ArrayList<>();
        for(int i=0;i<40;i++) {
            boolean port=i==0||i==20||i==39;List<Double> offsets=port?List.of(0.0):List.of(0.0,4.0,8.0,12.0);List<Band> modes=new ArrayList<>();
            for(int j=0;j<offsets.size();j++)modes.add(new Band(new BandRef(i,j),offsets.get(j),offsets.get(j),0,1,List.of(j),BoundaryStatus.OBSERVED));
            rows.add(new Row(i*1.56,new MetricPoint(i*1.56,0),new MetricPoint(0,1),modes,offsets,i==0||i==39?OptionalInt.of(0):OptionalInt.empty()));
            if(i>0) {
                int before=rows.get(i-1).bands().size(),after=modes.size();
                for(int j=0;j<4;j++) {
                    int a=before==1?0:j,b=after==1?0:j;
                    links.add(new CertifiedLink(new BandRef(i-1,a),new BandRef(i,b),before==1||after==1?LinkKind.OBSERVED_JUNCTION:LinkKind.DIRECT_CONTINUATION,
                        Set.of(new StatePair(a,b)),Map.of()));
                }
            }
        }
        var out=solve(costs(rows,links));assertTrue(out.set().overflow());assertTrue(out.paths().isEmpty());
        assertEquals(8,out.set().reservations().size());assertTrue(out.set().unresolvedPorts().stream().anyMatch(s->s.contains("nine")));
    }
    @Test void ninthFirstOrderRequestIsRefusedWithoutCountingANinthSolveOrOwnedLeak() {
        var f=parallel(20,false,false);var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var context=new Context(TraceBudgets.defaults(),CancellationProbe.NONE,owner);var set=reserve(f.rows(),f.links(),context);
            var complete=set.reservations().stream().filter(r->r.status()==Status.COMPLETE).findFirst().orElseThrow();
            for(int i=0;i<8;i++)assertEquals(20,firstOrderPath(complete,f.unary(),f.adjacent(),context).originalStateIndices().size());
            long before=owner.currentBytes();
            assertThrows(WorkRefusal.class,()->firstOrderPath(complete,f.unary(),f.adjacent(),context));
            assertEquals(8,context.snapshot().firstOrderSolves());assertEquals(before,owner.currentBytes());
        }
        assertEquals(0,ledger.currentBytes());
    }
}
