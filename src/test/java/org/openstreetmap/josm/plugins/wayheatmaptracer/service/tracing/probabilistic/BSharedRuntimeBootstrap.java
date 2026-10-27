package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Stateless startup(A) entry. Original typed root is constructed before any case input. */
public final class BSharedRuntimeBootstrap {
    private BSharedRuntimeBootstrap() { }

    public static void main(String[] args) throws Exception {
        if(args.length==1&&"--host-control".equals(args[0])) {
            // Synchronize only. The reviewed HOST still checks this actual PID's
            // OS state before sending its one release byte. No runtime permit
            // or production initializer is issued or invoked on this branch.
            System.out.println("B_HOST_READY_1 "+ProcessHandle.current().pid());
            System.out.flush();
            if(System.in.read()!=1)throw new IllegalStateException("HOST control not released");
            System.out.println("PENDING_TRUSTED_HOST_STARTUP_BOOT_LAUNCH");return;
        }
        if(args.length==1&&"--host-control-gnu-v1".equals(args[0])){
            System.out.println("B_HOST_READY_1 "+ProcessHandle.current().pid());System.out.flush();
            BSharedOwnedCaptureProbe.Context context=null;Throwable primary=null;
            try{
                context=BPhysicalWork.prepareOwnedGnuUnit(System.in,ProcessHandle.current().pid());
                //A parsed frame is DATA,never a clock/source/startup permit.
                System.out.println("PENDING_GNU_CLOCK_SOURCE_RUNTIME");
            }catch(Exception|Error failure){primary=failure;throw failure;}
            finally{if(context!=null)try{context.close();}catch(RuntimeException|Error secondary){if(primary==null)throw secondary;}}
            return;
        }
        if(args.length!=1||!"--unit".equals(args[0])) {
            System.out.println("PENDING_TRUSTED_HOST_STARTUP_BOOT_LAUNCH");return;
        }
        ClassLoader system=ClassLoader.getSystemClassLoader();
        if(!system.getClass().getName().equals("jdk.internal.loader.ClassLoaders$AppClassLoader"))
            throw new IllegalStateException("Unsupported UNIT system loader");
        if(AttemptMemoryLedger.class.getClassLoader()!=system)
            throw new IllegalStateException("Foreign UNIT ledger definition");
        AttemptMemoryLedger ledger=new AttemptMemoryLedger(64L<<20);
        AttemptMemoryLedger.Owner root=ledger.rootOwner();
        //Direct original construction removes the reflective Constructor and
        //root Method returns. Load/root/startup work remains unqualified; no
        //case input or retained algorithm buffer is created here.
        Throwable primary=null;
        try {
            Class<?> entry=Class.forName("runtimeunit.ClosedDirectoryProbe",true,system);
            if(entry.getClassLoader()!=system)throw new IllegalStateException("Foreign UNIT entry definition");
            if(((Integer)entry.getMethod("initialize").invoke(null))!=32)
                throw new IllegalStateException("Unexpected UNIT initializer");
        } catch(Exception|Error failure) {primary=failure;throw failure;}
        finally {
            try {root.close();}
            catch(Exception|Error secondary) {if(primary==null)throw secondary;}
        }
        if(!root.isClosed())
            throw new IllegalStateException("UNIT root not closed");
        System.out.println("UNIT_ROOT_CREATED_CLOSED");
    }

    /** No factory exists until the real trusted HOST/startup producer is implemented. */
    static final class InitializationPermit {
        private boolean ownedAttemptStarted;
        private InitializationPermit() { }
        void require() {throw new UnsupportedOperationException("Production startup/load envelope UNSEALED");}
        void claimOwnedAttempt(){
            require();if(ownedAttemptStarted)throw new IllegalStateException("Owned runtime attempt consumed");
            ownedAttemptStarted=true;
        }
    }
}
