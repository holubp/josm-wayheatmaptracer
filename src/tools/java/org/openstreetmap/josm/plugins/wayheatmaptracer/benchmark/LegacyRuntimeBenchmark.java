package org.openstreetmap.josm.plugins.wayheatmaptracer.benchmark;

import java.lang.management.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Fixed public-only schedule; readiness controls never invoke a timed kernel. */
public final class LegacyRuntimeBenchmark {
    private static final String PROBE="org.openstreetmap.josm.plugins.wayheatmaptracer.benchmark.LegacyRuntimeProbe";
    private static final String[] FIXTURES={"parallel-185","weak-gap-185","switchback-512"};
    private static final String[] VERSIONS={"RC14","CORRECTED323"};
    private record Ready(boolean valid,double medianRatio,double fittedDrift,boolean ready) { }
    private record Counters(long compilation,long count,long time) { }
    private static double median(double[] values,int from,int to) {
        var copy=Arrays.copyOfRange(values,from,to);Arrays.sort(copy);return (copy[9]+copy[10])/2;
    }
    private static Ready readiness(double[] samples) {
        if(samples.length!=100)throw new IllegalArgumentException("Exactly100 fixed warmup batches required");
        for(double value:samples)if(!Double.isFinite(value)||value<=0)return new Ready(false,Double.NaN,Double.NaN,false);
        double ratio=median(samples,80,100)/median(samples,60,80),slope=0;
        for(int i=0;i<20;i++)slope+=(i-9.5)*Math.log(samples[80+i]);
        double drift=Math.expm1(19*slope/665);
        return new Ready(true,ratio,drift,ratio>=.9&&ratio<=1.1&&Math.abs(drift)<=.1);
    }
    private static int versionAt(int pair,int position){return pair%2==0?position:1-position;}
    private static Counters counters() {
        var compiler=ManagementFactory.getCompilationMXBean();long compilation=compiler!=null&&compiler.isCompilationTimeMonitoringSupported()?compiler.getTotalCompilationTime():-1;
        long count=0,time=0;boolean countKnown=true,timeKnown=true;var collectors=ManagementFactory.getGarbageCollectorMXBeans();
        if(collectors.isEmpty()){countKnown=false;timeKnown=false;}
        for(var collector:collectors) {
            long c=collector.getCollectionCount(),t=collector.getCollectionTime();
            if(c<0)countKnown=false;else count+=c;if(t<0)timeKnown=false;else time+=t;
        }
        return new Counters(compilation,countKnown?count:-1,timeKnown?time:-1);
    }
    private static Object call(Object probe,String method,Class<?>[] types,Object...args) throws Exception {
        try{return probe.getClass().getMethod(method,types).invoke(probe,args);}
        catch(InvocationTargetException failure){
            if(failure.getCause() instanceof Exception cause)throw cause;
            if(failure.getCause() instanceof Error cause)throw cause;throw failure;
        }
    }
    private static Object call(Object probe,String method) throws Exception{return call(probe,method,new Class<?>[0]);}
    private static void space(Path output) throws Exception {
        if(Files.getFileStore(output).getUsableSpace()<512L*1024*1024)throw new IllegalStateException("512MiB disk reserve required");
    }
    private static double batch(Object probe,Path output,String fixture,int version,String phase,int index,int calls,String input,String[] reference) throws Exception {
        space(output);var before=counters();long nanos=(Long)call(probe,"runBatch",new Class<?>[]{int.class},calls);var after=counters();
        String hash=call(probe,"outputHash").toString();
        if(reference[0]==null)reference[0]=hash;
        boolean unchanged=input.equals(call(probe,"currentInputHash"));
        System.out.println("sample fixture="+fixture+" version="+VERSIONS[version]+" phase="+phase+" index="+index+" calls="+calls+" batchNanos="+nanos
            +" output="+hash+" batchOutput="+call(probe,"batchHash")+" candidates="+call(probe,"candidates")
            +" compilerBefore="+before.compilation()+" compilerAfter="+after.compilation()+" gcCountBefore="+before.count()+" gcCountAfter="+after.count()
            +" gcMillisBefore="+before.time()+" gcMillisAfter="+after.time());
        if(!reference[0].equals(hash)||!unchanged)throw new AssertionError("Hard full-bit or input disagreement");
        return (double)nanos/calls;
    }
    private static String number(double value){return Double.isFinite(value)?Double.toString(value):"UNAVAILABLE";}
    private static void controls() {
        double[] values=new double[100];Arrays.fill(values,1);Arrays.fill(values,0,60,20);
        if(!readiness(values).ready())throw new AssertionError("Fixed earlier warmups changed late-window readiness");
        Arrays.fill(values,80,100,1.1);if(!readiness(values).ready())throw new AssertionError("Inclusive high boundary lost");
        Arrays.fill(values,80,100,Math.nextUp(1.1));if(readiness(values).ready())throw new AssertionError("High boundary bypassed");
        Arrays.fill(values,80,100,.9);if(!readiness(values).ready())throw new AssertionError("Inclusive low boundary lost");
        Arrays.fill(values,80,100,Math.nextDown(.9));if(readiness(values).ready())throw new AssertionError("Low boundary bypassed");
        for(int i=0;i<20;i++)values[80+i]=Math.exp((i-9.5)*.02);
        if(readiness(values).ready())throw new AssertionError("Fitted trend bypassed");
        values[0]=0;if(readiness(values).valid())throw new AssertionError("Zero observation accepted");
        values[0]=Double.NaN;if(readiness(values).valid())throw new AssertionError("Unknown observation accepted");
        int[] first=new int[2];for(int i=0;i<16;i++){int v=versionAt(i,0);first[v]++;if(versionAt(i,1)!=1-v)throw new AssertionError("Pair lost");}
        if(!Arrays.equals(first,new int[]{8,8}))throw new AssertionError("Order imbalance");
        System.out.println("PUBLIC_READINESS_SCHEDULE_CONTROLS_PASS_NO_KERNEL");
    }
    public static void main(String[] args) throws Exception {
        if(args.length==1&&args[0].equals("--controls")){controls();return;}
        if(args.length!=6||!args[0].equals("--benchmark")||!args[5].matches("[0-9a-f]{64}")
                ||!"true".equals(System.getenv("GITHUB_ACTIONS"))||!"workflow_dispatch".equals(System.getenv("GITHUB_EVENT_NAME"))
                ||!"1".equals(System.getenv("GITHUB_RUN_ATTEMPT"))||!"true".equals(System.getenv("ORDINARY_LEGACY_BENCHMARK")))
            throw new IllegalArgumentException("Only a fresh explicit public CI dispatch may benchmark");
        if(Runtime.version().feature()!=17||Runtime.getRuntime().maxMemory()!=536870912L)throw new IllegalArgumentException("Normal Java17/512MiB heap required");
        for(String flag:ManagementFactory.getRuntimeMXBean().getInputArguments())if(flag.contains("Xint")||flag.contains("Tiered")||flag.contains("CompileThreshold")||flag.contains("javaagent"))
            throw new IllegalArgumentException("Special compilation modes are refused");
        Path output=Path.of(args[4]);space(output);Files.createFile(output.resolve("benchmark-once.claim"));
        URLClassLoader[] loaders=new URLClassLoader[2];
        try {
            URL helper=LegacyRuntimeBenchmark.class.getProtectionDomain().getCodeSource().getLocation();
            for(int version=0;version<2;version++)loaders[version]=new URLClassLoader(new URL[]{helper,Path.of(args[1+version]).toUri().toURL(),Path.of(args[3]).toUri().toURL()},ClassLoader.getPlatformClassLoader());
            counters();System.out.println("schema=ordinary-legacy-public-runtime-v1 fingerprint="+args[5]+" java="+Runtime.version()+" heap="+Runtime.getRuntime().maxMemory());
            for(String fixture:FIXTURES) {
                Object[] probes=new Object[2];String[] inputs=new String[2],reference={null};double[][] warm=new double[2][100];
                for(int version=0;version<2;version++) {
                    space(output);var type=Class.forName(PROBE,true,loaders[version]);probes[version]=type.getConstructor(String.class).newInstance(fixture);
                    if(!call(probes[version],"origin").toString().equals(Path.of(args[1+version]).toUri().toURL().toString()))throw new AssertionError("Wrong class origin");
                    inputs[version]=call(probes[version],"inputHash").toString();
                }
                if(!inputs[0].equals(inputs[1]))throw new AssertionError("Public input bits differ");System.out.println("fixture="+fixture+" input="+inputs[0]);
                for(int index=0;index<100;index++)for(int position=0;position<2;position++) {
                    int version=versionAt(index,position);warm[version][index]=batch(probes[version],output,fixture,version,"WARMUP",index,32,inputs[version],reference);
                }
                boolean ready=true;
                for(int version=0;version<2;version++) {
                    var r=readiness(warm[version]);ready&=r.ready();
                    System.out.println("readiness fixture="+fixture+" version="+VERSIONS[version]+" ready="+r.ready()+" medianRatio="+number(r.medianRatio())+" fittedDrift="+number(r.fittedDrift()));
                }
                if(!ready){System.out.println("outcome=INCONCLUSIVE fixture="+fixture+" reason=WARMUP_NOT_READY");return;}
                for(int index=0;index<16;index++)for(int position=0;position<2;position++) {
                    int version=versionAt(index,position);batch(probes[version],output,fixture,version,"MEASURED",index,64,inputs[version],reference);
                }
                System.out.println("fixtureComplete="+fixture);
            }
            System.out.println("outcome=COMPLETE");
        }finally {for(var loader:loaders)if(loader!=null)loader.close();}
    }
}
