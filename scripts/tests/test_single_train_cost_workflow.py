"""Fixed cost-only branch route; controlled snippets never acquire a catalog scene."""
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github/workflows/observation-single-train-cost.yml'
FREEZE = 'superpowers/docs/plans/2026-10-19-b-single-train-cost-freeze.json'
PURPOSE = 'SINGLE_ORIGINAL_TRAIN_FIXED_PATH_COST_ACCOUNTING'
APPROVED = 'APPROVED_AFTER_INDEPENDENT_REVIEW_AND_CONTROLLER_RULING'
BRANCH = 'refs/heads/codex/b-observation-cost-20261019'

def workflow():
    assert WORKFLOW.exists(), 'new fixed cost-only workflow missing'
    return yaml.load(WORKFLOW.read_text(), Loader=yaml.BaseLoader)

def snippet(marker):
    steps = workflow()['jobs']['cost-accounting']['steps']
    run = next(s['run'] for s in steps if marker in s.get('run', ''))
    return run.split("python3 - <<'PY'\n", 1)[1].split('\nPY', 1)[0]

def execute(script, root, optimized, **env):
    return subprocess.run([sys.executable, *(['-O'] if optimized else []), '-c', script], cwd=root,
                          env={**os.environ, **env}, capture_output=True, text=True)

def launch():
    return dict(GITHUB_ACTIONS='true', GITHUB_EVENT_NAME='push', GITHUB_REF=BRANCH,
                GITHUB_RUN_ATTEMPT='1', GITHUB_REPOSITORY='holubp/josm-wayheatmaptracer',
                GITHUB_RUN_ID='123', GITHUB_SHA='a'*40,
                GITHUB_WORKFLOW_REF='holubp/josm-wayheatmaptracer/.github/workflows/observation-single-train-cost.yml@'+BRANCH)

def test_fixed_push_route_init_and_exact_uploads_preserve_old_authority():
    data = workflow()
    assert data['on'] == {'push': {'branches': ['codex/b-observation-cost-20261019']}}
    job = data['jobs']['cost-accounting']
    assert job['runs-on'] == 'ubuntu-24.04' and job['timeout-minutes'] == '15'
    assert "github.event_name == 'push'" in job['if'] and "github.run_attempt == 1" in job['if']
    commands = '\n'.join(s.get('run', '') for s in job['steps'])
    assert commands.index('freeze=Path') < commands.index('compileObservationIdentifyJava')
    assert 'timeout 360s' in commands and 'v022SingleTrainCostDiagnostic' in commands
    assert 'gh ' not in commands and 'v022ObservationFit' not in commands
    init = (ROOT/'scripts/single-train-cost.init.gradle.kts').read_text()
    assert 'b-single-train-cost/witness.json' in init
    assert all(name in init for name in ['BSingleTrainCostManifest.java', 'BSingleTrainObjectiveAccounting.java', 'BSingleTrainCostDiagnosticTool.java'])
    upload = next(s for s in job['steps'] if s.get('uses', '').startswith('actions/upload-artifact@'))
    leaves = [Path(p).name for p in upload['with']['path'].splitlines()]
    expected = ['binding.json','status.json','summary-B0.json','summary-B1.json']
    expected += [f'detail-B{b}-{c:02d}.json' for b in range(2) for c in range(11)]
    expected += [f'progress-{p:02d}.json' for p in range(6)]
    assert set(leaves) == set(expected) and len(leaves) == 32
    assert not any('*' in p or 'scratch' in p or '..' in p for p in upload['with']['path'].splitlines())
    old = json.loads((ROOT/'superpowers/docs/plans/2026-10-18-b-single-train-geometry-freeze.json').read_bytes())
    assert len(old['members']) == 48
    for path, digest in old['members'].items():
        assert hashlib.sha256((ROOT/path).read_bytes()).hexdigest() == digest, path

@pytest.mark.parametrize('optimized',[False, True])
def test_actual_precompile_guard_rejects_pending_source_context_and_joint_tamper(tmp_path, optimized):
    source = tmp_path/'src/C.java';source.parent.mkdir();source.write_text('class C {}\n')
    freeze = tmp_path/FREEZE;freeze.parent.mkdir(parents=True)
    fields = dict(schema=1,purpose=PURPOSE,approval=APPROVED,branch=BRANCH,members={'src/C.java':hashlib.sha256(source.read_bytes()).hexdigest()})
    def write(value):
        freeze.write_text(json.dumps(value,sort_keys=True,separators=(',',':'))+'\n')
        return hashlib.sha256(freeze.read_bytes()).hexdigest()
    expected = write(fields);script=snippet('freeze=Path')
    assert execute(script,tmp_path,optimized,COST_FREEZE_SHA256=expected,**launch()).returncode == 0
    for key,value in [('approval','PENDING_INDEPENDENT_APPROVAL'),('purpose','PROSPECTIVE_FIT'),('members',{}),('branch','other')]:
        bad=copy.deepcopy(fields);bad[key]=value
        assert execute(script,tmp_path,optimized,COST_FREEZE_SHA256=write(bad),**launch()).returncode != 0
    write(fields)
    for key in launch():
        bad=launch();bad[key]='unsafe\nvalue'
        assert execute(script,tmp_path,optimized,COST_FREEZE_SHA256=expected,**bad).returncode != 0
    source.write_text('class Changed {}\n')
    assert execute(script,tmp_path,optimized,COST_FREEZE_SHA256=expected,**launch()).returncode != 0
    fields['members']['src/C.java']=hashlib.sha256(source.read_bytes()).hexdigest();write(fields)
    assert execute(script,tmp_path,optimized,COST_FREEZE_SHA256=expected,**launch()).returncode != 0

def test_actual_candidate_is_pending_canonical_and_externally_pinned():
    data=workflow();raw=(ROOT/FREEZE).read_bytes();fields=json.loads(raw)
    assert raw == (json.dumps(fields,sort_keys=True,separators=(',',':'))+'\n').encode()
    assert fields['approval'] in ('PENDING_INDEPENDENT_APPROVAL',APPROVED)
    assert fields['purpose'] == PURPOSE
    assert data['jobs']['cost-accounting']['env']['COST_FREEZE_SHA256'] == hashlib.sha256(raw).hexdigest()
    assert len(fields['outputFiles']) == 32
    for path,digest in fields['members'].items():
        assert hashlib.sha256((ROOT/path).read_bytes()).hexdigest() == digest,path
    assert hashlib.sha256((ROOT/'src/test/resources/b-single-train-cost/witness.json').read_bytes()).hexdigest() == '306df0ebe59f052104883c8113823a127176c254510faebf10b32ff896fba5ad'

def receipts(root):
    """Controlled producer-shaped receipts, with no raster/provider/Java invocation."""
    output=root/'josm-single-train-cost';output.mkdir()
    freeze=json.loads((ROOT/FREEZE).read_bytes())
    (root/Path(FREEZE).parent).mkdir(parents=True)
    (root/FREEZE).write_text(json.dumps(freeze))
    archive_dir=root/'src/test/resources/b-single-train-geometry';archive_dir.mkdir(parents=True)
    witness_dir=root/'src/test/resources/b-single-train-cost';witness_dir.mkdir(parents=True)
    points=[dict(xMeters=float(i),yMeters=0.0) for i in range(81)]
    (witness_dir/'witness.json').write_text(json.dumps(dict(states=[0]*81,points=points)))
    status=dict(schema=1,status='COMPLETE_NONQUALIFYING_ACCOUNTING',purpose=PURPOSE,selectionAuthority='NONE',
                rasterReads=1,labelReads=1,selectReads=0,holdoutReads=0,newMapSolves=0,newSearches=0,
                completedPathAccounts=4,ownerBytesAfterClose=0,uncertainOrdinaryReservationBytes=0,
                peakAccountedBytes=1234,diskChargeBeforeStatusUpperBound=1234,
                counts=dict(adapterEvaluations=162,profileTerms=324,pairTerms=320,tripleTerms=316,
                            primitiveCalls=1924,selectedCellComponentRecords=972),
                phases={p:dict(attempted=1,succeeded=1,failed=0,active=0) for p in
                        ['ACQUISITION','DECODE','B0_EVALUATION','B0_ACCOUNT','B1_EVALUATION','B1_ACCOUNT']})
    binding=dict(schema=1,purpose=PURPOSE,freezeSha256='a'*64,selectionAuthority='NONE',
                 sourceProofOnly='94d7ea9a03134cab331b317a1764b0d517bd2dc1dea6d2a78482bcf8313dc6de',
                 case='sparse-crossing-noisy-strands-vs-persistent-parallel/train/v0/right',
                 witnessMemberSha256='306df0ebe59f052104883c8113823a127176c254510faebf10b32ff896fba5ad',
                 runtimeClassSha256={str(i):'b'*64 for i in range(9)})
    values={'status.json':status,'binding.json':binding}
    for b in range(2):
        archive=dict(input={'factsHash':'fixture'},rawGeometry=dict(modelHash='fixture-model',mapEnergy=1.0,mapStateIndices=[0]*81))
        (archive_dir/f'G1-L1-B{b}.json').write_text(json.dumps(archive))
        values[f'summary-B{b}.json']=dict(schema=1,purpose=PURPOSE,freezeSha256='a'*64,parameter=f'G1-L1-B{b}',
                modelHash='fixture-model',selectionAuthority='NONE',input=archive['input'],archivedEnergyBitExact=True,
                archivedEnergy=1.0,archivedEnergyBits='3ff0000000000000',witnessEnergy=1.0,
                witnessAvailability='FINITE_FIXED_CONTROL_NOT_OPTIMAL_OR_PLAUSIBILITY_PROOF',witnessMemberSha256=binding['witnessMemberSha256'])
        for c in range(11):
            rows=[]
            for i in range(c*8,min(81,(c+1)*8)):
                paths=[dict(path=p,state=0,point=points[i],prefixEnergy=1.0) for p in ['ARCHIVED_MAP','STORED_GEOMETRY_ONLY_WITNESS']]
                rows.append(dict(profile=i,paths=paths))
            values[f'detail-B{b}-{c:02d}.json']=dict(schema=1,parameter=f'G1-L1-B{b}',profiles=rows)
    for p in range(6):
        values[f'progress-{p:02d}.json']=dict(schema=1,phases={'fixture':dict(active=1)})
    for name,value in values.items():(output/name).write_text(json.dumps(value))
    return output,values

@pytest.mark.parametrize('optimized',[False,True])
def test_actual_terminal_guard_rejects_partial_malformed_binding_and_numerical_drift(tmp_path,optimized):
    output,values=receipts(tmp_path);script=snippet('root=Path(os.environ')
    env=dict(RUNNER_TEMP=str(tmp_path),COST_FREEZE_SHA256='a'*64)
    assert execute(script,tmp_path,optimized,**env).returncode == 0
    failures=[('status.json','status','INCOMPLETE'),('status.json','completedPathAccounts',2),
              ('status.json','newMapSolves',1),('status.json','selectReads',1),('status.json','ownerBytesAfterClose',1),
              ('status.json','peakAccountedBytes',67108865),('status.json','uncertainOrdinaryReservationBytes',1),
              ('binding.json','purpose','PROSPECTIVE_FIT'),('binding.json','freezeSha256','b'*64),
              ('summary-B0.json','archivedEnergyBitExact',False),('summary-B0.json','archivedEnergy',1.01),
              ('summary-B0.json','witnessAvailability','OPTIMAL'),('summary-B1.json','input',{}),
              ('summary-B0.json','witnessEnergy','UNAVAILABLE'),('detail-B0-00.json','profiles',[])]
    for name,key,value in failures:
        changed=copy.deepcopy(values[name]);changed[key]=value;(output/name).write_text(json.dumps(changed))
        assert execute(script,tmp_path,optimized,**env).returncode != 0,(name,key)
        (output/name).write_text(json.dumps(values[name]))
    (output/'summary-B0.json').write_text('{')
    assert execute(script,tmp_path,optimized,**env).returncode != 0
    (output/'summary-B0.json').unlink()
    assert execute(script,tmp_path,optimized,**env).returncode != 0

def test_clock_starts_before_registration_and_no_solver_or_search_entrypoint():
    text=(ROOT/'src/test/java/org/openstreetmap/josm/plugins/wayheatmaptracer/service/tracing/probabilistic/BSingleTrainCostDiagnosticTool.java').read_text()
    assert text.index('long started=clock.getAsLong()') < text.index('BSingleTrainCostManifest.register(digest)')
    assert '.solve(' not in text and '.search(' not in text
