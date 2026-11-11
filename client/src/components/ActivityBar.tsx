import { Icon } from './Icon'

export type TabId = 'servers' | 'files' | 'sftp' | 'extensions'

interface ActivityBarProps {
  activeTab: TabId
  onTabChange: (tab: TabId) => void
  onOpenSettings: () => void
}

const tabs: { id: TabId; icon: (p: { size?: number }) => React.ReactElement; label: string }[] = [
  { id: 'servers', icon: Icon.Server, label: 'SSH 服务器' },
  { id: 'files', icon: Icon.Folder, label: '文件目录' },
  { id: 'sftp', icon: Icon.Transfer, label: 'SFTP 传输' },
  { id: 'extensions', icon: Icon.Puzzle, label: '扩展' },
]

export function ActivityBar({ activeTab, onTabChange, onOpenSettings }: ActivityBarProps) {
  return (
    <div className="nox-activity">
      {tabs.map((tab) => {
        const Glyph = tab.icon
        return (
          <button
            key={tab.id}
            onClick={() => onTabChange(tab.id)}
            aria-current={activeTab === tab.id}
            aria-label={tab.label}
            title={tab.label}
          >
            <Glyph size={17} />
          </button>
        )
      })}
      <span className="nox-rail-sep" />
      <button onClick={onOpenSettings} title="设置" aria-label="设置" style={{ marginTop: 'auto' }}>
        <Icon.Settings size={17} />
      </button>
    </div>
  )
}
