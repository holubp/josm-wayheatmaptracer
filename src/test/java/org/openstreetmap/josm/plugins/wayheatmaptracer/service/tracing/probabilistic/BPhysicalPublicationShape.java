package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

/** Partial numeric DATA receipt;never a complete PublicationPlan or export permit. */
final class BPhysicalPublicationShape {
    static final int PENDING_METADATA=1,COUNT_AVAILABLE=2,NOT_RUN=3;
    static final long FIXED=64*32+128*8+256;
    static final long PROFILE=32*8+128+2*32+2*73+41;
    static final long MODE=16*8+128+4*32+2*73+41;
    static final long RING=8*8+32+48+73+41,ROLE=8*8+32+48;
    static final long PAYLOAD=112+200; //O92=112 plus A(23,8)=200,BEFORE new.
    private final BPhysicalWork work;
    private final BExperimentalScalarCapture.AcquisitionPath path;
    private final long[] counts;
    private final long sourceEdges,sourceRings,pointQueries,sourceTaps,pointCost,
            pointSemanticUpper,pointHelperUpper,numericLeaves;
    private final int state;
    private BPhysicalPublicationShape(BPhysicalWork w,BExperimentalScalarCapture.AcquisitionPath p,long[] counts,
            long edges,long rings,long queries,long taps,long cost,long semantic,long helpers,long leaves){
        work=w;path=p;this.counts=counts;sourceEdges=edges;sourceRings=rings;pointQueries=queries;
        sourceTaps=taps;pointCost=cost;pointSemanticUpper=semantic;pointHelperUpper=helpers;
        numericLeaves=leaves;state=PENDING_METADATA;
    }
    /** Mandatory seam: no issuer exists until ALL actual ordered metadata and
     * protocol/source/dictionary/compiled/runtime authorities are implemented.
     * This type has no constructible value and grants no placeholder authority. */
    private static final class MetadataInput {
        private MetadataInput(){throw new UnsupportedOperationException("Actual mandatory metadata producer is UNSEALED");}
    }
    static BPhysicalPublicationShape compilePending(BPhysicalWork work,BPhysicalResponse response,
            BExperimentalScalarCapture.AcquisitionPath path){return compile(work,response,path,null);}
    private static BPhysicalPublicationShape compile(BPhysicalWork work,BPhysicalResponse response,
            BExperimentalScalarCapture.AcquisitionPath path,MetadataInput metadata){
        work.prepayTableBody(FIXED); //fixed helpers/producer/constructor/failure union BEFORE calls.
        if(response==null||path==null||response.work!=work||metadata!=null)
            throw new IllegalArgumentException("Only authentic partial publication DATA compilation");
        var b=response.bound();path.requireConsumed(b.input.evidence,work);
        var graph=b.input.lattices;var facts=b.input.facts;
        int n=graph.size(),d=b.indices[2].length;
        var source=b.input.evidence.evidenceRegion().polygons();var decision=b.input.region.polygons();
        int r=source.size(),dr=decision.size();
        long variable=add(mul(PROFILE,n),add(mul(MODE,d),add(mul(RING,add(r,dr)),mul(ROLE,23))));
        work.prepayTableBody(variable); //all loops and their failed prefixes before iteration.
        if(n<1||n>81||facts.size()!=n||d>8192||b.source==null||b.interpolation==null)
            throw new IllegalArgumentException("Complete original publication DATA inventory");
        work.sourceWork().metadata(PAYLOAD);
        long[] counts=new long[23];counts[7]=-1;counts[22]=-1; //unavailable,NEVER fabricated zero.
        counts[0]=b.source.length;counts[1]=b.interpolation.length;counts[2]=d;counts[4]=n;
        counts[5]=mul(20,d);counts[8]=b.q;counts[15]=path.pointCount();
        long p=0,provenance=0,modes=0,q=0,edges=0,decisionEdges=0;
        for(int i=0;i<n;i++){
            work.poll();b.ensureOpen();var cells=graph.get(i).cells();var ms=facts.get(i).modes();
            if(cells.isEmpty()||cells.size()>96||b.stateRows[i+1]-b.stateRows[i]!=cells.size())
                throw new IllegalArgumentException("Whole original profile/state shape");
            if(i+1<n)p=add(p,mul(cells.size(),graph.get(i+1).cells().size()));
            for(int m=0;m<ms.size();m++){
                work.poll();b.ensureOpen();int at=Math.toIntExact(modes);
                if(at>=d)throw new IllegalArgumentException("Original descriptor order");
                provenance=add(provenance,ms.get(m).provenance().size());
                int x0=b.indices[3][at],x1=b.indices[4][at],y0=b.indices[5][at],y1=b.indices[6][at];
                if(x0==0&&x1==-1&&y0==0&&y1==-1){/*receipt-proved canonical empty*/}
                else {
                    if(x0<0||x1<x0||x1>=b.grid.width||y0<0||y1<y0||y1>=b.grid.height)
                        throw new IllegalArgumentException("Original clipped inclusive footprint");
                    q=add(q,mul((long)x1-x0+1,(long)y1-y0+1));
                }
                modes++;
            }
        }
        if(modes!=d||q!=b.q||p>8_000_000)throw new IllegalArgumentException("Original Q/mode/pair join");
        for(int i=0;i<r;i++){work.poll();b.ensureOpen();edges=add(edges,source.get(i).size());}
        for(int i=0;i<dr;i++){work.poll();b.ensureOpen();decisionEdges=add(decisionEdges,decision.get(i).size());}
        counts[3]=add(counts[15],add(edges,decisionEdges));counts[6]=provenance;counts[14]=p;
        long leaves=0;for(int role=0;role<23;role++)if(availabilityOf(role)==COUNT_AVAILABLE)
            leaves=add(leaves,chunks(counts[role],stride(role)));
        long raw=add(11128,add(mul(969,edges),add(mul(42,r),mul(342,counts[0]))));
        long polls=add(1585,add(mul(122,edges),add(mul(2,r),mul(38,counts[0]))));
        long oneSource=add(raw,add(mul(41,polls),add(4*66,add(mul(10*66,counts[0]),mul(4*76,edges)))));
        long cost=add(BPhysicalWork.POINT_COUPLING_UNITS+BPhysicalWork.EXACT_GEOMETRY_UNITS+136754+11*73,mul(9,oneSource));
        long queries=add(q,mul(50,mul(d,counts[15]))),taps=mul(9,queries);
        long semantic=mul(queries,cost),helpers=add(mul(230,semantic),4845);
        add(semantic,helpers); //checked representability of this partial child,not complete U.
        work.poll();b.ensureOpen();path.requireConsumed(b.input.evidence,work);
        return new BPhysicalPublicationShape(work,path,counts,edges,r,queries,taps,cost,semantic,helpers,leaves);
    }
    private void live(){work.prepayTableBody(256);work.live();
        if(state!=PENDING_METADATA||path.pointCount()!=counts[15])throw new IllegalStateException("No live numeric DATA receipt");}
    int status(){live();return state;}
    private static int availabilityOf(int role){stride(role);
        return role==7||role==22?PENDING_METADATA:role==9||role>=10&&role<=13||role>=16&&role<=21?NOT_RUN:COUNT_AVAILABLE;}
    int availability(int role){live();return availabilityOf(role);}
    long records(int role){live();if(availabilityOf(role)!=COUNT_AVAILABLE)
        throw new IllegalStateException("Role payload is NOT_RUN or mandatory metadata pending");return counts[role];}
    long chunks(int role){long count=records(role);return chunks(count,stride(role));}
    long availableNumericLeaves(){live();return numericLeaves;} //subset ONLY;no CSG1/text/metadata/INV/cache.
    long pointQueries(){live();return pointQueries;}
    long sourceTaps(){live();return sourceTaps;}
    long pointCost(){live();return pointCost;}
    long pointSemanticUpper(){live();return pointSemanticUpper;}
    long pointHelperUpper(){live();return pointHelperUpper;}
    long accessMaximum(int kind){live();return switch(kind){case 0->mul(4,sourceTaps);
        case 1->mul(10,mul(counts[0],sourceTaps));case 2->mul(14,pointQueries);
        case 3->mul(4,mul(sourceEdges,sourceTaps));default->throw new IllegalArgumentException("Source event kind");};}
    long completeCacheSlots(){live();throw new IllegalStateException("Mandatory metadata/CSG1/compiler producers pending;no complete plan");}
    private static long chunks(long n,int stride){if(n<0)throw new IllegalArgumentException("Unavailable numeric role");return n==0?0:1+(n-1)/(65528/stride);}
    static int stride(int role){return switch(role){case 0->9;case 1,7,22->1;case 2->208;case 3->24;case 4->32;case 5->40;
        case 6->80;case 8->704;case 9->160;case 10->176;case 11->80;case 12->8;case 13->432;case 14->28;case 15->68;
        case 16,17,18,19->16;case 20->20;case 21->24;default->throw new IllegalArgumentException("Fixed DATA role");};}
    private static long add(long a,long b){return Math.addExact(a,b);}
    private static long mul(long a,long b){return Math.multiplyExact(a,b);}
}
