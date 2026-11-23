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

export interface BookItem {
  id: string;
  title: string;
  genre: string | null;
  synopsis: string | null;
  status: string;
  version: number;
  chapterCount: number;
  totalWordCount: number;
  createdAt: string | null;
  updatedAt: string | null;
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
export type RunMode = "AGENT" | "DIRECT";

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
  createdAt: string | null;
  updatedAt: string | null;
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
  title: string;
  description: string;
  plantedChapterNo: number | null;
  resolvedChapterNo: number | null;
  status: string;
  updatedAt: string | null;
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
  if (init.body && !headers.has("Content-Type")) {
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

export const runsApi = {
  start(payload: {
    bookId: string;
    chapterId?: string;
    mode?: RunMode;
    action: RunAction;
    modelAlias?: string;
    instruction?: string;
    chapterContext?: string;
  }): Promise<AgentRunItem> {
    return request<AgentRunItem>("/api/v1/runs", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  get(runId: string): Promise<AgentRunItem> {
    return request<AgentRunItem>(`/api/v1/runs/${runId}`);
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
    },
  ): Promise<CharacterItem> {
    return request<CharacterItem>(`/api/v1/books/${bookId}/characters`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
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
  upsertForeshadowing(
    bookId: string,
    payload: {
      title: string;
      description?: string;
      plantedChapterNo?: number;
      status?: string;
    },
  ): Promise<ForeshadowingItem> {
    return request<ForeshadowingItem>(`/api/v1/books/${bookId}/foreshadowings`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  deleteForeshadowing(bookId: string, foreshadowingId: string): Promise<void> {
    return request<void>(
      `/api/v1/books/${bookId}/foreshadowings/${foreshadowingId}`,
      { method: "DELETE" },
    );
  },
};
