// Browser behavior/layout checks using a clearly labelled simulated phone bridge.
const { chromium } = require('playwright');
const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');
const assert = require('node:assert/strict');

(async () => {
  const root = path.resolve(__dirname, '..');
  const assets = path.join(root, 'app/src/main/assets/light');
  const qa = path.join(root, 'tmp/light-console'); await fs.mkdir(qa, { recursive: true });
  const commands = [], origin = Date.now();
  const state = { hosting: true, simulated: true, note: 'SIMULATED MESH / browser QA', readyCount: 16,
    show: { showId: 'simulated-show', hostId: 'QA-host', startAt: 100000, durationMs: 120000, preset: 'RAINBOW' }, programs: [],
    participants: Array.from({length:16}, (_, i) => ({ nodeId: `QA-phone-${i+1}`, seat: {group: i%4, x:(i%4)*1000/3,y:Math.floor(i/4)*1000/3}, ageMs:0,roundTripMs:120+i*3,revision:0 })) };
  const sampleScene = {effect:'WAVE',primary:0x35dcc3,secondary:0x18224c,bpm:100,spreadMs:2400,brightness:85,groups:15,direction:1};
  const server = http.createServer(async (request, response) => {
    try {
      response.setHeader('Content-Security-Policy', "default-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'");
      if(request.url === '/api/state') {response.setHeader('Content-Type','application/json');response.end(JSON.stringify({...state,now:110000+Date.now()-origin}));return;}
      if(request.url === '/api/command') {
        let body='';for await(const chunk of request)body+=chunk;const command=JSON.parse(body);commands.push(command);
        if(command.action==='scene'||command.action==='timeline') {state.programs.push({revision:state.programs.length+1,effectiveAt:110000+Date.now()-origin-1500,cues:command.cues||[{atMs:0,scene:command.scene}]});}
        if(command.action==='seat'){const phone=state.participants.find(p=>p.nodeId===command.nodeId);if(phone)phone.seat=command.seat;}
        if(command.action==='stop')state.hosting=false;
        response.setHeader('Content-Type','application/json');response.end('{"ok":true}');return;
      }
      const routes = {'/':['conductor.html','text/html'],'/conductor.css':['conductor.css','text/css'],'/conductor.js':['conductor.js','text/javascript']};
      const asset=routes[request.url];if(!asset){response.writeHead(404);response.end();return;}
      response.setHeader('Content-Type',asset[1]);response.end(await fs.readFile(path.join(assets,asset[0])));
    }catch(error){response.writeHead(500);response.end(error.message);}
  });
  await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
  const url=`http://127.0.0.1:${server.address().port}/#token=simulated-qa`;
  const browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{})});
  const failures=[];
  try {
    const page=await browser.newPage({viewport:{width:1440,height:1000}});
    page.on('pageerror',error=>failures.push(error.message));
    await page.goto(url);await page.getByText('Simulated mesh · browser QA',{exact:true}).waitFor();
    assert.equal(new URL(page.url()).hash,'','pairing token removed from address bar');
    await page.locator('[data-effect="SPIRAL"]').click();await page.locator('#fire').click();
    await page.waitForFunction(()=>document.querySelector('#notice').textContent==='Cue queued on the host timeline.');
    assert.equal(commands.at(-1).scene.effect,'SPIRAL');
    await page.locator('#add-cue').click();await page.locator('#cue-at').fill('15');await page.locator('[data-effect="RIPPLE"]').click();await page.locator('#add-cue').click();
    assert.equal(await page.locator('#timeline time').count(),2,await page.locator('#notice').textContent());
    await page.locator('#arm').click();await page.waitForTimeout(250);
    assert.equal(commands.at(-1).action,'timeline');assert.equal(commands.at(-1).cues.length,2);
    await page.getByRole('button',{name:/QA-phone-1 G1/}).click();await page.locator('#seat-group').selectOption({value:'2'});
    assert.equal(await page.locator('#seat-group').inputValue(),'2');
    await page.locator('#seat-form button').click();await page.waitForTimeout(250);
    assert.equal(commands.at(-1).action,'seat');assert.equal(commands.at(-1).seat.group,2,JSON.stringify(commands.at(-1)));
    await page.locator('#canvas').scrollIntoViewIfNeeded();
    const canvas=await page.locator('#canvas').boundingBox();const pointerX=canvas.x+70,pointerY=canvas.y+48;
    await page.mouse.move(pointerX,pointerY);await page.mouse.down();await page.mouse.move(pointerX+150,pointerY+90,{steps:5});await page.mouse.up();await page.waitForTimeout(250);
    assert.equal(commands.at(-1).action,'seat');assert.ok(commands.at(-1).seat.x>0,'drag assigns new position');
    await page.locator('#save').click();
    const badPlan={format:1,durationMs:120000,cues:[{atMs:0,scene:{...sampleScene,bpm:0}}]};
    await page.locator('#load').setInputFiles({name:'invalid-plan.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(badPlan))});
    await page.waitForFunction(()=>document.querySelector('#notice').textContent==='Invalid bpm.');
    await page.locator('#load').setInputFiles({name:'good-plan.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify({format:1,durationMs:120000,cues:[{atMs:0,scene:sampleScene}]}))});
    await page.waitForFunction(()=>document.querySelector('#notice').textContent==='Plan loaded locally. Arm it when you are ready.');
    await page.locator('h1').click();await page.keyboard.press('b');await page.waitForTimeout(250);
    assert.equal(commands.at(-1).action,'blackout','keyboard blackout dispatches to the bridge');
    await page.locator('#restore').click();await page.waitForTimeout(250);assert.equal(commands.at(-1).action,'restore');
    await page.locator('#finale').click();await page.waitForTimeout(250);assert.equal(commands.at(-1).action,'finale');
    await page.locator('#bpm').focus();await page.keyboard.press('Home');
    assert.equal(Number(await page.locator('#bpm').inputValue()),30);
    await page.locator('#tap').click();await page.waitForTimeout(500);await page.locator('#tap').click();
    assert.ok(Number(await page.locator('#bpm').inputValue())>30,'tap tempo updates the scene tempo');
    // Leave the page in a representative operating state for the single batched visual inspection.
    await page.locator('#notice').evaluate(el=>el.hidden=true);await page.locator('#seat-form').evaluate(el=>el.hidden=true);
    await page.screenshot({path:path.join(qa,'desktop.png'),fullPage:true});
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'desktop has no horizontal overflow');
    await page.setViewportSize({width:390,height:844});await page.screenshot({path:path.join(qa,'mobile.png'),fullPage:true});
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'narrow screen has no horizontal overflow');
    assert.deepEqual(failures,[],'no browser runtime errors');
    await fs.writeFile(path.join(qa,'report.json'),JSON.stringify({simulation:true,checks:['pairing token hygiene','scene dispatch','timeline dispatch','group assignment','drag placement','cue plan save/import validation','keyboard blackout / restore / finale dispatch','tap tempo','desktop/mobile overflow','browser runtime errors'],commands:commands.length,errors:failures},null,2));
    console.log(JSON.stringify({checks:10,commands:commands.length,screenshots:qa,errors:failures}));
  } finally {await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
