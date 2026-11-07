package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.function.IntUnaryOperator;
import java.util.List;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Conservative complete-work admission over original row widths, without running inference. */
public final class DenseBAdmission {
    public static final String VERSION="original-width-complete-k-work-v1";
    public record Bounds(long scoreUpper,long pairUpper,boolean scoresExceeded,boolean pairsExceeded,
            boolean stateEligible,int effectiveK,int profiles) {
        public boolean admitted(){return stateEligible&&!scoresExceeded&&!pairsExceeded;}
    }
    private DenseBAdmission() { }
    static Bounds estimate(List<InferenceProfile> dense,TraceBudgets budgets,CancellationProbe cancel,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.Owner owner) {
        // Bounds, the width accessor and two scalar accumulators, with no per-row scratch.
        return ProbabilisticInference.allocated(owner,256,"dense admission bounds",
                () -> estimate(dense.size(),i->dense.get(i).cells().size(),budgets,cancel));
    }
    static String witness(List<InferenceProfile> dense,Bounds bounds,CancellationProbe cancel,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.Owner owner) {
        long chars=Math.addExact(512,Math.multiplyExact(12L,dense.size()));
        return ProbabilisticInference.allocated(owner,Math.addExact(128,Math.multiplyExact(4,chars)),
                "dense admission census witness",()->canonical(dense.size(),i->dense.get(i).cells().size(),bounds,cancel));
    }
    public static String witnessFromExplanation(String explanation) {
        int end=explanation.indexOf('\n');
        if(!explanation.startsWith(VERSION+"|")||end<0)throw new IllegalArgumentException("Dense admission witness is unavailable");
        return explanation.substring(0,end);
    }
    /** Recomputes the complete decision from declared original widths and frozen budgets. Strict replay also compares reconstructed actual widths. */
    public static Bounds validateWitness(String witness,TraceBudgets budgets,int expectedProfiles) {
        String marker="|rows=";int at=witness.indexOf(marker);
        if(at<0||!witness.startsWith(VERSION+"|"))throw new IllegalArgumentException("Dense admission witness is invalid");
        String[] rows=witness.substring(at+marker.length()).split(",",-1);
        if(rows.length!=expectedProfiles)throw new IllegalArgumentException("Dense admission profile census differs");
        int[] widths=new int[rows.length];for(int i=0;i<widths.length;i++)widths[i]=Integer.parseInt(rows[i]);
        var bounds=estimate(widths.length,i->widths[i],budgets,CancellationProbe.NONE);
        if(!canonical(widths.length,i->widths[i],bounds,CancellationProbe.NONE).equals(witness))
            throw new IllegalArgumentException("Dense admission decision or bound differs");
        return bounds;
    }
    private static String canonical(int n,IntUnaryOperator width,Bounds b,CancellationProbe cancel) {
        var text=new StringBuilder(Math.addExact(512,Math.multiplyExact(n,12)));
        text.append(VERSION).append("|K=").append(b.effectiveK()).append("|score=").append(b.scoreUpper())
                .append("|pairs=").append(b.pairUpper()).append("|scoreExceeded=").append(b.scoresExceeded())
                .append("|pairExceeded=").append(b.pairsExceeded()).append("|stateEligible=").append(b.stateEligible())
                .append("|branch=").append(b.admitted()?"dense":"compact").append("|rows=");
        for(int i=0;i<n;i++){cancel.checkpoint();if(i>0)text.append(',');text.append(width.applyAsInt(i));}
        return text.toString();
    }
    public static Bounds estimate(int profiles,IntUnaryOperator width,TraceBudgets budgets,CancellationProbe cancel) {
        if(profiles<1||width==null||budgets==null||cancel==null)throw new IllegalArgumentException("Dense census is incomplete");
        var score=new Capped(budgets.maximumTransitions());var pairs=new Capped(budgets.maximumPairVisits());
        int k=Math.min(32,budgets.maximumRawAlternatives()),before=0,previous=0,prefix=1;boolean eligible=true;
        for(int i=0;i<profiles;i++) {
            cancel.checkpoint();int current=width.applyAsInt(i);
            if(current<1)throw new IllegalArgumentException("Dense rows must be nonempty");
            eligible&=current<=budgets.maximumStatesPerProfile();
            if(profiles==1)pairs.product(current,1,1,1);
            else if(i>0)pairs.product(previous,current,1,1);
            if(i>=2) {
                score.product(before,previous,current,2L+prefix);
                prefix=(int)Math.min(k,(long)prefix*before);
            }
            before=previous;previous=current;
        }
        return new Bounds(score.value,pairs.value,score.exceeded,pairs.exceeded,eligible,k,profiles);
    }
    /** Scalar saturation at budget+1; explicit exceeded remains authoritative at Long.MAX_VALUE. */
    private static final class Capped {
        final long budget,ceiling;long value;boolean exceeded;
        Capped(long budget){this.budget=budget;ceiling=budget==Long.MAX_VALUE?budget:budget+1;}
        void product(long a,long b,long c,long d) {
            if(exceeded)return;
            if(a>budget/b||a*b>budget/c||a*b*c>budget/d){exceeded=true;value=ceiling;return;}
            long term=a*b*c*d;
            if(value>budget-term){exceeded=true;value=ceiling;}else value+=term;
        }
    }
}
