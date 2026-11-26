export interface UserProfile {
  id: string;
  email: string;
  username: string;
  role: string;
  createdAt: string | null;
}

export interface AuthResponse {
  accessToken: string;
  expiresIn: number;
  user: UserProfile;
}

export interface GenreCharacterField {
  key: string;
  label: string;
  placeholder: string;
}

export interface GenreProfileItem {
  code: string;
  displayName: string;
  shortName: string;
  description: string;
  recommendedSubTags: string[];
  characterFieldSchema: GenreCharacterField[];
  stylePrinciples: string;
  antiClicheRules: string[];
  defaultBeatPattern: string;
  criticRubric: string[];
  summaryFocus: string;
}

export interface StyleAnchorItem {
  id: string;
  bookId: string;
  title: string;
  excerptText: string;
  sortOrder: number;
  enabled: boolean;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface BookItem {
  id: string;
  title: string;
  genre: string | null;
  genreCode?: string;
  subGenreTags?: string;
  synopsis: string | null;
  status: string;
  version: number;
  chapterCount: number;
  totalWordCount: number;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface BookCharterItem {
  bookId: string;
  authorCharter: string | null;
  autoCharter: string | null;
  charterSource: "NONE" | "AUTO_INFERRED" | "AUTHOR_DEFINED" | string;
  charterChapterCount: number;
  effectiveCharter: string;
}

export interface LibraryDocumentItem {
  id: string;
  bookId: string | null;
  scope: "BOOK" | "USER" | "GLOBAL" | string;
  accessType: "USER" | "ASSIGNED" | "GLOBAL" | string;
  docType: KnowledgeDocType;
  title: string;
  fileName: string;
  fileSize: number;
  genreCodes?: string;
  genreTags: string;
  readonly: boolean;
  enabled: boolean;
  boundToBook: boolean;
  status: "QUEUED" | "PARSING" | "CHUNKING" | "EMBEDDING" | "READY" | "FAILED" | "DELETED";
  progress: number;
  chunkCount: number;
  errorMessage: string | null;
  createdAt: string | null;
  updatedAt: string | null;
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
  planCode: "FREE" | "VIP" | "SVIP" | "STUDIO" | string;
  displayName: string;
  monthlyPriceCents: number;
  monthlyPriceCredits: number;
  monthlyGrantMicros: number;
  monthlyGrantCredits: number;
  creditDiscountBps: number;
  entitlements: VipEntitlements;
  enabled: boolean;
  sortOrder: number;
}

export interface LibraryQuotaUsage {
  usedDocs: number;
  maxDocs: number;
  usedBytes: number;
  maxTotalBytes: number;
}

export interface MyVipStatus {
  planCode: "FREE" | "VIP" | "SVIP" | "STUDIO" | string;
  displayName: string;
  status: string;
  startedAt: string | null;
  expiresAt: string | null;
  creditDiscountBps: number;
  balanceCredits: number;
  rippleScansUsedToday: number;
  rippleDailyScanLimit: number;
  libraryUsage: LibraryQuotaUsage;
  entitlements: VipEntitlements;
}

export interface SubscribeVipResult {
  orderId: string;
  planCode: string;
  displayName: string;
  durationDays: number;
  chargedCredits: number;
  grantedBonusCredits: number;
  expiresAt: string | null;
  idempotentReplay: boolean;
}

export interface ChapterSummary {
  id: string;
  bookId: string;
  chapterNo: number;
  title: string;
  wordCount: number;
  status: string;
  version: number;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface ChapterDetail extends ChapterSummary {
  content: string;
}

export interface SectionSummaryItem {
  id: string;
  chapterId: string;
  chapterNo: number;
  sectionNo: number;
  narrativeOrder: number;
  title: string;
  wordCount: number;
  isDefault: boolean;
  summaryDirty: boolean;
  version: number;
}

export interface SectionDetail extends SectionSummaryItem {
  bookId: string;
  content: string;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface ChapterTreeItem {
  id: string;
  chapterNo: number;
  volumeId: string | null;
  title: string;
  structureMode: "SINGLE" | "MULTI_SECTION" | string;
  wordCount: number;
  status: string;
  version: number;
  sections: SectionSummaryItem[];
}

export interface VolumeTreeItem {
  id: string | null;
  volumeNo: number;
  title: string;
  arcGoal: string;
  pacingNotes: string;
  realmScope: string;
  status: string;
  chapters: ChapterTreeItem[];
}

export interface BookTreeResponse {
  bookId: string;
  precisionMode: string;
  effectivePrecisionMode: "PRECISE" | "EXPEDITION" | "EPIC" | string;
  totalUnits: number;
  volumes: VolumeTreeItem[];
}

export interface FullChapterPreview {
  chapterId: string;
  chapterNo: number;
  title: string;
  structureMode: string;
  totalWordCount: number;
  sectionCount: number;
  combinedContent: string;
}

export interface ChapterVersion {
  id: string;
  chapterId: string;
  versionNo: number;
  content: string;
  changeSummary: string | null;
  createdBy: string | null;
  createdAt: string | null;
}

export type RunAction = "CONTINUE" | "REWRITE" | "OUTLINE";
export type RunMode = "AGENT" | "DIRECT" | "MULTI_AGENT";

export interface AgentRunItem {
  id: string;
  bookId: string;
  chapterId: string | null;
  mode: RunMode;
  action: RunAction;
  modelAlias: string;
  instruction: string;
  status: "QUEUED" | "RUNNING" | "PAUSED" | "COMPLETED" | "CANCELLED" | "FAILED";
  accumulatedText: string;
  lastSeq: number;
  lastEventId: string | null;
  inputTokens: number;
  cachedTokens: number;
  outputTokens: number;
  errorMessage: string | null;
  currentNode?: string | null;
  interruptType?: string | null;
  interruptPayloadJson?: string | null;
  routeIntent?: string | null;
  revisionCount?: number;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface AgentCheckpointItem {
  id: string;
  runId: string;
  stepNo: number;
  nodeName: string;
  status: string;
  stateJson: string;
  createdAt: string | null;
}

export interface CharacterItem {
  id: string;
  bookId: string;
  name: string;
  roleType: string;
  personality: string;
  realmOrLevel: string;
  currentLocation: string;
  statusNotes: string;
  inventory: string;
  aliases?: string;
  lifecycleStatus?: "CORE_PINNED" | "ACTIVE" | "DORMANT" | "DEAD" | string;
  lastSeenOrder?: number;
  arcScope?: string;
  lockedFields?: string[];
  customAttributes?: Record<string, string>;
  updatedAt: string | null;
}

export interface WorldEntryItem {
  id: string;
  bookId: string;
  category: string;
  title: string;
  content: string;
  tags: string;
  updatedAt: string | null;
}

export interface ForeshadowingItem {
  id: string;
  bookId: string;
  code?: string;
  title: string;
  description: string;
  importance?: "MAIN_ARC" | "SUBPLOT" | "DETAIL" | string;
  revealPolicy?: "HIDE_TRUTH" | "HINT_ONLY" | "REVEAL_NOW" | string;
  surfaceClue?: string;
  hiddenTruth?: string;
  plantedChapterNo: number | null;
  resolvedChapterNo: number | null;
  plantedSectionId?: string | null;
  plantedNarrativeOrder?: number | null;
  plantedQuote?: string;
  resolvedSectionId?: string | null;
  targetChapterMin?: number | null;
  targetChapterMax?: number | null;
  involvedCharacters?: string;
  hintHistoryJson?: string;
  source?: string;
  authorLocked?: boolean;
  status: string;
  updatedAt: string | null;
}

export interface DueForeshadowingsResponse {
  chapterNo: number;
  dueNow: ForeshadowingItem[];
  readyToPlant: ForeshadowingItem[];
  activePlanted: ForeshadowingItem[];
}

export interface ForeshadowingDirective {
  foreshadowingId: string;
  mode: "PLANT" | "HINT" | "PAYOFF";
  resolveType?: "FULL_RESOLVE" | "PARTIAL_REVEAL";
}

export interface MemoryProposalItem {
  id: string;
  bookId: string;
  sectionId: string | null;
  proposalType: "CHARACTER_DEATH" | "FORESHADOWING_RESOLVE" | "CORE_IDENTITY_SHIFT" | string;
  targetEntityId: string | null;
  targetName: string;
  summary: string;
  diffJson: string;
  status: "PENDING" | "ACCEPTED" | "REJECTED" | string;
  createdAt: string | null;
  resolvedAt: string | null;
}

export interface NovelBible {
  bookId: string;
  bookTitle: string;
  genre: string | null;
  synopsis: string | null;
  characters: CharacterItem[];
  worldEntries: WorldEntryItem[];
  openForeshadowings: ForeshadowingItem[];
}

export type KnowledgeDocType = "WORLDBOOK" | "CHAPTER" | "STYLE_REF" | "OUTLINE";

export interface KnowledgeDocumentItem {
  id: string;
  bookId: string;
  sourceType: "UPLOAD" | "CHAPTER";
  sourceChapterId: string | null;
  chapterNo: number;
  docType: KnowledgeDocType;
  title: string;
  fileName: string;
  contentType: string;
  fileSize: number;
  docVersion: number;
  status: "QUEUED" | "PARSING" | "CHUNKING" | "EMBEDDING" | "READY" | "FAILED" | "DELETED";
  progress: number;
  chunkCount: number;
  errorMessage: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface RagSearchHit {
  parent_chunk_id: string;
  document_id: string;
  document_title: string;
  doc_type: string;
  chapter_no: number;
  scene_title: string;
  score: number;
  matched_snippet: string;
  parent_context: string;
}

export interface RagSearchResult {
  query: string;
  expanded_queries?: string[];
  max_chapter_no: number | null;
  embedding_mode: string;
  rerank_mode: string;
  hits: RagSearchHit[];
}

export interface CharacterRelationItem {
  id: string;
  bookId: string;
  sourceCharacter: string;
  targetCharacter: string;
  relationType: string;
  description: string;
  sinceChapterNo: number | null;
  confidence: number;
  updatedAt: string | null;
}

export interface TimelineEventItem {
  id: string;
  bookId: string;
  chapterNo: number;
  eventTitle: string;
  summary: string;
  involvedCharacters: string;
  location: string;
  updatedAt: string | null;
}

export interface StructuredMemorySnapshot {
  bookId: string;
  characters: CharacterItem[];
  foreshadowings: ForeshadowingItem[];
  relations: CharacterRelationItem[];
  timelineEvents: TimelineEventItem[];
}

export interface BillingAccountSummary {
  userId: string;
  balanceMicros: number;
  balanceCredits: number;
  frozenMicros: number;
  frozenCredits: number;
  monthlyBudgetMicros: number;
  monthlyBudgetCredits: number;
  monthlySpentMicros: number;
  monthlySpentCredits: number;
  budgetMonth: string;
  budgetUsageRatio: number;
}

export interface BillingLedgerEntry {
  id: string;
  runId: string | null;
  requestId: string;
  entryType: "GRANT" | "TOPUP" | "FREEZE" | "SETTLE" | "RELEASE" | string;
  amountMicros: number;
  amountCredits: number;
  balanceAfterMicros: number;
  balanceAfterCredits: number;
  frozenAfterMicros: number;
  modelAlias: string | null;
  inputTokens: number;
  cachedTokens: number;
  outputTokens: number;
  cacheSavedMicros: number;
  cacheSavedCredits: number;
  sandboxCalls: number;
  description: string;
  createdAt: string | null;
}

export interface ModelCostStat {
  modelAlias: string;
  runsCount: number;
  totalSpentMicros: number;
  totalSpentCredits: number;
  totalInputTokens: number;
  totalCachedTokens: number;
  totalOutputTokens: number;
  totalCacheSavedMicros: number;
}

export interface GatewayStatusInfo {
  circuitBreakerState: string;
  circuitBreakerFailureRate: number;
  bulkheadAvailableCalls: number;
  bulkheadMaxConcurrentCalls: number;
  rpmLimit: number;
  tpmLimit: number;
}

export interface BillingDashboardData {
  account: BillingAccountSummary;
  totalCacheSavedCredits: number;
  cacheHitRatio: number;
  modelBreakdown: ModelCostStat[];
  gateway: GatewayStatusInfo;
  recentLedger: BillingLedgerEntry[];
}

export class ApiClientError extends Error {
  status: number;
  code: string;
  details: Record<string, string>;

  constructor(
    status: number,
    code: string,
    message: string,
    details: Record<string, string> = {},
  ) {
    super(message);
    this.name = "ApiClientError";
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

const API_BASE =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

let memoryAccessToken: string | null = null;
let inflightRefresh: Promise<AuthResponse | null> | null = null;

export function setAccessToken(token: string | null) {
  memoryAccessToken = token;
}

export function getAccessToken(): string | null {
  return memoryAccessToken;
}

async function parseError(res: Response): Promise<ApiClientError> {
  try {
    const data = await res.json();
    return new ApiClientError(
      res.status,
      data.code ?? "HTTP_ERROR",
      data.message ?? `请求失败 (${res.status})`,
      data.details ?? {},
    );
  } catch {
    return new ApiClientError(
      res.status,
      "HTTP_ERROR",
      `请求失败 (${res.status})`,
    );
  }
}

/**
 * Single-flight Refresh Token rotation over HttpOnly cookie.
 * Deduplicates concurrent calls (including React StrictMode double effects)
 * so rotated tokens are never replayed accidentally.
 */
export async function refreshSession(): Promise<AuthResponse | null> {
  if (inflightRefresh) {
    return inflightRefresh;
  }
  inflightRefresh = (async () => {
    try {
      const res = await fetch(`${API_BASE}/api/v1/auth/refresh`, {
        method: "POST",
        credentials: "include",
      });
      if (!res.ok) {
        setAccessToken(null);
        return null;
      }
      const data: AuthResponse = await res.json();
      setAccessToken(data.accessToken);
      return data;
    } catch {
      setAccessToken(null);
      return null;
    } finally {
      inflightRefresh = null;
    }
  })();
  return inflightRefresh;
}

async function request<T>(
  path: string,
  init: RequestInit = {},
  retryOnUnauthorized = true,
): Promise<T> {
  const headers = new Headers(init.headers);
  if (
    init.body &&
    !(init.body instanceof FormData) &&
    !headers.has("Content-Type")
  ) {
    headers.set("Content-Type", "application/json");
  }
  if (memoryAccessToken) {
    headers.set("Authorization", `Bearer ${memoryAccessToken}`);
  }

  const res = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers,
    credentials: "include",
  });

  if (res.status === 401 && retryOnUnauthorized) {
    const refreshed = await refreshSession();
    if (refreshed) {
      return request<T>(path, init, false);
    }
  }

  if (!res.ok) {
    throw await parseError(res);
  }
  if (res.status === 204) {
    return undefined as T;
  }
  return (await res.json()) as T;
}

export const authApi = {
  async register(payload: {
    email: string;
    username: string;
    password: string;
  }): Promise<AuthResponse> {
    const data = await request<AuthResponse>(
      "/api/v1/auth/register",
      { method: "POST", body: JSON.stringify(payload) },
      false,
    );
    setAccessToken(data.accessToken);
    return data;
  },

  async login(payload: {
    email: string;
    password: string;
  }): Promise<AuthResponse> {
    const data = await request<AuthResponse>(
      "/api/v1/auth/login",
      { method: "POST", body: JSON.stringify(payload) },
      false,
    );
    setAccessToken(data.accessToken);
    return data;
  },

  async logout(): Promise<void> {
    try {
      await request<void>("/api/v1/auth/logout", { method: "POST" }, false);
    } finally {
      setAccessToken(null);
    }
  },

  me(): Promise<UserProfile> {
    return request<UserProfile>("/api/v1/auth/me");
  },
};

export const booksApi = {
  list(): Promise<BookItem[]> {
    return request<BookItem[]>("/api/v1/books");
  },
  get(bookId: string): Promise<BookItem> {
    return request<BookItem>(`/api/v1/books/${bookId}`);
  },
  create(payload: {
    title: string;
    genre?: string;
    genreCode?: string;
    subGenreTags?: string;
    synopsis?: string;
  }): Promise<BookItem> {
    return request<BookItem>("/api/v1/books", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  update(
    bookId: string,
    payload: {
      title: string;
      genre?: string | null;
      genreCode?: string;
      subGenreTags?: string;
      synopsis?: string | null;
      status?: string;
      version: number;
    },
  ): Promise<BookItem> {
    return request<BookItem>(`/api/v1/books/${bookId}`, {
      method: "PATCH",
      body: JSON.stringify(payload),
    });
  },
  remove(bookId: string): Promise<void> {
    return request<void>(`/api/v1/books/${bookId}`, { method: "DELETE" });
  },
  async exportBook(
    bookId: string,
    format: "epub" | "md" | "txt",
    fallbackTitle = "book",
  ): Promise<void> {
    async function fetchBlob(retry: boolean): Promise<Response> {
      const headers = new Headers();
      if (memoryAccessToken) {
        headers.set("Authorization", `Bearer ${memoryAccessToken}`);
      }
      const res = await fetch(
        `${API_BASE}/api/v1/books/${bookId}/export?format=${format}`,
        { headers, credentials: "include" },
      );
      if (res.status === 401 && retry) {
        const refreshed = await refreshSession();
        if (refreshed) return fetchBlob(false);
      }
      return res;
    }
    const res = await fetchBlob(true);
    if (!res.ok) {
      throw await parseError(res);
    }
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    const safeTitle = fallbackTitle.replace(/[\\/:*?"<>|]+/g, "_").trim() || "book";
    a.href = url;
    a.download = `${safeTitle}.${format}`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  },
  getCharter(bookId: string): Promise<BookCharterItem> {
    return request<BookCharterItem>(`/api/v1/books/${bookId}/charter`);
  },
  updateCharter(bookId: string, charter: string): Promise<BookCharterItem> {
    return request<BookCharterItem>(`/api/v1/books/${bookId}/charter`, {
      method: "PUT",
      body: JSON.stringify({ charter }),
    });
  },
};

export const genresApi = {
  list(): Promise<GenreProfileItem[]> {
    return request<GenreProfileItem[]>("/api/v1/genres", {}, false);
  },
  get(genreCode: string): Promise<GenreProfileItem> {
    return request<GenreProfileItem>(
      `/api/v1/genres/${encodeURIComponent(genreCode)}`,
      {},
      false,
    );
  },
};

export const styleAnchorsApi = {
  list(bookId: string): Promise<StyleAnchorItem[]> {
    return request<StyleAnchorItem[]>(`/api/v1/books/${bookId}/style-anchors`);
  },
  upsert(
    bookId: string,
    payload: {
      id?: string;
      title?: string;
      excerptText: string;
      sortOrder?: number;
      enabled?: boolean;
    },
  ): Promise<StyleAnchorItem> {
    return request<StyleAnchorItem>(`/api/v1/books/${bookId}/style-anchors`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  remove(bookId: string, anchorId: string): Promise<void> {
    return request<void>(`/api/v1/books/${bookId}/style-anchors/${anchorId}`, {
      method: "DELETE",
    });
  },
};

export const libraryApi = {
  list(bookId?: string): Promise<LibraryDocumentItem[]> {
    const query = bookId ? `?bookId=${encodeURIComponent(bookId)}` : "";
    return request<LibraryDocumentItem[]>(`/api/v1/library/documents${query}`);
  },
  upload(payload: {
    file: File;
    docType?: KnowledgeDocType;
    title?: string;
    genreTags?: string;
  }): Promise<LibraryDocumentItem> {
    const form = new FormData();
    form.append("file", payload.file);
    const params = new URLSearchParams();
    if (payload.docType) params.set("docType", payload.docType);
    if (payload.title?.trim()) params.set("title", payload.title.trim());
    if (payload.genreTags?.trim()) params.set("genreTags", payload.genreTags.trim());
    return request<LibraryDocumentItem>(
      `/api/v1/library/documents?${params.toString()}`,
      { method: "POST", body: form },
    );
  },
  remove(docId: string): Promise<void> {
    return request<void>(`/api/v1/library/documents/${docId}`, {
      method: "DELETE",
    });
  },
  setBookBinding(
    bookId: string,
    docId: string,
    enabled: boolean,
  ): Promise<LibraryDocumentItem> {
    return request<LibraryDocumentItem>(
      `/api/v1/books/${bookId}/library/${docId}/binding`,
      {
        method: "PUT",
        body: JSON.stringify({ enabled }),
      },
    );
  },
};

export const vipApi = {
  listPlans(): Promise<VipPlanItem[]> {
    return request<VipPlanItem[]>("/api/v1/vip/plans", {}, false);
  },
  me(): Promise<MyVipStatus> {
    return request<MyVipStatus>("/api/v1/vip/me");
  },
  subscribe(payload: {
    planCode: string;
    months?: number;
    paymentChannel?: string;
    idempotencyKey?: string;
  }): Promise<SubscribeVipResult> {
    return request<SubscribeVipResult>("/api/v1/vip/subscribe", {
      method: "POST",
      body: JSON.stringify({
        planCode: payload.planCode,
        months: payload.months ?? 1,
        paymentChannel: payload.paymentChannel ?? "CREDITS",
        idempotencyKey: payload.idempotencyKey,
      }),
    });
  },
};

export const chaptersApi = {
  list(bookId: string): Promise<ChapterSummary[]> {
    return request<ChapterSummary[]>(`/api/v1/books/${bookId}/chapters`);
  },
  get(bookId: string, chapterId: string): Promise<ChapterDetail> {
    return request<ChapterDetail>(
      `/api/v1/books/${bookId}/chapters/${chapterId}`,
    );
  },
  create(
    bookId: string,
    payload: { title: string; chapterNo?: number; content?: string },
  ): Promise<ChapterDetail> {
    return request<ChapterDetail>(`/api/v1/books/${bookId}/chapters`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  update(
    bookId: string,
    chapterId: string,
    payload: {
      title: string;
      content: string;
      status?: string;
      version: number;
      createSnapshot?: boolean;
      changeSummary?: string;
    },
  ): Promise<ChapterDetail> {
    return request<ChapterDetail>(
      `/api/v1/books/${bookId}/chapters/${chapterId}`,
      {
        method: "PUT",
        body: JSON.stringify({
          ...payload,
          createSnapshot: payload.createSnapshot ?? false,
        }),
      },
    );
  },
  remove(bookId: string, chapterId: string): Promise<void> {
    return request<void>(`/api/v1/books/${bookId}/chapters/${chapterId}`, {
      method: "DELETE",
    });
  },
  listVersions(bookId: string, chapterId: string): Promise<ChapterVersion[]> {
    return request<ChapterVersion[]>(
      `/api/v1/books/${bookId}/chapters/${chapterId}/versions`,
    );
  },
  createSnapshot(
    bookId: string,
    chapterId: string,
    changeSummary?: string,
  ): Promise<ChapterVersion> {
    return request<ChapterVersion>(
      `/api/v1/books/${bookId}/chapters/${chapterId}/versions`,
      {
        method: "POST",
        body: JSON.stringify({ changeSummary }),
      },
    );
  },
  rollback(
    bookId: string,
    chapterId: string,
    versionId: string,
    currentVersion: number,
  ): Promise<ChapterDetail> {
    return request<ChapterDetail>(
      `/api/v1/books/${bookId}/chapters/${chapterId}/versions/${versionId}/rollback`,
      {
        method: "POST",
        body: JSON.stringify({ version: currentVersion }),
      },
    );
  },
};

export function getRunStreamUrl(runId: string): string {
  return `${API_BASE}/api/v1/runs/${runId}/stream`;
}

export const structureApi = {
  getTree(bookId: string): Promise<BookTreeResponse> {
    return request<BookTreeResponse>(`/api/v1/books/${bookId}/tree`);
  },
  createVolume(
    bookId: string,
    payload: { title: string; arcGoal?: string; pacingNotes?: string; realmScope?: string },
  ): Promise<VolumeTreeItem> {
    return request<VolumeTreeItem>(`/api/v1/books/${bookId}/volumes`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateVolume(
    bookId: string,
    volumeId: string,
    payload: {
      title?: string;
      arcGoal?: string;
      pacingNotes?: string;
      realmScope?: string;
      status?: string;
    },
  ): Promise<VolumeTreeItem> {
    return request<VolumeTreeItem>(`/api/v1/books/${bookId}/volumes/${volumeId}`, {
      method: "PATCH",
      body: JSON.stringify(payload),
    });
  },
  deleteVolume(bookId: string, volumeId: string): Promise<void> {
    return request<void>(`/api/v1/books/${bookId}/volumes/${volumeId}`, {
      method: "DELETE",
    });
  },
  moveChapterVolume(
    bookId: string,
    chapterId: string,
    volumeId: string | null,
  ): Promise<void> {
    return request<void>(`/api/v1/books/${bookId}/chapters/${chapterId}/volume`, {
      method: "PATCH",
      body: JSON.stringify({ volumeId }),
    });
  },
  createSection(
    bookId: string,
    chapterId: string,
    payload: { title?: string; content?: string },
  ): Promise<SectionDetail> {
    return request<SectionDetail>(
      `/api/v1/books/${bookId}/chapters/${chapterId}/sections`,
      {
        method: "POST",
        body: JSON.stringify(payload),
      },
    );
  },
  getSection(
    bookId: string,
    chapterId: string,
    sectionId: string,
  ): Promise<SectionDetail> {
    return request<SectionDetail>(
      `/api/v1/books/${bookId}/chapters/${chapterId}/sections/${sectionId}`,
    );
  },
  updateSection(
    bookId: string,
    chapterId: string,
    sectionId: string,
    payload: {
      title: string;
      content: string;
      version: number;
      createSnapshot?: boolean;
      changeSummary?: string;
    },
  ): Promise<SectionDetail> {
    return request<SectionDetail>(
      `/api/v1/books/${bookId}/chapters/${chapterId}/sections/${sectionId}`,
      {
        method: "PUT",
        body: JSON.stringify({
          ...payload,
          createSnapshot: payload.createSnapshot ?? false,
        }),
      },
    );
  },
  deleteSection(
    bookId: string,
    chapterId: string,
    sectionId: string,
  ): Promise<void> {
    return request<void>(
      `/api/v1/books/${bookId}/chapters/${chapterId}/sections/${sectionId}`,
      { method: "DELETE" },
    );
  },
  getFullPreview(bookId: string, chapterId: string): Promise<FullChapterPreview> {
    return request<FullChapterPreview>(
      `/api/v1/books/${bookId}/chapters/${chapterId}/full-preview`,
    );
  },
};

export const runsApi = {
  start(payload: {
    bookId: string;
    chapterId?: string;
    sectionId?: string;
    mode?: RunMode;
    action: RunAction;
    modelAlias?: string;
    instruction?: string;
    chapterContext?: string;
    requireOutlineApproval?: boolean;
    povCharacter?: string;
    foreshadowingDirectives?: ForeshadowingDirective[];
  }): Promise<AgentRunItem> {
    return request<AgentRunItem>("/api/v1/runs", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  get(runId: string): Promise<AgentRunItem> {
    return request<AgentRunItem>(`/api/v1/runs/${runId}`);
  },
  listCheckpoints(runId: string): Promise<AgentCheckpointItem[]> {
    return request<AgentCheckpointItem[]>(`/api/v1/runs/${runId}/checkpoints`);
  },
  list(bookId: string, chapterId?: string): Promise<AgentRunItem[]> {
    const query = new URLSearchParams({ bookId });
    if (chapterId) {
      query.set("chapterId", chapterId);
    }
    return request<AgentRunItem[]>(`/api/v1/runs?${query.toString()}`);
  },
  pause(runId: string): Promise<AgentRunItem> {
    return request<AgentRunItem>(`/api/v1/runs/${runId}/pause`, {
      method: "POST",
    });
  },
  resume(runId: string, revisedInstruction?: string): Promise<AgentRunItem> {
    return request<AgentRunItem>(`/api/v1/runs/${runId}/resume`, {
      method: "POST",
      body: JSON.stringify({ revisedInstruction }),
    });
  },
  approve(
    runId: string,
    payload: {
      approved: boolean;
      editedOutline?: string;
      feedback?: string;
    },
  ): Promise<AgentRunItem> {
    return request<AgentRunItem>(`/api/v1/runs/${runId}/approve`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  cancel(runId: string): Promise<AgentRunItem> {
    return request<AgentRunItem>(`/api/v1/runs/${runId}/cancel`, {
      method: "POST",
    });
  },
};

export const loreApi = {
  listCharacters(bookId: string): Promise<CharacterItem[]> {
    return request<CharacterItem[]>(`/api/v1/books/${bookId}/characters`);
  },
  upsertCharacter(
    bookId: string,
    payload: {
      name: string;
      roleType?: string;
      personality?: string;
      realmOrLevel?: string;
      currentLocation?: string;
      statusNotes?: string;
      inventory?: string;
      aliases?: string;
      lifecycleStatus?: string;
      arcScope?: string;
      lockedFields?: string[];
      customAttributes?: Record<string, string>;
    },
  ): Promise<CharacterItem> {
    return request<CharacterItem>(`/api/v1/books/${bookId}/characters`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  wakeCharacter(bookId: string, characterId: string): Promise<CharacterItem> {
    return request<CharacterItem>(
      `/api/v1/books/${bookId}/characters/${characterId}/wake`,
      { method: "POST" },
    );
  },
  updateCharacterLocks(
    bookId: string,
    characterId: string,
    lockedFields: string[],
  ): Promise<CharacterItem> {
    return request<CharacterItem>(
      `/api/v1/books/${bookId}/characters/${characterId}/locks`,
      {
        method: "PUT",
        body: JSON.stringify({ lockedFields }),
      },
    );
  },
  deleteCharacter(bookId: string, characterId: string): Promise<void> {
    return request<void>(`/api/v1/books/${bookId}/characters/${characterId}`, {
      method: "DELETE",
    });
  },
  listWorldbook(bookId: string): Promise<WorldEntryItem[]> {
    return request<WorldEntryItem[]>(`/api/v1/books/${bookId}/worldbook`);
  },
  createWorldEntry(
    bookId: string,
    payload: { category?: string; title: string; content?: string; tags?: string },
  ): Promise<WorldEntryItem> {
    return request<WorldEntryItem>(`/api/v1/books/${bookId}/worldbook`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  deleteWorldEntry(bookId: string, entryId: string): Promise<void> {
    return request<void>(`/api/v1/books/${bookId}/worldbook/${entryId}`, {
      method: "DELETE",
    });
  },
  listForeshadowings(bookId: string): Promise<ForeshadowingItem[]> {
    return request<ForeshadowingItem[]>(`/api/v1/books/${bookId}/foreshadowings`);
  },
  listDueForeshadowings(
    bookId: string,
    chapterNo: number,
  ): Promise<DueForeshadowingsResponse> {
    return request<DueForeshadowingsResponse>(
      `/api/v1/books/${bookId}/foreshadowings/due?chapterNo=${chapterNo}`,
    );
  },
  upsertForeshadowing(
    bookId: string,
    payload: {
      id?: string;
      code?: string;
      title: string;
      description?: string;
      importance?: string;
      revealPolicy?: string;
      surfaceClue?: string;
      hiddenTruth?: string;
      plantedChapterNo?: number;
      resolvedChapterNo?: number;
      targetChapterMin?: number;
      targetChapterMax?: number;
      involvedCharacters?: string;
      plantedQuote?: string;
      authorLocked?: boolean;
      status?: string;
    },
  ): Promise<ForeshadowingItem> {
    return request<ForeshadowingItem>(`/api/v1/books/${bookId}/foreshadowings`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  transitionForeshadowing(
    bookId: string,
    foreshadowingId: string,
    payload: {
      targetStatus: string;
      sectionId?: string;
      chapterNo?: number;
      quote?: string;
      note?: string;
    },
  ): Promise<ForeshadowingItem> {
    return request<ForeshadowingItem>(
      `/api/v1/books/${bookId}/foreshadowings/${foreshadowingId}/transition`,
      {
        method: "POST",
        body: JSON.stringify(payload),
      },
    );
  },
  deleteForeshadowing(bookId: string, foreshadowingId: string): Promise<void> {
    return request<void>(
      `/api/v1/books/${bookId}/foreshadowings/${foreshadowingId}`,
      { method: "DELETE" },
    );
  },
};

export const memoryProposalsApi = {
  list(bookId: string, status = "PENDING"): Promise<MemoryProposalItem[]> {
    const q = status ? `?status=${encodeURIComponent(status)}` : "";
    return request<MemoryProposalItem[]>(
      `/api/v1/books/${bookId}/memory/proposals${q}`,
    );
  },
  decide(
    bookId: string,
    proposalId: string,
    decision: "ACCEPT" | "REJECT",
  ): Promise<MemoryProposalItem> {
    return request<MemoryProposalItem>(
      `/api/v1/books/${bookId}/memory/proposals/${proposalId}/decision`,
      {
        method: "POST",
        body: JSON.stringify({ decision }),
      },
    );
  },
};

export const knowledgeApi = {
  listDocuments(bookId: string): Promise<KnowledgeDocumentItem[]> {
    return request<KnowledgeDocumentItem[]>(`/api/v1/books/${bookId}/documents`);
  },
  uploadDocument(
    bookId: string,
    payload: {
      file: File;
      docType?: KnowledgeDocType;
      chapterNo?: number;
      title?: string;
    },
  ): Promise<KnowledgeDocumentItem> {
    const form = new FormData();
    form.append("file", payload.file);
    const params = new URLSearchParams();
    if (payload.docType) params.set("docType", payload.docType);
    if (payload.chapterNo !== undefined) {
      params.set("chapterNo", String(payload.chapterNo));
    }
    if (payload.title?.trim()) params.set("title", payload.title.trim());
    return request<KnowledgeDocumentItem>(
      `/api/v1/books/${bookId}/documents?${params.toString()}`,
      { method: "POST", body: form },
    );
  },
  reprocess(bookId: string, docId: string): Promise<KnowledgeDocumentItem> {
    return request<KnowledgeDocumentItem>(
      `/api/v1/books/${bookId}/documents/${docId}/reprocess`,
      { method: "POST" },
    );
  },
  deleteDocument(bookId: string, docId: string): Promise<void> {
    return request<void>(`/api/v1/books/${bookId}/documents/${docId}`, {
      method: "DELETE",
    });
  },
  syncChapter(
    bookId: string,
    chapterId: string,
    extractMemory = true,
  ): Promise<{ document: KnowledgeDocumentItem; memoryExtractionQueued: boolean }> {
    return request(`/api/v1/books/${bookId}/chapters/${chapterId}/sync-rag`, {
      method: "POST",
      body: JSON.stringify({ extractMemory }),
    });
  },
  search(
    bookId: string,
    payload: {
      query: string;
      topK?: number;
      maxChapterNo?: number;
      docType?: string;
      rewriteQuery?: boolean;
    },
  ): Promise<RagSearchResult> {
    return request<RagSearchResult>(`/api/v1/books/${bookId}/knowledge/search`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
};

export const memoryApi = {
  get(bookId: string, maxChapterNo?: number): Promise<StructuredMemorySnapshot> {
    const query =
      maxChapterNo !== undefined ? `?maxChapterNo=${maxChapterNo}` : "";
    return request<StructuredMemorySnapshot>(
      `/api/v1/books/${bookId}/memory${query}`,
    );
  },
  upsertRelation(
    bookId: string,
    payload: {
      sourceCharacter: string;
      targetCharacter: string;
      relationType: string;
      description?: string;
      sinceChapterNo?: number;
    },
  ): Promise<CharacterRelationItem> {
    return request<CharacterRelationItem>(
      `/api/v1/books/${bookId}/memory/relations`,
      { method: "POST", body: JSON.stringify(payload) },
    );
  },
  deleteRelation(bookId: string, relationId: string): Promise<void> {
    return request<void>(
      `/api/v1/books/${bookId}/memory/relations/${relationId}`,
      { method: "DELETE" },
    );
  },
  upsertTimeline(
    bookId: string,
    payload: {
      chapterNo: number;
      eventTitle: string;
      summary?: string;
      involvedCharacters?: string;
      location?: string;
    },
  ): Promise<TimelineEventItem> {
    return request<TimelineEventItem>(
      `/api/v1/books/${bookId}/memory/timeline`,
      { method: "POST", body: JSON.stringify(payload) },
    );
  },
  deleteTimeline(bookId: string, eventId: string): Promise<void> {
    return request<void>(
      `/api/v1/books/${bookId}/memory/timeline/${eventId}`,
      { method: "DELETE" },
    );
  },
  triggerExtract(bookId: string, chapterId: string): Promise<{ queued: boolean }> {
    return request(`/api/v1/books/${bookId}/memory/chapters/${chapterId}/extract`, {
      method: "POST",
    });
  },
};

export interface FactDeltaItem {
  type: string;
  entity: string;
  oldFact: string;
  newFact: string;
  searchKeywords: string[];
}

export interface PatchHunkItem {
  originalParagraph: string;
  patchedParagraph: string;
  reason: string;
}

export interface RippleJobItem {
  id: string;
  bookId: string;
  sourceSectionId: string;
  sourceChapterNo: number;
  sourceNarrativeOrder: number;
  vipPlanCode: string;
  rangeMode: "FORWARD" | "BACKWARD" | "BIDIRECTIONAL" | "CUSTOM_RANGE" | "FULL_BOOK" | string;
  rangeStartChapter: number;
  rangeEndChapter: number;
  maxCandidatesCap: number;
  maxPatchBatchLimit: number;
  status:
    | "ANALYZING"
    | "NO_CHANGE"
    | "READY_TO_PATCH"
    | "BLAST_RADIUS_EXCEEDED"
    | "PATCHING"
    | "READY_FOR_REVIEW"
    | "PARTIALLY_APPLIED"
    | "APPLIED"
    | "FAILED"
    | "CANCELLED"
    | string;
  changeSummary: string;
  scannedSectionCount: number;
  candidateSectionCount: number;
  backwardConflictCount: number;
  forwardConflictCount: number;
  minorMentionCount: number;
  estimatedCostMicros: number;
  estimatedCostCredits: number;
  actualCostMicros: number;
  actualCostCredits: number;
  errorMessage: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface RippleImpactItem {
  id: string;
  jobId: string;
  targetSectionId: string;
  chapterNo: number;
  sectionNo: number;
  narrativeOrder: number;
  chapterTitle: string;
  direction: "BACKWARD" | "FORWARD" | string;
  severity: "STRONG_CONFLICT" | "MINOR_MENTION" | "UNAFFECTED" | string;
  conflictReason: string;
  matchedExcerpt: string;
  patchStatus: string;
}

export interface RipplePatchItem {
  id: string;
  jobId: string;
  impactId: string;
  targetSectionId: string;
  chapterNo: number;
  sectionNo: number;
  chapterTitle: string;
  direction: string;
  batchNo: number;
  baseVersion: number;
  hunks: PatchHunkItem[];
  status: string;
  updatedAt: string | null;
}

export interface RippleJobDetail {
  job: RippleJobItem;
  factDeltas: FactDeltaItem[];
  backwardImpacts: RippleImpactItem[];
  forwardImpacts: RippleImpactItem[];
  patches: RipplePatchItem[];
}

export interface ApplyRipplePatchesResult {
  jobStatus: string;
  appliedCount: number;
  conflictCount: number;
  manualReviewCount: number;
  rejectedCount: number;
  appliedSectionIds: string[];
}

export const rippleApi = {
  analyze(
    bookId: string,
    sectionId: string,
    payload: {
      rangeMode?: "FORWARD" | "BACKWARD" | "BIDIRECTIONAL" | "CUSTOM_RANGE" | "FULL_BOOK";
      rangeStartChapter?: number;
      rangeEndChapter?: number;
      volumeIds?: string[];
      oldVersionId?: string;
      oldContent?: string;
      newContent?: string;
      changeNote?: string;
    },
  ): Promise<RippleJobItem> {
    return request<RippleJobItem>(
      `/api/v1/books/${bookId}/sections/${sectionId}/ripple-analyze`,
      {
        method: "POST",
        body: JSON.stringify(payload),
      },
    );
  },
  listJobs(bookId: string): Promise<RippleJobItem[]> {
    return request<RippleJobItem[]>(`/api/v1/books/${bookId}/ripple-jobs`);
  },
  getJob(bookId: string, jobId: string): Promise<RippleJobDetail> {
    return request<RippleJobDetail>(`/api/v1/books/${bookId}/ripple-jobs/${jobId}`);
  },
  generatePatches(
    bookId: string,
    jobId: string,
    impactIds: string[],
  ): Promise<RippleJobDetail> {
    return request<RippleJobDetail>(
      `/api/v1/books/${bookId}/ripple-jobs/${jobId}/generate-patches`,
      {
        method: "POST",
        body: JSON.stringify({ impactIds }),
      },
    );
  },
  applyPatches(
    bookId: string,
    jobId: string,
    payload: { patchIds: string[]; rejectedPatchIds?: string[] },
  ): Promise<ApplyRipplePatchesResult> {
    return request<ApplyRipplePatchesResult>(
      `/api/v1/books/${bookId}/ripple-jobs/${jobId}/apply`,
      {
        method: "POST",
        body: JSON.stringify(payload),
      },
    );
  },
  cancelJob(bookId: string, jobId: string): Promise<RippleJobItem> {
    return request<RippleJobItem>(
      `/api/v1/books/${bookId}/ripple-jobs/${jobId}/cancel`,
      { method: "POST" },
    );
  },
};

export const billingApi = {
  dashboard(): Promise<BillingDashboardData> {
    return request<BillingDashboardData>("/api/v1/billing/dashboard");
  },
  topUp(amountCredits: number, requestId?: string): Promise<BillingAccountSummary> {
    return request<BillingAccountSummary>("/api/v1/billing/topup", {
      method: "POST",
      body: JSON.stringify({ amountCredits, requestId }),
    });
  },
  updateBudget(monthlyBudgetCredits: number): Promise<BillingAccountSummary> {
    return request<BillingAccountSummary>("/api/v1/billing/budget", {
      method: "PUT",
      body: JSON.stringify({ monthlyBudgetCredits }),
    });
  },
};
