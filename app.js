'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const FILTERS = {
    none: 'none', cinematic: 'contrast(1.16) saturate(.88)', mono: 'grayscale(1)',
    warm: 'sepia(.22) saturate(1.22)', vintage: 'sepia(.34) contrast(.88) saturate(.82)',
    cool: 'hue-rotate(10deg) saturate(.88)', fade: 'brightness(1.12) contrast(.82) saturate(.82)',
    vivid: 'saturate(1.45) contrast(1.08)'
  };
  const FILTER_NAMES = {none:'מקורי',cinematic:'קולנועי',mono:'שחור לבן',warm:'חם',vintage:'וינטג׳',cool:'קר',fade:'דהוי',vivid:'חי'};
  const state = {clips:[], media:new Map(), selected:null, musicId:null, musicVolume:.35, ratio:'16:9', fit:'cover', zoom:85, brightness:1, contrast:1, saturation:1, textSize:42, textPosition:'bottom', textStyle:'bold', projectName:'פרויקט חדש'};
  let activeUrl = '', toastTimer = null, autosaveTimer = null, dbPromise = null, undoStack = [], redoStack = [], exporting = false, cancelExport = false, activeRecorder = null, activeExportTimer = null, previewMuted = false;

  const uid = () => Math.random().toString(36).slice(2,9) + Date.now().toString(36).slice(-4);
  const clone = obj => JSON.parse(JSON.stringify(obj));
  const getClip = () => state.clips.find(c => c.id === state.selected) || null;
  const getMedia = id => state.media.get(id) || null;
  const sec = n => Math.max(0, Number(n) || 0);
  const fmt = n => { n=Math.max(0,Number(n)||0); const m=Math.floor(n/60), s=Math.floor(n%60), d=Math.floor((n%1)*10); return String(m).padStart(2,'0')+':'+String(s).padStart(2,'0')+'.'+d; };
  const plainTime = n => { n=Math.max(0,Number(n)||0); return String(Math.floor(n/60)).padStart(2,'0')+':'+String(Math.floor(n%60)).padStart(2,'0'); };
  const clipRawLength = c => Math.max(.1, (Number(c.outPoint)||0) - (Number(c.inPoint)||0));
  const clipLength = c => clipRawLength(c) / Math.max(.25, Number(c.speed)||1);
  const totalLength = () => state.clips.reduce((a,c)=>a+clipLength(c),0);
  const mediaKind = file => file.type.startsWith('video/') ? 'video' : file.type.startsWith('image/') ? 'image' : file.type.startsWith('audio/') ? 'audio' : 'other';
  const escapeHtml = s => String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

  function notify(message, ms=3000) { const el=$('toast'); el.textContent=message; el.classList.add('show'); clearTimeout(toastTimer); toastTimer=setTimeout(()=>el.classList.remove('show'),ms); }
  function setStatus(text) { $('saveLabel').textContent=text; }
  function snapshot() { return {clips:clone(state.clips),selected:state.selected,musicId:state.musicId,musicVolume:state.musicVolume,ratio:state.ratio,fit:state.fit,brightness:state.brightness,contrast:state.contrast,saturation:state.saturation,textSize:state.textSize,textPosition:state.textPosition,textStyle:state.textStyle,projectName:state.projectName}; }
  function pushUndo() { undoStack.push(snapshot()); if(undoStack.length>40)undoStack.shift(); redoStack=[]; updateUndoButtons(); }
  function restoreSnapshot(s) { Object.assign(state, clone(s)); renderAll(); persistLocal(); }
  function undo() { if(!undoStack.length)return notify('אין עוד פעולה לביטול'); redoStack.push(snapshot()); restoreSnapshot(undoStack.pop()); updateUndoButtons(); notify('הפעולה בוטלה'); }
  function redo() { if(!redoStack.length)return notify('אין פעולה לביצוע מחדש'); undoStack.push(snapshot()); restoreSnapshot(redoStack.pop()); updateUndoButtons(); notify('הפעולה בוצעה מחדש'); }
  function updateUndoButtons() { $('undoBtn').disabled=!undoStack.length; $('redoBtn').disabled=!redoStack.length; $('undoBtn').style.opacity=undoStack.length?1:.45; $('redoBtn').style.opacity=redoStack.length?1:.45; }

  function openDb() {
    if(!('indexedDB' in window)) return Promise.reject(new Error('IndexedDB לא זמין'));
    if(!dbPromise) dbPromise = new Promise((resolve,reject)=>{ const req=indexedDB.open('cutlab-studio',1); req.onupgradeneeded=()=>{const db=req.result;if(!db.objectStoreNames.contains('projects'))db.createObjectStore('projects');}; req.onsuccess=()=>resolve(req.result); req.onerror=()=>reject(req.error); });
    return dbPromise;
  }
  async function idbGet(key) { const db=await openDb(); return new Promise((resolve,reject)=>{const tx=db.transaction('projects','readonly'),r=tx.objectStore('projects').get(key);r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);}); }
  async function idbPut(key,value) { const db=await openDb(); return new Promise((resolve,reject)=>{const tx=db.transaction('projects','readwrite');tx.objectStore('projects').put(value,key);tx.oncomplete=()=>resolve();tx.onerror=()=>reject(tx.error);tx.onabort=()=>reject(tx.error);}); }
  function persistLocal() {
    clearTimeout(autosaveTimer); setStatus('שומר…');
    autosaveTimer=setTimeout(async()=>{try{const data={snapshot:snapshot(),media:[...state.media.values()].map(m=>({id:m.id,name:m.name,type:m.type,file:m.file,duration:m.duration,kind:m.kind})),version:1};await idbPut('autosave',data);setStatus('נשמר מקומית');}catch(e){setStatus('שמירה מקומית מוגבלת');} },500);
  }
  async function restoreLocal() {
    try {
      const data=await idbGet('autosave'); if(!data||!data.snapshot)return;
      for(const m of (data.media||[])){ if(m.file) state.media.set(m.id,{...m,url:URL.createObjectURL(m.file)}); }
      Object.assign(state, data.snapshot); state.media=state.media; renderAll();
      if(state.musicId && !getMedia(state.musicId)) state.musicId=null;
      if(state.selected && !getClip()) state.selected=state.clips[0]?.id||null;
      updatePreview(); renderTimeline(); renderMediaList(); updateMusicPanel();
      if(state.media.size) notify('הפרויקט הקודם שוחזר מהמכשיר');
    } catch(e) { /* private mode or disabled storage: editing still works */ }
  }

  function readDuration(file, url) {
    return new Promise(resolve=>{
      const kind=mediaKind(file); if(kind==='image')return resolve(5); if(kind!=='video'&&kind!=='audio')return resolve(5);
      const el=document.createElement(kind==='video'?'video':'audio'); el.preload='metadata'; el.src=url;
      const done=v=>{const d=Number.isFinite(el.duration)&&el.duration>0?el.duration:5;el.removeAttribute('src');try{el.load();}catch{}resolve(d);};
      el.onloadedmetadata=done;el.onerror=()=>resolve(5);setTimeout(()=>resolve(5),9000);
    });
  }
  async function registerMedia(file, idOverride=null, durationOverride=null) {
    if(!file) return null; const id=idOverride||uid(); if(state.media.has(id))return state.media.get(id);
    const url=URL.createObjectURL(file); const kind=mediaKind(file); const duration=durationOverride||await readDuration(file,url);
    const media={id,name:file.name||'מדיה',type:file.type||'application/octet-stream',file,url,kind,duration,size:file.size||0}; state.media.set(id,media); return media;
  }
  async function addFiles(files) {
    const chosen=Array.from(files||[]); if(!chosen.length)return;
    const accepted=chosen.filter(f=>['video','image','audio'].includes(mediaKind(f)));
    if(accepted.length!==chosen.length)notify('קבצים שאינם תמונה, וידאו או שמע דולגו');
    let addedClips=0,addedAudio=0;
    for(const file of accepted){
      try{const media=await registerMedia(file);if(media.kind==='audio'){state.musicId=media.id;addedAudio++;}else{state.clips.push({id:uid(),mediaId:media.id,name:file.name.replace(/\.[^.]+$/,''),inPoint:0,outPoint:media.duration,duration:media.duration,filter:'none',speed:1,volume:1,text:'',textStyle:state.textStyle,textPosition:state.textPosition,textSize:state.textSize,brightness:1,contrast:1,saturation:1});state.selected=state.clips[state.clips.length-1].id;addedClips++;}}
      catch(e){notify('לא הצלחתי לפתוח את '+file.name);}
    }
    renderAll(); persistLocal();
    if(addedClips||addedAudio)notify(`נוספו ${addedClips} קליפים${addedAudio?` ו-${addedAudio} קובצי שמע`:''}`);
  }
  function addMediaAsClip(mediaId) {
    const m=getMedia(mediaId); if(!m||m.kind==='audio')return;
    pushUndo(); state.clips.push({id:uid(),mediaId:m.id,name:m.name.replace(/\.[^.]+$/,''),inPoint:0,outPoint:m.duration,duration:m.duration,filter:'none',speed:1,volume:1,text:'',textStyle:state.textStyle,textPosition:state.textPosition,textSize:state.textSize,brightness:1,contrast:1,saturation:1});state.selected=state.clips[state.clips.length-1].id;renderAll();persistLocal();notify('הקליפ נוסף לציר הזמן');
  }
  function fmtSize(n) { if(n<1024*1024)return Math.max(1,Math.round(n/1024))+' KB';return (n/(1024*1024)).toFixed(1)+' MB'; }
  function renderMediaList() {
    const root=$('mediaList');root.textContent='';$('mediaCount').textContent=String(state.media.size);
    if(!state.media.size){const e=document.createElement('div');e.className='empty-state';e.textContent='ייבא קבצים כדי להתחיל לערוך';root.append(e);return;}
    for(const m of state.media.values()){
      const card=document.createElement('div');card.className='media-card';
      const thumb=document.createElement('div');thumb.className='media-thumb';
      if(m.kind==='image'){const img=document.createElement('img');img.src=m.url;img.alt='';thumb.append(img);}else if(m.kind==='video'){const v=document.createElement('video');v.src=m.url;v.muted=true;v.preload='metadata';thumb.append(v);}else {thumb.textContent='♫';}
      const copy=document.createElement('div');copy.className='media-copy';const name=document.createElement('strong');name.textContent=m.name;name.title=m.name;const meta=document.createElement('span');meta.textContent=(m.kind==='audio'?'שמע':m.kind==='image'?'תמונה':'וידאו')+' · '+fmtSize(m.size);copy.append(name,meta);
      const btn=document.createElement('button');btn.className='media-add';btn.textContent=m.kind==='audio'?(state.musicId===m.id?'נבחר ✓':'בחר פסקול'):'הוסף +';btn.addEventListener('click',()=>{if(m.kind==='audio'){state.musicId=m.id;updateMusicPanel();renderMediaList();persistLocal();notify('קובץ השמע נבחר כמוזיקת רקע');}else addMediaAsClip(m.id);});
      card.append(thumb,copy,btn);root.append(card);
    }
  }

  function renderAll() { renderMediaList();renderTimeline();updatePreview();updateClipControls();updateMusicPanel();updateSettingsControls();updateUndoButtons();$('projectLabel').textContent=state.projectName||'פרויקט חדש'; }
  function renderTimeline() {
    const track=$('videoTrack');track.textContent=''; const music=$('audioTrack');music.textContent=''; const total=totalLength(), zoom=state.zoom;
    if(!state.clips.length){const e=document.createElement('div');e.className='track-empty';e.textContent='הקליפים שלך יופיעו כאן אחרי הייבוא';track.append(e);} else {
      state.clips.forEach((c,index)=>{const m=getMedia(c.mediaId);const el=document.createElement('div');el.className='clip-block'+(c.id===state.selected?' selected':'');el.draggable=true;el.dataset.clipId=c.id;el.style.width=Math.max(67,clipLength(c)*zoom)+'px';
        const t=document.createElement('span');t.className='clip-title';t.textContent=m?m.name:c.name;const meta=document.createElement('span');meta.className='clip-meta';meta.textContent=fmt(clipLength(c));el.append(t,meta);
        el.addEventListener('click',()=>selectClip(c.id));el.addEventListener('dragstart',e=>{e.dataTransfer.setData('text/plain',String(index));e.dataTransfer.effectAllowed='move';el.classList.add('dragging');});el.addEventListener('dragend',()=>el.classList.remove('dragging'));el.addEventListener('dragover',e=>{e.preventDefault();e.dataTransfer.dropEffect='move';});el.addEventListener('drop',e=>{e.preventDefault();const from=Number(e.dataTransfer.getData('text/plain'));const to=state.clips.findIndex(x=>x.id===c.id);if(Number.isInteger(from)&&from!==to&&from>=0){pushUndo();const [moved]=state.clips.splice(from,1);state.clips.splice(to,0,moved);renderTimeline();persistLocal();}});track.append(el);
      });
    }
    if(state.musicId&&getMedia(state.musicId)) {const m=getMedia(state.musicId);const el=document.createElement('div');el.className='clip-block audio-clip';el.style.width=Math.max(85,(total||4)*zoom)+'px';const title=document.createElement('span');title.className='clip-title';title.textContent='♫ '+m.name;const meta=document.createElement('span');meta.className='clip-meta';meta.textContent='מוזיקת רקע · '+Math.round(state.musicVolume*100)+'%';el.append(title,meta);music.append(el);} else {const e=document.createElement('div');e.className='track-empty';e.textContent='אפשר להוסיף מוזיקת רקע מהכלים משמאל';music.append(e);}
    const px=Math.max(500,Math.min(18000,total*zoom+130));track.style.width=px+'px';music.style.width=px+'px';$('ruler').style.width=px+'px';$('ruler').textContent='';const r=$('ruler');const label=document.createElement('span');label.textContent='00:00';r.append(label);for(let n=2;n<=Math.max(2,total);n+=2){const l=document.createElement('span');l.textContent=plainTime(n);r.append(l);}const playhead=$('playhead');playhead.style.right=(84+(window.previewCurrentTime||0)*zoom)+'px';playhead.style.display=state.clips.length?'block':'none';
    $('timelineSummary').textContent=`${state.clips.length} קליפים · ${plainTime(total)}`;$('durationTime').textContent=fmt(total);$('playbackZoom').value=String(state.zoom);
  }
  function selectClip(id) {const c=state.clips.find(x=>x.id===id);if(!c)return;state.selected=id;updatePreview(true);updateClipControls();renderTimeline();}
  function updatePreview(seek=false) {
    const c=getClip(),video=$('previewVideo'),img=$('previewImage'),empty=$('placeholder'),frame=$('previewFrame'),caption=$('captionOverlay');
    frame.classList.remove('ratio-16-9','ratio-9-16','ratio-1-1','ratio-4-5');frame.classList.add('ratio-'+state.ratio.replace(':','-'));
    if(!c){video.pause();video.style.display='none';img.style.display='none';empty.style.display='flex';caption.style.display='none';$('previewMeta').textContent='אין קליפ נבחר';$('currentTime').textContent='00:00.0';window.previewCurrentTime=0;return;}
    const m=getMedia(c.mediaId);if(!m){return;}
    empty.style.display='none';const filter=(FILTERS[c.filter]||'none')+` brightness(${(c.brightness||1)*state.brightness}) contrast(${(c.contrast||1)*state.contrast}) saturate(${(c.saturation||1)*state.saturation})`;
    if(m.kind==='image'){video.pause();video.style.display='none';img.style.display='block';if(img.src!==m.url)img.src=m.url;img.style.objectFit=state.fit;img.style.filter=filter;}
    else {img.style.display='none';video.style.display='block';video.style.objectFit=state.fit;video.style.filter=filter;if(activeUrl!==m.url){video.pause();video.src=m.url;activeUrl=m.url;video.load();seek=true;}video.muted=previewMuted;if(seek){try{video.currentTime=Math.min(c.outPoint-.01,c.inPoint);}catch{}}}
    caption.textContent=c.text||'';caption.style.display=c.text?'block':'none';caption.className='caption-overlay '+(c.textStyle||'bold')+' '+(c.textPosition||'bottom');caption.style.fontSize=(c.textSize||42)+'px';
    $('previewMeta').textContent=m.name;$('currentTime').textContent=fmt((Number(video.currentTime)||c.inPoint));$('durationTime').textContent=fmt(totalLength());$('playBtn').textContent=(!video.paused&&m.kind==='video')?'Ⅱ':'▶';
  }
  function filterCss(c) {return (FILTERS[c.filter]||'none')+` brightness(${(c.brightness||1)*state.brightness}) contrast(${(c.contrast||1)*state.contrast}) saturate(${(c.saturation||1)*state.saturation})`;}
  function updateClipControls() {
    const c=getClip();$('selectedClipLabel').textContent=c?c.name:'לא נבחר';$('trimHint').textContent=c?'החיתוך חל על הקליפ המסומן':'בחר קליפ כדי לערוך התחלה וסיום';
    const controls=['trimStart','trimEnd','clipSpeed','clipName','applyTrimBtn','clipText','applyTextBtn','clearTextBtn'];controls.forEach(id=>$(id).disabled=!c);
    if(c){$('trimStart').value=Number(c.inPoint).toFixed(1);$('trimEnd').value=Number(c.outPoint).toFixed(1);$('clipSpeed').value=String(c.speed||1);$('clipName').value=c.name;$('clipText').value=c.text||'';$('clipVolume').value=String(Math.round((c.volume??1)*100));$('clipVolumeValue').textContent=Math.round((c.volume??1)*100)+'%';}
    document.querySelectorAll('.filter-option').forEach(el=>el.classList.toggle('selected',!!c&&el.dataset.filter===c.filter));
    $('brightness').value=String(Math.round((c?.brightness??1)*100));$('brightnessValue').textContent=Math.round((c?.brightness??1)*100)+'%';$('contrast').value=String(Math.round((c?.contrast??1)*100));$('contrastValue').textContent=Math.round((c?.contrast??1)*100)+'%';$('saturation').value=String(Math.round((c?.saturation??1)*100));$('saturationValue').textContent=Math.round((c?.saturation??1)*100)+'%';
    $('textStyle').value=c?.textStyle||state.textStyle;$('textPosition').value=c?.textPosition||state.textPosition;$('textSize').value=String(c?.textSize||state.textSize);$('textSizeValue').textContent=String(c?.textSize||state.textSize);
  }
  function updateMusicPanel(){const m=getMedia(state.musicId);$('musicName').textContent=m?m.name:'לא נבחר קובץ שמע';$('musicVolume').value=String(Math.round(state.musicVolume*100));$('musicVolumeValue').textContent=Math.round(state.musicVolume*100)+'%';}
  function updateSettingsControls(){ $('aspectRatio').value=state.ratio;$('fitMode').value=state.fit; }
  function applyTrim(){const c=getClip();if(!c)return;const m=getMedia(c.mediaId);let start=sec($('trimStart').value),end=sec($('trimEnd').value);const max=m?.duration||c.duration||end;if(end>max)end=max;if(start>=end){notify('נקודת הסיום חייבת להיות אחרי נקודת ההתחלה');return;}pushUndo();c.inPoint=start;c.outPoint=end;c.speed=Number($('clipSpeed').value)||1;c.name=$('clipName').value.trim()||c.name;renderAll();persistLocal();notify('החיתוך והמהירות עודכנו');}
  function applyText(){const c=getClip();if(!c)return pushNoticeNoClip();pushUndo();c.text=$('clipText').value;c.textStyle=$('textStyle').value;c.textPosition=$('textPosition').value;c.textSize=Number($('textSize').value);state.textStyle=c.textStyle;state.textPosition=c.textPosition;state.textSize=c.textSize;updatePreview();persistLocal();notify('הטקסט עודכן');}
  function pushNoticeNoClip(){notify('תחילה בחר קליפ בציר הזמן');}
  function splitClip(){const c=getClip();if(!c)return pushNoticeNoClip();const m=getMedia(c.mediaId);if(!m||m.kind!=='video')return notify('פיצול זמין לקליפ וידאו');const t=Number($('previewVideo').currentTime)||c.inPoint;if(t<=c.inPoint+.1||t>=c.outPoint-.1)return notify('נקודת הניגון צריכה להיות בתוך הקליפ');pushUndo();const originalEnd=c.outPoint;const second={...clone(c),id:uid(),name:c.name+' – חלק 2',inPoint:t,outPoint:originalEnd};c.outPoint=t;const index=state.clips.findIndex(x=>x.id===c.id);state.clips.splice(index+1,0,second);state.selected=second.id;renderAll();persistLocal();notify('הקליפ פוצל לשני חלקים');}
  function duplicateClip(){const c=getClip();if(!c)return pushNoticeNoClip();pushUndo();const copy={...clone(c),id:uid(),name:c.name+' – עותק'};const i=state.clips.findIndex(x=>x.id===c.id);state.clips.splice(i+1,0,copy);state.selected=copy.id;renderAll();persistLocal();notify('נוצר עותק של הקליפ');}
  function deleteClip(){const c=getClip();if(!c)return pushNoticeNoClip();pushUndo();const i=state.clips.findIndex(x=>x.id===c.id);state.clips.splice(i,1);state.selected=state.clips[Math.min(i,state.clips.length-1)]?.id||null;renderAll();persistLocal();notify('הקליפ הוסר מציר הזמן');}
  function updatePlayhead() {const v=$('previewVideo'),c=getClip();if(!c)return;const t=Number(v.currentTime)||0;window.previewCurrentTime=t;const local=(t-c.inPoint)/Math.max(.25,c.speed||1);$('currentTime').textContent=fmt(Math.max(0,local));$('playhead').style.right=(84+timelineOffset(c)*state.zoom)+'px';}
  function timelineOffset(c){let t=0;for(const clip of state.clips){if(clip.id===c.id)break;t+=clipLength(clip);}return t;}
  function applyLiveFilter(){const c=getClip();if(!c)return;const val=FILTERS[c.filter]||'none';$('previewVideo').style.filter=val+` brightness(${(c.brightness||1)*state.brightness}) contrast(${(c.contrast||1)*state.contrast}) saturate(${(c.saturation||1)*state.saturation})`;$('previewImage').style.filter=$('previewVideo').style.filter;}

  function drawSource(ctx,source,w,h,fit){const sw=source.videoWidth||source.naturalWidth||source.width,sh=source.videoHeight||source.naturalHeight||source.height;if(!sw||!sh)return;let scale=fit==='contain'?Math.min(w/sw,h/sh):Math.max(w/sw,h/sh);let dw=sw*scale,dh=sh*scale;ctx.drawImage(source,(w-dw)/2,(h-dh)/2,dw,dh);}
  function drawCaption(ctx,c,w,h){if(!c.text)return;const text=String(c.text).slice(0,500),size=Math.min(c.textSize||42,Math.round(w*.11)),lines=text.split('\n').slice(0,5);ctx.save();ctx.font=`800 ${size}px Arial, sans-serif`;ctx.textAlign='center';ctx.textBaseline='middle';const lineH=size*1.3;const widths=lines.map(line=>Math.min(w*.88,ctx.measureText(line).width));const maxWidth=Math.max(0,...widths);let y=c.textPosition==='top'?h*.13:c.textPosition==='center'?h/2:h*.87-(lines.length-1)*lineH/2;
    if(c.textStyle==='box'){ctx.fillStyle='rgba(0,0,0,.68)';ctx.beginPath();const x=(w-maxWidth)/2-18,yy=y-(lines.length*lineH)/2-7;ctx.roundRect?ctx.roundRect(x,yy,maxWidth+36,lines.length*lineH+14,10):ctx.rect(x,yy,maxWidth+36,lines.length*lineH+14);ctx.fill();}
    lines.forEach((line,i)=>{const yy=y+i*lineH;if(c.textStyle==='outline'){ctx.lineWidth=Math.max(2,size*.06);ctx.strokeStyle='#111';ctx.strokeText(line,w/2,yy,maxWidth||w*.9);}ctx.fillStyle='#fff';ctx.shadowColor='#000';ctx.shadowBlur=size*.16;ctx.shadowOffsetY=2;ctx.fillText(line,w/2,yy,w*.9);});ctx.restore();}
  function canvasDraw(ctx,source,c,w,h){ctx.save();ctx.fillStyle='#000';ctx.fillRect(0,0,w,h);ctx.filter=filterCss(c);drawSource(ctx,source,w,h,state.fit);ctx.filter='none';drawCaption(ctx,c,w,h);ctx.restore();}
  function readAsDataURL(file){return new Promise((resolve,reject)=>{const r=new FileReader();r.onload=()=>resolve(r.result);r.onerror=()=>reject(r.error);r.readAsDataURL(file);});}
  function dataUrlBlob(dataUrl){const [head,b64]=dataUrl.split(',');const mime=(head.match(/data:([^;]+)/)||[])[1]||'application/octet-stream';const bin=atob(b64);const arr=new Uint8Array(bin.length);for(let i=0;i<bin.length;i++)arr[i]=bin.charCodeAt(i);return new Blob([arr],{type:mime});}
  async function exportProject(){if(!state.clips.length&&!state.media.size)return notify('אין פרויקט לייצוא');const all=[...state.media.values()];const total=all.reduce((a,m)=>a+m.size,0);if(total>180*1024*1024){notify('הפרויקט גדול מדי לקובץ אחד. הוא נשמר מקומית בדפדפן, וניתן לייצא וידאו בנפרד.',6000);return;}
    try{const media=[];for(let i=0;i<all.length;i++){const m=all[i];media.push({id:m.id,name:m.name,type:m.type,duration:m.duration,kind:m.kind,data:await readAsDataURL(m.file)});}const data={format:'cutlab-project',version:1,createdAt:new Date().toISOString(),snapshot:snapshot(),media};const blob=new Blob([JSON.stringify(data)],{type:'application/json'});const saved=await downloadBlob(blob,(state.projectName||'cutlab-project').replace(/[^\w\u0590-\u05FF-]+/g,'_')+'.cutproj');if(saved)notify('קובץ הפרויקט נשמר, כולל קובצי המדיה');}catch(e){notify('לא הצלחתי לארוז את הפרויקט. נסה לשמור מקומית או לייצא וידאו.');}}
  async function importProjectFile(file){try{const text=await file.text();const data=JSON.parse(text);if(data.format!=='cutlab-project'||!Array.isArray(data.media))throw new Error('פורמט לא תקין');for(const m of state.media.values())URL.revokeObjectURL(m.url);state.media.clear();for(const raw of data.media){const blob=dataUrlBlob(raw.data);const fileObj=new File([blob],raw.name,{type:raw.type||blob.type});await registerMedia(fileObj,raw.id,raw.duration);}Object.assign(state,data.snapshot||{});state.clips=Array.isArray(data.snapshot?.clips)?data.snapshot.clips:[];state.selected=state.clips.some(c=>c.id===state.selected)?state.selected:state.clips[0]?.id||null;state.projectName=file.name.replace(/\.cutproj$|\.json$/i,'');renderAll();persistLocal();notify('הפרויקט נטען בהצלחה');}catch(e){notify('לא הצלחתי לפתוח את קובץ הפרויקט');}}
  async function downloadBlob(blob,name){
    const native=window.CUTLABNative;
    if(native&&typeof native.requestSave==='function'&&typeof native.writeChunk==='function'&&typeof native.finishSave==='function'){
      const saved=await new Promise(resolve=>{
        let settled=false;
        window.__cutlabNativeSaveReady=async ready=>{
          if(settled)return;settled=true;window.__cutlabNativeSaveReady=null;
          if(!ready){resolve(false);return;}
          try{
            const chunkSize=256*1024;
            for(let offset=0;offset<blob.size;offset+=chunkSize){
              const bytes=new Uint8Array(await blob.slice(offset,offset+chunkSize).arrayBuffer());
              let binary='';for(let i=0;i<bytes.length;i++)binary+=String.fromCharCode(bytes[i]);
              if(!native.writeChunk(btoa(binary)))throw new Error('הכתיבה לקובץ נכשלה');
            }
            native.finishSave(true);resolve(true);
          }catch(error){try{native.finishSave(false);}catch{}notify(error?.message||'לא ניתן לשמור את הקובץ');resolve(false);}
        };
        try{native.requestSave(name,blob.type||'application/octet-stream');}catch(error){window.__cutlabNativeSaveReady=null;resolve(false);}
      });
      if(saved)notify('הקובץ נשמר במכשיר');else notify('השמירה בוטלה או נכשלה');
      return saved;
    }
    const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download=name;document.body.appendChild(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),30000);return true;
  }
  function ratioSize(){switch(state.ratio){case'9:16':return[720,1280];case'1:1':return[1080,1080];case'4:5':return[864,1080];default:return[1280,720];}}
  function sleep(ms){return new Promise(resolve=>{activeExportTimer=setTimeout(resolve,ms);});}
  function waitEvent(el,success,error='error',timeout=15000){return new Promise((resolve,reject)=>{let done=false;const clean=()=>{el.removeEventListener(success,ok);el.removeEventListener(error,bad);clearTimeout(t);};const ok=()=>{if(done)return;done=true;clean();resolve();};const bad=()=>{if(done)return;done=true;clean();reject(new Error('המדיה לא נטענה'));};const t=setTimeout(()=>{if(done)return;done=true;clean();reject(new Error('פג הזמן להמתנה למדיה'));},timeout);el.addEventListener(success,ok,{once:true});el.addEventListener(error,bad,{once:true});});}
  async function runExport(){if(exporting)return;if(!state.clips.length)return notify('הוסף לפחות קליפ אחד לפני הייצוא');if(!window.MediaRecorder||!HTMLCanvasElement.prototype.captureStream)return notify('הדפדפן הזה לא תומך בייצוא וידאו. נסה Chrome עדכני.');
    exporting=true;cancelExport=false;activeRecorder=null;$('exportModal').hidden=false;$('exportTitle').textContent='מכינים את הסרטון';$('exportMessage').textContent='מכין את ציר הזמן ואת מקורות המדיה…';$('exportProgress').style.width='0%';$('cancelExportBtn').textContent='בטל ייצוא';
    let canvasStream=null,audioCtx=null,recStream=null,renderVideo=null,musicAudio=null,raf=0;const chunks=[];let blobResult=null;
    try{
      const [w,h]=ratioSize();const canvas=document.createElement('canvas');canvas.width=w;canvas.height=h;const ctx=canvas.getContext('2d',{alpha:false});
      renderVideo=document.createElement('video');renderVideo.playsInline=true;renderVideo.preload='auto';renderVideo.crossOrigin='anonymous';renderVideo.muted=false;
      audioCtx=new (window.AudioContext||window.webkitAudioContext)();await audioCtx.resume();const dest=audioCtx.createMediaStreamDestination();const videoSource=audioCtx.createMediaElementSource(renderVideo);const clipGain=audioCtx.createGain();clipGain.gain.value=1;videoSource.connect(clipGain);clipGain.connect(dest);
      if(state.musicId&&getMedia(state.musicId)) {musicAudio=document.createElement('audio');musicAudio.src=getMedia(state.musicId).url;musicAudio.loop=true;musicAudio.preload='auto';const musicSource=audioCtx.createMediaElementSource(musicAudio);const musicGain=audioCtx.createGain();musicGain.gain.value=state.musicVolume;musicSource.connect(musicGain);musicGain.connect(dest);}
      canvasStream=canvas.captureStream(30);recStream=new MediaStream([...canvasStream.getVideoTracks(),...dest.stream.getAudioTracks()]);
      const types=['video/mp4;codecs=avc1.42E01E,mp4a.40.2','video/mp4','video/webm;codecs=vp9,opus','video/webm;codecs=vp8,opus','video/webm'];const mime=types.find(t=>MediaRecorder.isTypeSupported(t))||'';const opts=mime?{mimeType:mime,videoBitsPerSecond:6500000}:undefined;const recorder=new MediaRecorder(recStream,opts);activeRecorder=recorder;recorder.ondataavailable=e=>{if(e.data&&e.data.size)chunks.push(e.data);};const stopped=new Promise((resolve,reject)=>{recorder.onstop=()=>resolve();recorder.onerror=e=>reject(e.error||new Error('שגיאת מקליט'));});
      let activeClip=null,activeSource=null,activeImage=null,drawRunning=true;
      const drawLoop=()=>{if(!drawRunning)return;try{if(activeClip&&activeSource)canvasDraw(ctx,activeSource,activeClip,w,h);else{ctx.fillStyle='#07070a';ctx.fillRect(0,0,w,h);}}catch(e){}raf=requestAnimationFrame(drawLoop);};drawLoop();
      recorder.start(1000);
      if(musicAudio){try{musicAudio.currentTime=0;await musicAudio.play();}catch(e){notify('דפדפן מנע את מוזיקת הרקע; ייצוא הקליפים ימשיך');}}
      for(let i=0;i<state.clips.length;i++){
        if(cancelExport)throw new Error('הייצוא בוטל');const clip=state.clips[i],m=getMedia(clip.mediaId);if(!m)continue;activeClip=clip;$('exportTitle').textContent=`מייצא קליפ ${i+1} מתוך ${state.clips.length}`;$('exportMessage').textContent=m.name;$('exportProgress').style.width=Math.round(i/state.clips.length*100)+'%';clipGain.gain.value=clip.volume??1;
        if(m.kind==='image'){const im=new Image();activeImage=im;await new Promise((resolve,reject)=>{im.onload=resolve;im.onerror=reject;im.src=m.url;});activeSource=im;await sleep(clipLength(clip)*1000);clearTimeout(activeExportTimer);}
        else if(m.kind==='video'){
          activeSource=renderVideo;if(renderVideo.src!==m.url){renderVideo.pause();renderVideo.src=m.url;renderVideo.load();await waitEvent(renderVideo,'loadedmetadata');}
          const start=Math.min(Math.max(0,clip.inPoint),Math.max(0,(renderVideo.duration||m.duration)-.05));renderVideo.currentTime=start;await new Promise(resolve=>{const check=()=>{if(renderVideo.readyState>=2){renderVideo.removeEventListener('seeked',check);resolve();}};renderVideo.addEventListener('seeked',check);setTimeout(()=>{renderVideo.removeEventListener('seeked',check);resolve();},3000);});
          renderVideo.playbackRate=Math.min(3,Math.max(.25,clip.speed||1));try{await renderVideo.play();}catch(e){throw new Error('לא ניתן לנגן את אחד מקובצי הווידאו לייצוא');}
          await sleep(clipLength(clip)*1000+80);clearTimeout(activeExportTimer);renderVideo.pause();
        }
        $('exportProgress').style.width=Math.round((i+1)/state.clips.length*100)+'%';
      }
      drawRunning=false;cancelAnimationFrame(raf);if(musicAudio)musicAudio.pause();if(renderVideo)renderVideo.pause();if(recorder.state!=='inactive')recorder.stop();await stopped;blobResult=new Blob(chunks,{type:recorder.mimeType||'video/webm'});if(cancelExport)throw new Error('הייצוא בוטל');
      const ext=(blobResult.type||'').includes('mp4')?'mp4':'webm';const date=new Date();const tag=[date.getFullYear(),String(date.getMonth()+1).padStart(2,'0'),String(date.getDate()).padStart(2,'0'),'-',String(date.getHours()).padStart(2,'0'),String(date.getMinutes()).padStart(2,'0')].join('');const saved=await downloadBlob(blobResult,'CUTLAB-'+tag+'.'+ext);if(!saved)throw new Error('השמירה בוטלה או נכשלה');$('exportTitle').textContent='הייצוא הושלם ✓';$('exportMessage').textContent=`הסרטון נשמר כקובץ ${ext.toUpperCase()} (${fmtSize(blobResult.size)}).`; $('exportProgress').style.width='100%';$('cancelExportBtn').textContent='סיום';notify('הסרטון יוצא בהצלחה');
    }catch(e){if(!cancelExport){$('exportTitle').textContent='לא הצלחנו לייצא';$('exportMessage').textContent=e?.message||'שגיאה לא צפויה במהלך הייצוא';$('cancelExportBtn').textContent='סגירה';notify(e?.message||'שגיאה בייצוא',5000);}else{$('exportTitle').textContent='הייצוא בוטל';$('exportMessage').textContent='הייצוא נעצר לפי בקשתך.';$('cancelExportBtn').textContent='סגירה';}}
    finally{if(activeExportTimer)clearTimeout(activeExportTimer);if(activeRecorder&&activeRecorder.state!=='inactive'){try{activeRecorder.stop();}catch{}}if(raf)cancelAnimationFrame(raf);try{if(recStream)recStream.getTracks().forEach(t=>t.stop());if(canvasStream)canvasStream.getTracks().forEach(t=>t.stop());if(audioCtx)await audioCtx.close();}catch{}activeRecorder=null;exporting=false;}
  }

  function switchPanel(name){document.querySelectorAll('.rail-btn').forEach(b=>b.classList.toggle('active',b.dataset.panel===name));document.querySelectorAll('.tool-section').forEach(p=>p.classList.toggle('active',p.id==='panel-'+name));}
  $('importDrop').addEventListener('click',()=>$('fileInput').click());$('startImportBtn').addEventListener('click',()=>$('fileInput').click());$('fileInput').addEventListener('change',async e=>{await addFiles(e.target.files);e.target.value='';});
  document.querySelectorAll('.rail-btn').forEach(b=>b.addEventListener('click',()=>switchPanel(b.dataset.panel)));
  $('clearMediaBtn').addEventListener('click',()=>{if(!state.media.size)return;if(!confirm('לנקות את ספריית המדיה ואת ציר הזמן?'))return;for(const m of state.media.values())URL.revokeObjectURL(m.url);state.media.clear();state.clips=[];state.selected=null;state.musicId=null;activeUrl='';renderAll();persistLocal();notify('הספרייה נוקתה');});
  $('applyTrimBtn').addEventListener('click',applyTrim);$('clipSpeed').addEventListener('change',()=>{const c=getClip();if(!c)return;pushUndo();c.speed=Number($('clipSpeed').value)||1;renderTimeline();updatePreview();persistLocal();});$('clipName').addEventListener('change',()=>{const c=getClip();if(!c)return;pushUndo();c.name=$('clipName').value.trim()||c.name;renderTimeline();persistLocal();});
  $('applyTextBtn').addEventListener('click',applyText);$('clearTextBtn').addEventListener('click',()=>{const c=getClip();if(!c)return;pushUndo();c.text='';$('clipText').value='';updatePreview();persistLocal();notify('הטקסט הוסר מהקליפ');});$('textSize').addEventListener('input',()=>{$('textSizeValue').textContent=$('textSize').value;const c=getClip();if(c){c.textSize=Number($('textSize').value);updatePreview();}});
  document.querySelectorAll('.filter-option').forEach(b=>b.addEventListener('click',()=>{const c=getClip();if(!c)return pushNoticeNoClip();pushUndo();c.filter=b.dataset.filter;applyLiveFilter();updatePreview();document.querySelectorAll('.filter-option').forEach(x=>x.classList.toggle('selected',x===b));persistLocal();}));
  $('chooseMusicBtn').addEventListener('click',()=>$('musicInput').click());$('musicInput').addEventListener('change',async e=>{const file=e.target.files?.[0];if(file){const m=await registerMedia(file);if(m){state.musicId=m.id;renderMediaList();updateMusicPanel();renderTimeline();persistLocal();notify('מוזיקת הרקע נבחרה');}}e.target.value='';});$('musicVolume').addEventListener('input',()=>{state.musicVolume=Number($('musicVolume').value)/100;$('musicVolumeValue').textContent=$('musicVolume').value+'%';renderTimeline();persistLocal();});$('clipVolume').addEventListener('input',()=>{const c=getClip();if(c){c.volume=Number($('clipVolume').value)/100;$('clipVolumeValue').textContent=$('clipVolume').value+'%';persistLocal();}});$('removeMusicBtn').addEventListener('click',()=>{state.musicId=null;updateMusicPanel();renderMediaList();renderTimeline();persistLocal();notify('מוזיקת הרקע הוסרה');});
  ['brightness','contrast','saturation'].forEach(id=>$(id).addEventListener('input',()=>{const c=getClip();const value=Number($(id).value)/100;$(id+'Value').textContent=$(''+id).value+'%';if(c){c[id]=value;applyLiveFilter();persistLocal();}}));
  $('resetAdjustBtn').addEventListener('click',()=>{const c=getClip();if(!c)return;pushUndo();c.brightness=c.contrast=c.saturation=1;c.filter='none';state.brightness=state.contrast=state.saturation=1;updateClipControls();updatePreview();persistLocal();notify('ההתאמות אופסו');});
  $('aspectRatio').addEventListener('change',()=>{state.ratio=$('aspectRatio').value;updatePreview();renderTimeline();persistLocal();});$('fitMode').addEventListener('change',()=>{state.fit=$('fitMode').value;updatePreview();persistLocal();});
  $('splitBtn').addEventListener('click',splitClip);$('duplicateBtn').addEventListener('click',duplicateClip);$('deleteClipBtn').addEventListener('click',deleteClip);$('undoBtn').addEventListener('click',undo);$('redoBtn').addEventListener('click',redo);$('exportBtn').addEventListener('click',runExport);$('saveProjectBtn').addEventListener('click',exportProject);$('openProjectBtn').addEventListener('click',()=>$('projectInput').click());$('projectInput').addEventListener('change',async e=>{if(e.target.files?.[0])await importProjectFile(e.target.files[0]);e.target.value='';});
  $('cancelExportBtn').addEventListener('click',()=>{if(exporting){cancelExport=true;if(activeRecorder&&activeRecorder.state!=='inactive'){try{activeRecorder.stop();}catch{}}return;} $('exportModal').hidden=true;});
  $('playBtn').addEventListener('click',async()=>{const c=getClip(),m=c&&getMedia(c.mediaId);if(!c||!m)return pushNoticeNoClip();if(m.kind==='image')return notify('תמונות יוצגו בתצוגה וייכללו בייצוא');const v=$('previewVideo');if(v.paused){if(v.currentTime<c.inPoint||v.currentTime>=c.outPoint)v.currentTime=c.inPoint;try{await v.play();}catch{notify('לחץ ישירות על הסרטון להפעלה');}}else v.pause();});
  $('jumpStartBtn').addEventListener('click',()=>{const c=getClip();if(!c)return;const v=$('previewVideo');if(getMedia(c.mediaId)?.kind==='video')v.currentTime=c.inPoint;window.previewCurrentTime=timelineOffset(c);renderTimeline();});$('jumpEndBtn').addEventListener('click',()=>{const c=getClip();if(!c)return;const v=$('previewVideo');if(getMedia(c.mediaId)?.kind==='video')v.currentTime=Math.max(c.inPoint,c.outPoint-.1);});
  $('previewVideo').addEventListener('timeupdate',()=>{const c=getClip();if(!c)return;updatePlayhead();if($('previewVideo').currentTime>=c.outPoint-.03&&!$('previewVideo').paused){$('previewVideo').pause();const i=state.clips.findIndex(x=>x.id===c.id);if(i>=0&&i<state.clips.length-1){selectClip(state.clips[i+1].id);const nc=getClip();if(nc&&getMedia(nc.mediaId)?.kind==='video'){$('previewVideo').currentTime=nc.inPoint;$('previewVideo').play().catch(()=>{});}}}});$('previewVideo').addEventListener('play',()=>{$('playBtn').textContent='Ⅱ';});$('previewVideo').addEventListener('pause',()=>{$('playBtn').textContent='▶';});
  $('playbackZoom').addEventListener('input',()=>{state.zoom=Number($('playbackZoom').value);renderTimeline();});$('mutePreviewBtn').addEventListener('click',()=>{previewMuted=!previewMuted;$('previewVideo').muted=previewMuted;$('mutePreviewBtn').textContent=previewMuted?'×♫':'♫';});$('fitStageBtn').addEventListener('click',()=>{$('previewFrame').classList.toggle('expanded');notify('יחס התצוגה נקבע לפי יחס המסך שנבחר בכלים');});$('previewZone').addEventListener('dragover',e=>{e.preventDefault();$('importDrop').style.borderColor='#b7a0ff';});$('previewZone').addEventListener('dragleave',()=>$('importDrop').style.borderColor='');$('previewZone').addEventListener('drop',async e=>{e.preventDefault();$('importDrop').style.borderColor='';await addFiles(e.dataTransfer.files);});
  window.addEventListener('beforeunload',()=>{try{if(activeRecorder&&activeRecorder.state!=='inactive')activeRecorder.stop();}catch{}});
  renderAll();restoreLocal();
})();
