package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class V022BGapFieldTest {
    @Test void rawStageWinnerAndLocalMaximumMustAgreeWithItsOwnRawReceipt() {
        for(boolean wrongWinner:new boolean[]{false,true})try(var f=BGapTestFixture.standard()) {
            var input=f.changeMode(0,1,mode->{
                var receipts=new java.util.ArrayList<>(mode.provenance());
                var old=(BIdentificationFacts.StageReceipt)receipts.get(2);var stage=old.original();
                var changed=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.NestedCenterReceipt(
                        stage.stage(),true,true,wrongWinner?7:stage.winnerSampleIndex(),wrongWinner?7:stage.firstCoreSampleIndex(),
                        wrongWinner?9:stage.lastCoreSampleIndex(),wrongWinner?stage.localMaximum():.75,stage.threshold(),stage.centerOffsetMeters());
                receipts.set(2,new BIdentificationFacts.StageReceipt(changed,wrongWinner?3:old.firstOffsetMeters(),wrongWinner?5:old.lastOffsetMeters()));
                return new BIdentificationFacts.ModeFacts(mode.lineage(),mode.values(),receipts);
            });
            try(var field=BGapField.build(input,CancellationProbe.NONE,f.owner)) {
                assertEquals(BGapEvidence.Status.UNAVAILABLE_RECEIPT,field.status(0,4));assertEquals(0,field.value(0,4),0);
            }
        }
    }
    @Test void sharedRawPeakOwnershipInvalidatesEveryClaimantWithCountedWork() {
        int[] owners=new int[5],conflicts=new int[3];var work=new BGapEvidence.WorkCounter();
        BGapField.claimRaw(owners,conflicts,0,1,2,work,CancellationProbe.NONE);
        BGapField.claimRaw(owners,conflicts,1,2,3,work,CancellationProbe.NONE);
        BGapField.claimRaw(owners,conflicts,2,2,2,work,CancellationProbe.NONE);
        assertArrayEquals(new int[]{1,1,1},conflicts);assertEquals(5,work.snapshot().cacheReads());
        assertEquals(5,work.snapshot().cancellationChecks());assertEquals(50,work.snapshot().buildUnits());
    }
    @Test void differentModesRawPeakCannotBorrowAnotherModesSample() {
        try(var f=BGapTestFixture.standard()) {
            var input=f.changeMode(0,1,mode->{
                var values=new java.util.ArrayList<>(mode.values());
                for(int i=0;i<values.size();i++)if(values.get(i).feature()==BIdentificationFacts.Feature.PEAK) {
                    var old=values.get(i);values.set(i,new BIdentificationFacts.ScalarFact(old.feature(),old.unit(),old.availability(),java.util.OptionalDouble.of(.75)));
                }
                var receipts=new java.util.ArrayList<>(mode.provenance());receipts.set(0,new BIdentificationFacts.RawReceipt(2,2,2));
                return new BIdentificationFacts.ModeFacts(mode.lineage(),values,receipts);
            });
            try(var field=BGapField.build(input,CancellationProbe.NONE,f.owner)) {
                assertEquals(BGapEvidence.Status.UNAVAILABLE_RECEIPT,field.status(0,4));assertEquals(0,field.value(0,4),0);
            }
        }
    }
    @Test void initialScratchRefusalLeavesExactOriginalOwnerTree() throws Exception {
        try(var f=BGapTestFixture.standard()) {
            long original=f.owner.currentBytes();var originalTree=BGapTestFixture.ownerTree(f.owner);
            try(var filler=f.owner.reserve(BGapEvidence.BYTES-original-799)) {
                assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->BGapField.build(f.input,CancellationProbe.NONE,f.owner));
                assertEquals(BGapEvidence.BYTES-799,f.owner.currentBytes());
                assertEquals(originalTree,BGapTestFixture.ownerTree(f.owner));
            }
            assertEquals(original,f.owner.currentBytes());assertEquals(originalTree,BGapTestFixture.ownerTree(f.owner));
        }
    }
    @Test void absentOrDuplicateOwnSourceReceiptCannotCertifyGapOwnership() {
        for(boolean duplicate:new boolean[]{false,true})try(var f=BGapTestFixture.standard()) {
            var input=f.changeMode(0,0,mode->{var receipts=new java.util.ArrayList<>(mode.provenance());
                var source=receipts.remove(receipts.size()-1);if(duplicate){receipts.add(source);receipts.add(source);}
                return new BIdentificationFacts.ModeFacts(mode.lineage(),mode.values(),receipts);});
            try(var field=BGapField.build(input,CancellationProbe.NONE,f.owner)) {
                assertEquals(BGapEvidence.Status.UNAVAILABLE_RECEIPT,field.status(0,4));
            }
        }
    }
    @Test void ownDeclaredZeroExcessHasExactlyZeroNominalGapWithoutPositiveFloor() {
        try(var f=BGapTestFixture.standard()) {
            var input=f.changeMode(0,0,mode->{var values=new java.util.ArrayList<>(mode.values());
                for(int i=0;i<values.size();i++)if(values.get(i).feature()==BIdentificationFacts.Feature.RMS_DESCRIPTOR) {
                    var old=values.get(i);values.set(i,new BIdentificationFacts.ScalarFact(old.feature(),old.unit(),old.availability(),java.util.OptionalDouble.of(.5)));
                }
                return new BIdentificationFacts.ModeFacts(mode.lineage(),values,mode.provenance());});
            try(var field=BGapField.build(input,CancellationProbe.NONE,f.owner)) {
                assertEquals(BGapEvidence.Status.OBSERVED_NO_GAP,field.status(0,4));assertEquals(0,field.value(0,4),0);
            }
        }
    }
    @Test void curvedJoinedAndFoldedStripsUseActualMetricGeometry() {
        try(var f=new BGapTestFixture(1,(x,y)->BGapTestFixture.observed(y),false,true,3,1,false,false,1);
                var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner)) {
            assertEquals(BGapEvidence.Status.OBSERVED_GAP,field.status(0,4));assertEquals(1.0/32,field.value(0,4),0);
        }
        try(var f=new BGapTestFixture(1,(x,y)->BGapTestFixture.observed(y),false,true,3,1,false,false,2);
                var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner)) {
            assertEquals(BGapEvidence.Status.AMBIGUOUS_STRIP,field.status(1,4));assertEquals(0,field.value(1,4),0);
        }
    }
    @Test void duplicateRawStageAndOneSidedCoreOwnershipCannotBecomeCompleteGap() {
        try(var f=BGapTestFixture.standard()) {
            var input=f.changeMode(0,0,mode->{var receipts=new java.util.ArrayList<>(mode.provenance());
                receipts.add(receipts.get(2));return new BIdentificationFacts.ModeFacts(mode.lineage(),mode.values(),receipts);});
            try(var field=BGapField.build(input,CancellationProbe.NONE,f.owner)) {
                assertEquals(BGapEvidence.Status.UNAVAILABLE_RECEIPT,field.status(0,4));
            }
        }
        try(var f=BGapTestFixture.standard()) {
            var input=f.changeMode(0,0,mode->{var receipts=new java.util.ArrayList<>(mode.provenance());
                receipts.set(1,new BIdentificationFacts.FlankReceipt(2,3,true));
                return new BIdentificationFacts.ModeFacts(mode.lineage(),mode.values(),receipts);});
            try(var field=BGapField.build(input,CancellationProbe.NONE,f.owner)) {
                assertEquals(BGapEvidence.Status.UNAVAILABLE_RECEIPT,field.status(0,4));
            }
        }
    }
    @Test void unalignedInvalidAuthoritativeInteriorIsUnknownAndNeverObservedDark() {
        try(var f=new BGapTestFixture(2,(x,y)->BGapTestFixture.observed(y),true,true,2,1,true,false);
                var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner)) {
            assertEquals(BGapEvidence.Status.UNAVAILABLE_SUPPORT,field.status(0,4));
            assertEquals(0,field.lower(0,4),0);assertEquals(0,field.upper(0,4),0);
        }
    }
    @Test void unalignedSourceMustCoverEveryFineCellNotFourCanonicalCorners() {
        try(var f=new BGapTestFixture(2,(x,y)->x==.25&&y==.25?1:BGapTestFixture.observed(y),false,true,2,1,true,false);
                var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner)) {
            assertEquals(BGapEvidence.Status.OBSERVED_NO_GAP,field.status(0,4));assertEquals(0,field.value(0,4),0);
        }
    }
    @Test void verticalSourceOwnedGeometryUsesSamePhysicalCellFeature() {
        try(var f=new BGapTestFixture(1,(x,y)->BGapTestFixture.observed(y),false,true,2,1,false,true);
                var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner)) {
            assertEquals(BGapEvidence.Status.OBSERVED_GAP,field.status(0,4));assertEquals(1.0/32,field.value(0,4),0);
        }
    }
    @Test void joinedStripSeamCoversWholeCellWithoutDoubleCounting() {
        try(var f=new BGapTestFixture(1,(x,y)->BGapTestFixture.observed(y),false,true,3,1,false,false,3);
                var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner)) {
            assertEquals(BGapEvidence.Status.OBSERVED_GAP,field.status(0,4));assertEquals(1.0/32,field.value(0,4),0);
            assertEquals(BGapEvidence.Status.OBSERVED_GAP,field.status(1,4));
            assertTrue(field.workDetails().bigIntegerApiCalls()>0);
            assertTrue(field.counters().buildUnits()<=field.workDetails().reservedUpperUnits());
        }
    }
    @Test void originalLineClippingRejectsPositiveOverlapButNotContact() {
        var work=new BGapEvidence.WorkCounter();var exact=new BGapExact(512,work,CancellationProbe.NONE);
        double[] contact={0,0,1,0,1,2,0,2, 1,0,2,0,2,2,1,2, 0,0,2,0,2,2,0,2};
        assertTrue(exact.prepare(contact,3));assertFalse(exact.overlap());
        double[] overlap={0,0,1.5,0,1.5,2,0,2, 1,0,2,0,2,2,1,2, 0,0,2,0,2,2,0,2};
        assertTrue(exact.prepare(overlap,3));assertTrue(exact.overlap());
        double[] bend={0,0,2,0,1,2,0,1, -1,-1,3,-1,3,3,-1,3};
        assertTrue(exact.prepare(bend,2));assertEquals(4,exact.clip());assertEquals(2.5,exact.rounded(exact.areaN,exact.areaD),0);
        double[] fold={0,0,2,2,0,2,2,0, -1,-1,3,-1,3,3,-1,3};
        assertFalse(exact.prepare(fold,2));
    }
    @Test void constructionCancellationAndCloseReleaseOnlyNewPayload() throws Exception {
        try(var f=BGapTestFixture.standard()) {
            long original=f.owner.currentBytes();var originalTree=BGapTestFixture.ownerTree(f.owner);int[] calls={0};
            assertThrows(IllegalStateException.class,()->BGapField.build(f.input,()->{if(++calls[0]==100)throw new IllegalStateException("toy cancel");return false;},f.owner));
            assertEquals(original,f.owner.currentBytes());
            assertEquals(originalTree,BGapTestFixture.ownerTree(f.owner));
            var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner);field.close();field.close();
            assertEquals(original,f.owner.currentBytes());assertEquals(0,field.counters().currentOwnedBytes());
            assertEquals(originalTree,BGapTestFixture.ownerTree(f.owner));
            assertThrows(IllegalStateException.class,()->field.value(0,4));
        }
    }
    @Test void ownFlanksOnlyDefineGapStrength() {
        try(var f=BGapTestFixture.standard();var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner)) {
            assertEquals(BGapEvidence.Status.OBSERVED_GAP,field.status(0,4));
            assertEquals(1.0/32,field.value(0,4),0);
            assertTrue(field.lower(0,4)<=1.0/32&&field.upper(0,4)>=1.0/32);
        }
    }
    @Test void missingAndValidDarkHaveDifferentStatuses() {
        try(var f=new BGapTestFixture(1,(x,y)->BGapTestFixture.observed(y),true,true,2,1);
                var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner)) {
            assertEquals(BGapEvidence.Status.UNAVAILABLE_SUPPORT,field.status(0,4));assertEquals(0,field.value(0,4));
        }
    }
    @Test void wholeBilinearCellMustBeBelowOwnThreshold() {
        try(var f=new BGapTestFixture(1,(x,y)->y>=0&&y<=2?.25:BGapTestFixture.observed(y),false,true,2,1);
                var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner)) {
            assertEquals(BGapEvidence.Status.OBSERVED_NO_GAP,field.status(0,4));assertEquals(0,field.value(0,4));
        }
    }
    @Test void coarseCellCannotHideBrightFineInterior() {
        try(var f=new BGapTestFixture(2,(x,y)->x==.5&&y==.5?1:BGapTestFixture.observed(y),false,true,2,1);
                var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner)) {
            assertEquals(BGapEvidence.Status.OBSERVED_NO_GAP,field.status(0,4));assertEquals(0,field.value(0,4));
        }
    }
    @Test void censoredOrAmbiguousFlankCannotInventGap() {
        try(var f=new BGapTestFixture(1,(x,y)->BGapTestFixture.observed(y),false,false,2,1);
                var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner)) {
            assertEquals(BGapEvidence.Status.UNAVAILABLE_RECEIPT,field.status(0,4));assertEquals(0,field.value(0,4));
        }
    }
}
