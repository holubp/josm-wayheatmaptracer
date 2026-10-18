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
        Path registry=root.resolve(BIdentificationManifest.REVIEWED_SOURCE_REGISTRY_PATH);
        Files.writeString(registry," ",StandardOpenOption.APPEND);
        assertProbe(root,"refused",true);
    }

    private Path copyReviewedInputs() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("checkout"));
        for(String member:BIdentificationReceipt.SOURCE_FILES) {
            Path original=sourcePath(Path.of(""),member);
            Path copy=root.resolve(original);
            Files.createDirectories(copy.getParent());Files.copy(original,copy);
        }
        for(Path path:List.of(MANIFEST,BIdentificationManifest.REVIEWED_SOURCE_REGISTRY_PATH)) {
            Path copy=root.resolve(path);Files.createDirectories(copy.getParent());Files.copy(path,copy);
        }
        Path resource=root.resolve("resources").resolve(INDEX);Files.createDirectories(resource.getParent());
        try(var input=BIdentificationCatalog.class.getResourceAsStream("/"+INDEX)) {
            assertNotNull(input);Files.copy(input,resource);
        }
        return root;
    }
    private static Path sourcePath(Path root,String member) {
        String relative=BIdentificationReceipt.PACKAGE+member;
        Path main=Path.of("src/main/java",relative);
        Path test=Path.of("src/test/java",relative);
        return root.resolve(Files.exists(main)?main:test);
    }
    private static void assertProbe(Path root,String output,boolean refusal) throws Exception {
        String classes=Path.of(V022BRegistrationBoundaryTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String main=Path.of(CancellationProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String josm=Path.of(org.openstreetmap.josm.data.coor.LatLon.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        String classpath=String.join(java.io.File.pathSeparator,root.resolve("resources").toString(),classes,main,josm);
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",classpath,Probe.class.getName(),output,Boolean.toString(refusal))
                .directory(root.toFile()).redirectErrorStream(true).start();
        boolean stopped=process.waitFor(30,TimeUnit.SECONDS);
        if(!stopped)process.destroyForcibly();
        assertTrue(stopped,"Metadata-only registration probe did not terminate");
        String text=new String(process.getInputStream().readNBytes(4096),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0,process.exitValue(),text);
        assertEquals(refusal?"REFUSED_BEFORE_SCENE\n":"CATALOG_REGISTERED_WITHOUT_SCENE\n",text);
    }
    public static final class Probe {
        public static void main(String[] arguments) throws Exception {
            var calls=new AtomicInteger();boolean refusal=Boolean.parseBoolean(arguments[1]);
            var controls=new BObservationIdentificationTool.Controls(null,64L*1024*1024,3_600_000_000_000L,
                    System::nanoTime,CancellationProbe.NONE,calls::incrementAndGet);
            try {
                var receipt=BObservationIdentificationTool.run(MANIFEST,BIdentificationCatalog.Phase.CATALOG,
                        Path.of(arguments[0]),null,controls);
                if(refusal||!"CATALOG_REGISTERED".equals(receipt.status())||calls.get()!=0)
                    throw new AssertionError("Registration unexpectedly succeeded or opened a scene");
                System.out.println("CATALOG_REGISTERED_WITHOUT_SCENE");
            } catch(IllegalArgumentException|IllegalStateException failure) {
                if(!refusal||calls.get()!=0||Files.exists(Path.of(arguments[0])))throw failure;
                System.out.println("REFUSED_BEFORE_SCENE");
            }
        }
    }
}
