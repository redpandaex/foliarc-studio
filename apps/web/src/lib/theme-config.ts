export const themeConfig = {
  key: "foliarc-theme",
  fallback: "paper",
  labels: {
    paper: "宣纸日间",
    sepia: "护眼羊皮纸",
    ink: "深夜墨蓝",
  },
} as const;

export type AppThemeId = keyof typeof themeConfig.labels;
