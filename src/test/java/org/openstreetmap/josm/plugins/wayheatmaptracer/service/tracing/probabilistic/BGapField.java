package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.math.BigInteger;
import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationFacts.*;

/** Immutable negative observed-gap field. No truth, likelihood, admission or inference is consulted. */
final class BGapField implements AutoCloseable {
    static final long WRAPPERS=512;
    final BGapGrid grid;
    final List<InferenceProfile> profiles;
    private final double[] values;
    private final byte[] reasons;
    final double error;
    final AttemptMemoryLedger.Owner owner;
    final BGapEvidence.WorkCounter work;
    final long retainedBytes;
    private AttemptMemoryLedger.Owner pendingTable;
    private final long tableBytes;
    private BGapField(BGapGrid grid,List<InferenceProfile> profiles,double[] values,byte[] reasons,double error,
            AttemptMemoryLedger.Owner owner,BGapEvidence.WorkCounter work,long retainedBytes,
            AttemptMemoryLedger.Owner pendingTable,long tableBytes) {
        this.grid=grid;this.profiles=profiles;this.values=values;this.reasons=reasons;this.error=error;
        this.owner=owner;this.work=work;this.retainedBytes=retainedBytes;
        this.pendingTable=pendingTable;this.tableBytes=tableBytes;
    }
    static long array(long n,int size){return AttemptMemoryLedger.arrayBytes(n,size);}
    static void poll(BGapEvidence.WorkCounter work,CancellationProbe cancellation) {
        work.add(BGapEvidence.Work.CANCELLATION_CHECK,1);cancellation.checkpoint();
    }
    /** Every raw peak sample has one elementary owner; a conflict invalidates all claimants. */
    static void claimRaw(int[] sampleOwners,int[] conflicts,int mode,int first,int last,
            BGapEvidence.WorkCounter work,CancellationProbe cancellation) {
        for(int sample=first;sample<=last;sample++) {
            poll(work,cancellation);int prior=sampleOwners[sample];work.add(BGapEvidence.Work.CACHE_READ,1);
            if(prior==0)sampleOwners[sample]=mode+1;
            else if(prior!=mode+1){conflicts[prior-1]=1;conflicts[mode]=1;}
            work.numeric(8);
        }
    }
    /** Includes zero-duration and exterior candidates, not just positive SLAB events. */
    static long attemptedColumnBound(int width,long pairs) {
        if(width<2||pairs<0)throw new IllegalArgumentException("Gap column-bound dimensions");
        return Math.multiplyExact(pairs,Math.addExact((long)width,1));
    }
    static long buildBound(int n,int m,int rowModes,long c,long q,long k,long entries,int pieces,int words,long pairs,long attempts) {
        if(n<0||m<0||rowModes<0||c<0||q<0||k<0||entries<0||pieces<0||words<1||pairs<0||attempts<0)
            throw new IllegalArgumentException("Gap work-bound dimensions");
        long overlap=Math.multiplyExact(entries,Math.max(0,pieces-1))/2;
        long geometry=Math.addExact(Math.multiplyExact(8192,Math.addExact(entries,overlap)),
                Math.addExact(Math.multiplyExact(8,entries),Math.multiplyExact(16,c)));
        // Both sourceMaximum's sourceCoordinates and the pair integrator use
        // BGapExact(512), independently of the field geometry's derived words.
        long fixed512=Math.addExact(Math.multiplyExact(128,c),
                Math.addExact(Math.multiplyExact(128,pairs),Math.multiplyExact(256,attempts)));
        long side=Math.addExact(Math.multiplyExact(2L,words),2),block=Math.multiplyExact(side,side);
        int log=rowModes==0?0:32-Integer.numberOfLeadingZeros(rowModes);
        long scalar=Math.addExact(2*(2*191401L+190404L),Math.addExact(Math.multiplyExact(1024L,m),
                Math.multiplyExact(Math.multiplyExact(Math.multiplyExact(128L,n),m),log)));
        scalar=Math.addExact(scalar,Math.addExact(Math.multiplyExact(256,Math.addExact(Math.addExact(c,q),Math.addExact(k,entries))),
                Math.addExact(Math.multiplyExact(4096,attempts),Math.multiplyExact(128,pairs))));
        return Math.addExact(scalar,Math.addExact(Math.multiplyExact(geometry,Math.addExact(block,8)),
                Math.multiplyExact(fixed512,1_052_684L)));
    }
    static BGapField build(BGapEvidence.Input input,CancellationProbe cancellation,AttemptMemoryLedger.Owner parent) {
        Objects.requireNonNull(input);Objects.requireNonNull(cancellation);Objects.requireNonNull(parent);
        AttemptMemoryLedger.Owner metadata=null,construction=null,frozen=null,pendingTable=null;
        AttemptMemoryLedger.Reservation count=null;
        boolean success=false;
        try {
            metadata=parent.child("gap-metadata");construction=parent.child("gap-construction");
            frozen=parent.child("gap-field");pendingTable=parent.child("gap-future-table");
            count=metadata.reserve(800);
            BGapEvidence.WorkCounter work=new BGapEvidence.WorkCounter();work.own(800);
            poll(work,cancellation);BGapEvidence.validateAndRetain(input,metadata,cancellation,work);
            int n=input.facts().size(),m=0,maxRow=0;
            for(var row:input.facts()){poll(work,cancellation);m=Math.addExact(m,row.modes().size());maxRow=Math.max(maxRow,row.modes().size());work.numeric(5);}
            //32KiB bounds the <=4096-char identity's parsed/format buffers, the
            //<=1024-char frame prefix, source-coordinate scratch and wrappers.
            //Original evidence is borrowed; no native scalar plane is reconstructed.
            long metadataBytes=32768+4*array(m,8)+13*array(m,4)+8*array((long)maxRow+1,8)+array((long)n+1,4);
            int cMax=Math.multiplyExact(input.evidence().resampling().inputWidth()-1,input.evidence().resampling().inputHeight()-1);
            cMax=Math.max(cMax,Math.multiplyExact(input.evidence().fields().get(input.fieldKey()).width()-1,
                    input.evidence().fields().get(input.fieldKey()).height()-1));
            metadataBytes=Math.addExact(metadataBytes,array(cMax,4));
            count.close();count=null;work.release(800);
            try(var reservation=metadata.reserve(metadataBytes)) {
                work.own(metadataBytes);
                var grid=BGapGrid.from(input,work,cancellation);
                var data=new Metadata(input,grid,m,maxRow,work,cancellation);
                data.decode();data.match();data.quads();
                int c=grid.cells();int[] counts=new int[c];
                long entries=0;int maximum=0;
                for(int quad=0;quad<data.k;quad++) {
                    data.bounds(quad);
                    for(int y=data.minY;y<=data.maxY;y++)for(int x=data.minX;x<=data.maxX;x++) {
                        poll(work,cancellation);int index=y*(grid.width-1)+x;
                        counts[index]=Math.addExact(counts[index],1);maximum=Math.max(maximum,counts[index]);entries=Math.addExact(entries,1);
                        work.numeric(8);work.add(BGapEvidence.Work.CACHE_READ,1);
                    }
                }
                if(entries>Integer.MAX_VALUE)throw new IllegalArgumentException("CSR index capacity");
                int b=data.coordinateBits();int d=Math.addExact(Math.multiplyExact(64,b),1024);
                int words=Math.toIntExact((Math.addExact(Math.multiplyExact((long)Math.max(1,maximum),d),95))/32);
                long pairs=0;
                for(int row=1;row<input.profiles().size();row++){poll(work,cancellation);pairs=Math.addExact(pairs,
                        Math.multiplyExact((long)input.profiles().get(row-1).cells().size(),input.profiles().get(row).cells().size()));work.numeric(8);}
                long attempts=attemptedColumnBound(grid.width,pairs);
                work.reserveUpper(buildBound(n,m,maxRow,c,(long)(grid.width-1)*grid.height,data.k,entries,maximum,words,pairs,attempts));
                long scratchBytes=Math.addExact(BGapExact.bytes(words),Math.addExact(array((long)c+1,4),array(entries,4)));
                long fieldBytes=Math.addExact(WRAPPERS,Math.addExact(array(c,8),array(c,1)));
                long sourceBytes=BGapEvidence.sourceCloneBytes(input.evidence().fields().get(input.fieldKey()));
                long tableBytes=BGapPairTable.tableBytes(input.profiles());
                // Reserve the complete future NEW prefix/coordinate envelope during
                // construction as well, then reacquire it at its actual later phase.
                scratchBytes=Math.addExact(scratchBytes,Math.addExact(BGapPairTable.coordinateBytes(input.profiles()),BGapPairTable.prefixBytes(grid)));
                if(Math.addExact(parent.currentBytes(),Math.addExact(tableBytes,Math.addExact(scratchBytes,Math.addExact(fieldBytes,sourceBytes))))>BGapEvidence.BYTES)
                    throw new IllegalArgumentException("Gap field preflight memory limit");
                try(var scratch=construction.reserve(scratchBytes);var retained=frozen.reserve(fieldBytes);var future=pendingTable.reserve(tableBytes)) {
                    work.own(Math.addExact(scratchBytes,Math.addExact(fieldBytes,tableBytes)));future.adopt(new Object());
                    int[] offsets=new int[c+1],ids=new int[(int)entries];
                    for(int i=0;i<c;i++){poll(work,cancellation);offsets[i+1]=Math.addExact(offsets[i],counts[i]);counts[i]=0;work.numeric(5);}
                    for(int quad=0;quad<data.k;quad++) {
                        data.bounds(quad);
                        for(int y=data.minY;y<=data.maxY;y++)for(int x=data.minX;x<=data.maxX;x++) {
                            poll(work,cancellation);int index=y*(grid.width-1)+x;ids[offsets[index]+counts[index]++]=quad;work.numeric(8);
                        }
                    }
                    double[] values=new double[c];byte[] reasons=new byte[c];double error=0;
                    var exact=new BGapExact(words,work,cancellation);
                    try(var source=BGapEvidence.copySource(input,construction,cancellation)) {
                        work.own(sourceBytes);
                        for(int y=0;y<grid.height-1;y++)for(int x=0;x<grid.width-1;x++) {
                            poll(work,cancellation);int cell=y*(grid.width-1)+x;
                            var status=grid.known?BGapEvidence.Status.UNAVAILABLE_RECEIPT:BGapEvidence.Status.UNAVAILABLE_SOURCE_GRID;
                            BigInteger sumN=BigInteger.ZERO,sumD=BigInteger.ONE,targetN=null,targetD=null;
                            double strength=Double.POSITIVE_INFINITY,threshold=Double.POSITIVE_INFINITY;int chain=-1,positive=0;
                            boolean ambiguous=false;
                            for(int at=offsets[cell];at<offsets[cell+1];at++) {
                                int quad=ids[at];data.coordinates(quad,0);grid.corners(x,y,data.coordinates,8);
                                work.add(BGapEvidence.Work.STRIP_PREDICATE,1);
                                if(!exact.prepare(data.coordinates,2)){ambiguous=true;break;}
                                if(exact.clip()==0)continue;
                                if(chain>=0&&chain!=data.q[5][quad]){ambiguous=true;break;}
                                chain=data.q[5][quad];positive++;
                                if(targetN==null){targetN=exact.cellN;targetD=exact.cellD;}
                                sumN=exact.add(exact.mul(sumN,exact.areaD),exact.mul(exact.areaN,sumD));sumD=exact.mul(sumD,exact.areaD);
                                strength=Math.min(strength,data.strength(quad));threshold=Math.min(threshold,data.threshold(quad));work.numeric(4);
                                for(int previous=offsets[cell];previous<at;previous++) {
                                    data.coordinates(ids[previous],0);data.coordinates(quad,8);grid.corners(x,y,data.coordinates,16);
                                    work.add(BGapEvidence.Work.STRIP_PREDICATE,1);
                                    if(!exact.prepare(data.coordinates,3)||exact.overlap()){ambiguous=true;break;}
                                }
                                if(ambiguous)break;
                            }
                            if(ambiguous)status=BGapEvidence.Status.AMBIGUOUS_STRIP;
                            else if(positive>0&&exact.cmp(exact.mul(sumN,targetD),exact.mul(targetN,sumD))==0&&grid.known) {
                                double maximumValue=data.sourceMaximum(x,y,source);
                                if(Double.isNaN(maximumValue))status=BGapEvidence.Status.UNAVAILABLE_SUPPORT;
                                else {
                                    double deficit=Math.max(0,threshold-maximumValue),value=strength*deficit;work.numeric(4);
                                    if(!Double.isFinite(value)||strength>0&&deficit>0&&value==0)
                                        throw new IllegalArgumentException("Positive gap lost to underflow/nonfinite arithmetic");
                                    values[cell]=value;
                                    double aLo=Math.max(0,Math.nextDown(strength-data.descriptorError));
                                    double aHi=Math.nextUp(strength+data.descriptorError);
                                    double dLo=Math.max(0,Math.nextDown(threshold-maximumValue));
                                    double dHi=Math.max(0,Math.nextUp(threshold-maximumValue));
                                    double radius=Math.nextUp(Math.max(value-Math.nextDown(aLo*dLo),Math.nextUp(aHi*dHi)-value));
                                    error=Math.max(error,radius);
                                    status=value>0?BGapEvidence.Status.OBSERVED_GAP:BGapEvidence.Status.OBSERVED_NO_GAP;
                                    work.add(BGapEvidence.Work.ENCLOSURE_OPERATION,20);
                                }
                            }
                            reasons[cell]=(byte)status.ordinal();exact.clear();work.numeric(2);
                        }
                        work.absorb(source.counters());work.release(sourceBytes);
                    }
                    poll(work,cancellation);
                    var result=new BGapField(grid,input.profiles(),values,reasons,error,frozen,work,fieldBytes,pendingTable,tableBytes);
                    retained.adopt(result);work.release(scratchBytes);work.release(metadataBytes);
                    success=true;return result;
                }
            }
        } finally {
            if(count!=null)count.close();if(metadata!=null)metadata.close();if(construction!=null)construction.close();
            if(!success){if(frozen!=null)frozen.close();if(pendingTable!=null)pendingTable.close();}
        }
    }
    BGapEvidence.Status status(int x,int y){ensureOpen();return BGapEvidence.Status.values()[reasons[index(x,y)]];}
    double value(int x,int y){ensureOpen();return values[index(x,y)];}
    double cellValue(int index){ensureOpen();return values[index];}
    byte cellReason(int index){ensureOpen();return reasons[index];}
    double lower(int x,int y){double value=value(x,y);return reasons[index(x,y)]>=2?0:Math.max(0,Math.nextDown(value-error));}
    double upper(int x,int y){double value=value(x,y);return reasons[index(x,y)]>=2?0:Math.nextUp(value+error);}
    int index(int x,int y){if(x<0||y<0||x>=grid.width-1||y>=grid.height-1)throw new IndexOutOfBoundsException();return y*(grid.width-1)+x;}
    void ensureOpen(){if(owner.isClosed())throw new IllegalStateException("Gap field is closed");}
    BGapEvidence.Counters counters(){return work.snapshot();}
    BGapEvidence.WorkDetails workDetails(){return work.details();}
    void claimTable(AttemptMemoryLedger.Owner destination) {
        ensureOpen();if(pendingTable==null)throw new IllegalStateException("Original complete table reservation already consumed");
        pendingTable.transferTo(destination);pendingTable.close();pendingTable=null;
    }
    public void close(){if(!owner.isClosed()){owner.close();work.release(retainedBytes);if(pendingTable!=null){pendingTable.close();pendingTable=null;work.release(tableBytes);}}}

    private static final class Metadata {
        final BGapEvidence.Input input;final BGapGrid grid;final BGapEvidence.WorkCounter work;final CancellationProbe cancellation;
        final double[][] d;final int[][] e,q;final int[] rowOffsets;
        final int[] starts,ends;final long[] startXor,endXor;
        // The original <=401 sample bound is already verified by owned validation.
        // This fixed index lives inside the reserved32KiB metadata/scratch envelope.
        final int[] rawOwners=new int[401];
        final double[] coordinates=new double[24],bounds=new double[4];
        final BGapChord sourceCoordinates;
        int eligible,k,minX,maxX,minY,maxY;
        double descriptorError;
        Metadata(BGapEvidence.Input input,BGapGrid grid,int m,int maxRow,BGapEvidence.WorkCounter work,CancellationProbe cancellation) {
            this.input=input;this.grid=grid;this.work=work;this.cancellation=cancellation;
            d=new double[4][m];e=new int[4][m];q=new int[9][m];rowOffsets=new int[input.facts().size()+1];
            starts=new int[maxRow];ends=new int[maxRow];startXor=new long[maxRow+1];endXor=new long[maxRow+1];
            var t=input.evidence().transform();var origin=t.pixelCenterToMetric(0,0);
            double divisor=t.axisUnit()==RasterMetricTransform.AxisUnit.SOURCE_PIXEL?t.rasterPixelsPerSourcePixel():1;
            var field=input.evidence().fields().get(input.fieldKey());
            sourceCoordinates=new BGapChord(new BGapGrid(field.width(),field.height(),origin.xMeters(),origin.yMeters(),
                    t.xAxisEastMetersPerSourcePixel()/divisor,t.xAxisNorthMetersPerSourcePixel()/divisor,
                    t.yAxisEastMetersPerSourcePixel()/divisor,t.yAxisNorthMetersPerSourcePixel()/divisor,true),work,cancellation);
            Arrays.fill(e[2],-1);Arrays.fill(e[3],-1);
        }
        void poll(){BGapField.poll(work,cancellation);}
        void decode() {
            for(int row=0;row<input.facts().size();row++) {
                rowOffsets[row]=eligible;var facts=input.facts().get(row);
                poll();Arrays.fill(rawOwners,0);work.numeric(rawOwners.length);poll();
                for(int i=0;i<ends.length;i++){if((i&63)==0)poll();ends[i]=0;work.numeric(3);}
                for(int m=0;m<facts.modes().size();m++) {
                    poll();if(facts.baseline().modes().get(m).groupedParent())continue;
                    for(var receipt:facts.modes().get(m).provenance()) {
                        poll();work.add(BGapEvidence.Work.CACHE_READ,1);
                        if(receipt instanceof RawReceipt peak&&peak.firstPeakIndex()>=0
                                &&peak.lastPeakIndex()<facts.baseline().samples().size()
                                &&peak.firstPeakIndex()<=peak.lastPeakIndex())
                            claimRaw(rawOwners,ends,m,peak.firstPeakIndex(),peak.lastPeakIndex(),work,cancellation);
                    }
                }
                for(int m=0;m<facts.modes().size();m++) {
                    poll();var mode=facts.modes().get(m);var raw=facts.baseline().modes().get(m);
                    work.add(BGapEvidence.Work.MODE_COMPARISON,1);
                    if(raw.groupedParent()||ends[m]!=0)continue;
                    RawReceipt peak=null;FlankReceipt flank=null;StageReceipt rawStage=null;boolean duplicate=false;int sourceReceipts=0;
                    for(var receipt:mode.provenance()) {poll();work.add(BGapEvidence.Work.CACHE_READ,1);
                        if(receipt instanceof RawReceipt p){if(peak!=null)duplicate=true;peak=p;}
                        if(receipt instanceof FlankReceipt f){if(flank!=null)duplicate=true;flank=f;}
                        if(receipt instanceof CorrelationReceipt)sourceReceipts++;
                        if(receipt instanceof StageReceipt s&&s.original().stage()==
                                org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.ObservationStage.RAW)
                            {if(rawStage!=null)duplicate=true;rawStage=s;}
                    }
                    var samples=facts.baseline().samples();
                    if(duplicate||sourceReceipts!=1||peak==null||flank==null||!flank.complete()
                            ||rawStage==null||!rawStage.original().available()||peak.firstPeakIndex()<0
                            ||peak.lastPeakIndex()>=samples.size()||peak.firstPeakIndex()>peak.lastPeakIndex()
                            ||peak.winnerIndex()<peak.firstPeakIndex()||peak.winnerIndex()>peak.lastPeakIndex()
                            ||flank.leftIndex()<0||flank.rightIndex()>=samples.size()||flank.leftIndex()>=flank.rightIndex()
                            ||peak.firstPeakIndex()<flank.leftIndex()||peak.lastPeakIndex()>flank.rightIndex()
                            ||rawStage.original().winnerSampleIndex()!=peak.winnerIndex())continue;
                    double left=fact(mode,Feature.SHOULDER_MINIMUM),right=fact(mode,Feature.SHOULDER_MAXIMUM);
                    double peakValue=fact(mode,Feature.PEAK),background=fact(mode,Feature.BACKGROUND),rms=fact(mode,Feature.RMS_DESCRIPTOR),filter=fact(mode,Feature.FILTER_DESCRIPTOR);
                    double threshold=fact(mode,Feature.SHOULDER_THRESHOLD);
                    if(!Double.isFinite(left+right+peakValue+background+rms+filter+threshold)||rms<0||filter<0||left>=right
                            ||peakValue<0||peakValue>1||background<0||background>1||rms>1||filter>1||threshold<0||threshold>1
                            ||samples.get(flank.leftIndex()).offsetMeters()!=left||samples.get(flank.rightIndex()).offsetMeters()!=right
                            ||!samples.get(flank.leftIndex()).valid()||!samples.get(flank.rightIndex()).valid()
                            ||!samples.get(peak.winnerIndex()).valid()||samples.get(peak.winnerIndex()).intensity()!=peakValue
                            ||Double.doubleToRawLongBits(rawStage.original().localMaximum())!=Double.doubleToRawLongBits(peakValue))continue;
                    double a=Math.max(0,(peakValue-background)-Math.max(rms,filter));work.numeric(12);
                    double tau=Math.max(rms,filter);
                    double lo=Math.max(0,Math.nextDown(Math.nextDown(peakValue-background)-tau));
                    double hi=Math.max(0,Math.nextUp(Math.nextUp(peakValue-background)-tau));
                    descriptorError=Math.max(descriptorError,Math.nextUp(Math.max(a-lo,hi-a)));
                    work.add(BGapEvidence.Work.ENCLOSURE_OPERATION,14);
                    d[0][eligible]=left;d[1][eligible]=right;d[2][eligible]=a;d[3][eligible]=threshold;
                    e[0][eligible]=row;e[1][eligible]=m;eligible++;work.numeric(8);
                }
            }
            rowOffsets[input.facts().size()]=eligible;
        }
        double fact(ModeFacts mode,Feature feature) {
            for(var fact:mode.values()){poll();work.add(BGapEvidence.Work.CACHE_READ,1);if(fact.feature()==feature)
                return fact.availability()==Availability.MEASURED?fact.value().orElse(Double.NaN):Double.NaN;}
            return Double.NaN;
        }
        void events(int row) {
            int begin=rowOffsets[row],n=rowOffsets[row+1]-begin;
            for(int i=0;i<n;i++){poll();starts[i]=ends[i]=begin+i;work.numeric(4);}
            sort(starts,n,0);sort(ends,n,1);startXor[0]=endXor[0]=0;
            for(int i=0;i<n;i++){poll();startXor[i+1]=startXor[i]^(starts[i]+1L);endXor[i+1]=endXor[i]^(ends[i]+1L);work.numeric(8);}
        }
        void sort(int[] ids,int n,int feature) {
            for(int i=n/2-1;i>=0;i--)sift(ids,i,n,feature);
            for(int end=n-1;end>0;end--){poll();int t=ids[0];ids[0]=ids[end];ids[end]=t;sift(ids,0,end,feature);work.numeric(4);}
        }
        void sift(int[] ids,int root,int n,int feature) {
            while(root*2+1<n){poll();int child=root*2+1;if(child+1<n&&compare(ids[child],ids[child+1],feature)<0)child++;
                if(compare(ids[root],ids[child],feature)>=0)return;int t=ids[root];ids[root]=ids[child];ids[child]=t;root=child;work.numeric(8);}
        }
        int compare(int a,int b,int feature){work.add(BGapEvidence.Work.MODE_COMPARISON,1);work.add(BGapEvidence.Work.CACHE_READ,2);
            int value=Double.compare(d[feature][a],d[feature][b]);return value==0?Integer.compare(e[1][a],e[1][b]):value;}
        int bound(int[] ids,int n,double value,int feature,boolean inclusive) {
            int lo=0,hi=n;
            while(lo<hi){poll();int mid=(lo+hi)>>>1;double candidate=d[feature][ids[mid]];
                work.add(BGapEvidence.Work.CACHE_READ,2);work.add(BGapEvidence.Work.MODE_COMPARISON,1);work.numeric(5);
                if(candidate<value||inclusive&&candidate==value)lo=mid+1;else hi=mid;}
            return lo;
        }
        int partner(int id,int targetRow) {
            int n=rowOffsets[targetRow+1]-rowOffsets[targetRow];
            int startsBeforeEnd=bound(starts,n,d[1][id],0,false),endsBeforeStart=bound(ends,n,d[0][id],1,true);
            work.numeric(4);
            return startsBeforeEnd-endsBeforeStart==1?(int)(startXor[startsBeforeEnd]^endXor[endsBeforeStart])-1:-1;
        }
        void match() {
            for(int row=0;row<input.facts().size()-1;row++) {
                events(row+1);for(int id=rowOffsets[row];id<rowOffsets[row+1];id++){poll();e[2][id]=partner(id,row+1);}
                events(row);for(int id=rowOffsets[row+1];id<rowOffsets[row+2];id++){poll();e[3][id]=partner(id,row);}
                for(int id=rowOffsets[row];id<rowOffsets[row+1];id++){poll();int next=e[2][id];if(next>=0&&e[3][next]!=id)e[2][id]=-1;work.numeric(4);}
            }
            Arrays.fill(e[3],-1);work.numeric(eligible);
        }
        void quads() {
            for(int row=0;row<input.facts().size()-1;row++) {
                events(row);int count=rowOffsets[row+1]-rowOffsets[row];
                for(int i=0;i<count-1;i++) {
                    poll();int low=starts[i],high=starts[i+1],nl=e[2][low],nh=e[2][high];work.numeric(8);
                    if(nl<0||nh<0||d[1][low]>=d[0][high]||d[1][nl]>=d[0][nh]||d[0][nl]>=d[0][nh])continue;
                    q[0][k]=row;q[1][k]=low;q[2][k]=high;q[3][k]=nl;q[4][k]=nh;
                    int previous=e[3][low];q[5][k]=previous>=0&&q[4][previous]==high?q[5][previous]:k;
                    e[3][nl]=k;k++;work.numeric(12);
                }
            }
        }
        void coordinates(int quad,int offset) {
            point(q[1][quad],d[1][q[1][quad]],offset);point(q[3][quad],d[1][q[3][quad]],offset+2);
            point(q[4][quad],d[0][q[4][quad]],offset+4);point(q[2][quad],d[0][q[2][quad]],offset+6);
        }
        void point(int descriptor,double lateral,int offset) {
            var raw=input.facts().get(e[0][descriptor]).baseline();
            coordinates[offset]=raw.anchor().xMeters()+raw.normalUnit().xMeters()*lateral;
            coordinates[offset+1]=raw.anchor().yMeters()+raw.normalUnit().yMeters()*lateral;work.numeric(8);
        }
        void bounds(int quad) {
            poll();coordinates(quad,0);double loX=Double.POSITIVE_INFINITY,hiX=Double.NEGATIVE_INFINITY,loY=loX,hiY=hiX;
            for(int i=0;i<8;i+=2){grid.bounds(coordinates[i],coordinates[i+1],bounds);
                loX=Math.min(loX,bounds[0]);hiX=Math.max(hiX,bounds[1]);loY=Math.min(loY,bounds[2]);hiY=Math.max(hiY,bounds[3]);work.numeric(80);}
            minX=Math.max(0,(int)Math.floor(loX));maxX=Math.min(grid.width-2,(int)Math.floor(hiX));
            minY=Math.max(0,(int)Math.floor(loY));maxY=Math.min(grid.height-2,(int)Math.floor(hiY));work.numeric(12);
        }
        int coordinateBits() {
            int scale=0;double maximum=1;
            for(int quad=0;quad<k;quad++){poll();coordinates(quad,0);for(int i=0;i<8;i++){scale=Math.max(scale,-BGapExact.exponent(coordinates[i]));maximum=Math.max(maximum,Math.abs(coordinates[i]));work.numeric(6);}}
            grid.corners(grid.width-2,grid.height-2,coordinates,0);grid.corners(0,0,coordinates,8);
            for(int i=0;i<16;i++){scale=Math.max(scale,-BGapExact.exponent(coordinates[i]));maximum=Math.max(maximum,Math.abs(coordinates[i]));work.numeric(6);}
            return Math.max(64,Math.addExact(scale,Math.getExponent(maximum)+2));
        }
        double strength(int quad){return Math.min(Math.min(d[2][q[1][quad]],d[2][q[2][quad]]),Math.min(d[2][q[3][quad]],d[2][q[4][quad]]));}
        double threshold(int quad){return Math.min(Math.min(d[3][q[1][quad]],d[3][q[2][quad]]),Math.min(d[3][q[3][quad]],d[3][q[4][quad]]));}
        double sourceMaximum(int x,int y,BGapEvidence.SourceCopy source) {
            var original=input.evidence().fields().get(input.fieldKey());int w=original.width(),h=original.height();
            var fine=sourceCoordinates.grid;
            // Fixed physical analysis partition; all covered authoritative fine
            // cells count, including half-phase cells whose vertices lie outside it.
            // Registered capture has parallel axes; direct scalar grids share axes.
            if(grid.ux*fine.uy-grid.uy*fine.ux!=0||grid.vx*fine.vy-grid.vy*fine.vx!=0)return Double.NaN;
            grid.point(x,y,coordinates,0);grid.point(x+1,y+1,coordinates,2);
            sourceCoordinates.bind(coordinates[0],coordinates[1],coordinates[2],coordinates[3]);
            var e=sourceCoordinates.exact;
            BigInteger xn=sourceCoordinates.x0,yn=sourceCoordinates.y0,den=sourceCoordinates.den;
            BigInteger xend=e.add(xn,sourceCoordinates.dx),yend=e.add(yn,sourceCoordinates.dy);
            if(e.cmp(yn,yend)>0){BigInteger swap=yn;yn=yend;yend=swap;}
            int xlo=sourceCoordinates.floor(xn,den),ylo=sourceCoordinates.floor(yn,den);
            int xhi=sourceCoordinates.floor(xend,den),yhi=sourceCoordinates.floor(yend,den);
            if(e.cmp(xend,e.mul(e.integer(xhi),den))==0)xhi--;
            if(e.cmp(yend,e.mul(e.integer(yhi),den))==0)yhi--;
            if(xlo<0||ylo<0||xhi>=w-1||yhi>=h-1)return Double.NaN;
            double maximum=Double.NEGATIVE_INFINITY;
            for(int fy=ylo;fy<=yhi;fy++)for(int fx=xlo;fx<=xhi;fx++) {
                poll();work.add(BGapEvidence.Work.MASK_READ,1);
                if(!source.interpolationValid(fy*(w-1)+fx))return Double.NaN;
                for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++) {
                    int index=(fy+dy)*w+fx+dx;work.add(BGapEvidence.Work.MASK_READ,1);
                    if(!source.valid(index))return Double.NaN;
                    maximum=Math.max(maximum,source.value(index));work.add(BGapEvidence.Work.CACHE_READ,1);work.numeric(6);
                }
            }
            sourceCoordinates.clear();
            return maximum;
        }
    }
}
