export type EditorFontId =
  | "noto-serif"
  | "lxgw-wenkai"
  | "zhuque-fangsong"
  | "noto-sans"
  | "system";

export interface EditorFontMeta {
  id: EditorFontId;
  label: string;
  desc: string;
  cssUrl: string | null;
  stack: string;
}

export const DEFAULT_EDITOR_FONT: EditorFontId = "noto-serif";

export const EDITOR_FONT_ORDER: readonly EditorFontId[] = [
  "noto-serif",
  "lxgw-wenkai",
  "zhuque-fangsong",
  "noto-sans",
  "system",
];

export const EDITOR_FONT_META: Record<EditorFontId, EditorFontMeta> = {
  "noto-serif": {
    id: "noto-serif",
    label: "思源宋体",
    desc: "默认 · 出版宋体",
    cssUrl: "https://fontsapi.zeoseven.com/285/main/result.css",
    stack:
      '"Noto Serif CJK", "Noto Serif CJK SC", "Source Han Serif SC", "Songti SC", "STSong", SimSun, serif',
  },
  "lxgw-wenkai": {
    id: "lxgw-wenkai",
    label: "霞鹜文楷",
    desc: "温润书卷楷体",
    cssUrl: "https://fontsapi.zeoseven.com/292/main/result.css",
    stack: '"LXGW WenKai", "LXGW WenKai Screen", "Kaiti SC", "STKaiti", KaiTi, serif',
  },
  "zhuque-fangsong": {
    id: "zhuque-fangsong",
    label: "朱雀仿宋",
    desc: "民国古籍仿宋",
    cssUrl: "https://fontsapi.zeoseven.com/7/main/result.css",
    stack:
      '"Zhuque Fangsong (technical preview)", "FangSong", "STFangsong", FangSong_GB2312, serif',
  },
  "noto-sans": {
    id: "noto-sans",
    label: "思源黑体",
    desc: "清晰现代无衬线",
    cssUrl: "https://fontsapi.zeoseven.com/69/main/result.css",
    stack:
      '"Noto Sans CJK", "Noto Sans CJK SC", "Source Han Sans SC", "PingFang SC", "Microsoft YaHei", sans-serif',
  },
  system: {
    id: "system",
    label: "系统默认",
    desc: "零流量本地字体",
    cssUrl: null,
    stack:
      'system-ui, -apple-system, BlinkMacSystemFont, "PingFang SC", "Noto Sans CJK SC", "Microsoft YaHei", sans-serif',
  },
};

/** Normalize current and legacy localStorage font keys into EditorFontId. */
export function normalizeEditorFontId(raw: unknown): EditorFontId {
  switch (raw) {
    case "noto-serif":
    case "serif":
      return "noto-serif";
    case "lxgw-wenkai":
    case "kai":
      return "lxgw-wenkai";
    case "zhuque-fangsong":
      return "zhuque-fangsong";
    case "noto-sans":
    case "sans":
      return "noto-sans";
    case "system":
      return "system";
    default:
      return DEFAULT_EDITOR_FONT;
  }
}

/** Inject a cn-font-split stylesheet link into document.head once per font. */
export function ensureEditorFontLoaded(fontId: EditorFontId): void {
  if (typeof document === "undefined") return;
  const meta = EDITOR_FONT_META[fontId];
  if (!meta?.cssUrl) return;

  const linkId = `foliarc-editor-font-${fontId}`;
  if (document.getElementById(linkId)) return;

  const link = document.createElement("link");
  link.id = linkId;
  link.rel = "stylesheet";
  link.href = meta.cssUrl;
  link.crossOrigin = "anonymous";
  document.head.appendChild(link);
}
