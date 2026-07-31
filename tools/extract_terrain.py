# -*- coding: utf-8 -*-
"""Extract real terrain from an anvil save into gametest SNBT templates.
Modes:  scan  <regiondir> <block...>          -> list sections containing blocks
        extract <regiondir> <x0> <y0> <z0> <sx> <sy> <sz> <out.snbt>  -> cut box
"""
import io, os, sys, struct, zlib, gzip

def read_str(f):
    n = struct.unpack('>H', f.read(2))[0]
    return f.read(n).decode('utf-8', 'replace')

def parse_payload(f, t):
    if t == 1: return struct.unpack('>b', f.read(1))[0]
    if t == 2: return struct.unpack('>h', f.read(2))[0]
    if t == 3: return struct.unpack('>i', f.read(4))[0]
    if t == 4: return struct.unpack('>q', f.read(8))[0]
    if t == 5: return struct.unpack('>f', f.read(4))[0]
    if t == 6: return struct.unpack('>d', f.read(8))[0]
    if t == 7:
        n = struct.unpack('>i', f.read(4))[0]; return f.read(n)
    if t == 8: return read_str(f)
    if t == 9:
        et = f.read(1)[0]; n = struct.unpack('>i', f.read(4))[0]
        return [parse_payload(f, et) for _ in range(n)]
    if t == 10:
        d = {}
        while True:
            et = f.read(1)[0]
            if et == 0: break
            k = read_str(f); d[k] = parse_payload(f, et)
        return d
    if t == 11:
        n = struct.unpack('>i', f.read(4))[0]
        return list(struct.unpack('>%di' % n, f.read(4 * n)))
    if t == 12:
        n = struct.unpack('>i', f.read(4))[0]
        return list(struct.unpack('>%dq' % n, f.read(8 * n)))
    raise ValueError('tag %d' % t)

def read_nbt(data):
    f = io.BytesIO(data)
    t = f.read(1)[0]
    read_str(f)
    return parse_payload(f, t)

_region_cache = {}
def load_region(regdir, rx, rz):
    key = (rx, rz)
    if key in _region_cache: return _region_cache[key]
    p = os.path.join(regdir, 'r.%d.%d.mca' % (rx, rz))
    data = open(p, 'rb').read() if os.path.exists(p) else None
    _region_cache[key] = data
    return data

_chunk_cache = {}
def load_chunk(regdir, cx, cz):
    key = (cx, cz)
    if key in _chunk_cache: return _chunk_cache[key]
    data = load_region(regdir, cx >> 5, cz >> 5)
    chunk = None
    if data:
        idx = ((cx & 31) + (cz & 31) * 32) * 4
        off = int.from_bytes(data[idx:idx+3], 'big') * 4096
        if off:
            ln = int.from_bytes(data[off:off+4], 'big')
            comp = data[off+4]
            raw = data[off+5:off+4+ln]
            nbt = zlib.decompress(raw) if comp == 2 else gzip.decompress(raw)
            chunk = read_nbt(nbt)
    _chunk_cache[key] = chunk
    return chunk

def section_reader(sec):
    """-> (palette, get(i)) ; get None means uniform palette[0]. i = y*256+z*16+x."""
    bs = sec.get('block_states')
    if not bs: return None, None
    pal = bs['palette']
    data = bs.get('data')
    if not data: return pal, None
    bits = max(4, (len(pal) - 1).bit_length())
    per = 64 // bits
    mask = (1 << bits) - 1
    udata = [x & 0xFFFFFFFFFFFFFFFF for x in data]
    def get(i):
        return (udata[i // per] >> ((i % per) * bits)) & mask
    return pal, get

def iter_chunks(regdir):
    for fn in os.listdir(regdir):
        if not fn.endswith('.mca'): continue
        rx, rz = int(fn.split('.')[1]), int(fn.split('.')[2])
        data = load_region(regdir, rx, rz)
        if not data: continue
        for ci in range(1024):
            off = int.from_bytes(data[ci*4:ci*4+3], 'big')
            if off == 0: continue
            cx, cz = (rx << 5) + (ci & 31), (rz << 5) + (ci >> 5)
            yield cx, cz

def scan(regdir, targets):
    hits = []
    for cx, cz in iter_chunks(regdir):
        ch = load_chunk(regdir, cx, cz)
        if not ch or 'sections' not in ch: continue
        for sec in ch['sections']:
            bs = sec.get('block_states')
            if not bs: continue
            names = [p.get('Name', '') for p in bs['palette']]
            found = [t for t in targets if t in names]
            if not found: continue
            pal, get = section_reader(sec)
            counts = {}
            if get is None:
                if pal[0].get('Name', '') in targets:
                    counts[pal[0]['Name']] = 4096
            else:
                for i in range(4096):
                    n = pal[get(i)].get('Name', '')
                    if n in targets:
                        counts[n] = counts.get(n, 0) + 1
            if counts:
                hits.append((cx * 16, sec['Y'] * 16, cz * 16, counts))
    hits.sort(key=lambda h: -sum(h[3].values()))
    for h in hits[:20]:
        print('block(%d,%d,%d)' % (h[0], h[1], h[2]), h[3])

def get_block(regdir, x, y, z):
    ch = load_chunk(regdir, x >> 4, z >> 4)
    if not ch: return None
    for sec in ch.get('sections', []):
        if sec.get('Y') == (y >> 4):
            pal, get = section_reader(sec)
            if pal is None: return None
            i = (y & 15) * 256 + (z & 15) * 16 + (x & 15)
            return pal[0] if get is None else pal[get(i)]
    return None

AIR = {'minecraft:air', 'minecraft:cave_air', 'minecraft:void_air', None}

def snbt_state(p):
    # gametest .snbt PACKED format (NbtUtils.packBlockState): palette entries are
    # STRINGS like  minecraft:spruce_log{axis:y}  (props sorted, unquoted values).
    # The raw structure-NBT compound form ({Name,Properties}) is silently ignored
    # by NbtUtils.unpackStructureTemplate -> template places ZERO blocks.
    if not p['Name'].startswith('minecraft:'):
        # modded block from the source save: the test env has no mods -> would
        # silently become air. Substitute plain terrain filler.
        print('substituting', p['Name'], '-> minecraft:deepslate')
        p = {'Name': 'minecraft:deepslate'}
    s = p['Name']
    props = p.get('Properties')
    if props:
        s += '{' + ','.join('%s:%s' % (k, v) for k, v in sorted(props.items())) + '}'
    return s

def extract(regdir, x0, y0, z0, sx, sy, sz, out):
    palette, pindex, blocks = [], {}, []
    spawn = None
    for dy in range(sy):
        for dz in range(sz):
            for dx in range(sx):
                p = get_block(regdir, x0 + dx, y0 + dy, z0 + dz)
                name = p.get('Name') if p else None
                if name in AIR:
                    continue
                key = snbt_state(p)
                if key not in pindex:
                    pindex[key] = len(palette)
                    palette.append(key)
                blocks.append('{pos:[%d,%d,%d],state:"%s"}' % (dx, dy, dz, key))
    # find a standable spawn: solid ground with 2 air above, prefer near a corner
    for dx in range(1, sx - 1):
        for dz in range(1, sz - 1):
            for dy in range(sy - 3, 0, -1):
                g = get_block(regdir, x0 + dx, y0 + dy, z0 + dz)
                a1 = get_block(regdir, x0 + dx, y0 + dy + 1, z0 + dz)
                a2 = get_block(regdir, x0 + dx, y0 + dy + 2, z0 + dz)
                gn = g.get('Name') if g else None
                if gn not in AIR and gn != 'minecraft:water' and \
                   (a1.get('Name') if a1 else None) in AIR and (a2.get('Name') if a2 else None) in AIR:
                    spawn = (dx, dy + 1, dz)
                    break
            if spawn: break
        if spawn: break
    snbt = '{DataVersion:3955,size:[%d,%d,%d],entities:[],palette:[%s],data:[%s]}' % (
        sx, sy, sz, ','.join('"%s"' % k for k in palette), ','.join(blocks))
    io.open(out, 'w', encoding='utf-8', newline='\n').write(snbt + '\n')
    print('wrote', out, '| blocks:', len(blocks), '| palette:', len(palette), '| spawn(rel):', spawn)

if __name__ == '__main__':
    mode = sys.argv[1]
    if mode == 'scan':
        scan(sys.argv[2], sys.argv[3:])
    else:
        extract(sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5]),
                int(sys.argv[6]), int(sys.argv[7]), int(sys.argv[8]), sys.argv[9])
