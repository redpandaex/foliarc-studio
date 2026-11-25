# 04｜双向自选范围蝴蝶效应链式修改引擎（Ripple Edit Engine）

> 对应子阶段：**Phase 5.8D**
> 核心目标：当作者修改任意一章/节的关键事实（如“把某配角从战死改为重伤逃走”“把关键法宝/物证换掉”“统一某人物姓氏或秘宝起源”）时，支持作者**自由框选任意影响范围 `[rangeStartChapter, rangeEndChapter]`（支持只查前文倒推一致性、只查后文蝴蝶效应、双向窗口、或按卷勾选）**，并在 **VIP 分级范围上限（50 / 300 / 1,000 / 2,000~全书）** 内自动完成 **事实变更提取 → 两级影响雷达 → 稀疏局部 Git Diff 补丁 → 人工逐章审阅采纳 → 定向重算记忆与状态快照** 的全闭环。

---

## 一、为什么必须改为「双向自选范围 + VIP 分级上限」？

固定写死“只能向后扫 500 章”在真实长篇写作中有两个明显短板：
1. **作者改中间章时，“前面”和“后面”往往都需要查**：
   - **向后查（Forward Ripple）**：改了第 400 章把二长老改为没死，需要查第 401~800 章哪里写了他已死。
   - **向前查（Backward Consistency）**：写到第 400 章突然灵光一闪改了某件秘宝的来历或人物关系，需要**倒推检查第 1~399 章**哪里与新设定冲突、或需要补埋铺垫！
   - **按需缩小范围省钱省时**：如果只是本卷内部的副本道具调整，作者只需框选「本卷第 301~380 章」，无需白白扫描全书。
2. **最大可选章数受 VIP 权益与双熔断共同治理**（详见 [05-VIP-ENTITLEMENTS-SYSTEM.md](./05-VIP-ENTITLEMENTS-SYSTEM.md)）：
   - **范围跨度上限**（由 `VipEntitlementService` 动态下发）：
     - `FREE`：最多框选 **50 章**（如前 20 + 后 30）；单次生成补丁 ≤ **5 章**
     - `VIP`：最多框选 **300 章**（如前后各 150 章 / 整卷）；单次生成补丁 ≤ **20 章**
     - `SVIP`：最多框选 **1,000 章**（如前 500 + 后 500）；单次生成补丁 ≤ **50 章**
     - `STUDIO`：最多框选 **2,000 章**，并解锁 **「全书 Coarse-to-Fine 摘要路由扫描」**；单次生成补丁 ≤ **100 章**（超出强制分批审阅）
   - **安全算力封顶**：无论范围选多大（哪怕全书 5,000 章），Stage 2a（PG 实体索引 + Milvus 标量区间预过滤）耗时恒定 **< 0.5s、0 Token**，并通过 **`max_candidates_cap`（20 / 50 / 100 / 150 节）** 限制送入快模型精判的疑似候选上限，彻底封死最坏情况成本！
   - **爆炸半径熔断（Blast Radius Cap）**：若强冲突数超出当前 VIP 档位的 `rippleMaxPatchChapters`（如改了世界观底层铁律导致冲突 180 章），系统自动进入 `BLAST_RADIUS_EXCEEDED`，输出完整冲突清单，允许作者**分批勾选（每次 ≤ 配额上限）**修复或开启新分支卷。

---

## 二、四种快捷范围选择模式（前端交互）

在编辑器点击「检测蝴蝶效应 / 同步前后文」时，弹出范围选择器（实时显示当前 VIP 最大可选章数与预估费用）：

| 快捷模式 | 示例（正在改第 400 章，当前共 750 章） | 底层 `narrative_order` 过滤区间 |
| :--- | :--- | :--- |
| **仅查后文（`FORWARD`）** | 第 401 ~ 700 章（按 VIP 上限截断） | `(source_order, end_order]` |
| **仅查前文倒推（`BACKWARD`）** | 第 100 ~ 399 章（检查旧伏笔/铺垫矛盾） | `[start_order, source_order)` |
| **双向对称窗口（`BIDIRECTIONAL`，默认）** | 往前 100 章 + 往后 200 章 = 第 300 ~ 600 章 | `[start_order, end_order] \ {source_section}` |
| **自由框选 / 按卷勾选（`CUSTOM_RANGE`）** | 手动输入起止章 `[120, 480]` 或直接勾选「第 2 卷 + 第 4 卷」 | 校验总跨度 `<= vip.rippleMaxRangeChapters`（STUDIO 允许全书） |

---

## 三、四阶段双向链式联动流水线

```mermaid
sequenceDiagram
    participant Author as 作者前端 (范围选择器 & Diff 审阅台)
    participant Java as biz-java (RippleEditService + VipEntitlement + Billing)
    participant PG as PostgreSQL 16
    participant Milvus as Milvus (chunks_v2 + summary_vectors)
    participant Py as agent-py (RippleEngine)

    Author->>Java: 1. 提交蝴蝶效应扫描 (sourceSectionId, rangeStart~rangeEnd 或 volumeIds)
    Java->>Java: 校验 VIP 跨度配额 & 今日扫描次数 (超限返回 403 VIP_QUOTA_EXCEEDED)
    Java->>PG: 创建 ripple_jobs (status=ANALYZING, range_start/end, vip_plan_code)
    Java->>Py: 异步投递 ripple.analyze (含新旧 Diff + 起止序位区间 + maxCandidates)

    Note over Py: Stage 1: Fact Diff 原子变更提取 (1次快模型, ~3s)
    Py->>Py: 过滤纯错别字/修辞润色 -> 抽出结构化 FactDelta 列表
    alt 无实质事实变动 (仅润色)
        Py->>Java: 回调 NO_CHANGE -> 结束 (成本 ~¥0.01)
    else 存在实质事实变更
        Note over Py,Milvus: Stage 2: Impact Radar 双向影响雷达 (~5~12s)
        Py->>Java: 2a. PG 索引粗筛: 查区间内涉事角色/别名/伏笔命中节 (排除 source_section)
        Py->>Milvus: 2b. Milvus 区间混合召回 (区间 <=1000章直搜 chunks_v2; 全书先搜 summary_vectors 再定位)
        Py->>Py: 2c. 合并去重取 Top maxCandidates -> 10~15 并发快模型分方向判冲突
        Note over Py: 区分 BACKWARD_INCONSISTENCY (前文铺垫矛盾) vs FORWARD_CONFLICT (后文剧情矛盾)
        Py->>Java: 落库 ripple_impacts + 精准计算补丁积分预估
    end

    Java-->>Author: 3. 展示【双向影响雷达报告】(按前文/后文分组) + 预估积分
    Note over Author: 若强冲突 > VIP单次补丁上限，触发熔断提示支持分批勾选

    Author->>Java: 4. 勾选本批待修复章节 (<= rippleMaxPatchChapters) + 确认预冻结积分
    Java->>Py: 异步投递 ripple.patch (仅处理已勾选节)

    Note over Py: Stage 3: Sparse Surgical Patch 稀疏局部补丁 (~20~90s)
    Py->>Py: 并发生成段落级最小改动 Patch Hunks (前文侧重统一铺垫, 后文侧重顺延剧情)
    Py->>Java: 落库 ripple_patches (status=READY_FOR_REVIEW)

    Author->>Java: 5. 逐章审阅 Git Diff 并点击「采纳合并」
    Java->>PG: 乐观锁写回 chapter_sections + 自动打快照 + 仅对采纳节定向重算记忆与摘要树
```

---

## 四、双向语义判别与最小手术补丁

### 1. Stage 1：Fact Diff（原子事实变更提取）
- 用 `difflib` 提取新旧版差异块 `hunks` + 前后 200 字上下文，抽取出结构化 `FactDelta`（`CHARACTER_STATE` / `ITEM_OWNERSHIP` / `WORLD_RULE` / `RELATIONSHIP` / `TIMELINE` / `NAME_OR_TITLE`），自动产出 `entities` 与 `searchKeywords`。
- 纯错别字/文笔润色直接返回 `hasSubstantiveFactChange = false` 短路退出。

### 2. Stage 2：双向 Impact Radar（区分前文倒推 vs 后文顺延）
- **粗筛策略随范围自适应**：
  - 选定区间 **≤ 1,000 章**：直接在 `section_summaries` + `foliarc_novel_chunks_v2` 执行硬区间过滤 `narrative_order >= start_order and narrative_order <= end_order and section_id != source_id`，取相关度 Top `maxCandidates`；
  - 选定区间 **> 1,000 章 / 全书扫描（STUDIO 专属）**：走 **Coarse-to-Fine** —— 先在 `foliarc_summary_vectors` + PG 实体别名索引定位高相关章节，再批量拉取命中的原文切片，同样截断到 Top `150` 候选节进入精判，保证耗时 < 15 秒。
- **方向感知冲突分类**：
  - 对 `target_order < source_order`（**前文**）：标注 `direction = BACKWARD`，模型重点判断“前文已写事实是否与修改后的新设定矛盾，或前文是否缺少合理铺垫”，输出 `BACKWARD_INCONSISTENCY` / `MINOR_MENTION` / `UNAFFECTED`；
  - 对 `target_order > source_order`（**后文**）：标注 `direction = FORWARD`，模型重点判断“后文剧情/物品/人物生死是否因前文改动产生硬逻辑穿帮”，输出 `FORWARD_CONFLICT` / `MINOR_MENTION` / `UNAFFECTED`。

### 3. Stage 3 & 4：稀疏局部补丁 + 分批合并
- Prompt 强制输出自然段级 `Patch Hunks`（`originalParagraph` + `patchedParagraph` + `reason`），保持原著文风与字数，绝不重写无关段落；
- 校验 `originalParagraph` 原文精确锚定，失配则标 `MANUAL_REVIEW_REQUIRED` 交由人工手改；
- **支持同一 `ripple_job` 多批修复**：如果某次大改命中了 65 章强冲突（SVIP 单批上限 50 章），作者可先勾前 40 章生成补丁并合并，再勾剩下 25 章发第二批 `generate-patches`，已合并章节自动标 `APPLIED`。
- 合并写入时校验乐观锁 `version`、自动保存改前快照至 `section_versions`，并仅对采纳修改的章节触发 `ChapterDistiller` 定向重算（清除旧 `AI_EXTRACTED` 记忆并刷新快照与摘要树）。

---

## 五、数据库表结构（`V8` 节选）

```sql
CREATE TABLE ripple_jobs (
    id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    book_id                 UUID        NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    owner_id                UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_section_id       UUID        NOT NULL REFERENCES chapter_sections(id) ON DELETE CASCADE,
    source_chapter_no       INT         NOT NULL,
    source_narrative_order  BIGINT      NOT NULL,
    vip_plan_code           VARCHAR(24) NOT NULL DEFAULT 'FREE',
    range_mode              VARCHAR(24) NOT NULL DEFAULT 'BIDIRECTIONAL',
        -- FORWARD / BACKWARD / BIDIRECTIONAL / CUSTOM_RANGE / FULL_BOOK
    range_start_chapter     INT         NOT NULL,
    range_end_chapter       INT         NOT NULL,
    range_start_order       BIGINT      NOT NULL,
    range_end_order         BIGINT      NOT NULL,
    selected_volume_ids     JSONB       NOT NULL DEFAULT '[]'::jsonb,
    max_candidates_cap      INT         NOT NULL DEFAULT 50,
    max_patch_batch_limit   INT         NOT NULL DEFAULT 20,
    status                  VARCHAR(32) NOT NULL DEFAULT 'ANALYZING',
        -- ANALYZING / NO_CHANGE / READY_TO_PATCH / BLAST_RADIUS_EXCEEDED / PATCHING / READY_FOR_REVIEW / PARTIALLY_APPLIED / APPLIED / FAILED / CANCELLED
    change_summary          TEXT        NOT NULL DEFAULT '',
    fact_deltas_json        JSONB       NOT NULL DEFAULT '[]'::jsonb,
    scanned_section_count   INT         NOT NULL DEFAULT 0,
    candidate_section_count INT         NOT NULL DEFAULT 0,
    backward_conflict_count INT         NOT NULL DEFAULT 0,
    forward_conflict_count  INT         NOT NULL DEFAULT 0,
    minor_mention_count     INT         NOT NULL DEFAULT 0,
    estimated_cost_micros   BIGINT      NOT NULL DEFAULT 0,
    actual_cost_micros      BIGINT      NOT NULL DEFAULT 0,
    error_message           TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ripple_jobs_book ON ripple_jobs (book_id, created_at DESC);

CREATE TABLE ripple_impacts (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id             UUID        NOT NULL REFERENCES ripple_jobs(id) ON DELETE CASCADE,
    target_section_id  UUID        NOT NULL REFERENCES chapter_sections(id) ON DELETE CASCADE,
    chapter_no         INT         NOT NULL,
    section_no         INT         NOT NULL,
    narrative_order    BIGINT      NOT NULL,
    chapter_title      VARCHAR(200) NOT NULL DEFAULT '',
    direction          VARCHAR(16) NOT NULL,  -- BACKWARD / FORWARD
    severity           VARCHAR(32) NOT NULL,  -- STRONG_CONFLICT / MINOR_MENTION / UNAFFECTED
    conflict_reason    TEXT        NOT NULL DEFAULT '',
    matched_excerpt    TEXT        NOT NULL DEFAULT '',
    patch_status       VARCHAR(24) NOT NULL DEFAULT 'UNSELECTED',
        -- UNSELECTED / QUEUED / READY_FOR_REVIEW / APPLIED / SKIPPED
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ripple_impacts_job_order ON ripple_impacts (job_id, direction, narrative_order);

CREATE TABLE ripple_patches (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id             UUID        NOT NULL REFERENCES ripple_jobs(id) ON DELETE CASCADE,
    impact_id          UUID        NOT NULL REFERENCES ripple_impacts(id) ON DELETE CASCADE,
    target_section_id  UUID        NOT NULL REFERENCES chapter_sections(id) ON DELETE CASCADE,
    batch_no           INT         NOT NULL DEFAULT 1,
    base_version       BIGINT      NOT NULL,
    hunks_json         JSONB       NOT NULL DEFAULT '[]'::jsonb,
    status             VARCHAR(24) NOT NULL DEFAULT 'PENDING',
        -- PENDING / READY_FOR_REVIEW / ACCEPTED / REJECTED / APPLIED / VERSION_CONFLICT / MANUAL_REVIEW_REQUIRED
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ripple_patches_job ON ripple_patches (job_id, batch_no, status);
```

---

## 六、不同 VIP 档位范围的成本与耗时测算（DeepSeek 级快模型基准）

| VIP 档位 | 最大扫描范围（双向任选） | 粗筛候选上限 | 扫描阶段耗时 & 成本 | 典型局部补丁耗时 & 成本 |
| :--- | :--- | :--- | :--- | :--- |
| **`FREE`** | ≤ 50 章（~18 万字） | Top 20 节 | ~4 秒 / **~¥0.08** | ≤ 5 章：~15 秒 / **~¥0.15** |
| **`VIP`** | ≤ 300 章（~105 万字） | Top 50 节 | ~6 秒 / **~¥0.20** | ≤ 20 章：~35 秒 / **~¥0.60** |
| **`SVIP`** | ≤ 1,000 章（~350 万字） | Top 100 节 | ~10 秒 / **~¥0.42** | ≤ 50 章：~70 秒 / **~¥1.50** |
| **`STUDIO`** | ≤ 2,000 章 / 全书摘要路由 | Top 150 节 | ~14 秒 / **~¥0.65** | ≤ 100 章（分批）：~2 分钟 / **~¥3.00** |

---

## 七、REST API 清单

| 方法与路径 | 说明 |
| :--- | :--- |
| `POST /api/v1/books/{bookId}/sections/{sectionId}/ripple-analyze` | 发起双向/自定义范围蝴蝶效应分析（支持 `rangeMode`、`rangeStartChapter`、`rangeEndChapter`、`volumeIds`、`oldVersionId`；自动校验 VIP 配额） |
| `GET /api/v1/books/{bookId}/ripple-jobs/{jobId}` | 查询任务进度、FactDelta、按前文/后文分组的冲突雷达列表、熔断状态与补丁积分预估 |
| `POST /api/v1/books/{bookId}/ripple-jobs/{jobId}/generate-patches` | 针对作者本批勾选的 `impactIds`（校验 ≤ VIP 单批补丁上限）预冻结积分并异步生成局部 Diff 补丁（支持同一 Job 多次分批调用） |
| `POST /api/v1/books/{bookId}/ripple-jobs/{jobId}/apply` | 将作者审阅通过的 `patchIds` 原子写回正文、自动打快照并触发定向记忆重算 |
| `POST /api/v1/books/{bookId}/ripple-jobs/{jobId}/cancel` | 取消进行中的扫描或补丁任务并退还多余预冻结积分 |
