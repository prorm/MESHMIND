'use strict';
const $ = id => document.getElementById(id);
const EFFECTS = [['SOLID','Color wash','M3 8h14M3 12h14'],['BREATH','Breathing glow','M2 10q4-10 8 0t8 0'],['WAVE','Traveling wave','M1 10q3-8 6 0t6 0t6 0'],['RIPPLE','Center ripple','M10 4a6 6 0 1 0 .01 0M10 8a2 2 0 1 0 .01 0'],['SPIRAL','Spiral vortex','M10 10c-4-5 5-7 6-1s-7 11-12 4 0-15 8-12'],['CHECKER','Checker dance','M3 3h6v6H3zM11 11h6v6h-6z'],['CONSTELLATION','Constellation','M3 5h.1M13 3h.1M8 12h.1M17 15h.1M2 17h.1'],['RAINBOW','Rainbow sweep','M2 16a8 8 0 0 1 16 0M5 16a5 5 0 0 1 10 0M8 16a2 2 0 0 1 4 0']];
let token = new URLSearchParams(location.hash.slice(1)).get('token') || '';
// Pairing stays in memory; don't leave the token in history, links, or a saved cue plan.
if (token) history.replaceState(null, '', location.pathname);
let selectedEffect = 'WAVE', snapshot = null, receivedAt = performance.now(), online = false;
let cues = [], selectedPhone = null, taps = [], noticeTimer, commandBusy = false, pointerDraft = null;
const GROUP_COLORS = ['#7fd1c7','#9db8ff','#ffb785','#d4a7ee'];
const canvas = $('canvas'), context = canvas.getContext('2d');
const virtualPhones = Array.from({length:64}, (_,i) => ({nodeId:`preview-${i}`,seat:{group:i%4,x:(i%8)*1000/7,y:Math.floor(i/8)*1000/7}}));
function notify(message) { $('notice').textContent=message; $('notice').hidden=false; clearTimeout(noticeTimer); noticeTimer=setTimeout(() => $('notice').hidden=true, 5000); }
function scene() {
  const groups=[...document.querySelectorAll('.groups input:checked')].reduce((mask,input)=>mask | (1 << Number(input.value)),0);
  if (!groups) throw new Error('Choose at least one group.');
  return {effect:selectedEffect,primary:parseInt($('primary').value.slice(1),16),secondary:parseInt($('secondary').value.slice(1),16),bpm:Number($('bpm').value),spreadMs:Number($('spread').value),brightness:Number($('brightness').value),groups,direction:$('reverse').checked?-1:1};
}
function validScene(s) {
  const fields={primary:[0,0xffffff],secondary:[0,0xffffff],bpm:[30,180],spreadMs:[0,8000],brightness:[0,100],groups:[1,15]};
  if (!s || !EFFECTS.some(e=>e[0]===s.effect) || ![1,-1].includes(s.direction)) throw new Error('Invalid scene.');
  Object.entries(fields).forEach(([key,[min,max]])=>{if(!Number.isInteger(s[key])||s[key]<min||s[key]>max)throw new Error(`Invalid ${key}.`);});
  return s;
}
function labels(){ $('bpm-label').textContent=`${$('bpm').value} BPM`; $('spread-label').textContent=`${(Number($('spread').value)/1000).toFixed(1)} s`; $('brightness-label').textContent=`${$('brightness').value}%`; }
EFFECTS.forEach(([id,title,path]) => {
  const button=document.createElement('button');button.type='button';button.dataset.effect=id;button.setAttribute('aria-pressed',String(id===selectedEffect));
  // Only authored, static icon paths enter this markup; device names always use textContent.
  button.innerHTML=`<svg viewBox="0 0 20 20" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" aria-hidden="true"><path d="${path}"/></svg>`;
  button.append(document.createTextNode(title));button.onclick=()=>{selectedEffect=id; document.querySelectorAll('[data-effect]').forEach(b=>b.setAttribute('aria-pressed',String(b===button)));};$('effects').append(button);
});
['bpm','spread','brightness'].forEach(id=>$(id).addEventListener('input',labels));
$('tap').onclick=()=>{const now=performance.now();if(taps.length && now-taps.at(-1)>2500)taps=[];taps.push(now);taps=taps.slice(-6);if(taps.length>1){$('bpm').value=String(Math.max(30,Math.min(180,Math.round(60000/((taps.at(-1)-taps[0])/(taps.length-1))))));labels();}};
function hostNow() { return snapshot ? snapshot.now+performance.now()-receivedAt : performance.now(); }
function activeProgram(time=hostNow()){return (snapshot?.programs||[]).filter(p=>p.effectiveAt<=time).sort((a,b)=>b.revision-a.revision)[0];}
function pending(){return (snapshot?.programs||[]).some(p=>p.effectiveAt>hostNow());}
function controls(){
  const host=online && snapshot?.hosting, queued=pending();
  $('start').disabled=!online||snapshot?.hosting||commandBusy;
  ['stop','blackout','grid'].forEach(id=>$(id).disabled=!host||commandBusy);
  ['fire','restore','finale','arm'].forEach(id=>$(id).disabled=!host||queued||commandBusy||(id==='arm'&&!cues.length));
  $('fire').firstChild.textContent=queued?'Cue queued… ': 'Fire on the next beat ';
}
async function poll(){
  try {
    if(!token) throw new Error('Open the pairing URL shown on the host phone.');
    const response=await fetch('/api/state',{headers:{Authorization:`Bearer ${token}`},cache:'no-store',signal:AbortSignal.timeout(2500)});
    if(!response.ok) throw new Error(response.status===401?'Pairing expired. Enable laptop control again.':'Host phone unavailable.');
    snapshot=await response.json();receivedAt=performance.now();online=true;
    $('connection').textContent=snapshot.simulated?'Simulated mesh · browser QA':'Host phone connected';
    document.querySelector('.connection').classList.add('online');updateRoster();
  } catch(error){online=false;$('connection').textContent=error.message;document.querySelector('.connection').classList.remove('online');}
  controls();
}
async function send(action,fields={}){
  if(!online||commandBusy)return;commandBusy=true;controls();
  try{
    const response=await fetch('/api/command',{method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:JSON.stringify({action,...fields}),signal:AbortSignal.timeout(5000)});
    const result=await response.json();if(!response.ok||!result.ok)throw new Error(result.error||'Command refused.');
    notify(['scene','timeline','finale','restore'].includes(action)?'Cue queued on the host timeline.':action==='blackout'?'Blackout queued with a short distribution lead.':action==='seat'?'Position assigned.':action==='grid'?'Virtual grid assigned.':action==='stop'?'Stop sent to reachable participants.':'Show scheduled. Phones can now join.');
    await poll();
  }catch(error){notify(error.message);}finally{commandBusy=false;controls();}
}
function safely(action){try{action();}catch(error){notify(error.message);}}
$('start').onclick=()=>safely(()=>send('host',{durationMs:Number($('duration').value),scene:scene()}));
$('stop').onclick=()=>send('stop');$('fire').onclick=()=>safely(()=>send('scene',{scene:scene()}));
$('blackout').onclick=()=>send('blackout');$('restore').onclick=()=>send('restore');
$('finale').onclick=()=>send('finale',{bpm:Number($('bpm').value)});$('grid').onclick=()=>send('grid');
document.addEventListener('keydown',event=>{
  if(event.repeat||event.ctrlKey||event.metaKey||event.altKey||/INPUT|SELECT|TEXTAREA|BUTTON/.test(event.target.tagName))return;
  const ids={' ':'fire',b:'blackout',r:'restore',f:'finale',g:'grid'};const id=ids[event.key.toLowerCase()];
  if(id&&!$(id).disabled){event.preventDefault();$(id).click();}
});
function renderTimeline(){
  $('timeline').replaceChildren();
  if(!cues.length){const li=document.createElement('li');li.textContent='Add a scene at 0 seconds to begin your sequence.';li.style.color='var(--muted)';$('timeline').append(li);}
  cues.forEach((cue,index)=>{
    const li=document.createElement('li'),time=document.createElement('time'),swatch=document.createElement('span'),description=document.createElement('span'),remove=document.createElement('button');
    time.textContent=`${cue.atMs/1000} s`;swatch.className='cue-swatch';swatch.style.background=`#${cue.scene.primary.toString(16).padStart(6,'0')}`;
    description.className='cue-description';description.textContent=`${EFFECTS.find(e=>e[0]===cue.scene.effect)[1]} · ${cue.scene.bpm} BPM · ${cue.scene.brightness}%`;
    remove.textContent='Remove';remove.setAttribute('aria-label',`Remove cue at ${cue.atMs/1000} seconds`);remove.onclick=()=>{cues.splice(index,1);renderTimeline();};
    li.append(time,swatch,description,remove);$('timeline').append(li);
  });controls();
}
$('add-cue').onclick=()=>safely(()=>{
  const seconds=Number($('cue-at').value),duration=snapshot?.hosting?snapshot.show.durationMs:Number($('duration').value);
  if(!Number.isInteger(seconds)||seconds<0||seconds*1000>=duration)throw new Error('Cue must be inside the show duration.');
  if(cues.some(c=>c.atMs===seconds*1000))throw new Error('A cue already exists at that time. Remove it first.');
  if(cues.length>=12)throw new Error('This sequence can hold 12 cues.');
  cues.push({atMs:seconds*1000,scene:scene()});cues.sort((a,b)=>a.atMs-b.atMs);$('cue-at').value=String(Math.min(Math.floor(duration/1000)-1,seconds+15));renderTimeline();
});
$('arm').onclick=()=>safely(()=>{if(cues[0]?.atMs!==0)throw new Error('The sequence needs its first cue at 0 seconds.');send('timeline',{cues});});
$('save').onclick=()=>safely(()=>{
  if(!cues.length||cues[0].atMs!==0)throw new Error('Build a sequence beginning at 0 seconds first.');
  const url=URL.createObjectURL(new Blob([JSON.stringify({format:1,durationMs:snapshot?.hosting?snapshot.show.durationMs:Number($('duration').value),cues},null,2)],{type:'application/json'}));
  const anchor=document.createElement('a');anchor.href=url;anchor.download='meshmind-cue-plan.json';anchor.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
});
$('load').onchange=async()=>{
  const file=$('load').files[0];if(!file)return;
  try{
    if(file.size>16384)throw new Error('Cue plan exceeds 16 KB.');
    const plan=JSON.parse(await file.text());
    if(plan.format!==1||!Number.isInteger(plan.durationMs)||plan.durationMs<10000||plan.durationMs>300000||!Array.isArray(plan.cues)||plan.cues.length<1||plan.cues.length>12)throw new Error('Invalid cue plan.');
    let last=-1;const checked=plan.cues.map(c=>{if(!Number.isInteger(c.atMs)||c.atMs<=last||c.atMs>=plan.durationMs)throw new Error('Cue times must increase within the show.');last=c.atMs;return {atMs:c.atMs,scene:{...validScene(c.scene)}};});
    if(checked[0].atMs!==0)throw new Error('First cue must be at 0 seconds.');
    cues=checked;
    if(![...$('duration').options].some(o=>Number(o.value)===plan.durationMs)){const option=document.createElement('option');option.value=plan.durationMs;option.textContent=`${plan.durationMs/1000} seconds`;$('duration').append(option);}
    $('duration').value=String(plan.durationMs);renderTimeline();notify('Plan loaded locally. Arm it when you are ready.');
  }catch(error){notify(error.message);}finally{$('load').value='';}
};
function selectPhone(phone){selectedPhone=phone.nodeId;$('seat-form').hidden=false;$('selected-phone').textContent=phone.nodeId;$('seat-group').value=String(phone.seat.group);$('seat-x').value=String(phone.seat.x);$('seat-y').value=String(phone.seat.y);updateRoster();}
function updateRoster(){
  const phones=snapshot?.participants||[];$('roster-count').textContent=`${phones.length} / 64 slots`;$('ready-count').textContent=String(snapshot?.readyCount||0);$('roster-empty').hidden=phones.length>0;
  if(selectedPhone&&!phones.some(p=>p.nodeId===selectedPhone)){selectedPhone=null;$('seat-form').hidden=true;}
  $('roster').replaceChildren();phones.forEach(phone=>{
    const button=document.createElement('button'),dot=document.createElement('span'),detail=document.createElement('span');
    dot.textContent='●';dot.style.color=GROUP_COLORS[phone.seat.group];detail.textContent=`G${phone.seat.group+1} / cue ${phone.revision}`;
    button.append(dot,document.createTextNode(phone.nodeId),detail);button.setAttribute('aria-pressed',String(phone.nodeId===selectedPhone));button.onclick=()=>selectPhone(phone);$('roster').append(button);
  });
  const times=phones.map(p=>p.roundTripMs).sort((a,b)=>a-b);$('rtt').textContent=times.length?`${Math.round(times[Math.floor(times.length/2)])} ms`:'—';
}
$('seat-form').onsubmit=event=>{event.preventDefault();if(selectedPhone)send('seat',{nodeId:selectedPhone,seat:{group:Number($('seat-group').value),x:Number($('seat-x').value),y:Number($('seat-y').value)}});};
function blend(a,b,f){f=Math.max(0,Math.min(1,f));const ch=s=>Math.round(((a>>s)&255)*(1-f)+((b>>s)&255)*f);return (ch(16)<<16)|(ch(8)<<8)|ch(0);}
function hash(id){let h=0;for(let i=0;i<id.length;i++)h=(Math.imul(31,h)+id.charCodeAt(i))|0;return h>>>0;}
function rainbow(t,period){const palette=[0xff7043,0xffc857,0x72d572,0x35dcc3,0x5599ff,0xb477ef,0xef77b4],phase=(((Math.trunc(t)%period)+period)%period)/period*palette.length;return blend(palette[Math.floor(phase)%palette.length],palette[(Math.floor(phase)+1)%palette.length],phase-Math.floor(phase));}
function presetColor(show,t){
  const period=show.periodMs||8000,phase=(((Math.trunc(t)%period)+period)%period)/period;
  if(show.preset==='PULSE'){const b=.18+.82*(1-Math.cos(phase*Math.PI*2))/2;return (Math.trunc(35*b)<<16)|(Math.trunc(220*b)<<8)|Math.trunc(195*b);}
  if(show.preset==='SUNSET'){const palette=[0xffc857,0xf78465,0xdf70b0,0x7377cd,0xdf70b0,0xf78465],p=phase*palette.length;return blend(palette[Math.floor(p)],palette[(Math.floor(p)+1)%palette.length],p-Math.floor(p));}
  return rainbow(t,period);
}
function colorAt(s,t,seat,id){
  if(!(s.groups&(1<<seat.group)))return 0;
  const x=seat.x/1000,y=seat.y/1000,radius=Math.hypot(x-.5,y-.5)/Math.sqrt(.5),period=240000/s.bpm,phase=t/period*s.direction,g=seat.group/4,tau=Math.PI*2;
  let mix=1;
  switch(s.effect){
    case 'BREATH':mix=.12+.88*(.5-.5*Math.cos(phase*tau));break;
    case 'WAVE':mix=.5+.5*Math.sin(tau*(phase-x*s.spreadMs/period-g));break;
    case 'RIPPLE':mix=(.5+.5*Math.cos(tau*(phase-radius*s.spreadMs/period)))**3;break;
    case 'SPIRAL':mix=.5+.5*Math.sin(Math.atan2(y-.5,x-.5)+radius*4*Math.PI-phase*tau);break;
    case 'CHECKER':mix=.5+.5*Math.sin(phase*tau+((Math.floor(x*7)+Math.floor(y*7)+seat.group)%2)*Math.PI);break;
    case 'CONSTELLATION':mix=.08+.92*(.5+.5*Math.cos((phase+(hash(id)%1000)/1000)*tau))**8;break;
  }
  const color=s.effect==='RAINBOW'?rainbow(t*s.direction-x*s.spreadMs-g*period,Math.trunc(period)):blend(s.secondary,s.primary,mix);
  const channel=shift=>Math.round(((color>>shift)&255)*s.brightness/100);return (channel(16)<<16)|(channel(8)<<8)|channel(0);
}
function programColor(program,t,phone){const index=Math.max(0,program.cues.findLastIndex(c=>c.atMs<=t)),cue=program.cues[index],color=colorAt(cue.scene,t,phone.seat,phone.nodeId);return index?blend(colorAt(program.cues[index-1].scene,t,phone.seat,phone.nodeId),color,(t-cue.atMs)/1000):color;}
const rgba=(rgb,alpha=1)=>`rgba(${rgb>>16&255},${rgb>>8&255},${rgb&255},${alpha})`;
function geometry(){const rect=canvas.getBoundingClientRect();return {width:rect.width,height:rect.height,left:70,top:48,right:rect.width-45,bottom:rect.height-95};}
function point(seat,box){return {x:box.left+seat.x/1000*(box.right-box.left),y:box.top+seat.y/1000*(box.bottom-box.top)};}
canvas.addEventListener('pointerdown',event=>{
  if(!online||!snapshot?.hosting||!snapshot.participants.length)return;
  const rect=canvas.getBoundingClientRect(),x=event.clientX-rect.left,y=event.clientY-rect.top,box=geometry();
  const near=snapshot.participants.map(p=>({p,d:Math.hypot(point(p.seat,box).x-x,point(p.seat,box).y-y)})).sort((a,b)=>a.d-b.d)[0];
  if(near.d>30)return;selectPhone(near.p);pointerDraft={nodeId:near.p.nodeId,seat:{...near.p.seat}};canvas.setPointerCapture(event.pointerId);
});
canvas.addEventListener('pointermove',event=>{if(!pointerDraft)return;const rect=canvas.getBoundingClientRect(),box=geometry();pointerDraft.seat.x=Math.round(Math.max(0,Math.min(1000,(event.clientX-rect.left-box.left)/(box.right-box.left)*1000)));pointerDraft.seat.y=Math.round(Math.max(0,Math.min(1000,(event.clientY-rect.top-box.top)/(box.bottom-box.top)*1000)));$('seat-x').value=pointerDraft.seat.x;$('seat-y').value=pointerDraft.seat.y;});
canvas.addEventListener('pointerup',()=>{if(pointerDraft){send('seat',{nodeId:pointerDraft.nodeId,seat:pointerDraft.seat});pointerDraft=null;}});canvas.addEventListener('pointercancel',()=>pointerDraft=null);
function render(){
  const box=geometry(),dpr=devicePixelRatio||1;if(canvas.width!==Math.round(box.width*dpr)||canvas.height!==Math.round(box.height*dpr)){canvas.width=Math.round(box.width*dpr);canvas.height=Math.round(box.height*dpr);}context.setTransform(dpr,0,0,dpr,0,0);context.clearRect(0,0,box.width,box.height);
  const time=hostNow(),show=snapshot?.show,position=show?time-show.startAt:performance.now(),active=activeProgram(time),phones=snapshot?.participants?.length?snapshot.participants:virtualPhones,simulated=phones===virtualPhones;
  const editing=sceneSafe();const activeScene=active?.cues.findLast(c=>c.atMs<=position)?.scene||editing;
  const live=show&&position>=0&&position<show.durationMs;
  const previous=active?(snapshot.programs.filter(p=>p.revision<active.revision&&p.effectiveAt<=active.effectiveAt).sort((a,b)=>b.revision-a.revision)[0]):null;
  context.strokeStyle='#243237';context.lineWidth=1;
  for(let i=0;i<=4;i++){const x=box.left+(box.right-box.left)*i/4,y=box.top+(box.bottom-box.top)*i/4;context.beginPath();context.moveTo(x,box.top-12);context.lineTo(x,box.bottom+12);context.stroke();context.beginPath();context.moveTo(box.left-12,y);context.lineTo(box.right+12,y);context.stroke();}
  phones.forEach(phone=>{
    const seat=pointerDraft?.nodeId===phone.nodeId?pointerDraft.seat:phone.seat,{x,y}=point(seat,box);
    let color=show?0:colorAt(editing,position,seat,phone.nodeId);
    if(live){color=active?programColor(active,position,{...phone,seat}):presetColor(show,position);if(active)color=blend(previous?programColor(previous,position,{...phone,seat}):presetColor(show,position),color,(time-active.effectiveAt)/1000);}
    const size=simulated?Math.min(34,(box.right-box.left)/10):32;
    context.fillStyle='#152227';context.beginPath();context.roundRect(x-size/2-3,y-size/2-4,size+6,size+8,6);context.fill();
    context.fillStyle=rgba(color);context.beginPath();context.roundRect(x-size/2,y-size/2,size,size,4);context.fill();
    context.strokeStyle=phone.nodeId===selectedPhone?'#f4f9f8':GROUP_COLORS[seat.group];context.lineWidth=phone.nodeId===selectedPhone?2:1;context.stroke();
    if(!simulated){context.fillStyle='#a5bbbe';context.font='11px Consolas,monospace';context.textAlign='center';context.fillText(phone.nodeId,x,y+size/2+18);}
  });
  $('stage-title').textContent=show?'The crowd canvas':'Rehearsal canvas';$('stage-description').textContent=simulated?'Virtual phones preview the choreography; no joined screens are represented.':'Drag a phone to assign a position. Coordinates are virtual until placed physically.';
  $('stage-mode').textContent=simulated?'SIMULATED PREVIEW · 64 VIRTUAL PHONES':snapshot?.simulated?`SIMULATED MESH · ${phones.length} VIRTUAL PHONES`:`${phones.length} JOINED PHONES · ASSIGNED POSITIONS${online?'':' · LAST SNAPSHOT'}`;
  $('active-effect').textContent=show&&!active?({RAINBOW:'Rainbow preset',PULSE:'Ocean pulse preset',SUNSET:'Sunset preset'}[show.preset]):EFFECTS.find(e=>e[0]===activeScene.effect)[1];
  $('clock').textContent=!show?'STANDBY':position<0?`START IN ${Math.ceil(-position/1000)}s`:position>=show.durationMs?'FINISHED':`${Math.ceil((show.durationMs-position)/1000)}s LEFT`;
  const latest=Math.max(0,...(snapshot?.programs||[]).map(p=>p.revision)),reported=snapshot?.participants?.length?Math.min(...snapshot.participants.map(p=>p.revision)):0;$('cue-revision').textContent=latest?`${reported} / ${latest}`:'—';controls();requestAnimationFrame(render);
}
function sceneSafe(){try{return scene();}catch{return {effect:selectedEffect,primary:0x35dcc3,secondary:0x18224c,bpm:100,spreadMs:2400,brightness:85,groups:15,direction:1};}}
labels();renderTimeline();poll();setInterval(poll,1500);requestAnimationFrame(render);
