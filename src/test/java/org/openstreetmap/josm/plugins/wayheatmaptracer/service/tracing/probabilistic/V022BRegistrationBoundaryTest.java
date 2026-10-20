package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Actual registration in disposable source copies; no renderer or evaluation data is used. */
class V022BRegistrationBoundaryTest {
    @TempDir Path temporary;
    private static final Path MANIFEST=Path.of("superpowers/docs/plans/2026-10-18-b-observation-feasibility-manifest.json");
    private static final String INDEX="b-observation-identification/holdout-index.json";

    @Test void actualChangedSourceIsRejectedBeforeAnySceneRead() throws Exception {
        Path root=copyReviewedInputs();
        assertProbe(root,"control",false);
        String member=BIdentificationReceipt.SOURCE_FILES.get(0);
        Path copy=sourcePath(root,member);
        Files.writeString(copy,"\n// disposable mutation\n",StandardOpenOption.APPEND);
        assertProbe(root,"refused",true);
    }
    @Test void actualChangedPublicIndexIsRejectedBeforeAnySceneRead() throws Exception {
        Path root=copyReviewedInputs();
        assertProbe(root,"control",false);
        Files.writeString(root.resolve("resources").resolve(INDEX)," ",StandardOpenOption.APPEND);
        assertProbe(root,"refused",true);
    }
    @Test void actualChangedFixedRegistryIsRejectedBeforeAnySceneRead() throws Exception {
        Path root=copyReviewedInputs();
        assertProbe(root,"control",false);
        Path registry=root.resolve(BProspectiveIdentificationManifest.REGISTRY);
        Files.writeString(registry," ",StandardOpenOption.APPEND);
        assertProbe(root,"refused",true);
    }
    @Test void changedHelperBasePolicyAndLaunchPinRefuseBeforeOutput() throws Exception {
        for(Path path:List.of(Path.of(BProspectiveIdentificationManifest.PROTOCOL_FILES.get(0)),MANIFEST,
                BProspectiveIdentificationManifest.policyPath(BProspectiveIdentificationManifest.Purpose.PROSPECTIVE_FIT),
                BProspectiveIdentificationManifest.PINS)) {
            Path root=copyReviewedInputs();assertProbe(root,"control",false);
            Files.writeString(root.resolve(path)," ",StandardOpenOption.APPEND);assertProbe(root,"refused",true);
        }
    }
    @Test void actualMetadataSealDetectsPinSourceDriftAndWrongPurpose() throws Exception {
        for(String action:List.of("pin-drift","source-drift","wrong-purpose"))
            assertProbe(copyReviewedInputs(),"refused",true,ProspectiveTestInputs.expectedPins(),action);
    }
    @Test void explicitPendingFixturesRefuseEveryEmpiricalRouteBeforeAcquisition() throws Exception {
        for(String route:List.of("fit","extract","diagnostic")) {
            Path root=copyReviewedInputs();String fixture=forceStatus(root,BProspectiveIdentificationManifest.PENDING);
            assertProbe(root,"refused",true,fixture,"pending-"+route);
        }
    }
    @Test void approvedFixturesCannotAcquireLocallyAndDummyEndpointIsNeverCalled() throws Exception {
        for(String route:List.of("fit","extract","diagnostic")) {
            Path root=copyReviewedInputs();String fixture=forceStatus(root,BProspectiveIdentificationManifest.APPROVED);
            assertProbe(root,"refused",true,fixture,"approved-"+route);
        }
    }
    @Test void oversizedAndSymbolicAuthorityPathsRefuseBeforeSceneOrOutput() throws Exception {
        Path large=copyReviewedInputs();Files.write(large.resolve(BProspectiveIdentificationManifest.PINS),new byte[65_537]);
        assertProbe(large,"refused",true);
        Path linked=copyReviewedInputs(),pins=linked.resolve(BProspectiveIdentificationManifest.PINS),target=linked.resolve("real-pins.json");
        Files.move(pins,target);Files.createSymbolicLink(pins,target);assertProbe(linked,"refused",true);
    }
    private String forceStatus(Path root,String status) throws Exception {
        Path path=root.resolve(BProspectiveIdentificationManifest.PINS);
        var pins=new java.util.LinkedHashMap<>(BIdentificationReceipt.read(path));pins.put("approval",status);
        byte[] bytes=(BIdentificationReceipt.canonical(pins)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(path,bytes);return BIdentificationReceipt.hash(bytes); // Disposable fixture, never review authority.
    }

    Path copyReviewedInputs() throws Exception {
        return HistoricalObservationMetadataFixture.install(Files.createTempDirectory(temporary,"historical-metadata-"));
    }
    static Path sourcePath(Path root,String member) {
        String relative=BIdentificationReceipt.PACKAGE+member;
        Path main=Path.of("src/main/java",relative);
        Path test=Path.of("src/test/java",relative);
        return root.resolve(Files.exists(main)?main:test);
    }
    private static void assertProbe(Path root,String output,boolean refusal) throws Exception {
        assertProbe(root,output,refusal,ProspectiveTestInputs.expectedPins(),"catalog");
    }
    private static void assertProbe(Path root,String output,boolean refusal,String expected,String action) throws Exception {
        String classes=Path.of(V022BRegistrationBoundaryTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String main=Path.of(CancellationProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String josm=Path.of(org.openstreetmap.josm.data.coor.LatLon.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        String classpath=String.join(java.io.File.pathSeparator,root.resolve("resources").toString(),classes,main,josm);
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",classpath,Probe.class.getName(),output,Boolean.toString(refusal),expected,action)
                .directory(root.toFile()).redirectErrorStream(true);
        builder.environment().remove("GITHUB_ACTIONS"); // Fixtures stay local even when JUnit is launched by CI.
        var process=builder.start();
        boolean stopped=process.waitFor(30,TimeUnit.SECONDS);
        if(!stopped)process.destroyForcibly();
        assertTrue(stopped,"Metadata-only registration probe did not terminate");
        String text=new String(process.getInputStream().readNBytes(4096),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0,process.exitValue(),text);
        assertEquals(refusal?"REFUSED_BEFORE_SCENE\n":"CATALOG_REGISTERED_WITHOUT_SCENE\n",text);
    }
    public static final class Probe {
        public static void main(String[] arguments) throws Exception {
            var calls=new AtomicInteger();boolean refusal=Boolean.parseBoolean(arguments[1]);String action=arguments[3];
            var controls=new BObservationIdentificationTool.Controls(null,64L*1024*1024,3_600_000_000_000L,
                    System::nanoTime,CancellationProbe.NONE,()->{calls.incrementAndGet();throw new AssertionError("No acquisition permitted");});
            try {
                if(!action.equals("catalog")) {
                    var seal=BProspectiveIdentificationManifest.registerMetadata(BProspectiveIdentificationManifest.Purpose.PROSPECTIVE_FIT,arguments[2]);
                    if(action.endsWith("diagnostic")) {
                        String status=action.startsWith("pending")?BProspectiveIdentificationManifest.PENDING:BProspectiveIdentificationManifest.APPROVED;
                        if(!status.equals(seal.approval()))throw new AssertionError("Explicit status fixture required");
                        // Output creation precedes any renderer on this fixed route: absence is also an acquisition sentinel.
                        BObservationIdentificationTool.runOptimizedTrainRuntimeDiagnostic(Path.of(arguments[0]),arguments[2]);
                    } else if(action.endsWith("fit")||action.endsWith("extract")) {
                        String status=action.startsWith("pending")?BProspectiveIdentificationManifest.PENDING:BProspectiveIdentificationManifest.APPROVED;
                        if(!status.equals(seal.approval()))throw new AssertionError("Explicit status fixture required");
                        BObservationIdentificationTool.runProspective(BIdentificationCatalog.Phase.CATALOG,Path.of("metadata"),null,controls,arguments[2]);
                        BObservationIdentificationTool.runProspective(action.endsWith("fit")?BIdentificationCatalog.Phase.FIT_SELECT:BIdentificationCatalog.Phase.EXTRACT,
                                Path.of(arguments[0]),Path.of("metadata/binding.json"),controls,arguments[2]);
                    } else if(action.equals("pin-drift")) {
                        Files.writeString(BProspectiveIdentificationManifest.PINS," ",StandardOpenOption.APPEND);seal.requireExecutionApproved();
                    } else if(action.equals("source-drift")) {
                        Files.writeString(sourcePath(Path.of(""),BIdentificationReceipt.SOURCE_FILES.get(0))," ",StandardOpenOption.APPEND);seal.verifyUnchanged();
                    } else if(action.equals("wrong-purpose")) {
                        BObservationIdentificationTool.runProspective(BIdentificationCatalog.Phase.CATALOG,Path.of("metadata"),null,controls,arguments[2]);
                        var fields=new java.util.LinkedHashMap<>(BIdentificationReceipt.read(Path.of("metadata/binding.json")));
                        fields.put("protocolPurpose","OPTIMIZED_TRAIN_DIAGNOSTIC");BIdentificationReceipt.publish(Path.of("wrong.json"),fields);
                        BObservationIdentificationTool.runProspective(BIdentificationCatalog.Phase.EXTRACT,Path.of(arguments[0]),Path.of("wrong.json"),controls,arguments[2]);
                    } else throw new AssertionError("Unknown test action");
                    throw new AssertionError("Expected refusal");
                }
                var receipt=BObservationIdentificationTool.runProspective(BIdentificationCatalog.Phase.CATALOG,
                        Path.of(arguments[0]),null,controls,arguments[2]);
                if(refusal||!"CATALOG_REGISTERED".equals(receipt.status())||calls.get()!=0)
                    throw new AssertionError("Registration unexpectedly succeeded or opened a scene");
                System.out.println("CATALOG_REGISTERED_WITHOUT_SCENE");
            } catch(IllegalArgumentException|IllegalStateException|SecurityException failure) {
                if(!refusal||calls.get()!=0||Files.exists(Path.of(arguments[0])))throw failure;
                if(action.equals("wrong-purpose")&&!failure.getMessage().startsWith("CATALOG "))throw failure;
                if(action.equals("pin-drift")&&!failure.getMessage().equals("External prospective pins changed"))throw failure;
                if(action.equals("source-drift")&&!failure.getMessage().startsWith("Reviewed source member changed:"))throw failure;
                if(action.startsWith("pending-")&&!failure.getMessage().equals("Prospective execution is pending independent approval"))throw failure;
                if(action.startsWith("approved-")&&!failure.getMessage().equals("Empirical execution requires a bound manual CI launch"))throw failure;
                System.out.println("REFUSED_BEFORE_SCENE");
            }
        }
    }
}
