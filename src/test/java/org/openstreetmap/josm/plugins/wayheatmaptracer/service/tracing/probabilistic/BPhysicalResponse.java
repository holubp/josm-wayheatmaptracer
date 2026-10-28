package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Singleton physical-query stage;never a chord or general integral certificate. */
final class BPhysicalResponse extends BPhysicalResponseBank implements AutoCloseable {
    static OwnedLocal openCapturedOwn(BPhysicalWork work,BSharedPositionEvidence.CapturedInput input) {
        work.prepayCapturedOwnEntry();
        work.claim();
        var bound=BSharedPositionEvidence.bindCaptured(input,work.owner(),work.probe(),work.sourceWork());
        AttemptMemoryLedger.Owner child=null;
        try {
            var view=new BorrowedSourceView(work,bound);
            child=work.owner().child("captured-own-local-response");
            child.reserve(4200+2048);
            var response=new BPhysicalResponse(work,view,child);
            work.install(view);
            return new OwnedLocal(response);
        } catch(RuntimeException|Error primary) {
            if(child!=null)try{child.close();}catch(RuntimeException|Error secondary){/*preserve primary*/}
            try{bound.close();}catch(RuntimeException|Error secondary){/*preserve primary*/}
            throw primary;
        }
    }
    sealed interface OwnAccess extends AutoCloseable permits OwnedLocal,TableLocal {
        void requireWork(BPhysicalWork work);
        int originalRow(BIdentificationFacts.ProfileFacts facts,ProbabilisticStateLattice lattice,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe probe);
        int originalMode(int row,ObservationComponent component);
        void prepayKernel(int components,int states,int samples,int modes);
        void begin();void end(boolean normal);
        void query(int row,int mode,int state);Reason reason();double value();
        void close();
    }
    static final class OwnedLocal implements OwnAccess {
        private final BPhysicalResponse response;
        private int reads;
        private boolean valid,closed,failed;
        private OwnedLocal(BPhysicalResponse response){this.response=response;}
        public void requireWork(BPhysicalWork work){
            if(closed||failed||response.work!=work)throw new IllegalArgumentException("Foreign or closed own-local field");
            response.view.ensureSourceLive();
        }
        public int originalRow(BIdentificationFacts.ProfileFacts facts,ProbabilisticStateLattice lattice,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe probe) {
            requireWork(response.work);response.work.sourceWork().requireProbe(probe);
            int row=facts.baseline().profileIndex();var input=response.view.bound.input;
            if(row<0||row>=input.facts.size()||input.facts.get(row)!=facts||input.lattices.get(row)!=lattice)
                throw new IllegalArgumentException("Facts/lattice are not this original capture row");
            return row;
        }
        public int originalMode(int row,ObservationComponent component) {
            requireWork(response.work);var b=response.view.bound;var modes=b.input.facts.get(row).modes();
            for(int m=0;m<modes.size();m++)if(modes.get(m).lineage().equals(component.evidenceLineage())) {
                if(b.indices[2][b.rows[row]+m]!=BSharedPositionEvidence.Status.AVAILABLE.ordinal())return -1;
                return m;
            }
            return -1;
        }
        public void prepayKernel(int c,int s,int samples,int modes){response.work.prepayLocalKernel(c,s,samples,modes);}
        public void begin(){requireWork(response.work);}
        public void end(boolean normal){response.work.live();}
        public void query(int row,int mode,int state){
            valid=false;reads=0;
            if(closed||failed)throw new IllegalStateException("Own-local handle is closed or failed");
            failed=true;
            response.work.capturedOwnQuery();response.ownLocal(row,mode,state);
            valid=true;failed=false;
        }
        private void ready(){
            if(closed||failed||!valid||reads==8)throw new IllegalStateException("No current own-local receipt or read budget");
            reads++;
        }
        public Reason reason(){ready();return response.reason();}
        public double value(){ready();return response.value();}
        double lower(){ready();return response.lower();}
        double upper(){ready();return response.upper();}
        double error(){ready();return response.error();}
        @Override public void close(){valid=false;closed=true;response.close();}
    }
    /** Issued only by the actual table's private pre-graph permit;never owns its source. */
    static final class TableLocal implements OwnAccess {
        private final BPhysicalResponse response;
        private final BPhysicalPairTable.Permit permit;
        private int reads;
        private boolean valid,closed,failed;
        private TableLocal(BPhysicalResponse r,BPhysicalPairTable.Permit p){response=r;permit=p;}
        public void requireWork(BPhysicalWork work){
            if(closed||failed||response.work!=work)throw new IllegalArgumentException("Foreign or closed borrowed local field");
            permit.requireLocal(response,work);response.view.ensureSourceLive();
        }
        public int originalRow(BIdentificationFacts.ProfileFacts facts,ProbabilisticStateLattice lattice,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe probe){
            requireWork(response.work);response.work.sourceWork().requireProbe(probe);
            int row=facts.baseline().profileIndex();var input=response.view.bound.input;
            if(row<0||row>=input.facts.size()||input.facts.get(row)!=facts||input.lattices.get(row)!=lattice)
                throw new IllegalArgumentException("Not this original captured row");return row;
        }
        public int originalMode(int row,ObservationComponent component){
            requireWork(response.work);var b=response.view.bound;var modes=b.input.facts.get(row).modes();
            for(int m=0;m<modes.size();m++)if(modes.get(m).lineage().equals(component.evidenceLineage()))
                return b.indices[2][b.rows[row]+m]==BSharedPositionEvidence.Status.AVAILABLE.ordinal()?m:-1;
            return -1;
        }
        public void prepayKernel(int c,int s,int samples,int modes){requireWork(response.work);response.work.prepayTableLocalKernel(permit,c,s,samples,modes);}
        public void begin(){requireWork(response.work);permit.beginLocal();}
        public void end(boolean normal){permit.endLocal(normal);}
        public void query(int row,int mode,int state){
            valid=false;reads=0;requireWork(response.work);failed=true;
            response.work.capturedTableOwnQuery(permit);response.ownLocal(row,mode,state,permit);
            valid=true;failed=false;
        }
        private void ready(){if(closed||failed||!valid||reads==8)throw new IllegalStateException("No borrowed local result");
            requireWork(response.work);reads++;}
        public Reason reason(){ready();return response.reason();}
        public double value(){ready();return response.value();}
        public void close(){valid=false;closed=true;} //The table retains its response/Bound.
    }
    TableLocal borrowLocal(BPhysicalPairTable.Permit permit){
        permit.requireLocal(this,work);work.prepayTableBody(256);work.sourceWork().metadata(40);
        return new TableLocal(this,permit); //2refs+int+3flags=23/O40 BEFORE new.
    }
    enum Reason { AVAILABLE, OBSERVED_ZERO, GEOMETRIC_ZERO, SOURCE_UNKNOWN, UNRESOLVED, INELIGIBLE }
    private static final Reason[] REASONS=Reason.values();
    private final byte[] flags,tags;
    private final SourceBank source;
    private final SourceView view;
    private final AttemptMemoryLedger.Owner allocation;
    private int row,mode,reason,phase;
    // Inherited5arrays+work,2bytearrays+source+view+owner =>11refs,4ints:
    // accepted component2048B including every43-cell plane and this shell.
    private BPhysicalResponse(BPhysicalWork work,SourceView view,AttemptMemoryLedger.Owner allocation) {
        super(work,43);flags=new byte[43];tags=new byte[43];this.view=view;this.allocation=allocation;
        source=new SourceBank(work,view);
    }
    static BPhysicalResponse open(BPhysicalWork work,EvidenceSnapshot snapshot,String field,
            List<ProbabilisticProfile> profiles,List<ProbabilisticStateLattice> lattices) {
        return open(work,snapshot,field,profiles,lattices,null);
    }
    static BPhysicalResponse openCapturedTable(BPhysicalWork work,BSharedPositionEvidence.CapturedInput input){
        work.prepayCapturedTableEntry();return open(work,null,null,null,null,input);
    }
    private static BPhysicalResponse open(BPhysicalWork work,EvidenceSnapshot snapshot,String field,
            List<ProbabilisticProfile> profiles,List<ProbabilisticStateLattice> lattices,BSharedPositionEvidence.CapturedInput captured) {
        return open(work,snapshot,field,profiles,lattices,captured,null);
    }
    static BPhysicalResponse openExploratoryTable(BPhysicalWork work,BSharedFeasibilityInputs.OwnedSource source){
        source.require(work);work.prepayCapturedTableEntry();
        return open(work,null,null,null,null,null,source);
    }
    private static BPhysicalResponse open(BPhysicalWork work,EvidenceSnapshot snapshot,String field,
            List<ProbabilisticProfile> profiles,List<ProbabilisticStateLattice> lattices,BSharedPositionEvidence.CapturedInput captured,
            BSharedFeasibilityInputs.OwnedSource exploratory) {
        var registry=work.tableRegistry();if(registry!=null)registry.begin(BPhysicalTableRegistry.BIND);
        boolean normal=false;BPhysicalResponse result=null;Throwable primary=null;
        try {
            result=exploratory!=null?openExploratorySource(work,exploratory):
                captured==null?openSource(work,snapshot,field,profiles,lattices):openCapturedSource(work,captured);
            if(registry!=null){registry.begin(BPhysicalTableRegistry.VIEW);boolean published=false;
                try{registry.publishView(result.panelSource());published=true;}
                catch(RuntimeException|Error failure){primary=failure;throw failure;}
                finally{try{registry.end(BPhysicalTableRegistry.VIEW,published);}
                    catch(RuntimeException|Error failure){if(primary==null)throw failure;}}}
            normal=true; //Factual body completion,not handle transfer.
        } catch(RuntimeException|Error failure){primary=failure;throw failure;}
        finally {
            try{if(registry!=null)registry.end(BPhysicalTableRegistry.BIND,normal);}
            catch(RuntimeException|Error failure){if(primary==null){primary=failure;throw failure;}}
            finally{if(primary!=null&&result!=null)try{result.close();}
                catch(RuntimeException|Error cleanup){/*Primary propagates unchanged;no suppressed allocation.*/}}
        }
        return result; //Only after every fallible completion has succeeded.
    }
    private static BPhysicalResponse openCapturedSource(BPhysicalWork work,BSharedPositionEvidence.CapturedInput input){
        work.claim();var bound=BSharedPositionEvidence.bindCaptured(input,work.owner(),work.probe(),work.sourceWork());
        AttemptMemoryLedger.Owner child=null;
        try{var view=new BorrowedSourceView(work,bound);child=work.owner().child("captured-table-source-component");
            child.reserve(4200+2048);var response=new BPhysicalResponse(work,view,child);work.install(view);return response;
        }catch(RuntimeException|Error primary){
            if(child!=null)try{child.close();}catch(RuntimeException|Error secondary){/*preserve primary*/}
            try{bound.close();}catch(RuntimeException|Error secondary){/*preserve primary*/}throw primary;
        }
    }
    private static BPhysicalResponse openExploratorySource(BPhysicalWork work,BSharedFeasibilityInputs.OwnedSource source){
        source.require(work);work.claim();var bound=BSharedPositionEvidence.bindExploratory(source,work);
        AttemptMemoryLedger.Owner child=null;
        try{var view=new SourceView(work,bound);child=work.owner().child("exploratory-table-response");
            child.reserve(4200+2048);var response=new BPhysicalResponse(work,view,child);work.install(view);return response;}
        catch(RuntimeException|Error primary){
            if(child!=null)try{child.close();}catch(RuntimeException|Error secondary){/* preserve primary */}
            try{bound.close();}catch(RuntimeException|Error secondary){/* preserve primary */}throw primary;}
    }
    private static BPhysicalResponse openSource(BPhysicalWork work,EvidenceSnapshot snapshot,String field,
            List<ProbabilisticProfile> profiles,List<ProbabilisticStateLattice> lattices) {
        var old=work.sourceWork();
        work.claim();
        var authority=new BIdentificationFactsExtractor().extractSharedPosition(snapshot,field,profiles,
                work.probe(),work.owner(),old);
        try {
            var input=BSharedPositionEvidence.Input.create(snapshot,field,authority,authority.facts(),lattices,
                    snapshot.decisionRegion(),BSharedPositionEvidence.approvedContract(),old);
            var bound=BSharedPositionEvidence.bind(input,work.owner(),work.probe(),old);
            var view=new SourceView(work,bound);
            var child=work.owner().child("physical-response-source-component");
            try {
                child.reserve(4200+2048); //BEFORE every bank/array/response allocation
                var response=new BPhysicalResponse(work,view,child);
                work.install(view);return response;
            } catch(RuntimeException|Error failure){child.close();bound.close();throw failure;}
        } catch(RuntimeException|Error failure){authority.close();throw failure;}
    }
    BSharedPositionEvidence.Bound bound(){view.ensureSourceLive();return view.bound;}
    SourceView panelSource(){view.ensureSourceLive();return view;}
    int sourceWidth(){view.ensureSourceLive();return source.width;}
    int sourceHeight(){view.ensureSourceLive();return source.height;}
    int descriptorCount(){view.ensureSourceLive();return view.bound.indices[2].length;}
    int componentStatus(int at){return view.index(2,at);}
    void componentAt(int at,double x,double y){phase=0;int r=view.index(0,at),m=view.index(1,at);query(r,m,x,y);}
    // Only the original final Context with an active private translation phase
    // can issue these lanes. No caller coordinates, errors or authority flags.
    void componentAtContext(int at,BPhysicalContext context) {
        phase=0;
        if(context==null)throw new IllegalArgumentException("Missing translated-query producer");
        context.requireFor(this);
        int r=view.index(0,at),m=view.index(1,at);
        query(r,m,context.n[14],context.n[15],-1,context);
    }
    void ownLocal(int row,int mode,int state) {
        ownLocal(row,mode,state,null);
    }
    private void ownLocal(int row,int mode,int state,BPhysicalPairTable.Permit permit) {
        phase=0; //Invalidation precedes EVERY fallible precheck/lookup.
        view.ensureSourceLive();var b=view.bound;
        if(row<0||row>=b.rows.length-1||state<0||state>=b.stateRows[row+1]-b.stateRows[row])throw new IndexOutOfBoundsException();
        query(row,mode,b.stateX[b.stateRows[row]+state],b.stateY[b.stateRows[row]+state],state,null,permit);
    }
    void query(int row,int mode,double x,double y) {
        query(row,mode,x,y,-1,null);
    }
    private void query(int row,int mode,double x,double y,int ownState,BPhysicalContext context) {
        query(row,mode,x,y,ownState,context,null);
    }
    private void query(int row,int mode,double x,double y,int ownState,BPhysicalContext context,BPhysicalPairTable.Permit permit) {
        phase=0; //A refused new entry must never expose the previous result.
        if(permit==null)work.startQuery();else work.startTableOwnQuery(permit);
        work.admit(BPhysicalWork.COMPONENT);
        try {
            work.poll();this.row=row;this.mode=mode;var b=view.bound;
            if(row<0||row>=b.rows.length-1||mode<0||mode>=b.rows[row+1]-b.rows[row])throw new IndexOutOfBoundsException();
            int at=b.rows[row]+mode;
            if(b.indices[2][at]!=BSharedPositionEvidence.Status.AVAILABLE.ordinal()) {finish(Reason.INELIGIBLE,0,0,0,context);return;}
            for(int i=0;i<11;i++)constant(i,view.descriptor(i,at));
            constant(11,x);constant(12,y);
            work.pointCouplingGuard();
            if(context!=null) {
                // Recheck AFTER the same-probe callback and BEFORE borrowing
                // its lanes: root/source liveness alone is not Context liveness.
                context.requireFor(this);copy(11,context,14);copy(12,context,15);
            }
            work.geometryGuard();boolean exactFamily=exactGeometry(b.grid,x,y,n[0],n[1],n[2],n[3]);
            if(ownState>=0) {
                var profile=b.input.facts.get(row).baseline();
                double offset=b.input.lattices.get(row).cells().get(ownState).offsetMeters();
                ownCoordinate(11,x,profile.anchor().xMeters(),profile.normalUnit().xMeters(),offset,
                    exactFamily&&exactHalfGrid(offset,b.grid.rho*.5));
                ownCoordinate(12,y,profile.anchor().yMeters(),profile.normalUnit().yMeters(),offset,
                    exactFamily&&exactHalfGrid(offset,b.grid.rho*.5));
            }
            flags[0]=(byte)(exactFamily&&lo[11]==hi[11]&&lo[12]==hi[12]?1:0);
            project(13,14,11,12);
            boolean nominalOutside=n[13]<n[9]||n[13]>n[10];
            boolean referenceOutside=hi[13]<n[9]||lo[13]>n[10];
            if(nominalOutside&&referenceOutside) {finish(Reason.GEOMETRIC_ZERO,0,0,0,context);return;}
            // A closed-cap branch may differ between the nominal program and
            // real-coordinate enclosure. Keep both traces, without narrowing
            // coordinates: the reference response is a union with zero.
            boolean mixedCap=nominalOutside||lo[13]<n[9]||hi[13]>n[10];
            sub(15,13,4);absolute(15,15);absolute(16,14);
            div(15,15,7);div(16,16,8);constant(17,1);
            sub(15,17,15);positive(15,15);sub(16,17,16);positive(16,16);
            if(hi[15]==0||hi[16]==0){finish(Reason.GEOMETRIC_ZERO,0,0,0,context);return;}
            for(int by=-1;by<=1;by++)for(int ax=-1;ax<=1;ax++) {
                int tap=(by+1)*3+ax+1;work.poll();
                if(!source.sample(this,ax,by,flags[0]!=0)) {
                    finish(source.reason==1?Reason.SOURCE_UNKNOWN:Reason.UNRESOLVED,0,0,0,context);return;
                }
                copy(18,source,0);copy(19,source,1);project(20,-1,18,19);
                boolean tapNominalOutside=n[20]<n[9]||n[20]>n[10];
                boolean tapReferenceOutside=hi[20]<n[9]||lo[20]>n[10];
                if(tapNominalOutside&&tapReferenceOutside)constant(21+tap,0);
                else {
                    copy(21+tap,source,29);sub(21+tap,21+tap,5);sub(21+tap,21+tap,6);positive(21+tap,21+tap);
                    if(tapNominalOutside||lo[20]<n[9]||hi[20]>n[10]){
                        int slot=21+tap;mixedCap=true;
                        set(slot,tapNominalOutside?0:n[slot],0,tapReferenceOutside?0:Math.max(0,hi[slot]));
                    }
                }
            }
            constant(33,2);constant(34,4);
            for(int i=0;i<3;i++) {mul(30+i,22+3*i,33);add(30+i,21+3*i,30+i);add(30+i,30+i,23+3*i);div(30+i,30+i,34);}
            mul(31,31,33);add(30,30,31);add(30,30,32);div(30,30,34);
            mul(35,16,15);mul(35,35,30);
            double value=nominalOutside?0:n[35];
            double lower=referenceOutside?0:mixedCap?Math.min(0,lo[35]):lo[35];
            double upper=referenceOutside?0:mixedCap?Math.max(0,hi[35]):hi[35];
            finish(value==0&&!mixedCap?Reason.OBSERVED_ZERO:Reason.AVAILABLE,value,lower,upper,context);
        } catch(RuntimeException|Error failure) {phase=0;work.complete(BPhysicalWork.COMPONENT,false);throw failure;}
        finally { }
    }
    private void ownCoordinate(int slot,double nominal,double anchor,double normal,double offset,boolean exactOwn) {
        // EXACT original zero-offset branch; otherwise enclose its real retained
        // operands without replacing the nominal state point already bound.
        if(offset==0){constant(slot,anchor);return;}
        double product=normal*offset;
        if(normal!=0&&offset!=0&&product==0)throw new ArithmeticException("Own-coordinate product underflow");
        //Only the private original-state producer supplies exactOwn. The full
        //existing class proves normal=0/+-1 and anchor=h*ka; offset proof adds
        //offset=h*ko,|ka|,|ko|<=2^50. The original product and sum have integer
        //coefficient <=2^51 (<53 bits),h exponent [-1022,970];no under/overflow.
        //This certifies the ORIGINAL sum,not an interval narrowed from nominal.
        if(exactOwn){constant(slot,nominal);return;}
        set(slot,nominal,Math.nextDown(anchor+Math.nextDown(product)),
                Math.nextUp(anchor+Math.nextUp(product)));
    }
    private void project(int u,int z,int x,int y) {
        sub(36,x,0);sub(37,y,1);mul(38,36,2);mul(39,37,3);add(u,38,39);
        if(z>=0){constant(40,-n[3]);mul(38,36,40);mul(39,37,2);add(z,38,39);}
        // The admitted dyadic class proves these ORIGINAL nominal operations
        // exact. Source interpolation still has its separate outward error.
        if(flags[0]!=0){constant(u,n[u]);if(z>=0)constant(z,n[z]);}
    }
    /** Sufficient arithmetic class, never a fixture-name or epsilon predicate.
     * h=rho/2 is normal and a power of two. Four input coordinates are h*k,
     * |k|<=2^50. Nine-tap translation changes k by at most2 per axis; the
     * anchor difference therefore has <53 significant bits. rho exponent
     * <=971 keeps every such translated difference below2^1023. Axis-unit
     * normals make both dot products exact (one term is zero). Thus q/tap
     * translation and own projection agree with the real retained operands.
     */
    static boolean exactGeometry(BSharedPositionEvidence.Grid g,double x,double y,
            double ax,double ay,double nx,double ny) {
        if(g.uy!=0||g.vx!=0||Math.abs(g.ux)!=g.rho||Math.abs(g.vy)!=g.rho)return false;
        if(!((nx==0&&Math.abs(ny)==1)||(ny==0&&Math.abs(nx)==1)))return false;
        long bits=Double.doubleToRawLongBits(g.rho);int exponent=(int)((bits>>>52)&2047);
        if(bits<0||(bits&0xfffffffffffffL)!=0||exponent<2||exponent>1994)return false;
        double h=g.rho*.5;
        return exactHalfGrid(x,h)&&exactHalfGrid(y,h)&&exactHalfGrid(ax,h)&&exactHalfGrid(ay,h);
    }
    private static boolean exactHalfGrid(double value,double h) {
        double k=value/h;
        return Double.isFinite(k)&&Math.abs(k)<=0x1p50&&StrictMath.rint(k)==k&&k*h==value;
    }
    private void finish(Reason why,double value,double lower,double upper,BPhysicalContext context) {
        //No stale result:final SAME-probe+original authority check BEFORE publish.
        work.poll();if(context!=null)context.requireFor(this);
        constant(41,value);lo[41]=lower;hi[41]=upper;
        error[41]=Math.nextUp(Math.max(Math.abs(value-lower),Math.abs(upper-value)));
        reason=why.ordinal();phase=1;work.complete(BPhysicalWork.COMPONENT,true);
    }
    Reason reason(){ready();return REASONS[reason];}
    double value(){ready();return reason<=Reason.GEOMETRIC_ZERO.ordinal()?n[41]:Double.NaN;}
    double error(){ready();return error[41];}
    double lower(){ready();return lo[41];}
    double upper(){ready();return hi[41];}
    private void ready(){view.ensureSourceLive();if(phase!=1)throw new IllegalStateException("No current physical response");}
    @Override public void close(){allocation.close();view.close();}

    /** No mutable arrays/sampler exposed.ONLY real original bind return can issue it. */
    static class SourceView implements AutoCloseable {
        private final BPhysicalWork work;final BSharedPositionEvidence.Bound bound;private boolean closed;
        private SourceView(BPhysicalWork work,BSharedPositionEvidence.Bound bound){this.work=work;this.bound=bound;}
        void ensureSourceLive(){if(closed)throw new IllegalStateException("Physical source view closed");bound.ensureOpen();}
        int index(int column,int at){work.admit(BPhysicalWork.DESCRIPTOR);try {ensureSourceLive();int v=bound.indices[column][at];work.stat(3);work.complete(BPhysicalWork.DESCRIPTOR,true);return v;}catch(RuntimeException|Error x){work.complete(BPhysicalWork.DESCRIPTOR,false);throw x;}}
        double descriptor(int column,int mode){work.admit(BPhysicalWork.DESCRIPTOR);try {ensureSourceLive();double v=bound.d[column][mode];work.stat(3);work.complete(BPhysicalWork.DESCRIPTOR,true);return v;}catch(RuntimeException|Error x){work.complete(BPhysicalWork.DESCRIPTOR,false);throw x;}}
        double scalar(int index){work.admit(BPhysicalWork.SCALAR);try {ensureSourceLive();double v=bound.source[index];work.stat(0);work.complete(BPhysicalWork.SCALAR,true);return v;}catch(RuntimeException|Error x){work.complete(BPhysicalWork.SCALAR,false);throw x;}}
        boolean mask(int index,boolean cell){work.admit(BPhysicalWork.MASK);try {ensureSourceLive();boolean v=cell?bound.interpolation[index]:bound.valid[index];work.stat(1);work.complete(BPhysicalWork.MASK,true);return v;}catch(RuntimeException|Error x){work.complete(BPhysicalWork.MASK,false);throw x;}}
        double coordinate(int ring,int point,boolean east){work.admit(BPhysicalWork.REGION);try {ensureSourceLive();var p=bound.input.evidence.evidenceRegion().polygons().get(ring).get(point);double v=east?p.xMeters():p.yMeters();work.stat(4);work.complete(BPhysicalWork.REGION,true);return v;}catch(RuntimeException|Error x){work.complete(BPhysicalWork.REGION,false);throw x;}}
        @Override public void close(){closed=true;bound.close();bound.input.authority.close();}
    }
    /** Private constructor/callgraph: never transfers caller facts/graph ownership. */
    private static final class BorrowedSourceView extends SourceView {
        private BorrowedSourceView(BPhysicalWork work,BSharedPositionEvidence.Bound bound){super(work,bound);}
        @Override public void close(){super.closed=true;bound.close();}
    }

    /** Accepted4200B:5*77double/2*77byte/2*35double/2*35byte,13refs/6ints. */
    private static final class SourceBank extends BPhysicalResponseBank {
        private final byte[] nominalFlags,referenceFlags,regionNominal,regionReference;
        private final double[] regionLower,regionUpper;
        private final SourceView view;
        private int width,height,cellX,cellY,reason,signature;
        SourceBank(BPhysicalWork work,SourceView view) {
            super(work,77);this.view=view;
            nominalFlags=new byte[77];referenceFlags=new byte[77];regionLower=new double[35];regionUpper=new double[35];
            regionNominal=new byte[35];regionReference=new byte[35];
            var f=view.bound.input.evidence.fields().get(view.bound.input.fieldKey);width=f.width();height=f.height();
        }
        boolean sample(BPhysicalResponse caller,int ax,int by,boolean exactGeometry) {
            work.admit(BPhysicalWork.SOURCE);boolean returned=false;
            try {
                reason=0;work.poll();var g=view.bound.grid;var t=view.bound.input.evidence.transform();
                //Borrow the SAME authenticated response's coupled query lanes;
                //never seed rounded own-state coordinates as exact real input.
                copy(0,caller,11);copy(1,caller,12);constant(2,ax);constant(3,by);
                constant(4,g.ux);constant(5,g.uy);constant(6,g.vx);constant(7,g.vy);
                mul(8,2,4);add(0,0,8);mul(8,3,6);add(0,0,8);
                mul(8,2,5);add(1,1,8);mul(8,3,7);add(1,1,8);
                if(exactGeometry){constant(0,n[0]);constant(1,n[1]);}
                if(!contains(n[0],n[1])){reason=1;returned=true;return false;}
                constant(2,t.origin().xMeters());constant(3,t.origin().yMeters());
                constant(4,t.xAxisEastMetersPerSourcePixel());constant(5,t.xAxisNorthMetersPerSourcePixel());
                constant(6,t.yAxisEastMetersPerSourcePixel());constant(7,t.yAxisNorthMetersPerSourcePixel());
                constant(8,t.originKind()==RasterMetricTransform.OriginKind.MANAGED_SOURCE_PIXEL_BOUNDARY?.5:0);
                constant(9,t.axisUnit()==RasterMetricTransform.AxisUnit.SOURCE_PIXEL?t.rasterPixelsPerSourcePixel():1);
                sub(10,0,2);sub(11,1,3);mul(12,4,7);mul(13,5,6);sub(12,12,13);
                mul(13,10,7);mul(14,11,6);sub(13,13,14);div(15,13,12);sub(15,15,8);mul(15,15,9);
                mul(13,11,4);mul(14,10,5);sub(13,13,14);div(16,13,12);sub(16,16,8);mul(16,16,9);
                double rx=n[15],ry=n[16];
                if(rx<0||ry<0||rx>width-1||ry>height-1){reason=1;returned=true;return false;}
                cellX=Math.min(width-2,(int)StrictMath.floor(rx));cellY=Math.min(height-2,(int)StrictMath.floor(ry));
                int x0=(int)StrictMath.floor(rx),y0=(int)StrictMath.floor(ry);
                int x1=Math.min(width-1,x0+1),y1=Math.min(height-1,y0+1);
                if(!view.mask(cellY*(width-1)+cellX,true)||!view.mask(y0*width+x0,false)||!view.mask(y0*width+x1,false)
                        ||!view.mask(y1*width+x0,false)||!view.mask(y1*width+x1,false)){reason=1;returned=true;return false;}
                if(lo[15]<0||lo[16]<0||hi[15]>width-1||hi[16]>height-1){reason=2;returned=true;return false;}
                //Uniform6eps Lipschitz source lemma across certified cells. Refuse
                //uncertified cells;never relabel certificate width as measuredunknown.
                int lx=(int)StrictMath.floor(lo[15]),hx=Math.min(width-2,(int)StrictMath.floor(hi[15]));
                int ly=(int)StrictMath.floor(lo[16]),hy=Math.min(height-2,(int)StrictMath.floor(hi[16]));
                lx=Math.min(width-2,lx);ly=Math.min(height-2,ly);
                for(int iy=0;iy<height-1;iy++)for(int ix=0;ix<width-1;ix++) {
                    work.poll();if(ix<lx||ix>hx||iy<ly||iy>hy)continue;
                    if(!view.mask(iy*(width-1)+ix,true)||!view.mask(iy*width+ix,false)||!view.mask(iy*width+ix+1,false)
                            ||!view.mask((iy+1)*width+ix,false)||!view.mask((iy+1)*width+ix+1,false)){reason=2;returned=true;return false;}
                }
                constant(20,view.scalar(y0*width+x0));constant(21,view.scalar(y0*width+x1));
                constant(22,view.scalar(y1*width+x0));constant(23,view.scalar(y1*width+x1));
                //Exact original nominal subtractive interpolation grouping.
                double fx=rx-x0,fy=ry-y0;
                double top=n[20]+fx*(n[21]-n[20]),bottom=n[22]+fx*(n[23]-n[22]);
                double value=top+fy*(bottom-top);
                if(!Double.isFinite(value))throw new ArithmeticException("Physical source nonfinite");
                double dx=Math.nextUp(Math.max(Math.abs(rx-lo[15]),Math.abs(hi[15]-rx)));
                double dy=Math.nextUp(Math.max(Math.abs(ry-lo[16]),Math.abs(hi[16]-ry)));
                double err=Math.nextUp(Math.nextUp(dx+dy)+6*Math.scalb(1.0,-52));
                set(29,value,Math.max(0,Math.nextDown(value-err)),Math.min(1,Math.nextUp(value+err)));
                work.stat(5);work.poll();returned=true;return true;
            } finally {work.complete(BPhysicalWork.SOURCE,returned);}
        }
        private boolean contains(double x,double y) {
            var polygons=view.bound.input.evidence.evidenceRegion().polygons();
            for(int p=0;p<polygons.size();p++) {
                boolean inside=false;int count=polygons.get(p).size();
                for(int current=0,previous=count-1;current<count;previous=current++) {
                    work.poll();double ax=view.coordinate(p,previous,true),ay=view.coordinate(p,previous,false);
                    double bx=view.coordinate(p,current,true),by=view.coordinate(p,current,false);
                    if(x>=Math.min(ax,bx)-1e-9&&x<=Math.max(ax,bx)+1e-9
                            &&y>=Math.min(ay,by)-1e-9&&y<=Math.max(ay,by)+1e-9) {
                        double cross=(bx-ax)*(y-ay)-(by-ay)*(x-ax);
                        double scale=Math.max(1,StrictMath.hypot(bx-ax,by-ay));
                        if(Math.abs(cross)<=1e-9*scale)return true;
                    }
                    if((ay>y)!=(by>y)&&x<(bx-ax)*(y-ay)/(by-ay)+ax)inside=!inside;
                }
                if(inside)return true;
            }
            return false;
        }
    }
}

/** Allocation-free singleton nominal/exact-real interval transfers;no RN polynomial. */
abstract class BPhysicalResponseBank {
    final BPhysicalWork work;final double[] n,p,lo,hi,error;
    BPhysicalResponseBank(BPhysicalWork work,int size) {
        this.work=work;n=new double[size];p=new double[size];lo=new double[size];hi=new double[size];error=new double[size];
    }
    final void set(int o,double nominal,double lower,double upper) {
        if(!Double.isFinite(nominal)||!Double.isFinite(lower)||!Double.isFinite(upper)||lower>upper)
            throw new ArithmeticException("Uncertified physical interval");
        n[o]=nominal;p[o]=nominal;lo[o]=lower;hi[o]=upper;
        error[o]=Math.nextUp(Math.max(Math.abs(nominal-lower),Math.abs(upper-nominal)));work.stat(2);
    }
    final void constant(int o,double v){set(o,v,v,v);}
    final void copy(int o,BPhysicalResponseBank a,int i){set(o,a.n[i],a.lo[i],a.hi[i]);}
    final void add(int o,int a,int b){work.poll();set(o,n[a]+n[b],Math.nextDown(lo[a]+lo[b]),Math.nextUp(hi[a]+hi[b]));}
    final void sub(int o,int a,int b){work.poll();set(o,n[a]-n[b],Math.nextDown(lo[a]-hi[b]),Math.nextUp(hi[a]-lo[b]));}
    final void mul(int o,int a,int b) {
        work.poll();double nominal=n[a]*n[b];positiveProduct(n[a],n[b],nominal);
        double aa=lo[a]*lo[b],ab=lo[a]*hi[b],ba=hi[a]*lo[b],bb=hi[a]*hi[b];
        set(o,nominal,Math.nextDown(Math.min(Math.min(aa,ab),Math.min(ba,bb))),Math.nextUp(Math.max(Math.max(aa,ab),Math.max(ba,bb))));
    }
    final void div(int o,int a,int b) {
        work.poll();if(lo[b]<=0&&hi[b]>=0)throw new ArithmeticException("Uncertified physical denominator");
        double nominal=n[a]/n[b];if(n[a]>0&&n[b]>0&&nominal==0)throw new ArithmeticException("Positive quotient underflow");
        double aa=lo[a]/lo[b],ab=lo[a]/hi[b],ba=hi[a]/lo[b],bb=hi[a]/hi[b];
        set(o,nominal,Math.nextDown(Math.min(Math.min(aa,ab),Math.min(ba,bb))),Math.nextUp(Math.max(Math.max(aa,ab),Math.max(ba,bb))));
    }
    final void absolute(int o,int a){work.poll();set(o,Math.abs(n[a]),lo[a]<=0&&hi[a]>=0?0:Math.min(Math.abs(lo[a]),Math.abs(hi[a])),Math.max(Math.abs(lo[a]),Math.abs(hi[a])));}
    final void positive(int o,int a){work.poll();set(o,Math.max(0,n[a]),Math.max(0,lo[a]),Math.max(0,hi[a]));}
    static void positiveProduct(double a,double b,double result){if(a>0&&b>0&&result==0)throw new ArithmeticException("Positive product underflow");}
}
