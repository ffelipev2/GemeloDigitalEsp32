"""Generate the optional local XYZ guide (unlit GLB, no runtime geometry work)."""
import json
import math
import pathlib
import struct

output = pathlib.Path(__file__).resolve().parents[1] / 'app/src/main/assets/model-axes.glb'
binary = bytearray()
views, accessors, primitives = [], [], []

for axis in range(3):
    points = []

    def point(length, u, v):
        xyz = [0.0, 0.0, 0.0]
        xyz[axis] = length
        xyz[(axis + 1) % 3] = u
        xyz[(axis + 2) % 3] = v
        return xyz

    for segment in range(12):
        a, b = segment * math.tau / 12, (segment + 1) * math.tau / 12
        u, v = math.cos(a), math.sin(a)
        s, t = math.cos(b), math.sin(b)
        # Cylinder shaft and cone tip. Double-sided unlit triangles need no normals.
        points.extend([point(0, u*.012, v*.012), point(.8, u*.012, v*.012), point(.8, s*.012, t*.012),
                       point(0, u*.012, v*.012), point(.8, s*.012, t*.012), point(0, s*.012, t*.012),
                       point(.8, u*.05, v*.05), point(.95, 0, 0), point(.8, s*.05, t*.05)])
    data = struct.pack('<' + 'f' * (len(points)*3), *(value for xyz in points for value in xyz))
    views.append({'buffer': 0, 'byteOffset': len(binary), 'byteLength': len(data), 'target': 34962})
    binary.extend(data)
    accessors.append({'bufferView': axis, 'componentType': 5126, 'count': len(points), 'type': 'VEC3',
                      'min': [min(p[i] for p in points) for i in range(3)],
                      'max': [max(p[i] for p in points) for i in range(3)]})
    primitives.append({'attributes': {'POSITION': axis}, 'material': axis, 'mode': 4})

materials = [{'pbrMetallicRoughness': {'baseColorFactor': color, 'metallicFactor': 0, 'roughnessFactor': 1},
              'doubleSided': True, 'extensions': {'KHR_materials_unlit': {}}}
             for color in ([1, .18, .12, 1], [.12, 1, .35, 1], [.12, .55, 1, 1])]
gltf = {'asset': {'version': '2.0', 'generator': 'GemeloDigitalEsp32 XYZ guide'},
        'extensionsUsed': ['KHR_materials_unlit'], 'buffers': [{'byteLength': len(binary)}],
        'bufferViews': views, 'accessors': accessors, 'materials': materials,
        'meshes': [{'primitives': primitives}], 'nodes': [{'mesh': 0}], 'scenes': [{'nodes': [0]}], 'scene': 0}
metadata = json.dumps(gltf, separators=(',', ':')).encode()
metadata += b' ' * (-len(metadata) % 4)
output.write_bytes(struct.pack('<4sII', b'glTF', 2, 28 + len(metadata) + len(binary))
                   + struct.pack('<I4s', len(metadata), b'JSON') + metadata
                   + struct.pack('<I4s', len(binary), b'BIN\0') + binary)
print(output)
