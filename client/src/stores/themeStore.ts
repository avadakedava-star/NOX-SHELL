import { create } from 'zustand'

export type ThemeName = 'dark' | 'light' | 'midnight' | 'forest'

export interface ThemeColors {
  bgPrimary: string       // 主背景 #1e1e1e
  bgSecondary: string     // 侧栏背景 #252526
  bgTertiary: string      // 控件/卡片 #2d2d2d
  bgInput: string         // 输入框 #1e1e1e
  bgHover: string         // hover 态
  bgTitleBar: string      // 标题栏 #323233
  border: string          // 边框 #3c3c3c
  text: string            // 正文 #e5e7eb
  textSecondary: string   // 次要文字 #9ca3af
  textDim: string         // 弱化文字 #6b7280
  accent: string          // 主色（蓝）
  accentSoft: string      // 主色淡底
  green: string           // 成功/在线
  red: string             // 错误
  yellow: string          // 警告
}

export interface ThemeConfig {
  name: ThemeName
  label: string
  colors: ThemeColors
}

export const themes: Record<ThemeName, ThemeConfig> = {
  dark: {
    name: 'dark',
    label: 'VS Code Dark',
    colors: {
      bgPrimary: '#1e1e1e',
      bgSecondary: '#252526',
      bgTertiary: '#2d2d2d',
      bgInput: '#1e1e1e',
      bgHover: '#2a2d2e',
      bgTitleBar: '#323233',
      border: '#3c3c3c',
      text: '#e5e7eb',
      textSecondary: '#9ca3af',
      textDim: '#6b7280',
      accent: '#4f8af5',
      accentSoft: 'rgba(79,138,245,0.12)',
      green: '#3fb950',
      red: '#f85149',
      yellow: '#d29922',
    },
  },
  light: {
    name: 'light',
    label: '浅色',
    colors: {
      bgPrimary: '#ffffff',
      bgSecondary: '#f3f4f6',
      bgTertiary: '#e5e7eb',
      bgInput: '#ffffff',
      bgHover: '#ebedef',
      bgTitleBar: '#f0f0f0',
      border: '#d1d5db',
      text: '#111827',
      textSecondary: '#4b5563',
      textDim: '#9ca3af',
      accent: '#2563eb',
      accentSoft: 'rgba(37,99,235,0.10)',
      green: '#16a34a',
      red: '#dc2626',
      yellow: '#ca8a04',
    },
  },
  midnight: {
    name: 'midnight',
    label: 'GitHub Dark',
    colors: {
      bgPrimary: '#0d1117',
      bgSecondary: '#161b22',
      bgTertiary: '#21262d',
      bgInput: '#0d1117',
      bgHover: '#1c2129',
      bgTitleBar: '#161b22',
      border: '#30363d',
      text: '#e6edf3',
      textSecondary: '#8b949e',
      textDim: '#484f58',
      accent: '#58a6ff',
      accentSoft: 'rgba(88,166,255,0.12)',
      green: '#3fb950',
      red: '#f85149',
      yellow: '#d29922',
    },
  },
  forest: {
    name: 'forest',
    label: '森林',
    colors: {
      bgPrimary: '#1a1f16',
      bgSecondary: '#222820',
      bgTertiary: '#2d332a',
      bgInput: '#1a1f16',
      bgHover: '#282e24',
      bgTitleBar: '#222820',
      border: '#3d4538',
      text: '#d4e4d8',
      textSecondary: '#8aaa90',
      textDim: '#5a6b5e',
      accent: '#4ade80',
      accentSoft: 'rgba(74,222,128,0.12)',
      green: '#22c55e',
      red: '#ef4444',
      yellow: '#eab308',
    },
  },
}

interface ThemeStore {
  currentTheme: ThemeName
  colors: ThemeColors
  setTheme: (name: ThemeName) => void
}

export const useThemeStore = create<ThemeStore>((set) => ({
  currentTheme: 'light',
  colors: themes.light.colors,

  setTheme: (name) =>
    set({
      currentTheme: name,
      colors: themes[name].colors,
    }),
}))
