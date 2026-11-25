from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class CharacterFieldDef:
    key: str
    label: str
    placeholder: str


@dataclass(frozen=True, slots=True)
class GenreProfile:
    code: str
    display_name: str
    short_name: str
    description: str
    recommended_sub_tags: tuple[str, ...]
    character_field_schema: tuple[CharacterFieldDef, ...]
    style_principles: str
    anti_cliche_rules: tuple[str, ...]
    default_beat_pattern: str
    critic_rubric: tuple[str, ...]
    summary_focus: str
    default_skill: str | None = None

    def format_writer_guard_block(self) -> str:
        anti = "；".join(self.anti_cliche_rules)
        return (
            f"【本题材文风铁律 · {self.display_name}】\n"
            f"- 写作基调：{self.style_principles}\n"
            f"- 严禁套话与出戏词：{anti}"
        )

    def format_director_pattern_block(self) -> str:
        return (
            f"【本题材默认章节节拍骨架 · {self.display_name}】\n"
            f"{self.default_beat_pattern}"
        )

    def format_critic_rubric_block(self) -> str:
        lines = [f"- {item}" for item in self.critic_rubric]
        anti = "；".join(self.anti_cliche_rules)
        return (
            f"【题材专属审稿权重（{self.display_name}）】\n"
            + "\n".join(lines)
            + f"\n- 禁用 AI 套话/出戏词红线（出现即扣分）：{anti}"
        )

    def format_distill_focus_block(self) -> str:
        fields = "、".join(f"{f.key}({f.label})" for f in self.character_field_schema)
        return (
            f"题材提取重点（{self.display_name}）：{self.summary_focus}\n"
            f"人物动态扩展属性 customAttributes 推荐键：{fields}"
        )


_PROFILES: tuple[GenreProfile, ...] = (
    GenreProfile(
        code="XIANXIA_FANTASY",
        display_name="仙侠 / 玄幻",
        short_name="仙侠玄幻",
        description="东方修真、玄幻升级、宗门博弈与力量体系推演",
        recommended_sub_tags=("古典仙侠", "凡人流", "宗门权谋", "东方玄幻", "剑修", "志怪修真"),
        character_field_schema=(
            CharacterFieldDef("realm", "修为境界", "如：筑基后期 / 金丹初期"),
            CharacterFieldDef("cultivationArt", "功法秘术", "如：青莲剑典 / 敛息诀"),
            CharacterFieldDef("faction", "宗门势力", "如：青云宗外门 / 散修"),
            CharacterFieldDef("artifacts", "法宝灵器", "如：玄铁残剑 / 聚灵玉符"),
        ),
        style_principles="强调力量体系层级感、出手代价与因果牵连；动作与术法落笔到感官与真气消耗，避免空泛数值堆砌。",
        anti_cliche_rules=(
            "禁用现代管理学与互联网黑话（如“降维打击”“赋能”“拉满”“底层逻辑”“闭环”）",
            "忌连篇累牍“恐怖如斯”“倒吸一口凉气”“眼神骤冷”等廉价套话",
        ),
        default_beat_pattern="承接上章危机/悬念 → 冲突升级与资源/战力压制 → 借助智谋或底牌付出代价破局 → 埋下更大格局的章末钩子",
        critic_rubric=(
            "战力与境界层级合理性：严查无合理代价的越阶碾压或忽略既有伤势",
            "法宝、丹药与功法流向一致性：不得凭空冒出未交代法器或忘记已损毁宝物",
            "宗门与势力利益逻辑：角色抉择需符合宗门规矩与现实利害",
            "语言纯度：剔除现代职场/互联网词汇及廉价网文重复套话",
        ),
        summary_focus="境界突破、秘术消耗、资源法宝得失与宗门势力格局变动",
        default_skill="combat-scene",
    ),
    GenreProfile(
        code="HORROR_SUPERNATURAL",
        display_name="恐怖 / 灵异 / 规则怪谈",
        short_name="恐怖灵异",
        description="中式民俗惊悚、规则怪谈、不可名状与心理压迫",
        recommended_sub_tags=("中式民俗", "规则怪谈", "克苏鲁", "心理惊悚", "无限副本", "都市异闻"),
        character_field_schema=(
            CharacterFieldDef("sanity", "理智/精神状态", "如：清醒 / 轻度幻听 / 濒临崩溃"),
            CharacterFieldDef("knownRules", "已知规则", "如：午夜不可照镜、听见敲门数三声"),
            CharacterFieldDef("taboosTriggered", "已触发禁忌", "如：误答陌生人呼名（待清算）"),
            CharacterFieldDef("survivalStatus", "存活/污染态", "如：正常 / 轻度诡异浸染"),
        ),
        style_principles="严禁直接播报情绪！必须通过感官微异常（气味、温差、反常倒影、多余脚步声）、空间压抑、日常异化与信息不对称制造寒意，留白大于直白解释。",
        anti_cliche_rules=(
            "严禁使用“令人毛骨悚然”“一阵寒意直窜天灵盖”“他感到无比恐惧/害怕极了”等直白情绪播报",
            "严禁过早用旁白全知科普鬼怪原理导致恐惧张力泄气",
            "严禁角色为推进剧情强行降智送人头",
        ),
        default_beat_pattern="看似正常的日常切入与一处微弱违和 → 感官异常逐步升级 → 试探规则/信息残缺下的抉择 → 假安全后的二次反转或冰冷悬念",
        critic_rubric=(
            "恐惧张力与留白：出现直白情绪播报（毛骨悚然/寒意直窜/感到无比恐惧）或过早泄底必须扣分",
            "规则逻辑闭环：核查角色已知规则、触发禁忌与代价兑现是否严密自洽",
            "角色智商在线：严禁强行无脑送死或违背求生本能",
            "视角与信息不对称：严禁主角凭空全知未知规则或幕后真相",
        ),
        summary_focus="异常感官细节、精确规则措辞、禁忌触发条件与角色认知盲区",
        default_skill="horror-atmosphere",
    ),
    GenreProfile(
        code="CLASSICAL_HISTORICAL",
        display_name="古典 / 历史 / 世情志怪",
        short_name="古典历史",
        description="严肃古典、历史权谋、明清世情、笔记志怪与文学大章",
        recommended_sub_tags=("明清世情", "历史权谋", "志怪笔记", "古典白描", "民俗恐怖", "严肃文学"),
        character_field_schema=(
            CharacterFieldDef("courtesyName", "字 / 号", "如：字子敬，号青溪居士"),
            CharacterFieldDef("officialTitle", "官职爵位", "如：翰林院编修 / 县丞"),
            CharacterFieldDef("clanRank", "宗族辈分", "如：嫡次子 / 族叔"),
            CharacterFieldDef("residence", "籍贯府邸", "如：姑苏城西柳巷"),
        ),
        style_principles="讲究白描、节制与半文白韵律；通过起居器物、礼法称谓、话语留白折射世态人心与命运暗流。",
        anti_cliche_rules=(
            "严禁现代网文套话与翻译腔（禁用“嘴角勾起一抹弧度”“眼神骤冷”“破防”“空气仿佛凝固”“情绪价值”）",
            "严禁朝代错位器物与现代口语（如“逻辑”“概率”“方案”“OK”“吐槽”）",
        ),
        default_beat_pattern="起势入景（时令/器物/礼仪定调） → 人物交锋与世情礼法暗涌 → 细微变故折射格局倾斜 → 余韵留白或章回悬念收束",
        critic_rubric=(
            "时代常识与器物考据：严查朝代错位器物、服饰、称谓及现代口语穿帮",
            "尊卑称谓与避讳：核对自称、尊称、谦称与宗族官场礼法是否得体",
            "白描克制与去网文腔：出现“嘴角勾起一抹弧度”“眼神骤冷”等现代网文套话必须扣分",
            "人物动机与世情逻辑：权谋、家宅与人情往来须合乎时代利益格局",
        ),
        summary_focus="礼法暗示、称谓与官职变更、官场/宗族利益格局及历法时序",
        default_skill="classical-prose",
    ),
    GenreProfile(
        code="MYSTERY_THRILLER",
        display_name="悬疑 / 推理 / 刑侦",
        short_name="悬疑推理",
        description="本格/社会派推理、刑侦探案、心理博弈与公平线索反转",
        recommended_sub_tags=("本格推理", "刑侦破案", "社会派悬疑", "密室诡计", "心理惊悚", "双线叙事"),
        character_field_schema=(
            CharacterFieldDef("identity", "社会身份", "如：法医助理 / 钟表匠"),
            CharacterFieldDef("alibi", "不在场证明", "如：21:00-22:30 在地铁站被监控拍到"),
            CharacterFieldDef("motive", "潜在动机", "如：遗产继承纠纷 / 旧案知情人"),
            CharacterFieldDef("knownClues", "掌握线索", "如：半枚带机油味的袖扣"),
        ),
        style_principles="坚守公平线索原则（核心物证须在揭晓前自然露出）；对话重潜台词、微表情与供词缝隙；忌旁白提前全知泄底。",
        anti_cliche_rules=(
            "忌“柯南式长篇说教”一次性灌输推理结论，须由线索与对质层层逼出",
            "严禁主角凭空全知尚未查证的隐秘信息或证人内心独白",
        ),
        default_beat_pattern="现场勘查/新物证浮现 → 盘问试探与供词矛盾暴露 → 局部推演验证或推翻假设 → 抛出更具指向性的新疑点",
        critic_rubric=(
            "物理时间线可行性：核查案发时间、移动距离、作案工具与不在场证明是否冲突",
            "线索公平性：关键推理依据必须已在前文呈现，严禁机械降神",
            "事实与证词隔离：严格区分客观事实与嫌疑人口供，防止角色视角越权读心",
            "悬念节奏：避免冗长说教或过早泄露核心诡计底牌",
        ),
        summary_focus="精确时间点、物证位置、口供矛盾（严格区分客观事实与人物声称）",
        default_skill="fair-play-clues",
    ),
    GenreProfile(
        code="SCIFI_APOCALYPSE",
        display_name="科幻 / 赛博 / 末世",
        short_name="科幻末世",
        description="硬核科幻、赛博朋克、废土生存与星际文明推演",
        recommended_sub_tags=("硬科幻", "赛博朋克", "废土末世", "星际探索", "人工智能", "生存危机"),
        character_field_schema=(
            CharacterFieldDef("augmentation", "义体/异能/权限", "如：神经反射芯片 Mk.II / B 级密钥"),
            CharacterFieldDef("affiliation", "阵营/公司", "如：苍穹重工安全部 / 自由拾荒者"),
            CharacterFieldDef("resources", "能源/物资", "如：剩余 48 小时滤芯、2 块电池"),
            CharacterFieldDef("infectionOrRisk", "辐射/污染风险", "如：神经过载 35% / 轻度辐射"),
        ),
        style_principles="严格遵循书内已确立的科技树与物理硬约束；借极端环境与资源倒计时挤压人性抉择，描写落到工业材质、物理反馈与代价。",
        anti_cliche_rules=(
            "严禁把“量子”“纳米”“基因”当成无逻辑万能魔法随意解围",
            "严禁忽略能源、氧气、弹药或义体过载等已确立的硬约束",
        ),
        default_beat_pattern="危机环境/资源倒计时提示 → 技术约束下的试错与阵营博弈 → 付出实质代价完成关键操作 → 引发更深层的系统或文明级变数",
        critic_rubric=(
            "科技与物理规则自洽：严禁违背既定科技树或用“量子/纳米”糊弄逻辑漏洞",
            "物资与生存账目一致：氧气、电力、弹药、污染值消耗须前后吻合",
            "阵营与政治逻辑：公司/组织行动须受利益、协议与权限约束",
            "场景质感：避免空洞玄幻式描写替代科幻工业与环境细节",
        ),
        summary_focus="科技铁律、资源消耗账目、时间跨度与系统/组织权限变动",
        default_skill="scifi-hard-logic",
    ),
    GenreProfile(
        code="URBAN_REALISM",
        display_name="都市 / 现实 / 职场情感",
        short_name="都市现实",
        description="现实职场、都市商战、人情世故与细腻情感拉扯",
        recommended_sub_tags=("现实职场", "都市商战", "情感成长", "家庭伦理", "行业群像", "年代生活"),
        character_field_schema=(
            CharacterFieldDef("occupation", "职业 / 职级", "如：律所资深合伙人 / 急诊主治医师"),
            CharacterFieldDef("socialCircle", "社交圈层", "如：投行圈 / 老城区邻里"),
            CharacterFieldDef("financialState", "资产 / 债务", "如：背负房贷与创业对赌条款"),
            CharacterFieldDef("coreDesire", "核心诉求", "如：保住项目控制权同时护住家人"),
        ),
        style_principles="注重日常质感、真实行业规则与人物潜台词；通过具体生活细节、利益交换与克制情绪递进塑造人物弧光。",
        anti_cliche_rules=(
            "忌脸谱化无脑反派与强行降智的“歪嘴装逼打脸”套路",
            "忌空洞鸡汤说教与不合常理的职场/法律/财务描写",
        ),
        default_beat_pattern="现实处境与具体利益压力切入 → 职场或情感关系中的言语试探与博弈 → 关键取舍打破脆弱平衡 → 留下真实余波与关系温度变化",
        critic_rubric=(
            "社会与行业常识合理性：严查明显违背职场、商业或生活常理的降智桥段",
            "人物动机真实度：配角与对手须有合理自身诉求，拒绝工具人脸谱化",
            "情感与关系递进连贯性：温度变化须有具体事件铺垫，不突兀转折",
            "语言生活质感：避免浮夸网文口号或油腻霸总套话",
        ),
        summary_focus="利益交换、关系温度变化、关键约定与现实阻碍",
        default_skill="urban-tension",
    ),
)

_BY_CODE: dict[str, GenreProfile] = {p.code: p for p in _PROFILES}


class GenreProfileRegistry:
    """In-memory mirror of the 6 canonical genre profiles shared with biz-java."""

    @staticmethod
    def list_all() -> list[GenreProfile]:
        return list(_PROFILES)

    @staticmethod
    def get(genre_code: str | None) -> GenreProfile | None:
        if not genre_code:
            return None
        return _BY_CODE.get(genre_code.strip().upper())

    @staticmethod
    def resolve(genre_code: str | None, legacy_genre: str | None = None) -> GenreProfile:
        if genre_code:
            found = _BY_CODE.get(genre_code.strip().upper())
            if found is not None:
                return found
        text = (legacy_genre or "").strip().lower()
        if any(k in text for k in ("恐怖", "灵异", "怪谈", "克苏鲁", "惊悚", "horror")):
            return _BY_CODE["HORROR_SUPERNATURAL"]
        if any(k in text for k in ("古典", "历史", "世情", "志怪", "明清", "武侠", "严肃", "classical")):
            return _BY_CODE["CLASSICAL_HISTORICAL"]
        if any(k in text for k in ("悬疑", "推理", "刑侦", "侦探", "探案", "mystery")):
            return _BY_CODE["MYSTERY_THRILLER"]
        if any(k in text for k in ("科幻", "赛博", "末世", "星际", "废土", "scifi")):
            return _BY_CODE["SCIFI_APOCALYPSE"]
        if any(k in text for k in ("都市", "现实", "职场", "情感", "商战", "urban")):
            return _BY_CODE["URBAN_REALISM"]
        return _BY_CODE["XIANXIA_FANTASY"]

    @staticmethod
    def parse_sub_tags(sub_genre_tags: str | None) -> list[str]:
        if not sub_genre_tags:
            return []
        raw = sub_genre_tags.replace("，", ",").replace("、", ",").replace(";", ",")
        return [item.strip() for item in raw.split(",") if item.strip()]
