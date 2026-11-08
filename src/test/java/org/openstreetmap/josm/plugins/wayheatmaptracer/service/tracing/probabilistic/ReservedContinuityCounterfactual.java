package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.PersistentProposalExperiment.*;

/** Fixed public history restriction, deliberately separate from live B policy. */
final class ReservedContinuityCounterfactual {
    static final String MODEL="PUBLIC_RESERVED_CONTINUITY_V1";
    static final class Refusal extends IllegalArgumentException { Refusal(String reason){super(reason);} }
    static final class Carrier implements BTransitionAdmission {
        private final List<InferenceProfile> profiles;
        private final MetricRegion region;
        private final List<MetricRegion> patches;
        private final List<String> provenance;
        private final byte[][] admitted;
        private final Context context;
        private final ProposalReservation history;
        Carrier(List<InferenceProfile> profiles,MetricRegion region,List<MetricRegion> patches,List<String> provenance,
                byte[][] admitted,Context context,ProposalReservation history) {
            this.profiles=profiles;this.region=region;this.patches=patches;this.provenance=provenance;
            this.admitted=admitted;this.context=context;this.history=history;
        }
        List<InferenceProfile> profiles(){return profiles;}
        List<MetricRegion> patches(){return patches;}
        List<String> provenance(){return provenance;}
        ProposalReservation history(){return history;}
        public boolean allowed(int row,int first,int second,List<InferenceProfile> domain,MetricRegion original) {
            if(context.owner.isClosed())throw new IllegalStateException("Closed carrier owner");
            context.step();
            if(original!=region||row<0||row>=admitted.length||domain.size()!=profiles.size()
                ||domain.get(row)!=profiles.get(row)||domain.get(row+1)!=profiles.get(row+1)
                ||first<0||first>=profiles.get(row).cells().size()||second<0||second>=profiles.get(row+1).cells().size())
                throw new IllegalArgumentException("Stale or foreign original carrier domain");
            return admitted[row][first*profiles.get(row+1).cells().size()+second]!=0;
        }
    }
    static Carrier prepare(List<Row> rows,ProposalReservation history,List<InferenceProfile> profiles,
            MetricRegion originalRegion,Map<Integer,MetricRegion> certifiedPatches,Context context) {
        try(var transaction=context.owner.child("fixed-reserved-continuity")) {
            context.step();int count=rows.size();
            if(history.status()!=Status.COMPLETE||count<2||profiles.size()!=count||history.rowsOfAllowedOriginalIndices().size()!=count
                ||history.originalStateCounts().size()!=count||history.bandTrace().isEmpty()
                ||history.bandTrace().get(0).row()!=0||history.bandTrace().get(history.bandTrace().size()-1).row()!=count-1)
                throw new Refusal("INCOMPLETE_HISTORY");
            Map<Integer,Band> observed=context.own(transaction,64L+count*80L,TreeMap::new);int previous=-1;
            for(var ref:history.bandTrace()) {
                context.step();if(ref.row()<=previous||ref.row()<0||ref.row()>=count)throw new Refusal("UNKNOWN_HISTORY");previous=ref.row();
                var band=rows.get(ref.row()).bands().stream().filter(b->b.ref().equals(ref)).findFirst().orElseThrow(()->new Refusal("UNKNOWN_BAND"));
                if(band.boundaryStatus()!=BoundaryStatus.OBSERVED)throw new Refusal("INCOMPLETE_CLIPPED");observed.put(ref.row(),band);
            }
            for(int row=0;row<count;row++) {
                context.step();var r=rows.get(row);var p=profiles.get(row);var allowed=history.rowsOfAllowedOriginalIndices().get(row);
                if(p.chainageMeters()!=r.chainageM()||!p.anchor().equals(r.originalMetricAnchor())||!p.normalUnit().equals(r.originalUnitNormal())
                    ||p.cells().size()!=r.originalStateOffsetsM().size()||p.cells().size()>context.limits.maximumStatesPerProfile()||history.originalStateCounts().get(row)!=p.cells().size()||allowed.isEmpty()
                    ||(!observed.containsKey(row))!=history.gapRows().contains(row))throw new Refusal("STALE_OR_INCOMPLETE_DOMAIN");
                for(int state=0;state<p.cells().size();state++){context.step();if(p.cells().get(state).offsetMeters()!=r.originalStateOffsetsM().get(state))throw new Refusal("STALE_ORIGINAL_STATES");}
                int prior=-1;for(int state:allowed){context.step();if(state<=prior||state>=p.cells().size())throw new Refusal("FOREIGN_ORIGINAL_INDEX");prior=state;}
                if(r.fixedStateIndex().isPresent()&&!allowed.equals(List.of(r.fixedStateIndex().getAsInt())))throw new Refusal("EXACT_ANCHOR_CHANGED");
            }
            for(var link:history.certifiedLinks()) {
                context.step();if(!history.bandTrace().contains(link.from())||!history.bandTrace().contains(link.to()))throw new Refusal("FOREIGN_CERTIFICATE");
                if(link.to().row()>link.from().row()+1) {
                    if(rows.get(link.to().row()).chainageM()-rows.get(link.from().row()).chainageM()>20)throw new Refusal("OVERLONG_GAP");
                    var product=context.own(transaction,64L+64L*history.rowsOfAllowedOriginalIndices().get(link.from().row()).size()*history.rowsOfAllowedOriginalIndices().get(link.to().row()).size(),HashSet<StatePair>::new);
                    for(int a:history.rowsOfAllowedOriginalIndices().get(link.from().row()))for(int b:history.rowsOfAllowedOriginalIndices().get(link.to().row())){context.pair();product.add(new StatePair(a,b));}
                    if(!product.equals(link.allowedOriginalStatePairs()))throw new Refusal("INCOMPLETE_UNREPRESENTABLE_CERTIFICATE");
                    for(int r=link.from().row()+1;r<link.to().row();r++) {
                        context.step();if(!new HashSet<>(history.rowsOfAllowedOriginalIndices().get(r)).equals(link.gapConstraints().get(r)))throw new Refusal("UNKNOWN_GAP_CONSTRAINT");
                    }
                }
            }
            for(int row:certifiedPatches.keySet())if(row<0||row>=count-1||!witnessed(history,row))throw new Refusal("FOREIGN_PATCH_WITHOUT_CERTIFICATE");
            double[][] bounds=context.own(transaction,64L+count*40L,()->new double[count][]);
            for(int row=0;row<count;row++) {
                context.step();var band=observed.get(row);
                if(band!=null)bounds[row]=new double[]{Math.max(-25,band.coreMinM()-band.sourcePitchM()),Math.min(25,band.coreMaxM()+band.sourcePitchM())};
                else {
                    int before=row-1,after=row+1;while(before>=0&&!observed.containsKey(before)){context.step();before--;}
                    while(after<count&&!observed.containsKey(after)){context.step();after++;}
                    if(before<0||after>=count)throw new Refusal("OPEN_GAP");
                    if(!frame(rows,before,after,context))continue; // Requires explicit metric patches below.
                    if(rows.get(after).chainageM()-rows.get(before).chainageM()>20)throw new Refusal("OVERLONG_GAP");
                    var a=observed.get(before);var b=observed.get(after);double t=(rows.get(row).chainageM()-rows.get(before).chainageM())/(rows.get(after).chainageM()-rows.get(before).chainageM());
                    double uncertainty=Math.max(StrictMath.hypot(a.localizationSigmaM(),a.sourcePitchM()),StrictMath.hypot(b.localizationSigmaM(),b.sourcePitchM()));
                    bounds[row]=new double[]{Math.max(-25,a.coreMinM()+t*(b.coreMinM()-a.coreMinM())-uncertainty),Math.min(25,a.coreMaxM()+t*(b.coreMaxM()-a.coreMaxM())+uncertainty)};
                }
            }
            List<MetricRegion> patches=new ArrayList<>();List<String> provenance=new ArrayList<>();
            byte[][] admitted=context.own(transaction,64L+count*16L,()->new byte[count-1][]);
            for(int row=0;row<count-1;row++) {
                context.step();MetricRegion patch=certifiedPatches.get(row);
                if(patch==null) {
                    if(!frame(rows,row,row+1,context)||bounds[row]==null||bounds[row+1]==null)throw new Refusal("UNKNOWN_ROTATED_FRAME");
                    var polygon=List.of(point(rows.get(row),bounds[row][0]),point(rows.get(row+1),bounds[row+1][0]),point(rows.get(row+1),bounds[row+1][1]),point(rows.get(row),bounds[row][1]));
                    try{patch=new MetricRegion(List.of(polygon));}catch(IllegalArgumentException invalid){throw new Refusal("UNKNOWN_NON_SIMPLE_PATCH");}
                }
                MetricRegion frozen=patch;context.own(transaction,96L+patch.polygons().stream().mapToLong(p->p.size()*48L).sum(),()->frozen);
                patches.add(patch);provenance.add(history.gapRows().contains(row)||history.gapRows().contains(row+1)?"UNSUPPORTED_GAP":witnessed(history,row)?"CERTIFIED_PORT":"OBSERVED_CARRIER");
                int width=profiles.get(row+1).cells().size(),size=Math.multiplyExact(profiles.get(row).cells().size(),width);
                admitted[row]=context.own(transaction,32L+size,()->new byte[size]);
                for(int a=0;a<profiles.get(row).cells().size();a++)for(int b=0;b<width;b++) {
                    context.pair();if(!history.rowsOfAllowedOriginalIndices().get(row).contains(a)||!history.rowsOfAllowedOriginalIndices().get(row+1).contains(b))continue;
                    boolean exact=true;
                    for(var link:history.certifiedLinks())if(link.from().row()==row&&link.to().row()==row+1&&!link.allowedOriginalStatePairs().contains(new StatePair(a,b)))exact=false;
                    if(exact&&patch.containsSegmentWithBoundedEvents(profiles.get(row).point(a),profiles.get(row+1).point(b),context.cancellation,n->{for(long k=0;k<n;k++)context.step();},transaction))admitted[row][a*width+b]=1;
                }
            }
            var result=context.own(transaction,128L+count*24L,()->new Carrier(List.copyOf(profiles),originalRegion,List.copyOf(patches),List.copyOf(provenance),admitted,context,history));
            context.cancellation.checkpoint();transaction.transferTo(context.owner);return result;
        }
    }
    private static boolean witnessed(ProposalReservation history,int row) {return history.certifiedLinks().stream().anyMatch(c->c.from().row()<=row&&c.to().row()>row);}
    private static boolean frame(List<Row> rows,int first,int last,Context context) {
        for(int row=first;row<=last;row++)context.step();
        return commonFrame(rows,first,last);
    }
    private static MetricPoint point(Row row,double offset){return new MetricPoint(row.originalMetricAnchor().xMeters()+offset*row.originalUnitNormal().xMeters(),row.originalMetricAnchor().yMeters()+offset*row.originalUnitNormal().yMeters());}
}
