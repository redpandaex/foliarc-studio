import type { UiStatus } from "@/components/ui/badge";

const ROLE_LABELS: Record<string, string> = {
  ADMIN: "管理员",
  AUTHOR: "作者",
};

const PLAN_LABELS: Record<string, string> = {
  FREE: "免费版",
  VIP: "签约会员",
  SVIP: "白金会员",
  STUDIO: "工作室版",
};

const DOC_TYPE_LABELS: Record<string, string> = {
  WORLDBOOK: "世界观资料",
  STYLE_REF: "文风参考",
  OUTLINE: "大纲模板",
  CHAPTER: "作品章节",
};

const SCOPE_LABELS: Record<string, string> = {
  GLOBAL: "全部作者",
  ASSIGNED: "指定作者",
  USER: "个人资料",
  BOOK: "作品内资料",
};

const STATUS_LABELS: Record<string, string> = {
  READY: "已就绪",
  PUBLISHED: "已投递",
  COMPLETED: "已完成",
  ACTIVE: "已启用",
  DRAFT: "草稿",
  ARCHIVED: "已归档",
  PENDING: "排队中",
  QUEUED: "排队中",
  PARSING: "解析中",
  CHUNKING: "切分中",
  EMBEDDING: "索引中",
  RUNNING: "运行中",
  PAUSED: "待确认",
  CANCELLED: "已取消",
  FAILED: "失败",
  SCANNED: "已扫描",
  PATCHING: "修订中",
  PATCHED: "已修订",
  RESUMED: "已处理",
  WAITING: "待确认",
  RESOLVED: "已处理",
};

const GENRE_LABELS: Record<string, string> = {
  XIANXIA_FANTASY: "仙侠玄幻",
  HORROR_SUPERNATURAL: "恐怖灵异",
  CLASSICAL_HISTORICAL: "古典历史",
  MYSTERY_THRILLER: "悬疑推理",
  SCIFI_APOCALYPSE: "科幻末世",
  URBAN_REALISM: "都市现实",
};

const LEDGER_LABELS: Record<string, string> = {
  GRANT: "赠送",
  TOPUP: "充值",
  FREEZE: "冻结",
  SETTLE: "扣费",
  RELEASE: "退还",
  ADMIN_GRANT: "人工发放",
};

const RUN_ACTION_LABELS: Record<string, string> = {
  CONTINUE: "续写",
  REWRITE: "改写",
  OUTLINE: "大纲",
  EXPAND: "扩写",
  POLISH: "润色",
};

const EVENT_TYPE_LABELS: Record<string, string> = {
  run_started: "开始生成",
  route_decision: "路由决策",
  agent_handoff: "角色交接",
  tool_call: "检索调用",
  tool_result: "检索返回",
  critic_review: "质量审校",
  interrupt: "等待确认",
  usage: "用量结算",
  error: "执行异常",
  done: "生成结束",
};

const AUDIT_ACTION_LABELS: Record<string, string> = {
  USER_ROLE_UPDATE: "调整角色",
  USER_CREDITS_GRANT: "发放积分",
  KNOWLEDGE_UPLOAD: "上传公共资料",
  KNOWLEDGE_DELETE: "删除公共资料",
  KNOWLEDGE_GRANT: "授权资料",
  KNOWLEDGE_REVOKE: "撤销授权",
  OUTBOX_RETRY: "重试事件投递",
  DOCUMENT_RETRY: "重跑资料处理",
  VIP_PLAN_UPDATE: "调整会员方案",
  VIP_GRANT: "人工开通会员",
  PROMPT_PUBLISH: "发布提示词",
  PROMPT_ACTIVATE: "启用提示词版本",
  EVAL_RUN: "运行质量评测",
};

const TARGET_TYPE_LABELS: Record<string, string> = {
  USER: "用户",
  DOCUMENT: "资料",
  KNOWLEDGE_DOCUMENT: "资料",
  OUTBOX_EVENT: "投递事件",
  VIP_PLAN: "会员方案",
  PROMPT_TEMPLATE: "提示词模板",
  EVAL_SUITE: "评测集",
};

const GUARDRAIL_STAGE_LABELS: Record<string, string> = {
  INPUT: "输入检查",
  OUTPUT: "输出检查",
  TOOL: "调用检查",
};

const GUARDRAIL_ACTION_LABELS: Record<string, string> = {
  BLOCK: "已拦截",
  REDACT: "已脱敏",
  WARN: "已提醒",
  PASS: "已放行",
};

export function labelRole(role?: string | null): string {
  if (!role) return "作者";
  return ROLE_LABELS[role.toUpperCase()] ?? role;
}

export function labelPlan(planCode?: string | null): string {
  if (!planCode) return "免费版";
  return PLAN_LABELS[planCode.toUpperCase()] ?? planCode;
}

export function labelDocType(docType?: string | null): string {
  if (!docType) return "资料";
  return DOC_TYPE_LABELS[docType.toUpperCase()] ?? docType;
}

export function labelScope(scope?: string | null, accessType?: string | null): string {
  const key = (accessType || scope || "").toUpperCase();
  return SCOPE_LABELS[key] ?? "全部作者";
}

export function labelStatus(status?: string | null): string {
  if (!status) return "未知";
  return STATUS_LABELS[status.toUpperCase()] ?? status;
}

export function badgeForStatus(status?: string | null): UiStatus {
  const key = (status ?? "").toUpperCase();
  if (["READY", "PUBLISHED", "COMPLETED", "ACTIVE", "PATCHED", "RESOLVED", "RESUMED"].includes(key)) {
    return "success";
  }
  if (["FAILED", "BLOCK"].includes(key)) {
    return "danger";
  }
  if (["PAUSED", "WAITING"].includes(key)) {
    return "ai";
  }
  if (["PENDING", "QUEUED", "PARSING", "CHUNKING", "EMBEDDING", "RUNNING", "PATCHING"].includes(key)) {
    return "warning";
  }
  return "neutral";
}

export function labelGenreCodes(raw?: string | null): string {
  if (!raw || !raw.trim()) return "全题材通用";
  return raw
    .split(",")
    .map((part) => {
      const code = part.trim();
      return GENRE_LABELS[code] ?? code;
    })
    .filter(Boolean)
    .join("、");
}

export function labelLedgerType(entryType?: string | null): string {
  if (!entryType) return "变动";
  return LEDGER_LABELS[entryType.toUpperCase()] ?? entryType;
}

export function labelRunAction(action?: string | null): string {
  if (!action) return "写作";
  return RUN_ACTION_LABELS[action.toUpperCase()] ?? action;
}

export function labelEventType(eventType?: string | null): string {
  if (!eventType) return "步骤";
  return EVENT_TYPE_LABELS[eventType] ?? EVENT_TYPE_LABELS[eventType.toLowerCase()] ?? eventType;
}

export function labelAuditAction(action?: string | null): string {
  if (!action) return "后台操作";
  return AUDIT_ACTION_LABELS[action.toUpperCase()] ?? action;
}

export function labelTargetType(targetType?: string | null): string {
  if (!targetType) return "对象";
  return TARGET_TYPE_LABELS[targetType.toUpperCase()] ?? targetType;
}

export function labelGuardrailStage(stage?: string | null): string {
  if (!stage) return "安全检查";
  return GUARDRAIL_STAGE_LABELS[stage.toUpperCase()] ?? stage;
}

export function labelGuardrailAction(action?: string | null): string {
  if (!action) return "已拦截";
  return GUARDRAIL_ACTION_LABELS[action.toUpperCase()] ?? action;
}

export function formatBpsAsDiscount(bps: number): string {
  if (!Number.isFinite(bps) || bps <= 0 || bps >= 10000) return "原价";
  const zhe = bps / 1000;
  return `${Number(zhe.toFixed(1))} 折`;
}

export function bpsToZheInput(bps: number): string {
  if (!Number.isFinite(bps) || bps <= 0) return "10";
  return String(Number((bps / 1000).toFixed(2)));
}

export function zheInputToBps(value: string): number {
  const num = Number.parseFloat(value);
  if (!Number.isFinite(num) || num <= 0) return 10000;
  return Math.max(100, Math.min(10000, Math.round(num * 1000)));
}
