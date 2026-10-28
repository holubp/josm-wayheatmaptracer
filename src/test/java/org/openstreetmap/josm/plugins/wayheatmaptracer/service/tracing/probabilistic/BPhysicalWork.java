package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** F1 RESPONSE_ONLY stage,not a table/integrator/solver/codec capability. */
final class BPhysicalWork implements AutoCloseable {
    static final int SCALAR=3,MASK=4,DESCRIPTOR=5,REGION=6,SOURCE=9,COMPONENT=11,CONTEXT=12,SOURCE_PANEL=13,COMPONENT_PANEL=14,MAX_PANEL=15,ROOT_INTEGRAL=16;
    //Per log:20Y/z operators,3*3895weighted terms,860moment sums,
    //40divisions,20alternating sums,one tail. Plus tail setup,difference,scale.
    static final long ROOT_COEFFICIENT_OPS=2*(20+3*3895+860+40+20+1)+3;
    //Per log:5Y+5z+centre+5initial copies+20k+855outputs+4275attempts
    //+860moment coefficients. Plus length,first-log copy,final admission.
    static final long ROOT_LOOP_CHECKS=2*(5+5+1+5+20+855+4275+860)+3;
    //Both reviewed20-term moments plus length/gain/tail/scale/gate/root and
    //publication/cleanup interfaces. Poly.check adds two source-live73 calls
    //+32own checks; Root.check adds four source-live73+64own/loan controls.
    //Extra128*8550 funds actual
    //attempt/index/count/weight producer guards beyond frozen log program.
    static final long ROOT_SETUP=12977,ROOT_BODY=2*2470117L+199+451+2*51+15+531+149+59+43+57+16
            +ROOT_COEFFICIENT_OPS*(2*73+32)+ROOT_LOOP_CHECKS*(4*73+64)+128L*8550+2*(109+52);
    //Full context literal-weight constructor plus separate shell/caller setup.
    static final long MAX_SETUP=185080+4427+9800+12977;
    //At most30 preparations/candidate:centre,9tap classifiers,2tent endpoints,
    //18tap endpoints. Each adds8coupled transfers2248,8seeds52,128controls.
    //Additional64per old reference/component poll pays new max-frame guard.
    static final long MAX_TRANSLATED_COMPONENT=30L*(8*2248+8*52+128)+64L*(10933+2834)+4096+66;
    //Max.live:two previously registered source-live73 bodies,plus32 own
    //root/work/child/closed/reference/control slots. A candidate update has
    //5comparison checkpoints+5conditional copy checkpoints+1visit checkpoint;
    //a weighted update adds5weight checkpoints. Each initial row has5.
    static final long MAX_LIVE=2*73+32,MAX_UPDATE_CALLER=512+11*MAX_LIVE,
            MAX_WEIGHT_CALLER=512+15*MAX_LIVE+53+34,MAX_INITIAL=119+5*MAX_LIVE;
    // Separate root/component setup;full fixed constructor covers the new
    // component/poly subset,reference181 covers its12 allocations/literal seeds.
    static final long COMPONENT_PANEL_SETUP=12977+181;
    // Full reviewed component136754/reference292755;additional64 per2834
    // component polls/10933 reference-row accesses funds new private-frame and
    // coefficient caller guards. Root construction has6 transfers<=2248,
    // 6seeds52,one claim264 and64 canonical/caller controls.
    static final long ENDPOINT_SOURCE_UNITS=39L*3017;
    //New dispatch96=24 parent/table-null controls+9 false/true tap dispatches*8.
    //Included in EVERY component admission and all expanded parent ceilings.
    static final long COMPONENT_PANEL_BODY=136754+292755+64*(2834+10933)+6*2248+6*52+264+64+961L*3017+4887+96;
    // Separate source-panel construction,never unused old setup credit. Full
    // reviewed fixed-workspace constructor12977 dominates this23-allocation
    // subset;K1 node-pool setup50+41*1=91. No polynomial bank is claimed.
    //One stackless Throwable shell O44=64 plus its optional empty retained
    //trace array16 and ONE empty caller copy16. No cause/message allocation.
    //Only one fatal source escape can occur before this single-use stage dies.
    static final long SOURCE_FATAL_BYTES=64+16+16;
    //5constructor API slots40,override8,field initialization24,metadata16,
    //allocation/throw16,one lazy-reader/copy48,caller/failure controls40.
    static final long SOURCE_FATAL_SETUP=40+8+24+16+16+48+40;
    static final long PANEL_CONSTRUCTOR_UNITS=12977+91+SOURCE_FATAL_SETUP;
    // Three source-live/accessor guards73;root claim59+41*5;canonical/caller52
    // (2Double.compare API visits*8,swap operand/stores16,control12,args8);
    // six input seeds52;8bounded output/API visits*8;fixed output controls64.
    static final long PANEL_INTERFACE_UNITS=3*73+264+52+6*52+8*8+64+4*52;
    // Frozen F0 context setup28296+41*3824; exact new shells/array
    // constructors1106+41*81. Literal decoder adds49*(4*(7+41)+8).
    // A separate256 pays the fixed checked join before use.
    static final long CONTEXT_SETUP_UNITS=185080+4427+9800,CONTEXT_JOIN_UNITS=256;
    // Additional GENERAL_CONTEXT source ceiling. Approved full P/E/delta
    // transfer bounds dominate the smaller implemented singleton bank helpers:
    // add816,mul2248 (raw119/321 +41*17/47 polls). Per offset:4each,
    // 8seeds*52,2optional singleton resets*52,exact-base proof256,caller64.
    // Private same-producer guard64 at entry/after-probe/final publication,
    // 2coupled copies*(52+9operands),14adaptercontrols =>328 per component.
    // Final Context publication liveness64. All paid before first operation.
    static final long GENERAL_CONTEXT_FIXED=49*(4*(816+2248)+8*52+2*52+256+64)+64;
    static final long GENERAL_CONTEXT_PER_DESCRIPTOR=50*(3*64+2*(52+9)+14);
    // F0 registered constructor/interface programs:870+423+101+11547+36.
    // Full fixed-workspace constructor is a conservative upper for this
    // source/component-only subset,not a claim polynomial arrays were created.
    private static final long SETUP_UNITS=12977;
    //Once before any Response entry:primary init2,VIEW catch5/end7,outer
    //catch5,BIND end9,cleanup predicate delta1/handler2,dispatch+joins16.
    //Existing constructor literal seed prepays47;no new API/field/register.
    static final long RESPONSE_LIFECYCLE_UNITS=2+5+7+5+9+1+2+16;
    // RESPONSE_ONLY install preflight: fixed source/shape joins and six checked
    // formula APIs, plus charge/store/overflow branches. Paid at construction,
    // before the input-derived ceiling is itself instantiated. No self-charge.
    static final long INSTALL_BOOTSTRAP_UNITS=256;
    //NEW runtime instantiation of the accepted prefix cost polynomial:21
    //direct checked APIs+3 nested arrayBytes/alignedSize APIs,each8 units.
    //128 source slots:72 operand/result lanes,22 predicates/arithmetic,
    //16 reference/local/cast slots,18 dispatch/forward/store controls.
    //The direct prepayment registration uses the existing nonrecursive63
    //helper body;it executes BEFORE the first producer,not recursively.
    static final long PREFIX_RUNTIME_SETUP=24*8+128;
    // Additional RESPONSE_ONLY exact-geometry guard (not an old-source debit):
    // Fixed source branch-union: caller/flag32 + axes24 + normal21 + bits4
    // + exponent7 + power guards19 + halfstep5 + scalar-call control16=128;
    // four scalar tests each <=32 (loads/div/store/finite/abs/rint/mul/tests).
    // Every invocation is admitted before its first operand test; no retries.
    static final long EXACT_GEOMETRY_UNITS=128+4*32;
    // New singleton adapter, independently prepaid BEFORE source-bound own-state
    // getters/coordinate arithmetic:24 bounded lookup/getter visits*8;
    // two scalar coordinate branch unions*160;caller/flags/dispatch64.
    // Coordinate160:operand/local/fetch/store/control72,arithmetic/predicates12,
    // outward/API8,set/error finite/store52,call/return/exception controls16.
    // Existing component body pays unchanged projection/tent/filter operations.
    // Source copy set(o,a.n[i],a.lo[i],a.hi[i]) adds9 source operands vs
    // set(o,v,v,v):3 receiver+3 array-ref+3 index+3 array-read vs3 scalar loads.
    // Two copies/9 taps=>162. Context adapter/caller branch adds8 controls.
    // All are nonnegative source-unit ceilings,not CPU instruction estimates.
    static final long OWN_COORDINATE_EXACT_UNITS=2*32+48;
    static final long POINT_COUPLING_UNITS=24*8+2*160+64+9*18+8+OWN_COORDINATE_EXACT_UNITS;
    private final AttemptMemoryLedger.Owner owner;
    private final BSharedWork sourceWork;
    private final CancellationProbe probe;
    private final long[] costs,admitted,normal,thrown,stats;
    private final Image prefix,terminal;
    private Object bindingOrGeometry;
    private BPhysicalResponse.SourceView view;
    private BPhysicalTableRegistry tableRegistry;
    private long upper,semantic,helpers,helperUpper,queryLimit,queries,checkpointIntents,
            checkpointReturns,actualTableGeometry,sourceWrapperUnits,adaptiveNodeHelpers;
    private int stage,contextQueries,contextLimit;
    private boolean closed,claimed,installed,registryClaimed;
    // Header:13refs,11longs,3ints,4flags => O208=224. Two internal derived
    // coefficients are NOT observed counters. Stats9/images137 add24B and
    // Futuretable reference+once flag adds8 aligned bytes:3560B in same holder.
    private BPhysicalWork(AttemptMemoryLedger.Owner owner,BSharedWork work,CancellationProbe probe) {
        this.owner=owner;this.sourceWork=work;this.probe=probe;
        costs=new long[20];admitted=new long[20];normal=new long[20];thrown=new long[20];stats=new long[9];
        prefix=new Image(this,owner,probe);terminal=new Image(this,owner,probe);
        bindingOrGeometry=new Carrier(work,owner);stage=1;semantic=SETUP_UNITS+INSTALL_BOOTSTRAP_UNITS+5+2+RESPONSE_LIFECYCLE_UNITS;
    }
    static BPhysicalWork create(CancellationProbe probe) {
        if(probe==null)throw new NullPointerException("probe");
        var ledger=new AttemptMemoryLedger(64L<<20);var root=ledger.rootOwner();
        try {
            var work=BSharedWork.create(root,probe);
            work.metadata(3560);
            return new BPhysicalWork(root,work,probe);
        } catch(RuntimeException|Error failure){root.close();throw failure;}
    }
    /** Separately labelled partial-accounting experiment, never a runtime permit. */
    static BPhysicalWork createExploratory(CancellationProbe probe) {
        var result=create(probe);
        try{result.sourceWork.markExploratory();return result;}
        catch(RuntimeException|Error primary){try{result.close();}catch(RuntimeException|Error secondary){/* preserve primary */}throw primary;}
    }
    void requireExploratory(){if(!sourceWork().isExploratory())
        throw new IllegalArgumentException("Exploratory Work required");}
    /** Explicit UNIT deadline mechanics;this original root is retained for the one Work. */
    static BSharedOwnedCaptureProbe.Context prepareOwnedUnit(byte[] frame,long pid,long now){
        var ledger=new AttemptMemoryLedger(64L<<20);var root=ledger.rootOwner();
        try{return BSharedOwnedCaptureProbe.parseUnit(root,frame,pid,now);}
        catch(RuntimeException|Error primary){try{root.close();}catch(RuntimeException|Error secondary){ /* preserve primary */ }throw primary;}
    }
    static BSharedOwnedCaptureProbe.Context prepareOwnedGnuUnit(java.io.InputStream input,long pid)throws java.io.IOException{
        var ledger=new AttemptMemoryLedger(64L<<20);var root=ledger.rootOwner();
        try{return BSharedOwnedCaptureProbe.readGnuUnit(root,input,pid);}
        catch(java.io.IOException|RuntimeException|Error primary){try{root.close();}catch(RuntimeException|Error secondary){ /* preserve primary */ }throw primary;}
    }
    static BPhysicalWork createOwnedUnit(BSharedOwnedCaptureProbe.Context context){
        var root=context.consumeRootUnit(); //A rejected replay never owns/closes the already-transferred root.
        try{
            var probe=BSharedOwnedCaptureProbe.issueUnit(context);
            var source=BSharedWork.create(root,probe);source.metadata(3560);
            return new BPhysicalWork(root,source,probe);
        }catch(RuntimeException|Error primary){try{root.close();}catch(RuntimeException|Error secondary){ /* preserve primary */ }throw primary;}
    }
    static BPhysicalWork createOwnedRuntime(BSharedRuntimeBootstrap.InitializationPermit permit,BSharedOwnedCaptureProbe.Context context){
        permit.require();context.requireRuntime(permit);return createOwnedUnit(context);
    }
    /** No caller owner/fee/identity tuple;actual fields bind this producer once. */
    void claimOwnedCaptureInputs(BSharedOwnedCaptureProbe.Context context){
        requireOwnedCaptureContext(context);
        sourceWork.claimCaptureInputs(context);
    }
    void requireOwnedCaptureContext(BSharedOwnedCaptureProbe.Context context){
        context.check(owner);
        if(closed||sourceWork.captureOwnerClosed()||!(probe instanceof BSharedOwnedCaptureProbe))context.refuseCapture();
        ((BSharedOwnedCaptureProbe)probe).requireContextUnit(context);
    }
    static BSharedOwnedCaptureProbe.Context prepareOwnedGnuRuntime(BSharedRuntimeBootstrap.InitializationPermit permit,
            java.io.InputStream input,long pid)throws java.io.IOException{
        permit.claimOwnedAttempt();var ledger=new AttemptMemoryLedger(64L<<20);var root=ledger.rootOwner();
        try{return BSharedOwnedCaptureProbe.readGnuRuntime(permit,root,input,pid);}
        catch(java.io.IOException|RuntimeException|Error primary){try{root.close();}catch(RuntimeException|Error secondary){ /* preserve primary */ }throw primary;}
    }
    AttemptMemoryLedger.Owner owner(){sourceWork.ensureOpen();return owner;}
    CancellationProbe probe(){sourceWork.ensureOpen();return probe;}
    BSharedWork sourceWork(){sourceWork.ensureOpen();return sourceWork;}
    void fundPublicationBounds(long sourcePointsUpper) {
        live();if(claimed||registryClaimed||installed||sourcePointsUpper<2||sourcePointsUpper>(2L<<30)/24)
            throw new IllegalArgumentException("Pre-output prospective source-point upper only");
        //12 bounded constructor/interface/API slots*8+64 operand/control
        //slots,including own registration;one root-holder O16=32 control.
        semantic=Math.addExact(semantic,12*8+64);helper(63);sourceWork.metadata(32);
    }
    BPhysicalTableRegistry claimTableRegistry() {
        live();if(registryClaimed||claimed||installed)throw new IllegalStateException("Table registry before source,once");
        registryClaimed=true;
        //Accepted constructor1547+issue51+new fee interface33;charge before
        //metadata/arrays. The original nonrecursive helper remains63.
        semantic=Math.addExact(semantic,BPhysicalTableRegistry.CONSTRUCTOR+BPhysicalTableRegistry.ISSUE+33);helper(63);
        sourceWork.metadata(2688);var r=new BPhysicalTableRegistry(this);
        poll();live();tableRegistry=r;r.begin(BPhysicalTableRegistry.SETUP);r.end(BPhysicalTableRegistry.SETUP,true);return r;
    }
    BPhysicalTableRegistry tableRegistry(){live();return tableRegistry;}
    boolean defersTableBounds(){live();return tableRegistry!=null&&sourceWork.isExploratory();}
    void requireProspectiveTableBounds(){if(defersTableBounds())throw new UnsupportedOperationException("Qualified prospective table envelopes UNAVAILABLE");}
    void prepayTableBody(long units) {
        live();if(tableRegistry==null||units<0)throw new IllegalStateException("No private table body");
        long next=Math.addExact(semantic,units);
        if(!defersTableBounds()&&installed&&next>upper)throw new IllegalStateException("Table semantic ceiling");
        helper(63);semantic=next;
    }
    void installTableCaps(BPhysicalPairTable table,long semanticUpper,long stageUpper) {
        requireProspectiveTableBounds();
        liveSource();table.requireRegistry(tableRegistry,this,view);
        if(stage!=1||semanticUpper<semantic||stageUpper<helpers)throw new IllegalStateException("Global caps once");
        Math.addExact(semanticUpper,stageUpper);
        upper=semanticUpper;helperUpper=stageUpper;
    }
    void installExploratoryTable(BPhysicalPairTable table) {
        liveSource();table.requireRegistry(tableRegistry,this,view);
        if(!defersTableBounds()||stage!=1)throw new IllegalStateException("Explicit exploratory table required");
        upper=helperUpper=-1; //Private unavailable sentinels, never exported as bounds.
    }
    void claim(){live();if(claimed)throw new IllegalStateException("Physical source already claimed");claimed=true;}
    //New captured-only caller:<=32 API/constructor/failure visits*8 plus256
    //operand/control/default/store slots. Includes its nonrecursive registration.
    static final long CAPTURED_OWN_SETUP=32*8+256,CAPTURED_OWN_QUERY=256;
    void prepayCapturedOwnEntry(){
        live();if(claimed||installed||tableRegistry!=null)throw new IllegalStateException("Captured own-only entry before source,once");
        semantic=Math.addExact(semantic,CAPTURED_OWN_SETUP);helper(32);
        sourceWork.metadata(32); //OwnedLocal O15=32,BEFORE its allocation.
    }
    void prepayCapturedTableEntry(){
        live();if(claimed||installed||tableRegistry==null)throw new IllegalStateException("Captured table before source,once");
        semantic=Math.addExact(semantic,CAPTURED_OWN_SETUP);helper(32);
    }
    /** New private pre-graph schedule;actual stage/event counters are never reset. */
    void registerTableLocal(BPhysicalPairTable.Permit permit,long queriesUpper,long localFees,long regionEdges){
        liveSource();permit.requireLocalSource(this,view);
        //Fixed registration bootstrap before every checked count producer below.
        prepayTableBody(1024);
        if(queriesUpper<0||localFees<0||regionEdges<0||stage!=1||queries!=0||queryLimit!=0)
            throw new IllegalStateException("Captured local schedule once");
        if(defersTableBounds()){
            tableRegistry.registerExploratoryLocal(permit);queryLimit=queriesUpper;return;
        }
        long v=view.bound.source.length;
        long oneSource=Math.addExact(costs[SOURCE],Math.addExact(4*66,
                Math.addExact(Math.multiplyExact(10*v,66),Math.multiplyExact(4*regionEdges,76))));
        long point=Math.addExact(POINT_COUPLING_UNITS+EXACT_GEOMETRY_UNITS+136754+11*73+CAPTURED_OWN_QUERY,
                Math.multiplyExact(9,oneSource));
        long u=Math.addExact(semantic,Math.addExact(localFees,Math.multiplyExact(queriesUpper,point)));
        long h=Math.addExact(Math.multiplyExact(230,u),4845);
        Math.addExact(u,h); //Representation BEFORE local queries,not a runtime seal.
        long joins=Math.addExact(Math.multiplyExact(2,h/32),Math.addExact(Math.multiplyExact(10,queriesUpper),256));
        sourceWork.extendCapturedAuthority(view.bound,joins);
        tableRegistry.registerLocal(permit,queriesUpper,regionEdges,v,localFees,u);
        queryLimit=queriesUpper;
    }
    void prepayTableLocalKernel(BPhysicalPairTable.Permit permit,int c,int s,int samples,int modes){
        liveSource();permit.requireLocalSource(this,view);
        prepayTableBody(512);long units=localKernelUnits(c,s,samples,modes);
        permit.consumeLocalFee(Math.addExact(512,units));prepayTableBody(units);
        sourceWork.extendCapturedAuthority(view.bound,Math.addExact(units,256));
    }
    void capturedTableOwnQuery(BPhysicalPairTable.Permit permit){
        liveSource();permit.requireLocalSource(this,view);prepayTableBody(CAPTURED_OWN_QUERY);
    }
    void startTableOwnQuery(BPhysicalPairTable.Permit permit){
        liveSource();permit.requireLocalSource(this,view);
        if(queries==queryLimit)throw new IllegalStateException("Original local query ceiling");queries++;
    }
    void capturedOwnQuery(){
        liveSource();if(view.bound.input.captured==null||tableRegistry!=null||stage!=1)
            throw new IllegalStateException("No captured own-only stage");
        long next=Math.addExact(semantic,CAPTURED_OWN_QUERY);
        if(next>upper)throw new IllegalStateException("Captured own-query work ceiling");
        helper(32);semantic=next;
    }
    /** Per-profile Task2 registration;no old source/stage debit is reset or renamed. */
    void prepayLocalKernel(int components,int states,int samples,int modes) {
        liveSource();
        if(stage!=1||tableRegistry!=null||view.bound.input.captured==null)
            throw new IllegalStateException("Captured own-only kernel stage");
        //512 BEFORE this checked DAG:<=32 checked/API/failure visits*8 +256
        //operand,argument,guard,assignment and own-registration control units.
        long entry=Math.addExact(semantic,512);
        if(entry>upper)throw new IllegalStateException("Kernel registration prefix ceiling");
        helper(32);semantic=entry;
        long units=localKernelUnits(components,states,samples,modes);
        long nextUpper=Math.addExact(upper,units),nextHelpers=Math.addExact(helperUpper,Math.multiplyExact(230,units));
        Math.addExact(nextUpper,nextHelpers);
        sourceWork.extendCapturedAuthority(view.bound,Math.addExact(units,256));
        upper=nextUpper;helperUpper=nextHelpers;
        semantic=Math.addExact(semantic,units);helper(32);
    }
    static long localKernelUnits(int c,int s,int samples,int modes) {
        if(c<0||s<0||samples<0||modes<0||c>8192||s>96)
            throw new IllegalArgumentException("Local kernel dimensions");
        //modes covers own-lineage lookup;c covers native censored lookup too.
        long visits=Math.multiplyExact((long)c,Math.addExact(Math.multiplyExact((long)s,samples+1L),(long)modes+c+8));
        if(visits>8_000_000)throw new IllegalArgumentException("Local kernel work ceiling");
        //Each branch-union visit:<=320 reads/operands/arithmetic/controls/stores
        //and<=24 named collection,StrictMath,copy,allocation/owner APIs*8.
        //4096 fixed and128/s cover complete/default/outer loops and receipts.
        //New sealed own-access boundary:32 forwarding/entry controls,
        //64 begin/end/interface slots,32 primary/finally/failed-end controls.
        //Charged per registration;factory-only calls conservatively pay it too.
        return Math.addExact(4096+128,Math.addExact(Math.multiplyExact(512,visits),Math.multiplyExact(128L,s)));
    }
    static long installJoinUnits(long profiles,long modes,long rings) {
        if(profiles<0||modes<0||rings<0)throw new IllegalArgumentException("Negative install parent");
        // Fixed394:original386 +8 for source-kind branch. Captured-only
        // seven extra checked producer calls are separately prepaid by setup512.
        // ring: indexed controls7+source-list/size/checked-sum body16.
        // row: controls7+inner init/final6+state/index/checked joins64.
        // mode: controls7+indices/status/eligible branch-union17.
        return Math.addExact(394,Math.addExact(Math.multiplyExact(23,rings),
                Math.addExact(Math.multiplyExact(77,profiles),Math.multiplyExact(24,modes))));
    }
    void install(BPhysicalResponse.SourceView source) {
        live();if(installed||!claimed)throw new IllegalStateException("Physical stage installation");
        source.ensureSourceLive();var b=source.bound;
        var polygons=b.input.evidence.evidenceRegion().polygons();
        long r=polygons.size(),n=b.rows.length-1,d=b.indices[2].length;
        long prepaid=installJoinUnits(n,d,r);
        // BEFORE ring/profile/mode iteration and every dictionary/limit op.
        semantic=Math.addExact(semantic,prepaid);helper(32);stats[6]=prepaid;
        long e=0;
        for(int i=0;i<r;i++)e=Math.addExact(e,polygons.get(i).size());
        long v=Math.multiplyExact((long)b.source.length,1);
        long raw=Math.addExact(11128,Math.addExact(Math.multiplyExact(969,e),
                Math.addExact(Math.multiplyExact(42,r),Math.multiplyExact(342,v))));
        long polls=Math.addExact(1585,Math.addExact(Math.multiplyExact(122,e),
                Math.addExact(Math.multiplyExact(2,r),Math.multiplyExact(38,v))));
        costs[SCALAR]=66;costs[MASK]=66;costs[DESCRIPTOR]=73;costs[REGION]=76;
        costs[SOURCE]=Math.addExact(raw,Math.multiplyExact(41,polls));
        costs[COMPONENT]=136754; //20560+41*2834,accepted component body+guards
        if(tableRegistry!=null) {
            //Only bounded setup/source phases may execute before full global
            //caps/K installation. Public point/context entries remain disabled;
            //do not instantiate an unused single-point221-U ceiling.
            upper=helperUpper=defersTableBounds()?-1:Long.MAX_VALUE;queryLimit=0;
            view=source;bindingOrGeometry=new Geometry(null,null,null,null,0);installed=true;
            poll();source.ensureSourceLive();return;
        }
        long oneSource=Math.addExact(costs[SOURCE],Math.addExact(4*66,
                Math.addExact(Math.multiplyExact(10*v,66),Math.multiplyExact(4*e,76))));
        long oneQuery=Math.addExact(POINT_COUPLING_UNITS,Math.addExact(EXACT_GEOMETRY_UNITS,Math.addExact(costs[COMPONENT],Math.addExact(11*73,Math.multiplyExact(9,oneSource)))));
        long m=0,local=0;
        for(int i=0;i<b.rows.length-1;i++) {
            long eligible=0;
            for(int j=b.rows[i];j<b.rows[i+1];j++)if(b.indices[2][j]==BSharedPositionEvidence.Status.AVAILABLE.ordinal())eligible++;
            m=Math.addExact(m,eligible);local=Math.addExact(local,Math.multiplyExact(eligible,b.stateRows[i+1]-b.stateRows[i]));
        }
        queryLimit=Math.max(1,Math.addExact(Math.multiplyExact(50*m,(long)b.grid.width*b.grid.height),local));
        upper=Math.addExact(semantic,Math.multiplyExact(queryLimit,oneQuery));
        if(b.input.captured!=null)
            upper=Math.addExact(upper,Math.multiplyExact(Math.addExact(queryLimit,1),CAPTURED_OWN_QUERY));
        helperUpper=Math.addExact(Math.multiplyExact(230,upper),4845);
        Math.addExact(upper,helperUpper); //representation refusal BEFORE any query
        if(b.input.captured!=null) {
            //Every helper costs>=32;at most2 source-live joins/helper call.
            //Own precheck+startQuery and <=8 result reads give<=10 direct/query.
            long joins=Math.addExact(Math.multiplyExact(2,helperUpper/32),
                Math.addExact(Math.multiplyExact(10,queryLimit),256));
            sourceWork.extendCapturedAuthority(b,joins);
        }
        view=source;bindingOrGeometry=new Geometry(null,null,null,null,0);installed=true;
        poll();source.ensureSourceLive();
    }
    void startQuery(){liveSource();if(tableRegistry!=null||queries==queryLimit)throw new IllegalStateException("Physical response event ceiling");queries++;}
    static long prefixConstructionUnits(int w,int h) {
        BPhysicalSignaturePrefix.bytes(w,h);
        long v=Math.multiplyExact((long)w,h),n=Math.multiplyExact((long)w+1,h+1L);
        //Accepted R1 expansion:435fixed +Ndefault cells +583V +20H.
        //Five66-unit original accessor bodies/vertex are admitted at fetch.
        return Math.addExact(435,Math.addExact(n,Math.addExact(Math.multiplyExact(583,v),Math.multiplyExact(20L,h))));
    }
    void prepayPrefix(BPhysicalPanelCertificate parent,BPhysicalResponse.SourceView source,
            AttemptMemoryLedger.Owner allocation,int w,int h) {
        parent.requirePrefixConstruction(this,source,allocation);
        if(stage<4||stage>8)throw new IllegalStateException("No source-prefix stage");
        long prepaid=Math.addExact(semantic,PREFIX_RUNTIME_SETUP);
        if(!defersTableBounds()&&prepaid>upper)throw new IllegalStateException("Prefix dictionary bootstrap ceiling");
        helper(63);semantic=prepaid;
        long v=Math.multiplyExact((long)w,h),body=prefixConstructionUnits(w,h);
        long helpers=Math.addExact(Math.multiplyExact(5*v,68+69),Math.addExact(Math.multiplyExact(6*v,32),
                Math.multiplyExact(Math.addExact(17,Math.addExact(23*v,h)),61)));
        //N<=2V+1,H<=V/2:body<=436+595V<2^27 and
        //helpers<=1037+2261V<2^29. These fixed additions cannot overflow long.
        long u=tableRegistry==null?Math.addExact(upper,body+PREFIX_RUNTIME_SETUP):upper;
        long budget=tableRegistry==null?Math.addExact(helperUpper,helpers+63):helperUpper;
        if(!defersTableBounds())Math.addExact(u,budget);upper=u;helperUpper=budget;
        semantic=Math.addExact(semantic,Math.subtractExact(body,Math.multiplyExact(5*v,66)));
    }
    void claimContext() {
        liveSource();if(!installed||stage!=1)throw new IllegalStateException("Physical context already claimed");
        long next=Math.addExact(semantic,CONTEXT_JOIN_UNITS);
        if(next>upper)throw new IllegalStateException("Physical context join ceiling");
        semantic=next;helper(63);stage=3; //Failed construction consumes this claim.
        long descriptors=view.bound.indices[2].length;
        // F0 maximum stream382361+62400D, source-error6110, RANGE1980.
        // NEW context translations49*(8 RN-op guards+14 source slots)=49*398.
        // Three factual descriptor accessors per each of50D visits:10950D.
        //Two proved nonnegative-domain intersections:2*(7+41)=96.
        //Adapter invalidation before its own fallible admissions:one store/50D.
        // Constants are the reviewed additions below; still only TWO checked
        // body-instantiation calls,so the existing prepaid join census is intact.
        long body=Math.addExact(410049+GENERAL_CONTEXT_FIXED,
                Math.multiplyExact(73400+GENERAL_CONTEXT_PER_DESCRIPTOR,descriptors));
        long count=Math.multiplyExact((long)view.bound.grid.width,view.bound.grid.height);
        long revised=contextUpper(upper,count,body);
        long revisedHelpers=Math.addExact(Math.multiplyExact(230,revised),4845);
        Math.addExact(revised,revisedHelpers);
        upper=revised;helperUpper=revisedHelpers;costs[CONTEXT]=body;
        semantic=Math.addExact(semantic,CONTEXT_SETUP_UNITS);
        contextLimit=Math.toIntExact(count);stage=2;poll();
    }
    void startContext(){liveSource();if(stage!=2||contextQueries==contextLimit)throw new IllegalStateException("Physical context event ceiling");contextQueries++;admit(CONTEXT);}
    void claimSourcePanel() {
        liveSource();if(!installed||(stage!=1&&stage!=2))throw new IllegalStateException("Source panel already claimed");
        if(tableRegistry!=null){prepayTableBody(16);tableRegistry.requireEngine(view);}
        var rings=view.bound.input.evidence.evidenceRegion().polygons();
        //NEW16 scalar slots:one cache publication store and three future
        //read/predicate/branch/read/operand groups. Default2 cells were paid
        //before Work construction. No old helper/body credit is reused.
        //One checked body join plus operands/store/staging:12 new fixed units.
        long join=Math.addExact(256+16+12+64,Math.multiplyExact(23L,rings.size()));
        long next=Math.addExact(semantic,join);
        if(!defersTableBounds()&&next>upper)throw new IllegalStateException("Source panel join ceiling");
        semantic=next;helper(63);stage=4; //Failure consumes the claim,not a reset.
        long e=0;for(int i=0;i<rings.size();i++)e=Math.addExact(e,rings.get(i).size());
        long polls=Math.addExact(1585+60,Math.addExact(Math.multiplyExact(122,e),Math.multiplyExact(2L,rings.size())));
        long raw=Math.addExact(11128+2*639,Math.addExact(Math.multiplyExact(969,e),Math.multiplyExact(42L,rings.size())));
        //Owner guard64 plus NEW shared-transfer dispatch/target/return guard64
        //per source poll. Original root checks cannot certify child lifetime.
        //EXCLUSIVE mathematical parent. Ordinary scalar/coordinate accessors
        //retain their unchanged actual66/76 admissions/completions/read stats.
        long body=Math.addExact(ENDPOINT_SOURCE_UNITS,Math.addExact(PANEL_INTERFACE_UNITS,Math.addExact(raw,Math.multiplyExact(128+41,polls))));
        long wrappers=Math.addExact(4*66,Math.multiplyExact(4*76,e));
        //Only actual TABLE source entries execute the adjacent-cell extension.
        //The once64 setup above pays these producers/cache joins,not tap guards.
        if(tableRegistry!=null){body=Math.addExact(body,39992);wrappers=Math.addExact(wrappers,16*66);}
        long revised=tableRegistry==null?Math.addExact(upper,Math.addExact(join,Math.addExact(PANEL_CONSTRUCTOR_UNITS,Math.addExact(body,wrappers)))):upper;
        long revisedHelpers=tableRegistry==null?Math.addExact(Math.multiplyExact(230,revised),4845):helperUpper;if(!defersTableBounds())Math.addExact(revised,revisedHelpers);
        upper=revised;helperUpper=revisedHelpers;costs[SOURCE_PANEL]=body;
        semantic=Math.addExact(semantic,PANEL_CONSTRUCTOR_UNITS);
        //Same already-owned1MiB holder;before every public/private SourceBank
        //entry. A failed slice consumes the stage but allocates no source bank.
        sourceWork.metadata(SOURCE_FATAL_BYTES);poll();
        sourceWrapperUnits=wrappers; //Only after checked construction and SAME-probe liveness.
    }
    long contextQueries(){liveSource();return contextQueries;}
    static long contextUpper(long previous,long count,long body) {
        if(previous<0||count<0||body<0)throw new IllegalArgumentException("Negative context parent");
        return Math.addExact(previous,Math.addExact(Math.addExact(CONTEXT_JOIN_UNITS,CONTEXT_SETUP_UNITS),Math.multiplyExact(count,body)));
    }
    void claimComponentPanel() {
        liveSource();if(stage!=4)throw new IllegalStateException("Component panel stage");
        //24 fixed checked APIs/guards fit the separate256 join BEFORE use.
        long join=256+64,next=Math.addExact(semantic,join);
        if(!defersTableBounds()&&next>upper)throw new IllegalStateException("Component panel join ceiling");
        semantic=next;helper(63);stage=5;
        if(sourceWrapperUnits<=0)throw new IllegalStateException("Unpublished source wrapper coefficient");
        long source=Math.multiplyExact(9,Math.addExact(costs[SOURCE_PANEL],sourceWrapperUnits));
        long getters=Math.addExact(11*73,36*66);
        long extra=Math.addExact(join,Math.addExact(COMPONENT_PANEL_SETUP,
                Math.addExact(COMPONENT_PANEL_BODY,Math.addExact(source,getters))));
        long revised=tableRegistry==null?Math.addExact(upper,extra):upper;
        long revisedHelpers=tableRegistry==null?Math.addExact(Math.multiplyExact(230,revised),4845):helperUpper;if(!defersTableBounds())Math.addExact(revised,revisedHelpers);
        upper=revised;helperUpper=revisedHelpers;costs[COMPONENT_PANEL]=COMPONENT_PANEL_BODY;
        semantic=Math.addExact(semantic,COMPONENT_PANEL_SETUP);poll();
    }
    long registeredUpper(){liveSource();requireProspectiveTableBounds();return upper;}
    void pointCouplingGuard(){liveSource();long next=Math.addExact(semantic,POINT_COUPLING_UNITS);if(!defersTableBounds()&&next>upper)throw new IllegalStateException("Physical point coupling ceiling");helper(63);semantic=next;}
    void geometryGuard(){liveSource();long next=Math.addExact(semantic,EXACT_GEOMETRY_UNITS);if(!defersTableBounds()&&next>upper)throw new IllegalStateException("Physical geometry work ceiling");helper(63);semantic=next;stat(7);}
    void claimMaxPanel() {
        liveSource();if(stage!=5)throw new IllegalStateException("Max panel stage");
        //32fixed checked API/guard slots*8+64controls,BEFORE dictionary ops.
        long join=320,next=Math.addExact(semantic,join);
        if(!defersTableBounds()&&next>upper)throw new IllegalStateException("Max join ceiling");
        semantic=next;helper(63);stage=6;
        long d=view.bound.indices[2].length;
        if(sourceWrapperUnits<=0)throw new IllegalStateException("Unpublished source wrapper coefficient");
        long candidate=Math.addExact(COMPONENT_PANEL_BODY,Math.addExact(MAX_TRANSLATED_COMPONENT,
                Math.addExact(Math.multiplyExact(9,Math.addExact(costs[SOURCE_PANEL],sourceWrapperUnits)),14*73+36*66)));
        //All50streams including duplicate self;separate update315/caller512,
        //51initial119,49weights1005+512,error903,RANGE299,D/interface4096.
        long visits=Math.multiplyExact(50,d);
        long controls=Math.addExact(Math.multiplyExact(visits,315+MAX_UPDATE_CALLER),
                51*MAX_INITIAL+49*(315+1005+MAX_WEIGHT_CALLER)+903+299+4096);
        long body=Math.addExact(controls,Math.multiplyExact(visits,candidate));
        long revised=tableRegistry==null?Math.addExact(upper,Math.addExact(join,Math.addExact(MAX_SETUP,body))):upper;
        long h=tableRegistry==null?Math.addExact(Math.multiplyExact(230,revised),4845):helperUpper;if(!defersTableBounds())Math.addExact(revised,h);
        upper=revised;helperUpper=h;costs[MAX_PANEL]=Math.addExact(controls,Math.multiplyExact(visits,MAX_TRANSLATED_COMPONENT));
        semantic=Math.addExact(semantic,MAX_SETUP);poll();
    }
    void admit(int block) {
        liveSource();if(block<0||block>=20||costs[block]<=0)throw new UnsupportedOperationException("F1 stage capability");
        long next=Math.addExact(semantic,costs[block]),count=Math.addExact(admitted[block],1);
        if(!defersTableBounds()&&next>upper)throw new IllegalStateException("Physical registered work ceiling");
        var registry=tableRegistry;
        if(registry!=null)registry.prepareStage(block,0);
        helper(68);semantic=next;admitted[block]=count;
        if(registry!=null)registry.commitPrepared();
    }
    void complete(int block,boolean returned) {
        live();long a=normal[block],b=thrown[block];
        long n=Math.addExact(returned?a:b,1);
        if(Math.addExact(n,returned?b:a)>admitted[block])throw new IllegalStateException("Unobserved completion");
        var registry=tableRegistry;
        if(registry!=null)registry.prepareStage(block,returned?1:2);
        helper(69);if(returned)normal[block]=n;else thrown[block]=n;
        if(registry!=null)registry.commitPrepared();
    }
    void stat(int slot){liveSource();long next=Math.addExact(stats[slot],1);helper(32);stats[slot]=next;}
    void poll() {
        live();helper(61);checkpointIntents=Math.addExact(checkpointIntents,1);
        probe.checkpoint();liveSource();checkpointReturns=Math.addExact(checkpointReturns,1);
    }
    private void helper(long n){long next=Math.addExact(helpers,n);if(!defersTableBounds()&&installed&&next>helperUpper)throw new IllegalStateException("Physical helper ceiling");helpers=next;}
    void live(){sourceWork.ensureOpen();if(closed||owner.isClosed())throw new IllegalStateException("Physical attempt closed");}
    void liveSource(){live();if(view!=null)view.ensureSourceLive();}
    long observed(int slot){liveSource();return stats[slot];}
    long semanticUnits(){liveSource();return semantic;}
    void tableGeometry(long units,long upper) {
        liveSource();if(tableRegistry==null||units<0||!defersTableBounds()&&actualTableGeometry>upper)throw new IllegalStateException("Geometry currency authority");
        long next=Math.addExact(actualTableGeometry,units);
        if(!defersTableBounds()&&next>upper)throw new IllegalStateException("Original geometry currency ceiling");
        actualTableGeometry=next;
    }
    long queries(){liveSource();return queries;}
    //137-lane ABI:reviewed1581+11(new stat)+20*7(record getters)+32guards.
    static final long TABLE_IMAGE_COPY=1581+11+20*7+32;
    void freezeTableCut(BPhysicalTableRegistry registry,BSharedWork.Counters c,boolean last) {
        requireProspectiveTableBounds();
        live();if(registry!=tableRegistry||c==null)throw new IllegalArgumentException("Foreign actual table cut");
        var image=last?terminal:prefix;if(image.frozen)throw new IllegalStateException("Physical cut once");
        prepayTableBody(TABLE_IMAGE_COPY);long[] out=image.values;
        out[0]=c.buildUnits();out[1]=c.reservedUpperUnits();out[2]=c.primitiveUpperUnits();out[3]=c.sourceReads();out[4]=c.maskReads();
        out[5]=c.strictMathCalls();out[6]=c.exactApiCalls();out[7]=c.weightedLimbPairProxy();out[8]=c.checkpoints();out[9]=c.hotLookups();
        out[10]=c.arrayWrites();out[11]=c.sourceInterpolations();out[12]=c.retainedMetadataSliceBytes();out[13]=c.remainingPublicationBytes();
        out[14]=c.authorityMetadataSlots();out[15]=c.sourceExtractionStartedCalls();out[16]=c.sourceExtractionCompletedCalls();
        out[17]=c.sourceExtractionNormalReturns();out[18]=c.sourceExtractionCancelledCalls();out[19]=c.sourceIssuerCancellations();
        out[20]=semantic;out[21]=upper;out[22]=helpers;out[23]=helperUpper;out[24]=queries;out[25]=checkpointIntents;
        out[26]=checkpointReturns;out[27]=actualTableGeometry;out[28]=stage;
        for(int i=0;i<20;i++){out[29+i]=admitted[i];out[49+i]=normal[i];out[69+i]=thrown[i];}
        for(int i=0;i<9;i++)out[89+i]=stats[i];
        //98..121 are unavailable original-inference/cross carriers,not
        //renamed stage counts. False availability forbids their consumption.
        out[122]=1;out[123]=1;out[124]=0;out[125]=0;out[126]=registry.geometryAvailable()?1:0;
        out[127]=last?2:1;out[128]=stage;out[129]=owner.currentBytes();out[130]=owner.isClosed()?1:0;
        out[131]=1;out[132]=0;out[133]=0;out[134]=queries;out[135]=actualTableGeometry;out[136]=137;
        image.references[0]=null;image.references[1]=null;image.phase=last?2:1;image.frozen=true;
    }
    long tableImage(boolean last,int index){live();requireProspectiveTableBounds();var i=last?terminal:prefix;
        if(!i.frozen)throw new IllegalStateException("No actual frozen physical cut");
        if(index<0||index>=137)throw new IndexOutOfBoundsException();
        if(index>=98&&index<122)throw new IllegalStateException("Original solver work NOT_RUN");return i.values[index];}
    //Eight root/metadata live-guard parents32 plus128 identity/completion/
    //index/reference/return slots cover either bounded raw accessor;no source
    //guard is used,so an actual HOST cut remains readable after child rollback.
    static final long METADATA_CUT_READ=8*32+128;
    void requireMetadataCut(BPhysicalTableRegistry issuer,boolean last){
        requireProspectiveTableBounds();
        live();if(issuer==null||issuer!=tableRegistry)throw new IllegalArgumentException("Foreign metadata cut issuer");
        if(!(last?terminal:prefix).frozen)throw new IllegalStateException("Physical metadata cut incomplete");
        issuer.requireMetadataImage(this,last);
    }
    long metadataTableImage(BPhysicalTableRegistry issuer,boolean last,int index){
        prepayTableBody(METADATA_CUT_READ);requireMetadataCut(issuer,last);
        if(index<0||index>=137)throw new IndexOutOfBoundsException("Physical metadata raw word");
        return (last?terminal:prefix).values[index]; //Opaque unavailable words retain their availability flags.
    }
    long stageActual(int block,int outcome){live();if(block<0||block>=20||outcome<0||outcome>2)throw new IndexOutOfBoundsException();
        return outcome==0?admitted[block]:outcome==1?normal[block]:thrown[block];}
    void claimRootIntegral() {
        liveSource();if(stage!=6)throw new IllegalStateException("Root integral stage");
        long next=Math.addExact(semantic,256);
        if(!defersTableBounds()&&next>upper)throw new IllegalStateException("Integral join ceiling");
        semantic=next;helper(63);stage=7;
        long revised=tableRegistry==null?Math.addExact(upper,Math.addExact(256,Math.addExact(ROOT_SETUP,ROOT_BODY))):upper;
        long h=tableRegistry==null?Math.addExact(Math.multiplyExact(230,revised),4845):helperUpper;if(!defersTableBounds())Math.addExact(revised,h);
        upper=revised;helperUpper=h;costs[ROOT_INTEGRAL]=ROOT_BODY;
        semantic=Math.addExact(semantic,ROOT_SETUP);poll();
    }
    //Adaptive parameter replay:<=30preparations/50D candidate. The new
    //integer/scalb/nextafter branch has<=24API slots*8+96 scalar/control
    //slots;two new base-tent endpoints use the already registered8 coupled
    //transfers and8seeds. No old MAX unused ceiling is credited.
    static final long ADAPTIVE_PARAMETER=30*(24*8+96)+2*(8*2248+8*52+128);
    //New refusal classification:3ready/live guards<=MAX_LIVE each;
    //4isFinite APIs*8 plus48 status/sign/order/branch/counter slots. A numeric
    //failure never purchases another interval;exceptions propagate unchanged.
    //Independent returned-leaf validation adds six ready/live guards,
    //six isFinite+scalb+nextDown API slots and80 scalar/load/branch slots.
    static final long ADAPTIVE_LEAF_GUARD=6*MAX_LIVE+8*8+80;
    //Table ACCURACY:4null+8policy+4logs+4operands+16finite APIs+
    //8unknown predicates+4return+4fallthrough+6caller/Adaptive dispatch.
    static final long ADAPTIVE_ACCURACY=4+8+4+4+16+8+4+4+6;
    //Two accepted-leaf getters+two once/edge preflight getters,charged
    //conservatively in every node;Root prepays its own two getters separately.
    static final long ADAPTIVE_CONTROL=23+59*2+31+4096+80+3*MAX_LIVE+ADAPTIVE_LEAF_GUARD+535+ADAPTIVE_ACCURACY+4*52;
    static final long ADAPTIVE_MERGE=406+4096+34;
    //H fixed DAG separately expands36add+18mul+11array(3)+11list(4)
    //=131checked calls,plus2Math.max. Its256 non-API slots cover shape10,
    //local initializers46,pair guard3,assignment/control40,array bitops44,
    //caller/return/branch union113. Not the separate64-slot dictionary fee.
    static final long ADAPTIVE_FIXED=12977+451+31*(3*73+64+32*8+64)+640+(131+2)*8+256+16+6;
    static long adaptiveSetupUnits(long n) {
        if(n<1||n>81)throw new IllegalArgumentException("Original profile guard");
        //Per profile H census:<=128checked arithmetic/API slots and256
        //indexed/branch/load/store slots. Fixed64checked slots+128controls.
        //Table-only dominating N1 H branch adds16fixed dispatch/controls and
        //<=16 per-row source selectors;public N1 refusal is unchanged.
        return Math.addExact(ADAPTIVE_FIXED,Math.multiplyExact(n,128*8+256+16));
    }
    long beginAdaptive(BPhysicalResponse.SourceView source) {
        liveSource();if(stage!=7||source!=view)throw new IllegalStateException("Adaptive stage identity");
        //Prepaid before H loops,checked dictionary and K search. The fixed
        //join is itself<=32checked API slots*8,nonrecursive instrumentation.
        long next=Math.addExact(semantic,256);if(!defersTableBounds()&&next>upper)throw new IllegalStateException("Adaptive join ceiling");
        semantic=next;helper(63);stage=8;
        long setup=adaptiveSetupUnits(view.bound.stateRows.length-1);
        long d=view.bound.indices[2].length;
        if(sourceWrapperUnits<=0)throw new IllegalStateException("Unpublished source wrapper coefficient");
        costs[17]=Math.addExact(ADAPTIVE_CONTROL,Math.multiplyExact(50*d,ADAPTIVE_PARAMETER));
        costs[18]=ADAPTIVE_MERGE;
        if(defersTableBounds()){poll();return -1;}
        long base=tableRegistry==null?Math.addExact(upper,Math.addExact(256,setup)):upper;
        long h=tableRegistry==null?Math.addExact(Math.multiplyExact(230,base),4845):helperUpper;Math.addExact(base,h);
        upper=base;helperUpper=h;
        //Global compiler prepaid THIS complete setup before its moved H
        //evaluation/K search. No second setup admission on workspace issue.
        if(tableRegistry==null)semantic=Math.addExact(semantic,setup);
        long candidate=Math.addExact(COMPONENT_PANEL_BODY,Math.addExact(MAX_TRANSLATED_COMPONENT,
                Math.addExact(Math.multiplyExact(9,Math.addExact(costs[SOURCE_PANEL],sourceWrapperUnits)),14*73+36*66)));
        long controls=Math.addExact(Math.multiplyExact(50*d,315+MAX_UPDATE_CALLER),
                51*MAX_INITIAL+49*(315+1005+MAX_WEIGHT_CALLER)+903+299+4096);
        long node=Math.addExact(costs[17],Math.addExact(ROOT_BODY,Math.addExact(controls,Math.multiplyExact(50*d,candidate))));
        //The immutable coefficient was produced from the authenticated e loop:
        //sourceWrapperUnits=264+304e,so this exact integer inversion needs no
        //ring replay or source access. It is NOT factual authority inferred
        //from a hash/output. Full checked helper DAG executes ONCE,under the
        //already prepaid64fixed API slots,never inside the K search.
        long edges=(sourceWrapperUnits-(tableRegistry==null?264:264+16*66))/304;
        long helpers=prefixNodeHelperUnits(Math.max(1,d),edges,view.bound.input.evidence.evidenceRegion().polygons().size());
        //Once-only source/component setup joins prepay these two checked
        //producers. No repeated source guard or callback is funded by them.
        if(tableRegistry!=null)helpers=Math.addExact(helpers,Math.multiplyExact(50*9*d,8926));
        poll();adaptiveNodeHelpers=helpers;return node;
    }
    static long prefixNodeHelperUnits(long descriptors,long edges,long rings) {
        if(descriptors<1||descriptors>8192||rings<1||rings>Integer.MAX_VALUE||edges<3*rings)
            throw new IllegalArgumentException("Original source helper parents");
        long visits=Math.multiplyExact(50,descriptors),taps=Math.multiplyExact(9,visits);
        long polls=Math.addExact(1645,Math.addExact(Math.multiplyExact(122,edges),Math.multiplyExact(2,rings)));
        long retained=Math.addExact(Math.multiplyExact(taps,Math.addExact(8,Math.multiplyExact(4,edges))),Math.multiplyExact(14,visits));
        long admissions=Math.addExact(retained,Math.addExact(taps,Math.addExact(visits,3)));
        long stats=Math.addExact(retained,Math.multiplyExact(8,taps));
        long checkpoints=Math.addExact(Math.multiplyExact(taps,polls),Math.addExact(Math.multiplyExact(visits,
                2834+10933+30*8*47+ADAPTIVE_PARAMETER+11+11),ROOT_LOOP_CHECKS+ROOT_COEFFICIENT_OPS+51*5+49*15+3));
        return Math.addExact(Math.multiplyExact(137,admissions),Math.addExact(Math.multiplyExact(32,stats),Math.multiplyExact(61,checkpoints)));
    }
    private long adaptiveHelpers(int k) {
        if(adaptiveNodeHelpers<=0)throw new IllegalStateException("Unpublished adaptive helper coefficient");
        //One pool admission/completion and FOUR fixed boundary checkpoints;
        //per-node and per-merge events have their own disjoint source parents.
        return Math.addExact(helperUpper,Math.addExact(137+4*61,Math.addExact(
                Math.multiplyExact(k,adaptiveNodeHelpers),Math.multiplyExact(k-1L,198))));
    }
    boolean adaptiveFits(BPhysicalAdaptiveIntegral a,int k) {
        liveSource();a.requireWork(this);if(stage!=8||k<1)return false;
        try {long u=adaptiveUpper(upper,a.nodeCeiling(),costs[18],k);
            long h=adaptiveHelpers(k);Math.addExact(u,h);return true;
        } catch(ArithmeticException overflow){return false;}
    }
    void installAdaptive(BPhysicalAdaptiveIntegral a,int k) {
        liveSource();a.requireWork(this);if(!adaptiveFits(a,k))throw new ArithmeticException("Adaptive checked work refused");
        long initialization=Math.addExact(50,Math.multiplyExact(41L,k));
        long u=adaptiveUpper(upper,a.nodeCeiling(),costs[18],k);
        long h=adaptiveHelpers(k);upper=u;helperUpper=h;costs[19]=initialization;stage=9;poll();
    }
    void installTableAdaptive(BPhysicalAdaptiveIntegral a,BPhysicalPairTable.Permit permit,int k) {
        liveSource();a.requireWork(this);permit.requireAdaptiveConstruction(a,this,k);
        if(tableRegistry==null||stage!=8||k<1)throw new IllegalStateException("No global adaptive plan");
        costs[19]=Math.addExact(50,Math.multiplyExact(41L,k));stage=9;poll();
    }
    static long adaptiveUpper(long previous,long node,long merge,int k) {
        if(previous<0||node<=0||merge<=0||k<1)throw new IllegalArgumentException("Adaptive body parents");
        return Math.addExact(previous,Math.addExact(Math.addExact(50,Math.multiplyExact(41L,k)),
                Math.addExact(Math.multiplyExact(k,node),Math.multiplyExact(k-1L,merge))));
    }
    void refuseUnimplemented(){throw new UnsupportedOperationException("F1 RESPONSE_ONLY:no table/integrator/solver/codec");}
    @Override public void close(){closed=true;owner.close();if(owner.currentBytes()!=0)throw new AssertionError("actual root not closed");}
    private static final class Image {
        final long[] values=new long[137];final Object[] references=new Object[2];
        final BPhysicalWork work;final AttemptMemoryLedger.Owner owner;final CancellationProbe probe;
        int phase;boolean frozen;
        Image(BPhysicalWork w,AttemptMemoryLedger.Owner o,CancellationProbe p){work=w;owner=o;probe=p;}
    }
    private record Carrier(Object input,Object authority) { }
    private record Geometry(Object work,Object bound,Object owner,Object probe,long upper) { }
}
