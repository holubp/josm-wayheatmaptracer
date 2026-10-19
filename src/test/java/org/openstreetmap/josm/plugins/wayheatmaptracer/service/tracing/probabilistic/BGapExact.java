package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.math.BigInteger;
import java.util.Arrays;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Bounded original-supporting-line geometry. No recursive rational clipping, GCD or rounded predicate. */
final class BGapExact {
    static final BigInteger ZERO=BigInteger.ZERO,ONE=BigInteger.ONE;
    final BGapEvidence.WorkCounter work;
    final CancellationProbe cancellation;
    final int bits;
    final long block;
    final BigInteger[] x=new BigInteger[12],y=new BigInteger[12];
    final BigInteger[] a=new BigInteger[12],b=new BigInteger[12],c=new BigInteger[12];
    final BigInteger[] px=new BigInteger[28],py=new BigInteger[28],pz=new BigInteger[28];
    final int[] order=new int[64];
    BigInteger areaN=ZERO,areaD=ONE;
    BigInteger cellN=ZERO,cellD=ONE;
    int scale;
    BGapExact(int words,BGapEvidence.WorkCounter work,CancellationProbe cancellation) {
        this.bits=Math.multiplyExact(words,32);this.work=work;this.cancellation=cancellation;
        this.block=Math.multiplyExact(2L*words+2,2L*words+2);
    }
    static long bytes(int words) {
        return Math.addExact(Math.multiplyExact(512,AttemptMemoryLedger.objectBytes(64)),
                Math.addExact(Math.multiplyExact(512,AttemptMemoryLedger.arrayBytes(2L*words,4)),AttemptMemoryLedger.arrayBytes(64,4)));
    }
    void poll() {work.add(BGapEvidence.Work.CANCELLATION_CHECK,1);cancellation.checkpoint();}
    private void before() {poll();work.integerCall(block);}
    private BigInteger after(BigInteger value) {if(length(value)>bits)throw new IllegalArgumentException("Exact arithmetic capacity");poll();return value;}
    int length(BigInteger value){poll();work.integerCall(1);int result=value.bitLength();poll();return result;}
    int intValue(BigInteger value){poll();work.integerCall(1);int result=value.intValue();poll();return result;}
    double doubleValue(BigInteger value){poll();work.integerCall(1);double result=value.doubleValue();poll();return result;}
    boolean odd(BigInteger value){poll();work.integerCall(1);boolean result=value.testBit(0);poll();return result;}
    BigInteger add(BigInteger p,BigInteger q) {before();return after(p.add(q));}
    BigInteger sub(BigInteger p,BigInteger q) {before();return after(p.subtract(q));}
    BigInteger mul(BigInteger p,BigInteger q) {before();return after(p.multiply(q));}
    BigInteger div(BigInteger p,BigInteger q) {before();return after(p.divide(q));}
    BigInteger rem(BigInteger p,BigInteger q) {before();return after(p.remainder(q));}
    BigInteger shift(BigInteger p,int n) {before();return after(p.shiftLeft(n));}
    BigInteger neg(BigInteger p) {before();return after(p.negate());}
    BigInteger integer(long value) {before();return after(BigInteger.valueOf(value));}
    int cmp(BigInteger p,BigInteger q) {before();int result=p.compareTo(q);poll();return result;}
    static int exponent(double value) {
        long bits=Double.doubleToRawLongBits(value)&Long.MAX_VALUE;
        if(bits==0)return 0;
        int exponent=(int)(bits>>>52);long mantissa=bits&0xfffffffffffffL;
        if(exponent!=0)mantissa|=1L<<52;
        return (exponent==0?-1074:exponent-1075)+Long.numberOfTrailingZeros(mantissa);
    }
    BigInteger scaled(double value,int binaryScale) {
        if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite exact coordinate");
        long raw=Double.doubleToRawLongBits(value),m=raw&0xfffffffffffffL;
        int e=(int)((raw>>>52)&2047);
        if(e==0&&m==0)return ZERO;
        if(e!=0)m|=1L<<52;
        int trailing=Long.numberOfTrailingZeros(m);m>>>=trailing;
        int shift=(e==0?-1074:e-1075)+trailing+binaryScale;
        if(shift<0)throw new IllegalArgumentException("Inexact binary scale");
        BigInteger result=shift(integer(m),shift);
        return raw<0?neg(result):result;
    }
    /** Input is original binary64 vertices, in four-vertex rings. */
    boolean prepare(double[] coordinates,int polygons) {
        clear();int n=4*polygons;scale=0;
        for(int i=0;i<2*n;i++){poll();scale=Math.max(scale,-exponent(coordinates[i]));work.numeric(3);}
        for(int i=0;i<n;i++){x[i]=scaled(coordinates[2*i],scale);y[i]=scaled(coordinates[2*i+1],scale);}
        for(int ring=0;ring<polygons;ring++) {
            BigInteger signed=ZERO;
            for(int i=0;i<4;i++){int j=(i+1)%4,p=ring*4+i,q=ring*4+j;signed=add(signed,sub(mul(x[p],y[q]),mul(x[q],y[p])));}
            int sign=cmp(signed,ZERO);if(sign==0)return false;
            if(ring==polygons-1){cellN=sign<0?neg(signed):signed;cellD=shift(ONE,1+2*scale);}
            for(int i=0;i<4;i++) {
                int p=ring*4+i,q=ring*4+(i+1)%4,r=ring*4+(i+2)%4;
                BigInteger turn=sub(mul(sub(x[q],x[p]),sub(y[r],y[q])),mul(sub(y[q],y[p]),sub(x[r],x[q])));
                if(cmp(turn,ZERO)*sign<=0)return false;
                a[p]=sub(y[p],y[q]);b[p]=sub(x[q],x[p]);c[p]=sub(mul(x[p],y[q]),mul(x[q],y[p]));
                if(sign<0){a[p]=neg(a[p]);b[p]=neg(b[p]);c[p]=neg(c[p]);}
            }
        }
        return true;
    }
    BigInteger ix,iy,iz;
    private boolean intersection(int p,int q,int lines) {
        ix=sub(mul(b[p],c[q]),mul(b[q],c[p]));
        iy=sub(mul(c[p],a[q]),mul(c[q],a[p]));
        iz=sub(mul(a[p],b[q]),mul(a[q],b[p]));
        int sign=cmp(iz,ZERO);if(sign==0)return false;
        if(sign<0){ix=neg(ix);iy=neg(iy);iz=neg(iz);}
        for(int k=0;k<lines;k++)if(cmp(add(add(mul(a[k],ix),mul(b[k],iy)),mul(c[k],iz)),ZERO)<0)return false;
        return true;
    }
    private boolean equal(int p) {return cmp(mul(px[p],iz),mul(ix,pz[p]))==0&&cmp(mul(py[p],iz),mul(iy,pz[p]))==0;}
    int clip() {
        int n=0;
        for(int i=0;i<8;i++)for(int j=i+1;j<8;j++)if(intersection(i,j,8)) {
            boolean duplicate=false;
            for(int k=0;k<n;k++)if(equal(k)){duplicate=true;break;}
            if(!duplicate){px[n]=ix;py[n]=iy;pz[n]=iz;order[n]=n;n++;}
        }
        if(n<3){areaN=ZERO;areaD=ONE;return 0;}
        heapSort(n);
        int count=0;
        for(int i=0;i<n;i++) {
            int id=order[i];
            while(count>=2&&orientation(order[28+count-2],order[28+count-1],id)<=0)count--;
            order[28+count++]=id;
        }
        int lower=count;
        for(int i=n-2;i>=0;i--) {
            int id=order[i];
            while(count>lower&&orientation(order[28+count-2],order[28+count-1],id)<=0)count--;
            if(count>=36)throw new IllegalArgumentException("Hull capacity");order[28+count++]=id;
        }
        count--;if(count>8)throw new IllegalArgumentException("Original-line hull degree changed");
        areaN=ZERO;areaD=ONE;
        for(int i=0;i<count;i++) {
            int p=order[28+i],q=order[28+(i+1)%count];
            BigInteger numerator=sub(mul(px[p],py[q]),mul(px[q],py[p]));
            BigInteger denominator=mul(pz[p],pz[q]);
            areaN=add(mul(areaN,denominator),mul(numerator,areaD));areaD=mul(areaD,denominator);
        }
        if(cmp(areaN,ZERO)<0)areaN=neg(areaN);
        areaD=shift(areaD,Math.addExact(1,Math.multiplyExact(2,scale)));
        return cmp(areaN,ZERO)>0?count:0;
    }
    boolean overlap() {
        int n=0;
        for(int i=0;i<12;i++)for(int j=i+1;j<12;j++)if(intersection(i,j,12)) {
            if(n==0){px[0]=ix;py[0]=iy;pz[0]=iz;n=1;}
            else if(n==1) {if(!equal(0)){px[1]=ix;py[1]=iy;pz[1]=iz;n=2;}}
            else {px[2]=ix;py[2]=iy;pz[2]=iz;if(orientation(0,1,2)!=0)return true;}
        }
        return false;
    }
    private int compare(int p,int q) {
        int result=cmp(mul(px[p],pz[q]),mul(px[q],pz[p]));
        return result!=0?result:cmp(mul(py[p],pz[q]),mul(py[q],pz[p]));
    }
    private void heapSort(int n) {
        for(int k=n/2-1;k>=0;k--)sift(k,n);
        for(int end=n-1;end>0;end--){int t=order[0];order[0]=order[end];order[end]=t;sift(0,end);}
    }
    private void sift(int root,int n) {
        while(root*2+1<n){int child=root*2+1;if(child+1<n&&compare(order[child],order[child+1])<0)child++;
            if(compare(order[root],order[child])>=0)return;int t=order[root];order[root]=order[child];order[child]=t;root=child;}
    }
    private int orientation(int p,int q,int r) {
        BigInteger first=mul(px[p],sub(mul(py[q],pz[r]),mul(py[r],pz[q])));
        BigInteger second=mul(py[p],sub(mul(px[q],pz[r]),mul(px[r],pz[q])));
        BigInteger third=mul(pz[p],sub(mul(px[q],py[r]),mul(px[r],py[q])));
        return cmp(add(sub(first,second),third),ZERO);
    }
    /** Exact round-to-nearest-even conversion; no decimal/double division shortcut. */
    double rounded(BigInteger numerator,BigInteger denominator) {
        if(cmp(denominator,ZERO)<=0)throw new IllegalArgumentException("Rational denominator");
        int sign=cmp(numerator,ZERO);if(sign==0)return 0;
        BigInteger n=sign<0?neg(numerator):numerator;
        int e=length(n)-length(denominator);
        if(e>=0?cmp(n,shift(denominator,e))<0:cmp(shift(n,-e),denominator)<0)e--;
        if(e>1023)throw new IllegalArgumentException("Rational conversion overflow");
        int power=Math.max(-1074,e-52);
        BigInteger scaled=power<0?shift(n,-power):n;
        BigInteger divisor=power>0?shift(denominator,power):denominator;
        BigInteger q=div(scaled,divisor),r=rem(scaled,divisor);
        int half=cmp(shift(r,1),divisor);
        if(half>0||half==0&&odd(q))q=add(q,ONE);
        double result=Math.scalb(doubleValue(q),power);
        if(!Double.isFinite(result))throw new IllegalArgumentException("Rational conversion overflow");
        return sign<0?-result:result;
    }
    void clear() {
        poll();work.numeric(152);
        discard();
    }
    /** Cleanup must not call a cancelled probe or an exhausted work counter. */
    void discard() {
        Arrays.fill(x,null);Arrays.fill(y,null);Arrays.fill(a,null);Arrays.fill(b,null);Arrays.fill(c,null);
        Arrays.fill(px,null);Arrays.fill(py,null);Arrays.fill(pz,null);ix=iy=iz=null;areaN=ZERO;areaD=ONE;
    }
}
