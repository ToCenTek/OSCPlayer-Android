package com.oscvideoplayer

internal object FusionEditorHtml {
    val HTML: String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
<title>投影融合校准</title>
<style>
* { margin:0; padding:0; box-sizing:border-box; }
body { background:#0d0d1a; color:#d0d0d0; font-family:'Segoe UI',system-ui,sans-serif; overflow:hidden; height:100vh; display:flex; flex-direction:column; }
.header { display:flex; align-items:center; gap:12px; padding:8px 16px; background:#15152a; border-bottom:1px solid #2a2a4a; flex-shrink:0; }
.header h1 { font-size:15px; color:#6af; font-weight:600; letter-spacing:0.5px; }
.header .badge { font-size:10px; background:#2a2a4a; color:#88c; padding:2px 8px; border-radius:3px; }
.toolbar { display:flex; gap:6px; flex-wrap:wrap; align-items:center; padding:6px 16px; background:#11112a; border-bottom:1px solid #2a2a4a; flex-shrink:0; }
.toolbar button,.toolbar .btn { background:#222244; color:#ccc; border:1px solid #3a3a6a; padding:5px 12px; border-radius:3px; cursor:pointer; font-size:12px; transition:all .15s; white-space:nowrap; }
.toolbar button:hover { background:#333366; color:#fff; }
.toolbar button.active { background:#3a6aff; color:#fff; border-color:#5a8aff; }
.toolbar button.danger { color:#f88; border-color:#633; }
.toolbar button.danger:hover { background:#422; }
.toolbar label { font-size:11px; color:#88a; display:flex; align-items:center; gap:4px; }
.toolbar input[type=number] { background:#1a1a3a; color:#ddd; border:1px solid #3a3a6a; padding:3px 5px; border-radius:3px; width:48px; font-size:12px; }
.toolbar .sep { width:1px; height:20px; background:#2a2a4a; margin:0 4px; }
.toolbar .hint { font-size:10px; color:#556; margin-left:auto; }
.canvas-container { flex:1; display:flex; gap:4px; padding:4px; min-height:0; }
.view-wrap { flex:1; display:flex; flex-direction:column; min-width:0; }
.view-wrap .view-label { font-size:10px; color:#556; padding:2px 8px; text-transform:uppercase; letter-spacing:1px; }
.view-wrap canvas { flex:1; background:#0a0a1a; cursor:crosshair; display:block; width:100%; image-rendering:pixelated; }
.view-output canvas { background:#0a0a14; }
#statusBar { padding:3px 16px; font-size:11px; color:#556; background:#0d0d1a; border-top:1px solid #1a1a3a; flex-shrink:0; display:flex; gap:16px; }
#statusBar .ok { color:#4a4; }
#statusBar .err { color:#c44; }
@media(max-width:800px){ .canvas-container { flex-direction:column; } .header h1 { font-size:13px; } .toolbar { padding:4px 8px; } .toolbar button { font-size:11px; padding:3px 8px; } }
</style>
</head>
<body>
<div class="header">
  <h1>⬡ 投影融合校准</h1>
  <span class="badge" id="modeBadge">平面模式</span>
  <span style="margin-left:auto;font-size:11px;color:#556;" id="fpsDisplay"></span>
</div>
<div class="toolbar">
  <button id="btnEnable" onclick="toggleEnable()">◉ 开启融合</button>
  <div class="sep"></div>
  <label>网格 <input type="number" id="meshCols" value="9" min="2" max="65" onchange="resizeMesh()">x<input type="number" id="meshRows" value="9" min="2" max="65" onchange="resizeMesh()"></label>
  <button onclick="regularize()">▣ 均匀化</button>
  <button onclick="resetMesh()">↺ 重置</button>
  <div class="sep"></div>
  <button onclick="fitSelected()">⊞ 适配选中</button>
  <button onclick="alignGrid('left')">◀ 左对齐</button>
  <button onclick="alignGrid('center')">⇔ 居中</button>
  <button onclick="alignGrid('right')">▶ 右对齐</button>
  <div class="sep"></div>
  <button onclick="resetSource()">源重置</button>
  <span class="hint">WASD选点 ↑↓←→移动 Shift多选 Tab循环</span>
</div>
<div class="canvas-container">
  <div class="view-wrap view-source">
    <div class="view-label">◈ 源 (Source)</div>
    <canvas id="srcCanvas"></canvas>
  </div>
  <div class="view-wrap view-output">
    <div class="view-label">◉ 输出 (Output)</div>
    <canvas id="outCanvas"></canvas>
  </div>
</div>
<div id="statusBar">
  <span id="statusConn" class="err">● 未连接</span>
  <span id="statusInfo"></span>
  <span id="statusPos"></span>
  <span id="statusSel"></span>
</div>
<script>
const SRC = document.getElementById('srcCanvas');
const OUT = document.getElementById('outCanvas');
const SX = SRC.getContext('2d');
const OX = OUT.getContext('2d');
const sConn = document.getElementById('statusConn');
const sInfo = document.getElementById('statusInfo');
const sPos = document.getElementById('statusPos');
const sSel = document.getElementById('statusSel');

let mesh = null, enabled = false;
let src = {x:0,y:0,w:1,h:1};
let sel = new Set();
let curR = 4, curC = 4;
let z = 1, px = 0, py = 0;
let drag = null, start = null, mx = 0, my = 0;

function resize() {
  const w = document.querySelector('.canvas-container');
  const hw = (w.clientWidth - 8) / 2, hh = w.clientHeight - 8;
  SRC.width = Math.max(100, hw); SRC.height = Math.max(80, hh);
  OUT.width = SRC.width; OUT.height = SRC.height;
}
resize();
window.addEventListener('resize', resize);

async function api(p, o) {
  const r = await fetch(p, o); return r.json();
}

async function load() {
  try {
    const s = await api('/fusion/api/state');
    enabled = s.enabled;
    document.getElementById('btnEnable').textContent = enabled ? '◉ 关闭' : '◉ 开启';
    document.getElementById('btnEnable').className = enabled ? 'active' : '';
    if (s.mesh) {
      if (!mesh || s.mesh.rows !== mesh.rows || s.mesh.cols !== mesh.cols) {
        mesh = s.mesh;
        document.getElementById('meshRows').value = mesh.rows;
        document.getElementById('meshCols').value = mesh.cols;
        curR = Math.min(curR, mesh.rows-1);
        curC = Math.min(curC, mesh.cols-1);
      } else mesh.points = s.mesh.points;
    }
    if (s.source) src = s.source;
    sConn.className = 'ok'; sConn.textContent = '● 已连接';
    draw();
  } catch(e) {
    sConn.className = 'err'; sConn.textContent = '● 失败';
  }
}

async function setPts(pts) {
  await api('/fusion/api/mesh', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:'set_multi',points:pts})});
}

// ─── Drawing ───
function draw() {
  SX.clearRect(0,0,SRC.width,SRC.height);
  OX.clearRect(0,0,OUT.width,OUT.height);
  if (!mesh) return;
  drawSrc(); drawOut(); status();
}

function drawSrc() {
  const w=SRC.width, h=SRC.height;
  SX.fillStyle='#0e0e20'; SX.fillRect(0,0,w,h);
  const sx=src.x*w, sy=src.y*h, sw=src.w*w, sh=src.h*h;
  SX.strokeStyle='#6af'; SX.lineWidth=2; SX.strokeRect(sx,sy,sw,sh);
  SX.fillStyle='rgba(100,170,255,0.05)'; SX.fillRect(sx,sy,sw,sh);
  SX.strokeStyle='rgba(100,170,255,0.15)'; SX.lineWidth=1;
  for(let r=0;r<mesh.rows;r++){ const t=r/(mesh.rows-1); SX.beginPath(); SX.moveTo(sx,sy+t*sh); SX.lineTo(sx+sw,sy+t*sh); SX.stroke(); }
  for(let c=0;c<mesh.cols;c++){ const t=c/(mesh.cols-1); SX.beginPath(); SX.moveTo(sx+t*sw,sy); SX.lineTo(sx+t*sw,sy+sh); SX.stroke(); }
  SX.fillStyle='#447'; SX.font='11px sans-serif';
  SX.fillText('源: '+(src.w*100).toFixed(0)+'% x '+(src.h*100).toFixed(0)+'%',4,14);
}

function drawOut() {
  const w=OUT.width, h=OUT.height;
  OX.fillStyle='#0a0a18'; OX.fillRect(0,0,w,h);
  if(!mesh||!mesh.points)return;
  const {rows,cols,points}=mesh;
  const ox_ = w/2 + px*10, oy_ = h/2 + py*10;
  function mp(mx_,my_){ return {x: mx_*z*w + ox_ - z*w/2, y: my_*z*h + oy_ - z*h/2}; }
  const hs = sel.size > 0;
  for(let r=0;r<rows-1;r++) for(let c=0;c<cols-1;c++){
    const p00=mp(points[r][c].x,points[r][c].y), p10=mp(points[r][c+1].x,points[r][c+1].y);
    const p01=mp(points[r+1][c].x,points[r+1][c].y), p11=mp(points[r+1][c+1].x,points[r+1][c+1].y);
    const sd = hs && (sel.has(r+','+c)||sel.has(r+','+(c+1))||sel.has((r+1)+','+c)||sel.has((r+1)+','+(c+1)));
    OX.beginPath(); OX.moveTo(p00.x,p00.y); OX.lineTo(p10.x,p10.y); OX.lineTo(p11.x,p11.y); OX.lineTo(p01.x,p01.y); OX.closePath();
    OX.fillStyle=sd?'rgba(100,170,255,0.12)':'rgba(60,60,120,0.04)'; OX.fill();
    OX.strokeStyle='rgba(255,255,255,0.06)'; OX.lineWidth=0.5; OX.stroke();
  }
  OX.strokeStyle='rgba(0,212,255,0.35)'; OX.lineWidth=1;
  for(let r=0;r<rows;r++){ OX.beginPath(); let s=mp(points[r][0].x,points[r][0].y); OX.moveTo(s.x,s.y); for(let c=1;c<cols;c++){let p=mp(points[r][c].x,points[r][c].y);OX.lineTo(p.x,p.y);}OX.stroke(); }
  for(let c=0;c<cols;c++){ OX.beginPath(); let s=mp(points[0][c].x,points[0][c].y); OX.moveTo(s.x,s.y); for(let r=1;r<rows;r++){let p=mp(points[r][c].x,points[r][c].y);OX.lineTo(p.x,p.y);}OX.stroke(); }
  OX.strokeStyle='rgba(255,100,100,0.25)'; OX.lineWidth=2; OX.setLineDash([4,6]);
  if(cols>1){ OX.beginPath(); let s=mp(points[0][0].x,points[0][0].y); OX.moveTo(s.x,s.y); for(let r=1;r<rows;r++){let p=mp(points[r][0].x,points[r][0].y);OX.lineTo(p.x,p.y);}OX.stroke(); }
  if(cols>1){ OX.beginPath(); let s=mp(points[0][cols-1].x,points[0][cols-1].y); OX.moveTo(s.x,s.y); for(let r=1;r<rows;r++){let p=mp(points[r][cols-1].x,points[r][cols-1].y);OX.lineTo(p.x,p.y);}OX.stroke(); }
  if(rows>1){ OX.beginPath(); let s=mp(points[0][0].x,points[0][0].y); OX.moveTo(s.x,s.y); for(let c=1;c<cols;c++){let p=mp(points[0][c].x,points[0][c].y);OX.lineTo(p.x,p.y);}OX.stroke(); }
  if(rows>1){ OX.beginPath(); let s=mp(points[rows-1][0].x,points[rows-1][0].y); OX.moveTo(s.x,s.y); for(let c=1;c<cols;c++){let p=mp(points[rows-1][c].x,points[rows-1][c].y);OX.lineTo(p.x,p.y);}OX.stroke(); }
  OX.setLineDash([]);
  for(let r=0;r<rows;r++) for(let c=0;c<cols;c++){
    const p=mp(points[r][c].x,points[r][c].y);
    const ic=r===curR&&c===curC, is_=sel.has(r+','+c), ie=r===0||r===rows-1||c===0||c===cols-1;
    OX.beginPath(); OX.arc(p.x,p.y,ic?7:(is_?6:4),0,Math.PI*2);
    if(ic){OX.fillStyle='#ff0';OX.strokeStyle='#fff';OX.lineWidth=2;OX.fill();OX.stroke();}
    else if(is_){OX.fillStyle='#fa0';OX.strokeStyle='#fff';OX.lineWidth=1.5;OX.fill();OX.stroke();}
    else if(ie){OX.fillStyle='#f80';OX.fill();}
    else{OX.fillStyle='#0df';OX.fill();}
  }
  if(drag==='box'&&start){ OX.strokeStyle='rgba(255,255,100,0.5)'; OX.lineWidth=1; OX.setLineDash([3,3]); OX.strokeRect(start.x,start.y,mx-start.x,my-start.y); OX.setLineDash([]); }
  OX.fillStyle='#447'; OX.font='11px sans-serif';
  OX.fillText(cols+'x'+rows+' | 选中 '+sel.size+' 点',4,14);
}

function status() {
  if(!mesh)return;
  const p=mesh.points[curR][curC];
  sInfo.textContent = '光标: ['+curR+','+curC+']';
  sPos.textContent = '位置: ('+(p.x*100).toFixed(1)+'%, '+(p.y*100).toFixed(1)+'%)';
  sSel.textContent = '选中: '+sel.size+' 点';
}

// ─── Interaction ───
function toMesh(cx,cy){
  return {x:(cx-OUT.width/2-px*10)/(z*OUT.width)+0.5, y:(cy-OUT.height/2-py*10)/(z*OUT.height)+0.5};
}

function findPt(cx,cy){
  if(!mesh)return null;
  let best=null,bd=Infinity;
  for(let r=0;r<mesh.rows;r++) for(let c=0;c<mesh.cols;c++){
    const p=mesh.points[r][c];
    const sx_=(p.x-0.5)*z*OUT.width+OUT.width/2+px*10;
    const sy_=(p.y-0.5)*z*OUT.height+OUT.height/2+py*10;
    const d=(cx-sx_)**2+(cy-sy_)**2;
    if(d<bd){bd=d;best={row:r,col:c};}
  }
  return bd<400?best:null;
}

OUT.addEventListener('mousedown',e=>{
  const r=OUT.getBoundingClientRect();
  mx=(e.clientX-r.left)*OUT.width/r.width; my=(e.clientY-r.top)*OUT.height/r.height;
  const hit=findPt(mx,my);
  if(hit){
    if(e.shiftKey){const k=hit.row+','+hit.col;if(sel.has(k))sel.delete(k);else sel.add(k);}
    else{sel.clear();sel.add(hit.row+','+hit.col);curR=hit.row;curC=hit.col;}
    drag='pt'; start={row:hit.row,col:hit.col}; draw(); return;
  }
  if(e.button===2||e.altKey){drag='pan';start={x:e.clientX,y:e.clientY};return;}
  drag='box'; start={x:mx,y:my};
});

OUT.addEventListener('mousemove',e=>{
  const r=OUT.getBoundingClientRect();
  mx=(e.clientX-r.left)*OUT.width/r.width; my=(e.clientY-r.top)*OUT.height/r.height;
  if(drag==='pt'&&mesh){const p=toMesh(mx,my);for(const k of sel){const rc=k.split(',');mesh.points[+rc[0]][+rc[1]].x=Math.max(0,Math.min(1,p.x));mesh.points[+rc[0]][+rc[1]].y=Math.max(0,Math.min(1,p.y));}draw();}
  if(drag==='pan'){px+=(e.clientX-start.x)/10;py+=(e.clientY-start.y)/10;start={x:e.clientX,y:e.clientY};draw();}
  if(drag==='box'){draw();}
});

OUT.addEventListener('mouseup',e=>{
  if(drag==='box'&&start){const x1=Math.min(start.x,mx),x2=Math.max(start.x,mx),y1=Math.min(start.y,my),y2=Math.max(start.y,my);sel.clear();
    for(let r=0;r<mesh.rows;r++)for(let c=0;c<mesh.cols;c++){const px=(mesh.points[r][c].x-0.5)*z*OUT.width+OUT.width/2+px*10,py=(mesh.points[r][c].y-0.5)*z*OUT.height+OUT.height/2+py*10;if(px>=x1&&px<=x2&&py>=y1&&py<=y2)sel.add(r+','+c);}draw();}
  if(drag==='pt'&&mesh){const pts=[];for(const k of sel){const rc=k.split(',');pts.push({row:+rc[0],col:+rc[1],x:mesh.points[+rc[0]][+rc[1]].x,y:mesh.points[+rc[0]][+rc[1]].y});}if(pts.length)setPts(pts);}
  drag=null;start=null;
});

OUT.addEventListener('mouseleave',()=>{drag=null;});
OUT.addEventListener('wheel',e=>{e.preventDefault();z*=e.deltaY>0?0.92:1.08;z=Math.max(0.2,Math.min(10,z));draw();},{passive:false});
OUT.addEventListener('contextmenu',e=>e.preventDefault());

// Touch
OUT.addEventListener('touchstart',e=>{e.preventDefault();const t=e.touches;
  if(t.length===1){const r=OUT.getBoundingClientRect();const mx_=(t[0].clientX-r.left)*OUT.width/r.width,my_=(t[0].clientY-r.top)*OUT.height/r.height;const hit=findPt(mx_,my_);if(hit){sel.clear();sel.add(hit.row+','+hit.col);curR=hit.row;curC=hit.col;drag='pt';}draw();}});
OUT.addEventListener('touchmove',e=>{e.preventDefault();if(drag!=='pt'||!mesh)return;const t=e.touches[0],r=OUT.getBoundingClientRect();const mx_=(t[0].clientX-r.left)*OUT.width/r.width,my_=(t[0].clientY-r.top)*OUT.height/r.height;const p=toMesh(mx_,my_);for(const k of sel){const rc=k.split(',');mesh.points[+rc[0]][+rc[1]].x=Math.max(0,Math.min(1,p.x));mesh.points[+rc[0]][+rc[1]].y=Math.max(0,Math.min(1,p.y));}draw();});
OUT.addEventListener('touchend',()=>{if(drag==='pt'&&mesh){const pts=[];for(const k of sel){const rc=k.split(',');pts.push({row:+rc[0],col:+rc[1],x:mesh.points[+rc[0]][+rc[1]].y,y:mesh.points[+rc[0]][+rc[1]].y});}if(pts.length)setPts(pts);}drag=null;});

// Keyboard
document.addEventListener('keydown',e=>{
  if(!mesh)return;
  const R=mesh.rows,C=mesh.cols;let mv=false;
  if(e.key==='w'){curR=Math.max(0,curR-1);mv=true;}
  if(e.key==='s'){curR=Math.min(R-1,curR+1);mv=true;}
  if(e.key==='a'){curC=Math.max(0,curC-1);mv=true;}
  if(e.key==='d'){curC=Math.min(C-1,curC+1);mv=true;}
  if(mv){e.preventDefault();if(e.shiftKey)sel.add(curR+','+curC);else if(!e.ctrlKey&&!e.metaKey){sel.clear();sel.add(curR+','+curC);}draw();return;}
  const st=e.shiftKey?0.001:0.005;let nu=false;
  if(e.key==='ArrowUp'){nu=true;for(const k of sel){const rc=k.split(',');mesh.points[+rc[0]][+rc[1]].y=Math.max(0,mesh.points[+rc[0]][+rc[1]].y-st);}}
  if(e.key==='ArrowDown'){nu=true;for(const k of sel){const rc=k.split(',');mesh.points[+rc[0]][+rc[1]].y=Math.min(1,mesh.points[+rc[0]][+rc[1]].y+st);}}
  if(e.key==='ArrowLeft'){nu=true;for(const k of sel){const rc=k.split(',');mesh.points[+rc[0]][+rc[1]].x=Math.max(0,mesh.points[+rc[0]][+rc[1]].x-st);}}
  if(e.key==='ArrowRight'){nu=true;for(const k of sel){const rc=k.split(',');mesh.points[+rc[0]][+rc[1]].x=Math.min(1,mesh.points[+rc[0]][+rc[1]].x+st);}}
  if(nu){e.preventDefault();const pts=[];for(const k of sel){const rc=k.split(',');pts.push({row:+rc[0],col:+rc[1],x:mesh.points[+rc[0]][+rc[1]].x,y:mesh.points[+rc[0]][+rc[1]].y});}setPts(pts);draw();return;}
  if(e.key==='Tab'){e.preventDefault();if(sel.size===R*C)sel.clear();else for(let r=0;r<R;r++)for(let c=0;c<C;c++)sel.add(r+','+c);draw();}
  if((e.ctrlKey||e.metaKey)&&e.key==='a'){e.preventDefault();for(let r=0;r<R;r++)for(let c=0;c<C;c++)sel.add(r+','+c);draw();}
  if(e.key==='Escape'){sel.clear();draw();}
  if(e.key==='r'&&!e.ctrlKey&&!e.metaKey){regularize();}
});

// ─── Toolbar actions ───
async function toggleEnable(){const s=await api('/fusion/api/enable',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({enable:!enabled})});enabled=s.enabled;document.getElementById('btnEnable').textContent=enabled?'◉ 关闭':'◉ 开启';document.getElementById('btnEnable').className=enabled?'active':'';}
async function regularize(){const r=await api('/fusion/api/mesh',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:'regularize'})});mesh=r;draw();}
async function resetMesh(){document.getElementById('meshRows').value=9;document.getElementById('meshCols').value=9;const r=await api('/fusion/api/mesh',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:'reset'})});mesh=r;sel.clear();curR=4;curC=4;draw();}
async function resizeMesh(){const cols=+document.getElementById('meshCols').value||9,rows=+document.getElementById('meshRows').value||9;const r=await api('/fusion/api/mesh',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:'resize',cols,rows})});mesh=r;curR=Math.min(curR,rows-1);curC=Math.min(curC,cols-1);sel.clear();draw();}
function resetSource(){src={x:0,y:0,w:1,h:1};draw();}
function alignGrid(d){if(!mesh||!sel.size)return;let mn=1,mx=0;for(const k of sel){const rc=k.split(',');const p=mesh.points[+rc[0]][+rc[1]];if(p.x<mn)mn=p.x;if(p.x>mx)mx=p.x;}
  const cx=(mn+mx)/2;const pts=[];for(const k of sel){const rc=k.split(',');if(d==='left')mesh.points[+rc[0]][+rc[1]].x=mn;else if(d==='right')mesh.points[+rc[0]][+rc[1]].x=mx;else mesh.points[+rc[0]][+rc[1]].x=cx;pts.push({row:+rc[0],col:+rc[1],x:mesh.points[+rc[0]][+rc[1]].x,y:mesh.points[+rc[0]][+rc[1]].y});}setPts(pts);draw();}
function fitSelected(){if(!mesh||sel.size<2)return;let mnx=1,mxx=0,mny=1,mxy=0;for(const k of sel){const rc=k.split(',');const p=mesh.points[+rc[0]][+rc[1]];if(p.x<mnx)mnx=p.x;if(p.x>mxx)mxx=p.x;if(p.y<mny)mny=p.y;if(p.y>mxy)mxy=p.y;}
  const pts=[];for(const k of sel){const rc=k.split(',');const t=mesh.rows>1?+rc[0]/(mesh.rows-1):0.5,s=mesh.cols>1?+rc[1]/(mesh.cols-1):0.5;mesh.points[+rc[0]][+rc[1]].x=mnx+(mxx-mnx)*s;mesh.points[+rc[0]][+rc[1]].y=mny+(mxy-mny)*t;pts.push({row:+rc[0],col:+rc[1],x:mesh.points[+rc[0]][+rc[1]].x,y:mesh.points[+rc[0]][+rc[1]].y});}setPts(pts);draw();}

load();
setInterval(load, 3000);
</script>
</body>
</html>
    """.trimIndent()
}
