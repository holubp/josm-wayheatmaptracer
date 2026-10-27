package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;
import java.util.ArrayList;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.*;

/** Genuine extractor-bound inputs for the explicitly unqualified quality experiment. */
final class BSharedFeasibilityInputs {
    private BSharedFeasibilityInputs(){}
    static final class OwnedSource implements AutoCloseable {
        private final BPhysicalWork work;
        private final BIdentificationFactsExtractor.SharedPositionFacts authority;
        private final BSharedPositionEvidence.Input input;
        private final int sourcePoints;
        private final List<MetricPoint> original;
        private boolean closed;
        private OwnedSource(BPhysicalWork work,EvidenceSnapshot snapshot,
                List<ProbabilisticProfile> profiles,List<ProbabilisticStateLattice> lattices,int sourcePoints){
            this(work,snapshot,profiles,lattices,List.of(profiles.get(0).anchor(),profiles.get(profiles.size()-1).anchor()));
        }
        private OwnedSource(BPhysicalWork work,EvidenceSnapshot snapshot,
                List<ProbabilisticProfile> profiles,List<ProbabilisticStateLattice> lattices,List<MetricPoint> original){
            work.requireExploratory();this.work=work;this.original=List.copyOf(original);this.sourcePoints=original.size();
            authority=new BIdentificationFactsExtractor().extractSharedPosition(snapshot,"scalar",profiles,
                work.probe(),work.owner(),work.sourceWork());
            try{input=BSharedPositionEvidence.Input.create(snapshot,"scalar",authority,authority.facts(),lattices,
                snapshot.decisionRegion(),BSharedPositionEvidence.approvedContract(),work.sourceWork());}
            catch(RuntimeException|Error primary){try{authority.close();}catch(RuntimeException|Error secondary){/* preserve primary */}throw primary;}
        }
        void require(BPhysicalWork expected){
            if(closed)throw new IllegalStateException("Exploratory source closed");
            if(expected!=work)throw new IllegalArgumentException("Foreign exploratory source");
            work.requireExploratory();work.probe().checkpoint();authority.ensureOpen();
        }
        BSharedPositionEvidence.Input input(){require(work);return input;}
        int sourcePoints(){require(work);return sourcePoints;}
        EvidenceSnapshot snapshot(){require(work);return (EvidenceSnapshot)input.evidence;}
        List<MetricPoint> original(){require(work);return original;}
        public void close(){if(!closed){closed=true;authority.close();}}
    }
    /** Tiny real scalar capture, not any original control recipe or truth input. */
    static OwnedSource tiny(BPhysicalWork work){
        work.requireExploratory();work.probe().checkpoint();
        var fixture=V022BPhysicalPairTableTest.fixture(work,true,2,false,true,false);
        var lattices=fixture.profiles().stream().map(p->new ProbabilisticStateBuilder().build(p,96,work.owner())
            .lattice().orElseThrow()).toList();
        return new OwnedSource(work,fixture.snapshot(),fixture.profiles(),lattices,2);
    }
    /** Fixed1/3/1 analytical graph with actual wide-window stripe observations.
     * The native builder supplies component semantics, not this toy's states.
     * None of the32 original control lattices is acquired or pruned here. */
    static OwnedSource tinyThree(BPhysicalWork work){
        work.requireExploratory();work.probe().checkpoint();
        var fixture=V022BPhysicalPairTableTest.fixture(work,true,3);
        var lattices=new ArrayList<ProbabilisticStateLattice>();
        for(int i=0;i<3;i++){
            var observed=new ProbabilisticStateBuilder().build(fixture.profiles().get(i),96,work.owner()).lattice().orElseThrow();
            if(i!=1)lattices.add(observed);
            else try(var held=work.owner().reserve(2048)){
                var cells=List.of(new LateralStateCell(-1,1,false,true,"unlocalized"),
                    new LateralStateCell(0,1,false,true,"unlocalized"),new LateralStateCell(1,1,false,true,"unlocalized"));
                var declared=new ProbabilisticStateLattice(cells,observed.components(),1,false);
                held.adopt(declared);lattices.add(declared);
            }
        }
        return new OwnedSource(work,fixture.snapshot(),fixture.profiles(),lattices,
            List.of(fixture.profiles().get(0).anchor(),fixture.profiles().get(2).anchor()));
    }
    static OwnedSource capture(String id,BPhysicalWork work)throws Exception{
        work.requireExploratory();
        if(!"true".equals(System.getenv("CI"))||!"true".equals(System.getenv("V022_RUN_SHARED_FEASIBILITY")))
            throw new IllegalStateException("Original feasibility cases require explicit CI opt-in");
        BSharedPositionComparison.requireAttempt(id,1,0);work.probe().checkpoint();
        var input=V022BGapMatchedControlsTest.neutralNativeInput(id,work.probe());
        String selected=System.getenv("SHARED_FEASIBILITY_INPUTS");
        if(selected==null)throw new IllegalStateException("Frozen neutral inputs required");
        var neutral=Path.of(selected);
        var index=BIdentificationReceipt.parse(BNativeNeutralFixtureExporter.bounded(neutral.resolve("index.json"),65536));
        BNativeNeutralFixtureExporter.validateIndex(index);
        var members=BNativeNeutralFixtureExporter.map(index.get("members"));
        byte[][] actual={BNativeNeutralFixtureExporter.scalarBytes(input.scalar()),
            BNativeNeutralFixtureExporter.maskBytes(input.validity()),
            BNativeNeutralFixtureExporter.json(BNativeNeutralFixtureExporter.descriptor(id,input))};
        String[] leaves={"scalar.f64","validity.bin","descriptor.json"};
        for(int i=0;i<3;i++){
            var expected=BNativeNeutralFixtureExporter.map(members.get(id+"/"+leaves[i]));
            if(!BIdentificationReceipt.hash(actual[i]).equals(expected.get("sha256")))
                throw new IllegalArgumentException("Current source differs from frozen neutral input");
        }
        var frame=input.grid().coordinateFrame();
        var inverse=SupportedInputRasterTransform.localMetricAffine(frame,input.inputRasterOrigin(),
                1/input.pitch(),0,0,1/input.pitch());
        var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"matched-declared-scalar",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false);
        EvidenceSnapshot snapshot;
        try(var lease=work.owner().reserve(32L<<20)){
            snapshot=new RasterEvidenceCapture(32L<<20).captureWithMetricScalarSource("matched-"+id,
                new BufferedImage(81,81,BufferedImage.TYPE_INT_ARGB),input.validity(),input.scalar(),
                input.source().stream().map(frame::toGeographic).toList(),input.source(),inverse,input.grid(),
                EvidenceResolution.nativeSource(input.pitch(),input.pitch()),25,"matched-public-declared-"+id,
                EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,"scalar",lineage,work.probe());
            lease.adopt(snapshot);
        }
        var expectedCapture=BNativeNeutralFixtureExporter.map(index.get("captures")).get(id);
        if(!BIdentificationReceipt.canonical(BNativeNeutralFixtureExporter.captured(snapshot)).equals(BIdentificationReceipt.canonical(expectedCapture)))
            throw new IllegalArgumentException("Current captured scalar/masks/frame differ from neutral snapshot");
        var factory=new ProbabilisticProfileFactory();
        var profiles=factory.create(input.source(),factory.profileChainage(input.source(),1.5),25,true,
            snapshot,snapshot.fields().get("scalar"),EvidenceModelParameters.defaults(),work.probe(),true,work.owner());
        if(profiles.size()>81)throw new IllegalArgumentException("Original profile capability exceeded");
        var lattices=new ArrayList<ProbabilisticStateLattice>();
        for(var profile:profiles){work.probe().checkpoint();
            var built=new ProbabilisticStateBuilder().build(profile,96,work.owner());
            if(built.status()!=StateSpaceBuildResult.Status.COMPLETE)
                throw new IllegalArgumentException("Original baseline lattice incomplete");
            lattices.add(built.lattice().orElseThrow());}
        return new OwnedSource(work,snapshot,profiles,lattices,input.source());
    }
}
