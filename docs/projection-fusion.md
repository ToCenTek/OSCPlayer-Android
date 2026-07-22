# 投影融合 (Projection Fusion) 规划

## 核心理念

所有融合模式的统一框架: **裁剪 + 扭曲 + 遮罩** (Crop + Warp + Blend)

```
输入视频 → 裁剪本节点区域 → 网格扭曲 → alpha 遮罩 → 输出
```

硬件限制: S905X3 / Mali-G31, 支持 GLES 3.2, 可跑自定义 shader.

---

## 融合模式分级

| 等级 | 名称 | 说明 | 网格复杂度 | GPU 负载 |
|------|------|------|-----------|---------|
| 1 | 单行/单列 (1xN / Nx1) | 一维, 只对 1 条边/2 条边做融合 | 无网格 | 极低 |
| 2 | NxM 网格 | 二维, 4 边融合 + 角点重叠处理 | 网格 α 图 | 低 |
| 3 | 异形拼缝 | 拼缝不是直线, 有角度 | 稀疏网格 | 中 |
| 4 | 单曲面 (柱面) | 一个方向弧形 | 贝塞尔曲线网格 | 中 |
| 5 | 双曲面 (环面) | 两个方向弧形, 曲率可不同 | 贝塞尔曲面片 | 高 |
| 6 | 球面/半球/局部球面 | 全 3D 投影映射 | 3D 网格 + 虚拟相机 | 极高 |

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

统一方案: **贝塞尔网格** (Bezier Patch Grid)

### 网格定义

```
M × N 控制点网格 (M,N 可配置)
每点: x, y (目标位置, 归一化 0-1)
```

- 等级 1-2: 不需要扭曲, 只做 α 遮罩
- 等级 3-4: 16×16 网格 + 线性插值
- 等级 5: 32×32 网格 + 贝塞尔曲面插值
- 等级 6: 64×64 网格 + 3D 投影矩阵

### 贝塞尔曲线 (等级 4)

单弧形 (柱面): 控制点沿一个方向按贝塞尔曲线偏移.

```
P₀, P₁, P₂, P₃ (4 控制点/行)
B(t) = (1-t)³·P₀ + 3(1-t)²·t·P₁ + 3(1-t)·t²·P₂ + t³·P₃
```

### 贝塞尔曲面 (等级 5)

双弧形 (环面): 在两个方向上独立定义贝塞尔曲线.

### 3D 投影矩阵 (等级 6)

模拟虚拟相机投影到 3D 球面:

```
gl_Position = projectionMatrix * modelViewMatrix * vec4(meshPos, 1.0)
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
| `/fusion/mesh/load` | - | 从文件加载网格 |
| `/fusion/mesh/save` | name | 保存网格为预设 |
| `/fusion/mesh/reset` | - | 重置为矩形 |

### 贝塞尔 (等级 4-5)

| 地址 | 参数 | 说明 |
|------|------|------|
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
├── FusionMesh.kt            # 网格数据结构 + 插值算法
├── FusionBlend.kt           # alpha 遮罩生成
├── FusionBezier.kt          # 贝塞尔曲线/曲面计算
├── FusionOSC.kt             # OSC 命令处理器
├── FusionPreset.kt          # 预置序列化
```

---

## 实现路线

### Milestone 1 — 基础裁剪 + 1xN 融合
- `FusionEngine` 基本架构
- 视频裁剪 (Media3 `VideoTransformation` 或 `TextureView` 手动 crop)
- `FusionBlend` 1D alpha 渐变
- OSC: `/fusion/enable`, `/layout`, `/position`, `/source`, `/overlap`, `/blend/*`
- 等级 1 到 2

### Milestone 2 — 网格扭曲
- `FusionMesh` + `FusionRenderer`
- GLSurfaceView 渲染管线
- 控制点设置 + 插值
- 等级 3

### Milestone 3 — 贝塞尔曲面
- `FusionBezier`
- 贝塞尔网格插值
- 等级 4 到 5

### Milestone 4 — 3D 球面
- 虚拟相机 + 3D 网格
- 等级 6

### 非功能
- 所有参数保存在 SharedPreferences
- 预置管理
- 与 3D (SBS/OU) 互斥
- 与 SurfaceView 调试模式互斥 (融合必须 TextureView)
