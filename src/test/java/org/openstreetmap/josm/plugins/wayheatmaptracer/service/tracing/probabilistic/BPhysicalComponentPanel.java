package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** One source-bound component root;no context maximum or integral authority. */
final class BPhysicalComponentPanel implements AutoCloseable {
    enum Status { AVAILABLE,GEOMETRIC_ZERO,SOURCE_UNKNOWN,UNRESOLVED,INELIGIBLE,KNOWN_RANGE }
    //7refs+5ints+5doubles+bool=117fieldbytes,O117=136.
    final BPhysicalWork work;
    final AttemptMemoryLedger.Owner owner;
    private final BPhysicalResponse.SourceView view;
    private final BPhysicalPanelCertificate source;
    private final ComponentBank bank;
    private final Polynomial poly;
    private final Reference reference;
    double ax,ay,bx,by;
    private double uniform;
    private int phase=3,status,contextX=Integer.MIN_VALUE,contextY,consumed;
    private boolean closed;
    private BPhysicalComponentPanel(BPhysicalResponse response,AttemptMemoryLedger.Owner owner) {
        this.work=response.work;this.owner=owner;view=response.panelSource();
        source=BPhysicalPanelCertificate.componentFrame(response,this);
        bank=new ComponentBank(work);poly=new Polynomial(this);reference=new Reference(this);
        phase=0;
    }
    static BPhysicalComponentPanel open(BPhysicalResponse response) {
        response.bound();var w=response.work;w.claimSourcePanel();w.claimComponentPanel();
        var child=w.owner().child("physical-component-panel");
        //Original banks remain live. Source4208+wrapper96+root352;
        //the immutable prefix shell/array is a separately funded child.
        //new component2048+poly7680+reference1264+actual outer136.
        try {child.reserve(4656+2048+7680+1264+136+BPhysicalPanelCertificate.reuseBytes(w));var p=new BPhysicalComponentPanel(response,child);
            w.poll();p.live();return p;
        } catch(RuntimeException|Error failure){child.close();throw failure;}
    }
    static BPhysicalComponentPanel forMax(BPhysicalResponse response,BPhysicalMaxPanel parent) {
        parent.requireConstruction(response);return new BPhysicalComponentPanel(response,parent.owner);
    }
    void beginSourceContext(BPhysicalMaxPanel parent) {
        phase=2;live();parent.requireFor(this);source.resetComponentReuse(this);
    }
    void beginMaxRoot(BPhysicalMaxPanel parent) {
        phase=0;live();parent.requireFor(this);
        if(consumed!=0)throw new IllegalStateException("Component root consumed");
        consumed=-1;phase=2;ax=parent.ax;ay=parent.ay;bx=parent.bx;by=parent.by;
        source.beginComponent(this);
    }
    void beginAdaptiveNode(BPhysicalMaxPanel parent,BPhysicalAdaptiveIntegral adaptive) {
        phase=0;live();adaptive.requireComponent(this);parent.requireFor(this);
        if(consumed!=0&&consumed!=-1)throw new IllegalStateException("Public component root consumed");
        phase=2;ax=parent.ax;ay=parent.ay;bx=parent.bx;by=parent.by;
        if(consumed==0){consumed=-1;source.beginComponent(this);}else source.activeComponent(this);
        source.adaptiveNode(this,adaptive);
    }
    void candidateFromMax(BPhysicalMaxPanel parent,int at,int context) {
        phase=0;live();parent.requireFor(this);
        if(consumed!=-1||context< -1||context>=49)throw new IllegalStateException("No private max root");
        phase=2;ax=parent.ax;ay=parent.ay;bx=parent.bx;by=parent.by;
        if(context<0){contextX=Integer.MIN_VALUE;contextY=0;}
        else {int pair=BPhysicalContext.offset(context);contextX=(pair>>>4)-4;contextY=(pair&15)-4;}
        work.admit(BPhysicalWork.COMPONENT_PANEL);boolean returned=false;
        try {
            work.poll();parent.requireFor(this);var s=source.activeComponent(this);
            int boundStatus=view.index(2,at);
            for(int i=0;i<11;i++)bank.seed(i,view.descriptor(i,at));
            if(boundStatus==BSharedPositionEvidence.Status.AVAILABLE.ordinal())status=evaluate(s,parent.tableOnly(),parent);
            else if(boundStatus==BSharedPositionEvidence.Status.INELIGIBLE_GROUPED_PARENT.ordinal())status=Status.INELIGIBLE.ordinal();
            else {
                if(!s.prepareComponent(this,0,0,-1))status=unresolved();
                else {
                    bank.copyFull(11,s,36);bank.copyFull(12,s,40);
                    double length=view.descriptor(18,at);
                    status=!project(s,13,14,11,12)?unresolved():bracket(13)==0||bank.low(14)>length||bank.high(14)<-length
                            ?geometricZero():Status.SOURCE_UNKNOWN.ordinal();
                }
            }
            work.poll();parent.requireFor(this);requireFrame(source);returned=true;
        } finally {work.complete(BPhysicalWork.COMPONENT_PANEL,returned);if(!returned)phase=0;}
        phase=1;
    }
    Polynomial polynomialForMax(BPhysicalMaxPanel parent){live();parent.requireBank(this);return poly;}
    Reference referenceForIntegral(BPhysicalMaxPanel parent,BPhysicalRootIntegral integral){live();parent.requireLoan(integral);return reference;}
    void beginIntegral(BPhysicalMaxPanel parent,BPhysicalRootIntegral integral){live();parent.requireLoan(integral);if(phase!=1)throw new IllegalStateException("Candidate stream incomplete");phase=2;}
    void scalarForMax(BPhysicalMaxPanel parent,int offset) {
        ready();parent.requireFor(this);
        if(status>1&&status!=Status.KNOWN_RANGE.ordinal())throw new IllegalStateException("Unavailable candidate scalar");
        var r=poly.registers;
        r[offset]=status==1?0:bank.n[35];r[offset+1]=status==1?0:bank.p[35];
        r[offset+2]=status==1?0:Math.max(0,bank.lo[35]);r[offset+3]=status==1?0:Math.max(0,bank.hi[35]);
        r[offset+4]=status==1?0:bank.error[35];
    }
    boolean hasContext(){return contextX!=Integer.MIN_VALUE;}
    int contextX(){return contextX;}int contextY(){return contextY;}
    void certify(int row,int mode,double ax,double ay,double bx,double by) {
        phase=0;live();if(consumed!=0)throw new IllegalStateException("Component root consumed");consumed=1;
        work.admit(BPhysicalWork.COMPONENT_PANEL);boolean returned=false;
        try {
            if(!Double.isFinite(ax)||!Double.isFinite(ay)||!Double.isFinite(bx)||!Double.isFinite(by))
                throw new IllegalArgumentException("Finite original endpoint bits required");
            int order=Double.compare(ax,bx);
            if(order>0||order==0&&Double.compare(ay,by)>0){double v=ax;ax=bx;bx=v;v=ay;ay=by;by=v;}
            contextX=Integer.MIN_VALUE;contextY=0;this.ax=ax;this.ay=ay;this.bx=bx;this.by=by;phase=2;
            var bound=view.bound;
            if(row<0||row>=bound.rows.length-1||mode<0||mode>=bound.rows[row+1]-bound.rows[row])throw new IndexOutOfBoundsException();
            int at=bound.rows[row]+mode;var s=source.beginComponent(this);
            if(view.index(2,at)!=BSharedPositionEvidence.Status.AVAILABLE.ordinal())status=Status.INELIGIBLE.ordinal();
            else {
                for(int i=0;i<11;i++)bank.seed(i,view.descriptor(i,at));
                status=evaluate(s,false,null);
            }
            work.poll();requireFrame(source);returned=true;
        } finally {work.complete(BPhysicalWork.COMPONENT_PANEL,returned);if(!returned)phase=0;}
        phase=1;
    }
    private int evaluate(BPhysicalPanelCertificate.SourceBank s,boolean table,BPhysicalMaxPanel currentMax) {
        if(!s.prepareComponent(this,0,0,-1))return unresolved();
        bank.copyFull(11,s,36);bank.copyFull(12,s,40);
        if(!project(s,13,14,11,12))return unresolved();
        int bracket=bracket(13);if(bracket==0)return geometricZero();if(bracket<0&&!table)return unresolved();
        boolean polynomial=bracket>0;
        int baseP=cap(bank.n[13],bank.p[13]),baseE=cap(bank.lo[13],bank.hi[13]);
        if(!s.transferTo(bank,15,13,4,1))return unresolved();
        int su=sign(15),sz=sign(14);if((su==0||sz==0)&&!table)return unresolved();
        polynomial&=su!=0&&sz!=0;
        reference.cases[4]=su;reference.cases[5]=sz;
        bank.copyFull(16,bank,14);
        if(table){absolute(15);absolute(16);}else {bank.applySign(15,su);bank.applySign(16,sz);}
        bank.seed(17,1);
        if(!s.transferTo(bank,15,15,7,3)||!s.transferTo(bank,16,16,8,3)
                ||!s.transferTo(bank,15,17,15,1)||!s.transferTo(bank,16,17,16,1))return unresolved();
        if(bank.high(15)<=0||bank.high(16)<=0)return geometricZero();
        if(bank.low(15)<0||bank.low(16)<0){if(!table)return unresolved();polynomial=false;}
        if(table){positivePart(15);positivePart(16);}
        if(polynomial&&!buildTents(s)){if(!table)return unresolved();polynomial=false;}
        poly.fill(11,2); //vertical B3 starts only after certified tap values.
        for(int ty=-1;ty<=1;ty++) {
            poly.fill(10,2);
            for(int tx=-1;tx<=1;tx++) {
                work.poll();requireFrame(source);int tap=(ty+1)*3+tx+1;
                int support=table?source.componentTableTap(this,currentMax,tx,ty):source.componentTap(this,tx,ty);
                if(support!=0)return support==1?Status.SOURCE_UNKNOWN.ordinal():unresolved();
                bank.copyFull(18,s,44);bank.copyFull(19,s,48);
                if(!project(s,20,-1,18,19))return unresolved();
                int own=bracket(20);if(own<0){if(!table)return unresolved();polynomial=false;}
                int ownP=cap(bank.n[20],bank.p[20]),ownE=cap(bank.lo[20],bank.hi[20]);
                //Every applicable tap has complete support,including capped
                //and zero-weight taps. Adjacent-cell ranges have no polynomial.
                if(table&&s.scratch!=1&&s.scratch!=2)return unresolved();
                if(table&&s.scratch==2)polynomial=false;
                if(own==0){bank.seed(21+tap,0);if(polynomial)poly.fill(9,2);}
                else {
                    if(s.scratch!=2&&!s.interpolateFixed())return unresolved();
                    bank.copyFull(21+tap,s,29);
                    if(!s.transferTo(bank,21+tap,21+tap,5,1)||!s.transferTo(bank,21+tap,21+tap,6,1))return unresolved();
                    int positive;
                    if(table){positive=bank.high(21+tap)<=0?0:bank.low(21+tap)>=0?1:-1;positivePart(21+tap);
                        if(positive<0)polynomial=false;capUnion(21+tap,ownP,ownE);}
                    else {positive=positive(21+tap);if(positive<0)return unresolved();}
                    if(polynomial&&!buildTap(s,tx,ty,positive)){if(!table)return unresolved();polynomial=false;}
                }
                if(polynomial)for(int k=0;k<3;k++) {
                    if(tx==-1)poly.copy(10,k,9,k);
                    else {if(tx==0)poly.scale(9,k,2);poly.add(10,k,10,k,9,k);}
                    if(tx==1)poly.divide(10,k,4);
                }
            }
            if(polynomial)for(int k=0;k<3;k++) {
                if(ty==-1)poly.copy(11,k,10,k);
                else {if(ty==0)poly.scale(10,k,2);poly.add(11,k,11,k,10,k);}
                if(ty==1)poly.divide(11,k,4);
            }
        }
        bank.seed(33,2);bank.seed(34,4);
        for(int i=0;i<3;i++)if(!s.transferTo(bank,30+i,22+3*i,33,2)||!s.transferTo(bank,30+i,21+3*i,30+i,0)
                ||!s.transferTo(bank,30+i,30+i,23+3*i,0)||!s.transferTo(bank,30+i,30+i,34,3))return unresolved();
        if(!s.transferTo(bank,31,31,33,2)||!s.transferTo(bank,30,30,31,0)||!s.transferTo(bank,30,30,32,0)
                ||!s.transferTo(bank,30,30,34,3)||!s.transferTo(bank,35,16,15,2)||!s.transferTo(bank,35,35,30,2))return unresolved();
        if(table){capUnion(35,baseP,baseE);
            if(!domain(35))return unresolved();
            if(!polynomial){cartesian(35);uniform=Double.NaN;return Status.KNOWN_RANGE.ordinal();}}
        poly.product(12,11,2,4);poly.product(13,12,3,5);
        double gamma=0;
        for(int i=0;i<5;i++)gamma=Math.max(gamma,Math.nextUp(Math.max(poly.c[13][i]-poly.lo[13][i],poly.hi[13][i]-poly.c[13][i])));
        uniform=Math.nextUp(bank.error[35]+gamma);
        if(!Double.isFinite(uniform))throw new ArithmeticException("Component uniform error overflow");
        return Status.AVAILABLE.ordinal();
    }
    private boolean project(BPhysicalPanelCertificate.SourceBank s,int u,int z,int x,int y) {
        if(!s.transferTo(bank,36,x,0,1)||!s.transferTo(bank,37,y,1,1)||!s.transferTo(bank,38,36,2,2)
                ||!s.transferTo(bank,39,37,3,2)||!s.transferTo(bank,u,38,39,0))return false;
        if(z<0)return true;bank.seed(40,-bank.n[3]);
        return s.transferTo(bank,38,36,40,2)&&s.transferTo(bank,39,37,2,2)&&s.transferTo(bank,z,38,39,0);
    }
    private int bracket(int index) {
        if(bank.high(index)<bank.n[9]||bank.low(index)>bank.n[10])return 0;
        return bank.low(index)>=bank.n[9]&&bank.high(index)<=bank.n[10]?1:-1;
    }
    //Callback-free interval lane operators;the table component body separately
    //prepays ABS148,positive88,cap56/90,Cartesian96 and domain98 programs.
    private int cap(double lower,double upper) {
        return upper<bank.n[9]||lower>bank.n[10]?0:lower>=bank.n[9]&&upper<=bank.n[10]?1:2;
    }
    private void absolute(int i) {
        double a=bank.n[i],b=bank.p[i],c=bank.lo[i],d=bank.hi[i];
        double aa=Math.abs(a),bb=Math.abs(b),cc=Math.abs(c),dd=Math.abs(d);
        bank.n[i]=a<=0&&b>=0?0:Math.min(aa,bb);bank.p[i]=Math.max(aa,bb);
        bank.lo[i]=c<=0&&d>=0?0:Math.min(cc,dd);bank.hi[i]=Math.max(cc,dd);
    }
    private void positivePart(int i) {
        bank.n[i]=Math.max(0,bank.n[i]);bank.p[i]=Math.max(0,bank.p[i]);
        bank.lo[i]=Math.max(0,bank.lo[i]);bank.hi[i]=Math.max(0,bank.hi[i]);
    }
    private void capUnion(int i,int p,int e) {
        if(p==0)bank.n[i]=bank.p[i]=0;else if(p==2)bank.n[i]=0;
        if(e==0)bank.lo[i]=bank.hi[i]=0;else if(e==2)bank.lo[i]=0;
        if(p!=1||e!=1)cartesian(i);
    }
    private void cartesian(int i) {
        bank.error[i]=Math.nextUp(Math.max(0,Math.max(bank.p[i]-bank.lo[i],bank.hi[i]-bank.n[i])));
        if(!Double.isFinite(bank.error[i]))throw new ArithmeticException("Component range coupling overflow");
    }
    private boolean domain(int i) {
        bank.n[i]=Math.max(0,bank.n[i]);bank.p[i]=Math.min(5,bank.p[i]);
        bank.lo[i]=Math.max(0,bank.lo[i]);bank.hi[i]=Math.min(1,bank.hi[i]);
        return bank.n[i]<=bank.p[i]&&bank.lo[i]<=bank.hi[i];
    }
    private int sign(int index){return bank.low(index)>=0?1:bank.high(index)<=0?-1:0;}
    private int positive(int index) {
        if(bank.high(index)<=0){bank.seed(index,0);return 0;}
        return bank.low(index)>=0?1:-1;
    }
    private int geometricZero(){poly.fill(13,4);uniform=0;return Status.GEOMETRIC_ZERO.ordinal();}
    private int unresolved(){uniform=Double.NaN;return Status.UNRESOLVED.ordinal();}
    private boolean buildTents(BPhysicalPanelCertificate.SourceBank s) {
        for(int endpoint=0;endpoint<2;endpoint++) {
            if(!hasContext()&&s.fullRoot())reference.project(endpoint==0?ax:bx,endpoint==0?ay:by);
            else {
                if(!s.prepareComponent(this,0,0,endpoint))return false;
                reference.projectCoupled(s);
            }
            reference.seed(2,bank.n[4]);reference.op(6,6,2,1);
            reference.selectSign(reference.cases[4]);reference.op(6,6,28,2);
            reference.seed(2,bank.n[7]);reference.op(6,6,2,3);reference.op(6,24,6,1);
            reference.selectSign(reference.cases[5]);reference.op(7,7,28,2);
            reference.seed(2,bank.n[8]);reference.op(7,7,2,3);reference.op(7,24,7,1);
            poly.from(4,endpoint,reference,6);poly.from(5,endpoint,reference,7);
        }
        return true;
    }
    private boolean buildTap(BPhysicalPanelCertificate.SourceBank s,int tx,int ty,int positive) {
        if(s.scratch!=1)return false;
        int x=s.cx,y=s.cy,width=s.width,height=s.height;
        //Fresh reference-corner reads;nominal source interpolation's four reads
        //are neither reused as observed reference calls nor hidden from counters.
        for(int i=0;i<4;i++)reference.seed(15+i,view.scalar(Math.min(height-1,y+i/2)*width+Math.min(width-1,x+i%2)));
        for(int endpoint=0;endpoint<2;endpoint++) {
            if(!s.prepareComponent(this,tx,ty,endpoint)||!s.inverse())return false;
            poly.put(0,endpoint,s.lo[15],s.lo[15],s.hi[15]);poly.put(1,endpoint,s.lo[16],s.lo[16],s.hi[16]);
            reference.tap(endpoint,s,44,0);reference.tap(endpoint,s,48,3);
        }
        reference.seed(19,x);reference.seed(20,y);
        for(int i=0;i<2;i++) {
            poly.with(0,i,0,i,reference,19,1);poly.with(1,i,1,i,reference,20,1);
            poly.left(2,i,reference,24,0,i,1);poly.left(3,i,reference,24,1,i,1);
        }
        poly.fill(8,2);
        for(int corner=0;corner<4;corner++) {
            poly.product(6,corner%2==0?2:0,1,corner/2==0?3:1);
            for(int k=0;k<3;k++){poly.with(7,k,6,k,reference,15+corner,2);poly.add(8,k,8,k,7,k);}
        }
        //Fixed tap own-cap and nominal/reference excess were already certified.
        //Reference coefficient hull must agree before accepting this branch.
        for(int k=0;k<3;k++) {
            reference.seed(19,bank.n[5]);poly.with(9,k,8,k,reference,19,1);
            reference.seed(19,bank.n[6]);poly.with(9,k,9,k,reference,19,1);
            if(positive==0){if(poly.hi[9][k]>0)return false;}
            else if(poly.lo[9][k]<0)return false;
        }
        if(positive==0)poly.fill(9,2);
        return true;
    }
    void requireConstruction(BPhysicalResponse response) {
        if(phase!=3||closed||owner.isClosed()||response.work!=work||response.panelSource()!=view)
            throw new IllegalStateException("Foreign component construction");
    }
    void requireFrame(BPhysicalPanelCertificate frame) {
        live();if(phase!=2||source!=frame)throw new IllegalStateException("No active component source frame");
    }
    private void live(){view.ensureSourceLive();if(closed||owner.isClosed())throw new IllegalStateException("Component panel closed");}
    private void ready(){live();if(phase!=1)throw new IllegalStateException("No published component polynomial");}
    Status status(){ready();return switch(status){case 0->Status.AVAILABLE;case 1->Status.GEOMETRIC_ZERO;case 2->Status.SOURCE_UNKNOWN;case 3->Status.UNRESOLVED;case 5->Status.KNOWN_RANGE;default->Status.INELIGIBLE;};}
    int degree(){ready();return 4;}
    double coefficient(int index){ready();if(index<0||index>4)throw new IndexOutOfBoundsException();return status<=1?poly.c[13][index]:Double.NaN;}
    double lowerCoefficient(int index){ready();if(index<0||index>4)throw new IndexOutOfBoundsException();return status<=1?poly.lo[13][index]:Double.NaN;}
    double upperCoefficient(int index){ready();if(index<0||index>4)throw new IndexOutOfBoundsException();return status<=1?poly.hi[13][index]:Double.NaN;}
    double error(){ready();return status<=1?uniform:Double.NaN;}
    @Override public void close(){closed=true;owner.close();}

    /**Eight allocations within reviewed component2048B (shell<=O104). */
    private static final class ComponentBank extends BPhysicalResponseBank {
        final byte[] flags=new byte[43],tags=new byte[43];
        ComponentBank(BPhysicalWork work){super(work,43);}
        void seed(int i,double x){n[i]=p[i]=lo[i]=hi[i]=x;error[i]=0;}
        void copyFull(int i,BPhysicalResponseBank b,int j){n[i]=b.n[j];p[i]=b.p[j];lo[i]=b.lo[j];hi[i]=b.hi[j];error[i]=b.error[j];}
        double low(int i){return Math.min(n[i],lo[i]);}double high(int i){return Math.max(p[i],hi[i]);}
        void applySign(int i,int sign){if(sign<0){double a=n[i],b=p[i],c=lo[i],d=hi[i];n[i]=-b;p[i]=-a;lo[i]=-d;hi[i]=-c;}}
    }

    /**Actual22 three-plane rows+33registers;reserved7680 includes O204 shell. */
    static final class Polynomial {
        final double[][] c=new double[22][],lo=new double[22][],hi=new double[22][];
        final double[] registers=new double[33];final BPhysicalComponentPanel parent;
        long operations;
        Polynomial(BPhysicalComponentPanel parent){this.parent=parent;for(int r=0;r<22;r++){int d=r<6?1:r<12?2:r==12?3:r<20?4:80;c[r]=new double[d+1];lo[r]=new double[d+1];hi[r]=new double[d+1];}}
        void check(){parent.work.poll();parent.requireFrame(parent.source);}
        void put(int r,int i,double value,double lower,double upper){c[r][i]=value;lo[r][i]=lower;hi[r][i]=upper;}
        void from(int r,int i,Reference s,int j){check();put(r,i,s.c[j],s.lo[j],s.hi[j]);}
        void fill(int r,int d){for(int i=0;i<=d;i++){check();put(r,i,0,0,0);}}
        void copy(int r,int i,int a,int j){check();put(r,i,c[a][j],lo[a][j],hi[a][j]);}
        void add(int r,int i,int a,int j,int b,int k){op(c[r],lo[r],hi[r],i,c[a],lo[a],hi[a],j,c[b],lo[b],hi[b],k,0);}
        void with(int r,int i,int a,int j,Reference s,int k,int kind){op(c[r],lo[r],hi[r],i,c[a],lo[a],hi[a],j,s.c,s.lo,s.hi,k,kind);}
        void left(int r,int i,Reference s,int k,int a,int j,int kind){op(c[r],lo[r],hi[r],i,s.c,s.lo,s.hi,k,c[a],lo[a],hi[a],j,kind);}
        void scale(int r,int i,double factor){parent.reference.seed(19,factor);with(r,i,r,i,parent.reference,19,2);}
        void divide(int r,int i,double divisor){parent.reference.seed(19,divisor);with(r,i,r,i,parent.reference,19,3);}
        void product(int out,int a,int da,int b) {
            fill(out,da+1);var s=parent.reference;
            for(int i=0;i<=da;i++)for(int j=0;j<=1;j++) {
                //C(da,i)*C(1,j)/C(da+1,i+j),closed da1..3 table via
                //identity: j0=(da+1-i)/(da+1),j1=(i+1)/(da+1).
                s.seed(8,j==0?da+1-i:i+1);s.seed(9,da+1);s.op(8,8,9,3);
                op(s.c,s.lo,s.hi,9,c[a],lo[a],hi[a],i,c[b],lo[b],hi[b],j,2);
                s.op(9,9,8,2);
                op(c[out],lo[out],hi[out],i+j,c[out],lo[out],hi[out],i+j,s.c,s.lo,s.hi,9,0);
            }
        }
        void op(double[] dc,double[] dl,double[] dh,int d,double[] ac,double[] al,double[] ah,int a,
                double[] bc,double[] bl,double[] bh,int b,int kind) {
            check();if(d<0||d>=dc.length||a<0||a>=ac.length||b<0||b>=bc.length)throw new IndexOutOfBoundsException();
            double av=ac[a],bv=bc[b],la=al[a],ha=ah[a],lb=bl[b],hb=bh[b],value,l,h;
            if(kind==0){value=av+bv;l=la+lb;h=ha+hb;}
            else if(kind==1){value=av-bv;l=la-hb;h=ha-lb;}
            else {
                if(kind==3&&lb<=0&&hb>=0)throw new ArithmeticException("Reference denominator unresolved");
                double aa,ab,ba,bb;
                if(kind==2){value=av*bv;aa=la*lb;ab=la*hb;ba=ha*lb;bb=ha*hb;}
                else {value=av/bv;aa=la/lb;ab=la/hb;ba=ha/lb;bb=ha/hb;}
                if(av>0&&bv>0&&value==0)throw new ArithmeticException("Positive reference underflow");
                l=Math.min(Math.min(aa,ab),Math.min(ba,bb));h=Math.max(Math.max(aa,ab),Math.max(ba,bb));
            }
            l=Math.nextDown(l);h=Math.nextUp(h);
            if(!Double.isFinite(value)||!Double.isFinite(l)||!Double.isFinite(h))throw new ArithmeticException("Reference coefficient overflow");
            dc[d]=value;dl[d]=l;dh[d]=h;operations++;
        }
    }

    /**Reviewed1264B:6tap arrays/refarray,3*32scratch,int16,O96shell. */
    static final class Reference {
        final double[] c=new double[32],lo=new double[32],hi=new double[32];
        final double[][] tapRows=new double[6][2];final int[] cases=new int[16];
        final Polynomial poly;final ComponentBank component;final BPhysicalPanelCertificate source;
        final BPhysicalWork work;final AttemptMemoryLedger.Owner owner;
        int phase,cursor;long calls;
        Reference(BPhysicalComponentPanel parent) {
            poly=parent.poly;component=parent.bank;source=parent.source;work=parent.work;owner=parent.owner;
            seed(23,0);seed(24,1);seed(25,2);seed(26,4);
            c[27]=-c[24];lo[27]=-lo[24];hi[27]=-hi[24];
        }
        void seed(int i,double x){c[i]=lo[i]=hi[i]=x;}
        void op(int out,int a,int b,int kind){poly.op(c,lo,hi,out,c,lo,hi,a,c,lo,hi,b,kind);calls++;}
        void selectSign(int sign){int i=sign<0?27:24;c[28]=c[i];lo[28]=lo[i];hi[28]=hi[i];}
        void project(double x,double y) {
            seed(0,x);seed(1,y);projectPrepared();
        }
        void projectCoupled(BPhysicalPanelCertificate.SourceBank s) {
            c[0]=lo[0]=s.lo[36];hi[0]=s.hi[36];c[1]=lo[1]=s.lo[40];hi[1]=s.hi[40];projectPrepared();
        }
        private void projectPrepared() {
            seed(2,component.n[0]);seed(3,component.n[1]);op(0,0,2,1);op(1,1,3,1);
            seed(2,component.n[2]);seed(3,component.n[3]);op(4,0,2,2);op(5,1,3,2);op(6,4,5,0);
            seed(2,-component.n[3]);seed(3,component.n[2]);op(4,0,2,2);op(5,1,3,2);op(7,4,5,0);
        }
        void tap(int endpoint,BPhysicalPanelCertificate.SourceBank bank,int index,int base) {
            tapRows[base][endpoint]=tapRows[base+1][endpoint]=bank.lo[index];tapRows[base+2][endpoint]=bank.hi[index];
        }
    }
}
