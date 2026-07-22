# 投影融合 (Projection Fusion) 规划

## 核心理念

所有融合模式的统一框架: **裁剪 + 网格扭曲 + 遮罩** (Crop + Warp + Blend)

```
输入视频 → 裁剪本节点区域 → 网格扭曲 → alpha 遮罩 → 输出
```

硬件限制: S905X3 / Mali-G31, 支持 GLES 3.2, 可跑自定义 shader.

---

## 融合模式分级

| 等级 | 名称 | 插值方式 | 网格密度 | GPU 负载 |
|------|------|---------|---------|---------|
| 1 | 单行/单列 (1xN / Nx1) | 线性 | 低 | 极低 |
| 2 | NxM 网格 | 线性 | 中 | 低 |
| 3 | 异形拼缝 | 线性 | 中 | 中 |
| 4 | 单曲面 (柱面) | 贝塞尔 | 中 | 中 |
| 5 | 双曲面 (环面) | 贝塞尔 | 高 | 高 |
| 6 | 球面/半球/局部球面 | 3D 投影 | 高 | 极高 |

所有等级都使用网格 (Mesh). 平面投影只需线性插值, 贝塞尔只对曲面启用.

---

## 角点重叠 (4-way corner overlap)

以 2x2 田字形为例:

```
+-----+-----+
|  P1 |  P2 |
|  α↘ | ↙α  |
|-----+-----|   ← 中心是 4 台重叠区域
|  P3 |  P4 |
|  α↗ | ↖α  |
+-----+-----+
```

每台投影机的 alpha 是 2D 函数:

```
αᵢ(x,y) = f(x) × f(y)
```

其中 `f(t)` 是 1D 混合曲线 (t∈[0,1] 从重叠内缘到外缘).

在四角重叠区, 4 台 alpha 之和为 1:
```
α₁(x,y) + α₂(x,y) + α₃(x,y) + α₄(x,y) = 1
```

**实现**: 每台机器存一张 `blend_mask` (灰度图或 GPU 实时计算), 尺寸 = 输出分辨率.
- 融合区外部: α=1
- 融合区内部: α 沿对应边渐变至 0
- 四角区: 两个方向 α 的乘积

---

## 画面叠加 (Accumulation)

重叠区本质是**叠加混合**:

```
最终像素 = Σ(αᵢ × pixelᵢ)   for i = 1..N
```

所有 αᵢ 之和为 1, 显示内容为同一源视频的不同部分 → 无缝.
GLSL 实现:

```glsl
// fragment shader
uniform sampler2D u_video;     // 解码后的视频帧
uniform sampler2D u_blendMask; // alpha 遮罩

void main() {
    vec2 uv = warpMesh(u_video_coord);    // 先做网格扭曲
    vec4 color = texture(u_video, uv);
    float alpha = texture(u_blendMask, uv).r;
    gl_FragColor = vec4(color.rgb * alpha, alpha);
}
```

最终多台输出叠加, 需要融合控制器 (如 Chataigne 不处理视频, 只有物理叠加).

---

## 网格扭曲 (Mesh Warp)

### 网格定义

```
M × N 控制点网格 (M,N 可配置)
每点: x, y (归一化 0-1)
```

默认插值: **线性双线性插值** (所有等级通用). 贝塞尔为可选开关, 仅等级 4-5 启用.

### 线性网格

平面类投影面 (等级 1-3) 使用线性网格:

```
for each pixel at (u, v):
    find cell (i,j) such that u ∈ [i/M, (i+1)/M], v ∈ [j/N, (j+1)/N]
    warp_u = bilinear_interpolate(P[i][j].x, P[i+1][j].x, P[i][j+1].x, P[i+1][j+1].x, u, v)
    warp_v = bilinear_interpolate(P[i][j].y, P[i+1][j].y, P[i][j+1].y, P[i+1][j+1].y, u, v)
```

GPU 直接传网格为 `GL_RG32F` texture, fragment shader 查表插值.

### 网格均匀化 (Mesh Regularization)

**问题**: 拖拽控制点做对齐后, 网格会拉伸不均 — 密集区挤在一起, 稀疏区拉伸, 导致画面变形不均匀.

**方案 — Laplacian 均匀化**:

```
对每个内部点 P[i][j]:
    邻居平均 = (P[i-1][j] + P[i+1][j] + P[i][j-1] + P[i][j+1]) / 4
    P[i][j] += (邻居平均 - P[i][j]) × λ
```

- λ: 松弛因子 (默认 0.5)
- 边界点 (第 0/M 行, 第 0/N 列) 固定不变
- 迭代执行 N 次, 收敛后网格点分布均匀, 整体扭曲形状不变

交互流程:
```
1. 拖拽控制点 → 粗调对齐
2. 执行均匀化 → 消除局部挤压/拉伸
3. 微调个别点 → 精调
4. 重复 2-3 直到满意
```

OSC 命令: `/fusion/mesh/regularize` 触发均匀化, 成功返回 `ok`, 失败返回 `error`.

### 贝塞尔网格 (等级 4-5)

由 `/fusion/bezier/axis` 开启. 平面投影不需要贝塞尔.

单弧形 (柱面): 每行的控制点按 4 点贝塞尔曲线定义.

```
B(t) = (1-t)³·P₀ + 3(1-t)²·t·P₁ + 3(1-t)·t²·P₂ + t³·P₃
```

双弧形 (环面): 两个方向独立定义贝塞尔曲线, GPU 做曲面插值.

### 3D 投影矩阵 (等级 6)

模拟虚拟相机投影到 3D 球面:

```
gl_Position = projectionMatrix × modelViewMatrix × vec4(meshPos, 1.0)
```

---

## OSC 命令设计

### 基础

| 地址 | 参数 | 说明 |
|------|------|------|
| `/fusion/enable` | 0/1 | 开关融合模式 |
| `/fusion/mode` | 1-6 | 设融合等级 |
| `/fusion/layout` | cols rows | 整体布局 (如 `2 2`, `1 3`) |
| `/fusion/position` | col row | 本节点在布局中的位置 |
| `/fusion/source` | x y w h | 源视频裁剪区域, 归一化 0-1 |
| `/fusion/status` | - | 返回当前配置 |

### 融合遮罩 (Blend)

| 地址 | 参数 | 说明 |
|------|------|------|
| `/fusion/overlap` | left right top bottom | 各边重叠宽度 (像素) |
| `/fusion/blend/curve` | linear/smooth/gamma | 混合曲线类型 |
| `/fusion/blend/gamma` | float | 混合曲线 gamma 值 |
| `/fusion/blend/width` | pixels | 融合带宽度 |
| `/fusion/blend/mask` | - | 导出当前 blend mask 为图片 (调试) |

### 网格扭曲 (Warp)

| 地址 | 参数 | 说明 |
|------|------|------|
| `/fusion/mesh/rows` | N | 网格行数 |
| `/fusion/mesh/cols` | N | 网格列数 |
| `/fusion/mesh/point` | row col x y | 设单个控制点位置 |
| `/fusion/mesh/regularize` | - | Laplacian 均匀化, 消除挤压/拉伸 |
| `/fusion/mesh/load` | - | 从文件加载网格 |
| `/fusion/mesh/save` | name | 保存网格为预设 |
| `/fusion/mesh/reset` | - | 重置为矩形 |

### 贝塞尔 (等级 4-5 可选)

| 地址 | 参数 | 说明 |
|------|------|------|
| `/fusion/bezier/enable` | 0/1 | 开启/关闭贝塞尔插值 |
| `/fusion/bezier/axis` | h/v/both | 弯曲方向 |
| `/fusion/bezier/ctrl` | row col x1 y1 x2 y2 x3 y3 | 设贝塞尔控制点 |
| `/fusion/bezier/tension` | float | 曲线张力 (0-1) |

### 3D 参数 (等级 6)

| 地址 | 参数 | 说明 |
|------|------|------|
| `/fusion/3d/fov` | float | 虚拟相机视场角 |
| `/fusion/3d/rotate` | x y z | 旋转角度 |
| `/fusion/3d/translate` | x y z | 平移 |
| `/fusion/3d/radius` | float | 球体半径 |

### 预置管理

| 地址 | 参数 | 说明 |
|------|------|------|
| `/fusion/preset/save` | name | 保存当前所有参数为预置 |
| `/fusion/preset/load` | name | 加载预置 |
| `/fusion/preset/list` | - | 列出预置 |
| `/fusion/preset/delete` | name | 删除预置 |

---

## 文件结构

```
app/src/main/java/com/oscvideoplayer/
├── FusionEngine.kt          # 融合引擎主入口
├── FusionRenderer.kt        # GLSurfaceView.Renderer (shader + mesh)
├── FusionShader.kt          # GLSL 源码管理
├── FusionMesh.kt            # 网格数据结构 + 插值 + 均匀化
├── FusionBlend.kt           # alpha 遮罩生成
├── FusionBezier.kt          # 贝塞尔曲线/曲面计算 (可选, 仅等级 4-5)
├── FusionOSC.kt             # OSC 命令处理器
├── FusionPreset.kt          # 预置序列化
```

---

## 实现路线

### Milestone 1 — 基础网格 + 裁剪 + 1xN 融合
- `FusionEngine` 基本架构
- `FusionMesh`: 网格定义 + 线性双线性插值 + Laplacian 均匀化
- `FusionRenderer`: GLSurfaceView 基本渲染管线
- 视频裁剪 (Media3 `VideoTransformation` 或 `TextureView` 手动 crop)
- `FusionBlend` 1D alpha 渐变
- OSC: `/fusion/enable`, `/layout`, `/position`, `/source`, `/overlap`, `/blend/*`, `/mesh/*`
- 等级 1-3

### Milestone 2 — 贝塞尔曲面
- `FusionBezier`
- 贝塞尔网格插值
- 等级 4-5

### Milestone 3 — 3D 球面
- 虚拟相机 + 3D 网格
- 等级 6

### 非功能
- 所有参数保存在 SharedPreferences
- 预置管理
- 与 3D (SBS/OU) 互斥
- 与 SurfaceView 调试模式互斥 (融合必须 TextureView)
