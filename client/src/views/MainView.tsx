import { useState, useEffect, useCallback } from 'react'
import { Header } from '../components/Header'
import { ActivityBar } from '../components/ActivityBar'
import { LeftSidebar } from '../components/LeftSidebar'
import { RightSidebar } from '../components/RightSidebar'
import { HistorySidebar } from '../components/HistorySidebar'
import { TerminalPanel } from '../components/TerminalPanel'
import { Settings } from '../components/Settings'
import { SSHConnectionModal } from '../components/SSHConnectionModal'
import { useThemeStore } from '../stores/themeStore'

type TabId = 'servers' | 'files' | 'sftp' | 'extensions'

export function MainView() {
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [sshModalOpen, setSshModalOpen] = useState(false)
  const [activeTab, setActiveTab] = useState<TabId>('servers')
  const [sidebarVisible, setSidebarVisible] = useState(true)
  const [sidebarWidth, setSidebarWidth] = useState(260)
  const [chatVisible, setChatVisible] = useState(true)
  const [chatWidth, setChatWidth] = useState(560)
  const [historyVisible, setHistoryVisible] = useState(false)
  const [historyWidth, setHistoryWidth] = useState(280)
  const [terminalVisible, setTerminalVisible] = useState(true)
  const [isResizingSidebar, setIsResizingSidebar] = useState(false)
  const [isResizingChat, setIsResizingChat] = useState(false)
  const [isResizingHistory, setIsResizingHistory] = useState(false)

  const { colors } = useThemeStore()

  // Sidebar 拖拽
  const handleSidebarResizeStart = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    setIsResizingSidebar(true)
    const startX = e.clientX
    const startWidth = sidebarWidth

    const onMouseMove = (moveEvent: MouseEvent) => {
      const newWidth = Math.max(180, Math.min(500, startWidth + (moveEvent.clientX - startX)))
      setSidebarWidth(newWidth)
    }

    const onMouseUp = () => {
      setIsResizingSidebar(false)
      document.removeEventListener('mousemove', onMouseMove)
      document.removeEventListener('mouseup', onMouseUp)
      document.body.style.cursor = ''
    }

    document.addEventListener('mousemove', onMouseMove)
    document.addEventListener('mouseup', onMouseUp)
    document.body.style.cursor = 'col-resize'
  }, [sidebarWidth])

  // Chat 拖拽
  const handleChatResizeStart = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    setIsResizingChat(true)
    const startX = e.clientX
    const startWidth = chatWidth

    const onMouseMove = (moveEvent: MouseEvent) => {
      const newWidth = Math.max(300, Math.min(700, startWidth - (moveEvent.clientX - startX)))
      setChatWidth(newWidth)
    }

    const onMouseUp = () => {
      setIsResizingChat(false)
      document.removeEventListener('mousemove', onMouseMove)
      document.removeEventListener('mouseup', onMouseUp)
      document.body.style.cursor = ''
    }

    document.addEventListener('mousemove', onMouseMove)
    document.addEventListener('mouseup', onMouseUp)
    document.body.style.cursor = 'col-resize'
  }, [chatWidth])

  // History 拖拽
  const handleHistoryResizeStart = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    setIsResizingHistory(true)
    const startX = e.clientX
    const startWidth = historyWidth

    const onMouseMove = (moveEvent: MouseEvent) => {
      const newWidth = Math.max(200, Math.min(450, startWidth - (moveEvent.clientX - startX)))
      setHistoryWidth(newWidth)
    }

    const onMouseUp = () => {
      setIsResizingHistory(false)
      document.removeEventListener('mousemove', onMouseMove)
      document.removeEventListener('mouseup', onMouseUp)
      document.body.style.cursor = ''
    }

    document.addEventListener('mousemove', onMouseMove)
    document.addEventListener('mouseup', onMouseUp)
    document.body.style.cursor = 'col-resize'
  }, [historyWidth])

  useEffect(() => {
    document.body.style.backgroundColor = colors.bgPrimary
    document.body.style.color = colors.text
  }, [colors])

  return (
    <div className="w-screen h-screen flex flex-col overflow-hidden" style={{ backgroundColor: colors.bgPrimary }}>
      {/* ===== 顶部标题栏 ===== */}
      <Header
        onToggleSidebar={() => setSidebarVisible(!sidebarVisible)}
        sidebarVisible={sidebarVisible}
        onToggleTerminal={() => setTerminalVisible(!terminalVisible)}
        terminalVisible={terminalVisible}
        onToggleChat={() => setChatVisible(!chatVisible)}
        chatVisible={chatVisible}
        onOpenSSHModal={() => setSshModalOpen(true)}
      />

      {/* ===== 主体区域 ===== */}
      <div className="flex-1 flex overflow-hidden">
        {/* 左侧：ActivityBar + Sidebar */}
        {sidebarVisible && (
          <>
            <ActivityBar activeTab={activeTab} onTabChange={setActiveTab} onOpenSettings={() => setSettingsOpen(true)} />

            <div
              className="flex-shrink-0 transition-none overflow-hidden relative"
              style={{ width: sidebarWidth }}
            >
              <LeftSidebar activeTab={activeTab} />
            </div>

            {/* Sidebar 拖拽条 */}
            <div
              className="w-1.5 h-full cursor-col-resize relative z-50 flex-shrink-0"
              style={{ backgroundColor: isResizingSidebar ? colors.accent : 'transparent' }}
              onMouseDown={handleSidebarResizeStart}
            >
              <div
                className={`absolute inset-y-0 -left-[3px] -right-[3px] ${isResizingSidebar ? '' : 'hover:bg-blue-500/30'} rounded-full`}
              />
            </div>
          </>
        )}

        {/* 中间：终端 */}
        <div className="flex-1 flex flex-col min-w-0 min-h-0 overflow-hidden">
          {terminalVisible ? (
            <TerminalPanel />
          ) : (
            <div className="flex-1 flex items-center justify-center">
              <p className="text-sm" style={{ color: colors.textDim }}>终端已隐藏 (按 ⌘` 显示)</p>
            </div>
          )}
        </div>

        {/* 右侧：AI 对话面板 */}
        {chatVisible && (
          <>
            {/* Chat 拖拽条 */}
            <div
              className="w-1.5 h-full cursor-col-resize relative z-50 flex-shrink-0 bg-[#3c3c3c]/40"
              style={{ backgroundColor: isResizingChat ? colors.accent : undefined }}
              onMouseDown={handleChatResizeStart}
            >
              <div
                className={`absolute inset-y-0 -left-[3px] -right-[3px] ${isResizingChat ? '' : 'hover:bg-blue-500/30'} rounded-full`}
              />
            </div>
            <RightSidebar
              width={chatWidth}
              historyVisible={historyVisible}
              onToggleHistory={() => setHistoryVisible(!historyVisible)}
            />
          </>
        )}

        {/* 最右侧：历史对话侧边栏 */}
        {historyVisible && (
          <>
            {/* History 拖拽条 */}
            <div
              className="w-1.5 h-full cursor-col-resize relative z-50 flex-shrink-0"
              style={{ backgroundColor: isResizingHistory ? colors.accent : 'transparent' }}
              onMouseDown={handleHistoryResizeStart}
            >
              <div
                className={`absolute inset-y-0 -left-[3px] -right-[3px] ${isResizingHistory ? '' : 'hover:bg-blue-500/30'} rounded-full`}
              />
            </div>
            <HistorySidebar width={historyWidth} onClose={() => setHistoryVisible(false)} />
          </>
        )}
      </div>

      {/* SSH 连接配置弹窗 */}
      <SSHConnectionModal open={sshModalOpen} onClose={() => setSshModalOpen(false)} />

      {/* 设置弹窗 */}
      <Settings open={settingsOpen} onClose={() => setSettingsOpen(false)} />
    </div>
  )
}
