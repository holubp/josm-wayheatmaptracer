package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

/** One canonical chord,one reusable evaluator,one jointly admitted node pool. */
final class BPhysicalAdaptiveIntegral implements AutoCloseable {
    enum Status { CERTIFIED,REFUSED,UNRESOLVED,SOURCE_UNKNOWN }
    private final BPhysicalRootIntegral root;
    private final BPhysicalWork work;
    private final org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.Owner owner;
    private Pool pool;
    private BPhysicalPairTable.Permit tablePermit;
    double ax,ay,bx,by;int gain;
    private double length,lengthLo,lengthHi,tolerance,unknownTolerance,centre,error,lower,upper,unknownLo,unknownHi;
    private long nodeBound,headroom,initialBytes;
    private int phase=3,consumed,status,current,splits,merges,rangeNodes,branchNodes,logNodes,numericRefusals;
    private boolean closed;
    //5refs+15doubles+3longs+11ints+flag=229fields,O229=248;reserve256.
    private BPhysicalAdaptiveIntegral(BPhysicalRootIntegral root){this.root=root;work=root.work;owner=root.owner;}
    static BPhysicalAdaptiveIntegral open(BPhysicalResponse response) {
        var root=BPhysicalRootIntegral.open(response);
        try {
            long nodeBound=root.work.beginAdaptive(response.panelSource());
            root.owner.reserve(256);var a=new BPhysicalAdaptiveIntegral(root);
            a.nodeBound=nodeBound;
            a.headroom=headroom(response.bound());
            a.initialBytes=a.owner.currentBytes();
            if(!a.fits(1))throw new IllegalStateException("No jointly funded adaptive root");
            int low=1,high=(int)((64L<<20)/56),steps=0;
            while(low<high){if(++steps>31)throw new AssertionError("K search bound");int mid=low+(high-low+1)/2;if(a.fits(mid))low=mid;else high=mid-1;}
            a.work.installAdaptive(a,low);
            //No retry if a callback changed C0:actual root reservation must win.
            a.work.admit(19);boolean constructed=false;
            try {a.owner.reserve(add(a.headroom,poolBytes(low)));a.pool=new Pool(low);constructed=true;}
            finally {a.work.complete(19,constructed);}
            root.bindAdaptive(a);a.work.poll();a.live();a.phase=0;return a;
        }catch(RuntimeException|Error failure){root.close();throw failure;}
    }
    static BPhysicalAdaptiveIntegral forTable(BPhysicalResponse response,BPhysicalPairTable.Permit permit,int k,long headroom) {
        permit.requireConstruction(response);var root=BPhysicalRootIntegral.forTable(response,permit);
        try {
            long nodeBound=root.work.beginAdaptive(response.panelSource());
            root.owner.reserve(256);var a=new BPhysicalAdaptiveIntegral(root);
            a.tablePermit=permit;a.nodeBound=nodeBound;a.headroom=headroom;a.initialBytes=a.owner.currentBytes();
            root.work.installTableAdaptive(a,permit,k);
            root.work.prepayTableBody(BPhysicalPairTable.SOLVER_HEADROOM_SETUP);
            root.work.admit(19);boolean normal=false;
            try {
                long poolCharge=poolBytes(k);add(headroom,poolCharge); //unchanged checked simultaneous total
                permit.holdSolverHeadroom(a.owner.reserve(headroom));
                a.owner.reserve(poolCharge);a.pool=new Pool(k);normal=true;
            }
            finally {root.work.complete(19,normal);}
            root.bindAdaptive(a);root.work.poll();permit.requireConstruction(response);a.live();a.phase=0;return a;
        }catch(RuntimeException|Error failure){root.close();throw failure;}
    }
    void certifyForTable(BPhysicalPairTable.Permit permit,double ax,double ay,double bx,double by,int gain) {
        phase=0;permit.requireIntegral(this);live();
        //Only a terminal predecessor's table permit can re-enter. Public
        //single-chord APIs and all cumulative Work/Registry counts stay intact.
        consumed=0;pool.cursor=pool.maximumDepth=0;current=splits=merges=rangeNodes=branchNodes=logNodes=numericRefusals=0;
        certify(ax,ay,bx,by,gain);permit.requireIntegral(this);
    }
    private boolean fits(int k){try{return add(initialBytes,add(headroom,poolBytes(k)))<=64L<<20&&work.adaptiveFits(this,k);}catch(ArithmeticException overflow){return false;}}
    private void live(){work.liveSource();if(closed||owner.isClosed())throw new IllegalStateException("Adaptive owner closed");}
    void requireWork(BPhysicalWork expected){live();if(work!=expected||phase!=3)throw new IllegalStateException("Foreign adaptive setup");}
    long nodeCeiling(){return nodeBound;}
    void requireRoot(BPhysicalRootIntegral expected){live();if(root!=expected||(phase!=2&&phase!=3))throw new IllegalStateException("No adaptive root permit");}
    void requireMax(BPhysicalMaxPanel expected){requireRoot(root);root.requireAdaptiveMax(this,expected);}
    void requireComponent(BPhysicalComponentPanel expected){requireRoot(root);root.requireAdaptiveComponent(this,expected);}
    int activeDepth(){requireRoot(root);return pool.depth[current];}
    long activeOrdinal(){requireRoot(root);return pool.ordinal[current];}
    void storeOriginalLength(BPhysicalRootIntegral expected,double n,double lo,double hi){requireRoot(expected);if(pool.cursor!=0)throw new IllegalStateException("Length already bound");length=n;lengthLo=lo;lengthHi=hi;}
    double originalLength(){return length;}double originalLengthLower(){return lengthLo;}double originalLengthUpper(){return lengthHi;}
    //A nonnull policy can only be issued by the already-authenticated private
    //forTable constructor. Active frame/owner guards dominate its consumers.
    boolean tableOnly(){return tablePermit!=null;}
    double nodeBudget(BPhysicalRootIntegral expected){requireRoot(expected);return leafBudget(tolerance);}
    double nodeUnknownBudget(BPhysicalRootIntegral expected){requireRoot(expected);return leafBudget(unknownTolerance);}
    private double leafBudget(double full) {
        if(tablePermit==null)return Math.nextDown(Math.scalb(full,-activeDepth()));
        double floor=Math.nextDown(full/(2.0*pool.capacity));
        if(!Double.isFinite(floor)||!(floor>0))throw new ArithmeticException("Table leaf budget refused");
        int depth=pool.depth[current];
        double weighted=Math.nextDown(Math.scalb(full,-depth-2));
        double combined=Math.nextDown(weighted+floor);
        return Math.max(floor,combined);
    }
    void certify(double ax,double ay,double bx,double by,int gain) {
        phase=0;live();if(consumed!=0)throw new IllegalStateException("Adaptive chord consumed");consumed=1;
        work.admit(17);boolean returned=false;
        try {
            if(!Double.isFinite(ax)||!Double.isFinite(ay)||!Double.isFinite(bx)||!Double.isFinite(by))throw new IllegalArgumentException("Finite original chord");
            if(gain!=1&&gain!=4&&gain!=16&&gain!=64)throw new IllegalArgumentException("Fixed gain family");
            if(Double.compare(ax,bx)>0||Double.compare(ax,bx)==0&&Double.compare(ay,by)>0){double t=ax;ax=bx;bx=t;t=ay;ay=by;by=t;}
            this.ax=ax;this.ay=ay;this.bx=bx;this.by=by;this.gain=gain;phase=2;
            centre=error=lower=upper=unknownLo=unknownHi=Double.NaN;status=Status.REFUSED.ordinal();
            if(ax==bx&&ay==by){centre=error=lower=upper=unknownLo=unknownHi=0;status=0;}
            else {
                root.prepareAdaptiveEdge(this);tolerance=Math.nextDown(Math.scalb(Math.max(1,lengthLo),-32));unknownTolerance=tolerance;
                if(tablePermit!=null){nodeBudget(root);nodeUnknownBudget(root);}
                current=pool.claim(-1,0,0);boolean finished=false;
                while(!finished) {
                    if(pool.state[current]==0) {
                        if(pool.cursor>1)work.admit(17);boolean normal=false;
                        try {pool.state[current]=1;work.poll();requireRoot(root);root.certifyNode(this);normal=true;}
                        finally {if(pool.cursor>1)work.complete(17,normal);}
                        if(root.status()==BPhysicalRootIntegral.Status.CERTIFIED){
                            double c=root.value(),e=root.error(),lo=root.lower(),hi=root.upper(),ul=root.unknownLower(),uh=root.unknownUpper();
                            double share=tablePermit==null?Math.nextDown(Math.scalb(tolerance,-pool.depth[current])):nodeBudget(root);
                            double unknownShare=tablePermit==null?share:nodeUnknownBudget(root);
                            //Status is not a substitute for validating the actual
                            //returned carrier AFTER its final caller callback.
                            if(!Double.isFinite(c)||!Double.isFinite(e)||!Double.isFinite(lo)||!Double.isFinite(hi)||!Double.isFinite(ul)||!Double.isFinite(uh)
                                    ||c<0||e<0||lo>c||hi<c||ul<0||uh<ul||e>share||uh-ul>unknownShare){numericRefusals++;status=1;break;}
                            pool.value[current]=c;pool.error[current]=e;pool.unknownLo[current]=ul;pool.unknownHi[current]=uh;pool.state[current]=3;
                        }
                        else if(root.status()==BPhysicalRootIntegral.Status.SOURCE_UNKNOWN){status=3;unknownLo=0;unknownHi=lengthHi;break;}
                        else {
                            var reason=root.refinement();
                            if(reason==BPhysicalRootIntegral.Refinement.NONE){numericRefusals++;status=1;break;}
                            if(reason==BPhysicalRootIntegral.Refinement.RANGE)rangeNodes++;
                            else if(reason==BPhysicalRootIntegral.Refinement.BRANCH)branchNodes++;
                            else if(reason==BPhysicalRootIntegral.Refinement.LOG_DOMAIN)logNodes++;
                            //ACCURACY is a table-only valid enclosure width
                            //miss;it is neither a numeric nor log-domain event.
                            if(pool.depth[current]==62||pool.cursor>pool.capacity-2){status=root.status()==BPhysicalRootIntegral.Status.UNRESOLVED?2:1;break;}
                            int parent=current,d=pool.depth[parent]+1;long ordinal=pool.ordinal[parent]*2;
                            //Both capacity permits checked BEFORE either claim.
                            int left=pool.claim(parent,d,ordinal),right=pool.claim(parent,d,ordinal+1);
                            if(right!=left+1)throw new AssertionError("Sibling adjacency");pool.firstChild[parent]=left;pool.state[parent]=2;splits++;current=left;continue;
                        }
                    }
                    while(pool.state[current]==3) {
                        if(current==0){publishRoot();finished=true;break;}
                        int parent=pool.parent[current],left=pool.firstChild[parent];
                        if(current==left){current=left+1;break;}
                        if(!merge(parent,left,left+1)){finished=true;break;}current=parent;
                    }
                }
            }
            work.poll();requireRoot(root);returned=true;
        }finally{work.complete(17,returned);if(!returned)phase=0;}
        phase=1;
    }
    private boolean merge(int parent,int left,int right) {
        work.admit(18);boolean returned=false;
        try {
            merges++;work.poll();requireRoot(root);
            double c=pool.value[left]+pool.value[right];
            double lo=Math.nextDown(Math.nextDown(pool.value[left]-pool.error[left])+Math.nextDown(pool.value[right]-pool.error[right]));
            double hi=Math.nextUp(Math.nextUp(pool.value[left]+pool.error[left])+Math.nextUp(pool.value[right]+pool.error[right]));
            double e=Math.nextUp(Math.max(c-lo,hi-c));
            if(pool.error[left]==0&&pool.error[right]==0&&pool.value[left]==0&&pool.value[right]==0){c=lo=hi=e=0;}
            double ul=pool.unknownLo[left]==0&&pool.unknownLo[right]==0?0:Math.max(0,Math.nextDown(pool.unknownLo[left]+pool.unknownLo[right]));
            double uh=pool.unknownHi[left]==0&&pool.unknownHi[right]==0?0:Math.nextUp(pool.unknownHi[left]+pool.unknownHi[right]);
            double budget=Math.nextDown(Math.scalb(tolerance,-pool.depth[parent]));
            double ub=Math.nextDown(Math.scalb(unknownTolerance,-pool.depth[parent]));
            if(!Double.isFinite(c)||!Double.isFinite(e)||c<0||e<0||!Double.isFinite(ul)||!Double.isFinite(uh)
                    ||ul<0||uh<ul||lo>c||hi<c||tablePermit==null&&(e>budget||uh-ul>ub)){status=1;returned=true;return false;}
            pool.value[parent]=c;pool.error[parent]=e;pool.unknownLo[parent]=ul;pool.unknownHi[parent]=uh;pool.state[parent]=3;returned=true;return true;
        }finally{work.complete(18,returned);}
    }
    private void publishRoot(){centre=pool.value[0];error=pool.error[0];lower=error==0?centre:Math.nextDown(centre-error);upper=error==0?centre:Math.nextUp(centre+error);unknownLo=pool.unknownLo[0];unknownHi=pool.unknownHi[0];
        status=tablePermit!=null&&(!Double.isFinite(centre)||!Double.isFinite(error)||!Double.isFinite(lower)||!Double.isFinite(upper)
                ||!Double.isFinite(unknownLo)||!Double.isFinite(unknownHi)||centre<0||error<0||lower>centre||upper<centre
                ||unknownLo<0||unknownHi<unknownLo||error>tolerance||unknownHi-unknownLo>unknownTolerance)?1:0;}
    private void ready(){live();if(phase!=1)throw new IllegalStateException("No adaptive chord");}
    Status status(){ready();return status==0?Status.CERTIFIED:status==1?Status.REFUSED:status==2?Status.UNRESOLVED:Status.SOURCE_UNKNOWN;}
    double value(){ready();return status==0?centre:Double.NaN;}double error(){ready();return error;}double lower(){ready();return lower;}double upper(){ready();return upper;}
    double unknownLower(){ready();return unknownLo;}double unknownUpper(){ready();return unknownHi;}
    int nodes(){live();return pool.cursor;}int splits(){live();return splits;}int merges(){live();return merges;}
    boolean finalAdmissionPending(){live();return phase==2&&pool.cursor>0&&pool.state[0]==3;}
    int maximumDepth(){live();return pool.maximumDepth;}
    int rangeNodes(){live();return rangeNodes;}int branchNodes(){live();return branchNodes;}
    int logNodes(){live();return logNodes;}int numericRefusals(){live();return numericRefusals;}
    int capacity(){live();return pool.capacity;}long headroomBytes(){live();return headroom;}long initialBytes(){live();return initialBytes;}
    @Override public void close(){closed=true;root.close();}
    private static final class Pool {
        final long[] ordinal;final double[] value,error,unknownLo,unknownHi;
        final int[] depth,parent,firstChild,state;int capacity,cursor,maximumDepth;
        Pool(int k){capacity=k;ordinal=new long[k];value=new double[k];error=new double[k];unknownLo=new double[k];unknownHi=new double[k];depth=new int[k];parent=new int[k];firstChild=new int[k];state=new int[k];}
        int claim(int p,int d,long o){if(cursor==capacity||d<0||d>62||o<0||o>=(1L<<d))throw new IllegalStateException("Node permit refused");int i=cursor++;parent[i]=p;depth[i]=d;ordinal[i]=o;value[i]=error[i]=unknownLo[i]=unknownHi[i]=Double.NaN;firstChild[i]=-1;state[i]=0;maximumDepth=Math.max(maximumDepth,d);return i;}
    }
    static long poolBytes(int k){if(k<1)throw new IllegalArgumentException("Positive pool");return add(add(mul(5,array(k,8)),mul(4,array(k,4))),136);}
    private static long add(long a,long b){return Math.addExact(a,b);}private static long mul(long a,long b){return Math.multiplyExact(a,b);}
    private static long array(long n,int w){return add(16,add(mul(n,w),7)&~7L);}
    private static long list(long n){return add(40,array(n,8));}
    private static long grid(long a,long b,int w){return add(array(a,8),mul(a,array(b,w)));}
    //The reviewed raw1 probe allowance,not a whole-inference peak theorem.
    //All originals/capture/publication are ALREADY in actual C0,not repeated.
    static long headroom(BSharedPositionEvidence.Bound b) {
        return headroom(b,false);
    }
    static long tableHeadroom(BSharedPositionEvidence.Bound b) {return headroom(b,true);}
    private static long headroom(BSharedPositionEvidence.Bound b,boolean singleTable) {
        int actual=b.stateRows.length-1,n=actual;
        if(singleTable&&n==1)n=2; //registered dominating one-state allowance only
        if(n<2||n>81)throw new IllegalArgumentException("Original profile census");
        long pairs=0,pmax=0,pfirst=0,plast=0,frontier=0,previous=0,messages=mul(2,list(n-1)),pos=list(n),comp=list(n),credible=list(n),temporary=0,smax=0;
        for(int i=0;i<n;i++) {
            int s=i==actual?1:b.stateRows[i+1]-b.stateRows[i],m=i==actual?1:b.input.lattices.get(i).components().size();
            if(s<1||s>96||m<1||m>8192)throw new IllegalArgumentException("Original state/component guard");
            smax=Math.max(smax,s);pos=add(pos,array(s,8));comp=add(comp,array(m,8));long bands=(s+1)/2;
            credible=add(credible,add(32,add(list(bands),mul(bands,32))));temporary=Math.max(temporary,add(mul(2,list(s)),add(array(s,1),array(s,8))));
            if(i+1<n){int t=i+1==actual?1:b.stateRows[i+2]-b.stateRows[i+1];long p=mul(s,t);pairs=add(pairs,p);if(i==0)pfirst=p;plast=p;pmax=Math.max(pmax,p);
                messages=add(messages,mul(2,grid(s,t,8)));long f=add(add(grid(s,t,8),grid(s,t,4)),mul(p,list(1)));frontier=Math.max(frontier,add(previous,f));previous=f;}
        }
        if(pairs>8_000_000)throw new IllegalArgumentException("Original pair cap");
        long h=messages;
        h=add(h,add(72,add(array(n,4),array(pairs,1))));h=add(h,array(plast,8));h=add(h,frontier);
        h=add(h,mul(80,add(Math.max(mul(2,pfirst),mul(2,pmax)),n)));h=add(h,add(mul(2,list(pmax)),mul(3,list(1))));
        h=add(h,add(64,add(mul(2,array(1,4)),mul(2,array(1,8)))));
        long materialized=add(64,add(mul(2,array(n,4)),add(mul(2,list(n)),add(mul(n,32),list(1)))));
        long branch=add(64,add(array(Math.max(16,2*n),8),mul(n,add(56,24))));
        long outputs=add(mul(4,pos),add(mul(2,comp),add(credible,add(temporary,add(branch,materialized)))));
        outputs=add(outputs,144+40+64+56+24);outputs=add(outputs,add(mul(3,list(n)),add(mul(3,list(1)),list(2))));h=add(h,outputs);
        h=add(h,add(72,add(mul(2,array(smax,8)),add(array(smax,1),add(56,add(mul(2,array(pmax,8)),array(pmax,1)))))));
        return add(h,96+56+424);
    }
}
