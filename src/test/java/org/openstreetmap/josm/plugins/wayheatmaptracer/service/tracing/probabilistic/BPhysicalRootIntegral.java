package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** One certified root or refusal;no subdivision/table/solver capability. */
final class BPhysicalRootIntegral implements AutoCloseable {
    enum Status { CERTIFIED,REFUSED,UNRESOLVED,SOURCE_UNKNOWN }
    enum Refinement { NONE,RANGE,BRANCH,LOG_DOMAIN,ACCURACY }
    final BPhysicalWork work;
    final AttemptMemoryLedger.Owner owner;
    private final BPhysicalMaxPanel max;
    private BPhysicalAdaptiveIntegral adaptive;
    private BPhysicalComponentPanel.Polynomial poly;
    private BPhysicalComponentPanel.Reference scratch;
    private double centre,lower,upper,error,unknownLower,unknownUpper,budget,length,lengthLower,lengthUpper;
    private int phase=3,consumed,status,gain,attempted,valid,rejected,logs,checks;
    private long operationStart=-1;
    private boolean closed;
    //6refs+10doubles+9ints+long+flag=173fieldbytes,O173=192;prefund192.
    private BPhysicalRootIntegral(BPhysicalMaxPanel max) {
        this.max=max;work=max.work;owner=max.owner;phase=0;
    }
    static BPhysicalRootIntegral open(BPhysicalResponse response) {
        var max=BPhysicalMaxPanel.open(response);
        try {response.work.claimRootIntegral();max.owner.reserve(192);var r=new BPhysicalRootIntegral(max);r.work.poll();r.live();return r;}
        catch(RuntimeException|Error failure){max.close();throw failure;}
    }
    static BPhysicalRootIntegral forTable(BPhysicalResponse response,BPhysicalPairTable.Permit permit) {
        permit.requireConstruction(response);var max=BPhysicalMaxPanel.forTable(response,permit);
        try {response.work.claimRootIntegral();max.owner.reserve(192);var r=new BPhysicalRootIntegral(max);r.work.poll();r.live();return r;}
        catch(RuntimeException|Error failure){max.close();throw failure;}
    }
    void requireConstruction(BPhysicalMaxPanel expected){if(phase!=3||expected!=max||expected.work!=work||expected.owner!=owner||closed||owner.isClosed())throw new IllegalStateException("Foreign root construction");}
    void requireFor(BPhysicalMaxPanel expected){if(expected!=max||closed||owner.isClosed()||(phase!=3&&phase!=2&&phase!=1))throw new IllegalStateException("No owned integral frame");}
    void bindAdaptive(BPhysicalAdaptiveIntegral ad) {
        live();ad.requireRoot(this);if(phase!=0||consumed!=0||adaptive!=null)throw new IllegalStateException("Adaptive root binding");
        adaptive=ad;consumed=-1;max.bindAdaptive(this,ad);
    }
    void requireAdaptiveComponent(BPhysicalAdaptiveIntegral ad,BPhysicalComponentPanel component){live();if(adaptive!=ad)throw new IllegalStateException("Foreign adaptive evaluator");max.requireAdaptive(ad,component);}
    void requireAdaptiveMax(BPhysicalAdaptiveIntegral ad,BPhysicalMaxPanel expected){live();if(adaptive!=ad||max!=expected)throw new IllegalStateException("Foreign adaptive maximum");}
    void prepareAdaptiveEdge(BPhysicalAdaptiveIntegral ad) {
        phase=0;live();if(adaptive!=ad)throw new IllegalStateException("No adaptive edge capability");ad.requireRoot(this);
        phase=2;checks=0;length(ad.ax,ad.ay,ad.bx,ad.by);ad.storeOriginalLength(this,length,lengthLower,lengthUpper);phase=0;
    }
    void certifyNode(BPhysicalAdaptiveIntegral ad) {
        phase=0;live();ad.requireRoot(this);if(adaptive!=ad||consumed!=-1)throw new IllegalStateException("No private root permit");
        work.admit(BPhysicalWork.ROOT_INTEGRAL);boolean returned=false;
        try {
            phase=2;checks=attempted=valid=rejected=logs=0;operationStart=-1;gain=ad.gain;
            centre=lower=upper=error=Double.NaN;unknownLower=unknownUpper=0;status=Status.REFUSED.ordinal();
            double width=Math.scalb(1.0,-ad.activeDepth());
            length=ad.originalLength()*width;lengthLower=Math.max(0,Math.nextDown(ad.originalLengthLower()*width));lengthUpper=Math.nextUp(ad.originalLengthUpper()*width);
            if(!Double.isFinite(length)||length==0||lengthLower<=0)throw new ArithmeticException("Node length refused");
            max.certifyNode(this,ad);consumeMaximum();check();ad.requireRoot(this);returned=true;
        } finally {work.complete(BPhysicalWork.ROOT_INTEGRAL,returned);if(!returned)phase=0;}
        phase=1;
    }
    private void live(){work.liveSource();if(closed||owner.isClosed())throw new IllegalStateException("Integral closed");}
    private void check(){if(checks==BPhysicalWork.ROOT_LOOP_CHECKS)throw new IllegalStateException("Root checkpoint ceiling");checks++;work.poll();live();if(phase!=2)throw new IllegalStateException("Integral frame unpublished");if(poly!=null)max.requireLoan(this);}
    void certify(double ax,double ay,double bx,double by,int gain) {
        phase=0;live();if(consumed!=0)throw new IllegalStateException("Integral root consumed");consumed=1;
        work.admit(BPhysicalWork.ROOT_INTEGRAL);boolean returned=false;
        try {
            if(!Double.isFinite(ax)||!Double.isFinite(ay)||!Double.isFinite(bx)||!Double.isFinite(by))throw new IllegalArgumentException("Finite original endpoints required");
            if(gain!=1&&gain!=4&&gain!=16&&gain!=64)throw new IllegalArgumentException("Fixed gain family");
            if(Double.compare(ax,bx)>0||Double.compare(ax,bx)==0&&Double.compare(ay,by)>0){double t=ax;ax=bx;bx=t;t=ay;ay=by;by=t;}
            this.gain=gain;phase=2;attempted=valid=rejected=logs=0;
            centre=lower=upper=error=Double.NaN;unknownLower=unknownUpper=0;status=Status.REFUSED.ordinal();
            if(ax==bx&&ay==by){length=lengthLower=lengthUpper=0;zero();}
            else {
                length(ax,ay,bx,by);max.certify(ax,ay,bx,by,gain);
                consumeMaximum();
            }
            check();returned=true;
        } finally {work.complete(BPhysicalWork.ROOT_INTEGRAL,returned);if(!returned)phase=0;}
        phase=1;
    }
    private void length(double ax,double ay,double bx,double by) {
        check();double dx=bx-ax,dy=by-ay;
        double ex=dx==0?0:Math.ulp(dx),ey=dy==0?0:Math.ulp(dy),delta=Math.nextUp(ex+ey);
        double n=StrictMath.hypot(dx,dy);
        double l=Math.max(0,Math.nextDown(Math.nextDown(Math.nextDown(n))-delta));
        double h=Math.nextUp(Math.nextUp(Math.nextUp(n))+delta);
        if(!Double.isFinite(n)||!Double.isFinite(l)||!Double.isFinite(h)||n==0||l<=0)throw new ArithmeticException("Length enclosure refused");
        length=n;lengthLower=l;lengthUpper=h;
    }
    private void consumeMaximum() {
        //Only AFTER every component/source callback has completed. Subsequent
        //private node frames refresh the SAME immutable-identity loan.
        if(poly==null){poly=max.loan(this);scratch=max.integralScratch(this);}else max.requireLoan(this);
        poly.registers[24]=length;poly.registers[25]=lengthLower;poly.registers[26]=lengthUpper;
        if(max.fieldStatus()==BPhysicalMaxPanel.Status.UNRESOLVED||max.contextStatus()==BPhysicalMaxPanel.Status.UNRESOLVED){status=Status.UNRESOLVED.ordinal();unknownLower=unknownUpper=Double.NaN;}
        else if(max.fieldStatus()==BPhysicalMaxPanel.Status.SOURCE_UNKNOWN||max.contextStatus()==BPhysicalMaxPanel.Status.SOURCE_UNKNOWN){status=Status.SOURCE_UNKNOWN.ordinal();unknownUpper=lengthUpper;}
        else if(max.exactZero(this))zero();else integrate();
    }
    private double currentBudget(){return adaptive==null?Math.nextDown(Math.scalb(Math.max(1,lengthLower),-32)):adaptive.nodeBudget(this);}
    private double currentUnknownBudget(){return adaptive==null?Math.nextDown(Math.scalb(Math.max(1,lengthLower),-32)):adaptive.nodeUnknownBudget(this);}
    private void zero(){centre=lower=upper=error=unknownLower=unknownUpper=0;budget=currentBudget();status=Status.CERTIFIED.ordinal();}
    private void integrate() {
        max.beginIntegral(this);operationStart=poly.operations;scratch.seed(19,gain);
        //Two exact integer literals<2^53,one checked product,then outward
        //division. Symmetric interval has central0;no rounded-down tail.
        long denominator=Math.multiplyExact(14L,10460353203L);
        scratch.seed(22,(double)denominator);op(22,24,22,3);
        scratch.c[29]=0;scratch.lo[29]=-scratch.hi[22];scratch.hi[29]=scratch.hi[22];
        boolean winners=max.fieldStatus()==BPhysicalMaxPanel.Status.WINNER&&max.contextStatus()==BPhysicalMaxPanel.Status.WINNER;
        boolean polynomial=winners&&moment(14,18);
        if(polynomial) {
            copyRecord(30,11);polynomial=moment(15,19);
        }
        if(polynomial) {
            op(14,11,30,1);
            //The coefficient intervals already include real-polynomial gamma,
            //centre-log API error and BOTH analytic tails. Max.error is the
            //SEPARATE original nominal-D versus exact-real-D coupling.
            scratch.seed(31,max.error());
            scratch.lo[14]=Math.nextDown(scratch.lo[14]-scratch.c[31]);
            scratch.hi[14]=Math.nextUp(scratch.hi[14]+scratch.c[31]);
        } else {
            check();double l=max.deficitLower(),h=max.deficitUpper();
            scratch.c[14]=l/2+h/2;scratch.lo[14]=l;scratch.hi[14]=h;
        }
        scratch.c[15]=length;scratch.lo[15]=lengthLower;scratch.hi[15]=lengthUpper;
        op(16,14,15,2);centre=scratch.c[16];lower=scratch.lo[16];upper=scratch.hi[16];
        error=Math.nextUp(Math.max(centre-lower,upper-centre));
        budget=currentBudget();
        if(!Double.isFinite(centre)||!Double.isFinite(lower)||!Double.isFinite(upper)||!Double.isFinite(error)||budget<=0)
            throw new ArithmeticException("Integral enclosure refused");
        //L0=1m. Root width=1;J/error dimensionless,unknown length metres.
        //No central-value clamp and no split when this actual gate fails.
        status=centre>=0&&error<=budget&&unknownUpper-unknownLower<=currentUnknownBudget()?Status.CERTIFIED.ordinal():Status.REFUSED.ordinal();
    }
    /** One exact-real degree20/80 moment;false takes monotone RANGE only. */
    private boolean moment(int input,int z) {
        double yl=Double.POSITIVE_INFINITY,yh=Double.NEGATIVE_INFINITY;
        for(int i=0;i<5;i++) {
            check();poly.op(scratch.c,scratch.lo,scratch.hi,8,poly.c[input],poly.lo[input],poly.hi[input],i,scratch.c,scratch.lo,scratch.hi,19,2);
            op(8,8,24,0);poly.put(z,i,scratch.c[8],scratch.lo[8],scratch.hi[8]);
            yl=Math.min(yl,scratch.lo[8]);yh=Math.max(yh,scratch.hi[8]);
        }
        double a=yl/2+yh/2;
        if(!Double.isFinite(a)||a<=0||yl<=0)return false;
        scratch.seed(20,a);double bound=0;
        for(int i=0;i<5;i++) {
            check();poly.op(scratch.c,scratch.lo,scratch.hi,8,poly.c[z],poly.lo[z],poly.hi[z],i,scratch.c,scratch.lo,scratch.hi,20,3);
            op(8,8,24,1);poly.put(z,i,scratch.c[8],scratch.lo[8],scratch.hi[8]);
            bound=Math.max(bound,Math.max(Math.abs(scratch.lo[8]),Math.abs(scratch.hi[8])));
        }
        double checkBound=Math.nextUp(3*bound);
        if(!Double.isFinite(checkBound)||checkBound>1)return false;
        check();double log=StrictMath.log(a),ll=Math.nextDown(Math.nextDown(log)),lh=Math.nextUp(Math.nextUp(log));
        if(!Double.isFinite(log)||!Double.isFinite(ll)||!Double.isFinite(lh))throw new ArithmeticException("Centre log enclosure refused");
        scratch.c[11]=log;scratch.lo[11]=ll;scratch.hi[11]=lh;
        int power=20,output=21,degree=4;
        for(int i=0;i<5;i++){check();poly.put(power,i,poly.c[z][i],poly.lo[z][i],poly.hi[z][i]);}
        for(int k=1;k<=20;k++) {
            check();if(k>1) {
                int d=4*k;
                for(int h=0;h<=d;h++) {
                    check();poly.put(output,h,0,0,0);
                    for(int j=0;j<=4;j++) {
                        check();if(attempted==8550)throw new IllegalStateException("Log attempt ceiling");attempted++;
                        int i=h-j;
                        if(i<0||i>degree){rejected++;continue;}
                        valid++;weight(d,h,j);
                        poly.op(scratch.c,scratch.lo,scratch.hi,8,poly.c[power],poly.lo[power],poly.hi[power],i,poly.c[z],poly.lo[z],poly.hi[z],j,2);
                        op(8,8,9,2);
                        poly.op(poly.c[output],poly.lo[output],poly.hi[output],h,poly.c[output],poly.lo[output],poly.hi[output],h,scratch.c,scratch.lo,scratch.hi,8,0);
                    }
                }
                int swap=power;power=output;output=swap;degree=d;
            }
            scratch.seed(13,0);
            for(int i=0;i<=degree;i++) {
                check();poly.op(scratch.c,scratch.lo,scratch.hi,13,scratch.c,scratch.lo,scratch.hi,13,poly.c[power],poly.lo[power],poly.hi[power],i,0);
            }
            scratch.seed(22,degree+1);op(13,13,22,3);scratch.seed(22,k);op(13,13,22,3);
            op(11,11,13,(k&1)==0?1:0);
        }
        op(11,11,29,0);logs++;return true;
    }
    private void weight(int d,int h,int j) {
        //Reached only after attempted-slot guard and valid i=h-j. Each of
        //four numerator/four denominator factors is1..80. Products<=6*80^4
        //and80^4 are exact longs AND exact binary64 integers(<2^53).
        long n=j==2?6:j==1||j==3?4:1,den=1;
        for(int t=0;t<j;t++)n*=h-t;
        for(int t=0;t<4-j;t++)n*=d-h-t;
        for(int t=0;t<4;t++)den*=d-t;
        if(d<8||d>80||n<=0||n>245760000L||den<=0||den>40960000L)throw new ArithmeticException("Bernstein weight domain");
        double w=(double)n/(double)den;
        if(!Double.isFinite(w)||w<=0||w>1)throw new ArithmeticException("Bernstein weight refused");
        scratch.c[9]=w;scratch.lo[9]=Math.nextDown(w);scratch.hi[9]=Math.nextUp(w);
    }
    private void op(int out,int a,int b,int kind){poly.op(scratch.c,scratch.lo,scratch.hi,out,scratch.c,scratch.lo,scratch.hi,a,scratch.c,scratch.lo,scratch.hi,b,kind);}
    private void copyRecord(int out,int in){check();scratch.c[out]=scratch.c[in];scratch.lo[out]=scratch.lo[in];scratch.hi[out]=scratch.hi[in];}
    private void ready(){live();if(phase!=1)throw new IllegalStateException("No current integral");}
    Status status(){ready();return switch(status){case 0->Status.CERTIFIED;case 1->Status.REFUSED;case 2->Status.UNRESOLVED;default->Status.SOURCE_UNKNOWN;};}
    Refinement refinement() {
        ready();if(status==2)return Refinement.BRANCH;
        if(status!=1||!Double.isFinite(centre)||centre<0||!Double.isFinite(error)||error<0||!Double.isFinite(lower)||!Double.isFinite(upper)
                ||lower>centre||upper<centre)return Refinement.NONE;
        if(max.fieldStatus()==BPhysicalMaxPanel.Status.RANGE||max.contextStatus()==BPhysicalMaxPanel.Status.RANGE)return Refinement.RANGE;
        //Only the authentic table policy refines a completed,valid polynomial
        //whose fixed-K leaf tolerance was missed. Numeric/source failures and
        //the public single-chord policy retain their existing classifications.
        if(adaptive!=null&&adaptive.tableOnly()&&logs==2&&Double.isFinite(unknownLower)&&Double.isFinite(unknownUpper)
                &&unknownLower>=0&&unknownUpper>=unknownLower)return Refinement.ACCURACY;
        return logs<2?Refinement.LOG_DOMAIN:Refinement.NONE;
    }
    double value(){ready();return status==0?centre:Double.NaN;}
    double error(){ready();return error;}double lower(){ready();return lower;}double upper(){ready();return upper;}
    double unknownLower(){ready();return unknownLower;}double unknownUpper(){ready();return unknownUpper;}
    int attempted(){ready();return attempted;}int valid(){ready();return valid;}int rejected(){ready();return rejected;}
    int logs(){ready();return logs;}double budget(){ready();return budget;}double lengthLower(){ready();return lengthLower;}double lengthUpper(){ready();return lengthUpper;}
    long coefficientOperations(){ready();return operationStart<0?0:poly.operations-operationStart;}
    int checkpointCalls(){ready();return checks;}
    double tailUpper(){ready();return logs>0?scratch.hi[29]:0;}
    //Factual progress observer for the SAME caller probe,not a partial result.
    //No coefficient/value/error is observable before final phase1.
    int completedMomentCalls(){live();return logs;}
    int checkpointIntents(){live();return checks;}
    @Override public void close(){closed=true;max.close();}
}
