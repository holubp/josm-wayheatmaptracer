package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;

/** Sound one-sided proof for unchanged cached-V4 inference on a nested retained graph. */
public final class BRefinementBudget {
    public enum Decision { UNKNOWN, PROVEN_UNAFFORDABLE }
    public enum Outcome { DENSE, NOT_NEEDED, ORIGINAL_REFINEMENT, FIRST_GRAPH_PROVEN_UNAFFORDABLE }
    private static final String PREFIX="b-refinement-budget-v1";
    private static final List<String> KEYS=List.of("outcome","forwardBackwardScores","kBestHeadScores",
            "scoredTransitions","pairVisits","remainingScores","lowerBoundScores","originalIndexHash");

    /** Bounded exported evidence. Live execution never parses this evidence to make its decision. */
    public record Witness(Outcome outcome, Optional<CompletedBPassWork> first,
            OptionalLong remainingScores, OptionalLong lowerBoundScores, String originalIndexHash) {
        public Witness {
            if(outcome==null||first==null||remainingScores==null||lowerBoundScores==null
                    ||originalIndexHash==null||!originalIndexHash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Incomplete refinement witness");
            if(outcome==Outcome.DENSE) {
                if(first.isPresent()||remainingScores.isPresent()||lowerBoundScores.isPresent())
                    throw new IllegalArgumentException("Dense execution has no retained first pass");
            } else {
                if(first.isEmpty()||remainingScores.isEmpty()||lowerBoundScores.isEmpty()
                        ||remainingScores.getAsLong()<0)
                    throw new IllegalArgumentException("Completed retained pass is required");
                var work=first.orElseThrow();
                long lower=Math.addExact(work.forwardBackwardScores(),work.kBestHeadScores());
                if(lowerBoundScores.getAsLong()!=lower
                        ||outcome==Outcome.FIRST_GRAPH_PROVEN_UNAFFORDABLE
                            &&assess(work,remainingScores.getAsLong())!=Decision.PROVEN_UNAFFORDABLE
                        ||outcome==Outcome.ORIGINAL_REFINEMENT
                            &&assess(work,remainingScores.getAsLong())!=Decision.UNKNOWN)
                    throw new IllegalArgumentException("Refinement witness contradicts decision");
            }
        }
        public String canonical() {
            var work=first.orElse(null);
            return PREFIX+"|outcome="+outcome
                +"|forwardBackwardScores="+(work==null?"unavailable":work.forwardBackwardScores())
                +"|kBestHeadScores="+(work==null?"unavailable":work.kBestHeadScores())
                +"|scoredTransitions="+(work==null?"unavailable":work.scoredTransitions())
                +"|pairVisits="+(work==null?"unavailable":work.pairVisits())
                +"|remainingScores="+value(remainingScores)+"|lowerBoundScores="+value(lowerBoundScores)
                +"|originalIndexHash="+originalIndexHash;
        }
    }
    private static String value(OptionalLong n) { return n.isPresent()?Long.toString(n.getAsLong()):"unavailable"; }
    private static OptionalLong count(String text) {
        if("unavailable".equals(text))return OptionalLong.empty();
        if(text==null||!text.matches("0|[1-9][0-9]*"))throw new IllegalArgumentException("Invalid witness count");
        try{return OptionalLong.of(Long.parseLong(text));}
        catch(NumberFormatException invalid){throw new IllegalArgumentException("Witness count overflow",invalid);}
    }
    public static Witness parse(String text) {
        if(text==null||text.length()>2048||!text.chars().allMatch(c->c>=32&&c<=126))
            throw new IllegalArgumentException("Invalid witness encoding");
        var parts=text.split("\\|",-1);
        if(parts.length!=KEYS.size()+1||!PREFIX.equals(parts[0]))
            throw new IllegalArgumentException("Invalid witness keys");
        Map<String,String> fields=new LinkedHashMap<>();
        for(int i=1;i<parts.length;i++) {
            var entry=parts[i].split("=",2);
            if(entry.length!=2||!KEYS.get(i-1).equals(entry[0])||fields.put(entry[0],entry[1])!=null)
                throw new IllegalArgumentException("Invalid witness key");
        }
        var f=count(fields.get("forwardBackwardScores"));var h=count(fields.get("kBestHeadScores"));
        var s=count(fields.get("scoredTransitions"));var p=count(fields.get("pairVisits"));
        boolean absent=f.isEmpty()&&h.isEmpty()&&s.isEmpty()&&p.isEmpty();
        if(!absent&&(f.isEmpty()||h.isEmpty()||s.isEmpty()||p.isEmpty()))
            throw new IllegalArgumentException("Partial completed work");
        return new Witness(Outcome.valueOf(fields.get("outcome")),
                absent?Optional.empty():Optional.of(new CompletedBPassWork(f.getAsLong(),h.getAsLong(),s.getAsLong(),p.getAsLong())),
                count(fields.get("remainingScores")),count(fields.get("lowerBoundScores")),fields.get("originalIndexHash"));
    }
    public static Optional<Witness> fromExplanation(String text) {
        if(text==null)throw new IllegalArgumentException("Explanation required");
        Witness found=null;
        for(String line:text.split("\\n"))if(line.startsWith(PREFIX)) {
            if(found!=null)throw new IllegalArgumentException("Duplicate refinement witness");
            found=parse(line);
        }
        return Optional.ofNullable(found);
    }
    /** Checks exported scalar evidence against the actual native cumulative work and frozen limit. */
    public static void validateAccounting(Witness witness,
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets budgets,
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.ExecutionWorkReceipt receipt) {
        if(witness==null||budgets==null||receipt==null||receipt.bStages().size()!=1
                ||receipt.otherEngineTransitions()!=0||receipt.computedTransitions()>budgets.maximumTransitions())
            throw new IllegalArgumentException("Refinement work binding is incomplete");
        var work=receipt.bStages().get(0);
        if(work.refusedScoreAttempts()!=0)throw new IllegalArgumentException("Refused execution is not completed");
        if(witness.outcome()==Outcome.DENSE)return;
        var first=witness.first().orElseThrow();
        if(Math.addExact(first.scoredTransitions(),witness.remainingScores().orElseThrow())!=budgets.maximumTransitions()
                ||first.pairVisits()>budgets.maximumPairVisits())
            throw new IllegalArgumentException("Refinement remaining budget differs");
        if(witness.outcome()==Outcome.ORIGINAL_REFINEMENT) {
            if(receipt.computedTransitions()<Math.addExact(first.scoredTransitions(),witness.lowerBoundScores().orElseThrow())
                    ||work.forwardBackwardScores()<Math.multiplyExact(2,first.forwardBackwardScores())
                    ||work.kBestScores()<Math.addExact(first.scoredTransitions()-first.forwardBackwardScores(),first.kBestHeadScores()))
                throw new IllegalArgumentException("Refinement work is below the proved minimum");
        } else if(receipt.computedTransitions()!=first.scoredTransitions()
                ||work.forwardBackwardScores()!=first.forwardBackwardScores()
                ||work.kBestScores()!=first.scoredTransitions()-first.forwardBackwardScores())
            throw new IllegalArgumentException("First graph work differs");
    }
    private BRefinementBudget() { }
    static Decision assess(CompletedBPassWork first, long remainingScores) {
        if (first == null || remainingScores < 0)
            throw new IllegalArgumentException("Completed work and nonnegative remaining budget required");
        return Math.addExact(first.forwardBackwardScores(), first.kBestHeadScores()) > remainingScores
            ? Decision.PROVEN_UNAFFORDABLE : Decision.UNKNOWN;
    }
}
