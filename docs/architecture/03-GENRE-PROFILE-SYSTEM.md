# 03｜题材分类档案包（Genre Profile）与全链路风格匹配系统

> 对应子阶段：**Phase 5.8C**
> 核心解决：彻底消除现有系统对“仙侠玄幻”的隐性硬编码，通过 **6 大标准主分类 + 自由副标签** 的题材档案包机制，一键联动驱动 **Prompt 文风铁律、反 AI 味禁忌词、题材专属 Skill 路由、人物 `JSONB` 动态表单、Critic 审稿权重、文风锚点与公共 RAG 精准挂载**。

---

## 一、两级分类体系（主分类枚举 + 自由副标签）

- **主分类（`books.genre_code` 强枚举，必选其一）**：决定默认人物扩展字段、审稿 Rubric 权重、核心写作禁忌与 Skill 基础池。
- **副标签（`books.sub_genre_tags` 逗号分隔自由标签，如 `中式民俗,规则怪谈,克苏鲁` 或 `明清世情,志怪笔记,权谋`）**：支持跨界混搭（例如主类选 `CLASSICAL_HISTORICAL`、副标签加 `民俗恐怖`，系统自动叠加恐怖氛围 Skill 与公共民俗资料包！）。

### 六大内置标准题材档案矩阵

| 主分类代码 | 中文名称 | 人物动态扩展字段 (`custom_attributes JSONB`) | 核心文风铁律 & 反 AI 味禁忌词 | Critic 审稿核心权重 | 摘要提取重点 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`XIANXIA_FANTASY`** | 仙侠 / 玄幻 | `realm`（修为境界）、`cultivationArt`（功法秘术）、`faction`（宗门势力）、`artifacts`（法宝灵器） | 强调力量体系层级感与代价；忌现代管理学/互联网黑话（如“降维打击”“赋能”“拉满”） | 战力/境界越阶合理性、法宝流向、宗门利益逻辑 | 境界突破、秘术消耗、资源法宝得失 |
| **`HORROR_SUPERNATURAL`** | 恐怖 / 灵异 / 规则怪谈 | `sanity`（理智/精神状态）、`knownRules`（已知规则）、`taboosTriggered`（已触发禁忌）、`survivalStatus`（存活/污染态） | **严禁直接播报情绪**（禁用“令人毛骨悚然/一阵寒意直窜天灵盖/他感到无比恐惧”）；强制感官微异常、空间压抑、日常异化与留白；保持信息不对称 | 恐惧张力是否过早泄气、规则逻辑闭环、角色是否强行降智送人头、视角越权 | 异常感官细节、精确规则措辞、禁忌触发条件、角色认知盲区 |
| **`CLASSICAL_HISTORICAL`** | 古典 / 历史 / 世情志怪 | `courtesyName`（字/号）、`officialTitle`（官职爵位）、`clanRank`（宗族辈分）、`residence`（籍贯府邸） | 讲究白描、节制与半文白韵律；严守尊卑称谓与避讳；**严禁现代网文套话与翻译腔**（禁用“嘴角勾起一抹弧度”“眼神骤冷”“破防”“空气仿佛凝固”） | 时代器物/常识穿帮（如朝代错位器物与词汇）、称谓尊卑错乱、现代口语突兀 | 礼法暗示、称谓变更、官场/宗族利益格局、时间历法 |
| **`MYSTERY_THRILLER`** | 悬疑 / 推理 / 刑侦 | `identity`（社会身份）、`alibi`（不在场证明）、`motive`（潜在动机）、`knownClues`（掌握线索） | 公平线索原则（核心线索须在揭晓前自然呈现）；对话重潜台词与微表情；忌旁白全知透底与“柯南式长篇说教” | 物理时间线可行性、不在场证明冲突、线索公平性、角色信息隔离 | 精确时间点、物证位置、口供矛盾（严格区分事实与证词） |
| **`SCIFI_APOCALYPSE`** | 科幻 / 赛博 / 末世 | `augmentation`（义体/异能/权限）、`affiliation`（阵营/公司）、`resources`（能源/物资）、`infectionOrRisk`（辐射/污染风险） | 遵循书内确立的科技树/物理硬设定；重设定推演对人性的挤压；忌用“量子/纳米”充当无逻辑万能魔法 | 科技/物理规则自洽、物资消耗账目、阵营政治逻辑 | 科技铁律、资源消耗、时间跨度、系统/组织权限变动 |
| **`URBAN_REALISM`** | 都市 / 现实 / 职场情感 | `occupation`（职业/职级）、`socialCircle`（社交圈层）、`financialState`（资产/债务）、`coreDesire`（核心诉求） | 注重生活质感、真实职场/社会规则与人物潜台词；忌脸谱化反派与无脑降智打脸桥段 | 社会常识合理性、人物动机真实度、情感递进连贯性 | 利益交换、关系温度变化、关键约定与现实阻碍 |

---

## 二、全链路六处自动联动设计

建书选定 `genre_code` + `sub_genre_tags` 后，通过统一的 **`GenreProfileRegistry`**（Java 侧提供元数据接口供前端动态渲染表单，Python 侧镜像加载同源 JSON 配置供 Prompt/Router/Critic/Distiller 使用）自动联动 6 个环节：

### 1. 分层 Skill 技能包库与题材感知路由
将 `services/agent-py/skills/` 升级为 **通用层 + 题材专属层**（每个 `SKILL.md` 的 YAML Frontmatter 新增 `genres: ["ALL"]` 或 `genres: ["HORROR_SUPERNATURAL"]` 及 `sub_tags`）：

| Skill 名称 | 归属题材 | 触发场景 |
| :--- | :--- | :--- |
| `foreshadowing-craft`（已有，升级） | `ALL` | 伏笔埋设、冰山原则、草蛇灰线照应与自然回收 |
| `plot-unblock`（已有） | `ALL` | 卡文破局、冲突升级、章末钩子设计 |
| `dialogue-subtext`（新增） | `ALL` | 高张力潜台词博弈、角色言不由衷的暗流对话 |
| `combat-scene`（已有） | `XIANXIA_FANTASY`, `SCIFI_APOCALYPSE` | 冷兵器/术法/机甲动作交锋节奏与代价感 |
| `horror-atmosphere`（新增） | `HORROR_SUPERNATURAL` | 五感微异化、压抑铺垫、假安全后的反转惊悚 |
| `rule-horror-logic`（新增） | `HORROR_SUPERNATURAL` | 规则怪谈条文设计、规则漏洞博弈与代价兑现 |
| `classical-prose`（新增） | `CLASSICAL_HISTORICAL` | 半文白韵律控制、白描留白、章回体起承转合 |
| `ancient-dialogue`（已有，升级） | `CLASSICAL_HISTORICAL`, `XIANXIA_FANTASY` | 古代礼法称谓、宗族/朝堂言辞试探与避讳 |
| `fair-play-clues`（新增） | `MYSTERY_THRILLER` | 公平推理线索埋点、红鲱鱼误导与现场勘查描写 |
| `scifi-hard-logic`（新增） | `SCIFI_APOCALYPSE` | 科技设定推演、极端环境生存细节与危机倒计时 |
| `urban-tension`（新增） | `URBAN_REALISM` | 现实利益拉扯、细腻心理刻画与生活流张力 |

- **路由引擎改造（`IntentAndModelRouter`）**：
  - 路由阶段优先过滤出 `匹配当前书目 genre_code 或 sub_genre_tags 或 ALL` 的 Skill 候选子集；
  - 彻底杜绝写《红楼梦》风格古典小说时因为正文出现“争执/推搡”而误触发修仙剑招 Skill，或写恐怖小说时误挂古风论道 Skill。
  - 同时支持**跨界子标签叠加**：例如主类 `CLASSICAL_HISTORICAL` + 副标签 `民俗恐怖`，候选池自动并集纳入 `classical-prose` + `horror-atmosphere`。

### 2. 人物动态属性表单（前端 + 后端 + 记忆抽取三端联动）
- `characters` 表保留通用基础字段（`name, aliases, role_type, personality, current_location, status_notes, inventory`），将题材专属字段收口进 `custom_attributes JSONB`（旧 `realm_or_level` 保留向下兼容，对仙侠类双向同步）。
- 前端 `LorePanel` 读取 `GET /api/v1/genres/{genreCode}` 返回的 `characterFieldSchema` 动态渲染输入框标签与占位符：
  - 仙侠书显示「修为境界 / 功法 / 宗门」；
  - 恐怖书显示「理智状态 / 已知规则 / 已触发禁忌」；
  - 古典书显示「字号 / 官职爵位 / 宗族辈分」；
  - 推理书显示「社会身份 / 不在场证明 / 潜在动机」。
- `ChapterDistiller` 与 `Lorekeeper` 同步读取同一份 `characterFieldSchema`，抽取和核验对应的 `custom_attributes`。

### 3. 少样本黄金文风锚点（Style Anchors — 根治“文风自噬/AI 味浓化”）
- **痛点**：长篇连续让 AI 续写 20~30 章后，仅靠抽象形容词描述文风会出现严重的**“模型吃自己输出导致 AI 味指数放大”**（通篇“深吸一口气”“眼神骤冷”“一抹不易察觉的弧度”）。
- **设计**：
  - 新增 `style_anchors(id, book_id, title, excerpt_text, sort_order, enabled)` 表，每本书允许作者标记 **1~3 段** 最满意的亲笔正文（或大师风格参考段，总长封顶 **900 字**）；
  - 固定拼入 **L0 静态 System Prefix**（位于 `story_charter` 之后、变动频率极低，**100% 复用 KV Cache 几乎不增加输入成本**）；
  - Prompt 明确指示 Writer：“请模仿以下【文风锚点】的句式长短、白描密度、对话节奏与用词克制度，但严禁照抄其剧情内容。”

### 4. Writer 禁用套话清单 & Critic 差异化审稿 Rubric
- 每个 `GenreProfile` 定义 `anti_cliche_rules`（禁用套话清单）与 `critic_rubric`（按题材定制的 4 项评分维度与扣分红线）：
  - Writer 的静态前缀自动注入本题材的 `style_principles` + `anti_cliche_rules`；
  - Critic 的 System Prompt 不再写死“战力境界矛盾”，而是由 `GenreProfile.critic_rubric` 动态拼装（如古典类重点扣“现代词汇/器物穿帮/称谓错乱”，恐怖类重点扣“直接情绪播报/规则自相矛盾/过早泄底”）。

### 5. Director 默认节拍模板自适应
- 不同题材的章节结构节奏截然不同，`Director` 根据 `GenreProfile.default_beat_pattern` 调整大纲规划习惯：
  - 恐怖类：`日常微异 → 异常升级 → 规则试探/信息残缺 → 假安全或真危机悬念`
  - 古典类：`起势入景 → 人物交锋与世情暗涌 → 余韵留白/章回悬念`
  - 悬疑类：`现场/新证浮现 → 盘问试探与矛盾证词 → 推演反转 → 新疑点抛出`
  - 仙侠/网文：`承接上章危机 → 冲突升级与压制 → 底牌破局/收获 → 更大格局钩子`

### 6. 公共 RAG 资料库标准化匹配（替代脆弱子串比对）
- `knowledge_documents` 新增标准化字段 `genre_codes`（例如 `HORROR_SUPERNATURAL` 或 `CLASSICAL_HISTORICAL,XIANXIA_FANTASY` 或 `ALL`），继续保留 `genre_tags` 作为细分子标签（如 `明清官制,民俗禁忌`）。
- 匹配规则升级（[KnowledgeDocumentService.java](file:///root/agent-project/foliarc-studio/services/biz-java/src/main/java/studio/foliarc/biz/rag/service/KnowledgeDocumentService.java#L1015-L1030)）：
  1. `genre_codes` 包含 `ALL` 或为空且 `genre_tags` 为空 → 全局通用默认挂载；
  2. `genre_codes` 命中本书 `genre_code` → 默认挂载；
  3. 本书 `sub_genre_tags` 与文档 `genre_tags` 有交集（跨界场景，如古典书加了“民俗恐怖”副标签）→ 默认挂载；
  4. 作者仍可在「我的资料库 / 设定库」里对任意单本书手动开关挂载（`book_knowledge_bindings` 优先级最高）。

---

## 三、新增 API 与前端交互要点

| 方法与路径 | 用途 |
| :--- | :--- |
| `GET /api/v1/genres` | 获取内置 6 大标准题材档案元数据（含动态人物字段 Schema、推荐副标签、文风铁律摘要） |
| `GET /api/v1/books/{bookId}/style-anchors` | 获取本书文风锚点列表 |
| `POST /api/v1/books/{bookId}/style-anchors` | 新增/更新文风锚点（校验总字数 ≤ 900 字，或从正文划词一键收藏为文风锚点） |
| `DELETE /api/v1/books/{bookId}/style-anchors/{id}` | 删除文风锚点 |

- **前端体验升级**：
  - 新建/编辑作品弹窗升级为「6 大主分类卡片选择 + 可点选/自定义的副标签芯片」；
  - 设定库「人物」页签根据当前书目 `genreCode` 动态切换表单字段；
  - 设定库「故事档案」页签新增 **「文风锚点（≤3 段）」** 卡片，编辑器划词菜单支持「设为本书文风锚点」。
