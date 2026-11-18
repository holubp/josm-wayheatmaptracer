package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.geom.Point2D;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;

class CorridorComponentDependencyTest {
    @Test void finiteInactiveWeakFitOutsideEnvelopeRemainsDiagnosticOnly()throws Exception {
        var fixture=offNormal();var diagnostics=new ArrayList<CorridorTubeBuilder.WorldFitDiagnostic>();
        assertDoesNotThrow(()->fit(fixture,"weak32m",false,diagnostics),"A finite exactly unused weak fit must not abort complete field assembly");
        assertTrue(diagnostics.get(0).projectionMissMeters()>1);
        assertFalse(diagnostics.get(0).requiredForStoredFields());assertFalse(diagnostics.get(0).projectionPasses());assertEquals("UNUSED_OUTSIDE_ENVELOPE",diagnostics.get(0).dependencyStatus());
    }
    @Test void inactiveFiniteWeakVariationLeavesEveryStoredFieldBitwiseIdentical()throws Exception {
        var f=offNormal();var local=regression(3,.1,.5,.8);var stable=regression(2,.2,.8,.7);
        var first=call(f.builder,"assembleComponentSlice",slice(1),f.profiles.get(1).bands().get(0),1.0,local,stable,regression(10,99,.7,.1));
        var changed=call(f.builder,"assembleComponentSlice",slice(1),f.profiles.get(1).bands().get(0),1.0,local,stable,regression(-Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE,.99));
        assertEquals(first,changed,"Every stored tuple must be independent of exactly unused finite weak values");
        assertEquals(2.0,((CorridorTubeSlice)changed).stabilityCenterOffsetPx(),0);assertEquals(3.0,((CorridorTubeSlice)changed).localCenterOffsetPx(),0);
        assertEquals(-Double.MAX_VALUE,(Double)call(f.builder,"componentBlend",-Double.MAX_VALUE,Double.MAX_VALUE,0.0),0);
        assertEquals(3.0,(Double)call(f.builder,"componentBlend",-Double.MAX_VALUE,3.0,1.0),0);
    }
    @Test void anyPositiveWeakDependencyRefusesAndM1StillRequiresStoredStability()throws Exception {
        var f=offNormal();for(double w:new double[]{Double.MIN_VALUE,Math.ulp(1.0),.5,1}){
            assertTrue((Boolean)staticCall("requiredForStoredFields","weak32m",w));
            var diagnostics=new ArrayList<CorridorTubeBuilder.WorldFitDiagnostic>();fit(f,"weak32m",true,diagnostics);
            assertFalse((Boolean)staticCall("projectionAdmitted",diagnostics.get(0)));
        }
        assertTrue((Boolean)staticCall("requiredForStoredFields","stability12m",0.0));
        var stable=new ArrayList<CorridorTubeBuilder.WorldFitDiagnostic>();fit(f,"stability12m",true,stable);assertFalse((Boolean)staticCall("projectionAdmitted",stable.get(0)));
        assertTrue((Boolean)staticCall("requiredForStoredFields","local5m",1.0));assertFalse((Boolean)staticCall("requiredForStoredFields","stability12m",1.0));
        assertTrue((Boolean)staticCall("requiredForStoredFields","stability12m",Math.nextDown(1.0)));
    }
    @Test void inheritedProperSourceContactRetainsOrdinaryTubeAndLiteralCollars()throws Exception {
        double[][] xy={{-3,0},{-2,0},{-1,-1},{1,1},{-1,1},{1,-1},{2,0},{3,0},{4,0}};
        var profiles=new ArrayList<CorridorProfile>();var track=new LinkedHashMap<Integer,CorridorTrackPoint>();double distance=0;
        for(int i=0;i<xy.length;i++){
            if(i>0)distance+=StrictMath.hypot(xy[i][0]-xy[i-1][0],xy[i][1]-xy[i-1][1]);
            var band=new CorridorBand("inherited"+i,0,-2,2,-.5,.5,List.of(0.0),.8,.02,1,.95,.95,.5,false,List.of());
            var source=new RenderedHeatmapSampler.CrossSectionProfile(new ProfileSamplingAnchor(new EastNorth(xy[i][0],xy[i][1]),xy[i][0],xy[i][1],distance),new Point2D.Double(0,1),List.of(new RenderedHeatmapSampler.CrossSectionPeak(0,.8)),true,List.of());
            profiles.add(new CorridorProfile(i,source,List.of(band),.8,.02,.78,true));track.put(i,new CorridorTrackPoint(i,band,false));
        }
        var corridor=new CorridorTrack("inherited-proper",track,xy.length,1,false,List.of(),"");var builder=new CorridorTubeBuilder(RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),1,Set.of(0,xy.length-1));
        var original=new CorridorTubeBuilder(CorridorTubeBuilder.MotionPolicy.TARGET_OWNED_APEX).build(corridor,profiles,1,Map.of());var result=builder.buildComponentReference(corridor,profiles,1,Map.of());
        assertTrue(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.properIntersection(new MetricPoint(-1,-1),new MetricPoint(1,1),new MetricPoint(-1,1),new MetricPoint(1,-1)));
        assertEquals(original,result.tube());assertTrue(result.components().isEmpty());assertTrue(result.proposedTube().isEmpty());
    }
    @Test void singularAndNonfiniteFitsAreFatalEvenWhenUnused()throws Exception {
        var f=offNormal();var diagnostics=new ArrayList<CorridorTubeBuilder.WorldFitDiagnostic>();
        var duplicated=new Fixture(f.builder,f.profiles,List.of(f.observations.get(0),f.observations.get(0)),f.distances);
        assertThrows(InvocationTargetException.class,()->fit(duplicated,"weak32m",false,diagnostics));assertTrue(diagnostics.isEmpty());
        var type=f.observations.get(0).getClass();var constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
        Object invalid=constructor.newInstance(0,0.0,Double.POSITIVE_INFINITY,1.0,.5,0.0,false,false,CorridorPointSupport.DIRECT_UNION);
        var nonfinite=new Fixture(f.builder,f.profiles,List.of(invalid,f.observations.get(1),f.observations.get(2)),f.distances);
        assertThrows(InvocationTargetException.class,()->fit(nonfinite,"weak32m",false,diagnostics));assertTrue(diagnostics.isEmpty());
    }
    @Test void causalMaskSharesVerticesThenNormalizesOnlyAlreadyMarkedVertices()throws Exception {
        var seed=List.of(new CorridorTubeBuilder.ReferenceComponent(2,3));
        assertEquals(List.of(new CorridorTubeBuilder.ReferenceComponent(2,5)),mask(seed,List.of(new CorridorTubeBuilder.ReferenceComponent(3,5))));
        assertEquals(seed,mask(seed,List.of(new CorridorTubeBuilder.ReferenceComponent(4,6))),"Adjacent unmarked hard interval must not be absorbed");
        var adjacent=List.of(new CorridorTubeBuilder.ReferenceComponent(2,3),new CorridorTubeBuilder.ReferenceComponent(4,5));
        assertEquals(List.of(new CorridorTubeBuilder.ReferenceComponent(2,5)),mask(adjacent,List.of(new CorridorTubeBuilder.ReferenceComponent(6,8))));
        var sharedCollar=List.of(new CorridorTubeBuilder.ReferenceComponent(2,3),new CorridorTubeBuilder.ReferenceComponent(5,6));
        assertEquals(sharedCollar,mask(sharedCollar,List.of()));
        assertEquals(Set.of(1,4,7),staticCall("literalCollars",10,sharedCollar));
        assertEquals(Set.of(1,6),staticCall("literalCollars",10,mask(adjacent,List.of())));
    }
    @SuppressWarnings("unchecked")private static List<CorridorTubeBuilder.ReferenceComponent> mask(List<CorridorTubeBuilder.ReferenceComponent> seeds,List<CorridorTubeBuilder.ReferenceComponent> hard)throws Exception{return (List<CorridorTubeBuilder.ReferenceComponent>)staticCall("componentMask",10,seeds,hard);}
    private static Object staticCall(String name,Object...args)throws Exception{for(var method:CorridorTubeBuilder.class.getDeclaredMethods())if(method.getName().equals(name)&&method.getParameterCount()==args.length){method.setAccessible(true);return method.invoke(null,args);}throw new NoSuchMethodException(name);}
    private static Object regression(double center,double slope,double residual,double confidence)throws Exception{var type=Class.forName(CorridorTubeBuilder.class.getName()+"$Regression");var c=type.getDeclaredConstructor(double.class,double.class,double.class,double.class);c.setAccessible(true);return c.newInstance(center,slope,residual,confidence);}
    private static CorridorTubeSlice slice(double motion){return new CorridorTubeSlice(1,10,5,.3,5,.3,5,.3,.5,motion,"independent",0,-.5,.5,-2,2,.5,.9,false,false,0,0,0,true);}
    private static Object fit(Fixture f,String name,boolean required,List<CorridorTubeBuilder.WorldFitDiagnostic> diagnostics)throws Exception {
        for(var method:f.builder.getClass().getDeclaredMethods())if(method.getName().equals("worldRegression")&&method.getParameterCount()==9){method.setAccessible(true);return method.invoke(f.builder,f.observations,1,name,f.profiles,f.distances,1.0,f.profiles.get(1).bands().get(0),diagnostics,required);}
        // Behavioral RED executes the existing all-attempted-fit guard before the new seam exists.
        return call(f.builder,"worldRegression",f.observations,1,name,f.profiles,f.distances,1.0,f.profiles.get(1).bands().get(0),diagnostics);
    }
    private static Fixture offNormal()throws Exception {
        var builder=new CorridorTubeBuilder(RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),1,Set.of());
        var profiles=new ArrayList<CorridorProfile>();var observations=new ArrayList<Object>();double[] distances={0,10,20};
        for(int i=0;i<3;i++){
            double x=i==1?10:0;var band=new CorridorBand("fold"+i,0,-2,2,-.5,.5,List.of(0.0),.8,.02,1,.95,.95,.5,false,List.of());
            var source=new RenderedHeatmapSampler.CrossSectionProfile(new ProfileSamplingAnchor(new EastNorth(x,0),x,0,distances[i]),new Point2D.Double(0,1),List.of(new RenderedHeatmapSampler.CrossSectionPeak(0,.8)),true,List.of());
            profiles.add(new CorridorProfile(i,source,List.of(band),.8,.02,.78,true));observations.add(call(builder,"observation",new CorridorTrackPoint(i,band,false),profiles.get(i),distances,1.0,Map.of()));
        }
        return new Fixture(builder,profiles,observations,distances);
    }
    private static Object call(Object target,String name,Object...args)throws Exception{for(var method:target.getClass().getDeclaredMethods())if(method.getName().equals(name)&&method.getParameterCount()==args.length){method.setAccessible(true);return method.invoke(target,args);}throw new NoSuchMethodException(name);}
    private record Fixture(CorridorTubeBuilder builder,List<CorridorProfile> profiles,List<Object> observations,double[] distances) { }
}
