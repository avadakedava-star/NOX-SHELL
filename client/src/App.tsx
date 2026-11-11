import './index.css'
import { MainView } from './views/MainView'
import { useEffect } from 'react'
import { useConnectionStore } from './stores/connectionStore'
import { useThemeStore } from './stores/themeStore'

function App() {
  const startHeartbeat = useConnectionStore((s) => s.startHeartbeat)
  const stopHeartbeat = useConnectionStore((s) => s.stopHeartbeat)
  const currentTheme = useThemeStore((s) => s.currentTheme)
  const colors = useThemeStore((s) => s.colors)

  useEffect(() => {
    // 应用启动时开启心跳检测
    startHeartbeat()
    return () => stopHeartbeat()
  }, [startHeartbeat, stopHeartbeat])

  // 同步主题 class 到 body，供 CSS 变量使用
  useEffect(() => {
    document.body.className = `theme-${currentTheme}`
    for (const [key, value] of Object.entries(colors)) {
      document.body.style.setProperty(`--nox-${key}`, value)
    }
    document.body.style.colorScheme = currentTheme === 'light' ? 'light' : 'dark'
  }, [currentTheme, colors])

  return <MainView />
}

export default App
