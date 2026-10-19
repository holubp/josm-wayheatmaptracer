package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Explicit comparison capability; ordinary strict admission and its result layout are unchanged. */
final class ProbabilisticOptionalOverflowAdmission {
    static final String POLICY_ID="native-preserving-optional-overflow-v1";
    private static final byte DECLINED=1, MATERIALIZED=2, ADDED=4;
    private ProbabilisticOptionalOverflowAdmission(){ }

    /** Selection104 field bytes, Work64 field bytes, three eight-byte arrays and one byte array. */
    static long retainedBytes(int rows) {
        return Math.addExact(AttemptMemoryLedger.objectBytes(104)+AttemptMemoryLedger.objectBytes(64),
                Math.addExact(Math.multiplyExact(3,AttemptMemoryLedger.arrayBytes(rows,8)),AttemptMemoryLedger.arrayBytes(rows,1)));
    }
    /** Our copied slots/row visits/cell tests/checkpoints plus one materialization per row.
     * Builder/strict-selection invocations are separately counted, not represented as inner primitive work. */
    static long preflightUpper(int rows,int stateLimit) {
        return Math.addExact(32,Math.multiplyExact((long)rows,16+2L*stateLimit));
    }
    static long temporaryBytes(int rows) {
        // Arrays.ArrayList inherits AbstractList.modCount in addition to its array reference.
        return Math.addExact(AttemptMemoryLedger.arrayBytes(rows,4),
                AttemptMemoryLedger.objectBytes(8)+AttemptMemoryLedger.objectBytes(12));
    }
    static final class Work {
        final long upper;
        long units,nativeBuilds,cellComparisons,checkpoints,strictSelections,materializations,copiedElements;
        Work(long upper){this.upper=upper;}
        void charge(long amount){units=Math.addExact(units,amount);if(units>upper)throw new IllegalStateException("optional admission work bound");}
        void poll(CancellationProbe cancellation){charge(1);checkpoints++;cancellation.checkpoint();}
    }

    static Selection select(List<ProbabilisticProfile> profiles,double[] proposals,TraceBudgets budgets,
            CancellationProbe cancellation,AttemptMemoryLedger.Owner parent) {
        if(profiles==null||profiles.isEmpty()||proposals==null||proposals.length!=profiles.size()||budgets==null||cancellation==null||parent==null)
            throw new IllegalArgumentException("Owned optional-overflow inputs are incomplete");
        int n=profiles.size(),limit=Math.min(96,budgets.maximumStatesPerProfile());
        long bytes=retainedBytes(n),upper=preflightUpper(n,limit);
        var owner=parent.child("optional-overflow-selection");boolean success=false;
        try(var arena=owner.reserve(bytes);var temporary=owner.child("optional-overflow-preflight")) {
            // Count array, stateless builder and borrowed-array List wrapper.
            try(var scratch=temporary.reserve(temporaryBytes(n))) {
                var work=new Work(upper);var refs=new ProbabilisticProfile[n];var original=new double[n];var effective=new double[n];var declined=new byte[n];
                int[] counts=new int[n];var builder=new ProbabilisticStateBuilder();int declines=0;
                for(int i=0;i<n;i++) {
                    work.poll(cancellation);work.charge(3);refs[i]=Objects.requireNonNull(profiles.get(i));
                    double proposed=proposals[i];original[i]=proposed;effective[i]=proposed;work.copiedElements+=3;
                }
                long nativeBound=-1,selectedBound=-1,selectedPairs=-1;String refusal=null;
                for(int i=0;i<n;i++) {
                    work.poll(cancellation);work.charge(1);work.nativeBuilds++;
                    try(var query=temporary.child("optional-native-row")) {
                        var baseline=builder.build(refs[i],limit,query);
                        if(baseline.status()!=StateSpaceBuildResult.Status.COMPLETE){refusal="NATIVE_STATE_LIMIT";break;}
                        var cells=baseline.lattice().orElseThrow().cells();counts[i]=cells.size();
                        double proposed=original[i];work.charge(1);
                        // Match the original builder's anchor/NaN precedence exactly.
                        if(Double.isNaN(proposed)||refs[i].exactAnchorOffsetMeters().isPresent())continue;
                        if(!Double.isFinite(proposed)||proposed<refs[i].minimumOffsetMeters()||proposed>refs[i].maximumOffsetMeters())
                            throw new IllegalArgumentException("Continuation position is outside the decision window");
                        boolean present=false;double tolerance=1e-6*Math.max(refs[i].sourcePitchMeters(),1);
                        // Indexed immutable-cell access avoids an unfunded temporary List iterator.
                        for(int state=0;state<cells.size();state++) {
                            var cell=cells.get(state);
                            work.poll(cancellation);work.charge(1);work.cellComparisons++;
                            if(Math.abs(cell.offsetMeters()-proposed)<=tolerance){present=true;break;}
                        }
                        if(!present&&cells.size()>=limit){declined[i]=DECLINED;effective[i]=Double.NaN;declines++;}
                        else if(!present){counts[i]++;declined[i]|=ADDED;}
                    }
                }
                ProbabilisticLatticeAdmission.Plan plan=null;
                if(refusal==null) {
                    // Prospective retained-graph bounds. No geometry, likelihood or solver is evaluated.
                    long triples=0,pairs=0,nativeTriples=0;
                    for(int i=0;i<n;i++) {
                        work.poll(cancellation);work.charge(3);
                        if(i>0)pairs=Math.addExact(pairs,Math.multiplyExact((long)counts[i-1],counts[i]));
                        if(i>1)triples=Math.addExact(triples,Math.multiplyExact(Math.multiplyExact((long)counts[i-2],counts[i-1]),counts[i]));
                        if(i>1)nativeTriples=Math.addExact(nativeTriples,Math.multiplyExact(Math.multiplyExact((long)nativeCount(counts,declined,i-2),nativeCount(counts,declined,i-1)),nativeCount(counts,declined,i)));
                    }
                    selectedBound=n==1?counts[0]:Math.addExact(Math.multiplyExact((long)counts[0],counts[1]),Math.multiplyExact(2,triples));
                    nativeBound=n==1?nativeCount(counts,declined,0):Math.addExact(Math.multiplyExact((long)nativeCount(counts,declined,0),nativeCount(counts,declined,1)),Math.multiplyExact(2,nativeTriples));
                    selectedPairs=n==1?counts[0]:pairs;
                    if(declines>0&&selectedBound>budgets.maximumTransitions())refusal="NATIVE_PRESERVATION_WORK_LIMIT";
                    else {
                        work.poll(cancellation);work.charge(1);work.strictSelections++;
                        plan=ProbabilisticLatticeAdmission.select(Arrays.asList(refs),effective,budgets,cancellation,owner);
                        if(declines>0&&plan.coarsened())throw new IllegalStateException("native-preserving selection unexpectedly coarsened");
                        if(!plan.admitted())refusal="STRICT_ADMISSION_REFUSED";
                    }
                }
                work.poll(cancellation);
                var result=new Selection(owner,parent,refs,original,effective,declined,plan,work,refusal,nativeBound,selectedBound,selectedPairs,limit,declines);
                arena.adopt(result);success=true;return result;
            }
        } finally {if(!success)owner.close();}
    }
    private static int nativeCount(int[] selected,byte[] decisions,int row){return selected[row]-((decisions[row]&ADDED)!=0?1:0);}

    static final class Selection implements AutoCloseable {
        private final AttemptMemoryLedger.Owner owner,parent;
        private final ProbabilisticProfile[] profiles;
        private final double[] original,effective;
        private final byte[] declined;
        private final ProbabilisticLatticeAdmission.Plan plan;
        private final Work work;
        private final String refusal;
        private final long nativeBound,selectedBound,selectedPairs;
        private final int limit,declines;
        Selection(AttemptMemoryLedger.Owner owner,AttemptMemoryLedger.Owner parent,ProbabilisticProfile[] profiles,double[] original,double[] effective,
                byte[] declined,ProbabilisticLatticeAdmission.Plan plan,Work work,String refusal,long nativeBound,long selectedBound,long selectedPairs,int limit,int declines) {
            this.owner=owner;this.parent=parent;this.profiles=profiles;this.original=original;this.effective=effective;this.declined=declined;this.plan=plan;
            this.work=work;this.refusal=refusal;this.nativeBound=nativeBound;this.selectedBound=selectedBound;this.selectedPairs=selectedPairs;this.limit=limit;this.declines=declines;
        }
        private void live(){if(owner.isClosed()||parent.isClosed())throw new IllegalStateException("Optional selection is closed");}
        boolean admitted(){live();return refusal==null&&plan!=null&&plan.admitted();}
        boolean coarsened(){live();return plan!=null&&plan.coarsened();}
        int declinedCount(){live();return declines;}
        boolean declined(int row){live();return (declined[row]&DECLINED)!=0;}
        double originalProposal(int row){live();return original[row];}
        double effectiveProposal(int row){live();return effective[row];}
        long nativeForwardBackwardBound(){live();return nativeBound;}
        long selectedForwardBackwardBound(){live();return selectedBound;}
        long pairBound(){live();return plan==null?selectedPairs:plan.pairBound();}
        long completeScoreBound(){live();return plan==null?-1:plan.completeScoreBound();}
        String refusalReason(){live();return refusal;}
        Work work(){live();return work;}
        StateSpaceBuildResult buildProfile(int row,AttemptMemoryLedger.Owner output,CancellationProbe cancellation) {
            live();if(!admitted())throw new IllegalStateException("Optional graph was refused");
            if((declined[row]&MATERIALIZED)!=0)throw new IllegalStateException("Optional graph row already materialized");
            declined[row]|=MATERIALIZED;
            // A temporary co-ownership lease validates the SAME ledger without expanding its API.
            try(var binding=output.retain(this)) {
                work.poll(cancellation);
                try(var temporary=output.child("optional-row-temporary");var reserve=temporary.reserve(AttemptMemoryLedger.objectBytes(8));
                        var query=output.child("optional-selected-row")) {
                    var builder=new ProbabilisticStateBuilder();work.charge(1);work.materializations++;
                    var result=declined(row)?builder.build(profiles[row],limit,query):plan.coarsened()?
                            builder.buildWithReservedProposal(profiles[row],plan.maximumStates(row),effective[row],query):
                            builder.buildWithProposal(profiles[row],plan.maximumStates(),effective[row],query);
                    if(result.status()!=StateSpaceBuildResult.Status.COMPLETE)throw new IllegalStateException("Selected optional graph failed materialization");
                    work.poll(cancellation);query.transferTo(output);return result;
                }
            } catch(RuntimeException|Error failure){close();throw failure;}
        }
        public void close(){owner.close();}
    }
}
