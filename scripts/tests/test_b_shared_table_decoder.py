"""Independent literal UNIT wire fixtures; no source tree/artifact/VM inputs."""
from pathlib import Path
import hashlib
import importlib.util
import os
import struct
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('table_decoder',Path(__file__).resolve().parents[1]/'b-shared-table-decoder.py')
D=importlib.util.module_from_spec(spec);spec.loader.exec_module(D)

def mutf(text):
    # Fixture-only independent Java writeUTF spelling, including surrogate units.
    out=bytearray()
    for char in text:
        n=ord(char)
        if n>65535:raise AssertionError('literal UTF16 units required')
        if 0<n<128:out.append(n)
        elif n<2048:out.extend((192|(n>>6),128|(n&63)))
        else:out.extend((224|(n>>12),128|((n>>6)&63),128|(n&63)))
    return struct.pack('>H',len(out))+out

def bits(x):return struct.unpack('>Q',struct.pack('>d',x))[0]

def cell(row,state):
    # Exact original F0/CSG1 golden vector, with only row/state changed when
    # required by the complete graph. State(0,1) is the unchanged native vector.
    return (struct.pack('>ii12dB',row,state,1.,-0.,2.,1.,0.,-0.,.5,-0.,2.,-2.,0.,1.,3)+
            b'\x00\x01A\x00\x00\x01B\x00\x01C'+
            struct.pack('>di4di2d',.5,1,0.,1.,.5,1.,2,.25,.75))

def fixture(*, source='UNIT-source', extra_text='', side=2, long_text=False):
    """Specify literal roles independently; never call decoder encoding helpers."""
    strides=(9,1,208,24,32,40,80,1,704,160,176,80,8,432,28,68,16,16,16,16,20,24,1)
    graph=struct.pack('>ii',0x43534731,3)+b''.join(struct.pack('>i',len(cell(r,s)))+cell(r,s) for r,s in ((0,0),(0,1),(1,0)))
    graphhash=hashlib.sha256(graph).hexdigest()
    texts=['TABLE_ONLY_DATA_1','TABLE_ONLY','UNIT-case','a'*40,'b'*40,'1'*64,'2'*64,'3'*64,'4'*64,
           '5'*64,'6'*64,source,'scalar','UNIT-transform','7'*64,'UNIT-runtime-pending','UNIT-facts',
           '8'*64,None,None,None,graphhash,'UNIT-compiled-pending','physical-table-known-range-hybrid-leaf-v2']
    texts[12]+=extra_text
    occurrences=[struct.pack('>4iB',0,0,i,i,int(t is not None))+(mutf(t) if t is not None else b'') for i,t in enumerate(texts)]
    ordinal=24
    for mode in range(2):
        for kind in (1,2,3):
            occurrences.append(struct.pack('>4iB',kind,mode,0,ordinal,1)+mutf('\0'*8192 if long_text else 'UNIT-mode-%d-%d'%(mode,kind)));ordinal+=1
    for mode in range(2):
        for kind in (4,5):
            occurrences.append(struct.pack('>4iB',kind,mode,0,ordinal,1)+mutf('\0'*8192 if long_text else 'UNIT-correlation-%d-%d'%(mode,kind)));ordinal+=1
    textbody=b''.join(occurrences)
    records={0:b''.join(struct.pack('>QB',bits(-0. if i==0 else .5),1) for i in range(side*side)),1:b'\x01'*((side-1)**2),
             2:bytes(208*2),3:b''.join(struct.pack('>ii2d',kind,i,float(i),0.) for kind,n in ((0,2),(1,4),(2,4)) for i in range(n)),
             4:struct.pack('>16i',0,2,2,1,1,20,1,5,1,1,2,1,1,20,1,5),
             5:b''.join(struct.pack('>6iB7xQ',row,0,f,0,int(row==0 and f==2),0,
                                    int(not(row==0 and f==2)),bits(0. if row==0 and f==2 else -.0)) for row in range(2) for f in range(20)),
             6:b''.join(struct.pack('>4i2iB55x',row,0,0,3,30+2*row,31+2*row,1) for row in range(2)),7:textbody,
             8:b''.join(b''.join(struct.pack('>iiqii5d',q,0,q,t,0,.5,.5,.5,.5,0.) for t in range(9))+
                        struct.pack('>iiq12d4i',q,0,q,1.,1.,.5,.5,.5,.5,0.,.5,.5,.5,.5,0.,0,0,511,0) for q in range(2)),
             14:struct.pack('>3di3di',1.25,.01,0.,0,0.,0.,0.,1),
             15:b''.join(struct.pack('>8Q4B',bits(-0.),bits(0.),0,0,0,0,0,0,0,1,2,3) for _ in range(2))}
    counts=[len(records.get(i,b''))//stride for i,stride in enumerate(strides)]
    # Literal fixed-field ordering from SCHEMA_META_FIXED. Only role magic and
    # prospective enum/profile literals are new; fields/widths are unchanged.
    fixed=b''.join((struct.pack('>2I4I12B',0x504d5331,2,1,1,0,0,1,1,1,0,1,1,0,0,0,0,0,0),
        struct.pack('>2i6d2i7d',side,side,1,0,0,0,1,0,2,2,1,0,0,0,1,0,1),
        struct.pack('>d5d3q9q10q',1.,4.,1.,0.,0.,0.,2,2,8,96,8000000,128000000,64<<20,300000000000,360000000000,1,1,8000000,
                    2,3,2,2,4,2,2,side*side,(side-1)**2,3),
        struct.pack('>2i2dQ2qqB6QiB4i2q2i3i3B2iii',1,1,.01,1.,0x7ff8000000000000,0,0,0,0,
                    *([0x7ff8000000000000]*6),0,0,0,0,0,0,2,200,0,1,0,0,0,0,0,0,0,0,-1,24)))
    prefix=struct.pack('>237Q2i',*([0]*237),18,19)
    def shape(role,n):
        per=65528//strides[role] if role<23 else 64
        return (0 if n==0 else (n+per-1)//per, n*strides[role]+8*((n+per-1)//per))
    def metadata():
        ranges=b''
        for role,n in enumerate(counts+[3]):
            chunks,size=shape(role,n) if role<23 else (1,len(graph))
            ranges+=struct.pack('>iiqq',role,chunks,n,size)
        return fixed+ranges+struct.pack('>2i',4,4)+prefix+textbody
    records[22]=metadata();counts[22]=len(records[22]);records[22]=metadata()
    files={};entries=[]
    for role,n in enumerate(counts):
        stride=strides[role];per=65528//stride
        for chunk,start in enumerate(range(0,n,per)):
            count=min(per,n-start);data=struct.pack('>II',0x44415400+role,count)+records[role][start*stride:(start+count)*stride]
            files['r%02d-c%08d.bin'%(role,chunk)]=data
            entries.append(struct.pack('>iiqqq',role,chunk,start,count,len(data))+hashlib.sha256(data).digest())
    files['r23-c00000000.bin']=graph
    entries.append(struct.pack('>iiqqq',23,0,0,3,len(graph))+hashlib.sha256(graph).digest())
    inv=struct.pack('>II',0x494e5631,len(entries))+b''.join(entries);files['inventory-00000000.bin']=inv
    total=sum(map(len,files.values()))+88
    files['receipt.bin']=struct.pack('>4sIIIQ32s32s',b'BTR1',1,len(entries),1,total,hashlib.sha256(inv).digest(),bytes.fromhex(graphhash))
    expected={'schema':'TABLE_ONLY_DATA_1','global_texts':tuple(texts),'role_counts':tuple(counts+[3]),
              'role_availability':tuple(2 if i in (9,10,11,12,13,16,17,18,19,20,21) else 0 for i in range(24)),
              'states':(2,1),'components':(2,2),'cell_lengths':tuple(len(cell(r,s)) for r,s in ((0,0),(0,1),(1,0)))}
    return files,expected

def reseal(files):
    """Fixture mutation only: retain range words, refresh byte digests/closure.

    This deliberately lets malformed field bytes reach the structural reader;
    it does not derive expected field values from the implementation.
    """
    inv=bytearray(files['inventory-00000000.bin'])
    for at in range(8,len(inv),64):
        role,chunk=struct.unpack_from('>ii',inv,at)
        inv[at+32:at+64]=hashlib.sha256(files['r%02d-c%08d.bin'%(role,chunk)]).digest()
    files['inventory-00000000.bin']=bytes(inv)
    receipt=bytearray(files['receipt.bin']);receipt[24:56]=hashlib.sha256(inv).digest();files['receipt.bin']=bytes(receipt)

def modern_fixture(schema, *, source_side=2, analysis=(2, 2)):
    """Literal v3/v4 UNIT data, independently framed from the old golden roles."""
    old, expected = fixture(side=source_side)
    version = {'TABLE_ONLY_DATA_2': 3, 'TABLE_ONLY_SOURCE_DATA_4': 4}[schema]
    globals = list(expected['global_texts'])
    globals[0] = schema
    globals[23] = 'physical-table-adjacent-source-range-v3'
    if version == 4:
        globals.append('UNIT-full-native-parameter-version')
    text = b''.join(struct.pack('>4iB', 0, 0, i, i, int(t is not None)) +
                    (mutf(t) if t is not None else b'') for i, t in enumerate(globals))
    ordinal = len(globals)
    for mode in range(2):
        for kind in (1, 2, 3):
            text += struct.pack('>4iB', kind, mode, 0, ordinal, 1) + mutf('UNIT-mode-%d-%d' % (mode, kind))
            ordinal += 1
    for mode in range(2):
        for kind in (4, 5):
            text += struct.pack('>4iB', kind, mode, 0, ordinal, 1) + mutf('UNIT-correlation-%d-%d' % (mode, kind))
            ordinal += 1
    strides = (9, 1, 208, 24, 32, 40, 80, 1, 704, 160, 176, 80, 8, 432, 28, 68, 16, 16, 16, 16, 20, 24, 1)
    bodies = {role: old['r%02d-c00000000.bin' % role][8:]
              for role in range(23) if 'r%02d-c00000000.bin' % role in old}
    fixed = bytearray(bodies[22][:561])
    struct.pack_into('>i', fixed, 4, version)
    struct.pack_into('>2i', fixed, 92, *analysis)
    struct.pack_into('>q', fixed, 514, 100*analysis[0]*analysis[1])
    parameters = (struct.pack('>idBBi4d8d2i11d', 1, 4., 0, 4, -1, 0., .25, 1., 4.,
                             1., 1., 2., .5, .5, .5235987755982988, 1., 2.,
                             9, 256, 6., 4., .5, .75, .2, .1, .1, .72, .92, 8., .08))
    if version == 4:
        fixed = fixed[:164] + parameters + fixed[204:]
    assert len(fixed) == (731 if version == 4 else 561)
    bodies[7] = text
    bodies[6] = b''.join(struct.pack('>4i2iB55x', row, 0, 0, 3,
                                    len(globals)+6+2*row, len(globals)+7+2*row, 1) for row in range(2))
    bodies[15] = bodies[15][:68] * (analysis[0]*analysis[1])
    counts = [len(bodies.get(i, b'')) // stride for i, stride in enumerate(strides)] + [3]
    counts[22] = len(fixed) + 24*24 + 8 + 237*8 + 8 + len(text)
    ranges = b''.join(struct.pack('>iiqq', role, int(n > 0), n,
                                  (len(old['r23-c00000000.bin']) if role == 23 else n*strides[role]+8*int(n > 0)))
                      for role, n in enumerate(counts))
    bodies[22] = bytes(fixed) + ranges + struct.pack('>2i', 4, 4) + struct.pack('>237Q2i', *([0]*237), 18, 19) + text
    files = {'r%02d-c00000000.bin' % r: struct.pack('>II', 0x44415400+r, counts[r])+body
             for r, body in bodies.items()}
    files['r23-c00000000.bin'] = old['r23-c00000000.bin']
    entries = []
    for r, n in enumerate(counts):
        if n:
            data = files['r%02d-c00000000.bin' % r]
            entries.append(struct.pack('>iiqqq', r, 0, 0, n, len(data))+hashlib.sha256(data).digest())
    inv = struct.pack('>II', 0x494e5631, len(entries))+b''.join(entries)
    files['inventory-00000000.bin'] = inv
    total = sum(map(len, files.values()))+88
    files['receipt.bin'] = struct.pack('>4sIIIQ32s32s', b'BTR1', 1, len(entries), 1, total,
                                      hashlib.sha256(inv).digest(), bytes.fromhex(globals[21]))
    expected.update(schema=schema, global_texts=tuple(globals), role_counts=tuple(counts))
    return files, expected, parameters

def write(root,files):
    root.mkdir()
    for name,data in files.items():(root/name).write_bytes(data)

class DecoderTests(unittest.TestCase):
    def test_modern_declared_and_source_versions_remain_distinct_structural_data(self):
        for schema, version, globals_count in (('TABLE_ONLY_DATA_2', 3, 24),
                                                ('TABLE_ONLY_SOURCE_DATA_4', 4, 25)):
            with self.subTest(schema=schema), tempfile.TemporaryDirectory() as tmp:
                self.assertIn(schema, getattr(D, 'SCHEMAS', {}), 'modern schema is not supported')
                files, expected, params = modern_fixture(schema)
                root = Path(tmp)/'bundle'
                write(root, files)
                result = D.decode_directory(root, expected)
                self.assertFalse(result['qualified'])
                self.assertEqual(result['runtime'], 'UNAVAILABLE')
                self.assertEqual(result['metadata_raw']['schemaVersionCode'], (version,))
                self.assertEqual(len(result['global_texts']), globals_count)
                self.assertEqual(result['role_counts'][15], 4)
                self.assertEqual(result['metadata_raw']['compactAndOwnQueryCounts'], (2, 400))
                if version == 4:
                    fields = result['metadata_raw']
                    self.assertEqual(fields['parameterEncoding'], (1,))
                    self.assertEqual(fields['etaSelection'], (0, 4, -1))
                    self.assertEqual(fields['supportedEta'], tuple(bits(v) for v in (0., .25, 1., 4.)))
                    self.assertEqual(fields['nativeParameters'], struct.unpack_from('>8Q', params, 50))
                    self.assertEqual(fields['localizationInts'], (9, 256))
                    self.assertEqual(fields['localizationParameters'], struct.unpack_from('>11Q', params, 122))
                    self.assertNotIn('gainEtaAndOriginalParameters', fields)

    def test_source_version_rejects_an_invented_selected_eta_and_family(self):
        for offset, value in ((176, b'\x01'), (177, b'\x03'), (178, struct.pack('>i', 0)),
                              (182, struct.pack('>d', .125)), (164, struct.pack('>i', 99))):
            with self.subTest(offset=offset), tempfile.TemporaryDirectory() as tmp:
                files, expected, _ = modern_fixture('TABLE_ONLY_SOURCE_DATA_4')
                frame = bytearray(files['r22-c00000000.bin'])
                frame[8+offset:8+offset+len(value)] = value
                files['r22-c00000000.bin'] = bytes(frame)
                reseal(files)
                root = Path(tmp)/'bundle'
                write(root, files)
                with self.assertRaisesRegex(D.DecodeError, 'PARAMETER_(ENCODING|ETA_POLICY)'):
                    D.decode_directory(root, expected)

    def test_modern_visual_plane_and_query_counts_follow_analysis_not_source_or_path(self):
        for schema in ('TABLE_ONLY_DATA_2', 'TABLE_ONLY_SOURCE_DATA_4'):
            with self.subTest(schema=schema), tempfile.TemporaryDirectory() as tmp:
                files, expected, _ = modern_fixture(schema, source_side=3, analysis=(3, 2))
                root = Path(tmp)/'bundle'
                write(root, files)
                result = D.decode_directory(root, expected)
                self.assertEqual(result['role_counts'][0], 9)
                self.assertEqual(result['role_counts'][15], 6)
                self.assertEqual(result['metadata_raw']['originalPointRingVertexCounts'][0], 2)
                self.assertEqual(result['metadata_raw']['compactAndOwnQueryCounts'], (2, 600))

    def test_source_parameter_raw_bits_and_unavailable_prefix_remain_diagnostic_only(self):
        files, expected, _ = modern_fixture('TABLE_ONLY_SOURCE_DATA_4')
        frame = bytearray(files['r22-c00000000.bin'])
        struct.pack_into('>Q', frame, 8+214, 0x8000000000000000)  # first native parameter
        prefix = 8+731+24*24+8
        struct.pack_into('>Q', frame, prefix+98*8, 0x7ff8000000000042)
        files['r22-c00000000.bin'] = bytes(frame)
        reseal(files)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)/'bundle'
            write(root, files)
            result = D.decode_directory(root, expected)
        self.assertEqual(result['metadata_raw']['nativeParameters'][0], 0x8000000000000000)
        self.assertEqual(result['prefix_longs'][98], 0x7ff8000000000042)
        self.assertFalse(result['qualified'])
        self.assertEqual(result['factual_authority'], 'NOT_ESTABLISHED')
        self.assertEqual(result['solver_results'], 'NOT_RUN')

    def test_source_parameter_nonfinite_values_refuse_even_with_fresh_hashes(self):
        for offset in (214, 286):  # native first and localization first raw64
            with self.subTest(offset=offset), tempfile.TemporaryDirectory() as tmp:
                files, expected, _ = modern_fixture('TABLE_ONLY_SOURCE_DATA_4')
                frame = bytearray(files['r22-c00000000.bin'])
                struct.pack_into('>Q', frame, 8+offset, 0x7ff8000000000042)
                files['r22-c00000000.bin'] = bytes(frame)
                reseal(files)
                root = Path(tmp)/'bundle'
                write(root, files)
                with self.assertRaisesRegex(D.DecodeError, '^PARAMETER_NONFINITE$'):
                    D.decode_directory(root, expected)

    def test_retained_actual_java_parameter_sections_match_the_additive_decoder(self):
        # Only these210B sections are actual Java producer output. The enclosing
        # UNIT frame is synthetic; it cannot establish a complete export join.
        vectors = (
            ('actual-source-default-210.hex', '0d1b0f76ccdd4f309c2341c2e322948fc2596b8bc4b051a9d8ed0ba02393c9bc',
             (18, 16384), (1., 1., 2., .5, .5, .5235987755982988, 1., 2.),
             (6., 4., .5, .75, .2, .1, .1, .72, .92, 8., .08)),
            ('actual-source-signed-zero-210.hex', 'd38a45647a016500e1ed0ded81ab8c6c291fe484d137ab8324f3a76fb6af2ced',
             (9, 8192), (-0., 1., 2., .25, .75, .5235987755982988, 2., 3.),
             (7., 5., .25, .5, .3, .2, .2, .6, .95, 9., .09)),
        )
        for name, digest, ints, native, localization in vectors:
            with self.subTest(vector=name), tempfile.TemporaryDirectory() as tmp:
                section = bytes.fromhex((Path(__file__).with_name('fixtures')/name).read_text())
                self.assertEqual(len(section), 210)
                self.assertEqual(hashlib.sha256(section).hexdigest(), digest)
                files, expected, _ = modern_fixture('TABLE_ONLY_SOURCE_DATA_4')
                frame = bytearray(files['r22-c00000000.bin'])
                frame[8+164:8+374] = section
                files['r22-c00000000.bin'] = bytes(frame)
                reseal(files)
                root = Path(tmp)/'bundle'
                write(root, files)
                result = D.decode_directory(root, expected)
                fields = result['metadata_raw']
                self.assertEqual(fields['gain'], (bits(1.),))
                self.assertEqual(fields['nativeParameters'], tuple(bits(v) for v in native))
                self.assertEqual(fields['localizationInts'], ints)
                self.assertEqual(fields['localizationParameters'], tuple(bits(v) for v in localization))
                self.assertFalse(result['qualified'])

    def check_bad_record(self,role,offset,value,code):
        files,expected=fixture();name='r%02d-c00000000.bin'%role;data=bytearray(files[name])
        data[8+offset:8+offset+len(value)]=value;files[name]=bytes(data);reseal(files)
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)/'bundle';write(root,files)
            with self.assertRaisesRegex(D.DecodeError,code):D.decode_directory(root,expected)

    def test_literal_complete_bundle_preserves_raw_bits_and_unavailable_semantics(self):
        files,expected=fixture(source='UNIT\0\ud83d\ude00');seen=[]
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)/'bundle';write(root,files)
            result=D.decode_directory(root,expected,seen.append)
        self.assertFalse(result['qualified']);self.assertEqual('STRUCTURAL_DATA',result['scope'])
        self.assertEqual('NOT_ESTABLISHED',result['factual_authority']);self.assertEqual('UNAVAILABLE',result['runtime'])
        self.assertEqual(tuple(expected['global_texts']),result['global_texts'])
        self.assertEqual(24,len(result['role_counts']));self.assertEqual(237,len(result['prefix_longs']))
        vertex=next(r for r in seen if r.role==0);self.assertEqual(1<<63,vertex.require_values()[0])
        rejected=next(r for r in seen if r.role==14 and r.ordinal==1)
        self.assertEqual('NOT_ADMITTED',rejected.availability)
        with self.assertRaisesRegex(D.DecodeError,'VALUE_UNAVAILABLE'):rejected.require_values()
        fact=next(r for r in seen if r.role==5 and r.ordinal==2)
        with self.assertRaisesRegex(D.DecodeError,'VALUE_UNAVAILABLE'):fact.require_values()
        original=next(r for r in seen if r.role==23 and r.ordinal==1)
        self.assertEqual(cell(0,1),original.raw)
        self.assertEqual((0,1,0x3ff0000000000000,0x8000000000000000,0x4000000000000000,
            0x3ff0000000000000,0,0x8000000000000000,0x3fe0000000000000,0x8000000000000000,
            0x4000000000000000,0xc000000000000000,0,0x3ff0000000000000,3,'A',0,'B','C',
            0x3fe0000000000000,((0,0x3ff0000000000000,0x3fe0000000000000,0x3ff0000000000000),),
            (0x3fd0000000000000,0x3fe8000000000000)),original.require_values())

    def test_unknown_query_retains_raw_nan_and_never_returns_a_measured_zero(self):
        files,expected=fixture();name='r08-c00000000.bin';data=bytearray(files[name]);seen=[]
        data[8+20:8+24]=struct.pack('>i',1)
        data[8+24:8+64]=struct.pack('>5Q',*([0x7ff8000000000042]*5))
        data[8+576+124:8+576+128]=struct.pack('>i',1)
        files[name]=bytes(data);reseal(files)
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)/'bundle';write(root,files);D.decode_directory(root,expected,seen.append)
        row=next(r for r in seen if r.role==8)
        self.assertEqual('UNAVAILABLE',row.availability)
        self.assertEqual(0x7ff8000000000042,struct.unpack_from('>Q',row.raw,24)[0])
        with self.assertRaisesRegex(D.DecodeError,'VALUE_UNAVAILABLE'):row.require_values()

    def test_maximal_whole_records_and_cross_leaf_byte_streams(self):
        files,expected=fixture(side=90,long_text=True);seen=[]
        self.assertEqual(8+7280*9,len(files['r00-c00000000.bin']))
        self.assertGreater(len([n for n in files if n.startswith('r07-')]),1)
        self.assertGreater(len([n for n in files if n.startswith('r22-')]),1)
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)/'bundle';write(root,files);result=D.decode_directory(root,expected,seen.append)
        self.assertEqual(8100,sum(r.role==0 for r in seen));self.assertEqual(expected['role_counts'],result['role_counts'])
        files,expected=fixture(side=90);data=bytearray(files['r00-c00000000.bin']);data[4:8]=struct.pack('>I',7279)
        files['r00-c00000000.bin']=bytes(data);reseal(files)
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)/'bundle';write(root,files)
            with self.assertRaisesRegex(D.DecodeError,'LEAF_HEADER'):D.decode_directory(root,expected)

    def test_actual_typed_record_semantics_even_with_resealed_hashes(self):
        cases=((0,8,b'\x02','BOOLEAN'),(3,0,struct.pack('>i',3),'GEOMETRY_INDEX'),
          (4,12,struct.pack('>i',2),'PROFILE_SHAPE'),(5,24,b'\x00','FACT_STATUS'),
          (5,2*40+24,b'\x01','FACT_STATUS'),(5,25,b'\x01','FACT_STATUS'),
          (6,12,struct.pack('>i',4),'PROVENANCE_TAG'),(6,79,b'\x01','UNION_PADDING'),
          (8,16,struct.pack('>i',8),'QUERY_INDEX_STATUS'),(8,576+120,struct.pack('>i',1023),'QUERY_STATUS_MASK'),
          (14,24,struct.pack('>i',4),'TABLE_REASON'),(14,0,struct.pack('>d',-1.),'TABLE_AVAILABLE_RANGE'),
          (15,64,b'\x04','VISUAL_STATUS'),(23,4+104,b'\x04','GRAPH_FLAGS'),
          (23,4+105,b'\xff\xff','TEXT_BOUND'),(23,4+159,struct.pack('>i',3),'GRAPH_COMPONENTS'))
        for role,offset,value,code in cases:
            with self.subTest(role=role,offset=offset):self.check_bad_record(role,offset,value,code)

    def test_metadata_fields_ranges_not_run_and_version_are_structural(self):
        self.check_bad_record(22,4,struct.pack('>i',3),'METADATA_VERSION')
        self.check_bad_record(22,27,b'\x01','NOT_RUN_POLICY')
        self.check_bad_record(22,36,struct.pack('>i',1),'GRID_SHAPE')
        # Width census is independent of the reader's named tuple/constants.
        fixed_bytes=4+4+16+12+8+48+8+56+8+40+24+72+80+4+4+16+8+16+8+1+48+4+1+16+16+8+12+3+8+4+4
        self.check_bad_record(22,fixed_bytes+8,struct.pack('>q',5),'ROLE_RANGE')
        self.check_bad_record(22,fixed_bytes-19,b'\x02','NOT_RUN_POLICY')

    def test_required_declared_data_availability_cannot_silently_become_zero(self):
        for flag in (1,2,4,5):
            with self.subTest(flag=flag):self.check_bad_record(22,24+flag,b'\x00','METADATA_AVAILABILITY')

    def test_profile_provenance_count_and_text_owner_joins(self):
        for role,offset,value,code in ((4,24,2,'PROFILE_RECORD_JOIN'),(6,16,0,'CORRELATION_TEXT_JOIN')):
            with self.subTest(role=role):self.check_bad_record(role,offset,struct.pack('>i',value),code)

    def test_modified_utf8_rejects_raw_nul_overlong_four_byte_and_truncated_units(self):
        for raw in (b'\x00',b'\xc1\x81',b'\xe0\x80\x80',b'\xf0\x90\x80\x80',b'\xc2',b'\xe1\x80'):
            with self.subTest(raw=raw),self.assertRaises(D.DecodeError):D._mutf(raw)
        self.assertEqual('\0\ud83d\ude00',D._mutf(b'\xc0\x80\xed\xa0\xbd\xed\xb8\x80'))
        self.check_bad_record(7,19,b'\x00','MUTF_GRAMMAR')
        self.check_bad_record(7,0,struct.pack('>i',1),'TEXT_ORDER')

    def test_real_directory_and_leaf_no_follow_and_callback_drift_refuse(self):
        for change in ('parent-link','root-link','replace','in-place','extra-during','cancel'):
            with self.subTest(change=change),tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp);files,expected=fixture();parent=root/'parent';parent.mkdir();bundle=parent/'bundle';write(bundle,files)
                if change=='parent-link':parent.rename(root/'moved');parent.symlink_to(root/'moved',target_is_directory=True)
                elif change=='root-link':bundle.rename(parent/'moved');bundle.symlink_to(parent/'moved',target_is_directory=True)
                changed=[]
                def consume(record):
                    if changed:return
                    changed.append(True);leaf=bundle/'r00-c00000000.bin'
                    if change=='replace':
                        leaf.rename(root/'original');leaf.write_bytes((root/'original').read_bytes())
                    elif change=='in-place':
                        with leaf.open('r+b') as out:out.seek(8);out.write(b'x')
                    elif change=='extra-during':(bundle/'unexpected.bin').write_bytes(b'unlisted DATA')
                def cancel():return change=='cancel'
                identity=D._identity
                # Model a coarse unchanged timestamp explicitly; the bytes and
                # descriptor/inode checks remain real. No timing/sleep oracle.
                def metadata(info):return identity(info)[:6]+(0,0) if change=='in-place' else identity(info)
                with patch.object(D,'_identity',side_effect=metadata),self.assertRaises(D.DecodeError):
                    D.decode_directory(bundle,expected,consume,cancel)

    def test_large_inventory_formula_and_total_bound_are_checked_before_io(self):
        _,expected=fixture();counts=list(expected['role_counts']);counts[8]=100000;other=dict(expected,role_counts=tuple(counts))
        schedule,sizes,inv_count,total=D._plan(other)
        self.assertEqual(2,inv_count);self.assertEqual(1076,sizes[8][0])
        self.assertEqual(100000*704+8*1076,sizes[8][1]);self.assertEqual(sum(x[1] for x in sizes)+64*len(schedule)+8*inv_count+88,total)
        counts[8]=3100000;other=dict(expected,role_counts=tuple(counts))
        with patch.object(os,'open',side_effect=AssertionError('OPEN_BEFORE_TOTAL_REFUSAL')):
            with self.assertRaisesRegex(D.DecodeError,'TOTAL_BOUND'):D.decode_directory('/unused',other)

    def test_separate_contract_mismatch_and_missing_authority_cannot_promote(self):
        files,expected=fixture()
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)/'bundle';write(root,files)
            for field in (2,5,11,23):
                other=dict(expected);texts=list(other['global_texts']);texts[field]='WRONG';other['global_texts']=tuple(texts)
                with self.subTest(field=field),self.assertRaisesRegex(D.DecodeError,'TEXT_CONTRACT'):D.decode_directory(root,other)
            with self.assertRaisesRegex(D.DecodeError,'CONTRACT_REQUIRED'):D.decode_directory(root,None)

    def test_self_consistent_wrong_approximation_is_not_this_versioned_abi(self):
        files,expected=fixture();old=b'physical-table-known-range-hybrid-leaf-v2';new=b'X'+old[1:]
        texts=list(expected['global_texts']);texts[23]=new.decode();expected['global_texts']=tuple(texts)
        for name in ('r07-c00000000.bin','r22-c00000000.bin'):
            self.assertEqual(1,files[name].count(old));files[name]=files[name].replace(old,new)
        reseal(files)
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)/'bundle';write(root,files)
            with self.assertRaisesRegex(D.DecodeError,'TEXT_CONTRACT'):D.decode_directory(root,expected)

    def test_before_materialization_bounds_and_not_run_cannot_mean_available_zero(self):
        _,expected=fixture()
        for key,value in (('role_counts',tuple([1<<63]+[0]*23)),('states',(97,)),('global_texts',('x'*8193,)*24)):
            other=dict(expected);other[key]=value
            with self.subTest(key=key),patch.object(os,'open',side_effect=AssertionError('OPEN_BEFORE_REFUSAL')):
                with self.assertRaises(D.DecodeError):D.decode_directory('/unused',other)
        other=dict(expected);availability=list(other['role_availability']);availability[9]=0;other['role_availability']=tuple(availability)
        with self.assertRaisesRegex(D.DecodeError,'NOT_RUN_POLICY'):D.decode_directory('/unused',other)
        other=dict(expected,qualified=True)
        with self.assertRaisesRegex(D.DecodeError,'CONTRACT_REQUIRED'):D.decode_directory('/unused',other)

    def test_supplied_contract_is_pinned_before_callbacks_and_unsupported_io_refuses(self):
        files,expected=fixture();original=dict(expected);changed=[]
        def consume(record):
            if not changed:changed.append(True);expected['global_texts']=('CALLER_REPLACEMENT',)*24
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)/'bundle';write(root,files)
            result=D.decode_directory(root,expected,consume)
        self.assertTrue(changed);self.assertEqual(original['global_texts'],result['global_texts'])
        with patch.object(os,'supports_dir_fd',set()),self.assertRaisesRegex(D.DecodeError,'UNSUPPORTED_NOFOLLOW_IO'):
            D.decode_directory('/unused',original)

    def test_real_owned_descriptors_close_on_success_failure_and_secondary_close(self):
        for fail in (False,True):
            with self.subTest(fail=fail),tempfile.TemporaryDirectory() as tmp:
                files,expected=fixture();root=Path(tmp)/'bundle';write(root,files);closed=[];close=os.close;read=os.read
                def cleanup(fd):
                    close(fd);closed.append(fd)
                    if fail:raise OSError('controlled secondary close')
                def body(fd,n):
                    if fail:raise D.DecodeError('CANCELLED')
                    return read(fd,n)
                with patch.object(os,'close',side_effect=cleanup),patch.object(os,'read',side_effect=body):
                    if fail:
                        with self.assertRaisesRegex(D.DecodeError,'CANCELLED'):D.decode_directory(root,expected)
                    else:D.decode_directory(root,expected)
                self.assertTrue(closed)
                for fd in set(closed):
                    with self.assertRaises(OSError):os.fstat(fd)

    def test_final_barrier_never_calls_later_mutator_after_earlier_leaf_recheck(self):
        # Actual prior bug: final callback five runs after r00's recheck while
        # a later leaf is read. This is a synchronous caller, not a writer thread.
        files,expected=fixture();finish=D._Directory.finish;phase=[];polls=[];changed=[]
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)/'bundle';write(root,files);leaf=root/'r00-c00000000.bin'
            def enter(directory):
                phase.append(True)
                return finish(directory)
            def cancel():
                if phase:
                    polls.append(True)
                    if len(polls)==5:
                        raw=bytearray(leaf.read_bytes());raw[8]^=1;leaf.write_bytes(raw);changed.append(True)
                return False
            with patch.object(D._Directory,'finish',enter):
                result=D.decode_directory(root,expected,cancel=cancel)
            self.assertEqual('STRUCTURAL_DATA',result['scope'])
            self.assertEqual(files[leaf.name],leaf.read_bytes(), 'late callback changed validated bytes')
            self.assertFalse(changed)
            self.assertEqual(1,len(polls), 'only the final boundary-entry callback is permitted')

    def test_final_boundary_callback_mutation_or_cancellation_refuses_before_publication(self):
        for mode in ('in-place','new-inode','cancel','throw'):
            with self.subTest(mode=mode),tempfile.TemporaryDirectory() as tmp:
                files,expected=fixture();root=Path(tmp)/'bundle';write(root,files)
                finish=D._Directory.finish;phase=[];polls=[];leaf=root/'r00-c00000000.bin'
                def enter(directory):
                    phase.append(True)
                    return finish(directory)
                def cancel():
                    if phase:
                        polls.append(True)
                        if mode=='in-place':
                            raw=bytearray(leaf.read_bytes());raw[8]^=1;leaf.write_bytes(raw)
                        elif mode=='new-inode':
                            replacement=root/'replacement';replacement.write_bytes(leaf.read_bytes())
                            replacement.replace(leaf)
                        elif mode=='throw':raise RuntimeError('UNIT callback failure')
                        return mode=='cancel'
                    return False
                with patch.object(D._Directory,'finish',enter):
                    with self.assertRaisesRegex(D.DecodeError,'CANCELLED' if mode in ('cancel','throw') else 'LEAF_DRIFT'):
                        D.decode_directory(root,expected,cancel=cancel)
                self.assertEqual(1,len(polls))

    def test_missing_extra_symlink_and_truncated_leaves_refuse(self):
        for mutation in ('missing','extra','symlink','short','oversize'):
            with self.subTest(mutation=mutation),tempfile.TemporaryDirectory() as tmp:
                files,expected=fixture();root=Path(tmp)/'bundle';write(root,files);leaf=root/'r00-c00000000.bin'
                if mutation=='missing':leaf.unlink()
                elif mutation=='extra':(root/'unexpected.bin').write_bytes(b'private not read')
                elif mutation=='symlink':leaf.rename(Path(tmp)/'moved');leaf.symlink_to(Path(tmp)/'moved')
                elif mutation=='short':leaf.write_bytes(leaf.read_bytes()[:-1])
                else:leaf.write_bytes(b'x'*65537)
                with self.assertRaises(D.DecodeError):D.decode_directory(root,expected)

    def test_inventory_digest_range_order_and_closure_tampering(self):
        for mutation in ('digest','range','order','extra-entry','receipt','graph-hash'):
            with self.subTest(mutation=mutation),tempfile.TemporaryDirectory() as tmp:
                files,expected=fixture();name='inventory-00000000.bin';data=bytearray(files[name])
                if mutation=='digest':data[40]^=1
                elif mutation=='range':data[16:24]=struct.pack('>q',1)
                elif mutation=='order':data[8:72],data[72:136]=data[72:136],data[8:72]
                elif mutation=='extra-entry':data+=data[8:72]
                elif mutation=='receipt':files['receipt.bin']=files['receipt.bin'][:-1]+b'x'
                else:
                    receipt=bytearray(files['receipt.bin']);receipt[-1]^=1;files['receipt.bin']=bytes(receipt)
                files[name]=bytes(data);root=Path(tmp)/'bundle';write(root,files)
                with self.assertRaises(D.DecodeError):D.decode_directory(root,expected)

if __name__=='__main__':unittest.main()
