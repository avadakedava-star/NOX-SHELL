import { useState, useEffect, useCallback, useRef } from 'react'
import { useThemeStore, themes, type ThemeName } from '../stores/themeStore'
import { useConnectionStore } from '../stores/connectionStore'
import { getChatTimeoutMinutes, setChatTimeoutMinutes, getChatExecutionMode, setChatExecutionMode, type ChatExecutionMode } from '../api/agent'
import { isSoundEnabled, setSoundEnabled } from '../utils/sound'
import { NoxMark } from './NoxBrand'
import { Icon } from './Icon'

interface SettingsProps {
  open: boolean
  onClose: () => void
}

type Section = 'general' | 'appearance' | 'terminal' | 'about'
type TestStatus = 'idle' | 'testing' | 'success' | 'fail'

const SECTIONS: { id: Section; label: string; code: string; Glyph: (p: { size?: number }) => React.ReactElement }[] = [
  { id: 'general', label: '通用', code: 'GENERAL', Glyph: Icon.Settings },
  { id: 'appearance', label: '外观', code: 'THEME', Glyph: Icon.Spark },
  { id: 'terminal', label: '终端', code: 'TERMINAL', Glyph: Icon.Terminal },
  { id: 'about', label: '关于', code: 'ABOUT', Glyph: Icon.Info },
]

export function Settings({ open, onClose }: SettingsProps) {
  const { currentTheme, setTheme } = useThemeStore()
  const { serverUrl, setServerUrl } = useConnectionStore()

  const [inputUrl, setInputUrl] = useState(serverUrl)
  const [executionMode, setExecutionMode] = useState<ChatExecutionMode>(getChatExecutionMode())
  const [inputTimeout, setInputTimeout] = useState(getChatTimeoutMinutes())
  const [soundEnabled, setSoundEnabledState] = useState(isSoundEnabled())
  const [section, setSection] = useState<Section>('general')
  const [testStatus, setTestStatus] = useState<TestStatus>('idle')
  const [testMessage, setTestMessage] = useState('')

  const dialogRef = useRef<HTMLDivElement>(null)
  const [size, setSize] = useState({ w: 900, h: 640 })
  const dragging = useRef(false)
  const dragStart = useRef({ x: 0, y: 0, w: 0, h: 0 })

  useEffect(() => { setInputUrl(serverUrl) }, [serverUrl])

  const normalized = inputUrl.trim().replace(/\/+$/, '')
  const hasChanges = executionMode !== getChatExecutionMode()
    || normalized !== serverUrl
    || inputTimeout !== getChatTimeoutMinutes()
    || soundEnabled !== isSoundEnabled()

  const handleTest = useCallback(async () => {
    if (!normalized) return
    setTestStatus('testing')
    setTestMessage('')
    try {
      const res = await fetch(`${normalized}/api/v1/ssh/connection_list?userId=default`, { signal: AbortSignal.timeout(8000) })
      const json = await res.json()
      const ok = json.code === '0000'
      setTestStatus(ok ? 'success' : 'fail')
      setTestMessage(ok ? '接口响应正常，可以保存' : `接口返回异常：${json.info || '未知错误'}`)
    } catch {
      setTestStatus('fail')
      setTestMessage('无法建立请求。请确认地址、端口与跨域配置。')
    }
    setTimeout(() => setTestStatus('idle'), 4000)
  }, [normalized])

  const handleSave = useCallback(() => {
    if (normalized) setServerUrl(normalized)
    setChatExecutionMode(executionMode)
    setChatTimeoutMinutes(inputTimeout)
    setSoundEnabled(soundEnabled)
  }, [normalized, setServerUrl, executionMode, inputTimeout, soundEnabled])

  const handleCancel = useCallback(() => {
    setInputUrl(serverUrl)
    setExecutionMode(getChatExecutionMode())
    setInputTimeout(getChatTimeoutMinutes())
    setSoundEnabledState(isSoundEnabled())
    onClose()
  }, [serverUrl, onClose])

  const handleSaveAndClose = useCallback(() => {
    handleSave()
    onClose()
  }, [handleSave, onClose])

  const onResizeMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    dragging.current = true
    dragStart.current = { x: e.clientX, y: e.clientY, w: size.w, h: size.h }
    const onMove = (ev: MouseEvent) => {
      if (!dragging.current) return
      setSize({
        w: Math.max(700, Math.min(window.innerWidth - 24, dragStart.current.w + ev.clientX - dragStart.current.x)),
        h: Math.max(460, Math.min(window.innerHeight - 24, dragStart.current.h + ev.clientY - dragStart.current.y)),
      })
    }
    const onUp = () => {
      dragging.current = false
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
    }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  }, [size])

  useEffect(() => {
    if (!open) return
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') handleCancel()
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [open, handleCancel])

  if (!open) return null

  const themeList = (Object.entries(themes) as [ThemeName, typeof themes[ThemeName]][])

  return (
    <div className="dialog-scrim" onMouseDown={(e) => { if (e.target === e.currentTarget) handleCancel() }}>
      <div
        ref={dialogRef}
        className="dialog"
        data-size="settings"
        role="dialog"
        aria-modal="true"
        aria-labelledby="settings-title"
        style={{ width: size.w, height: size.h }}
      >
        <div className="dialog-head">
          <div className="dialog-head-id">
            <span className="dialog-glyph"><Icon.Settings size={14} /></span>
            <span className="dialog-title">
              <span className="u-caps">PREFERENCES</span>
              <strong id="settings-title">设置</strong>
            </span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            {hasChanges && <span className="u-caps" style={{ color: 'var(--signal)' }}>未保存</span>}
            <button className="nox-icon-btn" onClick={handleCancel} aria-label="关闭设置">
              <Icon.X size={14} />
            </button>
          </div>
        </div>

        <div className="settings-shell">
          <nav className="settings-nav" aria-label="设置分区">
            {SECTIONS.map(({ id, label, Glyph }) => (
              <button key={id} aria-current={section === id} onClick={() => setSection(id)}>
                <Glyph size={14} />
                <span>{label}</span>
              </button>
            ))}
            <div className="settings-nav-foot">
              <span className="u-caps" style={{ color: 'var(--ink-4)' }}>NOX-SHELL v0.1.0</span>
            </div>
          </nav>

          <div className="settings-content">
            {section === 'general' && (
              <section>
                <div className="settings-section-head">
                  <h2>通用</h2>
                  <p>后端连接与对话行为。修改后需要保存才会生效。</p>
                </div>

                <div className="settings-field">
                  <label htmlFor="settings-url">服务端地址</label>
                  <div style={{ display: 'flex', gap: 6 }}>
                    <input
                      id="settings-url"
                      className="input input-mono"
                      value={inputUrl}
                      onChange={(e) => setInputUrl(e.target.value)}
                      placeholder="http://localhost:8091"
                      spellCheck={false}
                    />
                    <button
                      className="btn btn-ghost"
                      onClick={handleTest}
                      disabled={testStatus === 'testing' || !normalized}
                      style={{ flex: 'none', minWidth: 92 }}
                    >
                      {testStatus === 'testing' ? <><span className="spinner" /> 测试中</> :
                        testStatus === 'success' ? <><Icon.Check size={12} /> 正常</> :
                        testStatus === 'fail' ? <><Icon.X size={12} /> 失败</> :
                        '测试连接'}
                    </button>
                  </div>
                  <p className="field-hint">NOX-SHELL 后端 API 的完整地址，例如 http://localhost:8091。</p>
                  {testMessage && (
                    <div className="form-note" data-tone={testStatus === 'success' ? 'signal' : 'danger'}>
                      {testStatus === 'success' ? <Icon.Check size={13} /> : <Icon.Alert size={13} />}
                      <span>{testMessage}</span>
                    </div>
                  )}
                </div>

                <div className="settings-field">
                  <label htmlFor="settings-mode">对话模式</label>
                  <div className="select-wrap">
                    <select
                      id="settings-mode"
                      className="select"
                      value={executionMode}
                      onChange={(e) => setExecutionMode(e.target.value as ChatExecutionMode)}
                    >
                      <option value="standard">标准模式（默认）</option>
                      <option value="enhanced">增强模式</option>
                    </select>
                    <Icon.ChevronDown size={11} />
                  </div>
                  <p className="field-hint">
                    {executionMode === 'enhanced'
                      ? '接续已保存的历史对话，识别任务意图并补充上下文；历史恢复需要后端数据库可用。'
                      : '直接处理消息，只使用当前会话的近期对话。'}
                  </p>
                </div>

                <div className="settings-field">
                  <label htmlFor="settings-timeout">AI 对话超时</label>
                  <div className="slider-row">
                    <input
                      id="settings-timeout"
                      type="range"
                      className="slider"
                      min={1}
                      max={60}
                      value={inputTimeout}
                      onChange={(e) => setInputTimeout(Number(e.target.value))}
                    />
                    <output>{inputTimeout} 分钟</output>
                  </div>
                  <p className="field-hint">流式对话的最长等待时间。AI 与 SSH 命令串联执行时可能需要更久（默认 10 分钟）。</p>
                </div>

                <div className="settings-divider" />

                <div className="settings-row">
                  <span className="copy">
                    <strong>对话完成提示音</strong>
                    <span>回答完成或出错时播放一段短促提示音</span>
                  </span>
                  <button
                    type="button"
                    role="switch"
                    aria-checked={soundEnabled}
                    aria-label="对话完成提示音"
                    className="switch"
                    onClick={() => setSoundEnabledState(!soundEnabled)}
                  />
                </div>
              </section>
            )}

            {section === 'appearance' && (
              <section>
                <div className="settings-section-head">
                  <h2>外观</h2>
                  <p>选择后立即生效并自动记住。所有面板共用同一套语义色板。</p>
                </div>
                <div className="theme-grid">
                  {themeList.map(([name, config]) => (
                    <button
                      key={name}
                      aria-pressed={currentTheme === name}
                      onClick={() => setTheme(name)}
                      className="theme-card"
                      style={{ background: config.colors.bgPrimary }}
                    >
                      <span className="theme-card-swatches">
                        {[config.colors.bgPrimary, config.colors.bgSecondary, config.colors.accent, config.colors.green].map((c, i) => (
                          <i key={i} style={{ background: c, boxShadow: `inset 0 0 0 1px ${config.colors.border}` }} />
                        ))}
                      </span>
                      <span className="theme-card-name" style={{ color: config.colors.text }}>{config.label}</span>
                      <span className="theme-card-code" style={{ color: config.colors.textDim }}>{name}</span>
                    </button>
                  ))}
                </div>
              </section>
            )}

            {section === 'terminal' && (
              <section>
                <div className="settings-section-head">
                  <h2>终端</h2>
                  <p>终端渲染参数。此处为预览值，当前会话沿用终端面板的内置设置。</p>
                </div>

                <div className="settings-field">
                  <label htmlFor="settings-font">字体</label>
                  <div className="select-wrap">
                    <select id="settings-font" className="select" defaultValue="JetBrains Mono">
                      <option>JetBrains Mono</option>
                      <option>Fira Code</option>
                      <option>Cascadia Code</option>
                      <option>Source Code Pro</option>
                    </select>
                    <Icon.ChevronDown size={11} />
                  </div>
                  <p className="field-hint">需要在本机安装对应字体；缺失时回退到 Cascadia Code / Consolas。</p>
                </div>

                <div className="settings-field">
                  <label htmlFor="settings-fontsize">字号</label>
                  <div className="slider-row">
                    <input id="settings-fontsize" type="range" className="slider" min={10} max={24} defaultValue={13} />
                    <output>13px</output>
                  </div>
                </div>

                <div className="form-note" data-tone="warn">
                  <Icon.Info size={13} />
                  <span>字体与字号目前尚未接入终端渲染，修改不会影响现有会话。</span>
                </div>
              </section>
            )}

            {section === 'about' && (
              <section>
                <div className="settings-section-head">
                  <h2>关于</h2>
                  <p>一个把 SSH 终端与 AI 执行过程放在同一工作空间的运维台。</p>
                </div>
                <div className="about-block">
                  <NoxMark />
                  <h3>NOX-SHELL</h3>
                  <p>v0.1.0 · AI + SSH 智能终端</p>
                  <dl className="about-list">
                    {[
                      ['前端', 'Tauri 2.0 + React 19'],
                      ['后端', 'Spring AI + Google ADK'],
                      ['构建', 'Vite 7 + TypeScript'],
                      ['终端', 'xterm.js + WebGL'],
                    ].map(([k, v]) => (
                      <div key={k}>
                        <dt>{k}</dt>
                        <dd>{v}</dd>
                      </div>
                    ))}
                  </dl>
                </div>
              </section>
            )}
          </div>
        </div>

        <div className="dialog-foot">
          <span className="dialog-foot-hint"><kbd>Esc</kbd> 关闭{hasChanges ? ' · 有未保存的修改' : ''}</span>
          <div className="dialog-foot-actions">
            <button className="btn btn-ghost" onClick={handleCancel}>取消</button>
            <button className="btn btn-ghost" onClick={handleSave} disabled={!hasChanges}>保存</button>
            <button className="btn btn-primary" onClick={handleSaveAndClose} disabled={!hasChanges}>保存并关闭</button>
          </div>
        </div>

        <div className="dialog-grip" onMouseDown={onResizeMouseDown} title="拖拽调整窗口大小">
          <svg width="8" height="8" viewBox="0 0 8 8" fill="currentColor">
            <circle cx="7" cy="1" r=".9" /><circle cx="7" cy="4" r=".9" /><circle cx="4" cy="4" r=".9" />
            <circle cx="7" cy="7" r=".9" /><circle cx="4" cy="7" r=".9" /><circle cx="1" cy="7" r=".9" />
          </svg>
        </div>
      </div>
    </div>
  )
}
