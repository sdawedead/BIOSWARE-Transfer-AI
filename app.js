const $ = id => document.getElementById(id);
let rides = [];
let closures = [];
let inbox = [];
let selectedInboxId = null;
const fields = ['rideName','price','date','time','customer','phone','pickup','destination','flight','people','luggage','note'];

function euro(v){
  return new Intl.NumberFormat('sk-SK',{style:'currency',currency:'EUR'}).format(Number(v)||0);
}
function isoDay(d){
  return [d.getFullYear(),String(d.getMonth()+1).padStart(2,'0'),String(d.getDate()).padStart(2,'0')].join('-');
}
function displayDate(iso){ if(!iso) return ''; const [y,m,d]=iso.split('-'); return `${Number(d)}. ${Number(m)}. ${y}`; }
function sundayStart(date=new Date()){
  const d=new Date(date); d.setHours(0,0,0,0); d.setDate(d.getDate()-d.getDay()); return d;
}
function rideDate(r){ return new Date(`${r.date || '1970-01-01'}T${r.time || '00:00'}:00`); }
function currentWeekTotal(){
  const start=sundayStart(), end=new Date(start); end.setDate(end.getDate()+7);
  return rides.filter(r=>{const d=rideDate(r);return d>=start&&d<end;}).reduce((s,r)=>s+(Number(r.price)||0),0);
}
async function api(path, options={}){
  const res=await fetch(path,{headers:{'Content-Type':'application/json'},...options});
  if(!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}
function parseMessage(text){
  const out={};
  const date=text.match(/\b(\d{1,2})[.\/-](\d{1,2})[.\/-](\d{2,4})\b/);
  if(date){let y=date[3].length===2?'20'+date[3]:date[3];out.date=`${y}-${date[2].padStart(2,'0')}-${date[1].padStart(2,'0')}`;}
  const time=text.match(/\b([01]?\d|2[0-3])[:.]([0-5]\d)\b(?![./-]\d{2,4})/); if(time) out.time=`${time[1].padStart(2,'0')}:${time[2]}`;
  const flight=text.match(/\b[A-Z]{2}\s?\d{2,4}\b/i); if(flight) out.flight=flight[0].toUpperCase().replace(/\s+/g,'');
  const phone=text.match(/(?:\+421|00421|0)\s?\d{3}[\s-]?\d{3}[\s-]?\d{3}/); if(phone) out.phone=phone[0];
  const people=text.match(/(\d+)\s*(?:osob|pax|people)/i); if(people) out.people=people[1];
  const customer=text.match(/(?:meno|name|customer|zákazník)\s*[:\-]\s*([^\n,;]+)/i); if(customer) out.customer=customer[1].trim();
  const pickup=text.match(/(?:pickup|vyzdvihnutie|odkiaľ|from)\s*[:\-]\s*([^\n,;]+)/i); if(pickup) out.pickup=pickup[1].trim();
  const destination=text.match(/(?:cieľ|destination|to)\s*[:\-]\s*([^\n,;]+)/i); if(destination) out.destination=destination[1].trim();
  const price=text.match(/(?:cena|price)\s*[:\-]?\s*(\d+(?:[.,]\d{1,2})?)\s*€?/i); if(price) out.price=price[1].replace(',','.');
  out.note=text.trim();
  if(out.pickup || out.destination) out.rideName=[out.pickup,out.destination].filter(Boolean).join(' → ');
  return out;
}
function fillEditor(data={}, inboxId=null){
  selectedInboxId=inboxId;
  fields.forEach(id=>{ $(id).value=data[id] ?? ''; });
  $('editor').classList.remove('hidden');
  $('confidence').textContent=inboxId ? 'Messenger návrh' : 'AI návrh';
  $('editor').scrollIntoView({behavior:'smooth',block:'start'});
}
function collectRide(){
  const r={}; fields.forEach(id=>r[id]=$(id).value.trim());
  r.price=Number(String(r.price).replace(',','.'))||0;
  if(!r.rideName) r.rideName=[r.pickup,r.destination].filter(Boolean).join(' → ') || 'Jazda';
  if(selectedInboxId) r.sourceInboxId=selectedInboxId;
  return r;
}
function renderInbox(){
  const list=$('inboxList'); list.innerHTML='';
  $('inboxCount').textContent=`${inbox.length} čaká`;
  if(!inbox.length){ list.innerHTML='<div class="empty">Žiadne nové správy z Messengeru.</div>'; return; }
  inbox.forEach(msg=>{
    const base=msg.candidate || parseMessage(msg.text);
    const candidates=(base.candidates && base.candidates.length) ? base.candidates : [base];
    const row=document.createElement('article'); row.className='inbox-item inbox-new';
    const head=document.createElement('div'); head.className='inbox-head';
    head.innerHTML=`<div class="sender"><span class="new-badge">NOVÁ</span><strong>${escapeHtml(msg.sender || 'Messenger')}</strong></div><small>${new Date(msg.receivedAt).toLocaleString('sk-SK')}</small>`;
    const count=document.createElement('div'); count.className='multi-count';
    count.textContent=candidates.length>1 ? `Rozpoznané ${candidates.length} jazdy v jednej správe` : 'Rozpoznaná 1 jazda';
    row.append(head,count);
    candidates.forEach((c,idx)=>{
      const box=document.createElement('div'); box.className='candidate-box';
      box.innerHTML=`<div class="candidate-no">JAZDA ${idx+1}</div><div class="summary-main"><strong>${escapeHtml(c.rideName || 'Nová jazda')}</strong><span>${escapeHtml([displayDate(c.date),c.time].filter(Boolean).join(' • ') || 'Dátum/čas nerozpoznaný')}</span></div><div class="summary-chips">${chip(c.customer,'👤')}${chip(c.flight,'✈')}${chip(c.people ? c.people+' os.' : '','👥')}${chip(c.price!==undefined ? euro(c.price) : '','€')}</div>`;
      const use=document.createElement('button'); use.className='primary confirm-big'; use.textContent=`Skontrolovať jazdu ${idx+1}`;
      use.addEventListener('click',()=>fillEditor(c,candidates.length===1 ? msg.id : null));
      box.append(use); row.append(box);
    });
    const details=document.createElement('details'); details.className='raw-message';
    details.innerHTML=`<summary>Pôvodná správa</summary><p>${escapeHtml(msg.text)}</p>`;
    const dismiss=document.createElement('button'); dismiss.className='ghost small inbox-remove'; dismiss.textContent=candidates.length>1 ? 'Hotovo / odstrániť správu' : 'Ignorovať';
    dismiss.addEventListener('click',async()=>{ if(!confirm('Odstrániť túto prijatú správu?')) return; await api(`/api/inbox/${encodeURIComponent(msg.id)}`,{method:'DELETE'}); await loadState(); });
    row.append(details,dismiss); list.appendChild(row);
  });
}

function escapeHtml(value=''){ const d=document.createElement('div'); d.textContent=String(value); return d.innerHTML; }
function chip(value,icon){ return value ? `<span class="data-chip">${icon} ${escapeHtml(value)}</span>` : ''; }
function renderRides(){
  const list=$('ridesList'); list.innerHTML='';
  const sorted=rides.slice().sort((a,b)=>rideDate(a)-rideDate(b));
  if(!sorted.length) list.innerHTML='<div class="empty">Žiadne aktívne jazdy. Práčička čaká na ďalšiu správu.</div>';
  const today=isoDay(new Date());
  sorted.forEach(r=>{
    const node=$('rideTemplate').content.cloneNode(true);
    const article=node.querySelector('.ride-item');
    if(r.date===today) article.classList.add('ride-today');
    node.querySelector('.ride-time').innerHTML=`<span class="ride-state ${r.date===today?'today':'planned'}">${r.date===today?'DNES':'NAPLÁNOVANÁ'}</span><strong>${escapeHtml(r.time || '—')}</strong><small>${escapeHtml(r.date || '—')}</small>`;
    node.querySelector('h3').textContent=r.rideName || 'Jazda';
    node.querySelector('.ride-price').textContent=euro(r.price);
    node.querySelector('.route').textContent=[r.pickup,r.destination].filter(Boolean).join(' → ') || 'Trasa neuvedená';
    node.querySelector('.meta').innerHTML=[chip(r.customer,'👤'),chip(r.flight,'✈'),chip(r.people ? r.people+' os.' : '','👥')].filter(Boolean).join('');
    node.querySelector('.delete').addEventListener('click',async()=>{ if(!confirm('Vymazať túto jazdu?')) return; await api(`/api/rides/${encodeURIComponent(r.id)}`,{method:'DELETE'}); await loadState(); });
    list.appendChild(node);
  });
  $('countAll').textContent=rides.length;
  $('countToday').textContent=rides.filter(r=>r.date===today).length;
  $('weekTotal').textContent=euro(currentWeekTotal());
}
function renderClosures(){
  const box=$('closureHistory'); box.innerHTML='';
  if(!closures.length){ box.innerHTML='<div class="empty">Zatiaľ žiadna týždenná uzávierka.</div>'; return; }
  closures.forEach(c=>{
    const start=new Date(c.weekStart), end=new Date(c.weekEnd);
    const row=document.createElement('article'); row.className='closure-item';
    const left=document.createElement('div');
    const range=document.createElement('span'); range.textContent=`${start.toLocaleDateString('sk-SK')} – ${new Date(end-1).toLocaleDateString('sk-SK')}`;
    const count=document.createElement('small'); count.textContent=`${c.rideCount} jázd`;
    const total=document.createElement('strong'); total.textContent=euro(c.total);
    left.append(range,count); row.append(left,total); box.appendChild(row);
  });
}
async function loadState(){
  try{
    const state=await api('/api/state');
    rides=state.rides || []; closures=state.closures || []; inbox=state.inbox || [];
    renderInbox(); renderRides(); renderClosures(); $('serverStatus').textContent='online';
  }catch(err){ console.error(err); $('serverStatus').textContent='offline'; }
}
function resetEditor(){
  selectedInboxId=null; fields.forEach(id=>$(id).value=''); $('editor').classList.add('hidden');
}
$('parseBtn').addEventListener('click',()=>{
  const text=$('messageInput').value.trim();
  if(!text){ alert('Najprv vlož správu od šéfa.'); return; }
  fillEditor(parseMessage(text));
});
$('demoBtn').addEventListener('click',()=>{
  $('messageInput').value='3.10.2026 14:30\nMeno: Test zákazník\nPickup: Vienna Airport\nCieľ: Trenčín\nLet: OS318\n2 osoby\nCena: 85 €';
  fillEditor(parseMessage($('messageInput').value));
});
$('saveBtn').addEventListener('click',async()=>{
  const ride=collectRide();
  if(!ride.date || !ride.time){ alert('Doplň dátum a čas jazdy.'); return; }
  await api('/api/rides',{method:'POST',body:JSON.stringify(ride)});
  $('messageInput').value=''; resetEditor(); await loadState();
});
$('cancelBtn').addEventListener('click',resetEditor);
$('clearBtn').addEventListener('click',async()=>{
  if(!confirm('Vymazať všetky aktívne testovacie jazdy? História uzávierok zostane zachovaná.')) return;
  await api('/api/rides',{method:'DELETE'}); await loadState();
});
loadState();
setInterval(loadState,15000);
