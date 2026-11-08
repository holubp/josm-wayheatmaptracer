package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import java.util.function.Supplier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Public-fixture-only reservation experiment. No native engine or applicability authority. */
final class PersistentProposalExperiment {
    enum BoundaryStatus { OBSERVED, CLIPPED, CENSORED }
    enum Status { COMPLETE, INCOMPLETE_CLIPPED, INCOMPLETE_ASSOCIATION, UNRESOLVED_CAPACITY }
    enum LinkKind { DIRECT_CONTINUATION, OBSERVED_JUNCTION }
    record BandRef(int row,int ordinal) { }
    record StatePair(int fromState,int toState) { }
    record Band(BandRef ref,double coreMinM,double coreMaxM,double localizationSigmaM,double sourcePitchM,
            List<Integer> originalCoreIndices,BoundaryStatus boundaryStatus) {
        Band {originalCoreIndices=List.copyOf(originalCoreIndices);}
    }
    record Row(double chainageM,MetricPoint originalMetricAnchor,MetricPoint originalUnitNormal,List<Band> bands,
            List<Double> originalStateOffsetsM,OptionalInt fixedStateIndex) {
        Row {bands=List.copyOf(bands);originalStateOffsetsM=List.copyOf(originalStateOffsetsM);}
    }
    record CertifiedLink(BandRef from,BandRef to,LinkKind kind,Set<StatePair> allowedOriginalStatePairs,
            Map<Integer,Set<Integer>> gapConstraints) {
        CertifiedLink {
            allowedOriginalStatePairs=Set.copyOf(allowedOriginalStatePairs);
            var copy=new TreeMap<Integer,Set<Integer>>();gapConstraints.forEach((r,s)->copy.put(r,Set.copyOf(s)));gapConstraints=Map.copyOf(copy);
        }
    }
    record Work(long pairChecks,long operations,long declaredAllocationBytes,int firstOrderSolves) { }
    record ProposalReservation(List<List<Integer>> rowsOfAllowedOriginalIndices,List<BandRef> bandTrace,Set<Integer> gapRows,
            Status status,String reason,List<CertifiedLink> certifiedLinks,List<Integer> originalStateCounts) {
        ProposalReservation {
            rowsOfAllowedOriginalIndices=rowsOfAllowedOriginalIndices.stream().map(List::copyOf).toList();
            bandTrace=List.copyOf(bandTrace);gapRows=Set.copyOf(gapRows);certifiedLinks=List.copyOf(certifiedLinks);originalStateCounts=List.copyOf(originalStateCounts);
        }
    }
    record ProposalSet(List<ProposalReservation> reservations,boolean overflow,List<String> unresolvedPorts,Work work) {
        ProposalSet {reservations=List.copyOf(reservations);unresolvedPorts=List.copyOf(unresolvedPorts);}
    }
    record PathResult(List<Integer> originalStateIndices,double originalCost,boolean ambiguous,Work work) {
        PathResult {originalStateIndices=List.copyOf(originalStateIndices);}
    }
    static final class WorkRefusal extends RuntimeException {WorkRefusal(){super("Public proposal work limit");}}
    static final class NoPath extends RuntimeException {NoPath(){super("No admissible original-state path");}}
    static final class Context {
        final TraceBudgets limits;final CancellationProbe cancellation;final AttemptMemoryLedger.Owner owner;
        long pairs,operations,allocation;int solves;
        Context(TraceBudgets limits,CancellationProbe cancellation,AttemptMemoryLedger.Owner owner) {
            var defaults=TraceBudgets.defaults();
            if(limits.maximumPairVisits()>defaults.maximumPairVisits()||limits.maximumTransitions()>defaults.maximumTransitions()
                ||limits.maximumStatesPerProfile()>defaults.maximumStatesPerProfile()||limits.maximumDistinctAlternatives()!=8)
                throw new IllegalArgumentException("Unchanged or reduced public work limits; fixed cap8");
            this.limits=limits;this.cancellation=cancellation;this.owner=owner;
        }
        void step(){cancellation.checkpoint();if(++operations>limits.maximumTransitions())throw new WorkRefusal();}
        void pair(){step();if(++pairs>limits.maximumPairVisits())throw new WorkRefusal();}
        Work snapshot(){return new Work(pairs,operations,allocation,solves);}
        <T>T own(AttemptMemoryLedger.Owner transaction,long bytes,Supplier<T> allocationFactory) {
            step();allocation=Math.addExact(allocation,bytes);
            try(var reservation=transaction.reserve(bytes)){T value=allocationFactory.get();reservation.adopt(value);return value;}
        }
    }
    private record Edge(BandRef from,BandRef to,CertifiedLink certified,Map<Integer,Set<Integer>> gap) { }
    private record Match(List<Edge> edges,double displacement,boolean ambiguous) { }
    private record Trace(List<BandRef> bands,List<Edge> edges) { }
    private static final Comparator<Band> ORDER=Comparator.comparingDouble(PersistentProposalExperiment::center).thenComparingInt(b->b.ref().ordinal());

    static ProposalSet reserve(List<Row> rows,List<CertifiedLink> links,Context context) {
        try(var transaction=context.owner.child("public-reservations")) {
            context.step();validate(rows,context);
            var bands=context.own(transaction,128L+rows.stream().mapToLong(r->r.bands().size()*64L).sum(),()->new LinkedHashMap<BandRef,Band>());
            for(var row:rows)row.bands().stream().sorted(ORDER).forEach(b->bands.put(b.ref(),b));
            Map<BandRef,List<Edge>> outgoing=new LinkedHashMap<>();Map<BandRef,Integer> incoming=new HashMap<>();
            Set<BandRef> blocked=new HashSet<>();List<String> unresolved=new ArrayList<>();
            for(var b:bands.values())if(b.boundaryStatus()==BoundaryStatus.OBSERVED){outgoing.put(b.ref(),new ArrayList<>());incoming.put(b.ref(),0);}
            for(var link:links) {
                context.pair();validateLink(rows,bands,link);
                add(outgoing,incoming,context.own(transaction,96,()->new Edge(link.from(),link.to(),link,link.gapConstraints())));
            }
            for(int row=0;row+1<rows.size();row++) {
                context.step();int leftRow=row;
                var left=rows.get(row).bands().stream().filter(b->b.boundaryStatus()==BoundaryStatus.OBSERVED&&outgoing.get(b.ref()).isEmpty()).sorted(ORDER).toList();
                var right=rows.get(row+1).bands().stream().filter(b->b.boundaryStatus()==BoundaryStatus.OBSERVED&&incoming.get(b.ref())==0).sorted(ORDER).toList();
                if(left.isEmpty()||right.isEmpty())continue;
                if(!commonFrame(rows,row,row+1)) {
                    left.forEach(b->blocked.add(b.ref()));right.forEach(b->blocked.add(b.ref()));unresolved.add("unknown-frame:"+row);continue;
                }
                var matched=match(left,right,context,transaction);
                if(matched.ambiguous()) {
                    left.forEach(b->blocked.add(b.ref()));right.forEach(b->blocked.add(b.ref()));unresolved.add("equal-association:"+row);continue;
                }
                for(var e:matched.edges())add(outgoing,incoming,e);
            }
            // All candidates see the same pre-gap snapshot; iteration order cannot claim a flank.
            Set<BandRef> sourceFlanks=new HashSet<>(),targetFlanks=new HashSet<>();
            for(var band:bands.values())if(band.boundaryStatus()==BoundaryStatus.OBSERVED&&!blocked.contains(band.ref())) {
                if(outgoing.get(band.ref()).isEmpty())sourceFlanks.add(band.ref());
                if(incoming.get(band.ref())==0)targetFlanks.add(band.ref());
            }
            List<Edge> candidates=new ArrayList<>();
            Map<BandRef,Integer> sourceCounts=new HashMap<>(),targetCounts=new HashMap<>();
            for(var from:bands.values())if(sourceFlanks.contains(from.ref())) {
                for(int next=from.ref().row()+2;next<rows.size();next++) {
                    context.step();
                    if(rows.get(next).chainageM()-rows.get(from.ref().row()).chainageM()>20)break;
                    if(!commonFrame(rows,from.ref().row(),next))break;
                    for(var to:rows.get(next).bands())if(targetFlanks.contains(to.ref())) {
                        context.pair();if(!compatible(from,to))continue;
                        var gap=gapEnvelope(rows,from,to,context);if(gap==null)continue;
                        candidates.add(context.own(transaction,96,()->new Edge(from.ref(),to.ref(),null,gap)));
                        sourceCounts.merge(from.ref(),1,Integer::sum);targetCounts.merge(to.ref(),1,Integer::sum);
                    }
                }
            }
            for(var edge:candidates) {
                context.step();
                if(sourceCounts.get(edge.from())==1&&targetCounts.get(edge.to())==1)add(outgoing,incoming,edge);
                else {
                    blocked.add(edge.from());blocked.add(edge.to());
                    unresolved.add("ambiguous-gap:"+edge.from()+"->"+edge.to());
                }
            }
            var traces=new ArrayList<Trace>();
            for(var b:bands.values())if(b.boundaryStatus()==BoundaryStatus.OBSERVED&&incoming.get(b.ref())==0&&traces.size()<9)
                traces(b.ref(),outgoing,bands,rows,blocked,new ArrayList<>(),new ArrayList<>(),traces,context);
            boolean overflow=traces.size()>8;
            if(overflow)unresolved.add("at-least-nine-required-traces; original inventory retained");
            List<ProposalReservation> reservations=new ArrayList<>();
            for(var trace:traces.subList(0,Math.min(8,traces.size()))) {
                List<List<Integer>> allowed=new ArrayList<>();for(var row:rows)allowed.add(List.of());
                Set<Integer> gaps=new TreeSet<>();List<CertifiedLink> certificates=new ArrayList<>();
                for(var ref:trace.bands())allowed.set(ref.row(),neighborhood(rows.get(ref.row()),bands.get(ref)));
                for(var e:trace.edges()) {
                    if(e.certified()!=null)certificates.add(e.certified());
                    for(var item:e.gap().entrySet()){allowed.set(item.getKey(),item.getValue().stream().sorted().toList());gaps.add(item.getKey());}
                }
                boolean complete=trace.bands().get(0).row()==0&&trace.bands().get(trace.bands().size()-1).row()==rows.size()-1
                    &&trace.bands().stream().noneMatch(blocked::contains);
                for(int r=0;r<rows.size();r++) {
                    if(rows.get(r).fixedStateIndex().isPresent()) {
                        int fixed=rows.get(r).fixedStateIndex().getAsInt();allowed.set(r,allowed.get(r).contains(fixed)?List.of(fixed):List.of());
                    }
                    if(allowed.get(r).isEmpty())complete=false;
                }
                boolean clipped=trace.bands().stream().anyMatch(ref->nearBoundary(rows,bands.get(ref)));
                Status status=overflow?Status.UNRESOLVED_CAPACITY:complete?Status.COMPLETE:clipped?Status.INCOMPLETE_CLIPPED:Status.INCOMPLETE_ASSOCIATION;
                var states=rows.stream().map(r->r.originalStateOffsetsM().size()).toList();
                reservations.add(context.own(transaction,128L+rows.size()*32L+trace.bands().size()*16L,
                    ()->new ProposalReservation(allowed,trace.bands(),gaps,status,status.name(),certificates,states)));
            }
            var result=context.own(transaction,64L+reservations.size()*8L,()->new ProposalSet(reservations,overflow,unresolved,context.snapshot()));
            context.cancellation.checkpoint();transaction.transferTo(context.owner);return result;
        }
    }
    private static double center(Band b){return .5*(b.coreMinM()+b.coreMaxM());}
    private static double uncertainty(Band b){return StrictMath.hypot(b.localizationSigmaM(),b.sourcePitchM());}
    private static boolean compatible(Band a,Band b){return a.coreMinM()-uncertainty(a)<=b.coreMaxM()+uncertainty(b)&&b.coreMinM()-uncertainty(b)<=a.coreMaxM()+uncertainty(a);}
    static boolean commonFrame(List<Row> rows,int first,int last) {
        var normal=rows.get(first).originalUnitNormal();
        for(int i=first;i<=last;i++) {
            var r=rows.get(i);if(r.originalUnitNormal().xMeters()!=normal.xMeters()||r.originalUnitNormal().yMeters()!=normal.yMeters())return false;
            if(i>first) {
                var p=rows.get(i-1).originalMetricAnchor();var q=r.originalMetricAnchor();double dx=q.xMeters()-p.xMeters(),dy=q.yMeters()-p.yMeters();
                if(dx*normal.xMeters()+dy*normal.yMeters()!=0||dx*normal.yMeters()-dy*normal.xMeters()<=0)return false;
            }
        }
        return true;
    }
    private static void validate(List<Row> rows,Context c) {
        if(rows.size()<2)throw new IllegalArgumentException("Complete original rows required");
        double previous=-Double.MAX_VALUE;
        for(int i=0;i<rows.size();i++) {
            c.step();var row=rows.get(i);var normal=row.originalUnitNormal();
            if(!Double.isFinite(row.chainageM())||row.chainageM()<=previous||StrictMath.hypot(normal.xMeters(),normal.yMeters())!=1.0
                ||row.originalStateOffsetsM().size()>c.limits.maximumStatesPerProfile())throw new IllegalArgumentException("Factual chainage/normal/state bounds required");
            previous=row.chainageM();double offset=-Double.MAX_VALUE;Set<Integer> ordinals=new HashSet<>();
            for(double value:row.originalStateOffsetsM()) {if(!Double.isFinite(value)||value< -25||value>25||value<=offset)throw new IllegalArgumentException("Original full25m ordered states");offset=value;}
            if(row.fixedStateIndex().isPresent()&&(row.fixedStateIndex().getAsInt()<0||row.fixedStateIndex().getAsInt()>=row.originalStateOffsetsM().size()))throw new IllegalArgumentException("Original fixed state");
            for(var b:row.bands()) {
                if(b.ref().row()!=i||!ordinals.add(b.ref().ordinal())||!Double.isFinite(b.coreMinM())||!Double.isFinite(b.coreMaxM())
                    ||b.coreMinM()>b.coreMaxM()||b.coreMinM()< -25||b.coreMaxM()>25||!Double.isFinite(b.localizationSigmaM())||b.localizationSigmaM()<0
                    ||!Double.isFinite(b.sourcePitchM())||b.sourcePitchM()<=0||b.boundaryStatus()==null)throw new IllegalArgumentException("Factual observed band");
                if(b.boundaryStatus()==BoundaryStatus.OBSERVED&&b.originalCoreIndices().isEmpty())throw new IllegalArgumentException("Observed band needs original core states");
                for(int index:b.originalCoreIndices())if(index<0||index>=row.originalStateOffsetsM().size()
                    ||row.originalStateOffsetsM().get(index)<b.coreMinM()||row.originalStateOffsetsM().get(index)>b.coreMaxM())throw new IllegalArgumentException("Original core index witness");
            }
        }
    }
    private static List<Integer> neighborhood(Row row,Band band) {
        List<Integer> result=new ArrayList<>();for(int i=0;i<row.originalStateOffsetsM().size();i++)
            if(row.originalStateOffsetsM().get(i)>=band.coreMinM()-band.sourcePitchM()&&row.originalStateOffsetsM().get(i)<=band.coreMaxM()+band.sourcePitchM())result.add(i);
        return List.copyOf(result);
    }
    private static void validateLink(List<Row> rows,Map<BandRef,Band> bands,CertifiedLink link) {
        var a=bands.get(link.from());var b=bands.get(link.to());
        if(a==null||b==null||a.boundaryStatus()!=BoundaryStatus.OBSERVED||b.boundaryStatus()!=BoundaryStatus.OBSERVED||link.from().row()>=link.to().row()
            ||link.kind()==null||link.allowedOriginalStatePairs().isEmpty())throw new IllegalArgumentException("Observed correspondence with actual state-pair witnesses required");
        var left=neighborhood(rows.get(link.from().row()),a);var right=neighborhood(rows.get(link.to().row()),b);
        for(var pair:link.allowedOriginalStatePairs())if(!left.contains(pair.fromState())||!right.contains(pair.toState()))throw new IllegalArgumentException("Foreign state pair");
        var expected=new TreeSet<Integer>();for(int i=link.from().row()+1;i<link.to().row();i++)expected.add(i);
        if(!expected.equals(link.gapConstraints().keySet())||!expected.isEmpty()&&rows.get(link.to().row()).chainageM()-rows.get(link.from().row()).chainageM()>20)
            throw new IllegalArgumentException("Certified gap requires all bounded original-row constraints");
        for(var item:link.gapConstraints().entrySet()) {
            if(item.getValue().isEmpty())throw new IllegalArgumentException("Empty certified gap constraint");
            for(int index:item.getValue())if(index<0||index>=rows.get(item.getKey()).originalStateOffsetsM().size())throw new IllegalArgumentException("Foreign gap state");
        }
    }
    private static void add(Map<BandRef,List<Edge>> out,Map<BandRef,Integer> in,Edge edge) {
        if(!out.get(edge.from()).contains(edge)){out.get(edge.from()).add(edge);in.put(edge.to(),in.get(edge.to())+1);}
    }
    private static Match match(List<Band> left,List<Band> right,Context c,AttemptMemoryLedger.Owner owner) {
        Match[][] table=c.own(owner,(long)(left.size()+1)*(right.size()+1)*32,()->new Match[left.size()+1][right.size()+1]);
        for(int i=0;i<=left.size();i++)for(int j=0;j<=right.size();j++) {
            c.pair();var best=new Match(List.of(),0,false);
            if(i>0)best=better(best,table[i-1][j]);if(j>0)best=better(best,table[i][j-1]);
            if(i>0&&j>0&&compatible(left.get(i-1),right.get(j-1))) {
                var previous=table[i-1][j-1];var edges=new ArrayList<>(previous.edges());
                edges.add(new Edge(left.get(i-1).ref(),right.get(j-1).ref(),null,Map.of()));
                best=better(best,new Match(List.copyOf(edges),previous.displacement()+Math.abs(center(left.get(i-1))-center(right.get(j-1))),previous.ambiguous()));
            }
            table[i][j]=best;
        }
        var result=table[left.size()][right.size()];
        boolean coincident=left.stream().mapToDouble(PersistentProposalExperiment::center).distinct().count()!=left.size()
            ||right.stream().mapToDouble(PersistentProposalExperiment::center).distinct().count()!=right.size();
        return new Match(result.edges(),result.displacement(),result.ambiguous()||coincident);
    }
    private static Match better(Match a,Match b) {
        int comparison=Integer.compare(a.edges().size(),b.edges().size());if(comparison==0)comparison=-Double.compare(a.displacement(),b.displacement());
        if(comparison>0)return a;if(comparison<0)return b;
        return new Match(a.edges(),a.displacement(),a.ambiguous()||b.ambiguous()||!a.edges().equals(b.edges()));
    }
    private static Map<Integer,Set<Integer>> gapEnvelope(List<Row> rows,Band from,Band to,Context c) {
        Map<Integer,Set<Integer>> gap=new TreeMap<>();double uncertainty=Math.max(uncertainty(from),uncertainty(to));
        for(int r=from.ref().row()+1;r<to.ref().row();r++) {
            c.step();double t=(rows.get(r).chainageM()-rows.get(from.ref().row()).chainageM())/(rows.get(to.ref().row()).chainageM()-rows.get(from.ref().row()).chainageM());
            double min=from.coreMinM()+t*(to.coreMinM()-from.coreMinM())-uncertainty,max=from.coreMaxM()+t*(to.coreMaxM()-from.coreMaxM())+uncertainty;
            // An observed rival or censor inside the envelope is not missing same-branch evidence.
            for(var band:rows.get(r).bands())if(band.coreMinM()<=max&&band.coreMaxM()>=min)return null;
            Set<Integer> allowed=new TreeSet<>();for(int i=0;i<rows.get(r).originalStateOffsetsM().size();i++) {
                c.step();double offset=rows.get(r).originalStateOffsetsM().get(i);if(offset>=min&&offset<=max)allowed.add(i);
            }
            if(allowed.isEmpty())return null;gap.put(r,Set.copyOf(allowed));
        }
        return Map.copyOf(gap);
    }
    private static boolean nearBoundary(List<Row> rows,Band band) {
        for(int other:new int[]{band.ref().row()-1,band.ref().row()+1})if(other>=0&&other<rows.size()&&commonFrame(rows,Math.min(other,band.ref().row()),Math.max(other,band.ref().row())))
            for(var b:rows.get(other).bands())if(b.boundaryStatus()!=BoundaryStatus.OBSERVED&&compatible(band,b))return true;
        return false;
    }
    private static void traces(BandRef at,Map<BandRef,List<Edge>> out,Map<BandRef,Band> bands,List<Row> rows,Set<BandRef> blocked,
            List<BandRef> chain,List<Edge> used,List<Trace> result,Context c) {
        c.step();chain.add(at);
        if(out.get(at).isEmpty()) {
            double span=rows.get(at.row()).chainageM()-rows.get(chain.get(0).row()).chainageM();
            double pitch=chain.stream().mapToDouble(ref->bands.get(ref).sourcePitchM()).max().orElseThrow();
            if(span>=Math.max(10,5*pitch)||chain.stream().anyMatch(blocked::contains)||chain.stream().anyMatch(ref->nearBoundary(rows,bands.get(ref))))
                result.add(new Trace(List.copyOf(chain),List.copyOf(used)));
        } else for(var edge:out.get(at)) {
            if(result.size()>=9)break;used.add(edge);traces(edge.to(),out,bands,rows,blocked,chain,used,result,c);used.remove(used.size()-1);
        }
        chain.remove(chain.size()-1);
    }
    private record Key(int state,int gapFlank) { }
    private record Node(int state,double cost,Node previous,boolean ambiguous) { }
    static PathResult firstOrderPath(ProposalReservation reservation,double[][] unary,double[][][] adjacent,Context context) {
        if(reservation.status()!=Status.COMPLETE)throw new IllegalArgumentException("Incomplete reservation is not a full path");
        try(var transaction=context.owner.child("public-first-order")) {
            context.step();if(context.solves>=8)throw new WorkRefusal();context.solves++;
            int count=reservation.originalStateCounts().size();
            if(unary.length!=count||adjacent.length!=count-1)throw new IllegalArgumentException("Original cost dimensions");
            for(int row=0;row<count;row++) {
                if(unary[row].length!=reservation.originalStateCounts().get(row))throw new IllegalArgumentException("Original unary states");
                for(double cost:unary[row])validCost(cost);
                if(row>0) {
                    if(adjacent[row-1].length!=unary[row-1].length)throw new IllegalArgumentException("Original adjacent rows");
                    for(var costs:adjacent[row-1]){if(costs.length!=unary[row].length)throw new IllegalArgumentException("Original adjacent states");for(double cost:costs)validCost(cost);}
                }
            }
            Map<Key,Node> current=new LinkedHashMap<>();
            for(int state:reservation.rowsOfAllowedOriginalIndices().get(0))if(Double.isFinite(unary[0][state]))
                current.put(new Key(state,-1),new Node(state,unary[0][state],null,false));
            for(int row=1;row<count;row++) {
                Map<Key,Node> next=new LinkedHashMap<>();int at=row;
                for(var previous:current.entrySet())for(int state:reservation.rowsOfAllowedOriginalIndices().get(row)) {
                    context.pair();int flank=previous.getKey().gapFlank();boolean allowed=true;
                    for(var link:reservation.certifiedLinks()) {
                        if(link.from().row()==row-1&&link.to().row()>row)flank=previous.getKey().state();
                        if(link.to().row()==row) {
                            int from=link.from().row()==row-1?previous.getKey().state():flank;
                            if(!link.allowedOriginalStatePairs().contains(new StatePair(from,state)))allowed=false;
                            if(link.from().row()<row-1)flank=-1;
                        }
                    }
                    if(!allowed)continue;
                    double cost=previous.getValue().cost()+adjacent[row-1][previous.getKey().state()][state]+unary[row][state];
                    if(!Double.isFinite(cost))continue;
                    var key=new Key(state,flank);var old=next.get(key);boolean tie=old!=null&&cost==old.cost();
                    if(old==null||cost<old.cost()||tie) {
                        Node parent=previous.getValue();boolean ambiguity=parent.ambiguous()||tie||old!=null&&old.ambiguous()&&cost==old.cost();
                        if(tie)parent=old.previous();Node selected=parent;boolean uncertain=ambiguity;
                        next.put(key,context.own(transaction,64,()->new Node(state,cost,selected,uncertain)));
                    }
                }
                current=next;
            }
            Node best=null;boolean tie=false;
            for(var node:current.values())if(best==null||node.cost()<best.cost()){best=node;tie=node.ambiguous();}else if(node.cost()==best.cost())tie=true;
            if(best==null)throw new NoPath();
            List<Integer> states=new ArrayList<>();for(var node=best;node!=null;node=node.previous())states.add(node.state());Collections.reverse(states);
            double cost=best.cost();boolean ambiguous=tie;
            var result=context.own(transaction,64L+states.size()*4L,()->new PathResult(states,cost,ambiguous,context.snapshot()));
            context.cancellation.checkpoint();transaction.transferTo(context.owner);return result;
        }
    }
    private static void validCost(double cost){if(Double.isNaN(cost)||cost==Double.NEGATIVE_INFINITY)throw new IllegalArgumentException("Unchanged finite or blocked original costs");}
}
