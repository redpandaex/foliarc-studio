export const ADMIN_API_BASE =
  process.env.NEXT_PUBLIC_ADMIN_API_BASE_URL ?? "http://localhost:8081";

const ADMIN_TOKEN_KEY = "foliarc_admin_jwt";

export function getAdminToken(): string | null {
  if (typeof window === "undefined") return null;
  return window.sessionStorage.getItem(ADMIN_TOKEN_KEY);
}

export function setAdminToken(token: string | null): void {
  if (typeof window === "undefined") return;
  if (!token) {
    window.sessionStorage.removeItem(ADMIN_TOKEN_KEY);
  } else {
    window.sessionStorage.setItem(ADMIN_TOKEN_KEY, token);
  }
}

export class AdminApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

async function adminFetch<T>(
  path: string,
  options: RequestInit & { skipAuth?: boolean } = {},
): Promise<T> {
  const { skipAuth, headers, ...rest } = options;
  const h = new Headers(headers ?? {});
  if (!(rest.body instanceof FormData) && !h.has("Content-Type")) {
    h.set("Content-Type", "application/json");
  }
  if (!skipAuth) {
    const token = getAdminToken();
    if (token) {
      h.set("Authorization", `Bearer ${token}`);
    }
  }
  const resp = await fetch(`${ADMIN_API_BASE}${path}`, {
    ...rest,
    headers: h,
  });
  if (resp.status === 204) {
    return undefined as T;
  }
  const text = await resp.text();
  let parsed: Record<string, unknown> | null = null;
  try {
    parsed = text ? (JSON.parse(text) as Record<string, unknown>) : null;
  } catch {
    parsed = null;
  }
  if (!resp.ok) {
    const code = String(parsed?.code ?? `HTTP_${resp.status}`);
    const message = String(parsed?.message ?? (text || resp.statusText));
    throw new AdminApiError(resp.status, code, message);
  }
  return parsed as T;
}

export interface AdminUserProfile {
  id: string;
  email: string;
  username: string;
  role: string;
  createdAt: string | null;
}

export interface AdminAuthResponse {
  accessToken: string;
  expiresIn: number;
  user: AdminUserProfile;
}

export interface AdminOverviewStats {
  totalUsers: number;
  totalBooks: number;
  totalRuns: number;
  failedOutboxEvents: number;
  pendingDocuments: number;
  totalRippleJobs: number;
  pendingProposals: number;
  guardrailBlocksCount: number;
}

export interface AdminUserRow {
  id: string;
  email: string;
  username: string;
  role: string;
  planCode: string;
  balanceCredits: number;
  createdAt: string | null;
}

export interface AdminLibraryDocument {
  id: string;
  bookId: string | null;
  scope: string;
  accessType: string;
  docType: string;
  title: string;
  fileName: string;
  fileSize: number;
  genreCodes?: string;
  genreTags: string;
  readonly: boolean;
  enabled: boolean;
  boundToBook: boolean;
  status: string;
  progress: number;
  chunkCount: number;
  errorMessage: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface DebugRagHit {
  chunk_id?: string;
  parent_chunk_id?: string;
  doc_type?: string;
  scope?: string;
  chapter_no?: number;
  scene_title?: string;
  score?: number;
  content?: string;
}

export interface DebugRagSearchResponse {
  query?: string;
  hits?: DebugRagHit[];
  retrieval_mode?: string;
  [key: string]: unknown;
}

export interface OutboxEventRow {
  id: string;
  aggregateType: string;
  aggregateId: string;
  eventType: string;
  routingKey: string;
  status: string;
  retryCount: number;
  lastError: string | null;
  createdAt: string | null;
  publishedAt: string | null;
}

export interface RippleJobRow {
  id: string;
  bookId: string;
  userId: string;
  sourceChapterNo: number;
  rangeStartChapterNo: number;
  rangeEndChapterNo: number;
  status: string;
  totalCandidates: number;
  patchedCount: number;
  createdAt: string | null;
}

export interface AgentRunRow {
  id: string;
  bookId: string;
  chapterId: string | null;
  userId: string;
  action: string;
  status: string;
  modelAlias: string | null;
  promptTokens: number;
  completionTokens: number;
  cachedTokens: number;
  errorMessage: string | null;
  createdAt: string | null;
  finishedAt: string | null;
}

export interface AgentRunEventRow {
  id: string;
  runId: string;
  seq: number;
  eventType: string;
  agentName: string;
  traceId: string;
  latencyMs: number;
  payloadJson: string;
  createdAt: string | null;
}

export interface AgentCheckpointRow {
  id: string;
  runId: string;
  nodeName: string;
  status: string;
  createdAt: string | null;
}

export interface GuardrailAuditRow {
  id: string;
  userId: string | null;
  bookId: string | null;
  runId: string | null;
  stage: string;
  action: string;
  ruleCode: string;
  detail: string;
  createdAt: string | null;
}

export interface AgentRunTraceDetail {
  run: AgentRunRow;
  events: AgentRunEventRow[];
  checkpoints: AgentCheckpointRow[];
  guardrailLogs: GuardrailAuditRow[];
}

export interface BillingModelBreakdown {
  modelAlias: string;
  runsCount: number;
  totalInputTokens: number;
  totalCachedTokens: number;
  totalOutputTokens: number;
  totalSpentCredits: number;
}

export interface BillingLedgerRow {
  id: string;
  requestId: string;
  entryType: string;
  modelAlias: string | null;
  inputTokens: number;
  cachedTokens: number;
  outputTokens: number;
  amountCredits: number;
  balanceAfterCredits: number;
  description: string;
  createdAt: string | null;
}

export interface AdminBillingOverview {
  totalAccounts: number;
  totalBalanceCredits: number;
  totalMonthlySpentCredits: number;
  totalCacheSavedCredits: number;
  modelBreakdown: BillingModelBreakdown[];
  recentLedger: BillingLedgerRow[];
}

export interface VipEntitlements {
  rippleMaxRangeChapters: number;
  rippleAllowFullBook: boolean;
  rippleMaxCandidates: number;
  rippleMaxPatchChapters: number;
  rippleDailyScanLimit: number;
  maxMemoryChapters: number;
  maxSectionChars: number;
  maxStyleAnchors: number;
  maxForeshadowings: number;
  userLibraryMaxDocs: number;
  userLibraryMaxTotalBytes: number;
  rpmLimit: number;
  tpmLimit: number;
  priorityQueue: boolean;
}

export interface VipPlanItem {
  planCode: string;
  displayName: string;
  monthlyPriceCents: number;
  monthlyPriceCredits: number;
  monthlyGrantMicros: number;
  monthlyGrantCredits: number;
  creditDiscountBps: number;
  enabled: boolean;
  sortOrder: number;
  entitlements: VipEntitlements;
}

export interface PromptTemplateRow {
  id: string;
  name: string;
  stage: string;
  version: number;
  status: string;
  description: string;
  templateBody: string;
  createdAt: string | null;
}

export interface AdminAuditLogRow {
  id: string;
  operatorId: string;
  operatorName: string;
  action: string;
  targetType: string;
  targetId: string;
  beforeJson: string | null;
  afterJson: string | null;
  clientIp: string;
  traceId: string;
  createdAt: string | null;
}

export const adminAuthApi = {
  login: (email: string, password: string) =>
    adminFetch<AdminAuthResponse>("/api/v1/admin/auth/login", {
      method: "POST",
      skipAuth: true,
      body: JSON.stringify({ email, password }),
    }),
  me: () => adminFetch<AdminUserProfile>("/api/v1/admin/auth/me"),
  logout: () => setAdminToken(null),
};

export const adminOverviewApi = {
  overview: () => adminFetch<AdminOverviewStats>("/api/v1/admin/overview"),
  listUsers: () => adminFetch<AdminUserRow[]>("/api/v1/admin/users"),
};

export const adminKnowledgeApi = {
  list: () => adminFetch<AdminLibraryDocument[]>("/api/v1/admin/knowledge/documents"),
  upload: (payload: {
    file: File;
    docType: string;
    title?: string;
    genreCodes?: string;
    genreTags?: string;
    publicToAll: boolean;
  }) => {
    const fd = new FormData();
    fd.append("file", payload.file);
    fd.append("docType", payload.docType);
    if (payload.title) fd.append("title", payload.title);
    if (payload.genreCodes) fd.append("genreCodes", payload.genreCodes);
    if (payload.genreTags) fd.append("genreTags", payload.genreTags);
    fd.append("publicToAll", String(payload.publicToAll));
    return adminFetch<AdminLibraryDocument>("/api/v1/admin/knowledge/documents", {
      method: "POST",
      body: fd,
    });
  },
  remove: (docId: string) =>
    adminFetch<void>(`/api/v1/admin/knowledge/documents/${docId}`, {
      method: "DELETE",
    }),
  grant: (docId: string, granteeUserId: string, note?: string) =>
    adminFetch<void>(`/api/v1/admin/knowledge/documents/${docId}/grants`, {
      method: "POST",
      body: JSON.stringify({ granteeUserId, permission: "READ", note: note ?? "" }),
    }),
  revoke: (docId: string, userId: string) =>
    adminFetch<void>(`/api/v1/admin/knowledge/documents/${docId}/grants/${userId}`, {
      method: "DELETE",
    }),
  debugSearch: (payload: {
    ownerId: string;
    bookId: string;
    query: string;
    topK?: number;
    maxChapterNo?: number;
  }) =>
    adminFetch<DebugRagSearchResponse>("/api/v1/admin/knowledge/debug-search", {
      method: "POST",
      body: JSON.stringify(payload),
    }),
};

export const adminJobsApi = {
  listOutbox: (status?: string, limit = 50) => {
    const qs = new URLSearchParams({ limit: String(limit) });
    if (status) qs.set("status", status);
    return adminFetch<OutboxEventRow[]>(`/api/v1/admin/jobs/outbox?${qs.toString()}`);
  },
  retryOutbox: (eventId: string) =>
    adminFetch<OutboxEventRow>(`/api/v1/admin/jobs/outbox/${eventId}/retry`, {
      method: "POST",
    }),
  listDocuments: (status?: string, limit = 50) => {
    const qs = new URLSearchParams({ limit: String(limit) });
    if (status) qs.set("status", status);
    return adminFetch<AdminLibraryDocument[]>(`/api/v1/admin/jobs/documents?${qs.toString()}`);
  },
  retryDocument: (docId: string) =>
    adminFetch<AdminLibraryDocument>(`/api/v1/admin/jobs/documents/${docId}/retry`, {
      method: "POST",
    }),
  listRipples: (limit = 30) =>
    adminFetch<RippleJobRow[]>(`/api/v1/admin/jobs/ripples?limit=${limit}`),
};

export const adminTracesApi = {
  listRuns: (status?: string, limit = 30) => {
    const qs = new URLSearchParams({ limit: String(limit) });
    if (status) qs.set("status", status);
    return adminFetch<AgentRunRow[]>(`/api/v1/admin/traces/runs?${qs.toString()}`);
  },
  getRunTrace: (runId: string) =>
    adminFetch<AgentRunTraceDetail>(`/api/v1/admin/traces/runs/${runId}`),
  listGuardrails: (limit = 50) =>
    adminFetch<GuardrailAuditRow[]>(`/api/v1/admin/traces/guardrails?limit=${limit}`),
};

export const adminBillingApi = {
  overview: () => adminFetch<AdminBillingOverview>("/api/v1/admin/billing/overview"),
  listVipPlans: () => adminFetch<VipPlanItem[]>("/api/v1/admin/vip/plans"),
  updateVipPlan: (
    planCode: string,
    payload: {
      displayName?: string;
      monthlyPriceCents?: number;
      monthlyPriceCredits?: number;
      monthlyGrantCredits?: number;
      creditDiscountBps?: number;
      active?: boolean;
      entitlements?: Partial<VipEntitlements>;
    },
  ) =>
    adminFetch<VipPlanItem>(`/api/v1/admin/vip/plans/${planCode}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    }),
  grantVip: (payload: {
    userId: string;
    planCode: string;
    durationDays: number;
    grantBonusCredits?: boolean;
    idempotencyKey?: string;
  }) =>
    adminFetch<Record<string, unknown>>("/api/v1/admin/vip/grant", {
      method: "POST",
      body: JSON.stringify(payload),
    }),
};

export const adminPromptsApi = {
  list: () => adminFetch<PromptTemplateRow[]>("/api/v1/admin/prompts"),
  publish: (payload: {
    name: string;
    stage: string;
    description: string;
    templateBody: string;
  }) =>
    adminFetch<PromptTemplateRow>("/api/v1/admin/prompts", {
      method: "POST",
      body: JSON.stringify(payload),
    }),
  activate: (promptId: string) =>
    adminFetch<PromptTemplateRow>(`/api/v1/admin/prompts/${promptId}/activate`, {
      method: "POST",
    }),
};

export const adminAuditApi = {
  list: (limit = 100) =>
    adminFetch<AdminAuditLogRow[]>(`/api/v1/admin/audit?limit=${limit}`),
};

export interface EvalCaseRow {
  case_id: string;
  category: string;
  passed: boolean;
  score: number;
  detail: string;
  latency_ms: number;
}

export interface EvalReportResponse {
  total_cases: number;
  passed_cases: number;
  overall_pass_rate: number;
  category_scores: Record<string, number>;
  duration_ms: number;
  cases: EvalCaseRow[];
}

export const adminEvalApi = {
  run: () =>
    adminFetch<EvalReportResponse>("/api/v1/admin/evals/run", {
      method: "POST",
    }),
};
