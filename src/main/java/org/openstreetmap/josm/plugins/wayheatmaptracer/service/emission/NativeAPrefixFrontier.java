package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.CompetitiveAdmissionExecutor.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Charged cost-and-stable-tie Pareto frontier for the opt-in native A left fold.
 * Ancestors and retired entries remain owned until the execution owner closes.
 * prepare/publish separates replacement admission from retirement: callers must install every
 * original outgoing obligation (or the final DP's complete row label) before publish.
 */
public final class NativeAPrefixFrontier {
    public interface Path {
        CanonicalKey key(); double cost(); int consumed(); int depth(); Path parent();
        default long version(){return -1;}
        default int incomingArcId(){return -1;}
    }
    private static final class Bucket { Prepared head;int active; }
    public static final class Prepared {
        private final Path path;private final Bucket bucket;private Prepared next;private boolean active;
        private Prepared(Path path,Bucket bucket){this.path=path;this.bucket=bucket;}
    }
    private final AttemptMemoryLedger.Owner owner;
    private final EmissionWorkBudget budget;
    private final NativeAExecutionCensus census;
    private final boolean finalReplay;
    private final Map<CanonicalKey,Bucket> buckets;
    private final IdentityHashMap<Path,Prepared> entries;
    private NativeAPrefixFrontier(AttemptMemoryLedger.Owner owner,EmissionWorkBudget budget,NativeAExecutionCensus census,boolean finalReplay) {
        this.owner=owner;this.budget=budget;this.census=census;this.finalReplay=finalReplay;buckets=new HashMap<>();entries=new IdentityHashMap<>();
    }
    /** Unsafe generic shapes retain all versions, including duplicate/proper-prefix ranks and signed synthetic costs. */
    public static NativeAPrefixFrontier create(Graph graph,EmissionWorkBudget budget,AttemptMemoryLedger.Owner parent,NativeAExecutionCensus census) {
        return create(graph,budget,parent,census,false);
    }
    public static NativeAPrefixFrontier create(Graph graph,EmissionWorkBudget budget,AttemptMemoryLedger.Owner parent,NativeAExecutionCensus census,boolean finalReplay) {
        if(!nativeCompatible(graph,budget))return null;
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION,3);
        var owner=parent.child("native-prefix-frontier");
        try {
            owner.reserve(4096);var frontier=new NativeAPrefixFrontier(owner,budget,census,finalReplay);
            census.prefixDominanceApplied();return frontier;
        }catch(RuntimeException failure){owner.close();throw failure;}
    }
    private static boolean nativeCompatible(Graph graph,EmissionWorkBudget budget) {
        if(graph.workBudget()!=budget)throw new IllegalArgumentException("Native frontier budget is foreign");
        if(graph.syntheticControl())return false;
        for(int index=0;index<graph.vertices().size();index++) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION,1);var vertex=graph.vertices().get(index);
            if(vertex.id()!=index||vertex.tieId()!=index)return false;
        }
        int previousFrom=-1,previousTo=-1;
        for(int index=0;index<graph.arcs().size();index++) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION,1);var arc=graph.arcs().get(index);
            if(arc.id()!=index||arc.tieId()!=index||arc.fromId()<0||arc.toId()>=graph.vertices().size()
                    ||arc.toId()<=arc.fromId()||arc.fromId()<previousFrom)return false;
            if(arc.fromId()==previousFrom&&arc.toId()<=previousTo)return false; // Includes parallel arcs with identical ID paths.
            int rows=graph.vertices().get(arc.toId()).originalRow()-graph.vertices().get(arc.fromId()).originalRow();
            if(rows<0||arc.optional()&&(rows<2||arc.consumedRows()!=rows-1
                    ||!graph.vertices().get(arc.fromId()).numeric()||!graph.vertices().get(arc.toId()).numeric())
                    ||!arc.optional()&&(arc.consumedRows()!=0||rows>1))return false;
            previousFrom=arc.fromId();previousTo=arc.toId();
        }
        return true;
    }
    public boolean active(Path path){budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP,1);var entry=entries.get(path);return entry!=null&&entry.active;}
    public Prepared prepare(Path path) {
        census.boundary(finalReplay?NativeAExecutionCensus.Phase.FINAL_EXACT:NativeAExecutionCensus.Phase.PREFIX_FRONTIER,path.incomingArcId(),path.key().cursorId(),path.version(),-1);
        budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP,1);var bucket=buckets.get(path.key());
        if(bucket!=null)for(var old=bucket.head;old!=null;old=old.next) {
            budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP,1);
            if(dominates(old.path,path)){census.prefixDominated();return null;}
        }
        if(bucket==null) {
            budget.charge(EmissionWorkBudget.Operation.ALLOCATION,1);owner.reserve(512);
            bucket=new Bucket();buckets.put(path.key(),bucket);
        }
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION,1);
        try(var reservation=owner.reserve(384)) {
            var entry=new Prepared(path,bucket);reservation.adopt(entry);return entry;
        }
    }
    public void publish(Prepared candidate) {
        census.boundary(finalReplay?NativeAExecutionCensus.Phase.FINAL_EXACT:NativeAExecutionCensus.Phase.PREFIX_PUBLICATION,candidate.path.incomingArcId(),candidate.path.key().cursorId(),candidate.path.version(),-1);
        budget.charge(EmissionWorkBudget.Operation.PAIR_INITIALIZATION,1);
        if(candidate.active||entries.containsKey(candidate.path))throw new IllegalStateException("Native prefix is already installed");
        // All entry/map capacity was prepaid by prepare. Successors must already be installed.
        entries.put(candidate.path,candidate);candidate.active=true;
        var bucket=candidate.bucket;candidate.next=bucket.head;bucket.head=candidate;bucket.active++;
        census.activePrefixes(bucket.active); // Includes the installed-replacement overlap, even if retirement throws.
        var previous=candidate;var old=candidate.next;
        while(old!=null) {
            budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP,1);var next=old.next;
            if(dominates(candidate.path,old.path)) {
                old.active=false;previous.next=next;old.next=null;bucket.active--;census.prefixRetired();
            }else previous=old;
            old=next;
        }
        census.activePrefixes(bucket.active);
    }
    private boolean dominates(Path first,Path second) {
        budget.charge(EmissionWorkBudget.Operation.TIE_COMPARISON,1);
        if(!first.key().equals(second.key())||!Double.isFinite(first.cost())||!Double.isFinite(second.cost())
                ||first.cost()<0||second.cost()<0||first.consumed()<0||second.consumed()<0
                ||first.cost()==0&&second.cost()==0&&Double.doubleToLongBits(first.cost())!=Double.doubleToLongBits(second.cost())
                ||Double.compare(first.cost(),second.cost())>0)return false;
        int consumed=Integer.compare(first.consumed(),second.consumed());if(consumed!=0)return consumed<0;
        return stableLex(first,second)<=0;
    }
    private int stableLex(Path first,Path second) {
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION,2);
        long bytes=Math.addExact(AttemptMemoryLedger.arrayBytes(first.depth(),4),AttemptMemoryLedger.arrayBytes(second.depth(),4));
        try(var scratch=owner.reserve(bytes)) {
            int[] left=new int[first.depth()],right=new int[second.depth()];
            if(!fill(first,left)||!fill(second,right))return 2;
            for(int index=0;index<Math.min(left.length,right.length);index++) {
                budget.charge(EmissionWorkBudget.Operation.TIE_COMPARISON,1);
                int difference=Integer.compare(left[index],right[index]);if(difference!=0)return difference;
            }
            // Proper-prefix ranks are not extension stable. Native forward IDs rule this out,
            // but refuse rather than using the terminal shorter-path preference if it occurs.
            return left.length==right.length?0:2;
        }
    }
    private boolean fill(Path path,int[] ids) {
        int at=ids.length;long previous=Long.MAX_VALUE;
        for(var current=path;current!=null;current=current.parent()) {
            budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT,1);
            int cursor=current.key().cursorId();if(at==0||cursor<0||cursor>=previous)return false;
            ids[--at]=cursor;previous=cursor;
        }
        return at==0;
    }
}
