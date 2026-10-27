package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;

/**
 * Source-owned input allocation/admission implementation in progress.
 * No runtime/source permit is issued. Actual recipe/capture entry remains gated.
 */
final class BSharedCaptureAdmission implements AutoCloseable {
    private static final int INPUT_ROLES=9,FAILURES=16,IMAGE=0,VALIDITY=1,SCALAR=2,
        GEOGRAPHIC=3,METRIC=4,TRANSFORM=5,GRID=6,RESOLUTION=7,LINEAGE=8;
    //13refs+four flags+one int =>O112=128;two9ref arrays88 each;16ref array144;
    //16private stackless Throwable graphs96 each. Paid in the existing holder.
    static final long METADATA_BYTES=128+2*88+144+FAILURES*96;
    //Two generations of at most12 immutable points/lists:original source
    //producer,then its experiment-owned immutable value copy. Each List.of
    //varargs invocation may hold both the caller array and the ListN copy.
    static final long METRIC_SOURCE_UPPER=2L*(12*32+2*AttemptMemoryLedger.arrayBytes(12,8)+32);
    private final BPhysicalWork physical;
    private final BSharedOwnedCaptureProbe.Context context;
    private final BSharedWork work;
    private final AttemptMemoryLedger.Owner inputs;
    private final BSharedOwnedCaptureProbe probe;
    private final BSharedRuntimeBootstrap.InitializationPermit permit;
    private final Object[] values;
    private final AttemptMemoryLedger.MemoryLease[] leases;
    private final Failure[] failures;
    private MetadataNames metadataNames;
    private BSharedCaptureAdmission rawHashPass;
    private RawHashBuffers rawHashBuffers;
    private BExperimentalScalarCapture claimedSession;
    private final boolean prehashOnly;
    private int rawHashCount;
    private boolean closed,captureStarted,continuationStarted;

    private static final class Failure extends IllegalArgumentException {
        Failure(String message){super(message);}
        @Override public synchronized Throwable fillInStackTrace(){return this;}
    }
    record MetadataNames(String id,String sourceIdentity) { }
    record RawHashBuffers(java.nio.ByteBuffer raster,byte[] mask) { }
    private BSharedCaptureAdmission(BPhysicalWork physical,BSharedOwnedCaptureProbe.Context context,
            AttemptMemoryLedger.Owner inputs,BSharedRuntimeBootstrap.InitializationPermit permit,
            boolean prehashOnly){
        this.physical=physical;this.context=context;this.inputs=inputs;this.permit=permit;
        this.prehashOnly=prehashOnly;
        work=physical.sourceWork();probe=(BSharedOwnedCaptureProbe)physical.probe();
        values=new Object[INPUT_ROLES];leases=new AttemptMemoryLedger.MemoryLease[INPUT_ROLES];
        failures=new Failure[FAILURES];
        for(int i=0;i<FAILURES;i++)failures[i]=new Failure(failureMessage(i));
    }
    private static String failureMessage(int branch){
        return switch(branch){
            case 0->"CAPTURE_INPUT_OWNER";case 1->"CAPTURE_INPUT_REISSUE";
            case 2->"CAPTURE_INPUT_SHAPE";case 3->"CAPTURE_INPUT_CLASS";
            case 4->"CAPTURE_SCALAR_NORMALIZATION";case 5->"CAPTURE_METADATA_TEXT";
            case 6->"CAPTURE_INPUT_LINEAGE";case 7->"CAPTURE_INPUT_RESOLUTION";
            case 8->"CAPTURE_INPUT_FRAME";case 9->"CAPTURE_INPUT_GEOMETRY";
            case 10->"CAPTURE_NATIVE_WORKING_LIMIT";case 11->"CAPTURE_RETAINED_LIMIT";
            case 12->"CAPTURE_PHASE_HEADROOM";case 13->"CAPTURE_PHASE_ONCE";
            case 14->"CAPTURE_RUNTIME_PREMISE";default->"CAPTURE_SOURCE_PROGRAM_UNAVAILABLE";
        };
    }
    static BSharedCaptureAdmission openUnit(BPhysicalWork physical,BSharedOwnedCaptureProbe.Context context){
        return open(physical,context,null);
    }
    static BSharedCaptureAdmission openRuntime(BSharedRuntimeBootstrap.InitializationPermit permit,
            BPhysicalWork physical,BSharedOwnedCaptureProbe.Context context){
        permit.require();context.requireRuntime(permit);
        return open(physical,context,permit);
    }
    private static BSharedCaptureAdmission open(BPhysicalWork physical,BSharedOwnedCaptureProbe.Context context,
            BSharedRuntimeBootstrap.InitializationPermit permit){
        physical.claimOwnedCaptureInputs(context);
        AttemptMemoryLedger.Owner child=null;
        try{
            child=physical.owner().child("source-owned-capture-inputs");
            return new BSharedCaptureAdmission(physical,context,child,permit,false);
        }catch(RuntimeException|Error primary){
            if(child!=null)try{child.close();}catch(RuntimeException|Error secondary){ /* preserve primary */ }
            throw primary;
        }
    }
    private void live(){
        physical.requireOwnedCaptureContext(context);
        if(closed||inputs.isClosed())throw failures[0];
        probe.requireContextUnit(context);work.requireProbe(probe);
    }
    /** Claims the fresh same-owner Session before the first generation. A
     * rejected foreign/repeated call owns neither Session nor input cleanup. */
    synchronized void claimOriginalSessionUnit(BExperimentalScalarCapture session){
        live();if(prehashOnly||rawHashPass!=null||captureStarted||claimedSession!=null)throw failures[13];
        session.claimOriginalOwnedAttempt(physical.owner(),work,probe);
        claimedSession=session;
    }
    /** The first original generation remains live beneath the second's owner.
     * The holder pays a second complete admission before its constructor; the
     * one attempt claim, root, Work, probe and clock are shared. */
    synchronized BSharedCaptureAdmission beginRawHashPassOriginalUnit(){
        live();if(prehashOnly)throw failures[1];
        if(rawHashPass!=null)return rawHashPass;
        AttemptMemoryLedger.Owner child=null;
        try{
            probe.checkpoint();work.metadata(METADATA_BYTES);
            child=inputs.child("original-raw-hash-pass");
            var result=new BSharedCaptureAdmission(physical,context,child,permit,true);
            rawHashPass=result;
            probe.checkpoint();live();return result;
        }catch(RuntimeException|Error primary){
            if(child!=null)try{child.close();}catch(RuntimeException|Error secondary){ /* preserve primary */ }
            abort(primary);throw primary;
        }
    }
    /** Reviewed fixed HeapByteBuffer shell64, backing array and mask, plus
     * this private two-reference carrier32: all live before the hash calls. */
    synchronized RawHashBuffers rawHashBuffersOriginalUnit(int scalarLength,int validityLength){
        live();if(!prehashOnly||rawHashBuffers!=null)throw failures[1];
        try{
            if(scalarLength!=6561||validityLength!=6561)throw failures[2];
            long rasterBytes=Math.multiplyExact((long)scalarLength,8);
            long graph=Math.addExact(64+32,Math.addExact(
                AttemptMemoryLedger.arrayBytes(rasterBytes,1),
                AttemptMemoryLedger.arrayBytes(validityLength,1)));
            probe.checkpoint();var reservation=reserve(graph);
            var raster=java.nio.ByteBuffer.allocate(Math.toIntExact(rasterBytes))
                .order(java.nio.ByteOrder.BIG_ENDIAN);
            var mask=new byte[validityLength];
            var result=new RawHashBuffers(raster,mask);
            reservation.adopt(result);rawHashBuffers=result;
            probe.checkpoint();live();return result;
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    /** Current runtime and digest/provider proof does not issue this gate.
     * It is checked before either full recipe generation. */
    synchronized void requireRawDigestRuntimeUnit(){
        live();if(!prehashOnly)throw failures[0];
        if(permit==null)throw failures[14];
        permit.require();throw failures[15];
    }
    /** Reviewed raw SHA success-core subset (engine552, Instance32,
     * digest bytes48, retained lowercase hex String112). Provider lookup,
     * throw frames and formatting temporaries are still unsealed; the gate
     * remains closed before this call can execute. */
    synchronized String rawHashOriginalUnit(byte[] bytes){
        live();if(!prehashOnly||rawHashBuffers==null||rawHashCount>=2)throw failures[1];
        if(bytes!=(rawHashCount==0?rawHashBuffers.raster().array():rawHashBuffers.mask()))
            throw failures[0];
        requireRawDigestRuntimeUnit();
        try{
            probe.checkpoint();var reservation=reserve(552+32+48+112);
            var result=BIdentificationReceipt.hash(bytes);
            reservation.adopt(result);rawHashCount++;
            probe.checkpoint();live();return result;
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    /** Original source(id) is the same object passed to the second capture
     * generation, retained by that generation without a duplicate payload. */
    synchronized void retainRawMetricSourceOriginalUnit(java.util.List<MetricPoint> metric){
        live();if(prehashOnly||values[METRIC]!=null||rawHashPass==null
                ||rawHashPass.values[METRIC]!=metric)throw failures[0];
        try{probe.checkpoint();leases[METRIC]=inputs.retainIfAbsent(metric);
            values[METRIC]=metric;probe.checkpoint();live();}
        catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    synchronized AttemptMemoryLedger.Reservation reserveRawHashObservationOriginalUnit(){
        live();if(!prehashOnly||rawHashCount!=2)throw failures[1];
        try{probe.checkpoint();return reserve(64+2*24+32);}
        catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    synchronized AttemptMemoryLedger.Reservation reserveCapturedRawHandoffOriginalUnit(){
        live();if(prehashOnly||rawHashPass==null||metadataNames==null)throw failures[0];
        try{
            probe.checkpoint();
            var reservation=reserve(32);
            probe.checkpoint();live();return reservation;
        }
        catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    /** Actual array allocation at the producer site,not adoption of caller data. */
    synchronized double[] scalarPlaneUnit(int length){
        return allocateScalar(length,null);
    }
    /** Clone adapter used at the original private ScalarInput getter site. */
    synchronized double[] scalarCopyAtOriginalSite(double[] original){
        return allocateScalar(original==null?-1:original.length,original);
    }
    private double[] allocateScalar(int length,double[] original){
        live();if(values[SCALAR]!=null)throw failures[1];
        try{
            if(length<4||length>6561)throw failures[2];
            probe.checkpoint();
            var reservation=reserve(AttemptMemoryLedger.arrayBytes(length,8));
            double[] result=original==null?new double[length]:original.clone();
            leases[SCALAR]=reservation.adopt(result);values[SCALAR]=result;
            probe.checkpoint();live();return result;
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    synchronized boolean[] validityPlaneUnit(int length){
        return allocateValidity(length,null);
    }
    synchronized boolean[] validityCopyAtOriginalSite(boolean[] original){
        return allocateValidity(original==null?-1:original.length,original);
    }
    private boolean[] allocateValidity(int length,boolean[] original){
        live();if(values[VALIDITY]!=null)throw failures[1];
        try{
            if(length<4||length>6561)throw failures[2];
            probe.checkpoint();
            var reservation=reserve(AttemptMemoryLedger.arrayBytes(length,1));
            boolean[] result=original==null?new boolean[length]:original.clone();
            leases[VALIDITY]=reservation.adopt(result);values[VALIDITY]=result;
            probe.checkpoint();live();return result;
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    /** Pays the original ScalarInput record and both constructor clones before entry. */
    synchronized AttemptMemoryLedger.Reservation reserveOriginalScalarInputUnit(
            int width,int height,double[] scalar,boolean[] validity,java.util.List<MetricPoint> source){
        live();
        try{
            if(width<2||height<2||scalar==null||validity==null||source==null
                    ||source.size()<2||source.size()>12
                    ||scalar.length!=Math.multiplyExact(width,height)||validity.length!=scalar.length
                    ||!source.getClass().getName().startsWith("java.util.ImmutableCollections$"))
                throw failures[2];
            probe.checkpoint();
            return reserve(Math.addExact(64,Math.addExact(
                AttemptMemoryLedger.arrayBytes(scalar.length,8),
                AttemptMemoryLedger.arrayBytes(validity.length,1))));
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    synchronized void abortOriginalScalarInputUnit(Throwable primary){abort(primary);}
    /** Original recipe planes are separately owned from record/getter clones. */
    synchronized double[] rawScalarPlaneOriginalUnit(int length){
        live();
        try{
            if(length<4||length>6561)throw failures[2];
            probe.checkpoint();
            var reservation=reserve(AttemptMemoryLedger.arrayBytes(length,8));
            var scalar=new double[length];reservation.adopt(scalar);
            probe.checkpoint();live();return scalar;
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    synchronized double[] rawScalarCopyOriginalUnit(double[] original){
        live();
        try{
            if(original==null||original.length<4||original.length>6561)throw failures[2];
            probe.checkpoint();
            var reservation=reserve(AttemptMemoryLedger.arrayBytes(original.length,8));
            var scalar=original.clone();reservation.adopt(scalar);
            probe.checkpoint();live();return scalar;
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    synchronized boolean[] rawValidityCopyOriginalUnit(boolean[] original){
        live();
        try{
            if(original==null||original.length<4||original.length>6561)throw failures[2];
            probe.checkpoint();
            var reservation=reserve(AttemptMemoryLedger.arrayBytes(original.length,1));
            var validity=original.clone();reservation.adopt(validity);
            probe.checkpoint();live();return validity;
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    synchronized double[] rawStationsCopyOriginalUnit(double[] original){
        live();
        try{
            if(original==null||original.length<2||original.length>119)throw failures[2];
            probe.checkpoint();
            var reservation=reserve(AttemptMemoryLedger.arrayBytes(original.length,8));
            var stations=original.clone();reservation.adopt(stations);
            probe.checkpoint();live();return stations;
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    synchronized boolean[] rawValidityPlaneOriginalUnit(int length){
        live();
        try{
            if(length<4||length>6561)throw failures[2];
            probe.checkpoint();
            var reservation=reserve(AttemptMemoryLedger.arrayBytes(length,1));
            var validity=new boolean[length];reservation.adopt(validity);
            probe.checkpoint();live();return validity;
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    synchronized MetricPoint metricPointOriginalUnit(double x,double y){
        live();
        try{
            if(!Double.isFinite(x)||!Double.isFinite(y))throw failures[9];
            probe.checkpoint();
            var reservation=reserve(AttemptMemoryLedger.objectBytes(16));
            var point=new MetricPoint(x,y);reservation.adopt(point);
            probe.checkpoint();live();return point;
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    synchronized void requireOwnedMetricSource(java.util.List<MetricPoint> metric){
        live();if(values[METRIC]!=metric||leases[METRIC]==null)throw failures[0];
    }
    synchronized void prepayOriginalFlecksUnit(){
        live();
        try{reserve(Math.multiplyExact(6561L,AttemptMemoryLedger.arrayBytes(4,8)));}
        catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    synchronized void recipeCheckpointUnit(){live();probe.checkpoint();}
    /** Selected thin recipe, with no activation of the original seven-case
     * static cache. The bound covers two 6561 planes and their Fixture clones,
     * four Strand original/clone pairs, the four-entry collection and the
     * three short generated labels before the first render allocation. Getter
     * and ScalarInput clones are separate original-site reservations. */
    synchronized AttemptMemoryLedger.Reservation reserveSelectedThinRecipeUnit(String id){
        live();
        try{
            if(!BSelectedThinStrandFixtures.supported(id))throw failures[3];
            probe.checkpoint();return reserve(131072);
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    /** At most eleven four-point rectangles from twelve owned source points;
     * their region, the certified frame and original curved-grid extent
     * construction are prepaid before entering the unchanged producer. */
    synchronized AttemptMemoryLedger.Reservation reserveCurvedGridScratchUnit(
            java.util.List<MetricPoint> metric){
        live();if(values[METRIC]!=metric)throw failures[0];
        try{
            if(metric.size()!=12)throw failures[2];
            if(values[GRID]!=null||values[GEOGRAPHIC]!=null)throw failures[1];
            probe.checkpoint();return reserve(10000);
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    synchronized void adoptCurvedGridOriginalUnit(AttemptMemoryLedger.Reservation reservation,
            MetricRasterGrid grid){
        live();
        try{
            if(grid==null||grid.coordinateFrame()==null||grid.width()<2||grid.height()<2)
                throw failures[8];
            var lease=reservation.adopt(grid);
            leases[GRID]=lease;values[GRID]=grid;
            //The frame is a constituent of this charged grid source graph.
            leases[GEOGRAPHIC]=lease;values[GEOGRAPHIC]=grid.coordinateFrame();
            probe.checkpoint();live();
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    /** Conservative simultaneous source graph upper for the original thin
     * fixture renderer; retained under this one input child until capture. */
    synchronized AttemptMemoryLedger.Reservation reserveThinSwitchbackFixtureUnit(String id){
        live();
        try{
            if(!BThinSwitchbackFixtures.STRONG.equals(id)&&!BThinSwitchbackFixtures.FAINT.equals(id))
                throw failures[3];
            long source=12*32L+2*AttemptMemoryLedger.arrayBytes(12,8)+32;
            long fragments=6*32L+2*(2*AttemptMemoryLedger.arrayBytes(3,8)+32)+32;
            long planes=2*(AttemptMemoryLedger.arrayBytes(6561,8)
                +AttemptMemoryLedger.arrayBytes(6561,1));
            long points=2L*6561*32;
            long bytes=Math.addExact(Math.addExact(source,fragments),
                Math.addExact(Math.addExact(planes,points),48));
            probe.checkpoint();return reserve(bytes);
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    /** One original same-trail byId pass: fixed Frame/station/recording/pixel
     * parents. Collection terms follow the reviewed T<=119, ListN and
     * default ArrayList growth source inventory; later getters are separate. */
    synchronized AttemptMemoryLedger.Reservation reserveSameTrailFixtureUnit(String id){
        live();
        try{
            int count=BSameTrailRecordingFixtures.FIVE.equals(id)?5:
                BSameTrailRecordingFixtures.THREE.equals(id)?3:0;
            if(count==0)throw failures[3];
            long t=119,i=6561,s=12;
            long aT=AttemptMemoryLedger.arrayBytes(t,8);
            long aS=AttemptMemoryLedger.arrayBytes(s,8);
            long station=Math.multiplyExact(6,24+80+t*(64+24)+24+56);
            long arrays=2*(AttemptMemoryLedger.arrayBytes(i,8)+AttemptMemoryLedger.arrayBytes(i,1))
                +3*aS+count*t*32+2L*count*t*32+i*32
                +count*t*AttemptMemoryLedger.arrayBytes(2,4)+6*aT+aT
                +s*32+2*aS+32;
            long collections=station+32+AttemptMemoryLedger.arrayBytes(10,8)
                +count*(32+3920)+count*(2*aT+32)+384+56+64;
            long bytes=Math.addExact(arrays,collections);
            probe.checkpoint();return reserve(bytes);
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    /** Separated three-recording original fixture. Each point() invokes two
     * fresh Frames and two additional twelve-point source lists; fund all
     * 3*T calls before the first station/source construction. */
    synchronized AttemptMemoryLedger.Reservation reserveSeparatedSameTrailFixtureUnit(){
        live();
        try{
            long t=119,i=6561;
            long aT=AttemptMemoryLedger.arrayBytes(t,8);
            long aS=AttemptMemoryLedger.arrayBytes(12,8);
            long source=12*32+2*aS+32;
            long frame=56+source+3*aS;
            long station=24+80+t*(64+24)+24+56+aT;
            long point=2*frame+2*source+3*32;
            long traces=32+AttemptMemoryLedger.arrayBytes(10,8)
                +3*(32+3920+2*aT+32);
            long fixture=2*(AttemptMemoryLedger.arrayBytes(i,8)
                +AttemptMemoryLedger.arrayBytes(i,1))+aT+384+64;
            long bytes=Math.addExact(Math.addExact(frame,station),
                Math.addExact(Math.multiplyExact(3*t,point),
                    Math.addExact(traces,Math.addExact(i*32,fixture))));
            probe.checkpoint();return reserve(bytes);
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    synchronized AttemptMemoryLedger.Reservation reserveMetricSourceUnit(){
        live();if(values[METRIC]!=null)throw failures[1];
        try{probe.checkpoint();return reserve(METRIC_SOURCE_UPPER);}
        catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    synchronized void adoptMetricSourceUnit(AttemptMemoryLedger.Reservation reservation,
            java.util.List<MetricPoint> metric){
        live();
        try{
            if(metric==null||metric.size()<2||metric.size()>12)throw failures[2];
            for(int i=0;i<metric.size();i++)if(metric.get(i)==null)throw failures[2];
            leases[METRIC]=reservation.adopt(metric);values[METRIC]=metric;
            probe.checkpoint();live();
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    /** Source-owned scalar predicate;no native validator or caller callback. */
    synchronized void prevalidateScalarUnit(){
        live();
        try{
            if(!(values[SCALAR] instanceof double[] scalar)||!(values[VALIDITY] instanceof boolean[] valid)
                    ||scalar.length!=valid.length)throw failures[2];
            for(int i=0;i<scalar.length;i++){
                probe.checkpoint();
                if(valid[i]&&(!Double.isFinite(scalar[i])||scalar[i]<0||scalar[i]>1))throw failures[4];
            }
            probe.checkpoint();live();
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    /** Source-owned closed text/radius checks in the native FieldSpec then
     * validateInputs order. Fixed experiment labels bound all scans. */
    synchronized void prevalidateMetadataUnit(String id,String sourceIdentity,
            String fieldName,double radius){
        live();
        try{
            probe.checkpoint();
            if(fieldName==null||fieldName.length()>32||fieldName.isBlank()
                    ||id==null||id.length()>128||id.isBlank()
                    ||sourceIdentity==null||sourceIdentity.length()>192||sourceIdentity.isBlank())
                throw failures[5];
            if(!Double.isFinite(radius)||radius<=0)throw failures[9];
            probe.checkpoint();live();
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    /** Both original leaf labels are built after one pre-call owned graph
     * reservation. The thin selected-only source gets its own provenance ID. */
    synchronized MetadataNames metadataNamesOriginalUnit(String caseId,boolean selectedThin){
        live();
        try{
            if(metadataNames!=null)throw failures[1];
            if(caseId==null||caseId.isBlank()||caseId.length()>104)throw failures[5];
            int idLength=Math.addExact(8,caseId.length());
            int sourceLength=Math.addExact(selectedThin
                ?BSelectedThinStrandFixtures.SOURCE_ID.length()+1:24,caseId.length());
            int maximum=Math.max(idLength,sourceLength);
            long graph=Math.addExact(32+2*24+128,
                Math.addExact(AttemptMemoryLedger.arrayBytes(idLength,1),
                    Math.addExact(AttemptMemoryLedger.arrayBytes(sourceLength,1),
                        Math.multiplyExact(2,AttemptMemoryLedger.arrayBytes(2L*maximum,1)))));
            probe.checkpoint();var reservation=reserve(graph);
            String id="matched-"+caseId;
            String sourceIdentity=selectedThin
                ?BSelectedThinStrandFixtures.SOURCE_ID+":"+caseId
                :"matched-public-declared-"+caseId;
            var result=new MetadataNames(id,sourceIdentity);
            reservation.adopt(result);
            metadataNames=result;
            probe.checkpoint();live();return result;
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    /** Fixed original synthetic scalar lineage. The constructor's empty
     * immutable-list anyMatch graph is covered by the reviewed 240B bound;
     * five references plus boolean give a 64B record shell. */
    synchronized EvidenceFieldLineage lineageOriginalUnit(String palette){
        live();if(values[LINEAGE]!=null)throw failures[1];
        try{
            if(!"matched-declared-scalar".equals(palette))throw failures[6];
            probe.checkpoint();
            var reservation=reserve(304);
            var result=new EvidenceFieldLineage(
                EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,
                palette,EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false);
            leases[LINEAGE]=reservation.adopt(result);values[LINEAGE]=result;
            probe.checkpoint();live();return result;
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    /** Original one-sample native-source resolution: OptionalDouble.of,
     * PitchSample, List12, EvidenceResolution and its validation ListItr are
     * reserved together before the first constructor. */
    synchronized EvidenceResolution resolutionOriginalUnit(double pitchMeters){
        live();if(values[RESOLUTION]!=null)throw failures[1];
        try{
            if(!Double.isFinite(pitchMeters)||pitchMeters<=0)throw failures[7];
            probe.checkpoint();
            var reservation=reserve(192);
            var result=EvidenceResolution.nativeSource(pitchMeters,pitchMeters);
            leases[RESOLUTION]=reservation.adopt(result);values[RESOLUTION]=result;
            probe.checkpoint();live();return result;
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    /** The original three geographic literals, one three-double latitude
     * scratch, certificate and frame are paid before the certified factory. */
    synchronized LocalMetricFrame frameOriginalUnit(){
        live();if(values[GEOGRAPHIC]!=null)throw failures[1];
        try{
            probe.checkpoint();var reservation=reserve(256);
            var result=LocalMetricFrame.certifiedEquirectangular(
                new GeographicPoint(0,0),new GeographicPoint(-1,-1),new GeographicPoint(1,1));
            leases[GEOGRAPHIC]=reservation.adopt(result);values[GEOGRAPHIC]=result;
            probe.checkpoint();live();return result;
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    /** Exact original TYPE_INT_ARGB constructor; 26264B pixel plane plus
     * 680B reviewed constructor wrappers, before entering java.awt. */
    synchronized java.awt.image.BufferedImage imageOriginalUnit(int width,int height){
        live();if(values[IMAGE]!=null)throw failures[1];
        try{
            if(width!=81||height!=81)throw failures[2];
            probe.checkpoint();var reservation=reserve(26944);
            var result=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_ARGB);
            leases[IMAGE]=reservation.adopt(result);values[IMAGE]=result;
            probe.checkpoint();live();return result;
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    /** The original ListN2/ListN12 source map-toList producer, including
     * returned points/list and the reviewed fixed sequential stream frame. */
    synchronized java.util.List<GeographicPoint> geographicOriginalUnit(
            LocalMetricFrame frame,java.util.List<MetricPoint> metric){
        live();if(values[GEOGRAPHIC]!=frame||values[METRIC]!=metric)throw failures[0];
        try{
            int n=metric.size();if(n!=2&&n!=12)throw failures[2];
            probe.checkpoint();
            var reservation=reserve(448+24+32L*n+32+AttemptMemoryLedger.arrayBytes(n,8));
            var result=metric.stream().map(frame::toGeographic).toList();
            if(result.size()!=n)throw failures[2];
            leases[GEOGRAPHIC]=reservation.adopt(result);values[GEOGRAPHIC]=result;
            probe.checkpoint();live();return result;
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    synchronized org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform
            transformOriginalUnit(LocalMetricFrame frame,MetricPoint origin,double pitch){
        live();if(values[TRANSFORM]!=null||values[GEOGRAPHIC]!=frame)throw failures[0];
        try{
            if(origin==null||!Double.isFinite(pitch)||pitch<=0)throw failures[8];
            //LocalMetricAffine64 + RasterPoint32 + finite(double...) four-element
            //array48 in its unchanged compact constructor, live together.
            probe.checkpoint();var reservation=reserve(144);
            var result=org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence
                .SupportedInputRasterTransform.localMetricAffine(frame,
                    new RasterPoint(-origin.xMeters()/pitch,-origin.yMeters()/pitch),1/pitch,0,0,1/pitch);
            leases[TRANSFORM]=reservation.adopt(result);values[TRANSFORM]=result;
            probe.checkpoint();live();return result;
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    synchronized MetricRasterGrid gridOriginalUnit(LocalMetricFrame frame,MetricPoint first,
            double pitch,int width,int height){
        live();if(values[GRID]!=null||values[GEOGRAPHIC]!=frame)throw failures[0];
        try{
            if(first==null||!Double.isFinite(pitch)||pitch<=0||width<2||height<2
                    ||(long)width*height>191401)throw failures[8];
            probe.checkpoint();var reservation=reserve(80);
            var result=new MetricRasterGrid(frame,first,1,0,0,1,pitch,width,height);
            leases[GRID]=reservation.adopt(result);values[GRID]=result;
            probe.checkpoint();live();return result;
        }catch(RuntimeException|Error primary){abort(primary);throw primary;}
    }
    /** Original-payload DATA guards. Historical hash binding additionally relies
     * on frozen G's no-posthash-buffer-write path; this is not an arbitrary
     * mutable-byte-array hash token. No Source8 grammar or permit is issued. */
    void requireRawPayloadLiveData(BSharedWork expected){
        live();if(expected!=work||prehashOnly||rawHashPass==null)throw failures[0];
        rawHashPass.live();
    }
    void rejectRawPayloadData(){throw failures[8];}
    private void requireOriginalRawPayloadData(){
        requireRawPayloadLiveData(work);
        var first=rawHashPass.rawHashBuffers;
        if(rawHashPass.rawHashCount!=2||first==null||first.raster().position()!=first.raster().capacity())
            throw failures[1];
        BOriginalRasterPayloadMatch.verifyCapturedData((double[])values[SCALAR],(boolean[])values[VALIDITY],
            first.raster().array(),first.mask(),work,this);
    }
    /**
     * Actual Session consumer. Only this private producer's identities/leases can
     * cross; neither a caller role list nor equality/hash can supply them.
     * The monitor spans validation,co-ownership and the synchronous Session call,
     * excluding producer.close/allocation races without exposing an owner/lease.
     */
    synchronized EvidenceReadView captureInto(BExperimentalScalarCapture session,String id,String sourceIdentity){
        live();if(prehashOnly)throw failures[0];
        boolean preclaimed=session.originalOwnedClaimed();
        if(preclaimed)session.requireClaimedOriginalOwnedInputs(physical.owner(),work,probe);
        else session.requireOwnedInputs(physical.owner(),work,probe);
        if(captureStarted)throw failures[13];
        if(claimedSession!=null&&claimedSession!=session)throw failures[0];
        captureStarted=true;
        claimedSession=session;
        if(!preclaimed)session.claimOwnedInputs();
        try{
            for(int role=0;role<INPUT_ROLES;role++)
                if(values[role]==null||leases[role]==null)throw failures[2];
            if(!(values[IMAGE] instanceof java.awt.image.BufferedImage)
                    ||!(values[VALIDITY] instanceof boolean[])
                    ||!(values[SCALAR] instanceof double[])
                    ||!(values[GEOGRAPHIC] instanceof java.util.List)
                    ||!(values[METRIC] instanceof java.util.List)
                    ||!(values[TRANSFORM] instanceof org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform)
                    ||!(values[GRID] instanceof MetricRasterGrid)
                    ||!(values[RESOLUTION] instanceof EvidenceResolution)
                    ||!(values[LINEAGE] instanceof EvidenceFieldLineage))throw failures[2];
            if(metadataNames==null||id!=metadataNames.id()||sourceIdentity!=metadataNames.sourceIdentity())
                throw failures[5];
            if(preclaimed){
                probe.checkpoint();
                session.requireClaimedOriginalOwnedInputs(physical.owner(),work,probe);
                requireOriginalRawPayloadData();
            }
            //Definition/initialization alone cannot certify the not-yet-lowered
            //capture program. No UNIT object or caller price opens this gate.
            if(permit==null)throw failures[14];
            permit.require();
            requireFullProgram();
            return session.captureClaimedOwned(this,id,(java.awt.image.BufferedImage)values[IMAGE],
                (boolean[])values[VALIDITY],(double[])values[SCALAR],
                geographicInputs(),metricInputs(),
                (org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform)values[TRANSFORM],
                (MetricRasterGrid)values[GRID],(EvidenceResolution)values[RESOLUTION],25,
                sourceIdentity,"scalar",(EvidenceFieldLineage)values[LINEAGE]);
        }catch(RuntimeException|Error primary){session.abortOwnedInputs(primary);abort(primary);throw primary;}
    }
    @SuppressWarnings("unchecked")
    private java.util.List<GeographicPoint> geographicInputs(){
        return (java.util.List<GeographicPoint>)values[GEOGRAPHIC];
    }
    @SuppressWarnings("unchecked")
    private java.util.List<MetricPoint> metricInputs(){
        return (java.util.List<MetricPoint>)values[METRIC];
    }
    /** Exact private identities, not caller equality or a fabricated permit. */
    synchronized void requireClaimedContinuation(BExperimentalScalarCapture session,
            AttemptMemoryLedger.Owner root,BSharedWork sourceWork,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe sourceProbe,
            String id,java.awt.image.BufferedImage image,boolean[] valid,double[] scalar,
            java.util.List<GeographicPoint> geographic,java.util.List<MetricPoint> metric,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform transform,
            MetricRasterGrid grid,EvidenceResolution resolution,double radius,String sourceIdentity,
            String fieldName,EvidenceFieldLineage lineage){
        live();
        if(prehashOnly||!captureStarted||continuationStarted)throw failures[13];
        if(session!=claimedSession||root!=physical.owner()||sourceWork!=work||sourceProbe!=probe)
            throw failures[0];
        for(int role=0;role<INPUT_ROLES;role++)
            if(values[role]==null||leases[role]==null)throw failures[2];
        if(image!=values[IMAGE]||valid!=values[VALIDITY]||scalar!=values[SCALAR]
                ||geographic!=values[GEOGRAPHIC]||metric!=values[METRIC]
                ||transform!=values[TRANSFORM]||grid!=values[GRID]
                ||resolution!=values[RESOLUTION]||lineage!=values[LINEAGE])throw failures[0];
        if(metadataNames==null||id!=metadataNames.id()||sourceIdentity!=metadataNames.sourceIdentity()
                ||fieldName!="scalar"||radius!=25)throw failures[5];
        try{
            if(permit==null)throw failures[14];
            permit.require();
            requireFullProgram();
            continuationStarted=true;
        }catch(RuntimeException|Error primary){session.abortOwnedInputs(primary);abort(primary);throw primary;}
    }
    private void requireFullProgram(){throw failures[15];}
    private AttemptMemoryLedger.Reservation reserve(long bytes){
        var root=context.rootUnit();
        if(bytes>root.limitBytes()-root.currentBytes())throw failures[12];
        return inputs.reserve(bytes);
    }
    private void abort(Throwable primary){
        try{close();}catch(RuntimeException|Error secondary){ /* primary remains unchanged */ }
    }
    /** Private source handles never escape;closure serializes with allocation/use. */
    @Override public synchronized void close(){closed=true;inputs.close();}
}
