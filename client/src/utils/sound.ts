/**
 * 对话完成提示音
 * 使用 Web Audio API 合成短促音效，零依赖、Win/Mac 跨平台兼容。
 * 用户可在设置中开关，默认关闭（浏览器自动播放策略要求用户交互后才可用）。
 */

const SOUND_ENABLED_KEY = 'walissh_sound_enabled'

let audioCtx: AudioContext | null = null

function getAudioCtx(): AudioContext | null {
  if (typeof window === 'undefined') return null
  if (!audioCtx) {
    try {
      audioCtx = new (window.AudioContext || (window as any).webkitAudioContext)()
    } catch {
      return null
    }
  }
  // 浏览器策略：用户交互前 context 可能被 suspend
  if (audioCtx.state === 'suspended') {
    audioCtx.resume().catch(() => {})
  }
  return audioCtx
}

export function isSoundEnabled(): boolean {
  try {
    return localStorage.getItem(SOUND_ENABLED_KEY) === 'true'
  } catch {
    return false
  }
}

export function setSoundEnabled(enabled: boolean): void {
  try {
    if (enabled) {
      localStorage.setItem(SOUND_ENABLED_KEY, 'true')
    } else {
      localStorage.removeItem(SOUND_ENABLED_KEY)
    }
  } catch {
    // 静默忽略
  }
}

/**
 * 播放完成提示音：两声升调（C5 → E5），清脆悦耳
 */
export function playCompleteSound(): void {
  if (!isSoundEnabled()) return
  const ctx = getAudioCtx()
  if (!ctx) return

  const now = ctx.currentTime
  const notes = [
    { freq: 523.25, start: 0, duration: 0.12 },    // C5
    { freq: 659.25, start: 0.10, duration: 0.15 },  // E5
  ]

  for (const note of notes) {
    const osc = ctx.createOscillator()
    const gain = ctx.createGain()
    osc.type = 'sine'
    osc.frequency.value = note.freq
    gain.gain.setValueAtTime(0, now + note.start)
    gain.gain.linearRampToValueAtTime(0.15, now + note.start + 0.01)
    gain.gain.exponentialRampToValueAtTime(0.001, now + note.start + note.duration)
    osc.connect(gain)
    gain.connect(ctx.destination)
    osc.start(now + note.start)
    osc.stop(now + note.start + note.duration)
  }
}

/**
 * 播放错误提示音：一声低沉降调
 */
export function playErrorSound(): void {
  if (!isSoundEnabled()) return
  const ctx = getAudioCtx()
  if (!ctx) return

  const now = ctx.currentTime
  const osc = ctx.createOscillator()
  const gain = ctx.createGain()
  osc.type = 'sine'
  osc.frequency.setValueAtTime(440, now)
  osc.frequency.exponentialRampToValueAtTime(220, now + 0.2)
  gain.gain.setValueAtTime(0, now)
  gain.gain.linearRampToValueAtTime(0.12, now + 0.01)
  gain.gain.exponentialRampToValueAtTime(0.001, now + 0.25)
  osc.connect(gain)
  gain.connect(ctx.destination)
  osc.start(now)
  osc.stop(now + 0.25)
}
