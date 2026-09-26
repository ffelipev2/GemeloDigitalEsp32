"""Convert nuevo_modelo's OBJ and color atlas into the viewer's self-contained GLB.

The supplied OBJ references an absent MTL. Its two material groups are reconstructed
as the color atlas (Material) and the black inverted hull (Outline). The ground AO
image has no corresponding geometry/material in the OBJ and is not a model texture.
Geometry, face winding and UV seams are preserved. The source's initial facing
direction is aligned with the viewer's +Z front by one fixed rotation of positions
and normals. OBJ V is flipped for glTF's top-left texture origin. Sensor and scene
coordinate conversion are unchanged.
Run from any directory with Python 3; no third-party packages are required.
"""
import array
import json
import math
import pathlib
import struct
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'nuevo_modelo/source/BLENDER_Cubone.obj'
TEXTURE = ROOT / 'nuevo_modelo/textures/Material_Base_Color.png'
OUTPUT = ROOT / 'android/app/src/main/assets/cubone.glb'
FRONT_YAW_DEGREES = 40.0


def convert():
    positions, normals, uvs = [], [], []
    groups = {}
    current = None
    yaw = math.radians(FRONT_YAW_DEGREES)
    cosine, sine = math.cos(yaw), math.sin(yaw)

    def face_front(values):
        x, y, z = values
        return (cosine * x + sine * z, y, -sine * x + cosine * z)

    for line in SOURCE.read_text(encoding='utf-8').splitlines():
        fields = line.split()
        if not fields:
            continue
        kind = fields[0]
        if kind == 'v':
            positions.append(face_front(tuple(map(float, fields[1:4]))))
        elif kind == 'vn':
            normal = face_front(tuple(map(float, fields[1:4])))
            length = math.sqrt(sum(v * v for v in normal))
            if not length:
                raise ValueError('Zero-length OBJ normal')
            normals.append(tuple(v / length for v in normal))
        elif kind == 'vt':
            u, v = map(float, fields[1:3])
            uvs.append((u, 1 - v))
        elif kind == 'usemtl':
            current = fields[1]
            if current not in ('Material', 'Outline'):
                raise ValueError(f'Unsupported source material: {current}')
            groups.setdefault(current, [])
        elif kind == 'f':
            if current is None:
                raise ValueError('Face without a material')
            face = []
            for corner in fields[1:]:
                indices = corner.split('/')
                if len(indices) != 3 or not all(indices):
                    raise ValueError('Expected position/UV/normal on every OBJ corner')
                counts = (len(positions), len(uvs), len(normals))
                indices = tuple(int(i) - 1 if int(i) > 0 else count + int(i)
                                for i, count in zip(indices, counts))
                if any(i < 0 or i >= count for i, count in zip(indices, counts)):
                    raise ValueError('OBJ index out of range')
                face.append(indices)
            if len(face) not in (3, 4):
                raise ValueError('Expected triangles or quads in this model')
            for i in range(1, len(face) - 1):
                groups[current].append((face[0], face[i], face[i + 1]))

    binary, views, accessors = bytearray(), [], []

    def buffer_view(data, target=None):
        binary.extend(b'\0' * (-len(binary) % 4))
        view = {'buffer': 0, 'byteOffset': len(binary), 'byteLength': len(data)}
        if target is not None:
            view['target'] = target
        views.append(view)
        binary.extend(data)
        return len(views) - 1

    def accessor(values, components, kind, index=False, bounds=False):
        data = array.array('H' if index else 'f', values)
        if data.itemsize != (2 if index else 4):
            raise ValueError('Unexpected platform array size')
        if sys.byteorder != 'little':
            data.byteswap()
        item = {'bufferView': buffer_view(data.tobytes(), 34963 if index else 34962),
                'componentType': 5123 if index else 5126,
                'count': len(values) // components, 'type': kind}
        if bounds:
            item['min'] = [min(values[i::components]) for i in range(components)]
            item['max'] = [max(values[i::components]) for i in range(components)]
        accessors.append(item)
        return len(accessors) - 1

    primitives = []
    for material_index, name in enumerate(('Material', 'Outline')):
        vertices, indices, unique = [], [], {}
        for triangle in groups[name]:
            for corner in triangle:
                if corner not in unique:
                    unique[corner] = len(vertices)
                    vertices.append(corner)
                indices.append(unique[corner])
        if len(vertices) >= 65535:
            raise ValueError('Source exceeds the 16-bit index limit')
        attributes = {
            'POSITION': accessor([v for p, _, _ in vertices for v in positions[p]], 3, 'VEC3', bounds=True),
            'NORMAL': accessor([v for _, _, n in vertices for v in normals[n]], 3, 'VEC3'),
            'TEXCOORD_0': accessor([v for _, t, _ in vertices for v in uvs[t]], 2, 'VEC2'),
        }
        primitives.append({'attributes': attributes, 'indices': accessor(indices, 1, 'SCALAR', index=True),
                           'material': material_index, 'mode': 4})
        print(f'{name}: {len(vertices)} vertices, {len(indices) // 3} triangles')

    texture_view = buffer_view(TEXTURE.read_bytes())
    gltf = {
        'asset': {'version': '2.0', 'generator': 'GemeloDigitalEsp32 OBJ importer'},
        'extensionsUsed': ['KHR_materials_unlit'],
        'buffers': [{'byteLength': len(binary)}], 'bufferViews': views, 'accessors': accessors,
        'images': [{'name': 'Material_Base_Color', 'bufferView': texture_view, 'mimeType': 'image/png'}],
        'samplers': [{'magFilter': 9729, 'minFilter': 9987, 'wrapS': 10497, 'wrapT': 10497}],
        'textures': [{'source': 0, 'sampler': 0}],
        'materials': [
            {'name': 'Material', 'pbrMetallicRoughness': {
                'baseColorTexture': {'index': 0}, 'metallicFactor': 0, 'roughnessFactor': 1}},
            # Keep back-face culling: making the inverted hull double-sided hides the color mesh.
            {'name': 'Outline', 'pbrMetallicRoughness': {
                'baseColorFactor': [0, 0, 0, 1], 'metallicFactor': 0, 'roughnessFactor': 1},
             'extensions': {'KHR_materials_unlit': {}}},
        ],
        'meshes': [{'name': 'Cubone', 'primitives': primitives}], 'nodes': [{'name': 'Cubone', 'mesh': 0}],
        'scenes': [{'nodes': [0]}], 'scene': 0,
    }
    document = json.dumps(gltf, separators=(',', ':')).encode('utf-8')
    document += b' ' * (-len(document) % 4)
    binary.extend(b'\0' * (-len(binary) % 4))
    length = 12 + 8 + len(document) + 8 + len(binary)
    OUTPUT.write_bytes(struct.pack('<4sII', b'glTF', 2, length)
                       + struct.pack('<I4s', len(document), b'JSON') + document
                       + struct.pack('<I4s', len(binary), b'BIN\0') + binary)
    print(f'GLB ready: {OUTPUT} ({length:,} bytes)')


if __name__ == '__main__':
    convert()
