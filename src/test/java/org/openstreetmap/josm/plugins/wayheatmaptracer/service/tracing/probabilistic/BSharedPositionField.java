package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.math.BigInteger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BSharedPositionEvidence.*;

/** Source-bound shared response; never a completed-route aggregation. */
final class BSharedPositionField implements AutoCloseable {
    static final class UnavailableException extends IllegalArgumentException {
        final Status reason;
        UnavailableException(Status reason){super(reason.name());this.reason=reason;}
    }
    private final Bound bound;
    private final AttemptMemoryLedger.Owner owner;
    private final BSharedWork work;
    private final double[][] planes;
    private final byte[][] reasons;
    private final int[] cellSupport;
    private final Interval scratch;
    private final ExactProjection projection;
    private final GridCoordinates coordinates;
    private final MutableResponse response;
    private final double[] excess=new double[9];
    private final boolean[] stencilValid=new boolean[9];

    private BSharedPositionField(Bound bound,AttemptMemoryLedger.Owner owner,BSharedWork work) {
        this.bound=bound;this.owner=owner;this.work=work;
        int n=Math.multiplyExact(bound.grid.width,bound.grid.height);
        planes=new double[8][n];reasons=new byte[4][n];
        cellSupport=new int[(bound.grid.width-1)*(bound.grid.height-1)];
        // One reusable1536-byte interval bank fits the already reserved4096-byte exact/enclosure scratch.
        scratch=new Interval();projection=new ExactProjection(work);response=new MutableResponse();
        coordinates=new GridCoordinates(bound.grid,work);
    }
    static BSharedPositionField build(BSharedPositionEvidence.Bound bound,
            AttemptMemoryLedger.Owner owner, CancellationProbe probe, BSharedWork work) {
        work.requireOwner(owner);work.requireProbe(probe);bound.ensureOpen();work.poll();
        if(bound.work!=work||bound.fieldClaimed)throw new IllegalArgumentException("Foreign or reused shared field");
        if(bound.status()!=Status.AVAILABLE)throw new IllegalArgumentException("Unavailable shared source capability");
        bound.fieldClaimed=true;
        long n=Math.multiplyExact((long)bound.grid.width,bound.grid.height);
        long cells=Math.multiplyExact((long)bound.grid.width-1,bound.grid.height-1);
        long bytes=1024+8*array(n,8)+4*array(n,1)+array(cells,4);
        var child=owner.child("shared-position-planes");
        try {
            work.reserve(child,bytes);
            var result=new BSharedPositionField(bound,child,work);
            result.construct();
            work.poll(); // Final admission: no completed field escapes a late cancellation.
            return result;
        } catch(RuntimeException|Error failure){child.close();throw failure;}
    }
    static final class MutableResponse {
        double value, error;
        BSharedPositionEvidence.Status reason;
        void set(double value,double error,Status reason){this.value=value;this.error=error;this.reason=reason;}
    }
    private void ensureOpen(){bound.ensureOpen();if(owner.isClosed())throw new IllegalStateException("Shared field closed");}
    int width() {ensureOpen();return bound.grid.width;}
    int height() {ensureOpen();return bound.grid.height;}
    private int index(int x,int y){ensureOpen();if(x<0||y<0||x>=width()||y>=height())throw new IndexOutOfBoundsException();return y*width()+x;}
    double sourceValue(int x, int y) {int i=index(x,y);return available(reasons[0][i])?planes[0][i]:Double.NaN;}
    double positionalValue(int x, int y) {int i=index(x,y);return available(reasons[1][i])?planes[2][i]:Double.NaN;}
    double contextValue(int x, int y) {int i=index(x,y);return available(reasons[2][i])?planes[4][i]:Double.NaN;}
    double positionalError(int x,int y){return planes[3][index(x,y)];}
    Status sourceStatus(int x,int y){return status(reasons[0][index(x,y)]);}
    Status positionalStatus(int x,int y){return status(reasons[1][index(x,y)]);}
    Status contextStatus(int x,int y){return status(reasons[2][index(x,y)]);}
    static boolean available(int reason){return reason==Status.AVAILABLE.ordinal()||reason==Status.OBSERVED_ZERO.ordinal()||reason==Status.OUTSIDE_COMPONENT_SUPPORT.ordinal()||reason==Status.INELIGIBLE_GROUPED_PARENT.ordinal();}

    private void construct() {
        int w=width(),h=height();
        boolean anySource=false;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            work.poll();work.numeric(256);int i=y*w+x;
            double value=bound.sampleSource(bound.grid.east(x,y),bound.grid.north(x,y));
            planes[0][i]=value;reasons[0][i]=(byte)(Double.isFinite(value)?Status.AVAILABLE:Status.UNAVAILABLE_SOURCE_SUPPORT).ordinal();
            anySource|=Double.isFinite(value);
            reasons[1][i]=(byte)Status.OBSERVED_ZERO.ordinal();work.write(3);
        }
        if(!anySource)throw new IllegalArgumentException("All shared source support unavailable");
        boolean measured=false,unknown=false;
        Status missing=Status.UNAVAILABLE_RECEIPT;
        for(int m=0;m<bound.originals.length;m++) {
            work.poll();work.numeric(32);
            Status reason=status(bound.indices[2][m]);
            measured|=reason==Status.AVAILABLE;
            if(reason!=Status.AVAILABLE&&reason!=Status.INELIGIBLE_GROUPED_PARENT){unknown=true;missing=reason;}
        }
        if(unknown&&!measured)throw new UnavailableException(missing);
        // Complete conservative closed AABB of each contributor; no contributor plane/tensor retained.
        for(int m=0;m<bound.originals.length;m++) {
            prepare(m);
            for(int y=bound.indices[5][m];y<=bound.indices[6][m];y++)
                for(int x=bound.indices[3][m];x<=bound.indices[4][m];x++) {
                    evaluateVertex(m,x,y,response);
                    int i=y*w+x;
                    if(!available(response.reason.ordinal()))reasons[1][i]=(byte)response.reason.ordinal();
                    else {
                        double previous=planes[2][i],previousError=planes[3][i];
                        double lo=Math.max(previous-previousError,response.value-response.error);
                        double hi=Math.max(previous+previousError,response.value+response.error);
                        double nominal=Math.max(previous,response.value);
                        planes[2][i]=nominal;planes[3][i]=error(nominal,lo,hi);
                        if(available(reasons[1][i]))reasons[1][i]=(byte)(nominal>0?Status.AVAILABLE:Status.OBSERVED_ZERO).ordinal();
                    }
                    work.write(3);
                }
        }
        boolean anyAvailable=false;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            work.poll();int i=y*w+x;
            double maximum=planes[2][i],lo=Math.max(0,maximum-planes[3][i]),hi=maximum+planes[3][i];
            boolean known=available(reasons[1][i]);
            for(int dy=-4;dy<=4;dy++)for(int dx=-4;dx<=4;dx++) {
                if(dx*dx+dy*dy>16)continue;
                work.poll();work.numeric(128);work.maskRead(1);
                int xx=x+dx,yy=y+dy;
                if(xx<0||yy<0||xx>=w||yy>=h){known=false;continue;}
                int j=yy*w+xx;work.sourceRead(2);
                if(!available(reasons[1][j])){known=false;continue;}
                double distance=StrictMath.sqrt(dx*dx+dy*dy);work.strictMath();
                double weight=1-distance/4;
                scratch.set(0,planes[2][j],Math.max(0,planes[2][j]-planes[3][j]),planes[2][j]+planes[3][j]);
                scratch.set(1,weight,Math.max(0,Math.nextDown(1-Math.nextUp(distance)/4)),Math.nextUp(1-Math.nextDown(distance)/4));
                scratch.mul(2,0,1);
                maximum=Math.max(maximum,scratch.n[2]);lo=Math.max(lo,scratch.lo[2]);hi=Math.max(hi,scratch.hi[2]);
            }
            planes[4][i]=maximum;planes[5][i]=error(maximum,lo,hi);
            reasons[2][i]=(byte)(known?maximum>0?Status.AVAILABLE:Status.OBSERVED_ZERO:Status.UNAVAILABLE_SOURCE_SUPPORT).ordinal();
            reasons[3][i]=(byte)Status.UNAVAILABLE_RECEIPT.ordinal();
            anyAvailable|=available(reasons[1][i]);work.write(4);
        }
        if(!anyAvailable)throw new IllegalArgumentException("All shared source support unavailable");
        for(int y=0;y<h-1;y++)for(int x=0;x<w-1;x++) {
            work.poll();work.numeric(32);
            int i=y*w+x;
            cellSupport[y*(w-1)+x]=available(reasons[1][i])&&available(reasons[1][i+1])
                    &&available(reasons[1][i+w])&&available(reasons[1][i+w+1])?1:0;
            work.write(1);
        }
    }

    private int prepared=-1;
    private void prepare(int m) {
        if(prepared!=m) {
            projection.prepare(bound.d[AX][m],bound.d[AY][m],bound.d[NX][m],bound.d[NY][m],bound.d[LO][m],bound.d[HI][m]);
            prepared=m;
        }
    }

    /** Original own-shoulder projection is exact; tents/B3 use the frozen binary64 grouping. */
    private void evaluateVertex(int m,int x,int y,MutableResponse out) {
        work.poll();work.numeric(448);
        double east=bound.grid.east(x,y),north=bound.grid.north(x,y);
        var d=bound.d;
        scratch.exact(0,east);scratch.exact(1,d[AX][m]);scratch.sub(2,0,1);
        scratch.exact(3,north);scratch.exact(4,d[AY][m]);scratch.sub(5,3,4);
        scratch.exact(6,d[NX][m]);scratch.exact(7,d[NY][m]);
        scratch.mul(8,2,6);scratch.mul(9,5,7);scratch.add(10,8,9); // u
        scratch.exact(11,-d[NY][m]);scratch.mul(12,2,11);scratch.mul(13,5,6);scratch.add(14,12,13); // z
        Status eligibility=status(bound.indices[2][m]);
        if(eligibility==Status.INELIGIBLE_GROUPED_PARENT){out.set(0,0,eligibility);return;}
        if(!projection.inside(east,north)||scratch.lo[14]>d[18][m]||scratch.hi[14]<-d[18][m]) {
            out.set(0,0,Status.OUTSIDE_COMPONENT_SUPPORT);return;
        }
        if(eligibility!=Status.AVAILABLE){out.set(Double.NaN,Double.NaN,eligibility);return;}
        scratch.exact(15,d[CENTRE][m]);scratch.sub(16,10,15);
        if(scratch.lo[16]>d[16][m]||scratch.hi[16]<-d[16][m]){out.set(0,0,Status.OUTSIDE_COMPONENT_SUPPORT);return;}
        // Fixed9 V reads even for taps geometrically outside the own bracket or with zero tent.
        for(int dy=-1;dy<=1;dy++) {
            for(int dx=-1;dx<=1;dx++) {
                work.poll();work.numeric(64);int slot=20+dx+1;
                int xx=x+dx,yy=y+dy;
                if(xx<0||yy<0||xx>=width()||yy>=height()) {out.set(Double.NaN,Double.NaN,Status.UNAVAILABLE_SOURCE_SUPPORT);return;}
                int i=yy*width()+xx;work.maskRead(1);work.sourceRead(1);
                if(!available(reasons[0][i])){out.set(Double.NaN,Double.NaN,Status.UNAVAILABLE_SOURCE_SUPPORT);return;}
                if(!projection.inside(bound.grid.east(xx,yy),bound.grid.north(xx,yy)))scratch.exact(slot,0);
                else {
                    scratch.exact(23,planes[0][i]);scratch.exact(24,d[BACKGROUND][m]);scratch.sub(25,23,24);
                    scratch.exact(24,d[NUISANCE][m]);scratch.sub(slot,25,24);scratch.maximumZero(slot);
                }
                int tap=3*(dy+1)+dx+1;excess[tap]=scratch.n[slot];stencilValid[tap]=true;
            }
            // Horizontal [1,2,1]/4 then vertical [1,2,1]/4; no direct9-tap reassociation.
            scratch.exact(23,2);scratch.mul(24,21,23);scratch.add(25,20,24);scratch.add(26,25,22);
            scratch.exact(23,4);scratch.div(30+dy+1,26,23);
        }
        scratch.exact(23,2);scratch.mul(24,31,23);scratch.add(25,30,24);scratch.add(26,25,32);
        scratch.exact(23,4);scratch.div(33,26,23);
        double filtered=separableB3(excess,stencilValid);
        if(!bits(filtered,scratch.n[33]))throw new ArithmeticException("B3 grouping drift");
        scratch.absolute(40,16);scratch.set(41,d[H][m],d[15][m],d[16][m]);scratch.div(42,40,41);
        scratch.exact(43,1);scratch.sub(44,43,42);scratch.maximumZero(44);
        scratch.absolute(45,14);scratch.set(46,d[L][m],d[17][m],d[18][m]);scratch.div(47,45,46);
        scratch.sub(48,43,47);scratch.maximumZero(48);
        scratch.mul(49,48,44);scratch.mul(50,49,33);
        out.set(scratch.n[50],error(scratch.n[50],scratch.lo[50],scratch.hi[50]),
                scratch.n[50]>0?Status.AVAILABLE:Status.OBSERVED_ZERO);
    }

    /** Read-only per-vertex diagnostic uses the same owned response evaluator as construction. */
    void vertexResponse(int profile,int mode,int x,int y,MutableResponse out) {
        index(x,y);
        if(profile<0||profile>=bound.input.facts.size()||mode<0||mode>=bound.rows[profile+1]-bound.rows[profile])
            throw new IndexOutOfBoundsException();
        int m=bound.rows[profile]+mode;prepare(m);evaluateVertex(m,x,y,out);
    }

    static double ownExcess(double value,double background,double nuisance) {
        if(!Double.isFinite(value)||!Double.isFinite(background)||background<0||!Double.isFinite(nuisance)||nuisance<0)
            throw new ArithmeticException("Invalid own scalar excess");
        return Math.max(0,(value-background)-nuisance);
    }
    /** Literal horizontal then vertical B3. All nine support masks are required, even for zeros. */
    static double separableB3(double[] values,boolean[] valid) {
        if(values.length!=9||valid.length!=9)throw new IllegalArgumentException("B3 stencil inventory");
        for(int i=0;i<9;i++)if(!valid[i])return Double.NaN;
        double a=(values[0]+2*values[1]+values[2])/4;
        double b=(values[3]+2*values[4]+values[5])/4;
        double c=(values[6]+2*values[7]+values[8])/4;
        return (a+2*b+c)/4;
    }
    static double positiveProduct(double a,double b) {
        double result=a*b;
        if(!Double.isFinite(result)||a!=0&&b!=0&&result==0)throw new ArithmeticException("Positive shared product underflow/nonfinite");
        return result;
    }

    void componentResponse(int profile,int mode,int state,MutableResponse out) {
        ensureOpen();work.lookup();
        if(profile<0||profile>=bound.input.facts.size()||mode<0||mode>=bound.rows[profile+1]-bound.rows[profile]
                ||state<0||state>=bound.stateRows[profile+1]-bound.stateRows[profile])throw new IndexOutOfBoundsException();
        work.poll();work.numeric(5164); // Reviewed local scalar body, distinct from four R recomputations.
        int m=bound.rows[profile]+mode,s=bound.stateRows[profile]+state;
        if(status(bound.indices[2][m])==Status.INELIGIBLE_GROUPED_PARENT) {
            work.poll();out.set(0,0,Status.INELIGIBLE_GROUPED_PARENT);return;
        }
        prepare(m);
        double east=bound.stateX[s],north=bound.stateY[s];
        if(!projection.inside(east,north)){work.poll();out.set(0,0,Status.OUTSIDE_COMPONENT_SUPPORT);return;}
        if(!coordinates.locate(east,north,scratch)){work.poll();out.set(Double.NaN,Double.NaN,Status.UNAVAILABLE_SOURCE_SUPPORT);return;}
        int x0=coordinates.x0,y0=coordinates.y0;
        // Save all four corners in reusable slots; evaluateVertex's scratch uses only0..50.
        for(int c=0;c<4;c++) {
            evaluateVertex(m,x0+(c&1),y0+(c>>1),response);
            if(!available(response.reason.ordinal())){work.poll();out.set(Double.NaN,Double.NaN,response.reason);return;}
            scratch.set(56+c,response.value,Math.max(0,response.value-response.error),response.value+response.error);
        }
        scratch.mul(4,53,55);scratch.mul(5,52,55);scratch.mul(6,53,54);scratch.mul(7,52,54);
        scratch.mul(8,56,4);scratch.mul(9,57,5);scratch.mul(10,58,6);scratch.mul(11,59,7);
        scratch.add(12,8,9);scratch.add(13,10,11);scratch.add(14,12,13);
        double uncertainty=error(scratch.n[14],scratch.lo[14],scratch.hi[14]);
        work.poll();
        out.set(scratch.n[14],uncertainty,
                scratch.n[14]>0?Status.AVAILABLE:Status.OBSERVED_ZERO);
    }

    private static double error(double n,double lo,double hi) {
        if(n==lo&&n==hi)return 0;
        double result=Math.nextUp(Math.max(Math.abs(n-lo),Math.abs(hi-n)));
        if(!Double.isFinite(result))throw new ArithmeticException("Nonfinite shared enclosure");
        return result;
    }

    /** Fixed reusable nonnegative-response enclosure bank, no per-query objects. */
    static final class Interval {
        final double[] n=new double[64],lo=new double[64],hi=new double[64];
        void exact(int i,double value){set(i,value,value,value);}
        void set(int i,double value,double low,double high) {
            if(!Double.isFinite(value)||!Double.isFinite(low)||!Double.isFinite(high)||low>value||value>high)
                throw new ArithmeticException("Invalid shared interval");
            n[i]=value;lo[i]=low;hi[i]=high;
        }
        void add(int o,int a,int b){set(o,n[a]+n[b],Math.nextDown(lo[a]+lo[b]),Math.nextUp(hi[a]+hi[b]));}
        void sub(int o,int a,int b){set(o,n[a]-n[b],Math.nextDown(lo[a]-hi[b]),Math.nextUp(hi[a]-lo[b]));}
        void mul(int o,int a,int b) {
            double value=positiveProduct(n[a],n[b]);
            double aa=lo[a]*lo[b],ab=lo[a]*hi[b],ba=hi[a]*lo[b],bb=hi[a]*hi[b];
            set(o,value,Math.nextDown(Math.min(Math.min(aa,ab),Math.min(ba,bb))),Math.nextUp(Math.max(Math.max(aa,ab),Math.max(ba,bb))));
        }
        void div(int o,int a,int b) {
            if(lo[b]<=0&&hi[b]>=0)throw new ArithmeticException("Shared interval zero denominator");
            double aa=lo[a]/lo[b],ab=lo[a]/hi[b],ba=hi[a]/lo[b],bb=hi[a]/hi[b];
            set(o,n[a]/n[b],Math.nextDown(Math.min(Math.min(aa,ab),Math.min(ba,bb))),Math.nextUp(Math.max(Math.max(aa,ab),Math.max(ba,bb))));
        }
        void absolute(int o,int a){set(o,Math.abs(n[a]),lo[a]<=0&&hi[a]>=0?0:Math.min(Math.abs(lo[a]),Math.abs(hi[a])),Math.max(Math.abs(lo[a]),Math.abs(hi[a])));}
        void maximumZero(int i){set(i,Math.max(0,n[i]),Math.max(0,lo[i]),Math.max(0,hi[i]));}
    }

    /** Exact axis-aligned local cells; six retained integers share the funded scratch.
     * Public magnitudes are bounded BEFORE each API; <=115 APIs including old projection.
     * This local floor/upper ownership is distinct from lower-face CHORD ownership. */
    static final class GridCoordinates {
        private final Grid grid;
        private final BSharedWork work;
        private BigInteger ox,oy,ux,vy,maxX,maxY;
        private boolean initialized;
        int x0,y0;
        GridCoordinates(Grid grid,BSharedWork work) {
            if(grid.width<2||grid.height<2||grid.width>191401||grid.height>191401
                    ||grid.uy!=0||grid.vx!=0||!Double.isFinite(grid.ux)||!Double.isFinite(grid.vy)
                    ||grid.ux==0||grid.vy==0)throw new IllegalArgumentException("Unsupported exact local grid");
            this.grid=grid;this.work=work;
        }
        private void before(){work.poll();work.integerCall();}
        private BigInteger encode(double value) {
            if(!Double.isFinite(value))throw new ArithmeticException("Nonfinite local coordinate");
            long bits=Double.doubleToRawLongBits(value),exponent=(bits>>>52)&2047;
            long mantissa=bits&0xfffffffffffffL;
            if(exponent!=0)mantissa|=1L<<52;
            if(bits<0)mantissa=-mantissa;
            before();BigInteger integer=BigInteger.valueOf(mantissa);work.poll();
            int shift=exponent==0?0:(int)exponent-1;
            return shift(integer,shift,53);
        }
        private BigInteger shift(BigInteger operand,int amount,int magnitudeBits) {
            if(amount<0||magnitudeBits<0||amount>16384-magnitudeBits)
                throw new ArithmeticException("Local pre-call shift magnitude");
            before();BigInteger result=operand.shiftLeft(amount);work.poll();return result;
        }
        private void initialize() {
            if(initialized)return;
            ox=encode(grid.ox);oy=encode(grid.oy);ux=encode(grid.ux);vy=encode(grid.vy);
            if(grid.ux<0){before();ux=ux.negate();work.poll();}
            if(grid.vy<0){before();vy=vy.negate();work.poll();}
            before();BigInteger w=BigInteger.valueOf(grid.width-1);work.poll();
            before();BigInteger h=BigInteger.valueOf(grid.height-1);work.poll();
            // <=2098-bit positive axes times <=18-bit dimensions: result<=2116.
            before();maxX=ux.multiply(w);work.poll();
            before();maxY=vy.multiply(h);work.poll();initialized=true;
        }
        boolean locate(double east,double north,Interval out) {
            work.poll();initialize();
            x0=axis(east,ox,ux,maxX,grid.ux<0,grid.width,52,out);
            if(x0<0)return false;
            y0=axis(north,oy,vy,maxY,grid.vy<0,grid.height,54,out);
            work.poll();return y0>=0;
        }
        private int axis(double value,BigInteger origin,BigInteger den,BigInteger outer,
                boolean negative,int size,int slot,Interval out) {
            BigInteger encoded=encode(value);
            before();BigInteger num=encoded.subtract(origin);work.poll(); // <=2099 bits.
            if(negative){before();num=num.negate();work.poll();}
            before();int sign=num.signum();work.poll();
            if(sign<0)return -1;
            before();int bound=num.compareTo(outer);work.poll();
            if(bound>0)return -1; // Exact clipping precedes division and any cast.
            before();BigInteger[] qr=num.divideAndRemainder(den);work.poll();
            // Clipping proved quotient<=size-1<=191400 before this narrowing API.
            before();long cell=qr[0].longValue();work.poll();
            before();int remainder=qr[1].signum();work.poll();
            if(cell==size-1){out.exact(slot,1);out.exact(slot+1,0);return size-2;}
            if(remainder==0){out.exact(slot,0);out.exact(slot+1,1);return (int)cell;}
            before();BigInteger complement=den.subtract(qr[1]);work.poll(); // <=2099.
            ratio(qr[1],den,slot,out);ratio(complement,den,slot+1,out);
            return (int)cell;
        }
        /** RN-even nonnegative rational in[0,1], with exact rounding direction enclosure. */
        private void ratio(BigInteger num,BigInteger den,int slot,Interval out) {
            before();int sign=num.signum();work.poll();
            if(sign==0){out.exact(slot,0);return;}
            if(sign<0)throw new ArithmeticException("Negative local fraction");
            before();int bound=num.compareTo(den);work.poll();
            if(bound==0){out.exact(slot,1);return;}
            if(bound>0)throw new ArithmeticException("Local fraction above one");
            BigInteger classification=shift(num,1022,2099);
            before();int normal=classification.compareTo(den);work.poll();
            int exponent=-1022;
            if(normal>=0) {
                before();int numeratorBits=num.bitLength();work.poll();
                before();int denominatorBits=den.bitLength();work.poll();
                exponent=numeratorBits-denominatorBits;
                BigInteger trial=shift(num,-exponent,2099);
                before();int comparison=trial.compareTo(den);work.poll();
                if(comparison<0)exponent--;
                if(exponent< -1022||exponent>0)throw new ArithmeticException("Local normal exponent");
            }
            // <=2099+1074=3173 magnitude bits; no post-allocation size guessing.
            BigInteger scaled=shift(num,normal>=0?52-exponent:1074,2099);
            before();BigInteger[] qr=scaled.divideAndRemainder(den);work.poll();
            // Exact scaling gives quotient<=53 bits; no arbitrary BigInteger narrowing.
            before();long q=qr[0].longValue();work.poll();
            before();int remainder=qr[1].signum();work.poll();
            boolean roundUp=false;
            if(remainder!=0) {
                BigInteger twice=shift(qr[1],1,2098);
                before();int half=twice.compareTo(den);work.poll();
                roundUp=half>0||half==0&&(q&1L)!=0;
            }
            if(roundUp)q++;
            long bits=q;
            if(normal>=0) {
                if(q==1L<<53){q>>>=1;exponent++;}
                bits=((long)(exponent+1023)<<52)|(q-(1L<<52));
            }
            double nominal=Double.longBitsToDouble(bits);
            if(nominal==0)throw new ArithmeticException("Positive local fraction underflow");
            double lo=remainder==0||!roundUp?nominal:Math.nextDown(nominal);
            double hi=remainder==0||roundUp?nominal:Math.nextUp(nominal);
            if(lo<0||hi>1)throw new ArithmeticException("Local fraction enclosure outside cell");
            out.set(slot,nominal,lo,hi);
        }
    }

    /** Six retained dyadic constants, <=12 live expression references, no generic rational/cache.
     * Encode<=2098 bits; subtraction<=2099; product<=4197; sum<=4198; bracket<=3172.
     * These pre-call ceilings prove all public operands/results<16384 magnitude bits.
     * Constants are prepared once per mode; each tap uses at most11 actual exact APIs. */
    private static final class ExactProjection {
        private final BSharedWork work;
        private BigInteger ax,ay,nx,ny,lower,upper;
        ExactProjection(BSharedWork work){this.work=work;}
        private void before(){work.poll();work.integerCall();}
        private BigInteger encode(double value) {
            if(!Double.isFinite(value))throw new ArithmeticException("Nonfinite dyadic");
            long bits=Double.doubleToRawLongBits(value),exponent=(bits>>>52)&2047;
            long mantissa=bits&0xfffffffffffffL;
            if(exponent!=0)mantissa|=1L<<52;
            if(bits<0)mantissa=-mantissa;
            before();BigInteger integer=BigInteger.valueOf(mantissa);work.poll();
            int shift=exponent==0?0:(int)exponent-1;
            if(shift<0||shift+53>2098)throw new ArithmeticException("Dyadic pre-call magnitude");
            before();var result=integer.shiftLeft(shift);work.poll();return result;
        }
        void prepare(double x,double y,double nxx,double nyy,double lo,double hi) {
            ax=encode(x);ay=encode(y);nx=encode(nxx);ny=encode(nyy);
            BigInteger low=encode(lo),high=encode(hi);
            before();lower=low.shiftLeft(1074);work.poll();
            before();upper=high.shiftLeft(1074);work.poll();
        }
        boolean inside(double x,double y) {
            BigInteger ex=encode(x),ey=encode(y);
            before();BigInteger dx=ex.subtract(ax);work.poll();
            before();BigInteger dy=ey.subtract(ay);work.poll();
            before();BigInteger first=dx.multiply(nx);work.poll();
            before();BigInteger second=dy.multiply(ny);work.poll();
            before();BigInteger dot=first.add(second);work.poll();
            before();int left=dot.compareTo(lower);work.poll();
            before();int right=dot.compareTo(upper);work.poll();
            return left>=0&&right<=0;
        }
    }
    @Override public void close(){owner.close();}
}
