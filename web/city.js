/* One finite, user-provided city image and deterministic road-aligned traffic. */
(() => {
    const canvas = document.getElementById('city'), ctx = canvas.getContext('2d');
    const map = window.CityMap, world = map.world;
    let W = innerWidth, H = innerHeight, scale = 1, offset = { x: 0, y: 0 }, vehicles = [], positions = [], selected = null, paused = false, night = false, last = performance.now(), elapsed = 0, drag = null, filters = { owner: true, user: true, leaked: true };
    let followId = null, followAnchor = null, cameraTween = null, followZoom = .8;
    const staticMap = document.createElement('canvas'); staticMap.width = world.w; staticMap.height = world.h;
    const g = staticMap.getContext('2d'), background = new Image();
    let mapState = 'loading';
    canvas.dataset.mapState = mapState;
    function renderStatic() {
        g.clearRect(0, 0, world.w, world.h);
        g.fillStyle = '#bdc9aa'; g.fillRect(0, 0, world.w, world.h);
        if (mapState === 'ready') {
            g.drawImage(background, 0, 0, world.w, world.h);
            if (night) { g.fillStyle = '#102d4266'; g.fillRect(0, 0, world.w, world.h); }
        } else {
            g.fillStyle = '#304c3e'; g.font = '24px sans-serif'; g.textAlign = 'center';
            g.fillText(mapState === 'error' ? '地图加载失败，请检查地图资源后刷新页面' : '正在加载城市地图…', world.w / 2, world.h / 2);
        }
    }
    background.onload = () => { mapState = 'ready'; canvas.dataset.mapState = mapState; renderStatic(); };
    background.onerror = () => { mapState = 'error'; canvas.dataset.mapState = mapState; renderStatic(); };
    background.src = map.image;
    function resize() {
        W = innerWidth; H = W < 600 ? 450 : innerHeight;
        const d = Math.min(devicePixelRatio || 1, 2);
        canvas.width = Math.round(W * d); canvas.height = Math.round(H * d);
        canvas.style.width = W + 'px'; canvas.style.height = H + 'px';
        ctx.setTransform(d, 0, 0, d, 0, 0);
        if (!drag) reset({ keepFollow: followId !== null });
    }
    function reset(options = {}) {
        const bounds = viewBounds();
        scale = Math.min((bounds.right - bounds.left) / world.w, (bounds.bottom - bounds.top) / world.h);
        offset = { x: bounds.centerX - world.w * scale / 2, y: bounds.centerY - world.h * scale / 2 };
        cameraTween = null;
        if (!options.keepFollow) stopFollow();
    }
    function visibleRect(selector) {
        const el = document.querySelector(selector), r = el?.getBoundingClientRect();
        return r && r.width > 0 && r.height > 0 && getComputedStyle(el).display !== 'none' ? r : null;
    }
    function viewBounds() {
        const margin = 20;
        let left = margin, top = margin, right = W - margin, bottom = H - margin;
        const panel = visibleRect('.panel'), dock = visibleRect('.bottom-dock'), stats = visibleRect('.stats'), topbar = visibleRect('.topbar'), legend = visibleRect('.legend');
        if (W >= 600 && panel && panel.left > 0 && panel.top < H && panel.bottom > 0) right = Math.min(right, panel.left - margin);
        if (dock && dock.top > 0 && dock.top < H) bottom = Math.min(bottom, dock.top - margin);
        if (stats && stats.bottom > 0 && stats.top < H) top = Math.max(top, stats.bottom + 12);
        if (topbar) top = Math.max(top, topbar.bottom + 12);
        if (legend && legend.top < H) bottom = Math.min(bottom, legend.top - 12);
        if (W >= 600) left = 74;
        if (right - left < 220) { left = margin; right = Math.max(left + 220, W - margin); }
        if (bottom - top < 80) { top = Math.min(top, H - 120); bottom = Math.max(top + 60, bottom); }
        return { left, top, right, bottom, centerX: (left + right) / 2, centerY: (top + bottom) / 2 };
    }
    function anchorPoint(anchor) {
        const bounds = viewBounds();
        const x = Number(anchor?.x), y = Number(anchor?.y);
        return { x: Number.isFinite(x) ? x : bounds.centerX, y: Number.isFinite(y) ? y : bounds.centerY };
    }
    function emitFollowChange() { window.dispatchEvent(new CustomEvent('city-follow-change', { detail: { following: followId !== null, id: followId } })); }
    function stopFollow() {
        const changed = followId !== null;
        followId = null; followAnchor = null;
        cameraTween = null;
        if (changed) emitFollowChange();
    }
    function targetZoom() { return Math.max(.8, Math.min(1.08, scale)); }
    function advanceCamera(dt) {
        const id = followId !== null ? followId : cameraTween?.id;
        if (id === null || id === undefined) return;
        const p = positions.find(q => q.v.id === id);
        if (!p) return;
        const follow = followId !== null;
        const a = anchorPoint(follow ? followAnchor : cameraTween?.anchor);
        const targetScale = follow ? followZoom : (cameraTween?.scale || scale);
        const rate = 1 - Math.exp(-dt * (follow ? 6.5 : 8));
        const nextScale = scale + (targetScale - scale) * rate;
        const tx = a.x - p.x * nextScale, ty = a.y - p.y * nextScale;
        scale = nextScale;
        offset.x += (tx - offset.x) * rate;
        offset.y += (ty - offset.y) * rate;
        if (!follow && Math.abs(tx - offset.x) < .25 && Math.abs(ty - offset.y) < .25 && Math.abs(targetScale - scale) < .001) cameraTween = null;
    }
    let clock = null;
    function position(v) { return { ...map.position(v.id, elapsed), v }; }
    function car(p) {
        const v = p.v, color = v.leaked ? '#d77669' : v.role === 'owner' ? '#e5a25d' : '#5e9993'; const active = v.id === selected, len = v.id % 7 === 0 ? 18 : 14, w = v.id % 7 === 0 ? 7 : 6;
        ctx.save(); ctx.translate(p.x, p.y); if (active) { ctx.strokeStyle = '#fffde6'; ctx.lineWidth = 2 / scale; ctx.beginPath(); ctx.arc(0, 0, 21 + Math.sin(elapsed * 2) * 2, 0, Math.PI * 2); ctx.stroke(); ctx.strokeStyle = color; ctx.lineWidth = 1.5 / scale; ctx.beginPath(); ctx.arc(0, 0, 27, 0, Math.PI * 2); ctx.stroke(); }
        ctx.rotate(p.angle); ctx.globalAlpha = v.registered ? 1 : .65; ctx.fillStyle = '#21352644'; ctx.fillRect(-len / 2 + 3, -w / 2 + 4, len, w); ctx.fillStyle = '#324436'; ctx.fillRect(-len / 2 + 3, -w / 2 - .8, len - 6, w + 1.6); ctx.fillStyle = color; ctx.beginPath(); if (ctx.roundRect) ctx.roundRect(-len / 2, -w / 2, len, w, 3); else ctx.rect(-len / 2, -w / 2, len, w); ctx.fill(); ctx.fillStyle = '#c3dbca'; ctx.fillRect(len / 2 - 7, -w / 2 + 1, 3, w - 2); ctx.fillStyle = '#446b62'; ctx.fillRect(-len / 2 + 4, -w / 2 + 1, 3, w - 2); ctx.fillStyle = '#f8eac4'; ctx.fillRect(len / 2 - 1, -w / 2 + 1, 1.8, 2); ctx.fillRect(len / 2 - 1, w / 2 - 3, 1.8, 2); ctx.fillStyle = '#c06854'; ctx.fillRect(-len / 2, -w / 2 + 1, 1.5, 2); ctx.fillRect(-len / 2, w / 2 - 3, 1.5, 2); ctx.restore();
        if (active) { ctx.font = `600 ${11 / scale}px ui-monospace,monospace`; let label = v.number, ww = ctx.measureText(label).width + 16 / scale; ctx.fillStyle = '#294434'; ctx.fillRect(p.x - ww / 2, p.y - 43 / scale, ww, 21 / scale); ctx.fillStyle = '#f8f7e8'; ctx.textAlign = 'center'; ctx.fillText(label, p.x, p.y - 29 / scale); }
    }
    function cockpitFrame(active, dt) {
        if (!window.Cockpit?.frame) return;
        if (!active) { window.Cockpit.frame(null); return; }
        window.Cockpit.frame({ id: active.v.id, x: offset.x + active.x * scale, y: offset.y + active.y * scale, worldX: active.x, worldY: active.y, angle: active.angle, elapsed, paused, following: followId !== null, dt });
    }
    function frame(t) {
        const dt = Math.min((t - last) / 1000, .1); last = t; if (clock) { const age = (performance.now() - clock.received) / 1000; paused = clock.paused; elapsed = clock.elapsed + (paused ? 0 : Math.min(age, 5)); if(age>5) document.getElementById('vehicle-motion').textContent='等待 B 时钟同步'; }
        positions = mapState === 'ready' ? vehicles.map(position) : [];
        advanceCamera(dt);
        ctx.clearRect(0, 0, W, H);
        // Draw the supplied map once, at its native aspect ratio. No tiled copies.
        ctx.fillStyle = '#b8c5a8'; ctx.fillRect(0, 0, W, H);
        ctx.save(); ctx.translate(offset.x, offset.y); ctx.scale(scale, scale);
        ctx.drawImage(staticMap, 0, 0);
        for (let p of positions) if (filters[p.v.leaked ? 'leaked' : p.v.role] && p.v.id !== selected) car(p);
        let active = positions.find(p => p.v.id === selected); if (active) car(active); ctx.restore(); cockpitFrame(active, dt); canvas.dataset.simulationTime=elapsed.toFixed(3); canvas.dataset.selectedPosition=active?JSON.stringify({id:active.v.id,x:active.x,y:active.y,angle:active.angle}):"";
        requestAnimationFrame(frame);
    }
    function toWorld(x, y) { return { x: (x - offset.x) / scale, y: (y - offset.y) / scale }; } function zoom(amount, cx = viewBounds().centerX, cy = viewBounds().centerY) { stopFollow(); let p = toWorld(cx, cy); scale = Math.max(.08, Math.min(3, scale * amount)); offset = { x: cx - p.x * scale, y: cy - p.y * scale }; }
    canvas.addEventListener('wheel', e => { e.preventDefault(); stopFollow(); zoom(e.deltaY < 0 ? 1.1 : .9, e.clientX, e.clientY); }, { passive: false }); canvas.addEventListener('pointerdown', e => { stopFollow(); drag = { x: e.clientX, y: e.clientY, ox: offset.x, oy: offset.y, moved: false }; canvas.setPointerCapture(e.pointerId); }); canvas.addEventListener('pointermove', e => { let p = toWorld(e.clientX, e.clientY); document.getElementById('coord-x').textContent = Math.round(p.x).toString().padStart(3, '0'); document.getElementById('coord-y').textContent = Math.round(p.y).toString().padStart(3, '0'); if (drag) { if (Math.hypot(e.clientX - drag.x, e.clientY - drag.y) > 4) drag.moved = true; offset.x = drag.ox + e.clientX - drag.x; offset.y = drag.oy + e.clientY - drag.y; } }); canvas.addEventListener('pointerup', e => { if (drag && !drag.moved) { let p = toWorld(e.clientX, e.clientY), near = positions.map(q => ({ q, d: Math.hypot(p.x - q.x, p.y - q.y) })).filter(x => filters[x.q.v.leaked ? 'leaked' : x.q.v.role]).sort((a, b) => a.d - b.d)[0]; if (near && near.d < 18 / scale && window.selectVehicle) window.selectVehicle(near.q.v.id); } drag = null; });
    document.getElementById('zoom-in').onclick = () => zoom(1.2); document.getElementById('zoom-out').onclick = () => zoom(.8); document.getElementById('recenter').onclick = reset; document.getElementById('pause').onclick = () => { if(window.setCityPaused) window.setCityPaused(!paused); }; document.getElementById('night').onclick = e => { night = !night; e.currentTarget.textContent = night ? '☀' : '☾'; renderStatic(); }; document.querySelectorAll('[data-filter]').forEach(b => b.onclick = () => { let f = b.dataset.filter; filters[f] = !filters[f]; b.classList.toggle('active', filters[f]); });
    window.City = {
        sync(value, rtt=0) { clock={elapsed:Number(value.elapsed)+(value.paused?0:Math.min(rtt,1000)/2000),paused:!!value.paused,received:performance.now()}; paused=clock.paused; document.getElementById('pause').textContent=paused?'▷':'Ⅱ'; document.getElementById('vehicle-motion').textContent=paused?'全城已暂停':'B 时钟同步'; },
        snapshot() { return {elapsed,paused,mapState,world,routeCount:map.routes.length,camera:{scale,offset:{...offset}},positions:positions.map(p=>({id:p.v.id,x:p.x,y:p.y,angle:p.angle,route:p.route}))}; },
        update(v) { vehicles = Array.isArray(v) ? v : []; if (followId !== null && !vehicles.some(x => x.id === followId)) stopFollow(); },
        select(id) { const n = Number(id); if (!Number.isFinite(n)) return; if (selected !== n && followId !== null) stopFollow(); selected = n; },
        follow(id, anchor) { const n = Number(id); if (!Number.isFinite(n)) return; selected = n; followId = n; followAnchor = anchor && Number.isFinite(Number(anchor.x)) && Number.isFinite(Number(anchor.y)) ? { x: Number(anchor.x), y: Number(anchor.y) } : null; followZoom = targetZoom(); cameraTween = null; emitFollowChange(); },
        stopFollow() { stopFollow(); },
        focus(id) { const n = Number(id); if (!Number.isFinite(n)) return; selected = n; if (followId !== null) { followId = n; followZoom = targetZoom(); return; } const p = positions.find(q => q.v.id === n); if (p) { cameraTween = { id: n, anchor: anchorPoint(), scale: targetZoom() }; } },
        isFollowing() { return followId !== null; }
    }; window.addEventListener('resize', resize); renderStatic(); resize(); requestAnimationFrame(frame);
})();
