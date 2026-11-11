import { useConnectionStore } from '../stores/connectionStore'
import { useThemeStore, themes } from '../stores/themeStore'
import { ConnectionStatus } from '../types'

export function StatusBar() {
  const { connections, serverStatus, currentConnectionId } = useConnectionStore()
  const theme = useThemeStore(s => s.currentTheme)
  const online = connections.filter(c => c.status === ConnectionStatus.CONNECTED).length
  const current = connections.find(c => c.id === currentConnectionId)

  return (
    <footer className="nox-statusbar">
      <span className="nox-status-brand">N/S</span>
      <span className="nox-service-status">
        <i data-online={serverStatus.connected} />
        {serverStatus.connected ? '服务在线' : '服务不可达'}
      </span>
      <span className="nox-status-item">
        <span className="u-num">{online}</span>
        <span>/ {connections.length} 台已连接</span>
      </span>
      {current && (
        <span className="nox-status-item u-mono" style={{ color: 'var(--ink-4)' }}>
          {current.username}@{current.host}
        </span>
      )}
      <span className="nox-status-spacer" />
      <span className="nox-status-theme">{themes[theme].label}</span>
      <span className="u-mono">NOX-SHELL v0.1.0</span>
    </footer>
  )
}
