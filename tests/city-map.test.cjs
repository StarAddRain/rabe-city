const test = require('node:test');
const assert = require('node:assert/strict');

const map = require('../web/city-map.js');

const ids = Array.from({ length: 128 }, (_, i) => i);
const times = [0, 1.37, 12.31, 48.7, 1000];
const distance = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);

test('map exposes twelve named, closed forward and reverse routes', () => {
  assert.equal(map.routes.length, 12);
  assert.equal(new Set(map.routes.map(route => route.name)).size, 12);

  for (const route of map.routes) {
    for (const direction of ['forward', 'reverse']) {
      const path = route[direction];
      assert.ok(path.points.length >= 3);
      assert.ok(path.length > 0);
      assert.equal(path.lengths.length, path.points.length);
      assert.ok(path.lengths.every(length => Number.isFinite(length) && length > 0));

      // A route is cyclic: sampling one full loop returns to its start.
      const start = map.sample(path, 0);
      const end = map.sample(path, path.length);
      assert.ok(distance(start, end) < 1e-6, `${route.name} ${direction} is not closed`);
    }
  }
});

test('all registered vehicle IDs stay inside the image bounds', () => {
  for (const id of ids) {
    for (const elapsed of times) {
      const point = map.position(id, elapsed);
      assert.ok(Number.isFinite(point.x) && Number.isFinite(point.y));
      assert.ok(point.x >= 0 && point.x <= map.world.w, `x out of bounds for ${id}`);
      assert.ok(point.y >= 0 && point.y <= map.world.h, `y out of bounds for ${id}`);
      assert.ok(Number.isFinite(point.angle));
    }
  }
});

test('the first twelve vehicles cover every route', () => {
  const assignments = ids.slice(0, 12).map(id => map.assignment(id));
  assert.deepEqual(
    new Set(assignments.map(item => item.index)),
    new Set(Array.from({ length: 12 }, (_, i) => i))
  );
  assert.ok(assignments.every(item => item.reverse === false));
});

test('a vehicle position is independent of the other vehicles', () => {
  const before = map.position(37, 23.75);
  for (const id of ids) map.position(id, id * 2.1);
  const after = map.position(37, 23.75);
  assert.deepEqual(after, before);
});

test('movement remains continuous when a vehicle crosses a route seam', () => {
  for (const id of ids) {
    const assignment = map.assignment(id);
    const period = assignment.route.length / assignment.speed;
    const seamTime = period * (1 - assignment.phase);
    const before = map.position(id, seamTime - 0.002);
    const after = map.position(id, seamTime + 0.002);
    assert.ok(distance(before, after) < 0.2, `seam jump for vehicle ${id}`);
  }
});

test('forward and reverse lanes are both assigned and remain separated', () => {
  for (let routeIndex = 0; routeIndex < map.routes.length; routeIndex++) {
    const forward = map.assignment(routeIndex);
    const reverse = map.assignment(routeIndex + 12);
    assert.equal(forward.index, reverse.index);
    assert.equal(forward.reverse, false);
    assert.equal(reverse.reverse, true);

    const route = map.routes[routeIndex];
    const n = Math.min(route.forward.points.length, route.reverse.points.length);
    for (const fraction of [0.1, 0.5, 0.9]) {
      const i = Math.min(n - 1, Math.floor(n * fraction));
      const opposite = route.reverse.points[route.reverse.points.length - 1 - i];
      const laneGap = Math.hypot(
        route.forward.points[i][0] - opposite[0],
        route.forward.points[i][1] - opposite[1]
      );
      assert.ok(laneGap > 4 && laneGap < 20, `${route.name} lane gap ${laneGap}`);
    }
  }
});

test('cars sharing a route do not start on top of each other', () => {
  const byRoute = new Map();
  for (const id of ids) {
    const index = map.assignment(id).index;
    if (!byRoute.has(index)) byRoute.set(index, []);
    byRoute.get(index).push(map.position(id, 0));
  }
  for (const positions of byRoute.values()) {
    for (let i = 0; i < positions.length; i++) {
      for (let j = i + 1; j < positions.length; j++) {
        assert.ok(distance(positions[i], positions[j]) > 4);
      }
    }
  }
});

test('pixel movement matches a practical city-car speed', () => {
  for (const id of ids) {
    for (const elapsed of [0, 1.37, 12.31, 48.7]) {
      const current = map.position(id, elapsed);
      const next = map.position(id, elapsed + 0.25);
      const pixels = distance(current, next);
      assert.ok(pixels > 3 && pixels < 6.5, `unexpected displacement ${pixels} for ${id}`);
    }
  }
});
