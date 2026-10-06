"""Check production lanes against the illustration's grey road surface.

Run from the project root with Python + Pillow + NumPy and a Node executable:
    python tests/check-route-surface.py --node /path/to/node

No registration data or server is used. The image path and all geometry are read
from web/city-map.js. Colour classification is deliberately conservative: labels
over a road, shadows, and unknown colours require visual review, never exemption.
"""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
# These paved surfaces were reviewed against enlarged, unmodified source crops.
# Their blue-grey tint is darker and less saturated than the canal.
# A rectangle only restricts where the extra colour class may apply; every
# probe must still pass the colour predicate. White rails and water fail it.
REVIEWED_ROAD_TINTS = [
    {'name': 'west canal bridge', 'bounds': [330, 511, 378, 543],
     'reason': 'Continuous paved bridge deck, with a blue-grey canal reflection.'},
    {'name': 'south Liming Lake bridge', 'bounds': [637, 1030, 678, 1055],
     'reason': 'Continuous paved bridge deck between the lake and south canal.'},
    {'name': 'north Wushan road colour patch', 'bounds': [327, 637, 349, 657],
     'reason': 'Enlarged source shows uninterrupted paved road between white curbs; blue-grey pixels around RGB(90,111,131) are road shading, not water or a label.'},
]
NODE_EXPORT = r"""
const map = require(process.argv[1]);
const step = Number(process.argv[2]);
console.log(JSON.stringify({
  world: map.world, image: map.image, vehicleScale: map.vehicleScale ?? 1,
  routes: map.routes.flatMap((route, index) => ['forward', 'reverse'].map(direction => {
    const path = route[direction];
    const count = Math.ceil(path.length / step);
    return {index, name: route.name, direction, length: path.length,
      samples: Array.from({length: count}, (_, i) => map.sample(path, i * path.length / count))};
  }))
}));
"""


def reviewed_tint_mask(rgb):
    data = rgb.astype(np.int16)
    red, green, blue = data[..., 0], data[..., 1], data[..., 2]
    tint = ((red >= 75) & (red <= 125)
            & (green - red >= 3) & (green - red <= 36)
            & (blue - green >= -4) & (blue - green <= 31)
            & (np.max(data, axis=-1) <= 155))
    reviewed_area = np.zeros(rgb.shape[:2], dtype=bool)
    for region in REVIEWED_ROAD_TINTS:
        x0, y0, x1, y1 = region['bounds']
        reviewed_area[y0:y1+1, x0:x1+1] = True
    return reviewed_area & tint


def road_mask(rgb):
    """Main-road colours around RGB(95,111,119), excluding blue water/green trees."""
    data = rgb.astype(np.int16)
    red, green, blue = data[..., 0], data[..., 1], data[..., 2]
    grey = ((red >= 75) & (red <= 145)
            & (green - red >= 3) & (green - red <= 27)
            & (blue - green >= -4) & (blue - green <= 19)
            & (np.max(data, axis=-1) <= 150))
    return grey | reviewed_tint_mask(rgb)


def footprint(scale=1.0):
    # Largest rendered car is 18 x 7 px, with wheels reaching +/-4.3 px.
    # Sample a conservative enclosing rectangle, including its full perimeter.
    points = [(0.0, 0.0)]
    points += [(float(x), y) for x in np.linspace(-9, 9, 11) for y in (-4.3, 4.3)]
    points += [(x, float(y)) for y in np.linspace(-4.3, 4.3, 7) for x in (-9.0, 9.0)]
    return np.asarray(points) * scale


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--node', default=shutil.which('node'))
    parser.add_argument('--image', type=Path, help='Override the production image for diagnostics')
    parser.add_argument('--step', type=float, default=2.0)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/map-check/route-surface')
    args = parser.parse_args()
    if not args.node:
        parser.error('Node is not on PATH; provide --node with its full path')
    if not (0 < args.step <= 2):
        parser.error('--step must be greater than 0 and at most 2 pixels')
    result = subprocess.run([args.node, '-e', NODE_EXPORT, str(ROOT / 'web/city-map.js'), str(args.step)],
                            check=True, capture_output=True, encoding='utf-8')
    model = json.loads(result.stdout)
    vehicle_scale = float(model['vehicleScale'])
    if not np.isfinite(vehicle_scale) or vehicle_scale <= 0:
        raise ValueError('CityMap.vehicleScale must be finite and positive')
    image_path = (args.image or (ROOT / 'web' / model['image'].lstrip('/'))).resolve()
    original = Image.open(image_path).convert('RGB')
    rgb = np.asarray(original)
    mask = road_mask(rgb)
    width, height = original.size
    args.output.mkdir(parents=True, exist_ok=True)
    Image.fromarray(np.uint8(mask) * 255).save(args.output / 'road-mask.png')
    dim_match = (model['world']['w'], model['world']['h']) == original.size
    report = {
        'image': str(image_path), 'dimensions': {'w': width, 'h': height},
        'imageSha256': hashlib.sha256(image_path.read_bytes()).hexdigest(),
        'geometrySha256': hashlib.sha256((ROOT / 'web/city-map.js').read_bytes()).hexdigest(),
        'world': model['world'], 'dimensionsMatch': dim_match,
        'stepPixelsAtMost': args.step,
        'footprint': {'vehicleScale': vehicle_scale, 'length': 18 * vehicle_scale,
                      'widthIncludingWheels': 8.6 * vehicle_scale,
                      'probeCount': len(footprint(vehicle_scale)),
                      'shape': 'conservative enclosing rectangle perimeter plus center'},
        'classifier': {'red': [75, 145], 'greenMinusRed': [3, 27],
                       'blueMinusGreen': [-4, 19], 'maximumChannel': 150,
                       'reviewedRoadTint': {'red': [75, 125], 'greenMinusRed': [3, 36],
                                              'blueMinusGreen': [-4, 31], 'maximumChannel': 155,
                                              'regions': REVIEWED_ROAD_TINTS}},
        'limitations': [
            'Road colours are sampled from this specific illustration, not a semantic road dataset.',
            'Labels, unusual shading, and non-grey patches are reported as failures requiring visual review.',
            'No failed pixels are exempted or automatically interpreted as a bridge or hidden road.',
            'Visually verified bridge decks and one paved-road colour patch use a separate dark blue-grey colour class only within the documented image rectangles.',
            'The test checks lane placement; it does not prove traffic-light obedience or vehicle collision avoidance.'
        ],
        'routes': [], 'totalSamples': 0, 'totalFailedCenterSamples': 0,
        'totalFailedFootprintSamples': 0, 'totalOutsideImageSamples': 0,
    }
    overlay = original.convert('RGBA')
    marks = Image.new('RGBA', original.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(marks)
    palette = ['#e46c24', '#a42be0', '#087ee0', '#d42868', '#007f55', '#cb9210',
               '#772abb', '#006d92', '#c74828', '#c50091', '#1745c5', '#5f7100']
    local = footprint(vehicle_scale)
    for route in model['routes']:
        samples = route.pop('samples')
        values = np.asarray([[p['x'], p['y'], p['angle']] for p in samples])
        if not np.isfinite(values).all():
            raise ValueError(f"Non-finite route sample: {route['name']} {route['direction']}")
        angles = values[:, 2, None]
        xx = values[:, 0, None] + np.cos(angles) * local[None, :, 0] - np.sin(angles) * local[None, :, 1]
        yy = values[:, 1, None] + np.sin(angles) * local[None, :, 0] + np.cos(angles) * local[None, :, 1]
        ix, iy = np.rint(xx).astype(int), np.rint(yy).astype(int)
        inside = (ix >= 0) & (ix < width) & (iy >= 0) & (iy < height)
        valid = np.zeros_like(inside)
        valid[inside] = mask[iy[inside], ix[inside]]
        bad_center = ~valid[:, 0]
        bad_body = ~valid.all(axis=1)
        outside = ~inside.all(axis=1)
        count = len(samples)
        entry = {**route, 'sampleCount': count,
                 'failedCenterSamples': int(bad_center.sum()),
                 'failedFootprintSamples': int(bad_body.sum()),
                 'outsideImageSamples': int(outside.sum()), 'failureSpans': [], 'failures': []}
        bad_indices = np.flatnonzero(bad_body)
        if len(bad_indices):
            cuts = np.flatnonzero(np.diff(bad_indices) > 1) + 1
            for span in np.split(bad_indices, cuts):
                first, last = int(span[0]), int(span[-1])
                entry['failureSpans'].append({
                    'firstSample': first, 'lastSample': last, 'sampleCount': len(span),
                    'from': [round(float(v), 2) for v in values[first, :2]],
                    'to': [round(float(v), 2) for v in values[last, :2]],
                })
        for i in np.flatnonzero(bad_body):
            probes = []
            for j in np.flatnonzero(~valid[i]):
                probes.append({'x': int(ix[i, j]), 'y': int(iy[i, j]),
                               'probe': int(j), 'insideImage': bool(inside[i, j]),
                               'rgb': rgb[iy[i, j], ix[i, j]].tolist() if inside[i, j] else None})
            entry['failures'].append({'sample': int(i),
                                      'distance': round(float(i * route['length'] / count), 2),
                                      'x': round(float(values[i, 0]), 2), 'y': round(float(values[i, 1]), 2),
                                      'centerOnRoad': bool(valid[i, 0]),
                                      'failedProbeCount': len(probes), 'failedProbes': probes})
        report['routes'].append(entry)
        report['totalSamples'] += count
        report['totalFailedCenterSamples'] += entry['failedCenterSamples']
        report['totalFailedFootprintSamples'] += entry['failedFootprintSamples']
        report['totalOutsideImageSamples'] += entry['outsideImageSamples']
        points = [(float(p[0]), float(p[1])) for p in values]
        color = palette[route['index'] % len(palette)]
        draw.line(points + [points[0]], fill=color, width=2)
        for i in np.flatnonzero(bad_body):
            x, y = points[i]
            fill = '#e50046' if bad_center[i] else '#ff9d00'
            draw.ellipse((x-2, y-2, x+2, y+2), fill=fill)
        if route['direction'] == 'forward':
            x, y = points[0]
            draw.rectangle((x-2, y-2, x+23, y+14), fill='#ffffffec')
            draw.text((x, y), str(route['index']+1), fill=color)
    report['passed'] = bool(dim_match and report['totalFailedFootprintSamples'] == 0)
    overlay = Image.alpha_composite(overlay, marks)
    legend = ImageDraw.Draw(overlay)
    legend.rectangle((10, 10, 555, 45), fill='#ffffffe8')
    legend.text((18, 16), 'Road checks: pink = center off road; orange = body edge off road', fill='#222222')
    legend.text((18, 30), 'Numbered colours = production routes, both travel directions', fill='#222222')
    overlay.convert('RGB').save(args.output / 'route-overlay.png')
    (args.output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    summary = {key: report[key] for key in ['passed', 'dimensionsMatch', 'totalSamples',
                'totalFailedCenterSamples', 'totalFailedFootprintSamples', 'totalOutsideImageSamples']}
    summary['artifacts'] = str(args.output.resolve())
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    for route in report['routes']:
        if route['failedFootprintSamples']:
            print(f"route {route['index']+1} {route['direction']}: "
                  f"center={route['failedCenterSamples']}, body={route['failedFootprintSamples']}")
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
