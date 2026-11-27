export interface WebErrorContext {
  action?: "login" | "register" | "load" | "save" | "delete" | "upload" | "generate" | "billing" | "action";
  quotaHint?: string;
}

interface StructuredErrorLike {
  status?: number;
  code?: string;
  message?: string;
  details?: Record<string, string>;
}

const TECHNICAL_LEAK_PATTERNS = [
  /<!doctype/i,
  /<html/i,
  /<body/i,
  /^\s*\{/,
  /exception\b/i,
  /traceback/i,
  /sqlstate/i,
  /\bat\s+[a-z0-9_$.]+\(/i,
  /localhost:\d+/i,
  /127\.0\.0\.1/,
  /:(?:3000|3001|8000|8080|8081)\b/,
  /\b(?:admin-java|biz-java|agent-py|agent_run_events|rabbitmq|minio|milvus|postgres|redis|outbox)\b/i,
  /\baud=/i,
  /last-event-id/i,
  /\bhttp[_\s-]*\d{3}\b/i,
  /failed to fetch/i,
  /networkerror/i,
  /load failed/i,
  /heartbeat_timeout/i,
  /simulated_network_drop/i,
  /\bsse\b/i,
];

const CODE_MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: "邮箱或密码错误",
  BAD_CREDENTIALS: "邮箱或密码错误",
  AUTH_FAILED: "邮箱或密码错误",
  UNAUTHORIZED: "登录状态已过期，请重新登录",
  TOKEN_EXPIRED: "登录状态已过期，请重新登录",
  INVALID_TOKEN: "登录状态已过期，请重新登录",
  FORBIDDEN: "当前账号没有此操作权限",
  ACCESS_DENIED: "当前账号没有此操作权限",
  EMAIL_ALREADY_EXISTS: "该邮箱已被注册",
  DUPLICATE_EMAIL: "该邮箱已被注册",
  USER_ALREADY_EXISTS: "该邮箱已被注册",
  USERNAME_ALREADY_EXISTS: "该笔名已被使用",
  INSUFFICIENT_CREDITS: "可用积分不足，请先充值",
  INSUFFICIENT_BALANCE: "可用积分不足，请先充值",
  BUDGET_EXCEEDED: "已达本月预算上限，请调整预算后再试",
  MONTHLY_BUDGET_EXCEEDED: "已达本月预算上限，请调整预算后再试",
  VERSION_CONFLICT: "内容已在别处更新，请载入最新版本后再继续",
  RELATION_CONFLICT: "关系数据刚被更新，请刷新后重试",
  NOT_FOUND: "目标内容不存在或已被删除",
  RATE_LIMITED: "操作过于频繁，请稍后再试",
  TOO_MANY_REQUESTS: "操作过于频繁，请稍后再试",
};

function stripLeadingErrorCode(text: string): string {
  return text.replace(/^\s*\[[A-Z0-9_ -]{2,40}\]\s*/, "").trim();
}

export function isSafeUserMessage(raw: string): boolean {
  const cleaned = stripLeadingErrorCode(raw);
  if (!cleaned || cleaned.length > 140) return false;
  if (!/[\u4e00-\u9fff]/.test(cleaned)) return false;
  for (const pattern of TECHNICAL_LEAK_PATTERNS) {
    if (pattern.test(cleaned)) return false;
  }
  return true;
}

export function formatWebError(
  err: unknown,
  fallback: string,
  context?: WebErrorContext,
): string {
  if (err !== undefined && err !== null) {
    console.error("[FoliarcStudio]", err);
  }

  if (typeof err === "object" && err !== null) {
    const e = err as StructuredErrorLike;
    const code = typeof e.code === "string" ? e.code.trim().toUpperCase() : "";
    const status = typeof e.status === "number" ? e.status : undefined;
    const rawMsg = typeof e.message === "string" ? stripLeadingErrorCode(e.message) : "";

    if (
      /failed to fetch|networkerror|load failed|network request failed/i.test(rawMsg)
    ) {
      if (context?.action === "login") return "暂时无法登录，请稍后重试";
      if (context?.action === "register") return "暂时无法注册，请稍后重试";
      return "网络连接异常，请稍后重试";
    }

    if (code === "VIP_QUOTA_EXCEEDED" || code === "QUOTA_EXCEEDED") {
      if (isSafeUserMessage(rawMsg)) return rawMsg;
      return context?.quotaHint ?? "已超出当前套餐额度，升级会员可解锁更高上限";
    }

    if (code && CODE_MESSAGES[code]) {
      return CODE_MESSAGES[code];
    }

    if (status === 401) {
      return context?.action === "login"
        ? "邮箱或密码错误"
        : "登录状态已过期，请重新登录";
    }
    if (status === 403) {
      return "当前账号没有此操作权限";
    }
    if (status === 404) {
      return "目标内容不存在或已被删除";
    }
    if (status === 409) {
      return isSafeUserMessage(rawMsg) ? rawMsg : "内容已在别处更新，请刷新后重试";
    }
    if (status === 429) {
      return "操作过于频繁，请稍后再试";
    }
    if (status && status >= 500) {
      return fallback;
    }

    if (isSafeUserMessage(rawMsg)) {
      return rawMsg;
    }
  }

  return fallback;
}

export function formatStreamError(raw?: string | null): string {
  if (!raw) return "生成暂时中断，请重试";
  const cleaned = stripLeadingErrorCode(raw);
  if (/登录|过期|unauthorized|401/i.test(cleaned)) {
    return "登录状态已过期，请重新登录";
  }
  if (/积分|余额|insufficient|credit/i.test(cleaned)) {
    return "可用积分不足，请先充值";
  }
  if (/预算|budget/i.test(cleaned)) {
    return "已达本月预算上限，请调整预算后再试";
  }
  if (/频|rate.?limit|429/i.test(cleaned)) {
    return "请求过于频繁，请稍后再试";
  }
  if (/重连|连接|timeout|network|fetch|drop/i.test(cleaned)) {
    return "连接中断，请点击重试";
  }
  if (isSafeUserMessage(cleaned)) {
    return cleaned;
  }
  return "生成暂时中断，请重试";
}

export function formatDocFailureReason(raw?: string | null): string {
  if (!raw) return "解析失败，可点击重试";
  const cleaned = stripLeadingErrorCode(raw);
  if (isSafeUserMessage(cleaned)) {
    return cleaned;
  }
  return "解析失败，可点击重试";
}
