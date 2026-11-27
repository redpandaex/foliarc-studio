export interface ErrorContext {
  action?: "login" | "load" | "save" | "delete" | "upload" | "retry" | "search" | "eval" | "action";
}

interface StructuredErrorLike {
  status?: number;
  code?: string;
  message?: string;
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
  ADMIN_REQUIRED: "当前账号没有管理员权限",
  SELF_DEMOTION_FORBIDDEN: "不能取消当前登录账号的管理员权限",
  CANNOT_DEMOTE_SELF: "不能取消当前登录账号的管理员权限",
  EMAIL_ALREADY_EXISTS: "该邮箱已被注册",
  DUPLICATE_EMAIL: "该邮箱已被注册",
  USER_ALREADY_EXISTS: "该邮箱已被注册",
  INSUFFICIENT_CREDITS: "可用积分不足",
  INSUFFICIENT_BALANCE: "可用积分不足",
  VERSION_CONFLICT: "内容已在别处更新，请刷新后重试",
  CONFLICT: "数据状态已变化，请刷新后重试",
  NOT_FOUND: "目标记录不存在或已被删除",
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

export function formatAdminError(
  err: unknown,
  fallback: string,
  context?: ErrorContext,
): string {
  if (err !== undefined && err !== null) {
    console.error("[FoliarcAdmin]", err);
  }

  if (typeof err === "object" && err !== null) {
    const e = err as StructuredErrorLike;
    const code = typeof e.code === "string" ? e.code.trim().toUpperCase() : "";
    const status = typeof e.status === "number" ? e.status : undefined;
    const rawMsg = typeof e.message === "string" ? stripLeadingErrorCode(e.message) : "";

    if (
      /failed to fetch|networkerror|load failed|network request failed/i.test(rawMsg)
    ) {
      return context?.action === "login"
        ? "暂时无法登录，请稍后重试"
        : "网络连接异常，请稍后重试";
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
      return "目标记录不存在或已被删除";
    }
    if (status === 409) {
      return isSafeUserMessage(rawMsg) ? rawMsg : "数据状态已变化，请刷新后重试";
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

export function formatJobFailureReason(raw?: string | null, fallback = "处理失败"): string {
  if (!raw) return fallback;
  const cleaned = stripLeadingErrorCode(raw);
  if (isSafeUserMessage(cleaned)) {
    return cleaned;
  }
  return fallback;
}
