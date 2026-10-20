package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class V022BSameTrailRecordingTest {
    private static MetricPoint p(double x,double y){return new MetricPoint(x,y);}
    private static boolean passes(List<MetricPoint> path){var ledger=new AttemptMemoryLedger(1024*1024);
        try(var owner=ledger.rootOwner()){return BThinSwitchbackAcceptance.evaluate(BSameTrailRecordingFixtures.source(),path,1,CancellationProbe.NONE,owner).passed();}
        finally{assertEquals(0,ledger.currentBytes());}}
    private static List<MetricPoint> mean(BSameTrailRecordingFixtures.Fixture fixture){
        var result=new ArrayList<MetricPoint>();int size=fixture.traces().get(0).size();
        for(int i=0;i<size;i++){double x=0,y=0;for(var trace:fixture.traces()){assertEquals(size,trace.size());x+=trace.get(i).xMeters();y+=trace.get(i).yMeters();}
            result.add(p(x/fixture.count(),y/fixture.count()));}return result;}
    @Test void eachEqualNominalRecordingFailsTheRepresentativeTubeWithoutAPrivilegedCentre() {
        for(String id:BSameTrailRecordingFixtures.IDS){var f=BSameTrailRecordingFixtures.byId(id);assertEquals(id.equals(BSameTrailRecordingFixtures.THREE)?3:5,f.count());
            assertEquals(f.count(),f.traces().size());for(var trace:f.traces())assertFalse(passes(trace),"individual recording must not qualify: "+id);
            for(int a=0;a<f.count();a++)for(int b=a+1;b<f.count();b++)assertNotEquals(f.traces().get(a),f.traces().get(b));
        }
    }
    @Test void latentReferenceSubdivisionAndAnalyticalAlignedMeanPassBothRealTurns() {
        var source=BSameTrailRecordingFixtures.source();assertTrue(passes(source));var split=new ArrayList<MetricPoint>();split.add(source.get(0));
        for(int i=1;i<source.size();i++){var a=source.get(i-1);var b=source.get(i);split.add(p((a.xMeters()+b.xMeters())/2,(a.yMeters()+b.yMeters())/2));split.add(b);}
        assertTrue(passes(split));
        for(String id:BSameTrailRecordingFixtures.IDS){var f=BSameTrailRecordingFixtures.byId(id);var mean=mean(f);assertTrue(passes(mean));
            assertEquals(f.stations().length,mean.size());for(int i=0;i<mean.size();i++)assertTrue(mean.get(i).distanceTo(BSameTrailRecordingFixtures.referenceAt(f.stations()[i]))<=.3552+1e-12);}
        for(int vertex:new int[]{4,8}){var shortcut=new ArrayList<>(source);shortcut.remove(vertex);assertFalse(passes(shortcut));}
    }
    @Test void phaseNoiseGeometryIsIndependentOfStationInsertionAndNormalsNeverSingular() {
        double length=BSameTrailRecordingFixtures.length();assertTrue(length>80&&length<90);
        var stations=BSameTrailRecordingFixtures.stations(1);assertTrue(stations.length>83);assertEquals(0,stations[0]);assertEquals(length,stations[stations.length-1]);
        for(double s:stations){var normal=BSameTrailRecordingFixtures.normalAt(s);assertEquals(1,StrictMath.hypot(normal.xMeters(),normal.yMeters()),2e-15);}
        for(int count:new int[]{3,5})for(int j=0;j<count;j++){
            var coarse=BSameTrailRecordingFixtures.recording(count,j,1);var fine=BSameTrailRecordingFixtures.recording(count,j,.5);var fineStations=BSameTrailRecordingFixtures.stations(.5);
            for(int i=0;i<stations.length;i++){int at=Arrays.binarySearch(fineStations,stations[i]);assertTrue(at>=0);assertEquals(coarse.get(i),fine.get(at));}
        }
        double first=StrictMath.hypot(9,4);assertEquals(p(-4/first,9/first),BSameTrailRecordingFixtures.normalAt(0));
        assertThrows(IllegalArgumentException.class,()->BSameTrailRecordingFixtures.stations(0));
    }
    @Test void fixedRawOccupancyResponseHasNoNormalizationOrCentralRecordingWeight() {
        assertEquals(.02,BSameTrailRecordingFixtures.response(0));assertEquals(.706,BSameTrailRecordingFixtures.response(1),1e-15);
        assertEquals(.97354,BSameTrailRecordingFixtures.response(3),1e-15);assertEquals(.9976186,BSameTrailRecordingFixtures.response(5),1e-15);
        for(String id:BSameTrailRecordingFixtures.IDS){var f=BSameTrailRecordingFixtures.byId(id);var scalar=f.scalar();var valid=f.validity();
            assertEquals(6561,scalar.length);for(boolean bit:valid)assertTrue(bit);assertEquals(.02,scalar[0]);
            assertEquals(BSameTrailRecordingFixtures.response(f.count()),scalar[20*81+25],"all exact fixed starts overlap");
            assertEquals(BSameTrailRecordingFixtures.response(f.count()),scalar[39*81+50],"tip envelope preserves observed turn");
            for(double value:scalar){assertTrue(Double.isFinite(value)&&value>=.02&&value<=1);boolean known=false;
                for(int k=0;k<=f.count();k++)known|=Double.doubleToRawLongBits(value)==Double.doubleToRawLongBits(BSameTrailRecordingFixtures.response(k));assertTrue(known);}
            scalar[0]=99;valid[0]=false;assertEquals(.02,f.scalar()[0]);assertTrue(f.validity()[0]);
        }
        assertThrows(IllegalArgumentException.class,()->BSameTrailRecordingFixtures.byId("not-declared"));
    }
    @Test void spreadChangesAndNoPairIsAnExactlyMirroredStandInForACentralRecording() throws Exception {
        for(String id:BSameTrailRecordingFixtures.IDS){var f=BSameTrailRecordingFixtures.byId(id);var stations=f.stations();
            double smallest=Double.POSITIVE_INFINITY,largest=0;
            for(int i=0;i<stations.length;i++)if(stations[i]>8&&stations[i]<BSameTrailRecordingFixtures.length()-8){
                var base=BSameTrailRecordingFixtures.referenceAt(stations[i]);var normal=BSameTrailRecordingFixtures.normalAt(stations[i]);
                double lo=Double.POSITIVE_INFINITY,hi=Double.NEGATIVE_INFINITY;
                for(var trace:f.traces()){var p=trace.get(i);double d=(p.xMeters()-base.xMeters())*normal.xMeters()+(p.yMeters()-base.yMeters())*normal.yMeters();lo=Math.min(lo,d);hi=Math.max(hi,d);}
                smallest=Math.min(smallest,hi-lo);largest=Math.max(largest,hi-lo);
            }
            assertTrue(largest-smallest>.5,"interior spread genuinely changes");
            for(int a=0;a<f.count();a++)for(int b=a+1;b<f.count();b++){
                boolean nonMirror=false;for(int i=0;i<stations.length;i++){var x=f.traces().get(a).get(i);var y=f.traces().get(b).get(i);
                    nonMirror|=p((x.xMeters()+y.xMeters())/2,(x.yMeters()+y.yMeters())/2).distanceTo(BSameTrailRecordingFixtures.referenceAt(stations[i]))>.01;}
                assertTrue(nonMirror);
            }
        }
        try(var resource=getClass().getResourceAsStream("/b-observed-gap/same-trail-recordings.json")) {
            assertNotNull(resource);assertEquals("5b1fa171a9c1084a77a217c42d1d1a817ae5b928ab3b743560bf706d312cf2a9",BIdentificationReceipt.hash(resource.readAllBytes()));
        }
    }
    @Test void nativeExportPreservesEveryRecordingAndExactScalarBytesWithinOldLeafBound(@TempDir Path directory) throws Exception {
        BSameTrailRecordingFixtures.exportNative(directory);
        assertTrue(Files.exists(directory.resolve("native-fixtures.json")),"native export must publish actual metadata");
        var metadata=BIdentificationReceipt.parse(Files.readAllBytes(directory.resolve("native-fixtures.json")));
        assertEquals("same-trail-recordings-native-v1",metadata.get("schema"));
        var records=(List<?>)metadata.get("fixtures");assertEquals(2,records.size());
        for(var item:records){var record=(Map<?,?>)item;String id=(String)record.get("id");var fixture=BSameTrailRecordingFixtures.byId(id);
            var traces=(List<?>)record.get("traces");assertEquals(fixture.count(),traces.size());
            for(int j=0;j<traces.size();j++){var points=(List<?>)traces.get(j);assertEquals(fixture.traces().get(j).size(),points.size());
                for(int i=0;i<points.size();i++){var point=(List<?>)points.get(i);var expected=fixture.traces().get(j).get(i);
                    assertEquals(Double.doubleToRawLongBits(expected.xMeters()),Double.doubleToRawLongBits(((Number)point.get(0)).doubleValue()));
                    assertEquals(Double.doubleToRawLongBits(expected.yMeters()),Double.doubleToRawLongBits(((Number)point.get(1)).doubleValue()));}}
            byte[] plane=Files.readAllBytes(directory.resolve((String)record.get("rasterFile"))),mask=Files.readAllBytes(directory.resolve((String)record.get("validityFile")));
            assertEquals(52488,plane.length);assertEquals(6561,mask.length);assertEquals(record.get("rasterSha256"),BIdentificationReceipt.hash(plane));
            assertEquals(record.get("validitySha256"),BIdentificationReceipt.hash(mask));var buffer=ByteBuffer.wrap(plane).order(ByteOrder.BIG_ENDIAN);
            for(double value:fixture.scalar())assertEquals(Double.doubleToRawLongBits(value),Double.doubleToRawLongBits(buffer.getDouble()));
            for(byte value:mask)assertEquals(1,value);
        }
        try(var leaves=Files.list(directory)){assertEquals(5,leaves.count());}
        try(var leaves=Files.list(directory)){for(var leaf:leaves.toList())assertTrue(Files.size(leaf)<=65536);}
        BSameTrailRecordingFixtures.exportNative(Path.of("build/gap-same-trail-matrix"));
    }
}
