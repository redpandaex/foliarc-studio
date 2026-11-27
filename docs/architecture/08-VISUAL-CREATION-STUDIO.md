# FoliarcStudio 卷弧工坊 · Phase 7 可视化叙事与创作工坊施工蓝图

建议把四个可视化方向接进现有作者工作台，先交付能查、能改、能回到正文的关系图与泳道，再开放节拍编排、分叉推演和节奏与设定板。一个人物、一条伏笔、一个小节在各视图共享同一个业务身份。

**设计依据**　2026-11-27 `main` 基线（`893ddab`，已包含「清风素纸」色板、隐形细滚动条与紧凑全宽工作台）。本文的「现有」来自静态源码与迁移核查。React Flow 另做了本机包体取样和 React 渲染探针。以下新接口、迁移、权益值、工期和性能指标均为施工建议。读法可按第 2 节走一遍作者操作，再用第 3 至 8 节拆工单，第 9 节用于拍板。

## 1. 真实底座与接入位置

| 已核实的事实 | 对施工的直接影响 |
| --- | --- |
| Java 21、Spring Boot 4.1.3、MyBatis-Plus。`biz-java` 打开 `classpath:db/migration`，`admin-java` 关闭 Flyway。迁移文件实际放在 `services/java/foliarc-common/src/main/resources/db/migration/`，最新为 `V13__phase6_admin_audit_and_run_events.sql`。 | 7A 零迁移。以下从 V14 规划。作者 REST 放 `biz-java`，共享实体、Mapper、权益及业务服务沿现有 common 边界放置。动工时再核一次迁移号。 [Maven 父工程](../../services/java/pom.xml) · [业务配置](../../services/java/biz-java/src/main/resources/application.yml#L11) · [后台配置](../../services/java/admin-java/src/main/resources/application.yml#L11) · [V13](../../services/java/foliarc-common/src/main/resources/db/migration/V13__phase6_admin_audit_and_run_events.sql) |
| `apps/web` 使用 Next.js 16.4.6、React / React DOM 19.3.0、Tailwind 4.3.3，业务依赖里尚未引入 React Flow 或布局引擎。书页是 Client Component，正文用 `Textarea`，已有目录树、乐观锁保存、逐段 Diff、右栏双 Tab。 | 新增视图壳和懒加载面板。保留编辑草稿、选区、Copilot 连接的生命周期。 [Web 依赖](../../apps/web/package.json) · [作者工作台](<../../apps/web/src/app/books/[bookId]/page.tsx#L177>) · [设计契约](../../docs/DESIGN-SYSTEM.md) |
| 数据库的小节实名是 `chapter_sections`，HTTP 资源名是 `sections`。卷、默认小节、摘要、两级排序唯一键已在 V9。当前 `structureApi` 提供建节、改节、删节、整章预览。缺少批量重排。 | 普通章和多小节章分别沿现有入口保存。7B 独立实现带版本的重排事务。 [V9](../../services/java/foliarc-common/src/main/resources/db/migration/V9__phase58a_structure_and_logarithmic_memory.sql) · [StructureService](../../services/java/foliarc-common/src/main/java/studio/foliarc/biz/novel/service/StructureService.java#L190) · [structureApi](../../apps/web/src/lib/api.ts#L947) |
| 人物、设定、伏笔和关系/时间线已有作者读写接口。`getStructuredMemory(maxChapterNo)` 对关系起始章和事件章设限，人物和伏笔仍读取当前列表。内部 Agent 变体另做可见性处理。V10 已有角色状态快照、`known_by`、伏笔埋设位置与揭示策略。 | 防剧透做专用服务端时点投影。图谱的关系事实仍写原表。 [MemoryService](../../services/java/foliarc-common/src/main/java/studio/foliarc/biz/memory/service/MemoryService.java#L78) · [V10](../../services/java/foliarc-common/src/main/resources/db/migration/V10__phase58b_foreshadowing_director_and_continuity.sql) · [实体视角组装](../../services/java/foliarc-common/src/main/java/studio/foliarc/biz/memory/service/EntityScopedMemory.java#L57) |
| FastAPI 的 `EditorialTeamOrchestrator` 使用自研 `EditorialBlackboard`，链路为 Router → Director → 可选 `OUTLINE_APPROVAL` → Lorekeeper → Writer ↔ Critic。checkpoint 经 Java 写入 PG。`pyproject.toml` 和 `uv.lock` 当前没有 `langgraph`。 | 7C 把 LangGraph 作为 What-If 专用新增依赖。已有主干写作继续使用现成编辑部及审批协议。 [编排器](../../services/agent-py/src/foliarc_agent/agent/multi_agent.py#L208) · [Python 依赖](../../services/agent-py/pyproject.toml) · [路线图](../../docs/ROADMAP.md#L297) |
| Python `/internal/v1/runs/start`、`/{run_id}/resume`、`/{run_id}/control` 管任务，另有内部 RAG、沙箱和 eval 路由。Runner 向 Redis Stream 写事件。Java 向作者提供受鉴权的 SSE。V13 存运行事件供追踪。V12 Ripple 为改动既有正文分析前后文影响和补丁。 | 共用事件信封、游标和追踪。模型推演先写独立候选区。采纳对既有手稿有影响时可从相应小节打开已有 Ripple 审阅。 [FastAPI 路由](../../services/agent-py/src/foliarc_agent/main.py#L290) · [事件写入](../../services/agent-py/src/foliarc_agent/streaming/runner.py#L298) · [作者 run API](../../services/java/biz-java/src/main/java/studio/foliarc/biz/run/controller/RunController.java#L66) · [Ripple](../../services/java/foliarc-common/src/main/resources/db/migration/V12__phase58d_ripple_edit_engine.sql) |
| `MinioStorageService` 有默认文档桶、流式上传、整对象字节数组下载和尽力删除。资料库另有知识入库链路。`VipEntitlements`、分级限流、月预算和微积分账本已存在。 | 图片使用同一 MinIO 基础设施与独立对象前缀，新增缩略图与图片授权读取。读图和手工编排可独立于模型计费。 [对象存储](../../services/java/foliarc-common/src/main/java/studio/foliarc/biz/storage/MinioStorageService.java) · [权益服务](../../services/java/foliarc-common/src/main/java/studio/foliarc/biz/vip/service/VipEntitlementService.java) · [计费服务](../../services/java/foliarc-common/src/main/java/studio/foliarc/biz/billing/service/BillingService.java#L143) |

## 2. 从作者的一次操作设计五个视图

### 2.1 一套工作台外壳

桌面顶栏保留收目录、书名、当前章、保存状态与收助手，中间放单行视图切换 **正文 / 关系图谱 / 剧情泳道 / 灵感画布 / 节奏与设定板**。正文特有的快照、导出、版式收进正文操作区。图形视图把「当前章为止」、缩放复位和本视图的一个主操作放在画布上沿。第一批只显示已开放的视图。默认仍落在正文，有视图深链时直达目标。

| 中央视图 | 作者首屏所见与主操作 | 左右联动 |
| --- | --- | --- |
| 正文 | 现有正文、自动保存、划词工具及 Diff。状态条显示当前章或小节 | 左目录。右「AI 写作 / 故事设定」。有钉图时在故事设定顶部显示可收起缩略条 |
| 关系图谱 | 当前章滑块，角色实心节点，势力分组外框，关系有方向、类型和生效位置。默认当前章相关角色及一跳邻居。可展开全卷、折叠势力、聚焦角色 | 点节点定位右侧故事设定同一实体，含按章出场热力、证据、可编辑字段。点热格打开该章首次有证据的小节。拖动节点仅改变布局 |
| 剧情泳道 | 卷头 → 章号横轴。事件主轨、伏笔弧线、角色/势力/地点子轨。实线表示已有证据，目标回收窗口用括号区间，到期/逾期有文字和图标 | 点事件定位正文。点伏笔选「暗示 / 回收」送右栏。点普通支线送创作指令草稿。左目录切卷只改可视窗口 |
| 灵感画布 | 无限平移缩放。节拍卡展示标题、角色/地点/伏笔芯片、场景目标、阻力、转折、张力 1–10。章内按叙事顺序绘制张力折线。空分断线。从任一转折拉出候选线，未来 2–3 章以虚线卡表示 | 点卡可打开目标小节。手稿次序有专用拖拽把手与落点预览。选 A/B 分支在同一右栏对比代价、证据和待解决的问题 |
| 节奏与设定板 | 一个视图内用「节奏 / 世界版图 / 灵感板」切换。节奏先给字数、对话、冲突、戏份四项和取样覆盖率，再下钻到图表。版图画势力控制地标、人物归属、地点通路。灵感板可上传、关联、钉选 | 点异常定位相同章。点势力打开关系图的同一分组。把港口概念图钉到本节，回正文仍能看到。缩略图缺失保留标题与重试 |

沿用 `foliarc:workbench_density` 的 `compact / balanced / relaxed`，紧凑目录 `w-56`、助手 356px，中央占余宽。另两种密度调卡片内边距和标签密度。画布可用宽度跟中央容器走。建议中央可用宽度低于 640px 时优先把助手转抽屉，再收左栏。390px 单栏仅保留视图选择器、当前章、操作菜单与一个可返回的底部详情。根据实际容器计算，恢复宽屏时复原作者的栏位选择。

主题 ID 沿用 `paper / sepia / ink`，运行中的中文名为「晨露素白 / 柔光暖纸 / 静谧夜墨」。设计文档中仍有旧色板名称，UI 以 `theme-config.ts` 为准。画纸、卡、字、焦点、边分别取 `--background / --card / --foreground / --ring / --muted-foreground`。已回收、到期、逾期、AI 候选各用 `--status-success / warning / danger / ai` 对应令牌，并加对钩、时钟、叹号、虚线。作者端做局部图形样式映射。三个主题分别验收普通字 ≥4.5:1、关键线与焦点 ≥3:1。保留现有字族和主题初始化。 [主题配置](../../apps/web/src/lib/theme-config.ts) · [清风素纸色板](../../apps/web/src/app/palette.css) · [语义令牌](../../apps/web/src/app/ui-contract.css)

### 2.2 跨视图导航与写回

建议在现有书页上提取一个 `WorkbenchShell`，统一持有 `bookId, view, activeChapterId, activeSectionId, asOf, visibility, focus, rightTab`。`activeChapterId` 是正在写的章。`asOf` 是浏览时点。拖滑块不会切走正在写的草稿。深链例如 `/books/{bookId}?view=lanes&chapterId={id}&asOfChapter=12&focus=clue.{uuid}`。保留原 `chapterId` 和 sessionStorage 恢复，新增 `sectionId` 并验归属。主动换视图/钻取留历史项，连续滑动用 `replaceState`。浏览器后退恢复来源视图、筛选和滚动位置。窄屏返回后焦点落回原卡。

| 作者动作 | 唯一写入路径 | 读回和失败处理 |
| --- | --- | --- |
| 从甲拖线到乙，选「同盟」，锚定第 12 章第 2 节 | `memoryApi.upsertRelation` → Java 按本书规范名、起始章、源节写 `character_relations`。AUTHOR 边与按节抽取的 AI 证据沿 V10 的部分唯一索引区分 | 以服务器结果替换临时边，刷新图谱、设定栏和相关轨。下一次 Lorekeeper 可读同一事实。现有实现只取返回前 8 条关系，7A 增加按聚焦角色与最近有效证据选择、返回命中 ID。已开启的 run 快照保持其原事实 |
| 在右侧修改人物或事实 | 普通当前设定沿 `loreApi` / `memoryApi`。历史详情先选「在此节记录变化」或「编辑当前设定」。事实的方向、类型变更走受事务保护的替换操作 | 实体 UUID 不变时各视图共用选择。自然键或来源变化重新投影。多标签广播失效键后重读。写失败还原待提交连线、保留表单。三端以 Java 回包为准 |
| 在第 12 章点伏笔 #F03「送入导演槽」 | 把 `{foreshadowingId,mode:HINT}` 送入上提的导演草稿。`ForeshadowingDirectorSlot` 已接收受控 `directives/onDirectivesChange`，当前拥有者是 `AiCopilotPanel`。按书/章/节隔离，同 ID 替换，已有 3 条时提示替换。`PAYOFF` 支持 `FULL_RESOLVE / PARTIAL_REVEAL` | 打开右栏 AI，展开导演槽，作者点击生成才传给 `runsApi.start`。Java 对重复 ID、同书归属和最多 3 条再做校验。只选指令不改变伏笔状态。作者确认回收走现有 `transition`。普通支线附标题、目标和可见证据到 `instruction`。可从预览逐项移除 |
| 拖节拍卡排第 12 章，或推给 Director | 移动坐标走布局。拖顺序把手生成「原第 3 节 → 新第 2 节」预览。确定后调用批量 `apply-structure`。未绑定卡经此动作建节并保存映射。一个已绑定的主节拍对应一个小节 | 返回目录、卡片、版本和受影响小节 ID。右栏可将卡片序列作为 `MULTI_AGENT` 的导演输入，沿 `requireOutlineApproval=true` 中断，以 `runsApi.approve(editedOutline)` 继续。预填大纲按节点 ID/版本记录来源。正文生成后走 Diff 或空稿写入 |
| 比较两种未来，采用 B，归档 A | 先预览主干映射与费用。「立为主干并生成正文」提交一次采纳命令，事务中记选定分支、章节映射和写作任务。详见第 5 节 | 目录出现目标草稿和生成状态。未选方案可归档进「平行世界」并保留报告，点来源回到当时转折。正式草稿的保存事件刷新图谱、泳道与节奏缓存 |
| 从节奏雷达的高冲突章或钉图返回正文 | 深链指向证据 `chapterId + sectionId`。图片关联业务实体，钉选另记用户与作用域 | 有多个证据先列小节。更换章/节后侧栏按「本节 → 本章 → 全书」取钉图，按资产去重 |

跨视图导航须把当前 `saveCurrentUnit` 改成有结果的保存屏障。现有自动保存约 2.5 秒、切章在 `dirty` 时会等待保存，但保存函数会捕获异常，调用者不能凭 Promise 完成认定成功。建议按「书/章/节 + 本地编辑代次」串行提交。返回 `saved / conflict / failed`，切目标前等待进行中保存。旧响应只更新对应代次。冲突留草稿并展示服务端版本。仅切中央可视视图可保留原编辑器草稿与选区，跳另一章、重排、立主干时必须经过此屏障。右栏 SSE 状态提升或持续挂载，切图后按 runId 续流。来自原设定表单的写入统一通知书内查询失效，抽取/记忆审批完成时同样刷新。跨设备 A 先在重新聚焦、保存完成及可见窗口定时校验 ETag。B 后比较 lore_epoch / structure_epoch，附带重新查询受影响的实体。 [书页保存](<../../apps/web/src/app/books/[bookId]/page.tsx#L429>) · [导演槽](../../apps/web/src/components/foreshadowing-director-slot.tsx#L22) · [Lorekeeper 读取关系](../../services/agent-py/src/foliarc_agent/agent/multi_agent.py#L1000)

### 2.3 防剧透是一个服务端读合同

首选「读到这里」和显式「作者规划」两种视角。投影请求带目标章或节、可选 POV、可视横轴区间。Java 验证目标归属，本节用其真实 `narrative_order`，整章末用 `chapter_no × 1000 + 999`。区间只管横轴，不得扩大可知范围。严格模式未传截止点时取当前已选写作单元。空书或开篇前返回空事实。旧接口把 0 或缺值视为无上限，不能用它表达开篇前。全书规划可显示未来目标或未埋伏笔。严格视角内导演候选、联想搜索、缩略图、统计、标题、热格和图例计数全部使用相同边界。

| 数据 | 「读到这里」的取值规则 |
| --- | --- |
| 角色与势力 | 只显示截至此处有可信出场、关系端点或快照的名字。角色状态取同书同人的最后一份可用 `character_state_snapshots`，以 order、created_at、id 确定同序取舍。名字取当时证据。没有快照显示「当时状态未记录」。`last_seen_order` 只记录最近一次，不能推导首次出场与历史。`world_entries` 无登场时间。未锚定的设定只在规划视图出现 |
| 关系与事件 | 有来源小节时同时核对小节、章号及事实生效时间，取更严格的边界。只有章号的旧行仅在该章已读完后进入。旧作者行如同一自然键的正文描述已被改写且无原节证据，展示可核实的端点和生效章，省略新描述。章/节皆缺的旧事实放「时间未标注」。`known_by` 按 PUBLIC 或所选 POV 当时的身份/别名筛。默认读者仅见 PUBLIC。7A 只投影有时间证据的事实，后补描述无历史证据时只显示带出处的简述。历史起止与逐次修订见 7B |
| 伏笔 | 未到埋设点不可见。按当时已发生的埋设、`hint_history_json` 中可证实的提示和实际回收位置绘制。当前 `RESOLVED` 不得提前闭弧。无可判定历史的中间态显示「已埋，进展未记录」。默认只给安全表层文本/编号，含底牌的标题或文字使用中性标签。读者 DTO 省略 `hiddenTruth`、未来回收节、规划区间、未来提示及总量。规划视角才显示到期预警、私有底牌按需展开 |
| 检索与旁路 | 书页在读到这里时先加载裁剪后的目录和投影，不能让隐藏的 `ForeshadowingDirectorSlot`、`LorePanel` 或原 `getTree` 预拉全书明细。切模式取消在途请求，缓存键含用户、书、视角、POV、时点和范围。迟到应答按代次丢弃。严格 run 连 Director 的 bible 预取、MCP、Lorekeeper HTTP、RAG 父块与摘要都受同一截止约束。授权的私库、公共参考仍需明确可见性。缺少安全粒度时剔除整块。Synopsis、charter、世界手填描述及资料库文档若缺时点证明，只能在规划或作者明确允许的参考范围内使用。严格模式缺资料时返回空上下文 |

导演槽在 AS_OF 只允许符合当时状态的动作。要求使用底牌的 PAYOFF 切换到作者规划并由作者明确选定该条伏笔。服务端也校验动作和选中时点。现有导演包会对 PAYOFF 放开底牌、给 Critic 的保护包可包含真相，应在内部组包之前施加相同策略。严格模式还须过滤已打开书页中的旧搜索结果、tooltip 和钉图。 [现有导演包规则](../../services/java/foliarc-common/src/main/java/studio/foliarc/biz/memory/service/ForeshadowingDirector.java#L103)

7A 的新 run 请求附 `visualContext{visibility,cutoffSectionId,cutoffOrder,povId,focusIds}`，由 Java 根据本书 ID 重算边界。首个派发在已有 `agent_checkpoints` 的 step 0 记录与 Blackboard 兼容的已裁剪初始状态及上下文版本，此后每份 checkpoint 继承这些字段。恢复/审批路径从同一 run 恢复当时边界。这样 A 也能在刷新和人工中断后保持相同视角。无历史锚点的旧 run 仅从原工作区继续，不在严格视图自动重放。

验收样书固定设第 8 章首次遇见「舟师」，第 12 章结盟，第 16 章回收 #F03，第 18 章身亡。读第 7 章的响应中找不到其身份、坐标或隐藏计数。第 12 章见同盟与未收弧。第 16 章见已收。第 17 章不出现死亡。第 12 章第 1 节看不到同章第 2 节的新关系。对 UI 响应体、右栏预取、SSE 和模型输入的测试使用同一套预期。

## 3. PostgreSQL 模型与迁移合同

### 3.1 7A 可以直接使用的列和接口

此阶段对 V1–V13 保持零 DDL。作者规划视角可立即用原接口做第一屏。上线防剧透、稳定写回和分页仍需要新增 Java 查询及前端接线。

| 原表 | 直接复用的字段／作者 API | 视图产物与边界 |
| --- | --- | --- |
| `volumes / chapters / chapter_sections` | 卷 `volume_no, title, arc_goal, pacing_notes, realm_scope`。章 `id, volume_id, chapter_no, structure_mode, word_count, version`。节 `id, chapter_id, section_no, narrative_order, is_default, word_count, version, content_hash, pov_character`。`GET /api/v1/books/{b}/tree`、章/节详情、`full-preview` | 目录提供稳定 ID，未分卷旧书支持虚拟「第一卷」及空卷。`tree` 是摘要 DTO，`content_hash / pov_character` 等按用途从后端取。SINGLE 的默认节与章正文是镜像，只计一次 |
| `characters / character_state_snapshots` | `name, role_type, aliases, lifecycle_status, last_seen_order, arc_scope, locked_fields, custom_attributes`。快照 `character_id, section_id, narrative_order, snapshot_json`。`GET/POST …/characters` | 规划页分组按角色类型、已录入的 `custom_attributes.faction` 与设定标题匹配。旧书无此属性显示未归组。同名别名、多义别名只列待绑定候选。时点页按快照/出场证据单独查询 |
| `character_relations` | `source_character, target_character, relation_type, description, since_chapter_no, confidence, source_section_id, narrative_order, source, known_by`。`GET …/memory`、`POST …/memory/relations`、`DELETE …/memory/relations/{id}` | 两端当前存名字文本，`characters(book_id,name)` 唯一。前端稳定 ID 映射为同书规范名。新建须选两个已存在人物。历史未解端点显示「待绑定」。发现歧义后禁止静默合并。V10 将作者自然键与按来源节的抽取键分开。TS 关系/事件接口补齐 Java 已有的来源、时序和可见性字段 |
| `timeline_events / section_summaries` | 事件 `chapter_no, event_title, summary, involved_characters, location, source_section_id, narrative_order, known_by`。`POST …/memory/timeline`。节摘要 `section_id, content_hash, involved_characters, time_and_place, status` | 一条事件在主轨有一个 ID，可被角色/地点多条轨引用。热力用已匹配的不同小节数，旧事件只有章号只给章级标记。标明证据覆盖率，`STALE` 或哈希失配从统计中剔除。势力轨按已确认归属投影。未分类事件留在主轨 |
| `foreshadowings` | `code, importance, surface_clue, hidden_truth, reveal_policy, planted_chapter_no, planted_section_id, planted_narrative_order, target_chapter_min/max, resolved_chapter_no, resolved_section_id, hint_history_json, involved_characters, status, source, author_locked`。`GET/POST …/foreshadowings`、`GET …/foreshadowings/due?chapterNo=N`、`POST …/{id}/transition` | 规划页用 `dueNow / readyToPlant / activePlanted`。展示按业务口径，已回收优先。超过 max 为逾期，命中 min 或后端 due 为到期，缺窗口为未定期。虚线区间是目标、钩点是实际回收。严格页用时点投影，未知回收端以裁切箭头表示 |
| `world_entries` | `id, category, title, content, tags`。`GET/POST …/worldbook`、`DELETE …/worldbook/{id}` | `FACTION / LOCATION` 可作势力和地标目录，其他分类保留。A 可读基础地点/势力，尚无两条世界设定间的外键关系和坐标。原 POST 建条目。后续编辑已有条目补 PATCH |
| `agent_runs / agent_checkpoints / chapter_versions / section_versions` | `section_id, pov_character, foreshadowing_directives_json, current_node, interrupt_type`、审批、版本快照、流式回放 | 复用后续写作及人工审阅。A 的布局仅设备本地，键含用户/书/视图，限量保存坐标，不存底牌、长记忆或正文。无坐标时按 ID 稳定排列 |

[V3 设定](../../services/java/foliarc-common/src/main/resources/db/migration/V3__create_novel_lore_and_prompts.sql) · [V4 关系和事件](../../services/java/foliarc-common/src/main/resources/db/migration/V4__create_rag_outbox_and_memory.sql) · [V11 扩展字段](../../services/java/foliarc-common/src/main/resources/db/migration/V11__phase58c_genre_profiles_and_style_anchors.sql) · [作者 API 类型与写入契约](../../apps/web/src/lib/api.ts#L1333) · [Java 对应 DTO](../../services/java/foliarc-common/src/main/java/studio/foliarc/biz/rag/dto/RagDtos.java#L126)

### 3.2 通用 DDL 约定

以下是供迁移作者展开的逻辑 DDL。`id` 为 `uuid primary key default gen_random_uuid()`，`b` 代表实际列 `book_id uuid not null references books(id) on delete cascade`，`u` 代表 `user_id uuid not null references users(id)`。`可空` 或 `?` 表示 nullable。时间统一 `timestamptz`。下表中带 `_id` 的字段均为 uuid，`_epoch / _order / _version / _micros` 为 bigint（显式注明 int 的局部序号除外），`_hash` 为 64 位十六进制 SHA-256，token 与字数为非负 int。布尔默认 false，计数/epoch 初值 0，JSON 集合默认空数组或空对象，可选结果初值为 NULL。每张可编辑主表带 `version bigint not null default 0, created_at, updated_at`。可恢复的作品对象另带 `deleted_at`。创建/更新时间默认 `now()`，写事务递增版本。列表与唯一自然键只算 `deleted_at IS NULL`，业务引用用始终唯一的 ID。

关联两个同书资源使用复合 FK，例如 `(book_id, character_id) → characters(book_id,id)`。V14 对被引用的历史表补 `UNIQUE(book_id,id)`，包括 characters、world_entries、timeline_events、character_relations、foreshadowings、chapters、chapter_sections。对新增父表同样声明。绑定小节还要 `(book_id,chapter_id,section_id) → chapter_sections(book_id,chapter_id,id)` 并建配套唯一键。含 owner 的表使用 `(owner_id,book_id) → books(owner_id,id)`，父表补唯一键。实体外键列为 uuid，序号为 int，金额为 bigint micros。状态/种类均以列出的集合做 `CHECK`。每个引用列补支持删除/联查的 B-tree，下面列出决定读写正确性或分页顺序的特殊索引。

画布编辑、分支等调用提交 `expectedVersion`。`UPDATE … WHERE id=? AND book_id=? AND version=? AND deleted_at IS NULL` 命中一行才成功，新版随响应返回。多人/多标签冲突返回 409 与受影响 ID、当前版本，允许重载后重新应用移动。附属关系表以复合主键和父版本事务性替换。采纳目标外键的活动指针可空，写入时另存不可变 ID/标题快照以保留出处。缓存允许硬删重建。模拟用量、账本、采纳记录保留。软删作品对象不级联删除正式正文。删除整本书随现有外键策略清理书内资料，对象存储另走幂等清理。JSONB 用于版本化的小型嵌套值、证据或不可变报告。关系、排序、可见性、配额、钱和经常筛选的维度都有实体列。

### 3.3 7B · V14__visual_structure_and_lore.sql

| 新表／用途 | 关键列与约束 | 关键索引／删除行为 |
| --- | --- | --- |
| `book_visual_state`，书级失效控制 | `book_id PK/FK, structure_epoch bigint, lore_epoch bigint, updated_at`，初值 0。建书或第一次写惰性创建。所有正文结构/设定入口在相同事务递增相应 epoch | 按书锁一行。投影 ETag、推演输入和排序失效带 epoch。A 使用响应摘要哈希即可 |
| `visual_canvas_layouts`，每位作者的视图 | `id,b,u, view_kind text(GRAPH/BOARD/ATLAS), scope_key varchar(100), viewport jsonb, version, deleted_at`。`scope_key` 为 book 或已校验的卷 ID。viewport 限 `{x,y,zoom}`，有限数值、zoom 有界 | 活跃 `UNIQUE(b,u,view_kind,scope_key)`。删除布局清理其位置。业务对象继续存在 |
| `story_board_nodes`，主干计划卡 | `id,b, chapter_id?, section_id?, title varchar(200), scene_goal text, opposition text, turn text, tension smallint? CHECK 1..10, plan_order bigint, source text(AUTHOR/ADOPTED), version, deleted_at`。有 section 必有 chapter。独立草卡允许两个目标皆空 | 活跃 `UNIQUE(b,section_id) WHERE section_id IS NOT NULL`。`(b,chapter_id,plan_order,id)`。删卡只解除关联。删小节时在同一事务解绑卡并留下章归属 |
| `visual_node_positions`，按节点保存 | `id,b, layout_id, character_id?, world_entry_id?, board_node_id?, x numeric(11,2), y numeric(11,2), pinned boolean, version`。`num_nonnulls(character_id,world_entry_id,board_node_id)=1` | 各实体一个 `UNIQUE(layout_id,对应非空FK)`，按 layout 批量读取。位置行随布局或实体硬删。共享画布缩放与单节点坐标分别做 CAS，可并发移动不同节点 |
| `story_board_node_links`，可点击芯片 | `id,b,node_id,character_id?,world_entry_id?,foreshadowing_id?`，三选一 CHECK。地点/势力从 world 的 category 得出 | 每种类型 `UNIQUE(node_id,目标FK)`，目标反查节点索引。增删芯片与节拍版本同事务 |
| `story_board_edges`，节拍连线 | `id,b,from_node_id,to_node_id,kind text(NEXT/CAUSE),label varchar(100),version,deleted_at`。禁止自环，两个端点均须同书 | 活跃 `UNIQUE(b,from_node_id,to_node_id,kind)`，反向索引 `(b,to_node_id)`。NEXT 检环。叙事排序以章内 plan_order 为准。删除节点连同边软删 |
| `plot_threads / plot_thread_events`，可命名支线 | 主表 `id,b,title, goal text, kind text(MAIN/SUBPLOT), sort_order int, version, deleted_at`。关联表 `b,thread_id,timeline_event_id, PK(thread_id,timeline_event_id)`。同一正式事件可属于多轨 | `(b,sort_order,id)`、`(b,timeline_event_id)`。删支线仅清理分组。默认「人物/地点」轨仍从事件生成 |
| `world_links`，世界版图语义边 | `id,b,from_character_id?,from_world_id?,to_world_id,kind text(MEMBER_OF/CONTROLS/LOCATED_IN/ROUTE/ALLIED_WITH),from_section_id?,to_section_id?,known_by text,version,deleted_at`。起点二选一，按关系类型验证端点类别，区间左闭右开。对称边规范化两端 ID | 分来源种类建活跃唯一键 `UNIQUE NULLS NOT DISTINCT(b,起点,to_world_id,kind,from_section_id)`。`(b,to_world_id,kind)`。无起始证据的规划边只在作者视角可见 |
| `lore_fact_revisions`，作者事实的时点版本 | `id,b,character_id?,relation_id?,timeline_event_id?,foreshadowing_id?,world_entry_id?,world_link_id?, effective_section_id?, effective_order bigint, source_hash char(64)?, operation text(SET/END), safe_fields jsonb, known_by text, recorded_at, revision_no bigint`。六类主体恰选一，safe_fields 只收该类型确定可公开的字段与证据，伏笔底牌另由原服务管 | 每类 `UNIQUE(b,主体FK,effective_order,revision_no)`、`(b,主体FK,effective_order DESC,revision_no DESC)`。严格查询取截止 order 的最新有效修订，END 结束区间。同节校正留新版。修订只追加，正文重排更新派生 order 时依赖 section ID |

**原表增量。** `character_relations` 可空新增 `source_character_id / target_character_id` 同书 FK，保留既有文本与唯一键供抽取器和旧客户端使用。只自动回填同书精确且唯一的规范名。歧义留空并给作者绑定操作。新建人工边在一次事务中写 ID、名字和修订。人工修改有效期、结束关系、重接端点经共享设定服务写修订。现有设定面板走同一服务。引入修订的作者设定行另加 `retired_at` 作逻辑删除，更新列表、取上下文、自然键 upsert、恢复、引用校验。同名重建先恢复原 ID。原按节 AI 派生数据允许重新抽取覆盖，引用证据失配时隐藏或重建。首次迁移只在作者明确的有效时点或可信来源小节建立版本。不把今天的状态回填到开书。旧接口、根本缺乏历史的书与新书均有明确读法。

V14 同步增加 `character_relations(book_id,narrative_order,since_chapter_no)`、`timeline_events(book_id,chapter_no,narrative_order)` 和 `foreshadowings(book_id,planted_chapter_no,resolved_chapter_no)` 查询索引，以真实分布的执行计划决定是否另加到期窗口索引。删除来源小节时，可空历史定位列的复合 FK 使用仅清相应外键列的 SET NULL，保留书 ID、旧时点和证据哈希。来源已失效的证据从严格视角剔除。含绑定小节的卡及被引用的父资源在现有删除服务中先解绑或归档，整书硬删按 FK 级联。封存的采纳记录保留目标 ID 快照，若作者后来删除正式章，可空活动指针置空、详情显示「原稿已删除」。

**重排事务。** 仅开放同章重排。客户端传全量有序 `sectionIds`、各节版本、章版本和 `structureEpoch`。可包含经作者确认要建节的草卡。服务按书、章的固定顺序加锁，验证无重复、无外章、无遗漏、容量 ≤999。保留 `is_default` 的内容绑定和旧节 ID。从 SINGLE 扩展时绑定现有默认节或用旧章内容初始化首节，避免重复复制正文。将受影响节暂移到各自唯一的负号 `section_no / narrative_order`，再按映射一次生成正序 `chapterNo*1000 + sectionNo`，每个正式版本只加一次。两个阶段处于同一个 PG 事务，满足 V9 两个立即唯一约束。最简单的例子是 `[s1,s2,s3] → [s1,s3,s2]`，两节内容及版本历史仍跟原 UUID。

同事务更新绑定卡的 section_id、plan_order 和目录里的节版本。重算章的 structure_mode、总字数和必要的单节镜像，更新有 section ID 的关系/事件、角色快照、伏笔埋设序号、节摘要及新修订的派生位置。`hint_history_json` 旧元素可能只含章号/节号，先用旧顺序映射到 section ID，再更新序号。无法定位的保留为章级证据。按受影响角色重新聚合 `last_seen_order`，标记滚动摘要、知识索引、节奏缓存过期。快照与进行中的 Ripple/分支作业保留起始版本，写回前比对 epoch。事务 outbox 投递重建 PG/Milvus 摘要与检索索引。等待期间严格检索以 PG 的当前 section ID、哈希、顺序复核候选，无法验证的旧向量片段不进入提示词。并发改稿返回 409，全部回滚。 [小节排序与整章预览](../../services/java/foliarc-common/src/main/java/studio/foliarc/biz/novel/service/StructureService.java#L351) · [伏笔提示的历史格式](../../services/java/foliarc-common/src/main/java/studio/foliarc/biz/lore/service/LoreService.java#L680)

### 3.4 7C · V15__story_branches_and_simulations.sql

分叉组表示一次转折，分支表示对它的不同选择，推演批次表示一次计费执行。主干节拍持久化在 V14。候选未来卡从版本化报告投影，作者可以反复推演同一分支，历史结果分别保留。

| 新表 | 字段、外键与约束 | 检索与并发 |
| --- | --- | --- |
| `story_branch_forks` | `id,b,anchor_node_id?,parent_branch_id?,parent_step smallint?,base_section_id?,base_order bigint,title,version,deleted_at`。正式节拍与父候选二选一，父候选须有 1–3 的 step。已绑定卡以所绑小节为基准。无绑定草卡提示从目录选择基准，空书可取 order 0 | `(b,anchor_node_id)`、`(b,parent_branch_id)`。从候选再分叉时另存 `parent_item_id` 指向确切的成功模拟结果，防止上层重跑后漂移。涉及分叉组、分支及模拟子项的循环 FK 在这些表全部创建后用 ALTER 添加。按父链验证同书、无环 |
| `story_branches` | `id,b,fork_id,title varchar(160),premise text,status text(CANDIDATE/ADOPTED/ARCHIVED),adopted_at?,archived_at?,version,deleted_at` | `(b,fork_id,status,id)`，每组 `UNIQUE(fork_id) WHERE status='ADOPTED'`。允许 CANDIDATE ↔ ARCHIVED。ADOPTED 的状态由采纳事务维护。归档可恢复为候选，采纳及报告历史保留 |
| `branch_simulations` | `id,b,owner_id,fork_id,run_id UNIQUE FK agent_runs, quote_id uuid UNIQUE, idempotency_key varchar(100),request_hash char(64),base_hash char(64),structure_epoch,lore_epoch,horizon smallint CHECK IN(2,3),model_alias,cap_micros bigint,state text(QUEUED/RUNNING/COMPLETED/PARTIAL/FAILED/CANCELLED),context_snapshot jsonb,comparison jsonb?,version`。snapshot 固化已裁剪事实、证据锚点、输入哈希、协议/提示词版本、权益快照 | `UNIQUE(owner_id,idempotency_key)`、`(b,fork_id,created_at DESC,id)`。相同 key 不同输入报 409。重复读返回原 run。保留该次报告、错误和资金凭证 |
| `branch_simulation_items` | `id,b,fork_id,simulation_id,branch_id,branch_version,premise_snapshot text,state,report_json jsonb?,report_hash?,input_tokens,cached_tokens,output_tokens,provider_request_id?,lease_until?,attempt int,error_code?,version`。两条复合 FK 保证 simulation 与 branch 属同一书同一 fork | `UNIQUE(simulation_id,branch_id)`、`(simulation_id,state)`、`(state,lease_until)`。结果与用量在同一确认步骤落库，已完成子项可独立查看 |
| `branch_promotions` | `id,b,fork_id,branch_id,item_id,owner_id,idempotency_key,request_hash,accepted_outline jsonb,base_hash,state text(APPLIED/WRITING/NEEDS_REVIEW/DONE/BLOCKED),version`。item 须属于该 branch 且成功 | `UNIQUE(fork_id)`、`UNIQUE(owner_id,idempotency_key)`。同一转折只选中一次。作者后续编辑通过正文章节及版本管理 |
| `branch_promotion_targets` | `id,b,promotion_id,step_no smallint,chapter_id?,section_id?,target_ref_snapshot jsonb,base_version,base_hash,writer_run_id? UNIQUE FK agent_runs,mode text(NEW_DRAFT/REVIEW_EXISTING),state text(PENDING/WRITING/REVIEW/WRITTEN/FAILED),written_version?,version` | `UNIQUE(promotion_id,step_no)`、`(b,chapter_id)`。按目标幂等启动/采纳，精确展示「第 2 章待审」。写入结果、正式 run 与分支来源可互查 |

对 `agent_runs` 补本书复合候选键以约束关联，新增模拟 action 扩展校验见第 5 节。V15 用 JSONB 增量合并 What-If 权益默认值，保留运营已有键值。冻结/结算仍进 V6 账本。

### 3.5 7D · V16__visual_assets_and_pacing.sql

| 新表 | 字段与外键 | 索引、生命周期 |
| --- | --- | --- |
| `visual_assets` | `id,b,owner_id,title,alt_text,source text(UPLOAD/GENERATED),bucket,object_key,thumb_key?,sha256 char(64)?,mime_type,byte_size bigint,width int?,height int?,visibility_section_id?,status text(RESERVED/PROCESSING/READY/FAILED),reservation_expires_at?,version,deleted_at`。对象键由服务端生成，原图与缩略图分别登记 | `UNIQUE(bucket,object_key)`。活跃且 READY 时 `UNIQUE(b,sha256)`。`(owner_id,status,deleted_at)`、`(b,created_at DESC,id)`。存储配额计入有效预留。图片实体及绑定软删后才排队删对象 |
| `visual_asset_links` | `id,b,asset_id,character_id?,world_entry_id?,foreshadowing_id?,board_node_id?,chapter_id?,section_id?`。实体列恰一非空。章节与小节作为两种独立目标。复合 FK 校验同书 | 每类 `UNIQUE(asset_id,目标非空FK)`，`(b,目标FK)` 支持反查。解绑只删关联记录 |
| `visual_asset_pins` | `id,b,u,asset_id,chapter_id?,section_id?,sort_order int,version`。两目标皆空为全书。章或节至多一个。钉选与图片关联各自持久化 | `UNIQUE NULLS NOT DISTINCT(u,b,asset_id,chapter_id,section_id)`。`(u,b,chapter_id,section_id,sort_order)`。按作用域回写，解除钉选不删图 |
| `chapter_pacing_metrics` | `b,chapter_id,extractor_version varchar(64),wanted_hash char(64),computed_hash char(64)?,state text(DIRTY/RUNNING/READY/FAILED),word_count int?,dialogue_chars int?,dialogue_ratio numeric(5,4)?,conflict_score smallint?,score_source text?,confidence numeric(4,3)?,cast_counts jsonb,coverage jsonb,evidence jsonb,lease_until?,computed_at?,version` | `PK(chapter_id,extractor_version)`，`(b,extractor_version,chapter_id)`，部分索引 `(state,lease_until) WHERE state IN ('DIRTY','RUNNING')`。约束 `ratio/confidence∈[0,1]`、`score∈[1,10]`。级联随章删除，可重算。JSON 中角色 ID 是一次测量的证据与计数，在线权限查询和业务关系仍走实体表 |
| `pacing_score_jobs`，作者付费补评 | `id,b,owner_id,chapter_id,extractor_version varchar(64),input_hash char(64),model_alias varchar(64),attempt_no int,cap_micros bigint,frozen_micros bigint,settled_micros bigint,input_tokens int,cached_tokens int,output_tokens int,state text(QUEUED/RUNNING/DONE/FAILED/CANCELLED),idempotency_key varchar(100),version`。存实际模型调用和收费凭证索引 | `UNIQUE(owner_id,idempotency_key)`、`UNIQUE(chapter_id,extractor_version,input_hash,attempt_no)`，同一输入最多一个活跃任务的部分唯一索引。读回成功结果免重复补评。有已收费失败记录时，重试须新报价且增加 attempt_no |

**可执行的迁移片段。** 下例假定 V14 的 `chapters(book_id,id)` 唯一键已存在。计算任务领取时持有行版本。回写条件同时包含输入哈希和领取时版本，从而挡住「旧任务后完成」和「同一输入的租约已转交」两种竞争。

```sql
CREATE TABLE chapter_pacing_metrics (
    book_id           uuid NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    chapter_id        uuid NOT NULL,
    extractor_version varchar(64) NOT NULL,
    wanted_hash       char(64) NOT NULL,
    computed_hash     char(64),
    state             text NOT NULL DEFAULT 'DIRTY'
                      CHECK (state IN ('DIRTY','RUNNING','READY','FAILED')),
    word_count        integer CHECK (word_count >= 0),
    dialogue_chars    integer CHECK (dialogue_chars >= 0),
    dialogue_ratio    numeric(5,4) CHECK (dialogue_ratio BETWEEN 0 AND 1),
    conflict_score    smallint CHECK (conflict_score BETWEEN 1 AND 10),
    score_source      text CHECK (score_source IN ('RULE','MODEL')),
    confidence        numeric(4,3) CHECK (confidence BETWEEN 0 AND 1),
    cast_counts       jsonb NOT NULL DEFAULT '{}'::jsonb,
    coverage          jsonb NOT NULL DEFAULT '{}'::jsonb,
    evidence          jsonb NOT NULL DEFAULT '[]'::jsonb,
    lease_until       timestamptz,
    computed_at       timestamptz,
    version           bigint NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (chapter_id, extractor_version),
    FOREIGN KEY (book_id, chapter_id) REFERENCES chapters(book_id, id) ON DELETE CASCADE,
    CHECK (dialogue_chars IS NULL OR dialogue_chars <= word_count),
    CHECK (state <> 'READY' OR (computed_hash IS NOT DISTINCT FROM wanted_hash))
);
CREATE INDEX idx_pacing_book_series
    ON chapter_pacing_metrics (book_id, extractor_version, chapter_id);
CREATE INDEX idx_pacing_work
    ON chapter_pacing_metrics (state, lease_until) WHERE state IN ('DIRTY','RUNNING');
```

worker 完成时以 `WHERE book_id=:b AND chapter_id=:c AND extractor_version=:v AND wanted_hash=:inputHash AND version=:claimedVersion AND state='RUNNING'` 更新结果及版本。受影响行数为零便放弃该次写回。`READY` 的所有指标来自同一份输入。LLM 待评可以是 `conflict_score=NULL`。以下结构性索引均使用 PostgreSQL 16 支持的部分唯一索引或 `NULLS NOT DISTINCT`。 [PostgreSQL 16 唯一约束与外键](https://www.postgresql.org/docs/16/ddl-constraints.html)

增量合并图库权益到 `vip_plans.entitlements_json`，部署顺序为先兼容缺键的读取端、再补默认键、再放开写。低频归档低清图片、索引失效、老书回填都可暂停重启。每次迁移分别在空 PG16、V13 数据副本和上阶段数据副本验迁移与旧客户端。上线以功能开关回退入口，已生成的稿件可在原工作台继续打开。

## 4. 作者 REST 与服务边界

以下 `B` 展开为 `/api/v1/books/{bookId}`，`V` 展开为 `B/visual`。已有前端 `request` 负责 Bearer、刷新会话和错误映射。Java 从 `@AuthenticationPrincipal` 取用户，先 `BookService.requireOwnedBook`，再逐个校验 chapter、section、focus、branch、asset、receipt、run 的书籍/用户归属。列表和聚合在 SQL 中限定 book/owner，批量请求任一 ID 错即整体拒绝。内部 Python 只接受 `X-Internal-Token`，回调 run/simulation 由 Java 查找其 owner。按书读取内部数据同时传受信的 `X-Owner-Id`。浏览器只访问 Java。

| 阶段与接口（原有标「现有」） | 请求 → 响应摘要 |
| --- | --- |
| A · `GET V/projection` | `view=graph/lanes/atlas,visibility=AS_OF/PLAN,asOfChapterId?,asOfSectionId?,volumeId?,fromChapter?,toChapter?,povId?,focusId?,cursor?,limit?` → `cutoff,outlineIndex,nodes,edges,lanes,arcs,hasMore,nextCursor,coverage,etag`，仅返回所选视图字段。固定按 order、id 分页。跨窗口弧线按交叠查询，回传 `continuesLeft/Right`。严格模式右端未知返回 `open`，不返回未来节点 ID |
| A · `GET V/entities/{kind}/{id}` | 同一时点与 POV，另带 `page/cursor` → `visibleFields,evidence[],heatByChapter[],coverage,editableFields,revision`。热力按 distinct 章/节聚合，可跳正文。给导演槽复用 `GET V/director-options`，同一筛选输出可用角色、伏笔和动作 |
| A · 现有 `POST B/memory/relations`、`DELETE B/memory/relations/{id}`、`POST B/memory/timeline`、`DELETE B/memory/timeline/{id}` | 现有 upsert 以同书端点名字及类型定位作者边，已有 sinceChapterNo 只在空值时补上。修正起始章走下述 PATCH。作者关系示例 `{sourceCharacter,targetCharacter,relationType,sinceChapterNo,sourceSectionId,knownBy}` → 原事实 ID、来源、序号及刷新键。图谱补验证和 TS 类型。A 的同键改描述/删除加入可选前置条件 `expectedUpdatedAt`，在书锁内比较。变更自然键新增 `PATCH V/relations/{id}` 校验旧来源、目标及冲突，原子替换键或拒绝，A 返回新的映射。禁止把 AI 派生边当作作者边直接改。确认后建 AUTHOR 事实 |
| A · 现有 `B/characters`、`B/worldbook`、`B/foreshadowings`、`GET B/foreshadowings/due`、`POST B/foreshadowings/{id}/transition` | 规划页沿原 CRUD。早期时点详情只取 projection/entity。基础编辑复用旧表单，规范名冲突交还作者处理。7B `PATCH B/worldbook/{id}` 支持 `expectedUpdatedAt` 前置条件更新设定 |
| A/B · 现有 `GET B/tree`、`GET B/chapters/{c}/full-preview`、`POST B/chapters/{c}/sections`、`GET/PUT/DELETE B/chapters/{c}/sections/{s}` | 全书规划/正文读取原树。正文跳转使用章或节 ID。小节 PUT 沿用 `{title,content,version,createSnapshot,changeSummary}`。全章预览用于用户主动打开的单章 |
| B · `GET/PUT V/layouts/{kind}`、`PATCH V/layouts/{id}/positions` | kind + scope 取/建本人布局或保存 viewport。批量 `moves[{targetKind,targetId,x,y,pinned,expectedVersion}]` → 各节点新版本。任一冲突时整批返回 409 与冲突节点，客户端可只重放其余移动。首创时由服务器授权解析实体。布局读取也只回可见 ID |
| B · `GET/POST V/beats`、`PATCH/DELETE V/beats/{id}`、`POST V/beat-edges`、`DELETE V/beat-edges/{id}` | 成员芯片与 goal、opposition、turn、tension、目标章/节写入并返回稳定 beatId。创建边提交 `fromId,toId,kind`。列表按卷/章/框选范围分页。删除已绑定卡返回解除关联说明及正式节 ID |
| B · `POST V/chapters/{c}/apply-structure`、`POST V/chapters/{c}/outline-preview` | 前者含 `orderedUnits[],chapterVersion,structureEpoch,expectedSectionVersions,idempotencyKey` → `chapter,newSections,beatBindings,structureEpoch,invalidatedIds`。后者取已排序卡 ID/版本 → 大纲文本、输入哈希与证据，送 `POST /api/v1/runs`。结构确认与纯预览独立 |
| B · `GET/POST V/threads`、`PATCH/DELETE V/threads/{id}`、`PUT V/threads/{id}/events`。`GET/POST V/world-links`、`PATCH/DELETE V/world-links/{id}` | 维护支线标题/目标、关联事件 ID、世界边的端点与有效期。返回最新轨/边及投影失效键。查势力关系按视角和时点裁剪 |
| C · `POST V/forks`、`GET V/forks/{id}`、`POST V/forks/{id}/branches`、`PATCH V/branches/{id}` | 创建 fork 指定来源节拍或父报告 step，返回锚点和基线。建分支给标题/假设。PATCH 更新假设或归档/恢复。`GET V/branches?status=ARCHIVED&cursor=…` 给出平行世界列表与原位置 |
| C · `POST V/simulations/quote`、`POST V/simulations`、`GET V/simulations/{id}` | `{forkId,branchIds,branchVersions,horizon,baseHash}` → `quoteId,expiresAt,capMicros,eligibleLimits`。提交 quote、接受的上限及幂等键 → 202 `{simulationId,runId,streamUrl}`。GET → 逐分支状态、可读报告、结构化对照、已结算/估算。`POST /api/v1/runs/{runId}/cancel` 用于取消。重试失败分支另报价并回链旧批次 |
| C · `POST V/branches/{id}/promotion-preview`、`POST V/branches/{id}/promote`、`GET V/promotions/{id}`、`POST V/promotions/{id}/targets/{t}/accept` | 预览给目标章位置、基线版本/哈希、冲突及后续写作费用范围。promote 提交 `itemId,previewHash,targets[],acceptedCapMicros,idempotencyKey` → 202 `{promotionId,chapterIds,state}`。已有稿的 accept 需 `expectedVersion,acceptedHunks`。写作目标可 `POST V/promotions/{id}/targets/{t}/retry` 继续。已写入的小节可用 `POST V/promotions/{id}/targets/{t}/restore`，传当前版本及幂等键还原采纳前快照 |
| D · `GET V/pacing`、`GET V/pacing/chapters/{c}`、`POST V/pacing/chapters/{c}/score` | 读请求按卷或章区间返回点列、口径版本、覆盖率、生成时间和 stale 状态。详情取分子/分母、证据和来源。score 是作者发起的可计费补评，`mode=QUOTE` 只返回报价。`mode=CONFIRM` 带 quoteId、接受的上限和幂等键 |
| D · `GET/POST V/assets`、`PUT V/assets/{id}/content`、`GET/PATCH/DELETE V/assets/{id}`、`GET V/assets/{id}/image?size=thumb/original` | POST 提交书、名称、预期类型和字节数 → 预留 ID、期限、上传地址。PUT 上传二进制 → 202 加工状态。详情给 READY 状态、尺寸、缩略图授权地址。PATCH 可改标题、替代文本、可见起点。二进制指向私有对象，GET 不暴露桶凭据 |
| D · `PUT V/assets/{id}/links`、`GET/PUT V/pins`、`GET V/atlas` | links 替换该图同书实体关联。pins 提交作用域、有序资产 IDs 与前置版本/集合摘要。atlas 返回地点/势力、语义边和允许读取的图。分页排序均有 UUID 次序兜底 |
| D 可选 · `POST V/assets/prompt-preview`、`POST V/assets/image-jobs` | 选择可见角色/地点、画面要素、风格 → 可编辑提示词。模板即时返回。LLM 润色或生成图经报价、配额和显式提交 → job/run ID，完成后生成普通可绑定的 asset |

错误行为遵循现有 `BizException`。无会话 401，越书资源按所属查询统一返回 404，权益不足 403 `VIP_QUOTA_EXCEEDED` 携 feature、currentLimit、requested、recommendedPlan 等既有字段，并为图库扩展 used 字段，余额或月预算 402，版本、过期预览、同键异参 409，限流 429。非法自环、范围、张力、跨书芯片或排序集合返回相应 400/404。409 带当前版本及可恢复动作，客户端保留用户输入。202 必给可独立查询的任务 ID。A 的旧自然键 upsert 可安全重复建同一事实。B 的结构性创建增设 V14 表 `visual_command_receipts(owner_id FK,book_id FK,command,idempotency_key,request_hash,result_refs jsonb,created_at,PRIMARY KEY(owner_id,command,idempotency_key))`，业务写与回执同事务。C 的模型任务与采纳复用各自永久幂等记录。 [异常类](../../services/java/foliarc-common/src/main/java/studio/foliarc/biz/common/BizException.java) · [原 API 客户端](../../apps/web/src/lib/api.ts#L595)

## 5. 推演、立主干、节奏计算与图片链路

### 5.1 What-If 的执行与流式报告

```mermaid
flowchart LR
  A["画布选中转折与候选"] --> B["Java 核所有权、冻结额度、保存基线"]
  B --> O["PG outbox 派发"]
  O --> C["Python StateGraph 读取裁剪后的快照"]
  C --> D["Send 方案 A"]
  C --> E["Send 方案 B"]
  D --> F["校验逐章预测与引用"]
  E --> F
  F --> G["Java 持久化子项与比较报告"]
  G --> H["作者选定分支"]
  H --> I["PG 绑定正式草稿、版本与写作任务"]
  I --> J["现有编辑部生成正文"]
```

**输入与隔离。** 固化 `{schemaVersion,ownerId,bookId,forkId,anchor,cutoff,structureEpoch,loreEpoch,canonicalFacts[],sourceRefs[],orderedBeats[],premises[],horizon,modelAlias,tokenCaps}`。`baseHash` 纳入证据记录版本/哈希、父报告哈希和主干目标版本。只送必需的节摘要、POV 可知事实及少量授权引用。模拟默认仅使用基点以前已验证的书内证据。跨书资料只可作显式授权的创作参考。分支临时事实属于它自己的输入输出，彼此隔离。写工具、正式 proposal、知识摄入、Milvus 索引和正文保存均不接推演图。原有 Ripple 的差异归类、证据格式和局部检查可提取为纯函数。

**Python 新图。** 新增 `agent/what_if_graph.py` 及 Pydantic DTO。`load_snapshot → fan_out(Send) → simulate_branch → validate → reduce_compare → finish`。同一分支用一次有输出上限的调用规划 2 或 3 章，保留因果顺序。Java 创建批次时校验候选共享 fork 且版本与报价相同，每批候选数为 2 至 whatIfMaxBranches。显式重试允许只选一个失败项。不同分支有界并行，严格按书/用户并发配额与全局 worker 容量调度。用 branchId 作 reducer 的 key，报告按用户选定顺序展示。比较优先用结构化字段对齐。失败一支时仍交付其余支。第一版只对格式做确定性校验。需要重新调用模型时走显式预算与用量记录。LangGraph 的动态分发与 reducer 可直接使用其公开 API。 [LangGraph 图与 Send](https://docs.langchain.com/oss/python/langgraph/graph-api)

**恢复合同。** Java 新增内部任务 dispatch 指向 `POST /internal/v1/visual/simulations/start`，请求含 simulation/run/trace ID 与快照引用，202 即接受。Python 可从 `GET /internal/v1/visual/simulations/{id}/input` 取受限输入。以 `PUT /internal/v1/visual/simulations/{id}/items/{itemId}/result` 及 `POST /internal/v1/visual/simulations/{id}/finalize` 向 Java 回报，带任务租约、attempt、报告哈希及用量。服务端验证有效任务、书、分支、协议，再事务性落库。每个模型请求先登记 provider_request_id。完成子项持久化成功再 ACK。进程重启可按 PG 子项和父 checkpoint 重建图并跳过已完成项。调用状态不明时先查供应方或停在待核账，防止再次付费。保存子项的 HTTP 确认必须成功，不能沿用仅记 warning 后继续的 checkpoint 错误处理。该方案用业务状态恢复整批。已有 `agent_checkpoints.state_json` 只存步骤、ID、版本及游标，原生 LangGraph saver 的内部表格式另有定义。 [现有 checkpoint 适配器](../../services/agent-py/src/foliarc_agent/agent/multi_agent.py#L111) · [LangGraph 持久化](https://docs.langchain.com/oss/python/langgraph/persistence)

**流式进度。** 批次复用一个正式 runId，新增专用 action `SIMULATE_BRANCHES`，mode 复用 `MULTI_AGENT`，按 action 分派专用图。Java 校验枚举、Python 协议分派、Web `RunAction` 和 `SseEventType` 同步扩展，作者通用启动入口不能绕过专用报价。沿现有 `foliarc:run:{runId}:events`、Java `GET /api/v1/runs/{runId}/stream` 和 `Last-Event-ID`。事件例子如下。

```json
{"event_id":"1730000000012-0","run_id":"run-example","seq":7,"type":"branch_progress","agent":"simulator","payload":{"simulationId":"sim-example","branchId":"branch-B","stage":"validating","completed":1,"total":2}}
```

`run_started → branch_progress / branch_ready / branch_failed → comparison_ready → usage → done`。失败子项发 `branch_failed`，保留全局 `error` 给整批失败。由单个 sequencer 为并行结果分配递增 seq 并按同序写 Stream，恢复时从持久水位续号。领域批次 `PARTIAL` 映射为 run 完成且带分项状态。SSE 只发进度及结果 ID。最终报告由 Java 读 PG，Redis 游标过期则重读批次快照，再接新流。断开浏览器只断订阅，作者主动取消才停待调度项，复用合作式控制。扩展 `runner.py` 的审计事件白名单和前端 reducer，过滤模拟 run 与正文流，`agent_run_events` 用于追踪。 [流协议与重连](../../apps/web/src/lib/sse-client.ts) · [现有 Python 协议](../../services/agent-py/src/foliarc_agent/streaming/protocol.py)

**输出合同。** 每方案给 `{branchId,premise,chapters:[{offset,title,goal,conflict,turn,tension,characterEffects[],factionEffects[],clueEffects[],evidenceRefs[],assumptions[]}],continuityRisks[],unresolvedQuestions[]}`。offset 必须连续 1..horizon、tension 为 1..10、真实证据 ID 必须存在于快照白名单。新角色/地点用候选临时键。渲染写清「推测」。对照报告按同一维度列主角动机、势力变化、伏笔兑现、情绪曲线、依赖假设和对原稿的影响，每项可点证据。举例，第 12 章交钥匙这一转折，A「交给舟师」可预测第 +1 章失去通行权，B「留下钥匙」可预测第 +1 章同盟破裂。第 12 章的钥匙交接记录是证据，未来两种后果各属自己的方案。

### 5.2 一键立为主干并生成正文

1. 作者选成功报告，系统出可读预览。书尾可建后续 2–3 个 DRAFT。书中转折默认映射已有后续章做 Diff，空缺的尾章才追加。若中间缺连续章号或要顺移既有章，预览要求重新选目标。嵌套分支把父报告的分歧一起展开，避免跳过必要前提。
2. 提交预览 hash/预算，事务锁书、fork 和目标章，核对作者、`branchVersion / structureEpoch / loreEpoch / contentHash / version`。抢先采纳、源节删除、报告更新或正文刚保存都返回 409 与重做预览入口。一个事务确定唯一 ADOPTED、保存已批大纲、创建/绑定 DRAFT 目标、落 promotion/targets 和 outbox。有正文的目标先记录变更前快照。未选择方案可立即归档，报告仍可读。
3. 按目标顺序调度既有 `MULTI_AGENT / CONTINUE`，每个目标领取固定 writer_run_id。对已批大纲增加受控的 `approvedOutlineRef + hash` 进入现有审批恢复逻辑。服务端验证引用确在本次采纳中，避免二次索要同一批准。新章仍空且版本匹配时经 ChapterService／StructureService 保存生成草稿并留版本。已有稿、已被作者编辑或版本变化时进入 `NEEDS_REVIEW`，用 `AiDiffReviewModal` 逐段确认再带版本保存。后一章以实际已落稿的上一章为前文。
4. 状态区分「方案已采用」「正在生成第几章」「正文已写入/待审」。超额或模型失败使单一目标 BLOCKED/FAILED，可按同一目标续作。已生成章节和报告均可打开。章节回滚沿 `chaptersApi.rollback`。现有 `section_versions` 有快照，Web 的 `structureApi` 缺少节级回滚入口。7C 对已落稿的小节提供「还原采纳前版本」，用保存的目标快照和节 PUT 的当前版本生成一次有记录的修订。恢复另一条分支默认建立新的转折选择。正式稿保存之后沿已有知识同步/摘要链路更新长记忆，新建节奏失效事件与该次保存同事务。

### 5.3 节奏体检的可解释口径

| 指标 | 算法、输出和作者如何判断 |
| --- | --- |
| 章节字数 | 采用 Java `WordCounter.count` 的非空白 Unicode code point 口径。多节按 `section_no` 合并各节统计，忽略人工拼接分隔符。无节旧章回退章正文。Python 按相同空白集合处理，测试包含 emoji、全角空格和不换行空格。空章字数 0，其余比例显示「无样本」 |
| 对话占比 | Python 规则识别配对的中文双引号、直角引号和英文双引号，跨行延续，内嵌引用只计一次，未闭合片段标记低覆盖。分子为配对对话内部的非空白码点，排除包围引号。分母沿用章字数。保存原始分子、分母、规则版本。例 `他说：“走。”` 共 7 个码点，对话内部 2 个，显示 28.6%。卷级用分子分母分别求和 |
| 角色戏份 | 主指标为含某角色的有效小节数 / 有有效识别证据的小节数，每节每角色最多 1 次，多角色同场各记一次，总和可超过 100%。另列「仅提名」和 POV 计数。别名优先完整最长匹配、歧义不归人。保留证据节、哈希、匹配来源和已覆盖/总节数。7A 的热格只表示「记录出场」，D 的统计详情可展开到证据 |
| 冲突张力 | 画布展示作者设定的 1–10「计划张力」。体检单独显示「正文张力」。规则检测目标/阻力/逆转线索，词频只作提示与抽样依据。启用补评时给轻量模型有段落位置的开场、转折、结尾片段（例如总计 ≤1,800 个码点），输出 1–10、confidence、理由及章内证据位置。锚定量表 1–3 为铺垫，4–6 为阻力已出现，7–8 为明确对抗/升级，9–10 为重大且难逆的转折。无证据返回 null。模型不可用时保留规则信号和作者值，两条曲线分别标来源 |
| 雷达与轨迹 | 默认按卷看章字数、对话密度、正文冲突与角色集中度（出现份额最高者/所有已识别出现次数）。tooltip 给原始值、口径与覆盖率。雷达可选择同卷已完成章的百分位以统一尺度，样本不足 5 章退回原始值列表。不生成无依据的「健康总分」。折线既可对照计划/正文，也支持点峰谷打开证据节 |

**增量。** 每次保存/回滚、节新增/删除/重排、采纳写稿，以及节摘要或人物出场证据重新抽取完成时触发 `PACING_INVALIDATED`。Java 用 `sha256(有序 sectionId+contentHash 序列，相关出场证据/别名版本，extractorVersion)` 得到 wanted_hash。旧 SINGLE 以章内容 hash 兜底。保存事务 upsert DIRTY 并写 outbox，只携 ID/哈希到 RabbitMQ。worker 合并连续自动保存产生的任务，取最新已提交文本。批量消费直接调用提取函数，单章显式补评通过新增 `POST /internal/v1/visual/pacing/analyze` 调用同一函数。回调 `PUT /internal/v1/visual/pacing/chapters/{id}/result` 时必须命中同书、当前 wanted_hash 与算法版本。旧任务完成不覆盖新版。没有缓存时先用树的字数展示，其余标「待计算」，打开书页不轮询 200 篇正文。回填按卷游标每批如 10 章。改一章仅计算这一章，角色别名调整仅使其证据关联章/命中新增别名的候选章失效。失败重试沿 outbox、租约、完成回执。超过预算的后台任务只做确定性统计。模型补评经独立 opt-in 与账本，后台浏览不会触发模型消费。 [字数实现](../../services/java/foliarc-common/src/main/java/studio/foliarc/biz/novel/service/WordCounter.java) · [已有异步 worker](../../services/agent-py/src/foliarc_agent/rag/worker.py)

### 5.4 图像与世界版图

世界版图先交付可编辑的势力/地标关系图，在 ATLAS 布局里保存位置。`MEMBER_OF / CONTROLS / LOCATED_IN / ROUTE` 与点开的设定卡都能追溯同一世界条目。角色移入势力外框仅弹出「设为成员」预览。确认才建立有出处的归属关系。地图支持反向从地点查事件、出场人物与概念图。

图片首版支持 JPEG/PNG/WebP。Java 先在用户配额锁下创建 RESERVED，示例单图 ≤8 MiB、解码像素 ≤24 MP。PUT 按真实字节流校验类型、大小与图像头，生成 320/960px 缩略图、去元数据。最终文件大小和总图库配额在 READY 前复核。实际对象键形如 `visual/{ownerId}/{bookId}/{assetId}/original`，缩略图使用同前缀，PG 保留 key 和 SHA-256。在私有桶上传完成且缩略图就绪后才对用户可见。超时预留、失败半成品、重复 hash、硬删书籍都由幂等清理回收。开发环境 MinIO 不可用时显示「图像暂不可用」，版图和正文仍可打开。

建议先用已有 Bearer 客户端向 Java 换取短期签名 GET（建议 5 分钟），图片组件按尺寸懒加载。若选同源代理，使用带 Authorization 的 fetch 取缩略图 Blob 并在卸载时 revokeObjectURL。普通 img 的 src 无法附加现有 Bearer header。当前 `download` 会整体读入内存，首版应扩展流式读取/签名方法。钉选作用域用第 3 节 pin 表持久化，历史视角只返回作者标注为此时可见的图。单张概念图默认仅供创作参考。上传图片走视觉资产流程，知识文档摄入继续使用原流程。可选 `POST /internal/v1/visual/image-prompt` 从允许的角色特征、光线和构图生成可编辑提示词。真实生图另接图像供应方、独立单价和取消结算，产物沿同一资产流程入库。

## 6. VIP 权益、并发与计费

手动建图、排卡、读规则指标、看已付报告、归档和钉图不扣 credits。图库受空间/数量限制。作者明确启动 What-If、正文写作、可选的模型补评/润色或生图时才产生模型成本。报价界面并列显示「本次最多」「已用」「退回」，浏览和模拟结果可在降级后继续打开。

**建议初始运营值，待第 9 节确认。** 图库配额按作者跨书合计，避免通过新建书绕开。以下新字段的 0 表示该项禁用。无限制须有显式语义，保留既有旧权益各自的解析规则。

| `VipEntitlements` 新字段 | FREE | VIP | SVIP | STUDIO |
| --- | ---: | ---: | ---: | ---: |
| `whatIfMaxBranches` 每次最多候选 | 2 | 3 | 4 | 6 |
| `whatIfMaxHorizon` 每条最多未来章数 | 2 | 3 | 3 | 3 |
| `whatIfMaxParallel` 每用户同时占用推演模型槽 | 1 | 2 | 3 | 4 |
| `whatIfDailyRuns` 每日接受的推演批次 | 1 | 5 | 15 | 30 |
| `userVisualMaxAssets` 已用及预留张数 | 12 | 80 | 300 | 1000 |
| `userVisualMaxBytes` 图库上限（这里以 MiB 展示，存 bytes） | 32 | 256 | 1024 | 4096 |

`userVisualMaxBytes` Java 必须是 `long`。配额与素材/分支控制写在后端。TS 类型、`VipDtos.VipEntitlements`、`VipEntitlementService.defaultEntitlements/parseEntitlements/quotaExceeded`、作者 `GET /api/v1/vip/me`及后台套餐编辑器一起升级。JSONB 先保留计划定制值，按 planCode 为缺键补默认。区分缺键与显式 0，避免 Java primitive 反序列化后误封存量作者。更新计划后使 plans version 缓存失效。`/api/v1/admin/vip/plans/{planCode}` 用原有后台鉴权修改。 [权益 DTO](../../services/java/foliarc-common/src/main/java/studio/foliarc/biz/vip/dto/VipDtos.java#L20) · [作者权益客户端](../../apps/web/src/lib/api.ts#L838) · [后台权益端点](../../services/java/admin-java/src/main/java/studio/foliarc/admin/controller/AdminBillingAndVipController.java#L53)

**账本执行顺序**

1. Java 对固定输入报价。quote 使用签名票据绑定 quoteId、owner、book、输入哈希、价格版本、最高金额和过期时间。首次接受后以批次表唯一 quote_id 防止更换幂等键重复消费。用 `ModelPricing` 的模型费率分别估算每条候选的最大输入和受限输出，假设缓存零命中，以整数 micros 累加。1 credit = 1,000,000 micros。例按现有 `fast-router` 费率，两条候选各最多 2,000 input / 600 output，无缓存、无工具，示例上限为 `2 × (2,000 + 1,800) = 7,600 micros = 0.0076 credits`，实际报价跟真实提示上下文和输出预算变化。首发每批固定同一价目 alias，比较汇总为规则操作。 [当前费率与取整](../../services/java/foliarc-common/src/main/java/studio/foliarc/biz/billing/ModelPricing.java#L11)
2. 接受报价时，从幂等记录重放相同请求，校验报价未过期、输入版本一致。按用户串行核验日额和在途槽，复用原有 RPM/TPM。批次预留受每个分支将要产生的调用数及 token 约束。为 V15 补 `quota_day date, quota_refunded_at timestamptz null` 和 `(owner_id,quota_day,state)` 索引，建议业务日用 Asia/Shanghai。已接受批次计一次，任务从未派发且零消耗的系统失败返还日额。用唯一批次 ID 记一次返还。同一用户跨书共用额度。每书公平排队，全局并发另有上限。免费档两个方案串行，高档可并行。
3. 一个模拟批次对应一个计费 run。新增 `BillingService.reserveQuotedRun` 复用现有预冻结、月预算和账本逻辑，保留 `run_id`，用服务端 `FREEZE:{runId}`。普通 `freezeForRun` 按 mode 固定估值，`freezeCustom` 的 ledger.run_id 为空，需在适配层支持可报价的关联 run。持用户行锁/账户锁完成 run、simulation、items、冻结、outbox 同一事务。提交后派发。父批次唯一计费，子项只登记实际用量。先保留每一调用的最坏预算再发起，余额/上限不足就停排，不发生无提示超出报价的追扣。
4. 成功按真实 input/cached/output 汇总、套用现有折扣规则，`SETTLE:{runId}` 最多一次。部分失败、取消按已经确认发生的 token 结。未调用或零用量全额 RELEASE，剩余预扣解冻。对账以该批独有冻结金额、账本 request_id 和终态锁为准。并行结果到达顺序、重放 SSE、双击取消均不能重复结算。需给专用 run 路由终态处理，通用 run 的 cancel/state 回调不能在子项用量回收前抢先结清。缺少供应方用量的项进入限时对账，前台显示结算中。到期仍无证据按本期策略免收未知部分、留对账记录。
5. 主干写作产生独立 writer run，报价列出后续各章上限，作者一次接受合计上限。将上限分摊至 target，逐章按剩余预算冻结和结算。中途余额不足留在「待继续生成」。同一报告重看、归档或恢复不再收模拟费用。补评复用同一账本的 custom 方法。冻结和结算分别使用 `PACING_FREEZE:{jobId}`、`PACING_SETTLE:{jobId}`。同一章、输入哈希与提取版本归并至一个评分 job，服务端生成长度受限的 jobId。真实生图需要按张/分辨率定价的固定金额结算适配，再开放供应方开关。token 费率只用于文本调用。 [预冻结与结算](../../services/java/foliarc-common/src/main/java/studio/foliarc/biz/billing/service/BillingService.java#L220) · [现有账本唯一键](../../services/java/foliarc-common/src/main/resources/db/migration/V6__create_billing_ledger_and_guardrails.sql)

图库预留按 owner 行锁串行统计未过期 RESERVED、PROCESSING、READY。预留数量和**原图＋系统缩略图字节**。首版按申报原图上限加缩略图编码预算预留，READY 按实占修正。保证两台设备并发传最后一张时只有一次成功。补缩略、重复上传不重复占用。软删释放作者额度、回收失败另记对象存储积压。降级后超额可删、解绑、导出/查看，新增等待腾出容量。模型月度预算、并发位、图片容量分别计量。

## 7. 前端实现、包体与长篇性能预算

**选择。** 关系图和节拍板用按需加载的 `@xyflow/react` v12，共用拖拽、缩放、选中、连线和键盘操作。泳道、伏笔弧线、热格和情绪折线用轻量 SVG，在可视横纵范围生成图元。节奏表及实体详情用普通语义 HTML。首版不需要引入整套图表库。

| 组件／引擎 | 适用范围与代价 | 首发决定 |
| --- | --- | --- |
| React Flow v12 | 自定义节拍节点/handle、方向关系、选中态与视口都现成，节点是 DOM，成本随可见节点内容增加。12.10.0 的 React 和 React DOM peer 范围均为 ≥17。用本机 pnpm 缓存校验 SHA-512 后取其发布件，UMD JS 185,417 B，gzip -9 等效 58,585 B（57.2 KiB），style.css 18,574 / 3,015 B，base.css 13,563 / 2,412 B。UMD 外置 React/React DOM。这些是单文件数据 | 以 12.10.0 做可复现实验基线，实施时锁经验证的 12.x 补丁。本机项目 React/DOM 19.3.0 单节点 `renderToString` 渲染出标签和节点 class。真实 Next 产物、hydration、拖拽及帧率进入发布验收。 [v12 包声明](https://raw.githubusercontent.com/xyflow/xyflow/%40xyflow%2Freact%4012.10.0/packages/react/package.json) · [v12 迁移与 SSR](https://reactflow.dev/learn/troubleshooting/migrate-to-v12) |
| 稳定分组／轻量力导向 | 大量人物关系有环。以势力分区、书/实体 ID 固定种子布点，少量碰撞松弛，保留已手摆节点。有限迭代后停止。Worker 只收 ID、边和尺寸 | 7A 默认。全局无边角色排网格。只有显式「重新整理」或拓扑变化才启动布局，缩放过程中仅做 transform |
| `@dagrejs/dagre` | 有方向的节拍/分叉图可按层排，简单、对稳定次序友好。多势力跨组连边密集时需分组预处理。1.1.5 发布的 dagre.min.js 为 96.1 kB 原始文件 | 7B/C 自动整理节拍时再加载到 worker，手动排卡可直接开始。 [Dagre 项目](https://github.com/dagrejs/dagre) · [1.1.5 发布件](https://app.unpkg.com/@dagrejs/dagre@1.1.5/files/dist) |
| `elkjs` | 可处理嵌套分组、端口与正交路由。0.10.0 的 min worker 发布件约 1.57 MB 原始，另有 API 入口，worker 冷启动和配置成本更高 | 仅在世界版图确有复杂端口避让需求时单独分包接入。 [ELK worker 接口](https://github.com/kieler/elkjs) · [0.10.0 发布件](https://app.unpkg.com/elkjs@0.10.0/files/lib) |
| 自定义 SVG | 泳道横轴固定，屏幕坐标可直接由章序、轨序算出。区间着色把相交伏笔分层，跨度跨出窗口画延续标。高密度同层折叠为「另 N 条」，展开才生成明细。雷达、折线、图例由同一数据驱动 | 7A 上线轨道，7B 加计划张力，7D 加实测/评分曲线。标签跟随语义令牌，另有可键盘访问的事件列表。 [布局引擎对照](https://reactflow.dev/learn/layouting/layouting) |

**Next 集成。** 从现有 Client 工作台壳在模块顶层声明 `dynamic(() => import(...), {ssr:false})`，仅选中时挂载图形视图。定高容器、ResizeObserver 与加载态预留空间，避免初始尺寸为零。单独加载 React Flow base.css 和局部覆盖，避免引入的选择器改变正文。`paper/sepia → colorMode="light"`，`ink → "dark"`。将 `--xy-background-color / --xy-edge-stroke / --xy-edge-stroke-selected / --xy-node-color` 等 CSS 变量映射至本地语义色，节点卡用现成 UI 原语。布局 Worker 的 URL 在 Next 生产模式验证。默认正文进入时图形 JS/Worker 的网络增量应为 **0 B**。切图首批 JS+CSS 新增预算 ≤110 KiB gzip（不含宿主共享 React），手动布局 SVG 视图 ≤35 KiB。Dagre 可选 worker 另预算 ≤45 KiB gzip。以生产 build 客户端 chunk 图、冷缓存 Network 去重归因分别测 gzip 和实际传输编码。 [Next 懒加载](https://nextjs.org/docs/app/guides/lazy-loading) · [项目中安装的 Next 指南](../../apps/web/node_modules/next/dist/docs/01-app/02-guides/lazy-loading.md)

### 7.1 200 章压力样书的执行预算

基准固定为 200 章、150 人物、80 条伏笔、300 张卡，额外生成 4 卷、450 条人物边、单枢纽 60 邻居和跨卷长弧，保留空章节、无日期旧数据与多节章。测试账户权益应容纳 80 条伏笔。目标是 60Hz 下交互接近 60fps，以下门槛均待生产构建实测。

| 路径 | 限制工作量的做法 | 建议门槛 |
| --- | --- | --- |
| 初次打开图谱 | 服务端先按 as-of、卷、POV、角色聚焦。超过 60 个可见人物折叠为势力/同邻组，单屏边目标 ≤140。可搜索 150 人并一键居中。使用稳定节点 ID，`React.memo`、稳定 `nodeTypes/edgeTypes`、最小选择订阅 | 默认投影响应 ≤150 KiB 未压缩，开发基准库热查询 p95 ≤200ms。可见 DOM 节点卡 ≤80 |
| 画布平移／拖拽 | 300 张卡按卷/章和视口空间索引裁剪，四周超扫一屏以内，缩小时先降为题签/点，放大再渲染芯片。React Flow 的 `onlyRenderVisibleElements` 有裁剪开销，用两组负载 A/B 选择。拖动中的坐标留本地，rAF 合并指针事件。详情与目录只订阅选中 ID | 平移、缩放、拖拽各 10 秒，60Hz 中档笔电的帧间隔 p95 ≤18ms，掉帧 ≤2%，交互主线程无 >50ms 长任务。浏览 300 卡仍可从列表直达未挂载节点 |
| 泳道／滑块 | 章列窗口 20–32 章，两端预取 4 章。只画可见轨和相交弧，lane 分组收起不创建子节点。拖滑块每帧只改游标，释放后取最终精确投影。滑动中只允许显示已验证的该时点数据，向前拖立即遮住旧明细。取消旧请求，按版本拒绝迟返 | 摘要滑块交互反馈 <100ms。同一轮拖动最终请求 ≤1，预取另计。跨度 150 章的弧线在中间卷仍有截断标记 |
| 布局、缓存、图片 | 布局分批上 worker，用 generationId 丢弃过时计算。松手后 400–600ms 合并 PATCH 脏坐标，切视图 flush，网络失败本地保留已授权的位置变更、回连重试。节奏数据按需查询，图板懒加载缩略图、回收离屏 bitmap | 一次连续拖动最多一次成功写入。95% 普通布局请求 <300ms，布局不阻塞打字。打开节奏总览查询数按视窗固定，0 次正文全本下载、0 次隐式 LLM |
| 窄屏和无障碍 | 优先本卷/一跳。小屏可切「列表」完成定位、增删连线、改顺序、送导演。焦点环与文字区分同色多轨，拖拽配「移到上/下、连接到…」，适配 reduced-motion | 390px 仍可完成送入导演与跳回原章。三主题键盘全程可达。低性能设备在帧预算失败时先降标签、阴影、边数，保留选中节点及主操作 |

建议基准设备为 4 核/8 GB、集显、60Hz、1440×900。记录具体 CPU/浏览器版本和屏幕刷新率。在离线生成的固定样书上跑生产构建，冷缓存首进 5 轮、热操作至少 20 轮。Performance trace 统计 frame gap、missed frame、long tasks，React Profiler 查重渲染，PG `EXPLAIN (ANALYZE, BUFFERS)` 查窗口索引和 N+1。移动 390px 单独报告，4× CPU throttle 作为降级压力项。保留基线正文输入延迟和 chunk 清单以对比。 [React Flow 性能建议](https://reactflow.dev/learn/advanced-use/performance) · [可见元素参数成本说明](https://reactflow.dev/api-reference/react-flow)

## 8. 四次独立发布与验收

工期按熟悉现有仓库的工程团队估算，以人日计，包含开发、评审、专项测试和灰度观察。各阶段都包含可演示的作者操作。团队并行度决定日历时间。图像供应方采购与可选真实生图另估。

| 发布 | 上线交付 | 预估人日 | 放量门槛与回退 |
| --- | --- | ---: | --- |
| **7A · 关系与伏笔可操作** | 五视图外壳先开放正文、关系图、剧情泳道。严格时点投影、目录/右栏联动、连线写记忆、出场格、作者规划预警、伏笔/派生支线送导演、跳正文。本机坐标，零 DDL | **11–16** | 同样书第 7/12/16/17 章按第 2 节验。跨书请求失败。加一条关系能在下一次受限 Lorekeeper 输入中按 ID 找到。导演草稿 3 条上限。切图不丢未保存稿，作者无需 AI 可完成全部查图操作。关闭入口开关后原设定服务仍读到新增关系 |
| **7B · 排卡可成稿** | V14，跨端布局、持久化节拍/芯片/连线、计划张力曲线、命名支线/世界边、受控记忆修订。章内排卡建节、重排、送 HITL 大纲。可单独发布创作画布 | **22–32** | 从旧 SINGLE 章扩成 3 节不丢原文。交换 2/3 后，目录、整章预览、摘要/检索时间边界、命中证据一致。双标签改同一节按版本拒绝一次。150 人与 300 卡达到第 7 节门槛。关闭画布后正式节仍可在原目录写 |
| **7C · 分支可推演可采用** | V15，What-If 专用 LangGraph、2–3 章报告、并行/串行按档、SSE 重连、估价与账本、一键立主干、逐章生成及平行世界档案 | **21–30** | A/B 两支一成一败可读成功报告，重连不多扣。中途取消、worker 重启、重复回调、双标签选不同赢家均有确定终态。未选支在正式目录/记忆/Milvus 为 0 条。书中旧稿需 Diff，书尾产生可编辑草稿。可关闭新推演，已采用稿、报告与账单可读 |
| **7D · 节奏与设定板** | V16，按章规则指标及可选 LLM 补评、世界势力/地标图、MinIO 图片与作者侧栏钉图。上传、提示词模板均可独立使用 | **14–22** | 改第 73 章仅重算该章，旧 hash 迟返被拒。空文本、不配对引号、别名歧义有标识。最后一张额度并发仅一个成功。删除/失败预留可释放，越书图不可读。回到正文同一作用域显示钉图。关闭评分或图库入口，原稿和其余视图可独立使用 |

后端按书返回四个能力开关 `visualExplore / storyboard / branching / pacingAtlas`，前端据此开放入口。关闭新增任务与停读历史报告是两项操作。灰度停止接单后，已有任务仍能完成、取消和结算，正式正文可继续编辑。

基础路线合计 **68–100 人日**。可选模型生图约另加 4–8 人日，取决于供应方计价、任务查询与取消能力。长期运行观测项为投影 p95/载荷、前端掉帧率、布局冲突率、过期指标数与最长等待、推演成功/部分成功数、冻结未结金额、待清理对象字节数。每阶段在测试数据上演练关闭开关与继续读稿。迁移兼容旧客户端，按书灰度，保持已有写作入口可用。

**第一张工单。** 以同一本人工样书编写 Java 投影契约样例，包含第 12 章第 1/2 节的不同可见性，以及一条没有起始章的旧关系。前端实现图谱的「拖线 → 选择本节生效 → 侧栏见同一关系 → 回正文 → 下一次 Lorekeeper 读到该关系」。两端共享样例验收。该工单直接覆盖产品最大的信任边界，也给后续三期提供可重复使用的证据集。

## 9. 已拍板的六项架构与产品决策（2026-11-27 锁定：`1A 2A 3A 4A 5A 6A`）

以下六项关键架构与产品决策已全部确认采用 **A 方案**，作为 Phase 7A–7D 正式施工基线：

| 决策 | 已锁定方案 | 落地方案与施工影响 |
| --- | --- | --- |
| **1. 默认看见多少未来？** | **1A · 读到当前章/节，显式切全书规划** | 边写边查时不被后续章节状态剧透；决定默认读模型、右栏预取、缓存键与历史缺证空态；同时保留一键切全书规划视角 |
| **2. 节拍和正式小节如何对应？** | **2A · 草卡自由摆放，绑定后一张主节拍对应一节，确认顺序才改正文** | 作者可在画布自由试排，确认后经批量重排事务落到目录与 `chapter_sections`，避免拖动瞬时频繁触发记忆重算 |
| **3. 从书中转折采用未来时怎么处理已有稿？** | **3A · 书尾建新草稿，已有章先映射和 Diff** | 书尾直接建 `DRAFT`；书中转折涉及已有后续章时逐章映射并经 `AiDiffReviewModal` 审阅落稿，无需为正文/RAG/导出引入分支命名空间 |
| **4. What-If 如何收费和限流？** | **4A · 先报上限预冻结、按实际 token 结算、VIP 控扇出和并发** | 清晰体现 2×2 与 6×3 的成本差异，取消或部分失败退回未用预扣；复用现有微积分账本与 `VipEntitlements` |
| **5. 世界版图首发做到哪种空间精度？** | **5A · 势力/地标关系图、概念图灵感板与侧栏钉选** | 零底图即可立即使用势力/地标网络与视觉参考钉选；自定义底图坐标与区域图层留作后续扩展 |
| **6. LangGraph 的接入范围？** | **6A · 仅用于新增 What-If 推演图，主干写作沿用现有编辑部** | 新能力独立灰度、取消与回退，不影响现有主干 `EditorialTeamOrchestrator`、人工审批与历史 Checkpoint 恢复 |
