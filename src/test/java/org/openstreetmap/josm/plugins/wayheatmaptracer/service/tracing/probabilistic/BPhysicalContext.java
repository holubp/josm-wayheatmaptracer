package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** CONTEXT_ONLY point stage; no chord, route, integral or solver capability. */
final class BPhysicalContext extends BPhysicalResponseBank implements AutoCloseable {
    enum Reason { AVAILABLE,SOURCE_UNKNOWN,UNRESOLVED }
    private final BPhysicalResponse response;
    private final AttemptMemoryLedger.Owner allocation;
    private final Weights weights;
    private int phase,reason,fieldState,contextState;
    private boolean closed;
    //9refs+4ints+bool=>O89=112;five16-double arrays720 =>832.
    //Weights2144. Total2976, no per-query arrays.
    private BPhysicalContext(BPhysicalResponse response,AttemptMemoryLedger.Owner owner) {
        super(response.work,16);this.response=response;allocation=owner;weights=new Weights(work,owner);
    }
    static BPhysicalContext open(BPhysicalResponse response) {
        response.bound();var work=response.work;work.claimContext();
        var owner=work.owner().child("physical-context-point");
        try {owner.reserve(2976);var result=new BPhysicalContext(response,owner);work.poll();return result;}
        catch(RuntimeException|Error failure){owner.close();throw failure;}
    }
    void query(double x,double y,int gain) {
        phase=0;fieldState=-1;contextState=-1;work.startContext();
        try {
            live();if(gain!=1&&gain!=4&&gain!=16&&gain!=64)throw new IllegalArgumentException("Fixed physical gain family");
            constant(12,x);constant(13,y);constant(6,gain);
            fieldState=field(0,x,y);
            if(fieldState<0){finish(reason);return;}
            constant(1,0);contextState=0; //Only after the base-field producer returned.
            for(int i=0;i<49;i++) {
                work.poll();var g=response.bound().grid;
                translate(14,12,weights.ax[i],g.ux,weights.by[i],g.vx);
                translate(15,13,weights.ax[i],g.uy,weights.by[i],g.vy);
                // Original base coordinates h*k,|k|<=2^50; offsets add<=8h.
                // Both basis axes are +/-rho powers of two. Thus each ORIGINAL
                // multiplication/addition remains representable (<53 bits),
                // finite at the reviewed exponent bound. This proves all stages
                // exact from ORIGINAL inputs,not from rounded translated output.
                if(BPhysicalResponse.exactGeometry(g,x,y,0,0,0,1)) {
                    constant(14,n[14]);constant(15,n[15]);
                }
                phase=2; //Private coupled-query borrow is active only in field3.
                int incoming=field(3,n[14],n[15]);phase=0;
                //Support BEFORE weight, including the zero-weight boundary.
                if(incoming<0){contextState=-1;finish(reason);return;}
                set(4,weights.nominal[i],weights.lower[i],weights.upper[i]);mul(5,3,4);
                contextState=maximum(1,5,contextState);
            }
            if(n[1]<n[0])throw new ArithmeticException("Physical self maximum");
            mul(7,0,6);mul(8,1,6);log1p(9,7);log1p(10,8);sub(2,10,9);
            if(n[2]<0)throw new ArithmeticException("Negative nominal deficit");
            //Intersect ONLY the real enclosure with proved real D>=0.
            set(2,n[2],Math.max(0,lo[2]),hi[2]);finish(Reason.AVAILABLE.ordinal());
        } catch(RuntimeException|Error failure){phase=0;work.complete(BPhysicalWork.CONTEXT,false);throw failure;}
    }
    private int field(int out,double x,double y) {
        constant(out,0);int state=0;
        for(int at=0;at<response.descriptorCount();at++) {
            work.poll();int status=response.componentStatus(at);
            if(status==BSharedPositionEvidence.Status.INELIGIBLE_GROUPED_PARENT.ordinal())continue;
            if(status!=BSharedPositionEvidence.Status.AVAILABLE.ordinal()) {
                reason=Reason.SOURCE_UNKNOWN.ordinal();return -1;
            }
            if(out==3)response.componentAtContext(at,this);else response.componentAt(at,x,y);
            var why=response.reason();
            if(why==BPhysicalResponse.Reason.SOURCE_UNKNOWN||why==BPhysicalResponse.Reason.INELIGIBLE) {
                reason=Reason.SOURCE_UNKNOWN.ordinal();return -1;
            }
            if(why==BPhysicalResponse.Reason.UNRESOLVED){reason=Reason.UNRESOLVED.ordinal();return -1;}
            set(11,response.value(),response.lower(),response.upper());state=maximum(out,11,state);
        }
        return state; //Empty/parent-only maximum is geometric0, not measured support.
    }
    private void translate(int out,int base,int a,double axisA,int b,double axisB) {
        // The nominal bank operations preserve (base+a*axisA)+b*axisB;
        // each corresponding exact-real operation receives outward endpoints.
        constant(7,a);constant(8,axisA);mul(9,7,8);add(out,base,9);
        constant(7,b);constant(8,axisB);mul(9,7,8);add(out,out,9);
    }
    void requireFor(BPhysicalResponse expected) {
        if(response!=expected||work!=expected.work||phase!=2)
            throw new IllegalArgumentException("No active original translated-query producer");
        live();
    }
    private int maximum(int out,int incoming,int state) {
        work.poll();boolean dominates=hi[out]<lo[incoming]||hi[incoming]<lo[out];
        int next=state==0?1:state==2||!dominates?2:1; //RANGE is permanent.
        set(out,Math.max(n[out],n[incoming]),Math.max(lo[out],lo[incoming]),Math.max(hi[out],hi[incoming]));
        return next;
    }
    private void log1p(int out,int in) {
        //Only callers are g*F,g*C with g>0,F>=0,C>=0. Their generic outward
        //product may include -MIN_VALUE at exact0; intersect the REAL domain,
        //never the nominal deficit. Two extra guarded max calls are prepaid.
        work.poll();if(n[in]<0||hi[in]<0)throw new ArithmeticException("Physical log domain");
        double nominal=StrictMath.log1p(n[in]);
        double lower=StrictMath.log1p(Math.max(0,lo[in])),upper=StrictMath.log1p(hi[in]);
        //Reviewed1ulp API bound;TWO neighbors handle binade boundaries.
        lower=Math.max(0,Math.nextDown(Math.nextDown(lower)));
        upper=Math.nextUp(Math.nextUp(upper));set(out,nominal,lower,upper);
    }
    private void finish(int why){work.poll();live();reason=why;phase=1;work.complete(BPhysicalWork.CONTEXT,true);}
    private void live(){if(closed||allocation.isClosed())throw new IllegalStateException("Physical context closed");response.bound();}
    private void ready(){live();if(phase!=1)throw new IllegalStateException("No current context");}
    Reason reason(){ready();return switch(reason){case 0->Reason.AVAILABLE;case 1->Reason.SOURCE_UNKNOWN;default->Reason.UNRESOLVED;};}
    double field(){ready();return fieldState<0?Double.NaN:n[0];}
    double context(){ready();return contextState<0?Double.NaN:n[1];}
    double deficit(){ready();return reason==0?n[2]:Double.NaN;}
    double lower(){ready();return reason==0?lo[2]:Double.NaN;}
    double upper(){ready();return reason==0?hi[2]:Double.NaN;}
    double error(){ready();return reason==0?error[2]:Double.NaN;}
    int fieldStreamState(){ready();return fieldState;}
    int contextStreamState(){ready();return contextState;}
    @Override public void close(){closed=true;allocation.close();}

    /**2144B:two49-int/four49-double arrays+eight-reference O64 shell. */
    private static final class Weights {
        final int[] ax=new int[49],by=new int[49];
        final double[] nominal=new double[49],lower=new double[49],upper=new double[49],error=new double[49];
        final BPhysicalWork work;final AttemptMemoryLedger.Owner owner;
        Weights(BPhysicalWork work,AttemptMemoryLedger.Owner owner) {
            this.work=work;this.owner=owner;
            for(int i=0;i<49;i++) {
                work.poll();int pair=offset(i);ax[i]=(pair>>>4)-4;by[i]=(pair&15)-4;
                int d=ax[i]*ax[i]+by[i]*by[i];
                double root=StrictMath.sqrt(d),a=Math.max(0,Math.nextDown(root)),b=Math.nextUp(root);
                nominal[i]=1-root/4;
                lower[i]=Math.max(0,Math.nextDown(1-Math.nextUp(b/4)));
                upper[i]=Math.min(1,Math.nextUp(1-Math.nextDown(a/4)));
                error[i]=Math.nextUp(Math.max(Math.abs(nominal[i]-lower[i]),Math.abs(upper[i]-nominal[i])));
                if(!Double.isFinite(error[i])||lower[i]<0||upper[i]>1||lower[i]>upper[i])
                    throw new ArithmeticException("Context weight enclosure");
            }
            work.poll();
        }
    }
    /**Compiled literal49-pair program, not runtime81-candidate filtering/data. */
    static int offset(int i) {
        return switch(i) {
            case 0->4;
            case 1->18;
            case 2->19;
            case 3->20;
            case 4->21;
            case 5->22;
            case 6->33;
            case 7->34;
            case 8->35;
            case 9->36;
            case 10->37;
            case 11->38;
            case 12->39;
            case 13->49;
            case 14->50;
            case 15->51;
            case 16->52;
            case 17->53;
            case 18->54;
            case 19->55;
            case 20->64;
            case 21->65;
            case 22->66;
            case 23->67;
            case 24->68;
            case 25->69;
            case 26->70;
            case 27->71;
            case 28->72;
            case 29->81;
            case 30->82;
            case 31->83;
            case 32->84;
            case 33->85;
            case 34->86;
            case 35->87;
            case 36->97;
            case 37->98;
            case 38->99;
            case 39->100;
            case 40->101;
            case 41->102;
            case 42->103;
            case 43->114;
            case 44->115;
            case 45->116;
            case 46->117;
            case 47->118;
            case 48->132;
            default->throw new IndexOutOfBoundsException();
        };
    }
}
