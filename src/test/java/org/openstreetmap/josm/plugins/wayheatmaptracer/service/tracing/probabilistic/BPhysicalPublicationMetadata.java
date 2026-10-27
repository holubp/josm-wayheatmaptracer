package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.io.IOException;
import java.io.OutputStream;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Complete live-v3 DATA metadata/count software. Never a runtime/export issuer. */
final class BPhysicalPublicationMetadata implements AutoCloseable {
    static final String SCHEMA="TABLE_ONLY_DATA_2";
    static final String SOURCE_SCHEMA="TABLE_ONLY_SOURCE_DATA_4";
    static final int SOURCE_VERSION=4,SOURCE_FIXED=731,SOURCE_GLOBALS=25;
    static final int VERSION=3,LEAF=65536,DATA=65528;
    static final long COMPILER_BYTES=56,PLAN_BYTES=224;
    //Explicit prospective compiler source dictionary, separate from JVM work.
    static final long ENTRY=8192,PROFILE=4096,MODE=4096,TEXT=512,CHAR=128,STATE=4096,ROLE=512;
    //Both wrapper/map sizes+max/caller/guards/return32+16+16+12+20+32.
    static final long MAP_BOUND=128,QUERY_GUARD=128,LOOKUP_PRODUCER=128;
    //New source-slot bodies,not JVM/solver instructions. Parent guards are
    //separate from finite straight-line getter/raw-copy/control slots.
    static final long FIXED_SOURCE_BYTES=80+16+36*8;
    static final long FIXED_CREATE=8*BPhysicalWork.MAX_LIVE+256+512+53*8+34*(8+3)+2*3+176;
    static final long FIXED_READ=2*BPhysicalWork.MAX_LIVE+128;
    static final long FIXED_BIND=2*BPhysicalWork.MAX_LIVE+128;
    static final long FIXED_SUPPORT=2*BPhysicalWork.MAX_LIVE+128;
    static final long SOURCE_TEXT_COPY=25*3+24*12+3*3+8+16;
    private final BPhysicalWork work;
    private final AttemptMemoryLedger.Owner root;
    private final CancellationProbe probe;
    private Plan published;
    private boolean used,closed;

    /** Borrowed UNIT shape only. It grants no capture/graph/source authority. */
    record Input(int width,int height,int analysisWidth,int analysisHeight,long points,int sourceRings,
            InferenceProfile[] graph,int[] modeCounts,String[][] modeTexts,int[] provenanceCounts,
            int[] rectangles,int[] rings,String[] globals) { }

    static final class PendingMetadata extends IllegalStateException {
        PendingMetadata(){super("Mandatory metadata identity or source producer unavailable");}
    }
    static final class UnavailableGetterFee extends IllegalStateException {
        UnavailableGetterFee(){super("Orientation getter fee premise unavailable");}
    }
    private BPhysicalPublicationMetadata(BPhysicalWork w,AttemptMemoryLedger.Owner r,CancellationProbe p){work=w;root=r;probe=p;}
    static BPhysicalPublicationMetadata open(BPhysicalWork work,AttemptMemoryLedger.Owner root,CancellationProbe probe){
        if(work==null)throw new IllegalArgumentException("Metadata attempt required");
        if(work.tableRegistry()==null)work.claimTableRegistry();
        work.prepayTableBody(ENTRY); //own fixed entry/default/claim/throw union before getters/new
        if(root!=work.owner()||probe!=work.probe())throw new IllegalArgumentException("Same metadata owner and probe required");
        work.sourceWork().metadata(COMPILER_BYTES);
        return new BPhysicalPublicationMetadata(work,root,probe);
    }
    private void live(){work.prepayTableBody(256);if(closed||root.isClosed())throw new IllegalStateException("Metadata compiler closed");}
    private void poll(){work.poll();live();}
    Plan published(){live();if(published==null)throw new IllegalStateException("No completed metadata DATA");return published;}
    Plan compileUnit(Input input){return compile(input,null);}

    /** Called only by the table-owned pre-K entry with its actual private graph. */
    Plan compileTable(BPhysicalPairTable table,String[] globals){
        if(table==null)throw new IllegalArgumentException("Actual table producer required");
        table.issueMetadataSource(this);
        Plan plan=compile(table.metadataInput(this,globals),table);
        table.attachMetadataPlan(plan);return plan;
    }
    void requireWork(BPhysicalWork expected){live();if(work!=expected)throw new IllegalArgumentException("Foreign metadata attempt");}
    private Plan compile(Input in,BPhysicalPairTable table){
        live();if(used)throw new IllegalStateException("Metadata compilation attempted once");used=true;
        work.prepayTableBody(ENTRY);
        int globalCount=table==null?24:SOURCE_GLOBALS,fixedBytes=table==null?561:SOURCE_FIXED;
        if(in==null||in.graph()==null||in.modeCounts()==null||in.modeTexts()==null||in.provenanceCounts()==null
                ||in.rectangles()==null||in.rings()==null||in.globals()==null||in.globals().length!=globalCount
                ||in.width()<2||in.height()<2||in.analysisWidth()<2||in.analysisHeight()<2||in.points()<2||in.points()>(2L<<30)/24
                ||in.graph().length<1||in.graph().length>81||in.modeCounts().length!=in.graph().length
                ||in.modeTexts().length>8192||in.provenanceCounts().length!=in.modeTexts().length
                ||in.rectangles().length!=4L*in.modeTexts().length||in.rings().length>256||in.sourceRings()<1||in.sourceRings()>in.rings().length)
            throw new IllegalArgumentException("Complete original metadata shape required");
        int n=in.graph().length,d=in.modeTexts().length,occurrences=Math.toIntExact(add(globalCount,mul(5,d)));
        long inputBytes=add(array(n,8),add(array(n,4),add(array(d,4),add(array(4L*d,4),
            add(array(in.rings().length,4),array(occurrences,8))))));
        work.prepayTableBody(add(mul(PROFILE,n),add(mul(MODE,d),mul(TEXT,occurrences))));
        work.sourceWork().metadata(inputBytes); //all input copies BEFORE allocation
        var graph=in.graph().clone();var modes=in.modeCounts().clone();var provenance=in.provenanceCounts().clone();
        var rectangles=in.rectangles().clone();var rings=in.rings().clone();String[] texts=new String[occurrences];
        for(int j=0;j<globalCount;j++){poll();texts[j]=in.globals()[j];}
        long originalModes=0,states=0,components=0,pairs=0,receipts=0,q=0,ringVertices=0,characters=0;
        for(int row=0;row<n;row++){
            poll();if(graph[row]==null||graph[row].getClass()!=InferenceProfile.class||graph[row].cells().isEmpty()||graph[row].cells().size()>96||modes[row]<0)
                throw new IllegalArgumentException("Complete original profile/state shape");
            states=add(states,graph[row].cells().size());components=add(components,graph[row].componentCount());
            originalModes=add(originalModes,modes[row]);
            if(row>0)pairs=add(pairs,mul(graph[row-1].cells().size(),graph[row].cells().size()));
        }
        if(originalModes!=d||pairs>8_000_000||states>8192)throw new IllegalArgumentException("Original modes/pairs mismatch");
        //Validate ALL profiles and query Strings before the FIRST map lookup.
        //No map/key scan or cells==branches assumption;maps are immutable copies.
        for(var profile:graph){
            work.prepayTableBody(MAP_BOUND);int branches=profile.maximumOrientationBranchCount();
            if(branches<0||branches>8192)throw new UnavailableGetterFee();
            work.prepayTableBody(mul(QUERY_GUARD,profile.cells().size()));
            for(int state=0;state<profile.cells().size();state++){
                poll();int length=profile.cells().get(state).branchLabel().length();
                if(length<1||length>8192)throw new UnavailableGetterFee();
            }
        }
        for(int m=0;m<d;m++){
            poll();var row=in.modeTexts()[m];
            if(row==null||row.length!=5||provenance[m]<1||provenance[m]>6)throw new IllegalArgumentException("Complete correlation receipt/text union");
            for(int t=0;t<3;t++)texts[globalCount+3*m+t]=row[t];
            for(int t=0;t<2;t++)texts[globalCount+3*d+2*m+t]=row[3+t];
            receipts=add(receipts,provenance[m]);
            int x0=rectangles[4*m],x1=rectangles[4*m+1],y0=rectangles[4*m+2],y1=rectangles[4*m+3];
            if(x0==0&&x1==-1&&y0==0&&y1==-1){/*only a supplied receipt-proved canonical empty*/}
            else {if(x0<0||y0<0||x1<x0||y1<y0||x1>=in.analysisWidth()||y1>=in.analysisHeight())throw new IllegalArgumentException("Original analysis rectangle extent");
                q=add(q,mul((long)x1-x0+1,(long)y1-y0+1));}
        }
        long sourceEdges=0;for(int i=0;i<rings.length;i++){poll();int r=rings[i];if(r<3)throw new IllegalArgumentException("Complete source/decision rings");
            ringVertices=add(ringVertices,r);if(i<in.sourceRings())sourceEdges=add(sourceEdges,r);}
        for(int k=0;k<texts.length;k++){
            poll();String s=texts[k];if(s==null){if(k<18||k>20)throw new PendingMetadata();}
            else {if(s.length()<1||s.length()>8192)throw new IllegalArgumentException("Bounded mandatory text");characters=add(characters,s.length());}
        }
        if(!(table==null?SCHEMA:SOURCE_SCHEMA).equals(texts[0])||!"TABLE_ONLY".equals(texts[1])||!BPhysicalPairTable.APPROXIMATION_ID.equals(texts[23]))
            throw new IllegalArgumentException("Distinct current source/schema/approximation required");
        long scanFee=add(mul(CHAR,characters),add(mul(STATE,states),mul(ROLE,24)));
        work.prepayTableBody(scanFee); //all character/state/range loops and failed prefixes
        long textBytes=0;
        for(String s:texts){poll();textBytes=add(textBytes,s==null?17:add(19,mutfSize(s)));}
        long preGraph=array(states,4);work.sourceWork().metadata(preGraph);int[] cellBytes=new int[Math.toIntExact(states)];
        int at=0,graphCount=0,inChunk=0,chunkBytes=8;long graphBytes=0,angular=0;
        //Only sizes are read: no unary/R query, point construction or integration.
        for(var profile:graph){
            //Repeated count read is separately paid;no hidden cache/field/array.
            work.prepayTableBody(MAP_BOUND);int branches=profile.maximumOrientationBranchCount();
            work.prepayTableBody(LOOKUP_PRODUCER);
            work.prepayTableBody(mul(profile.cells().size(),orientationLookupUnits(branches)));
            for(int state=0;state<profile.cells().size();state++){
            poll();var orientation=profile.orientationSupport(state);
            //The original inline CSG1 strings have separate bounded scan parents.
            work.prepayTableBody(mul(CHAR,add(profile.ownership().name().length(),
                add(profile.cells().get(state).branchLabel().length(),orientation.status().name().length()))));
            angular=add(angular,orientation.modes().size());
            long size=add(128,add(mutfSize(profile.ownership().name()),add(mutfSize(profile.cells().get(state).branchLabel()),
                add(mutfSize(orientation.status().name()),add(mul(32,orientation.modes().size()),mul(8,profile.componentCount()))))));
            if(size>65524)throw new IllegalArgumentException("Original CSG1 whole cell bound");
            if(inChunk==64||chunkBytes+4+size>LEAF){graphCount++;graphBytes=add(graphBytes,chunkBytes);chunkBytes=8;inChunk=0;}
            cellBytes[at++]=Math.toIntExact(size);chunkBytes=Math.toIntExact(add(chunkBytes,add(4,size)));inChunk++;
        }}
        if(inChunk>0){graphCount++;graphBytes=add(graphBytes,chunkBytes);}
        if(graphCount>128)throw new IllegalArgumentException("Original CSG1 chunk guard");
        long metadataBytes=add(fixedBytes,add(24*24,add(mul(4,rings.length),add(237*8+8,textBytes))));
        long basePlan=add(PLAN_BYTES,add(mul(2,array(24,8)),add(array(24,4),add(array(graphCount,4),
            add(array(LEAF,1),add(2*48,add(array(fixedBytes,1),array(237,8))))))));
        work.prepayTableBody(3); //new parameter-emission flag default BEFORE Plan construction
        work.sourceWork().metadata(basePlan); //Plan/defaults/arrays/writer scratch BEFORE new
        long[] records=new long[24],bytes=new long[24];int[] chunks=new int[24],graphCounts=new int[graphCount];
        records[0]=mul(in.width(),in.height());records[1]=mul(in.width()-1L,in.height()-1L);records[2]=d;
        records[3]=add(in.points(),ringVertices);records[4]=n;records[5]=mul(20,d);records[6]=receipts;records[7]=textBytes;
        records[8]=q;records[14]=pairs;records[15]=mul(in.analysisWidth(),in.analysisHeight());records[22]=metadataBytes;records[23]=states;
        if(records[0]>191401||mul(in.analysisWidth(),in.analysisHeight())>191401)throw new IllegalArgumentException("Original raster bound");
        long leaves=0,total=0;
        for(int role=0;role<23;role++){
            poll();long count=records[role],per=DATA/BPhysicalPublicationShape.stride(role);
            chunks[role]=Math.toIntExact(count==0?0:1+(count-1)/per);
            bytes[role]=add(mul(count,BPhysicalPublicationShape.stride(role)),mul(8,chunks[role]));
            leaves=add(leaves,chunks[role]);total=add(total,bytes[role]);
        }
        chunks[23]=graphCount;bytes[23]=graphBytes;leaves=add(leaves,graphCount);total=add(total,graphBytes);
        long inv=leaves==0?0:1+(leaves-1)/1023,cacheSlots=add(leaves,inv);
        total=add(total,add(mul(64,leaves),add(mul(8,inv),88)));
        if(total>2L<<30)throw new IllegalArgumentException("Complete DATA/INV/receipt aggregate bound");
        long cacheBytes=array(mul(32,cacheSlots),1);
        work.prepayTableBody(add(mul(8,mul(32,cacheSlots)),mul(64,add(metadataBytes,textBytes))));
        work.sourceWork().metadata(cacheBytes); //complete32F cache in SAME holder BEFORE K/allocation
        byte[] cache=new byte[Math.toIntExact(mul(32,cacheSlots))],workspace=new byte[LEAF];
        int chunk=0,usedBytes=8,usedCells=0;
        for(int size:cellBytes){poll();if(usedCells==64||usedBytes+4+size>LEAF){graphCounts[chunk++]=usedCells;usedCells=0;usedBytes=8;}
            usedCells++;usedBytes+=4+size;}
        if(usedCells>0)graphCounts[chunk]=usedCells;
        long sourceRaw=add(11128,add(mul(969,sourceEdges),add(mul(42,in.sourceRings()),mul(342,records[0]))));
        long sourcePolls=add(1585,add(mul(122,sourceEdges),add(mul(2,in.sourceRings()),mul(38,records[0]))));
        long sourceBody=add(sourceRaw,add(mul(41,sourcePolls),add(4*66,add(mul(10*66,records[0]),mul(4*76,sourceEdges)))));
        long pointCost=add(BPhysicalWork.POINT_COUPLING_UNITS+BPhysicalWork.EXACT_GEOMETRY_UNITS+136754+11*73,mul(9,sourceBody));
        long pointParents=add(q,mul(50,mul(d,records[15])));
        long contextBody=add(410049+BPhysicalWork.GENERAL_CONTEXT_FIXED,mul(73400+BPhysicalWork.GENERAL_CONTEXT_PER_DESCRIPTOR,d));
        long numerical=add(mul(pointParents,pointCost),add(BPhysicalWork.CONTEXT_JOIN_UNITS+BPhysicalWork.CONTEXT_SETUP_UNITS,mul(records[15],contextBody)));
        long numericalHelpers=add(mul(230,numerical),4845);add(numerical,numericalHelpers);
        poll();Plan plan=new Plan(this,table,texts,records,bytes,chunks,rings,cellBytes,graphCounts,cache,workspace,
            textBytes,metadataBytes,leaves,inv,total,add(inputBytes,add(preGraph,add(basePlan,cacheBytes))),components,angular,
            in.points(),pointCost,numerical,numericalHelpers,
            in.width(),in.height(),in.analysisWidth(),in.analysisHeight());
        published=plan;return plan; //no callback between final liveness and publication
    }
    private long mutfSize(String text){
        long size=0;for(int i=0;i<text.length();i++){poll();int c=text.charAt(i);size=add(size,c==0?2:c<=127?1:c<=2047?2:3);}
        if(size>65535)throw new IllegalArgumentException("Native writeUTF extent");return size;
    }
    static long checkedTotal(long a,long b){return add(a,b);}
    static long orientationLookupUnits(int branches){
        if(branches<0||branches>8192)throw new UnavailableGetterFee();
        //F0 single-lookup subset:query hash8192;<=2B comparison/node parents,
        //each8192UTF16+onecomparisonAPI+onenode. Fixed128=8API slots*8,
        //32operand/control+16return/failure+16caller/source dispatch.
        return add(8192+128,mul(2L*branches,8192+2));
    }
    private static long add(long a,long b){if(a<0||b<0)throw new IllegalArgumentException("Negative metadata count");return Math.addExact(a,b);}
    private static long mul(long a,long b){if(a<0||b<0)throw new IllegalArgumentException("Negative metadata count");return Math.multiplyExact(a,b);}
    private static long array(long n,int width){return AttemptMemoryLedger.arrayBytes(n,width);}
    @Override public void close(){closed=true;if(published!=null)published.closed=true;}

    static final class Plan implements AutoCloseable {
        private final BPhysicalPublicationMetadata compiler;
        private final BPhysicalPairTable table;
        private final String[] texts;
        private final long[] records,bytes;
        private final int[] chunks,rings,cellBytes,graphCounts;
        private final byte[] cache,workspace;
        private final long textBytes,metadataBytes,leaves,inventories,total,payload,components,angular,sourcePoints,pointCost,numerical,numericalHelpers;
        private final int width,height,analysisWidth,analysisHeight;
        private boolean closed,textWritten,metadataWritten,parametersWritten;
        private Plan(BPhysicalPublicationMetadata c,BPhysicalPairTable t,String[] tx,long[] r,long[] b,int[] ch,int[] rings,
                int[] cells,int[] gc,byte[] cache,byte[] workspace,long tb,long mb,long l,long inv,long total,long payload,long components,long angular,
                long sourcePoints,long pointCost,long numerical,long numericalHelpers,
                int width,int height,int aw,int ah){
            compiler=c;table=t;texts=tx;records=r;bytes=b;chunks=ch;this.rings=rings;cellBytes=cells;graphCounts=gc;
            this.cache=cache;this.workspace=workspace;textBytes=tb;metadataBytes=mb;leaves=l;inventories=inv;this.total=total;this.payload=payload;
            this.components=components;this.angular=angular;
            this.sourcePoints=sourcePoints;this.pointCost=pointCost;this.numerical=numerical;this.numericalHelpers=numericalHelpers;
            this.width=width;this.height=height;analysisWidth=aw;analysisHeight=ah;
        }
        private void live(){compiler.live();if(closed)throw new IllegalStateException("Metadata DATA closed");if(table!=null)table.requireMetadataLive(this);}
        void requireTable(BPhysicalPairTable value){if(table!=value)throw new IllegalArgumentException("Foreign metadata graph producer");}
        String scope(){live();return table==null?"DECLARED_UNIT_DATA":"SOURCE_BOUND_TYPED_FIELDS_PENDING_METADATA_DATA";}
        boolean factualAuthorityAvailable(){live();return false;}
        boolean runtimeQualified(){live();return false;}
        /** Actual raw64 wire field from the privately issued source snapshot.
         * Deferred/unnamed fields refuse;no caller frame supplies these values. */
        long fixedSourceRaw64(int byteOffset){live();if(table==null)throw new PendingMetadata();return table.metadataRaw64(this,byteOffset);}
        /** Eight native getters in record order;not the unnamed three-slot ABI. */
        long nativeParameterRaw64(int ordinal){live();if(table==null)throw new PendingMetadata();return table.metadataParameterRaw64(this,ordinal);}
        long localizationRaw64(int ordinal){live();if(table==null)throw new PendingMetadata();return table.metadataLocalizationRaw64(this,ordinal);}
        int localizationInt(int ordinal){live();if(table==null)throw new PendingMetadata();return table.metadataLocalizationInt(this,ordinal);}
        EvidenceModelParameters originalParameters(){live();if(table==null)throw new PendingMetadata();return table.metadataParameters(this);}
        int fixedHeaderBytes(){live();return table==null?561:SOURCE_FIXED;}
        private int globalFields(){return table==null?24:SOURCE_GLOBALS;}
        int globalCount(){live();return globalFields();}
        /** Actual parameter DATA section only,not a full source/runtime export.
         * Once,with its own funded cursor/defaults/control before allocation. */
        void writeParameterData(OutputStream out)throws IOException{
            try{compiler.poll();live();if(table==null)throw new PendingMetadata();}
            catch(PendingMetadata pending){throw pending;}
            catch(RuntimeException|Error failure){invalidate();throw failure;}
            if(parametersWritten)throw new IllegalStateException("Actual parameter DATA once");parametersWritten=true;
            try{
                compiler.work.prepayTableBody(512+210*32); //getters prepay separately,never charged twice
                compiler.work.sourceWork().metadata(48); //fallible claim included in the primary-preserving failure union
                Cursor cursor=new Cursor(this,out);
                writeParameterSection(cursor);
                cursor.flush();if(cursor.written!=210)throw new IllegalStateException("Actual parameter section extent");
            }catch(IOException|RuntimeException|Error failure){invalidate();throw failure;}
        }
        /** No genuine identity/PRECOMMIT producer exists yet. Refuse BEFORE
         * cursor allocation,caller stream invocation or partial fixed bytes. */
        void writeActualMetadata(OutputStream out)throws IOException{
            try{compiler.poll();live();if(table==null)throw new PendingMetadata();table.requireActualMetadata(this);}
            catch(PendingMetadata pending){throw pending;}
            catch(RuntimeException|Error failure){invalidate();throw failure;}
            //The genuine identity/PRECOMMIT issuer above is still absent.
            //When it exists,this is the source-only encoder,never caller561B.
            if(metadataWritten)throw new IllegalStateException("Actual metadata emitted once");metadataWritten=true;
            try{Cursor cursor=new Cursor(this,out);writeSourceFixed(cursor);
                for(int role=0;role<24;role++){cursor.word(role,4);cursor.word(chunks[role],4);cursor.word(records[role],8);cursor.word(bytes[role],8);}
                for(int count:rings)cursor.word(count,4);
                for(int i=0;i<237;i++)cursor.word(table.metadataPrefix(this,i),8);
                cursor.word(18,4);cursor.word(19,4);writeTexts(cursor);cursor.flush();
                table.requireActualMetadata(this); //same issuer/image/lifetime after final callback
                if(cursor.written!=metadataBytes)throw new IllegalStateException("Actual metadata extent");
            }catch(IOException|RuntimeException|Error failure){invalidate();throw failure;}
        }
        private void writeSourceFixed(Cursor cursor)throws IOException{
            cursor.word(0x504d5331,4);cursor.word(SOURCE_VERSION,4);
            cursor.word(1,4);cursor.word(1,4);cursor.word(0,4);cursor.word(0,4);
            for(int i=0;i<12;i++)cursor.byteValue(i==0||i==1||i==2||i==4||i==5?1:0);
            cursor.word(width,4);cursor.word(height,4);
            for(int at=44;at<=84;at+=8)cursor.word(fixedSourceRaw64(at),8);
            cursor.word(analysisWidth,4);cursor.word(analysisHeight,4);
            for(int at=100;at<=148;at+=8)cursor.word(fixedSourceRaw64(at),8);
            cursor.word(fixedSourceRaw64(156),8);writeParameterSection(cursor);
            cursor.word(sourcePoints,8);cursor.word(rings.length,8);cursor.word(records[3]-sourcePoints,8);
            for(int i=0;i<9;i++)cursor.word(switch(i){case 0->96;case 1,8->8_000_000;case 2->128_000_000;
                case 3->64L<<20;case 4->300_000_000_000L;case 5->360_000_000_000L;default->1;},8);
            for(int i=0;i<10;i++)cursor.word(switch(i){case 0->records[4];case 1->records[23];case 2->records[2];
                case 3->angular;case 4->components;case 5->records[14];case 6->sourcePoints;case 7->records[0];
                case 8->records[1];default->fixedSourceRaw64(542);},8);
            cursor.word(1,4);cursor.word(1,4);cursor.word(fixedSourceRaw64(558),8);cursor.word(fixedSourceRaw64(566),8);
            cursor.word(0x7ff8000000000000L,8);cursor.word(0,8);cursor.word(0,8); //NOT_RUN solver values
            cursor.word(table.metadataPrefix(this,27),8);cursor.byteValue((int)table.metadataPrefix(this,126));
            for(int i=0;i<6;i++)cursor.word(0x7ff8000000000000L,8);
            cursor.word(0,4);cursor.byteValue(0);for(int i=0;i<4;i++)cursor.word(0,4);
            cursor.word(records[8],8);cursor.word(mul(50,mul(records[2],records[15])),8);
            cursor.word(0,4);cursor.word(1,4);for(int i=0;i<3;i++)cursor.word(0,4);
            for(int i=0;i<3;i++)cursor.byteValue(0);cursor.word(0,4);cursor.word(0,4);cursor.word(-1,4);cursor.word(24,4);
            if(cursor.written!=SOURCE_FIXED)throw new IllegalStateException("Source fixed header extent");
        }
        private void writeParameterSection(Cursor cursor)throws IOException{
            cursor.word(1,4);cursor.word(fixedSourceRaw64(168),8);
            cursor.byteValue(0);cursor.byteValue(4);cursor.word(-1,4); //NOT_SELECTED,not selected eta0
            cursor.word(0,8);cursor.word(0x3fd0000000000000L,8);cursor.word(0x3ff0000000000000L,8);cursor.word(0x4010000000000000L,8);
            for(int i=0;i<8;i++)cursor.word(nativeParameterRaw64(i),8);
            for(int i=0;i<2;i++)cursor.word(localizationInt(i),4);
            for(int i=0;i<11;i++)cursor.word(localizationRaw64(i),8);
        }
        void requireRuntimeAuthority(){live();throw new UnsupportedOperationException("Actual source/protocol/runtime/export admission unavailable");}
        int roleCount(){live();return 24;}
        int textOccurrences(){live();return texts.length;}
        long records(int role){live();if(role<0||role>=24)throw new IllegalArgumentException("Metadata role");return records[role];}
        String availability(int role){records(role);return role==9||role>=10&&role<=13||role>=16&&role<=21?"NOT_RUN":"AVAILABLE_DATA";}
        long result(int role){if(!availability(role).equals("AVAILABLE_DATA"))throw new IllegalStateException("Result role NOT_RUN");return records[role];}
        String global(int field){live();if(field<0||field>=globalFields())throw new IllegalArgumentException("Global field");return texts[field];}
        long dataLeaves(){live();return leaves;}long inventoryLeaves(){live();return inventories;}
        long cacheSlots(){live();return leaves+inventories;}long cachePayload(){live();return array(cache.length,1);}
        long payload(){live();return payload;}int jointKSelections(){live();return table==null?0:table.metadataKSelections(this);}
        long pointQueries(){live();return add(records[8],mul(50,mul(records[2],records[15])));}
        long sourceTaps(){return mul(9,pointQueries());}
        long numericalChildUpper(){live();return add(numerical,numericalHelpers);}
        long pointCost(){live();return pointCost;}
        void writeText(OutputStream out)throws IOException{
            live();if(textWritten)throw new IllegalStateException("Text DATA emitted once");textWritten=true;
            try{Cursor cursor=new Cursor(this,out);writeTexts(cursor);cursor.flush();if(cursor.written!=textBytes)throw new IllegalStateException("Complete text extent");}
            catch(IOException|RuntimeException|Error failure){invalidate();throw failure;}
        }
        void writeMetadata(byte[] fixed,long[] prefix,OutputStream out)throws IOException{
            live();if(table!=null)throw new PendingMetadata(); //No actual writer attempt/bytes from a declared frame.
            if(metadataWritten)throw new IllegalStateException("Metadata DATA emitted once");metadataWritten=true;
            try{
            if(fixed==null||fixed.length!=561||prefix==null||prefix.length!=237)throw new PendingMetadata();
            byte[] values=fixed.clone();long[] image=prefix.clone();
            validateFixed(values);
            Cursor cursor=new Cursor(this,out);for(byte value:values)cursor.byteValue(value);
            for(int role=0;role<24;role++){cursor.word(role,4);cursor.word(chunks[role],4);cursor.word(records[role],8);cursor.word(bytes[role],8);}
            for(int count:rings)cursor.word(count,4);for(long raw:image)cursor.word(raw,8);
            cursor.word(18,4);cursor.word(19,4);writeTexts(cursor);cursor.flush();
            if(cursor.written!=metadataBytes)throw new IllegalStateException("Complete metadata extent");
            }catch(IOException|RuntimeException|Error failure){invalidate();throw failure;}
        }
        private void invalidate(){closed=true;compiler.published=null;}
        /** DATA shape validation, never authentication of supplied fixed source facts. */
        private void validateFixed(byte[] v){
            compiler.work.prepayTableBody(ENTRY);
            if(integer(v,0)!=0x504d5331||integer(v,4)!=VERSION||integer(v,8)!=1||integer(v,12)!=1||integer(v,16)!=0||integer(v,20)!=0
                ||integer(v,36)!=width||integer(v,40)!=height||integer(v,92)!=analysisWidth||integer(v,96)!=analysisHeight
                ||integer(v,557)!=24)throw new IllegalArgumentException("Live-v3 fixed metadata source/schema mismatch");
            for(int i=0;i<12;i++){compiler.poll();int expected=i==1||i==2||i==4||i==5?1:0;
                if(i==0){if(v[24+i]!=0&&v[24+i]!=1)throw new IllegalArgumentException("PREFIX availability");}
                else if(v[24+i]!=expected)throw new IllegalArgumentException("TABLE_ONLY disposition");}
            if(raw(v,204)!=sourcePoints||raw(v,212)!=rings.length||raw(v,220)!=records[3]-sourcePoints)
                throw new IllegalArgumentException("Original path/ring join");
            //No array allocation:each literal is an original unchanged cap.
            for(int i=0;i<9;i++){compiler.poll();long cap=switch(i){case 0->96;case 1,8->8_000_000;case 2->128_000_000;
                case 3->64L<<20;case 4->300_000_000_000L;case 5->360_000_000_000L;default->1;};
                if(raw(v,228+8*i)!=cap)throw new IllegalArgumentException("Original cap mismatch");}
            for(int i=0;i<10;i++){compiler.poll();long count=switch(i){case 0->records[4];case 1->records[23];case 2->records[2];
                case 3->angular;case 4->components;case 5->records[14];case 6->sourcePoints;case 7->records[0];case 8->records[1];default->raw(v,372);};
                if(raw(v,300+8*i)!=count||i==9&&count<=0)throw new IllegalArgumentException("Original cardinality mismatch");}
            if(raw(v,506)!=records[8]||raw(v,514)!=mul(50,mul(records[2],records[15]))||integer(v,485)!=0||v[489]!=0
                ||integer(v,522)!=0||integer(v,526)!=1||integer(v,553)!=-1||raw(v,545)!=0)
                throw new IllegalArgumentException("TABLE_ONLY result/count policy");
            for(int at=490;at<506;at+=4){compiler.poll();if(integer(v,at)!=0)throw new IllegalArgumentException("Marginal NOT_RUN");}
            for(int at=530;at<542;at+=4){compiler.poll();if(integer(v,at)!=0)throw new IllegalArgumentException("Completion NOT_RUN");}
            for(int at=542;at<545;at++){compiler.poll();if(v[at]!=0)throw new IllegalArgumentException("Completion flag NOT_RUN");}
            if(v[436]!=0&&v[436]!=1)throw new IllegalArgumentException("Geometry availability");
            //Unavailable binary64 slots retain raw NaNs, never measured zero.
            if(!Double.isNaN(Double.longBitsToDouble(raw(v,404))))throw new IllegalArgumentException("Partition NOT_RUN");
            for(int at=437;at<485;at+=8){compiler.poll();if(!Double.isNaN(Double.longBitsToDouble(raw(v,at))))throw new IllegalArgumentException("Native gap NOT_RUN");}
        }
        private static long raw(byte[] v,int at){long n=0;for(int i=0;i<8;i++)n=(n<<8)|(v[at+i]&255L);return n;}
        private static int integer(byte[] bytes,int at){return (bytes[at]&255)<<24|(bytes[at+1]&255)<<16|(bytes[at+2]&255)<<8|bytes[at+3]&255;}
        private void writeTexts(Cursor cursor)throws IOException{
            int d=Math.toIntExact(records[2]),globals=globalFields();
            for(int ordinal=0;ordinal<texts.length;ordinal++){
                compiler.poll();live();int kind,index,field;
                if(ordinal<globals){kind=0;index=0;field=ordinal;}
                else if(ordinal<globals+3*d){int at=ordinal-globals;kind=1+at%3;index=at/3;field=0;}
                else {int at=ordinal-globals-3*d;kind=4+at%2;index=at/2;field=0;}
                cursor.word(kind,4);cursor.word(index,4);cursor.word(field,4);cursor.word(ordinal,4);
                String text=texts[ordinal];cursor.byteValue(text==null?0:1);
                if(text!=null){cursor.word(compiler.mutfSize(text),2);
                    for(int i=0;i<text.length();i++){compiler.poll();int ch=text.charAt(i);
                        if(ch>0&&ch<=127)cursor.byteValue(ch);
                        else if(ch<=2047){cursor.byteValue(192|(ch>>6));cursor.byteValue(128|(ch&63));}
                        else {cursor.byteValue(224|(ch>>12));cursor.byteValue(128|((ch>>6)&63));cursor.byteValue(128|(ch&63));}}
                }
            }
        }
        @Override public void close(){closed=true;if(table!=null)table.close();}
    }
    private static final class Cursor {
        private final Plan plan;private final OutputStream out;private int position;private long written;
        Cursor(Plan plan,OutputStream out){if(out==null)throw new IllegalArgumentException("DATA sink required");this.plan=plan;this.out=out;}
        void byteValue(int value)throws IOException{plan.compiler.poll();plan.live();if(position==plan.workspace.length)flush();plan.workspace[position++]=(byte)value;written++;}
        void word(long value,int bytes)throws IOException{for(int shift=(bytes-1)*8;shift>=0;shift-=8)byteValue((int)(value>>>shift));}
        void flush()throws IOException{for(int offset=0;offset<position;){plan.compiler.poll();plan.live();int n=Math.min(8192,position-offset);
            out.write(plan.workspace,offset,n);plan.compiler.poll();plan.live();offset+=n;}position=0;}
    }
}
