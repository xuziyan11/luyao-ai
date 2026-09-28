# DESIGN.md · 路瑶AI 项目介绍（深色科技 / 珊瑚渐变）

## 1. 画布与母版（A/B/C 三区，1280×720）
- A 标题块：0–120px，主标题 32–40px bold，左侧留 60px padding。
- B 内容区：120–660px（540px 可用），所有正文/图/卡。
- C 页脚条：660–720px，左「路瑶AI · 项目介绍」14px 灰字，右「NN / 15」14px 灰字。
- 全局 padding：上下 20px，左右 60px（封面/章节/结束页可自定义）。

## 2. 颜色系统（≤4 hex + 渐变 + rgba 衍生）
- 背景主色：`#0B0D12`（深空底）
- 品牌珊瑚：`#FF6F5E`
- 品牌琥珀：`#FFAE6B`
- 文本亮色：`#EDEFF5`
- 面板/分隔：用 `rgba(255,255,255,0.05)`；次级文本用 `rgba(237,239,245,0.6)`；描边 `rgba(255,255,255,0.10)`。
- 品牌渐变：`linear-gradient(135deg, #FF6F5E 0%, #FFAE6B 100%)`（标题栏/大色块/CTA）。
- 面积分配：主色(深底)≤60%；辅色(rgba面板)≤30%；强调色(珊瑚渐变)≤10%（Hero 页可 15–20%）。
- 色彩节奏：封面/架构/结束页强调色爆发；内容页克制（≤5%）。

## 3. 字体系统（≤2 家族）
- 家族：`'PingFang SC','Inter',sans-serif`（中文 PingFang，西文 Inter）。
- 封面主标题 72px bold；章节大字 64px bold；巨型锚点 72–96px bold；页面主标题 34px bold；卡片小标题 22px bold；正文 20–22px regular；脚注 14px。
- 巨型数字用 bold + 渐变文字（`backgroundClip:'text'`）。

## 4. 信息密度
- 常规内容页：正文 ≥180 字、留白 ≤35%、主视觉占 B 区 ≥30%。
- 卡片填充率 ≥85%，尾部元素 `marginTop:auto` 钉底；兄弟卡三段 y 轴对齐。

## 5. 配图系统
- 全程 SVG / 渐变 / FAIcon，不依赖外部摄影图（科技概念页用 SVG 与渐变光晕）。
- L1 主视觉：架构图(SVG)、章节巨型序号、封面/结束渐变光晕。
- L3 母版徽标：页脚项目名（全篇一致）。
- 图标：FAIcon 统一实心、圆角容器 48–64px，尺寸全篇一致。

## 6. 页面映射表
| # | 文件 | 类型 | 角色 | 版式 | L1 | 留白 | 色彩 | 关键约束 |
|---|---|---|---|---|---|---|---|---|
| 01 | 01_cover | cover | hero | 全屏渐变+骑线 | 光晕 | 35% | 强调20% | 大标题左骑 |
| 02 | 02_catalog | catalog | supporting | 左标题+右内容 | — | 25% | 主40%辅25% | 4 章 |
| 03 | 03_section1 | section | transition | 全屏大字 | 01 | 40% | 强调15% | 序号巨 |
| 04 | 04_intro | content | hero | 巨型句 | 金句 | 40% | 强调18% | 骑线 |
| 05 | 05_problem | content | supporting | 非对称双栏 | 场景 | 25% | 主50%辅20% | 宽窄60:40 |
| 06 | 06_section2 | section | transition | 全屏大字 | 02 | 40% | 强调15% | 序号巨 |
| 07 | 07_stack | content | supporting | 左标题+右内容 | 图标 | 25% | 主45%辅25% | 列表 |
| 08 | 08_arch | content | hero | 全幅图+骑线 | 架构SVG | 30% | 强调15% | 图占B≥40% |
| 09 | 09_section3 | section | transition | 全屏大字 | 03 | 40% | 强调15% | 序号巨 |
| 10 | 10_voice | content | supporting | 非对称双栏 | 3卡 | 28% | 主50%辅20% | 宽窄 |
| 11 | 11_features | content | supporting | 左标题+右内容 | 5模块 | 22% | 主45%辅25% | 列表 |
| 12 | 12_account | content | supporting | 非对称双栏 | 隔离 | 25% | 主50%辅20% | 宽窄 |
| 13 | 13_section4 | section | transition | 全屏大字 | 04 | 40% | 强调15% | 序号巨 |
| 14 | 14_eng | content | supporting | 左标题+右内容 | 清单 | 22% | 主45%辅25% | 列表 |
| 15 | 15_end | ending | hero | 全屏渐变+骑线 | 光晕 | 40% | 强调20% | 收束句 |
