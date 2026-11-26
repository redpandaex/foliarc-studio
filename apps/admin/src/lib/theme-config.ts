export const themeConfig = {
  key: "foliarc-ops-theme",
  fallback: "ops-dark",
  labels: {
    "ops-dark": "深蓝夜间",
    "ops-light": "工程日间",
  },
} as const;

export type AppThemeId = keyof typeof themeConfig.labels;
