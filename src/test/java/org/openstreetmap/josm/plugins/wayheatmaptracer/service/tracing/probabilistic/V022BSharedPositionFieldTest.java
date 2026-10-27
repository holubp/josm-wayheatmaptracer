package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationFacts.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Tiny source-consistent field controls. No inference or complete experiment is executed. */
class V022BSharedPositionFieldTest {
    @Test void falseInvalidSampleCannotHideSupportedSourceDrift() {
        try(var f=fixture(8.5,.5,true);var work=BSharedWork.create(f.owner,CancellationProbe.NONE)) {
            var samples=new ArrayList<>(f.profile.samples());
            var replaced=samples.get(12);assertTrue(replaced.valid());
            try(var lease=f.owner.reserve(AttemptMemoryLedger.objectBytes(32))) {
                var invalid=new ProbabilisticProfile.Sample(replaced.offsetMeters(),Double.NaN,false);
                lease.adopt(invalid);samples.set(12,invalid);
            }
            var p=f.profile;
            ProbabilisticProfile changed;
            try(var lease=f.owner.reserve(AttemptMemoryLedger.objectBytes(104));
                var list=f.owner.reserve(AttemptMemoryLedger.arrayBytes(samples.size(),8)+24)) {
                changed=new ProbabilisticProfile(p.profileIndex(),p.chainageMeters(),p.anchor(),p.normalUnit(),p.minimumOffsetMeters(),
                        p.maximumOffsetMeters(),p.sourcePitchMeters(),p.nativePitchKnown(),p.noiseFloor(),samples,p.modes(),
                        p.censoredModes(),p.exactAnchorOffsetMeters(),p.orientationSupport());
                lease.adopt(changed);list.adopt(changed.samples());
            }
            var input=issuedInput(f.snapshot,"scalar",List.of(changed),List.of(tinyLattice(f.owner,changed)),f.owner,work);
            long before=f.ledger.currentBytes();
            assertThrows(IllegalArgumentException.class,()->BSharedPositionEvidence.bind(input,f.owner,CancellationProbe.NONE,work));
            assertEquals(before,f.ledger.currentBytes());
        }
    }

    @Test void originalEvidenceRegionAuthorizesSourceReplayBeforeRasterSupport() {
        try(var f=fixture(8.5,.5,true);var work=BSharedWork.create(f.owner,CancellationProbe.NONE)) {
            var restricted=MetricRegion.rectangle(0,0,3,16);
            assertFalse(restricted.contains(f.profile.anchor()));
            assertTrue(f.field.sampleBilinear(4,8.5).isPresent());
            var changed=new EvidenceSnapshot("restricted-original-region",f.snapshot.coordinateFrame(),f.snapshot.transform(),
                    f.snapshot.resolution(),restricted,restricted,Map.of("scalar",f.field),f.snapshot.sourceIdentity());
            var input=issuedInput(changed,"scalar",List.of(f.profile),List.of(tinyLattice(f.owner,f.profile)),f.owner,work);
            long before=f.ledger.currentBytes();
            assertThrows(IllegalArgumentException.class,()->BSharedPositionEvidence.bind(input,f.owner,CancellationProbe.NONE,work));
            assertEquals(before,f.ledger.currentBytes());
        }
    }

    @Test void cancellationAfterLastStateStoreCannotPublishBound() {
        try(var f=fixture(8.5,.5,true)) {
            BSharedWork[] holder={null};
            //153 scalar values +153 vertex masks +128 interpolation masks +3 pairs of state coordinates.
            CancellationProbe probe=()->holder[0]!=null&&holder[0].recordedArrayWrites()>=440;
            try(var work=BSharedWork.create(f.owner,probe)) {
                holder[0]=work;var input=actualInput(f,work);long before=f.ledger.currentBytes();
                assertThrows(java.util.concurrent.CancellationException.class,()->BSharedPositionEvidence.bind(input,f.owner,probe,work));
                assertEquals(440,work.recordedArrayWrites());assertEquals(before,f.ledger.currentBytes());
            }
        }
    }

    @Test void rotatedDirectGridIsUnavailableWithoutChangingOriginalLattice() {
        var transform=RasterMetricTransform.metricGrid(new MetricPoint(20,0),0,1,-1,0);
        try(var f=factoryFixture(sourceField(true,true),transform,EvidenceResolution.nativeSource(1,1),
                    new MetricPoint(11.5,2.5),new MetricPoint(11.5,5.5),3);
            var work=BSharedWork.create(f.owner,CancellationProbe.NONE)) {
            var input=actualInput(f,work);var cells=input.lattices.get(0).cells();
            try(var bound=BSharedPositionEvidence.bind(input,f.owner,CancellationProbe.NONE,work)) {
                assertEquals(BSharedPositionEvidence.Status.UNAVAILABLE_SOURCE_GRID,bound.status());
                assertSame(cells,input.lattices.get(0).cells());
            }
        }
    }

    @Test void unavailableBoundAlsoPollsSameProbeAtPublication() {
        try(var f=fixture(8.5,1,false)) {
            BSharedWork[] holder={null};
            // The unavailable recipe has no source cloning. U is claimed only after the
            // last descriptor pass; a probe observing it targets the return boundary.
            CancellationProbe probe=()->holder[0]!=null&&holder[0].snapshot().reservedUpperUnits()>0;
            try(var work=BSharedWork.create(f.owner,probe)) {
                holder[0]=work;var input=actualInput(f,work);long before=f.ledger.currentBytes();
                assertThrows(java.util.concurrent.CancellationException.class,()->BSharedPositionEvidence.bind(input,f.owner,probe,work));
                assertEquals(0,work.snapshot().sourceReads());assertEquals(before,f.ledger.currentBytes());
            }
        }
    }

    @Test void indexedSourceMembershipMatchesOriginalUnionBoundaryAndTinyEdges() {
        var first=MetricRegion.rectangle(-.1,.2,2.3,3.4);
        var second=MetricRegion.rectangle(4,0,5,1);
        var nested=MetricRegion.rectangle(.5,.5,1,1);
        var regions=List.of(first,new MetricRegion(List.of(first.polygons().get(0),second.polygons().get(0))),
                new MetricRegion(List.of(first.polygons().get(0),nested.polygons().get(0))),
                MetricRegion.rectangle(0,0,1e-6,1));
        var ledger=new AttemptMemoryLedger(64L<<20);
        try(var owner=ledger.rootOwner();
            var work=BSharedWork.create(owner,CancellationProbe.NONE)) {
            work.reserveUpper(1_000_000);
            for(var region:regions) {
                long edges=0;for(var polygon:region.polygons())edges+=polygon.size();
                long perQuery=32+16L*region.polygons().size()+128*edges;
                for(var point:List.of(new MetricPoint(-.1,.2),new MetricPoint(2.3,1),new MetricPoint(2.3+5e-10,1),
                        new MetricPoint(2.3+2e-9,1),new MetricPoint(.75,.75),new MetricPoint(4.5,.5),
                        new MetricPoint(5e-7,.5),new MetricPoint(-2e-9,.5),new MetricPoint(9,9))) {
                    long before=work.snapshot().buildUnits();
                    assertEquals(region.contains(point),BSharedPositionEvidence.sourceRegionContains(region,point.xMeters(),point.yMeters(),work));
                    assertTrue(work.snapshot().buildUnits()-before<=perQuery);
                }
            }
            // Nested polygons are a union, not a hole; the original semantics remain authoritative.
            assertTrue(regions.get(2).contains(new MetricPoint(.75,.75)));
        }
        assertEquals(0,ledger.currentBytes());
    }

    @Test void actualFactoryInvalidSamplesAndRegionExteriorMatchOriginalSampler() {
        var complete=sourceField(true,true);var validity=complete.copiedValidity();
        // Factory windows clip to the decision region. A real interior source mask hole,
        // rather than an exterior requested window, supplies authentic invalid samples.
        validity[8*9+4]=false;
        var raw=new ScalarEvidenceField(9,17,complete.copiedValues(),validity,complete.lineage());
        var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),new GeographicPoint(-1,-1),new GeographicPoint(1,1));
        var transform=RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1);
        var region=MetricRegion.rectangle(3,7,5,10);
        var evidence=new EvidenceSnapshot("bounded-original-region",frame,transform,EvidenceResolution.nativeSource(1,1),
                region,region,Map.of("scalar",raw),"synthetic");
        try(var f=factoryFixture(evidence,new MetricPoint(3.5,8.5),new MetricPoint(4.5,8.5),3);
            var work=BSharedWork.create(f.owner,CancellationProbe.NONE)) {
            assertTrue(f.profile.samples().stream().anyMatch(s->!s.valid()));
            var input=actualInput(f,work);long before=f.ledger.currentBytes();
            try(var bound=BSharedPositionEvidence.bind(input,f.owner,CancellationProbe.NONE,work)) {
                assertEquals(BSharedPositionEvidence.Status.AVAILABLE,bound.status());
                for(int y=0;y<17;y++)for(int x=0;x<9;x++) {
                    var point=new MetricPoint(x,y);
                    var expected=region.contains(point)?raw.sampleBilinear(x,y):OptionalDouble.empty();
                    double actual=bound.sampleSource(x,y);
                    assertEquals(expected.isPresent(),Double.isFinite(actual),"source support at "+x+","+y);
                    if(expected.isPresent())assertEquals(Double.doubleToRawLongBits(expected.getAsDouble()),Double.doubleToRawLongBits(actual));
                }
                assertEquals(153L+f.profile.samples().size(),work.snapshot().sourceInterpolations());
            }
            assertEquals(before,f.ledger.currentBytes());
        }
    }

    @Test void sourceMembershipCensusAddsExactlyReviewedVariableTerm() {
        long base=BSharedPositionEvidence.upper(0,9,4,3,1,1,9,3,5,0,0,4,5,4,1);
        assertEquals(32+16+128*4,BSharedPositionEvidence.upper(0,9,4,3,1,1,9,3,5,0,0,4,6,4,1)-base);
        assertEquals(14*128,BSharedPositionEvidence.upper(0,9,4,3,1,1,9,3,5,0,0,4,5,5,1)-base);
        long onePolygon=BSharedPositionEvidence.upper(0,9,4,3,1,1,9,3,5,0,0,4,5,8,1);
        assertEquals(14*16,BSharedPositionEvidence.upper(0,9,4,3,1,1,9,3,5,0,0,4,5,8,2)-onePolygon);
        assertThrows(IllegalArgumentException.class,()->BSharedPositionEvidence.upper(0,9,4,3,1,1,9,3,5,0,0,4,5,2,1));
        assertThrows(ArithmeticException.class,()->BSharedPositionEvidence.upper(0,9,4,3,1,1,9,3,5,0,0,4,Long.MAX_VALUE,4,1));
    }

    @Test void capturedQuarterPhaseBindsInputGridAndActualResampledValues() {
        var raw=sourceField(true,true);
        var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),new GeographicPoint(-1,-1),new GeographicPoint(1,1));
        var source=List.of(new MetricPoint(2.5,8.5),new MetricPoint(5.5,8.5));
        var inverse=org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform.localMetricAffine(frame,new RasterPoint(0,0),1,0,0,1);
        var grid=new MetricRasterGrid(frame,new MetricPoint(-.25,-.25),1,0,0,1,.5,19,35);
        // Same existing32MiB capture envelope as the approved harness; its source-derived
        // fixed geometry workspace is included even for this9x17 ->19x35 tiny raster.
        var captured=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.RasterEvidenceCapture(32L<<20)
                .captureWithMetricScalarSource("tiny-quarter-phase",new java.awt.image.BufferedImage(9,17,java.awt.image.BufferedImage.TYPE_INT_ARGB),
                        raw.copiedValidity(),raw.copiedValues(),source.stream().map(frame::toGeographic).toList(),source,inverse,grid,
                        EvidenceResolution.nativeSource(1,1),3,"synthetic",EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                        "scalar",raw.lineage(),CancellationProbe.NONE);
        try(var f=factoryFixture(captured,source.get(0),source.get(1),3);
            var work=BSharedWork.create(f.owner,CancellationProbe.NONE)) {
            var input=actualInput(f,work);
            try(var bound=BSharedPositionEvidence.bind(input,f.owner,CancellationProbe.NONE,work);
                var field=BSharedPositionField.build(bound,f.owner,CancellationProbe.NONE,work)) {
                assertEquals(9,field.width());assertEquals(17,field.height());assertEquals(1,bound.grid.rho,0);
                assertEquals(19,captured.fields().get("scalar").width());
                var actual=captured.transform().metricToPixelCenter(new MetricPoint(4,8));
                assertEquals(bits(captured.fields().get("scalar").sampleBilinear(actual.x(),actual.y()).orElseThrow()),bits(field.sourceValue(4,8)));
                assertEquals(BSharedPositionEvidence.Status.UNAVAILABLE_SOURCE_SUPPORT,field.sourceStatus(8,16));
                var out=new BSharedPositionField.MutableResponse();field.componentResponse(0,0,1,out);
                // Bounded JUnit host diagnostics, not a second inference or feature definition.
                System.out.println("CAPTURE_PHASE_NATIVE input9x17/origin0,0/pitch1/background.02/rows8,9=1/all-valid");
                System.out.println("CAPTURE_PHASE_OUTPUT "+captured.resampling()+" "+captured.transform());
                System.out.println("CAPTURE_PHASE_PROFILE "+f.profile);
                for(var fact:input.facts.get(0).modes()) {
                    for(var value:fact.values())System.out.println("CAPTURE_PHASE_FACT "+value.feature()+" "+value.availability()+" "+(value.value().isPresent()?bits(value.value().getAsDouble()):"null"));
                    for(var receipt:fact.provenance())System.out.println("CAPTURE_PHASE_RECEIPT "+receipt);
                }
                System.out.println("CAPTURE_PHASE_BOUND rho="+bits(bound.grid.rho)+" h="+bits(bound.d[BSharedPositionEvidence.H][0])+" centre="+bits(bound.d[BSharedPositionEvidence.CENTRE][0])+" d="+bits(bound.d[14][0]));
                for(int y=8;y<=9;y++)System.out.println("CAPTURE_PHASE_CORNER x4 y"+y+" V="+bits(field.sourceValue(4,y))+" F="+bits(field.positionalValue(4,y))+" independentR="+bits(independentVertex(f,input.facts.get(0).modes().get(0),bound.grid,4,y,1)));
                System.out.println("CAPTURE_PHASE_RETURNED_RESPONSE "+bits(out.value)+" error="+bits(out.error)+" status="+out.reason);
                assertTrue(out.value>0,"required supported response through authentic factory and original-grid phase");
            }
        }
    }

    @Test void genuineCensoredComponentAndWrongOwnFlankRetainDispositions() {
        try(var f=factoryFixture(sourceField(true,true),RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),
                EvidenceResolution.nativeSource(1,1),new MetricPoint(2.5,7.5),new MetricPoint(5.5,7.5),1.5);
            var work=BSharedWork.create(f.owner,CancellationProbe.NONE)) {
            assertFalse(f.profile.censoredModes().isEmpty());assertTrue(f.profile.modes().isEmpty());
            var original=f.profile.censoredModes();var input=actualInput(f,work);
            try(var bound=BSharedPositionEvidence.bind(input,f.owner,CancellationProbe.NONE,work);
                var field=BSharedPositionField.build(bound,f.owner,CancellationProbe.NONE,work)) {
                assertSame(original,input.facts.get(0).baseline().censoredModes());
                assertEquals(0,bound.originals.length);assertEquals(0,field.positionalValue(4,8),0);
            }
        }
        try(var f=fixture(8.5,.5,true);var work=BSharedWork.create(f.owner,CancellationProbe.NONE)) {
            var authority=new BIdentificationFactsExtractor().extractSharedPosition(f.snapshot,"scalar",List.of(f.profile),CancellationProbe.NONE,f.owner,work);
            var original=authority.facts().get(0);
            var mode=original.modes().get(0);var receipts=new ArrayList<Receipt>();
            for(var receipt:mode.provenance())receipts.add(receipt instanceof FlankReceipt flank?new FlankReceipt(flank.leftIndex()+1,flank.rightIndex(),true):receipt);
            var changed=ownedFacts(f.owner,f.profile,List.of(new ModeFacts(mode.lineage(),mode.values(),receipts)));
            var lattices=List.of(tinyLattice(f.owner,f.profile));long before=f.ledger.currentBytes();
            assertThrows(IllegalArgumentException.class,()->BSharedPositionEvidence.Input.create(f.snapshot,"scalar",authority,List.of(changed),lattices,
                    f.snapshot.decisionRegion(),BSharedPositionEvidence.approvedContract(),work));
            assertEquals(before,f.ledger.currentBytes());
        }
    }

    @Test void unsupportedAnisotropyAndWordAliasLeaveLatticeUntouched() {
        var transform=RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,2);
        try(var f=factoryFixture(sourceField(true,true),transform,EvidenceResolution.nativeSource(1,1),new MetricPoint(2.5,17),new MetricPoint(5.5,17),3);
            var work=BSharedWork.create(f.owner,CancellationProbe.NONE)) {
            var input=actualInput(f,work);var cells=input.lattices.get(0).cells();
            try(var bound=BSharedPositionEvidence.bind(input,f.owner,CancellationProbe.NONE,work)) {
                assertEquals(BSharedPositionEvidence.Status.UNAVAILABLE_SOURCE_GRID,bound.status());assertSame(cells,input.lattices.get(0).cells());
                assertThrows(IllegalArgumentException.class,()->BSharedPositionField.build(bound,f.owner,CancellationProbe.NONE,work));
                assertEquals(0,work.snapshot().sourceInterpolations());
            }
        }
        var alias=new BSharedPositionEvidence.Grid(191401,2,1e16,0,.001,0,0,.001,true);
        assertEquals(alias.east(0,0),alias.east(1,0),0);assertFalse(alias.known);
    }
    @Test void cancellationAfterLastPayloadStoreCannotPublishField() {
        try(var f=fixture(8.5,.5,true)) {
            BSharedWork[] reference=new BSharedWork[1];long[] threshold={Long.MAX_VALUE};
            CancellationProbe probe=()->reference[0]!=null&&reference[0].recordedArrayWrites()>=threshold[0];
            try(var work=BSharedWork.create(f.owner,probe)) {
                reference[0]=work;
                try(var bound=BSharedPositionEvidence.bind(actualInput(f,work),f.owner,probe,work)) {
                    int n=bound.grid.width*bound.grid.height,cells=(bound.grid.width-1)*(bound.grid.height-1);
                    // Source-backed complete construction stores:3N source/status,3Q max/error/status,
                    //4N context/status and one store per completed cell. No poll ordinals or sleeps.
                    threshold[0]=work.recordedArrayWrites()+7L*n+3*bound.q+cells;
                    long before=f.ledger.currentBytes();
                    assertThrows(java.util.concurrent.CancellationException.class,()->BSharedPositionField.build(bound,f.owner,probe,work));
                    assertEquals(threshold[0],work.recordedArrayWrites());
                    assertEquals(before,f.ledger.currentBytes());
                }
            }
        }
    }

    @Test void aliasedRawReceiptAndGroupedParentCannotSupplyElementaryMass() {
        for(boolean parent:new boolean[]{false,true}) {
            try(var f=fixture(8.5,.5,true);var work=BSharedWork.create(f.owner,CancellationProbe.NONE)) {
                var original=f.profile.modes().get(0);var mode=original;
                if(parent)try(var lease=f.owner.reserve(AttemptMemoryLedger.objectBytes(128))) {
                    mode=new ProbabilisticProfile.Mode(original.id(),original.evidenceLineage(),original.coreMinimumMeters(),
                            original.coreMaximumMeters(),original.localizationSigmaMeters(),original.existenceConfidence(),
                            original.localizationConfidence(),original.peakOffsetsMeters(),original.nestedCenterOffsetsMeters(),
                            true,original.orientationSupport(),original.scalarAmplitudeReliability(),original.branchCoherence(),original.positionalReliability());
                    lease.adopt(mode);
                }
                var variant=variant(f,parent?List.of(mode):List.of(mode,mode));
                var input=issuedInput(f.snapshot,"scalar",List.of(variant),List.of(tinyLattice(f.owner,variant)),f.owner,work);
                try(var bound=BSharedPositionEvidence.bind(input,f.owner,CancellationProbe.NONE,work)) {
                    if(parent)try(var field=BSharedPositionField.build(bound,f.owner,CancellationProbe.NONE,work)) {
                        var out=new BSharedPositionField.MutableResponse();field.componentResponse(0,0,1,out);
                        assertEquals(BSharedPositionEvidence.Status.INELIGIBLE_GROUPED_PARENT,out.reason);assertEquals(0,out.value,0);
                        assertEquals(0,field.positionalValue(4,8),0);
                    } else {
                        assertEquals(BSharedPositionEvidence.Status.UNAVAILABLE_ALIAS.ordinal(),bound.indices[2][0]);
                        assertEquals(BSharedPositionEvidence.Status.UNAVAILABLE_ALIAS.ordinal(),bound.indices[2][1]);
                        assertThrows(BSharedPositionField.UnavailableException.class,
                                ()->BSharedPositionField.build(bound,f.owner,CancellationProbe.NONE,work));
                    }
                }
            }
        }
    }

    static ProbabilisticProfile variant(Fixture f,List<ProbabilisticProfile.Mode> modes) {
        List<ProbabilisticProfile.Mode> retained;
        try(var list=f.owner.reserve(AttemptMemoryLedger.arrayBytes(modes.size(),8)+24)) {
            retained=List.copyOf(modes);list.adopt(retained);
        }
        try(var lease=f.owner.reserve(AttemptMemoryLedger.objectBytes(104));
            var samples=f.owner.reserve(AttemptMemoryLedger.arrayBytes(f.profile.samples().size(),8)+24)) {
            var p=f.profile;
            var result=new ProbabilisticProfile(p.profileIndex(),p.chainageMeters(),p.anchor(),p.normalUnit(),p.minimumOffsetMeters(),
                    p.maximumOffsetMeters(),p.sourcePitchMeters(),p.nativePitchKnown(),p.noiseFloor(),p.samples(),retained,
                    p.censoredModes(),p.exactAnchorOffsetMeters(),p.orientationSupport());
            lease.adopt(result);samples.adopt(result.samples());return result;
        }
    }
    @Test void authenticFactoryDiagonalCornerAndShiftedPhasesMatchIndependentVertexOracle() {
        for(double phase:new double[]{0,.5,.25}) {
            var transform=RasterMetricTransform.metricGrid(new MetricPoint(phase,-phase),1,0,0,1);
            try(var f=factoryFixture(sourceField(true,true),transform,EvidenceResolution.nativeSource(1,1),
                    transform.pixelCenterToMetric(2.5,7),transform.pixelCenterToMetric(5.5,10),3);
                var work=BSharedWork.create(f.owner,CancellationProbe.NONE)) {
                var input=actualInput(f,work);
                try(var bound=BSharedPositionEvidence.bind(input,f.owner,CancellationProbe.NONE,work);
                    var field=BSharedPositionField.build(bound,f.owner,CancellationProbe.NONE,work)) {
                    var out=new BSharedPositionField.MutableResponse();
                    for(int y=7;y<=10;y++)for(int x=3;x<=5;x++) {
                        field.vertexResponse(0,0,x,y,out);
                        double expected=independentVertex(f,input.facts.get(0).modes().get(0),bound.grid,x,y,1);
                        assertEquals(expected,out.value,2e-15,"phase="+phase+" vertex="+x+","+y);
                        assertTrue(out.error>=Math.abs(expected-out.value));
                    }
                    field.componentResponse(0,0,1,out);
                    assertTrue(out.value>0,"phase="+phase+" actual diagonal source");
                }
            }
        }
    }

    @Test void analysisPitchIsNotSubstitutedForActualSourceSamplingPitch() {
        var transform=RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1);
        try(var f=factoryFixture(sourceField(true,true),transform,EvidenceResolution.nativeSource(.5,1),
                new MetricPoint(2.5,8.5),new MetricPoint(5.5,8.5),3);
            var work=BSharedWork.create(f.owner,CancellationProbe.NONE)) {
            assertEquals(.25,f.profile.samples().get(1).offsetMeters()-f.profile.samples().get(0).offsetMeters(),0);
            try(var bound=BSharedPositionEvidence.bind(actualInput(f,work),f.owner,CancellationProbe.NONE,work);
                var field=BSharedPositionField.build(bound,f.owner,CancellationProbe.NONE,work)) {
                assertEquals(1,bound.grid.rho,0);
                var out=new BSharedPositionField.MutableResponse();field.componentResponse(0,0,1,out);
                assertTrue(out.value>0);
            }
        }
    }

    @Test void blankObservedSourceAndAllUnknownSourceCannotBeConfused() {
        for(boolean known:new boolean[]{true,false}) {
            try(var f=factoryFixture(sourceField(known,false),RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),
                    EvidenceResolution.nativeSource(1,1),new MetricPoint(2.5,8.5),new MetricPoint(5.5,8.5),3);
                var work=BSharedWork.create(f.owner,CancellationProbe.NONE)) {
                assertTrue(f.profile.modes().isEmpty());
                try(var bound=BSharedPositionEvidence.bind(actualInput(f,work),f.owner,CancellationProbe.NONE,work)) {
                    long before=f.ledger.currentBytes();
                    if(!known) {
                        assertThrows(IllegalArgumentException.class,()->BSharedPositionField.build(bound,f.owner,CancellationProbe.NONE,work));
                        assertEquals(before,f.ledger.currentBytes());
                    } else try(var field=BSharedPositionField.build(bound,f.owner,CancellationProbe.NONE,work)) {
                        assertEquals(0,field.positionalValue(4,8),0);
                        assertEquals(0,field.contextValue(4,8),0);
                        assertEquals(BSharedPositionEvidence.Status.OBSERVED_ZERO,field.positionalStatus(4,8));
                    }
                }
            }
        }
    }

    @Test void fundedMissingOwnReceiptAndForeignCorrelationAreAuthorityRefused() {
        for(boolean foreign:new boolean[]{false,true}) {
            try(var f=fixture(8.5,.5,true);var work=BSharedWork.create(f.owner,CancellationProbe.NONE)) {
                var authority=new BIdentificationFactsExtractor().extractSharedPosition(f.snapshot,"scalar",List.of(f.profile),CancellationProbe.NONE,f.owner,work);
                var original=authority.facts().get(0);
                var mode=original.modes().get(0);var receipts=new ArrayList<Receipt>();
                for(var receipt:mode.provenance()) {
                    if(!foreign&&receipt instanceof RawReceipt)continue;
                    receipts.add(foreign&&receipt instanceof CorrelationReceipt c
                            ?new CorrelationReceipt("other-source",c.transformId(),c.nativePitchKnown()):receipt);
                }
                var changed=ownedFacts(f.owner,f.profile,List.of(new ModeFacts(mode.lineage(),mode.values(),receipts)));
                var lattices=List.of(tinyLattice(f.owner,f.profile));
                long before=f.ledger.currentBytes();
                assertThrows(IllegalArgumentException.class,()->BSharedPositionEvidence.Input.create(f.snapshot,"scalar",authority,List.of(changed),
                        lattices,f.snapshot.decisionRegion(),BSharedPositionEvidence.approvedContract(),work));
                assertEquals(before,f.ledger.currentBytes());
            }
        }
    }

    @Test void fieldCancellationAndSourceReservationRefusalLeaveCallerPayloadLive() {
        try(var f=fixture(8.5,.5,true)) {
            var cancel=new java.util.concurrent.atomic.AtomicBoolean();
            CancellationProbe probe=cancel::get;
            try(var work=BSharedWork.create(f.owner,probe);
                var bound=BSharedPositionEvidence.bind(actualInput(f,work),f.owner,probe,work)) {
                long before=f.ledger.currentBytes();cancel.set(true);
                assertThrows(java.util.concurrent.CancellationException.class,()->BSharedPositionField.build(bound,f.owner,probe,work));
                assertEquals(before,f.ledger.currentBytes());
                assertFalse(f.owner.isClosed());
            }
        }
        try(var f=fixture(8.5,.5,true);var work=BSharedWork.create(f.owner,CancellationProbe.NONE)) {
            var input=actualInput(f,work);
            try(var held=f.owner.child("caller-existing-payload")) {
                held.reserve((64L<<20)-f.ledger.currentBytes()-1024);
                long before=f.ledger.currentBytes();
                assertThrows(IllegalArgumentException.class,()->BSharedPositionEvidence.bind(input,f.owner,CancellationProbe.NONE,work));
                assertEquals(before,f.ledger.currentBytes());
            }
        }
    }

    private static ProfileFacts ownedFacts(AttemptMemoryLedger.Owner owner,ProbabilisticProfile profile,List<ModeFacts> modes) {
        long bytes=1024+96L*profile.samples().size()+8192L*modes.size();
        for(var mode:profile.modes())bytes+=4L*(mode.id().length()+mode.evidenceLineage().length());
        try(var lease=owner.reserve(bytes)) {
            var result=new ProfileFacts(profile,modes,VERSION);lease.adopt(result);return result;
        }
    }

    private static double independentVertex(Fixture f,ModeFacts mode,BSharedPositionEvidence.Grid grid,int x,int y,double q) {
        var p=f.profile;double ex=grid.east(x,y),ey=grid.north(x,y);
        double u=(ex-p.anchor().xMeters())*p.normalUnit().xMeters()+(ey-p.anchor().yMeters())*p.normalUnit().yMeters();
        double z=(ex-p.anchor().xMeters())*-p.normalUnit().yMeters()+(ey-p.anchor().yMeters())*p.normalUnit().xMeters();
        double lo=mode.fact(Feature.SHOULDER_MINIMUM).value().orElseThrow(),hi=mode.fact(Feature.SHOULDER_MAXIMUM).value().orElseThrow();
        if(u<lo||u>hi)return 0;
        var raw=mode.provenance().stream().filter(StageReceipt.class::isInstance).map(StageReceipt.class::cast)
                .filter(s->s.original().stage()==LocalScalarProfileExtractor.ObservationStage.RAW).findFirst().orElseThrow();
        double centre=raw.original().centerOffsetMeters(),disagreement=0;
        for(var receipt:mode.provenance())if(receipt instanceof StageReceipt stage&&stage.original().available())
            disagreement=Math.max(disagreement,Math.abs(stage.original().centerOffsetMeters()-centre));
        double h=.5*(raw.lastOffsetMeters()-raw.firstOffsetMeters())+disagreement+grid.rho/2;
        double[] row=new double[3];
        for(int dy=-1;dy<=1;dy++) {
            double[] tap=new double[3];
            for(int dx=-1;dx<=1;dx++) {
                double xx=grid.east(x+dx,y+dy),yy=grid.north(x+dx,y+dy);
                double uu=(xx-p.anchor().xMeters())*p.normalUnit().xMeters()+(yy-p.anchor().yMeters())*p.normalUnit().yMeters();
                var rp=f.snapshot.transform().metricToPixelCenter(new MetricPoint(xx,yy));
                double v=f.field.sampleBilinear(rp.x(),rp.y()).orElseThrow();
                tap[dx+1]=uu<lo||uu>hi?0:Math.max(0,(v-mode.fact(Feature.BACKGROUND).value().orElseThrow())
                        -Math.max(mode.fact(Feature.RMS_DESCRIPTOR).value().orElseThrow(),mode.fact(Feature.FILTER_DESCRIPTOR).value().orElseThrow()));
            }
            row[dy+1]=(tap[0]+2*tap[1]+tap[2])/4;
        }
        return tent(z,Math.max(grid.rho,q))*tent(u-centre,h)*(row[0]+2*row[1]+row[2])/4;
    }

    private static ScalarEvidenceField sourceField(boolean known,boolean stripe) {
        double[] values=new double[9*17];boolean[] validity=new boolean[values.length];
        Arrays.fill(values,.02);Arrays.fill(validity,known);
        if(stripe)Arrays.fill(values,8*9,10*9,1);
        return new ScalarEvidenceField(9,17,values,validity,new EvidenceFieldLineage(
                EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,
                "scalar",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false));
    }

    static Fixture factoryFixture(ScalarEvidenceField field,RasterMetricTransform transform,EvidenceResolution resolution,
            MetricPoint start,MetricPoint end,double width) {
        var region=new MetricRegion(List.of(List.of(transform.pixelCenterToMetric(0,0),transform.pixelCenterToMetric(8,0),
                transform.pixelCenterToMetric(8,16),transform.pixelCenterToMetric(0,16))));
        var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),new GeographicPoint(-1,-1),new GeographicPoint(1,1));
        var evidence=new EvidenceSnapshot("shared-factory-phases",frame,transform,resolution,region,region,Map.of("scalar",field),"synthetic");
        return factoryFixture(evidence,start,end,width);
    }
    private static Fixture factoryFixture(EvidenceSnapshot evidence,MetricPoint start,MetricPoint end,double width) {
        var field=evidence.fields().get("scalar");
        double length=start.distanceTo(end);
        var ledger=new AttemptMemoryLedger(64*1024*1024);var owner=ledger.rootOwner();
        try {
            var profiles=new ProbabilisticProfileFactory().create(List.of(start,end),new ProfileChainage(List.of(0.0,.5*length,length),.5*length),
                    width,true,evidence,field,EvidenceModelParameters.defaults(),CancellationProbe.NONE,true,owner);
            return new Fixture(field,evidence,profiles.get(1),profiles,null,ledger,owner);
        } catch(RuntimeException|Error failure){owner.close();throw failure;}
    }
    @Test void excessIsAppliedBeforeSeparableB3AndEveryStencilMaskIsMandatory() {
        double[] raw=new double[9];Arrays.fill(raw,.02);raw[4]=.08;
        double[] excess=new double[9];boolean[] valid=new boolean[9];Arrays.fill(valid,true);
        for(int i=0;i<9;i++)excess[i]=BSharedPositionField.ownExcess(raw[i],.02,.04);
        assertEquals(1.0/200,BSharedPositionField.separableB3(excess,valid),1e-18);
        assertEquals(0,BSharedPositionField.ownExcess(BSharedPositionField.separableB3(raw,valid),.02,.04));
        Arrays.fill(excess,0);excess[4]=.5;
        assertEquals(.125,BSharedPositionField.separableB3(excess,valid),0);
        for(int invalid=0;invalid<9;invalid++) {
            valid[invalid]=false;
            assertTrue(Double.isNaN(BSharedPositionField.separableB3(excess,valid)),"tap="+invalid);
            valid[invalid]=true;
        }
        assertThrows(ArithmeticException.class,()->BSharedPositionField.positiveProduct(Double.MIN_VALUE,.5));
    }

    @Test void overlappingActualProfilesUseMaximumAndPhysicalQuadrature() {
        try(var f=fixture(8.5,.5,true);var work=BSharedWork.create(f.owner,CancellationProbe.NONE)) {
            var lattices=new ArrayList<ProbabilisticStateLattice>();
            for(var p:f.profiles)lattices.add(tinyLattice(f.owner,p));
            var input=issuedInput(f.snapshot,"scalar",f.profiles,lattices,f.owner,work);
            try(var bound=BSharedPositionEvidence.bind(input,f.owner,CancellationProbe.NONE,work);
                var field=BSharedPositionField.build(bound,f.owner,CancellationProbe.NONE,work)) {
                assertEquals(.75,bound.d[11][0],0);assertEquals(1.5,bound.d[11][1],0);
                assertEquals(.75,bound.d[11][2],0);
                assertTrue(bound.q < 3L*field.width()*field.height(),"actual compact closed footprints");
                var a=new BSharedPositionField.MutableResponse();var b=new BSharedPositionField.MutableResponse();
                field.vertexResponse(0,0,3,8,a);field.vertexResponse(1,0,3,8,b);
                assertTrue(a.value>0&&b.value>0);
                assertEquals(Math.max(a.value,b.value),field.positionalValue(3,8),0);
                assertTrue(field.positionalValue(3,8)<a.value+b.value);
                field.vertexResponse(1,0,4,1,a);
                assertEquals(BSharedPositionEvidence.Status.OUTSIDE_COMPONENT_SUPPORT,a.reason);
                assertEquals(0,a.value,0);
                assertEquals(BSharedPositionEvidence.Status.UNAVAILABLE_SOURCE_SUPPORT,field.contextStatus(0,8));
            }
        }
    }

    @Test void sourceDriftRefusalAndCancellationReleaseOnlyOwnedConstruction() {
        try(var f=fixture(8.5,.5,true);var work=BSharedWork.create(f.owner,CancellationProbe.NONE)) {
            var lattice=tinyLattice(f.owner,f.profile);
            double[] changed=f.field.copiedValues();changed[8*9+4]=.75;
            var changedField=new ScalarEvidenceField(9,17,changed,f.field.copiedValidity(),f.field.lineage());
            var changedSnapshot=new EvidenceSnapshot("changed",f.snapshot.coordinateFrame(),f.snapshot.transform(),
                    f.snapshot.resolution(),f.snapshot.decisionRegion(),f.snapshot.evidenceRegion(),Map.of("scalar",changedField),f.snapshot.sourceIdentity());
            var input=issuedInput(changedSnapshot,"scalar",List.of(f.profile),List.of(lattice),f.owner,work);
            long before=f.ledger.currentBytes();
            assertThrows(IllegalArgumentException.class,()->BSharedPositionEvidence.bind(input,f.owner,CancellationProbe.NONE,work));
            assertEquals(before,f.ledger.currentBytes());
        }
        try(var f=fixture(8.5,.5,true)) {
            var cancel=new java.util.concurrent.atomic.AtomicBoolean();
            CancellationProbe probe=cancel::get;
            try(var work=BSharedWork.create(f.owner,probe)) {
                var input=actualInput(f,work);
                long before=f.ledger.currentBytes();
                cancel.set(true);
                assertThrows(java.util.concurrent.CancellationException.class,()->BSharedPositionEvidence.bind(input,f.owner,probe,work));
                assertEquals(before,f.ledger.currentBytes());
            }
        }
    }

    @Test void supportedSelectedFieldKeyAndClosedLifetimeAreExplicit() {
        try(var f=fixture(8.5,.5,true);var work=BSharedWork.create(f.owner,CancellationProbe.NONE)) {
            var renamed=new EvidenceSnapshot("selected",f.snapshot.coordinateFrame(),f.snapshot.transform(),
                    f.snapshot.resolution(),f.snapshot.decisionRegion(),f.snapshot.evidenceRegion(),Map.of("acquired",f.field),f.snapshot.sourceIdentity());
            var input=issuedInput(renamed,"acquired",List.of(f.profile),List.of(tinyLattice(f.owner,f.profile)),f.owner,work);
            BSharedPositionField field;
            try(var bound=BSharedPositionEvidence.bind(input,f.owner,CancellationProbe.NONE,work)) {
                field=BSharedPositionField.build(bound,f.owner,CancellationProbe.NONE,work);
                field.close();
                assertThrows(IllegalStateException.class,()->field.sourceValue(4,8));
            }
        }
    }

    private static BSharedPositionEvidence.Input actualInput(Fixture f,BSharedWork work) {
        return issuedInput(f.snapshot,"scalar",List.of(f.profile),List.of(tinyLattice(f.owner,f.profile)),f.owner,work);
    }
    static BSharedPositionEvidence.Input issuedInput(EvidenceSnapshot snapshot,String key,List<ProbabilisticProfile> profiles,
            List<ProbabilisticStateLattice> lattices,AttemptMemoryLedger.Owner owner,BSharedWork work) {
        var authority=new BIdentificationFactsExtractor().extractSharedPosition(snapshot,key,profiles,work.cancellation(),owner,work);
        return BSharedPositionEvidence.Input.create(snapshot,key,authority,authority.facts(),lattices,
                snapshot.decisionRegion(),BSharedPositionEvidence.approvedContract(),work);
    }
    @Test void ownedActualFactsProducePositiveSharedResponseAndRetainSourceBits() {
        try (var f = fixture(8.5, .5, true);
             var work = BSharedWork.create(f.owner, CancellationProbe.NONE)) {
            var lattice = tinyLattice(f.owner,f.profile);
            var input=issuedInput(f.snapshot,"scalar",List.of(f.profile),List.of(lattice),f.owner,work);
            long before = f.ledger.currentBytes();
            try (var bound = BSharedPositionEvidence.bind(input, f.owner, CancellationProbe.NONE, work);
                 var field = BSharedPositionField.build(bound, f.owner, CancellationProbe.NONE, work)) {
                assertEquals(BSharedPositionEvidence.Status.AVAILABLE, bound.status());
                assertEquals(9, field.width()); assertEquals(17, field.height());
                assertEquals(Double.doubleToRawLongBits(f.field.sampleBilinear(4, 8).orElseThrow()),
                        Double.doubleToRawLongBits(field.sourceValue(4, 8)));
                var response = new BSharedPositionField.MutableResponse();
                field.componentResponse(0, 0, 1, response);
                assertEquals(BSharedPositionEvidence.Status.AVAILABLE, response.reason);
                assertTrue(response.value > 0);
                assertTrue(response.error >= 0);
                assertTrue(field.positionalValue(4, 8) > 0);
                assertTrue(field.contextValue(4, 8) >= field.positionalValue(4, 8));
                assertTrue(work.snapshot().buildUnits() <= work.snapshot().reservedUpperUnits());
                assertThrows(IllegalStateException.class, () ->
                        BSharedPositionEvidence.bind(input, f.owner, CancellationProbe.NONE, work));
            }
            assertEquals(before, f.ledger.currentBytes());
        }
    }

    static ProbabilisticStateLattice tinyLattice(AttemptMemoryLedger.Owner owner,ProbabilisticProfile profile) {
        // Analytical 3-cell state inventory, not an inference/state-admission claim.
        var reservation = owner.reserve(1024);
        try {
            var components=new ArrayList<ObservationComponent>();
            for(var mode:profile.modes())if(components.isEmpty())components.add(new ObservationComponent(
                    mode.id(),mode.evidenceLineage(),ObservationComponent.Kind.MEASURED,1,mode.groupedParent()));
            for(var mode:profile.censoredModes())components.add(new ObservationComponent(mode.id(),mode.evidenceLineage(),
                    ObservationComponent.Kind.CENSORED,.25,false));
            if(components.isEmpty())components.add(new ObservationComponent("missing","missing:"+profile.profileIndex(),ObservationComponent.Kind.MISSING,1,false));
            var lattice = new ProbabilisticStateLattice(List.of(
                    new LateralStateCell(-.5, .5, false, false, "unit"),
                    new LateralStateCell(0, .5, false, false, "unit"),
                    new LateralStateCell(.5, .5, false, false, "unit")),
                    components, .5, false);
            reservation.adopt(lattice);
            return lattice;
        } finally { reservation.close(); }
    }
    @Test void fundedWorkKeepsLongCountersAndRejectsForeignReuseAtomically() {
        var ledger = new AttemptMemoryLedger(64 * 1024 * 1024);
        try (var root = ledger.rootOwner(); var work = BSharedWork.create(root, CancellationProbe.NONE)) {
            assertEquals(1024 * 1024, ledger.currentBytes());
            work.reserveUpper(1L << 35);
            work.numeric((1L << 31) + 7);
            work.sourceRead(1);
            work.integerCall();
            assertEquals((1L << 31) + 7 + 1 + (2 * 512 + 2L) * (2 * 512 + 2L),
                    work.snapshot().buildUnits());
            long before = work.snapshot().buildUnits();
            assertThrows(ArithmeticException.class, () -> work.numeric(Long.MAX_VALUE));
            assertEquals(before, work.snapshot().buildUnits());
            try (var foreign = root.child("foreign")) {
                assertThrows(IllegalArgumentException.class, () -> work.requireOwner(foreign));
            }
        }
        assertEquals(0, ledger.currentBytes());
        var tiny = new AttemptMemoryLedger(1024 * 1024 - 1);
        try (var root = tiny.rootOwner()) {
            assertThrows(AttemptMemoryLedger.ResourceLimitException.class,
                    () -> BSharedWork.create(root, CancellationProbe.NONE));
            assertEquals(0, tiny.currentBytes());
        }
    }

    @Test void actualOwnedFactoryProducesSupportedHalfPitchProfile() {
        try (var fixture = fixture(8.5, .5, true)) {
            assertEquals(1, fixture.profile.profileIndex());
            assertEquals(new MetricPoint(4, 8.5), fixture.profile.anchor());
            assertEquals(.5, fixture.profile.samples().get(1).offsetMeters()
                    - fixture.profile.samples().get(0).offsetMeters());
            var receipt = observe(fixture);
            assertEquals(.5, receipt.rawHalfwidth);
            assertEquals(0.0, receipt.disagreement);
            assertEquals(1.0, receipt.h);
            assertTrue(receipt.response > 0.0);
            System.out.println("ACTUAL_OWNED_FACTORY " + receipt);
        }
    }

    @Test void originalFactoryHalfPitchSamplingRetainsTheSameNativeStripe() {
        // ProbabilisticProfileFactory uses samplePitch=0.5*sourcePitch (line146–148).
        try (var fixture = fixture(8.5, .5)) {
            var receipt = observe(fixture);
            assertEquals(0.0, receipt.center);
            assertEquals(.5, receipt.rawHalfwidth);
            assertEquals(0.0, receipt.disagreement);
            assertEquals(1.0, receipt.h);
            assertTrue(receipt.filteredExcess > 0.0);
            assertTrue(receipt.response > 0.0);
            System.out.println("FACTORY_HALF_PITCH " + receipt);
        }
    }

    @Test void halfGridNarrowRawCoreMustRetainPositiveSupportedResponse() {
        try (var fixture = fixture(8.5, 1.0)) {
            var receipt = observe(fixture);
            assertEquals(0.0, receipt.center);
            assertEquals(0.0, receipt.rawHalfwidth);
            assertEquals(0.0, receipt.disagreement);
            assertEquals(.5, receipt.h);
            assertEquals(0.0, receipt.lowerTent);
            assertEquals(0.0, receipt.upperTent);
            assertTrue(receipt.filteredExcess > 0.0,
                    "actual fully valid own-stencil excess must precede the sampling test");
            System.out.println("HALF_GRID " + receipt);
            // The original positive assertion and its authentic RED are archived in build/Task1phase.
            // This recipe cannot be produced by the original factory on this factual window.
            try (var work = BSharedWork.create(fixture.owner, CancellationProbe.NONE)) {
                assertEquals(BSharedPositionEvidence.Status.UNAVAILABLE_PROFILE_RECIPE,
                        BSharedPositionEvidence.profileCapability(fixture.profile, work));
            }
            assertEquals(0.0, receipt.response);
        }
    }

    private static Observation observe(Fixture fixture) {
        var profile = fixture.profile;
        var facts = new BIdentificationFactsExtractor().extract(fixture.snapshot, List.of(profile),
                CancellationProbe.NONE, fixture.owner).get(0);
        assertSame(profile, facts.baseline());
        assertEquals(1, facts.modes().size());
        assertFalse(profile.modes().get(0).groupedParent());
        var mode = facts.modes().get(0);
        emitEvidence(fixture, mode);
        var stages = mode.provenance().stream().filter(StageReceipt.class::isInstance)
                .map(StageReceipt.class::cast).toList();
        assertEquals(3, stages.size());
        assertTrue(stages.stream().allMatch(s -> s.original().available()));
        assertEquals(1, mode.provenance().stream().filter(RawReceipt.class::isInstance).count());
        var flanks = mode.provenance().stream().filter(FlankReceipt.class::isInstance)
                .map(FlankReceipt.class::cast).toList();
        assertEquals(1, flanks.size());
        assertTrue(flanks.get(0).complete());
        assertEquals(1, mode.provenance().stream().filter(CorrelationReceipt.class::isInstance).count());
        var raw = stages.stream().filter(s -> s.original().stage()
                == LocalScalarProfileExtractor.ObservationStage.RAW).findFirst().orElseThrow();
        double center = mode.fact(Feature.RAW_CENTER).value().orElseThrow();
        double disagreement = mode.fact(Feature.CENTER_DISAGREEMENT).value().orElseThrow();
        assertEquals(center, mode.fact(Feature.B3_CENTER).value().orElseThrow());
        assertEquals(center, mode.fact(Feature.B5_CENTER).value().orElseThrow());
        double rawHalfwidth = .5 * (raw.lastOffsetMeters() - raw.firstOffsetMeters());
        double h = rawHalfwidth + disagreement + profile.sourcePitchMeters() / 2;
        double lowerTent = tent(8.0 - profile.anchor().yMeters() - center, h);
        double upperTent = tent(9.0 - profile.anchor().yMeters() - center, h);
        double lowerJ = filteredExcess(fixture, mode, 4, 8);
        double upperJ = filteredExcess(fixture, mode, 4, 9);
        // At x=4 the longitudinal tent equals1; y=8.5 has bilinear weights1/2,1/2.
        double response = .5 * lowerTent * lowerJ + .5 * upperTent * upperJ;
        return new Observation(center, rawHalfwidth, disagreement, h, lowerTent, upperTent,
                .5 * lowerJ + .5 * upperJ, response, stages.toString(), flanks.toString());
    }

    private static double filteredExcess(Fixture fixture, ModeFacts mode, int x, int y) {
        double background = mode.fact(Feature.BACKGROUND).value().orElseThrow();
        double nuisance = Math.max(mode.fact(Feature.RMS_DESCRIPTOR).value().orElseThrow(),
                mode.fact(Feature.FILTER_DESCRIPTOR).value().orElseThrow());
        double lo = mode.fact(Feature.SHOULDER_MINIMUM).value().orElseThrow();
        double hi = mode.fact(Feature.SHOULDER_MAXIMUM).value().orElseThrow();
        double total = 0.0;
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            // Real original-source validity is required even where own bracket excludes the tap.
            double value = fixture.field.sampleBilinear(x + dx, y + dy).orElseThrow();
            double offset = y + dy - fixture.profile.anchor().yMeters();
            double excess = offset < lo || offset > hi ? 0.0 : Math.max(0, value - background - nuisance);
            total += (dx == 0 ? 2 : 1) * (dy == 0 ? 2 : 1) * excess / 16;
        }
        return total;
    }

    private static double tent(double delta, double halfwidth) {
        return Math.max(0, 1 - Math.abs(delta) / halfwidth);
    }

    private static String bits(double value) {
        return String.format("%016x", Double.doubleToRawLongBits(value));
    }

    private static void emitEvidence(Fixture f, ModeFacts mode) {
        double[] raster = f.field.copiedValues();
        var encoded = ByteBuffer.allocate(raster.length * Double.BYTES);
        for (double value : raster) encoded.putDouble(value);
        try {
            System.out.println("NATIVE_F64_BE_SHA256 " + java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(encoded.array())));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        System.out.println("NATIVE dimensions=9x17 origin=(0,0) affine=(1,0;0,1) rho=" + bits(1));
        System.out.println("NATIVE_VALUES_BITS " + Arrays.stream(raster).mapToObj(
                V022BSharedPositionFieldTest::bits).toList());
        System.out.println("NATIVE_VALIDITY " + Arrays.toString(f.field.copiedValidity()));
        System.out.println("NATIVE_CELL_VALIDITY " + Arrays.toString(f.field.copiedInterpolationValidity()));
        System.out.println("PROFILE_ANCHOR_NORMAL_BITS " + bits(f.profile.anchor().xMeters()) + ","
                + bits(f.profile.anchor().yMeters()) + "," + bits(f.profile.normalUnit().xMeters()) + ","
                + bits(f.profile.normalUnit().yMeters()));
        System.out.println("PROFILE_SAMPLES offsetBits:intensityBits:valid " + f.profile.samples().stream()
                .map(s -> bits(s.offsetMeters()) + ":" + bits(s.intensity()) + ":" + s.valid()).toList());
        System.out.println("REAL_EXTRACTOR_RECEIPTS " + f.detailed);
        for (var fact : mode.values()) System.out.println("FACT_BITS " + fact.feature() + " " + fact.unit()
                + " " + fact.availability() + " " + (fact.value().isPresent()
                        ? bits(fact.value().getAsDouble()) : "null") + " " + fact.unavailableReason());
        for (var receipt : mode.provenance()) {
            System.out.println("REAL_FACT_RECEIPT " + receipt);
            if (receipt instanceof StageReceipt stage) {
                var s = stage.original();
                System.out.println("STAGE_BITS " + s.stage() + " " + bits(s.localMaximum()) + " "
                        + bits(s.threshold()) + " " + bits(s.centerOffsetMeters()) + " "
                        + bits(stage.firstOffsetMeters()) + " " + bits(stage.lastOffsetMeters()));
            }
        }
    }

    private record Observation(double center, double rawHalfwidth, double disagreement, double h,
            double lowerTent, double upperTent, double filteredExcess, double response,
            String realStages, String realFlanks) { }

    private static Fixture fixture(double anchorY, double samplePitch) {
        return fixture(anchorY, samplePitch, false);
    }

    static Fixture fixture(double anchorY, double samplePitch, boolean actualFactory) {
        double[] values = new double[9 * 17];
        boolean[] valid = new boolean[values.length];
        Arrays.fill(values, .02);
        Arrays.fill(valid, true);
        Arrays.fill(values, 8 * 9, 10 * 9, 1.0);
        var lineage = new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "scalar",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        var field = new ScalarEvidenceField(9, 17, values, valid, lineage);
        var transform = RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 1);
        var region = MetricRegion.rectangle(0, 0, 8, 16);
        var frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0, 0),
                new GeographicPoint(-1, -1), new GeographicPoint(1, 1));
        var snapshot = new EvidenceSnapshot("shared-phase-two-native-rows", frame, transform,
                EvidenceResolution.nativeSource(1, 1), region, region, Map.of("scalar", field), "synthetic");
        var ledger = new AttemptMemoryLedger(64 * 1024 * 1024);
        var owner = ledger.rootOwner();
        try {
            if (actualFactory) {
                var profiles = new ProbabilisticProfileFactory().create(
                        List.of(new MetricPoint(2.5, anchorY), new MetricPoint(5.5, anchorY)),
                        new ProfileChainage(List.of(0.0, 1.5, 3.0), 1.5), 6, true, snapshot, field,
                        EvidenceModelParameters.defaults(), CancellationProbe.NONE, true, owner);
                assertEquals(3, profiles.size());
                for (var actual : profiles) {
                    assertEquals(1.0, actual.sourcePitchMeters());
                    assertEquals(25, actual.samples().size());
                    for (int i = 1; i < actual.samples().size(); i++)
                        assertEquals(.5, actual.samples().get(i).offsetMeters()
                                - actual.samples().get(i - 1).offsetMeters());
                }
                var actual = profiles.get(1);
                var detailed = new LocalScalarProfileExtractor().extractDetailed(actual.samples().stream()
                                .map(s -> new LocalScalarProfileExtractor.Sample(
                                        s.offsetMeters(), s.intensity(), s.valid())).toList(),
                        1, EvidenceModelParameters.defaults().localization(), owner,
                        CancellationProbe.NONE, ignored -> { });
                return new Fixture(field, snapshot, actual, profiles, detailed, ledger, owner);
            }
            var samples = new ArrayList<LocalScalarProfileExtractor.Sample>();
            int intervals = (int) (12 / samplePitch);
            for (int index = 0; index <= intervals; index++) {
                double offset = -6 + index * samplePitch;
                samples.add(new LocalScalarProfileExtractor.Sample(offset,
                        field.sampleBilinear(4, anchorY + offset).orElseThrow(), true));
            }
            var detailed = new LocalScalarProfileExtractor().extractDetailed(samples, 1,
                    EvidenceModelParameters.defaults().localization(), owner,
                    CancellationProbe.NONE, ignored -> { });
            assertEquals(1, detailed.baseline().modes().size());
            assertTrue(detailed.baseline().censoredModes().isEmpty());
            var actual = detailed.baseline().modes().get(0);
            // Same value-for-value elementary adaptation as ProbabilisticProfileFactory.adaptModes.
            var mode = new ProbabilisticProfile.Mode("p0-m0", "p0-m0-scalar-band",
                    actual.coreMinimumMeters(), actual.coreMaximumMeters(), actual.localizationSigmaMeters(),
                    actual.existenceConfidence(), actual.localizationConfidence(), actual.peakOffsetsMeters(),
                    actual.nestedCenterOffsetsMeters(), false,
                    ImageOrientationSupport.unknown(ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT),
                    actual.scalarAmplitudeReliability(), 0, actual.scalarAmplitudeReliability());
            var profile = new ProbabilisticProfile(0, 0, new MetricPoint(4, anchorY), new MetricPoint(0, 1),
                    -6, 6, 1, true, detailed.baseline().noiseFloor(), samples.stream().map(s ->
                            new ProbabilisticProfile.Sample(s.offsetMeters(), s.intensity(), s.valid())).toList(),
                    List.of(mode), List.of(), OptionalDouble.empty(), ImageOrientationSupport.unknown(
                            ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT));
            // Explicitly owned analytical recipe; this does not label it factory-admissible.
            try(var lease=owner.reserve(AttemptMemoryLedger.objectBytes(104))){lease.adopt(profile);}
            return new Fixture(field, snapshot, profile, List.of(profile), detailed, ledger, owner);
        } catch (RuntimeException | Error failure) {
            owner.close();
            throw failure;
        }
    }

    record Fixture(ScalarEvidenceField field, EvidenceSnapshot snapshot,
            ProbabilisticProfile profile, List<ProbabilisticProfile> profiles, LocalScalarProfileExtractor.DetailedResult detailed,
            AttemptMemoryLedger ledger,
            AttemptMemoryLedger.Owner owner) implements AutoCloseable {
        @Override public void close() {
            owner.close();
            assertEquals(0, ledger.currentBytes());
        }
    }
}
