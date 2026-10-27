    static final class Probe implements CancellationProbe {
        final LongSupplier clock;final CancellationProbe delegate;final long started;
        final java.util.function.BooleanSupplier forbiddenFrame;
        long checkpoints;long cancelAt=Long.MAX_VALUE;boolean allowEngine;
        Probe(){this(System::nanoTime,CancellationProbe.NONE);}
        Probe(LongSupplier clock,CancellationProbe delegate){this(clock,delegate,()->WALKER.walk(frames->frames.anyMatch(f->
            f.getClassName().endsWith(".ProbabilisticProfileFactory")||f.getClassName().endsWith(".ProbabilisticInference")
            ||f.getClassName().endsWith(".ProbabilisticObservationModel")||f.getClassName().endsWith(".ProbabilisticTraceEngine"))));}
        Probe(LongSupplier clock,CancellationProbe delegate,java.util.function.BooleanSupplier forbiddenFrame){this.clock=clock;this.delegate=delegate;this.forbiddenFrame=forbiddenFrame;started=clock.getAsLong();}
        public boolean cancelled(){
            checkpoints++;
            // Capture-only admission needs the stack observer. Once native
            // execution is explicitly allowed, its result cannot affect the
            // decision; retain every cancellation/deadline check below.
            boolean forbidden=!allowEngine&&forbiddenFrame.getAsBoolean();
            if(forbidden&&!allowEngine)throw new SecurityException("Numerical entry in CAPTURE_ONLY");
            return delegate.cancelled()||checkpoints>=cancelAt||clock.getAsLong()-started>=300_000_000_000L;
        }
    }

