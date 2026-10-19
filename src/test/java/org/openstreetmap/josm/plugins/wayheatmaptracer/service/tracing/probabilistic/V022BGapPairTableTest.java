package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class V022BGapPairTableTest {
    @Test void etaIntegrationUpperDeltaIsExplicitAndRefusesBeforeArenaClaim() throws Exception {
        assertEquals(1_440_507,BGapPairTable.integrationUpperDelta(81,719040));
        try(var f=BGapTestFixture.standard();var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner)) {
            long upper=field.work.details().reservedUpperUnits();
            try(var table=BGapPairTable.build(field,f.input.profiles(),CancellationProbe.NONE,f.owner)) {
                // N2/P1:64 view setup+2 finite checks+7*(2+3*2) bindings+8*2 dimension pass.
                assertEquals(upper+138,field.work.details().reservedUpperUnits());
            }
        }
        try(var f=BGapTestFixture.standard();var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner)) {
            field.work.reserveUpper(BGapEvidence.BUILD_UNITS);
            long before=f.owner.currentBytes();var owners=BGapTestFixture.ownerTree(f.owner);
            assertThrows(IllegalArgumentException.class,()->BGapPairTable.build(field,f.input.profiles(),CancellationProbe.NONE,f.owner));
            assertEquals(before,f.owner.currentBytes());assertEquals(owners,BGapTestFixture.ownerTree(f.owner));
        }
    }
    @Test void exteriorAndOuterContactReasonsAreExactForPositiveLengthOnly() {
        try(var f=BGapTestFixture.standard();var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner);
                var integral=BGapPairTable.prefix(field,CancellationProbe.NONE,f.owner)) {
            int outside=1<<BGapEvidence.Status.UNAVAILABLE_SOURCE_GRID.ordinal();
            var right=integral.integral(new MetricPoint(3,.5),new MetricPoint(4,.5));
            var boundary=integral.integral(new MetricPoint(1,-4),new MetricPoint(1,6));
            int expected=0;double known=0;
            for(int y=0;y<field.grid.height-1;y++)if(field.status(1,y).ordinal()>=2)expected|=1<<field.status(1,y).ordinal();else known++;
            final int mask=expected;final double observed=known;
            assertAll(
                ()->assertEquals(outside,right.unavailableReasons()),
                ()->assertEquals(1,right.unavailableMeters(),0),
                ()->assertEquals(0,right.observedMeters(),0),
                ()->assertEquals(right,integral.integral(new MetricPoint(4,.5),new MetricPoint(3,.5))),
                ()->assertEquals(mask,boundary.unavailableReasons()),
                ()->assertEquals(observed,boundary.observedMeters(),1e-12),
                ()->assertEquals(10-observed,boundary.unavailableMeters(),1e-12),
                ()->assertEquals(boundary,integral.integral(new MetricPoint(1,6),new MetricPoint(1,-4))));
            assertEquals(outside,integral.integral(new MetricPoint(-2,.5),new MetricPoint(-1,.5)).unavailableReasons());
        }
    }
    @Test void repeatedConsumedTableClaimLeavesExistingOwnersAndTableUntouched() throws Exception {
        try(var f=BGapTestFixture.standard();var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner);
                var table=BGapPairTable.build(field,f.input.profiles(),CancellationProbe.NONE,f.owner)) {
            var originalTree=BGapTestFixture.ownerTree(f.owner);long original=f.owner.currentBytes();
            assertThrows(IllegalStateException.class,()->BGapPairTable.build(field,f.input.profiles(),CancellationProbe.NONE,f.owner));
            assertEquals(original,f.owner.currentBytes());assertEquals(originalTree,BGapTestFixture.ownerTree(f.owner));
            assertEquals(1.0/16,table.value(0,0,0),0);
        }
    }
    @Test void unequalWidthCompleteInventoryAndHotLookupsNeverReadRasterOrTriples() {
        try(var f=new BGapTestFixture(1,(x,y)->BGapTestFixture.observed(y),false,true,3,1,false,false,0,new int[]{1,3,2});
                var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner);
                var table=BGapPairTable.build(field,f.input.profiles(),CancellationProbe.NONE,f.owner)) {
            assertEquals(9,table.counters().pairWrites());
            assertEquals(1.0/32,table.value(0,0,0),0);
            assertEquals(StrictMath.hypot(1,.5)/32,table.value(1,1,0),table.entry(1,1,0).error());
            var before=table.counters();
            for(int i=0;i<1000;i++)assertEquals(1.0/32,table.value(0,0,0),0);
            var after=table.counters();assertEquals(before.sourceElements(),after.sourceElements());
            assertEquals(before.stripPredicates(),after.stripPredicates());assertEquals(before.maskReads(),after.maskReads());
            assertEquals(before.prefixOperations(),after.prefixOperations());assertEquals(before.buildUnits(),after.buildUnits());
            assertEquals(before.solverLookups()+1000,after.solverLookups());
        }
    }
    @Test void publicBoundAndSmallPayloadLayoutAreIndependentCheckedCensus() {
        assertEquals(78_320_919_079_993_196L,BGapField.buildBound(81,8192,401,47601,47970,8192,5062656,3,482,719040,3595200));
        try(var f=BGapTestFixture.standard()) {
            long original=f.owner.currentBytes();
            try(var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner)) {
                // align8(16+1*8)=24; two stage arrays48, two row-int arrays48,
                // two row references32, J/observed/reasons72, wrappers784:
                // table112+arena16+counter168+report128+owner64+7*view32+array72.
                assertEquals(984,BGapPairTable.tableBytes(f.input.profiles()));
                //20 cell doubles176 +20 status bytes40 + wrappers512.
                assertEquals(728,field.retainedBytes);
                assertEquals(1712,field.counters().currentOwnedBytes());
                assertEquals(f.owner.peakBytes()-original,field.counters().peakOwnedBytes());
                System.out.println("gap-toy-field "+field.counters()+" "+field.workDetails());
            }
            assertEquals(original,f.owner.currentBytes());
        }
    }
    @Test void changedProfileIdentityAndExhaustedWorkCannotPublishTable() {
        try(var f=BGapTestFixture.standard();var other=BGapTestFixture.standard();var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner)) {
            long original=f.owner.currentBytes();
            assertThrows(IllegalArgumentException.class,()->BGapPairTable.build(field,other.input.profiles(),CancellationProbe.NONE,f.owner));
            assertEquals(original,f.owner.currentBytes());
            field.work.reserveUpper(field.counters().buildUnits());
            assertThrows(IllegalArgumentException.class,()->BGapPairTable.build(field,f.input.profiles(),CancellationProbe.NONE,f.owner));
            assertEquals(original,f.owner.currentBytes());
        }
    }
    @Test void boundaryOwnershipCornersNearHorizontalAndExteriorHaveIndependentValues() {
        try(var f=BGapTestFixture.standard();var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner);
                var integral=BGapPairTable.prefix(field,CancellationProbe.NONE,f.owner)) {
            // y=-1 is owned by the higher row, whose next vertex is below threshold.
            assertEquals(0,integral.integral(new MetricPoint(0,-1),new MetricPoint(2,-1)).value(),0);
            assertEquals(1.0/16,integral.integral(new MetricPoint(0,0),new MetricPoint(2,0)).value(),0);
            assertEquals(1.0/16,integral.integral(new MetricPoint(2,0),new MetricPoint(0,0)).value(),0);
            assertEquals(1.0/16,integral.integral(new MetricPoint(0,1),new MetricPoint(2,1)).value(),0);
            assertEquals(1.0/16,integral.integral(new MetricPoint(0,Math.nextUp(.5)),new MetricPoint(2,.5)).value(),1e-15);
            var corner=integral.integral(new MetricPoint(0,0),new MetricPoint(2,2));
            assertEquals(StrictMath.hypot(2,2)/32,corner.value(),corner.error());
            var exterior=integral.integral(new MetricPoint(-1,.5),new MetricPoint(3,.5));
            assertEquals(1.0/16,exterior.value(),exterior.error());assertEquals(2,exterior.observedMeters(),1e-14);
            assertEquals(2,exterior.unavailableMeters(),1e-14);assertNotEquals(0,exterior.unavailableReasons());
            var zero=integral.integral(new MetricPoint(0,.5),new MetricPoint(0,.5));
            assertEquals(new BGapPairTable.Integral(0,0,0,0,0),zero);
        }
    }
    @Test void longPrefixBranchAndUnknownLengthsAgreeWithSeparateRowTraversal() {
        try(var f=new BGapTestFixture(1,(x,y)->BGapTestFixture.observed(y),true,true,2,1);
                var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner);
                var integral=BGapPairTable.prefix(field,CancellationProbe.NONE,f.owner)) {
            var actual=integral.integral(new MetricPoint(.5,-2),new MetricPoint(.5,4));
            double sum=0,observed=0;int reasons=0;
            // Independent axis traversal: six complete unit rows, no prefix arithmetic.
            for(int row=2;row<8;row++) {
                sum+=field.value(0,row);
                if(field.status(0,row).ordinal()<2)observed++;else reasons|=1<<field.status(0,row).ordinal();
            }
            assertEquals(sum,actual.value(),actual.error());assertEquals(observed,actual.observedMeters(),1e-12);
            assertEquals(6-observed,actual.unavailableMeters(),1e-12);assertEquals(reasons,actual.unavailableReasons());
            assertEquals(actual,integral.integral(new MetricPoint(.5,4),new MetricPoint(.5,-2)));
        }
    }
    @Test void tableRetainsOnlyPrimitivePayloadAndExactProfilesAfterFieldCloses() {
        try(var f=BGapTestFixture.standard()) {
            long original=f.owner.currentBytes();var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner);
            var table=BGapPairTable.build(field,f.input.profiles(),CancellationProbe.NONE,f.owner);
            long tableBytes=BGapPairTable.tableBytes(f.input.profiles());field.close();
            assertEquals(original+tableBytes,f.owner.currentBytes());assertEquals(1.0/16,table.value(0,0,0),0);
            for(var member:BGapPairTable.class.getDeclaredFields())assertNotEquals(BGapField.class,member.getType());
            table.close();table.close();assertEquals(original,f.owner.currentBytes());
            assertThrows(IllegalStateException.class,()->table.value(0,0,0));
        }
    }
    @Test void cancellationDiscardsWholeTableAndPrefixWithoutChangingOriginalOwners() throws Exception {
        try(var f=BGapTestFixture.standard();var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner)) {
            var expectedTree=BGapTestFixture.ownerTree(f.owner);expectedTree.removeIf(owner->owner!=f.owner&&owner!=field.owner);
            long original=f.owner.currentBytes();int[] calls={0};
            assertThrows(IllegalStateException.class,()->BGapPairTable.build(field,f.input.profiles(),()->{if(++calls[0]==50)throw new IllegalStateException("toy cancel");return false;},f.owner));
            assertEquals(original-BGapPairTable.tableBytes(f.input.profiles()),f.owner.currentBytes());assertEquals(field.retainedBytes,field.counters().currentOwnedBytes());
            assertEquals(expectedTree,BGapTestFixture.ownerTree(f.owner));
        }
    }
    @Test void resourceRefusalAndLateCancellationLeaveNoPartialPublishedArena() throws Exception {
        try(var f=BGapTestFixture.standard();var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner)) {
            long original=f.owner.currentBytes();var originalTree=BGapTestFixture.ownerTree(f.owner);
            try(var blocked=f.owner.reserve(BGapEvidence.BYTES-original-16)) {
                assertThrows(IllegalArgumentException.class,()->BGapPairTable.build(field,f.input.profiles(),CancellationProbe.NONE,f.owner));
            }
            assertEquals(original,f.owner.currentBytes());
            assertEquals(originalTree,BGapTestFixture.ownerTree(f.owner));
            long oldWrites=field.counters().pairWrites();
            assertThrows(IllegalStateException.class,()->BGapPairTable.build(field,f.input.profiles(),()->{
                if(field.counters().pairWrites()>oldWrites)throw new IllegalStateException("late toy cancel");return false;},f.owner));
            assertEquals(original-BGapPairTable.tableBytes(f.input.profiles()),f.owner.currentBytes());assertEquals(field.retainedBytes,field.counters().currentOwnedBytes());
            originalTree.removeIf(owner->owner!=f.owner&&owner!=field.owner);
            assertEquals(originalTree,BGapTestFixture.ownerTree(f.owner));
        }
    }
    @Test void denseIndependentAxisAndDiagonalTraversalContainNominalPrefixWithinAllowance() {
        try(var f=BGapTestFixture.standard();var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner);
                var integral=BGapPairTable.prefix(field,CancellationProbe.NONE,f.owner)) {
            double[][] endpoints={{0,-3,2,5},{.5,-3,.5,5},{0,-1.5,2,2.5},{2,2.5,0,-1.5},{0,.5,2,Math.nextUp(.5)},{1,-4,1,6}};
            for(var e:endpoints) {
                double dx=e[2]-e[0],dy=e[3]-e[1],ell=StrictMath.hypot(dx,dy);
                // Independent all-grid-line event oracle, tiny and deliberately O(C).
                var events=new java.util.TreeSet<Double>();events.add(0.0);events.add(1.0);
                for(int x=0;x<field.grid.width;x++)if(dx!=0){double t=(x-e[0])/dx;if(t>0&&t<1)events.add(t);}
                for(int y=0;y<field.grid.height;y++)if(dy!=0){double t=(y-4-e[1])/dy;if(t>0&&t<1)events.add(t);}
                double sum=0,obs=0,previous=0;
                for(double t:events) {
                    if(t==0)continue;double mid=(previous+t)/2;
                    int x=(int)Math.floor(e[0]+dx*mid),y=(int)Math.floor(e[1]+dy*mid+4);
                    if(x==field.grid.width-1)x--;if(y==field.grid.height-1)y--;
                    if(x>=0&&x<field.grid.width-1&&y>=0&&y<field.grid.height-1) {
                        sum+=(t-previous)*ell*field.value(x,y);
                        if(field.status(x,y).ordinal()<2)obs+=(t-previous)*ell;
                    }
                    previous=t;
                }
                var actual=integral.integral(new MetricPoint(e[0],e[1]),new MetricPoint(e[2],e[3]));
                assertEquals(sum,actual.value(),actual.error()+1e-14);
                assertEquals(obs,actual.observedMeters(),1e-12);
                assertTrue(actual.error()<=Math.scalb(Math.max(1,ell),-32));
            }
        }
    }
    @Test void handDyadicIntegralAndSubdivisionAreIndependentOracles() {
        try(var f=BGapTestFixture.standard();var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner);
                var integral=BGapPairTable.prefix(field,CancellationProbe.NONE,f.owner)) {
            var a=new MetricPoint(0,.5);var b=new MetricPoint(2,.5);var split=new MetricPoint(.75,.5);
            assertEquals(1.0/16,integral.integral(a,b).value(),0);
            assertEquals(3.0/128,integral.integral(a,split).value(),0);
            assertEquals(5.0/128,integral.integral(split,b).value(),0);
            assertEquals(integral.integral(a,b),integral.integral(b,a));
            assertEquals(1.0/16,integral.integral(new MetricPoint(.5,0),new MetricPoint(.5,2)).value(),0);
        }
    }
    @Test void completeTableUsesOriginalPointAndStateIdentity() {
        try(var f=new BGapTestFixture(1,(x,y)->BGapTestFixture.observed(y),false,true,2,3);
                var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner);
                var table=BGapPairTable.build(field,f.input.profiles(),CancellationProbe.NONE,f.owner)) {
            assertEquals(9,table.counters().pairWrites());
            assertEquals(1.0/16,table.value(0,0,0),0);
            assertEquals(2,table.entry(0,0,0).observedMeters(),0);
        }
    }
}
