# FoliarcStudio Design Token System & UI Primitive Architecture

> 本文档沉淀 **FoliarcStudio（卷弧工坊）** 作者端（`apps/web :3000`）与独立运营后台（`apps/admin :3001`）的统一 Design Token 体系与同构 UI 原语规范。该方案由主线工程与 Codex `gpt-6.2-astra` 联合设计并经 OKLCH 线性 sRGB 对比度计算验证，全部 5 套主题满足 **WCAG 2.2 AA**（正文 ≥ 8.91:1、次要文字 ≥ 5.37:1、主按钮 ≥ 7.45:1、状态徽章 ≥ 6.74:1、描边 ≥ 3.07:1、焦点环 ≥ 4.98:1）。

---

## 1. 架构原则：双端同构契约 + 独立容器构建上下文

为保持 `apps/web/Dockerfile` 与 `apps/admin/Dockerfile` 各自独立构建（零根仓构建上下文耦合、零新增 npm 依赖），两应用采用 **三文件 CSS 分层 + 字节级镜像 `components/ui/` 原语**：

| 文件 | 镜像关系 | 职责 |
| :--- | :--- | :--- |
| `src/app/globals.css` | 双端完全一致 | 入口文件：加载 Tailwind v4、`tw-animate-css`、`palette.css`、`ui-contract.css`、统一 `@custom-variant dark` 与焦点环规则 |
| `src/app/ui-contract.css` | 双端完全一致 | 6 组状态语义色公式（`success` / `warning` / `danger` / `info` / `ai` / `vip`）、字族、间距、圆角、中文写作排版令牌与 `@theme inline` 映射 |
| `src/app/palette.css` | 各应用独立 | 仅定义该应用专属主题色板与 3 个状态亮度标量（`--status-text-l` / `--status-bg-l` / `--status-border-l`） |
| `src/lib/theme-config.ts` | 各应用独立 | 定义该应用的 `localStorage` key、默认主题与中文化名称 |
| `src/components/ui/*.tsx` | 双端完全一致（15 个原语） | 基于 `@base-ui/react@1.9.0` + `cva` + `cn` 实现，可通过 `diff -qr apps/web/src/components/ui apps/admin/src/components/ui` 零偏差校验 |

---

## 2. 卷弧文学风 & 同源工程风 5 套主题色板（OKLCH）

| 应用 | Theme ID | 名称 | 视觉定位 | 正文最低对比度 |
| :--- | :--- | :--- | :--- | ---: |
| `apps/web` | `paper`（默认） | 宣纸日间 | 暖象牙宣纸画布 + 墨砚正文 + 青黛主色 + 朱砂浅印选区 | 11.93:1 |
| `apps/web` | `sepia` | 护眼羊皮纸 | 低炫光温润羊皮纸，面向多小时长篇码字护眼 | 9.19:1 |
| `apps/web` | `ink`（兼 `.dark`） | 深夜墨蓝 | 深青墨蓝夜写模式 + 温润米白正文 + 玉青强调色 | 8.91:1 |
| `apps/admin` | `ops-dark`（默认） | 深蓝夜间 | 同源高对比深蓝工程后台，强调表格与状态可读性 | 10.27:1 |
| `apps/admin` | `ops-light` | 工程日间 | 澄明冷灰白底 + 工程钴蓝主色，适配亮环境运维 | 12.20:1 |

### 零闪烁（Zero-FOUC）主题初始化
- 根布局 `src/app/layout.tsx` 保持 Server Component，在 `<html data-theme={fallback} suppressHydrationWarning>` 上挂载默认主题，并在 `<head>` 内注入静态同步脚本读取 `localStorage` 提前写入 `document.documentElement.dataset.theme`（兼容 Next.js 16 `cacheComponents: true`）。
- `ThemeToggle` 使用 React 19 `useSyncExternalStore` 订阅同页事件与跨标签页 `storage` 事件，避免 `react-hooks/set-state-in-effect` 警告与水合不一致。

---

## 3. 15 个同构 UI 原语清单（`src/components/ui/`）

1. **`button.tsx`** — `Button` & Server Component 安全的 `buttonVariants`，采用实色高对比令牌与 `loading` / `aria-busy` 状态。
2. **`badge.tsx`** — 7 态语义徽章（`neutral` / `success` / `warning` / `danger` / `info` / `ai` / `vip`）。
3. **`input.tsx`** — 统一封装文本/数字/密码/文件/复选框原生控件。
4. **`textarea.tsx`** — 支持 `form` 与 `manuscript`（自动启用 `--font-editor` 中文宋体栈、`1.9` 行高与 `36ic` 黄金行宽）。
5. **`select.tsx`** — 基于 `@base-ui/react/select`，支持空字符串筛选值与 Portal 主题继承。
6. **`card.tsx`** & **`kpi-card.tsx`** — 统一容器表面层（`card` / `elevated`）与语义化 `<dl>` 等宽数字指标卡。
7. **`alert.tsx`** — 持久内联提示（表单校验、配额引导、版本冲突、失败错误）。
8. **`data-table.tsx`** & **`empty-state.tsx`** — 带无障碍 `<caption>`、表头 `scope="col"` 与等宽数字对齐的数据表格与空态。
9. **`toast.tsx`** — 基于 `@base-ui/react/toast`（`limit={3}`，常规 5s / 错误 8s 高优先级），承接一切瞬态操作反馈。
10. **`confirm-dialog.tsx`** — 基于 `@base-ui/react/dialog`（破坏性操作带 `role="alertdialog"`、默认聚焦取消按钮、内置单飞防重提交与失败 Toast），全面替代双端所有 `window.confirm`。
11. **`prompt-dialog.tsx`** — 基于 `@base-ui/react/dialog` 的表单输入弹窗，替代写作台 `window.prompt`。
12. **`theme-toggle.tsx`** & **`providers.tsx`** — 全局 `<UiProviders>`（统一挂接 `ToastProvider` → `ConfirmProvider` → `PromptProvider`）与零水合抖动主题切换菜单。

---

## 4. 字体体系、版权合规边界与商用字库演进规范

### 4.1 当前字体栈与零字库托管现状

目前双端未自托管或打包任何 `.ttf` / `.otf` / `.woff2` 字库资源文件，均通过 `ui-contract.css` 中的字族令牌调用用户操作系统本地字体：

| 令牌 / 载体 | 当前声明值 | 主要应用场景 |
| :--- | :--- | :--- |
| `--font-editor` (`--type-literary`) | `"Songti SC", "Noto Serif CJK SC", "Source Han Serif SC", SimSun, serif` | `Textarea`（`manuscript` 模式）、章节标题与正文预览、AI Copilot 输出（配合 `--editor-font-size: 1.09375rem`、`--editor-line-height: 1.98`、`--editor-measure: 38ic`） |
| `--font-sans` / `--font-heading` (`--type-ui`) | `system-ui, -apple-system, BlinkMacSystemFont, "PingFang SC", "Noto Sans CJK SC", "Microsoft YaHei", sans-serif` | 界面导航、按钮、表格、弹窗与表单控件 |
| `--font-mono` (`--type-code`) | `ui-monospace, "SFMono-Regular", Menlo, Consolas, monospace` | Trace ID、状态码、字数统计与等宽数值 |
| `BookExportService.java`（导出层） | `MD` / `TXT` 纯文本；`EPUB` 仅声明通用 `font-family: serif;` | 小说整书导出（**未打包或嵌入任何字体文件**，由阅读器端决定渲染字体） |

### 4.2 版权合规边界与已知短板

- **当前用法合规性**：Web CSS 仅声明系统字体名称，由浏览器调用用户操作系统本地已获系统厂商授权的字库，服务端未复制、托管或分发字库文件，常规商业 Web 场景侵权风险极低。
- **跨平台观感短板**：macOS 默认命中 `Songti SC`（华文宋体），阅读观感较好；多数 Windows 终端未预装思源宋体，会直接回退至 `SimSun`（中易宋体），在 `1.09375rem`（17.5px）编辑器正文下偏细、清晰度不足。

> [!WARNING]
> **版权红线**：一旦未来扩展 **自托管 `@font-face` 分发、服务端导出 PDF 样书、EPUB 嵌入字库、小说封面 / 金句卡片 / 宣传海报渲染** 等能力，严禁使用 `Microsoft YaHei`（方正）、`SimSun`（中易）、`Songti SC`（华文）、`PingFang SC`（苹果）等非免费商用系统字体，必须切换至下表开源可商用白名单字库。

### 4.3 推荐免费可商用开源小说字库清单（SIL OFL 1.1）

以下字库均采用 **SIL Open Font License 1.1（SIL OFL 1.1）** 开源协议，个人与企业全场景免费商用（包含网页 `@font-face` 托管、EPUB/PDF 嵌入与海报封面生成）：

| 字体名称 | 协议 | 风格特点与适用场景 | 选型与兜底建议 |
| :--- | :--- | :--- | :--- |
| **思源宋体** (`Noto Serif SC` / `Source Han Serif SC`) | SIL OFL 1.1 | Adobe & Google 联合开源，端庄大气的现代印刷宋体；字重丰富、生僻字覆盖极全 | **全平台出版级正文基准**，适合严肃、历史、悬疑、科幻及全站衬线兜底 |
| **霞鹜文楷** (`LXGW WenKai` / 屏幕阅读版 `LXGW WenKai Screen`) | SIL OFL 1.1 | 介于楷体与宋体之间的温润书卷风格，阅读器口碑极佳 | **极度契合 `paper` / `sepia` 护眼主题**；Windows 1080p 屏幕建议屏幕阅读版，macOS 推荐原版 `Regular` |
| **朱雀仿宋** (`Zhuque Fangsong`) | SIL OFL 1.1 | 璇玑造字开源的民国活字风仿宋体，清雅古朴 | 适合古风、武侠、轻文学或引文/卷首语（预览版约 9,000+ 字，**须配置思源宋体兜底生僻字**） |
| **思源黑体** (`Noto Sans SC` / `Source Han Sans SC`) | SIL OFL 1.1 | 干净克制的现代无衬线体，字库极全 | 适合偏好清爽网文阅读体验的作者，以及全站 UI 兜底 |

*协议约束备忘（SIL OFL 1.1）：禁止单独出售原始或修改后的字库文件本身；如对字库进行二次修改并发布衍生版本，须继续以 SIL OFL 1.1 协议开源且不得沿用原作者保留字体名称。*

### 4.4 后续 Webfont 与写作台字体切换演进规范

1. **禁止整包直链加载**：完整中文字库单包体积通常为 `8–20 MB`，严禁在页面中直接全量加载整包 `.ttf` / `.woff2` 阻塞首屏渲染。
2. **写作台正文字体切换规划**：在 `apps/web` 与主题切换（`paper` / `sepia` / `ink`）同构，提供并持久化至 `localStorage` 的四档字体选项：
   - **系统默认**（零网络流量，保持现行本地字体栈）
   - **出版宋体**（思源宋体）
   - **书卷文楷**（霞鹜文楷）
   - **现代黑体**（思源黑体）
3. **按需分片加载策略**：统一采用 `unicode-range` 静态分片按需加载（如离线通过 `cn-font-split` 切分后自托管于静态资源目录，或合规切片源）并配置 `font-display: swap`，保证首屏仅按需请求几百 KB 常用字片。
4. **服务端出图与导出白名单**：后续如新增服务端 PDF 样书排版、EPUB 字体嵌入或封面/金句图片生成，字库仅允许从 4.3 节 SIL OFL 1.1 白名单中选取。
