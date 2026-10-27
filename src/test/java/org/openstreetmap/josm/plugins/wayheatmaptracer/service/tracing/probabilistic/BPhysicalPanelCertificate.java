package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Root-only source certificate;never R/D/J,polynomial or integration authority. */
final class BPhysicalPanelCertificate implements AutoCloseable {
    enum Status { TRUE,FALSE,UNRESOLVED }
    /** One bounded fatal object;the private single-use stage cannot retry it.
     * Throwable's constructor dispatches this override before recording its
     * fixed message. No native backtrace or case-derived message is created.
     */
    static final class PositiveUnderflow extends ArithmeticException {
        PositiveUnderflow(){super("Positive source transfer underflow");}
        @Override public synchronized Throwable fillInStackTrace(){return this;}
    }
    //5refs+2ints+bool+3doubles=73fieldbytes:O73=96.
    private final BPhysicalResponse.SourceView view;
    private final BPhysicalWork work;
    private final AttemptMemoryLedger.Owner allocation;
    private final SourceBank bank;
    private final Nodes nodes;
    private int phase,status;
    private boolean closed;
    private double lower,upper,error;
    private BPhysicalPanelCertificate(BPhysicalResponse response,AttemptMemoryLedger.Owner owner) {
        view=response.panelSource();work=response.work;allocation=owner;
        bank=new SourceBank(work,this,response.sourceWidth(),response.sourceHeight());
        nodes=new Nodes(work,owner);
    }
    static BPhysicalPanelCertificate open(BPhysicalResponse response) {
        response.bound();var work=response.work;work.claimSourcePanel();
        var child=work.owner().child("physical-source-panel-root");
        try {child.reserve(4208+96+352);var result=new BPhysicalPanelCertificate(response,child);work.poll();result.live();return result;}
        catch(RuntimeException|Error failure){child.close();throw failure;}
    }
    //Only a privately constructed component owner can create/use this frame.
    //It owns ONE root and ONE bank;tap classifications do not claim more roots.
    static BPhysicalPanelCertificate componentFrame(BPhysicalResponse response,BPhysicalComponentPanel component) {
        component.requireConstruction(response);
        return new BPhysicalPanelCertificate(response,component.owner);
    }
    SourceBank beginComponent(BPhysicalComponentPanel component) {
        component.requireFrame(this);live();nodes.claim();return bank;
    }
    SourceBank activeComponent(BPhysicalComponentPanel component) {
        component.requireFrame(this);live();if(nodes.cursor!=1)throw new IllegalStateException("No claimed component root");return bank;
    }
    void adaptiveNode(BPhysicalComponentPanel component,BPhysicalAdaptiveIntegral adaptive) {
        component.requireFrame(this);live();adaptive.requireComponent(component);
        if(nodes.cursor!=1)throw new IllegalStateException("No original source root permit");
        nodes.ordinal=adaptive.activeOrdinal();nodes.depth[0]=adaptive.activeDepth();
    }
    int componentTap(BPhysicalComponentPanel component,int x,int y) {
        component.requireFrame(this);live();work.admit(BPhysicalWork.SOURCE_PANEL);boolean returned=false;
        try {
            int result=bank.prepareComponent(component,x,y,-1)?bank.classifyPrepared():2;
            returned=true;return result; //A typed refusal is still a normal return.
        } finally {work.complete(BPhysicalWork.SOURCE_PANEL,returned);}
    }
    /** TABLE-only extension;the actual active Max owns the sole source frame. */
    int componentTableTap(BPhysicalComponentPanel component,BPhysicalMaxPanel currentMax,int x,int y) {
        component.requireFrame(this);live();
        work.admit(BPhysicalWork.SOURCE_PANEL);boolean returned=false;
        try {
            currentMax.requireFor(component);
            if(!currentMax.tableOnly())throw new IllegalStateException("No table source-range permit");
            int result=bank.prepareComponent(component,x,y,-1)?bank.classifyPrepared():2;
            if(result==0&&bank.scratch==0&&!bank.adjacentRange(component,currentMax))result=2;
            returned=true;return result;
        } finally {work.complete(BPhysicalWork.SOURCE_PANEL,returned);}
    }
    void certify(double ax,double ay,double bx,double by) {
        phase=0;live();nodes.claim();work.admit(BPhysicalWork.SOURCE_PANEL);boolean returned=false;
        try {
            if(!Double.isFinite(ax)||!Double.isFinite(ay)||!Double.isFinite(bx)||!Double.isFinite(by))
                throw new IllegalArgumentException("Finite original endpoint bits required");
            int order=Double.compare(ax,bx);
            if(order>0||order==0&&Double.compare(ay,by)>0) {
                double t=ax;ax=bx;bx=t;t=ay;ay=by;by=t;
            }
            int result=bank.certify(ax,ay,bx,by);
            work.poll();live();status=result;
            lower=result==0?bank.lo[29]:Double.NaN;upper=result==0?bank.hi[29]:Double.NaN;
            error=result==0?bank.error[29]:Double.NaN;
            nodes.value[0]=lower;nodes.low[0]=lower;nodes.high[0]=upper;nodes.error[0]=error;
            nodes.reason[0]=result;returned=true;
        } finally {work.complete(BPhysicalWork.SOURCE_PANEL,returned);}
        phase=1; //Completion bookkeeping can fail;publish only after it returns.
    }
    Status status(){ready();return status==0?Status.TRUE:status==1?Status.FALSE:Status.UNRESOLVED;}
    double lower(){ready();return lower;}
    double upper(){ready();return upper;}
    double error(){ready();return error;}
    void refuseIntegration(){throw new UnsupportedOperationException("SOURCE_PANEL_ONLY:no R/D/J,children or integral");}
    private void live(){view.ensureSourceLive();if(closed||allocation.isClosed())throw new IllegalStateException("Source panel closed");}
    void requirePrefixConstruction(BPhysicalWork expected,BPhysicalResponse.SourceView source,AttemptMemoryLedger.Owner owner) {
        live();if(bank!=null||work!=expected||view!=source||allocation!=owner)
            throw new IllegalArgumentException("No private source-prefix construction");
    }
    private void ready(){live();if(phase!=1)throw new IllegalStateException("No published source certificate");}
    @Override public void close(){closed=true;allocation.close();}

    /** Actual K1 root pool:9arrays+Work/owner,4ints,2longs=O120;352B. */
    private static final class Nodes {
        final double[] value=new double[1],low=new double[1],high=new double[1],error=new double[1],unknown=new double[1];
        final int[] parent=new int[1],depth=new int[1],reason=new int[1],links=new int[1];
        final BPhysicalWork work;final AttemptMemoryLedger.Owner owner;
        int capacity=1,cursor,phase,disposition;long ordinal,total;
        Nodes(BPhysicalWork work,AttemptMemoryLedger.Owner owner){this.work=work;this.owner=owner;}
        void claim(){work.poll();if(owner.isClosed()||cursor==capacity)throw new IllegalStateException("K1 source root exhausted");
            cursor++;total++;parent[0]=-1;depth[0]=0;ordinal=0;phase=1;}
    }

    /**5*77double,2*77byte,2*35double,2*35byte;14refs+6ints=4208B.
     * n/p are nominal-program interval endpoints,lo/hi real endpoints,error is
     * SAME-parameter coupling. No point-bank constant/operation methods are used.
     */
    static final class SourceBank extends BPhysicalResponseBank {
        final byte[] flags=new byte[77],flags2=new byte[77],truth=new byte[35],truth2=new byte[35];
        final double[] rl=new double[35],rh=new double[35];
        final BPhysicalPanelCertificate parent;
        final BPhysicalSignaturePrefix prefix;
        int width,height,result,cx,cy,scratch;
        private SourceBank(BPhysicalWork work,BPhysicalPanelCertificate parent,int width,int height) {
            super(work,77);this.parent=parent;this.width=width;this.height=height;
            prefix=BPhysicalSignaturePrefix.issue(parent,work,parent.view,parent.allocation,width,height);
        }
        private void checkpoint(){work.poll();parent.live();}
        void seed(int i,double x){n[i]=p[i]=lo[i]=hi[i]=x;error[i]=0;}
        int certify(double ax,double ay,double bx,double by) {
            result=2;seed(2,ax);seed(3,ay);seed(4,bx);seed(5,by);
            n[6]=lo[6]=0;p[6]=hi[6]=1;error[6]=Math.ulp(1.0); //RN(t),t in[0,1].
            if(!op(7,4,2,1)||!op(8,5,3,1)||!op(9,6,7,2)||!op(10,6,8,2)
                    ||!op(0,2,9,0)||!op(1,3,10,0))return 2;
            return classifyPrepared();
        }
        boolean prepare(double ax,double ay,double bx,double by,int tx,int ty,int endpoint) {
            return prepare(ax,ay,bx,by,tx,ty,endpoint,null);
        }
        boolean prepareComponent(BPhysicalComponentPanel component,int tx,int ty,int endpoint) {
            component.requireFrame(parent);
            return prepare(component.ax,component.ay,component.bx,component.by,tx,ty,endpoint,component);
        }
        private boolean prepare(double ax,double ay,double bx,double by,int tx,int ty,int endpoint,BPhysicalComponentPanel component) {
            result=2;seed(2,ax);seed(3,ay);seed(4,bx);seed(5,by);
            if(parent.nodes.depth[0]>0)parameter(endpoint);
            else if(endpoint<0){n[6]=lo[6]=0;p[6]=hi[6]=1;error[6]=Math.ulp(1.0);}
            else seed(6,endpoint);
            if(!op(7,4,2,1)||!op(8,5,3,1)||!op(9,6,7,2)||!op(10,6,8,2)
                    ||!op(0,2,9,0)||!op(1,3,10,0))return false;
            var g=parent.view.bound.grid;
            if(component!=null&&component.hasContext()) {
                seed(2,component.contextX());seed(3,component.contextY());seed(4,g.ux);seed(5,g.uy);seed(6,g.vx);seed(7,g.vy);
                if(!op(8,2,4,2)||!op(0,0,8,0)||!op(8,3,6,2)||!op(0,0,8,0)
                        ||!op(8,2,5,2)||!op(1,1,8,0)||!op(8,3,7,2)||!op(1,1,8,0))return false;
            }
            copyRecord(36,0);copyRecord(40,1);
            seed(2,tx);seed(3,ty);seed(4,g.ux);seed(5,g.uy);seed(6,g.vx);seed(7,g.vy);
            if(!op(8,2,4,2)||!op(0,0,8,0)||!op(8,3,6,2)||!op(0,0,8,0)
                    ||!op(8,2,5,2)||!op(1,1,8,0)||!op(8,3,7,2)||!op(1,1,8,0))return false;
            copyRecord(44,0);copyRecord(48,1);return true;
        }
        boolean fullRoot(){return parent.nodes.depth[0]==0;}
        private void parameter(int endpoint) {
            int depth=parent.nodes.depth[0];long ordinal=parent.nodes.ordinal;
            if(depth<1||depth>62||ordinal<0||ordinal>=(1L<<depth)||endpoint< -1||endpoint>1)
                throw new IllegalStateException("Original dyadic node identity");
            long a=ordinal+(endpoint==1?1:0),b=ordinal+(endpoint==0?0:1);
            double aa=(double)a,bb=(double)b;
            n[6]=Math.scalb(aa,-depth);p[6]=Math.scalb(bb,-depth);
            //Correct long-to-double RN;scale is exact and normal. A positive
            //integer is exactly representable iff discarded low bits are0.
            lo[6]=dyadicBound(a,depth,false);
            hi[6]=dyadicBound(b,depth,true);
            error[6]=Math.ulp(p[6]); //Uniform RN(t)-exact t bound,SAME parameter.
        }
        static double dyadicBound(long ordinal,int depth,boolean upper) {
            if(depth<0||depth>62||ordinal<0||ordinal>(1L<<depth))throw new IllegalArgumentException("Dyadic endpoint guard");
            double v=(double)ordinal;boolean exact=ordinal==0||64-Long.numberOfLeadingZeros(ordinal)-Long.numberOfTrailingZeros(ordinal)<=53;
            return Math.scalb(exact?v:upper?Math.nextUp(v):Math.nextDown(v),-depth);
        }
        private void copyRecord(int out,int in) {
            n[out]=n[in];p[out]=p[in];lo[out]=lo[in];hi[out]=hi[in];error[out]=error[in];
        }
        private int classifyPrepared() {
            scratch=0;
            int region=region();if(region==1)return 1;if(region!=2)return 2;
            if(!inverse())return 2;
            return signatures();
        }
        boolean inverse() {
            var t=parent.view.bound.input.evidence.transform();
            seed(2,t.origin().xMeters());seed(3,t.origin().yMeters());
            seed(4,t.xAxisEastMetersPerSourcePixel());seed(5,t.xAxisNorthMetersPerSourcePixel());
            seed(6,t.yAxisEastMetersPerSourcePixel());seed(7,t.yAxisNorthMetersPerSourcePixel());
            seed(8,t.originKind()==RasterMetricTransform.OriginKind.MANAGED_SOURCE_PIXEL_BOUNDARY?.5:0);
            seed(9,t.axisUnit()==RasterMetricTransform.AxisUnit.SOURCE_PIXEL?t.rasterPixelsPerSourcePixel():1);
            if(!op(10,0,2,1)||!op(11,1,3,1)||!op(12,4,7,2)||!op(13,5,6,2)||!op(12,12,13,1)
                    ||!op(13,10,7,2)||!op(14,11,6,2)||!op(13,13,14,1)||!op(15,13,12,3)
                    ||!op(15,15,8,1)||!op(15,15,9,2)||!op(13,11,4,2)||!op(14,10,5,2)
                    ||!op(13,13,14,1)||!op(16,13,12,3)||!op(16,16,8,1)||!op(16,16,9,2))return false;
            return true;
        }
        private int signatures() {
            if(p[15]<0||n[15]>width-1||p[16]<0||n[16]>height-1)return 1;
            if(n[15]<0||p[15]>width-1||n[16]<0||p[16]>height-1)return 2;
            int nominal=maskBox(n[15],p[15],n[16],p[16]);
            if(nominal==1)return 1;if(nominal!=2)return 2;
            double lx=Math.min(n[15],lo[15]),hx=Math.max(p[15],hi[15]);
            double ly=Math.min(n[16],lo[16]),hy=Math.max(p[16],hi[16]);
            if(lx<0||ly<0||hx>width-1||hy>height-1)return 2;
            if(maskBox(lx,hx,ly,hy)!=2)return 2;
            //Only a fixed actual value-index trace reads four fresh corners.
            //For multi-cell covers the normalized domain supplies[0,1],not an
            //invented interpolant/corner average. Later polynomial entry refuses.
            lo[29]=0;hi[29]=1;
            double err=up(up(error[15]+error[16])+6*0x1p-52);
            if(!Double.isFinite(err))return 2;
            if((int)StrictMath.floor(lx)==(int)StrictMath.floor(hx)
                    &&(int)StrictMath.floor(ly)==(int)StrictMath.floor(hy)) {
                int x0=(int)StrictMath.floor(lx),y0=(int)StrictMath.floor(ly);
                cx=x0;cy=y0;scratch=1;
                int x1=Math.min(width-1,x0+1),y1=Math.min(height-1,y0+1);
                double a=parent.view.scalar(y0*width+x0),b=parent.view.scalar(y0*width+x1);
                double c=parent.view.scalar(y1*width+x0),d=parent.view.scalar(y1*width+x1);
                seed(20,a);seed(21,b);seed(22,c);seed(23,d);
                lo[29]=Math.min(Math.min(a,b),Math.min(c,d));hi[29]=Math.max(Math.max(a,b),Math.max(c,d));
                //Identical retained values make both ORIGINAL lerps constant;
                //this proven identity is not an error clamp.
                if(a==b&&a==c&&a==d)err=0;
            }
            error[29]=err;return 0;
        }
        boolean interpolateFixed() {
            if(scratch!=1)return false;
            seed(24,cx);seed(25,cy);
            return op(24,15,24,1)&&op(25,16,25,1)
                    &&op(26,21,20,1)&&op(26,24,26,2)&&op(26,20,26,0)
                    &&op(27,23,22,1)&&op(27,24,27,2)&&op(27,22,27,0)
                    &&op(29,27,26,1)&&op(29,25,29,2)&&op(29,26,29,0);
        }
        private int maskBox(double lx,double hx,double ly,double hy) {
            return prefix.rectangle(lx,hx,ly,hy);
        }
        /** Both masks/region have already passed in signatures(). Records15/16
         * remain the original inverse P/E. Records52..61 are private scratch;
         * no endpoint is relabelled exact and no unique interpolant is claimed.
         */
        private boolean adjacentRange(BPhysicalComponentPanel component,BPhysicalMaxPanel currentMax) {
            n[29]=p[29]=lo[29]=hi[29]=error[29]=Double.NaN;
            n[52]=p[52]=lo[52]=hi[52]=error[52]=Double.NaN;scratch=0;
            checkpoint();
            double pxl=n[15],pxh=p[15],pyl=n[16],pyh=p[16];
            double exl=lo[15],exh=hi[15],eyl=lo[16],eyh=hi[16];
            //The sufficient normal-product lattice applies BEFORE fraction
            //subtraction,including near-face fractions smaller than2^-32.
            if(!((pxl==0&&pxh==0)||pxl>=0x1p-32)
                    ||!((pyl==0&&pyh==0)||pyl>=0x1p-32))return false;
            int xa=(int)StrictMath.floor(Math.min(pxl,exl)),xb=(int)StrictMath.floor(Math.max(pxh,exh));
            int ya=(int)StrictMath.floor(Math.min(pyl,eyl)),yb=(int)StrictMath.floor(Math.max(pyh,eyh));
            if(xb-xa>1||yb-ya>1)return false;
            int seen=0;double pl=Double.POSITIVE_INFINITY,ph=Double.NEGATIVE_INFINITY;
            double el=Double.POSITIVE_INFINITY,eh=Double.NEGATIVE_INFINITY;
            for(int y=ya;y<=yb;y++)for(int x=xa;x<=xb;x++) {
                checkpoint();
                int x1=Math.min(width-1,x+1),y1=Math.min(height-1,y+1);
                int row=y*width,row1=y1*width;
                double a=parent.view.scalar(row+x),b=parent.view.scalar(row+x1);
                double c=parent.view.scalar(row1+x),d=parent.view.scalar(row1+x1);
                if(!(a>=0&&a<=1&&(a==0||a>=0x1p-256))
                        ||!(b>=0&&b<=1&&(b==0||b>=0x1p-256))
                        ||!(c>=0&&c<=1&&(c==0||c>=0x1p-256))
                        ||!(d>=0&&d<=1&&(d==0||d>=0x1p-256)))return false;
                seed(20,a);seed(21,b);seed(22,c);seed(23,d);
                for(int domain=0;domain<2;domain++) {
                    checkpoint();
                    double xl=Math.max(domain==0?pxl:exl,x),xh=Math.min(domain==0?pxh:exh,x1);
                    double yl=Math.max(domain==0?pyl:eyl,y),yh=Math.min(domain==0?pyh:eyh,y1);
                    if(xl>xh||yl>yh)continue;
                    n[53]=xl;p[53]=xh;n[54]=yl;p[54]=yh;
                    seed(59,x);seed(60,y);
                    //Two fractions followed by ORIGINAL horizontal/vertical
                    //lerp grouping (nine ops). P uses RN endpoint arithmetic;
                    //E alone uses outward bounds of each exact operation.
                    if(!rangeOp(55,53,59,1,domain)||!rangeOp(56,54,60,1,domain)
                            ||!rangeOp(59,21,20,1,domain)||!rangeOp(59,55,59,2,domain)||!rangeOp(57,20,59,0,domain)
                            ||!rangeOp(59,23,22,1,domain)||!rangeOp(59,55,59,2,domain)||!rangeOp(58,22,59,0,domain)
                            ||!rangeOp(59,58,57,1,domain)||!rangeOp(59,56,59,2,domain)||!rangeOp(61,57,59,0,domain))return false;
                    if(domain==0){pl=Math.min(pl,n[61]);ph=Math.max(ph,p[61]);seen|=1;}
                    else {el=Math.min(el,n[61]);eh=Math.max(eh,p[61]);seen|=2;}
                    n[52]=pl;p[52]=ph;lo[52]=el;hi[52]=eh;
                }
            }
            checkpoint();currentMax.requireFor(component);component.requireFrame(parent);
            if(seen!=3||!(pl>=-Double.MAX_VALUE&&ph<=Double.MAX_VALUE&&pl<=ph
                    &&el>=-Double.MAX_VALUE&&eh<=Double.MAX_VALUE&&el<=eh))return false;
            double coupling=Math.max(0,Math.max(Math.nextUp(ph-el),Math.nextUp(eh-pl)));
            if(!(coupling<=Double.MAX_VALUE))return false;
            n[29]=pl;p[29]=ph;lo[29]=el;hi[29]=eh;error[29]=coupling;
            scratch=2;return true; //Publish only the complete nonpolynomial ABI.
        }
        private boolean rangeOp(int out,int a,int b,int kind,int reference) {
            checkpoint();
            //Copy before writing:out may alias either original operand.
            double al=n[a],ah=p[a],bl=n[b],bh=p[b],l,h;
            if(kind==0){l=al+bl;h=ah+bh;}
            else if(kind==1){l=al-bh;h=ah-bl;}
            else {
                double aa=al*bl,ab=al*bh,ba=ah*bl,bb=ah*bh;
                l=Math.min(Math.min(aa,ab),Math.min(ba,bb));h=Math.max(Math.max(aa,ab),Math.max(ba,bb));
            }
            if(reference!=0){l=Math.nextDown(l);h=Math.nextUp(h);}
            if(!(l>=-Double.MAX_VALUE&&h<=Double.MAX_VALUE&&l<=h))return false;
            n[out]=l;p[out]=h;return true;
        }
        private static double up(double x){return Math.nextUp(x);}
        private static double down(double x){return Math.nextDown(x);}
        private static double min4(double a,double b,double c,double d){return Math.min(Math.min(a,b),Math.min(c,d));}
        private static double max4(double a,double b,double c,double d){return Math.max(Math.max(a,b),Math.max(c,d));}
        /**Reviewed RN-P/exact-E/coupled-error transfer;out may alias input. */
        /**Sufficient finite dyadic producer proof;false retains outward fallback. */
        private static boolean exactEndpoint(int kind,double a,double b,double rounded) {
            if(kind<0||kind>3)return false;
            long bitsA=Double.doubleToRawLongBits(a),mA=bitsA&0x000fffffffffffffL;
            int rawA=(int)((bitsA>>>52)&2047);
            if(rawA==2047)return false;
            int eA=rawA==0?-1074:rawA-1075;
            if(rawA!=0)mA|=0x0010000000000000L;
            if(mA==0)eA=0;
            else {
                if((mA&4294967295L)==0){mA>>>=32;eA+=32;}
                if((mA&65535L)==0){mA>>>=16;eA+=16;}
                if((mA&255L)==0){mA>>>=8;eA+=8;}
                if((mA&15L)==0){mA>>>=4;eA+=4;}
                if((mA&3L)==0){mA>>>=2;eA+=2;}
                if((mA&1L)==0){mA>>>=1;eA+=1;}
                if(bitsA<0)mA=-mA;
            }
            long bitsB=Double.doubleToRawLongBits(b),mB=bitsB&0x000fffffffffffffL;
            int rawB=(int)((bitsB>>>52)&2047);
            if(rawB==2047)return false;
            int eB=rawB==0?-1074:rawB-1075;
            if(rawB!=0)mB|=0x0010000000000000L;
            if(mB==0)eB=0;
            else {
                if((mB&4294967295L)==0){mB>>>=32;eB+=32;}
                if((mB&65535L)==0){mB>>>=16;eB+=16;}
                if((mB&255L)==0){mB>>>=8;eB+=8;}
                if((mB&15L)==0){mB>>>=4;eB+=4;}
                if((mB&3L)==0){mB>>>=2;eB+=2;}
                if((mB&1L)==0){mB>>>=1;eB+=1;}
                if(bitsB<0)mB=-mB;
            }
            long bitsR=Double.doubleToRawLongBits(rounded),mR=bitsR&0x000fffffffffffffL;
            int rawR=(int)((bitsR>>>52)&2047);
            if(rawR==2047)return false;
            int eR=rawR==0?-1074:rawR-1075;
            if(rawR!=0)mR|=0x0010000000000000L;
            if(mR==0)eR=0;
            else {
                if((mR&4294967295L)==0){mR>>>=32;eR+=32;}
                if((mR&65535L)==0){mR>>>=16;eR+=16;}
                if((mR&255L)==0){mR>>>=8;eR+=8;}
                if((mR&15L)==0){mR>>>=4;eR+=4;}
                if((mR&3L)==0){mR>>>=2;eR+=2;}
                if((mR&1L)==0){mR>>>=1;eR+=1;}
                if(bitsR<0)mR=-mR;
            }
            long v;int e;
            if(kind<=1) {
                if(kind==1)mB=-mB;
                if(mA==0){v=mB;e=eB;}
                else if(mB==0){v=mA;e=eA;}
                else {
                    e=eA<eB?eA:eB;int sa=eA-e,sb=eB-e;
                    if(sa>62||sb>62)return false;
                    long aa=mA<0?-mA:mA,bb=mB<0?-mB:mB;
                    if(aa>(Long.MAX_VALUE/2>>sa)||bb>(Long.MAX_VALUE/2>>sb))return false;
                    v=(mA<<sa)+(mB<<sb);
                }
            } else if(kind==2) {
                if(mA==0||mB==0){v=0;e=0;}
                else {
                    long aa=mA<0?-mA:mA,bb=mB<0?-mB:mB;
                    if(aa>(Long.MAX_VALUE/2)/bb)return false;
                    v=mA*mB;e=eA+eB;
                }
            } else {
                if(mB==0)return false;
                if(mA==0){v=0;e=0;}
                else {if(mA%mB!=0)return false;v=mA/mB;e=eA-eB;}
            }
            if(v==0)return mR==0;
            if((v&4294967295L)==0){v>>=32;e+=32;}
            if((v&65535L)==0){v>>=16;e+=16;}
            if((v&255L)==0){v>>=8;e+=8;}
            if((v&15L)==0){v>>=4;e+=4;}
            if((v&3L)==0){v>>=2;e+=2;}
            if((v&1L)==0){v>>=1;e+=1;}
            long magnitude=v<0?-v:v;
            return magnitude<=0x001fffffffffffffL&&e>=-1074&&e<=1023&&v==mR&&e==eR;
        }
        private boolean op(int out,int a,int b,int kind) {
            return transferTo(this,out,a,b,kind);
        }
        boolean transferTo(BPhysicalResponseBank target,int out,int a,int b,int kind) {
            checkpoint();double al=target.n[a],ah=target.p[a],bl=target.n[b],bh=target.p[b];
            double el=target.lo[a],eh=target.hi[a],fl=target.lo[b],fh=target.hi[b];
            double da=target.error[a],db=target.error[b],pl,ph,ll,hh;boolean exact;
            if(kind==0){pl=al+bl;ph=ah+bh;ll=el+fl;hh=eh+fh;
                exact=exactEndpoint(kind,el,fl,ll)&exactEndpoint(kind,eh,fh,hh);}
            else if(kind==1){pl=al-bh;ph=ah-bl;ll=el-fh;hh=eh-fl;
                exact=exactEndpoint(kind,el,fh,ll)&exactEndpoint(kind,eh,fl,hh);}
            else {
                if(kind==3&&((bl<=0&&bh>=0)||(fl<=0&&fh>=0)))return false;
                double a0,a1,a2,a3,b0,b1,b2,b3;
                if(kind==2){a0=al*bl;a1=al*bh;a2=ah*bl;a3=ah*bh;
                    b0=el*fl;b1=el*fh;b2=eh*fl;b3=eh*fh;}
                else {a0=al/bl;a1=al/bh;a2=ah/bl;a3=ah/bh;
                    b0=el/fl;b1=el/fh;b2=eh/fl;b3=eh/fh;}
                pl=min4(a0,a1,a2,a3);ph=max4(a0,a1,a2,a3);
                ll=min4(b0,b1,b2,b3);hh=max4(b0,b1,b2,b3);
                if(al>0&&bl>0&&pl==0)throw new PositiveUnderflow(); //Fatal,never a refinable branch.
                exact=exactEndpoint(kind,el,fl,b0)&exactEndpoint(kind,el,fh,b1)
                        &exactEndpoint(kind,eh,fl,b2)&exactEndpoint(kind,eh,fh,b3);
            }
            double u=Math.max(Math.abs(down(pl)),Math.abs(up(ph))),r=Math.ulp(u),delta;
            double aa=Math.max(Math.abs(el),Math.abs(eh)),ab=Math.max(Math.abs(fl),Math.abs(fh));
            if(kind<=1)delta=up(up(da+db)+r);
            else if(kind==2)delta=up(up(up(up(aa*db)+up(ab*da))+up(da*db))+r);
            else {
                double betaP=Math.min(Math.abs(bl),Math.abs(bh)),betaE=Math.min(Math.abs(fl),Math.abs(fh));
                double denom=down(betaP*betaE);
                if(!(betaP>0)||!(betaE>0)||!(denom>0)||!Double.isFinite(denom))return false;
                double numerator=up(aa*db),t1=up(da/betaP),t2=up(numerator/denom);
                delta=up(up(t1+t2)+r);
            }
            if(!exact){ll=down(ll);hh=up(hh);}
            if(exact&&pl==ph&&ll==hh&&pl==ll)delta=0;
            if(!Double.isFinite(pl)||!Double.isFinite(ph)||!Double.isFinite(ll)||!Double.isFinite(hh)
                    ||!Double.isFinite(delta)||delta<0)return false;
            target.n[out]=pl;target.p[out]=ph;target.lo[out]=ll;target.hi[out]=hh;target.error[out]=delta;return true;
        }
        //Boolean bitsets:1=onlyFALSE,2=onlyTRUE,3=both. ORIGINAL RN predicates.
        private static int and(int a,int b){return ((a&1)!=0||(b&1)!=0?1:0)|((a&2)!=0&&(b&2)!=0?2:0);}
        private static int xor(int a,int b){return ((a&1)!=0&&(b&1)!=0||(a&2)!=0&&(b&2)!=0?1:0)
                |((a&1)!=0&&(b&2)!=0||(a&2)!=0&&(b&1)!=0?2:0);}
        private int lt(int a,int b){return rh[a]<rl[b]?2:rl[a]>=rh[b]?1:3;}
        private int le(int a,int b){return rh[a]<=rl[b]?2:rl[a]>rh[b]?1:3;}
        private void rs(int i,double x){rl[i]=rh[i]=x;}
        private boolean ro(int o,int a,int b,int kind) {
            double al=rl[a],ah=rh[a],bl=rl[b],bh=rh[b],l,h;
            if(kind==0){l=al+bl;h=ah+bh;}else if(kind==1){l=al-bh;h=ah-bl;}
            else {
                if(kind==3&&bl<=0&&bh>=0)return false;
                double a0,a1,a2,a3;
                if(kind==2){a0=al*bl;a1=al*bh;a2=ah*bl;a3=ah*bh;}
                else {a0=al/bl;a1=al/bh;a2=ah/bl;a3=ah/bh;}
                l=min4(a0,a1,a2,a3);h=max4(a0,a1,a2,a3);
            }
            if(!Double.isFinite(l)||!Double.isFinite(h))return false;rl[o]=l;rh[o]=h;return true;
        }
        private int region() {
            var rings=parent.view.bound.input.evidence.evidenceRegion().polygons();boolean uncertain=false;
            rl[0]=n[0];rh[0]=p[0];rl[1]=n[1];rh[1]=p[1];
            for(int ring=0;ring<rings.size();ring++) {
                int parity=1;boolean hitPossible=false;int count=rings.get(ring).size();
                for(int cur=0,prev=count-1;cur<count;prev=cur++) {
                    checkpoint();rs(2,parent.view.coordinate(ring,prev,true));rs(3,parent.view.coordinate(ring,prev,false));
                    rs(4,parent.view.coordinate(ring,cur,true));rs(5,parent.view.coordinate(ring,cur,false));
                    rs(6,Math.min(rl[2],rl[4])-1e-9);rs(7,Math.max(rl[2],rl[4])+1e-9);
                    rs(8,Math.min(rl[3],rl[5])-1e-9);rs(9,Math.max(rl[3],rl[5])+1e-9);
                    int box=and(and(le(6,0),le(0,7)),and(le(8,1),le(1,9))),on=1;
                    if(box!=1) {
                        if(!ro(10,4,2,1)||!ro(11,5,3,1)||!ro(12,1,3,1)||!ro(13,0,2,1)
                                ||!ro(14,10,12,2)||!ro(15,11,13,2)||!ro(14,14,15,1))return 3;
                        double l=rl[14]<=0&&rh[14]>=0?0:Math.min(Math.abs(rl[14]),Math.abs(rh[14]));
                        double h=Math.max(Math.abs(rl[14]),Math.abs(rh[14]));rl[14]=l;rh[14]=h;
                        double tolerance=1e-9*Math.max(1,StrictMath.hypot(rl[4]-rl[2],rl[5]-rl[3]));
                        if(!Double.isFinite(tolerance))return 3;rs(15,tolerance);on=and(box,le(14,15));
                    }
                    if(on==2)return 2;if(on==3)hitPossible=true;
                    int vertical=xor(lt(1,3),lt(1,5)),crossing=1;
                    if(vertical!=1) {
                        if(!ro(10,4,2,1)||!ro(11,1,3,1)||!ro(12,10,11,2)||!ro(13,5,3,1)
                                ||!ro(12,12,13,3)||!ro(12,12,2,0))return 3;
                        crossing=and(vertical,lt(0,12));
                    }
                    int toggled=((parity&1)!=0?2:0)|((parity&2)!=0?1:0);
                    if(crossing==2)parity=toggled;else if(crossing==3)parity|=toggled;
                }
                int ringResult=parity|(hitPossible?2:0);
                if(ringResult==2)return 2;if(ringResult==3)uncertain=true;
            }
            return uncertain?3:1;
        }
    }
}
