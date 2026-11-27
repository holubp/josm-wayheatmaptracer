package org.openstreetmap.josm.plugins.wayheatmaptracer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.EnumMap;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceWorkUsage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ManagedModernPreviewSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.SelectionResolver;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticExecutionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticInference;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticInferenceResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticObservationModel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticStateBuilder;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;
import org.openstreetmap.josm.tools.PlatformManager;

class PublicGuiSmokeContractTest {
    @Test void visibleIntervalBootstrapConsumerFramesRequireBoundedPluginIdentifiers() {
        String older="{\"phase\":\"BOOTSTRAP\",\"cause\":\"RESOURCE_LIMIT\",\"publication\":\"NO_PREPARED_ROOT\",\"nativeCalls\":0,\"nativeCallScope\":\"requested-optional-operation\",\"nativeFrozenInputs\":\"unavailable\",\"chainReplay\":\"unavailable\",\"auxiliaryWorkVisits\":8000000}";
        assertTrue(PublicGuiSmokeMain.bootstrapFailureFacts(older).contains("\"consumerFramesUnavailable\":true"),"Missing older consumer fields remain unavailable");
        String prefix="org.openstreetmap.josm.plugins.wayheatmaptracer.";
        String frame="{\"class\":\""+prefix+"service.refinement.NativeRefinementSourceContext\",\"method\":\"intervalQuality\",\"line\":184,\"file\":\"synthetic-cookie=secret\"}";
        String current=older.substring(0,older.length()-1)+",\"consumerFrames\":["+frame+"],\"consumerFramesUnavailable\":false,\"consumerFramesTruncated\":false}";
        String safe=PublicGuiSmokeMain.bootstrapFailureFacts(current);assertTrue(safe.contains("intervalQuality"));assertFalse(safe.contains("secret"));assertFalse(safe.contains("\"file\""));
        for(String invalid:List.of(current.replace(prefix,"java."),current.replace("intervalQuality","lambda$prepare$1"),current.replace("intervalQuality","x".repeat(97)),
                current.replace("NativeRefinementSourceContext","X".repeat(193)),current.replace("refinement.NativeRefinementSourceContext","quality.GeometryWorkContext").replace("intervalQuality","debit"),
                current.replace("["+frame+"]","["+String.join(",",java.util.Collections.nCopies(5,frame))+"]")))
            assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.bootstrapFailureFacts(invalid));
    }
    @Test void visibleIntervalBootstrapFailureKeepsActualRootlessFactsAndUnavailableWork() {
        String actual="{\"phase\":\"BOOTSTRAP\",\"cause\":\"RESOURCE_LIMIT\",\"publication\":\"NO_PREPARED_ROOT\",\"nativeCalls\":0,\"nativeCallScope\":\"requested-optional-operation\",\"nativeFrozenInputs\":\"unavailable\",\"chainReplay\":\"unavailable\",\"auxiliaryWorkVisits\":7999999}";
        var facts=PublicGuiSmokeMain.bootstrapFailureFacts(actual);assertTrue(facts.contains("\"auxiliaryWorkVisits\":7999999"));assertTrue(facts.contains("\"cause\":\"RESOURCE_LIMIT\""));
        assertTrue(PublicGuiSmokeMain.bootstrapFailureFacts(actual.replace("7999999","null")).contains("\"auxiliaryWorkVisits\":null"));
        assertTrue(PublicGuiSmokeMain.bootstrapFailureFacts(actual.replace("RESOURCE_LIMIT","synthetic-cookie=secret")).contains("\"cause\":\"UNAVAILABLE\""));
        for(var invalid:List.of(actual.replace("7999999","8000001"),actual.replace("\"nativeCalls\":0","\"nativeCalls\":1"),actual.replace("BOOTSTRAP","NATIVE"),actual.replace("NO_PREPARED_ROOT","PUBLISHED")))
            assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.bootstrapFailureFacts(invalid));
    }
    @Test void visibleIntervalBootstrapSchemaTwoRequiresItsActualCeilingAndPreservesOldEightMillion() {
        String actual="{\"schema\":2,\"phase\":\"BOOTSTRAP\",\"cause\":\"RESOURCE_LIMIT\",\"publication\":\"NO_PREPARED_ROOT\",\"nativeCalls\":0,\"nativeCallScope\":\"requested-optional-operation\",\"nativeFrozenInputs\":\"unavailable\",\"chainReplay\":\"unavailable\",\"auxiliaryWorkVisits\":9000000,\"auxiliaryMaximumVisits\":64000000}";
        assertTrue(PublicGuiSmokeMain.bootstrapFailureFacts(actual).contains("\"auxiliaryMaximumVisits\":64000000"));
        for(var bad:List.of(actual.replace(",\"auxiliaryMaximumVisits\":64000000",""),actual.replace("64000000","8000000"),actual.replace("64000000","64000001"),actual.replace("\"schema\":2","\"schema\":1"),actual.replace("\"schema\":2","\"schema\":3")))
            assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.bootstrapFailureFacts(bad));
        assertTrue(PublicGuiSmokeMain.bootstrapFailureFacts(actual.replace("9000000","8000000").replace("64000000","8000000")).contains("\"auxiliaryMaximumVisits\":8000000"));
    }
    @Test void visibleIntervalFailureClassificationExposesOnlyExactFixedSourceSymbols() {
        String known="GLOBAL_FINAL_VALIDATION:IllegalArgumentException:Composite original occurrence exceeds its captured movement authority";
        assertEquals("COMPOSITE_OCCURRENCE_AUTHORITY",PublicGuiSmokeMain.visibleIntervalFailureCode(known));
        assertEquals("PLAN_EDIT_AUTHORITY",PublicGuiSmokeMain.visibleIntervalFailureCode("GLOBAL_FINAL_VALIDATION:IllegalArgumentException:Structural diff exceeds explicit primitive edit authority"));
        assertEquals("GLOBAL_REASON_UNAVAILABLE",PublicGuiSmokeMain.visibleIntervalFailureCode("GLOBAL_FINAL_VALIDATION"));
        for(String unknown:List.of("synthetic-cookie=secret",known+" synthetic coordinate=1,2","untrusted-prefix:"+known))
            assertEquals("UNAVAILABLE",PublicGuiSmokeMain.visibleIntervalFailureCode(unknown));
        assertEquals("UNAVAILABLE",PublicGuiSmokeMain.visibleIntervalFailureCode(null));
    }
    @Test void visibleIntervalFixtureSeparatesAllFrozenCompactNegativeFromExistingEligibleIntervals() throws Exception {
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
        var fixture=PublicGuiSmokeFixture.create(temporary);
        var configMethod=PublicGuiSmokeMain.class.getDeclaredMethod("config",boolean.class,boolean.class);configMethod.setAccessible(true);
        var config=AlignmentConfig.withoutCleanup(((ManagedHeatmapConfig)configMethod.invoke(null,true,false)).withAlignmentMode(AlignmentMode.MOVE_EXISTING_NODES));
        var seed=new LiveBPreviewService.ManagedCaptureSeed[2];
        SwingUtilities.invokeAndWait(()->{
            fixture.configureCompactVisibleSelection();
            var middle=fixture.selectedWay().getNode(5);var point=middle.getEastNorth();
            var remote=new org.openstreetmap.josm.data.osm.Node(ProjectionRegistry.getProjection().eastNorth2latlon(new org.openstreetmap.josm.data.coor.EastNorth(point.east(),point.north()+30)));
            fixture.dataSet().addPrimitive(remote);var branch=new org.openstreetmap.josm.data.osm.Way();branch.setNodes(List.of(middle,remote));branch.put("highway","path");fixture.dataSet().addPrimitive(branch);
            var service=new LiveBPreviewService();fixture.dataSet().setSelected(fixture.selectedWay());
            seed[0]=service.captureManagedSeed(fixture.dataSet(),SelectionResolver.resolve(fixture.dataSet(),false),config,"public-compact-negative");
            branch.setNodes(List.of());fixture.dataSet().removePrimitive(branch);fixture.dataSet().removePrimitive(remote);
            PublicGuiSmokeMain.configureOversizedVisibleIntervalFixture(fixture);
            seed[1]=service.captureManagedSeed(fixture.dataSet(),SelectionResolver.resolve(fixture.dataSet(),false),config,"public-existing-interval-positive");
        });
        assertEquals(1,seed[0].intervalPartition().fixedIslands().size());
        assertEquals(seed[0].specification().selectedRange(),seed[0].intervalPartition().fixedIslands().get(0).range());
        assertTrue(seed[0].intervalPartition().slideIntervals().isEmpty(),"The compact junction case has no native interval to run");
        assertFalse(seed[1].intervalPartition().fixedIslands().isEmpty());
        assertFalse(seed[1].intervalPartition().slideIntervals().isEmpty());
        assertTrue(seed[1].intervalPartition().slideIntervals().stream().allMatch(value->value.traceRange().size()>2));
        assertEquals(21,fixture.selectedWay().getNodesCount(),"Reuse the existing interval midpoint fixture without changing its polyline");
    }
    @Test void visibleIntervalCompactPositiveKeepsTwoEditableArmsBeyondAFixedJunction() throws Exception {
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
        var fixture=PublicGuiSmokeFixture.create(temporary);
        var configMethod=PublicGuiSmokeMain.class.getDeclaredMethod("config",boolean.class,boolean.class);configMethod.setAccessible(true);
        var config=((ManagedHeatmapConfig)configMethod.invoke(null,true,false)).withAlignmentMode(AlignmentMode.MOVE_EXISTING_NODES);
        var compactConfig=AlignmentConfig.withoutCleanup(config.withSearchHalfWidthMeters(PublicGuiSmokeMain.COMPACT_VISIBLE_INTERVAL_HALF_WIDTH_METERS));
        var seed=new LiveBPreviewService.ManagedCaptureSeed[2];
        SwingUtilities.invokeAndWait(()->{
            PublicGuiSmokeMain.configureVisibleIntervalFixture(fixture);
            var service=new LiveBPreviewService();
            var selection=SelectionResolver.resolve(fixture.dataSet(),false);
            seed[0]=service.captureManagedSeed(fixture.dataSet(),selection,compactConfig,"public-compact-interval-positive");
            seed[1]=service.captureManagedSeed(fixture.dataSet(),selection,AlignmentConfig.withoutCleanup(config),"public-compact-interval-reference-width");
        });
        var part=seed[0].intervalPartition();
        var reference=seed[1].intervalPartition();
        assertEquals(reference.fixedIslands(),part.fixedIslands());
        assertEquals(reference.slideIntervals(),part.slideIntervals());
        assertEquals(reference.junctionDispositions(),part.junctionDispositions());
        assertEquals(1,part.fixedIslands().size());
        assertEquals(3,part.fixedIslands().get(0).range().size(),
            "Two real fixed center arms keep the compact attempt inventory within its diagnostic ceiling");
        assertEquals(2,part.slideIntervals().size());
        var points=seed[0].sourceMetric();
        double span=points.get(0).distanceTo(points.get(points.size()-1));
        assertTrue(span>=76&&span<79,"Compact fixture must leave traced exteriors beyond a real fixed island");
        for(var interval:part.slideIntervals()) {
            var range=interval.traceRange();
            assertTrue(interval.range().size()>2,"Refinement fallback needs more than two owned occurrences even before adding a frozen trace boundary");
            var first=points.get(range.firstIndex());var last=points.get(range.lastIndex());
            assertTrue(first.distanceTo(last)>=7);
            assertTrue(points.subList(range.firstIndex()+1,range.lastIndex()).stream().anyMatch(point->point.yMeters()>Math.max(first.yMeters(),last.yMeters())+.35),
                "Smoothen needs an editable bend inside each supported exterior");
        }
        var tile=fixture.compactIntervalTile();
        assertEquals(17,tile.address().zoom());
        var crest=PublicGuiSmokeOracle.point(35.7,PublicGuiSmokeFixture.compactIntervalBend(35.7));
        var background=PublicGuiSmokeOracle.point(35.7,15);
        assertTrue(tile.gray(new org.openstreetmap.josm.data.coor.LatLon(crest.latitudeDegrees(),crest.longitudeDegrees()))>128);
        assertTrue(tile.gray(new org.openstreetmap.josm.data.coor.LatLon(background.latitudeDegrees(),background.longitudeDegrees()))<64);
    }
    @Test void visiblePreparationWitnessAcceptsFreshAAndCoveredBWithoutInventingCaptureWork() throws Exception {
        var a=preparationTicket(StageKind.REFINE_A);var b=preparationTicket(StageKind.REFINE_B);
        var fresh=preparationDiagnostic(a,true,9_000_000,64_000_000,1);
        PublicGuiSmokeMain.requireVisiblePreparationWitness(fresh,a.parent(),a.choice(),a.kind(),a.mode(),true);
        var covered=preparationDiagnostic(b,false,12,64_000_000,1);
        PublicGuiSmokeMain.requireVisiblePreparationWitness(covered,b.parent(),b.choice(),b.kind(),b.mode(),false);
        assertTrue(covered.sourcePreparation().orElseThrow().sourceFacts().orElseThrow().captureAuxiliaryVisits().isEmpty());
        // An interval may already have complete source coverage for A too.
        var interval=preparationDiagnostic(a,false,12,64_000_000,1);
        PublicGuiSmokeMain.requireVisiblePreparationWitness(interval,a.parent(),a.choice(),a.kind(),a.mode(),false);
    }
    @Test void visiblePreparationWitnessRejectsMissingFreshProofOrForeignOperation() throws Exception {
        var a=preparationTicket(StageKind.REFINE_A);var b=preparationTicket(StageKind.REFINE_B);
        for(var bad:List.of(preparationDiagnostic(a,false,12,64_000_000,1),preparationDiagnostic(a,true,8_000_000,64_000_000,1)))
            assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireVisiblePreparationWitness(bad,a.parent(),a.choice(),a.kind(),a.mode(),true));
        var good=preparationDiagnostic(b,false,12,64_000_000,1);
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireVisiblePreparationWitness(good,a.parent(),b.choice(),b.kind(),b.mode(),false));
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireVisiblePreparationWitness(good,b.parent(),a.choice(),b.kind(),b.mode(),false));
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireVisiblePreparationWitness(good,b.parent(),b.choice(),a.kind(),b.mode(),false));
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireVisiblePreparationWitness(good,b.parent(),b.choice(),b.kind(),AlignmentMode.PRECISE_SHAPE,false));
        for(var bad:List.of(preparationDiagnostic(b,false,12,8_000_000,1),preparationDiagnostic(b,false,12,64_000_000,0)))
            assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireVisiblePreparationWitness(bad,b.parent(),b.choice(),b.kind(),b.mode(),false));
        var noChild=new RefinementAttemptDiagnostics(good.binding(),good.pipeline(),RefinementAttemptDiagnostics.Publication.NO_CHILD,
            good.phase(),good.cause(),0,true,good.sourcePreparation());
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireVisiblePreparationWitness(noChild,b.parent(),b.choice(),b.kind(),b.mode(),false));
        var old=good.binding();var foreignSource=new RefinementAttemptDiagnostics.Binding(old.ticket(),old.rootStage(),"other-synthetic-source",old.originalNetworkHash(),old.rootParameterHash(),old.frozenGeometryIdentity(),old.parentIdentity());
        assertThrows(IllegalArgumentException.class,()->new RefinementAttemptDiagnostics(foreignSource,good.pipeline(),good.publication(),good.phase(),good.cause(),0,true,good.sourcePreparation()));
    }
    private static OperationTicket preparationTicket(StageKind kind) {
        return new OperationTicket(new SessionId(UUID.randomUUID()),1,new StageId(UUID.randomUUID()),new CandidateId("synthetic-"+UUID.randomUUID()),
            kind==StageKind.REFINE_A?TrackerMode.CORRIDOR_AWARE:TrackerMode.PROBABILISTIC,AlignmentMode.MOVE_EXISTING_NODES,kind,Optional.empty());
    }
    private static RefinementAttemptDiagnostics preparationDiagnostic(OperationTicket ticket,boolean fresh,long visits,long ceiling,int calls) throws Exception {
        String source="synthetic-visible-source";String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var facts=new RefinementAttemptDiagnostics.SourceFacts(ticket,hash,RefinementAttemptDiagnostics.SourceKind.VISIBLE,
            fresh?RefinementAttemptDiagnostics.Coverage.RECAPTURED:RefinementAttemptDiagnostics.Coverage.COVERED,"a".repeat(64),"b".repeat(64),"c".repeat(64),
            fresh?RefinementAttemptDiagnostics.InputFrameRole.CAPTURED_INPUT:RefinementAttemptDiagnostics.InputFrameRole.ORIGINAL_INPUT,8,8,6,6,1,fresh?OptionalLong.of(1):OptionalLong.empty());
        var counts=new EnumMap<GeometryWorkContext.Kind,Long>(GeometryWorkContext.Kind.class);
        for(var kind:GeometryWorkContext.Kind.values())counts.put(kind,kind==GeometryWorkContext.Kind.COPY?visits:0L);
        var nativeWork=new RefinementAttemptDiagnostics.NativeWork(Outcome.COMPLETE,Optional.of(new TraceWorkUsage(1,2,1,1)),OptionalInt.of(calls));
        var work=new RefinementAttemptDiagnostics.SourcePreparationWork(RefinementAttemptDiagnostics.Cause.NONE,visits,counts,Optional.empty(),Optional.of(nativeWork),Optional.of(facts),OptionalLong.of(ceiling));
        var binding=new RefinementAttemptDiagnostics.Binding(ticket,ticket.parent(),source,"network","parameters","frozen","parent");
        return new RefinementAttemptDiagnostics(binding,Optional.empty(),RefinementAttemptDiagnostics.Publication.PUBLISHED,RefinementAttemptDiagnostics.Phase.PUBLICATION,RefinementAttemptDiagnostics.Cause.NONE,0,true,Optional.of(work));
    }
    @Test void intermediateDiagnosticWritesKeepTheVisibleFixtureAlive(@TempDir Path directory) throws Exception {
        var fixtureField=PublicGuiSmokeMain.class.getDeclaredField("visiblePainterFixture");fixtureField.setAccessible(true);
        var write=PublicGuiSmokeMain.class.getDeclaredMethod("writeSummary",Path.class,String.class);write.setAccessible(true);
        Object previous=fixtureField.get(null);var closes=new java.util.concurrent.atomic.AtomicInteger();
        AutoCloseable fixture=closes::incrementAndGet;fixtureField.set(null,fixture);
        try {
            var report=directory.resolve("later-refinement-diagnostics.json");
            write.invoke(null,report,"{\"synthetic\":true}\n");
            assertEquals("{\"synthetic\":true}\n",Files.readString(report));
            assertEquals(0,closes.get(),"An expected operation diagnostic must preserve its actual visible source");
            org.junit.jupiter.api.Assertions.assertSame(fixture,fixtureField.get(null));
            var terminal=PublicGuiSmokeMain.class.getDeclaredMethod("writeTerminalSummary",Path.class,String.class);terminal.setAccessible(true);
            terminal.invoke(null,directory.resolve("summary.json"),"{\"status\":\"PASS\"}\n");
            assertEquals(1,closes.get());org.junit.jupiter.api.Assertions.assertNull(fixtureField.get(null));
            terminal.invoke(null,directory.resolve("summary.json"),"{\"status\":\"FAIL\"}\n");
            assertEquals(1,closes.get(),"Terminal report replacement must not close the source twice");
        } finally {fixtureField.set(null,previous);}
    }

    @Test void terminalReportWriteFailureStillReleasesTheVisibleFixture(@TempDir Path directory) throws Exception {
        var fixtureField=PublicGuiSmokeMain.class.getDeclaredField("visiblePainterFixture");fixtureField.setAccessible(true);
        var terminal=PublicGuiSmokeMain.class.getDeclaredMethod("writeTerminalSummary",Path.class,String.class);terminal.setAccessible(true);
        Object previous=fixtureField.get(null);var closes=new java.util.concurrent.atomic.AtomicInteger();fixtureField.set(null,(AutoCloseable)closes::incrementAndGet);
        try {
            Path parent=directory.resolve("regular-file");Files.writeString(parent,"synthetic");
            var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->terminal.invoke(null,parent.resolve("summary.json"),"{}\n"));
            assertTrue(failure.getCause() instanceof java.io.IOException);
            assertEquals(1,closes.get());org.junit.jupiter.api.Assertions.assertNull(fixtureField.get(null));
        } finally {fixtureField.set(null,previous);}
    }

    @Test void settingsControlLookupRejectsMissingOrAmbiguousControls() {
        var panel = new JPanel(); var nested = new JPanel(); panel.add(nested);
        var box = new javax.swing.JCheckBox("Verbose logging"); nested.add(box);
        assertEquals(box, PublicGuiSmokeMain.requiredCheckbox(panel, "Verbose logging"));
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requiredCheckbox(panel, "missing"));
        panel.add(new javax.swing.JCheckBox("Verbose logging"));
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requiredCheckbox(panel, "Verbose logging"));
    }
    @Test void smokeEngineAdmissionNeverSubstitutesAnotherEngine() {
        assertEquals(TrackerMode.PROBABILISTIC, PublicGuiSmokeMain.guiEngine("PROBABILISTIC"));
        assertEquals(TrackerMode.CORRIDOR_AWARE, PublicGuiSmokeMain.guiEngine("CORRIDOR_AWARE"));
        assertEquals(TrackerMode.LEGACY_THEN_B, PublicGuiSmokeMain.guiEngine("LEGACY_THEN_B"));
        assertEquals(TrackerMode.LEGACY_V02, PublicGuiSmokeMain.guiEngine("LEGACY_V02"));
        for (String other : List.of("HYBRID", "DIRECTIONAL_IMAGE", "unknown"))
            assertThrows(IllegalArgumentException.class, () -> PublicGuiSmokeMain.guiEngine(other));
    }
    @Test void migrationCasesCoverThreeDistinctRegisteredKeyboardActions() {
        assertEquals("wayheatmaptracer.align.global", PublicGuiSmokeMain.entryActionKey(TrackerMode.PROBABILISTIC));
        assertEquals("wayheatmaptracer.align.precise.global", PublicGuiSmokeMain.entryActionKey(TrackerMode.LEGACY_THEN_B));
        assertEquals("wayheatmaptracer.align.move-nodes.global", PublicGuiSmokeMain.entryActionKey(TrackerMode.CORRIDOR_AWARE));
        assertThrows(IllegalArgumentException.class, () -> PublicGuiSmokeMain.entryActionKey(TrackerMode.LEGACY_V02));
    }
    @Test void actualSettingsControlOracleRejectsNestedTrackerChoices() {
        var root = new JPanel(); var nested = new JPanel(); root.add(nested);
        nested.add(new JComboBox<>(new String[] {"color"}));
        PublicGuiSmokeMain.requireNoObsoleteMethodUi(root);
        var obsolete = new javax.swing.JLabel("The old A+B Hybrid has been removed. Your tracing method is now Legacy.");
        nested.add(obsolete);
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requireNoObsoleteMethodUi(root));
        nested.remove(obsolete);
        nested.add(new JComboBox<>(TrackerMode.values()));
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requireNoObsoleteMethodUi(root));
    }
    @Test void stepwiseControlLookupIsExactAndRejectsAmbiguity() {
        var panel=new JPanel();var apply=new javax.swing.JButton("Apply");apply.setName("legacy-apply");panel.add(apply);
        assertEquals(apply,PublicGuiSmokeMain.requiredNamed(panel,"legacy-apply",javax.swing.JButton.class));
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requiredNamed(panel,"missing",javax.swing.JButton.class));
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requiredNamed(panel,"legacy-apply",JComboBox.class));
        var duplicate=new javax.swing.JButton("Other");duplicate.setName("legacy-apply");panel.add(duplicate);
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requiredNamed(panel,"legacy-apply",javax.swing.JButton.class));
    }
    @Test void keyboardAndFooterOraclesRejectMissingEvidence() {
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requireKeyboardReady(false, true));
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requireKeyboardReady(true, false));
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requireOperationChild(false, true));
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requireOperationChild(true, false));
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requireFooterBounds(new java.awt.Rectangle(0, 0, 100, 100), null));
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requireFooterBounds(new java.awt.Rectangle(0, 0, 100, 100), new java.awt.Rectangle(90, 90, 20, 20)));
        PublicGuiSmokeMain.requireKeyboardReady(true, true);
        PublicGuiSmokeMain.requireOperationChild(true, true);
        PublicGuiSmokeMain.requireFooterBounds(new java.awt.Rectangle(0, 0, 100, 100), new java.awt.Rectangle(10, 10, 20, 20));
    }
    @Test void footerHierarchyRejectsHiddenAncestor() {
        var root = new JPanel(null); root.setSize(200, 200);
        var footer = new JPanel(null); footer.setBounds(10, 10, 100, 100); root.add(footer);
        var button = new javax.swing.JButton("Apply"); button.setBounds(10, 10, 30, 20); footer.add(button);
        PublicGuiSmokeMain.requireFooterHierarchy(root, button, true);
        footer.setVisible(false);
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requireFooterHierarchy(root, button, true));
    }
    @Test void footerHierarchyRejectsClippingAncestor() {
        var root = new JPanel(null); root.setSize(200, 200);
        var footer = new JPanel(null); footer.setBounds(10, 10, 30, 30); root.add(footer);
        var button = new javax.swing.JButton("Apply"); button.setBounds(20, 10, 30, 20); footer.add(button);
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requireFooterHierarchy(root, button, true));
        footer.setSize(100, 100);
        PublicGuiSmokeMain.requireFooterHierarchy(root, button, true);
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requireFooterHierarchy(root, button, false));
    }
    @Test void footerHierarchyStopsAtWindowBoundaryBeforeOwner() {
        var outsideBoundary = new JPanel(null); outsideBoundary.setSize(20, 20);
        var window = new JPanel(null); window.setBounds(50, 50, 200, 200); outsideBoundary.add(window);
        var root = new JPanel(null); root.setBounds(0, 0, 180, 180); window.add(root);
        var button = new javax.swing.JButton("Apply"); button.setBounds(10, 10, 30, 20); root.add(button);
        assertThrows(IllegalStateException.class,
            () -> PublicGuiSmokeMain.requireFooterHierarchy(root, button, true, component -> false));
        PublicGuiSmokeMain.requireFooterHierarchy(root, button, true, component -> component == window);
        window.setSize(20, 20);
        assertThrows(IllegalStateException.class,
            () -> PublicGuiSmokeMain.requireFooterHierarchy(root, button, true, component -> component == window));
        window.setSize(200, 200);
        assertThrows(IllegalStateException.class,
            () -> PublicGuiSmokeMain.requireFooterHierarchy(new JPanel(), button, true, component -> component == window));
    }
    @Test void managedDisplayAutoloadIsDisabledBeforeLayerIsAdded() {
        File cacheRoot = new File(System.getProperty("java.io.tmpdir"), "josm-public-gui-smoke-contract");
        Config.setBaseDirectoriesProvider(new org.openstreetmap.josm.spi.preferences.IBaseDirectories() {
            @Override public File getPreferencesDirectory(boolean create) { return cacheRoot; }
            @Override public File getUserDataDirectory(boolean create) { return cacheRoot; }
            @Override public File getCacheDirectory(boolean create) { return cacheRoot; }
        });
        Config.getPref().putBoolean("imagery.generic.default_autoload", true);
        var config = PublicGuiSmokeMain.stepwiseConfig(org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode.PRECISE_SHAPE);
        var added = new AtomicBoolean();
        PublicGuiSmokeMain.addOfflineManagedLayer(config, layer -> {
            assertFalse(layer.getDisplaySettings().isAutoLoad());
            assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.ManagedImageryService.MANAGED_LAYER_ID, layer.getInfo().getId());
            String template = new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileUrlBuilder().buildJosmTemplate(
                config.activity(), config.color(), new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration(config.cacheBuster()));
            assertEquals(template.substring("tms[15]:".length()), layer.getInfo().getUrl());
            assertEquals(15, layer.getInfo().getMaxZoom());
            added.set(true);
        });
        assertTrue(added.get());
    }
    @Test void firstChildStateDistinguishesIdleBeforeActionFromSettledRefusal() {
        assertEquals(PublicGuiSmokeMain.FirstChildState.WAIT, PublicGuiSmokeMain.firstChildState(false, false, true, false));
        assertEquals(PublicGuiSmokeMain.FirstChildState.WAIT, PublicGuiSmokeMain.firstChildState(true, true, true, false));
        assertEquals(PublicGuiSmokeMain.FirstChildState.NO_CHILD, PublicGuiSmokeMain.firstChildState(true, false, true, false));
        assertEquals(PublicGuiSmokeMain.FirstChildState.CHILD, PublicGuiSmokeMain.firstChildState(true, false, true, true));
        assertEquals(PublicGuiSmokeMain.FirstChildState.LOST_OWNER, PublicGuiSmokeMain.firstChildState(true, false, false, false));
    }
    @Test void pipelineAdmissionSummaryBindsExactTicketAndKeepsAbsentCountersNull() {
        var ticket=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.OperationTicket(
            new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.SessionId(java.util.UUID.randomUUID()),1,
            new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.StageId(java.util.UUID.randomUUID()),
            new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.CandidateId("private-candidate"),
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode.PROBABILISTIC,
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode.PRECISE_SHAPE);
        var observed=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.PipelineAdmissionObserver.Rejection(ticket,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.PipelineAdmissionObserver.Gate.ASSEMBLED_RETENTION,
            java.util.OptionalLong.of(2048),java.util.OptionalLong.of(0),java.util.OptionalLong.of(1024),
            java.util.OptionalLong.empty(),java.util.OptionalLong.empty(),11,11,1,java.util.OptionalLong.of(95),java.util.OptionalLong.of(53),
            1.56,53,41,9,java.util.Optional.empty());
        String json=PublicGuiSmokeMain.diagnosticAdmission(java.util.Optional.of(observed),ticket);
        assertTrue(json.startsWith("{"));assertTrue(json.endsWith("}"));
        assertTrue(json.contains("\"gate\":\"ASSEMBLED_RETENTION\""));assertTrue(json.contains("\"requestedBytes\":2048"));
        assertTrue(json.contains("\"currentBytes\":0"));assertTrue(json.contains("\"limitBytes\":1024"));
        assertTrue(json.contains("\"elapsedNanos\":null"));assertTrue(json.contains("\"deadlineNanos\":null"));
        assertTrue(json.contains("\"rootProfileRows\":53"));assertTrue(json.contains("\"rowBound\":53"));
        assertTrue(json.contains("\"rootRasterWidth\":41"));assertTrue(json.contains("\"currentEvidence\":null"));
        assertFalse(json.contains("private"));assertFalse(json.contains(ticket.session().value().toString()));
        var foreign=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.OperationTicket(
            ticket.session(),2,ticket.parent(),ticket.choice(),ticket.engine(),ticket.mode());
        assertEquals("null",PublicGuiSmokeMain.diagnosticAdmission(java.util.Optional.of(observed),foreign));
        assertEquals("null",PublicGuiSmokeMain.diagnosticAdmission(java.util.Optional.empty(),ticket));
        var withEvidence=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.PipelineAdmissionObserver.Rejection(ticket,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.PipelineAdmissionObserver.Gate.ASSESSMENT_SCRATCH,
            observed.requestedBytes(),observed.currentBytes(),observed.limitBytes(),observed.elapsedNanos(),observed.deadlineNanos(),
            observed.parentPoints(),observed.selectedOccurrences(),observed.intervals(),observed.assembledVertexBound(),observed.rowBound(),
            observed.rootConfiguredStepMeters(),observed.rootProfileRows(),observed.rootRasterWidth(),observed.rootRasterHeight(),
            java.util.Optional.of(new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.PipelineAdmissionObserver.GridCounts(512,512,13,11,1)));
        String grids=PublicGuiSmokeMain.diagnosticAdmission(java.util.Optional.of(withEvidence),ticket);
        assertTrue(grids.contains("\"rootRasterWidth\":41"));
        assertTrue(grids.contains("\"currentEvidence\":{\"inputWidth\":512,\"inputHeight\":512,\"outputWidth\":13,\"outputHeight\":11,\"fields\":1}"));
    }
    @Test void attemptRowsPreserveUnknownAndZeroWithoutExposingUnsafeValues() {
        var range = new org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange(0, 1);
        var aborted = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementOperationReceipt.Attempt(
            "hot", "hot", org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementPipeline.AttemptKind.UNATTEMPTED,
            range, "operation-aborted", java.util.Optional.empty(), java.util.OptionalInt.empty(), java.util.Optional.empty(),
            java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), List.of());
        var refused = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementOperationReceipt.Attempt(
            "blue", "blue", org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementPipeline.AttemptKind.WHOLE,
            range, "RESOURCE_LIMIT", java.util.Optional.of(org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Outcome.RESOURCE_LIMIT),
            java.util.OptionalInt.of(0), java.util.Optional.empty(), java.util.Optional.of("private-evidence"),
            java.util.Optional.of("private-parameter"), java.util.Optional.of("private-sampler"), java.util.Optional.empty(), List.of());
        String json = PublicGuiSmokeMain.diagnosticAttemptRows(List.of(aborted, refused));
        assertTrue(json.contains("\"kind\":\"UNATTEMPTED\",\"reason\":\"operation-aborted\""));
        assertTrue(json.contains("\"nativeCalls\":null"));
        assertTrue(json.contains("\"nativeCalls\":0"));
        assertTrue(json.contains("\"outcome\":null,\"nativeCalls\":null,\"nativeStatus\":null,\"requestPresent\":false,\"evidencePresent\":false,\"parameterPresent\":false"));
        assertTrue(json.contains("\"outcome\":\"RESOURCE_LIMIT\",\"nativeCalls\":0,\"nativeStatus\":null"));
        assertTrue(json.contains("\"requestPresent\":true,\"evidencePresent\":true,\"parameterPresent\":true"));
        assertFalse(json.contains("private-"));
        var unsafe = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementOperationReceipt.Attempt(
            "https://secret", "secret\"value", org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementPipeline.AttemptKind.UNAVAILABLE,
            range, "private-reason", java.util.Optional.empty(), java.util.OptionalInt.empty(), java.util.Optional.empty(),
            java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), List.of());
        String redacted = PublicGuiSmokeMain.diagnosticAttemptRows(List.of(unsafe));
        assertFalse(redacted.contains("secret")); assertFalse(redacted.contains("private"));
        assertTrue(redacted.contains("\"palette\":\"UNAVAILABLE\""));
        assertTrue(redacted.contains("\"reason\":\"UNAVAILABLE\""));
        assertEquals(PublicGuiSmokeMain.diagnosticAttemptRows(java.util.Collections.nCopies(64, aborted)),
            PublicGuiSmokeMain.diagnosticAttemptRows(java.util.Collections.nCopies(65, aborted)));
    }
    @Test void exportedReviewRequiresRetainedBytesAndExactIdentity() {
        var mode = org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode.PRECISE_SHAPE;
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requireClosedExportAccounting(1, 4, 5, true, false, false, false));
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requireClosedExportAccounting(0, 4, 0, true, false, false, false));
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requireClosedExportAccounting(0, 0, 0, false, false, false, false));
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requireClosedExportAccounting(0, 4, 4, true, false, false, true));
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requireClosedExportAccounting(0, 4, 4, true, true, false, false));
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requireClosedExportAccounting(0, 4, 4, true, false, true, false));
        PublicGuiSmokeMain.requireClosedExportAccounting(0, 4, 4, true, false, false, false);
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requireExportIdentity(mode, mode, "s", "other", "c", "c", true, true));
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requireExportIdentity(mode, mode, "s", "s", "c", "c", true, false));
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requireExportIdentity(mode, mode, "s", "s", "c", "other", true, true));
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requireExportIdentity(mode, mode, "s", "s", "c", "c", false, true));
        PublicGuiSmokeMain.requireExportIdentity(mode, mode, "s", "s", "c", "c", true, true);
    }
    @Test void laterFailureRejectsStalePublishedReceiptForSameSelection() {
        var session=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.SessionId(java.util.UUID.randomUUID());
        var root=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.StageId(java.util.UUID.randomUUID());
        var stage=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.StageId(java.util.UUID.randomUUID());
        var rootChoice=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.CandidateId("root");
        var choice=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.CandidateId("selected");
        var key=new org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.RefinementLineageCodec.CandidateKey(stage,choice);
        var mode=org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode.PRECISE_SHAPE;
        var first=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.OperationTicket(session,1,root,rootChoice,TrackerMode.CORRIDOR_AWARE,mode);
        var second=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.OperationTicket(session,2,stage,choice,TrackerMode.PROBABILISTIC,mode);
        var firstBinding=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics.Binding(first,root,"source","network","settings","frozen","parent");
        var secondBinding=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics.Binding(second,root,"source","network","settings","frozen","parent");
        var firstReceipt=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementOperationReceipt(first,"parent","network",org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Outcome.COMPLETE,List.of(),java.util.Optional.empty(),false);
        var secondReceipt=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementOperationReceipt(second,"parent","network",org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Outcome.RESOURCE_LIMIT,List.of(),java.util.Optional.empty(),false);
        var stale=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics(firstBinding,java.util.Optional.of(firstReceipt),org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics.Publication.PUBLISHED,org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics.Phase.PUBLICATION,org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics.Cause.NONE,0);
        var failed=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics(secondBinding,java.util.Optional.of(secondReceipt),org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics.Publication.NO_CHILD,org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics.Phase.PIPELINE,org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics.Cause.RESOURCE_LIMIT,0);
        var availability=org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics.Availability.AVAILABLE;
        var old=new org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.RefinementLineageCodec.LatestPublication(stale,key);
        var current=new org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.RefinementLineageCodec.LatestPublication(failed,key);
        assertFalse(PublicGuiSmokeMain.isCurrentAttemptDiagnostic(java.util.Optional.of(stale),stage,choice,org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.StageKind.REFINE_B));
        assertTrue(PublicGuiSmokeMain.isCurrentAttemptDiagnostic(java.util.Optional.of(failed),stage,choice,org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.StageKind.REFINE_B));
        assertFalse(PublicGuiSmokeMain.isCurrentAttemptDiagnostic(java.util.Optional.empty(),stage,choice,org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.StageKind.REFINE_B));
        assertTrue(PublicGuiSmokeMain.isPublishedAttemptDiagnostic(java.util.Optional.of(stale),org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.StageKind.REFINE_A,java.util.Optional.of(root),java.util.Optional.of(rootChoice),org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.StageKind.REFINE_A));
        assertFalse(PublicGuiSmokeMain.isPublishedAttemptDiagnostic(java.util.Optional.of(stale),org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.StageKind.REFINE_A,java.util.Optional.of(root),java.util.Optional.of(rootChoice),org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.StageKind.REFINE_B));
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireLatestAttemptBinding(java.util.Optional.of(failed),availability,java.util.Optional.of(old),java.util.Optional.of(firstReceipt),"AVAILABLE",key));
        PublicGuiSmokeMain.requireLatestAttemptBinding(java.util.Optional.of(failed),availability,java.util.Optional.of(current),java.util.Optional.of(secondReceipt),"AVAILABLE",key);
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireLatestAttemptBinding(java.util.Optional.empty(),org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics.Availability.NONE,java.util.Optional.of(old),java.util.Optional.empty(),"NONE",key));
        PublicGuiSmokeMain.requireLatestAttemptBinding(java.util.Optional.empty(),org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementAttemptDiagnostics.Availability.NONE,java.util.Optional.empty(),java.util.Optional.empty(),"NONE",key);
        PublicGuiSmokeMain.requireLatestAttemptBinding(java.util.Optional.of(stale),availability,java.util.Optional.of(old),java.util.Optional.of(firstReceipt),"AVAILABLE",null);
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireLatestAttemptBinding(java.util.Optional.of(failed),availability,java.util.Optional.of(current),java.util.Optional.of(firstReceipt),"AVAILABLE",key));
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireLatestAttemptBinding(java.util.Optional.of(failed),availability,java.util.Optional.of(current),java.util.Optional.of(secondReceipt),"NONE",key));
    }
    @Test void reviewStatusRejectsWrongGeometryAndAppliedSelection() {
        String good="{\"state\":\"applied\",\"mode\":\"PRECISE_SHAPE\",\"latestAttemptAvailability\":\"AVAILABLE\",\"currentStage\":\"stage\",\"currentChoice\":\"choice\",\"currentGeometry\":\"geometry/x.csv\",\"currentGeometrySha256\":\"hash\",\"applied\":{\"stage\":\"stage\",\"choice\":\"choice\"}}";
        PublicGuiSmokeMain.requireReviewStatus(good,"applied","PRECISE_SHAPE","AVAILABLE","stage","choice","geometry/x.csv","hash",true);
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireReviewStatus(good.replace("geometry/x.csv","geometry/y.csv"),"applied","PRECISE_SHAPE","AVAILABLE","stage","choice","geometry/x.csv","hash",true));
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireReviewStatus(good.replace("\"applied\":{\"stage\":\"stage\"","\"applied\":{\"stage\":\"other\""),"applied","PRECISE_SHAPE","AVAILABLE","stage","choice","geometry/x.csv","hash",true));
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireReviewStatus(good.replace("\"currentChoice\":\"choice\"","\"currentChoice\":\"other\""),"applied","PRECISE_SHAPE","AVAILABLE","stage","choice","geometry/x.csv","hash",true));
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireReviewStatus(good.replace("\"currentStage\":\"stage\"","\"currentStage\":\"other\""),"applied","PRECISE_SHAPE","AVAILABLE","stage","choice","geometry/x.csv","hash",true));
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireReviewStatus(good.replace("\"currentGeometrySha256\":\"hash\"","\"currentGeometrySha256\":\"wrong\""),"applied","PRECISE_SHAPE","AVAILABLE","stage","choice","geometry/x.csv","hash",true));
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireReviewStatus(good.replace("\"applied\":{\"stage\":\"stage\",\"choice\":\"choice\"}","\"applied\":{\"stage\":\"stage\",\"choice\":\"other\"}"),"applied","PRECISE_SHAPE","AVAILABLE","stage","choice","geometry/x.csv","hash",true));
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireReviewStatus(good.replace("\"currentStage\":\"stage\"","\"currentStage\":\"stage\",\"currentStage\":\"stage\""),"applied","PRECISE_SHAPE","AVAILABLE","stage","choice","geometry/x.csv","hash",true));
        String cancelled=good.replace("\"state\":\"applied\"","\"state\":\"cancelled\"").replace("\"applied\":{\"stage\":\"stage\",\"choice\":\"choice\"}","\"applied\":null");
        PublicGuiSmokeMain.requireReviewStatus(cancelled,"cancelled","PRECISE_SHAPE","AVAILABLE","stage","choice","geometry/x.csv","hash",false);
    }
    @Test void stepwisePublicFixtureHasEveryNativePaletteAtBothZooms() throws Exception {
        Path root=temporary.resolve("stepwise-cache");
        var fixture=PublicGuiSmokeFixture.create(temporary.resolve("stepwise-input"));
        var cache=new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileCache(root,new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileDecoderClassifier());
        assertEquals(90,fixture.seedRefinementTiles(cache));
        for(String palette:List.of("hot","blue","bluered","purple","gray"))for(int zoom:List.of(14,15)) {
            Path directory=root.resolve("cache-0/all/"+palette+"/"+zoom);
            try(var files=Files.walk(directory)){assertEquals(9,files.filter(Files::isRegularFile).count());}
            int x=(int)Math.floor((PublicGuiSmokeOracle.BASE_LONGITUDE+180)/360*(1<<zoom));
            double lat=Math.toRadians(PublicGuiSmokeOracle.BASE_LATITUDE);
            int y=(int)Math.floor((1-Math.log(Math.tan(lat)+1/Math.cos(lat))/Math.PI)/2*(1<<zoom));
            var address=new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileAddress("all",palette,zoom,x,y);
            assertTrue(new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileDecoderClassifier().decodeAndClassify(address,"image/png",Files.readAllBytes(cache.path(new ManagedTileGeneration(0),address))).usable(),palette+" z"+zoom);
        }
    }
    @Test void cancellationCannotPassDisabledOrRefusedNativeWork() {
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireNativeCancellation(false,false,false,true,false,true,0));
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireNativeCancellation(true,true,true,true,false,false,1024));
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireNativeCancellation(true,true,true,false,true,false,1024));
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireNativeCancellation(true,true,true,true,true,true,1024));
        PublicGuiSmokeMain.requireNativeCancellation(true,true,true,true,true,false,1024);
    }
    @Test void requestedModeCannotBeRelabelledFromObservedMode() {
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.checkedMode(
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode.MOVE_EXISTING_NODES,
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode.PRECISE_SHAPE));
    }
    @Test void migrationRootReadsEngineOnEdtAndRejectsNonLegacy() throws Exception {
        PublicGuiSmokeMain.requireLegacyMigrationRoot(() -> {
            assertTrue(javax.swing.SwingUtilities.isEventDispatchThread());
            return TrackerMode.LEGACY_V02;
        });
        assertThrows(IllegalStateException.class,
            () -> PublicGuiSmokeMain.requireLegacyMigrationRoot(() -> TrackerMode.PROBABILISTIC));
    }
    @Test void moveCannotReplaceOriginalOccurrencesAtIdenticalCoordinates() {
        var a=new org.openstreetmap.josm.data.osm.Node(new LatLon(0,0));var b=new org.openstreetmap.josm.data.osm.Node(new LatLon(0,.001));
        var originals=List.of(a,b);var replacements=List.of(new org.openstreetmap.josm.data.osm.Node(a.getCoor()),new org.openstreetmap.josm.data.osm.Node(b.getCoor()));
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.checkedAppliedGeometry(
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode.MOVE_EXISTING_NODES,originals,replacements));
    }
    @Test void noninitialParentOracleCannotSkipMissingOrUnchangedChoice() {
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.checkedNoninitialChoice("legacy-choice-0","legacy-choice-0",1));
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.checkedNoninitialChoice("legacy-choice-0","legacy-choice-0",2));
        assertEquals("legacy-choice-1",PublicGuiSmokeMain.checkedNoninitialChoice("legacy-choice-0","legacy-choice-1",2));
    }
    @Test void generatedStepwiseFixtureProvidesActualNoninitialLegacyChoice() throws Exception {
        var fixture=PublicGuiSmokeFixture.create(temporary.resolve("legacy-choice-input"));
        var decoder=new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileDecoderClassifier();
        var cache=new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileCache(temporary.resolve("legacy-choice-cache"),decoder);
        fixture.seedRefinementTiles(cache);
        try(var coordinator=new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchCoordinator((request,credentials)->{throw new IllegalStateException("Public offline fixture cache miss");},cache,decoder,org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileReliabilityPolicy.defaults())) {
            var actual=new org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentResult[1];
            SwingUtilities.invokeAndWait(()->{
                ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));fixture.dataSet().setSelected(fixture.selectedWay());
                var selected=SelectionResolver.resolve(fixture.dataSet(),false);
                actual[0]=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentService(new org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler(coordinator))
                    .align(selected,null,null,AlignmentConfig.withoutCleanup(PublicGuiSmokeMain.stepwiseConfig(org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode.PRECISE_SHAPE)));
            });
            assertTrue(actual[0].candidates().size()>1,"Real Legacy engine must supply noninitial choices for this GUI gate");
            assertTrue(actual[0].candidates().stream().map(org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate::id).distinct().count()>1);
        }
    }
    @TempDir Path temporary;
    private static final List<String> JOSM_STARTUP_EXPORTS = List.of(
            "--add-exports=java.base/sun.security.action=ALL-UNNAMED",
            "--add-exports=java.desktop/com.sun.imageio.plugins.jpeg=ALL-UNNAMED",
            "--add-exports=java.desktop/com.sun.imageio.spi=ALL-UNNAMED");

    @BeforeAll
    static void configureJosm() {
        Config.setPreferencesInstance(new MemoryPreferences());
    }

    @Test
    void generatedPublicFixtureHasKnownGoodElevenNodeWayAndUsableAnalyticTiles() throws Exception {
        var fixture = PublicGuiSmokeFixture.create(temporary);
        assertEquals(11, fixture.selectedWay().getNodesCount());
        assertEquals(0.01, fixture.selectedWay().getNode(5).lat(), 1.0e-8);
        assertEquals(2, fixture.tileCount());
        assertTrue(fixture.tilesUsable());
        assertTrue(fixture.measuredRidgeOffsetMeters() > 2.5);
        assertTrue(fixture.measuredRidgeOffsetMeters() < 5.0);
    }

    @Test
    void publicNativeHotRasterDrivesTheRealManagedProbabilisticEngineToItsRidge() throws Exception {
        var computed = publicComputed();
        assertFalse(computed.pipeline().routes().isEmpty());
        var choices = new LiveBPreviewService().adaptChoices(computed, point -> ProjectionRegistry.getProjection()
                .latlon2eastNorth(new LatLon(point.latitudeDegrees(), point.longitudeDegrees())));
        assertEquals(computed.pipeline().routes().size(), choices.size());
        for (int i = 0; i < choices.size(); i++) {
            assertEquals(computed, choices.get(i).owner());
            assertEquals(i, choices.get(i).localRouteIndex());
        }
        assertEquals(choices.size(), choices.stream().map(choice -> choice.candidate().id()).distinct().count());
        var first = new ModernSingleWayEditPlanAdapter().assess(computed, 0).plan().orElseThrow();
        PublicGuiSmokeOracle.verifyFinalGeometry(first.finalPreviewWays().get(first.selectedWayKey()));
        int selected = PublicGuiSmokeMain.oraclePassingRouteIndex(choices);
        var selectedPlan = new ModernSingleWayEditPlanAdapter().assess(computed, selected).plan().orElseThrow();
        assertEquals(choices.get(selected).candidate().id(), selectedPlan.routeIdentity());
        PublicGuiSmokeOracle.verifyFinalGeometry(selectedPlan.finalPreviewWays().get(selectedPlan.selectedWayKey()));
    }

    @Test
    void publicNativeHotRasterDrivesActualCorridorAwareChoiceToAnalyticRidge() throws Exception {
        var computed = publicComputed(TrackerMode.CORRIDOR_AWARE);
        assertEquals(TrackerMode.CORRIDOR_AWARE, computed.request().engine());
        assertEquals(TrackerMode.CORRIDOR_AWARE, computed.pipeline().inference().engine());
        var choices = new LiveBPreviewService().adaptChoices(computed, point -> ProjectionRegistry.getProjection()
                .latlon2eastNorth(new LatLon(point.latitudeDegrees(), point.longitudeDegrees())));
        int selected = PublicGuiSmokeMain.oraclePassingRouteIndex(choices);
        var plan = new ModernSingleWayEditPlanAdapter().assess(computed, selected).plan().orElseThrow();
        assertEquals(choices.get(selected).candidate().id(), plan.routeIdentity());
        PublicGuiSmokeOracle.verifyFinalGeometry(plan.finalPreviewWays().get(plan.selectedWayKey()));
    }

    @Test
    void publicMeasuredRidgeRetainsExactEagerAndOptimizedPaths() throws Exception {
        var computed = publicComputed();
        var request = computed.request();
        var network = computed.captured().network();
        var way = (org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay)
                network.primitives().get(request.selectedWayKey());
        var source = way.nodeKeys().subList(request.selectedRange().firstIndex(), request.selectedRange().lastIndex() + 1)
                .stream().map(key -> computed.evidence().coordinateFrame().toMetric(
                        ((org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode)
                                network.primitives().get(key)).coordinate())).toList();
        var parameters = EvidenceModelParameters.defaults();
        var profiles = new ProbabilisticProfileFactory().create(source, request.configuredSampleStepMeters(),
                request.permissions().ordinaryRadiusMeters(), true, computed.evidence(),
                computed.evidence().fields().get(computed.options().fieldName()));
        var evaluated = profiles.stream().map(profile -> new ProbabilisticObservationModel().evaluate(profile,
                new ProbabilisticStateBuilder().build(profile, request.budgets().maximumStatesPerProfile())
                        .lattice().orElseThrow(), parameters)).toList();
        ProbabilisticInferenceResult reference = null;
        for (var policy : ProbabilisticExecutionPolicy.Mode.values()) {
            var ledger = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.production();
            var owner = ledger.rootOwner();
            try {
                var result = new ProbabilisticInference().solve(evaluated, parameters, request.budgets(),
                        computed.evidence().decisionRegion(), CancellationProbe.NONE, owner, policy);
                if (reference == null) reference = result;
                else {
                    assertEquals(reference.rawPaths(), result.rawPaths());
                    assertEquals(reference.distinctPaths(), result.distinctPaths());
                }
            } finally { owner.close(); }
        }
    }

    @Test void publicHybridOracleRequiresAnActualBRefinedRidge() throws Exception {
        var computed = publicComputed(TrackerMode.LEGACY_THEN_B);
        var choices = new LiveBPreviewService().adaptChoices(computed, point -> ProjectionRegistry.getProjection()
                .latlon2eastNorth(new LatLon(point.latitudeDegrees(), point.longitudeDegrees())));
        int selected = PublicGuiSmokeMain.oraclePassingRouteIndex(choices);
        assertEquals(1.0, computed.pipeline().routes().get(selected).hypothesis().diagnostics().get("hasBRefinement"));
        var legacyOnly = choices.stream().filter(choice -> computed.pipeline().routes().get(choice.localRouteIndex())
                .hypothesis().diagnostics().getOrDefault("hasBRefinement", 0.0) == 0.0).toList();
        assertFalse(legacyOnly.isEmpty());
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.oraclePassingRouteIndex(legacyOnly));
    }

    private LiveBPreviewService.Computed publicComputed() throws Exception {
        return publicComputed(TrackerMode.PROBABILISTIC);
    }

    private LiveBPreviewService.Computed publicComputed(TrackerMode engine) throws Exception {
        var fixture = PublicGuiSmokeFixture.create(temporary);
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
        fixture.dataSet().setSelected(fixture.selectedWay());
        var selection = SelectionResolver.resolve(fixture.dataSet(), false);
        var configMethod = PublicGuiSmokeMain.class.getDeclaredMethod("config");
        configMethod.setAccessible(true);
        var config = new AlignmentConfig(((ManagedHeatmapConfig) configMethod.invoke(null)).withTrackerMode(engine),
                GeometryCleanupConfig.disabled());
        var service = new LiveBPreviewService();
        LiveBPreviewService.ManagedCaptureSeed[] seed = new LiveBPreviewService.ManagedCaptureSeed[1];
        LiveBPreviewService.LegacyBInvocation[] legacy = new LiveBPreviewService.LegacyBInvocation[1];
        SwingUtilities.invokeAndWait(() -> {
            seed[0] = service.captureManagedSeed(fixture.dataSet(), selection, config, "public-native-hot");
            if (engine == TrackerMode.LEGACY_THEN_B) legacy[0] = LiveBPreviewService.LegacyBInvocation.capture(selection, config);
        });
        if (engine == TrackerMode.LEGACY_THEN_B) {
            var decoder = new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileDecoderClassifier();
            var cache = new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileCache(
                    temporary.resolve("hybrid-tile-cache"), decoder);
            fixture.seedTiles(cache);
            try (var coordinator = new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchCoordinator(
                    (request, credentials) -> { throw new AssertionError("Public fixture must stay offline"); },
                    cache, decoder, org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileReliabilityPolicy.defaults())) {
                coordinator.updateActiveGeneration(new ManagedTileGeneration(0));
                var sources = new ManagedModernPreviewSource(coordinator).acquireSources(seed[0].sourceGeographic(),
                        config.effectiveHeatmap(), seed[0].sourceIdentity(),
                        org.openstreetmap.josm.plugins.wayheatmaptracer.tile.CredentialSnapshot.fromConfig(config.heatmap()),
                        CancellationProbe.NONE);
                return service.compute(service.attachManagedSources(seed[0], sources, coordinator),
                        CancellationProbe.NONE, legacy[0]);
            }
        }
        BufferedImage image = ImageIO.read(temporary.resolve("public-hot-z15.png").toFile());
        boolean[] valid = new boolean[image.getWidth() * image.getHeight()];
        Arrays.fill(valid, true);
        var raster = new ManagedModernPreviewSource.Raster(image, valid,
                SupportedInputRasterTransform.webMercator(15, 16_384 * 256.0,
                        16_383 * 256.0, 2.0), "hot", 15, "public-native-hot",
                new ManagedTileGeneration(0L));
        return service.compute(service.attachManagedRaster(seed[0], raster),
                CancellationProbe.NONE, legacy[0]);
    }

    @Test
    void analyticOracleRejectsUnchangedDisplacedWay() {
        List<GeographicPoint> unchanged = List.of(
                PublicGuiSmokeOracle.point(-40, 0),
                PublicGuiSmokeOracle.point(0, 0),
                PublicGuiSmokeOracle.point(40, 0));
        var failure = assertThrows(IllegalStateException.class,
                () -> PublicGuiSmokeOracle.verifyFinalGeometry(unchanged));
        assertTrue(failure.getMessage().contains("centralSamples=1"));
        assertTrue(failure.getMessage().contains("maxErrorMeters=4.00"));
        assertTrue(failure.getMessage().contains("meanObservedNorthMeters=0.00"));
    }

    @Test
    void analyticOracleRequiresFixedEndpointsAndCentralRidge() {
        List<GeographicPoint> aligned = List.of(
                PublicGuiSmokeOracle.point(-40, 0),
                PublicGuiSmokeOracle.point(-16, 2.8),
                PublicGuiSmokeOracle.point(0, 4),
                PublicGuiSmokeOracle.point(16, 2.8),
                PublicGuiSmokeOracle.point(40, 0));
        PublicGuiSmokeOracle.verifyFinalGeometry(aligned);
        List<GeographicPoint> movedEndpoint = List.of(
                PublicGuiSmokeOracle.point(-40, 0.2),
                PublicGuiSmokeOracle.point(0, 4),
                PublicGuiSmokeOracle.point(40, 0));
        assertThrows(IllegalStateException.class,
                () -> PublicGuiSmokeOracle.verifyFinalGeometry(movedEndpoint));
    }

    @Test
    void guiHostFailsClosedWithoutRealDisplay() {
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requireDisplay(true));
    }

    @Test
    void visibleChoiceDiscoveryRequiresOneDisplayedTypedControl() {
        JPanel panel = new JPanel();
        assertThrows(IllegalStateException.class,
                () -> PublicGuiSmokeMain.visibleChoiceCombo(panel, String.class));
        JComboBox<Integer> unrelated = new JComboBox<>(new Integer[] {0, 1});
        panel.add(unrelated);
        assertThrows(IllegalStateException.class,
                () -> PublicGuiSmokeMain.visibleChoiceCombo(panel, String.class));
        JPanel nested = new JPanel();
        JComboBox<String> route = new JComboBox<>(new String[] {"unchanged", "ridge"});
        nested.add(route);
        panel.add(nested);
        assertEquals(route, PublicGuiSmokeMain.visibleChoiceCombo(panel, String.class));
        panel.add(new JComboBox<>(new String[] {"ambiguous"}));
        assertThrows(IllegalStateException.class,
                () -> PublicGuiSmokeMain.visibleChoiceCombo(panel, String.class));
    }

    @Test
    void soleProductionChoiceNeedsNoChooserButCannotHideMultipleRoutes() {
        JPanel panel = new JPanel();
        assertEquals("ridge", PublicGuiSmokeMain.soleChoiceWithoutControl(
            panel, List.of("ridge"), String.class));
        assertThrows(IllegalStateException.class,
            () -> PublicGuiSmokeMain.soleChoiceWithoutControl(panel, List.of(), String.class));
        assertThrows(IllegalStateException.class,
            () -> PublicGuiSmokeMain.soleChoiceWithoutControl(
                panel, List.of("ridge", "other"), String.class));
        panel.add(new JComboBox<>(new String[] {"ridge"}));
        assertThrows(IllegalStateException.class,
            () -> PublicGuiSmokeMain.soleChoiceWithoutControl(panel, List.of("ridge"), String.class));
    }

    @Test
    void freshJvmInitializesJosmBeforeParsingPublicFixture() throws Exception {
        Process child = startProbe(StartupProbe.class, temporary.resolve("fresh-jvm").toString());
        assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Fresh JVM fixture probe timed out");
        String output = new String(child.getInputStream().readAllBytes());
        assertEquals(0, child.exitValue(), output);
        assertTrue(output.contains("PUBLIC_FIXTURE_READY"), output);
    }

    @Test
    void stalledJosmStartupProducesBoundedSafeFailureSummary() throws Exception {
        Path report = temporary.resolve("startup-timeout.json");
        Process child = startProbe(StartupTimeoutProbe.class, report.toString());
        assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Startup watchdog probe timed out");
        String output = new String(child.getInputStream().readAllBytes());
        assertEquals(1, child.exitValue(), output);
        assertTrue(output.contains("GUI_SMOKE_STARTUP_TIMEOUT"), output);
        assertTrue(output.contains("GUI_SMOKE_FRAME role=startup"), output);
        assertFalse(output.contains(temporary.toString()), output);
        String summary = Files.readString(report);
        assertTrue(summary.contains("\"status\":\"FAIL\""), summary);
        assertTrue(summary.contains("\"stage\":\"josm-startup\""), summary);
        assertTrue(summary.contains("\"errorType\":\"StartupTimeout\""), summary);
    }

    @Test
    void returnedJosmStartupStopsWatchdogBeforeItCanPublishFailure() throws Exception {
        Path report = temporary.resolve("no-timeout.json");
        CountDownLatch deadlineWaiting = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        PublicGuiSmokeMain.startJosmWithDeadline(() -> {
            try {
                assertTrue(deadlineWaiting.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        }, report, () -> {
            deadlineWaiting.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException completed) {
                interrupted.set(true);
                throw completed;
            }
        });
        assertTrue(interrupted.get());
        assertFalse(Files.exists(report));
    }

    @Test
    void publicGuiTaskProvidesJosmStartupModuleExports() throws Exception {
        String build = Files.readString(Path.of("build.gradle.kts"));
        String task = build.substring(build.indexOf("tasks.register<JavaExec>(\"v022PublicGuiSmoke\")"));
        task = task.substring(0, task.indexOf("tasks.jar {"));
        for (String argument : JOSM_STARTUP_EXPORTS) {
            assertTrue(task.contains("\"" + argument + "\""), argument);
        }
    }

    @Test
    void josmSanityCheckAcceptsExactlyTheRequiredJava17Exports() throws Exception {
        Process missing = startProbe(StartupSanityProbe.class, "missing");
        assertTrue(missing.waitFor(10, TimeUnit.SECONDS));
        String missingOutput = new String(missing.getInputStream().readAllBytes());
        assertEquals(0, missing.exitValue(), missingOutput);
        for (String argument : JOSM_STARTUP_EXPORTS) {
            assertTrue(missingOutput.contains(argument), missingOutput);
        }
        Process configured = startProbe(StartupSanityProbe.class, "configured",
                JOSM_STARTUP_EXPORTS);
        assertTrue(configured.waitFor(10, TimeUnit.SECONDS));
        String configuredOutput = new String(configured.getInputStream().readAllBytes());
        assertEquals(0, configured.exitValue(), configuredOutput);
        assertTrue(configuredOutput.contains("JOSM_SANITY_CLEAR"), configuredOutput);
    }

    private static Process startProbe(Class<?> probe, String argument) throws Exception {
        return startProbe(probe, argument, List.of());
    }

    private static Process startProbe(Class<?> probe, String argument, List<String> vmArguments)
            throws Exception {
        List<String> classPath = new ArrayList<>();
        classPath.add(System.getProperty("java.class.path"));
        for (ClassLoader loader = probe.getClassLoader(); loader != null;
                loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (var url : urls.getURLs()) {
                    if ("file".equals(url.getProtocol())) {
                        classPath.add(Path.of(url.toURI()).toString());
                    }
                }
            }
        }
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(vmArguments);
        command.addAll(List.of("-cp", String.join(File.pathSeparator, classPath),
                probe.getName(), argument));
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    public static final class StartupProbe {
        private StartupProbe() { }

        public static void main(String[] args) throws Exception {
            var fixture = PublicGuiSmokeMain.startJosmThenCreateFixture(
                    () -> Config.setPreferencesInstance(new MemoryPreferences()),
                    Path.of(args[0]));
            if (fixture.selectedWay().getNodesCount() != 11 || !fixture.tilesUsable()) {
                throw new AssertionError("Public fixture was not parsed after JOSM initialization");
            }
            System.out.println("PUBLIC_FIXTURE_READY");
        }
    }

    public static final class StartupTimeoutProbe {
        private StartupTimeoutProbe() { }

        public static void main(String[] args) {
            PublicGuiSmokeMain.startJosmWithDeadline(() -> {
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }, Path.of(args[0]), () -> { });
        }
    }

    public static final class StartupSanityProbe {
        private StartupSanityProbe() { }

        public static void main(String[] args) {
            List<String> messages = new ArrayList<>();
            PlatformManager.getPlatform().startupSanityChecks((title, canContinue, issues) ->
                    messages.addAll(List.of(issues)));
            if ("configured".equals(args[0])) {
                if (!messages.isEmpty()) throw new AssertionError("JOSM still reports a sanity issue");
                System.out.println("JOSM_SANITY_CLEAR");
            } else {
                System.out.println(String.join("\n", messages));
            }
        }
    }
}
