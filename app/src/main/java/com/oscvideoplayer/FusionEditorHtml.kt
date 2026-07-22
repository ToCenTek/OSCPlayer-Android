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
*{margin:0;padding:0;box-sizing:border-box}
body{background:#0d0d1a;color:#d0d0d0;font-family:system-ui,sans-serif;overflow:hidden;height:100vh;display:flex;flex-direction:column}
.header{display:flex;align-items:center;gap:12px;padding:8px 16px;background:#15152a;border-bottom:1px solid #2a2a4a;flex-shrink:0}
.header h1{font-size:15px;color:#6af}
.toolbar{display:flex;gap:6px;flex-wrap:wrap;align-items:center;padding:6px 16px;background:#11112a;border-bottom:1px solid #2a2a4a;flex-shrink:0}
.toolbar button{background:#222244;color:#ccc;border:1px solid #3a3a6a;padding:5px 12px;border-radius:3px;cursor:pointer;font-size:12px}
.toolbar button:hover{background:#333366;color:#fff}
.toolbar button.active{background:#3a6aff;color:#fff;border-color:#5a8aff}
.toolbar label{font-size:11px;color:#88a;display:flex;align-items:center;gap:4px}
.toolbar input[type=number]{background:#1a1a3a;color:#ddd;border:1px solid #3a3a6a;padding:3px 5px;border-radius:3px;width:42px;font-size:12px}
.toolbar .sep{width:1px;height:20px;background:#2a2a4a;margin:0 4px}
.canvas-container{flex:1;display:flex;gap:4px;padding:4px;min-height:0}
.view-wrap{flex:1;display:flex;flex-direction:column;min-width:0}
.view-wrap .view-label{font-size:10px;color:#556;padding:2px 8px;text-transform:uppercase;letter-spacing:1px}
.view-wrap canvas{flex:1;background:#0a0a1a;cursor:crosshair;display:block;width:100%}
#statusBar{padding:3px 16px;font-size:11px;color:#556;background:#0d0d1a;border-top:1px solid #1a1a3a;flex-shrink:0;display:flex;gap:16px}
#statusBar .ok{color:#4a4}
#statusBar .err{color:#c44}
@media(max-width:800px){.canvas-container{flex-direction:column}}
</style>
</head>
<body>
<div class="header"><h1>网格校正</h1><span style="font-size:10px;color:#556" id="fpsDisplay"></span></div>
<div class="toolbar">
<button id="btnEnable" onclick="toggle()">开启</button>
<div class="sep"></div>
<label>X <button onclick="subdiv(-1,0)">−</button><span id="subdivX">0</span><button onclick="subdiv(1,0)">+</button></label>
<label>Y <button onclick="subdiv(0,-1)">−</button><span id="subdivY">0</span><button onclick="subdiv(0,1)">+</button></label>
<div class="sep"></div>
<button onclick="regularize()">均匀化</button>
<button onclick="resetMesh()">重置</button>
<button onclick="resetSource()">源重置</button>
<span style="font-size:10px;color:#556;margin-left:auto">WASD选点 ↑↓→←移动 Shift+WASD扩选 Shift+箭头微调</span>
</div>
<div class="canvas-container">
<div class="view-wrap view-source"><div class="view-label">源</div><canvas id="srcCanvas"></canvas></div>
<div class="view-wrap view-output"><div class="view-label">输出</div><canvas id="outCanvas"></canvas></div>
</div>
<div id="statusBar"><span id="sConn" class="err">● 未连接</span><span id="sInfo"></span><span id="sPos"></span><span id="sSel"></span></div>
<script>
const SC=document.getElementById('srcCanvas'),OC=document.getElementById('outCanvas')
const SX=SC.getContext('2d'),OX=OC.getContext('2d')
let mesh=null, src={x:0,y:0,w:1,h:1}, en=false, aspect=16/9
let sel=new Set(), cr=1, cc=1
let z=1, px=0, py=0, drag=null, start=null

function resize(){
  const w=document.querySelector('.canvas-container'), hw=(w.clientWidth-8)/2, hh=w.clientHeight-8
  SC.width=Math.max(100,hw);SC.height=Math.max(80,hh);OC.width=SC.width;OC.height=SC.height
}
resize();window.addEventListener('resize',resize)

async function api(p,o){return await(await fetch(p,o||{})).json()}

async function load(){
  try{
    const s=await api('/fusion/api/state')
    en=s.enabled; document.getElementById('btnEnable').textContent=en?'关闭':'开启'
    document.getElementById('btnEnable').className=en?'active':''
    if(s.mesh){
      if(!mesh||s.mesh.cols!==mesh.cols||s.mesh.rows!==mesh.rows){
        mesh=s.mesh; cr=Math.min(cr,mesh.rows-1); cc=Math.min(cc,mesh.cols-1)
      }else mesh.points=s.mesh.points
      document.getElementById('subdivX').textContent=s.mesh.subdivX||0
      document.getElementById('subdivY').textContent=s.mesh.subdivY||0
    }
    if(s.displayWidth&&s.displayHeight){aspect=s.displayWidth/s.displayHeight}
    if(s.source) src=s.source
    document.getElementById('sConn').className='ok'; document.getElementById('sConn').textContent='● 已连接'
    draw()
  }catch(e){document.getElementById('sConn').className='err';document.getElementById('sConn').textContent='● 失败'}
}

async function subdiv(dx,dy){
  const s=await api('/fusion/api/mesh',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({action:'subdiv',subdivX:Math.max(0,(mesh.subdivX||0)+dx),subdivY:Math.max(0,(mesh.subdivY||0)+dy)})})
  mesh=s; cr=Math.min(cr,mesh.rows-1); cc=Math.min(cc,mesh.cols-1); sel.clear()
  document.getElementById('subdivX').textContent=mesh.subdivX; document.getElementById('subdivY').textContent=mesh.subdivY
  draw()
}

async function setPts(pts){await api('/fusion/api/mesh',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:'set_multi',points:pts})})}

function draw(){
  SX.clearRect(0,0,SC.width,SC.height); OX.clearRect(0,0,OC.width,OC.height)
  if(!mesh)return
  drawSrc(); drawOut(); updateStatus()
}

function drawSrc(){
  const w=SC.width,h=SC.height
  SX.fillStyle='#0e0e20';SX.fillRect(0,0,w,h)
  const sx=src.x*w,sy=src.y*h,sw=src.w*w,sh=src.h*h
  SX.strokeStyle='#6af';SX.lineWidth=2;SX.strokeRect(sx,sy,sw,sh)
  SX.fillStyle='rgba(100,170,255,0.05)';SX.fillRect(sx,sy,sw,sh)
  SX.fillStyle='#447';SX.font='11px sans-serif';SX.fillText('源 '+(src.w*100).toFixed(0)+'%',4,14)
}

function drawOut(){
  const w=OC.width,h=OC.height
  OX.fillStyle='#0a0a18';OX.fillRect(0,0,w,h)
  // output area matching display aspect ratio
  const aw=w*0.85,ah=aw/aspect
  const ox0=(w-aw)/2,oy0=(h-ah)/2
  OX.fillStyle='#000';OX.fillRect(ox0,oy0,aw,ah)
  OX.strokeStyle='#333';OX.lineWidth=1;OX.strokeRect(ox0,oy0,aw,ah)

  if(!mesh||!mesh.points)return
  const {rows,cols,points}=mesh
  const sc = Math.min(z, 5)
  function mp(mx,my){
    return {x:ox0+mx*aw, y:oy0+my*ah}
  }

  // Grid cells
  for(let r=0;r<rows-1;r++)for(let c=0;c<cols-1;c++){
    const p00=mp(points[r][c].x,points[r][c].y),p10=mp(points[r][c+1].x,points[r][c+1].y)
    const p01=mp(points[r+1][c].x,points[r+1][c].y),p11=mp(points[r+1][c+1].x,points[r+1][c+1].y)
    OX.beginPath();OX.moveTo(p00.x,p00.y);OX.lineTo(p10.x,p10.y);OX.lineTo(p11.x,p11.y);OX.lineTo(p01.x,p01.y);OX.closePath()
    OX.fillStyle='rgba(60,60,120,0.04)';OX.fill()
    OX.strokeStyle='rgba(255,255,255,0.06)';OX.lineWidth=0.5;OX.stroke()
  }

  // Grid lines
  OX.strokeStyle='rgba(0,212,255,0.35)';OX.lineWidth=1
  for(let r=0;r<rows;r++){OX.beginPath();let s=mp(points[r][0].x,points[r][0].y);OX.moveTo(s.x,s.y);for(let c=1;c<cols;c++){let p=mp(points[r][c].x,points[r][c].y);OX.lineTo(p.x,p.y)}OX.stroke()}
  for(let c=0;c<cols;c++){OX.beginPath();let s=mp(points[0][c].x,points[0][c].y);OX.moveTo(s.x,s.y);for(let r=1;r<rows;r++){let p=mp(points[r][c].x,points[r][c].y);OX.lineTo(p.x,p.y)}OX.stroke()}

  // Edge blend overlay
  OX.strokeStyle='rgba(255,100,100,0.25)';OX.lineWidth=2;OX.setLineDash([4,6])
  if(cols>1&&rows>1){
    OX.beginPath();for(let r=0;r<rows;r++){let p=mp(points[r][0].x,points[r][0].y);r===0?OX.moveTo(p.x,p.y):OX.lineTo(p.x,p.y)}OX.stroke()
    OX.beginPath();for(let r=0;r<rows;r++){let p=mp(points[r][cols-1].x,points[r][cols-1].y);r===0?OX.moveTo(p.x,p.y):OX.lineTo(p.x,p.y)}OX.stroke()
    OX.beginPath();for(let c=0;c<cols;c++){let p=mp(points[0][c].x,points[0][c].y);c===0?OX.moveTo(p.x,p.y):OX.lineTo(p.x,p.y)}OX.stroke()
    OX.beginPath();for(let c=0;c<cols;c++){let p=mp(points[rows-1][c].x,points[rows-1][c].y);c===0?OX.moveTo(p.x,p.y):OX.lineTo(p.x,p.y)}OX.stroke()
  }
  OX.setLineDash([])

  // Control points
  for(let r=0;r<rows;r++)for(let c=0;c<cols;c++){
    const p=mp(points[r][c].x,points[r][c].y)
    const ic=r===cr&&c===cc,is=sel.has(r+','+c),ie=r===0||r===rows-1||c===0||c===cols-1
    OX.beginPath();OX.arc(p.x,p.y,ic?7:(is?6:4),0,Math.PI*2)
    if(ic){OX.fillStyle='#ff0';OX.strokeStyle='#fff';OX.lineWidth=2;OX.fill();OX.stroke()}
    else if(is){OX.fillStyle='#fa0';OX.strokeStyle='#fff';OX.lineWidth=1.5;OX.fill();OX.stroke()}
    else if(ie){OX.fillStyle='#f80';OX.fill()}
    else{OX.fillStyle='#0df';OX.fill()}
  }

  OX.fillStyle='#447';OX.font='11px sans-serif'
  OX.fillText(cols+'x'+rows+' | 选中 '+sel.size,4,14)
}

function updateStatus(){
  if(!mesh)return
  const p=mesh.points[cr][cc]
  document.getElementById('sInfo').textContent='光标 ['+cr+','+cc+']'
  document.getElementById('sPos').textContent='位置 ('+(p.x*100).toFixed(1)+'%,'+(p.y*100).toFixed(1)+'%)'
  document.getElementById('sSel').textContent='选中 '+sel.size
}

function findPt(cx,cy){
  if(!mesh)return null
  let best=null,bd=Infinity
  const aw=OC.width*0.85,ah=aw/aspect,ox0=(OC.width-aw)/2,oy0=(OC.height-ah)/2
  for(let r=0;r<mesh.rows;r++)for(let c=0;c<mesh.cols;c++){
    const p=mesh.points[r][c],sx=ox0+p.x*aw,sy=oy0+p.y*ah
    const d=(cx-sx)**2+(cy-sy)**2
    if(d<bd){bd=d;best={row:r,col:c}}
  }
  return bd<600?best:null
}

// Mouse
let dragOrigins = {} // store initial positions of selected points on drag start

OC.addEventListener('mousedown',e=>{
  const r=OC.getBoundingClientRect(),mx=(e.clientX-r.left)*OC.width/r.width,my=(e.clientY-r.top)*OC.height/r.height
  const hit=findPt(mx,my)
  if(hit){
    if(e.shiftKey){const k=hit.row+','+hit.col;if(sel.has(k))sel.delete(k);else sel.add(k)}
    else if(e.ctrlKey||e.metaKey){sel.add(hit.row+','+hit.col)}
    else{// Start drag with all selected points
      if(!sel.has(hit.row+','+hit.col)){sel.clear();sel.add(hit.row+','+hit.col)}
      cr=hit.row;cc=hit.col;drag='pt'
      // Store initial positions
      dragOrigins={mx:mx,my:my,pts:{}}
      for(const k of sel){const rc=k.split(',');const p=mesh.points[+rc[0]][+rc[1]];dragOrigins.pts[k]={x:p.x,y:p.y}}
    }
    draw();return
  }
  if(e.button===2||e.altKey){drag='pan';start={x:e.clientX,y:e.clientY};return}
  drag='box';start={x:mx,y:my}
})

OC.addEventListener('mousemove',e=>{
  const r=OC.getBoundingClientRect(),mx=(e.clientX-r.left)*OC.width/r.width,my=(e.clientY-r.top)*OC.height/r.height
  if(drag==='pt'&&mesh&&dragOrigins){const aw=OC.width*0.85,ah=aw/aspect,ox0=(OC.width-aw)/2,oy0=(OC.height-ah)/2
    const dx=(mx-dragOrigins.mx)/aw,dy=(my-dragOrigins.my)/ah
    for(const k of sel){const o=dragOrigins.pts[k];if(o){const rc=k.split(',');mesh.points[+rc[0]][+rc[1]].x=o.x+dx;mesh.points[+rc[0]][+rc[1]].y=o.y+dy}}
    draw()
  }
  if(drag==='pan'){px+=(e.clientX-start.x)/10;py+=(e.clientY-start.y)/10;start={x:e.clientX,y:e.clientY};draw()}
  if(drag==='box'){start=start||{x:mx,y:my};draw()
    const aw=OC.width*0.85,ah=aw/aspect,ox0=(OC.width-aw)/2,oy0=(OC.height-ah)/2
    OX.strokeStyle='rgba(255,255,100,0.5)';OX.lineWidth=1;OX.setLineDash([3,3])
    OX.strokeRect(Math.min(start.x,mx),Math.min(start.y,my),Math.abs(mx-start.x),Math.abs(my-start.y))
    OX.setLineDash([])
  }
})

OC.addEventListener('mouseup',e=>{
  if(drag==='box'&&start){const r=OC.getBoundingClientRect(),mx=(e.clientX-r.left)*OC.width/r.width,my=(e.clientY-r.top)*OC.height/r.height
    const x1=Math.min(start.x,mx),x2=Math.max(start.x,mx),y1=Math.min(start.y,my),y2=Math.max(start.y,my),aw=OC.width*0.85,ah=aw/aspect,ox0=(OC.width-aw)/2,oy0=(OC.height-ah)/2
    if(x2-x1>3||y2-y1>3){sel.clear()
      for(let r=0;r<mesh.rows;r++)for(let c=0;c<mesh.cols;c++){const px=ox0+mesh.points[r][c].x*aw,py=oy0+mesh.points[r][c].y*ah;if(px>=x1&&px<=x2&&py>=y1&&py<=y2)sel.add(r+','+c)}
    }else{sel.clear();sel.add(cr+','+cc)}
    draw()
  }
  if(drag==='pt'&&mesh){const pts=[];for(const k of sel){const rc=k.split(',');pts.push({row:+rc[0],col:+rc[1],x:mesh.points[+rc[0]][+rc[1]].x,y:mesh.points[+rc[0]][+rc[1]].y})};if(pts.length)setPts(pts)}
  drag=null;start=null;dragOrigins=null
})

OC.addEventListener('mouseleave',()=>{drag=null})
OC.addEventListener('wheel',e=>{e.preventDefault();z*=e.deltaY>0?0.92:1.08;z=Math.max(0.2,Math.min(10,z));draw()},{passive:false})
OC.addEventListener('contextmenu',e=>e.preventDefault())

// Keyboard
document.addEventListener('keydown',e=>{
  if(!mesh)return
  const R=mesh.rows,C=mesh.cols

  // WASD: cursor movement (selection)
  if(['w','W','a','A','s','S','d','D'].includes(e.key)){
    e.preventDefault();const pr=cr,pc=cc
    if(e.key==='w'||e.key==='W')cr=Math.max(0,cr-1)
    if(e.key==='s'||e.key==='S')cr=Math.min(R-1,cr+1)
    if(e.key==='a'||e.key==='A')cc=Math.max(0,cc-1)
    if(e.key==='d'||e.key==='D')cc=Math.min(C-1,cc+1)
    if(e.shiftKey){
      const r1=Math.min(pr,cr),r2=Math.max(pr,cr),c1=Math.min(pc,cc),c2=Math.max(pc,cc)
      for(let r=r1;r<=r2;r++)for(let c=c1;c<=c2;c++)sel.add(r+','+c)
    }else if(!e.ctrlKey&&!e.metaKey){sel.clear();sel.add(cr+','+cc)}
    draw();return
  }

  // Arrow keys: nudge selected points
  if(['ArrowUp','ArrowDown','ArrowLeft','ArrowRight'].includes(e.key)){
    e.preventDefault()
    const step=e.shiftKey?0.001:0.005
    let dx=0,dy=0
    if(e.key==='ArrowUp')dy=-step
    if(e.key==='ArrowDown')dy=step
    if(e.key==='ArrowLeft')dx=-step
    if(e.key==='ArrowRight')dx=step
    const pts=[]
    for(const k of sel){const rc=k.split(',');const p=mesh.points[+rc[0]][+rc[1]];p.x+=dx;p.y+=dy;pts.push({row:+rc[0],col:+rc[1],x:p.x,y:p.y})}
    if(pts.length)setPts(pts)
    draw();return
  }

  if(e.key==='Tab'){e.preventDefault();if(sel.size===R*C)sel.clear();else for(let r=0;r<R;r++)for(let c=0;c<C;c++)sel.add(r+','+c);draw()}
  if((e.ctrlKey||e.metaKey)&&e.key==='a'){e.preventDefault();for(let r=0;r<R;r++)for(let c=0;c<C;c++)sel.add(r+','+c);draw()}
  if(e.key==='Escape'){sel.clear();draw()}
  if(e.key==='r'&&!e.ctrlKey&&!e.metaKey)regularize()
})

async function toggle(){const s=await api('/fusion/api/enable',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({enable:!en})});en=s.enabled;document.getElementById('btnEnable').textContent=en?'关闭':'开启';document.getElementById('btnEnable').className=en?'active':''}
async function regularize(){const r=await api('/fusion/api/mesh',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:'regularize'})});mesh=r;draw()}
async function resetMesh(){const r=await api('/fusion/api/mesh',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:'reset'})});mesh=r;sel.clear();cr=1;cc=1;document.getElementById('subdivX').textContent='0';document.getElementById('subdivY').textContent='0';draw()}
function resetSource(){src={x:0,y:0,w:1,h:1};draw()}

load()
setInterval(load,3000)
</script>
</body></html>
    """.trimIndent()
}
