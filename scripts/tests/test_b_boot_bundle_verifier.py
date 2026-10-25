"""Portable PUBLIC DATA parser regressions with independently encoded bytes.

No external fixtures, host discovery, tool/native/VM execution or network.
Broader W verifier tests remain separate evidence; these are parser grammar
checks only and issue no source/runtime authority.
"""
from pathlib import Path
import importlib.util
import struct
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('boot_data',ROOT/'scripts/b-boot-bundle-verifier.py')
B=importlib.util.module_from_spec(spec);spec.loader.exec_module(B)

def class_bytes(code=b'\x1a\x04\x60\xac', descriptor=b'(I)I', superclass=b'java/lang/Object', handlers=(), binary=b'fixture'):
    """Independent tiny real class file; never loaded/executed by any VM."""
    def u2(n): return struct.pack('>H', n)
    def u4(n): return struct.pack('>I', n)
    pool = [b'\x01'+u2(len(binary))+binary, b'\x07'+u2(1),
            b'\x01'+u2(len(superclass))+superclass, b'\x07'+u2(3),
            b'\x01'+u2(1)+b'm', b'\x01'+u2(len(descriptor))+descriptor,
            b'\x01'+u2(4)+b'Code']
    body = u2(2)+u2(1)+u4(len(code))+code+u2(len(handlers))
    body += b''.join(struct.pack('>HHHH', *h) for h in handlers)+u2(0)
    return (b'\xca\xfe\xba\xbe'+u2(0)+u2(61)+u2(8)+b''.join(pool)+
            u2(0x21)+u2(2)+u2(4)+u2(0)+u2(0)+u2(1)+
            u2(9)+u2(5)+u2(6)+u2(1)+u2(7)+u4(len(body))+body+u2(0))


def attribute_class(class_attrs=(), field_attrs=(), method_attrs=(), shifted=False,
                    mark=b'Ldemo/Mark;', integer=7, method_descriptor=b'()V',
                    class_descriptor=b'[Ljava/lang/String;'):
    """Independent declaration-attribute bytes; no VM loading or target tools."""
    u2=lambda n:struct.pack('>H',n)
    definitions=[('name',1,b'fixture'),('this',7,'name'),('object',1,b'java/lang/Object'),
        ('super',7,'object'),('field',1,b'f'),('int_desc',1,b'I'),('method',1,b'm'),
        ('method_desc',1,method_descriptor),('outer_name',1,b'demo/Outer'),('outer',7,'outer_name'),
        ('outer_method',12,('method','method_desc')),('mark',1,mark),('nested',1,b'Ldemo/Nested;'),
        ('v',1,b'v'),('text',1,b'hello'),('string',8,'text'),('enum',1,b'Ldemo/Mode;'),
        ('constant',1,b'FAST'),('class_desc',1,class_descriptor),('void_desc',1,b'V'),
        ('integer',3,struct.pack('>i',integer)),('float',4,bytes.fromhex('3fc00000')),
        ('long',5,bytes.fromhex('0000000000000017')),('double',6,bytes.fromhex('4004000000000000')),
        ('true',3,bytes.fromhex('00000001'))]
    for name in ('EnclosingMethod','RuntimeVisibleAnnotations','RuntimeInvisibleAnnotations',
                 'RuntimeVisibleTypeAnnotations','UnknownAttribute'):
        definitions.append((name,1,name.encode('ascii')))
    if shifted:definitions.insert(0,('unused',1,b'padding'))
    indexes={};slot=1
    for name,tag,value in definitions:indexes[name]=slot;slot+=2 if tag in (5,6) else 1
    pool=[]
    for name,tag,value in definitions:
        if tag==1:body=u2(len(value))+value
        elif tag in (7,8):body=u2(indexes[value])
        elif tag==12:body=b''.join(u2(indexes[v]) for v in value)
        else:body=value
        pool.append(bytes([tag])+body)
    def attrs(values):
        out=u2(len(values))
        for name,body in values:
            if callable(body):body=body(indexes)
            out+=u2(indexes[name])+struct.pack('>I',len(body))+body
        return out
    return (bytes.fromhex('cafebabe0000003d')+u2(slot)+b''.join(pool)+
        u2(0x21)+u2(indexes['this'])+u2(indexes['super'])+u2(0)+u2(1)+
        u2(1)+u2(indexes['field'])+u2(indexes['int_desc'])+attrs(field_attrs)+u2(1)+
        u2(0x109)+u2(indexes['method'])+u2(indexes['method_desc'])+attrs(method_attrs)+attrs(class_attrs))


def annotation_body(value,mark='mark'):
    """One independently encoded annotation with one element named v."""
    return lambda i:struct.pack('>HHHH',1,i[mark],1,i['v'])+value(i)


class PortableParserTests(unittest.TestCase):
    def test_new_attributes_enclosing_method_preserves_symbols_and_nullable_method(self):
        enclosing=lambda i:struct.pack('>HH',i['outer'],i['outer_method'])
        attrs=(('EnclosingMethod',enclosing),)
        try:actual=B.normalize_class(attribute_class(class_attrs=attrs))
        except B.Pending as failure:self.fail('Required enclosing attribute refused: '+str(failure))
        utf=lambda v:('utf',v.encode('ascii').hex())
        self.assertEqual((('EnclosingMethod',((7,utf('demo/Outer')),
                         (12,utf('m'),utf('()V')))),),actual['attributes'])
        self.assertEqual(actual,B.normalize_class(attribute_class(class_attrs=attrs,shifted=True)))
        zero=(('EnclosingMethod',lambda i:struct.pack('>HH',i['outer'],0)),)
        self.assertEqual((('EnclosingMethod',((7,utf('demo/Outer')),None)),),
                         B.normalize_class(attribute_class(class_attrs=zero))['attributes'])
        self.assertNotEqual(actual,B.normalize_class(attribute_class(class_attrs=attrs,method_descriptor=b'(I)V')))


    def test_new_attributes_annotations_keep_all_typed_values_and_declarations(self):
        def values(i):
            out=b'['+struct.pack('>H',14)
            for tag,key in [('B','integer'),('C','integer'),('D','double'),('F','float'),
                            ('I','integer'),('J','long'),('S','integer'),('Z','true'),('s','text')]:
                out+=tag.encode()+struct.pack('>H',i[key])
            out+=b'e'+struct.pack('>HH',i['enum'],i['constant'])
            out+=b'c'+struct.pack('>H',i['class_desc'])+b'c'+struct.pack('>H',i['void_desc'])
            out+=b'@'+struct.pack('>HH',i['nested'],0)+b'['+struct.pack('>H',0)
            return out
        attrs=(('RuntimeVisibleAnnotations',annotation_body(values)),)
        try:actual=B.normalize_class(attribute_class(class_attrs=attrs,field_attrs=attrs,method_attrs=attrs))
        except B.Pending as failure:self.fail('Required annotation attribute refused: '+str(failure))
        utf=lambda v:('utf',v.encode('ascii').hex())
        typed=[(t,(3,'00000007')) for t in ('B','C')]+[('D',(6,'4004000000000000')),
            ('F',(4,'3fc00000')),('I',(3,'00000007')),('J',(5,'0000000000000017')),
            ('S',(3,'00000007')),('Z',(3,'00000001')),('s',utf('hello')),
            ('e',utf('Ldemo/Mode;'),utf('FAST')),('c',utf('[Ljava/lang/String;')),
            ('c',utf('V')),('@',(utf('Ldemo/Nested;'),())),('[',())]
        expected=('RuntimeVisibleAnnotations',((utf('Ldemo/Mark;'),((utf('v'),('[',tuple(typed))),)),))
        self.assertEqual((expected,),actual['fields'][0][3])
        self.assertEqual((expected,),actual['methods'][0][5])
        self.assertEqual((expected,),actual['attributes'])
        self.assertEqual(actual,B.normalize_class(attribute_class(class_attrs=attrs,field_attrs=attrs,
                                                                 method_attrs=attrs,shifted=True)))


    def test_annotation_value_and_type_changes_cannot_disappear(self):
        body=annotation_body(lambda i:b'I'+struct.pack('>H',i['integer']))
        attrs=(('RuntimeVisibleAnnotations',body),)
        before=B.normalize_class(attribute_class(method_attrs=attrs))
        self.assertNotEqual(before,B.normalize_class(attribute_class(method_attrs=attrs,integer=8)))
        self.assertNotEqual(before,B.normalize_class(attribute_class(method_attrs=attrs,mark=b'Lother/Mark;')))
        # Ordered pairs must not be sorted/collapsed, including repeated names.
        pair=lambda i:struct.pack('>HHHH',1,i['mark'],2,i['v'])+b'I'+struct.pack('>H',i['integer'])+\
            struct.pack('>H',i['text'])+b'Z'+struct.pack('>H',i['true'])
        actual=B.normalize_class(attribute_class(field_attrs=(('RuntimeVisibleAnnotations',pair),)))
        self.assertEqual(['v','hello'],[bytes.fromhex(p[0][1]).decode() for p in actual['fields'][0][3][0][1][0][1]])


    def test_enclosing_malformed_references_lengths_and_duplicate_refuse(self):
        invalid=[lambda i:struct.pack('>HH',i['text'],0),
                 lambda i:struct.pack('>HH',i['outer'],i['outer']),
                 lambda i:struct.pack('>HH',0,0),lambda i:struct.pack('>HH',65535,0)]
        for body in invalid:
            with self.subTest(body=body),self.assertRaisesRegex(B.Pending,'CLASS_(ENCLOSING|CP_INDEX)'):
                B.normalize_class(attribute_class(class_attrs=(('EnclosingMethod',body),)))
        for body in (b'\0\0\0',b'\0\0\0\0\0'):
            with self.assertRaisesRegex(B.Pending,'CLASS_ENCLOSING_LENGTH'):
                B.normalize_class(attribute_class(class_attrs=(('EnclosingMethod',body),)))
        valid=('EnclosingMethod',lambda i:struct.pack('>HH',i['outer'],0))
        with self.assertRaisesRegex(B.Pending,'CLASS_DUPLICATE_ATTRIBUTE'):
            B.normalize_class(attribute_class(class_attrs=(valid,valid)))


    def test_annotation_wrong_cp_kind_truncation_tag_and_duplicate_refuse(self):
        # Every constant tag must use its prescribed CP kind, not any symbolic ref.
        invalid=[annotation_body(lambda i,t=t:t.encode()+struct.pack('>H',i['outer'])) for t in 'BCDFIJSZs']
        invalid += [annotation_body(lambda i:b'e'+struct.pack('>HH',i['outer'],i['constant'])),
                    annotation_body(lambda i:b'c'+struct.pack('>H',i['outer'])),
                    annotation_body(lambda i:b'?'),annotation_body(lambda i:b'I\0'),
                    lambda i:struct.pack('>HHH',1,i['outer'],0),
                    lambda i:struct.pack('>HHHH',1,i['mark'],1,i['outer'])+b'I'+struct.pack('>H',i['integer']),
                    lambda i:struct.pack('>H',0)+b'x']
        for body in invalid:
            with self.subTest(body=body),self.assertRaises(B.Pending):
                B.normalize_class(attribute_class(method_attrs=(('RuntimeVisibleAnnotations',body),)))
        valid=('RuntimeVisibleAnnotations',struct.pack('>H',0))
        for place in ('class_attrs','field_attrs','method_attrs'):
            with self.subTest(place=place),self.assertRaisesRegex(B.Pending,'CLASS_DUPLICATE_ATTRIBUTE'):
                B.normalize_class(attribute_class(**{place:(valid,valid)}))


    def test_annotation_depth_is_bounded_and_unknown_attribute_stays_refused(self):
        def nested(depth):
            return annotation_body(lambda i:(b'[\0\1'*depth)+b'I'+struct.pack('>H',i['integer']))
        B.normalize_class(attribute_class(field_attrs=(('RuntimeVisibleAnnotations',nested(4)),)))
        with self.assertRaisesRegex(B.Pending,'CLASS_ANNOTATION_DEPTH'):
            B.normalize_class(attribute_class(field_attrs=(('RuntimeVisibleAnnotations',nested(100)),)))
        for name in ('RuntimeInvisibleAnnotations','RuntimeVisibleTypeAnnotations','UnknownAttribute'):
            with self.subTest(name=name),self.assertRaisesRegex(B.Pending,'CLASS_FIELD_ATTRIBUTE_UNSUPPORTED'):
                B.normalize_class(attribute_class(field_attrs=((name,b'\0\0'),)))


    def test_declaration_descriptor_constraints_preserve_valid_arrays_and_void(self):
        marker=(('RuntimeVisibleAnnotations',annotation_body(lambda i:b'I'+struct.pack('>H',i['integer']))),)
        for bad in (b'I',b'L;',b'Lbad.name;',b'[Ljava/lang/String;',b'Lbad//name;',b'Ldemo/Mark;x'):
            with self.subTest(mark=bad),self.assertRaisesRegex(B.Pending,'CLASS_(ANNOTATION_TYPE|DECLARATION)_DESCRIPTOR'):
                B.normalize_class(attribute_class(field_attrs=marker,mark=bad))
        enclosing=(('EnclosingMethod',lambda i:struct.pack('>HH',i['outer'],i['outer_method'])),)
        for bad in (b'I',b'(V)V',b'()[V',b'()Vx',b'('):
            with self.subTest(method=bad),self.assertRaises(B.Pending):
                B.normalize_class(attribute_class(class_attrs=enclosing,method_descriptor=bad))
        B.normalize_class(attribute_class(class_attrs=enclosing,method_descriptor=b'([I)Ljava/lang/Object;'))
        literal=(('RuntimeVisibleAnnotations',annotation_body(lambda i:b'c'+struct.pack('>H',i['class_desc']))),)
        B.normalize_class(attribute_class(field_attrs=literal,class_descriptor=b'['*255+b'I'))
        with self.assertRaisesRegex(B.Pending,'CLASS_DECLARATION_DESCRIPTOR'):
            B.normalize_class(attribute_class(field_attrs=literal,class_descriptor=b'['*256+b'I'))


    def test_method_normalizer_independent_arithmetic_and_semantic_mutations(self):
        actual = B.normalize_class(class_bytes())
        self.assertEqual((9, 'm', '(I)I', (2, 1, ((26, ()), (4, ()), (96, ()), (172, ())), ()), (), ()),
                         actual['methods'][0])
        for data in (class_bytes(code=b'\x1a\x05\x60\xac'), class_bytes(descriptor=b'(J)I'),
                     class_bytes(superclass=b'java/lang/Number'),
                     class_bytes(handlers=((0, 3, 3, 0),))):
            self.assertNotEqual(actual, B.normalize_class(data))
        with self.assertRaisesRegex(B.Pending, 'CLASS_BRANCH_TARGET'):
            B.normalize_class(class_bytes(code=b'\xa7\x00\x01\xac'))
        with self.assertRaisesRegex(B.Pending, 'CLASS_HANDLER_TARGET'):
            B.normalize_class(class_bytes(handlers=((0, 3, 4, 0),)))
        with self.assertRaisesRegex(B.Pending, 'CLASS_VERSION'):
            B.normalize_class(class_bytes().replace(b'\x00=', b'\x00>', 1))
        malformed = bytearray(class_bytes()); malformed[8:10] = b'\xff\xff'
        with self.assertRaisesRegex(B.Pending, 'CLASS_CP_BOUND'):
            B.normalize_class(bytes(malformed))


    def test_symbolic_ldc_normalizes_cp_positions_but_not_literal(self):
        symbols = {1: ('String', 'same'), 257: ('String', 'same')}
        a, _ = B._instructions(b'\x12\x01\xb0', symbols.__getitem__)
        b, _ = B._instructions(b'\x13\x01\x01\xb0', symbols.__getitem__)
        self.assertEqual(a, b)
        symbols[257] = ('String', 'different')
        self.assertNotEqual(a, B._instructions(b'\x13\x01\x01\xb0', symbols.__getitem__)[0])


    def test_truncation_trailing_cp_counts_and_reference_kinds_refuse(self):
        data=class_bytes()
        for end in (0,1,3,4,7,9,10,len(data)-1):
            with self.subTest(end=end),self.assertRaises(B.Pending):B.normalize_class(data[:end])
        with self.assertRaisesRegex(B.Pending,'CLASS_TRAILING'):B.normalize_class(data+b'x')
        bad=bytearray(data);bad[8:10]=b'\x00\x01'
        with self.assertRaisesRegex(B.Pending,'CLASS_CP_COUNT'):B.normalize_class(bytes(bad))
        bad=bytearray(data);bad[10]=255
        with self.assertRaisesRegex(B.Pending,'CLASS_CP_UNSUPPORTED'):B.normalize_class(bytes(bad))
        # Literal fixture UTF8 entry, followed by its Class reference. A Class
        # reference to itself is invalid; expected offsets are independently encoded.
        cp2=10+1+2+len(b'fixture');self.assertEqual(b'\x07\x00\x01',data[cp2:cp2+3])
        bad=bytearray(data);bad[cp2+1:cp2+3]=b'\x00\x02'
        with self.assertRaisesRegex(B.Pending,'CLASS_CP_REFERENCE_KIND'):B.normalize_class(bytes(bad))

    def test_unsupported_attributes_stay_refused_at_each_declaration(self):
        for place,code in (('class_attrs','CLASS_ATTRIBUTE_UNSUPPORTED'),
                           ('field_attrs','CLASS_FIELD_ATTRIBUTE_UNSUPPORTED'),
                           ('method_attrs','CLASS_METHOD_ATTRIBUTE_UNSUPPORTED')):
            with self.subTest(place=place),self.assertRaisesRegex(B.Pending,code):
                B.normalize_class(attribute_class(**{place:(('UnknownAttribute',b''),)}))
        enclosing=('EnclosingMethod',lambda i:struct.pack('>HH',i['outer'],0))
        with self.assertRaisesRegex(B.Pending,'CLASS_FIELD_ATTRIBUTE_UNSUPPORTED'):
            B.normalize_class(attribute_class(field_attrs=(enclosing,)))
        with self.assertRaisesRegex(B.Pending,'CLASS_METHOD_ATTRIBUTE_UNSUPPORTED'):
            B.normalize_class(attribute_class(method_attrs=(enclosing,)))

    def test_instruction_wide_reserved_branch_and_opcode_grammar(self):
        symbol=lambda index:('UNIT_CP',index)
        rows,labels=B._instructions(b'\xc4\x84\x01\x2c\xff\xfe\xb1',symbol)
        self.assertEqual(((196,(132,300,-2)),(177,())),rows)
        self.assertEqual({0:0,6:1,7:2},labels)
        rows,labels=B._instructions(b'\xa7\x00\x03\xb1',symbol)
        self.assertEqual(((167,('branch',1)),(177,())),rows)
        for code,cause in ((b'\xca','CLASS_OPCODE_UNSUPPORTED'),
                           (b'\xc4\x00','CLASS_WIDE_OPCODE'),
                           (b'\xb9\x00\x01\x01\x01','CLASS_INVOKE_RESERVED'),
                           (b'\xba\x00\x01\x01\x00','CLASS_INVOKE_RESERVED'),
                           (b'\xa7\x00\x01\xb1','CLASS_BRANCH_TARGET')):
            with self.subTest(code=code),self.assertRaisesRegex(B.Pending,cause):B._instructions(code,symbol)

if __name__=='__main__':unittest.main()
