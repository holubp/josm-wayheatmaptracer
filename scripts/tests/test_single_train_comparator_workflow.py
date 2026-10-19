"""Pending source-only comparator guards; all subprocesses use controlled fixtures."""
import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import pytest
import yaml

ROOT=Path(__file__).resolve().parents[2]
WORKFLOW=ROOT/'.github/workflows/observation-single-train-comparator.yml'
FREEZE='superpowers/docs/plans/2026-10-19-b-single-train-comparator-freeze.json'
PURPOSE='SINGLE_ORIGINAL_TRAIN_B0_ACCEPTED_COMPARATOR'
BRANCH='refs/heads/codex/b-observation-comparator-20261019'
APPROVED='APPROVED_AFTER_INDEPENDENT_REVIEW_AND_CONTROLLER_RULING'

def workflow():
    assert WORKFLOW.exists(),'fixed pending comparator workflow missing'
    return yaml.load(WORKFLOW.read_text(),Loader=yaml.BaseLoader)

def snippet(marker):
    run=next(s['run'] for s in workflow()['jobs']['accepted-comparator']['steps'] if marker in s.get('run',''))
    return run.split("python3 - <<'PY'\n",1)[1].split('\nPY',1)[0]

def execute(script,root,optimized,**env):
    return subprocess.run([sys.executable,*(['-O'] if optimized else []),'-c',script],cwd=root,env={**os.environ,**env},capture_output=True,text=True)

def launch():
    return dict(GITHUB_ACTIONS='true',GITHUB_EVENT_NAME='push',GITHUB_REF=BRANCH,GITHUB_RUN_ATTEMPT='1',GITHUB_REPOSITORY='holubp/josm-wayheatmaptracer',GITHUB_RUN_ID='123',GITHUB_SHA='a'*40,
                GITHUB_WORKFLOW_REF='holubp/josm-wayheatmaptracer/.github/workflows/observation-single-train-comparator.yml@'+BRANCH)

def test_fixed_route_bounded_explicit_leaves_and_old63_unchanged():
    data=workflow();assert data['on']=={'push':{'branches':['codex/b-observation-comparator-20261019']}}
    job=data['jobs']['accepted-comparator'];assert job['runs-on']=='ubuntu-24.04' and job['timeout-minutes']=='15'
    assert "github.event_name == 'push'" in job['if'] and 'github.run_attempt == 1' in job['if']
    commands='\n'.join(s.get('run','') for s in job['steps']);assert commands.index('freeze=Path')<commands.index('compileObservationIdentifyJava')
    assert 'timeout 360s' in commands and 'v022SingleTrainAcceptedComparator' in commands
    init=(ROOT/'scripts/single-train-comparator.init.gradle.kts').read_text()
    for name in ['BSingleTrainComparatorRegion.java','BSingleTrainComparatorTool.java','BSingleTrainComparatorManifest.java']:assert name in init
    upload=next(s for s in job['steps'] if s.get('uses','').startswith('actions/upload-artifact@'))
    paths=upload['with']['path'].splitlines();leaves=[Path(p).name for p in paths]
    expected=['binding.json','status.json','proof.json','solver.json','summary.json']+[f'detail-{p}-{i:02d}.json' for p in ['archived','comparator'] for i in range(11)]+[f'progress-{i:02d}.json' for i in range(5)]
    assert len(leaves)==32 and set(leaves)==set(expected)
    assert all('*' not in p and '..' not in p and 'scratch' not in p for p in paths)
    old=json.loads((ROOT/'superpowers/docs/plans/2026-10-19-b-single-train-cost-freeze.json').read_bytes());assert len(old['members'])==63
    for p,h in old['members'].items():assert hashlib.sha256((ROOT/p).read_bytes()).hexdigest()==h,p

def test_actual_runtime_shell_one_dummy_invocation_with_both_bound_inits(tmp_path):
    script=next(s['run'] for s in workflow()['jobs']['accepted-comparator']['steps'] if s.get('name')=='Run the fixed accepted B0 comparator once')
    shell=shutil.which('sh');assert shell
    binaries=tmp_path/'bin';binaries.mkdir();capture=tmp_path/'argv.jsonl'
    recorder='import json,os,sys\nfrom pathlib import Path\nwith Path(os.environ["ARGV_CAPTURE"]).open("a") as f:\n    f.write(json.dumps({"program":PROGRAM,"argv":sys.argv[1:]})+"\\n")\n'
    for path,name,tail in [(binaries/'timeout','timeout','os.execvp(sys.argv[2],sys.argv[2:])\n'),(binaries/'sh','sh','os.execv(sys.executable,[sys.executable,*sys.argv[1:]])\n'),(tmp_path/'gradlew','gradlew','')]:
        path.write_text(f'#!{sys.executable}\nPROGRAM={name!r}\n'+recorder+tail);path.chmod(0o755)
    runner=tmp_path/'runner with spaces';digest='d'*64
    result=subprocess.run([shell,'-c',script],cwd=tmp_path,env={**os.environ,'PATH':str(binaries)+os.pathsep+os.environ.get('PATH',''),'ARGV_CAPTURE':str(capture),'RUNNER_TEMP':str(runner),'COMPARATOR_FREEZE_SHA256':digest},capture_output=True,text=True)
    expected=['-I','scripts/single-train-cost.init.gradle.kts','-I','scripts/single-train-comparator.init.gradle.kts','v022SingleTrainAcceptedComparator','-PsingleTrainComparatorFreezeSha256='+digest,'-PsingleTrainComparatorOutput='+str(runner/'josm-single-train-comparator'),'--console=plain']
    assert [json.loads(r) for r in capture.read_text().splitlines()]==[dict(program='timeout',argv=['360s','sh','./gradlew',*expected]),dict(program='sh',argv=['./gradlew',*expected]),dict(program='gradlew',argv=expected)]
    assert result.returncode==0,result.stderr

@pytest.mark.parametrize('optimized',[False,True])
def test_actual_preflight_pending_source_context_and_joint_pin_tamper_refuse(tmp_path,optimized):
    source=tmp_path/'src/C.java';source.parent.mkdir();source.write_text('class C {}\n');freeze=tmp_path/FREEZE;freeze.parent.mkdir(parents=True)
    fields=dict(schema=1,purpose=PURPOSE,approval=APPROVED,branch=BRANCH,members={'src/C.java':hashlib.sha256(source.read_bytes()).hexdigest()})
    def write(value):
        freeze.write_text(json.dumps(value,sort_keys=True,separators=(',',':'))+'\n');return hashlib.sha256(freeze.read_bytes()).hexdigest()
    expected=write(fields);script=snippet('freeze=Path')
    assert execute(script,tmp_path,optimized,COMPARATOR_FREEZE_SHA256=expected,**launch()).returncode==0
    for key,value in [('purpose','PROSPECTIVE_FIT'),('approval','PENDING_INDEPENDENT_APPROVAL'),('branch','other'),('members',{})]:
        bad=copy.deepcopy(fields);bad[key]=value;assert execute(script,tmp_path,optimized,COMPARATOR_FREEZE_SHA256=write(bad),**launch()).returncode!=0
    write(fields)
    for key in launch():
        bad=launch();bad[key]='unsafe\nvalue';assert execute(script,tmp_path,optimized,COMPARATOR_FREEZE_SHA256=expected,**bad).returncode!=0
    source.write_text('class Changed {}\n');assert execute(script,tmp_path,optimized,COMPARATOR_FREEZE_SHA256=expected,**launch()).returncode!=0
    fields['members']['src/C.java']=hashlib.sha256(source.read_bytes()).hexdigest();write(fields)
    assert execute(script,tmp_path,optimized,COMPARATOR_FREEZE_SHA256=expected,**launch()).returncode!=0

def test_actual_candidate_canonical_binding_and_single_public_solver_seam():
    raw=(ROOT/FREEZE).read_bytes();fields=json.loads(raw);assert raw==(json.dumps(fields,sort_keys=True,separators=(',',':'))+'\n').encode()
    assert fields['purpose']==PURPOSE and fields['approval'] in ('PENDING_INDEPENDENT_APPROVAL',APPROVED)
    assert workflow()['jobs']['accepted-comparator']['env']['COMPARATOR_FREEZE_SHA256']==hashlib.sha256(raw).hexdigest()
    assert fields['maximumGeometryCallbackUnits']==1_000_000_000 and fields['maximumProofPairs']==101160
    for p,h in fields['members'].items():assert hashlib.sha256((ROOT/p).read_bytes()).hexdigest()==h,p
    tool=(ROOT/'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/BSingleTrainComparatorTool.java').read_text()
    assert tool.count('new ProbabilisticInference().solve(')==1 and 'enumerateKBest' not in tool and 'forwardBackward(' not in tool
    assert tool.index('long started=clock.getAsLong()')<tool.index('BSingleTrainComparatorManifest.register(digest)')

def controlled_receipts(root):
    """Producer-shaped fixture only; no actual original-case input or invocation."""
    output=root/'josm-single-train-comparator';output.mkdir();freeze=json.loads((ROOT/FREEZE).read_bytes())
    (root/Path(FREEZE).parent).mkdir(parents=True);(root/FREEZE).write_text(json.dumps(freeze))
    archive_dir=root/'src/test/resources/b-single-train-geometry';archive_dir.mkdir(parents=True)
    archived=141.19791473890345;energy=142.0
    archive=dict(input={'factsHash':'controlled-fixture'},rawGeometry=dict(modelHash='controlled-model',mapEnergy=archived,mapStateIndices=[0]*81))
    (archive_dir/'G1-L1-B0.json').write_text(json.dumps(archive))
    status=dict(schema=1,purpose=PURPOSE,status='COMPLETE_TRUTH_CONDITIONED_COMPARISON_NOT_QUALIFIED',selectionAuthority='NONE',rasterReads=1,labelReads=1,selectReads=0,holdoutReads=0,
                actualSolverEntries=1,completedPathAccounts=2,proofComplete=True,proofPairs=101160,ownerBytesAfterClose=0,uncertainOrdinaryReservationBytes=0,peakAccountedBytes=1000,diskChargeBeforeStatusUpperBound=1000,
                counts=dict(adapterEvaluations=81,profileTerms=162,pairTerms=160,tripleTerms=158,primitiveCalls=962,selectedCellComponentRecords=0),
                phases={p:dict(attempted=1,succeeded=1,failed=0,active=0) for p in ['ACQUISITION','ADAPT','PROOF','SOLVE','ACCOUNT']})
    names=['BSingleTrainCostManifest','BSingleTrainObjectiveAccounting','BSingleTrainCostDiagnosticTool','BObservationAdapter','BIdentifiedObservationKernel','BObservationMixture','ProbabilisticInference','InferenceProfile','BSingleTrainDiagnosticBridge','BSingleTrainComparatorRegion','BSingleTrainComparatorTool','BSingleTrainComparatorManifest','ProbabilisticInferenceResult','ProbabilisticPath']
    classes={'org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.'+name:'b'*64 for name in names};classes['org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion']='b'*64
    binding=dict(schema=1,purpose=PURPOSE,freezeSha256='a'*64,sourceProofOnly='94d7ea9a03134cab331b317a1764b0d517bd2dc1dea6d2a78482bcf8313dc6de',
                 case='sparse-crossing-noisy-strands-vs-persistent-parallel/train/v0/right',parameter='G1-L1-B0',selectionAuthority='NONE',runtimeClassSha256=classes)
    proof=dict(schema=1,complete=True,pairs=101160,finishedPairs=101160,firstMismatch={},refusedCallbackCharges=0,maximumPairs=101160,maximumCallbackUnits=1000000000,admittedCallbackUnits=1000,noHookSegmentQueries=1000,orderedAnswerSha256='c'*64,coordinateSha256='d'*64)
    solver=dict(schema=1,status='REVIEW_REQUIRED',route='ACTUAL_PUBLIC_FULL_FB_KBEST_MATERIALIZATION_DIAGNOSTICS',posteriorUsable=True,rawPathCount=1,distinctPathCount=1,logPartition=-1.0,mapEnergy=energy,mapStateIndices=[0]*81,
                workAccounting=dict(executionPolicy='terminal-path-count-scored-work-v2',forwardBackwardScores=10,kBestScores=10,logicalKBestExtensions=12,refusedScoreAttempts=0),evaluatedTransitions=20,evaluatedPairVisits=100,
                completion=dict(effectiveRawLimit=1,effectiveDistinctLimit=1,completePathsAtSaturation=2,terminalCountSaturated=True,rawEnumerationCapped=True,requestedDiversityReached=True),alternativeSearchTruncated=False,alternativeAuthority='CAP1_MAP_ONLY_NOT_ALL_ALTERNATIVES_COMPLETE')
    summary=dict(schema=1,purpose=PURPOSE,freezeSha256='a'*64,parameter='G1-L1-B0',input=archive['input'],modelHash='controlled-model',selectionAuthority='NONE',
                 archivedReceiptSha256='05a3daf825e5dad3f8b3a563dcdf2c215b4a72ffedef8a912115371fd60f22f1',energyBitEquality=True,actualOriginalWholeOraclePassed=True,originalHardRoutePassed=True,exactAnchorsPassed=True,
                 checks=[dict(code='PARALLEL_SEGMENT_UNION',verdict='PASS',count=28)],archivedEnergy=archived,comparatorEnergy=energy,gapComparatorMinusArchived=energy-archived,
                 comparisonAuthority='BEST_UNDER_ORIGINAL_SELECTOR_X40_80_ONLY',exteriorImageSupport='UNAVAILABLE_NOT_ENFORCED_BY_SELECTOR')
    values={'status.json':status,'binding.json':binding,'proof.json':proof,'solver.json':solver,'summary.json':summary}
    for path in ['archived','comparator']:
        for c in range(11):values[f'detail-{path}-{c:02d}.json']=dict(schema=1,path='ARCHIVED_B0' if path=='archived' else 'TRUTH_CONDITIONED_COMPARATOR_B0',
                profiles=[dict(profile=i,state=0,prefixEnergy=archived if path=='archived' else energy,componentDensities='UNAVAILABLE_NO_SECOND_KERNEL_PASS') for i in range(c*8,min(81,(c+1)*8))])
    for p in range(5):values[f'progress-{p:02d}.json']=dict(schema=1,phases={'fixture':dict(active=1)})
    for name,value in values.items():(output/name).write_text(json.dumps(value))
    return output,values

@pytest.mark.parametrize('optimized',[False,True])
def test_actual_terminal_guard_refuses_incomplete_proof_solver_or_nonfinite_comparison(tmp_path,optimized):
    output,values=controlled_receipts(tmp_path);script=snippet('root=Path(os.environ');env=dict(RUNNER_TEMP=str(tmp_path),COMPARATOR_FREEZE_SHA256='a'*64)
    assert execute(script,tmp_path,optimized,**env).returncode==0
    invalid=[('status.json','status','INCOMPLETE'),('status.json','actualSolverEntries',2),('status.json','proofComplete',False),('status.json','selectReads',1),('status.json','ownerBytesAfterClose',1),
             ('proof.json','complete',False),('proof.json','finishedPairs',101159),('proof.json','firstMismatch',{'startProfile':1}),('proof.json','admittedCallbackUnits',1000000001),
             ('solver.json','status','RESOURCE_LIMIT'),('solver.json','status','NUMERIC_FAILURE'),('solver.json','status','NO_ROUTE'),('solver.json','workAccounting','UNAVAILABLE'),('solver.json','completion','UNAVAILABLE'),
             ('solver.json','mapEnergy',float('nan')),('summary.json','comparatorEnergy',float('inf')),('summary.json','actualOriginalWholeOraclePassed',False),('summary.json','energyBitEquality',False),
             ('summary.json','comparisonAuthority','WHOLE_ROUTE_CONTINUOUS'),('summary.json','archivedEnergy',141.0),('summary.json','input',{}),('binding.json','purpose','PROSPECTIVE_FIT'),('binding.json','freezeSha256','b'*64),('detail-comparator-00.json','profiles',[])]
    for name,key,value in invalid:
        bad=copy.deepcopy(values[name]);bad[key]=value;(output/name).write_text(json.dumps(bad))
        assert execute(script,tmp_path,optimized,**env).returncode!=0,(name,key)
        (output/name).write_text(json.dumps(values[name]))
    (output/'solver.json').write_text('{');assert execute(script,tmp_path,optimized,**env).returncode!=0
    (output/'solver.json').unlink();assert execute(script,tmp_path,optimized,**env).returncode!=0
