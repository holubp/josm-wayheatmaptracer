package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.*;
import java.nio.file.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class V022BSeparatedSameTrailTest {
    private static boolean passes(List<MetricPoint> path){var ledger=new AttemptMemoryLedger(1024*1024);
        try(var owner=ledger.rootOwner()){return BThinSwitchbackAcceptance.evaluate(BSameTrailRecordingFixtures.source(),path,1,CancellationProbe.NONE,owner).passed();}
        finally{assertEquals(0,ledger.currentBytes());}}
    private static double minimumAdjacent(BSameTrailRecordingFixtures.Fixture f,int i){
        var s=f.stations()[i];var base=BSameTrailRecordingFixtures.referenceAt(s);var n=BSameTrailRecordingFixtures.normalAt(s);var d=new double[3];
        for(int j=0;j<3;j++){var p=f.traces().get(j).get(i);d[j]=(p.xMeters()-base.xMeters())*n.xMeters()+(p.yMeters()-base.yMeters())*n.yMeters();}
        Arrays.sort(d);return Math.min(d[1]-d[0],d[2]-d[1]);}
    @Test void threeNativePixelSeparatedCentresPersistOverTwelveMetresAndImproveOldThree(){
        var f=BSeparatedSameTrailFixtures.fixture();var old=BSameTrailRecordingFixtures.byId(BSameTrailRecordingFixtures.THREE);
        assertEquals(BSeparatedSameTrailFixtures.ID,f.id());assertArrayEquals(old.stations(),f.stations());
        int count=0;double minimum=Double.POSITIVE_INFINITY,oldMinimum=Double.POSITIVE_INFINITY;
        for(int i=0;i<f.stations().length;i++)if(f.stations()[i]>=8&&f.stations()[i]<=20){
            minimum=Math.min(minimum,minimumAdjacent(f,i));oldMinimum=Math.min(oldMinimum,minimumAdjacent(old,i));count++;}
        assertTrue(count>=13,"whole-metre stations plus mandatory source vertices");assertTrue(minimum>=2.16,"new signed centre spacing in native1m pixels: "+minimum);
        assertTrue(minimum>oldMinimum+1,"strictly more physical separation than old THREE");
    }
    @Test void noInputRecordingQualifiesButReferenceAndAlignedMeanPreserveBothTips(){
        var f=BSeparatedSameTrailFixtures.fixture();var mean=new ArrayList<MetricPoint>();
        for(var trace:f.traces()){assertFalse(passes(trace));assertEquals(BSameTrailRecordingFixtures.source().get(0),trace.get(0));}
        for(int i=0;i<f.stations().length;i++){double x=0,y=0;for(var trace:f.traces()){x+=trace.get(i).xMeters();y+=trace.get(i).yMeters();}
            var p=new MetricPoint(x/3,y/3);mean.add(p);assertTrue(p.distanceTo(BSameTrailRecordingFixtures.referenceAt(f.stations()[i]))<=.7+1e-12);}
        assertTrue(passes(mean));assertTrue(passes(BSameTrailRecordingFixtures.source()));
        for(int tip:new int[]{4,8}){var shortcut=new ArrayList<>(BSameTrailRecordingFixtures.source());shortcut.remove(tip);assertFalse(passes(shortcut));}
    }
    @Test void exportKeepsOldThreeFiveBytesAndBindsOneSeparatedNativeInput(@TempDir Path directory) throws Exception {
        var oldDir=directory.resolve("old");BSameTrailRecordingFixtures.exportNative(oldDir);
        assertEquals("f1da6eefc06fba36254192255eee635550c5df5de0ac5ee265ed3f912407e5c5",BIdentificationReceipt.hash(Files.readAllBytes(oldDir.resolve("native-fixtures.json"))));
        var out=directory.resolve("new");BSeparatedSameTrailFixtures.exportNative(out);
        var metadata=BIdentificationReceipt.parse(Files.readAllBytes(out.resolve("native-fixtures.json")));
        assertEquals("6934e4a5ffc18757bcdf4c4815e442f31c915e6ea339c9f144ebefd32242a86d",metadata.get("recipeSha256"));
        var records=(List<?>)metadata.get("fixtures");assertEquals(1,records.size());var record=(Map<?,?>)records.get(0);
        assertEquals(BSeparatedSameTrailFixtures.ID,record.get("id"));assertEquals(3,((List<?>)record.get("traces")).size());
        byte[] raster=Files.readAllBytes(out.resolve(BSeparatedSameTrailFixtures.ID+".f64"));assertEquals(52488,raster.length);
        assertEquals(record.get("rasterSha256"),BIdentificationReceipt.hash(raster));var buffer=ByteBuffer.wrap(raster).order(ByteOrder.BIG_ENDIAN);
        for(double value:BSeparatedSameTrailFixtures.fixture().scalar())assertEquals(Double.doubleToRawLongBits(value),Double.doubleToRawLongBits(buffer.getDouble()));
        byte[] valid=Files.readAllBytes(out.resolve(BSeparatedSameTrailFixtures.ID+".valid"));assertEquals(6561,valid.length);for(byte v:valid)assertEquals(1,v);
        assertEquals(record.get("validitySha256"),BIdentificationReceipt.hash(valid));
        try(var leaves=Files.list(out)){var files=leaves.toList();assertEquals(3,files.size());for(var leaf:files)assertTrue(Files.size(leaf)<=65536);}
        BSeparatedSameTrailFixtures.exportNative(Path.of("build/gap-separated-same-trail-matrix"));
    }
}
