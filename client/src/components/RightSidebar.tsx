import { memo, useEffect, useMemo, useRef, useState } from 'react'
import { useThemeStore, type ThemeColors } from '../stores/themeStore'
import { useAgentStore } from '../stores/agentStore'
import { useConnectionStore } from '../stores/connectionStore'
import { ConnectionStatus } from '../types'
import { CommandApprovalCard } from './CommandApprovalCard'
import { TaskProgressPanel } from './TaskProgressPanel'
import type {
  AgentMessage,
  AgentRunStage,
  AgentRunTrace,
  AgentTimelineEntry,
  AgentToolStatus,
  AgentToolTrace,
} from '../types'

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/**
 * 安全清理 HTML：使用浏览器原生 DOMParser 解析后移除危险节点和属性。
 * 零依赖，防止 XSS（on* 事件属性、javascript: 协议等）。
 */
function sanitizeHtml(html: string): string {
  const doc = new DOMParser().parseFromString(html, 'text/html')
  const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_ELEMENT)
  const elements: Element[] = []
  let node = walker.currentNode as Element | null
  while (node) {
    if (node.nodeType === Node.ELEMENT_NODE) elements.push(node)
    node = walker.nextNode() as Element | null
  }
  for (const el of elements) {
    // 移除危险标签
    const tag = el.tagName.toLowerCase()
    if (['script', 'iframe', 'object', 'embed', 'link', 'meta', 'base', 'form'].includes(tag)) {
      el.remove()
      continue
    }
    // 移除所有 on* 事件属性和危险属性
    for (const attr of Array.from(el.attributes)) {
      const name = attr.name.toLowerCase()
      if (name.startsWith('on')) {
        el.removeAttribute(attr.name)
        continue
      }
      // href/src 中 javascript: data: 协议检查
      if ((name === 'href' || name === 'src') && /^\s*(javascript|data|vbscript):/i.test(attr.value)) {
        el.removeAttribute(attr.name)
      }
    }
  }
  return doc.body.innerHTML
}

function formatMarkdown(text: string): string {
  const codeBlocks: string[] = []
  let html = escapeHtml(text).replace(/```(\w*)\n?([\s\S]*?)```/g, (_match, lang, code) => {
    const placeholder = `__CODE_BLOCK_${codeBlocks.length}__`
    const language = lang ? `<div style="font-size:10px;color:var(--code-text,#8b949e);opacity:0.5;margin-bottom:6px;text-transform:uppercase;letter-spacing:.08em">${lang}</div>` : ''
    codeBlocks.push(
      `<div class="code-block-wrapper" style="position:relative;margin:8px 0"><button class="code-copy-btn" data-code="${codeBlocks.length}" style="position:absolute;top:8px;right:8px;z-index:10;padding:3px 8px;border-radius:6px;font-size:10px;font-family:sans-serif;background:var(--code-border,#30363d);color:var(--code-text,#c9d1d9);border:1px solid var(--code-border,#30363d);cursor:pointer">复制</button><pre style="margin:0;padding:12px;border-radius:8px;overflow-x:auto;max-width:100%;font-size:11px;font-family:'JetBrains Mono',monospace;background:var(--code-bg,#0d1117);color:var(--code-text,#c9d1d9);border:1px solid var(--code-border,#30363d)">${language}<code>${code.trim()}</code></pre></div>`,
    )
    return placeholder
  })

  // 行内代码
  html = html.replace(/`([^`\n]+)`/g, '<code style="padding:1px 5px;border-radius:4px;font-size:11px;font-family:monospace;background:rgba(120,160,255,0.12);color:#79c0ff;border:1px solid rgba(120,160,255,0.15)">$1</code>')
  // 链接 [text](url) — url 已被 escapeHtml 转义，再确保无 javascript: 协议
  html = html.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer" style="color:#58a6ff;text-decoration:none;border-bottom:1px solid rgba(88,166,255,0.4)">$1</a>')
  // 裸链接
  html = html.replace(/(^|[^"'>])(https?:\/\/[^\s<]+)/g, '$1<a href="$2" target="_blank" rel="noopener noreferrer" style="color:#58a6ff;text-decoration:none;border-bottom:1px solid rgba(88,166,255,0.4)">$2</a>')
  // 表格
  html = html.replace(/(\|.+\|\n\|[-| :]+\|\n(?:\|.+\|\n?)+)/g, (match) => {
    const lines = match.trim().split('\n')
    const header = lines[0]
      .split('|')
      .filter((cell) => cell.trim())
      .map((cell) => `<th style="padding:6px 8px;border:1px solid #30363d;text-align:left;word-break:break-word">${cell.trim()}</th>`)
      .join('')
    const rows = lines.slice(2)
      .map((line) => {
        const cells = line
          .split('|')
          .filter((cell) => cell.trim())
          .map((cell) => `<td style="padding:6px 8px;border:1px solid #30363d;word-break:break-word">${cell.trim()}</td>`)
          .join('')
        return `<tr>${cells}</tr>`
      })
      .join('')
    return `<table style="border-collapse:collapse;width:100%;max-width:100%;margin:8px 0;font-size:12px;table-layout:fixed"><thead><tr>${header}</tr></thead><tbody>${rows}</tbody></table>`
  })
  // 标题
  html = html.replace(/^### (.+)$/gm, '<h3 style="font-size:13px;font-weight:600;margin:8px 0 4px">$1</h3>')
  html = html.replace(/^## (.+)$/gm, '<h2 style="font-size:14px;font-weight:700;margin:10px 0 4px">$1</h2>')
  html = html.replace(/^# (.+)$/gm, '<h1 style="font-size:15px;font-weight:700;margin:10px 0 6px">$1</h1>')
  // 分割线
  html = html.replace(/^---$/gm, '<hr style="border:none;border-top:1px solid var(--code-border,#30363d);margin:10px 0"/>')
  // 粗体、斜体、删除线
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  html = html.replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g, '$1<em>$2</em>')
  html = html.replace(/~~([^~]+)~~/g, '<del>$1</del>')
  // 无序列表
  html = html.replace(/(?:^|\n)((?:- .+(?:\n|$))+)/g, (_match, listText) => {
    const items = listText
      .trim()
      .split('\n')
      .map((line: string) => `<li style="margin:2px 0">${line.replace(/^\-\s+/, '')}</li>`)
      .join('')
    return `\n<ul style="margin:8px 0 8px 18px;padding:0">${items}</ul>`
  })
  // 有序列表
  html = html.replace(/(?:^|\n)((?:\d+\. .+(?:\n|$))+)/g, (_match, listText) => {
    const items = listText
      .trim()
      .split('\n')
      .map((line: string) => `<li style="margin:2px 0">${line.replace(/^\d+\.\s+/, '')}</li>`)
      .join('')
    return `\n<ol style="margin:8px 0 8px 18px;padding:0">${items}</ol>`
  })
  // 引用块（blockquote）— escapeHtml 已将 > 转为 &gt;，故匹配 &gt;
  html = html.replace(/(?:^|\n)((?:&gt;\s?.+(?:\n|$))+)/g, (_match, quoteText) => {
    const lines = quoteText
      .trim()
      .split('\n')
      .map((line: string) => line.replace(/^&gt;\s?/, ''))
      .join('<br/>')
    return `\n<blockquote style="margin:8px 0;padding:6px 12px;border-left:3px solid var(--code-border,#30363d);background:rgba(120,160,255,0.06);color:var(--text-dim,#8b949e);font-size:12px;border-radius:0 6px 6px 0">${lines}</blockquote>`
  })

  const placeholders = codeBlocks.map((_, index) => `__CODE_BLOCK_${index}__`)
  const blocks = html
    .split(/\n{2,}/)
    .filter(Boolean)
    .map((block) => {
      if (placeholders.some((placeholder) => block.includes(placeholder))) {
        return block
      }
      if (/^<(h1|h2|h3|table|ul|ol|hr|a|p|blockquote)/.test(block.trim())) {
        return block
      }
      return `<p style="margin:0 0 8px;white-space:pre-wrap">${block.replace(/\n/g, '<br/>')}</p>`
    })
    .join('')

  const raw = codeBlocks.reduce(
    (content, block, index) => content.replace(`__CODE_BLOCK_${index}__`, block),
    blocks,
  )

  return sanitizeHtml(raw)
}

function formatDuration(startedAt: number, completedAt?: number): string {
  const duration = Math.max(0, (completedAt ?? Date.now()) - startedAt)
  if (duration < 1000) return '刚刚'
  if (duration < 60_000) return `${Math.round(duration / 1000)}s`
  return `${Math.floor(duration / 60_000)}m ${Math.round((duration % 60_000) / 1000)}s`
}

function formatTimestamp(ts: number): string {
  const date = new Date(ts)
  const hh = String(date.getHours()).padStart(2, '0')
  const mm = String(date.getMinutes()).padStart(2, '0')
  return `${hh}:${mm}`
}

function truncateText(text: string, maxLength = 900): string {
  if (text.length <= maxLength) return text
  return `${text.slice(0, maxLength)}\n...`
}

function isDispatchTool(tool?: Pick<AgentToolTrace, 'kind' | 'name' | 'sourceAgent'>): boolean {
  if (tool?.kind) return tool.kind === 'dispatch'
  return tool?.name === 'dispatchSubAgents'
    || tool?.name === 'planAndDispatchSubAgents'
    || Boolean(tool?.name && tool?.sourceAgent && tool.name === tool.sourceAgent)
}

function formatToolLabel(tool: AgentToolTrace): string {
  if (isDispatchTool(tool)) {
    return `子Agent派发 · ${tool.name}`
  }
  return tool.sourceAgent ? `${tool.sourceAgent} · ${tool.name}` : tool.name
}

function tailText(text: string, maxLength = 600): string {
  if (text.length <= maxLength) return text
  return `...\n${text.slice(-maxLength)}`
}

function getStageMeta(stage: AgentRunStage, colors: ThemeColors) {
  switch (stage) {
    case 'waiting_approval':
      return { label: '等待确认', color: colors.yellow, background: `${colors.yellow}18` }
    case 'queued':
      return { label: '排队中', color: colors.textSecondary, background: colors.bgTertiary }
    case 'thinking':
      return { label: '分析中', color: colors.yellow, background: `${colors.yellow}18` }
    case 'tool_running':
      return { label: '工具执行', color: colors.accent, background: colors.accentSoft }
    case 'responding':
      return { label: '生成中', color: colors.accent, background: colors.accentSoft }
    case 'completed':
      return { label: '已完成', color: colors.green, background: `${colors.green}18` }
    case 'error':
      return { label: '失败', color: colors.red, background: `${colors.red}18` }
    case 'stopped':
      return { label: '已停止', color: colors.yellow, background: `${colors.yellow}18` }
    default:
      return { label: '处理中', color: colors.textSecondary, background: colors.bgTertiary }
  }
}

function getToolStatusMeta(status: AgentToolStatus, colors: ThemeColors) {
  switch (status) {
    case 'blocked':
      return { label: '已拦截', color: colors.red, background: `${colors.red}18` }
    case 'unknown':
      return { label: '结果未知', color: colors.textSecondary, background: colors.bgTertiary }
    case 'pending':
      return { label: '等待中', color: colors.textSecondary, background: colors.bgTertiary }
    case 'running':
      return { label: '执行中', color: colors.accent, background: colors.accentSoft }
    case 'success':
      return { label: '成功', color: colors.green, background: `${colors.green}18` }
    case 'error':
      return { label: '失败', color: colors.red, background: `${colors.red}18` }
    default:
      return { label: '执行中', color: colors.accent, background: colors.accentSoft }
  }
}

function AssistantStatusCapsule({ trace, colors }: { trace: AgentRunTrace; colors: ThemeColors }) {
  const stageMeta = getStageMeta(trace.stage, colors)
  const isStreaming = !['completed', 'error', 'stopped', 'waiting_approval'].includes(trace.stage)
  const activeTool = [...trace.tools].reverse().find((tool) => tool.status === 'running')
  const latestTool = trace.tools[trace.tools.length - 1]
  const focusedTool = activeTool ?? latestTool
  const toolLabel = focusedTool ? formatToolLabel(focusedTool) : null
  const metrics = [
    trace.totalSteps > 0 ? `${trace.totalSteps} 步` : null,
    trace.totalToolCalls > 0 ? `${trace.totalToolCalls} 工具` : null,
  ].filter(Boolean).join(' · ')

  const stageIcon = trace.stage === 'completed' ? '✓'
    : trace.stage === 'error' ? '!'
    : trace.stage === 'tool_running' ? '⚙'
    : trace.stage === 'responding' ? '✎'
    : '○'

  return (
    <div className="absolute bottom-0 left-0 right-0 z-20 flex justify-center px-4 pb-2 pointer-events-none">
      <div
        className="flex max-w-full items-center gap-2 rounded-full border px-3 py-1 overflow-hidden shadow-sm backdrop-blur-sm"
        style={{
          backgroundColor: stageMeta.background,
          borderColor: `${stageMeta.color}33`,
          boxShadow: `0 6px 18px ${stageMeta.color}18`,
        }}
      >
        <span
          className={`w-3.5 h-3.5 rounded-full flex items-center justify-center text-[8px] font-bold flex-shrink-0 ${isStreaming ? 'stage-active' : ''}`}
          style={{ color: stageMeta.color, backgroundColor: `${stageMeta.color}18` }}
        >
          {stageIcon}
        </span>

        <span className="text-[10px] font-semibold whitespace-nowrap flex-shrink-0" style={{ color: stageMeta.color }}>
          {stageMeta.label}
        </span>

        {toolLabel && (
          <span className="text-[10px] truncate min-w-0 flex-1" style={{ color: colors.textSecondary }} title={toolLabel}>
            {toolLabel}
          </span>
        )}

        {isStreaming && (
          <span className="text-[10px] tabular-nums whitespace-nowrap flex-shrink-0" style={{ color: colors.textDim }}>
            {formatDuration(trace.startedAt)}
          </span>
        )}

        {metrics && (
          <span className="text-[10px] whitespace-nowrap flex-shrink-0" style={{ color: colors.textDim }}>
            {metrics}
          </span>
        )}
      </div>
    </div>
  )
}

function getTimelineMeta(entry: AgentTimelineEntry, colors: ThemeColors) {
  if (entry.kind === 'dispatch') {
    return { color: colors.yellow, background: `${colors.yellow}18`, icon: '⇄' }
  }
  if (entry.type === 'tool_call') {
    return { color: colors.accent, background: colors.accentSoft, icon: '⚙' }
  }
  if (entry.type === 'tool_result') {
    return {
      color: entry.status === 'success' ? colors.green : colors.red,
      background: entry.status === 'success' ? `${colors.green}14` : `${colors.red}14`,
      icon: entry.status === 'success' ? '✓' : entry.status === 'unknown' ? '?' : '✕',
    }
  }
  if (entry.type === 'round_end') {
    return { color: colors.yellow, background: `${colors.yellow}14`, icon: '⋯' }
  }
  if (entry.type === 'error') {
    return { color: colors.red, background: `${colors.red}14`, icon: '!' }
  }
  if (entry.type === 'done') {
    return { color: colors.green, background: `${colors.green}14`, icon: '✓' }
  }
  if (entry.type === 'text') {
    return { color: colors.accent, background: `${colors.accent}14`, icon: '✎' }
  }
  return { color: colors.textSecondary, background: colors.bgPrimary, icon: '•' }
}

function StreamingPreviewCard({
  text,
  isStreaming,
  colors,
}: {
  text?: string
  isStreaming: boolean
  colors: ThemeColors
}) {
  const previewText = text?.trim()

  return (
    <div className="mb-3">
      <div className="text-[10px] uppercase tracking-[0.12em] mb-2" style={{ color: colors.textDim }}>实时输出</div>
      <div
        className="rounded-xl border px-3 py-2.5"
        style={{
          background: `linear-gradient(180deg, ${colors.bgPrimary} 0%, ${colors.bgSecondary} 100%)`,
          borderColor: colors.border,
        }}
      >
        {previewText ? (
          <div className="text-[12px] leading-relaxed whitespace-pre-wrap break-words" style={{ color: colors.text }}>
            {tailText(previewText)}
            {isStreaming && <span className="inline-block ml-1 w-2 h-4 rounded-[2px] align-[-2px] animate-pulse" style={{ backgroundColor: colors.accent }} />}
          </div>
        ) : (
          <div className="text-[12px] leading-relaxed" style={{ color: colors.textDim }}>
            {isStreaming ? '模型暂未输出正文，当前会先实时展示步骤、工具与状态推进…' : '本轮暂无可展示的文本输出'}
          </div>
        )}
      </div>
    </div>
  )
}

function TimelineEntryCard({ entry, isLast }: { entry: AgentTimelineEntry; isLast: boolean }) {
  const { colors } = useThemeStore()
  const meta = getTimelineMeta(entry, colors)

  return (
    <div className="flex gap-2.5">
      <div className="flex flex-col items-center flex-shrink-0">
        <div
          className="w-6 h-6 rounded-full flex items-center justify-center text-[11px] font-semibold"
          style={{ backgroundColor: meta.background, color: meta.color, border: `1px solid ${meta.color}33` }}
        >
          {meta.icon}
        </div>
        {!isLast && <div className="w-px flex-1 mt-1" style={{ backgroundColor: colors.border }} />}
      </div>

      <div className="min-w-0 flex-1 pb-3">
        <div className="flex items-center gap-2 flex-wrap mb-1">
          <span className="text-[12px] font-medium" style={{ color: colors.text }}>{entry.label}</span>
          <span
            className="px-1.5 py-0.5 rounded-full text-[10px] font-semibold"
            style={{ color: meta.color, backgroundColor: meta.background }}
          >
            {getStageMeta(entry.stage, colors).label}
          </span>
        </div>
        {entry.detail && (
          <div className="text-[11px] leading-relaxed whitespace-pre-wrap break-words" style={{ color: colors.textSecondary }}>
            {truncateText(entry.detail, 240)}
          </div>
        )}
      </div>
    </div>
  )
}

function ToolCard({ tool, colors }: { tool: AgentToolTrace; colors: ThemeColors }) {
  const statusMeta = tool.rewriteAllowed
    ? { label: '需改写', color: colors.textSecondary, background: colors.bgTertiary }
    : getToolStatusMeta(tool.status, colors)
  const [expanded, setExpanded] = useState(false)
  const hasDetail = Boolean(tool.argumentsText || tool.resultText)
  const isDispatch = isDispatchTool(tool)

  // 状态图标
  const icon = tool.rewriteAllowed ? '↻' : tool.status === 'success' ? '✓' : tool.status === 'error' || tool.status === 'blocked' ? '✕' : tool.status === 'unknown' ? '?' : tool.status === 'running' ? '⟳' : '○'

  return (
    <div
      className="rounded-lg border overflow-hidden"
      style={{ backgroundColor: colors.bgPrimary, borderColor: `${statusMeta.color}30` }}
    >
      <button
        onClick={() => hasDetail && setExpanded(!expanded)}
        className="w-full flex items-center gap-2 px-3 py-2 text-left"
        style={{ cursor: hasDetail ? 'pointer' : 'default' }}
      >
        <span
          className="w-5 h-5 rounded-md flex items-center justify-center text-[11px] font-bold flex-shrink-0"
          style={{
            color: isDispatch ? colors.yellow : statusMeta.color,
            backgroundColor: isDispatch ? `${colors.yellow}18` : statusMeta.background,
          }}
        >
          {isDispatch ? '⇄' : icon}
        </span>
        <span className="text-[12px] font-medium truncate flex-1" style={{ color: colors.text }}>
          {formatToolLabel(tool)}
        </span>
        {isDispatch && (
          <span
            className="px-1.5 py-0.5 rounded-full text-[10px] font-semibold flex-shrink-0"
            style={{ color: colors.yellow, backgroundColor: `${colors.yellow}18` }}
          >
            子Agent
          </span>
        )}
        <span className="text-[10px] whitespace-nowrap" style={{ color: colors.textDim }}>
          {formatDuration(tool.startedAt, tool.status === 'running' ? undefined : tool.updatedAt)}
        </span>
        {hasDetail && (
          <svg
            className="w-3 h-3 flex-shrink-0 transition-transform"
            style={{ color: colors.textDim, transform: expanded ? 'rotate(180deg)' : '' }}
            viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
          >
            <polyline points="6 9 12 15 18 9"></polyline>
          </svg>
        )}
      </button>

      {tool.approvalId && <CommandApprovalCard approvalId={tool.approvalId} />}
      {expanded && hasDetail && (
        <div className="px-3 pb-2.5 panel-slide">
          {tool.argumentsText && (
            <div className="mb-2">
              <div className="text-[10px] uppercase tracking-[0.1em] mb-1" style={{ color: colors.textDim }}>参数</div>
              <pre
                className="m-0 rounded-lg p-2 text-[11px] overflow-auto"
                style={{
                  backgroundColor: colors.bgSecondary,
                  color: colors.textSecondary,
                  border: `1px solid ${colors.border}`,
                  maxHeight: 120,
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-word',
                }}
              >
                {truncateText(tool.argumentsText, 400)}
              </pre>
            </div>
          )}
          {tool.resultText && (
            <div>
              <div className="text-[10px] uppercase tracking-[0.1em] mb-1" style={{ color: colors.textDim }}>结果</div>
              <pre
                className="m-0 rounded-lg p-2 text-[11px] overflow-auto"
                style={{
                  backgroundColor: 'var(--code-bg, #0d1117)',
                  color: 'var(--code-text, #c9d1d9)',
                  border: `1px solid ${colors.border}`,
                  maxHeight: 180,
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-word',
                }}
              >
                {truncateText(tool.resultText)}
              </pre>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function AssistantTracePanel({ trace, previewText }: { trace: AgentRunTrace; previewText?: string }) {
  const { colors } = useThemeStore()
  const stageMeta = getStageMeta(trace.stage, colors)
  const timelineRef = useRef<HTMLDivElement>(null)
  const isStreaming = !['completed', 'error', 'stopped'].includes(trace.stage)
  const [collapsed, setCollapsed] = useState(false)
  const dispatchTools = trace.tools.filter((tool) => isDispatchTool(tool))
  const normalTools = trace.tools.filter((tool) => !isDispatchTool(tool))

  useEffect(() => {
    if (!timelineRef.current) return
    timelineRef.current.scrollTo({
      top: timelineRef.current.scrollHeight,
      behavior: 'smooth',
    })
  }, [trace.timeline.length, trace.updatedAt])

  // 阶段图标
  const stageIcon = trace.stage === 'completed' ? '✓' : trace.stage === 'error' ? '✕' : trace.stage === 'tool_running' ? '⚙' : trace.stage === 'thinking' ? '💭' : trace.stage === 'responding' ? '✎' : '○'

  return (
    <div
      className="mb-3 rounded-lg overflow-hidden"
      style={{
        backgroundColor: colors.bgSecondary,
        borderLeft: `3px solid ${stageMeta.color}`,
        border: `1px solid ${colors.border}`,
        borderLeftWidth: '3px',
        borderLeftColor: stageMeta.color,
      }}
    >
      {/* 标题栏：阶段图标 + 状态 + 耗时 + 折叠 */}
      <button
        onClick={() => setCollapsed(!collapsed)}
        className="w-full flex items-center gap-2.5 px-3 py-2 transition-colors hover:bg-white/[0.03]"
        style={{ borderBottom: collapsed ? 'none' : `1px solid ${colors.border}` }}
      >
        <span
          className={`w-7 h-7 rounded-lg flex items-center justify-center text-[14px] flex-shrink-0 ${isStreaming ? 'stage-active' : ''}`}
          style={{ color: stageMeta.color, backgroundColor: stageMeta.background }}
        >
          {stageIcon}
        </span>
        <div className="min-w-0 flex-1 text-left">
          <div className="flex items-center gap-2">
            <span className="text-[12px] font-semibold" style={{ color: colors.text }}>{stageMeta.label}</span>
            <span
              className="px-1.5 py-0.5 rounded text-[9px] font-semibold uppercase tracking-wider"
              style={{ color: stageMeta.color, backgroundColor: `${stageMeta.color}15` }}
            >
              {trace.stage}
            </span>
          </div>
          <div className="text-[11px] truncate mt-0.5" style={{ color: colors.textSecondary }}>{trace.statusText}</div>
        </div>
        <div className="flex items-center gap-2 flex-shrink-0">
          <span className="text-[10px] tabular-nums" style={{ color: colors.textDim }}>
            {formatDuration(trace.startedAt, trace.completedAt)}
          </span>
          <svg
            className="w-3.5 h-3.5 transition-transform"
            style={{ color: colors.textDim, transform: collapsed ? 'rotate(-90deg)' : '' }}
            viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
          >
            <polyline points="6 9 12 15 18 9"></polyline>
          </svg>
        </div>
      </button>

      {!collapsed && (
      <div className="px-3 py-2.5 panel-slide">

      {/* 步骤进度条 */}
      {trace.steps.length > 0 && (
        <div className="mb-3">
          <div className="flex items-center gap-1.5 mb-1.5">
            <span className="text-[10px] uppercase tracking-[0.1em] font-medium" style={{ color: colors.textDim }}>步骤</span>
            <span className="text-[10px]" style={{ color: colors.textDim }}>{trace.totalSteps} 步</span>
          </div>
          <div className="flex items-center gap-1">
            {trace.steps.map((step, idx) => (
              <div key={step.currentStep} className="flex items-center gap-1 flex-1 min-w-0">
                <div
                  className="flex-1 h-1.5 rounded-full overflow-hidden"
                  style={{ backgroundColor: colors.bgPrimary }}
                >
                  <div
                    className="h-full rounded-full transition-all"
                    style={{
                      width: step.shouldContinue === false ? '100%' : '60%',
                      backgroundColor: step.shouldContinue === false ? colors.green : colors.accent,
                    }}
                  />
                </div>
                {idx < trace.steps.length - 1 && (
                  <span className="text-[9px] flex-shrink-0" style={{ color: colors.textDim }}>→</span>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* 子 Agent 派发区 */}
      {dispatchTools.length > 0 && (
        <div className="mb-3">
          <div className="flex items-center gap-1.5 mb-2">
            <span className="text-[10px] uppercase tracking-[0.1em] font-medium" style={{ color: colors.textDim }}>子 Agent 派发</span>
            <span className="text-[10px]" style={{ color: colors.textDim }}>{dispatchTools.length} 次</span>
          </div>
          <div className="space-y-1.5">
            {dispatchTools.map((tool) => (
              <ToolCard key={tool.id} tool={tool} colors={colors} />
            ))}
          </div>
        </div>
      )}

      {/* 工具调用区 */}
      {normalTools.length > 0 && (
        <div className="mb-3">
          <div className="flex items-center gap-1.5 mb-2">
            <span className="text-[10px] uppercase tracking-[0.1em] font-medium" style={{ color: colors.textDim }}>工具记录</span>
            <span className="text-[10px]" style={{ color: colors.textDim }}>{normalTools.length} 条</span>
          </div>
          <div className="space-y-1.5">
            {normalTools.map((tool) => (
              <ToolCard key={tool.id} tool={tool} colors={colors} />
            ))}
          </div>
        </div>
      )}

      {/* 实时输出预览：仅在流式过程中且还没有最终 content 时显示 */}
      {isStreaming && !previewText?.trim() && (
        <StreamingPreviewCard text={trace.finalResponse} isStreaming={isStreaming} colors={colors} />
      )}

      {/* 事件流（折叠到二级） */}
      {trace.timeline.length > 0 && (
        <details className="mt-2">
          <summary
            className="text-[10px] uppercase tracking-[0.1em] cursor-pointer py-1 select-none"
            style={{ color: colors.textDim }}
          >
            事件流（{trace.timeline.length} 条）
          </summary>
          <div
            ref={timelineRef}
            className="rounded-xl border p-2.5 max-h-60 overflow-y-auto mt-1.5"
            style={{ backgroundColor: colors.bgPrimary, borderColor: colors.border }}
          >
            {trace.timeline.map((entry, index) => (
              <TimelineEntryCard key={entry.id} entry={entry} isLast={index === trace.timeline.length - 1} />
            ))}
          </div>
        </details>
      )}

      {/* 错误提示 */}
      {trace.error && (
        <div
          className="mt-3 rounded-xl border px-3 py-2 text-[12px] flex items-center gap-2"
          style={{ backgroundColor: `${colors.red}12`, borderColor: `${colors.red}40`, color: colors.red }}
        >
          <span className="w-5 h-5 rounded-md flex items-center justify-center font-bold flex-shrink-0" style={{ backgroundColor: `${colors.red}20` }}>!</span>
          <span className="break-words">{trace.error}</span>
        </div>
      )}
      </div>
      )}
    </div>
  )
}

const MessageBubble = memo(function MessageBubble({ message }: { message: AgentMessage }) {
  const { colors } = useThemeStore()
  const isUser = message.role === 'user'
  const isSystem = message.role === 'system'
  const isAssistant = message.role === 'assistant'
  const [copied, setCopied] = useState(false)
  const contentRef = useRef<HTMLDivElement>(null)
  const renderedContent = useMemo(
    () => (message.content ? formatMarkdown(message.content) : ''),
    [message.content],
  )

  const handleCopy = () => {
    navigator.clipboard.writeText(message.content).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    })
  }

  // 事件委托：处理代码块复制按钮点击
  useEffect(() => {
    const el = contentRef.current
    if (!el) return
    const onClick = (e: MouseEvent) => {
      const target = e.target as HTMLElement
      if (target.classList.contains('code-copy-btn')) {
        e.stopPropagation()
        const wrapper = target.closest('.code-block-wrapper')
        const codeEl = wrapper?.querySelector('code')
        if (codeEl) {
          navigator.clipboard.writeText(codeEl.textContent || '').then(() => {
            target.textContent = '已复制'
            setTimeout(() => { target.textContent = '复制' }, 1500)
          })
        }
      }
    }
    el.addEventListener('click', onClick)
    return () => el.removeEventListener('click', onClick)
  }, [message.content])

  const isEmpty = isAssistant && !message.content

  // 角色标签配置
  const roleLabel = isUser ? '我' : isSystem ? '系统' : 'WaLiSSH'
  const roleColor = isUser ? colors.accent : isSystem ? colors.red : colors.green
  const roleIcon = isUser ? '●' : isSystem ? '⚠' : '◆'

  return (
    <div className="group px-4 py-2 msg-enter">
      {/* 角色行：图标 + 名称 + 时间 */}
      <div className="flex items-center gap-2 mb-1.5">
        <span className="text-[10px] flex-shrink-0" style={{ color: roleColor }}>{roleIcon}</span>
        <span className="text-[11px] font-semibold" style={{ color: isUser ? colors.accent : isSystem ? colors.red : colors.text }}>{roleLabel}</span>
        <span className="text-[10px]" style={{ color: colors.textDim, opacity: 0.5 }}>{formatTimestamp(message.timestamp)}</span>
      </div>

      {/* 内容区域：全宽，无气泡圆角，扁平 */}
      <div
        className="text-[13px] leading-[1.7] relative ml-0.5"
        style={{
          color: isSystem ? colors.red : colors.text,
          overflowWrap: 'break-word',
          wordBreak: 'break-word',
          overflowX: 'auto',
          padding: isUser ? '8px 12px' : '0',
          borderRadius: isUser ? '8px' : '0',
          backgroundColor: isUser ? colors.bgTertiary : 'transparent',
        }}
      >
        {/* AI 思考/执行面板（独立区块，和最终回答视觉分离） */}
        {isAssistant && message.trace && <AssistantTracePanel trace={message.trace} previewText={message.content} />}

        {/* 最终回答内容 */}
        {message.content ? (
          <div ref={contentRef} dangerouslySetInnerHTML={{ __html: renderedContent }} />
        ) : isEmpty ? (
          <div className="flex items-center gap-1.5 py-1" style={{ color: colors.textDim }}>
            <span className="text-[12px]">思考中</span>
            <span className="flex gap-1">
              <span className="w-1.5 h-1.5 rounded-full loading-dot" style={{ backgroundColor: colors.accent }} />
              <span className="w-1.5 h-1.5 rounded-full loading-dot" style={{ backgroundColor: colors.accent }} />
              <span className="w-1.5 h-1.5 rounded-full loading-dot" style={{ backgroundColor: colors.accent }} />
            </span>
          </div>
        ) : null}

        {/* 复制按钮 */}
        {message.content && (
          <button
            onClick={handleCopy}
            className="absolute top-0 right-0 p-1 rounded transition-opacity"
            style={{
              color: copied ? colors.green : colors.textDim,
              opacity: 0,
            }}
            onMouseEnter={(e) => { e.currentTarget.style.opacity = '1' }}
            onMouseLeave={(e) => { e.currentTarget.style.opacity = '0' }}
            title={copied ? '已复制' : '复制'}
          >
            {copied ? (
              <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <polyline points="20 6 9 17 4 12"></polyline>
              </svg>
            ) : (
              <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
              </svg>
            )}
          </button>
        )}
      </div>
    </div>
  )
})

interface RightSidebarProps {
  width?: number
  historyVisible?: boolean
  onToggleHistory?: () => void
}

export function RightSidebar({ width = 560, historyVisible = false, onToggleHistory }: RightSidebarProps) {
  const { colors } = useThemeStore()
  const {
    sessions,
    currentSessionId,
    inputText,
    setInputText,
    isLoading,
    createSession,
    clearSessionMessages,
    sendMessage,
    stopGeneration,
    agents,
    currentAgentId,
    setCurrentAgent,
    loadAgents,
    loadPersistedSessions,
  } = useAgentStore()

  const { currentConnectionId, connections, getCurrentTerminalSession, serverStatus } = useConnectionStore()

 const currentSession = currentSessionId ? sessions.get(currentSessionId) : null
 const messagesEndRef = useRef<HTMLDivElement>(null)
 const textareaRef = useRef<HTMLTextAreaElement>(null)
 const [isFocused, setIsFocused] = useState(false)
 const [agentDropdownOpen, setAgentDropdownOpen] = useState(false)
 const inputHeightRef = useRef<number>(120)

 // 首次挂载时加载持久化会话
 useEffect(() => {
   loadPersistedSessions()
 }, [loadPersistedSessions])

 // 点击外部关闭下拉菜单
 const agentDropdownRef = useRef<HTMLDivElement>(null)
 useEffect(() => {
   if (!agentDropdownOpen) return
   const onClick = (e: MouseEvent) => {
     const target = e.target as Node
     if (agentDropdownRef.current && !agentDropdownRef.current.contains(target)) {
       setAgentDropdownOpen(false)
     }
   }
   document.addEventListener('mousedown', onClick)
   return () => document.removeEventListener('mousedown', onClick)
 }, [agentDropdownOpen])

 const currentConnection = connections.find((connection) => connection.id === currentConnectionId)
 const terminalSessionId = getCurrentTerminalSession()
 const isConnected = currentConnection?.status === ConnectionStatus.CONNECTED && !!terminalSessionId
 const autoScrollRef = useRef(true)
 const scrollContainerRef = useRef<HTMLDivElement>(null)
 const [showScrollToBottom, setShowScrollToBottom] = useState(false)

 const currentTrace = useMemo(() => {
    if (!currentSession) return null
    for (let index = currentSession.messages.length - 1; index >= 0; index -= 1) {
      const message = currentSession.messages[index]
      if (message.role === 'assistant' && message.trace) {
        return message.trace
      }
    }
    return null
  }, [currentSession])

 const isTraceActive = Boolean(
   currentTrace && !['completed', 'error', 'stopped'].includes(currentTrace.stage)
 )


  useEffect(() => {
    loadAgents()
  }, [loadAgents])

 useEffect(() => {
   const loaded=useAgentStore.getState()
   if (!loaded.currentSessionId && loaded.sessions.size === 0) {
     loaded.createSession()
   }
 }, [currentSessionId, sessions.size, createSession])

// 流式输出时仅在用户未手动上滚时自动滚到底部
const messageCount = currentSession?.messages.length ?? 0
const lastMessageLen = currentSession?.messages[messageCount - 1]?.content.length ?? 0
const traceRevision = currentTrace
  ? currentTrace.timeline.length + currentTrace.tools.length
  : 0
useEffect(() => {
  if (!autoScrollRef.current) return
  const el = scrollContainerRef.current
  if (!el) return
  // 等浏览器完成本轮布局，避免读取到更新前的 scrollHeight 导致差一点到底。
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
    })
  })
}, [messageCount, lastMessageLen, isLoading, traceRevision])

// textarea 双向自适应：先重置为 auto 再读取 scrollHeight，输入变少时也能收缩
useEffect(() => {
  const el = textareaRef.current
  if (!el) return
  el.style.height = 'auto'
  const next = Math.min(Math.max(el.scrollHeight, 60), 280)
  el.style.height = `${next}px`
  inputHeightRef.current = next
}, [inputText])

const handleSend = async () => {
  if (!inputText.trim() || !currentSessionId || isLoading) return
  const text = inputText.trim()
  setInputText('')

  // textarea 高度会在 inputText 变为空时通过 useEffect 自动收缩
  autoScrollRef.current = true
  await sendMessage(text, terminalSessionId)
}

const handleKeyDown = (e: React.KeyboardEvent) => {
  if (e.nativeEvent.isComposing || e.keyCode === 229) return
  if (e.key === 'Enter' && !e.shiftKey && !e.metaKey && !e.ctrlKey) {
    e.preventDefault()
    void handleSend()
  }
}

const currentAgent = agents.find((agent) => agent.agentId === currentAgentId)
// 放宽发送限制：未连接终端时仍可发送纯对话消息（agent 可不依赖终端）
const canSend = Boolean(inputText.trim() && currentSession && !isLoading && currentAgentId)

const handleScroll = () => {
   const el = scrollContainerRef.current
   if (!el) return
   const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80
   autoScrollRef.current = nearBottom
   setShowScrollToBottom(!nearBottom && !!currentSession && currentSession.messages.length > 0)
 }

 const scrollToBottom = (behavior: ScrollBehavior = 'smooth') => {
   const el = scrollContainerRef.current
   if (!el) return
 el.scrollTo({ top: el.scrollHeight, behavior })
   autoScrollRef.current = true
   setShowScrollToBottom(false)
 }

 return (
    <div
      className="flex flex-col h-full flex-shrink-0 relative"
      style={{ width, backgroundColor: colors.bgPrimary }}
    >
      {/* AI 思考/执行中的顶部扫描进度条 */}
      {isLoading && (
        <div className="ai-progress-bar" style={{ backgroundColor: `${colors.accent}20` }}>
          <style>{`.ai-progress-bar::after { background: linear-gradient(90deg, transparent, ${colors.accent}, transparent); }`}</style>
        </div>
      )}

      <div className="relative flex-1 min-h-0">
        <div ref={scrollContainerRef} className="h-full overflow-y-auto" onScroll={handleScroll}>
        {!currentSession ? (
          <div className="flex flex-col items-center justify-center h-full gap-3 px-6">
            <img src="/logo.png" alt="WaLiSSH" className="w-14 h-14 mb-2 opacity-60 rounded" />
            <h3 className="text-base font-medium" style={{ color: colors.text }}>开始对话</h3>
            <p className="text-xs text-center max-w-xs leading-relaxed" style={{ color: colors.textSecondary }}>
              连接 SSH 后，可以直接发起对话，并实时查看思考状态、步骤推进、工具调用和最终答案。
            </p>
          </div>
        ) : currentSession.messages.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full gap-5 px-6 py-8">
            <div
              className="w-16 h-16 rounded-2xl flex items-center justify-center text-2xl"
              style={{
                background: `linear-gradient(135deg, ${colors.accentSoft} 0%, ${colors.bgTertiary} 100%)`,
                border: `1px solid ${colors.border}`,
              }}
            >
              <img src="/logo.png" alt="WaLiSSH" className="w-10 h-10 rounded-xl" />
            </div>
            <div className="text-center">
              <p className="text-[15px] font-semibold mb-1" style={{ color: colors.text }}>WaLiSSH AI</p>
              <p className="text-xs" style={{ color: colors.textDim }}>实时事件流 · 工具标签 · 最终结论</p>
            </div>
            <div
              className="w-full max-w-xs rounded-2xl border p-3.5 text-[12px]"
              style={{ backgroundColor: colors.bgSecondary, borderColor: colors.border, color: colors.textSecondary }}
            >
              <div className="mb-2 font-medium flex items-center gap-1.5" style={{ color: colors.text }}>
                <span className="w-1 h-3 rounded-full" style={{ backgroundColor: colors.accent }} />
                能力
              </div>
              <div className="space-y-2">
                <div className="flex items-center gap-2">
                  <span style={{ color: colors.green }}>✓</span>
                  <span>每一步分析实时入面板</span>
                </div>
                <div className="flex items-center gap-2">
                  <span style={{ color: colors.green }}>✓</span>
                  <span>工具调用和结果分开显示</span>
                </div>
                <div className="flex items-center gap-2">
                  <span style={{ color: colors.green }}>✓</span>
                  <span>最终答案渲染在对话区</span>
                </div>
              </div>
            </div>
            <div className="w-full max-w-xs space-y-1.5">
              {[
                {
                  label: '检查服务器状态',
                  prompt: '检查服务器状态',
                  icon: '\u{1F4CA}',
                },
                {
                  label: '服务器有安装 Docker 吗？',
                  prompt: '服务器有安装 Docker 吗？如果有，请查看 Docker 版本和运行状态。',
                  icon: '\u{1F433}',
                },
                {
                  label: 'Docker 里部署了哪些应用？',
                  prompt: '请查看 Docker 里部署了哪些应用，列出容器名、镜像、端口和运行状态。',
                  icon: '\u{1F4E6}',
                },
                {
                  label: '安装 Arthas 并压测应用',
                  prompt: '帮我安装 Arthas，并对 Docker 里的应用进行压测。',
                  icon: '\u{1F680}',
                },
              ].map((item) => (
                <button
                  key={item.label}
                  onClick={() => setInputText(item.prompt)}
                  className="w-full flex items-center gap-2 text-left px-3 py-2 rounded-md text-xs transition-all hover:translate-x-1"
                  style={{
                    color: colors.textSecondary,
                    backgroundColor: colors.bgTertiary,
                    border: `1px solid ${colors.border}`,
                  }}
                  onMouseEnter={(e) => {
                    e.currentTarget.style.backgroundColor = colors.accentSoft
                    e.currentTarget.style.color = colors.accent
                    e.currentTarget.style.borderColor = `${colors.accent}40`
                  }}
                  onMouseLeave={(e) => {
                    e.currentTarget.style.backgroundColor = colors.bgTertiary
                    e.currentTarget.style.color = colors.textSecondary
                    e.currentTarget.style.borderColor = colors.border
                  }}
                >
                  <span className="text-[14px]">{item.icon}</span>
                  <span>{item.label}</span>
                </button>
              ))}
            </div>
          </div>
        ) : (
          <div
            className="py-3"
            style={{ paddingBottom: isTraceActive ? 48 : undefined }}
          >
            {currentSession.messages.map((message) => (
              <MessageBubble key={message.id} message={message} />
            ))}
            <div ref={messagesEndRef} />
          </div>
        )}

        <TaskProgressPanel sessionId={currentSession?.backendSessionId} chatRunning={isLoading} />
        </div>

        {isTraceActive && currentTrace && <AssistantStatusCapsule trace={currentTrace} colors={colors} />}
      </div>

      {showScrollToBottom && (
        <div className="flex justify-center py-1 flex-shrink-0">
          <button
            onClick={() => scrollToBottom('smooth')}
            className="flex items-center gap-1 px-3 py-1 rounded-full text-[10px] transition-all hover:scale-105"
            style={{
              backgroundColor: colors.bgTertiary,
              border: `1px solid ${colors.border}`,
              color: colors.textSecondary,
            }}
            title="回到底部"
          >
            <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="6 9 12 15 18 9"></polyline>
            </svg>
            <span>回到底部</span>
          </button>
        </div>
      )}

      <div
        className="w-full h-3 cursor-ns-resize select-none flex items-center justify-center transition-colors hover:bg-blue-500/20 flex-shrink-0"
        title="拖拽调整输入框高度"
        onMouseDown={(e) => {
          e.preventDefault()
          e.stopPropagation()
          const startY = e.clientY
          const startHeight = textareaRef.current?.offsetHeight || 120

          const onMouseMove = (moveEvent: MouseEvent) => {
            const deltaY = startY - moveEvent.clientY
            const newHeight = Math.max(80, Math.min(280, startHeight + deltaY))
            if (textareaRef.current) {
              textareaRef.current.style.height = `${newHeight}px`
              inputHeightRef.current = newHeight
            }
          }

          const onMouseUp = () => {
            document.removeEventListener('mousemove', onMouseMove)
            document.removeEventListener('mouseup', onMouseUp)
          }

          document.addEventListener('mousemove', onMouseMove)
          document.addEventListener('mouseup', onMouseUp)
        }}
      >
        <div className="flex gap-1 opacity-30">
          <div className="w-1 h-1 rounded-full bg-gray-400" />
          <div className="w-1 h-1 rounded-full bg-gray-400" />
        </div>
      </div>

      <div
        className="flex items-center justify-between px-4 py-2 border-t flex-shrink-0"
        style={{
          backgroundColor: colors.bgSecondary,
          borderColor: colors.border,
        }}
      >
        <div ref={agentDropdownRef} className="relative">
          <button
            onClick={() => setAgentDropdownOpen(!agentDropdownOpen)}
            className="flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-[11px] font-medium transition-all"
            style={{
              backgroundColor: colors.bgTertiary,
              color: colors.textSecondary,
              border: '1px solid transparent',
            }}
          >
            <span className="w-1.5 h-1.5 rounded-full" style={{ backgroundColor: colors.green }} />
            <span className="truncate max-w-[100px]">{currentAgent?.agentName || '选择智能体'}</span>
            <svg className="w-3 h-3 opacity-60" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="6 9 12 15 18 9"></polyline>
            </svg>
          </button>

          {agentDropdownOpen && (
            <div
              className="absolute bottom-full left-0 mb-1 py-1 rounded-lg shadow-lg z-50 min-w-[160px]"
              style={{ backgroundColor: colors.bgTertiary, border: `1px solid ${colors.border}` }}
            >
              {agents.map((agent) => (
                <button
                  key={agent.agentId}
                  onClick={() => {
                    setCurrentAgent(agent.agentId)
                    setAgentDropdownOpen(false)
                  }}
                  className="w-full text-left px-3 py-2 text-[11px] transition-colors hover:bg-white/5"
                  style={{ color: colors.text }}
                >
                  <div className="font-medium">{agent.agentName}</div>
                  <div className="opacity-60 truncate" style={{ color: colors.textDim }}>{agent.agentDesc}</div>
                </button>
              ))}
              {agents.length === 0 && (
                <div className="px-3 py-2 text-[11px]" style={{ color: colors.textDim }}>暂无智能体</div>
              )}
            </div>
          )}
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={() => createSession()}
            className="flex items-center gap-1 px-2 py-1.5 rounded-lg text-[11px] font-medium transition-all"
            style={{
              backgroundColor: colors.bgTertiary,
              color: colors.textSecondary,
              border: '1px solid transparent',
            }}
            title="新建会话"
            onMouseEnter={(e) => { e.currentTarget.style.backgroundColor = colors.accentSoft; e.currentTarget.style.color = colors.accent }}
            onMouseLeave={(e) => { e.currentTarget.style.backgroundColor = colors.bgTertiary; e.currentTarget.style.color = colors.textSecondary }}
          >
            <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <line x1="12" y1="5" x2="12" y2="19"></line>
              <line x1="5" y1="12" x2="19" y2="12"></line>
            </svg>
          </button>
          {currentSession && currentSession.messages.length > 0 && (
            <button
              onClick={() => {
                if (currentSessionId) clearSessionMessages(currentSessionId)
              }}
              className="flex items-center gap-1 px-2 py-1.5 rounded-lg text-[11px] font-medium transition-all"
              style={{
                backgroundColor: colors.bgTertiary,
                color: colors.textSecondary,
                border: '1px solid transparent',
              }}
              title="清空当前会话"
              onMouseEnter={(e) => { e.currentTarget.style.backgroundColor = `${colors.red}18`; e.currentTarget.style.color = colors.red }}
              onMouseLeave={(e) => { e.currentTarget.style.backgroundColor = colors.bgTertiary; e.currentTarget.style.color = colors.textSecondary }}
            >
              <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <polyline points="3 6 5 6 21 6"></polyline>
                <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
              </svg>
            </button>
          )}
          <button
            onClick={() => onToggleHistory?.()}
            className="flex items-center gap-1 px-2 py-1.5 rounded-lg text-[11px] font-medium transition-all"
            style={{
              backgroundColor: historyVisible ? colors.accentSoft : colors.bgTertiary,
              color: historyVisible ? colors.accent : colors.textSecondary,
              border: '1px solid transparent',
            }}
            title="历史对话"
            onMouseEnter={(e) => { if (!historyVisible) { e.currentTarget.style.backgroundColor = colors.accentSoft; e.currentTarget.style.color = colors.accent } }}
            onMouseLeave={(e) => { if (!historyVisible) { e.currentTarget.style.backgroundColor = colors.bgTertiary; e.currentTarget.style.color = colors.textSecondary } }}
          >
            <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <circle cx="12" cy="12" r="10"></circle>
              <polyline points="12 6 12 12 16 14"></polyline>
            </svg>
          </button>
        </div>
      </div>

      {(!isConnected || !serverStatus.connected) && currentSession && (
        <div
          className="px-4 py-1.5 text-[11px] flex items-center gap-2 flex-shrink-0"
          style={{ backgroundColor: `${colors.yellow}10`, color: colors.yellow, borderTop: `1px solid ${colors.border}` }}
        >
          <svg className="w-3.5 h-3.5 flex-shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path>
            <line x1="12" y1="9" x2="12" y2="13"></line>
            <line x1="12" y1="17" x2="12.01" y2="17"></line>
          </svg>
          <span className="truncate">
            {!serverStatus.connected
              ? '本机接口暂时不可达，SSH 状态待确认；接口恢复后继续检查'
              : currentConnectionId
              ? '尚未连接云服务器：可以继续对话，但 AI 无法执行 SSH 命令'
              : '尚未选择云服务器：连接后 AI 才能执行 SSH 命令'}
          </span>
        </div>
      )}

      <div
        className="flex flex-col relative px-4 pt-2 pb-3 flex-shrink-0"
        style={{ backgroundColor: colors.bgSecondary }}
      >
        <div
          className="relative w-full rounded-2xl border transition-all"
          style={{
            backgroundColor: colors.bgInput,
            borderColor: isFocused ? `${colors.accent}80` : colors.border,
            boxShadow: isFocused ? `0 0 0 1px ${colors.accent}30` : 'none',
          }}
        >
          <textarea
            ref={textareaRef}
            value={inputText}
            onChange={(e) => setInputText(e.target.value)}
            onKeyDown={handleKeyDown}
            onFocus={() => setIsFocused(true)}
            onBlur={() => setIsFocused(false)}
            rows={2}
            className="w-full bg-transparent resize-none outline-none text-[13px] leading-relaxed"
            style={{
              color: colors.text,
              minHeight: 60,
              maxHeight: 280,
              padding: '10px 16px 44px 16px',
            }}
          />

          {!inputText && !isFocused && (
            <div
              className="absolute pointer-events-none text-sm"
              style={{ left: '16px', top: '10px', color: colors.textDim, opacity: 0.6 }}
            >
              向 WaLiSSH 提问...
            </div>
          )}

          <div className="absolute right-3 bottom-3 flex items-center gap-1.5">
            <button
              disabled
              className="p-1.5 rounded-md transition-colors opacity-40 cursor-not-allowed"
              style={{ backgroundColor: colors.bgTertiary, color: colors.textSecondary }}
              title="附件（即将支持）"
            >
              <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"></path>
              </svg>
            </button>
            <button
              disabled
              className="p-1.5 rounded-md transition-colors opacity-40 cursor-not-allowed"
              style={{ backgroundColor: colors.bgTertiary, color: colors.textSecondary }}
              title="上传图片（即将支持）"
            >
              <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
                <circle cx="8.5" cy="8.5" r="1.5"></circle>
                <polyline points="21 15 16 10 5 21"></polyline>
              </svg>
            </button>
            {isLoading ? (
              <button
                onClick={stopGeneration}
                className="p-1.5 rounded-md transition-colors"
                style={{ backgroundColor: colors.red, color: '#fff' }}
                title="停止生成"
              >
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="currentColor">
                  <rect x="6" y="6" width="12" height="12" rx="2" />
                </svg>
              </button>
            ) : (
              <button
                onClick={() => void handleSend()}
                disabled={!canSend}
                className="p-1.5 rounded-md transition-colors"
                style={{
                  backgroundColor: canSend ? colors.accent : colors.bgTertiary,
                  color: canSend ? '#fff' : colors.textSecondary,
                  opacity: canSend ? 1 : 0.5,
                  cursor: canSend ? 'pointer' : 'not-allowed',
                }}
                title="发送"
              >
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M22 2L11 13M22 2l-7 20-4-9-9-4 20-7z" />
                </svg>
              </button>
            )}
          </div>
        </div>

        <div className="flex items-center justify-between mt-1.5 text-[10px]" style={{ color: colors.textDim }}>
          <div className="flex items-center gap-1.5">
            <span
              className="w-1.5 h-1.5 rounded-full"
              style={{ backgroundColor: isConnected ? colors.green : colors.yellow }}
            />
            <span className="truncate max-w-[140px]">{!serverStatus.connected ? '接口不可达，状态待确认' : isConnected ? '已连接终端' : '未连接终端'}</span>
          </div>
          <span className="opacity-50">Enter 发送 · Shift+Enter 换行</span>
        </div>
      </div>
    </div>
  )
}
