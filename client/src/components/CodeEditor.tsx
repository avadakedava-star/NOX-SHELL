import Editor from '@monaco-editor/react'
import { useThemeStore } from '../stores/themeStore'

export function CodeEditor() {
  const { colors, currentTheme } = useThemeStore()
  return (
    <div className="flex-1 flex flex-col" style={{ backgroundColor: colors.bgPrimary }}>
      {/* 编辑器标题栏 */}
      <div className="h-9 border-b flex items-center px-2" style={{ backgroundColor: colors.bgSecondary, borderColor: colors.border }}>
        <div className="px-3 py-1 text-sm border-t-2 rounded-t" style={{ color: colors.text, backgroundColor: colors.bgPrimary, borderColor: colors.accent }}>
          editor.js
        </div>
      </div>

      {/* Monaco Editor */}
      <div className="flex-1">
        <Editor
          height="100%"
          defaultLanguage="javascript"
          theme={currentTheme === 'light' ? 'light' : 'vs-dark'}
          defaultValue={`// NOX-SHELL Code Editor
// 使用 AI Agent 协助编写代码

function hello() {
  console.log("Hello from NOX-SHELL!");
}
`}
          options={{
            fontSize: 14,
            fontFamily: "'JetBrains Mono', 'Fira Code', Consolas, monospace",
            minimap: { enabled: true },
            scrollBeyondLastLine: false,
            automaticLayout: true,
            tabSize: 2,
            wordWrap: 'on',
            lineNumbers: 'on',
            renderWhitespace: 'selection',
            padding: { top: 10 },
          }}
        />
      </div>
    </div>
  )
}
