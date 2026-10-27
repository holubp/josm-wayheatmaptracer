package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Actual reached boundaries,not stage aliases or scheduled numerical debits. */
final class BPhysicalTableRegistry {
    static final int SETUP=0,BIND=1,VIEW=2,SCALAR=3,MASK=4,DESCRIPTOR=5,REGION=6,
            TABLE=7,EDGE=8,NODE=9,MERGE=10,LOCAL=11,BASELINE=12,CODEC=13,
            DIGEST=14,UNIX=15,INVENTORY=16,ENERGY=17,SUPPORT=18,FAILURE=19;
    //Accepted R1 registered programs,not CPU instruction counts.
    static final long BEGIN=117,END=85,HOOK=36,COPY=1832+13+8,CONSTRUCTOR=1553,ISSUE=51;
    private final BPhysicalWork work;
    private final AttemptMemoryLedger.Owner owner;
    private BPhysicalPairTable table;
    private BPhysicalResponse.SourceView view;
    private BSharedWork.Counters originalPrefix,originalTerminal;
    private final long[] costUpper,eventUpper,entered,normal,thrown,prefix,terminal;
    private long used,upper,pendingCount,pendingProxy,pendingUpper;
    private int phase,pendingMode=-1,firstCause;
    private boolean sealed,frozen;
    //13refs/5longs/3ints/2flags=158/O176;arrays2512 =>2688 holder bytes.
    //Two additional reference defaults are each a literal/load/store triple.
    BPhysicalTableRegistry(BPhysicalWork work) {
        this.work=work;owner=work.owner();
        costUpper=new long[20];eventUpper=new long[20];entered=new long[20];normal=new long[20];thrown=new long[20];
        prefix=new long[100];terminal=new long[100];
        //Only bounded,actual pre-source control roles are initially enabled.
        eventUpper[SETUP]=eventUpper[BIND]=eventUpper[VIEW]=eventUpper[TABLE]=eventUpper[BASELINE]=eventUpper[FAILURE]=1;
        upper=CONSTRUCTOR+ISSUE+6*(BEGIN+END)+2*HOOK;
    }
    private void live(){work.live();if(owner.isClosed()||frozen)throw new IllegalStateException("Global registry unavailable");}
    private static int mapped(int stage) {
        return stage==3?SCALAR:stage==4?MASK:stage==5?DESCRIPTOR:stage==6?REGION:stage==17?NODE:stage==18?MERGE:-1;
    }
    void prepareStage(int stage,int outcome) {
        pendingMode=-1;live();int role=mapped(stage);
        if(role<0){prepareProxy(HOOK);pendingMode=-2;return;}
        prepare(role,outcome,HOOK);
    }
    void prepare(int role,int outcome){prepare(role,outcome,0);}
    private void prepare(int role,int outcome,long hook) {
        pendingMode=-1;live();
        if(role<0||role>=20||outcome<0||outcome>2||role==LOCAL&&eventUpper[LOCAL]==0||role==ENERGY)
            throw new IllegalStateException("Unavailable global role");
        if(outcome==0&&role!=SETUP&&role!=BIND&&role!=VIEW&&role!=FAILURE&&role!=CODEC&&role!=DIGEST&&role!=UNIX&&role!=INVENTORY) {
            if(view==null)throw new IllegalStateException("No source publication");view.ensureSourceLive();
        }
        long old=outcome==0?entered[role]:outcome==1?normal[role]:thrown[role];
        long next=Math.addExact(old,1);
        if(outcome==0) {if(next>eventUpper[role])throw new IllegalStateException("Global boundary ceiling");}
        else if(Math.addExact(next,outcome==1?thrown[role]:normal[role])>entered[role])
            throw new IllegalStateException("Unobserved global completion");
        prepareProxy(Math.addExact(outcome==0?BEGIN:END,hook));
        //The event upper is dead after its guard;reuse that pending register
        //for the checked outcome selector. No decode arithmetic at commit.
        pendingCount=next;pendingUpper=outcome;pendingMode=role;
    }
    private void prepareProxy(long units) {
        long next=Math.addExact(used,units);
        if(next>upper)throw new IllegalStateException("Global bookkeeping ceiling");
        pendingProxy=next;
    }
    //No guard,API,callback or arithmetic here:all prospective values validated.
    void commitPrepared() {
        if(pendingMode>=0){
            if(pendingUpper==0)entered[pendingMode]=pendingCount;else if(pendingUpper==1)normal[pendingMode]=pendingCount;else thrown[pendingMode]=pendingCount;
        }
        used=pendingProxy;pendingMode=-1;
    }
    void begin(int role){prepare(role,0);commitPrepared();}
    void end(int role,boolean returned){prepare(role,returned?1:2);commitPrepared();}
    void publishView(BPhysicalResponse.SourceView source) {
        live();if(view!=null||source==null)throw new IllegalStateException("Global source issue once");
        source.ensureSourceLive();work.poll();source.ensureSourceLive();live();view=source;
    }
    void registerLocal(BPhysicalPairTable.Permit permit,long q,long e,long v,long fees,long semanticUpper){
        live();permit.requireLocalSource(work,view);
        if(sealed||table!=null||eventUpper[LOCAL]!=0)throw new IllegalStateException("Local source schedule once");
        long scalar=Math.multiplyExact(36,q),mask=Math.multiplyExact(Math.multiplyExact(90,v),q);
        long descriptor=Math.multiplyExact(11,q),region=Math.multiplyExact(Math.multiplyExact(36,e),q);
        long rows=view.bound.rows.length-1;
        long events=Math.addExact(6,Math.addExact(rows,Math.addExact(scalar,Math.addExact(mask,Math.addExact(descriptor,region)))));
        long proxy=Math.addExact(used,Math.addExact(Math.multiplyExact(events,BEGIN+END),
                Math.multiplyExact(Math.addExact(Math.multiplyExact(2,semanticUpper),1),HOOK)));
        //All checked prospective arithmetic precedes callback-free stores.
        costUpper[SCALAR]=costUpper[MASK]=66;costUpper[DESCRIPTOR]=73;costUpper[REGION]=76;costUpper[LOCAL]=fees;
        eventUpper[SCALAR]=scalar;eventUpper[MASK]=mask;eventUpper[DESCRIPTOR]=descriptor;eventUpper[REGION]=region;eventUpper[LOCAL]=rows;
        upper=proxy; //Actual earlier begins/normal/throw and proxy used remain intact.
    }
    void install(BPhysicalPairTable table,long proxyUpper) {
        live();if(sealed||this.table!=null||view==null||proxyUpper<used)
            throw new IllegalStateException("Global plan once");
        table.requireRegistry(this,work,view);
        view.ensureSourceLive();
        for(int i=0;i<20;i++)if(table.roleCost(i)<0||table.roleMaximum(i)<entered[i])throw new IllegalArgumentException("Global plan bounds");
        for(int i=0;i<20;i++){costUpper[i]=table.roleCost(i);eventUpper[i]=table.roleMaximum(i);}
        this.table=table;upper=proxyUpper;sealed=true;phase=1;
    }
    long actual(int role,int outcome){live();if(role<0||role>=20||outcome<0||outcome>2)throw new IndexOutOfBoundsException();return outcome==0?entered[role]:outcome==1?normal[role]:thrown[role];}
    long maximum(int role){live();if(role<0||role>=20)throw new IndexOutOfBoundsException();return eventUpper[role];}
    boolean sourcePublished(){live();return view!=null;}
    boolean geometryAvailable(){live();return sealed;}
    void requireEngine(BPhysicalResponse.SourceView source) {
        //No callback or new source check:claimSourcePanel already checked the
        //same current source. This is only the separately prepaid cap gate.
        if(!sealed||table==null||view!=source)throw new IllegalStateException("Global numerical caps not installed");
    }
    void failure(){pendingMode=-1;live();if(firstCause==0)firstCause=1;}
    void freeze(boolean last) {
        live();long next=Math.addExact(used,COPY);if(next>upper)throw new IllegalStateException("Global cut ceiling");
        //A caller-owned PRECOMMIT opportunity,not a build-completion event.
        //TABLE success alone does not prove staging;the future wrapper owns
        //that phase. HOST may retain actual failure facts after child rollback.
        if(!last&&(table==null||normal[TABLE]!=1))throw new IllegalStateException("No completed table for owning precommit caller");
        int bit=last?4:2;if((phase&bit)!=0)throw new IllegalStateException("Original cut once");
        phase|=bit; //Consume BEFORE the fallible snapshot;failure cannot retry.
        //snapshot() itself claims exactly208 from the existing holder. No
        //predebit and no pre-K snapshot:these are the two genuine cut records.
        var original=work.sourceWork().snapshot();
        if(last)originalTerminal=original;else originalPrefix=original;
        work.freezeTableCut(this,original,last);
        long[] dst=last?terminal:prefix;
        for(int i=0;i<20;i++){dst[i]=costUpper[i];dst[20+i]=eventUpper[i];dst[40+i]=entered[i];dst[60+i]=normal[i];dst[80+i]=thrown[i];}
        used=next;
        phase|=last?16:8; //Completion ONLY after both actual frozen images/copy tail;claim stays consumed on failure.
    }
    BSharedWork.Counters original(boolean last){live();var c=last?originalTerminal:originalPrefix;
        if(c==null)throw new IllegalStateException("Original cut unavailable");return c;}
    void requireMetadataImage(BPhysicalWork issuer,boolean last){
        live();if(issuer!=work)throw new IllegalArgumentException("Foreign metadata cut issuer");
        if((phase&(last?16:8))==0)throw new IllegalStateException("Global metadata cut incomplete");
    }
    long metadataImage(BPhysicalWork issuer,boolean last,int index){
        work.prepayTableBody(BPhysicalWork.METADATA_CUT_READ);requireMetadataImage(issuer,last);
        work.requireMetadataCut(this,last);
        if(index<0||index>=100)throw new IndexOutOfBoundsException("Global metadata raw word");
        return (last?terminal:prefix)[index];
    }
}
