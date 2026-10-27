package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;
import java.security.MessageDigest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/**
 * Bounded CSG1 DATA encoding shared by pre-K digest and later graph publication.
 * A chunk buffer is borrowed until next()/close(); no graph/source/runtime
 * authority follows from bytes. Generic DATA borrows an immutable indexed list;
 * the table entry additionally joins the exact privately prepared graph.
 * Cold, whole-source, transitive cadence and failure qualification stay open.
 */
final class BSharedCsg1 implements AutoCloseable {
    static final int LEAF=65536,MAX_CHUNKS=128,MAX_CELLS=64;
    static final long CONTROL_BYTES=80,BUFFER_BYTES=65552;
    private final BPhysicalWork work;
    private final BPhysicalPairTable table;
    private final List<InferenceProfile> graph;
    private final AttemptMemoryLedger.Owner owner;
    private final byte[] buffer;
    private int row,state,position,inChunk,chunks;
    private boolean closed,ready,finished;

    private BSharedCsg1(BPhysicalWork work,BPhysicalPairTable table,List<InferenceProfile> graph,
            AttemptMemoryLedger.Owner owner,byte[] buffer){
        this.work=work;this.table=table;this.graph=graph;this.owner=owner;this.buffer=buffer;
    }
    static BSharedCsg1 openData(BPhysicalWork work,List<InferenceProfile> graph){return open(work,null,graph);}
    static BSharedCsg1 openTableData(BPhysicalWork work,BPhysicalPairTable table,List<InferenceProfile> graph){
        if(table==null)throw new IllegalArgumentException("Missing private CSG1 graph");
        table.requireCanonicalGraphData(work,graph);return open(work,table,graph);
    }
    /** Table-only pre-K digest: no buffer escapes into metadata compilation/K.
     * Root payload owns temporary scratch; later export retains its holder path. */
    static String sha256PreKTableData(BPhysicalWork work,BPhysicalPairTable table,
            List<InferenceProfile> graph,MessageDigest borrowed){
        if(table==null)throw new IllegalArgumentException("Missing private pre-K graph");
        table.requirePreKCanonicalGraphData(work,graph);
        work.poll();table.requirePreKCanonicalGraphData(work,graph);
        try(var codec=open(work,table,graph,true)){
            String hash=codec.sha256SunData(borrowed,true);
            table.requirePreKCanonicalGraphData(work,graph);return hash;
        }
    }
    private static BSharedCsg1 open(BPhysicalWork work,BPhysicalPairTable table,List<InferenceProfile> graph){
        return open(work,table,graph,false);
    }
    private static BSharedCsg1 open(BPhysicalWork work,BPhysicalPairTable table,List<InferenceProfile> graph,boolean preK){
        if(work==null)throw new IllegalArgumentException("Missing CSG1 attempt");
        work.prepayTableBody(8192);
        if(graph==null||graph.isEmpty()||graph.size()>81||work.owner().limitBytes()>64L<<20)
            throw new IllegalArgumentException("Bounded original CSG1 graph required");
        //The already reserved1MiB publication holder owns scratch too: K may
        //consume all remaining64MiB payload headroom. Never charge it twice.
        work.sourceWork().metadata(CONTROL_BYTES+(preK?56:BUFFER_BYTES));
        var owner=work.owner().child("shared-csg1-chunk");
        try{
            if(preK)owner.reserve(BUFFER_BYTES);
            var bytes=new byte[LEAF];
            var result=new BSharedCsg1(work,table,graph,owner,bytes);
            work.poll();result.live();return result;
        }catch(RuntimeException|Error primary){
            try{owner.close();}catch(RuntimeException|Error secondary){/* preserve primary */}throw primary;
        }
    }
    private void live(){
        if(closed||owner.isClosed())throw new IllegalStateException("CSG1 scratch is closed");
        work.prepayTableBody(128);
        if(table!=null)table.requireCanonicalGraphData(work,graph);
    }
    void requireLive(){live();}
    boolean next(){
        return next(false);
    }
    private boolean next(boolean preK){
        try{
            live();ready=false;
            if(finished)return false;
            work.poll();position=8;inChunk=0;
            while(row<graph.size()){
                work.prepayTableBody(4096);work.poll();live();
                var profile=graph.get(row);int count=profile.cells().size();
                if(count<1||count>96||state<0||state>=count)
                    throw new IllegalArgumentException("Original CSG1 state inventory");
                work.prepayTableBody(128);
                int branches=profile.maximumOrientationBranchCount();
                work.prepayTableBody(Math.multiplyExact(2,BPhysicalPublicationMetadata.orientationLookupUnits(branches)));
                var orientation=profile.orientationSupport(state);
                int size=cellSize(profile,orientation);
                if(inChunk>0&&(inChunk==MAX_CELLS||position+4+size>LEAF))break;
                if(chunks>=MAX_CHUNKS)throw new IllegalArgumentException("CSG1 chunk inventory exceeds limit");
                work.prepayTableBody(Math.addExact(4096,Math.multiplyExact(32L,size)));
                //Exactly one native point32 and one default Double24 in the
                //orientationReliability getter; no temporary double arrays.
                if(!preK)work.sourceWork().metadata(56);
                i32(size);int start=position;
                cell(profile,orientation);
                if(position-start!=size)throw new IllegalStateException("CSG1 cell extent changed");
                inChunk++;state++;
                if(state==count){row++;state=0;}
            }
            if(inChunk==0){finished=true;position=0;return false;}
            header(0,0x43534731);header(4,inChunk);
            work.poll();live();chunks++;ready=true;return true;
        }catch(RuntimeException|Error primary){
            try{close();}catch(RuntimeException|Error secondary){/* preserve primary */}throw primary;
        }
    }
    /**
     * Consume this fresh encoder with an exclusively borrowed initialized SUN
     * engine. The borrower retains/funds its552B body including lazy W. This
     * defensive DATA profile check does not issue runtime/provider authority.
     * Registered control/array-slice work is partial: SHA/native/lookup/cold/
     * ordinary-failure source prices remain explicitly unqualified.
     */
    String sha256SunData(MessageDigest sha){
        return sha256SunData(sha,false);
    }
    private String sha256SunData(MessageDigest sha,boolean preK){
        try{
            live();work.prepayTableBody(8192);
            if(row!=0||state!=0||chunks!=0||ready||finished)
                throw new IllegalStateException("CSG1 digest requires a fresh encoder");
            if(sha==null||!"SHA-256".equals(sha.getAlgorithm())||sha.getProvider()==null
                    ||!"SUN".equals(sha.getProvider().getName())
                    ||!"sun.security.provider.Sun".equals(sha.getProvider().getClass().getName())
                    ||sha.getProvider().getClass().getClassLoader()!=null
                    ||!"java.security.MessageDigest$Delegate$CloneableDelegate".equals(sha.getClass().getName())
                    ||sha.getClass().getClassLoader()!=null||sha.getDigestLength()!=32)
                throw new IllegalArgumentException("Real borrowed SUN SHA-256 DATA engine required");
            work.poll();live();sha.reset();work.poll();live();
            while(next(preK)){
                int count=length();
                //Caller/slice traversal only; not a complete SHA compression fee.
                work.prepayTableBody(512+4L*count);
                sha.update(buffer(),0,count);work.poll();live();
            }
            work.prepayTableBody(8192);
            //digest byte[32]48 + manual char[64]144 + String32 + worst
            //UTF16 byte[128]144; all normal owned output before allocation.
            work.sourceWork().metadata(48+320);
            byte[] digest=sha.digest();work.poll();live();
            if(digest.length!=32)throw new IllegalArgumentException("Unexpected SHA-256 DATA length");
            char[] chars=new char[64];String digits="0123456789abcdef";
            for(int i=0;i<32;i++){
                int value=digest[i]&255;chars[2*i]=digits.charAt(value>>>4);chars[2*i+1]=digits.charAt(value&15);
            }
            String result=new String(chars);work.poll();live();close();return result;
        }catch(RuntimeException|Error primary){
            try{close();}catch(RuntimeException|Error secondary){/* preserve primary */}throw primary;
        }
    }
    private int cellSize(InferenceProfile profile,ImageOrientationSupport orientation){
        var lateral=profile.cells().get(state);
        int modes=orientation.modes().size(),components=profile.componentCount();
        if(modes<0||modes>8192||components<0||components>8192)
            throw new IllegalArgumentException("CSG1 mode/component inventory");
        long size=128L+mutfLength(profile.ownership().name())+mutfLength(lateral.branchLabel())
            +mutfLength(orientation.status().name())+32L*modes+8L*components;
        if(size>LEAF-12)throw new IllegalArgumentException("CSG1 whole cell exceeds leaf");
        return Math.toIntExact(size);
    }
    private int mutfLength(String value){
        if(value==null||value.length()>8192)throw new IllegalArgumentException("Bounded CSG1 text required");
        work.prepayTableBody(512+128L*value.length());int length=0;
        for(int i=0;i<value.length();i++){
            if((i&63)==0)work.poll();char c=value.charAt(i);
            length+=c>=1&&c<=127?1:c<=2047?2:3;
        }
        return length;
    }
    private void cell(InferenceProfile profile,ImageOrientationSupport orientation){
        var lateral=profile.cells().get(state);var point=profile.point(state);
        i32(row);i32(state);
        f64(profile.chainageMeters());f64(profile.anchor().xMeters());f64(profile.anchor().yMeters());
        f64(profile.normalUnit().xMeters());f64(profile.normalUnit().yMeters());
        f64(lateral.offsetMeters());f64(lateral.quadratureWidthMeters());f64(point.xMeters());f64(point.yMeters());
        f64(profile.unaryCost(state));f64(profile.structuralGuideCost(state));f64(profile.orientationReliability(state));
        b1((lateral.exactAnchor()?1:0)|(lateral.mandatory()?2:0));
        utf(profile.ownership().name());b1(profile.entirelyMissing()?1:0);utf(lateral.branchLabel());
        utf(orientation.status().name());f64(orientation.certainty());i32(orientation.modes().size());
        for(int i=0;i<orientation.modes().size();i++){
            work.poll();var mode=orientation.modes().get(i);
            f64(mode.startRadians());f64(mode.endRadians());f64(mode.peakBearingRadians());f64(mode.response());
        }
        i32(profile.componentCount());
        for(int i=0;i<profile.componentCount();i++){work.poll();f64(profile.componentResponsibility(state,i));}
    }
    private void utf(String value){
        int length=mutfLength(value);b1(length>>>8);b1(length);
        work.prepayTableBody(512+128L*value.length());
        for(int i=0;i<value.length();i++){
            if((i&63)==0)work.poll();char c=value.charAt(i);
            if(c>=1&&c<=127)b1(c);
            else if(c<=2047){b1(192|(c>>>6));b1(128|(c&63));}
            else{b1(224|(c>>>12));b1(128|((c>>>6)&63));b1(128|(c&63));}
        }
    }
    private void b1(int value){
        if(position>=LEAF)throw new IllegalStateException("CSG1 buffer extent");buffer[position++]=(byte)value;
    }
    private void i32(int value){b1(value>>>24);b1(value>>>16);b1(value>>>8);b1(value);}
    private void f64(double value){long bits=Double.doubleToRawLongBits(value);for(int shift=56;shift>=0;shift-=8)b1((int)(bits>>>shift));}
    private void header(int at,int value){for(int shift=24;shift>=0;shift-=8)buffer[at++]=(byte)(value>>>shift);}
    byte[] buffer(){live();if(!ready)throw new IllegalStateException("No complete CSG1 chunk");return buffer;}
    int length(){live();if(!ready)throw new IllegalStateException("No complete CSG1 chunk");return position;}
    @Override public void close(){if(!closed){closed=true;ready=false;owner.close();}}
}
