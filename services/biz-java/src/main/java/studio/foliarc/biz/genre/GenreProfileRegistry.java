package studio.foliarc.biz.genre;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.genre.GenreDtos.CharacterFieldDef;
import studio.foliarc.biz.genre.GenreDtos.GenreProfileResponse;

@Component
public class GenreProfileRegistry {

    public static final String DEFAULT_GENRE_CODE = "XIANXIA_FANTASY";

    private static final List<GenreProfileResponse> PROFILES = List.of(
            new GenreProfileResponse(
                    DEFAULT_GENRE_CODE, "仙侠 / 玄幻", "仙侠玄幻", "围绕修行、力量体系与宗门利益展开的故事",
                    List.of("东方玄幻", "修仙", "宗门", "洪荒", "志怪"),
                    List.of(new CharacterFieldDef("realm", "修为境界", "如：筑基后期"),
                            new CharacterFieldDef("cultivationArt", "功法秘术", "如：青木诀、御剑术"),
                            new CharacterFieldDef("faction", "宗门势力", "如：青云宗外门"),
                            new CharacterFieldDef("artifacts", "法宝灵器", "如：断水剑、护心符")),
                    "建立清晰的境界层级。突破、秘术与越阶取胜都要有铺垫和代价，宗门行动应有利益依据。",
                    List.of("禁用“降维打击”“赋能”“拉满”等现代黑话", "避免靠无铺垫的突破或法宝化解危机"),
                    "承接上章危机 → 冲突升级与压制 → 底牌破局或收获 → 更大格局的钩子",
                    List.of("战力与境界越阶合理性", "法宝得失与流向", "宗门利益逻辑", "突破与秘术消耗的代价"),
                    "提取境界突破、秘术消耗、资源与法宝的得失。"
            ),
            new GenreProfileResponse(
                    "HORROR_SUPERNATURAL", "恐怖 / 灵异 / 规则怪谈", "恐怖灵异", "以感官异常、认知盲区与禁忌规则积累恐惧",
                    List.of("中式民俗", "民俗恐怖", "规则怪谈", "克苏鲁", "都市怪谈"),
                    List.of(new CharacterFieldDef("sanity", "理智/精神状态", "如：濒临崩溃，出现幻听"),
                            new CharacterFieldDef("knownRules", "已知规则", "如：午夜不照镜"),
                            new CharacterFieldDef("taboosTriggered", "已触发禁忌", "如：听见敲门后三次应声"),
                            new CharacterFieldDef("survivalStatus", "存活/污染态", "如：存活，左手出现污染")),
                    "用感官微异常、空间压抑和日常异化呈现恐惧。保留信息落差，控制揭示规则的时机。",
                    List.of("禁用“令人毛骨悚然”“一阵寒意直窜天灵盖”“他感到无比恐惧”等情绪播报",
                            "避免提前解释异常的来源", "避免角色为触发规则而突然丧失判断力"),
                    "日常微异 → 异常升级 → 规则试探或信息残缺 → 假安全或真危机悬念",
                    List.of("恐惧张力与揭示时机", "规则逻辑闭环", "角色决策是否可信", "视角与信息边界"),
                    "记录异常感官细节、规则原文、禁忌触发条件与角色认知盲区。"
            ),
            new GenreProfileResponse(
                    "CLASSICAL_HISTORICAL", "古典 / 历史 / 世情志怪", "古典历史", "以时代礼法、社会关系与人物选择推进故事",
                    List.of("明清世情", "志怪笔记", "权谋", "武侠", "历史架空"),
                    List.of(new CharacterFieldDef("courtesyName", "字/号", "如：字子安，号听松"),
                            new CharacterFieldDef("officialTitle", "官职爵位", "如：礼部侍郎"),
                            new CharacterFieldDef("clanRank", "宗族辈分", "如：长房次子"),
                            new CharacterFieldDef("residence", "籍贯府邸", "如：苏州吴县，寓居京城")),
                    "使用白描和节制的半文白韵律，通过礼数、称谓与避讳写出尊卑和利害。",
                    List.of("禁用“嘴角勾起一抹弧度”“眼神骤冷”“破防”“空气仿佛凝固”等套话",
                            "避免现代网文口语与翻译腔", "避免朝代错位的器物和词汇"),
                    "起势入景 → 人物交锋与世情暗涌 → 余韵留白或章回悬念",
                    List.of("时代器物与风俗", "称谓、尊卑与避讳", "语言与时代语感", "官场与宗族利益逻辑"),
                    "记录礼法暗示、称谓变更、官场与宗族利益格局，以及时间历法。"
            ),
            new GenreProfileResponse(
                    "MYSTERY_THRILLER", "悬疑 / 推理 / 刑侦", "悬疑推理", "以可追溯线索、矛盾证词与推演反转构建谜局",
                    List.of("本格推理", "刑侦", "密室", "社会派推理", "心理悬疑"),
                    List.of(new CharacterFieldDef("identity", "社会身份", "如：旅店经理，案发现场目击者"),
                            new CharacterFieldDef("alibi", "不在场证明", "如：21:10 出现在前台监控"),
                            new CharacterFieldDef("motive", "潜在动机", "如：担心伪造账目被发现"),
                            new CharacterFieldDef("knownClues", "掌握线索", "如：备用钥匙上有油渍")),
                    "核心线索须在揭晓前自然呈现。用对话潜台词、反应与证词差异推动调查。",
                    List.of("避免全知旁白提前揭底", "避免揭晓时凭空加入关键线索", "避免以长篇说教串讲案情"),
                    "现场或新证浮现 → 盘问试探与矛盾证词 → 推演反转 → 抛出新疑点",
                    List.of("物理时间线可行性", "不在场证明的冲突", "线索公平性", "角色信息隔离"),
                    "记录精确时间、物证位置和口供矛盾，区分已核实事实与证词。"
            ),
            new GenreProfileResponse(
                    "SCIFI_APOCALYPSE", "科幻 / 赛博 / 末世", "科幻末世", "以技术约束、资源稀缺与阵营选择推演世界",
                    List.of("硬科幻", "赛博朋克", "废土", "星际", "灾变求生"),
                    List.of(new CharacterFieldDef("augmentation", "义体/异能/权限", "如：二级视觉增强，限时接入权限"),
                            new CharacterFieldDef("affiliation", "阵营/公司", "如：外环补给联盟"),
                            new CharacterFieldDef("resources", "能源/物资", "如：氧气剩余 40 分钟"),
                            new CharacterFieldDef("infectionOrRisk", "辐射/污染风险", "如：累计辐射剂量接近阈值")),
                    "遵循已经建立的科技树和物理约束，写出技术与资源限制如何改变人物的选择。",
                    List.of("禁用“量子”“纳米”充当无约束的万能解释", "避免忽略能源、补给与权限的限制"),
                    "设定约束明确 → 资源或系统告急 → 权衡方案与代价 → 新的生存压力",
                    List.of("科技与物理规则自洽", "物资消耗账目", "阵营政治逻辑", "风险与权限变化"),
                    "记录科技铁律、资源消耗、时间跨度和系统或组织权限变动。"
            ),
            new GenreProfileResponse(
                    "URBAN_REALISM", "都市 / 现实 / 职场情感", "都市现实", "通过职业、家庭与亲密关系中的具体选择呈现现实张力",
                    List.of("职场", "商战", "生活流", "家庭关系", "情感"),
                    List.of(new CharacterFieldDef("occupation", "职业/职级", "如：区域销售主管"),
                            new CharacterFieldDef("socialCircle", "社交圈层", "如：前同事、客户和家长群"),
                            new CharacterFieldDef("financialState", "资产/债务", "如：背负房贷，现金流紧张"),
                            new CharacterFieldDef("coreDesire", "核心诉求", "如：在裁员前争取转岗")),
                    "用工作和生活细节建立可信度，让真实的职场规则、利益拉扯与对话潜台词推动关系变化。",
                    List.of("避免脸谱化反派", "避免角色降智配合打脸", "避免用职权、金钱或巧合跳过人物抉择"),
                    "日常目标浮现 → 现实阻碍或利益冲突 → 人物选择与关系变化 → 新的约定或难题",
                    List.of("社会与职场常识", "人物动机真实度", "情感递进连贯性", "利益交换与决定的后果"),
                    "记录利益交换、关系温度变化、关键约定和现实阻碍。"
            )
    );

    private static final Map<String, GenreProfileResponse> BY_CODE = PROFILES.stream()
            .collect(Collectors.toUnmodifiableMap(GenreProfileResponse::code, Function.identity()));
    private static final Set<String> CHARACTER_FIELD_KEYS = PROFILES.stream()
            .flatMap(profile -> profile.characterFieldSchema().stream())
            .map(CharacterFieldDef::key)
            .collect(Collectors.toUnmodifiableSet());

    // Keep inference order and keywords aligned with the V11 backfill.
    private static final List<GenreKeywords> INFERENCE_RULES = List.of(
            new GenreKeywords("HORROR_SUPERNATURAL", Pattern.compile("恐怖|灵异|怪谈|克苏鲁|惊悚")),
            new GenreKeywords("CLASSICAL_HISTORICAL", Pattern.compile("古典|历史|世情|志怪|武侠|权谋|明清")),
            new GenreKeywords("MYSTERY_THRILLER", Pattern.compile("悬疑|推理|刑侦|侦探|探案")),
            new GenreKeywords("SCIFI_APOCALYPSE", Pattern.compile("科幻|赛博|末世|星际|废土")),
            new GenreKeywords("URBAN_REALISM", Pattern.compile("都市|现实|职场|情感|商战"))
    );

    public List<GenreProfileResponse> listAll() {
        return PROFILES;
    }

    public GenreProfileResponse getRequired(String code) {
        GenreProfileResponse profile = BY_CODE.get(normalizeCode(code));
        if (profile == null) {
            throw BizException.notFound("GENRE_NOT_FOUND", "题材档案不存在");
        }
        return profile;
    }

    public String resolveGenreCode(String genreCode, String freeGenreText) {
        String code = normalizeCode(genreCode);
        if (!code.isEmpty()) {
            if (!BY_CODE.containsKey(code)) {
                throw BizException.badRequest("INVALID_GENRE_CODE", "无效的题材代码：" + code);
            }
            return code;
        }
        if (freeGenreText != null) {
            for (GenreKeywords rule : INFERENCE_RULES) {
                if (rule.keywords().matcher(freeGenreText).find()) {
                    return rule.code();
                }
            }
        }
        return DEFAULT_GENRE_CODE;
    }

    public String normalizeSubGenreTags(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return Arrays.stream(raw.split("[,，、;；\\r\\n]+"))
                .map(String::strip)
                .filter(tag -> !tag.isEmpty())
                .distinct()
                .collect(Collectors.joining(","));
    }

    public static String codeOrDefault(String code) {
        String normalized = normalizeCode(code);
        return normalized.isEmpty() ? DEFAULT_GENRE_CODE : normalized;
    }

    public static boolean isCharacterFieldKey(String key) {
        return CHARACTER_FIELD_KEYS.contains(key);
    }

    private static String normalizeCode(String code) {
        return code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
    }

    private record GenreKeywords(String code, Pattern keywords) {}
}
