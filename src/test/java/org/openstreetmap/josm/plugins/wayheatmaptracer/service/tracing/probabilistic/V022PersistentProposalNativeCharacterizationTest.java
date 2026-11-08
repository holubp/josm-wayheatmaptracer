package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.PersistentProposalExperiment.*;

/** Fixed public-only native kernel characterization; no tracing engine or sampler. */
class V022PersistentProposalNativeCharacterizationTest {
    static final String COST_FORMULA="min((i<10?0:2)+25*d*d,(i<10?2:0)+25*(d-L_i)*(d-L_i));L_i=8*sin(pi*i/19)^2;endpoints=0";
    private static final int COUNT=20;
    private static final double STEP=1.56,PITCH=1;
    private static final AtomicInteger NATIVE_CALLS=new AtomicInteger();
    private static final List<String> LABELS=List.of("FULL_REFERENCE","PROPOSAL_UNION","UNION_PLUS_WINNER","UNION_PLUS_K32");
    record Fixture(List<Row> rows,List<CertifiedLink> links,List<InferenceProfile> profiles,double[][] unary,double[][][] adjacent) { }
    private record Prepared(Fixture fixture,ProposalSet proposals,List<PathResult> paths,List<List<Integer>> union,Work proposalWork,long proposalPeak) { }
    private record PathEvidence(List<Integer> originalIndices,long energyBits,String branch) { }
    private record NativeEvidence(String label,String status,List<PathEvidence> raw,Long pairVisits,Long scores,String work,long peak,boolean spliced,int unsupportedSamples) { }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static double left(double row){return row==0||row==COUNT-1?0:8*StrictMath.pow(StrictMath.sin(StrictMath.PI*row/(COUNT-1)),2);}
    private static double unary(int row,double offset){double delta=offset-left(row);return Math.min((row<10?0:2)+25*offset*offset,(row<10?2:0)+25*delta*delta);}
    static Fixture fixture() {
        List<Row> rows=new ArrayList<>();List<InferenceProfile> profiles=new ArrayList<>();List<CertifiedLink> links=new ArrayList<>();
        for(int row=0;row<COUNT;row++) {
            boolean fixed=row==0||row==COUNT-1;TreeSet<Double> original=new TreeSet<>();
            if(fixed)original.add(0.0);else {for(int d=-25;d<=25;d++)original.add((double)d);original.add(left(row));}
            List<Double> offsets=List.copyOf(original);int rightIndex=offsets.indexOf(0.0),leftIndex=offsets.indexOf(left(row));
            List<Band> bands=new ArrayList<>();bands.add(new Band(new BandRef(row,0),0,0,0,PITCH,List.of(rightIndex),BoundaryStatus.OBSERVED));
            if(!fixed)bands.add(new Band(new BandRef(row,1),left(row),left(row),0,PITCH,List.of(leftIndex),BoundaryStatus.OBSERVED));
            rows.add(new Row(row*STEP,new MetricPoint(row*STEP,0),new MetricPoint(0,1),bands,offsets,fixed?OptionalInt.of(0):OptionalInt.empty()));
            List<LateralStateCell> cells=new ArrayList<>();double[] costs=new double[offsets.size()];
            for(int state=0;state<offsets.size();state++) {
                double offset=offsets.get(state);
                double lo=state==0?-25:.5*(offsets.get(state-1)+offset),hi=state==offsets.size()-1?25:.5*(offset+offsets.get(state+1));
                boolean core=state==rightIndex||state==leftIndex;
                cells.add(new LateralStateCell(offset,fixed?1:Math.max(1e-12,hi-lo),fixed,core,
                    state==rightIndex?"p"+row+"-right":state==leftIndex?"p"+row+"-left":"unlocalized"));
                costs[state]=unary(row,offset);
            }
            profiles.add(new InferenceProfile(row*STEP,rows.get(row).originalMetricAnchor(),rows.get(row).originalUnitNormal(),cells,costs,
                ImageOrientationSupport.unknown(ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT),
                fixed?ObservationOwnership.FIXED_TOPOLOGY_ONLY:ObservationOwnership.DIRECT_TWO_SIDED,false));
        }
        for(int branch=0;branch<2;branch++) {
            int first=branch==0?rows.get(1).originalStateOffsetsM().indexOf(0.0):rows.get(1).originalStateOffsetsM().indexOf(left(1));
            int last=branch==0?rows.get(COUNT-2).originalStateOffsetsM().indexOf(0.0):rows.get(COUNT-2).originalStateOffsetsM().indexOf(left(COUNT-2));
            links.add(new CertifiedLink(new BandRef(0,0),new BandRef(1,branch),LinkKind.OBSERVED_JUNCTION,Set.of(new StatePair(0,first)),Map.of()));
            links.add(new CertifiedLink(new BandRef(COUNT-2,branch),new BandRef(COUNT-1,0),LinkKind.OBSERVED_JUNCTION,Set.of(new StatePair(last,0)),Map.of()));
        }
        var parameters=EvidenceModelParameters.defaults();double[][] integrated=new double[COUNT][];double[][][] adjacent=new double[COUNT-1][][];
        for(int row=0;row<COUNT;row++) {
            integrated[row]=new double[profiles.get(row).cells().size()];
            for(int state=0;state<integrated[row].length;state++)integrated[row][state]=ProbabilisticInference.profileEnergy(profiles,row,state,parameters);
            if(row>0) {
                var a=profiles.get(row-1);var b=profiles.get(row);adjacent[row-1]=new double[a.cells().size()][b.cells().size()];
                for(int x=0;x<a.cells().size();x++)for(int y=0;y<b.cells().size();y++) {
                    var p=a.point(x);var q=b.point(y);double heading=StrictMath.atan2(q.yMeters()-p.yMeters(),q.xMeters()-p.xMeters());
                    adjacent[row-1][x][y]=ProbabilisticInference.pairEnergyAtHeading(a,b,x,y,parameters,heading);
                }
            }
        }
        return new Fixture(List.copyOf(rows),List.copyOf(links),List.copyOf(profiles),integrated,adjacent);
    }
    private static Prepared prepare() {
        var f=fixture();require(f.rows().size()==COUNT&&f.profiles().size()==COUNT,"Fixed original20-profile inventory");
        for(int row=0;row<COUNT;row++) {
            var profile=f.profiles().get(row);boolean fixed=row==0||row==COUNT-1;
            require(profile.cells().size()==(fixed?1:52),"Full integer+analytic original inventory");
            if(!fixed) {
                require(profile.cells().get(0).offsetMeters()==-25&&profile.cells().get(51).offsetMeters()==25,"Full configured25m window");
                require(Math.abs(profile.cells().stream().mapToDouble(LateralStateCell::quadratureWidthMeters).sum()-50)<=Math.ulp(50)*2,"Original midpoint quadrature");
            } else require(profile.cells().get(0).exactAnchor()&&profile.point(0).equals(new MetricPoint(row*STEP,0)),"Exact original anchors");
            for(int state=0;state<profile.cells().size();state++)require(profile.unaryCost(state)==unary(row,profile.cells().get(state).offsetMeters()),"Frozen public unary formula");
        }
        require(EvidenceModelParameters.defaults().turnWeight()==2,"Current native turn term remains active");
        var ledger=AttemptMemoryLedger.production();Prepared prepared;
        try(var owner=ledger.rootOwner()) {
            var context=new Context(TraceBudgets.defaults(),CancellationProbe.NONE,owner);var proposals=reserve(f.rows(),f.links(),context);
            require(!proposals.overflow()&&proposals.reservations().size()==2&&proposals.reservations().stream().allMatch(r->r.status()==Status.COMPLETE),"Both complete observed reservations precede ranking");
            String originalUnary=Arrays.deepToString(f.unary()),originalAdjacent=Arrays.deepToString(f.adjacent());
            List<PathResult> paths=new ArrayList<>();for(var reservation:proposals.reservations())paths.add(firstOrderPath(reservation,f.unary(),f.adjacent(),context));
            List<List<Integer>> union=new ArrayList<>();
            for(int row=0;row<COUNT;row++) {
                TreeSet<Integer> retained=new TreeSet<>();for(var reservation:proposals.reservations())retained.addAll(reservation.rowsOfAllowedOriginalIndices().get(row));
                for(int state=0;state<f.profiles().get(row).cells().size();state++)if(f.profiles().get(row).cells().get(state).mandatory()||f.profiles().get(row).cells().get(state).exactAnchor())retained.add(state);
                union.add(List.copyOf(retained));
            }
            verifyViews(f.profiles(),union,owner);
            require(originalUnary.equals(Arrays.deepToString(f.unary()))&&originalAdjacent.equals(Arrays.deepToString(f.adjacent())),"Original cost arrays remain unchanged");
            prepared=new Prepared(f,proposals,List.copyOf(paths),List.copyOf(union),context.snapshot(),ledger.peakBytes());
        }
        require(ledger.currentBytes()==0,"Pure proposal owner release");return prepared;
    }
    private static List<InferenceProfile> verifyViews(List<InferenceProfile> full,List<List<Integer>> selected,AttemptMemoryLedger.Owner owner) {
        List<InferenceProfile> view=new ArrayList<>();
        for(int row=0;row<COUNT;row++) {
            var indices=selected.get(row);var original=full.get(row);var retained=original.retainedView(indices.stream().mapToInt(Integer::intValue).toArray(),owner);
            for(int i=0;i<indices.size();i++)require(retained.cells().get(i)==original.cells().get(indices.get(i))
                &&Double.doubleToRawLongBits(retained.unaryCost(i))==Double.doubleToRawLongBits(original.unaryCost(indices.get(i)))
                &&retained.point(i).equals(original.point(indices.get(i))),"Original cell/measure/cost/point identity");
            view.add(retained);
        }
        return List.copyOf(view);
    }
    @Test void sourcePreflight() {int before=NATIVE_CALLS.get();prepare();require(NATIVE_CALLS.get()==before,"No native call in source preflight");}
    private static List<List<Integer>> fullIndices(Fixture f) {
        return f.profiles().stream().map(p->java.util.stream.IntStream.range(0,p.cells().size()).boxed().toList()).toList();
    }
    private static List<List<Integer>> plus(List<List<Integer>> union,List<PathEvidence> reference) {
        List<List<Integer>> result=new ArrayList<>();
        for(int row=0;row<COUNT;row++){TreeSet<Integer> indices=new TreeSet<>(union.get(row));for(var path:reference)indices.add(path.originalIndices().get(row));result.add(List.copyOf(indices));}
        return List.copyOf(result);
    }
    private static boolean spliced(Prepared prepared,List<Integer> indices) {
        return prepared.proposals().reservations().stream().noneMatch(r->{for(int i=0;i<COUNT;i++)if(!r.rowsOfAllowedOriginalIndices().get(i).contains(indices.get(i)))return false;return true;});
    }
    private static int unsupported(Fixture fixture,List<Integer> indices) {
        int count=0;
        for(int row=0;row<COUNT-1;row++) {
            double a=fixture.rows().get(row).originalStateOffsetsM().get(indices.get(row));double b=fixture.rows().get(row+1).originalStateOffsetsM().get(indices.get(row+1));
            for(int sample=0;sample<32;sample++){double t=(sample+.5)/32;double y=a+t*(b-a);if(Math.abs(y)>PITCH&&Math.abs(y-left(row+t))>PITCH)count++;}
        }
        return count;
    }
    private static NativeEvidence nativeControl(String label,Prepared prepared,List<List<Integer>> selected,Path output)throws Exception {
        int number=NATIVE_CALLS.get();require(number<4&&LABELS.get(number).equals(label),"Exactly four ordered single-use native controls");
        var ledger=AttemptMemoryLedger.production();ProbabilisticInferenceResult result=null;List<PathEvidence> paths=new ArrayList<>();long peak=0;Throwable failure=null;
        try(var owner=ledger.rootOwner()) {
            var profiles=verifyViews(prepared.fixture().profiles(),selected,owner);
            NATIVE_CALLS.incrementAndGet();Files.writeString(output.resolve("native-calls.tsv"),(number+1)+"\t"+label+"\n",StandardOpenOption.APPEND);
            long started=System.nanoTime();CancellationProbe cancellation=()->Thread.currentThread().isInterrupted()||System.nanoTime()-started>100_000_000_000L;
            result=new ProbabilisticInference().solve(profiles,EvidenceModelParameters.defaults(),TraceBudgets.defaults(),
                MetricRegion.rectangle(0,-25,(COUNT-1)*STEP,25),cancellation,owner,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4);
            for(var path:result.rawPaths()) {
                int[] states=path.stateIndices();List<Integer> originals=new ArrayList<>();
                for(int row=0;row<COUNT;row++)originals.add(selected.get(row).get(states[row]));
                require(prepared.fixture().profiles().get(0).point(originals.get(0)).equals(new MetricPoint(0,0))
                    &&prepared.fixture().profiles().get(COUNT-1).point(originals.get(COUNT-1)).equals(new MetricPoint((COUNT-1)*STEP,0)),"Exact protected anchors");
                paths.add(new PathEvidence(List.copyOf(originals),Double.doubleToRawLongBits(path.energy()),path.branchSignature()));
            }
            peak=ledger.peakBytes();
        } catch(Throwable thrown){failure=thrown;peak=ledger.peakBytes();}
        String status=result==null?"THREW_"+(failure==null?"UNKNOWN":failure.getClass().getSimpleName()):result.status().name();
        Long pairs=result!=null?result.evaluatedPairVisits():failure instanceof TraceMemoryLimitException limited?limited.pairVisits():null;
        Long scores=result!=null?result.evaluatedTransitions():failure instanceof TraceMemoryLimitException limited?limited.transitions():null;
        var evidence=new NativeEvidence(label,status,List.copyOf(paths),pairs,scores,
            result==null?"UNAVAILABLE":result.workAccounting().toString(),peak,!paths.isEmpty()&&spliced(prepared,paths.get(0).originalIndices()),
            paths.isEmpty()?0:unsupported(prepared.fixture(),paths.get(0).originalIndices()));
        StringBuilder text=new StringBuilder("diagnosticOnly=true\nlabel="+label+"\nstatus="+status+"\noriginalStates="+selected.stream().mapToInt(List::size).sum()+"\n")
            .append("pairVisits="+(pairs==null?"UNAVAILABLE":pairs)+"\nscoredTransitions="+(scores==null?"UNAVAILABLE":scores)+"\nwork="+evidence.work()+"\nownerPeakBytes="+peak+"\nownerCurrentBytesAfterClose="+ledger.currentBytes()+"\n")
            .append("nativeCallsSoFar="+NATIVE_CALLS.get()+"\nrawPaths="+paths.size()+"\nconditionalOnRetainedGraph="+!label.equals("FULL_REFERENCE")+"\n")
            .append("splicedAcrossReservations="+evidence.spliced()+"\nunsupportedAnalyticMidpoints="+evidence.unsupportedSamples()+"\nposteriorFullGraphEqualityClaim=false\nqualification=false\n");
        Files.writeString(output.resolve(label+"-result.txt"),text,StandardOpenOption.CREATE_NEW);
        try(var writer=Files.newBufferedWriter(output.resolve(label+"-paths.tsv"),StandardOpenOption.CREATE_NEW)) {
            writer.write("rank\tenergy_rawbits_hex\tenergy_hex\toriginal_state_indices\tbranch\n");
            for(int i=0;i<paths.size();i++){var p=paths.get(i);writer.write(i+"\t"+Long.toHexString(p.energyBits())+"\t"+Double.toHexString(Double.longBitsToDouble(p.energyBits()))+"\t"+p.originalIndices()+"\t"+p.branch()+"\n");}
        }
        if(failure!=null){Files.writeString(output.resolve(label+"-failure.txt"),failure.toString(),StandardOpenOption.CREATE_NEW);throw new AssertionError("Fixed native control failed; no retry; ownerCurrentBytes="+ledger.currentBytes(),failure);}
        require(ledger.currentBytes()==0,"Native caller owner release");
        require(!paths.isEmpty()&&!List.of("RESOURCE_LIMIT","CANCELLED","NO_ROUTE").contains(status),"Native control refusal ends fixed experiment: "+status);
        return evidence;
    }
    private static void samePath(PathEvidence expected,PathEvidence actual) {
        require(expected.originalIndices().equals(actual.originalIndices())&&expected.energyBits()==actual.energyBits()&&expected.branch().equals(actual.branch()),"Exact original-state/energy/order/branch control");
    }
    private static String sha(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    private static void requireImmutableProductionJar()throws Exception {
        Path jar=Path.of(ProbabilisticInference.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        require(Files.isRegularFile(jar)&&sha(jar).equals("3b83cc3a0ed6c5828f5c0ecd087c3e217a601e41cbc8836f4b8982f05b324990"),"Actual production inference must load pinned CI026 JAR");
    }
    /** Manual diagnostic only: ordinary JUnit executes sourcePreflight and never calls nativeControl. */
    public static void main(String[] args)throws Exception {
        requireImmutableProductionJar();
        if(args.length==1&&args[0].equals("--preflight-only")) {
            var p=prepare();require(NATIVE_CALLS.get()==0,"Zero native preflight calls");
            System.out.println("PURE_PREFLIGHT_PASS;formula="+COST_FORMULA+";reservations="+p.proposals().reservations().size()+";work="+p.proposalWork()+";nativeCalls=0");return;
        }
        require(args.length==2&&args[0].equals("--execute-once")&&"FIXED_PUBLIC_4_ONCE".equals(System.getenv("PERSISTENT_CHARACTERIZATION_EXECUTE")),"Explicit once-only public characterization guard");
        Path output=Path.of(args[1]);Files.createDirectory(output);Files.createFile(output.resolve("native-calls.tsv"));
        try {
            var prepared=prepare();Files.writeString(output.resolve("fixture.txt"),"formula="+COST_FORMULA+"\nparameters="+EvidenceModelParameters.defaults()+"\nlimits="+TraceBudgets.defaults()+"\nproposalWork="+prepared.proposalWork()+"\nproposalPeakBytes="+prepared.proposalPeak()+"\nproposalOwnerReleasedBytes=0\nreservations="+prepared.proposals()+"\nfirstOrderPaths="+prepared.paths()+"\n",StandardOpenOption.CREATE_NEW);
            var full=nativeControl(LABELS.get(0),prepared,fullIndices(prepared.fixture()),output);
            require(full.raw().size()==32,"Reference original rawK32 must be available");
            require(Double.longBitsToDouble(full.raw().get(0).energyBits())<Double.longBitsToDouble(full.raw().get(1).energyBits()),"Reference winner must be unique; no formula retuning");
            var candidate=nativeControl(LABELS.get(1),prepared,prepared.union(),output);
            var winner=nativeControl(LABELS.get(2),prepared,plus(prepared.union(),List.of(full.raw().get(0))),output);samePath(full.raw().get(0),winner.raw().get(0));
            var k32=nativeControl(LABELS.get(3),prepared,plus(prepared.union(),full.raw()),output);
            require(k32.raw().size()==full.raw().size(),"Exact retained rawK32 count");for(int i=0;i<full.raw().size();i++)samePath(full.raw().get(i),k32.raw().get(i));
            boolean modelNeeded=candidate.spliced()||candidate.unsupportedSamples()>0||winner.spliced()||k32.spliced();
            long pairs=full.pairVisits()+candidate.pairVisits()+winner.pairVisits()+k32.pairVisits(),scores=full.scores()+candidate.scores()+winner.scores()+k32.scores();
            Files.writeString(output.resolve("summary.txt"),"controlStatus=PASS\nverdict="+(modelNeeded?"MODEL_CONTINUITY_STILL_REQUIRED":"NO_SPLICE_ON_THIS_FIXTURE_NOT_A_MODEL_QUALIFICATION")
                +"\nnativeCalls="+NATIVE_CALLS.get()+"\nproposalWork="+prepared.proposalWork()+"\nseparateNativePairVisits="+pairs+"\nseparateNativeScores="+scores
                +"\nwinnerSequenceEnergyControl=PASS\nrawK32SequenceEnergyOrderControl=PASS\nposteriorFullGraphEqualityRequired=false\nqualification=false\nreleaseQualified=false\n",StandardOpenOption.CREATE_NEW);
        } catch(Throwable failure) {
            Files.writeString(output.resolve("experiment-failure.txt"),"fixedExperiment=FAILED\nnativeCalls="+NATIVE_CALLS.get()+"\ncause="+failure+"\nnoRetryOrRetuning=true\n",StandardOpenOption.CREATE_NEW);
            throw failure;
        } finally {Files.writeString(output.resolve("native-call-count.txt"),NATIVE_CALLS.get()+"\n",StandardOpenOption.CREATE_NEW);}
    }
}
