package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

/**
 * Fixed owned comparison orchestration, under construction. No runtime permit
 * or numerical qualification is granted by this caller. A live captured view
 * reaches the existing source-authority gate before any downstream allocation;
 * the unfinished profile/table/publication path is never reported as success.
 */
final class BSharedPositionComparison {
    private BSharedPositionComparison() { }

    /** Fixed metadata selection only; does not initialize or render fixtures. */
    static void requireAttempt(String id,int gain,int etaIndex) {
        if(id==null||gain!=1&&gain!=4&&gain!=16&&gain!=64||etaIndex<0||etaIndex>=4)
            throw new IllegalArgumentException("Unsupported fixed comparison attempt");
        boolean known=switch(id){
            case "strong-parallel-gap","connected-straight","connected-short-bend","connected-sharp-apex",
                "faint-six-metre-bend","faint-twelve-metre-bend","genuine-junction",
                "positive-twenty-five-representation","negative-twenty-five-representation",
                "flecks-versus-faint-coherent","valid-sparse-gap","invalid-unknown-gap","censored-flank",
                "strong-modulated-versus-quiet-faint","unknown-detour","uniform-null","alternating-null",
                "half-pitch-alias","quarter-phase-alias","reverse-original-mesh","thin-three-strands",
                "thin-three-strands-intermittent","thin-strands-merge-separate","drifting-strong-with-faint-original",
                "thin-strong-sharp-bend","thin-faint-coherent-sharp-bend","thin-local-overlap-noise",
                "switchback-thin-strong","switchback-thin-faint","same-trail-three-strong",
                "same-trail-five-strong","same-trail-three-separated-strong" -> true;
            default -> false;
        };
        if(!known)throw new IllegalArgumentException("Unknown fixed comparison case");
    }

    /**
     * One context, Work, root, probe, admission and Session. Invalid metadata or
     * unsealed authority leaves the borrowed context untouched. Once Work is
     * created this entry owns cleanup; failures preserve the original cause.
     * No callbacks, arbitrary clock, retry, UNIT fallback or escaped capability.
     */
    static void runOwned(BSharedRuntimeBootstrap.InitializationPermit permit,
            BSharedOwnedCaptureProbe.Context context,String id,int gain,int etaIndex) {
        requireAttempt(id,gain,etaIndex);
        BPhysicalWork physical=BSharedRuntimeInitializer.openOwnedWork(permit,context);
        BSharedCaptureAdmission admission=null;
        BExperimentalScalarCapture session=null;
        BPreparedNativeChainage prepared=null;
        BPreparedNativeParameters nativeParameters=null;
        BExperimentalScalarCapture.CapturedProfiles nativeProfiles=null;
        BIdentificationFactsExtractor.CapturedFacts nativeFacts=null;
        BExperimentalScalarCapture.CapturedLattices nativeLattices=null;
        try{
            admission=BSharedCaptureAdmission.openRuntime(permit,physical,context);
            session=BExperimentalScalarCapture.open(physical.owner(),physical.sourceWork(),physical.probe());
            var captured=V022BGapMatchedControlsTest.captureOriginalOwnedAtOriginalSite(id,admission,session);
            var path=session.acquisitionPath(captured.view());
            session.requireTableSourceAuthority();
            prepared=BPreparedNativeChainage.prepareData(session,captured.view(),path,physical);
            nativeParameters=BPreparedNativeParameters.prepareData(physical);
            nativeProfiles=session.createProfiles(captured.view(),"scalar",prepared.chainage(),
                    25,true,nativeParameters.parameters());
            physical.sourceWork().metadata(16);
            nativeFacts=new BIdentificationFactsExtractor().extractCapturedPosition(session,captured.view(),
                    "scalar",nativeProfiles,physical.probe(),physical.owner(),physical.sourceWork());
            nativeLattices=session.createLattices(nativeProfiles,nativeFacts);
            var capturedInput=BSharedPositionEvidence.CapturedInput.create(session,captured.view(),"scalar",
                    nativeProfiles,nativeFacts,nativeLattices,physical.owner(),physical.probe(),physical.sourceWork());
            var publication=BPhysicalPairTable.capturedPublicationBoundsData(session,captured.view(),path,physical);
            // If the authority gate changes, require the unfinished consumer
            // explicitly rather than quietly treating capture-only as a run.
            throw new UnsupportedOperationException("Owned publication/table/solver consumer is incomplete");
        }catch(RuntimeException|Error primary){
            if(nativeLattices!=null)try{nativeLattices.close();}catch(RuntimeException|Error secondary){/* preserve primary */}
            if(nativeFacts!=null)try{nativeFacts.close();}catch(RuntimeException|Error secondary){/* preserve primary */}
            if(nativeProfiles!=null)try{nativeProfiles.close();}catch(RuntimeException|Error secondary){/* preserve primary */}
            if(nativeParameters!=null)try{nativeParameters.close();}catch(RuntimeException|Error secondary){/* preserve primary */}
            if(prepared!=null)try{prepared.close();}catch(RuntimeException|Error secondary){/* preserve primary */}
            if(session!=null)try{session.close();}catch(RuntimeException|Error secondary){/* preserve primary */}
            if(admission!=null)try{admission.close();}catch(RuntimeException|Error secondary){/* preserve primary */}
            try{physical.close();}catch(RuntimeException|Error secondary){/* preserve primary */}
            throw primary;
        }
    }
}
