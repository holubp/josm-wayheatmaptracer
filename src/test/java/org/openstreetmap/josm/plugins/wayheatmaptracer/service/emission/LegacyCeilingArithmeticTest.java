package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.lang.reflect.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EmissionResult;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.LegacyEmissionProgress.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.coor.*;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.data.projection.*;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;
import org.openstreetmap.josm.spi.preferences.*;

class LegacyCeilingArithmeticTest {
    @TempDir Path cache;
    @Test void nativeRoundedDivisionAndGroupedAdditionsKeepEqualityUlpsUnderflowAndUnknownDistinct() {
        assertEquals(10,LegacyEmissionKernel.ceilingLower(0,0,0,0,20,2));
        // At2^53 every individual +1 rounds to the even prefix; grouping the four ones first differs.
        assertEquals(0x1.0p53,LegacyEmissionKernel.ceilingLower(0x1.0p53,1,1,1,1,1));
        assertEquals(Double.MIN_VALUE,LegacyEmissionKernel.ceilingLower(0,0,0,0,Double.MIN_VALUE,1));
        assertEquals(0,LegacyEmissionKernel.ceilingLower(0,0,0,0,Double.MIN_VALUE,2));
        assertEquals(0,Double.doubleToRawLongBits(LegacyEmissionKernel.ceilingLower(0,0,0,0,-0.0,1)));
        for(double pitch:new double[]{0,-0.0,-1,Double.NaN,Double.POSITIVE_INFINITY})assertTrue(Double.isNaN(LegacyEmissionKernel.ceilingLower(0,0,0,0,1,pitch)));
        for(double span:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY})assertTrue(Double.isNaN(LegacyEmissionKernel.ceilingLower(0,0,0,0,span,1)));
        assertTrue(Double.isNaN(LegacyEmissionKernel.ceilingLower(0,0,0,0,Double.MAX_VALUE,Double.MIN_VALUE)));
        assertTrue(Double.isNaN(LegacyEmissionKernel.ceilingLower(Double.MAX_VALUE,0,0,0,Double.MAX_VALUE,1)));
        assertTrue(Double.isNaN(LegacyEmissionKernel.ceilingLower(0,Double.NaN,0,0,1,1)));
    }

    static void observe(LegacyEmissionProgress p,SampledEmissionInput input,boolean zero,double l0,double cap,boolean deferred) {
        p.prepareCeilingObservation();p.ceilingObservation(input,zero,l0,cap,deferred);
    }
    @Test void storedWitnessBitsAndStrictCrossingsDescribeOnlyTheCurrentStageAndVisitedPopulation() throws Exception {
        try(var f=LegacyEmissionProgressTest.fixture();var p=LegacyEmissionProgress.open(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1)) {
            p.begin(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1);
            p.groupedBegin(LegacyEmissionKernel.GroupedExecution.EXACT_Q0_CEILING_CENSUS_V1);p.seedBegin(3);
            p.ceilingWitness(f.input(),Optional.of(new EmissionResult.Costs(.5,2,4,8)));
            observe(p,f.input(),false,1,Math.nextDown(14.5),false);
            observe(p,f.input(),false,1,14.5,false); // equality cannot cross
            observe(p,f.input(),false,1,Math.nextUp(14.5),false);
            observe(p,f.input(),false,Double.NaN,1,false);
            observe(p,f.input(),true,Double.NaN,Double.NaN,false);
            observe(p,f.input(),false,2,Double.NaN,false);
            observe(p,f.input(),false,15,16,true); // already cut by Q0: not an incremental scalar opportunity
            p.seal(null);var v=p.view();
            assertEquals(3,v.ceilingSeed());assertEquals(0,v.ceilingStage());
            assertEquals(List.of(.5,2.0,4.0,8.0),java.util.stream.IntStream.range(0,4).mapToObj(v::ceilingWitnessComponent).toList());
            assertEquals(14.5,v.ceilingWitnessUpper());assertEquals(15,v.ceilingMaximum(false));assertEquals(16,v.ceilingMaximum(true));
            assertEquals(7,v.ceilingCount(CeilingCounter.CANDIDATES));assertEquals(5,v.ceilingCount(CeilingCounter.FINITE_Q0));
            assertEquals(1,v.ceilingCount(CeilingCounter.ZERO_DIRECTION));assertEquals(1,v.ceilingCount(CeilingCounter.INVALID_Q0));
            assertEquals(1,v.ceilingCount(CeilingCounter.INVALID_CAP));assertEquals(2,v.ceilingCount(CeilingCounter.CAP_CROSSINGS));
            assertEquals(1,v.ceilingCount(CeilingCounter.Q0_SURVIVOR_CROSSINGS));assertEquals(1,v.ceilingCount(CeilingCounter.Q0_STRICT_BOUNDS));
            for(var c:CeilingCounter.values())assertEquals(v.ceilingCount(c),v.ceilingTotalCount(c));
            assertFalse(v.ceilingCounterOverflow());
        }
    }

    @Test void arbitraryStageCountHasNoHistoryLimitAndUnknownWitnessNeverBecomesZero() throws Exception {
        try(var f=LegacyEmissionProgressTest.fixture();var p=LegacyEmissionProgress.open(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1)) {
            p.begin(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1);
            p.groupedBegin(LegacyEmissionKernel.GroupedExecution.EXACT_Q0_CEILING_CENSUS_V1);p.seedBegin(9);
            for(int stage=0;stage<20;stage++) {
                p.phase(Phase.INTERVAL,stage,stage,stage+1);p.ceilingWitness(f.input(),Optional.empty());
                observe(p,f.input(),false,1,2,false);
            }
            p.seal(null);var v=p.view();assertEquals(9,v.ceilingSeed());assertEquals(19,v.ceilingStage());
            assertFalse(v.hasCeilingWitness());assertThrows(IllegalStateException.class,v::ceilingWitnessUpper);
            assertEquals(1,v.ceilingCount(CeilingCounter.CANDIDATES));assertEquals(20,v.ceilingTotalCount(CeilingCounter.CANDIDATES));
            assertEquals(0,v.ceilingTotalCount(CeilingCounter.CAP_CROSSINGS));
        }
    }

    @Test void nextStageInvalidatesBeforeAFailingChargeAndTerminalSealDoesNoWork() {
        try(var f=LegacyEmissionProgressTest.fixture();var p=LegacyEmissionProgress.open(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1)) {
            p.begin(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1);
            p.groupedBegin(LegacyEmissionKernel.GroupedExecution.EXACT_Q0_CEILING_CENSUS_V1);p.seedBegin(0);
            p.ceilingWitness(f.input(),Optional.of(new EmissionResult.Costs(1,2,3,4)));
            var b=f.input().workBudget();b.charge(EmissionWorkBudget.Operation.PREPARATION,b.workLimit()-b.workUsed());
            var failure=assertThrows(EmissionWorkBudget.LimitExceededException.class,()->p.phase(Phase.INTERVAL,1,0,1));
            long used=b.workUsed();p.seal(failure);assertEquals(used,b.workUsed());assertFalse(p.view().hasCeilingStage());
            assertThrows(IllegalStateException.class,()->p.view().ceilingCount(CeilingCounter.CANDIDATES));
            assertEquals(0,p.view().ceilingTotalCount(CeilingCounter.CANDIDATES));
        }
    }

    @Test void allOwnedProgressArraysAndConservativeObjectSlotsFitThePrepaid64KiB() throws Exception {
        try(var f=LegacyEmissionProgressTest.fixture();var p=LegacyEmissionProgress.open(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1)) {
            p.begin(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1);
            p.groupedBegin(LegacyEmissionKernel.GroupedExecution.EXACT_Q0_CEILING_CENSUS_V1);
            long bound=32+40; // conservative object header, plus preallocated View header/reference
            for(var field:LegacyEmissionProgress.class.getDeclaredFields())if(!Modifier.isStatic(field.getModifiers())) {
                bound+=8; // every primitive/reference field occupies a full8-byte slot in this upper bound
                if(field.getType().isArray()) {
                    assertTrue(field.getType().getComponentType().isPrimitive());field.setAccessible(true);var array=field.get(p);
                    if(array!=null)bound+=32+8L*Array.getLength(array); // overestimates even int arrays; aligned headers
                }
            }
            assertTrue(bound<=STORAGE_BYTES,"Conservative owned layout bound="+bound);
            System.out.println("Ceiling progress conservative owned layout upper bound="+bound+" bytes");
            p.seal(null);assertTrue(p.view().hasCeilingCensus());
        }
    }

    @Test void initializationCapKeepsCensusUnavailableAndCounterOverflowNeverWraps() throws Exception {
        try(var f=LegacyEmissionProgressTest.fixture();var p=LegacyEmissionProgress.open(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1)) {
            p.begin(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1);
            var b=f.input().workBudget();b.charge(EmissionWorkBudget.Operation.PREPARATION,b.workLimit()-b.workUsed()-72);
            var failure=assertThrows(EmissionWorkBudget.LimitExceededException.class,()->p.groupedBegin(LegacyEmissionKernel.GroupedExecution.EXACT_Q0_CEILING_CENSUS_V1));
            assertSame(failure,assertThrows(RuntimeException.class,f.input()::requireOpenAndUnchanged));p.seal(failure);
            assertFalse(p.view().hasCeilingCensus());assertFalse(p.view().hasCeilingStage());
            assertThrows(IllegalStateException.class,()->p.view().ceilingTotalCount(CeilingCounter.CANDIDATES));
        }
        try(var f=LegacyEmissionProgressTest.fixture();var p=LegacyEmissionProgress.open(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1)) {
            p.begin(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1);
            p.groupedBegin(LegacyEmissionKernel.GroupedExecution.EXACT_Q0_CEILING_CENSUS_V1);p.seedBegin(0);p.ceilingWitness(f.input(),Optional.empty());
            var field=LegacyEmissionProgress.class.getDeclaredField("ceiling");field.setAccessible(true);long[] data=(long[])field.get(p);
            data[17]=Long.MAX_VALUE;data[18+CeilingCounter.CANDIDATES.ordinal()]=Long.MAX_VALUE;
            observe(p,f.input(),true,Double.NaN,Double.NaN,false);p.seal(null);
            assertTrue(p.view().ceilingCounterOverflow());assertEquals(Long.MAX_VALUE,p.view().ceilingCount(CeilingCounter.CANDIDATES));
            assertEquals(Long.MAX_VALUE,p.view().ceilingTotalCount(CeilingCounter.CANDIDATES));
        }
    }

    @Test void actualManagedWitnessRecheckAllocationIsPaidDiagnosticTaxToo() throws Exception {
        // Real prepared capture/witness/factory, explicitly synthetic transport; no private fixture or HTTP.
        Config.setPreferencesInstance(new MemoryPreferences());
        Config.setBaseDirectoriesProvider(new IBaseDirectories() {
            public java.io.File getPreferencesDirectory(boolean create){return cache.toFile();}
            public java.io.File getUserDataDirectory(boolean create){return cache.toFile();}
            public java.io.File getCacheDirectory(boolean create){return cache.toFile();}
        });
        var projection=Projections.getProjectionByCode("EPSG:3857");ProjectionRegistry.setProjection(projection);
        var dataSet=new DataSet();var a=new Node(projection.eastNorth2latlon(new EastNorth(500,500)));
        var b=new Node(projection.eastNorth2latlon(new EastNorth(515,500)));dataSet.addPrimitive(a);dataSet.addPrimitive(b);
        var way=new Way();way.setNodes(List.of(a,b));dataSet.addPrimitive(way);dataSet.setSelected(way);
        var layer=new OsmDataLayer(dataSet,"ceiling census synthetic managed source",null);MainApplication.getLayerManager().addLayer(layer);
        var ledger=AttemptMemoryLedger.production();var budget=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
        var settings=new ManagedHeatmapConfig("key","policy","signature","token","all","hot","",".*",
            AlignmentMode.MOVE_EXISTING_NODES,TrackerMode.LEGACY_V02,false,false,false,false,false,false,true,false,false,false,
            18,4,3,InferenceMode.STABLE_FIXED_SCALE,15,15,28,5,IntensitySamplingMode.COLOR_MAPPING,0);
        var image=new BufferedImage(TileHeatmapSampler.TILE_SIZE,TileHeatmapSampler.TILE_SIZE,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)image.setRGB(x,y,0xFF808080);
        var bytes=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);var decoder=new TileDecoderClassifier();
        try(var owner=ledger.rootOwner();var coordinator=new TileFetchCoordinator((request,credentials)->new TransportResponse(
            TileFetchStatus.SUCCESS_NETWORK,200,"image/png",bytes.toByteArray(),null,Duration.ZERO,""),new ManagedTileCache(cache,decoder),decoder,TileReliabilityPolicy.defaults())) {
            var sampler=new TileHeatmapSampler(coordinator);var selection=SelectionResolver.resolve(dataSet,false);
            var source=selection.segmentNodes().stream().map(n->n.getEastNorth(projection)).toList();
            var witness=ManagedSelectionWitness.capture(dataSet,selection,projection,source,budget,owner);
            try(var capture=sampler.prepareForEmission(settings,witness,List.of("hot"),"hot",false,budget,owner);
                var permit=OptionalEmissionContextFactory.bindManagedCapture(capture,()->settings,budget,owner);
                var input=sampler.sampleProfilesForEmission(permit,CancellationProbe.NONE)) {
                long beforeOpen=budget.operationUnits(EmissionWorkBudget.Operation.ALLOCATION);
                try(var p=LegacyEmissionProgress.open(input,QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1)) {
                long openAllocation=budget.operationUnits(EmissionWorkBudget.Operation.ALLOCATION)-beforeOpen;
                p.begin(input,QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1);
                var ticketWork=LegacyCeilingTicketFixture.publicationWork(input,owner);
                long ticketAllocation=ticketWork.get(EmissionWorkBudget.Operation.ALLOCATION);assertTrue(ticketAllocation>0);
                long allocation=budget.operationUnits(EmissionWorkBudget.Operation.ALLOCATION);
                p.groupedBegin(LegacyEmissionKernel.GroupedExecution.EXACT_Q0_CEILING_CENSUS_V1);p.seal(null);
                // Independently prepared complete ticket plus one census array; every admitted unit is tax.
                assertEquals(1+ticketAllocation,budget.operationUnits(EmissionWorkBudget.Operation.ALLOCATION)-allocation);
                assertEquals(budget.operationUnits(EmissionWorkBudget.Operation.ALLOCATION)-allocation,
                    p.view().diagnosticUnits(EmissionWorkBudget.Operation.ALLOCATION)-openAllocation);
                assertTrue(p.view().hasCeilingCensus());
                }
            }
        } finally {MainApplication.getLayerManager().removeLayer(layer);}
        assertEquals(0,ledger.currentBytes());
    }

    @Test void syntheticFinalTicketHasNoCallbackOrChargeAtItsCompleteScanAndOwnsItsScratch() {
        try(var f=LegacyEmissionProgressTest.fixture()) {
            var budget=f.input().workBudget();long bytes=f.ledger().currentBytes();
            try(var publication=f.input().prepareManagedFinalPublication(f.owner(),64,256)) {
                var before=budget.snapshot();publication.validate();assertEquals(before,budget.snapshot());
                assertThrows(IllegalStateException.class,publication::validate);
            }
            assertEquals(bytes,f.ledger().currentBytes());
            try(var publication=f.input().prepareManagedFinalPublication(f.owner(),64,256)) {
                var normal=f.input().profiles().levelZeroProfiles().get(0).normalScreen();normal.x=Math.nextUp(normal.x);
                assertThrows(IllegalStateException.class,publication::validate);
            }
            assertEquals(bytes,f.ledger().currentBytes());
        }
    }
}
