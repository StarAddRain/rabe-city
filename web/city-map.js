/* Pixel coordinates traced against assets/pastel-canal-city-map.png (1672 x 941).
 * Keep this geometry with the image: these are illustration roads, not GPS data.
 * No vehicle is created here; identities still come from the registration service.
 */
(() => {
    'use strict';
    const world = Object.freeze({ w: 1672, h: 941 });
    const nodes = {
        nw: [54, 88], n1: [238, 67], n2: [466, 43], n3: [736, 23],
        a1: [274, 176], a2: [486, 151], a3: [758, 149], a4: [995, 124],
        a5: [1140, 113], a6: [1361, 94], a7: [1610, 73],
        b3: [784, 248], b4: [1010, 223],
        c0: [146, 393], c1: [329, 389], c3: [808, 369],
        c4: [1034, 330], c5: [1171, 306], c6: [1394, 251], c7: [1627, 188],
        d0: [130, 549], d1: [374, 516], d3: [841, 558],
        d4: [1060, 518], d5: [1200, 441], d6: [1440, 402],
        e0: [132, 683], e1: [409, 617], e2: [650, 596], e3: [865, 630], e4: [1058, 595],
        f1: [440, 839], f2: [731, 741], f3: [885, 690], f4: [1055, 702],
        f5: [1237, 711], f6: [1510, 718],
        g3: [919, 790], g4: [1056, 758], h4: [1115, 828],
        s0: [171, 912], s3: [949, 900], s5: [1237, 904], s6: [1539, 837],
        j5: [1422, 862], j4: [1236, 868]
    };
    // Shared curved sections prevent shortcuts across canals or landscaped corners.
    const bends = {
        'nw/c0': [[82, 172], [113, 278], [139, 365]],
        'c0/d0': [[147, 423], [143, 463], [133, 513]],
        'd0/d1': [[225, 555], [268, 542]],
        'a1/c1': [[295, 259], [311, 341]],
        'c1/d1': [[348, 434]],
        'c3/d3': [[815, 409], [817, 452], [830, 509]],
        'c6/d6': [[1412, 316]],
        'd6/f6': [[1455, 458], [1474, 559], [1487, 616]],
        'c5/d5': [[1184, 379], [1193, 421]],
        'd5/d4': [[1189, 452], [1126, 484], [1075, 508]],
        'd4/e4': [[1056, 537]],
        'e0/e1': [[212, 667], [312, 641]],
        'f3/f2': [[838, 690], [810, 696], [773, 715]],
        'f2/f1': [[679, 767], [588, 795], [519, 815]],
        'f1/s0': [[353, 879], [307, 904], [282, 910]],
        's0/e0': [[158, 845], [141, 769], [133, 710]],
        'g4/h4': [[1065, 774], [1083, 795], [1105, 819]],
        'h4/s5': [[1152, 853], [1203, 882]],
        'h4/s3': [[1081, 850], [1038, 868], [984, 889]],
        'j5/s6': [[1441, 859], [1474, 842], [1494, 840]],
        'f6/s6': [[1526, 810]]
    };
    function polyline(names) {
        const points = [];
        names.forEach((name, i) => {
            const next = names[(i + 1) % names.length];
            points.push(nodes[name]);
            const bend = bends[name + '/' + next];
            const reverse = bends[next + '/' + name];
            if (bend) points.push(...bend);
            else if (reverse) points.push(...reverse.slice().reverse());
        });
        return points;
    }
    const definitions = [
        ['西北沿河', ['nw', 'n1', 'a1', 'c1', 'd1', 'd0', 'c0']],
        ['北侧住宅', ['n1', 'n2', 'a2', 'a1']],
        ['西部街区', ['a1', 'a2', 'n2', 'n3', 'a3', 'b3', 'c3', 'd3', 'e2', 'e1', 'd1', 'c1']],
        ['中北街区', ['a3', 'a4', 'b4', 'c4', 'c3', 'b3']],
        ['东北沿河', ['a4', 'a5', 'c5', 'c4', 'b4']],
        ['东北住宅', ['a5', 'a6', 'c6', 'c5']],
        ['东侧街区', ['a6', 'a7', 'c7', 'c6']],
        ['中央跨河', ['c3', 'c4', 'c5', 'd5', 'd4', 'd3']],
        ['东部公园', ['c5', 'c6', 'd6', 'f6', 'f5', 'f4', 'e4', 'd4', 'd5']],
        ['西南公园', ['e0', 'e1', 'e2', 'd3', 'e3', 'f3', 'f2', 'f1', 's0']],
        ['南部街区', ['f3', 'f4', 'g4', 'h4', 's3', 'g3']],
        ['东南沿河', ['f4', 'f5', 'f6', 's6', 'j5', 'j4', 's5', 'h4', 'g4']]
    ];
    const distance = (a, b) => Math.hypot(b[0] - a[0], b[1] - a[1]);
    const lerp = (a, b, t) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];
    function lane(points, offset) {
        return points.map((p, i) => {
            const prev = points[(i + points.length - 1) % points.length];
            const next = points[(i + 1) % points.length];
            const l1 = distance(prev, p), l2 = distance(p, next);
            const n1 = [-(p[1] - prev[1]) / l1, (p[0] - prev[0]) / l1];
            const n2 = [-(next[1] - p[1]) / l2, (next[0] - p[0]) / l2];
            const divisor = Math.max(.6, 1 + n1[0] * n2[0] + n1[1] * n2[1]);
            return [p[0] + (n1[0] + n2[0]) * offset / divisor, p[1] + (n1[1] + n2[1]) * offset / divisor];
        });
    }
    function smooth(points) {
        const result = [];
        points.forEach((p, i) => {
            const prev = points[(i + points.length - 1) % points.length], next = points[(i + 1) % points.length];
            const radius = Math.min(6, distance(prev, p) / 4, distance(p, next) / 4);
            const a = lerp(p, prev, radius / distance(prev, p)), b = lerp(p, next, radius / distance(p, next));
            for (let s = 0; s <= 8; s++) {
                const t = s / 8, u = 1 - t;
                result.push([u * u * a[0] + 2 * u * t * p[0] + t * t * b[0], u * u * a[1] + 2 * u * t * p[1] + t * t * b[1]]);
            }
        });
        return result;
    }
    function compile(name, centerline, reverse = false) {
        const points = smooth(lane(reverse ? centerline.slice().reverse() : centerline, 3));
        const lengths = points.map((p, i) => distance(p, points[(i + 1) % points.length]));
        const cumulative = [0];
        lengths.forEach(length => cumulative.push(cumulative[cumulative.length - 1] + length));
        return { name, points, lengths, cumulative, length: cumulative[cumulative.length - 1] };
    }
    const routes = definitions.map(([name, names]) => {
        const centerline = polyline(names);
        return { name, centerline, forward: compile(name, centerline), reverse: compile(name, centerline, true) };
    });
    // Interleave distant districts: even the first few registered cars spread out.
    const order = [0, 8, 5, 9, 3, 11, 2, 6, 10, 4, 7, 1];
    function radicalInverse(n) { let result = 0, fraction = .5; while (n > 0) { result += (n % 2) * fraction; n = Math.floor(n / 2); fraction /= 2; } return result; }
    function assignment(id) {
        const slot = Math.max(0, Math.trunc(Number(id) || 0)), index = order[slot % order.length];
        const cohort = Math.floor(slot / order.length);
        const reverse = cohort % 2 === 1;
        return { index, route: routes[index][reverse ? 'reverse' : 'forward'], reverse,
            phase: (radicalInverse(Math.floor(cohort / 2)) + (index + 1) * .61803398875) % 1,
            speed: 19 + index % 4 };
    }
    function sample(route, at) {
        const d = ((at % route.length) + route.length) % route.length;
        let lo = 0, hi = route.lengths.length - 1;
        while (lo < hi) { const mid = (lo + hi) >>> 1; if (route.cumulative[mid + 1] <= d) lo = mid + 1; else hi = mid; }
        const a = route.points[lo], b = route.points[(lo + 1) % route.points.length];
        const t = (d - route.cumulative[lo]) / route.lengths[lo];
        return { x: a[0] + (b[0] - a[0]) * t, y: a[1] + (b[1] - a[1]) * t, angle: Math.atan2(b[1] - a[1], b[0] - a[0]) };
    }
    function position(id, elapsed) {
        const a = assignment(id);
        return { ...sample(a.route, a.phase * a.route.length + elapsed * a.speed), route: a.index };
    }
    const api = { world, image: '/assets/pastel-canal-city-map.png', routes, assignment, sample, position };
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    else window.CityMap = api;
})();
