package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CancellationException;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Actual GNU UNIT control mechanics;no clock/source/runtime issuer exists here. */
final class BSharedOwnedCaptureProbe implements CancellationProbe {
    static final long MAGIC=0x42474e5544303031L,MAX_DURATION=300_000_000_000L;
    static final int FRAME_BYTES=40,VERSION=1;
    static final long CONTEXT_BYTES=72,FAILURE_BYTES=64+16+16,PROBE_BYTES=48;
    static final long CONTEXT_PAYLOAD=CONTEXT_BYTES+FAILURE_BYTES,PROBE_PAYLOAD=PROBE_BYTES+FAILURE_BYTES;
    //context8+cached8+deadline8+two flags2 =>O26=48. Context owns original root.
    private final Context context;
    private final Stop failure;
    private final long deadline;
    private volatile boolean requested;
    private boolean terminal;
    private BSharedOwnedCaptureProbe(Context context){
        this.context=context;deadline=context.deadline;failure=new Stop();
    }
    private static final class Stop extends CancellationException {
        Stop(){super("Owned capture cancelled");}
        @Override public synchronized Throwable fillInStackTrace(){return this;}
    }
    private static final class BadFrame extends IllegalArgumentException {
        BadFrame(){super("Invalid or consumed owned deadline context");}
        @Override public synchronized Throwable fillInStackTrace(){return this;}
    }
    /** No callback/clock injection,listener,new thread or clearing interrupt read. */
    @Override public boolean cancelled(){
        if(terminal)return true;
        if(requested||context.owner.isClosed()||Thread.currentThread().isInterrupted()||System.nanoTime()>=deadline){
            terminal=true;return true;
        }
        return false;
    }
    @Override public void checkpoint(){if(cancelled())throw failure;}
    void requestCancelUnit(){requested=true;} //UNIT control only;not a runtime issuer.
    void requireContextUnit(Context expected){
        if(context!=expected||context.owner.isClosed())throw context.failure;
    }
    long deadlineUnit(){return deadline;}

    //owner8+failure8+permit8+three longs24+state4 =>O52=72.
    static final class Context implements AutoCloseable {
        private final AttemptMemoryLedger.Owner owner;
        private final BadFrame failure;
        private final BSharedRuntimeBootstrap.InitializationPermit permit;
        private final long pid,started,deadline;
        private int state;
        private Context(AttemptMemoryLedger.Owner owner,BadFrame failure,long pid,long started,long deadline,
                BSharedRuntimeBootstrap.InitializationPermit permit){
            this.owner=owner;this.failure=failure;this.pid=pid;this.started=started;this.deadline=deadline;this.permit=permit;
        }
        AttemptMemoryLedger.Owner rootUnit(){return owner;} //original factory cleanup,not authority
        long pidUnit(){check(owner);return pid;}
        long startedUnit(){check(owner);return started;}
        long deadlineUnit(){check(owner);return deadline;}
        void check(AttemptMemoryLedger.Owner expected){if(owner!=expected||state==3||owner.isClosed())throw failure;}
        void refuseCapture(){throw failure;}
        AttemptMemoryLedger.Owner consumeRootUnit(){check(owner);if(state!=0)throw failure;state=1;return owner;}
        void requireRuntime(BSharedRuntimeBootstrap.InitializationPermit expected){
            if(permit==null||permit!=expected)throw failure;
            permit.require();check(owner); //UNIT parse never has this private genuine reference.
        }
        @Override public void close(){state=3;owner.close();}
    }
    /** Context and rejection shell are paid before construction;input clone is temporary. */
    static Context parseUnit(AttemptMemoryLedger.Owner root,byte[] frame,long pid,long now){
        root.reserve(CONTEXT_PAYLOAD);BadFrame failure=new BadFrame();
        if(frame==null||frame.length!=FRAME_BYTES)throw failure;
        AttemptMemoryLedger.Reservation scratch=root.reserve(AttemptMemoryLedger.arrayBytes(FRAME_BYTES,1));
        Throwable primary=null;
        try{return parse(root,frame.clone(),pid,now,failure,null);}
        catch(RuntimeException|Error problem){primary=problem;throw problem;}
        finally{try{scratch.close();}catch(RuntimeException|Error secondary){if(primary==null)throw secondary;}}
    }
    /** Borrowed input is never closed. Reads at most40bytes+one EOF discriminator. */
    static Context readGnuUnit(AttemptMemoryLedger.Owner root,InputStream input,long pid)throws IOException{
        return readGnu(root,input,pid,null);
    }
    static Context readGnuRuntime(BSharedRuntimeBootstrap.InitializationPermit permit,
            AttemptMemoryLedger.Owner root,InputStream input,long pid)throws IOException{
        permit.require();return readGnu(root,input,pid,permit);
    }
    private static Context readGnu(AttemptMemoryLedger.Owner root,InputStream input,long pid,
            BSharedRuntimeBootstrap.InitializationPermit permit)throws IOException{
        root.reserve(CONTEXT_PAYLOAD);BadFrame failure=new BadFrame();
        AttemptMemoryLedger.Reservation scratch=root.reserve(AttemptMemoryLedger.arrayBytes(FRAME_BYTES,1));
        Throwable primary=null;
        try{
            byte[] bytes=new byte[FRAME_BYTES];
            for(int i=0;i<FRAME_BYTES;i++){int b=input.read();if(b<0||b>255)throw failure;bytes[i]=(byte)b;}
            if(input.read()!=-1)throw failure;
            return parse(root,bytes,pid,System.nanoTime(),failure,permit);
        }catch(IOException|RuntimeException|Error problem){primary=problem;throw problem;}
        finally{try{scratch.close();}catch(RuntimeException|Error secondary){if(primary==null)throw secondary;}}
    }
    private static Context parse(AttemptMemoryLedger.Owner root,byte[] frame,long pid,long now,BadFrame failure,
            BSharedRuntimeBootstrap.InitializationPermit permit){
        long actualPid=longAt(frame,16),start=longAt(frame,24),end=longAt(frame,32);
        if(longAt(frame,0)!=MAGIC||intAt(frame,8)!=VERSION||intAt(frame,12)!=FRAME_BYTES
                ||pid<=0||actualPid!=pid||start<0||end<=start||end-start>MAX_DURATION||now<start||now>=end)throw failure;
        if(root.isClosed())throw failure;
        return new Context(root,failure,pid,start,end,permit);
    }
    private static long longAt(byte[] bytes,int at){long n=0;for(int i=0;i<8;i++)n=(n<<8)|(bytes[at+i]&255L);return n;}
    private static int intAt(byte[] bytes,int at){int n=0;for(int i=0;i<4;i++)n=(n<<8)|(bytes[at+i]&255);return n;}
    static BSharedOwnedCaptureProbe issueUnit(Context context){
        context.check(context.owner);if(context.state!=1)throw context.failure;
        context.state=2;context.owner.reserve(PROBE_PAYLOAD);
        return new BSharedOwnedCaptureProbe(context);
    }
}
