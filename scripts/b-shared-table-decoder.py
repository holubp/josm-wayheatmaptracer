"""Independent streaming versioned TABLE_ONLY structural DATA reader.

No Java/Work/runtime capability is issued. An independently supplied expected
contract is compared with the bytes; its factual provenance is NOT established
by this reader. No worktree, source import, native tool or registry is consulted.
New framing literals are prospective, not claims of existing writer conformance.
"""
from pathlib import Path
import hashlib
import os
import stat
import struct

LEAF=65536
TOTAL=2<<30
LONG=(1<<63)-1
ROLES=(('source_vertex',9),('source_cell_mask',1),('source_descriptor',208),
 ('source_geometry',24),('profile_shape',32),('scalar_fact',40),('provenance',80),('text',1),
 ('per_mode_query',704),('selected_attribution',160),('local_component',176),('local_cell',80),
 ('local_width',8),('local_shared',432),('table',28),('visual',68),('position_marginal',16),
 ('component_marginal',16),('forward_marginal',16),('backward_marginal',16),('route_state',20),
 ('credible_interval',24),('metadata',1),('CSG1',0))
TEXT_FIELDS=('schemaVersion','scope','caseId','reviewedCodeCommit','actualCheckoutCommit',
 'protocolSha256','sourceClosureSha256','publicArchiveSha256','sourceBytesSha256','snapshotHash',
 'factsAndLatticeHash','sourceIdentity','selectedFieldKey','transformId','resourceProofSha256',
 'runtimeCapabilityId','factsVersion','oracleSourceSha256','oldExecutionPolicy','crossStreamVersion',
 'solverExplanation','rawGraphSha256','compiledBlockVersion','tableApproximationId')
META_FIXED=(('schemaMagic',4,1),('schemaVersionCode',4,1),('scopePhaseCauseStatus',4,4),
 ('availabilityAndDispositionFlags',1,12),('sourceWidthHeight',4,2),('sourceAffine',8,6),
 ('analysisWidthHeight',4,2),('analysisAffineAndRho',8,7),('sourcePitch',8,1),
 ('gainEtaAndOriginalParameters',8,5),('originalPointRingVertexCounts',8,3),('originalCaps',8,9),
 ('attemptCountsAndCardinalities',8,10),('localSharedLayoutVersion',4,1),('tableUnknownErrorRule',4,1),
 ('tableUnknownErrorUpperAndL0',8,2),('logPartition',8,1),('reportedPairVisitsAndTransitions',8,2),
 ('actualOriginalTableGeometryUnits',8,1),('actualOriginalTableGeometryUnitsAvailable',1,1),
 ('nativeGapSummaryLengths',8,6),('credibleRowCount',4,1),('credibleRowsAvailable',1,1),
 ('marginalRowCounts',4,4),('compactAndOwnQueryCounts',8,2),('selectedAttributionCountAndLayout',4,2),
 ('completionInts',4,3),('completionFlags',1,3),('rawDistinctPathCounts',4,2),('mapPathRawIndex',4,1),
 ('completeRoleCount',4,1))
NOT_RUN=frozenset((9,10,11,12,13,16,17,18,19,20,21))
APPROXIMATION='physical-table-known-range-hybrid-leaf-v2'
SCHEMAS={'TABLE_ONLY_DATA_1': (2,24,APPROXIMATION),
         'TABLE_ONLY_DATA_2': (3,24,'physical-table-adjacent-source-range-v3'),
         'TABLE_ONLY_SOURCE_DATA_4': (4,25,'physical-table-adjacent-source-range-v3')}

class DecodeError(ValueError):
    """Finite structural DATA refusal; no arbitrary input text is included."""

def need(ok,code):
    if not ok:raise DecodeError(code)

def integer(value,maximum=LONG):
    need(type(value) is int and 0<=value<=maximum,'COUNT_BOUND')
    return value

def checkpoint(cancel):
    try:stopped=cancel()
    except Exception:raise DecodeError('CANCELLED') from None
    need(not stopped,'CANCELLED')

class Record:
    """One bounded decoded record; raw bits remain diagnostic when unavailable.

    Availability is the wire claim, not factual source authority. A callback
    may see a prefix before a later refusal. Only the completed decode result
    establishes structural closure; neither establishes facts.
    """
    __slots__=('role','ordinal','raw','values','availability')
    def __init__(self,role,ordinal,raw,values,availability='AVAILABLE'):
        self.role=role;self.ordinal=ordinal;self.raw=raw;self.values=values;self.availability=availability
    def require_values(self):
        need(self.availability=='AVAILABLE','VALUE_UNAVAILABLE')
        return self.values

def _plan(expected):
    keys={'schema','global_texts','role_counts','role_availability','states','components','cell_lengths'}
    need(type(expected) is dict and set(expected)==keys,'CONTRACT_REQUIRED')
    need(type(expected['schema']) is str and expected['schema'] in SCHEMAS,'CONTRACT_SCHEMA')
    version,global_count,approximation=SCHEMAS[expected['schema']]
    texts=expected['global_texts'];counts=expected['role_counts'];availability=expected['role_availability']
    need(type(texts) is tuple and len(texts)==global_count,'TEXT_CONTRACT')
    for i,text in enumerate(texts):
        need((text is None and i in (18,19,20)) or (type(text) is str and 0<len(text)<=8192 and
             all(ord(c)<=65535 for c in text)),'TEXT_CONTRACT')
    need(texts[0]==expected['schema'] and texts[1]=='TABLE_ONLY' and texts[23]==approximation,'TEXT_CONTRACT')
    need(type(counts) is tuple and len(counts)==24,'ROLE_CONTRACT')
    for n in counts:integer(n)
    need(type(availability) is tuple and len(availability)==24,'ROLE_CONTRACT')
    for role,flag in enumerate(availability):
        need(type(flag) is int and flag==(2 if role in NOT_RUN else 0),'NOT_RUN_POLICY')
        if role in NOT_RUN:need(counts[role]==0,'NOT_RUN_POLICY')
    states=expected['states'];components=expected['components'];lengths=expected['cell_lengths']
    need(type(states) is tuple and 1<=len(states)<=81 and type(components) is tuple and len(components)==len(states),'GRAPH_SHAPE')
    for n in states:need(type(n) is int and 1<=n<=96,'GRAPH_SHAPE')
    for n in components:integer(n,8192)
    need(type(lengths) is tuple and len(lengths)==sum(states)==counts[23],'GRAPH_SHAPE')
    need(counts[4]==len(states) and counts[2]<=8192 and counts[0]<=191401 and counts[1]<=190404,'ROLE_CONTRACT')
    need(counts[14]==sum(a*b for a,b in zip(states,states[1:]))<=8000000,'PAIR_COUNT')
    need(counts[5]==20*counts[2] and (2 if version==2 else 4)<=counts[15]<=191401,'ROLE_CONTRACT')
    schedule=[];sizes=[];total=88;leaf_count=0
    # Bound aggregate arithmetic before creating any per-leaf container/open.
    for role,(name,stride) in enumerate(ROLES[:23]):
        n=counts[role];per=65528//stride;chunks=(n+per-1)//per
        size=n*stride+8*chunks;total+=size;leaf_count+=chunks
        need(total<=TOTAL,'TOTAL_BOUND');sizes.append((chunks,size))
    graph=[];start=0;chunk_size=8;chunk_count=0
    for index,length in enumerate(lengths):
        need(type(length) is int and 128<=length<=65524,'CELL_BOUND')
        if chunk_count==64 or chunk_size+4+length>LEAF:
            graph.append((start,chunk_count,chunk_size));start=index;chunk_size=8;chunk_count=0
        chunk_size+=4+length;chunk_count+=1
    if chunk_count:graph.append((start,chunk_count,chunk_size))
    need(len(graph)<=128,'GRAPH_CHUNK_BOUND')
    graph_size=sum(row[2] for row in graph);sizes.append((len(graph),graph_size))
    total+=graph_size;leaf_count+=len(graph);inventories=(leaf_count+1022)//1023
    total+=64*leaf_count+8*inventories;need(total<=TOTAL,'TOTAL_BOUND')
    for role,(name,stride) in enumerate(ROLES[:23]):
        n=counts[role];per=65528//stride
        for chunk,start in enumerate(range(0,n,per)):
            count=min(per,n-start);schedule.append((role,chunk,start,count,8+count*stride))
    schedule.extend((23,i,*row) for i,row in enumerate(graph))
    return schedule,tuple(sizes),inventories,total

def _identity(info):
    return (info.st_dev,info.st_ino,info.st_mode,info.st_uid,info.st_gid,info.st_size,info.st_mtime_ns,info.st_ctime_ns)

def _close(fd,primary):
    try:os.close(fd)
    except OSError:
        if primary is None:raise DecodeError('DATA_CLOSE') from None

class _Directory:
    def __init__(self,path,names,cancel):
        self.path=Path(path).absolute();self.cancel=cancel;self.owners=[];self.seen={};self.fd=None;self.names=frozenset(names)
        for p in reversed((self.path,*self.path.parents)):
            info=p.lstat();need(stat.S_ISDIR(info.st_mode),'DIRECTORY_NOFOLLOW')
            self.owners.append((p,(info.st_dev,info.st_ino)))
        self.fd=os.open(self.path,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
        try:
            self.guard();self.scan();self.guard()
        except BaseException as error:
            _close(self.fd,error);self.fd=None;raise
    def scan(self):
        remaining=set(self.names)
        with os.scandir(self.fd) as entries:
            for index,entry in enumerate(entries):
                need(index<len(self.names) and entry.name in remaining,'EXTRA_LEAF');remaining.remove(entry.name)
        need(not remaining,'MISSING_LEAF')
    def guard(self,*,poll=True):
        if poll:checkpoint(self.cancel)
        for path,key in self.owners:
            info=path.lstat();need(stat.S_ISDIR(info.st_mode) and (info.st_dev,info.st_ino)==key,'DIRECTORY_DRIFT')
        need((os.fstat(self.fd).st_dev,os.fstat(self.fd).st_ino)==self.owners[-1][1],'DIRECTORY_DRIFT')
    def read(self,name,size,*,poll=True):
        self.guard(poll=poll);info=os.stat(name,dir_fd=self.fd,follow_symlinks=False)
        need(stat.S_ISREG(info.st_mode) and info.st_size==size<=LEAF,'LEAF_EXTENT')
        identity=_identity(info);fd=os.open(name,os.O_RDONLY|os.O_NOFOLLOW,dir_fd=self.fd);primary=None
        try:
            need(_identity(os.fstat(fd))==identity,'LEAF_DRIFT');data=bytearray()
            while len(data)<size:
                self.guard(poll=poll);block=os.read(fd,size-len(data));need(bool(block),'TRUNCATED_LEAF');data.extend(block)
            need(not os.read(fd,1),'EXTRA_BYTES');self.guard(poll=poll)
            need(_identity(os.fstat(fd))==_identity(os.stat(name,dir_fd=self.fd,follow_symlinks=False))==identity,'LEAF_DRIFT')
            self.seen[name]=(identity,hashlib.sha256(data).digest());return bytes(data)
        except BaseException as error:primary=error;raise
        finally:_close(fd,primary)
    def finish(self):
        # Last caller callback: any mutation it makes is covered by the entire
        # subsequent pass. No consume/cancel callback runs from here through
        # publication. This relies on the supported no-concurrent-writer premise.
        self.guard()
        for name,(identity,digest) in self.seen.items():
            need(_identity(os.stat(name,dir_fd=self.fd,follow_symlinks=False))==identity,'LEAF_DRIFT')
            # Same-size writes can preserve coarse timestamps. One final bounded
            # streamed callback-free pass checks bytes too (artifact<=2GiB,
            # all content reads<=4GiB); cancellation is deferred for this pass.
            need(hashlib.sha256(self.read(name,identity[5],poll=False)).digest()==digest,'LEAF_CONTENT_DRIFT')
        self.scan()
        self.guard(poll=False)

class _Stream:
    def __init__(self,parts):self.parts=iter(parts);self.buffer=b'';self.at=0;self.ended=False
    def read(self,n):
        need(0<=n<=LEAF,'READ_BOUND');out=bytearray()
        while len(out)<n:
            if self.at==len(self.buffer):
                try:self.buffer=next(self.parts);self.at=0
                except StopIteration:raise DecodeError('TRUNCATED_FIELD') from None
            take=min(n-len(out),len(self.buffer)-self.at);out.extend(self.buffer[self.at:self.at+take]);self.at+=take
        return bytes(out)
    def unpack(self,fmt):return struct.unpack(fmt,self.read(struct.calcsize(fmt)))
    def finish(self):
        need(self.at==len(self.buffer),'EXTRA_FIELD_BYTES')
        try:next(self.parts)
        except StopIteration:return
        raise DecodeError('EXTRA_FIELD_BYTES')

def _mutf(raw):
    units=[];at=0
    while at<len(raw):
        a=raw[at];at+=1
        if 0<a<128:n=a
        elif 192<=a<=223:
            need(at<len(raw) and raw[at]&192==128,'MUTF_GRAMMAR');b=raw[at];at+=1;n=((a&31)<<6)|(b&63)
            need(n==0 and a==192 and b==128 or n>=128,'MUTF_CANONICAL')
        elif 224<=a<=239:
            need(at+1<len(raw) and raw[at]&192==128 and raw[at+1]&192==128,'MUTF_GRAMMAR')
            n=((a&15)<<12)|((raw[at]&63)<<6)|(raw[at+1]&63);at+=2;need(n>=2048,'MUTF_CANONICAL')
        else:raise DecodeError('MUTF_GRAMMAR')
        units.append(chr(n));need(len(units)<=8192,'TEXT_BOUND')
    return ''.join(units)

def _utf(stream):
    length=stream.unpack('>H')[0];need(length<=24576,'TEXT_BOUND');raw=stream.read(length)
    return _mutf(raw),struct.pack('>H',length)+raw

def _texts(stream,expected,modes):
    digest=hashlib.sha256();globals=[];ordinal=0
    def occurrence(kind,index,field):
        nonlocal ordinal
        header=stream.read(17);k,i,f,o,p=struct.unpack('>4iB',header)
        need((k,i,f,o)==(kind,index,field,ordinal) and p in (0,1),'TEXT_ORDER')
        ordinal+=1;digest.update(header);text=None
        if p:text,raw=_utf(stream);digest.update(raw)
        if kind==0:need(text==expected[field],'TEXT_CONTRACT')
        else:need(text is not None and len(text)>0,'TEXT_PRESENCE')
        return text
    for field in range(len(expected)):globals.append(occurrence(0,0,field))
    for mode in range(modes):
        for kind in (1,2,3):occurrence(kind,mode,0)
    for mode in range(modes):
        for kind in (4,5):occurrence(kind,mode,0)
    return tuple(globals),digest.digest()

def _finite(raw):return (raw>>52)&2047!=2047
def _float(raw):return struct.unpack('>d',struct.pack('>Q',raw))[0]
def _lanes(raw):
    need(all(_finite(v) for v in raw),'AVAILABLE_NONFINITE')
    need(_float(raw[0])<=_float(raw[1]) and _float(raw[2])<=_float(raw[3]) and _float(raw[4])>=0,'LANE_ORDER')

def _record(role,ordinal,raw,expected,profiles,facts,provenance):
    availability='AVAILABLE';values=()
    if role==0:
        values=struct.unpack('>QB',raw);need(values[1] in (0,1),'BOOLEAN')
        if values[1]:need(_finite(values[0]),'AVAILABLE_NONFINITE')
        else:availability='UNAVAILABLE'
    elif role==1:values=struct.unpack('>B',raw);need(values[0] in (0,1),'BOOLEAN')
    elif role==2:values=struct.unpack('>20Q12i',raw);availability='STRUCTURAL_ONLY'
    elif role==3:
        values=struct.unpack('>ii2Q',raw);need(0<=values[0]<=2 and values[1]>=0,'GEOMETRY_INDEX')
        need(all(_finite(v) for v in values[2:]),'GEOMETRY_NONFINITE')
    elif role==4:
        values=struct.unpack('>8i',raw);r,s,c,eligible,m,fc,rc,tc=values
        need(r==ordinal and s==expected['states'][r] and c==expected['components'][r] and
             0<=eligible<=m<=8192 and fc==20*m and rc>=m and tc==5*m,'PROFILE_SHAPE')
        profiles.append(values)
    elif role==5:
        h=struct.unpack('>6i',raw[:24]);present=raw[24];value=struct.unpack('>Q',raw[32:])[0]
        row,mode,feature,unit,flag,reason=h
        need(0<=row<len(profiles) and 0<=mode<profiles[row][4] and 0<=feature<20 and 0<=unit<=255 and
             flag in (0,1) and 0<=reason<=65535 and present==(flag==0) and raw[25:32]==bytes(7),'FACT_STATUS')
        need(feature==ordinal%20 and sum(p[4] for p in profiles[:row])+mode==ordinal//20,'FACT_ORDER');facts[0]+=1
        if flag==0:need(_finite(value),'AVAILABLE_NONFINITE')
        else:availability='UNAVAILABLE'
        values=(*h,present,value)
    elif role==6:
        row,mode,order,tag=struct.unpack('>4i',raw[:16]);body=raw[16:]
        need(0<=row<len(profiles) and 0<=mode<profiles[row][4] and tag in (0,1,2,3),'PROVENANCE_TAG')
        key=(row,mode);need(order==provenance.get(key,0),'PROVENANCE_ORDER');provenance[key]=order+1
        size=(12,9,58,9)[tag];need(body[size:]==bytes(64-size),'UNION_PADDING')
        if tag in (1,3):need(body[8] in (0,1),'BOOLEAN')
        if tag==2:need(body[4] in (0,1) and body[5] in (0,1),'BOOLEAN')
        if tag==3:
            flat=sum(p[4] for p in profiles[:row])+mode;first=len(expected['global_texts'])+3*expected['role_counts'][2]+2*flat
            need(struct.unpack_from('>2i',body)==(first,first+1),'CORRELATION_TEXT_JOIN')
        values=(row,mode,order,tag,body);availability='STRUCTURAL_ONLY'
    elif role==8:
        head=None;mask=0;all_available=True
        for tap in range(9):
            fields=struct.unpack_from('>iiqii5Q',raw,tap*64);mode,kind,q,t,status=fields[:5]
            need(0<=mode<expected['role_counts'][2] and 0<=kind<=3 and q==ordinal and t==tap and 0<=status<=3,'QUERY_INDEX_STATUS')
            if head is None:head=(mode,kind,q)
            need((mode,kind,q)==head,'QUERY_IDENTITY')
            if status!=2:mask|=1<<tap
            if status==0:_lanes(fields[5:])
            else:all_available=False
        tail=struct.unpack_from('>iiq12Q4i',raw,576)
        need(tail[:3]==head and all(0<=v<=3 for v in tail[-4:-2]) and tail[-2]==mask and 0<=tail[-1]<=3,'QUERY_STATUS_MASK')
        if tail[-1]==0:_lanes(tail[5:10]);_lanes(tail[10:15])
        if tail[-1]!=0 or not all_available:availability='UNAVAILABLE'
        values=tail
    elif role==14:
        values=struct.unpack('>3Qi',raw);reason=values[3];need(0<=reason<=3,'TABLE_REASON')
        if reason==0:need(all(_finite(v) and _float(v)>=0 for v in values[:3]),'TABLE_AVAILABLE_RANGE')
        else:availability=('NOT_ADMITTED','SOURCE_UNKNOWN','REFUSED')[reason-1]
    elif role==15:
        values=struct.unpack('>8Q4B',raw);need(all(v<=3 for v in values[8:]),'VISUAL_STATUS')
        for i,status in enumerate(values[8:]):
            if status==0:need(all(_finite(v) for v in values[i*2:i*2+2]),'AVAILABLE_NONFINITE')
        if any(values[8:]):availability='PARTIALLY_UNAVAILABLE'
    else:raise DecodeError('ROLE_NOT_TABLE_ONLY')
    return Record(role,ordinal,raw,values,availability)

def _graph(raw,row,state,components):
    stream=_Stream((raw,));r,s=stream.unpack('>ii');need((r,s)==(row,state),'GRAPH_ORDER')
    values=stream.unpack('>12Q');flags=stream.unpack('>B')[0];need(flags<=3,'GRAPH_FLAGS')
    ownership,_=_utf(stream);missing=stream.unpack('>B')[0];need(missing in (0,1),'BOOLEAN')
    branch,_=_utf(stream);status,_=_utf(stream);certainty=stream.unpack('>Q')[0];modes=stream.unpack('>i')[0]
    need(0<=modes<=8192 and 32*modes+4<=len(raw),'GRAPH_MODE_BOUND')
    angular=tuple(stream.unpack('>4Q') for _ in range(modes));count=stream.unpack('>i')[0]
    need(count==components,'GRAPH_COMPONENTS');responsibilities=tuple(stream.unpack('>Q')[0] for _ in range(count))
    stream.finish();return (r,s,*values,flags,ownership,missing,branch,status,certainty,angular,responsibilities)

def _metadata(stream,expected,sizes,text_digest,profiles):
    fields={};version=SCHEMAS[expected['schema']][0]
    for name,width,count in META_FIXED:
        if version==4 and name=='gainEtaAndOriginalParameters':
            fields['parameterEncoding']=stream.unpack('>i')
            fields['gain']=stream.unpack('>Q')
            fields['etaSelection']=stream.unpack('>BBi')
            fields['supportedEta']=stream.unpack('>4Q')
            fields['nativeParameters']=stream.unpack('>8Q')
            fields['localizationInts']=stream.unpack('>2i')
            fields['localizationParameters']=stream.unpack('>11Q')
            need(fields['parameterEncoding']==(1,),'PARAMETER_ENCODING')
            need(fields['etaSelection']==(0,4,-1) and fields['supportedEta']==
                 (0,0x3fd0000000000000,0x3ff0000000000000,0x4010000000000000),'PARAMETER_ETA_POLICY')
            need(fields['gain'][0] in (0x3ff0000000000000,0x4010000000000000,
                                      0x4030000000000000,0x4050000000000000),'PARAMETER_GAIN')
            need(all(_finite(v) for key in ('nativeParameters','localizationParameters')
                     for v in fields[key]),'PARAMETER_NONFINITE')
        else:fields[name]=stream.unpack('>'+str(count)+{1:'B',4:'i',8:'Q'}[width])
    need(fields['schemaMagic']==(0x504d5331,) and fields['schemaVersionCode']==(version,),'METADATA_VERSION')
    flags=fields['availabilityAndDispositionFlags'];need(all(v in (0,1) for v in flags),'BOOLEAN')
    need(flags[3]==flags[6]==flags[7]==flags[8]==flags[9]==flags[10]==flags[11]==0,'NOT_RUN_POLICY')
    need(all(flags[i]==1 for i in (1,2,4,5)),'METADATA_AVAILABILITY')
    if version==4:need(flags[0]==1,'METADATA_AVAILABILITY')
    need(fields['scopePhaseCauseStatus']==(1,1,0,0),'METADATA_SCOPE')
    need(fields['completeRoleCount']==(24,),'ROLE_COUNT')
    for role,n in enumerate(expected['role_counts']):
        need(stream.unpack('>iiqq')==(role,sizes[role][0],n,sizes[role][1]),'ROLE_RANGE')
    points,rings,vertices=fields['originalPointRingVertexCounts'];integer(rings,expected['role_counts'][3]//3)
    lengths=tuple(stream.unpack('>i')[0] for _ in range(rings))
    need(all(n>=3 for n in lengths) and sum(lengths)==vertices and points+vertices==expected['role_counts'][3] and
         (version!=2 or points==expected['role_counts'][15]),'GEOMETRY_SHAPE')
    need(fields['credibleRowCount']==(0,) and fields['credibleRowsAvailable']==(0,) and fields['marginalRowCounts']==(0,0,0,0) and
         fields['rawDistinctPathCounts']==(0,0) and fields['mapPathRawIndex']==(-1,) and fields['selectedAttributionCountAndLayout']==(0,1) and
         fields['completionInts']==(0,0,0) and fields['completionFlags']==(0,0,0),'NOT_RUN_POLICY')
    need(fields['actualOriginalTableGeometryUnitsAvailable'][0] in (0,1),'BOOLEAN')
    prefix=stream.unpack('>237Q');need(stream.unpack('>2i')==(18,19),'PREFIX_TEXT_ORDINAL')
    texts,digest=_texts(stream,expected['global_texts'],expected['role_counts'][2]);need(digest==text_digest,'TEXT_STREAM_JOIN');stream.finish()
    w,h=fields['sourceWidthHeight'];aw,ah=fields['analysisWidthHeight']
    need(w>=2 and h>=2 and aw>=2 and ah>=2 and aw*ah<=191401 and w*h==expected['role_counts'][0] and (w-1)*(h-1)==expected['role_counts'][1],'GRID_SHAPE')
    if version!=2:need(expected['role_counts'][15]==aw*ah,'ANALYSIS_VISUAL_SHAPE')
    n,s,d,m,c,p,n0,v,cells,k=fields['attemptCountsAndCardinalities']
    need((n,s,d,c,p,n0,v,cells)==(len(expected['states']),sum(expected['states']),expected['role_counts'][2],sum(expected['components']),
         expected['role_counts'][14],points,w*h,(w-1)*(h-1)) and m==sum(p[3] for p in profiles) and 0<k<=LONG,'CARDINALITY_JOIN')
    need(fields['compactAndOwnQueryCounts']==(expected['role_counts'][8],50*d*(n0 if version==2 else aw*ah)),'QUERY_COUNT_JOIN')
    need(fields['originalCaps']==(96,8000000,128000000,64<<20,300000000000,360000000000,1,1,8000000),'CAPS')
    return fields,prefix,texts

def decode_directory(path, expected, consume=None, cancel=lambda:None):
    """Stream a complete bundle; compare an independently supplied DATA contract.

    ``expected`` has exactly schema/global_texts/role_counts/role_availability/
    states/components/cell_lengths. It must come from the caller independently
    of payload parsing. Its bounded immutable members are privately pinned
    before callbacks. No provenance of that caller is established here.
    ``consume`` receives bounded Records; a refused decode may have delivered a
    prefix. The final result always refuses factual/runtime qualification.
    The final validation/publication barrier polls cancellation once on entry,
    then invokes no caller callbacks. Cancellation is deferred for its bounded
    <=2GiB content pass plus per-leaf identity/name checks and descriptor cleanup.
    Concurrent writers are unsupported; the barrier covers synchronous callbacks.
    """
    need(type(expected) is dict,'CONTRACT_REQUIRED');expected=dict(expected)
    schedule,sizes,inv_count,total=_plan(expected)
    need(hasattr(os,'O_NOFOLLOW') and hasattr(os,'O_DIRECTORY') and os.open in os.supports_dir_fd and
         os.stat in os.supports_dir_fd and os.scandir in os.supports_fd,'UNSUPPORTED_NOFOLLOW_IO')
    names=['r%02d-c%08d.bin'%(r,c) for r,c,_,_,_ in schedule]
    names+=['inventory-%08d.bin'%i for i in range(inv_count)]+['receipt.bin']
    directory=None;primary=None
    try:
        directory=_Directory(path,names,cancel);digests=[];graph_hash=hashlib.sha256();profiles=[];facts=[0];provenance={};correlations=set();last_receipt=(-1,-1)
        by_role=[[] for _ in range(24)]
        for item in schedule:by_role[item[0]].append(item)
        def parts(role):
            for r,c,start,count,size in by_role[role]:
                data=directory.read('r%02d-c%08d.bin'%(r,c),size);magic,n=struct.unpack_from('>II',data)
                need(magic==(0x43534731 if role==23 else 0x44415400+role) and n==count,'LEAF_HEADER')
                digests.append((r,c,start,count,size,hashlib.sha256(data).digest()))
                if role==23:graph_hash.update(data)
                yield data[8:]
        def deliver(record):
            if consume is not None:consume(record)
            checkpoint(cancel)
        for role in range(23):
            if role==7:
                stream=_Stream(parts(role));globals,text_digest=_texts(stream,expected['global_texts'],expected['role_counts'][2]);stream.finish()
            elif role==22:
                fields,prefix,globals=_metadata(_Stream(parts(role)),expected,sizes,text_digest,profiles)
            else:
                ordinal=0;stride=ROLES[role][1]
                for body in parts(role):
                    for at in range(0,len(body),stride):
                        record=_record(role,ordinal,body[at:at+stride],expected,profiles,facts,provenance)
                        if role==6:
                            row,mode,_,tag=record.values[:4];key=(row,mode);need(key>=last_receipt,'PROVENANCE_ORDER');last_receipt=key
                            if tag==3:need(key not in correlations,'CORRELATION_JOIN');correlations.add(key)
                        deliver(record);ordinal+=1
        need(sum(row[4] for row in profiles)==expected['role_counts'][2] and facts[0]==expected['role_counts'][5],'MODE_FACT_JOIN')
        need(sum(row[5] for row in profiles)==facts[0] and sum(row[6] for row in profiles)==expected['role_counts'][6],'PROFILE_RECORD_JOIN')
        need(len(correlations)==expected['role_counts'][2],'CORRELATION_JOIN')
        coordinates=iter((r,s) for r,n in enumerate(expected['states']) for s in range(n));ordinal=0
        for body in parts(23):
            stream=_Stream((body,))
            while ordinal<sum(expected['states']):
                # Chunk boundaries are independently preplanned from cell sizes.
                if stream.at==len(stream.buffer) and stream.buffer:break
                length=stream.unpack('>i')[0];need(length==expected['cell_lengths'][ordinal],'GRAPH_CELL_EXTENT')
                raw=stream.read(length);row,state=next(coordinates)
                deliver(Record(23,ordinal,raw,_graph(raw,row,state,expected['components'][row])));ordinal+=1
            stream.finish()
        need(ordinal==expected['role_counts'][23] and globals[21]==graph_hash.hexdigest(),'GRAPH_DIGEST_JOIN')
        inv_hash=hashlib.sha256();index=0
        for chunk in range(inv_count):
            count=min(1023,len(digests)-index);data=directory.read('inventory-%08d.bin'%chunk,8+64*count)
            need(struct.unpack_from('>II',data)==(0x494e5631,count),'INVENTORY_HEADER');inv_hash.update(data)
            for at in range(8,len(data),64):
                entry=struct.unpack_from('>iiqqq',data,at);digest=data[at+32:at+64]
                need((*entry,digest)==digests[index],'INVENTORY_JOIN');index+=1
        receipt=directory.read('receipt.bin',88)
        need(struct.unpack('>4sIIIQ32s32s',receipt)==(b'BTR1',1,len(digests),inv_count,total,inv_hash.digest(),graph_hash.digest()),'RECEIPT_JOIN')
        directory.finish()
        return {'scope':'STRUCTURAL_DATA','qualified':False,'factual_authority':'NOT_ESTABLISHED','runtime':'UNAVAILABLE',
                'physical_status_semantics':'REQUIRES_SOURCE_PRODUCER_JOIN','role_counts':expected['role_counts'],
                'global_texts':globals,'prefix_longs':prefix,'prefix_declared_available':bool(fields['availabilityAndDispositionFlags'][0]),
                'solver_results':'NOT_RUN','metadata_raw':fields,'data_leaves':len(digests),'bytes':total,
                'receipt_sha256':hashlib.sha256(receipt).hexdigest(),'graph_sha256':graph_hash.hexdigest()}
    except OSError:primary=True;raise DecodeError('DATA_IO') from None
    except BaseException as error:primary=error;raise
    finally:
        if directory is not None:_close(directory.fd,primary)
