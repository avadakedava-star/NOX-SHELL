import { NoxMark } from './NoxBrand'
import { useThemeStore, themes } from '../stores/themeStore'
import { useConnectionStore } from '../stores/connectionStore'
import { ConnectionStatus } from '../types'
import { Icon } from './Icon'

interface WelcomePanelProps {
  onOpenConnection: () => void
  onOpenAssistant: () => void
  onOpenServers?: () => void
}

export function WelcomePanel({ onOpenConnection, onOpenAssistant }: WelcomePanelProps) {
  // onOpenServers 由终端宿主转发，当前欢迎页通过“最近使用”直接发起连接，无需额外入口
  const { currentTheme, setTheme } = useThemeStore()
  const connections = useConnectionStore(s => s.connections)
  const selectConnection = useConnectionStore(s => s.selectConnection)
  const connect = useConnectionStore(s => s.connect)

  const online = connections.filter(c => c.status === ConnectionStatus.CONNECTED).length
  const recent = [...connections]
    .sort((a, b) => b.updatedAt - a.updatedAt)
    .slice(0, 4)

  return (
    <section className="nox-welcome">
      <div className="nox-panel-heading">
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
          <span className="nox-prompt">&gt;_</span> 工作台
        </span>
        <span className="u-mono" style={{ fontSize: 9, color: 'var(--ink-4)' }}>
          {online > 0 ? `${online} ACTIVE` : 'NO ACTIVE SESSION'}
        </span>
      </div>

      <div className="nox-welcome-scroll">
        <div className="nox-hero">
          <div className="nox-eyebrow"><span /> YOUR MACHINES. YOUR COMMAND. AI AT YOUR SIDE.</div>

          <div className="nox-wordmark" aria-label="NOX-SHELL">
            <span>NOX</span>
            <NoxMark className="nox-hero-mark" />
            <span className="nox-wordmark-sub">SHELL<span>_</span></span>
          </div>

          <div>
            <h1>接入你的下一台机器。</h1>
            <p className="nox-hero-description" style={{ marginTop: 8 }}>
              从一条命令开始，让复杂的运维清晰起来。<br />
              SSH 终端与 AI 助手，在同一个工作空间。
            </p>
          </div>

          <div className="nox-hero-actions">
            <button className="nox-button-primary" onClick={onOpenConnection}>
              <Icon.Plus size={13} /> 新建 SSH 连接
            </button>
            <button className="nox-button-secondary" onClick={onOpenAssistant}>
              <Icon.Spark size={13} /> 打开 AI 助手
            </button>
          </div>

          <div className="nox-command-line">
            <span>nox</span>
            <span>~</span>
            <span>$</span>
            <span>{connections.length === 0 ? '等待添加第一台服务器' : online > 0 ? `${online} 个会话在线` : '选择一台服务器建立连接'}</span>
            <i />
          </div>

          {recent.length > 0 ? (
            <div>
              <span className="nox-section-caption">最近使用</span>
              <div style={{ border: '1px solid var(--line)', borderRadius: 'var(--r-md)', overflow: 'hidden' }}>
                {recent.map((conn, index) => (
                  <button
                    key={conn.id}
                    className="nox-term-recent"
                    style={{
                      width: '100%',
                      display: 'flex',
                      alignItems: 'center',
                      gap: 9,
                      padding: '9px 12px',
                      borderRadius: 0,
                      fontSize: 11.5,
                      borderTop: index === 0 ? 'none' : '1px solid var(--line-soft)',
                      color: 'var(--ink-2)',
                      background: 'transparent',
                      marginTop: 0,
                    }}
                    onClick={() => {
                      selectConnection(conn.id)
                      if (conn.status === ConnectionStatus.DISCONNECTED) void connect(conn.id)
                    }}
                  >
                    <span
                      className="nox-dot"
                      data-tone={conn.status === ConnectionStatus.CONNECTED ? 'ok' : conn.status === ConnectionStatus.FAILED ? 'danger' : undefined}
                      data-pulse={conn.status === ConnectionStatus.CONNECTING ? 'true' : undefined}
                      style={{ color: conn.status === ConnectionStatus.CONNECTING ? 'var(--warn)' : undefined }}
                    />
                    <span style={{ fontWeight: 500, color: 'var(--ink)' }}>{conn.name}</span>
                    <span className="u-mono" style={{ fontSize: 10, color: 'var(--ink-3)' }}>{conn.username}@{conn.host}</span>
                    <span style={{ marginLeft: 'auto', fontSize: 10, color: conn.status === ConnectionStatus.CONNECTED ? 'var(--ok)' : 'var(--ink-4)' }}>
                      {conn.status === ConnectionStatus.CONNECTED ? '已连接' : '连接 →'}
                    </span>
                  </button>
                ))}
              </div>
            </div>
          ) : (
            <div className="nox-workflow">
              <div>
                <span className="nox-workflow-icon">&gt;_</span>
                <h2>直达终端</h2>
                <p>连接、切换与管理<br />你的远程服务器</p>
              </div>
              <div>
                <span className="nox-workflow-icon">✳</span>
                <h2>让 AI 协作</h2>
                <p>用自然语言提问<br />逐步查看执行结果</p>
              </div>
              <div>
                <span className="nox-workflow-icon">≋</span>
                <h2>掌握任务进度</h2>
                <p>跟踪长时间任务<br />随时查看运行状态</p>
              </div>
            </div>
          )}
        </div>

        <div className="nox-welcome-bottom">
          <span className="u-mono" style={{ fontSize: 9, color: 'var(--ink-4)' }}>
            {connections.length.toString().padStart(2, '0')} SAVED HOSTS
          </span>
          <div className="nox-theme-switch" aria-label="选择配色">
            <span>界面配色</span>
            {Object.values(themes).map(theme => (
              <button
                key={theme.name}
                title={theme.label}
                aria-label={theme.label}
                aria-pressed={currentTheme === theme.name}
                onClick={() => setTheme(theme.name)}
                style={{ '--swatch': theme.colors.accent } as React.CSSProperties}
              />
            ))}
          </div>
        </div>
      </div>
    </section>
  )
}
