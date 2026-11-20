(() => {
 const device=__DEVICE__, code=__CODE__, fmt=new Intl.DateTimeFormat('vi-VN',{timeZone:'Asia/Ho_Chi_Minh',day:'2-digit',month:'2-digit',year:'numeric',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false});
 const log=(kind,id,t=Date.now(),status='')=>window.b251log({kind,id:String(id),t,status}).catch(()=>{});
 let pending=null, off=false, click=null;
 const note=()=>document.querySelector('.metric-card .metric-note')?.textContent.trim();
 function check(){
   if(document.hidden) return;
   if(pending && note()===pending.label){ log('dom',pending.id);pending=null; }
   if(off && Array.from(document.querySelectorAll('.device-row')).some(r=>r.querySelector('.device-info span')?.textContent.trim().endsWith('· '+code) && r.querySelector('.device-state')?.textContent.trim()==='Offline')){log('offdom',code);off=false;}
 }
 new MutationObserver(check).observe(document,{subtree:true,childList:true,characterData:true,attributes:true});
 window.WebSocket=new Proxy(window.WebSocket,{construct(target,args){
   const ws=new target(...args);
   if(new URL(ws.url).pathname==='/ws/devices/'+device) ws.addEventListener('message',e=>{
     try {const m=JSON.parse(e.data), d=m.data;
       if(m.type==='telemetry.updated' && d?.eventId){
         log('ws',d.eventId);const label='Đo lúc '+fmt.format(new Date(d.timestamp));
         pending=(note()!==label)?{id:d.eventId,label}:null;
       }
       if(m.type==='device.status.changed' && Number(d?.id)===device) off=d.online===false;
     } catch {}
   });return ws;
 }});
 document.addEventListener('click',e=>{
   const b=e.target.closest?.('button');if(b && !b.disabled) click=Date.now();
 },true);
 const fetch0=window.fetch;
 window.fetch=async function(input,init){
   const url=new URL(input.url||input,location.href);
   const action=(init?.method||input.method||'GET').toUpperCase()==='POST' && /^\/api\/v1\/actuators\/\d+\/commands$/.test(url.pathname);
   const start=action && click && Date.now()-click<1500?click:null;
   if(action) click=null;
   let r;
   try{r=await fetch0.apply(this,arguments);}catch(e){if(start)log('clickerr',start,start,'NETWORK');throw e;}
   if(start){if(r.ok){r.clone().json().then(d=>d.commandId?log('click',d.commandId,start):log('clickerr',start,start,'NO_ID')).catch(()=>log('clickerr',start,start,'NO_ID'));}
   else log('clickerr',start,start,String(r.status));}return r;
 };
})();
