package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** One shared F/C root; degree4 winners or certified ranges, no integral. */
final class BPhysicalMaxPanel implements AutoCloseable {
    enum Status { EMPTY,WINNER,RANGE,SOURCE_UNKNOWN,UNRESOLVED }
    final BPhysicalWork work;
    final AttemptMemoryLedger.Owner owner;
    private final BPhysicalResponse.SourceView view;
    private final BPhysicalComponentPanel candidate;
    private final BPhysicalComponentPanel.Polynomial poly;
    private final Weights weights;
    private BPhysicalRootIntegral borrower;
    private BPhysicalAdaptiveIntegral adaptive;
    double ax,ay,bx,by;
    private double dLower,dUpper,dError;
    private int phase=3,consumed,fieldState,contextState,currentState,fieldWinner,contextWinner,currentWinner,
            contextIndex,visits,calls,gain,contextMode;
    private boolean closed;
    //8refs+7doubles+13ints+bool=173fieldbytes,O173=192;reserve192.
    private BPhysicalMaxPanel(BPhysicalResponse response,AttemptMemoryLedger.Owner owner) {
        work=response.work;this.owner=owner;view=response.panelSource();
        candidate=BPhysicalComponentPanel.forMax(response,this);
        poly=candidate.polynomialForMax(this);weights=new Weights(work,owner);phase=0;
    }
    static BPhysicalMaxPanel open(BPhysicalResponse response) {
        return openIn(response,response.work.owner());
    }
    static BPhysicalMaxPanel forTable(BPhysicalResponse response,BPhysicalPairTable.Permit permit) {
        permit.requireConstruction(response);return openIn(response,permit.owner());
    }
    private static BPhysicalMaxPanel openIn(BPhysicalResponse response,AttemptMemoryLedger.Owner parent) {
        response.bound();var w=response.work;w.claimSourcePanel();w.claimComponentPanel();w.claimMaxPanel();
        var child=parent.child("physical-max-panel");
        try {child.reserve(4656+2048+7680+1264+136+2144+192+BPhysicalPanelCertificate.reuseBytes(w));
            var p=new BPhysicalMaxPanel(response,child);w.poll();p.live();return p;
        } catch(RuntimeException|Error failure){child.close();throw failure;}
    }
    void requireConstruction(BPhysicalResponse response) {
        live();if(phase!=3||response.work!=work||response.panelSource()!=view)
            throw new IllegalArgumentException("No owned max construction");
    }
    void requireFor(BPhysicalComponentPanel c) {
        live();if(phase!=2||candidate!=c||c.owner!=owner||c.work!=work)
            throw new IllegalStateException("No active max frame");
    }
    void requireBank(BPhysicalComponentPanel c) {
        live();if((phase!=3&&phase!=2)||candidate!=c||c.owner!=owner||c.work!=work)
            throw new IllegalStateException("No owned max bank");
    }
    BPhysicalComponentPanel.Polynomial loan(BPhysicalRootIntegral integral) {
        ready();integral.requireFor(this);
        if(borrower!=null)throw new IllegalStateException("Root bank already lent");
        borrower=integral;return poly;
    }
    void bindAdaptive(BPhysicalRootIntegral root,BPhysicalAdaptiveIntegral ad) {
        live();ad.requireRoot(root);if(phase!=0||adaptive!=null||consumed!=0)throw new IllegalStateException("Max adaptive binding");adaptive=ad;
    }
    void requireAdaptive(BPhysicalAdaptiveIntegral ad,BPhysicalComponentPanel component) {
        live();if(adaptive!=ad||candidate!=component)throw new IllegalStateException("Foreign adaptive component");ad.requireMax(this);
    }
    //Only candidateFromMax calls this after requireFor on the same active
    //private frame. The authentic table permit is sealed once in Adaptive.
    boolean tableOnly(){return adaptive!=null&&adaptive.tableOnly();}
    void requireLoan(BPhysicalRootIntegral integral){live();if(borrower!=integral)throw new IllegalStateException("Foreign integral bank");integral.requireFor(this);}
    BPhysicalComponentPanel.Reference integralScratch(BPhysicalRootIntegral integral){requireLoan(integral);return candidate.referenceForIntegral(this,integral);}
    void beginIntegral(BPhysicalRootIntegral integral){ready();requireLoan(integral);candidate.beginIntegral(this,integral);}
    boolean exactZero(BPhysicalRootIntegral integral){
        ready();requireLoan(integral);var r=poly.registers;
        if(fieldState<3&&contextState<3&&r[1]==0&&r[3]==0&&r[4]==0&&r[11]==0&&r[13]==0&&r[14]==0)return true;
        // The complete self stream is F, including any changes of winner.
        // Every other weighted stream is bounded below F in both programs.
        if(fieldState<3&&contextState<3&&r[22]==1&&r[23]==1)return true;
        // Both whole-panel maxima select the same observed component with
        // zero translation and exact unit weight. Thus C=F, even for positive
        // signal: identical finite log1p expressions subtract to exact zero.
        return fieldState==1&&contextState==1&&fieldWinner>=0&&fieldWinner==contextMode
            &&contextWinner>=0&&contextWinner<49&&weights.ax[contextWinner]==0&&weights.by[contextWinner]==0
            &&weights.nominal[contextWinner]==1&&weights.lower[contextWinner]==1
            &&weights.upper[contextWinner]==1&&weights.error[contextWinner]==0;
    }
    void certify(double ax,double ay,double bx,double by,int gain) {
        phase=0;live();if(consumed!=0)throw new IllegalStateException("Max root consumed");consumed=1;
        work.admit(BPhysicalWork.MAX_PANEL);boolean returned=false;
        try {
            if(!Double.isFinite(ax)||!Double.isFinite(ay)||!Double.isFinite(bx)||!Double.isFinite(by))
                throw new IllegalArgumentException("Finite original endpoints required");
            if(gain!=1&&gain!=4&&gain!=16&&gain!=64)throw new IllegalArgumentException("Fixed gain family");
            if(Double.compare(ax,bx)>0||Double.compare(ax,bx)==0&&Double.compare(ay,by)>0){double t=ax;ax=bx;bx=t;t=ay;ay=by;by=t;}
            this.ax=ax;this.ay=ay;this.bx=bx;this.by=by;this.gain=gain;
            phase=2;visits=calls=0;candidate.beginMaxRoot(this);evaluate();
            work.poll();live();returned=true;
        } finally {work.complete(BPhysicalWork.MAX_PANEL,returned);if(!returned)phase=0;}
        phase=1;
    }
    void certifyNode(BPhysicalRootIntegral root,BPhysicalAdaptiveIntegral ad) {
        phase=0;live();ad.requireRoot(root);if(adaptive!=ad||(consumed!=0&&consumed!=-1))throw new IllegalStateException("No private max permit");
        consumed=-1;work.admit(BPhysicalWork.MAX_PANEL);boolean returned=false;
        try {
            ax=ad.ax;ay=ad.ay;bx=ad.bx;by=ad.by;gain=ad.gain;phase=2;visits=calls=0;
            candidate.beginAdaptiveNode(this,ad);evaluate();work.poll();ad.requireMax(this);returned=true;
        } finally {work.complete(BPhysicalWork.MAX_PANEL,returned);if(!returned)phase=0;}
        phase=1;
    }
    private void evaluate() {
            fieldState=stream(-1,14,0);fieldWinner=currentWinner;
            clear(10);contextState=0;contextWinner=contextMode=-1;fill(15);
            // Frame-local proof flags, reset also for every adaptive node.
            poly.registers[22]=0;poly.registers[23]=1;
            for(contextIndex=0;contextIndex<49;contextIndex++) {
                currentState=stream(contextIndex,16,5);
                //Full contributor support BEFORE weight,even weight0/self.
                if(currentState>=3){poly.registers[23]=0;contextState=combine(contextState,currentState);continue;}
                weight(contextIndex);
                var r=poly.registers;
                boolean self=weights.ax[contextIndex]==0&&weights.by[contextIndex]==0
                    &&weights.nominal[contextIndex]==1&&weights.lower[contextIndex]==1
                    &&weights.upper[contextIndex]==1&&weights.error[contextIndex]==0;
                if(self)r[22]=1;
                else if(r[16]>Math.max(0,r[0])||r[18]>Math.max(0,r[2]))r[23]=0;
                contextState=update(contextState,currentState,15,17,10,15);
                if(contextState==1&&poly.registers[32]==1){contextWinner=contextIndex;contextMode=currentWinner;}
                else if(contextState==2)contextWinner=contextMode=-1;
            }
            dLower=dUpper=dError=Double.NaN;
            if(fieldState<3&&contextState<3)deficit();
    }
    private int stream(int context,int winnerRow,int lanes) {
        //Every stream, including self, starts with an empty source cache.
        //Thus a new adaptive node or chord cannot reuse another node's rows.
        candidate.beginSourceContext(this);
        clear(lanes);fill(winnerRow);int state=0;currentWinner=-1;
        for(int at=0;at<view.bound.indices[2].length;at++) {
            work.poll();live();visits++;
            if(view.index(2,at)==BSharedPositionEvidence.Status.INELIGIBLE_GROUPED_PARENT.ordinal())continue;
            candidate.candidateFromMax(this,at,context);calls++;
            var s=candidate.status();
            if(s==BPhysicalComponentPanel.Status.SOURCE_UNKNOWN){state=combine(state,3);continue;}
            if(s==BPhysicalComponentPanel.Status.UNRESOLVED||s==BPhysicalComponentPanel.Status.INELIGIBLE){state=4;continue;}
            candidate.scalarForMax(this,15);
            state=update(state,s==BPhysicalComponentPanel.Status.KNOWN_RANGE?2:1,winnerRow,13,lanes,15);
            if(state==1&&poly.registers[32]==1)currentWinner=at;
            else if(state==2)currentWinner=-1;
        }
        return state;
    }
    private static int combine(int a,int b){return a==4||b==4?4:a==3||b==3?3:Math.max(a,b);}
    private void clear(int i){for(int k=0;k<5;k++)poly.registers[i+k]=0;}
    //Candidate publication is complete;these copies belong to the active MAX
    //frame,not to the closed component-source operation. No source-root reset.
    private void fill(int row){for(int k=0;k<5;k++){work.poll();live();poly.put(row,k,0,0,0);}}
    private void copy(int target,int source){for(int k=0;k<5;k++){work.poll();live();poly.put(target,k,poly.c[source][k],poly.lo[source][k],poly.hi[source][k]);}}
    private int update(int state,int incoming,int winner,int input,int out,int in) {
        var r=poly.registers;boolean old=true,fresh=true,equal=true;
        // A newly certified polynomial can dominate the whole previous range
        // in BOTH nominal and reference lanes. Unknown support stays sticky.
        if(state==2&&incoming==1&&r[in]>=r[out+1]&&r[in+2]>=r[out+3]){
            copy(winner,input);for(int k=0;k<5;k++)r[out+k]=r[in+k];r[32]=1;return 1;
        }
        if(state==2||incoming==2){
            for(int k=0;k<5;k++)r[out+k]=Math.max(r[out+k],r[in+k]);
            r[32]=0;return state>=3?state:2; //Never read a stale coefficient.
        }
        for(int k=0;k<5;k++) {
            work.poll();live();double al=poly.lo[winner][k],ah=poly.hi[winner][k],bl=poly.lo[input][k],bh=poly.hi[input][k];
            old&=al>=bh;fresh&=bl>=ah;equal&=al==ah&&bl==bh&&al==bl;
            r[out+k]=Math.max(r[out+k],r[in+k]);
        }
        boolean take=state==0||fresh&&!old;r[32]=take?1:0;
        int result=state>=3?state:state==2||incoming==2?2:state==0||old||fresh||equal?1:2;
        if(result==1&&take)copy(winner,input);
        return result;
    }
    // A positive exact product may round to zero. Adjacent endpoints still
    // enclose it: the positive upper endpoint preserves that tail and its
    // error contribution. Non-finite products remain a refusal.
    private void weight(int index) {
        var r=poly.registers;double w=weights.nominal[index],l=weights.lower[index],h=weights.upper[index];
        if(r[6]==0&&r[8]==0&&r[9]==0||w==0&&l==0&&h==0) {
            //Certified geometric0 (or exact disk boundary weight0),not a
            //missing source:the full stream above already classified support.
            clear(15);if(currentState!=2)fill(17);return;
        }
        boolean exactOne=w==1&&l==1&&h==1&&weights.error[index]==0;
        if(currentState!=2)for(int k=0;k<5;k++) {
            work.poll();live();double c=poly.c[16][k],a=poly.lo[16][k],b=poly.hi[16][k];
            if(exactOne)poly.put(17,k,c,a,b);
            else {
                double al=a*l,ah=a*h,bl=b*l,bh=b*h;
                poly.put(17,k,c*w,Math.nextDown(Math.min(Math.min(al,ah),Math.min(bl,bh))),Math.nextUp(Math.max(Math.max(al,ah),Math.max(bl,bh))));
            }
        }
        r[15]=Math.max(0,downProduct(r[5],w));r[16]=Math.max(0,upProduct(r[6],w));
        r[17]=Math.max(0,downProduct(r[7],l));r[18]=Math.max(0,upProduct(r[8],h));
        r[19]=upSum(upSum(upProduct(r[9],w),upProduct(r[8],weights.error[index])),Math.ulp(Math.max(r[6],r[8])*w));
    }
    private static double downProduct(double a,double b){double v=a*b;finite(v);return Math.nextDown(v);}
    private static double upProduct(double a,double b){double v=a*b;finite(v);return Math.nextUp(v);}
    private static double upSum(double a,double b){double v=Math.nextUp(a+b);finite(v);return v;}
    private void deficit() {
        var r=poly.registers;
        //Real range and RN-program range are separate enclosures;only REAL
        //D is intersected with D>=0. The original RN subtraction is unclamped.
        double realLow=Math.nextDown(logLow(Math.max(0,downProduct(r[12],gain)))-logHigh(upProduct(r[3],gain)));
        double realHigh=Math.nextUp(logHigh(upProduct(r[13],gain))-logLow(Math.max(0,downProduct(r[2],gain))));
        double nominalLow=Math.nextDown(logLow(Math.max(0,r[10]*gain))-logHigh(r[1]*gain));
        double nominalHigh=Math.nextUp(logHigh(r[11]*gain)-logLow(Math.max(0,r[0]*gain)));
        dLower=Math.min(Math.max(0,realLow),nominalLow);dUpper=Math.max(realHigh,nominalHigh);
        //Gain is power2 scaling,exact on nonnegative finite products. Each
        //log API is within1ulp;two neighbors cover binade transitions. The
        //largest range endpoint supplies an upward spacing bound.
        double logMax=logHigh(upProduct(Math.max(r[1],r[11]),gain));
        dError=upSum(upProduct(gain,upSum(r[4],r[14])),
                upSum(upProduct(4,Math.ulp(logMax)),Math.ulp(Math.max(Math.abs(dLower),Math.abs(dUpper)))));
        finite(dLower);finite(dUpper);finite(dError);
    }
    private static double logLow(double a){return Math.max(0,Math.nextDown(Math.nextDown(StrictMath.log1p(a))));}
    private static double logHigh(double a){return Math.nextUp(Math.nextUp(StrictMath.log1p(a)));}
    private static void finite(double x){if(!Double.isFinite(x))throw new ArithmeticException("Max panel overflow");}
    private void live(){work.liveSource();if(closed||owner.isClosed())throw new IllegalStateException("Max panel closed");view.ensureSourceLive();}
    private void ready(){live();if(phase!=1)throw new IllegalStateException("No current max panel");}
    Status fieldStatus(){ready();return status(fieldState);}Status contextStatus(){ready();return status(contextState);}
    private static Status status(int s){return switch(s){case 0->Status.EMPTY;case 1->Status.WINNER;case 2->Status.RANGE;case 3->Status.SOURCE_UNKNOWN;default->Status.UNRESOLVED;};}
    double fieldLower(){ready();return fieldState<3?poly.registers[2]:Double.NaN;}
    double fieldUpper(){ready();return fieldState<3?poly.registers[3]:Double.NaN;}
    double contextLower(){ready();return contextState<3?poly.registers[12]:Double.NaN;}
    double contextUpper(){ready();return contextState<3?poly.registers[13]:Double.NaN;}
    double deficitLower(){ready();return dLower;}double deficitUpper(){ready();return dUpper;}double error(){ready();return dError;}
    double coefficient(boolean context,int index){ready();if(index<0||index>4)throw new IndexOutOfBoundsException();return (context?contextState:fieldState)==1?poly.c[context?15:14][index]:Double.NaN;}
    double lowerCoefficient(boolean context,int index){ready();if(index<0||index>4)throw new IndexOutOfBoundsException();return (context?contextState:fieldState)==1?poly.lo[context?15:14][index]:Double.NaN;}
    double upperCoefficient(boolean context,int index){ready();if(index<0||index>4)throw new IndexOutOfBoundsException();return (context?contextState:fieldState)==1?poly.hi[context?15:14][index]:Double.NaN;}
    double fieldError(){ready();return fieldState<3?poly.registers[4]:Double.NaN;}
    double contextError(){ready();return contextState<3?poly.registers[14]:Double.NaN;}
    int winner(boolean context){ready();return (context?contextState:fieldState)==1?(context?contextMode:fieldWinner):-1;}
    int contextOffset(){ready();return contextState==1?contextWinner:-1;}
    int visits(){ready();return visits;}int calls(){ready();return calls;}
    @Override public void close(){closed=true;owner.close();}
    /**Same reviewed2144B weight record;no shared mutable point-context bank. */
    private static final class Weights {
        final int[] ax=new int[49],by=new int[49];
        final double[] nominal=new double[49],lower=new double[49],upper=new double[49],error=new double[49];
        final BPhysicalWork work;final AttemptMemoryLedger.Owner owner;
        Weights(BPhysicalWork w,AttemptMemoryLedger.Owner o){work=w;owner=o;for(int i=0;i<49;i++) {
            work.poll();int pair=BPhysicalContext.offset(i);ax[i]=(pair>>>4)-4;by[i]=(pair&15)-4;int d=ax[i]*ax[i]+by[i]*by[i];
            double root=StrictMath.sqrt(d),a=Math.max(0,Math.nextDown(root)),b=Math.nextUp(root);
            nominal[i]=1-root/4;lower[i]=Math.max(0,Math.nextDown(1-Math.nextUp(b/4)));upper[i]=Math.min(1,Math.nextUp(1-Math.nextDown(a/4)));
            error[i]=Math.nextUp(Math.max(Math.abs(nominal[i]-lower[i]),Math.abs(upper[i]-nominal[i])));finite(error[i]);
            if(d==0||d==16){lower[i]=upper[i]=nominal[i];error[i]=0;} //sqrt exact squares0/16.
        }work.poll();}
    }
}
