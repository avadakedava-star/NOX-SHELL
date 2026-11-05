import { useState, useEffect, useCallback, useRef } from 'react'
import { useThemeStore, themes, type ThemeName } from '../stores/themeStore'
import { useConnectionStore } from '../stores/connectionStore'
import { getChatTimeoutMinutes, setChatTimeoutMinutes } from '../api/agent'
import { isSoundEnabled, setSoundEnabled } from '../utils/sound'

interface SettingsProps {
  open: boolean
  onClose: () => void
}

type Section = 'general' | 'appearance' | 'terminal' | 'about'

export function Settings({ open, onClose }: SettingsProps) {
  const { currentTheme, setTheme } = useThemeStore()
  const { serverUrl, setServerUrl } = useConnectionStore()

  // ── 各设置项的本地编辑状态 ──
  const [inputUrl, setInputUrl] = useState(serverUrl)
  const [inputLang, setInputLang] = useState('简体中文')
  const [inputFont, setInputFont] = useState('JetBrains Mono')
  const [inputFontSize, setInputFontSize] = useState(13)
  const [inputTimeout, setInputTimeout] = useState(getChatTimeoutMinutes())
  const [soundEnabled, setSoundEnabledState] = useState(isSoundEnabled())
  const [section, setSection] = useState<Section>('general')
  const [testStatus, setTestStatus] = useState<'idle' | 'testing' | 'success' | 'fail'>('idle')

  // ── 可拖拽缩放 ──
  const dialogRef = useRef<HTMLDivElement>(null)
  const [size, setSize] = useState({ w: 960, h: 640 })
  const dragging = useRef(false)
  const dragStart = useRef({ x: 0, y: 0, w: 0, h: 0 })

  // 同步 store → 本地
  useEffect(() => { setInputUrl(serverUrl) }, [serverUrl])

  // ── 判断是否有修改 ──
  const hasChanges = inputUrl.trim().replace(/\/+$/, '') !== serverUrl || inputTimeout !== getChatTimeoutMinutes() || soundEnabled !== isSoundEnabled()

  /** 测试连接 — 直接请求用户输入的地址，不依赖 store 状态 */
  const handleTest = useCallback(async () => {
    const trimmed = inputUrl.trim().replace(/\/+$/, '')
    if (!trimmed) return
    setTestStatus('testing')
    try {
      const res = await fetch(`${trimmed}/api/v1/ssh/connection_list?userId=default`, {
        signal: AbortSignal.timeout(8000),
      })
      const json = await res.json()
      setTestStatus(json.code === '0000' ? 'success' : 'fail')
    } catch {
      setTestStatus('fail')
    }
    setTimeout(() => setTestStatus('idle'), 3000)
  }, [inputUrl])

  /** 保存所有设置 */
  const handleSave = useCallback(() => {
    const trimmed = inputUrl.trim().replace(/\/+$/, '')
    if (trimmed) setServerUrl(trimmed)
    setChatTimeoutMinutes(inputTimeout)
    setSoundEnabled(soundEnabled)
  }, [inputUrl, setServerUrl, inputTimeout, soundEnabled])

  /** 取消：还原所有本地状态 */
  const handleCancel = useCallback(() => {
    setInputUrl(serverUrl)
    setInputTimeout(getChatTimeoutMinutes())
    setSoundEnabledState(isSoundEnabled())
    onClose()
  }, [serverUrl, onClose])

  /** 保存并关闭 */
  const handleSaveAndClose = useCallback(() => {
    handleSave()
    onClose()
  }, [handleSave, onClose])

  // ── 拖拽缩放逻辑 ──
  const onResizeMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    dragging.current = true
    dragStart.current = { x: e.clientX, y: e.clientY, w: size.w, h: size.h }
    const onMove = (ev: MouseEvent) => {
      if (!dragging.current) return
      const nw = Math.max(700, dragStart.current.w + ev.clientX - dragStart.current.x)
      const nh = Math.max(460, dragStart.current.h + ev.clientY - dragStart.current.y)
      setSize({ w: nw, h: nh })
    }
    const onUp = () => {
      dragging.current = false
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
    }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  }, [size])

  // ── ESC 关闭 ──
  useEffect(() => {
    if (!open) return
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') handleCancel()
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [open, handleCancel])

  if (!open) return null

  const { colors } = themes[currentTheme]
  const themeList = (Object.entries(themes) as [ThemeName, typeof themes[ThemeName]][])

  const sections: { id: Section; label: string; icon: string }[] = [
    { id: 'general', label: '通用', icon: '⚙️' },
    { id: 'appearance', label: '外观', icon: '🎨' },
    { id: 'terminal', label: '终端', icon: '💻' },
    { id: 'about', label: '关于', icon: 'ℹ️' },
  ]

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center" style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
      <div
        ref={dialogRef}
        className="rounded-xl shadow-2xl flex flex-col overflow-hidden relative select-none"
        style={{
          backgroundColor: colors.bgSecondary,
          border: `1px solid ${colors.border}`,
          width: size.w,
          height: size.h,
          minWidth: 700,
          minHeight: 460,
        }}
      >
        {/* ── 顶栏 ── */}
        <div
          className="flex items-center justify-between px-6 py-3.5 shrink-0"
          style={{ backgroundColor: colors.bgPrimary, borderBottom: `1px solid ${colors.border}` }}
        >
          <span className="text-[14px] font-semibold" style={{ color: colors.text }}>设置</span>
          <button
            onClick={handleCancel}
            className="w-7 h-7 rounded-full flex items-center justify-center text-sm hover:bg-white/10 transition-colors"
            style={{ color: colors.textDim }}
          >
            ✕
          </button>
        </div>

        {/* ── 主体：左侧导航 + 右侧内容 ── */}
        <div className="flex flex-1 min-h-0">
          {/* 左侧导航 */}
          <div
            className="w-56 p-4 border-r flex flex-col gap-1 shrink-0"
            style={{ borderColor: colors.border }}
          >
            {sections.map((item) => (
              <button
                key={item.id}
                onClick={() => setSection(item.id)}
                className="flex items-center gap-3 px-3.5 py-2.5 rounded-lg text-[13px] text-left transition-colors"
                style={{
                  backgroundColor: section === item.id ? colors.accentSoft : 'transparent',
                  color: section === item.id ? colors.accent : colors.textSecondary,
                  fontWeight: section === item.id ? 600 : 400,
                }}
              >
                <span className="text-[15px]">{item.icon}</span>
                {item.label}
              </button>
            ))}
          </div>

          {/* 右侧内容区 */}
          <div className="flex-1 p-8 overflow-y-auto">
            {section === 'general' && (
              <div className="space-y-6">
                <h2 className="text-[15px] font-semibold" style={{ color: colors.text }}>通用设置</h2>

                {/* 服务端地址 */}
                <div>
                  <label className="block text-[13px] mb-2" style={{ color: colors.textDim }}>服务端地址</label>
                  <div className="flex gap-2">
                    <input
                      type="text"
                      value={inputUrl}
                      onChange={(e) => setInputUrl(e.target.value)}
                      placeholder="http://localhost:8091"
                      className="flex-1 px-3.5 py-2 rounded-md text-[13px] outline-none transition-colors"
                      style={{
                        backgroundColor: colors.bgInput,
                        border: `1px solid ${inputUrl.trim().replace(/\/+$/, '') !== serverUrl ? colors.accent : colors.border}`,
                        color: colors.text,
                      }}
                    />
                    <button
                      onClick={handleTest}
                      disabled={testStatus === 'testing'}
                      className="px-4 py-2 rounded-md text-[13px] font-medium transition-colors whitespace-nowrap"
                      style={{
                        backgroundColor: colors.bgTertiary,
                        border: `1px solid ${colors.border}`,
                        color: testStatus === 'success' ? colors.green : testStatus === 'fail' ? colors.red : colors.textSecondary,
                      }}
                    >
                      {testStatus === 'testing' ? '测试中...' : testStatus === 'success' ? '✓ 连接成功' : testStatus === 'fail' ? '✗ 连接失败' : '测试连接'}
                    </button>
                  </div>
                  <p className="mt-1.5 text-[12px]" style={{ color: colors.textDim }}>后端 API 的完整地址，如 http://localhost:8091</p>
                </div>

                {/* 语言 */}
                <div>
                  <label className="block text-[13px] mb-2" style={{ color: colors.textDim }}>语言</label>
                  <select
                    value={inputLang}
                    onChange={(e) => setInputLang(e.target.value)}
                    className="w-full px-3.5 py-2 rounded-md text-[13px] outline-none"
                    style={{ backgroundColor: colors.bgInput, border: `1px solid ${colors.border}`, color: colors.text }}
                  >
                    <option>简体中文</option>
                    <option>English</option>
                  </select>
                </div>

                {/* AI 对话超时 */}
                <div>
                  <label className="block text-[13px] mb-2" style={{ color: colors.textDim }}>AI 对话超时</label>
                  <div className="flex items-center gap-4">
                    <input
                      type="range"
                      min="1"
                      max="60"
                      value={inputTimeout}
                      onChange={(e) => setInputTimeout(Number(e.target.value))}
                      className="flex-1"
                    />
                    <span className="text-[13px] w-12 text-right tabular-nums" style={{ color: colors.text }}>{inputTimeout} 分钟</span>
                  </div>
                  <p className="mt-1.5 text-[12px]" style={{ color: colors.textDim }}>流式对话的最大等待时间，AI + SSH 命令执行可能需要较长时间（默认 10 分钟）</p>
                </div>

                {/* 完成提示音 */}
                <div className="flex items-center justify-between">
                  <div>
                    <label className="block text-[13px] mb-1" style={{ color: colors.textDim }}>对话完成提示音</label>
                    <p className="text-[12px]" style={{ color: colors.textDim }}>AI 回答完成或出错时播放短促提示音</p>
                  </div>
                  <button
                    onClick={() => setSoundEnabledState(!soundEnabled)}
                    className="relative w-10 h-6 rounded-full transition-colors flex-shrink-0"
                    style={{ backgroundColor: soundEnabled ? colors.accent : colors.bgTertiary }}
                  >
                    <span
                      className="absolute top-0.5 w-5 h-5 rounded-full bg-white transition-all"
                      style={{ left: soundEnabled ? '20px' : '2px' }}
                    />
                  </button>
                </div>
              </div>
            )}

            {section === 'appearance' && (
              <div className="space-y-6">
                <h2 className="text-[15px] font-semibold" style={{ color: colors.text }}>外观设置</h2>
                <div className="grid grid-cols-2 gap-4">
                  {themeList.map(([name, config]) => (
                    <button
                      key={name}
                      onClick={() => setTheme(name)}
                      className="p-4 rounded-lg text-left transition-all border-2"
                      style={{
                        backgroundColor: config.colors.bgPrimary,
                        borderColor: currentTheme === name ? config.colors.accent : config.colors.border,
                      }}
                    >
                      <div className="flex gap-1.5 mb-3">
                        {[config.colors.bgPrimary, config.colors.bgSecondary, config.colors.accent, config.colors.green].map((c, i) => (
                          <div key={i} className="w-5 h-5 rounded-full border" style={{ backgroundColor: c, borderColor: config.colors.border }} />
                        ))}
                      </div>
                      <span className="text-[13px] font-medium" style={{ color: config.colors.text }}>{config.label}</span>
                      {currentTheme === name && (
                        <span className="ml-2 text-xs" style={{ color: config.colors.accent }}>✓</span>
                      )}
                    </button>
                  ))}
                </div>
              </div>
            )}

            {section === 'terminal' && (
              <div className="space-y-6">
                <h2 className="text-[15px] font-semibold" style={{ color: colors.text }}>终端设置</h2>

                {/* 字体 */}
                <div>
                  <label className="block text-[13px] mb-2" style={{ color: colors.textDim }}>字体</label>
                  <select
                    value={inputFont}
                    onChange={(e) => setInputFont(e.target.value)}
                    className="w-full px-3.5 py-2 rounded-md text-[13px] outline-none"
                    style={{ backgroundColor: colors.bgInput, border: `1px solid ${colors.border}`, color: colors.text }}
                  >
                    <option>JetBrains Mono</option>
                    <option>Fira Code</option>
                    <option>Menlo</option>
                    <option>Source Code Pro</option>
                  </select>
                </div>

                {/* 字号 */}
                <div>
                  <label className="block text-[13px] mb-2" style={{ color: colors.textDim }}>字号</label>
                  <div className="flex items-center gap-4">
                    <input
                      type="range"
                      min="10"
                      max="24"
                      value={inputFontSize}
                      onChange={(e) => setInputFontSize(Number(e.target.value))}
                      className="flex-1"
                    />
                    <span className="text-[13px] w-8 text-right tabular-nums" style={{ color: colors.text }}>{inputFontSize}px</span>
                  </div>
                </div>
              </div>
            )}

            {section === 'about' && (
              <div className="flex flex-col items-center py-10">
                <img src="/logo.png" alt="WaLiSSH" className="w-20 h-20 rounded-xl mb-4" />
                <h3 className="text-lg font-semibold mb-1" style={{ color: colors.text }}>WaLiSSH</h3>
                <p className="text-[13px] mb-6" style={{ color: colors.textDim }}>v0.1.0 · AI + SSH 智能终端</p>
                <div className="w-full max-w-sm p-4 rounded-lg text-[13px] space-y-3" style={{ backgroundColor: colors.bgInput }}>
                  {[
                    ['前端', 'Tauri 2.0 + React 19'],
                    ['后端', 'Spring AI + Google ADK'],
                    ['构建', 'Vite 7 + TypeScript'],
                  ].map(([k, v]) => (
                    <div key={k} className="flex justify-between">
                      <span style={{ color: colors.textDim }}>{k}</span>
                      <span style={{ color: colors.textSecondary }}>{v}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>

        {/* ── 底栏：统一操作按钮 ── */}
        <div
          className="flex items-center justify-end gap-3 px-6 py-3.5 shrink-0"
          style={{ backgroundColor: colors.bgPrimary, borderTop: `1px solid ${colors.border}` }}
        >
          <button
            onClick={handleCancel}
            className="px-5 py-2 rounded-md text-[13px] font-medium transition-colors"
            style={{
              backgroundColor: colors.bgTertiary,
              border: `1px solid ${colors.border}`,
              color: colors.textSecondary,
            }}
          >
            取消
          </button>
          <button
            onClick={handleSave}
            disabled={!hasChanges}
            className="px-5 py-2 rounded-md text-[13px] font-medium transition-colors"
            style={{
              backgroundColor: hasChanges ? colors.bgTertiary : colors.bgInput,
              border: `1px solid ${colors.border}`,
              color: hasChanges ? colors.text : colors.textDim,
              cursor: hasChanges ? 'pointer' : 'not-allowed',
            }}
          >
            保存
          </button>
          <button
            onClick={handleSaveAndClose}
            disabled={!hasChanges}
            className="px-5 py-2 rounded-md text-[13px] font-medium transition-colors"
            style={{
              backgroundColor: hasChanges ? colors.accent : colors.bgInput,
              color: hasChanges ? '#fff' : colors.textDim,
              cursor: hasChanges ? 'pointer' : 'not-allowed',
            }}
          >
            保存并关闭
          </button>
        </div>

        {/* ── 右下角拖拽缩放手柄 ── */}
        <div
          onMouseDown={onResizeMouseDown}
          className="absolute bottom-0 right-0 w-5 h-5 cursor-se-resize flex items-end justify-end p-0.5"
          style={{ color: colors.textDim }}
        >
          <svg width="10" height="10" viewBox="0 0 10 10" fill="currentColor">
            <circle cx="8" cy="2" r="1" />
            <circle cx="8" cy="5" r="1" />
            <circle cx="5" cy="5" r="1" />
            <circle cx="8" cy="8" r="1" />
            <circle cx="5" cy="8" r="1" />
            <circle cx="2" cy="8" r="1" />
          </svg>
        </div>
      </div>
    </div>
  )
}
