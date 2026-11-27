# Phase 7B：AI 审校防截断 + shadcn v4（Base UI）组件统一 + React Bits 分层动效方案

> 本文档为 **FoliarcStudio（卷弧工坊）** 针对以下四项核心体验升级的设计与落地规范：
> 1. 彻底治理多 Agent 写作中「审校输出被截断或为空」及长章正文截断问题；
> 2. 基于项目已安装的 **Tailwind CSS v4 + shadcn/ui v4（`base-nova` / `@base-ui/react@1.9.0`）** 补齐缺失的无头组件原语，清零全站手写浮层与残留原生 `<select>`；
> 3. 修复导出弹窗与其他顶栏菜单交互不一致（无点击外部关闭、无 Portal、可重叠）的问题；
> 4. 按「展示区精致、创作区克制」原则引入 **React Bits** 精选动效组件。

---

## 一、AI 审校与正文防截断治理（`services/agent-py`）

### 1.1 根因诊断
1. **推理模型预算被思考链耗尽**：[multi_agent.py](../services/agent-py/src/foliarc_agent/agent/multi_agent.py) 中 `CRITIC` 节点请求 `max_tokens=1200`，经 [llm_client.py](../services/agent-py/src/foliarc_agent/streaming/llm_client.py) 放大后上限仅 `6144`。`deepseek-reasoner` 的 `max_tokens` 由 `reasoning_content` 与最终 `content` 共享，在审查 3,000–4,500 字正文叠加多维度设定包时极易耗尽配额，导致 `content=""` 且 `finish_reason="length"`。
2. **兜底重试缺少结构化 JSON 约束**：回退至 `writer-main`（`deepseek-chat`）时未开启 `response_format: {"type": "json_object"}`，且 `max_tokens` 仍偏紧（`1200`），容易因附加说明或换行导致 `_extract_critic_json_obj` 解析失败。
3. **JSON 提取器过于脆弱**：原 `_extract_critic_json_obj` 仅取首 `{` 与尾 `}`，一旦输出包含前后花括号、代码块或字符串内原始换行即报错返回 `None`。
4. **审校异常误杀正常正文**：原逻辑将「审校输出被截断」直接判定为 `passed=False`，导致系统触发 `draft_reset` 丢弃已写好的正文并强制返工，既耗时又消耗积分。
5. **正文与大纲输出预算偏紧**：`WRITER` 节点 `max_tokens=3200` 对 3,000 字长章较为吃紧，`DIRECTOR` 节点 `max_tokens=1200` 在复杂节拍下也容易提前收尾。

### 1.2 改造方案
| 模块 | 具体改造 |
| :--- | :--- |
| **`llm_client.py`** | 1. `stream_chat` 新增可选参数 `response_format: dict[str, Any] \| None = None`，在**非推理模型**上透传给上游接口（推理模型自动跳过，避免部分兼容接口报错）；<br>2. `effective_max_tokens` 对推理模型的保底从 `6144` 抬高至 `10240`（`min(16384, max(req + 6144, req * 4, 10240))`），并补充识别 `gemini-2.5`、`glm-z1`、`kimi-k2-thinking` 等思考模型；<br>3. `_parse_openai_chunk` 兼容 `delta.reasoning_content` 与 `delta.reasoning`。 |
| **`router.py`** | `FallbackStreamClient.stream_chat` 透传 `response_format` 参数，保持容灾链行为一致。 |
| **`multi_agent.py`** | 1. 提高输出预算：`DIRECTOR` `1200 → 1800`，`WRITER` `3200 → 4096`，`CRITIC` `1200 → 2048`；<br>2. `CRITIC` 主调用与 `writer-main` 兜底均传入 `response_format={"type": "json_object"}`（`writer-main` 自动启用 JSON mode，`critic-reasoner` 自动享有 `10240` 思考+输出预算）；<br>3. 强化 `_extract_critic_json_obj`：依次尝试 fenced `json` 块、平衡花括号扫描（优先匹配含 `passed`/`score` 的对象）、`json.loads(..., strict=False)`，以及从 `reasoning_text` 末段提取合法 JSON；<br>4. 审校保护不误杀正文：`_parse_critic_json` 保留原单测契约并增加 `inconclusive: bool` 标记；当主调用与 `writer-main` 兜底**均未返回有效 JSON** 且 `draft_text` 非空时，**不触发 `draft_reset` 强制重写**，而是保留当前正文、标记通过并记录轻量提示。 |

---

## 二、Tailwind v4 + shadcn/ui v4（Base UI）组件体系补全

### 2.1 架构选型决策：坚持单一无头底座，不混入 Radix UI
- 双端均已安装 **`tailwindcss@4.3.3` + `tw-animate-css@1.4.0` + `shadcn@4.23.0` + `@base-ui/react@1.9.0`**，且 `apps/web/components.json` 已采用 shadcn v4 官方预设 `base-nova`。
- **`@base-ui/react@1.9.0` 已完整内置** `menu`、`popover`、`dialog`、`switch`、`tooltip`、`select`、`toast` 等全部无头模块，**无需新增 Radix UI**（避免双套 Portal / 焦点陷阱 / Esc 层级冲突与包体膨胀）。
- 延续 `docs/DESIGN-SYSTEM.md` 的**双端字节级镜像契约**：新增与更新的所有 `components/ui/*.tsx` 原语在 `apps/web/src/components/ui` 与 `apps/admin/src/components/ui` 之间保持 `diff -qr` 零差异。

### 2.2 新增 6 个同构 UI 原语（双端镜像）
| 文件 | 底层模块 | 核心能力 |
| :--- | :--- | :--- |
| `dropdown-menu.tsx` | `@base-ui/react/menu` | Portal 渲染、视口自动避让、点击外部与 Esc 自动关闭、键盘方向键导航、焦点归还、`MenuRadioGroup` / 危险项变体、`tw-animate-css` 动效 |
| `popover.tsx` | `@base-ui/react/popover` | Portal 渲染、点击外部与 Esc 自动关闭、对齐与边距控制，承载富交互浮层（排版/字体设置等） |
| `dialog.tsx` | `@base-ui/react/dialog` | 居中模态框、毛玻璃遮罩、自动滚动锁、焦点陷阱与归还、`DialogHeader` / `DialogTitle` / `DialogFooter` |
| `sheet.tsx` | `@base-ui/react/dialog` | 左/右侧滑抽屉（内置平滑滑入/滑出动画、遮罩与 Esc 关闭），统一历史版本与跨章修订抽屉 |
| `switch.tsx` | `@base-ui/react/switch` | 统一无障碍开关控件，替代分散手写的 `<button role="switch">` |
| `tooltip.tsx` | `@base-ui/react/tooltip` | 延迟触发、键盘聚焦可达的 Portal 轻提示 |

### 2.3 全站替换清单（清零手写浮层与残留原生 `<select>`）
| 页面 / 组件 | 原实现问题 | 改造目标 |
| :--- | :--- | :--- |
| `components/ui/theme-toggle.tsx`（双端） | 手写 `useState` + `mousedown` 监听，无 Portal | 改用 `DropdownMenu`（`RadioGroup`） |
| `apps/web/src/app/books/[bookId]/page.tsx` | **导出菜单无点击外部关闭、无 Portal、可与密度/主题重叠**；密度排版手写 `mousedown`；整章预览与历史版本手写 `fixed inset-0` | 导出 → `DropdownMenu`；版心与字体 → `Popover`；整章预览 → `Dialog`；历史版本 → `Sheet` |
| `components/book-structure-tree.tsx` | 章节/小节 `⋯` 菜单手写 `openMenuId` + `absolute` 定位（易被滚动容器裁剪） | 改用 `DropdownMenu` |
| `components/ai-diff-review-modal.tsx` | 手写 `fixed inset-0` 弹窗，无焦点陷阱与 Esc 焦点归还 | 改用 `Dialog` |
| `components/ripple-edit-drawer.tsx` | 手写右侧 `fixed inset-0` 抽屉 + **原生 `<select>`** | 抽屉 → `Sheet`；版本基线选择 → `Select` |
| `components/visual/character-relation-graph.tsx` | 新建角色/新增关系手写 `fixed inset-0` + **原生 `<select>`** | 弹窗 → `Dialog`；角色定位选择 → `Select` |
| `components/visual/plotline-swimlanes.tsx` | 分卷筛选使用 **原生 `<select>`** + 新建事件手写 `fixed inset-0` | 分卷筛选 → `Select`；新建事件 → `Dialog` |
| `components/ai-copilot-panel.tsx` & `components/lore-panel.tsx` | 4 处手写 `<button role="switch">` | 统一改为 `Switch` 原语 |

---

## 三、React Bits 分层动效引入规范（`apps/web/src/components/motion/`）

### 3.1 设计红线与动效分级
遵循 React Bits（`reactbits.dev`）的 copy-paste 轻量组件理念：**零新增 npm 依赖**（不改动 `package.json` / `pnpm-lock.yaml`，保持 Docker `--frozen-lockfile` 秒级稳定构建），采用纯 React + GPU 合成 CSS（`transform` / `opacity` / `filter` / `background-position`）+ `IntersectionObserver` + `tw-animate-css` 实现，并全局严格遵循 `prefers-reduced-motion: reduce` 自动降级：

| 层次 | 覆盖页面 | 使用组件 | 约束 |
| :--- | :--- | :--- | :--- |
| **展示层（精致入场）** | 首页 `/`、书架 `/books`、会员 `/vip` | `BlurText`（柔焦文字入场）、`SpotlightCard`（指针微光卡片，适配宣纸/羊皮纸/墨蓝主题色令牌）、`FadeContent`（轻盈交错入场） | 入场时长 ≤ 450ms，一次触发不重复抖动 |
| **状态反馈层（轻量微光）** | AI 写作运行态、弹窗/菜单/抽屉 | `ShinyText`（AI 构思/生成中柔光扫过提示）、`tw-animate-css` 120–180ms 缩放淡入 | 不影响阅读清晰度与布局高度（零 CLS） |
| **专注工作层（严禁重动效）** | 正文编辑器画布、关系图谱/泳道画布、后台高密度表格 | 不叠加粒子/WebGL/文字扰动，仅保留基础交互过渡 | 保护长篇写作沉浸感与 React Flow 60fps 帧率 |

---

## 四、执行分工与验收清单

1. **后端 Track A（`services/agent-py`）**：修复 Critic / Writer 防截断、`response_format` JSON mode、稳健 JSON 提取与防误杀正文策略，补充单测并通过 `ruff` + `pytest`。
2. **前端 Track B（`apps/web` + `apps/admin`，委托 Codex GPT 协同实施）**：补齐 6 个 shadcn v4 Base UI 镜像原语、完成全站浮层与原生 `<select>` 清零迁移、接入 React Bits 精选动效组件，确保 `diff -qr` 双端零差异与 `eslint` / `tsc --noEmit` / `next build` 全通过。
3. **联调与上线验证**：重建 `agent-py`、`web`、`admin` 容器，验证健康接口与页面渲染。
