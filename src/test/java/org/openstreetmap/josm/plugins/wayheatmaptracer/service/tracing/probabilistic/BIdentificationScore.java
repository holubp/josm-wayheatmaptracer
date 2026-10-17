package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;
import java.util.OptionalDouble;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Offline physical-location scoring; truth is never an observation-kernel input. */
final class BIdentificationScore {
    static final class WorkLimitException extends IllegalArgumentException {
        WorkLimitException() { super("Identification score exceeds 8000000 work units"); }
    }
    enum Status { AVAILABLE, AMBIGUOUS, UNAVAILABLE, ANCHOR }
    record Target(Status status, int cellIndex, OptionalDouble offsetMeters) {
        Target {
            if(status==null || offsetMeters==null || (status==Status.AVAILABLE)
                    != (cellIndex>=0 && offsetMeters.isPresent())
                    || offsetMeters.isPresent() && !Double.isFinite(offsetMeters.getAsDouble())
                    || status!=Status.AVAILABLE && (cellIndex!=-1 || offsetMeters.isPresent()))
                throw new IllegalArgumentException("Inconsistent score target");
        }
    }
    record SceneScore(OptionalDouble meanLogLoss, int available, int ambiguous,
            int unavailable, int anchors, double scoredLengthMeters) {
        SceneScore {
            if(meanLogLoss==null || available<0 || ambiguous<0 || unavailable<0 || anchors<0
                    || !Double.isFinite(scoredLengthMeters) || scoredLengthMeters<0
                    || meanLogLoss.isPresent() && (Double.isNaN(meanLogLoss.getAsDouble())
                        || meanLogLoss.getAsDouble()==Double.NEGATIVE_INFINITY)
                    || meanLogLoss.isPresent() != (scoredLengthMeters>0))
                throw new IllegalArgumentException("Inconsistent scene score");
        }
    }

    private static final long MAX_WORK=8_000_000;
    private static final class Work {
        final CancellationProbe cancellation;
        long units;
        Work(CancellationProbe cancellation) {
            if(cancellation==null) throw new IllegalArgumentException("Cancellation probe is required");
            this.cancellation=cancellation;
        }
        void charge(long amount) {
            cancellation.checkpoint();
            if(amount<0 || amount>MAX_WORK-units) throw new WorkLimitException();
            units+=amount;
        }
    }

    static SceneScore score(BIdentificationCatalog.Scene scene, List<ProbabilisticProfile> profiles,
            List<ProbabilisticStateLattice> lattices, List<BObservationMixture.ReducedRows> rows) {
        return score(scene,profiles,lattices,rows,CancellationProbe.NONE);
    }

    static SceneScore scoreBaseline(BIdentificationCatalog.Scene scene,List<ProbabilisticProfile> profiles,
            List<ProbabilisticStateLattice> lattices,List<InferenceProfile> rows,CancellationProbe cancellation) {
        return score(scene,profiles,lattices,null,rows,cancellation);
    }

    static SceneScore score(BIdentificationCatalog.Scene scene, List<ProbabilisticProfile> profiles,
            List<ProbabilisticStateLattice> lattices, List<BObservationMixture.ReducedRows> rows,
            CancellationProbe cancellation) {
        return score(scene,profiles,lattices,rows,null,cancellation);
    }

    private static SceneScore score(BIdentificationCatalog.Scene scene,List<ProbabilisticProfile> profiles,
            List<ProbabilisticStateLattice> lattices,List<BObservationMixture.ReducedRows> rows,
            List<InferenceProfile> baseline,CancellationProbe cancellation) {
        if(scene==null || profiles==null || lattices==null || rows==null && baseline==null || profiles.isEmpty()
                || profiles.size()>4096 || profiles.size()!=lattices.size()
                || profiles.size()!=(rows==null ? baseline.size():rows.size()))
            throw new IllegalArgumentException("Scene scoring requires matching bounded profile rows");
        Work work=new Work(cancellation);
        // Refuse the full potential geometry product before any per-profile traversal.
        long inventory=0;
        for(var label:scene.truth().regions()) {
            work.charge(1);
            inventory=Math.addExact(inventory,1);
            for(var curve:label.acceptableCurves()) {
                work.charge(1);
                inventory=Math.addExact(inventory,curve.size());
            }
        }
        if(inventory>MAX_WORK/profiles.size()) throw new WorkLimitException();
        work.charge(inventory*profiles.size());
        for(int i=0;i<profiles.size();i++) {
            work.charge(1);
            if(profiles.get(i)==null || lattices.get(i)==null
                    || rows!=null && (rows.get(i)==null || rows.get(i).stateCount()!=lattices.get(i).cells().size())
                    || baseline!=null && (baseline.get(i)==null || !baseline.get(i).cells().equals(lattices.get(i).cells()))
                    || i>0 && !(profiles.get(i).chainageMeters()>profiles.get(i-1).chainageMeters()))
                throw new IllegalArgumentException("Score rows require matching states and increasing physical chainage");
            if(baseline!=null) for(int s=0;s<lattices.get(i).cells().size();s++) {
                work.charge(1);
                if(!lattices.get(i).cells().get(s).exactAnchor()
                        && !(baseline.get(i).unaryCost(s)<-StrictMath.log(1e-300)))
                    throw new IllegalArgumentException("Baseline arithmetic floor may be active; no true log likelihood is available");
            }
        }
        int available=0,ambiguous=0,unavailable=0,anchors=0;
        double scoredLength=0,mean=0;
        for(int i=0;i<profiles.size();i++) {
            work.charge(1);
            var target=targetCell(scene.truth(),profiles.get(i),lattices.get(i),work);
            switch(target.status()) {
                case ANCHOR -> { anchors++; continue; }
                case AMBIGUOUS -> { ambiguous++; continue; }
                case UNAVAILABLE -> { unavailable++; continue; }
                case AVAILABLE -> available++;
            }
            double weight=0;
            if(i>0) weight+=(profiles.get(i).chainageMeters()-profiles.get(i-1).chainageMeters())*.5;
            if(i+1<profiles.size()) weight+=(profiles.get(i+1).chainageMeters()-profiles.get(i).chainageMeters())*.5;
            // One isolated profile has no observed longitudinal extent: no invented metre weight.
            if(weight==0) continue;
            int state=target.cellIndex();
            double logDensity=rows==null ? -baseline.get(i).unaryCost(state):rows.get(i).logMixtureDensity(state);
            if(Double.isNaN(logDensity) || logDensity==Double.POSITIVE_INFINITY)
                throw new IllegalArgumentException("Shared likelihood is invalid");
            double loss=-logDensity-StrictMath.log(lattices.get(i).cells().get(state).quadratureWidthMeters()
                    /BObservationMixture.REFERENCE_LENGTH_METERS);
            double nextLength=scoredLength+weight;
            if(!Double.isFinite(nextLength)) throw new IllegalArgumentException("Physical scoring span overflow");
            if(loss==Double.POSITIVE_INFINITY || mean==Double.POSITIVE_INFINITY) mean=Double.POSITIVE_INFINITY;
            else mean+=(weight/nextLength)*(loss-mean);
            scoredLength=nextLength;
        }
        cancellation.checkpoint();
        return new SceneScore(scoredLength>0 ? OptionalDouble.of(mean):OptionalDouble.empty(),
                available,ambiguous,unavailable,anchors,scoredLength);
    }

    static Target targetCell(BIdentificationCatalog.Truth truth, ProbabilisticProfile profile,
            ProbabilisticStateLattice lattice, CancellationProbe cancellation) {
        return targetCell(truth,profile,lattice,new Work(cancellation));
    }

    private static Target targetCell(BIdentificationCatalog.Truth truth,ProbabilisticProfile profile,
            ProbabilisticStateLattice lattice,Work work) {
        if(truth==null || profile==null || lattice==null)
            throw new IllegalArgumentException("Physical score target requires complete inputs");
        work.charge(1);
        boolean anchor=profile.exactAnchorOffsetMeters().isPresent();
        double previous=Double.NEGATIVE_INFINITY;
        for(var cell:lattice.cells()) {
            work.charge(1);
            if(!(cell.offsetMeters()>previous) || cell.offsetMeters()<profile.minimumOffsetMeters()
                    || cell.offsetMeters()>profile.maximumOffsetMeters())
                throw new IllegalArgumentException("Target cells must be ordered within the physical profile domain");
            previous=cell.offsetMeters(); anchor|=cell.exactAnchor();
        }
        if(anchor) return absent(Status.ANCHOR);
        var intersections=new Intersections(profile,work);
        for(var label:truth.regions()) {
            work.charge(1);
            if(label.association()==BIdentificationCatalog.AssociationLabel.NOT_APPLICABLE) continue;
            if(label.association()==BIdentificationCatalog.AssociationLabel.UNIQUE && label.acceptableCurves().size()!=1)
                return absent(Status.UNAVAILABLE);
            for(var curve:label.acceptableCurves()) {
                work.charge(1);
                if(curve.size()<2) continue;
                for(int vertex=0;vertex<curve.size();vertex++) {
                    work.charge(1);
                    MetricPoint point=curve.get(vertex);
                    double side=intersections.side(point),offset=intersections.offset(point);
                    if(!Double.isFinite(side) || !Double.isFinite(offset)) return absent(Status.UNAVAILABLE);
                    if(side==0) intersections.accept(offset,label);
                    if(vertex==0) continue;
                    MetricPoint a=curve.get(vertex-1);
                    double sideA=intersections.side(a),offsetA=intersections.offset(a);
                    if(!Double.isFinite(sideA) || !Double.isFinite(offsetA)) return absent(Status.UNAVAILABLE);
                    if(sideA==0 && side==0 && offsetA!=offset) {
                        double left=Math.max(profile.minimumOffsetMeters(),Math.min(offsetA,offset));
                        double right=Math.min(profile.maximumOffsetMeters(),Math.max(offsetA,offset));
                        if(left<right && intersects(label.region(),intersections.point(left),intersections.point(right),work))
                            intersections.invalid=true;
                    } else if(sideA<0 && side>0 || sideA>0 && side<0) {
                        double magnitude=Math.max(Math.abs(sideA),Math.abs(side));
                        double fraction=(Math.abs(sideA)/magnitude)/(Math.abs(sideA)/magnitude+Math.abs(side)/magnitude);
                        double u=offsetA==offset ? offsetA:offsetA*(1-fraction)+offset*fraction;
                        if(!Double.isFinite(u)) return absent(Status.UNAVAILABLE);
                        intersections.accept(u,label);
                    }
                }
            }
        }
        if(intersections.ambiguous) return absent(Status.AMBIGUOUS);
        if(intersections.invalid || intersections.count!=1) return absent(Status.UNAVAILABLE);
        double offset=intersections.first;
        var cells=lattice.cells();
        for(int index=0;index+1<cells.size();index++) {
            work.charge(1);
            double midpoint=cells.get(index).offsetMeters()*.5+cells.get(index+1).offsetMeters()*.5;
            if(offset<=midpoint) return new Target(Status.AVAILABLE,index,OptionalDouble.of(offset));
        }
        return new Target(Status.AVAILABLE,cells.size()-1,OptionalDouble.of(offset));
    }

    private static final class Intersections {
        final ProbabilisticProfile profile;
        final Work work;
        double first;
        int count;
        boolean ambiguous,invalid;
        Intersections(ProbabilisticProfile profile,Work work) { this.profile=profile; this.work=work; }
        double side(MetricPoint p) {
            return -(p.xMeters()-profile.anchor().xMeters())*profile.normalUnit().yMeters()
                    +(p.yMeters()-profile.anchor().yMeters())*profile.normalUnit().xMeters();
        }
        double offset(MetricPoint p) {
            return (p.xMeters()-profile.anchor().xMeters())*profile.normalUnit().xMeters()
                    +(p.yMeters()-profile.anchor().yMeters())*profile.normalUnit().yMeters();
        }
        MetricPoint point(double offset) {
            return new MetricPoint(profile.anchor().xMeters()+profile.normalUnit().xMeters()*offset,
                    profile.anchor().yMeters()+profile.normalUnit().yMeters()*offset);
        }
        void accept(double offset,BIdentificationCatalog.LabelRegion label) {
            if(offset<profile.minimumOffsetMeters() || offset>profile.maximumOffsetMeters()) return;
            if(!contains(label.region(),point(offset),work)) return;
            if(label.association()==BIdentificationCatalog.AssociationLabel.AMBIGUOUS) ambiguous=true;
            if(count==0) { first=offset; count=1; }
            else if(first!=offset) count=2;
        }
    }

    /** Preflight the bounded original region predicate rather than copying polygon geometry. */
    private static void regionWork(MetricRegion region,Work work,int passes) {
        for(var polygon:region.polygons()) for(int vertex=0;vertex<polygon.size();vertex++) work.charge(passes);
    }
    private static boolean contains(MetricRegion region,MetricPoint point,Work work) {
        regionWork(region,work,1);
        return region.contains(point);
    }
    private static boolean intersects(MetricRegion region,MetricPoint a,MetricPoint b,Work work) {
        regionWork(region,work,3);
        return region.intersectsSegment(a,b);
    }
    private static Target absent(Status status) { return new Target(status,-1,OptionalDouble.empty()); }

    private BIdentificationScore() { }
}
