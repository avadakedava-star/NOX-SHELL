import { create } from 'zustand'

/**
 * 面板布局：宽度持久化，刷新后保留用户调整过的比例。
 * 只存 UI 布局，不涉及后端状态。
 */
export type SidebarTab = 'servers' | 'files' | 'sftp' | 'extensions'

interface PersistedLayout {
  sidebarWidth: number
  chatWidth: number
  sidebarVisible: boolean
  chatVisible: boolean
}

interface LayoutStore extends PersistedLayout {
  setSidebarWidth: (width: number) => void
  setChatWidth: (width: number) => void
  setSidebarVisible: (visible: boolean) => void
  setChatVisible: (visible: boolean) => void
  toggleSidebar: () => void
  toggleChat: () => void
  resetWidths: () => void
}

export const SIDEBAR_MIN = 216
export const SIDEBAR_MAX = 420
export const SIDEBAR_DEFAULT = 264
export const CHAT_MIN = 340
export const CHAT_MAX = 720
export const CHAT_DEFAULT = 408

const KEY = 'nox-shell-layout-v2'

export function clampWidth(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min
  return Math.round(Math.min(max, Math.max(min, value)))
}

function readLayout(): PersistedLayout {
  const fallback: PersistedLayout = {
    sidebarWidth: SIDEBAR_DEFAULT,
    chatWidth: CHAT_DEFAULT,
    sidebarVisible: true,
    chatVisible: true,
  }
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return fallback
    const parsed = JSON.parse(raw) as Partial<PersistedLayout>
    return {
      sidebarWidth: clampWidth(Number(parsed.sidebarWidth), SIDEBAR_MIN, SIDEBAR_MAX) || SIDEBAR_DEFAULT,
      chatWidth: clampWidth(Number(parsed.chatWidth), CHAT_MIN, CHAT_MAX) || CHAT_DEFAULT,
      sidebarVisible: parsed.sidebarVisible !== false,
      chatVisible: parsed.chatVisible !== false,
    }
  } catch {
    return fallback
  }
}

function persist(state: PersistedLayout): void {
  try {
    localStorage.setItem(KEY, JSON.stringify({
      sidebarWidth: state.sidebarWidth,
      chatWidth: state.chatWidth,
      sidebarVisible: state.sidebarVisible,
      chatVisible: state.chatVisible,
    }))
  } catch {
    /* 隐私模式下忽略 */
  }
}

const initial = readLayout()

export const useLayoutStore = create<LayoutStore>((set, get) => {
  const commit = (patch: Partial<PersistedLayout>) => {
    set(patch)
    persist({ ...get(), ...patch })
  }

  return {
    ...initial,
    setSidebarWidth: (width) => commit({ sidebarWidth: clampWidth(width, SIDEBAR_MIN, SIDEBAR_MAX) }),
    setChatWidth: (width) => commit({ chatWidth: clampWidth(width, CHAT_MIN, CHAT_MAX) }),
    setSidebarVisible: (visible) => commit({ sidebarVisible: visible }),
    setChatVisible: (visible) => commit({ chatVisible: visible }),
    toggleSidebar: () => commit({ sidebarVisible: !get().sidebarVisible }),
    toggleChat: () => commit({ chatVisible: !get().chatVisible }),
    resetWidths: () => commit({ sidebarWidth: SIDEBAR_DEFAULT, chatWidth: CHAT_DEFAULT }),
  }
})
