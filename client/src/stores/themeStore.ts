import { create } from 'zustand'

export type ThemeName = 'dark' | 'light' | 'midnight' | 'violet'

export interface ThemeColors {
  bgPrimary: string
  bgSecondary: string
  bgTertiary: string
  bgInput: string
  bgHover: string
  bgTitleBar: string
  border: string
  text: string
  textSecondary: string
  textDim: string
  accent: string
  accentSoft: string
  green: string
  red: string
  yellow: string
}

export interface ThemeConfig {
  name: ThemeName
  label: string
  colors: ThemeColors
}

export const themes: Record<ThemeName, ThemeConfig> = {
  dark: {
    name: 'dark',
    label: '赤夜 / Redline',
    colors: {
      bgPrimary: '#0b0c10',
      bgSecondary: '#111217',
      bgTertiary: '#1a1b22',
      bgInput: '#0e0f14',
      bgHover: '#242027',
      bgTitleBar: '#101115',
      border: '#2b2b35',
      text: '#f1f0f3',
      textSecondary: '#b1afb9',
      textDim: '#86838f',
      accent: '#ff4655',
      accentSoft: '#ff465514',
      green: '#57d9a3',
      red: '#ff7581',
      yellow: '#eec078',
    },
  },
  light: {
    name: 'light',
    label: '白昼 / Paper',
    colors: {
      bgPrimary: '#f5f4f2',
      bgSecondary: '#eeedeb',
      bgTertiary: '#e4e2df',
      bgInput: '#ffffff',
      bgHover: '#dfddda',
      bgTitleBar: '#f9f8f6',
      border: '#d0cdca',
      text: '#222129',
      textSecondary: '#595660',
      textDim: '#716d76',
      accent: '#bd2539',
      accentSoft: '#bd253911',
      green: '#187952',
      red: '#c02f42',
      yellow: '#97600e',
    },
  },
  midnight: {
    name: 'midnight',
    label: '极夜 / Icewire',
    colors: {
      bgPrimary: '#091015',
      bgSecondary: '#101920',
      bgTertiary: '#192730',
      bgInput: '#0b131a',
      bgHover: '#20343e',
      bgTitleBar: '#0d161d',
      border: '#283a45',
      text: '#e7f4fa',
      textSecondary: '#a1b9c6',
      textDim: '#819aa9',
      accent: '#54cce8',
      accentSoft: '#54cce814',
      green: '#63d7ad',
      red: '#ff818e',
      yellow: '#ecc684',
    },
  },
  violet: {
    name: 'violet',
    label: '紫电 / Ultraviolet',
    colors: {
      bgPrimary: '#100d17',
      bgSecondary: '#181320',
      bgTertiary: '#241d30',
      bgInput: '#130f1b',
      bgHover: '#30253e',
      bgTitleBar: '#16111d',
      border: '#382b47',
      text: '#f2eefa',
      textSecondary: '#bdb0cb',
      textDim: '#9a87ae',
      accent: '#c099ff',
      accentSoft: '#c099ff14',
      green: '#66dbb4',
      red: '#ff819d',
      yellow: '#efc47f',
    },
  },
}

interface ThemeStore {
  currentTheme: ThemeName
  colors: ThemeColors
  setTheme: (name: ThemeName) => void
}

const THEME_KEY = 'nox-shell-theme'
function readTheme(): ThemeName {
  try {
    const saved = localStorage.getItem(THEME_KEY)
    if (saved && Object.prototype.hasOwnProperty.call(themes, saved)) return saved as ThemeName
  } catch { /* 使用默认配色 */ }
  return 'dark'
}
const initialTheme = readTheme()

export const useThemeStore = create<ThemeStore>((set) => ({
  currentTheme: initialTheme,
  colors: themes[initialTheme].colors,
  setTheme: (name) => {
    try { localStorage.setItem(THEME_KEY, name) } catch { /* 仍可在本次使用中切换 */ }
    set({ currentTheme: name, colors: themes[name].colors })
  },
}))
