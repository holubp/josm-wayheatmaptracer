"""G006 released-source freeze with reviewed G004 amendments to RC15 pins.

The Legacy58 path set and original RC15 digests remain historical authority;
two reviewed G004 capture-file digests override those RC15 pins. Embedded
digests keep this guard usable in shallow public CI checkouts.
"""
import hashlib
import pathlib
import shutil
import sys
import tempfile
import unittest
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parents[2]
BASE = "111e5027c4d09e8a8ef672c2c58045edc0ce2663"
B_DIR = "src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic"
G004_REVIEWED_SOURCE_SHA256 = {
    "src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/ProbabilisticTraceEngine.java":
        "ecf57b8bd8ad06f89afa304111837c9832d1f51c519b1cc8909611e938efc28e",
}
SERVICE_DIR = "src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service"
G004_REVIEWED_LEGACY_CAPTURE_SHA256 = {
    # a0abe733: controller-reviewed native viewport/producer/repeat authority since
    # 941ee0ec. Legacy false-flag capture and all tracker/objective kernels remain
    # unchanged. This named integration pin is not final implementation approval.
    f"{SERVICE_DIR}/AlignmentService.java":
        "6e85428a489223a3f0f480b18acf180b7a14224ac8d21afef94a077fb2ca926c",
    f"{SERVICE_DIR}/TileHeatmapSampler.java":
        "41acb4d80367ef2d1cfdd8b37f484806b74bbaa6d1c338e90ab24f46d4910e11",
}
B_SHA256 = {
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/CensoredDirectionReliability.java':
        'e1c1ec0ca86f7b428aae429a78a8a5ef79969d2a83763d99ccd090d87042bd56',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/CredibleLateralSet.java':
        '859295f7c8b25536a3797bec07a4752cef7deccc69bb461bd68e40979eb613bb',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/EvidenceModelParameters.java':
        'e94e5c162cc2958158c1db8f7d670d4c8a3352e17f814d9176e78b10b1961367',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/GapSummary.java':
        '94aed5b44203b1903bf2cacd4c64577af409d50770d2681b509fead6fca609ae',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/InferenceProfile.java':
        '20100d29f42b61a46a6207f00693db8b6881d40a12feaa417dfae0eb41b0cbda',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/LateralStateCell.java':
        '0deb0d9c30810749d4348b0141622d87add09d8f5e8b6bb693b0369c476c1eea',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/LongitudinalModeReliability.java':
        '045c5a92e197fef73f00812bbcae02f2c81630de2803dd32e94d7c0772dc32bb',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/ObservationComponent.java':
        '6f7c489945eb525d844d3d30d148cda14c3cb2e0893e37aa2787301ade425707',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/ObservationFamily.java':
        '57f5a0267a2cd1c153f439a424bc96a5761e9280b5bfa65f09505f49664f589a',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/PathAlternativeSelector.java':
        '425e9292dacc2a0c45e2c196171feea5c381505da57835f9a8fdd2ad5aad32e1',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/ProbabilisticContinuationProposals.java':
        '14ed6e6a55225920d5f6d274445a3b2e9c6a377b92d4f8e1e63de4ae6aa689b2',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/ProbabilisticExecutionPolicy.java':
        'dbea2cb968a29bfb71dbf423a220f32445aa3eef6089cc751f5e9b0a542f10d5',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/ProbabilisticInference.java':
        'bd8685560bee26e8877914c16bd96daa9cc44f38facd015769c57d11651a5814',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/ProbabilisticInferenceFingerprint.java':
        '40461898a2d38195472f2ff628495b9d7319b0efa26ac6ba893c2d46dd241c35',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/ProbabilisticInferenceResult.java':
        '0df1260ec44b92726cd5ea31d231b59171fe4605fbbc42c2b64727d6c1095be0',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/ProbabilisticObservationModel.java':
        'fc22c140cddeccf8e811a11335a21538815800f9f2e97b48233eb0597b3db724',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/ProbabilisticPath.java':
        '14c8ed676de2d4621f6c3a90c36f7d2c3be73235f78914895647b8240c2507a6',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/ProbabilisticProfile.java':
        'c023ea513dfa395ad7ef97f4d69c06614017f37cc0dd7a0773ceb2a5966d1ad9',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/ProbabilisticProfileFactory.java':
        '5c99ba4dd0b6aad3e2c5341b46bdd39f3f4c6c365908b4ba11f7b3817cc0a088',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/ProbabilisticRmsCorroborationProposals.java':
        'fea4d8b203ea716a547a2e2c9e048947095a4c9bd8d45fec3b077f5d47df54c9',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/ProbabilisticStateBuilder.java':
        '426449c5de3a11ce535ddbe21d48ad0fe623e6b00402d4710278d8e3e22160da',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/ProbabilisticStateLattice.java':
        '4c0cfadf76c0e69a11c2d0e4dcf8c187603ed93c646754212af714c93f3e248f',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/ProbabilisticTraceEngine.java':
        '1dd73d6872266b40b69243ad728db12275010d9407d9fce322d4c97596cb896e',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/StateSpaceBuildResult.java':
        '057bd26aafbc4fe5cc0a9819bb4eff6c0f40f35b17aa69e9789fbf9c5113c8a7',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/TransitionAdmissionMemo.java':
        '2bf5a9fc32f0e9a7320723531f987c3c960076c38dccf1a223c3e81d419c4ffc',
}
LEGACY_SHA256 = {
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/diagnostics/replay/format15/LegacyBProvenance.java':
        '64bac5c949c32d72ff509b8cc663faaa67a974f26ac3e98f328c4ff26ddef207',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/diagnostics/replay/format15/LegacyBReplayWork.java':
        'c909f2730728e4128605af21932a1cb6c4aef9907c421dc3e770dd5e94ee9380',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/diagnostics/replay/format15/LegacySourceCodec.java':
        '3da694cb1e2902e822fafeb529d0a16678d4b1b3a1f7e1fb2df7194ed625e62e',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/AlignmentService.java':
        '5d891986a23487fa00ad787965a743f014d6bda776a1a86e313dbd17ae0672cd',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/LegacyBSourceCapture.java':
        'ffd1f00f93d5ba9f601112fe97b9646381d53ea32f0ba049d7711849de5acf3f',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/LegacyFinalPreviewSafety.java':
        'd5a975c36f4305731e6aa6ff12fb1685ea85dc0078bffba47debcfed32c933f8',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/LegacyManagedProfileSampler.java':
        '620c14dd6deacb6484208bfc4c6500ef771d6adbea0cc7a9511748a0cf6de8be',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/LegacyProfileNormalFences.java':
        '14f434bf07c75b5e0da42508957a41ef7a74db5cd10030f296fc3d5a37a79ebe',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/LegacyProfileNormalPolicy.java':
        '007515585bdabfe5dc213b8a11e49991f79dae4bd4da7781072134c51a381392',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/LegacyTrackerPolicy.java':
        '7c5569cbb64713816b9060043494620311bcf448ded28343a41d13eceae12e78',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/LegacyVisibleProfileSampler.java':
        '405081c60df27bd3e1280d8a6a756296e629d009a130147e848f4f705b6a3e6d',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/RenderedHeatmapSampler.java':
        '5782aa39f60bb6e957fa1604e73021af5da6335a6a58acb37a6b29127515bc8c',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/RidgeTracker.java':
        '180790228f153a71fc14bc3cf065e8422c95139c1fb4bc8c2f956ae8966bae65',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/TileHeatmapSampler.java':
        'ac8c2392c5c528b36f254164643e2ccab131d39e8181d2bbde2386f10663e1b9',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacyBAssessor.java':
        '25eb10f5bccdb6472b3d5d3bd5ac1ff5665db847e8d7978069c78b1acc10f0c8',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacyBModels.java':
        '5efda30fd4ae7f18fa483542a02ebc6dead12286d9b1303a496bf95632b87ef2',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacyBModernResultAdapter.java':
        'fb32d6be90765c9b86bd02a66104010deee040f017b95998a24ae87a67f0f73b',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacyBOccurrenceReconstruction.java':
        '6373bb634a61938456f02fc3c72e8b36ed407bdfd200912cb3e0b0d2b7891f97',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacyBParentAdmission.java':
        '716e02fd0292994f202d1a0e7962135aa44c0290ddf831401a972f92a9e8fa1e',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacyBPipeline.java':
        'c22ee25e8e412d2ed9b44931982fd40aad5d48e32d12e3d87c1745308a86e59f',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacyBProductionRunner.java':
        'af73321e16646b9b91d0fbd9da397d02af87986f65f3a0cf3eb996ed8feab8f6',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacyBReferenceFactory.java':
        'a5811f0fd861444ee41d7b9786dced495c2c504606130db0b417a0f0316ef9b4',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacyPreciseGeometry.java':
        'f5afb96cf63bf3b8fd41e7e7d1fa391d1c8746fd839369131384ddda5a4af228',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacySeedAdapter.java':
        'aa003e5e7253c8d67e28b24e79243ee12ad9ab086e471b8d9ac06255691d8b9b',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacyTopologyPlanner.java':
        'd6cebd5a10bb689e4d5d09a303655d3e40a0ee7d8d03bcdf750b1881712c545f',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/util/MoveNodesCommand.java':
        '276c492af39838bad01287b2ea4c70af928fb7cd6412cad25090b0ec9c3c9653',
    'src/main/java/org/openstreetmap/josm/plugins/wayheatmaptracer/util/ReplaceWaySegmentCommand.java':
        '810ea1715b6d697f66bc82ef9cd10fd850d7e3d4665af270ab43677d38f845b2',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/diagnostics/replay/format15/LegacyNumericalPolicyReceiptTest.java':
        'd8e9ec7503d0a6be32be2bc70b667e337161f4ceedf0d4538211f73380c35f87',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/diagnostics/replay/format15/LegacySourceCodecTest.java':
        'ece82c4571ddf42a39089ed9b9fc4ca1144ce2820b686bd8c95c9a308a00deaa',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/model/LegacyFirstTrackerModeTest.java':
        'fa0f292179d28ed52215d750a7f2f12c3e779e7927f5d48256288d26e8d21145',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/LegacyBSourceCaptureTest.java':
        '4539d24695cc5fe29155c1bc34bc640b72f7f8675b2d210fd694ae3b0e8b4e2c',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/LegacyDominatedSupportTest.java':
        '13f5de50e1245bea734b024e5261ed274ff56a14702849155c5dd2385841b513',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/LegacyFinalPreviewSafetyTest.java':
        'd1a3fd864a4c24afde55e0b975d866798d0223c1be2e0f57087f6965a71d4ea8',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/LegacyFineLocalizationTest.java':
        '8fc832fa90c6a2e2505cebea2428a89d39114a76c9768bee9c833796cefcd452',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/LegacyManagedProfileSamplerTest.java':
        '445e24f3a660c21c3a4773b19a976f88baf70b587bfda7de5d16f19e9ebe42be',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/LegacyNormalWavinessTest.java':
        'ca6c6543554adff6faa87d566e07b0241a2bc9132a2f2bbad5e7a2bba6930d61',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/LegacyPairedChordTest.java':
        'd47b719d532ad675eb10e710fbd44dfde735eefd4c224b811aea29ee72f96d88',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/LegacyProfileNormalPolicyTest.java':
        '20e7218d438ff215b410ef8cb752be5ab8a4c42b5fb901a2a2c29db0a83ddac2',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/LegacyResolvedSupportTest.java':
        '216b5651151196b53e6fe8b6abc97f7457fce1b9aa2966c39fc31d02270d1c42',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/LegacySyntheticPositionTest.java':
        'd443b35f234dad04793f8ffa9be218840d016b51b69ef81ffedbdae6fc5aad33',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/LegacyTrackerPolicyTest.java':
        '3eb4f45922cdab9804f40dc0f9d19fa8a8e5f7435077b99ecbf4ffa75e4e3baf',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/LegacyVisibleProfileSamplerTest.java':
        '064f21c949ac45c66f8a3080f2f1e946504a2887b37342253e0c62d0ece439f7',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacyBAssessorTest.java':
        '2e55b662c0fb75556ea91e26483388aa819978b10764ca1a53fc94cbb6d3d54a',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacyBFrozenReplayTest.java':
        '24ca377107a31b65d950cf3cdb6b4cb7b4c40727b69d4a354da77b9af6df8aa2',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacyBModelsTest.java':
        '0b32184dfb5fac456aaed9860d246e551144c4ac51acaf88036f7e095f9b9f2d',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacyBModernResultAdapterTest.java':
        '3346176743a1a29f570d8a6a55fdc2a9a08276231c2f86775ba63245c0294c05',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacyBOccurrenceReconstructionTest.java':
        'e9cbcf7f274000440c90b80995015b1dc4adfced8740da16f8ceec76a06cee5c',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacyBParentAdmissionTest.java':
        '3b85ec115d494803c47b2c43c5aa9ae6fe2734e0f50173853664685e30cc044b',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacyBPipelineTest.java':
        '12404461011562ea1efbb796c07b45f762c105c4214b2692b7617b68002ba5f5',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacyBProductionRunnerTest.java':
        '78cc0c7df75aa183dd3ef78550f402db9e8a497977f69223a4c52f3a2fbc7643',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacyBReferenceFactoryTest.java':
        '66030bad68b5e5d26cc3327df4b2fe8003d3a2d87362d94f262a18b0cf48df5e',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacyBReferencePreparationTest.java':
        '222e26dd5e3c227916745e75ac4d10e6e0caa993f435159f6d35e8795ed9615f',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacyBRefinedEngineTest.java':
        '563af19cba9599715c733b2701db6cf6edc28276c74e1cb994f74a9296c6efa3',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacyBTestFixtures.java':
        '3d043d42fb7b0ddb4a2880224cc76bc93b1736d52bc30efc2e45e8fd4df12451',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacyPreciseGeometryTest.java':
        '2912be38355ad6ac78c48eba2b137e6b492c5353417b02e4525c258860fc93cc',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacySeedAdapterTest.java':
        'b54365f96ec1e713764f364b05947d05bc956fb1cf85ad9c69c052eb66b28ff4',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacyTopologyPlannerTest.java':
        '9d2134a09e9bd005e2edc7f605dc54be00bda455dc59b176344bf5381dc86a20',
    'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/hybrid/LegacyWideSecantPerformanceMain.java':
        'beca67f28982213e722f87e0ae6f87154822595ec5617a59cafa2b99d23d78a8',
}


class ReleasedSourceFreezeTest(unittest.TestCase):
    def test_b_source_set_and_bytes_match_rc15_except_named_reviewed_g004_entry_point(self):
        actual = {str(p.relative_to(ROOT)) for p in (ROOT / B_DIR).rglob("*") if p.is_file()}
        self.assertEqual(set(B_SHA256), actual, "B source additions and deletions are forbidden")
        engine_path = f"{B_DIR}/ProbabilisticTraceEngine.java"
        self.assertEqual({engine_path}, set(G004_REVIEWED_SOURCE_SHA256))
        self.assertIn(engine_path, B_SHA256)
        expected = {**B_SHA256, **G004_REVIEWED_SOURCE_SHA256}
        self.assert_bytes(expected)

    def test_guard_rejects_changed_unlisted_b_file(self):
        self.assert_guard_rejects_mutation("CensoredDirectionReliability.java")

    def test_guard_rejects_changed_reviewed_entry_point(self):
        self.assert_guard_rejects_mutation("ProbabilisticTraceEngine.java")

    def assert_guard_rejects_mutation(self, filename):
        with tempfile.TemporaryDirectory() as directory:
            temporary_root = pathlib.Path(directory)
            shutil.copytree(ROOT / B_DIR, temporary_root / B_DIR)
            mutated_path = temporary_root / B_DIR / filename
            mutated_path.write_bytes(mutated_path.read_bytes() + b"\n// mutation\n")
            case = type(self)("test_b_source_set_and_bytes_match_rc15_except_named_reviewed_g004_entry_point")
            result = unittest.TestResult()
            with mock.patch.object(sys.modules[__name__], "ROOT", temporary_root):
                case.run(result)
            self.assertEqual([], result.errors)
            self.assertEqual(1, len(result.failures))
            expected_digest = G004_REVIEWED_SOURCE_SHA256.get(
                f"{B_DIR}/{filename}", B_SHA256[f"{B_DIR}/{filename}"]
            )
            self.assertIn(expected_digest, result.failures[0][1])

    def test_protected_legacy58_bytes_match_rc15_except_reviewed_g004_capture(self):
        self.assertEqual(58, len(LEGACY_SHA256))
        self.assertEqual(
            set(G004_REVIEWED_LEGACY_CAPTURE_SHA256),
            {f"{SERVICE_DIR}/{name}.java" for name in ("AlignmentService", "TileHeatmapSampler")},
        )
        self.assertTrue(set(G004_REVIEWED_LEGACY_CAPTURE_SHA256) <= set(LEGACY_SHA256))
        expected = {**LEGACY_SHA256, **G004_REVIEWED_LEGACY_CAPTURE_SHA256}
        self.assert_bytes(expected)

    def test_guard_rejects_mutated_reviewed_g004_legacy_capture_files(self):
        for relative_path in G004_REVIEWED_LEGACY_CAPTURE_SHA256:
            with self.subTest(path=relative_path):
                self.assert_legacy_guard_rejects_mutation(relative_path)

    def test_guard_rejects_mutated_unchanged_rc15_legacy_source(self):
        self.assert_legacy_guard_rejects_mutation(f"{SERVICE_DIR}/RidgeTracker.java")

    def assert_legacy_guard_rejects_mutation(self, relative_path):
        with tempfile.TemporaryDirectory() as directory:
            temporary_root = pathlib.Path(directory)
            for path in LEGACY_SHA256:
                source = ROOT / path
                destination = temporary_root / path
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, destination)
            mutated_path = temporary_root / relative_path
            mutated_path.write_bytes(mutated_path.read_bytes() + b"\n// mutation\n")
            case = type(self)("test_protected_legacy58_bytes_match_rc15_except_reviewed_g004_capture")
            result = unittest.TestResult()
            with mock.patch.object(sys.modules[__name__], "ROOT", temporary_root):
                case.run(result)
            self.assertEqual([], result.errors)
            self.assertEqual(1, len(result.failures))
            expected_digest = G004_REVIEWED_LEGACY_CAPTURE_SHA256.get(
                relative_path, LEGACY_SHA256[relative_path]
            )
            self.assertIn(expected_digest, result.failures[0][1])

    def assert_bytes(self, expected):
        for path, digest in expected.items():
            with self.subTest(path=path):
                self.assertEqual(digest, hashlib.sha256((ROOT / path).read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main()
