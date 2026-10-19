package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.math.BigInteger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Reused exact original-edge predicates and outward interval scratch. No per-slab objects. */
final class BGapChord {
    final BGapExact exact;
    final BGapGrid grid;
    final BGapEvidence.WorkCounter work;
    final double[] nominal=new double[96],low=new double[96],high=new double[96];
    int used;
    BigInteger x0,y0,dx,dy,den;
    BigInteger taN,taD,tbN,tbD,yaN,yaD,ybN,ybD;
    double ax,ay,bx,by;
    BGapChord(BGapGrid grid,BGapEvidence.WorkCounter work,CancellationProbe cancellation) {
        this.grid=grid;this.work=work;exact=new BGapExact(512,work,cancellation);
    }
    void bind(double ax,double ay,double bx,double by) {
        int scale=0;
        scale=Math.max(scale,-BGapExact.exponent(ax));scale=Math.max(scale,-BGapExact.exponent(ay));
        scale=Math.max(scale,-BGapExact.exponent(bx));scale=Math.max(scale,-BGapExact.exponent(by));
        scale=Math.max(scale,-BGapExact.exponent(grid.ox));scale=Math.max(scale,-BGapExact.exponent(grid.oy));
        scale=Math.max(scale,-BGapExact.exponent(grid.ux));scale=Math.max(scale,-BGapExact.exponent(grid.uy));
        scale=Math.max(scale,-BGapExact.exponent(grid.vx));scale=Math.max(scale,-BGapExact.exponent(grid.vy));
        BigInteger ox=exact.scaled(grid.ox,scale),oy=exact.scaled(grid.oy,scale);
        BigInteger ux=exact.scaled(grid.ux,scale),uy=exact.scaled(grid.uy,scale);
        BigInteger vx=exact.scaled(grid.vx,scale),vy=exact.scaled(grid.vy,scale);
        BigInteger aX=exact.sub(exact.scaled(ax,scale),ox),aY=exact.sub(exact.scaled(ay,scale),oy);
        BigInteger bX=exact.sub(exact.scaled(bx,scale),ox),bY=exact.sub(exact.scaled(by,scale),oy);
        den=exact.sub(exact.mul(ux,vy),exact.mul(uy,vx));
        x0=exact.sub(exact.mul(aX,vy),exact.mul(aY,vx));y0=exact.sub(exact.mul(aY,ux),exact.mul(aX,uy));
        BigInteger x1=exact.sub(exact.mul(bX,vy),exact.mul(bY,vx)),y1=exact.sub(exact.mul(bY,ux),exact.mul(bX,uy));
        if(exact.cmp(den,BigInteger.ZERO)<0){den=exact.neg(den);x0=exact.neg(x0);y0=exact.neg(y0);x1=exact.neg(x1);y1=exact.neg(y1);}
        if(exact.cmp(den,BigInteger.ZERO)==0)throw new IllegalArgumentException("Degenerate gap grid");
        if(exact.cmp(x0,x1)>0||exact.cmp(x0,x1)==0&&exact.cmp(y0,y1)>0) {
            BigInteger t=x0;x0=x1;x1=t;t=y0;y0=y1;y1=t;
            double d=ax;ax=bx;bx=d;d=ay;ay=by;by=d;
        }
        dx=exact.sub(x1,x0);dy=exact.sub(y1,y0);this.ax=ax;this.ay=ay;this.bx=bx;this.by=by;
        work.numeric(48);
    }
    int floor(BigInteger n,BigInteger d) {
        BigInteger q=exact.div(n,d),r=exact.rem(n,d);
        if(exact.cmp(n,BigInteger.ZERO)<0&&exact.cmp(r,BigInteger.ZERO)!=0)q=exact.sub(q,BigInteger.ONE);
        if(exact.length(q)>30)throw new IllegalArgumentException("Gap coordinate index capacity");
        return exact.intValue(q);
    }
    int compare(BigInteger an,BigInteger ad,BigInteger bn,BigInteger bd) {return exact.cmp(exact.mul(an,bd),exact.mul(bn,ad));}
    void slab(int column) {
        taN=BigInteger.ZERO;taD=BigInteger.ONE;tbN=BigInteger.ONE;tbD=BigInteger.ONE;
        if(exact.cmp(dx,BigInteger.ZERO)>0) {
            BigInteger left=exact.sub(exact.mul(exact.integer(column),den),x0);
            BigInteger right=exact.sub(exact.mul(exact.integer((long)column+1),den),x0);
            if(exact.cmp(left,BigInteger.ZERO)>0){taN=left;taD=dx;}
            if(exact.cmp(right,dx)<0){tbN=right;tbD=dx;}
        }
        yaN=exact.add(exact.mul(y0,taD),exact.mul(dy,taN));yaD=exact.mul(den,taD);
        ybN=exact.add(exact.mul(y0,tbD),exact.mul(dy,tbN));ybD=exact.mul(den,tbD);
    }
    boolean positiveSlab(){return compare(taN,taD,tbN,tbD)<0;}
    BigInteger cutN(int row){return exact.sub(exact.mul(exact.integer(row),den),y0);}
    // All interval operations count the actual fixed straight-line endpoint work.
    void reset(){used=0;}
    int slot(double n,double l,double h){if(used==nominal.length)throw new IllegalStateException("Interval scratch capacity");int i=used++;nominal[i]=n;low[i]=l;high[i]=h;return i;}
    int scalar(double value){return slot(value,value,value);}
    int fraction(BigInteger n,BigInteger d) {
        double v=exact.rounded(n,d);work.add(BGapEvidence.Work.ENCLOSURE_OPERATION,2);
        return slot(v,Math.nextDown(v),Math.nextUp(v));
    }
    int interval(double n,double error){work.add(BGapEvidence.Work.ENCLOSURE_OPERATION,4);return slot(n,Math.nextDown(n-error),Math.nextUp(n+error));}
    int add(int a,int b){work.add(BGapEvidence.Work.ENCLOSURE_OPERATION,5);return slot(nominal[a]+nominal[b],Math.nextDown(low[a]+low[b]),Math.nextUp(high[a]+high[b]));}
    int sub(int a,int b){work.add(BGapEvidence.Work.ENCLOSURE_OPERATION,5);return slot(nominal[a]-nominal[b],Math.nextDown(low[a]-high[b]),Math.nextUp(high[a]-low[b]));}
    int mul(int a,int b) {
        double p=low[a]*low[b],q=low[a]*high[b],r=high[a]*low[b],s=high[a]*high[b];
        work.add(BGapEvidence.Work.ENCLOSURE_OPERATION,13);
        return slot(nominal[a]*nominal[b],Math.nextDown(Math.min(Math.min(p,q),Math.min(r,s))),Math.nextUp(Math.max(Math.max(p,q),Math.max(r,s))));
    }
    int div(int a,int b) {
        if(low[b]<=0&&high[b]>=0)throw new IllegalArgumentException("Gap interval division by zero");
        double p=low[a]/low[b],q=low[a]/high[b],r=high[a]/low[b],s=high[a]/high[b];
        work.add(BGapEvidence.Work.ENCLOSURE_OPERATION,15);
        return slot(nominal[a]/nominal[b],Math.nextDown(Math.min(Math.min(p,q),Math.min(r,s))),Math.nextUp(Math.max(Math.max(p,q),Math.max(r,s))));
    }
    int abs(int a) {
        work.add(BGapEvidence.Work.ENCLOSURE_OPERATION,5);
        return slot(Math.abs(nominal[a]),low[a]<=0&&high[a]>=0?0:Math.min(Math.abs(low[a]),Math.abs(high[a])),Math.max(Math.abs(low[a]),Math.abs(high[a])));
    }
    int length() {
        int x=sub(scalar(bx),scalar(ax)),y=sub(scalar(by),scalar(ay));
        int ax=abs(x),ay=abs(y);work.hypot();work.hypot();work.hypot();work.add(BGapEvidence.Work.ENCLOSURE_OPERATION,8);
        double v=StrictMath.hypot(nominal[x],nominal[y]);
        double l=Math.nextDown(Math.nextDown(StrictMath.hypot(low[ax],low[ay])));
        double h=Math.nextUp(Math.nextUp(StrictMath.hypot(high[ax],high[ay])));
        if(!Double.isFinite(v)||!Double.isFinite(h))throw new IllegalArgumentException("Nonfinite chord length");
        return slot(v,Math.max(0,l),h);
    }
    double radius(int i){work.add(BGapEvidence.Work.ENCLOSURE_OPERATION,4);return Math.nextUp(Math.max(nominal[i]-low[i],high[i]-nominal[i]));}
    void clear(){x0=y0=dx=dy=den=taN=taD=tbN=tbD=yaN=yaD=ybN=ybD=null;exact.clear();}
    void discard(){x0=y0=dx=dy=den=taN=taD=tbN=tbD=yaN=yaD=ybN=ybD=null;exact.discard();}
}
