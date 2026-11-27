export const themeConfig = {
  key: "foliarc-theme",
  fallback: "paper",
  labels: {
    paper: "晨露素白",
    sepia: "柔光暖纸",
    ink: "静谧夜墨",
  },
} as const;

export type AppThemeId = keyof typeof themeConfig.labels;
