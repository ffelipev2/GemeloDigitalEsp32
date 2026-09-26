"""Create the static grid and ring that Three.js used under the existing model."""
import json
import math
import pathlib
import struct

output = pathlib.Path(__file__).resolve().parents[1] / 'app/src/main/assets/scene-guide.glb'
positions = []
groups = [[], [], []]

def strip(ax, az, bx, bz, y, width, group):
    length = math.hypot(bx - ax, bz - az)
    nx, nz = -(bz - az) * width / (2 * length), (bx - ax) * width / (2 * length)
    first = len(positions)
    positions.extend(((ax + nx, y, az + nz), (ax - nx, y, az - nz),
                      (bx + nx, y, bz + nz), (bx - nx, y, bz - nz)))
    groups[group].extend((first, first + 1, first + 2, first + 2, first + 1, first + 3))

for i in range(21):
    coord = -3 + 0.3 * i
    group = 1 if i == 10 else 0
    strip(coord, -3, coord, 3, 0.04, 0.004, group)
    strip(-3, coord, 3, coord, 0.04, 0.004, group)

for i in range(72):
    a, b = i * 2 * math.pi / 72, (i + 1) * 2 * math.pi / 72
    strip(1.45 * math.cos(a), 1.45 * math.sin(a),
          1.45 * math.cos(b), 1.45 * math.sin(b), 0.055, 0.005, 2)

binary = bytearray()
views = []
accessors = []

def append(data, component_type, count, kind, minimum=None, maximum=None):
    while len(binary) % 4:
        binary.append(0)
    offset = len(binary)
    binary.extend(data)
    views.append({'buffer': 0, 'byteOffset': offset, 'byteLength': len(data)})
    accessor = {'bufferView': len(views) - 1, 'componentType': component_type,
                'count': count, 'type': kind}
    if minimum is not None:
        accessor['min'], accessor['max'] = minimum, maximum
    accessors.append(accessor)
    return len(accessors) - 1

flat = [v for point in positions for v in point]
position_accessor = append(struct.pack('<' + 'f' * len(flat), *flat), 5126, len(positions), 'VEC3',
                           [min(p[i] for p in positions) for i in range(3)],
                           [max(p[i] for p in positions) for i in range(3)])
primitives = []
for material, indices in enumerate(groups):
    accessor = append(struct.pack('<' + 'H' * len(indices), *indices), 5123, len(indices), 'SCALAR')
    primitives.append({'attributes': {'POSITION': position_accessor}, 'indices': accessor,
                       'material': material, 'mode': 4})

def material(color, alpha):
    return {'pbrMetallicRoughness': {'baseColorFactor': [*color, alpha],
                                    'metallicFactor': 0, 'roughnessFactor': 1},
            'alphaMode': 'BLEND', 'doubleSided': True,
            'extensions': {'KHR_materials_unlit': {}}}

gltf = {'asset': {'version': '2.0', 'generator': 'GemeloDigitalEsp32 native scene guide'},
        'extensionsUsed': ['KHR_materials_unlit'],
        'buffers': [{'byteLength': len(binary)}], 'bufferViews': views, 'accessors': accessors,
        'materials': [material((.145, .224, .314), .34),
                      material((.208, .314, .420), .34),
                      material((.322, .471, .616), .45)],
        'meshes': [{'primitives': primitives}], 'nodes': [{'mesh': 0}],
        'scenes': [{'nodes': [0]}], 'scene': 0}
json_chunk = bytearray(json.dumps(gltf, separators=(',', ':')).encode())
while len(json_chunk) % 4:
    json_chunk.append(32)
while len(binary) % 4:
    binary.append(0)
length = 12 + 8 + len(json_chunk) + 8 + len(binary)
output.write_bytes(struct.pack('<4sII', b'glTF', 2, length)
                   + struct.pack('<I4s', len(json_chunk), b'JSON') + json_chunk
                   + struct.pack('<I4s', len(binary), b'BIN\0') + binary)
print(output)
