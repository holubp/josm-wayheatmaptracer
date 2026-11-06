package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class LegacyNormalWavinessTest {
    @Test void actualSecantChartStraightRidgeHistoricalRedBecomesGeometryOnlyGreen(){
        for(double p:List.of(6.0,24.0)){
            var profiles=straightTargetProfiles(p);var measured=measuredOffsets(profiles);
            var old=LegacyTrackerPolicyTest.track(profiles,p,p,LegacyTrackerPolicy.ORIGINAL_V02).get(0);
            var fixed=LegacyTrackerPolicyTest.track(profiles,p,p,LegacyTrackerPolicy.GEOMETRY_ONLY_V1).get(0);
            assertTrue(old.screenPoints().stream().mapToDouble(q->Math.abs(q.y)).max().orElseThrow()>.1*p,"Historical RED must stay discriminating");
            for(int i=0;i<profiles.size();i++){
                assertEquals(0,point(profiles.get(i),measured.get(i)).y,1e-9);
                assertEquals(0,fixed.screenPoints().get(i).y,1e-9);
                if(i>0)assertTrue(fixed.screenPoints().get(i).x>fixed.screenPoints().get(i-1).x);
            }
            assertTrue(energy(profiles,fixed.offsetsPx(),p)<=energy(profiles,measured,p)+1e-24);
            assertNoContacts(fixed.screenPoints());
        }
    }
    @Test void actualImageSpaceWrinkleStillSmoothsAndWholeEnergyNeverIncreases()throws Exception{
        for(double p:List.of(6.0,24.0)){
            var profiles=new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();
            for(int i=0;i<17;i++)profiles.add(LegacyTrackerPolicyTest.profile(i,List.of(LegacyTrackerPolicyTest.peak((i%2==0?.5:-.5)*p,p,.48)),10*p));
            var input=measuredOffsets(profiles);var result=smooth(profiles,input,p,p,LegacyTrackerPolicy.GEOMETRY_ONLY_V1);
            assertTrue(energy(profiles,result,p)<energy(profiles,input,p));
            assertTrue(java.util.stream.IntStream.range(1,16).anyMatch(i->Math.abs(result.get(i)-input.get(i))>.01*p),"Guard must accept useful real moves");
            assertEquals(input.get(0),result.get(0));assertEquals(input.get(16),result.get(16));
            assertNoContacts(points(profiles,result));
        }
    }
    @Test void activeFaintSignedArcsHairpinsAndSustainedBendsStayWithinNativeUncertainty()throws Exception{
        for(double p:List.of(6.0,24.0))for(double sweep:List.of(Math.toRadians(150),Math.toRadians(-150),Math.PI)){
            var profiles=circleTargetProfiles(p,sweep);var measured=measuredOffsets(profiles);
            var old=smooth(profiles,measured,p,p,LegacyTrackerPolicy.ORIGINAL_V02);
            var guarded=smooth(profiles,measured,p,p,LegacyTrackerPolicy.GEOMETRY_ONLY_V1);
            assertTrue(java.util.stream.IntStream.range(1,16).anyMatch(i->Math.abs(old.get(i)-measured.get(i))>.01*p),"Faint curve must exercise proposals");
            for(int i=0;i<17;i++)assertTrue(point(profiles.get(i),guarded.get(i)).distance(point(profiles.get(i),measured.get(i)))<=.5*p+1e-9,"Supported curve drift p="+p+" sweep="+sweep+" row="+i);
            assertTrue(energy(profiles,guarded,p)<=energy(profiles,measured,p)+1e-20);
            assertEquals(measured.get(0),guarded.get(0));assertEquals(measured.get(16),guarded.get(16));
            assertNoContacts(points(profiles,guarded));
        }
    }
    @Test void degenerateAndNonfiniteLocalGeometryCannotAuthorizeMoves()throws Exception{
        var duplicate=List.of(LegacyTrackerPolicyTest.profile(0,List.of(LegacyTrackerPolicyTest.peak(0,12,.48)),0),
                LegacyTrackerPolicyTest.profile(1,List.of(LegacyTrackerPolicyTest.peak(0,12,.48)),0),
                LegacyTrackerPolicyTest.profile(2,List.of(LegacyTrackerPolicyTest.peak(0,12,.48)),0));
        assertFalse(Double.isFinite(localEnergy(duplicate,List.of(0.0,0.0,0.0),1,6)));
        var valid=straightTargetProfiles(6);var bad=new ArrayList<>(measuredOffsets(valid));bad.set(8,Double.NaN);
        assertFalse(Double.isFinite(localEnergy(valid,bad,8,6)));
        assertEquals(List.of(0.0,0.0,0.0),smooth(duplicate,List.of(0.0,0.0,0.0),6,6,LegacyTrackerPolicy.GEOMETRY_ONLY_V1));
    }
    @Test void energyPredicateHasInverseScaleAndUsesOnlyThreeAffectedVertices()throws Exception{
        var small=straightTargetProfiles(6);var large=straightTargetProfiles(24);
        var a=new ArrayList<>(measuredOffsets(small));a.set(8,a.get(8)+3);
        var b=a.stream().map(x->x*4).toList();
        assertEquals(4,energy(small,a,6)/energy(large,b,24),1e-10);
        var changed=new ArrayList<>(a);changed.set(8,changed.get(8)+1);
        assertEquals(energy(small,changed,6)-energy(small,a,6),localEnergy(small,changed,8,6)-localEnergy(small,a,8,6),1e-15);
    }
    static List<RenderedHeatmapSampler.CrossSectionProfile> straightTargetProfiles(double p){
        var source=new ArrayList<Point2D.Double>();for(int i=0;i<17;i++)source.add(new Point2D.Double(10*i*p,5*p*Math.sin(Math.PI*i/4)));
        var result=new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();
        for(int i=0;i<17;i++){var n=normal(source,i);var a=source.get(i);double t=-a.y/n.y;
            result.add(profile(a,n,t,p));}
        return List.copyOf(result);
    }
    static List<RenderedHeatmapSampler.CrossSectionProfile> circleTargetProfiles(double p,double sweep){
        var source=new ArrayList<Point2D.Double>();double r=80*p;
        for(int i=0;i<17;i++){double angle=-sweep/2+sweep*i/16;double radius=r+5*p*Math.sin(Math.PI*i/4);source.add(new Point2D.Double(radius*Math.cos(angle),radius*Math.sin(angle)));}
        var result=new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();
        for(int i=0;i<17;i++){var a=source.get(i);var n=normal(source,i);double projection=a.x*n.x+a.y*n.y;
            double discriminant=projection*projection-(a.x*a.x+a.y*a.y-r*r);assertTrue(discriminant>=0);
            double t1=-projection+Math.sqrt(discriminant),t2=-projection-Math.sqrt(discriminant);double t=Math.abs(t1)<Math.abs(t2)?t1:t2;
            assertTrue(Math.abs(t)<8*p);result.add(profile(a,n,t,p));}
        return List.copyOf(result);
    }
    static Point2D.Double normal(List<Point2D.Double> a,int i){
        boolean wide=i>=3&&i+3<a.size();int first=wide?i-3:Math.max(0,i-1),last=wide?i+3:Math.min(a.size()-1,i+1);
        double dx=a.get(last).x-a.get(first).x,dy=a.get(last).y-a.get(first).y,l=Math.hypot(dx,dy);return new Point2D.Double(-dy/l,dx/l);
    }
    static RenderedHeatmapSampler.CrossSectionProfile profile(Point2D.Double a,Point2D.Double n,double t,double p){return new RenderedHeatmapSampler.CrossSectionProfile(new EastNorth(a.x,a.y),a,n,List.of(LegacyTrackerPolicyTest.peak(t,2*p,.48)));}
    static List<Double> measuredOffsets(List<RenderedHeatmapSampler.CrossSectionProfile> p){return p.stream().map(x->x.peaks().get(0).offsetPx()).toList();}
    static Point2D.Double point(RenderedHeatmapSampler.CrossSectionProfile p,double t){return new Point2D.Double(p.anchorScreen().x+p.normalScreen().x*t,p.anchorScreen().y+p.normalScreen().y*t);}
    static List<Point2D.Double> points(List<RenderedHeatmapSampler.CrossSectionProfile> p,List<Double> o){var r=new ArrayList<Point2D.Double>();for(int i=0;i<o.size();i++)r.add(point(p.get(i),o.get(i)));return r;}
    static double energy(List<RenderedHeatmapSampler.CrossSectionProfile> p,List<Double> o,double pitch){
        var q=points(p,o);double total=0;for(int i=1;i<q.size()-1;i++){double ax=q.get(i).x-q.get(i-1).x,ay=q.get(i).y-q.get(i-1).y,bx=q.get(i+1).x-q.get(i).x,by=q.get(i+1).y-q.get(i).y;double al=Math.hypot(ax,ay),bl=Math.hypot(bx,by);if(al==0||bl==0)return Double.POSITIVE_INFINITY;double dx=bx/bl-ax/al,dy=by/bl-ay/al;total+=(dx*dx+dy*dy)/Math.max(al+bl,pitch);}return total;
    }
    @SuppressWarnings("unchecked") static List<Double> smooth(List<RenderedHeatmapSampler.CrossSectionProfile> p,List<Double> o,double norm,double pitch,LegacyTrackerPolicy policy)throws Exception{
        var m=RidgeTracker.class.getDeclaredMethod("smoothOffsets",List.class,List.class,List.class,double.class,double.class,LegacyTrackerPolicy.class);m.setAccessible(true);return (List<Double>)m.invoke(new RidgeTracker(),p,o,p.stream().map(x->x.peaks().get(0).intensity()).toList(),norm,pitch,policy);
    }
    static double localEnergy(List<RenderedHeatmapSampler.CrossSectionProfile> p,List<Double> o,int changed,double pitch)throws Exception{var m=RidgeTracker.class.getDeclaredMethod("localCenterlineEnergy",List.class,List.class,int.class,double.class);m.setAccessible(true);return (double)m.invoke(new RidgeTracker(),p,o,changed,pitch);}
    static void assertNoContacts(List<Point2D.Double> q){for(int i=1;i<q.size();i++)for(int j=i+2;j<q.size();j++)assertFalse(Line2D.linesIntersect(q.get(i-1).x,q.get(i-1).y,q.get(i).x,q.get(i).y,q.get(j-1).x,q.get(j-1).y,q.get(j).x,q.get(j).y),"Nonadjacent contact "+i+","+j);}
}
