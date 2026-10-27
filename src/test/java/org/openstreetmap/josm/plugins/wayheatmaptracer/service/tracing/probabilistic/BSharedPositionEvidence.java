package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;
import java.util.Objects;
import java.security.MessageDigest;
import java.io.InputStream;
import java.io.IOException;
import java.util.HexFormat;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationFacts.*;

/** Factual source capability; unsupported inputs never modify the admitted lattice. */
final class BSharedPositionEvidence {
    static final String PROOF_SHA256 = "f415bbd2268c016992093d8387f06623ab3c344b6e6c763af3fb28f9bc76aeda";
    record Contract(String proofSha256) {
        Contract { if (!PROOF_SHA256.equals(proofSha256)) throw new IllegalArgumentException("Unreviewed shared proof"); }
    }
    static Contract approvedContract() { return new Contract(PROOF_SHA256); }
    static final String CAPTURED_VALIDATION_ID="experimental-captured-source-data-r3b-v1";
    interface SourceAuthority extends AutoCloseable {
        void ensureOpen();
        @Override void close();
    }
    static final class CapturedInput {
        private final Input data;
        private CapturedInput(Input data){this.data=data;}
        String factsHashData(BPhysicalWork physical,MessageDigest digest)throws java.io.UTFDataFormatException{
            return BSharedFactsDigest.hashCapturedData(data.facts,data.lattices,physical,digest,this);
        }
        void requireFactsDigestData(List<ProfileFacts> facts,List<ProbabilisticStateLattice> lattices,BPhysicalWork physical){
            if(physical==null||physical.sourceWork()!=data.work||facts!=data.facts||lattices!=data.lattices)
                throw new IllegalArgumentException("Foreign captured facts digest input");
            data.work.requireOwner(physical.owner());data.work.requireProbe(physical.probe());
            data.validateAuthority(physical.owner(),physical.probe(),data.work);
            requireFactsDigestLiveData(data.work);
        }
        void requireFactsDigestLiveData(BSharedWork work){
            if(work!=data.work)throw new IllegalArgumentException("Foreign captured facts digest work");
            data.captured.ensureOpen();
        }
        static CapturedInput create(BExperimentalScalarCapture capture,EvidenceReadView view,String key,
                BExperimentalScalarCapture.CapturedProfiles profiles,BIdentificationFactsExtractor.CapturedFacts facts,
                BExperimentalScalarCapture.CapturedLattices lattices,AttemptMemoryLedger.Owner owner,
                CancellationProbe probe,BSharedWork work) {
            Objects.requireNonNull(work);work.requireOwner(owner);work.requireProbe(probe);work.authoritySlots(12);
            Objects.requireNonNull(lattices).validate(capture,view,key,profiles,facts,owner,work,probe);
            var original=facts.facts();var graph=lattices.lattices();
            if(original.isEmpty()||original.size()>81||original.size()!=graph.size())
                throw new IllegalArgumentException("Captured original inventory");
            //Native Input's bound plus its new8B origin slot and this O8=24 wrapper.
            work.metadata(264+AttemptMemoryLedger.arrayBytes(original.size(),8)+24+24);
            return new CapturedInput(new Input(view,key,facts,original,graph,view.decisionRegion(),null,work,lattices));
        }
        void consumeAcquisitionPath(BExperimentalScalarCapture.AcquisitionPath path,BPhysicalWork physical,long prospectiveUpper){
            data.work.authoritySlots(BExperimentalScalarCapture.PATH_CONSUMER_SLOTS);
            if(path==null||physical==null)throw new IllegalArgumentException("Actual original acquisition path required");
            data.captured.ensureOpen();
            path.consume(data.evidence,physical.owner(),physical.sourceWork(),physical.probe(),prospectiveUpper);
        }
    }
    static Bound bindCaptured(CapturedInput input,AttemptMemoryLedger.Owner owner,CancellationProbe probe,BSharedWork work) {
        Objects.requireNonNull(input);return bindData(input.data,owner,probe,work);
    }
    /** Borrowed immutable originals; only the two bounded reference copies use metadata slices. */
    static final class Input {
        final EvidenceReadView evidence;
        final String fieldKey;
        final List<ProfileFacts> facts;
        final List<ProbabilisticStateLattice> lattices;
        final MetricRegion region;
        final Contract contract;
        final BSharedWork work;
        final SourceAuthority authority;
        final BExperimentalScalarCapture.CapturedLattices captured;
        private Input(EvidenceReadView e, String key,SourceAuthority authority,List<ProfileFacts> facts,
                List<ProbabilisticStateLattice> lattices, MetricRegion region, Contract c, BSharedWork work,
                BExperimentalScalarCapture.CapturedLattices captured) {
            this.evidence=e; this.fieldKey=key; this.facts=facts;this.authority=authority;
            this.lattices=List.copyOf(lattices); this.region=region; this.contract=c; this.work=work;
            this.captured=captured;
        }
        static Input create(EvidenceSnapshot e, String key,BIdentificationFactsExtractor.SharedPositionFacts authority,List<BIdentificationFacts.ProfileFacts> facts,
                List<ProbabilisticStateLattice> lattices, MetricRegion region, Contract c, BSharedWork work) {
            Objects.requireNonNull(e); Objects.requireNonNull(facts); Objects.requireNonNull(lattices);
            Objects.requireNonNull(c); Objects.requireNonNull(work);
            Objects.requireNonNull(authority).validate(e,key,facts,work);
            if (key==null || key.isBlank() || !e.fields().containsKey(key) || facts.isEmpty()
                    || facts.size()>81 || facts.size()!=lattices.size())
                throw new IllegalArgumentException("Shared original inventory");
            work.metadata(264+AttemptMemoryLedger.arrayBytes(facts.size(),8)+24);
            return new Input(e,key,authority,facts,lattices,region,c,work,null);
        }
        private void validateAuthority(AttemptMemoryLedger.Owner owner,CancellationProbe probe,BSharedWork work) {
            work.authoritySlots(2);
            if(captured==null)((BIdentificationFactsExtractor.SharedPositionFacts)authority)
                .validate((EvidenceSnapshot)evidence,fieldKey,facts,work);
            else {
                captured.validateBound(evidence,fieldKey,facts,owner,work,probe);
                if(captured.lattices()!=lattices||region!=evidence.decisionRegion()||contract!=null)
                    throw new IllegalArgumentException("Captured original graph or region changed");
            }
        }
    }
    static Bound bind(Input input, AttemptMemoryLedger.Owner owner, CancellationProbe probe, BSharedWork work) {
        Objects.requireNonNull(input);work.authoritySlots(1);
        if(input.captured!=null)throw new IllegalArgumentException("Captured data requires its distinct binder entry");
        return bindData(input,owner,probe,work);
    }
    static Bound bindExploratory(BSharedFeasibilityInputs.OwnedSource source,BPhysicalWork work){
        source.require(work);
        return bindData(source.input(),work.owner(),work.probe(),work.sourceWork());
    }
    private static Bound bindData(Input input, AttemptMemoryLedger.Owner owner, CancellationProbe probe, BSharedWork work) {
        Objects.requireNonNull(input); Objects.requireNonNull(probe);
        work.requireOwner(owner);work.requireProbe(probe);
        if (input.work!=work) throw new IllegalArgumentException("Foreign funded input");
        work.claimBound();input.validateAuthority(owner,probe,work);
        work.poll(); probe.checkpoint();
        if(input.captured!=null)input.captured.ensureOpen();
        var child=owner.child("shared-source-descriptors");
        try {
            // Fixed wrapper/grid scratch is funded before either object is allocated.
            work.metadata(16384);
            Grid grid=Grid.from(input,work);
            int n=input.facts.size(), modes=0, states=0;
            long t=work.authorityMetadataSlots(), local=0, k=0, pairs=0, samples=0;
            for(int row=0;row<n;row++) {
                work.poll(); work.preflight(256);
                var fact=input.facts.get(row); var raw=fact.baseline();
                var lattice=input.lattices.get(row);
                t=plus(t,joinComponents(raw,lattice,work));
                for(int previous=0;previous<row;previous++) {
                    work.poll();work.preflight(64);t=plus(t,2);
                    if(input.facts.get(previous)==fact||input.facts.get(previous).baseline()==raw)
                        throw new IllegalArgumentException("Aliased original profile identity");
                }
                if(raw.samples().size()>401 || lattice.cells().size()>96
                        || raw.modes().size()!=fact.modes().size())
                    throw new IllegalArgumentException("Shared lower inventory guard");
                var lease=child.retain(fact);
                long bytes=1024L+96L*raw.samples().size()+8192L*fact.modes().size();
                if(lease.bytes()<bytes) throw new IllegalArgumentException("Unfunded original facts");
                var latticeLease=child.retain(lattice);
                long completeLattice=128+array(lattice.cells().size(),8)+array(lattice.components().size(),8)
                        +64L*lattice.cells().size()+96L*lattice.components().size();
                // A tiny explicitly aggregate-owned analytical fixture is also legal. Actual
                // builder lattices have separate graph leases and use the retained path below.
                if(latticeLease.bytes()<completeLattice) {
                    child.retainIfAbsent(lattice.cells());child.retainIfAbsent(lattice.components());
                    for(int c=0;c<lattice.cells().size();c++){work.poll();work.preflight(32);child.retainIfAbsent(lattice.cells().get(c));}
                    for(int c=0;c<lattice.components().size();c++){work.poll();work.preflight(32);child.retainIfAbsent(lattice.components().get(c));}
                }
                modes=Math.addExact(modes,fact.modes().size());
                if(modes>8192) throw new IllegalArgumentException("Shared mode bound");
                states=Math.addExact(states,lattice.cells().size());
                local=Math.addExact(local,Math.multiplyExact((long)fact.modes().size(),lattice.cells().size()));
                k=Math.addExact(k,Math.multiplyExact((long)lattice.components().size(),lattice.cells().size()));
                if(row>0) {
                    if(raw.chainageMeters()<=input.facts.get(row-1).baseline().chainageMeters())
                        throw new IllegalArgumentException("Original source chainage order");
                    pairs=Math.addExact(pairs,Math.multiplyExact((long)lattice.cells().size(),
                            input.lattices.get(row-1).cells().size()));
                }
                for(int m=0;m<fact.modes().size();m++) {
                    var mode=fact.modes().get(m);var original=raw.modes().get(m);
                    if(mode.values().size()>20||mode.provenance().size()>6||original.id().length()>8192
                            ||original.evidenceLineage().length()>8192)
                        throw new IllegalArgumentException("Shared bounded original receipt inventory");
                    bytes=Math.addExact(bytes,4L*(original.id().length()+original.evidenceLineage().length()));
                    if(lease.bytes()<bytes)throw new IllegalArgumentException("Unfunded fact strings");
                    t=plus(t,plus(mode.values().size(),mode.provenance().size()));
                    t=plus(t,plus(original.id().length(),original.evidenceLineage().length()));
                    for(int r=0;r<mode.provenance().size();r++) {
                        work.poll();work.preflight(32);
                        if(mode.provenance().get(r) instanceof CorrelationReceipt c)
                            t=plus(t,plus(c.fieldLineage().length(),c.transformId().length()));
                    }
                }
                t=plus(t,raw.samples().size());
                samples=plus(samples,raw.samples().size());
            }
            if(pairs>8_000_000 || k>8_000_000)throw new IllegalArgumentException("Original pair/mixture guard");
            var source=input.evidence.fields().get(input.fieldKey);
            long n0=Math.multiplyExact((long)source.width(),source.height());
            long c0=Math.multiplyExact((long)source.width()-1,source.height()-1);
            long vertices=Math.multiplyExact((long)grid.width,grid.height);
            if(n0>191401 || c0>190404 || vertices>191401)
                throw new IllegalArgumentException("Shared raster lower guard");
            long z=Math.multiplyExact(pairs,plus(grid.width,plus(grid.height,4)));
            long edges=0;
            if(input.region!=null) for(var polygon:input.region.polygons())edges=plus(edges,polygon.size());
            // Borrow the immutable original snapshot region through Input/Bound; no geometry
            // copies or membership index are allocated. This is distinct from chord admission.
            var sourcePolygons=input.evidence.evidenceRegion().polygons();
            long sourceEdges=0;
            if(sourcePolygons.isEmpty())throw new IllegalArgumentException("Missing original source region");
            for(int polygon=0;polygon<sourcePolygons.size();polygon++) {
                work.poll();work.preflight(32);
                int count=sourcePolygons.get(polygon).size();
                if(count<3)throw new IllegalArgumentException("Invalid original source region");
                sourceEdges=plus(sourceEdges,count);t=plus(t,plus(1,count));
            }
            t=plus(t,plus(input.evidence.sourceIdentity().length(),input.evidence.transform().transformId().length()));
            t=plus(t,input.evidence.resampling().sourceTransformIdentity().length());
            t=plus(t,input.evidence.resolution().spatialPitchSamples().size());
            // The bounded local runtime fingerprint is metadata, not source observations.
            t=plus(t,128000);
            long descriptors=512+22*array(modes,8)+12*array(modes,4)+array(n+1,4);
            long sourceBytes=384+array(n0,8)+array(n0,1)+array(c0,1);
            long coordinates=128+2*array(states,8)+array(n+1,4);
            // Audited512 slots cover public exact temporaries AND serial JDK scratch.
            long exact=512L*(AttemptMemoryLedger.objectBytes(64)+array(1024,4))+array(128,8)+4096;
            // Index/descriptor storage is explicitly funded BEFORE counting clipped Q.
            work.reserve(child,plus(descriptors,coordinates));
            var bound=new Bound(input,child,work,grid,modes,states,local);
            bound.describeMetadata();
            long r=plus(bound.q,Math.multiplyExact(4,local));
            long upper=plus(upper(t,n0,c0,states,n,modes,vertices,k,r,pairs,z,edges,
                    samples,sourceEdges,sourcePolygons.size()),Math.multiplyExact(5164,local));
            if(input.captured!=null) {
                //27 actual authority slots per source-graph liveness join:
                //graph8 +profiles8 +facts3 +its profiles8. Original source validation
                //has exactly one sampleSource join per original sample; eight fixed
                //joins cover status/final publication and bounded data inspection.
                work.authoritySlots(4);
                upper=plus(upper,plus(4*256,Math.multiplyExact(27*256,plus(samples,8))));
            }
            work.reserveUpper(upper);
            if(bound.status()!=Status.AVAILABLE){work.poll();if(input.captured!=null)input.captured.ensureOpen();return bound;}
            work.reserve(child,plus(sourceBytes,exact));
            requireLocalRuntime(work);
            bound.copySource(source);
            bound.validateSourceAndStates();
            work.poll();
            if(input.captured!=null)input.captured.ensureOpen();
            return bound;
        } catch(RuntimeException|Error failure) {child.close();throw failure;}
    }
    static final class Bound implements AutoCloseable {
        final Input input;
        final AttemptMemoryLedger.Owner owner;
        final BSharedWork work;
        final Grid grid;
        final double[][] d;
        final int[][] indices;
        final Object[] originals, receipts;
        final int[] rows, stateRows;
        final double[] stateX,stateY;
        final long local;
        long q;
        double[] source;
        boolean[] valid, interpolation;
        boolean fieldClaimed;
        Status capability;
        Bound(Input input,AttemptMemoryLedger.Owner owner,BSharedWork work,Grid grid,
                int modes,int states,long local) {
            this.input=input;this.owner=owner;this.work=work;this.grid=grid;this.local=local;
            d=new double[20][modes];indices=new int[12][modes];
            originals=new Object[modes];receipts=new Object[modes];
            rows=new int[input.facts.size()+1];stateRows=new int[rows.length];
            stateX=new double[states];stateY=new double[states];
            capability=grid.known?Status.AVAILABLE:Status.UNAVAILABLE_SOURCE_GRID;
        }
        void ensureOpen() {work.ensureOpen();
            if(input.captured==null)input.authority.ensureOpen();else input.captured.ensureOpen();
            if(owner.isClosed())throw new IllegalStateException("Shared source closed");}
        Status status() {ensureOpen();return capability;}
        String validationIdentity(){ensureOpen();return input.captured==null?PROOF_SHA256:CAPTURED_VALIDATION_ID;}
        void requireRuntimeAuthority(){ensureOpen();
            if(work.isExploratory())throw new UnsupportedOperationException("Exploratory quality evidence is not qualified runtime authority");
            if(input.captured!=null)throw new UnsupportedOperationException("R3 full source/Work/runtime/table admission remains UNSEALED");}
        void copySource(ScalarEvidenceField field) {
            int vertices=Math.multiplyExact(field.width(),field.height()),cells=Math.multiplyExact(field.width()-1,field.height()-1);
            cloneAdmission(vertices);source=field.copiedValues();work.sourceRead(source.length);work.write(source.length);work.poll();
            cloneAdmission(vertices);valid=field.copiedValidity();work.maskRead(valid.length);work.write(valid.length);work.poll();
            cloneAdmission(cells);interpolation=field.copiedInterpolationValidity();work.maskRead(interpolation.length);work.write(interpolation.length);work.poll();
        }
        private void cloneAdmission(int count) {
            // Conservative work is registered BEFORE each existing opaque clone call.
            // Completed-read counters are updated only after that call returns normally.
            for(int i=0;i<count;i+=32){work.poll();work.numeric(16L*Math.min(32,count-i));}
            work.poll();
        }
        void describeMetadata() {
            int cursor=0;
            for(int row=0;row<input.facts.size();row++) {
                work.poll();work.binding(128);
                var p=input.facts.get(row).baseline();rows[row]=cursor;
                if(profileCapability(p,work)!=Status.AVAILABLE)capability=Status.UNAVAILABLE_PROFILE_RECIPE;
                double quadrature=input.facts.size()==1?1:row==0
                        ?.5*(input.facts.get(1).baseline().chainageMeters()-p.chainageMeters())
                        :row==input.facts.size()-1?.5*(p.chainageMeters()-input.facts.get(row-1).baseline().chainageMeters())
                        :.5*(input.facts.get(row+1).baseline().chainageMeters()-input.facts.get(row-1).baseline().chainageMeters());
                for(int m=0;m<input.facts.get(row).modes().size();m++,cursor++) {
                    describe(cursor,row,m,quadrature);
                }
            }
            rows[rows.length-1]=cursor;
            // Aliases may invalidate an earlier descriptor; construct footprints only after
            // the complete original ownership join, never leave that earlier footprint narrow.
            for(int m=0;m<cursor;m++) {
                footprint(m);
                long count=Math.multiplyExact((long)indices[4][m]-indices[3][m]+1,
                        (long)indices[6][m]-indices[5][m]+1);
                q=Math.addExact(q,count);
            }
        }
        void validateSourceAndStates() {
            int cursor=0, s=0;
            for(int row=0;row<input.facts.size();row++) {
                work.poll();work.numeric(128);
                var facts=input.facts.get(row);var p=facts.baseline();
                rows[row]=cursor;stateRows[row]=s;
                if(row>0 && facts==input.facts.get(row-1))throw new IllegalArgumentException("Aliased profile");
                retainProfile(p);
                if(p.nativePitchKnown()!=input.evidence.resolution().nativePitchMeters().isPresent()
                        || !bits(p.sourcePitchMeters(),effectivePitch(input.evidence.resolution(),p.chainageMeters(),work)))
                    throw new IllegalArgumentException("Source resolution drift");
                double norm=p.normalUnit().xMeters()*p.normalUnit().xMeters()
                        +p.normalUnit().yMeters()*p.normalUnit().yMeters();
                if(!Double.isFinite(norm)||Math.abs(norm-1)>1e-12)
                    throw new IllegalArgumentException("Original nonunit normal");
                for(int j=0;j<p.samples().size();j++) {
                    work.poll();work.numeric(128);
                    var sample=p.samples().get(j);
                    double x=p.anchor().xMeters()+p.normalUnit().xMeters()*sample.offsetMeters();
                    double y=p.anchor().yMeters()+p.normalUnit().yMeters()*sample.offsetMeters();
                    double actual=sampleSource(x,y);
                    if(sample.valid()!=Double.isFinite(actual)
                            ||sample.valid()&&!bits(actual,sample.intensity()))
                        throw new IllegalArgumentException("Original samples/source drift");
                }
                for(var cell:input.lattices.get(row).cells()) {
                    work.poll();work.numeric(64);
                    if(cell.offsetMeters()<p.minimumOffsetMeters()||cell.offsetMeters()>p.maximumOffsetMeters())
                        throw new IllegalArgumentException("State outside original source search");
                    stateX[s]=cell.offsetMeters()==0?p.anchor().xMeters():p.anchor().xMeters()+p.normalUnit().xMeters()*cell.offsetMeters();
                    stateY[s]=cell.offsetMeters()==0?p.anchor().yMeters():p.anchor().yMeters()+p.normalUnit().yMeters()*cell.offsetMeters();
                    if(!Double.isFinite(stateX[s])||!Double.isFinite(stateY[s]))throw new ArithmeticException("State coordinates");
                    s++;work.write(2);
                }
                cursor+=facts.modes().size();
            }
            rows[rows.length-1]=cursor;stateRows[stateRows.length-1]=s;
        }
        private void retainProfile(ProbabilisticProfile p) {
            owner.retainIfAbsent(p);owner.retainIfAbsent(p.samples());
            owner.retainIfAbsent(p.anchor());owner.retainIfAbsent(p.normalUnit());
            for(int i=0;i<p.samples().size();i++){work.poll();work.numeric(32);owner.retainIfAbsent(p.samples().get(i));}
            if(!p.modes().isEmpty())owner.retainIfAbsent(p.modes());
            for(int i=0;i<p.modes().size();i++) {
                work.poll();work.numeric(64);var mode=p.modes().get(i);
                owner.retainIfAbsent(mode);
                if(!mode.peakOffsetsMeters().isEmpty())owner.retainIfAbsent(mode.peakOffsetsMeters());
                if(!mode.nestedCenterOffsetsMeters().isEmpty())owner.retainIfAbsent(mode.nestedCenterOffsetsMeters());
                retainOrientation(mode.orientationSupport());
            }
            if(!p.censoredModes().isEmpty()) {
                owner.retainIfAbsent(p.censoredModes());
                for(int i=0;i<p.censoredModes().size();i++){work.poll();work.numeric(32);owner.retainIfAbsent(p.censoredModes().get(i));}
            }
            if(p.exactAnchorOffsetMeters().isPresent())owner.retainIfAbsent(p.exactAnchorOffsetMeters());
            retainOrientation(p.orientationSupport());
        }
        private void retainOrientation(ImageOrientationSupport support) {
            owner.retainIfAbsent(support);
            if(!support.modes().isEmpty()) {
                owner.retainIfAbsent(support.modes());
                for(int i=0;i<support.modes().size();i++){work.poll();work.numeric(32);owner.retainIfAbsent(support.modes().get(i));}
            }
        }
        void describe(int at,int row,int m,double quadrature) {
            work.poll();work.binding(256);
            var p=input.facts.get(row).baseline();var f=input.facts.get(row).modes().get(m);
            var original=p.modes().get(m);
            if(!same(f.lineage(),original.evidenceLineage(),work))throw new IllegalArgumentException("Original lineage drift");
            originals[at]=original;receipts[at]=f;
            indices[10][at]=-1;indices[11][at]=-1;
            indices[0][at]=row;indices[1][at]=m;
            indices[3][at]=0;indices[4][at]=grid.width-1;indices[5][at]=0;indices[6][at]=grid.height-1;
            d[0][at]=p.anchor().xMeters();d[1][at]=p.anchor().yMeters();
            d[2][at]=p.normalUnit().xMeters();d[3][at]=p.normalUnit().yMeters();
            d[8][at]=Math.max(grid.rho,quadrature);d[11][at]=quadrature;
            d[17][at]=Math.max(grid.rhoLo,quadrature);d[18][at]=Math.max(grid.rhoHi,quadrature);
            d[9][at]=p.minimumOffsetMeters();d[10][at]=p.maximumOffsetMeters();
            Status reason=original.groupedParent()?Status.INELIGIBLE_GROUPED_PARENT:Status.UNAVAILABLE_RECEIPT;
            RawReceipt raw=null;FlankReceipt flank=null;StageReceipt rawStage=null;
            int rawCount=0,flankCount=0,correlation=0,stageCount=0,filtered=0;
            double disagreement=0,disagreementLo=0,disagreementHi=0;
            for(int r=0;r<f.provenance().size();r++) {
                work.poll();work.binding(64);var receipt=f.provenance().get(r);
                if(receipt instanceof RawReceipt value){raw=value;rawCount++;}
                if(receipt instanceof FlankReceipt value){flank=value;flankCount++;}
                if(receipt instanceof CorrelationReceipt value) {
                    correlation++;
                    if(!same(value.fieldLineage(),input.evidence.sourceIdentity(),work)
                            ||!same(value.transformId(),input.evidence.transform().transformId(),work)
                            ||value.nativePitchKnown()!=p.nativePitchKnown())
                        throw new IllegalArgumentException("Foreign correlated source receipt");
                }
                if(receipt instanceof StageReceipt value) {
                    stageCount++;
                    if(value.original().stage()==LocalScalarProfileExtractor.ObservationStage.RAW)rawStage=value;
                    else if(value.original().available())filtered++;
                }
            }
            if(!original.groupedParent()&&rawCount==1&&flankCount==1&&correlation==1&&stageCount==3
                    &&rawStage!=null&&rawStage.original().available()&&filtered>0&&flank.complete()
                    &&raw.firstPeakIndex()>=0&&raw.lastPeakIndex()<p.samples().size()
                    &&raw.firstPeakIndex()<=raw.winnerIndex()&&raw.winnerIndex()<=raw.lastPeakIndex()
                    &&flank.leftIndex()>=0&&flank.rightIndex()<p.samples().size()
                    &&flank.leftIndex()<raw.firstPeakIndex()&&flank.rightIndex()>raw.lastPeakIndex()
                    &&rawStage.original().winnerSampleIndex()==raw.winnerIndex()) {
                double centre=value(f,Feature.RAW_CENTER),background=value(f,Feature.BACKGROUND);
                double rms=value(f,Feature.RMS_DESCRIPTOR),filter=value(f,Feature.FILTER_DESCRIPTOR);
                double lo=value(f,Feature.SHOULDER_MINIMUM),hi=value(f,Feature.SHOULDER_MAXIMUM);
                if(Double.isFinite(centre)&&Double.isFinite(background)&&background>=0
                        &&Double.isFinite(rms)&&rms>=0&&Double.isFinite(filter)&&filter>=0
                        &&bits(lo,p.samples().get(flank.leftIndex()).offsetMeters())
                        &&bits(hi,p.samples().get(flank.rightIndex()).offsetMeters())
                        &&bits(value(f,Feature.PEAK),p.samples().get(raw.winnerIndex()).intensity())
                        &&bits(value(f,Feature.PEAK),rawStage.original().localMaximum())) {
                    for(int r=0;r<f.provenance().size();r++) {
                        work.poll();work.binding(64);
                        if(f.provenance().get(r) instanceof StageReceipt stage&&stage.original().available()) {
                            double delta=stage.original().centerOffsetMeters()-centre;
                            disagreement=Math.max(disagreement,Math.abs(delta));
                            if(stage.original().centerOffsetMeters()!=centre) {
                                double low=Math.nextDown(delta),high=Math.nextUp(delta);
                                disagreementLo=Math.max(disagreementLo,low<=0&&high>=0?0:Math.min(Math.abs(low),Math.abs(high)));
                                disagreementHi=Math.max(disagreementHi,Math.max(Math.abs(low),Math.abs(high)));
                            }
                        }
                    }
                    double width=.5*(rawStage.lastOffsetMeters()-rawStage.firstOffsetMeters());
                    double h=width+disagreement+grid.rho/2;
                    if(!Double.isFinite(h)||h<=0)throw new ArithmeticException("Shared support width");
                    d[4][at]=centre;d[5][at]=background;d[6][at]=Math.max(rms,filter);
                    d[7][at]=h;d[9][at]=lo;d[10][at]=hi;d[12][at]=rawStage.firstOffsetMeters();
                    d[13][at]=rawStage.lastOffsetMeters();d[14][at]=disagreement;
                    double widthLo=width==0?0:Math.nextDown(Math.nextDown(rawStage.lastOffsetMeters()-rawStage.firstOffsetMeters())*.5);
                    double widthHi=width==0?0:Math.nextUp(Math.nextUp(rawStage.lastOffsetMeters()-rawStage.firstOffsetMeters())*.5);
                    d[15][at]=Math.nextDown(Math.nextDown(widthLo+disagreementLo)+Math.nextDown(grid.rhoLo/2));
                    d[16][at]=Math.nextUp(Math.nextUp(widthHi+disagreementHi)+Math.nextUp(grid.rhoHi/2));
                    if(!(d[15][at]>0)||!Double.isFinite(d[16][at]))throw new ArithmeticException("Shared width enclosure");
                    indices[7][at]=raw.winnerIndex();indices[8][at]=flank.leftIndex();indices[9][at]=flank.rightIndex();
                    indices[10][at]=raw.firstPeakIndex();indices[11][at]=raw.lastPeakIndex();
                    reason=Status.AVAILABLE;
                }
            }
            indices[2][at]=reason.ordinal();
            // Original raw-sample ownership, not overlapping shoulder geometry, defines aliases.
            if(raw!=null)for(int previous=rows[row];previous<at;previous++) {
                work.poll();work.binding(64);
                if(indices[2][previous]!=Status.INELIGIBLE_GROUPED_PARENT.ordinal()&&indices[10][previous]>=0
                        && raw.firstPeakIndex()<=indices[11][previous]&&raw.lastPeakIndex()>=indices[10][previous]) {
                    indices[2][at]=Status.UNAVAILABLE_ALIAS.ordinal();indices[2][previous]=Status.UNAVAILABLE_ALIAS.ordinal();
                }
            }
        }
        /** Outward rectangle inverse; a one-vertex halo covers B3 support and rounded grid centres.
         * Missing eligible receipts use the full original search bracket, never an empty footprint. */
        void footprint(int m) {
            work.poll();work.binding(256);
            if(indices[2][m]==Status.INELIGIBLE_GROUPED_PARENT.ordinal()) {
                indices[3][m]=0;indices[4][m]=-1;indices[5][m]=0;indices[6][m]=-1;return;
            }
            double lo=d[LO][m],hi=d[HI][m],length=d[18][m];
            if(indices[2][m]==Status.AVAILABLE.ordinal()) {
                lo=Math.max(lo,Math.nextDown(d[CENTRE][m]-d[16][m]));
                hi=Math.min(hi,Math.nextUp(d[CENTRE][m]+d[16][m]));
            }
            double nx=d[NX][m],ny=d[NY][m];
            double nl=Math.nextDown(Math.nextDown(nx*nx)+Math.nextDown(ny*ny));
            double nh=Math.nextUp(Math.nextUp(nx*nx)+Math.nextUp(ny*ny));
            if(nl<=0||!Double.isFinite(nh)||!Double.isFinite(lo)||!Double.isFinite(hi)||!Double.isFinite(length))
                throw new IllegalArgumentException("Unbounded contributor footprint");
            double xl=Math.nextDown(Math.min(nx*lo,nx*hi)-Math.nextUp(Math.abs(ny)*length));
            double xh=Math.nextUp(Math.max(nx*lo,nx*hi)+Math.nextUp(Math.abs(ny)*length));
            double yl=Math.nextDown(Math.min(ny*lo,ny*hi)-Math.nextUp(Math.abs(nx)*length));
            double yh=Math.nextUp(Math.max(ny*lo,ny*hi)+Math.nextUp(Math.abs(nx)*length));
            double exlo=Math.nextDown(d[AX][m]+quotientLow(xl,xh,nl,nh));
            double exhi=Math.nextUp(d[AX][m]+quotientHigh(xl,xh,nl,nh));
            double eylo=Math.nextDown(d[AY][m]+quotientLow(yl,yh,nl,nh));
            double eyhi=Math.nextUp(d[AY][m]+quotientHigh(yl,yh,nl,nh));
            double dl=Math.nextDown(Math.nextDown(grid.ux*grid.vy)-Math.nextUp(grid.uy*grid.vx));
            double dh=Math.nextUp(Math.nextUp(grid.ux*grid.vy)-Math.nextDown(grid.uy*grid.vx));
            if(dl<=0&&dh>=0)throw new IllegalArgumentException("Unbounded grid inverse");
            xl=Math.nextDown(exlo-grid.ox);xh=Math.nextUp(exhi-grid.ox);
            yl=Math.nextDown(eylo-grid.oy);yh=Math.nextUp(eyhi-grid.oy);
            double gxlo=quotientLow(Math.nextDown(productLow(xl,xh,grid.vy)-productHigh(yl,yh,grid.vx)),
                    Math.nextUp(productHigh(xl,xh,grid.vy)-productLow(yl,yh,grid.vx)),dl,dh);
            double gxhi=quotientHigh(Math.nextDown(productLow(xl,xh,grid.vy)-productHigh(yl,yh,grid.vx)),
                    Math.nextUp(productHigh(xl,xh,grid.vy)-productLow(yl,yh,grid.vx)),dl,dh);
            double gylo=quotientLow(Math.nextDown(productLow(yl,yh,grid.ux)-productHigh(xl,xh,grid.uy)),
                    Math.nextUp(productHigh(yl,yh,grid.ux)-productLow(xl,xh,grid.uy)),dl,dh);
            double gyhi=quotientHigh(Math.nextDown(productLow(yl,yh,grid.ux)-productHigh(xl,xh,grid.uy)),
                    Math.nextUp(productHigh(yl,yh,grid.ux)-productLow(xl,xh,grid.uy)),dl,dh);
            if(!Double.isFinite(gxlo)||!Double.isFinite(gxhi)||!Double.isFinite(gylo)||!Double.isFinite(gyhi))
                throw new IllegalArgumentException("Unbounded grid footprint");
            indices[3][m]=(int)Math.max(0,Math.min(grid.width,StrictMath.floor(gxlo)-1));
            indices[4][m]=(int)Math.max(-1,Math.min(grid.width-1,StrictMath.ceil(gxhi)+1));
            indices[5][m]=(int)Math.max(0,Math.min(grid.height,StrictMath.floor(gylo)-1));
            indices[6][m]=(int)Math.max(-1,Math.min(grid.height-1,StrictMath.ceil(gyhi)+1));
            for(int j=0;j<4;j++)work.strictMath();
        }
        /** Original source bilinear grouping, including last-row/cell masks. NaN means unavailable. */
        double sampleSource(double x,double y) {
            ensureOpen();work.interpolation();
            if(!sourceRegionContains(input.evidence.evidenceRegion(),x,y,work))return Double.NaN;
            var transform=input.evidence.transform();
            double dx=x-transform.origin().xMeters(),dy=y-transform.origin().yMeters();
            double det=transform.xAxisEastMetersPerSourcePixel()*transform.yAxisNorthMetersPerSourcePixel()
                    -transform.xAxisNorthMetersPerSourcePixel()*transform.yAxisEastMetersPerSourcePixel();
            double phase=transform.originKind()==RasterMetricTransform.OriginKind.MANAGED_SOURCE_PIXEL_BOUNDARY?.5:0;
            double scale=transform.axisUnit()==RasterMetricTransform.AxisUnit.SOURCE_PIXEL?transform.rasterPixelsPerSourcePixel():1;
            double rx=(((dx*transform.yAxisNorthMetersPerSourcePixel()-dy*transform.yAxisEastMetersPerSourcePixel())/det)-phase)*scale;
            double ry=(((dy*transform.xAxisEastMetersPerSourcePixel()-dx*transform.xAxisNorthMetersPerSourcePixel())/det)-phase)*scale;
            int w=input.evidence.fields().get(input.fieldKey).width(),h=input.evidence.fields().get(input.fieldKey).height();
            if(!Double.isFinite(rx)||!Double.isFinite(ry)||rx<0||ry<0||rx>w-1||ry>h-1)return Double.NaN;
            int cx=Math.min(w-2,(int)StrictMath.floor(rx)),cy=Math.min(h-2,(int)StrictMath.floor(ry));
            work.strictMath();work.strictMath();
            work.maskRead(1);if(!interpolation[cy*(w-1)+cx])return Double.NaN;
            int x0=(int)StrictMath.floor(rx),y0=(int)StrictMath.floor(ry);
            work.strictMath();work.strictMath();
            int x1=Math.min(w-1,x0+1),y1=Math.min(h-1,y0+1);
            int a=y0*w+x0,b=y0*w+x1,c=y1*w+x0,d=y1*w+x1;
            if(!sourceValid(a)||!sourceValid(b)||!sourceValid(c)||!sourceValid(d))return Double.NaN;
            double fx=rx-x0,fy=ry-y0;
            work.sourceRead(6);
            double top=source[a]+fx*(source[b]-source[a]);
            double bottom=source[c]+fx*(source[d]-source[c]);
            return top+fy*(bottom-top);
        }
        private boolean sourceValid(int i){work.maskRead(1);return valid[i];}
        @Override public void close() {owner.close();}
    }
    static final int AX=0,AY=1,NX=2,NY=3,CENTRE=4,BACKGROUND=5,NUISANCE=6,H=7,L=8,LO=9,HI=10;
    static long array(long n,int w){return AttemptMemoryLedger.arrayBytes(n,w);}
    static long plus(long a,long b){return Math.addExact(a,b);}
    static boolean bits(double a,double b){return Double.doubleToRawLongBits(a)==Double.doubleToRawLongBits(b);}
    static double value(ModeFacts facts,Feature feature) {
        for(int i=0;i<facts.values().size();i++)if(facts.values().get(i).feature()==feature)
            return facts.values().get(i).value().orElse(Double.NaN);
        return Double.NaN;
    }
    static boolean same(String a,String b,BSharedWork work) {
        if(a==null||b==null||a.length()!=b.length()||a.length()>8192)return false;
        for(int i=0;i<a.length();i++){if((i&31)==0)work.poll();work.binding(4);if(a.charAt(i)!=b.charAt(i))return false;}
        return true;
    }
    static double effectivePitch(EvidenceResolution resolution,double chainage,BSharedWork work) {
        var samples=resolution.spatialPitchSamples();
        if(!Double.isFinite(chainage)||chainage<0||samples.size()>1
                &&chainage>samples.get(samples.size()-1).chainageMeters()+1e-12)
            throw new IllegalArgumentException("Unsupported source chainage");
        if(samples.size()==1)return samples.get(0).effectivePitchMeters();
        for(int i=1;i<samples.size();i++) {
            work.poll();work.binding(64);var right=samples.get(i);
            if(chainage<=right.chainageMeters()) {
                var left=samples.get(i-1);
                double f=(chainage-left.chainageMeters())/(right.chainageMeters()-left.chainageMeters());
                return left.effectivePitchMeters()+f*(right.effectivePitchMeters()-left.effectivePitchMeters());
            }
        }
        return samples.get(samples.size()-1).effectivePitchMeters();
    }
    private static long joinComponents(ProbabilisticProfile p,ProbabilisticStateLattice lattice,BSharedWork work) {
        long visits=0;
        if(p.censoredModes().size()>401)throw new IllegalArgumentException("Original censored inventory guard");
        for(int c=0;c<lattice.components().size();c++) {
            work.poll();work.preflight(128);var component=lattice.components().get(c);
            boolean found=false;
            switch(component.kind()) {
                case MEASURED -> {
                    for(int m=0;m<p.modes().size();m++) {
                        work.poll();work.preflight(32);var mode=p.modes().get(m);
                        visits=plus(visits,plus(mode.id().length(),mode.evidenceLineage().length()));
                        if(same(component.id(),mode.id(),work)&&same(component.evidenceLineage(),mode.evidenceLineage(),work)
                                &&component.groupedParent()==mode.groupedParent())found=true;
                    }
                }
                case CENSORED -> {
                    for(int m=0;m<p.censoredModes().size();m++) {
                        work.poll();work.preflight(32);var mode=p.censoredModes().get(m);
                        visits=plus(visits,plus(mode.id().length(),mode.evidenceLineage().length()));
                        if(same(component.id(),mode.id(),work)&&same(component.evidenceLineage(),mode.evidenceLineage(),work)
                                &&!component.groupedParent())found=true;
                    }
                }
                case MISSING -> {
                    found=component.id().equals("missing")&&component.evidenceLineage().equals("missing:"+p.profileIndex())&&!component.groupedParent();
                    visits=plus(visits,component.id().length()+component.evidenceLineage().length());
                }
            }
            if(!found)throw new IllegalArgumentException("Lattice component lost original mode identity");
        }
        return visits;
    }
    private static double productLow(double lo,double hi,double scalar){return Math.nextDown(scalar>=0?lo*scalar:hi*scalar);}
    private static double productHigh(double lo,double hi,double scalar){return Math.nextUp(scalar>=0?hi*scalar:lo*scalar);}
    private static double quotientLow(double a,double b,double c,double d){return Math.nextDown(Math.min(Math.min(a/c,a/d),Math.min(b/c,b/d)));}
    private static double quotientHigh(double a,double b,double c,double d){return Math.nextUp(Math.max(Math.max(a/c,a/d),Math.max(b/c,b/d)));}
    /** Indexed original MetricRegion point predicate; registered upper slots are not CPU counts. */
    static boolean sourceRegionContains(MetricRegion region,double x,double y,BSharedWork work) {
        work.numeric(32);
        if(!Double.isFinite(x)||!Double.isFinite(y))throw new IllegalArgumentException("Nonfinite source query");
        var polygons=region.polygons();
        for(int polygon=0;polygon<polygons.size();polygon++) {
            work.numeric(16);var points=polygons.get(polygon);boolean inside=false;
            for(int current=0,previous=points.size()-1;current<points.size();previous=current++) {
                work.poll();work.numeric(128);
                var a=points.get(previous);var b=points.get(current);
                double ax=a.xMeters(),ay=a.yMeters(),bx=b.xMeters(),by=b.yMeters();
                // Preserve MetricRegion.onSegment's bounds, orientation, distance and grouping.
                if(x>=Math.min(ax,bx)-1e-9&&x<=Math.max(ax,bx)+1e-9
                        &&y>=Math.min(ay,by)-1e-9&&y<=Math.max(ay,by)+1e-9) {
                    double cross=(bx-ax)*(y-ay)-(by-ay)*(x-ax);
                    work.strictMath();double scale=Math.max(1.0,StrictMath.hypot(ax-bx,ay-by));
                    if(Math.abs(cross)<=1e-9*scale)return true;
                }
                boolean crosses=(ay>y)!=(by>y)&&x<(bx-ax)*(y-ay)/(by-ay)+ax;
                if(crosses)inside=!inside;
            }
            if(inside)return true;
        }
        return false;
    }
    static long upper(long t,long n0,long c0,long s,long n,long m,long v,long k,long r,long p,long z,long edges,
            long samples,long sourceEdges,long sourcePolygons) {
        if(samples<0||sourcePolygons<1||sourceEdges<Math.multiplyExact(3,sourcePolygons))
            throw new IllegalArgumentException("Invalid original source-region census");
        long b=plus(1024,Math.multiplyExact(256,plus(t,plus(n0,plus(c0,plus(s,plus(n,plus(m,plus(v,k)))))))));
        b=plus(b,Math.multiplyExact(1024,r));b=plus(b,Math.multiplyExact(128*81+96,v));
        b=plus(b,Math.multiplyExact(512,p));b=plus(b,Math.multiplyExact(4096,z));
        long events=plus(Math.multiplyExact(2,edges),2),ceil=0;
        for(long i=events-1;i>0;i>>=1)ceil++;
        long region=Math.multiplyExact(events,plus(Math.multiplyExact(128,edges+1),ceil+64));
        b=plus(b,Math.multiplyExact(Math.multiplyExact(128,p),region));
        b=plus(b,Math.multiplyExact(plus(v,samples),plus(32,
                plus(Math.multiplyExact(16,sourcePolygons),Math.multiplyExact(128,sourceEdges)))));
        return plus(b,Math.multiplyExact(1+BSharedWork.EXACT_PROXY,
                plus(Math.multiplyExact(256,plus(m,plus(r,p))),Math.multiplyExact(512,z))));
    }

    /** Only the reviewed local capability, fingerprinted bootstrap bytes rather than version alone. */
    static void requireLocalRuntime(BSharedWork work) {
        if(work.isExploratory()) {
            //Only named quality experiments: numerical guards still run;
            //no cold/native/bootstrap qualification follows from Java17.
            if(Runtime.version().feature()!=17)throw new IllegalArgumentException("Exploratory Java17 required");
            return;
        }
        if(!"17.0.20+0".equals(System.getProperty("java.runtime.version"))
                ||!"Termux".equals(System.getProperty("java.vendor"))
                ||!"Linux".equals(System.getProperty("os.name"))||!"aarch64".equals(System.getProperty("os.arch")))
            throw new IllegalArgumentException("Unreviewed shared exact runtime");
        String[] names={"java/math/BigInteger.class","java/math/MutableBigInteger.class","java/lang/Math.class",
                "java/lang/StrictMath.class","java/lang/FdLibm$Hypot.class"};
        String[] hashes={"ef83570994d21870aec73e70e687360c0df392e277cbef73877ae380182fd400",
                "feb53cccf4994fdb3296756e17c96ddb44150455e725bb15ff90818d00aa3dd6",
                "66d16712330824ea4f0adc08d48b5b7dabedaae8733e7ce718bfef6442260e3f",
                "74d060a9904c604aad1f00d644502fb886d6865df556e7de73c89cf6fc0b4878",
                "1ddbb214247bc90d73c21b6f144f26196409f7c2863925ec60d60357988e4db2"};
        work.metadata(8192);
        byte[] buffer=new byte[256];
        try {
            for(int i=0;i<names.length;i++) {
                MessageDigest digest=MessageDigest.getInstance("SHA-256");int bytes=0;
                try(InputStream stream=Object.class.getResourceAsStream("/"+names[i])) {
                    if(stream==null)throw new IllegalArgumentException("Missing bootstrap runtime");
                    for(int count;(count=stream.read(buffer))!=-1;) {
                        work.poll();work.numeric(4L*count);bytes=Math.addExact(bytes,count);
                        if(bytes>65536)throw new IllegalArgumentException("Bootstrap class bound");
                        digest.update(buffer,0,count);
                    }
                }
                if(!HexFormat.of().formatHex(digest.digest()).equals(hashes[i]))
                    throw new IllegalArgumentException("Unreviewed bootstrap fingerprint");
            }
        } catch(java.security.NoSuchAlgorithmException|IOException failure) {
            throw new IllegalArgumentException("Runtime fingerprint unavailable",failure);
        }
    }

    /** New source binder; old gap grid/counters are neither invoked nor changed. */
    static final class Grid {
        final int width,height; final double ox,oy,ux,uy,vx,vy,det,rho,rhoLo,rhoHi; final boolean known;
        Grid(int width,int height,double ox,double oy,double ux,double uy,double vx,double vy,boolean known) {
            this.width=width;this.height=height;this.ox=ox;this.oy=oy;this.ux=ux;this.uy=uy;this.vx=vx;this.vy=vy;
            det=ux*vy-uy*vx;
            // Initial capability is axis-aligned and isotropic; original lattices remain untouched.
            double first=ux*ux+uy*uy,second=vx*vx+vy*vy;
            rho=StrictMath.sqrt(first);
            double firstLo=Math.max(0,Math.nextDown(Math.nextDown(ux*ux)+Math.nextDown(uy*uy)));
            double firstHi=Math.nextUp(Math.nextUp(ux*ux)+Math.nextUp(uy*uy));
            rhoLo=Math.nextDown(StrictMath.sqrt(firstLo));rhoHi=Math.nextUp(StrictMath.sqrt(firstHi));
            double maxEast=Math.nextUp(Math.abs(ox)+Math.nextUp((width-1)*Math.abs(ux))+Math.nextUp((height-1)*Math.abs(vx)));
            double maxNorth=Math.nextUp(Math.abs(oy)+Math.nextUp((width-1)*Math.abs(uy))+Math.nextUp((height-1)*Math.abs(vy)));
            boolean distinct=Double.isFinite(maxEast)&&Double.isFinite(maxNorth)
                    &&4*Math.ulp(maxEast)<=rhoLo&&4*Math.ulp(maxNorth)<=rhoLo;
            this.known=known&&width>=2&&height>=2&&Double.isFinite(det)&&det!=0
                    &&uy==0&&vx==0&&distinct&&Double.isFinite(rho)&&rho>0&&rhoLo>0&&Double.isFinite(rhoHi)&&first==second;
        }
        static Grid from(Input input,BSharedWork work) {
            work.preflight(256);work.poll();work.strictMath();work.strictMath();work.strictMath();
            var e=input.evidence;var t=e.transform();var p=e.resampling();var f=e.fields().get(input.fieldKey);
            double scale=t.axisUnit()==RasterMetricTransform.AxisUnit.SOURCE_PIXEL?t.rasterPixelsPerSourcePixel():1;
            var origin=t.pixelCenterToMetric(0,0);
            double ux=t.xAxisEastMetersPerSourcePixel()/scale,uy=t.xAxisNorthMetersPerSourcePixel()/scale;
            double vx=t.yAxisEastMetersPerSourcePixel()/scale,vy=t.yAxisNorthMetersPerSourcePixel()/scale;
            if(p.method().equals("direct-scalar-grid-v1"))return new Grid(f.width(),f.height(),origin.xMeters(),origin.yMeters(),ux,uy,vx,vy,true);
            if(p.method().equals("exact-analytic-cell-support-strict-bilinear-v2")
                    &&p.sourceTransformKind().equals("constructed-local-metric-affine-v1")
                    &&f.lineage().acquisitionKind()==EvidenceFieldLineage.AcquisitionKind.SYNTHETIC
                    &&p.sourceTransformIdentity().length()<=4096&&e.coordinateFrame().projectionId().length()<=1024) {
                String actual=p.sourceTransformIdentity();
                String prefix=p.sourceTransformKind()+"|"+e.coordinateFrame().projectionId()+"|"
                        +Double.toHexString(e.coordinateFrame().origin().latitudeDegrees())+"|"
                        +Double.toHexString(e.coordinateFrame().origin().longitudeDegrees())+"|";
                if(actual.startsWith(prefix)) {
                    String[] parameters=actual.substring(prefix.length()).split("\\|",-1);
                    if(parameters.length==6) {
                        double[] v=new double[6];boolean valid=true;
                        for(int i=0;i<6;i++) {
                            work.preflight(128);work.poll();
                            if(parameters[i].length()>24) {valid=false;break;}
                            try {v[i]=Double.parseDouble(parameters[i]);}
                            catch(NumberFormatException invalid){valid=false;break;}
                            valid&=Double.isFinite(v[i])&&Double.toHexString(v[i]).equals(parameters[i]);
                        }
                        var original=SupportedInputRasterTransform.localMetricAffine(e.coordinateFrame(),new RasterPoint(v[0],v[1]),v[2],v[3],v[4],v[5]);
                        if(valid&&original.parameterIdentity().equals(actual)&&v[3]==0&&v[4]==0&&v[2]!=0&&v[5]!=0)
                            return new Grid(p.inputWidth(),p.inputHeight(),-v[0]/v[2],-v[1]/v[5],1/v[2],0,0,1/v[5],true);
                    }
                }
            }
            return new Grid(f.width(),f.height(),origin.xMeters(),origin.yMeters(),ux,uy,vx,vy,false);
        }
        double x(double x,double y){return ((x-ox)*vy-(y-oy)*vx)/det;}
        double y(double x,double y){return ((y-oy)*ux-(x-ox)*uy)/det;}
        double east(int x,int y){return ox+x*ux+y*vx;}
        double north(int x,int y){return oy+x*uy+y*vy;}
    }
    enum Status { AVAILABLE, OUTSIDE_COMPONENT_SUPPORT, OBSERVED_ZERO,
        UNAVAILABLE_PROFILE_RECIPE, UNAVAILABLE_SOURCE_GRID, UNAVAILABLE_SOURCE_SUPPORT,
        UNAVAILABLE_RECEIPT, UNAVAILABLE_ALIAS, INELIGIBLE_GROUPED_PARENT }
    private static final Status[] STATUSES=Status.values();
    static Status status(int ordinal) {return STATUSES[ordinal];}

    /** Exact replay of the original factory's ceil/parity/last-endpoint sample recipe. */
    static Status profileCapability(ProbabilisticProfile profile, BSharedWork work) {
        work.ensureOpen(); work.poll(); work.preflight(64);
        double pitch = .5 * profile.sourcePitchMeters();
        double span = profile.maximumOffsetMeters() - profile.minimumOffsetMeters();
        double requested = StrictMath.ceil(span / pitch); work.strictMath();
        if (!Double.isFinite(pitch) || pitch <= 0 || !Double.isFinite(requested)
                || requested > 400 || requested < 1) return Status.UNAVAILABLE_PROFILE_RECIPE;
        int intervals = Math.max(1, (int) requested);
        if (profile.minimumOffsetMeters() == -profile.maximumOffsetMeters() && (intervals & 1) != 0)
            intervals = Math.addExact(intervals, 1);
        if (profile.samples().size() != intervals + 1) return Status.UNAVAILABLE_PROFILE_RECIPE;
        for (int index = 0; index <= intervals; index++) {
            work.poll(); work.preflight(16);
            double expected = index == intervals ? profile.maximumOffsetMeters()
                    : profile.minimumOffsetMeters() + index * span / intervals;
            if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(
                    profile.samples().get(index).offsetMeters())) return Status.UNAVAILABLE_PROFILE_RECIPE;
        }
        return Status.AVAILABLE;
    }

    private BSharedPositionEvidence() { }
}
