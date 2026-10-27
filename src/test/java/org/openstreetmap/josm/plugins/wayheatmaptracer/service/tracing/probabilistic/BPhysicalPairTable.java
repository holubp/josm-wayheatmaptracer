package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;
import java.util.ArrayList;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceReadView;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Complete TABLE_ONLY capability; unimplemented entry publishes no result. */
final class BPhysicalPairTable implements AutoCloseable,java.util.function.LongConsumer {
    static final String APPROXIMATION_ID="physical-table-adjacent-source-range-v3";
    static final int CERTIFIED=1,NOT_ADMITTED=2;
    //A bounded UNIT data entry,not runtime admission or a global ENERGY event.
    //Six transitive ready/source/owner/probe guards <=MAX_LIVE each;512
    //covers the fixed identity/cap/dispatch/constructor/return control body.
    //The optional stored-row forwarding prefix has six operand/result reads,
    //one delegate API slot and one return. The common entry prepays that8-unit
    //source upper before ready/numerical work, after those fixed prefix reads.
    //Direct callers also pay the same conservative common-entry upper.
    static final long STORED_SOLVER_FORWARD_UNITS=8;
    static final long SOLVER_ENTRY_UNITS=512+6*BPhysicalWork.MAX_LIVE+STORED_SOLVER_FORWARD_UNITS;
    static final int SOLVER_PREFLIGHTS=8;
    static final long SOLVER_ENTRY_UPPER=(SOLVER_PREFLIGHTS+1L)*SOLVER_ENTRY_UNITS;
    static final long SOLVER_HEADROOM_SETUP=BPhysicalWork.MAX_LIVE+256;
    //One new reference default/caller store,paid BEFORE every table new.
    static final long FRAME=776,METADATA_REFERENCE_INIT=3;
    private final BPhysicalWork work;
    private final BPhysicalTableRegistry registry;
    private final BPhysicalResponse response;
    private final AttemptMemoryLedger.Owner owner;
    private List<InferenceProfile> graph;
    private double[] values,errors,unknown;
    private int[] status,offsets,widths;
    private long[] rowErrors;
    private View[] views;
    private final Permit permit;
    private final MutableSupport support;
    private final Budget budget;
    private final PublicationBounds publication;
    private final EvidenceModelParameters parameters;
    private final BExperimentalScalarCapture.AcquisitionPath acquisition;
    private AttemptMemoryLedger.Reservation solverHeadroom;
    private FixedSource metadataSource;
    private BSharedFeasibilityInputs.OwnedSource exploratorySource;
    private int sharedRowsPrepared;
    private int solverInvocations;
    private int phase,gain,solverAttempts;
    private boolean closed,solverConsumed;
    private BPhysicalPairTable(BPhysicalWork work,BPhysicalTableRegistry registry,BPhysicalResponse response,
            AttemptMemoryLedger.Owner owner,int gain,PublicationBounds publication,EvidenceModelParameters parameters,
            BExperimentalScalarCapture.AcquisitionPath acquisition) {
        this.work=work;this.registry=registry;this.response=response;this.owner=owner;this.gain=gain;
        this.publication=publication;this.parameters=parameters;this.acquisition=acquisition;permit=new Permit(this);support=new MutableSupport(this);budget=new Budget();
    }
    static BPhysicalPairTable buildCapturedData(BPhysicalWork work,BSharedPositionEvidence.CapturedInput input,
            EvidenceModelParameters parameters,int gain,PublicationBounds publication) {
        if(input==null)throw new IllegalArgumentException("Private captured input required");
        return build(work,null,null,null,null,parameters,gain,publication,input,null);
    }
    /** Typed factual-path DATA entry;full PublicationPlan/runtime remains absent. */
    static BPhysicalPairTable buildCapturedData(BPhysicalWork work,BSharedPositionEvidence.CapturedInput input,
            EvidenceModelParameters parameters,int gain,PublicationBounds publication,BExperimentalScalarCapture.AcquisitionPath path) {
        if(input==null||path==null)throw new IllegalArgumentException("Actual captured input and acquisition path required");
        return build(work,null,null,null,null,parameters,gain,publication,input,path);
    }
    /** Software-only actual metadata join. Same graph/source/path and one K;
     * no protocol/runtime identity or export capability follows. */
    static BPhysicalPairTable buildCapturedMetadataData(BPhysicalWork work,BSharedPositionEvidence.CapturedInput input,
            EvidenceModelParameters parameters,int gain,PublicationBounds publication,
            BExperimentalScalarCapture.AcquisitionPath path,String[] declaredGlobals){
        if(input==null||path==null||declaredGlobals==null)throw new IllegalArgumentException("Actual metadata build inputs required");
        return build(work,null,null,null,null,parameters,gain,publication,input,path,declaredGlobals);
    }
    /** Declared-DATA metadata with factual private graph21, not runtime authority.
     * The exclusively borrowed initialized SUN engine remains caller funded.
     * Other declarations still need genuine Controls/source/runtime producers. */
    static BPhysicalPairTable buildCapturedGraphMetadataData(BPhysicalWork work,BSharedPositionEvidence.CapturedInput input,
            EvidenceModelParameters parameters,int gain,PublicationBounds publication,
            BExperimentalScalarCapture.AcquisitionPath path,String[] declarations,
            java.security.MessageDigest borrowedGraphDigest){
        if(work==null||input==null||path==null||declarations==null||declarations.length!=24
                ||declarations[21]!=null||borrowedGraphDigest==null)
            throw new IllegalArgumentException("Declared metadata must leave private graph21 unassigned");
        //Private24-reference copy, funded BEFORE clone and before graph callbacks.
        //Registration remains partial; no source/runtime price is inferred.
        work.sourceWork().authoritySlots(24);work.sourceWork().metadata(208);
        String[] copied=declarations.clone();
        if(copied[21]!=null)throw new IllegalArgumentException("Caller graph hash is not an input");
        return build(work,null,null,null,null,parameters,gain,publication,input,path,copied,borrowedGraphDigest);
    }
    /** Partial publication DATA preflight;cannot select K or grant an export capability. */
    static BPhysicalPublicationShape preflightPublicationData(BPhysicalWork work,
            BSharedPositionEvidence.CapturedInput input,PublicationBounds upper,
            BExperimentalScalarCapture.AcquisitionPath path) {
        if(work==null)throw new IllegalArgumentException("Actual publication attempt required");
        //Own registration and all new fixed entry/cleanup controls,BEFORE joins.
        work.claimTableRegistry();work.prepayTableBody(512);
        BPhysicalResponse response=null;Throwable primary=null;
        try {
            if(input==null||path==null||upper==null||upper.work!=work)
                throw new IllegalArgumentException("Same captured publication DATA inputs required");
            input.consumeAcquisitionPath(path,work,upper.sourcePointsUpper);
            work.poll();
            response=BPhysicalResponse.openCapturedTable(work,input);
            return BPhysicalPublicationShape.compilePending(work,response,path);
        }catch(RuntimeException|Error failure){primary=failure;throw failure;}
        finally {if(response!=null)try{response.close();}
            catch(RuntimeException|Error cleanup){if(primary==null)throw cleanup;}}
    }
    /** Metadata software only:actual graph preparation precedes this compiler,
     * and this entry NEVER invokes budget.bind/allocate/evaluateEdges/joint K. */
    static BPhysicalPublicationMetadata.Plan preflightCompletePublicationData(BPhysicalWork work,
            BSharedPositionEvidence.CapturedInput input,EvidenceModelParameters parameters,int gain,
            PublicationBounds upper,BExperimentalScalarCapture.AcquisitionPath path,String[] globals) {
        if(work==null)throw new IllegalArgumentException("Actual metadata attempt required");
        var registry=work.claimTableRegistry();work.prepayTableBody(8192);
        BPhysicalResponse response=null;AttemptMemoryLedger.Owner owner=null;BPhysicalPairTable table=null;
        BPhysicalPublicationMetadata compiler=null;boolean returned=false;
        try {
            if(input==null||parameters==null||upper==null||upper.work!=work||path==null
                    ||gain!=1&&gain!=4&&gain!=16&&gain!=64)throw new IllegalArgumentException("Actual original metadata inputs");
            input.consumeAcquisitionPath(path,work,upper.sourcePointsUpper);work.poll();
            response=BPhysicalResponse.openCapturedTable(work,input);
            work.prepayTableBody(292+METADATA_REFERENCE_INIT);owner=work.owner().child("physical-metadata-pre-K");owner.reserve(FRAME);
            table=new BPhysicalPairTable(work,registry,response,owner,gain,upper,parameters,path);
            table.prepareCapturedLocal();
            try(var local=response.borrowLocal(table.permit);var fieldView=BSharedPositionKernel.Field.open(local,work)){
                var kernel=BSharedPositionKernel.createForTable(fieldView,gain,work);
                table.prepareGraph(parameters,kernel);
            }
            table.permit.state=0;
            compiler=BPhysicalPublicationMetadata.open(work,work.owner(),work.probe());
            var result=compiler.compileTable(table,globals);returned=true;return result;
        } finally {
            if(!returned){
                if(compiler!=null)try{compiler.close();}catch(RuntimeException|Error ignored){/*preserve primary*/}
                if(table!=null)try{table.close();}catch(RuntimeException|Error ignored){/*preserve primary*/}
                else {if(owner!=null)try{owner.close();}catch(RuntimeException|Error ignored){/*preserve primary*/}
                    if(response!=null)try{response.close();}catch(RuntimeException|Error ignored){/*preserve primary*/}}
            }
        }
    }
    void requireMetadataLive(BPhysicalPublicationMetadata.Plan plan){work.prepayTableBody(512);live();plan.requireTable(this);}
    /** Private-origin scalar snapshot. No caller arrays/scalars or readiness
     * boolean can construct it;the exact native parameters remain borrowed. */
    private static final class FixedSource {
        final BPhysicalPairTable table;
        final EvidenceModelParameters parameters;
        final long[] words;
        BPhysicalPublicationMetadata.Plan plan;
        long supportCount;
        double supportUpper;
        int k,certifiedCount,nextSupportSlot;
        boolean complete;
        private FixedSource(BPhysicalPairTable t,EvidenceModelParameters p,long[] values){table=t;parameters=p;words=values;}
    }
    void issueMetadataSource(BPhysicalPublicationMetadata compiler){
        work.prepayTableBody(BPhysicalPublicationMetadata.FIXED_CREATE);live();compiler.requireWork(work);
        if(metadataSource!=null||phase!=0||graph==null||acquisition==null)throw new IllegalStateException("Actual metadata source once before K");
        var b=response.bound();acquisition.requireConsumed(b.input.evidence,work);
        var t=b.input.evidence.transform();var origin=t.origin();
        //The actual captured scalar path owns this neutral retained affine.
        //Other transform kinds need a separately counted canonical lowering.
        if(t.axisUnit()!=org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform.AxisUnit.RASTER_PIXEL
                ||t.originKind()!=org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform.OriginKind.METRIC_FIRST_PIXEL_CENTER
                ||t.rasterPixelsPerSourcePixel()!=1)throw new BPhysicalPublicationMetadata.PendingMetadata();
        work.sourceWork().metadata(BPhysicalPublicationMetadata.FIXED_SOURCE_BYTES);
        long[] v=new long[36];
        v[0]=Double.doubleToRawLongBits(origin.xMeters());v[1]=Double.doubleToRawLongBits(origin.yMeters());
        v[2]=Double.doubleToRawLongBits(t.xAxisEastMetersPerSourcePixel());v[3]=Double.doubleToRawLongBits(t.xAxisNorthMetersPerSourcePixel());
        v[4]=Double.doubleToRawLongBits(t.yAxisEastMetersPerSourcePixel());v[5]=Double.doubleToRawLongBits(t.yAxisNorthMetersPerSourcePixel());
        v[6]=Double.doubleToRawLongBits(b.grid.ox);v[7]=Double.doubleToRawLongBits(b.grid.oy);
        v[8]=Double.doubleToRawLongBits(b.grid.ux);v[9]=Double.doubleToRawLongBits(b.grid.uy);
        v[10]=Double.doubleToRawLongBits(b.grid.vx);v[11]=Double.doubleToRawLongBits(b.grid.vy);v[12]=Double.doubleToRawLongBits(b.grid.rho);
        v[13]=Double.doubleToRawLongBits(b.input.evidence.resolution().effectivePitchMeters());v[14]=Double.doubleToRawLongBits(gain);
        v[15]=Double.doubleToRawLongBits(parameters.dataWeight());v[16]=Double.doubleToRawLongBits(parameters.centerWeight());
        v[17]=Double.doubleToRawLongBits(parameters.turnWeight());v[18]=Double.doubleToRawLongBits(parameters.orientationWeight());
        v[19]=Double.doubleToRawLongBits(parameters.guideWeight());v[20]=Double.doubleToRawLongBits(parameters.turnScaleRadians());
        v[21]=Double.doubleToRawLongBits(parameters.temperature());v[22]=Double.doubleToRawLongBits(parameters.ambiguityEnergyDelta());
        var local=parameters.localization();v[23]=local.orientationHeadingCount();v[24]=local.maximumOrientationSampleCount();
        v[25]=Double.doubleToRawLongBits(local.minimumOrientationRayMeters());v[26]=Double.doubleToRawLongBits(local.orientationRayLengthPitches());
        v[27]=Double.doubleToRawLongBits(local.maximumOrientationStepPitches());v[28]=Double.doubleToRawLongBits(local.minimumOrientationValidFraction());
        v[29]=Double.doubleToRawLongBits(local.orientationBackgroundQuantile());v[30]=Double.doubleToRawLongBits(local.orientationProminenceFraction());
        v[31]=Double.doubleToRawLongBits(local.localModeProminenceFraction());v[32]=Double.doubleToRawLongBits(local.localModeShoulderFraction());
        v[33]=Double.doubleToRawLongBits(local.localModeCoreFraction());v[34]=Double.doubleToRawLongBits(local.routeProfileHalfWidthMeters());
        v[35]=Double.doubleToRawLongBits(local.scalarAmplitudeHalfResponse());
        work.poll();live();acquisition.requireConsumed(b.input.evidence,work);
        metadataSource=new FixedSource(this,parameters,v); //No callback after final source check.
    }
    void attachMetadataPlan(BPhysicalPublicationMetadata.Plan plan){
        work.prepayTableBody(BPhysicalPublicationMetadata.FIXED_BIND);live();plan.requireTable(this);
        if(metadataSource==null||metadataSource.plan!=null||phase!=0)throw new IllegalStateException("Actual metadata plan once");
        metadataSource.plan=plan;
    }
    private FixedSource metadataSource(BPhysicalPublicationMetadata.Plan plan){
        live();plan.requireTable(this);
        if(metadataSource==null||metadataSource.plan!=plan||metadataSource.table!=this)throw new IllegalArgumentException("Original metadata source identity");
        return metadataSource;
    }
    long metadataParameterRaw64(BPhysicalPublicationMetadata.Plan plan,int ordinal){
        work.prepayTableBody(BPhysicalPublicationMetadata.FIXED_READ);var s=metadataSource(plan);
        if(ordinal<0||ordinal>=8)throw new IllegalArgumentException("Native parameter getter ordinal");return s.words[15+ordinal];
    }
    EvidenceModelParameters metadataParameters(BPhysicalPublicationMetadata.Plan plan){
        work.prepayTableBody(BPhysicalPublicationMetadata.FIXED_READ);return metadataSource(plan).parameters;
    }
    int metadataKSelections(BPhysicalPublicationMetadata.Plan plan){
        work.prepayTableBody(BPhysicalPublicationMetadata.FIXED_READ);return metadataSource(plan).k==0?0:1;
    }
    long metadataLocalizationRaw64(BPhysicalPublicationMetadata.Plan plan,int ordinal){
        work.prepayTableBody(BPhysicalPublicationMetadata.FIXED_READ);var s=metadataSource(plan);
        if(ordinal<0||ordinal>=11)throw new IllegalArgumentException("Native localization double ordinal");return s.words[25+ordinal];
    }
    int metadataLocalizationInt(BPhysicalPublicationMetadata.Plan plan,int ordinal){
        work.prepayTableBody(BPhysicalPublicationMetadata.FIXED_READ);var s=metadataSource(plan);
        if(ordinal<0||ordinal>=2)throw new IllegalArgumentException("Native localization int ordinal");return (int)s.words[23+ordinal];
    }
    long metadataRaw64(BPhysicalPublicationMetadata.Plan plan,int offset){
        work.prepayTableBody(BPhysicalPublicationMetadata.FIXED_READ);var s=metadataSource(plan);
        if(offset>=44&&offset<=84&&(offset-44)%8==0)return s.words[(offset-44)/8];
        if(offset>=100&&offset<=148&&(offset-100)%8==0)return s.words[6+(offset-100)/8];
        if(offset==156)return s.words[13];if(offset==168)return s.words[14];
        if(offset>=214&&offset<=270&&(offset-214)%8==0)return s.words[15+(offset-214)/8];
        if(offset>=286&&offset<=366&&(offset-286)%8==0)return s.words[25+(offset-286)/8];
        if(offset==542){if(s.k==0)throw new BPhysicalPublicationMetadata.PendingMetadata();return s.k;}
        if(offset==558){if(!s.complete||s.supportCount!=s.certifiedCount)throw new BPhysicalPublicationMetadata.PendingMetadata();return Double.doubleToRawLongBits(s.supportUpper);}
        if(offset==566)return Double.doubleToRawLongBits(1); //Original normalization L0.
        throw new BPhysicalPublicationMetadata.PendingMetadata();
    }
    void requireActualMetadata(BPhysicalPublicationMetadata.Plan plan){
        work.prepayTableBody(BPhysicalPublicationMetadata.FIXED_READ);var s=metadataSource(plan);
        if(s.k==0||!s.complete||s.supportCount!=s.certifiedCount)throw new BPhysicalPublicationMetadata.PendingMetadata();
        work.prepayTableBody(BPhysicalWork.METADATA_CUT_READ);
        work.requireMetadataCut(registry,false);
        //No actual build/protocol/runtime identity issuer or owning staged
        //PRECOMMIT consumer exists. No raw237 access/new snapshot is invented.
        throw new BPhysicalPublicationMetadata.PendingMetadata();
    }
    long metadataPrefix(BPhysicalPublicationMetadata.Plan plan,int index){
        work.prepayTableBody(BPhysicalPublicationMetadata.FIXED_READ);metadataSource(plan);
        if(index<0||index>=237)throw new IndexOutOfBoundsException("Original metadata PREFIX word");
        return index<137?work.metadataTableImage(registry,false,index):registry.metadataImage(work,false,index-137);
    }
    BPhysicalPublicationMetadata.Plan metadataPlan(){
        work.prepayTableBody(BPhysicalPublicationMetadata.FIXED_READ);live();
        if(metadataSource==null||metadataSource.plan==null)throw new BPhysicalPublicationMetadata.PendingMetadata();
        return metadataSource.plan;
    }
    private void bindMetadataK(){
        work.prepayTableBody(BPhysicalPublicationMetadata.FIXED_BIND);live();
        if(metadataSource==null||metadataSource.plan==null||metadataSource.k!=0||budget.k<1||phase!=0)
            throw new IllegalStateException("Actual metadata K once after original bind");
        metadataSource.k=budget.k;
    }
    private void bindMetadataCompletion(int certified){
        work.prepayTableBody(BPhysicalPublicationMetadata.FIXED_BIND);ready();
        if(metadataSource.plan==null||metadataSource.k!=budget.k||metadataSource.complete)
            throw new IllegalStateException("Actual metadata completion once after TABLE end");
        if(certified<0||certified>budget.pairs)throw new IllegalStateException("Actual certified slot count");
        metadataSource.certifiedCount=certified;metadataSource.complete=true;
    }
    /** Sole actual-input producer:the graph argument cannot be supplied by a caller. */
    BPhysicalPublicationMetadata.Input metadataInput(BPhysicalPublicationMetadata compiler,String[] globals){
        work.prepayTableBody(8192);live();compiler.requireWork(work);
        if(graph==null||acquisition==null||phase!=0)throw new IllegalStateException("Actual pre-K original graph required");
        if(globals==null||globals.length!=24)throw new BPhysicalPublicationMetadata.PendingMetadata();
        var b=response.bound();acquisition.requireConsumed(b.input.evidence,work);
        int n=graph.size(),d=b.indices[2].length;
        var source=b.input.evidence.evidenceRegion().polygons();var decision=b.input.region.polygons();
        int ringCount=Math.addExact(source.size(),decision.size());
        work.prepayTableBody(add(mul(BPhysicalPublicationMetadata.PROFILE,n),
            add(mul(BPhysicalPublicationMetadata.MODE,d),mul(BPhysicalPublicationMetadata.ROLE,ringCount))));
        long payload=add(104,add(array(n,8),add(array(n,4),add(array(d,8),
            add(mul(d,array(5,8)),add(array(d,4),add(array(4L*d,4),add(array(ringCount,4),array(25,8)))))))));
        work.sourceWork().metadata(payload);
        work.prepayTableBody(BPhysicalPublicationMetadata.SOURCE_TEXT_COPY); //defaults,24 bounded copies,3 links/version getter/loop ends
        String[] globalCopy=new String[25]; //private before callbacks;source-only appended field is never caller text
        for(int i=0;i<24;i++)globalCopy[i]=globals[i];
        globalCopy[0]=BPhysicalPublicationMetadata.SOURCE_SCHEMA;globalCopy[23]=APPROXIMATION_ID;globalCopy[24]=parameters.version();
        var originals=new InferenceProfile[n];int[] modeCounts=new int[n],provenance=new int[d],rectangles=new int[4*d],rings=new int[ringCount];
        String[][] texts=new String[d][];int at=0;
        for(int row=0;row<n;row++){
            work.poll();live();originals[row]=graph.get(row);var fact=b.input.facts.get(row);modeCounts[row]=fact.modes().size();
            if(originals[row].cells().size()!=b.input.lattices.get(row).cells().size())throw new IllegalArgumentException("Original graph state join");
            for(int state=0;state<originals[row].cells().size();state++)if(originals[row].cells().get(state)!=b.input.lattices.get(row).cells().get(state))
                throw new IllegalArgumentException("Original graph cell identity");
            for(int m=0;m<fact.modes().size();m++){
                work.poll();live();var mode=fact.modes().get(m);var original=fact.baseline().modes().get(m);
                if(at>=d||mode.values().size()!=20)throw new IllegalArgumentException("Complete original scalar facts");
                BIdentificationFacts.CorrelationReceipt correlation=null;
                for(var receipt:mode.provenance())if(receipt instanceof BIdentificationFacts.CorrelationReceipt c){
                    if(correlation!=null)throw new IllegalArgumentException("Duplicate original correlation receipt");correlation=c;}
                if(correlation==null)throw new BPhysicalPublicationMetadata.PendingMetadata();
                texts[at]=new String[]{original.id(),original.evidenceLineage(),mode.lineage(),correlation.fieldLineage(),correlation.transformId()};
                provenance[at]=mode.provenance().size();
                for(int k=0;k<4;k++)rectangles[4*at+k]=b.indices[3+k][at];at++;
            }
        }
        if(at!=d)throw new IllegalArgumentException("Whole original mode shape");
        int ring=0;for(var polygon:source){work.poll();live();rings[ring++]=polygon.size();}
        for(var polygon:decision){work.poll();live();rings[ring++]=polygon.size();}
        work.prepayTableBody(mul(BPhysicalPublicationMetadata.CHAR,add(b.input.evidence.sourceIdentity().length(),
            add(b.input.fieldKey.length(),add(b.input.evidence.resampling().sourceTransformIdentity().length(),BIdentificationFacts.VERSION.length())))));
        if(!b.input.evidence.sourceIdentity().equals(globalCopy[11])
                ||!b.input.fieldKey.equals(globalCopy[12])||!b.input.evidence.resampling().sourceTransformIdentity().equals(globalCopy[13])
                ||!BIdentificationFacts.VERSION.equals(globalCopy[16]))
            throw new BPhysicalPublicationMetadata.PendingMetadata();
        //Two existing getter/source-live children,then128 source/control slots:
        //2checked products*32,12array/ref reads,8bounds/arithmetic,20branches,
        //24caller/load/store/return. Own fee is BEFORE any new getter/check.
        work.prepayTableBody(2*BPhysicalWork.MAX_LIVE+2*8+128);
        int sourceWidth=response.sourceWidth(),sourceHeight=response.sourceHeight();
        if(sourceWidth<2||sourceHeight<2||Math.multiplyExact((long)sourceWidth,sourceHeight)!=b.source.length
                ||Math.multiplyExact(sourceWidth-1L,sourceHeight-1L)!=b.interpolation.length)
            throw new IllegalStateException("Retained source array dimensions changed");
        work.poll();live();acquisition.requireConsumed(b.input.evidence,work);
        return new BPhysicalPublicationMetadata.Input(sourceWidth,sourceHeight,b.grid.width,b.grid.height,
            acquisition.pointCount(),source.size(),originals,modeCounts,texts,provenance,rectangles,rings,globalCopy);
    }
    static BPhysicalPairTable build(BPhysicalWork work,EvidenceSnapshot snapshot,String field,
            List<ProbabilisticProfile> profiles,List<ProbabilisticStateLattice> lattices,
            EvidenceModelParameters parameters,int gain,PublicationBounds publication) {
        return build(work,snapshot,field,profiles,lattices,parameters,gain,publication,null,null);
    }
    static BPhysicalPairTable buildExploratory(BPhysicalWork work,BSharedFeasibilityInputs.OwnedSource source,
            EvidenceModelParameters parameters,int gain){
        source.require(work);work.fundPublicationBounds(source.sourcePoints());
        var publication=new PublicationBounds(work,source.sourcePoints());
        return build(work,null,null,null,null,parameters,gain,publication,null,null,null,null,source);
    }
    private static BPhysicalPairTable build(BPhysicalWork work,EvidenceSnapshot snapshot,String field,
            List<ProbabilisticProfile> profiles,List<ProbabilisticStateLattice> lattices,
            EvidenceModelParameters parameters,int gain,PublicationBounds publication,BSharedPositionEvidence.CapturedInput captured,
            BExperimentalScalarCapture.AcquisitionPath actualPath) {
        return build(work,snapshot,field,profiles,lattices,parameters,gain,publication,captured,actualPath,null);
    }
    private static BPhysicalPairTable build(BPhysicalWork work,EvidenceSnapshot snapshot,String field,
            List<ProbabilisticProfile> profiles,List<ProbabilisticStateLattice> lattices,
            EvidenceModelParameters parameters,int gain,PublicationBounds publication,BSharedPositionEvidence.CapturedInput captured,
            BExperimentalScalarCapture.AcquisitionPath actualPath,String[] declaredGlobals) {
        return build(work,snapshot,field,profiles,lattices,parameters,gain,publication,captured,actualPath,declaredGlobals,null);
    }
    private static BPhysicalPairTable build(BPhysicalWork work,EvidenceSnapshot snapshot,String field,
            List<ProbabilisticProfile> profiles,List<ProbabilisticStateLattice> lattices,
            EvidenceModelParameters parameters,int gain,PublicationBounds publication,BSharedPositionEvidence.CapturedInput captured,
            BExperimentalScalarCapture.AcquisitionPath actualPath,String[] declaredGlobals,
            java.security.MessageDigest borrowedGraphDigest) {
        return build(work,snapshot,field,profiles,lattices,parameters,gain,publication,captured,actualPath,
            declaredGlobals,borrowedGraphDigest,null);
    }
    private static BPhysicalPairTable build(BPhysicalWork work,EvidenceSnapshot snapshot,String field,
            List<ProbabilisticProfile> profiles,List<ProbabilisticStateLattice> lattices,
            EvidenceModelParameters parameters,int gain,PublicationBounds publication,BSharedPositionEvidence.CapturedInput captured,
            BExperimentalScalarCapture.AcquisitionPath actualPath,String[] declaredGlobals,
            java.security.MessageDigest borrowedGraphDigest,BSharedFeasibilityInputs.OwnedSource exploratory) {
        if(work==null||parameters==null||(gain!=1&&gain!=4&&gain!=16&&gain!=64))throw new IllegalArgumentException("Fixed table inputs");
        if(publication==null||publication.work!=work)throw new IllegalArgumentException("Same-attempt prospective publication bound");
        var registry=work.claimTableRegistry();work.prepayTableBody(64);BPhysicalResponse response=null;
        AttemptMemoryLedger.Owner owner=null;BPhysicalPairTable table=null;boolean returned=false,began=false;int certified=0;
        try {
            if(actualPath!=null){
                work.prepayTableBody(512); //new exclusive table-preflight caller control before joins
                captured.consumeAcquisitionPath(actualPath,work,publication.sourcePointsUpper);
                work.poll(); //real pre-bind consumer boundary;failure retains caller-owned capture
            }
            response=exploratory!=null?BPhysicalResponse.openExploratoryTable(work,exploratory):
                captured==null?BPhysicalResponse.open(work,snapshot,field,profiles,lattices):BPhysicalResponse.openCapturedTable(work,captured);
            registry.begin(BPhysicalTableRegistry.TABLE);began=true;
            //36 additionally pays the new reference/default/argument/store
            //producers BEFORE the same frame claim and table allocation.
            work.prepayTableBody(256+36+METADATA_REFERENCE_INIT+8); //new ref+optional compile dispatch BEFORE allocation
            owner=work.owner().child("physical-complete-table");owner.reserve(FRAME);
            table=new BPhysicalPairTable(work,registry,response,owner,gain,publication,parameters,actualPath);
            table.exploratorySource=exploratory;
            if(captured==null&&exploratory==null)table.prepareGraph(parameters,null);
            else {
                table.prepareCapturedLocal();
                try(var local=response.borrowLocal(table.permit);var fieldView=BSharedPositionKernel.Field.open(local,work)){
                    var kernel=BSharedPositionKernel.createForTable(fieldView,gain,work);
                    table.prepareGraph(parameters,kernel);
                }
                table.permit.state=0; //Local borrow expired;one original workspace may now be issued.
            }
            if(declaredGlobals!=null){
                if(borrowedGraphDigest!=null)declaredGlobals[21]=table.digestPreKCanonicalGraphData(borrowedGraphDigest);
                var compiler=BPhysicalPublicationMetadata.open(work,work.owner(),work.probe());
                compiler.compileTable(table,declaredGlobals);
            }
            table.budget.bind(table);
            work.prepayTableBody(8);if(table.metadataSource!=null)table.bindMetadataK();
            table.allocate();
            table.evaluateEdges();
            work.poll();table.live();
            work.prepayTableBody(16*table.budget.pairs); //same original verification pass,16 additional slots/P
            for(int at=0;at<table.status.length;at++){
                if(table.status[at]!=CERTIFIED&&table.status[at]!=NOT_ADMITTED)throw new IllegalStateException("Incomplete original slot inventory");
                if(table.status[at]==CERTIFIED)certified++;
            }
            if(table.permit.nodes>table.budget.pairs*table.budget.k||table.permit.merges>table.budget.pairs*(table.budget.k-1))
                throw new IllegalStateException("Cumulative table permit ceiling");
            work.poll();table.live();table.phase=1;returned=true;return table;
        } finally {
            boolean completed=false;
            try {if(began)registry.end(BPhysicalTableRegistry.TABLE,returned);
                if(returned){work.prepayTableBody(8);if(table.metadataSource!=null)table.bindMetadataCompletion(certified);}
                completed=true;}
            finally {if(!returned||!completed){registry.failure();if(owner!=null)owner.close();if(response!=null)response.close();}}
        }
    }
    private void prepareCapturedLocal(){
        var b=response.bound();int n=b.rows.length-1,d=b.indices[2].length;
        //All count/control producers below are prepaid before their input loops:
        //fixed1024;per row <=16 API slots*8+128 controls;per mode32.
        work.prepayTableBody(add(1024,add(mul(256,n),mul(32,d))));
        long q=0,fees=2*(512+BPhysicalWork.localKernelUnits(0,0,0,0)),e=0;
        for(int i=0;i<n;i++){
            work.poll();var f=b.input.facts.get(i);var l=b.input.lattices.get(i);long eligible=0;
            for(int j=b.rows[i];j<b.rows[i+1];j++)if(b.indices[2][j]==BSharedPositionEvidence.Status.AVAILABLE.ordinal())eligible++;
            q=add(q,mul(eligible,l.cells().size()));
            fees=add(fees,add(512,BPhysicalWork.localKernelUnits(l.components().size(),l.cells().size(),f.baseline().samples().size(),f.modes().size())));
        }
        var rings=b.input.evidence.evidenceRegion().polygons();
        work.prepayTableBody(add(6,mul(32,rings.size())));
        for(int i=0;i<rings.size();i++)e=add(e,rings.get(i).size());
        budget.localQueries=q;budget.localFeeUpper=fees;budget.localEdges=e;budget.localRows=n;
        permit.state=-2;work.registerTableLocal(permit,q,fees,e);
    }
    private void prepareGraph(EvidenceModelParameters parameters,BSharedPositionKernel kernel) {
        var b=response.bound();int n=b.stateRows.length-1;
        registry.begin(BPhysicalTableRegistry.BASELINE);boolean normal=false;
        try {
            work.prepayTableBody(32783+46L*n); //constant/runtime profile-loop body before census
            owner.reserve(40+2*array(n,8));var rows=new ArrayList<InferenceProfile>(n);
            var model=new ProbabilisticObservationModel();
            for(int i=0;i<n;i++) {
                work.poll();var original=b.input.facts.get(i).baseline();var lattice=b.input.lattices.get(i);
                int m=original.modes().size(),c=original.censoredModes().size(),s=lattice.cells().size(),components=lattice.components().size();
                if(m>8192||c>401||s<1||s>96||components<1||components>m+c+1)throw new IllegalArgumentException("Original baseline inventory");
                long fee=baselineFee(m,c);work.prepayTableBody(fee);
                work.prepayTableBody(6+32L*s);
                for(int j=0;j<s;j++)if(lattice.cells().get(j).branchLabel().length()>8192)
                    throw new IllegalArgumentException("Original graph branch text ceiling");
                budget.baselineFee=add(budget.baselineFee,fee);
                //Audited API extras coexist with retained original evaluate data;
                //source maxima frame816 includes captured/inherited JDK fields.
                long extra=baselineApiBytes(s,components,m);owner.reserve(extra);
                budget.baselineBytes=add(budget.baselineBytes,extra);
                InferenceProfile profile;
                if(kernel==null)profile=model.evaluate(original,lattice,parameters,true,owner);
                else {
                    //Additional constructor/reducer/copy branch union. The
                    //second complete baseline program dominates the repeated
                    //InferenceProfile constructor;512 per state/component pair
                    //covers Adapter+reduce scalar/API/loop/claim controls.
                    work.prepayTableBody(add(fee,mul(512,mul(s+1L,components+1L))));
                    owner.reserve(extra); //Second constructor/API overlap,not retained source credit.
                    profile=BObservationAdapter.evaluate(b.input.facts.get(i),lattice,parameters,true,kernel,work.probe(),owner);
                    sharedRowsPrepared++;
                }
                if(profile.anchor()!=original.anchor()||profile.normalUnit()!=original.normalUnit()||profile.cells().size()!=s)
                    throw new IllegalArgumentException("Original baseline source identity");
                for(int j=0;j<s;j++) {
                    work.poll();if(profile.cells().get(j)!=lattice.cells().get(j))throw new IllegalArgumentException("Original cell identity");
                    double o=profile.cells().get(j).offsetMeters();
                    double x=o==0?profile.anchor().xMeters():profile.anchor().xMeters()+profile.normalUnit().xMeters()*o;
                    double y=o==0?profile.anchor().yMeters():profile.anchor().yMeters()+profile.normalUnit().yMeters()*o;
                    int at=b.stateRows[i]+j;
                    if(Double.doubleToRawLongBits(x)!=Double.doubleToRawLongBits(b.stateX[at])||Double.doubleToRawLongBits(y)!=Double.doubleToRawLongBits(b.stateY[at]))
                        throw new IllegalArgumentException("Original state point bits");
                }
                rows.add(profile);
            }
            graph=List.copyOf(rows);work.poll();live();normal=true;
        } finally {registry.end(BPhysicalTableRegistry.BASELINE,normal);}
    }
    //Exact symbolic expansion of accepted baseline_api_program at source
    //samples401/states96/branches=m,components=m+c+1,censored inventory401.
    //No regression fitting:terms arise from algebraic Program.emit/add/mul.
    static long baselineFee(long m,long c) {
        if(m<0||m>8192||c<0||c>401)throw new IllegalArgumentException("Baseline cost domain");
        long numerator=add(12788476,add(mul(26446167,c),add(mul(16395,mul(c,c)),
                add(mul(25487985,m),add(mul(32790,mul(m,c)),add(mul(278583,mul(m,m)),mul(393216,mul(m,mul(m,m)))))))));
        if((numerator&1)!=0)throw new AssertionError("Integer baseline count");return numerator/2;
    }
    static long baselineApiBytes(int s,int c,int m) {
        long target=Math.max(64,2L*Math.max(1,m)),cap=1;
        while(cap<target)cap=mul(cap,2);
        long map=add(96,add(array(cap,8),add(array(cap/2,8),add(mul(2L*m,96),add(48,48)))));
        //Distinct branch count is not yet a produced baseline fact. Its
        //minimum retained credit is O48=64;never assume branches==all modes.
        long retained=64;
        long builder=add(64,add(array(Math.max(1,2L*m),8),mul(m,56)));
        long maps=mul(4,Math.max(0,map-Math.min(retained,builder)));
        return add(maps,add(mul(m,24),add(40+array(c,8),add(2*array(s,8)+array(c,8),
                40+40+816+16+104+16+72))));
    }
    void requireRegistry(BPhysicalTableRegistry registry,BPhysicalWork work,BPhysicalResponse.SourceView source) {
        live();if(this.registry!=registry||this.work!=work||response.panelSource()!=source||phase!=0)
            throw new IllegalArgumentException("Foreign table plan/source");
    }
    private void live(){work.liveSource();if(closed||owner.isClosed())throw new IllegalStateException("Table owner closed");response.bound();}
    private void ready(){live();if(phase!=1)throw new IllegalStateException("No complete table");}
    int slots(){ready();return status.length;}
    boolean complete(){ready();return true;}
    int capacity(){ready();return budget.k;}
    long nodes(){ready();return permit.nodes;}
    long merges(){ready();return permit.merges;}
    long roleCost(int role){live();return role==BPhysicalTableRegistry.SUPPORT
        ?add(budget.roleCost(role),8+(metadataSource==null?0:BPhysicalPublicationMetadata.FIXED_SUPPORT)):budget.roleCost(role);}
    long roleMaximum(int role){live();return budget.roleMaximum(role);}
    private void allocate() {
        int n=graph.size(),p=Math.toIntExact(budget.pairs);
        owner.reserve(budget.tableBytes-FRAME); //frame already in actual C0
        values=new double[p];errors=new double[p];unknown=new double[p];status=new int[p];
        offsets=new int[n];widths=new int[n];rowErrors=new long[n];views=new View[4];
        int next=0;for(int i=0;i<n;i++){work.poll();widths[i]=graph.get(i).cells().size();offsets[i]=next;if(i+1<n)next=Math.addExact(next,Math.multiplyExact(widths[i],graph.get(i+1).cells().size()));}
        if(next!=p)throw new IllegalArgumentException("Original pair census changed");
        views[1]=new View(this,.25);views[2]=new View(this,1);views[3]=new View(this,4);
    }
    private void evaluateEdges() {
        if(budget.pairs==0)return;
        work.prepayTableBody(191);permit.workspace=BPhysicalAdaptiveIntegral.forTable(response,permit,budget.k,budget.headroom);
        int at=0;
        for(int i=0;i+1<graph.size();i++)for(int j=0;j<widths[i];j++)for(int l=0;l<widths[i+1];l++) {
            permit.slot=at;permit.stage=i;permit.left=j;permit.right=l;permit.state=1;
            registry.begin(BPhysicalTableRegistry.EDGE);boolean normal=false;
            try {
                work.prepayTableBody(budget.edgeFee);
                var b=response.bound();int a=b.stateRows[i]+j,z=b.stateRows[i+1]+l;
                double ax=b.stateX[a],ay=b.stateY[a],bx=b.stateX[z],by=b.stateY[z];
                //The original bounded predicate owns its two temporary Points
                //and simultaneous sort/event scratch. The table is its funded
                //callback,not an allocated per-edge lambda or carrier.
                boolean admitted;
                //The independently priced G union contains these three Point
                //shells+816 caller frame IN ADDITION to original internal
                //Facade/backing/sort reservations. No H-slice reuse/credit.
                try(var arguments=owner.reserve(3*32+816)) {
                    admitted=b.input.region.containsSegmentWithBoundedEvents(
                            new MetricPoint(ax,ay),new MetricPoint(bx,by),work.probe(),this,owner);
                }
                work.poll();live();
                if(!admitted)status[at]=NOT_ADMITTED;
                else {
                    permit.state=2;permit.workspace.certifyForTable(permit,ax,ay,bx,by,gain);
                    permit.nodes=add(permit.nodes,permit.workspace.nodes());permit.merges=add(permit.merges,permit.workspace.merges());
                    if(permit.workspace.status()!=BPhysicalAdaptiveIntegral.Status.CERTIFIED)
                        throw new IllegalStateException("Admitted original edge not certified");
                    double v=permit.workspace.value(),e=permit.workspace.error(),u=permit.workspace.unknownUpper();
                    if(!Double.isFinite(v)||!Double.isFinite(e)||!Double.isFinite(u)||v<0||e<0||u<0)
                        throw new ArithmeticException("Invalid certified table carrier");
                    for(int eta=1;eta<4;eta++){double scale=eta==1?.25:eta==2?1:4,product=scale*v;
                        if(!Double.isFinite(product)||v>0&&product==0)throw new ArithmeticException("Eta product underflow/overflow");}
                    values[at]=v;errors[at]=e;unknown[at]=u;status[at]=CERTIFIED;
                }
                work.poll();live();permit.state=3;normal=true;
            } finally {registry.end(BPhysicalTableRegistry.EDGE,normal);if(!normal)permit.state=-1;}
            at++;
        }
        if(at!=status.length)throw new IllegalStateException("Complete ordered original pair inventory");
    }
    @Override public void accept(long units) {
        if(permit.state!=1||owner.isClosed()||units<0)throw new IllegalStateException("Foreign geometry callback");
        work.tableGeometry(units,budget.geometryUpper);
    }
    @Override public void close(){if(!closed){closed=true;owner.close();response.close();}}
    static long add(long a,long b){return Math.addExact(a,b);}static long mul(long a,long b){return Math.multiplyExact(a,b);}
    static long array(long n,int w){if(n<0)throw new IllegalArgumentException("Negative array");return add(16,add(mul(n,w),7)&~7L);}
    static final class MutableSupport {
        private BPhysicalPairTable table;private double value,error,unknownLength,unknownError;
        private int stage,left,right,reason;
        MutableSupport(BPhysicalPairTable t){table=t;}
        private void ready(){table.ready();if(reason!=CERTIFIED)throw new IllegalStateException("No current support carrier");}
        double value(){ready();return value;}double error(){ready();return error;}
        double unknownLength(){ready();return unknownLength;}double unknownError(){ready();return unknownError;}
    }
    static final class Permit {
        final BPhysicalPairTable table;private BPhysicalAdaptiveIntegral workspace;
        private long nodes,merges,ordinal;private int slot,stage,left,right,state;
        private Permit(BPhysicalPairTable t){table=t;}
        void requireLocal(BPhysicalResponse response,BPhysicalWork work){
            table.live();if(table.permit!=this||table.response!=response||table.work!=work||table.phase!=0||state!=-2||workspace!=null
                    ||response.bound().input.captured==null&&table.exploratorySource==null)throw new IllegalStateException("No captured or exploratory table local permit");
            if(table.exploratorySource!=null){table.exploratorySource.require(work);
                if(table.exploratorySource.input()!=response.bound().input)throw new IllegalArgumentException("Foreign exploratory table source");}
        }
        void requireLocalSource(BPhysicalWork work,BPhysicalResponse.SourceView source){
            requireLocal(table.response,work);if(table.response.panelSource()!=source||work.tableRegistry()!=table.registry)
                throw new IllegalArgumentException("Foreign captured local source");
        }
        void consumeLocalFee(long units){requireLocal(table.response,table.work);
            long next=add(table.budget.localFeeUsed,units);
            if(next>table.budget.localFeeUpper)throw new IllegalStateException("Local setup/body fee ceiling");table.budget.localFeeUsed=next;
        }
        void beginLocal(){requireLocal(table.response,table.work);table.registry.begin(BPhysicalTableRegistry.LOCAL);}
        void endLocal(boolean normal){table.registry.end(BPhysicalTableRegistry.LOCAL,normal);}
        AttemptMemoryLedger.Owner owner(){table.live();return table.owner;}
        void requireConstruction(BPhysicalResponse response){table.live();if(table.phase!=0||table.response!=response||workspace!=null||state!=0)
            throw new IllegalStateException("Private table workspace issue once");}
        void requireAdaptiveConstruction(BPhysicalAdaptiveIntegral a,BPhysicalWork w,int k){requireConstruction(table.response);
            if(w!=table.work||k!=table.budget.k)throw new IllegalStateException("Foreign global pool");a.requireWork(w);}
        //Sole producer:forTable immediately hands over a fresh,ACTIVE,
        //unadopted reservation from the genuine evaluator owner. The source
        //allocation permit has already been checked by installTableAdaptive.
        void holdSolverHeadroom(AttemptMemoryLedger.Reservation lease){
            requireConstruction(table.response);
            if(table.solverHeadroom!=null||lease==null||lease.bytes()!=table.budget.headroom)
                throw new IllegalStateException("Original future solver headroom once");
            table.solverHeadroom=lease;
        }
        void requireIntegral(BPhysicalAdaptiveIntegral a){table.live();if(table.phase!=0||workspace!=a||state!=2||slot<0||slot>=table.status.length)
            throw new IllegalStateException("No ordered table edge permit");}
    }
    private static final class View implements AdjacentStatePotential {
        private final BPhysicalPairTable table;private final double eta;
        View(BPhysicalPairTable t,double eta){table=t;this.eta=eta;}
        @Override public double energy(int stage,int left,int right){int at=table.index(stage,left,right);return eta*table.values[at];}
    }
    private int slot(int stage,int left,int right){ready();if(stage<0||stage+1>=widths.length||left<0||left>=widths[stage]||right<0||right>=widths[stage+1])
        throw new IndexOutOfBoundsException("Original table slot");return offsets[stage]+left*widths[stage+1]+right;}
    int slotStatus(int stage,int left,int right){return status[slot(stage,left,right)];}
    private int index(int stage,int left,int right){int at=slot(stage,left,right);
        if(status[at]!=CERTIFIED)throw new IllegalArgumentException("Original pair not admitted");return at;}
    AdjacentStatePotential potential(int eta){ready();if(eta<0||eta>3)throw new IllegalArgumentException("Fixed eta index");return views[eta];}
    /** Fixed consumers borrow the stored rows/parameters without reflection or
     * copying. This DATA entry confers no source/runtime/export authority.
     * All preflights, one-solve ownership and native execution stay below. */
    CrossStreamInferenceResult solveStoredRaw1(TraceBudgets budgets,int eta){
        return solveRaw1(graph,parameters,budgets,eta);
    }
    int sharedRowsPrepared(){ready();return sharedRowsPrepared;}
    int solverInvocations(){return solverInvocations;}
    /** One real solver invocation on the exact stored rows;not an F2 capability.
     * Malformed preflights consume a bounded paid attempt but not the solve.
     * The ninth attempt terminates the table;failed/cancelled solves consume it.
     * Native score/cross-stream currencies and result allocations remain native.
     */
    CrossStreamInferenceResult solveRaw1(List<InferenceProfile> expectedGraph,EvidenceModelParameters expectedParameters,
            TraceBudgets budgets,int eta){
        work.prepayTableBody(SOLVER_ENTRY_UNITS);ready();
        if(solverAttempts==SOLVER_PREFLIGHTS){phase=-1;throw new IllegalStateException("Solver preflight ceiling");}
        solverAttempts++;
        //Identity guards intentionally precede the consumed guard,so a removed
        //binding check cannot hide behind a repeated-solve refusal in tests.
        if(expectedGraph!=graph)throw new IllegalArgumentException("Original table graph identity");
        if(expectedParameters!=parameters)throw new IllegalArgumentException("Original table parameter identity");
        if(budgets==null||budgets.maximumRawAlternatives()!=1||budgets.maximumDistinctAlternatives()!=1)
            throw new IllegalArgumentException("Explicit raw-one budgets required");
        if(eta<0||eta>3)throw new IllegalArgumentException("Fixed eta index");
        if(solverConsumed)throw new IllegalStateException("Table solver entry consumed");
        solverConsumed=true;
        work.poll();ready();
        var region=response.bound().input.region;var probe=work.probe();var resultOwner=work.owner();var view=potential(eta);
        //Frame776 includes Table200+Permit80+Support72+Budget272+Inference24
        //+the additional Reservation48=696. Pool/banks/source remain charged.
        var engine=new ProbabilisticInference();
        if(solverHeadroom!=null){var lease=solverHeadroom;solverHeadroom=null;lease.close();}
        //All fallible source/preflight/callback checks precede the pure-H
        //handoff. Native reserve-before-allocate now owns its actual slices.
        solverInvocations++;
        return engine.solveCrossStreamRaw1(graph,parameters,budgets,region,probe,resultOwner,view);
    }
    MutableSupport supportCarrier(){ready();return support;}
    void support(int stage,int left,int right,MutableSupport out) {
        support.reason=-1;
        if(out!=support)throw new IllegalArgumentException("Foreign owned support carrier");
        int at=index(stage,left,right);registry.begin(BPhysicalTableRegistry.SUPPORT);boolean normal=false;
        try {
            work.prepayTableBody(575+8);var b=response.bound();int a=b.stateRows[stage]+left,z=b.stateRows[stage+1]+right;
            double ax=b.stateX[a],ay=b.stateY[a],bx=b.stateX[z],by=b.stateY[z];
            if(Double.compare(ax,bx)>0||Double.compare(ax,bx)==0&&Double.compare(ay,by)>0){double swap=ax;ax=bx;bx=swap;swap=ay;ay=by;by=swap;}
            double ul=0;
            if(ax!=bx||ay!=by){double dx=bx-ax,dy=by-ay;
                double delta=Math.nextUp((dx==0?0:Math.ulp(dx))+(dy==0?0:Math.ulp(dy)));
                double length=StrictMath.hypot(dx,dy);
                ul=Math.max(0,Math.nextDown(Math.nextDown(Math.nextDown(length))-delta));
                if(!Double.isFinite(length)||ul<=0)throw new ArithmeticException("Original support length enclosure");}
            double canonical=Math.nextDown(Math.scalb(Math.max(1,ul),-32));
            work.poll();ready();out.value=values[at];out.error=errors[at];out.unknownLength=unknown[at];
            out.unknownError=canonical;out.stage=stage;out.left=left;out.right=right;normal=true;
        } finally {registry.end(BPhysicalTableRegistry.SUPPORT,normal);}
        if(metadataSource!=null){
            work.prepayTableBody(BPhysicalPublicationMetadata.FIXED_SUPPORT);ready();
            if(!metadataSource.complete||metadataSource.supportCount>=metadataSource.certifiedCount||at<metadataSource.nextSupportSlot)
                throw new IllegalStateException("Scheduled original support maximum once per pair");
            //Only a successful original SUPPORT end can reach these stores.
            metadataSource.supportUpper=Math.max(metadataSource.supportUpper,out.unknownError);
            metadataSource.supportCount++;metadataSource.nextSupportSlot=at+1;
        }
        out.reason=CERTIFIED; //Publish only after fallible end bookkeeping.
    }
    /** Upper-only numeric compilation input; NEVER original geometry authority. */
    static final class PublicationBounds {
        private final BPhysicalWork work;private final long sourcePointsUpper;
        private PublicationBounds(BPhysicalWork w,long upper){work=w;sourcePointsUpper=upper;}
    }
    static PublicationBounds unitPublicationBounds(BPhysicalWork work,long sourcePointsUpper) {
        work.fundPublicationBounds(sourcePointsUpper);return new PublicationBounds(work,sourcePointsUpper);
    }
    /** Factual captured DATA cap; no caller count, path consumption or runtime grant. */
    static PublicationBounds capturedPublicationBoundsData(BExperimentalScalarCapture capture,EvidenceReadView view,
            BExperimentalScalarCapture.AcquisitionPath path,BPhysicalWork physical) {
        if(physical==null)throw new IllegalArgumentException("Missing captured publication attempt");
        var sourceWork=physical.sourceWork();
        //Fixed entry/validation registration; the two issued-path guards each
        //register their own8 slots. This is not a whole transitive source fee.
        sourceWork.authoritySlots(12);
        if(capture==null||view==null||path==null)
            throw new IllegalArgumentException("Missing captured publication source");
        var root=physical.owner();var probe=physical.probe();probe.checkpoint();
        capture.requireProduced(view,root,sourceWork,probe);
        long points=path.pointCountIssued(view,physical);
        physical.fundPublicationBounds(points);
        var result=new PublicationBounds(physical,points);
        probe.checkpoint();
        capture.requireProduced(view,root,sourceWork,probe);
        if(path.pointCountIssued(view,physical)!=points)
            throw new IllegalArgumentException("Changed captured publication source");
        return result;
    }
    /** Borrow the same private native graph before K or for later DATA export. */
    BSharedCsg1 openCanonicalGraphData(){
        work.prepayTableBody(512);live();
        if(graph==null)throw new IllegalStateException("Private CSG1 graph is not prepared");
        return BSharedCsg1.openTableData(work,this,graph);
    }
    void requireCanonicalGraphData(BPhysicalWork actual,List<InferenceProfile> original){
        work.prepayTableBody(128);live();
        if(actual!=work||original!=graph||graph==null)
            throw new IllegalArgumentException("Foreign private CSG1 graph");
    }
    /** Borrowed-engine DATA only; the fixed runtime caller supplies no handle. */
    String digestCanonicalGraphData(java.security.MessageDigest borrowed){
        try(var codec=openCanonicalGraphData()){return codec.sha256SunData(borrowed);}
    }
    /** Pre-K hash only: ends temporary root scratch before metadata and sizing. */
    private String digestPreKCanonicalGraphData(java.security.MessageDigest borrowed){
        requirePreKCanonicalGraphData(work,graph);
        return BSharedCsg1.sha256PreKTableData(work,this,graph,borrowed);
    }
    void requirePreKCanonicalGraphData(BPhysicalWork actual,List<InferenceProfile> original){
        requireCanonicalGraphData(actual,original);
        if(phase!=0||budget.k!=0||metadataSource!=null)
            throw new IllegalStateException("Private graph digest must precede metadata and K");
    }
    private static final class Budget {
        //All numeric compiler state is in this one frame-funded wrapper. No
        //per-fit arrays,Program interpreter,caller dictionary or K override.
        long baselineFee,baselineBytes,pairs,tableBytes,headroom,current,memoryBase,
                vertices,cells,descriptors,edges,rings,node,nodeHelper,admissions,
                edgeFee,geometryUpper,fixed,semanticBase,semanticSlope,helperBase,
                helperSlope,boundaryBase,boundarySlope,hookBase,hookSlope,proxyUpper,
                localQueries,localFeeUpper,localFeeUsed,localEdges;
        int k,localRows;
        //Whole admitted future publication UNION from unchanged count IR:
        //2GiB bytes,65536B leaves,<=1MiB/32 digest-cache slots;not actual work.
        static final long FUTURE_PUBLICATION=228397641503794L;
        //Compiler bootstrap BEFORE its checked producers/loops. Exact source
        //partition is independently tested; no semantic-body self-admission.
        static final long COMPILER_BOOTSTRAP=512*8+1024+12+256+16;
        void bind(BPhysicalPairTable t) {
            t.work.prepayTableBody(COMPILER_BOOTSTRAP);
            var b=t.response.bound();int n=t.graph.size();long p=0;
            for(int i=0;i+1<n;i++)p=add(p,mul(t.graph.get(i).cells().size(),t.graph.get(i+1).cells().size()));
            if(n<1||n>81||p>8_000_000)throw new IllegalArgumentException("Complete original graph cap");
            pairs=p;k=1;
            tableBytes=add(FRAME,add(mul(3,array(p,8)),add(array(p,4),add(mul(2,array(n,4)),add(array(n,8),add(3*48,array(4,8)))))));
            descriptors=b.indices[2].length;vertices=b.source.length;cells=b.interpolation.length;
            if(descriptors>8192)throw new IllegalArgumentException("All original descriptor cap");
            var sourceRings=b.input.evidence.evidenceRegion().polygons();rings=sourceRings.size();
            long r=b.input.region.polygons().size();
            //New repeated joins,not the already registered runtime/K DAG:
            //each ring4 API/check slots*8+20 indexed/control slots=52;
            //each mode4 API/check slots*8+24 indexed/control slots=56.
            //Two outer loop init/final bodies6 each. Registration/checked
            //producer calls occur under COMPILER_BOOTSTRAP above.
            t.work.prepayTableBody(add(12,add(mul(52,add(r,rings)),mul(56,descriptors))));
            for(int i=0;i<rings;i++)edges=add(edges,sourceRings.get(i).size());
            if(rings<1||edges<3*rings)throw new IllegalArgumentException("Source region census");
            long e=0;
            for(int i=0;i<r;i++)e=add(e,b.input.region.polygons().get(i).size());
            long event=add(2,mul(2,e));
            long ar=mul(event,add(mul(128,add(e,1)),add(bits(event-1),64)));
            long gp=add(r,add(mul(e,add(mul(3,event),2)),add(mul(10,event),add(3,mul(event,bits(event))))));
            geometryUpper=mul(p,gp);edgeFee=add(214,add(519,add(mul(128,ar),mul(86,gp))));
            long visits=mul(50,Math.max(1,descriptors)),taps=mul(9,visits);
            long polls=add(1645,add(mul(122,edges),mul(2,rings)));
            long source=add(BPhysicalWork.ENDPOINT_SOURCE_UNITS,add(BPhysicalWork.PANEL_INTERFACE_UNITS,add(12406+39992,add(mul(969,edges),add(mul(42,rings),mul(169,polls))))));
            long wrappers=add(264+16*66,mul(304,edges));
            long candidate=add(BPhysicalWork.COMPONENT_PANEL_BODY,add(BPhysicalWork.MAX_TRANSLATED_COMPONENT,
                    add(mul(9,add(source,wrappers)),14*73+36*66)));
            long controls=add(mul(visits,315+BPhysicalWork.MAX_UPDATE_CALLER),
                    51*BPhysicalWork.MAX_INITIAL+49*(315+1005+BPhysicalWork.MAX_WEIGHT_CALLER)+903+299+4096);
            node=add(BPhysicalWork.ADAPTIVE_CONTROL,add(mul(visits,BPhysicalWork.ADAPTIVE_PARAMETER),
                    add(BPhysicalWork.ROOT_BODY,add(controls,mul(visits,candidate)))));
            long retained=add(mul(taps,add(24,mul(4,edges))),mul(14,visits));
            admissions=add(retained,add(taps,add(visits,3)));
            nodeHelper=add(BPhysicalWork.prefixNodeHelperUnits(Math.max(1,descriptors),edges,rings),mul(taps,8926));
            //All original baseline allocations are ALREADY in this current,
            //including the frame776. Nothing retained is credited/re-added.
            //Move the complete old adaptive fixed/H/K prepayment BEFORE H,
            //rather than paying for a compiler operation after it executes.
            t.work.prepayTableBody(BPhysicalWork.adaptiveSetupUnits(n));
            headroom=BPhysicalAdaptiveIntegral.tableHeadroom(b);
            long scratch=geometryScratch(e,event);
            long prefix=BPhysicalSignaturePrefix.bytes(t.response.sourceWidth(),t.response.sourceHeight());
            long workspace=p==0?0:add(18120,add(192,add(256,prefix)));
            //A real same-probe boundary before taking C0 permits cancellation
            //and factual caller reservations. Search never retries a changed
            //post-C0 root. Two source-live guards+owner/caller controls187;
            //its actual poll helper61 remains separately observed.
            t.work.prepayTableBody(2*73+8+32+1);t.work.poll();t.live();
            current=t.owner.currentBytes();
            memoryBase=add(current,add(tableBytes-FRAME,add(workspace,add(headroom,scratch))));
            long remaining=t.work.sourceWork().remainingMetadataBytes();
            if(remaining<0||remaining>1L<<20)throw new IllegalStateException("Original holder observation");
            long used=(1L<<20)-remaining;
            //Complete future schema cache:actual raw graph records,all source
            //roles and bounded text occurrences,not a table subset or graph cap
            //misapplied to the table. No output/query is executed here.
            long data=128; //original CSG1 guard;unexecuted,conservative
            data=add(data,chunks(vertices,9));data=add(data,chunks(cells,1));
            data=add(data,chunks(descriptors,208));data=add(data,chunks(add(t.publication.sourcePointsUpper,add(edges,e)),24));
            data=add(data,chunks(n,32));data=add(data,chunks(mul(20,descriptors),40));
            long provenance=0;
            for(int i=0;i<n;i++){var ms=b.input.facts.get(i).modes();
                for(int j=0;j<ms.size();j++)provenance=add(provenance,ms.get(j).provenance().size());}
            data=add(data,chunks(provenance,80));
            int globals=t.metadataSource==null?24:25,fixedHeader=t.metadataSource==null?561:731;
            long text=mul(add(globals,mul(5,descriptors)),19+3*8192L);
            data=add(data,chunks(text,1));data=add(data,chunks(p,28));
            //Metadata561fixed+24roles*24+ringheaders+238long cut+text.
            long meta=add(8+fixedHeader+24*24+238*8+8,add(mul(4,add(r,rings)),text));
            data=add(data,chunks(meta,1));long inventories=(data+1022)/1023;
            long cache=array(mul(32,add(data,inventories)),1);
            long holder=add(used,add(96+2*208+24,add(821858,cache)));
            if(holder>1L<<20)throw new IllegalStateException("Complete future publication holder");
            long w=t.response.sourceWidth(),h=t.response.sourceHeight();
            long build=BPhysicalWork.prefixConstructionUnits((int)w,(int)h);
            long engine=add(16+284+64+23*rings+BPhysicalWork.PANEL_CONSTRUCTOR_UNITS+BPhysicalWork.PREFIX_RUNTIME_SETUP,
                    add(build,256+64+BPhysicalWork.COMPONENT_PANEL_SETUP+320+BPhysicalWork.MAX_SETUP+256+BPhysicalWork.ROOT_SETUP
                            +256+BPhysicalWork.adaptiveSetupUnits(n)));
            //Positive complete branch-union fees retain all future publication
            //and support work;the builder exports these as DERIVED upper only.
            //New actual metadata K/completion dispatches16;two successful
            //bind bodies only on the explicit metadata path;SUPPORT dispatch8
            //per original opportunity and its optional maximum update once/P.
            t.work.prepayTableBody(256+40*8+2*32); //<=8 checked formula calls +40 role wrapper branches/two checked additions
            long metadataFuture=add(16+16*p,add(t.metadataSource==null?0:2*BPhysicalPublicationMetadata.FIXED_BIND,
                mul(p,8+(t.metadataSource==null?0:BPhysicalPublicationMetadata.FIXED_SUPPORT))));
            long legacyFixed=add(SOLVER_ENTRY_UPPER+SOLVER_HEADROOM_SETUP,add(t.work.semanticUnits(),add(FUTURE_PUBLICATION,add(engine,
                    add(661+81L*n+144*p,add(mul(p,add(edgeFee,575+33)),32783+46L*n+1125+48+191+2*142+2*BPhysicalTableRegistry.COPY+2*BPhysicalWork.TABLE_IMAGE_COPY))))));
            fixed=add(legacyFixed,metadataFuture);
            semanticBase=add(fixed,add(mul(p,node),50+41));
            semanticSlope=add(mul(p,add(node,BPhysicalWork.ADAPTIVE_MERGE)),41);
            //Exact new helper census:K dispatch,slot verification,completion
            //dispatch=3;optional2 bind prepays and<=P maximum-update prepays.
            //No new helper on the existing SUPPORT prepay575 now paying583.
            //Pre-K new prepays are nonrecursive,>=3slots/one helper63,so
            //their independently proved ratio<=21 is below legacy prefix230.
            long metadataHelpers=63*(3+(t.metadataSource==null?0:2+p));
            helperBase=add(metadataHelpers,add(mul(230,legacyFixed),add(mul(p,nodeHelper),4845+381)));
            helperSlope=mul(p,add(nodeHelper,198));
            long allEvents=0,deltaEvents=0;
            for(int role=0;role<20;role++){allEvents=add(allEvents,roleMaximum(role));deltaEvents=add(deltaEvents,roleSlope(role));}
            boundaryBase=add(mul(allEvents,BPhysicalTableRegistry.BEGIN+BPhysicalTableRegistry.END),BPhysicalTableRegistry.BEGIN);
            boundarySlope=mul(deltaEvents,BPhysicalTableRegistry.BEGIN+BPhysicalTableRegistry.END);
            hookBase=mul(add(mul(2,fixed),add(mul(2*p,admissions),1)),BPhysicalTableRegistry.HOOK);
            hookSlope=mul(mul(2*p,add(admissions,1)),BPhysicalTableRegistry.HOOK);
            if(!fits(1))throw new IllegalStateException("No complete jointly funded table root");
            if(p!=0){int lo=1,hi=(int)((64L<<20)/56),steps=0;
                while(lo<hi){if(++steps>31)throw new AssertionError("Joint search bound");int mid=lo+(hi-lo+1)/2;if(fits(mid))lo=mid;else hi=mid-1;}k=lo;}
            long semantic=at(semanticBase,semanticSlope,k),helpers=at(helperBase,helperSlope,k);
            proxyUpper=add(at(boundaryBase,boundarySlope,k),add(at(hookBase,hookSlope,k),2*BPhysicalTableRegistry.COPY));
            t.work.poll();t.live();if(t.owner.currentBytes()!=current)throw new IllegalStateException("C0 changed;no search retry");
            t.work.installTableCaps(t,semantic,helpers);t.registry.install(t,proxyUpper);
            t.work.prepayTableBody(661+81L*n+144*p+1125+48); //before arrays/init/copy
        }
        static long bits(long x){return x==0?0:64-Long.numberOfLeadingZeros(x);}
        static long chunks(long records,int stride){return records==0?0:1+(records-1)/(65528/stride);}
        static long at(long base,long slope,int k){return add(base,mul(slope,k-1L));}
        boolean fits(int k){try{long sem=at(semanticBase,semanticSlope,k),h=at(helperBase,helperSlope,k);
            add(add(sem,h),add(at(boundaryBase,boundarySlope,k),add(at(hookBase,hookSlope,k),2*BPhysicalTableRegistry.COPY)));
            return add(memoryBase,pairs==0?0:BPhysicalAdaptiveIntegral.poolBytes(k))<=64L<<20;
        }catch(ArithmeticException overflow){return false;}}
        long roleSlope(int role){long visits=mul(50,Math.max(1,descriptors)),taps=mul(9,visits);
            return role==3?mul(pairs,mul(24,taps)):role==5?mul(pairs,mul(14,visits)):
                role==6?mul(pairs,mul(4*edges,taps)):role==9||role==10?pairs:0;}
        long roleMaximum(int role){long slope=roleSlope(role);
            if(role==3)return add(add(mul(k,slope),vertices),mul(36,localQueries));
            if(role==4)return add(add(mul(6,vertices),cells),mul(mul(90,vertices),localQueries));
            if(role==5)return add(add(mul(k,slope),mul(32,descriptors)),mul(11,localQueries));
            if(role==6)return add(mul(k,slope),mul(mul(36,localEdges),localQueries));
            if(role==9)return mul(k,slope);
            if(role==10)return mul(k-1L,pairs);
            if(role==8||role==18)return pairs;
            if(role==11)return localFeeUpper==0?0:graphRows();
            if(role==17)return 0;return 1;}
        private long graphRows(){return localRows;}
        long roleCost(int role){return role==3||role==4?66:role==5?73:role==6?76:role==9?node:
                role==10?BPhysicalWork.ADAPTIVE_MERGE:role==8?edgeFee:role==18?575:
                role==11?localFeeUpper:role==17?0:role>=13&&role<=16?FUTURE_PUBLICATION:fixed;}
        static long geometryScratch(long e,long event){return add(72+32+40+2*40+40+3*32+816,
                add(mul(2,array(event,8)),add(mul(event,24),add(array(event,16),mul(event,2*32)))));}
    }
}
