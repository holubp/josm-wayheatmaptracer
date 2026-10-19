package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.math.BigInteger;
import java.util.List;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

final class BGapPairTable implements AutoCloseable {
    record Integral(double value,double error,double observedMeters,double unavailableMeters,int unavailableReasons) { }
    private final double[] values,observed,errors,lengthErrors;
    private final int[] reasons,offsets,widths;
    private final List<InferenceProfile> profiles;
    private final BGapEvidence.WorkCounter work;
    private final AttemptMemoryLedger.Owner owner;
    private final long bytes;
    private BGapPairTable(double[] values,double[] observed,int[] reasons,double[] errors,double[] lengthErrors,
            int[] offsets,int[] widths,List<InferenceProfile> profiles,BGapEvidence.WorkCounter work,
            AttemptMemoryLedger.Owner owner,long bytes) {
        this.values=values;this.observed=observed;this.reasons=reasons;this.errors=errors;this.lengthErrors=lengthErrors;
        this.offsets=offsets;this.widths=widths;this.profiles=profiles;this.work=work;this.owner=owner;this.bytes=bytes;
    }
    static long tableBytes(List<InferenceProfile> profiles) {
        long p=0;int n=profiles.size();
        for(int i=1;i<n;i++)p=Math.addExact(p,Math.multiplyExact((long)profiles.get(i-1).cells().size(),profiles.get(i).cells().size()));
        return Math.addExact(512,Math.addExact(2*BGapField.array(p,8)+BGapField.array(p,4),
                2*BGapField.array(Math.max(0,n-1),8)+2*BGapField.array(n,4)+BGapField.array(n,8)));
    }
    static long coordinateBytes(List<InferenceProfile> profiles) {
        long states=0;for(var profile:profiles)states=Math.addExact(states,profile.cells().size());
        return Math.addExact(2*BGapField.array(states,8),BGapField.array((long)profiles.size()+1,4));
    }
    static long prefixBytes(BGapGrid grid) {
        long q=Math.multiplyExact((long)grid.width-1,grid.height);
        return Math.addExact(4096+BGapExact.bytes(512),2*BGapField.array(q,8)+4*BGapField.array(q,4));
    }
    static Integrator prefix(BGapField field,CancellationProbe cancellation,AttemptMemoryLedger.Owner parent) {
        field.ensureOpen();var owner=parent.child("gap-prefix");long bytes=prefixBytes(field.grid);boolean charged=false;
        try(var reservation=owner.reserve(bytes)) {
            field.work.own(bytes);charged=true;var result=new Integrator(field,cancellation,owner,bytes);reservation.adopt(result);return result;
        } catch(RuntimeException|Error failure){owner.close();if(charged)field.work.release(bytes);throw failure;}
    }
    static BGapPairTable build(BGapField field,List<InferenceProfile> profiles,CancellationProbe cancellation,AttemptMemoryLedger.Owner parent) {
        field.ensureOpen();if(profiles.size()!=field.profiles.size())throw new IllegalArgumentException("Different gap profile inventory");
        for(int i=0;i<profiles.size();i++){BGapField.poll(field.work,cancellation);if(profiles.get(i)!=field.profiles.get(i))throw new IllegalArgumentException("Different original gap profile identity");}
        long bytes=tableBytes(profiles),coordsBytes=coordinateBytes(profiles);
        if(Math.addExact(parent.currentBytes(),Math.addExact(coordsBytes,prefixBytes(field.grid)))>BGapEvidence.BYTES)
            throw new IllegalArgumentException("Gap complete-table preflight memory limit");
        AttemptMemoryLedger.Owner owner=null,coordinateOwner=null;boolean success=false,charged=false,transferred=false;
        try {
            owner=parent.child("gap-pair-table");coordinateOwner=parent.child("gap-point-scratch");
            field.claimTable(owner);transferred=true;
            try(var coordinates=coordinateOwner.reserve(coordsBytes)) {
                field.work.own(coordsBytes);charged=true;
                try(var integral=prefix(field,cancellation,parent)) {
            int n=profiles.size(),p=0,s=0;int[] offsets=new int[n],widths=new int[n],pointOffsets=new int[n+1];
            for(int i=0;i<n;i++) {
                BGapField.poll(field.work,cancellation);widths[i]=profiles.get(i).cells().size();pointOffsets[i]=s;
                s=Math.addExact(s,widths[i]);offsets[i]=p;if(i<n-1)p=Math.addExact(p,Math.multiplyExact(widths[i],profiles.get(i+1).cells().size()));field.work.numeric(12);
            }
            pointOffsets[n]=s;double[] xs=new double[s],ys=new double[s];
            for(int row=0;row<n;row++)for(int state=0;state<widths[row];state++) {
                BGapField.poll(field.work,cancellation);var point=profiles.get(row).point(state);
                int at=pointOffsets[row]+state;xs[at]=point.xMeters();ys[at]=point.yMeters();field.work.numeric(10);
            }
            double[] values=new double[p],observed=new double[p],errors=new double[Math.max(0,n-1)],lengthErrors=new double[Math.max(0,n-1)];int[] reasons=new int[p];
            for(int stage=0;stage<n-1;stage++)for(int left=0;left<widths[stage];left++)for(int right=0;right<widths[stage+1];right++) {
                BGapField.poll(field.work,cancellation);int a=pointOffsets[stage]+left,b=pointOffsets[stage+1]+right;
                integral.compute(xs[a],ys[a],xs[b],ys[b]);int at=offsets[stage]+left*widths[stage+1]+right;
                values[at]=integral.value;observed[at]=integral.observed;reasons[at]=integral.reasons;
                errors[stage]=Math.max(errors[stage],integral.error);lengthErrors[stage]=Math.max(lengthErrors[stage],integral.lengthError);
                field.work.add(BGapEvidence.Work.PAIR_WRITE,1);field.work.numeric(14);
            }
            BGapField.poll(field.work,cancellation);
            var result=new BGapPairTable(values,observed,reasons,errors,lengthErrors,offsets,widths,field.profiles,field.work,owner,bytes);
            field.work.release(coordsBytes);success=true;return result;
                }
            }
        } finally {
            if(coordinateOwner!=null)coordinateOwner.close();
            if(!success){if(owner!=null)owner.close();if(transferred)field.work.release(bytes);if(charged)field.work.release(coordsBytes);}
        }
    }
    static final class Integrator implements AutoCloseable {
        final BGapField field;final BGapEvidence.WorkCounter work;final CancellationProbe cancellation;
        final AttemptMemoryLedger.Owner owner;final long bytes;
        final double[] prefix,prefixError;final int[][] missing;
        final BGapChord c;
        double value,error,observed,unavailable,lengthError,length;
        int reasons;
        Integrator(BGapField field,CancellationProbe cancellation,AttemptMemoryLedger.Owner owner,long bytes) {
            this.field=field;this.work=field.work;this.cancellation=cancellation;this.owner=owner;this.bytes=bytes;
            int h=field.grid.height,q=Math.multiplyExact(field.grid.width-1,h);
            prefix=new double[q];prefixError=new double[q];missing=new int[4][q];c=new BGapChord(field.grid,work,cancellation);
            for(int x=0;x<field.grid.width-1;x++)for(int y=0;y<h-1;y++) {
                BGapField.poll(work,cancellation);int at=x*h+y,index=y*(field.grid.width-1)+x;
                prefix[at+1]=prefix[at]+field.cellValue(index);
                prefixError[at+1]=Math.nextUp(prefixError[at]+field.error+Math.ulp(prefix[at+1]));
                int reason=field.cellReason(index);for(int r=0;r<4;r++)missing[r][at+1]=missing[r][at]+(reason==r+2?1:0);
                // Nominal/error and four reason arrays: six reads, six writes.
                work.add(BGapEvidence.Work.PREFIX_OPERATION,12);work.add(BGapEvidence.Work.ENCLOSURE_OPERATION,5);work.numeric(18);
            }
        }
        Integral integral(MetricPoint a,MetricPoint b){compute(a.xMeters(),a.yMeters(),b.xMeters(),b.yMeters());return new Integral(value,error,observed,unavailable,reasons);}
        void compute(double ax,double ay,double bx,double by) {
            ensureOpen();field.ensureOpen();BGapField.poll(work,cancellation);c.bind(ax,ay,bx,by);c.reset();
            int ell=c.length();length=c.nominal[ell];double ellLo=c.low[ell],ellHi=c.high[ell];
            value=error=observed=unavailable=lengthError=0;reasons=0;
            if(ax==bx&&ay==by){c.clear();return;}
            double valueLo=0,valueHi=0,observedLo=0,observedHi=0;
            int first=c.floor(c.x0,c.den),last=c.floor(c.exact.add(c.x0,c.dx),c.den);
            if(first==field.grid.width-1&&c.exact.cmp(c.dx,BigInteger.ZERO)==0)first--;
            if(last==field.grid.width-1)last--;
            first=Math.max(-1,Math.min(field.grid.width-1,first));
            last=Math.max(-1,Math.min(field.grid.width-1,last));
            for(int column=first;column<=last;column++) {
                BGapField.poll(work,cancellation);c.slab(column);
                if(column==-1){c.taN=BigInteger.ZERO;c.taD=BigInteger.ONE;}
                if(column==field.grid.width-1){c.tbN=BigInteger.ONE;c.tbD=BigInteger.ONE;}
                if(!c.positiveSlab())continue;
                c.yaN=c.exact.add(c.exact.mul(c.y0,c.taD),c.exact.mul(c.dy,c.taN));c.yaD=c.exact.mul(c.den,c.taD);
                c.ybN=c.exact.add(c.exact.mul(c.y0,c.tbD),c.exact.mul(c.dy,c.tbN));c.ybD=c.exact.mul(c.den,c.tbD);
                c.reset();ell=c.interval(length,Math.max(length-ellLo,ellHi-length));
                int start=c.fraction(c.taN,c.taD),end=c.fraction(c.tbN,c.tbD),duration=c.sub(end,start);
                int slabLength=c.mul(duration,ell),j=c.scalar(0),obs=c.scalar(0);int mask=0;
                if(column<0||column>=field.grid.width-1){mask=1<<3;}
                else {
                    int rowA=c.floor(c.yaN,c.yaD),rowB=c.floor(c.ybN,c.ybD);
                    int lo=Math.min(rowA,rowB),hi=Math.max(rowA,rowB);
                    if(c.exact.cmp(c.dy,BigInteger.ZERO)==0) {
                        int row=rowA;if(row==field.grid.height-1)row--;
                        int index=cell(column,row);mask=index<0?1<<3:mask(index);
                        if(mask==0){j=c.mul(slabLength,g(index));obs=slabLength;}
                    } else if(hi-lo<=1) {
                        BigInteger cutN=c.cutN(hi),cutD=c.dy;
                        if(c.exact.cmp(cutD,BigInteger.ZERO)<0){cutN=c.exact.neg(cutN);cutD=c.exact.neg(cutD);}
                        boolean split=c.compare(cutN,cutD,c.taN,c.taD)>0&&c.compare(cutN,cutD,c.tbN,c.tbD)<0;
                        int cut=split?c.fraction(cutN,cutD):end;
                        int row=interiorRow(c.yaN,c.yaD,c.exact.cmp(c.dy,BigInteger.ZERO));
                        int index=cell(column,row),pieceLength=c.mul(c.sub(cut,start),ell);
                        if(index>=0&&mask(index)==0){j=c.mul(pieceLength,g(index));obs=pieceLength;}else mask|=index<0?1<<3:mask(index);
                        if(split) {
                            row+=c.exact.cmp(c.dy,BigInteger.ZERO)>0?1:-1;index=cell(column,row);
                            pieceLength=c.mul(c.sub(end,cut),ell);
                            if(index>=0&&mask(index)==0){j=c.add(j,c.mul(pieceLength,g(index)));obs=c.add(obs,pieceLength);}else mask|=index<0?1<<3:mask(index);
                        }
                    } else {
                        int fa=endpoint(column,c.yaN,c.yaD),fb=endpoint(column,c.ybN,c.ybD);
                        int dy=c.abs(c.fraction(c.dy,c.den));j=c.div(c.mul(ell,c.abs(c.sub(fb,fa))),dy);
                        int unknownA=unknownEndpoint(column,c.yaN,c.yaD),unknownB=unknownEndpoint(column,c.ybN,c.ybD);
                        int unknownSpan=c.abs(c.sub(unknownB,unknownA));
                        int yspan=c.abs(c.sub(c.fraction(c.ybN,c.ybD),c.fraction(c.yaN,c.yaD)));
                        obs=c.div(c.mul(ell,c.sub(yspan,unknownSpan)),dy);
                        for(int r=0;r<4;r++)if(hasReason(column,r,c.yaN,c.yaD,c.ybN,c.ybD))mask|=1<<(r+2);
                        // Only a positive exterior portion owns this reason. Exact
                        // contact with the outer maximum still belongs to its last cell.
                        if(c.exact.cmp(c.yaN,BigInteger.ZERO)<0||c.exact.cmp(c.ybN,BigInteger.ZERO)<0
                                ||c.exact.cmp(c.yaN,c.exact.mul(c.exact.integer(field.grid.height-1),c.yaD))>0
                                ||c.exact.cmp(c.ybN,c.exact.mul(c.exact.integer(field.grid.height-1),c.ybD))>0)mask|=1<<3;
                    }
                }
                value+=c.nominal[j];valueLo=Math.nextDown(valueLo+c.low[j]);valueHi=Math.nextUp(valueHi+c.high[j]);
                observed+=c.nominal[obs];observedLo=Math.nextDown(observedLo+c.low[obs]);observedHi=Math.nextUp(observedHi+c.high[obs]);
                reasons|=mask;work.add(BGapEvidence.Work.SLAB_EVALUATION,1);work.add(BGapEvidence.Work.ENCLOSURE_OPERATION,8);work.numeric(8);
            }
            unavailable=length-observed;error=Math.nextUp(Math.max(value-valueLo,valueHi-value));
            lengthError=Math.nextUp(Math.max(observed-observedLo,observedHi-observed)+Math.max(length-ellLo,ellHi-length)+Math.ulp(unavailable));
            if(observed<0&&-observed<=lengthError)observed=0;
            if(unavailable<0&&-unavailable<=lengthError)unavailable=0;
            double allowance=Math.scalb(Math.max(1,length),-32);
            if(!Double.isFinite(value)||value<0||observed<0||unavailable<0||error>allowance||lengthError>allowance)
                throw new IllegalArgumentException("Gap integral arithmetic allowance exceeded");
            c.clear();
        }
        int interiorRow(BigInteger n,BigInteger d,int direction) {
            int row=c.floor(n,d);if(direction<0&&c.exact.cmp(n,c.exact.mul(c.exact.integer(row),d))==0)row--;return row;
        }
        int cell(int x,int y){return y<0||y>=field.grid.height-1?-1:y*(field.grid.width-1)+x;}
        int mask(int index){int reason=field.cellReason(index);work.add(BGapEvidence.Work.CACHE_READ,1);return reason<2?0:1<<reason;}
        int g(int index){work.add(BGapEvidence.Work.CACHE_READ,1);return c.interval(field.cellValue(index),field.error);}
        int endpoint(int column,BigInteger n,BigInteger d) {
            int row=c.floor(n,d),h=field.grid.height;
            if(row<0)return c.scalar(0);if(row>=h-1){int at=column*h+h-1;work.add(BGapEvidence.Work.PREFIX_OPERATION,2);return c.interval(prefix[at],prefixError[at]);}
            int at=column*h+row,index=row*(field.grid.width-1)+column;
            int fraction=c.fraction(c.exact.sub(n,c.exact.mul(c.exact.integer(row),d)),d);
            work.add(BGapEvidence.Work.PREFIX_OPERATION,2);
            return c.add(c.interval(prefix[at],prefixError[at]),c.mul(fraction,g(index)));
        }
        int unknownEndpoint(int column,BigInteger n,BigInteger d) {
            int h=field.grid.height,row=c.floor(n,d),sum=0;
            if(row<0)return c.fraction(n,d);
            if(row>=h-1){for(int r=0;r<4;r++)sum+=missing[r][column*h+h-1];work.add(BGapEvidence.Work.PREFIX_OPERATION,4);return c.add(c.scalar(sum),c.sub(c.fraction(n,d),c.scalar(h-1)));}
            for(int r=0;r<4;r++)sum+=missing[r][column*h+row];
            work.add(BGapEvidence.Work.PREFIX_OPERATION,4);
            int base=c.scalar(sum);if(mask(row*(field.grid.width-1)+column)==0)return base;
            return c.add(base,c.fraction(c.exact.sub(n,c.exact.mul(c.exact.integer(row),d)),d));
        }
        boolean hasReason(int column,int reason,BigInteger an,BigInteger ad,BigInteger bn,BigInteger bd) {
            if(c.compare(an,ad,bn,bd)>0){BigInteger t=an;an=bn;bn=t;t=ad;ad=bd;bd=t;}
            int lo=Math.max(0,Math.min(field.grid.height-1,c.floor(an,ad))),hi=Math.max(0,Math.min(field.grid.height-1,c.floor(bn,bd)));
            int count=missing[reason][column*field.grid.height+hi]-missing[reason][column*field.grid.height+lo];
            if(hi<field.grid.height-1&&c.exact.cmp(bn,c.exact.mul(c.exact.integer(hi),bd))>0&&field.cellReason(hi*(field.grid.width-1)+column)==reason+2)count++;
            work.add(BGapEvidence.Work.PREFIX_OPERATION,2);return count>0;
        }
        void ensureOpen(){if(owner.isClosed())throw new IllegalStateException("Gap prefix is closed");}
        public void close(){if(!owner.isClosed()){c.discard();owner.close();work.release(bytes);}}
    }
    private int index(int stage,int left,int right) {
        if(owner.isClosed())throw new IllegalStateException("Gap table is closed");
        if(stage<0||stage>=profiles.size()-1||left<0||left>=widths[stage]||right<0||right>=widths[stage+1])throw new IndexOutOfBoundsException();
        return offsets[stage]+left*widths[stage+1]+right;
    }
    Integral entry(int stage,int left,int right) {
        int at=index(stage,left,right);double length=profiles.get(stage).point(left).distanceTo(profiles.get(stage+1).point(right));
        double unknown=length-observed[at];if(unknown<0&&-unknown<=lengthErrors[stage])unknown=0;
        return new Integral(values[at],errors[stage],observed[at],unknown,reasons[at]);
    }
    double value(int stage,int left,int right){int at=index(stage,left,right);work.add(BGapEvidence.Work.SOLVER_LOOKUP,1);return values[at];}
    BGapEvidence.Counters counters(){return work.snapshot();}
    public void close(){if(!owner.isClosed()){owner.close();work.release(bytes);}}
}
