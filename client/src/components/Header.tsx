import { useConnectionStore } from '../stores/connectionStore'
import { ConnectionStatus } from '../types'
import { NoxBrand } from './NoxBrand'
import { Icon } from './Icon'

interface HeaderProps {
  onToggleSidebar: () => void
  sidebarVisible: boolean
  onToggleTerminal: () => void
  terminalVisible: boolean
  onToggleChat: () => void
  chatVisible: boolean
  onOpenSSHModal: () => void
  onOpenSettings: () => void
}

export function Header({ onToggleSidebar, sidebarVisible, onToggleTerminal, terminalVisible, onToggleChat, chatVisible, onOpenSSHModal, onOpenSettings }: HeaderProps) {
  const { connections, currentConnectionId } = useConnectionStore()
  const current = connections.find(c => c.id === currentConnectionId)
  const online = current?.status === ConnectionStatus.CONNECTED
  const connecting = current?.status === ConnectionStatus.CONNECTING

  return (
    <header className="nox-header">
      <NoxBrand />
      <span className="nox-header-divider" />
      <span className="nox-header-label">REMOTE CONTROL</span>

      <div className="nox-header-connection" title={current ? `${current.username}@${current.host}:${current.port}` : '尚未选择服务器'}>
        <i className="nox-dot" data-tone={online ? 'ok' : connecting ? 'warn' : undefined} data-pulse={connecting ? 'true' : undefined} style={connecting ? { color: 'var(--warn)' } : undefined} />
        <span>{current ? current.name : '未选择服务器'}</span>
        {current && <span className="u-mono">{current.host}</span>}
      </div>

      <nav className="nox-header-controls" aria-label="工作区面板">
        <button aria-pressed={sidebarVisible} onClick={onToggleSidebar} title="显示或隐藏服务器列表">
          <Icon.PanelLeft size={14} /><span>服务器</span>
        </button>
        <button aria-pressed={terminalVisible} onClick={onToggleTerminal} title="显示或隐藏终端">
          <Icon.Terminal size={14} /><span>终端</span>
        </button>
        <button aria-pressed={chatVisible} onClick={onToggleChat} title="显示或隐藏 AI 助手">
          <Icon.Spark size={14} /><span>AI 助手</span>
        </button>
        <button onClick={onOpenSettings} title="设置" aria-label="设置">
          <Icon.Settings size={14} />
        </button>
      </nav>

      <button className="nox-header-add" onClick={onOpenSSHModal}>
        <Icon.Plus size={13} /><span>新建连接</span>
      </button>
    </header>
  )
}
