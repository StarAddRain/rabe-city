/* Static resources use the real Java server. API responses below are isolated UI fixtures. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const base = process.env.CITY_TEST_URL || 'http://127.0.0.1:8891';
const output = process.env.CITY_TEST_OUTPUT || 'build/map-check';
fs.mkdirSync(output,{recursive:true});
const vehicles = Array.from({length:16}, (_,id) => ({id,number:`V-${String(id+1).padStart(3,'0')}`,role:id%2?'user':'owner',attributes:[0,1,2],registered:true}));
(async () => {
 const imageResponse = await fetch(base+'/assets/pastel-canal-city-map.png');
 assert.equal(imageResponse.status,200); assert.equal(imageResponse.headers.get('content-type'),'image/png');
 assert.equal(imageResponse.headers.get('x-content-type-options'),'nosniff');
 assert.deepEqual(Buffer.from(await imageResponse.arrayBuffer()),fs.readFileSync('web/assets/pastel-canal-city-map.png'));
 const browser = await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE ? {executablePath:process.env.BROWSER_EXECUTABLE} : {channel:'chrome'})});
 const checks=['PNG served byte-for-byte with image/png and nosniff'];
 const errors=[]; const snapshots=[];
 try {
  for(const role of ['owner','cloud','user','curator']) {
   const context = await browser.newContext({viewport:{width:1440,height:1000},deviceScaleFactor:1});
   const page = await context.newPage(); page.on('pageerror',e=>errors.push(e.message));
   let motion={elapsed:123.456,paused:true}, stamp=Date.now();
   const clockValue=()=>({...motion,elapsed:motion.elapsed+(motion.paused?0:(Date.now()-stamp)/1000)});
   await page.route('**/api/**',async route=> {
    const path=new URL(route.request().url()).pathname;
    let json;
    if(path==='/api/state') json={role,csrf:'fixture-only',ready:true,localReady:true,enrolled:16,ctr:16,capacity:128,phase:'地图验证 · 16 辆测试车辆',nodes:{owner:true,cloud:true,user:true,curator:true},vehicles,messages:[],samples:[],events:[],jobs:[]};
    else if(path==='/api/motion/set'){motion={...clockValue(),...route.request().postDataJSON()};stamp=Date.now();json=clockValue();}
    else if(path==='/api/motion')json=clockValue();
    else if(path==='/api/inbox')json={vehicle:Number(new URL(route.request().url()).searchParams.get('vehicle')),inbox:[]};
    else throw Error('Unexpected test request '+path);
    await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(json)});
   });
   await page.goto(base); await page.waitForFunction(()=>City.snapshot().mapState==='ready'&&City.snapshot().positions.length===16);
   await page.locator('#recenter').click();
   const snapshot = await page.evaluate(()=>City.snapshot()); snapshots.push(snapshot.positions);
   assert.equal(snapshot.routeCount,12);assert.equal(snapshot.world.w,1672);assert.equal(snapshot.world.h,941);
   const {scale,offset}=snapshot.camera;
   assert.ok(offset.x>=0&&offset.y>=0&&offset.x+1672*scale<=1440&&offset.y+941*scale<=1000);
   if(role==='owner')await page.screenshot({path:output+'/overview.png'});
   checks.push(role+': native map aspect, full view and 16 registered vehicles');
   if(role==='owner'||role==='user'){
    const id=role==='owner'?0:1; const p=snapshot.positions.find(p=>p.id===id);
    await page.mouse.click(offset.x+p.x*scale,offset.y+p.y*scale);
    await page.waitForFunction(id=>Cockpit.snapshot().open&&Cockpit.snapshot().vehicleId===id,id);
    assert.equal(await page.locator('.cockpit-view:not(.hidden)').count(),1);
    assert.equal(await page.evaluate(()=>City.isFollowing()),true);
    const gender=await page.evaluate(()=>Cockpit.snapshot().profile.gender);
    await page.locator('#pause').click();
    await page.waitForFunction(()=>!City.snapshot().paused);
    const initial=await page.evaluate(()=>City.snapshot().positions);
    await page.waitForTimeout(1000);
    const moving=await page.evaluate(()=>City.snapshot().positions);
    assert.ok(moving.some((p,i)=>Math.hypot(p.x-initial[i].x,p.y-initial[i].y)>10));
    const nextId=id+2;
    await page.selectOption('#vehicle-select',String(nextId));
    await page.waitForFunction(id=>Cockpit.snapshot().vehicleId===id,nextId);
    assert.equal(await page.locator('.cockpit-view:not(.hidden)').count(),1);
    await page.selectOption('#vehicle-select',String(id));
    assert.equal(await page.evaluate(()=>Cockpit.snapshot().profile.gender),gender);
    await page.locator('#pause').click();
    // sync() receives the pause before the next animation frame renders its time.
    // Compare two rendered positions at the server's frozen timestamp.
    await page.waitForFunction(expected=>{
      const snapshot=City.snapshot();
      return snapshot.paused && snapshot.elapsed===expected;
    },motion.elapsed);
    const stopped=await page.evaluate(()=>City.snapshot().positions);
    await page.waitForTimeout(300);
    assert.deepEqual(await page.evaluate(()=>City.snapshot().positions),stopped);
    if(role==='owner')await page.screenshot({path:output+'/cockpit.png'});
    await page.locator('#cockpit-close').click();
    await page.locator('#recenter').click();
    await page.locator('#night').click();assert.equal(await page.evaluate(()=>City.snapshot().mapState),'ready');
    await page.locator('#night').click();
    await page.setViewportSize({width:390,height:844});
    await page.locator('#recenter').click();
    const mobile=await page.evaluate(()=>City.snapshot());
    assert.ok(mobile.camera.offset.x>=0&&mobile.camera.offset.y>=0);
    assert.ok(mobile.camera.offset.x+mobile.world.w*mobile.camera.scale<=390.01);
    assert.ok(mobile.camera.offset.y+mobile.world.h*mobile.camera.scale<=450.01);
    if(role==='owner')await page.screenshot({path:output+'/mobile.png'});
    checks.push(role+': click, single cabin, stable driver, movement, pause, day/night and mobile fit');
   }
   await context.close();
  }
  snapshots.forEach(s=>assert.deepEqual(s,snapshots[0]));checks.push('identical vehicle coordinates in all four roles at shared paused time');
  assert.deepEqual(errors,[]); checks.push('no JavaScript page errors');
  fs.writeFileSync(output+'/browser-report.json',JSON.stringify({checks,errors,fixture:'16 synthetic API vehicles in isolated browser contexts; no registrations persisted'},null,2));
  console.log(checks.join('\n'));
 } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
