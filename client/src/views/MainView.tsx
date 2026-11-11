import { useState, useEffect } from 'react'
import { Header } from '../components/Header'
import { ActivityBar, type TabId } from '../components/ActivityBar'
import { LeftSidebar } from '../components/LeftSidebar'
import { RightSidebar } from '../components/RightSidebar'
import { HistorySidebar } from '../components/HistorySidebar'
import { TerminalPanel } from '../components/TerminalPanel'
import { Settings } from '../components/Settings'
import { SSHConnectionModal } from '../components/SSHConnectionModal'
import { PanelDivider } from '../components/PanelDivider'
import { useThemeStore } from '../stores/themeStore'
import { StatusBar } from '../components/StatusBar'
import {
  useLayoutStore,
  SIDEBAR_MIN,
  SIDEBAR_MAX,
  SIDEBAR_DEFAULT,
  CHAT_MIN,
  CHAT_MAX,
  CHAT_DEFAULT,
} from '../stores/layoutStore'

export function MainView() {
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [sshModalOpen, setSshModalOpen] = useState(false)
  const [activeTab, setActiveTab] = useState<TabId>('servers')
  const [historyVisible, setHistoryVisible] = useState(false)
  const [terminalVisible, setTerminalVisible] = useState(true)

  const { colors } = useThemeStore()
  const {
    sidebarWidth, chatWidth, sidebarVisible, chatVisible,
    setSidebarWidth, setChatWidth, setSidebarVisible, setChatVisible,
  } = useLayoutStore()

  // 窄屏：单栏模式，避免三栏被压瘪
  const [compact, setCompact] = useState(() => window.innerWidth < 900)
  useEffect(() => {
    const media = window.matchMedia('(max-width: 899px)')
    const update = () => {
      setCompact(media.matches)
      if (media.matches) setHistoryVisible(false)
    }
    update()
    media.addEventListener('change', update)
    return () => media.removeEventListener('change', update)
  }, [])

  useEffect(() => {
    document.body.style.backgroundColor = colors.bgPrimary
    document.body.style.color = colors.text
  }, [colors])

  const showAssistant = () => {
    setChatVisible(true)
    if (compact) setSidebarVisible(false)
  }

  const showServers = () => {
    setSidebarVisible(true)
    if (compact) setChatVisible(false)
  }

  return (
    <div className="nox-app w-screen h-screen flex flex-col overflow-hidden" style={{ backgroundColor: colors.bgPrimary }}>
      <Header
        onToggleSidebar={() => {
          if (compact) {
            const next = !sidebarVisible
            setSidebarVisible(next)
            if (next) setChatVisible(false)
            return
          }
          setSidebarVisible(!sidebarVisible)
        }}
        sidebarVisible={sidebarVisible}
        onToggleTerminal={() => {
          if (compact) {
            setTerminalVisible(true)
            setSidebarVisible(false)
            setChatVisible(false)
            setHistoryVisible(false)
            return
          }
          setTerminalVisible(!terminalVisible)
        }}
        terminalVisible={terminalVisible && (!compact || (!sidebarVisible && !chatVisible))}
        onToggleChat={() => {
          if (compact) {
            const next = !chatVisible
            setChatVisible(next)
            if (next) { setSidebarVisible(false); setHistoryVisible(false) }
            return
          }
          setChatVisible(!chatVisible)
        }}
        chatVisible={chatVisible}
        onOpenSSHModal={() => setSshModalOpen(true)}
        onOpenSettings={() => setSettingsOpen(true)}
      />

      <div className="nox-workspace flex-1 flex overflow-hidden min-h-0">
        {sidebarVisible && (
          <>
            <ActivityBar activeTab={activeTab} onTabChange={setActiveTab} onOpenSettings={() => setSettingsOpen(true)} />
            <div className="nox-sidebar-host flex-shrink-0 overflow-hidden relative" style={{ width: compact ? undefined : sidebarWidth }}>
              <LeftSidebar activeTab={activeTab} onRequestOpenModal={() => setSshModalOpen(true)} />
            </div>
            {!compact && (
              <PanelDivider
                label="调整服务器栏宽度"
                value={sidebarWidth}
                min={SIDEBAR_MIN}
                max={SIDEBAR_MAX}
                direction={1}
                onChange={setSidebarWidth}
                onReset={() => setSidebarWidth(SIDEBAR_DEFAULT)}
              />
            )}
          </>
        )}

        <div className="nox-terminal-host flex-1 flex flex-col min-w-0 min-h-0 overflow-hidden">
          {terminalVisible ? (
            <TerminalPanel
              onOpenConnection={() => setSshModalOpen(true)}
              onOpenAssistant={showAssistant}
              onOpenServers={showServers}
            />
          ) : (
            <div className="flex-1 flex items-center justify-center">
              <button className="nox-button-secondary" onClick={() => setTerminalVisible(true)}>终端已隐藏 · 重新打开</button>
            </div>
          )}
        </div>

        {chatVisible && (
          <>
            {!compact && (
              <PanelDivider
                label="调整 AI 助手宽度"
                value={chatWidth}
                min={CHAT_MIN}
                max={CHAT_MAX}
                direction={-1}
                onChange={setChatWidth}
                onReset={() => setChatWidth(CHAT_DEFAULT)}
              />
            )}
            <RightSidebar
              width={chatWidth}
              historyVisible={historyVisible}
              onToggleHistory={() => setHistoryVisible(!historyVisible)}
            />
          </>
        )}

        {historyVisible && (
          <HistorySidebar width={280} onClose={() => setHistoryVisible(false)} />
        )}
      </div>

      <StatusBar />

      <SSHConnectionModal open={sshModalOpen} onClose={() => setSshModalOpen(false)} />
      <Settings open={settingsOpen} onClose={() => setSettingsOpen(false)} />
    </div>
  )
}
