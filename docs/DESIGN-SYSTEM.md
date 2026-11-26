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
