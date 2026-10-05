/* Stable per-vehicle characters. One cabin is shared by all local vehicles. */
(() => {
    'use strict';
    const $ = id => document.getElementById(id);
    const view = $('cockpit-view'), tether = $('cockpit-tether');
    let role = null, vehicles = [], vehicle = null, opened = false, following = false;
    let lastLayout = null, layoutDirty = true;
    let lastDirection = '', motionStamp = 0;

    // Versioned seed + slot: independent of browser storage, node port, deployment or polling.
    // Do not change this seed: it is the permanent assignment rule for existing vehicles.
    function profile(id) {
        let h = 2166136261;
        for (const c of 'veil-city-driver-v1:' + String(id)) h = Math.imul(h ^ c.charCodeAt(0), 16777619);
        h ^= h >>> 16; h = Math.imul(h, 0x85ebca6b); h ^= h >>> 13;
        return { gender: (h >>> 0) % 2 ? 'female' : 'male' };
    }
    function eligible(v) { return !!v && (role === 'owner' || role === 'user') && v.role === role; }
    function status(id, text, phase = 'idle') {
        if (!opened || vehicle?.id !== id) return;
        $('cockpit-status').textContent = text;
        const share = $('cockpit-map-share');
        if (share) share.textContent = phase === 'working' ? '数据共享链路 · 处理中' : phase === 'success' ? '数据共享链路 · 已同步' : phase === 'error' ? '数据共享链路 · 需检查' : role === 'owner' ? 'A → B · 等待发送' : 'B → C · 等待接收';
        view.dataset.phase = phase;
    }
    function idle() {
        if (!vehicle) return;
        status(vehicle.id, role === 'owner' ? '等待发送数据' : '等待接收数据');
    }
    function close() {
        opened = false; following = false;
        view.classList.add('hidden'); tether.classList.add('hidden');
        document.body.classList.remove('cockpit-active');
        window.City.stopFollow();
    }
    function layout() {
        if (!layoutDirty && lastLayout) return lastLayout;
        const mobile = innerWidth <= 600;
        const panel = document.querySelector('.panel').getBoundingClientRect();
        const stats = document.querySelector('.stats').getBoundingClientRect();
        const dock = document.querySelector('.bottom-dock').getBoundingClientRect();
        const left = mobile ? 58 : 80;
        const right = mobile ? innerWidth - 12 : panel.left - 20;
        const top = mobile ? 99 : Math.max(116, stats.bottom + 15);
        const bottom = mobile ? 418 : Math.min(innerHeight - 18, dock.top - 12);
        const chrome = innerWidth <= 600 ? 59 : innerWidth <= 900 ? 64 : 75;
        const maxWidth = Math.max(230, right - left);
        const width = Math.min(760, maxWidth, Math.max(230, (bottom - top - 62 - chrome) * 16 / 9));
        const height = width * 9 / 16 + chrome + 4;
        lastLayout = { left, right, top, bottom, width, height,
            anchor: { x: left + (right - left) * .48, y: Math.min(bottom - 12, top + height + 45) } };
        view.style.width = width + 'px'; layoutDirty = false;
        return lastLayout;
    }
    function follow() {
        if (!opened || !vehicle) return;
        layoutDirty = true;
        window.City.follow(vehicle.id, layout().anchor);
        following = true;
        $('cockpit-follow').textContent = '⌖ 跟随中';
        $('cockpit-follow').setAttribute('aria-pressed', 'true');
    }
    function select(v, open = false) {
        const changed = vehicle?.id !== v?.id;
        vehicle = v || null;
        if (!eligible(v)) { close(); return; }
        // Polling refreshes metadata but never reopens a cabin the user closed.
        if (!open && !opened) return;
        if (changed || !opened) {
            const driver = profile(v.id);
            view.dataset.gender = driver.gender;
            view.dataset.vehicleId = String(v.id);
            view.dataset.role = role;
            $('cockpit-number').textContent = v.number;
            $('cockpit-driver').textContent = driver.gender === 'female' ? '女驾驶员' : '男驾驶员';
            $('cockpit-art').src = '/assets/cabin-' + driver.gender + '.svg';
            $('cockpit-art').alt = (driver.gender === 'female' ? '女性' : '男性') + '驾驶员坐在左驾驶位，使用一块中央车机屏';
            $('cockpit-screen-role').textContent = '车机导航 · 数据共享';
            $('cockpit-map-share').textContent = role === 'owner' ? 'A → B · 等待发送' : 'B → C · 等待接收';
            $('cockpit-data-hint').textContent = role === 'owner' ? '车机地图 · 右侧确认后发送' : '车机地图 · 右侧选择密文并解密';
        }
        const wasOpen = opened; opened = true;
        view.classList.remove('hidden'); tether.classList.remove('hidden');
        document.body.classList.add('cockpit-active');
        if (changed || !wasOpen) idle();
        if (open || changed || !wasOpen) {
            follow();
            if (open && innerWidth <= 600) window.scrollTo({ top: 0, behavior: 'smooth' });
        }
    }
    function configure(localRole, items) {
        role = localRole; vehicles = items || [];
        if (vehicle) {
            const current = vehicles.find(v => v.id === vehicle.id);
            if (!eligible(current)) { vehicle = current || null; close(); }
        }
    }
    function frame(detail) {
        if (!opened || !detail || vehicle?.id !== detail.id) return;
        const box = layout();
        const x = Math.max(box.left, Math.min(box.right - box.width, detail.x - box.width * .4));
        const y = Math.max(box.top, Math.min(box.bottom - box.height - 45, detail.y - box.height - 49));
        view.style.transform = 'translate3d(' + x.toFixed(1) + 'px,' + y.toFixed(1) + 'px,0)';
        const startX = Math.max(x + 24, Math.min(x + box.width - 24, detail.x));
        const startY = y + box.height;
        $('cockpit-cone').setAttribute('d', `M${startX - 34},${startY} L${detail.x},${detail.y} L${startX + 34},${startY} Z`);
        $('cockpit-line').setAttribute('d', `M${startX},${startY} L${detail.x},${detail.y}`);
        $('cockpit-anchor').setAttribute('cx', detail.x);
        $('cockpit-anchor').setAttribute('cy', detail.y);
        following = detail.following;
        view.dataset.paused = String(detail.paused);
        // A gentle change of light in the windshield follows simulation time and stops with traffic.
        $('cockpit-road-flow').style.backgroundPosition = (160 - detail.elapsed * 8 % 220) + '% 0';
        if (detail.elapsed - motionStamp > .25 || detail.paused) {
            const angle = detail.angle;
            const heading = Math.abs(Math.sin(angle)) > .5 ? (Math.sin(angle) > 0 ? '向南' : '向北') : (Math.cos(angle) > 0 ? '向东' : '向西');
            const text = detail.paused ? '车流已暂停' : heading + '行驶 · ' + Math.round(detail.worldX) + ' / ' + Math.round(detail.worldY);
            if (text !== lastDirection) { $('cockpit-motion').textContent = text; lastDirection = text; }
            motionStamp = detail.elapsed;
        }
    }
    $('cockpit-close').onclick = close;
    $('cockpit-follow').onclick = follow;
    window.addEventListener('city-follow-change', event => {
        following = event.detail.following;
        $('cockpit-follow').textContent = following ? '⌖ 跟随中' : '⌖ 继续跟随';
        $('cockpit-follow').setAttribute('aria-pressed', String(following));
    });
    window.addEventListener('resize', () => { layoutDirty = true; if (opened && following) follow(); });
    window.addEventListener('keydown', event => { if (event.key === 'Escape' && opened) close(); });
    window.Cockpit = { configure, select, close, frame, status, profile,
        snapshot: () => ({ vehicleId: vehicle?.id ?? null, profile: vehicle ? profile(vehicle.id) : null, open: opened, following }) };
})();
