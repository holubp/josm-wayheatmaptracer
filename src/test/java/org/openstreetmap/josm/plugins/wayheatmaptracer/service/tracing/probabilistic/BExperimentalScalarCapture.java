package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.RasterEvidenceCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.DetachedValueVerifier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.MetricCorridorRegion;

/** Private experiment capture stage, not the old binder's source authority. */
final class BExperimentalScalarCapture implements AutoCloseable {
    static final String PRODUCER_ID="experimental-closed-rectangle-scalar-capture-r2-v1";
    //Eight references+six booleans=O70=88; carrier13refs=O104=120;
    //checked callback O8=24. Raw-helper instance/spec/List12/mapper=24+48+32+16.
    //Reviewed fixed seven-child verifier graph: <=12 source points, one pitch
    //sample, one direct field. Carrier/issuer/path are NOT appended to that graph.
    static final long METADATA_SLICE=88+120+24+24+48+32+16+84192;
    static final long PATH_ISSUE_SLOTS=8,PATH_CONSUMER_SLOTS=8;
    private final AttemptMemoryLedger.Owner root, payload;
    private final BSharedWork work;
    private final CancellationProbe probe;
    private Carrier published;
    private CapturedProfiles publishedProfiles;
    private CapturedLattices publishedLattices;
    private AcquisitionPath publishedPath;
    private boolean started,originalOwnedClaimed,profilesStarted,latticesStarted,pathStarted,closed;

    private BExperimentalScalarCapture(AttemptMemoryLedger.Owner root,
            AttemptMemoryLedger.Owner payload,BSharedWork work,CancellationProbe probe) {
        this.root=root;this.payload=payload;this.work=work;this.probe=probe;
    }

    static BExperimentalScalarCapture open(AttemptMemoryLedger.Owner owner,
            BSharedWork work,CancellationProbe probe) {
        Objects.requireNonNull(owner);Objects.requireNonNull(work);Objects.requireNonNull(probe);
        if(owner.limitBytes()>64L<<20)throw new IllegalArgumentException("Experimental capture requires a <=64MiB backing ledger");
        work.requireOwner(owner);work.requireProbe(probe);
        work.authoritySlots(1); //new Session default cells/constructor joins before allocation
        work.metadata(METADATA_SLICE);
        var payload=owner.child("experimental-scalar-capture");
        return new BExperimentalScalarCapture(owner,payload,work,probe);
    }

    /** Private source-owned route;the existing arbitrary-input API remains UNIT. */
    EvidenceReadView captureOwned(BSharedCaptureAdmission inputs,String id,String sourceIdentity){
        live();
        if(started&&!originalOwnedClaimed)throw new IllegalStateException("Capture stage is single use");
        return inputs.captureInto(this,id,sourceIdentity);
    }
    void claimOriginalOwnedAttempt(AttemptMemoryLedger.Owner owner,BSharedWork work,
            CancellationProbe probe){
        requireOwnedInputs(owner,work,probe);
        started=true;originalOwnedClaimed=true;
    }
    boolean originalOwnedClaimed(){return originalOwnedClaimed;}
    void requireClaimedOriginalOwnedInputs(AttemptMemoryLedger.Owner owner,BSharedWork work,
            CancellationProbe probe){
        live();
        if(owner!=root||work!=this.work||probe!=this.probe)
            throw new IllegalArgumentException("Foreign owned capture inputs");
        if(!started||!originalOwnedClaimed)
            throw new IllegalStateException("Original capture attempt was not claimed");
    }
    void requireOwnedInputs(AttemptMemoryLedger.Owner owner,BSharedWork work,CancellationProbe probe){
        live();
        if(owner!=root||work!=this.work||probe!=this.probe)
            throw new IllegalArgumentException("Foreign owned capture inputs");
        if(started)throw new IllegalStateException("Capture stage is single use");
    }
    void claimOwnedInputs(){started=true;}
    void abortOwnedInputs(Throwable primary){
        published=null;
        try{payload.close();}catch(RuntimeException|Error secondary){ /* preserve primary */ }
    }

    EvidenceReadView capture(String id,BufferedImage image,boolean[] valid,double[] scalar,
            List<GeographicPoint> geographic,List<MetricPoint> metric,
            SupportedInputRasterTransform inputTransform,MetricRasterGrid grid,
            EvidenceResolution resolution,double radius,String sourceIdentity,
            String fieldName,EvidenceFieldLineage lineage) {
        live();
        if(started)throw new IllegalStateException("Capture stage is single use");
        started=true;
        return captureAfterClaim(id,image,valid,scalar,geographic,metric,inputTransform,
            grid,resolution,radius,sourceIdentity,fieldName,lineage);
    }

    /** Continues the consumed source-owned attempt, never a fresh UNIT claim. */
    EvidenceReadView captureClaimedOwned(BSharedCaptureAdmission inputs,String id,
            BufferedImage image,boolean[] valid,double[] scalar,
            List<GeographicPoint> geographic,List<MetricPoint> metric,
            SupportedInputRasterTransform inputTransform,MetricRasterGrid grid,
            EvidenceResolution resolution,double radius,String sourceIdentity,
            String fieldName,EvidenceFieldLineage lineage) {
        synchronized(inputs){
            inputs.requireClaimedContinuation(this,root,work,probe,id,image,valid,scalar,
                geographic,metric,inputTransform,grid,resolution,radius,sourceIdentity,fieldName,lineage);
            live();
            if(!started)throw new IllegalStateException("Capture attempt was not claimed");
            return captureAfterClaim(id,image,valid,scalar,geographic,metric,inputTransform,
                grid,resolution,radius,sourceIdentity,fieldName,lineage);
        }
    }

    /** One native body for both wrappers; neither wrapper resets started. */
    private EvidenceReadView captureAfterClaim(String id,BufferedImage image,
            boolean[] valid,double[] scalar,List<GeographicPoint> geographic,
            List<MetricPoint> metric,SupportedInputRasterTransform inputTransform,
            MetricRasterGrid grid,EvidenceResolution resolution,double radius,
            String sourceIdentity,String fieldName,EvidenceFieldLineage lineage) {
        try {
            if(image==null||valid==null||scalar==null||grid==null||metric==null||geographic==null
                    ||resolution==null||lineage==null||grid.width()<2||grid.height()<2
                    ||metric.size()<2||metric.size()>12||geographic.size()!=metric.size()
                    ||resolution.spatialPitchSamples().size()!=1||!lineage.scalarOperations().isEmpty()
                    ||!(inputTransform instanceof SupportedInputRasterTransform.LocalMetricAffine))
                throw new IllegalArgumentException("Fixed scalar capture inputs are incomplete or unsupported");
            //All borrowed inputs must already have a caller-owned identity charge.
            //This is not acquisition/materialization outside the attempt clock.
            payload.retainIfAbsent(image);payload.retainIfAbsent(valid);payload.retainIfAbsent(scalar);
            payload.retainIfAbsent(geographic);payload.retainIfAbsent(metric);payload.retainIfAbsent(inputTransform);
            payload.retainIfAbsent(grid);payload.retainIfAbsent(resolution);payload.retainIfAbsent(lineage);
            long vertices=Math.multiplyExact((long)grid.width(),grid.height());
            long cells=Math.multiplyExact((long)grid.width()-1,grid.height()-1);
            payload.reserve(modeledArraysAndCallbacks(scalar.length,vertices,cells));
            CancellationProbe checked=()->{probe.checkpoint();live();return false;};
            RasterEvidenceCapture shared=new RasterEvidenceCapture(32L<<20);
            long nativeRetainedEstimate=shared.validatePrecomputedScalarInputs(id,image,valid,scalar,
                geographic,inputTransform,grid,resolution,radius,sourceIdentity,lineage.acquisitionKind(),
                fieldName,lineage,checked);
            //Separate, conservative native retained-metadata/field admission estimate.
            //It is NOT the old16MiB working allowance or a full API/runtime heap seal.
            payload.reserve(nativeRetainedEstimate);
            double[] scalarCopy=scalar.clone();
            LocalMetricFrame frame=grid.coordinateFrame();
            MetricRegion footprint=grid.footprint();
            footprint.polygons().forEach(polygon->polygon.forEach(frame::toGeographic));
            inputTransform.boundsForMetricCell(frame,footprint.polygons().get(0));
            List<MetricPoint> source=List.copyOf(metric);
            if(source.size()!=geographic.size()||source.stream().anyMatch(Objects::isNull))
                throw new IllegalArgumentException("Metric source polyline does not match geographic source occurrences");
            MetricRegion decision=MetricCorridorRegion.aroundPolyline(source,radius);
            if(!BExactRectangleContainment.contains(footprint,decision))
                throw new IllegalArgumentException("Output rectangle does not cover the original decision corridor");
            boolean[] validityCopy=valid.clone();
            ScalarEvidenceField field=RasterEvidenceCapture.resamplePrecomputedScalar(scalarCopy,validityCopy,
                image.getWidth(),image.getHeight(),inputTransform,grid,lineage,checked);
            checked.checkpoint();
            Map<String,ScalarEvidenceField> fields=new LinkedHashMap<>();fields.put(fieldName,field);
            EvidenceResolution outputResolution=resolution.resampledTo(grid.pitchMeters());
            RasterResamplingProvenance resampling=RasterResamplingProvenance.exactInverseBilinear(
                inputTransform.kindId(),inputTransform.parameterIdentity(),image.getWidth(),image.getHeight(),
                grid.width(),grid.height());
            Carrier result=new Carrier(this,id,frame,grid.transform(),outputResolution,decision,
                footprint,fields,resampling,sourceIdentity,inputTransform,List.copyOf(geographic),source);
            checked.checkpoint();
            published=result;
            return result;
        } catch(RuntimeException|Error primary) {
            published=null;
            try {payload.close();} catch(RuntimeException|Error secondary) { /* preserve primary */ }
            throw primary;
        }
    }

    void requireProduced(EvidenceReadView view,AttemptMemoryLedger.Owner owner,
            BSharedWork work,CancellationProbe probe) {
        live();
        if(owner!=root||work!=this.work||probe!=this.probe||!(view instanceof Carrier c)
                ||c.origin!=this||published!=c)
            throw new IllegalArgumentException("Foreign or reconstructed capture data");
        c.live();
    }

    CapturedProfiles createProfiles(EvidenceReadView view,String fieldKey,ProfileChainage chainage,
            double halfWidth,boolean fixedEndpoints,EvidenceModelParameters parameters) {
        live();
        if(profilesStarted)throw new IllegalStateException("Captured factory is single use");
        profilesStarted=true;
        AttemptMemoryLedger.Owner issued=null;
        try {
            work.authoritySlots(12);requireProduced(view,root,work,probe);
            if(fieldKey==null||fieldKey.length()>8192)throw new IllegalArgumentException("Missing selected capture field");
            work.authoritySlots(fieldKey.length());
            ScalarEvidenceField field=view.fields().get(fieldKey);
            if(field==null||chainage==null||parameters==null||chainage.cumulativeGroundMeters().size()>81)
                throw new IllegalArgumentException("Incomplete captured factory request");
            //Capsule8refs+double+two booleans=O74=96; checked callback2refs=O16=32;
            //stateless native factory O0=16. Native factory retains its own payload.
            work.metadata(96+32+16);
            issued=payload.child("captured-original-profiles");
            issued.retainIfAbsent(chainage);issued.retainIfAbsent(parameters);
            CancellationProbe checked=new ProfileProbe(this,view);
            var profiles=new ProbabilisticProfileFactory().create(published.metric,chainage,halfWidth,
                fixedEndpoints,view,field,parameters,checked,true,issued);
            checked.checkpoint();
            var result=new CapturedProfiles(this,view,fieldKey,field,profiles,issued,chainage,
                halfWidth,fixedEndpoints,parameters);
            //No callback between the last source guard and publication.
            publishedProfiles=result;
            return result;
        } catch(RuntimeException|Error primary) {
            if(issued!=null)try {issued.close();}catch(RuntimeException|Error secondary){ /* preserve primary */ }
            throw primary;
        }
    }

    private static final class ProfileProbe implements CancellationProbe {
        private final BExperimentalScalarCapture origin;
        private final EvidenceReadView view;
        private ProfileProbe(BExperimentalScalarCapture origin,EvidenceReadView view){this.origin=origin;this.view=view;}
        public boolean cancelled(){origin.work.authoritySlots(2);origin.work.poll();
            origin.requireProduced(view,origin.root,origin.work,origin.probe);return false;}
    }

    static final class CapturedProfiles implements AutoCloseable {
        private final BExperimentalScalarCapture origin;
        private final EvidenceReadView view;
        private final String fieldKey;
        private final ScalarEvidenceField field;
        private final List<ProbabilisticProfile> profiles;
        private final AttemptMemoryLedger.Owner issued;
        private final ProfileChainage chainage;
        private final EvidenceModelParameters parameters;
        private final double halfWidth;
        private final boolean fixedEndpoints;
        private boolean closed;
        private CapturedProfiles(BExperimentalScalarCapture origin,EvidenceReadView view,String fieldKey,
                ScalarEvidenceField field,List<ProbabilisticProfile> profiles,AttemptMemoryLedger.Owner issued,
                ProfileChainage chainage,double halfWidth,boolean fixedEndpoints,EvidenceModelParameters parameters) {
            this.origin=origin;this.view=view;this.fieldKey=fieldKey;this.field=field;
            this.profiles=profiles;this.issued=issued;this.chainage=chainage;this.halfWidth=halfWidth;
            this.fixedEndpoints=fixedEndpoints;this.parameters=parameters;
        }
        void ensureOpen() {
            origin.work.authoritySlots(8);
            origin.requireProduced(view,origin.root,origin.work,origin.probe);
            if(closed||issued.isClosed()||origin.publishedProfiles!=this)
                throw new IllegalStateException("Captured profile capability is closed or unissued");
        }
        void validate(BExperimentalScalarCapture capture,EvidenceReadView evidence,String key,
                AttemptMemoryLedger.Owner owner,BSharedWork work,CancellationProbe probe) {
            ensureOpen();origin.requireProduced(evidence,owner,work,probe);
            if(capture!=origin||evidence!=view||key==null||!fieldKey.equals(key)||view.fields().get(key)!=field)
                throw new IllegalArgumentException("Foreign captured profiles or selected field");
        }
        List<ProbabilisticProfile> profiles(){ensureOpen();return profiles;}
        ProfileChainage chainage(){ensureOpen();return chainage;}
        EvidenceModelParameters parameters(){ensureOpen();return parameters;}
        double halfWidth(){ensureOpen();return halfWidth;}
        boolean fixedEndpoints(){ensureOpen();return fixedEndpoints;}
        @Override public void close(){closed=true;issued.close();}
    }

    CapturedLattices createLattices(CapturedProfiles profiles,BIdentificationFactsExtractor.CapturedFacts facts) {
        live();
        if(latticesStarted)throw new IllegalStateException("Captured state builder is single use");
        latticesStarted=true;
        AttemptMemoryLedger.Owner issued=null;
        try {
            work.authoritySlots(12);
            Objects.requireNonNull(profiles).validate(this,published,profiles.fieldKey,root,work,probe);
            Objects.requireNonNull(facts).validate(this,published,profiles.fieldKey,profiles,root,work,probe);
            int count=profiles.profiles().size();
            if(count<1||count>81)throw new IllegalArgumentException("Captured graph inventory");
            //Capsule O41=64, caller ArrayList32, immutable-list shell32, builder16;
            //caller array +List.copyOf temporary/output arrays. Native result per row:
            //record48, Optional24, anchor List12/Double32+24, bounded failure String32+A128UTF16.
            work.metadata(64+32+32+16+3*AttemptMemoryLedger.arrayBytes(count,8)
                +(48+24+32+24+32+AttemptMemoryLedger.arrayBytes(128,2))*count);
            issued=payload.child("captured-original-lattices");
            var builder=new ProbabilisticStateBuilder();
            var lattices=new java.util.ArrayList<ProbabilisticStateLattice>(count);
            for(int row=0;row<count;row++) {
                work.authoritySlots(4);work.poll();profiles.ensureOpen();facts.ensureOpen();
                var result=builder.build(profiles.profiles().get(row),96,issued);
                work.poll();profiles.ensureOpen();facts.ensureOpen();
                if(result.status()!=StateSpaceBuildResult.Status.COMPLETE)
                    throw new IllegalArgumentException("Original native graph was not complete");
                lattices.add(result.lattice().orElseThrow());
            }
            var frozen=List.copyOf(lattices);
            work.poll();profiles.ensureOpen();facts.ensureOpen();
            var result=new CapturedLattices(this,profiles,facts,frozen,issued);
            publishedLattices=result;
            return result;
        } catch(RuntimeException|Error primary) {
            if(issued!=null)try {issued.close();}catch(RuntimeException|Error secondary){ /* preserve primary */ }
            throw primary;
        }
    }
    static final class CapturedLattices implements AutoCloseable {
        private final BExperimentalScalarCapture origin;
        private final CapturedProfiles profiles;
        private final BIdentificationFactsExtractor.CapturedFacts facts;
        private final List<ProbabilisticStateLattice> lattices;
        private final AttemptMemoryLedger.Owner issued;
        private boolean closed;
        private CapturedLattices(BExperimentalScalarCapture origin,CapturedProfiles profiles,
                BIdentificationFactsExtractor.CapturedFacts facts,List<ProbabilisticStateLattice> lattices,
                AttemptMemoryLedger.Owner issued) {
            this.origin=origin;this.profiles=profiles;this.facts=facts;this.lattices=lattices;this.issued=issued;
        }
        void ensureOpen() {
            origin.work.authoritySlots(8);profiles.ensureOpen();facts.ensureOpen();
            if(closed||issued.isClosed()||origin.publishedLattices!=this)
                throw new IllegalStateException("Captured original graph is closed or unissued");
        }
        void validate(BExperimentalScalarCapture capture,EvidenceReadView view,String key,
                CapturedProfiles profiles,BIdentificationFactsExtractor.CapturedFacts facts,
                AttemptMemoryLedger.Owner owner,BSharedWork work,CancellationProbe probe) {
            ensureOpen();this.facts.validate(capture,view,key,profiles,owner,work,probe);
            if(capture!=origin||this.profiles!=profiles||this.facts!=facts)
                throw new IllegalArgumentException("Foreign original graph provenance");
        }
        void validateBound(EvidenceReadView view,String key,List<BIdentificationFacts.ProfileFacts> supplied,
                AttemptMemoryLedger.Owner owner,BSharedWork work,CancellationProbe probe) {
            ensureOpen();facts.validate(origin,view,key,profiles,owner,work,probe);
            if(supplied!=facts.facts())throw new IllegalArgumentException("Replayed captured facts");
        }
        List<ProbabilisticStateLattice> lattices(){ensureOpen();return lattices;}
        @Override public void close(){closed=true;issued.close();}
    }

    /** No old proof/binder/runtime capability may be manufactured by this R2 stage. */
    static final class AcquisitionPath {
        private final BExperimentalScalarCapture origin;
        private final Carrier carrier;
        private final List<MetricPoint> metric;
        private final List<GeographicPoint> geographic;
        private final SupportedInputRasterTransform acquisitionTransform;
        private final long pointCount;
        private int state;
        private AcquisitionPath(BExperimentalScalarCapture origin,Carrier carrier){
            this.origin=origin;this.carrier=carrier;metric=carrier.metric;geographic=carrier.geographic;
            acquisitionTransform=carrier.acquisitionTransform;pointCount=metric.size();
        }
        private void live(){origin.live();
            if(state==0||origin.publishedPath!=this||origin.published!=carrier)
                throw new IllegalStateException("No live original acquisition path");
        }
        /** Factual read guard; it does not consume the later table-owned path. */
        long pointCountIssued(EvidenceReadView view,BPhysicalWork physical){
            origin.work.authoritySlots(PATH_CONSUMER_SLOTS);live();
            if(state!=1||view!=carrier||physical.owner()!=origin.root
                    ||physical.sourceWork()!=origin.work||physical.probe()!=origin.probe
                    ||metric!=carrier.metric||geographic!=carrier.geographic
                    ||acquisitionTransform!=carrier.acquisitionTransform||pointCount!=metric.size())
                throw new IllegalArgumentException("Not this issued original acquisition path");
            return pointCount;
        }
        //Only CapturedInput's prepaid,actual private-data consumer calls this.
        void consume(EvidenceReadView view,AttemptMemoryLedger.Owner owner,BSharedWork work,
                CancellationProbe probe,long prospectiveUpper){
            live();
            if(state!=1||view!=carrier||owner!=origin.root||work!=origin.work||probe!=origin.probe
                    ||metric!=carrier.metric||geographic!=carrier.geographic||acquisitionTransform!=carrier.acquisitionTransform
                    ||pointCount!=metric.size()||pointCount>prospectiveUpper)
                throw new IllegalArgumentException("Original acquisition path/source/attempt join");
            state=2;
        }
        void requireConsumed(EvidenceReadView view,BPhysicalWork physical){
            origin.work.authoritySlots(8);live();
            if(state!=2||view!=carrier||physical.owner()!=origin.root
                    ||physical.sourceWork()!=origin.work||physical.probe()!=origin.probe
                    ||metric!=carrier.metric||geographic!=carrier.geographic
                    ||acquisitionTransform!=carrier.acquisitionTransform||pointCount!=metric.size())
                throw new IllegalArgumentException("Not this consumed original acquisition path");
        }
        long pointCount(){origin.work.authoritySlots(1);live();return pointCount;}
        List<MetricPoint> metric(){origin.work.authoritySlots(1);live();return metric;}
        List<GeographicPoint> geographic(){origin.work.authoritySlots(1);live();return geographic;}
        SupportedInputRasterTransform acquisitionTransform(){origin.work.authoritySlots(1);live();return acquisitionTransform;}
        RasterMetricTransform outputTransform(){origin.work.authoritySlots(1);live();return carrier.transform;}
        String sourceIdentity(){origin.work.authoritySlots(1);live();return carrier.sourceIdentity;}
    }
    AcquisitionPath acquisitionPath(EvidenceReadView view) {
        work.authoritySlots(PATH_ISSUE_SLOTS);live();
        if(pathStarted)throw new IllegalStateException("Original acquisition path issued once");pathStarted=true;
        requireProduced(view,root,work,probe);
        work.metadata(72); //exact O52 before allocation;shared existing1MiB
        var result=new AcquisitionPath(this,(Carrier)view);
        work.poll();requireProduced(view,root,work,probe);
        result.state=1;publishedPath=result;return result; //callback-free publication
    }

    /** No old proof/binder/runtime capability may be manufactured by this R2 stage. */
    void requireTableSourceAuthority() {
        live();throw new UnsupportedOperationException("R3 capture Work/source/runtime join is UNSEALED");
    }

    private void live() {
        if(closed||root.isClosed()||payload.isClosed())throw new IllegalStateException("Capture owner is closed");
        work.requireOwner(root);work.requireProbe(probe);
    }

    /** Known plane allocation-sum plus reviewed fixed local-affine callback graph. */
    static long modeledArraysAndCallbacks(long input,long vertices,long cells) {
        if(input<0||vertices<0||cells<0)throw new IllegalArgumentException("Negative capture dimensions");
        long arrays=Math.addExact(AttemptMemoryLedger.arrayBytes(input,8),AttemptMemoryLedger.arrayBytes(input,1));
        arrays=Math.addExact(arrays,Math.multiplyExact(4,AttemptMemoryLedger.arrayBytes(vertices,8)));
        arrays=Math.addExact(arrays,Math.multiplyExact(3,AttemptMemoryLedger.arrayBytes(vertices,1)));
        arrays=Math.addExact(arrays,Math.multiplyExact(3,AttemptMemoryLedger.arrayBytes(cells,1)));
        //Bounds1+C: old stream1888 +map24 +Raster128 +Bounds48; cellList80+Metric128;
        //V four32B points; footprint discarded Geo/Consumers/iterators256 +handlelease64.
        long callbacks=Math.addExact(Math.multiplyExact(2088,Math.addExact(cells,1)),
            Math.addExact(Math.multiplyExact(208,cells),Math.addExact(Math.multiplyExact(128,vertices),320)));
        return Math.addExact(arrays,callbacks);
    }

    @Override public void close() {published=null;closed=true;payload.close();}

    /** The constructor and issuer reference are private; data/hash equality is irrelevant. */
    private static final class Carrier implements EvidenceReadView {
        private final BExperimentalScalarCapture origin;
        private final String snapshotId,sourceIdentity;
        private final LocalMetricFrame frame;
        private final RasterMetricTransform transform;
        private final EvidenceResolution resolution;
        private final MetricRegion decision,evidence;
        private final Map<String,ScalarEvidenceField> fields;
        private final RasterResamplingProvenance resampling;
        private final SupportedInputRasterTransform acquisitionTransform;
        private final List<GeographicPoint> geographic;
        private final List<MetricPoint> metric;

        private Carrier(BExperimentalScalarCapture origin,String id,LocalMetricFrame frame,
                RasterMetricTransform transform,EvidenceResolution resolution,MetricRegion decision,
                MetricRegion evidence,Map<String,ScalarEvidenceField> fields,RasterResamplingProvenance resampling,
                String source,SupportedInputRasterTransform acquisitionTransform,
                List<GeographicPoint> geographic,List<MetricPoint> metric) {
            if(id==null||id.isBlank()||source==null||source.isBlank())
                throw new IllegalArgumentException("Evidence snapshot identity is required");
            this.origin=Objects.requireNonNull(origin);this.frame=Objects.requireNonNull(frame);
            this.transform=Objects.requireNonNull(transform);this.resolution=Objects.requireNonNull(resolution);
            this.decision=Objects.requireNonNull(decision);this.evidence=Objects.requireNonNull(evidence);
            this.resampling=Objects.requireNonNull(resampling);
            if(fields==null||fields.isEmpty())throw new IllegalArgumentException("Evidence requires a scalar field");
            this.fields=Map.copyOf(new LinkedHashMap<>(fields));
            int width=this.fields.values().iterator().next().width(),height=this.fields.values().iterator().next().height();
            if(this.fields.entrySet().stream().anyMatch(e->e.getKey()==null||e.getKey().isBlank()
                    ||e.getValue()==null||e.getValue().width()!=width||e.getValue().height()!=height))
                throw new IllegalArgumentException("Evidence fields must share one named raster frame");
            if(resampling.outputWidth()!=width||resampling.outputHeight()!=height)
                throw new IllegalArgumentException("Resampling provenance does not match the evidence raster");
            MetricRegion raster=new MetricRegion(List.of(List.of(transform.pixelCenterToMetric(-0.5,-0.5),
                transform.pixelCenterToMetric(width-0.5,-0.5),transform.pixelCenterToMetric(width-0.5,height-0.5),
                transform.pixelCenterToMetric(-0.5,height-0.5))));
            if(!BExactRectangleContainment.contains(evidence,decision)||!BExactRectangleContainment.contains(raster,evidence))
                throw new IllegalArgumentException("Decision, halo, and raster regions are inconsistent");
            DetachedValueVerifier.verify(List.of(frame,transform,resolution,decision,evidence,this.fields,resampling));
            this.snapshotId=id;this.sourceIdentity=source;
            this.acquisitionTransform=Objects.requireNonNull(acquisitionTransform);
            this.geographic=List.copyOf(geographic);this.metric=List.copyOf(metric);
        }

        private void live() {origin.live();if(origin.published!=this)throw new IllegalStateException("Capture data is not published");}
        public String snapshotId(){live();return snapshotId;}
        public LocalMetricFrame coordinateFrame(){live();return frame;}
        public RasterMetricTransform transform(){live();return transform;}
        public EvidenceResolution resolution(){live();return resolution;}
        public MetricRegion decisionRegion(){live();return decision;}
        public MetricRegion evidenceRegion(){live();return evidence;}
        public Map<String,ScalarEvidenceField> fields(){live();return fields;}
        public RasterResamplingProvenance resampling(){live();return resampling;}
        public String sourceIdentity(){live();return sourceIdentity;}
        public boolean routePositionAuthorized(MetricPoint p){live();return decision.contains(p);}
        public boolean routeSegmentAuthorized(MetricPoint a,MetricPoint b){live();return decision.containsSegment(a,b);}
    }
}
