package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;
import java.util.*;
import org.openstreetmap.josm.data.coor.*;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
/** Analytic public scalar scene with parent geometry displaced from original OSM. */
final class RefinementParentFixtures {
 static RefinementRootAuthority authority(AlignmentMode mode) {return authority(mode,false,false,false);}
 static RefinementRootAuthority authority(AlignmentMode mode,boolean cleanup,boolean sparse,boolean gap) {
  var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(50,14),new GeographicPoint(49.99,13.99),new GeographicPoint(50.01,14.02));
  int w=161,h=101;double[] scalar=new double[w*h];boolean[] valid=new boolean[scalar.length];Arrays.fill(valid,true);
  for(int y=0;y<h;y++)for(int x=0;x<w;x++)scalar[y*w+x]=gap&&x>=70&&x<=86?.02:.02+.98*Math.exp(-.5*Math.pow((y-54.)/1.5,2));
  var field=new ScalarEvidenceField(w,h,scalar,valid,new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"native",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false));
  var region=MetricRegion.rectangle(1,1,159,99);
  var evidence=new EvidenceSnapshot("root-evidence",frame,RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),EvidenceResolution.nativeSource(2,1).resampledTo(1),region,MetricRegion.rectangle(-.5,-.5,160.5,100.5),Map.of("native",field),"synthetic-source");
  var way=PrimitiveKey.existing(PrimitiveKey.Type.WAY,10);var keys=new ArrayList<PrimitiveKey>();var values=new LinkedHashMap<PrimitiveKey,DetachedPrimitive>();
  for(int i=0;i<(sparse?2:5);i++){var key=PrimitiveKey.existing(PrimitiveKey.Type.NODE,i+1);keys.add(key);values.put(key,new DetachedNode(key,frame.toGeographic(new MetricPoint(sparse?20+120*i:20+30*i,50)),Map.of(),false,false));}
  values.put(way,new DetachedWay(way,keys,Map.of("highway","path"),false,false));
  var fixed=Set.of(keys.get(0),keys.get(keys.size()-1));var ordinary=new HashSet<>(keys);ordinary.removeAll(fixed);var editable=new HashSet<>(ordinary);editable.add(way);
  var closure=new ClosureDescriptor(ClosureDescriptor.Scope.EDIT_COMPONENT,"stepwise-test",values.keySet(),editable,mode==AlignmentMode.MOVE_EXISTING_NODES?ordinary:Set.of(),fixed,mode==AlignmentMode.PRECISE_SHAPE?ordinary:Set.of(),Map.of(way,List.of(new OccurrenceRange(0,keys.size()-1))),List.of(),region,MetricRegion.rectangle(5,40,155,65),mode==AlignmentMode.PRECISE_SHAPE,true,true,true);
  var network=new NetworkSnapshot("root-network",SnapshotRole.CAPTURED_BEFORE,"synthetic",1,closure,values,V022SnapshotFixtures.closedWorldReferrerWatches(values));
  var original=keys.stream().map(k->frame.toMetric(((DetachedNode)values.get(k)).coordinate())).toList();
  var request=new TraceRequest(way,new OccurrenceRange(0,keys.size()-1),TrackerMode.LEGACY_V02,mode,RecoveryPermissions.disabled(8),TraceBudgets.defaults(),evidence.snapshotId(),evidence.canonicalHash(),network.snapshotId(),network.canonicalHash(),"settings","parameters","legacy-root",3,new ProbabilisticProfileFactory().profileChainage(original,3),evidence.resolution());
  var config=RefinementSessionTest.config(mode);if(cleanup)config=new AlignmentConfig(config.heatmap(),GeometryCleanupPreset.STRONG.apply());
  return new RefinementRootAuthority(network,request,evidence,config,"EPSG:3857","synthetic-source");
 }
 static Candidate parent(RefinementRootAuthority root,String selected) {
  var frame=root.evidence().coordinateFrame();var projection=Projections.getProjectionByCode(root.projectionCode());var blue=new ArrayList<EastNorth>();var purple=new ArrayList<EastNorth>();
  for(int i=0;i<5;i++){var geo=frame.toGeographic(new MetricPoint(20+30*i,i==0||i==4?50:53));blue.add(projection.latlon2eastNorth(new LatLon(geo.latitudeDegrees(),geo.longitudeDegrees())));geo=frame.toGeographic(new MetricPoint(20+30*i,i==0||i==4?50:47));purple.add(projection.latlon2eastNorth(new LatLon(geo.latitudeDegrees(),geo.longitudeDegrees())));}
  if(root.request().selectedRange().size()==2){blue=new ArrayList<>(List.of(blue.get(0),blue.get(4)));purple=new ArrayList<>(List.of(purple.get(0),purple.get(4)));}
  var b=new CenterlineCandidate("blue-native",1,List.of(),List.of(),blue,blue,Map.of(),List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
  var q=new CenterlineCandidate("purple-native",2,List.of(),List.of(),purple,purple,Map.of(),List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
  var result=new AlignmentResult(selection(root),null,List.of(q,b),purple,purple,List.of(),null,null,List.of(new DetectorAttempt("blue","blue",TrackerMode.LEGACY_V02,DetectorAttemptStatus.APPLICABLE,List.of(b.id()),"",""),new DetectorAttempt("purple","purple",TrackerMode.LEGACY_V02,DetectorAttemptStatus.APPLICABLE,List.of(q.id()),"","")),List.of(q,b));
  var arcs=root.request().selectedRange().size()==2?List.of(new ParentArc(0,0,0),new ParentArc(1,0,1)):List.of(new ParentArc(0,0,0),new ParentArc(1,1,0),new ParentArc(2,2,0),new ParentArc(3,3,0),new ParentArc(4,3,1));
  return new Candidate(new CandidateId(selected+"-native"),result,arcs,"parent-"+selected);
 }
 static SelectionContext selection(RefinementRootAuthority root) {
  var source=(DetachedWay)root.network().primitives().get(root.request().selectedWayKey());
  var nodes=source.nodeKeys().stream().map(key->{var value=(DetachedNode)root.network().primitives().get(key);var c=value.coordinate();var node=new org.openstreetmap.josm.data.osm.Node(key.id(),1);node.setCoor(new LatLon(c.latitudeDegrees(),c.longitudeDegrees()));return node;}).toList();
  var way=new org.openstreetmap.josm.data.osm.Way(source.key().id(),1);way.setNodes(nodes);var range=root.request().selectedRange();
  return new SelectionContext(way,range.firstIndex(),range.lastIndex(),List.copyOf(nodes.subList(range.firstIndex(),range.lastIndex()+1)),Set.of());
 }

}
