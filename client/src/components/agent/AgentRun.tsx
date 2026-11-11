import { useEffect, useMemo, useRef, useState } from 'react'
import type {
  AgentRunStage,
  AgentRunTrace,
  AgentTimelineEntry,
  AgentToolStatus,
  AgentToolTrace,
} from '../../types'
import { Icon } from '../Icon'
import { CommandApprovalCard } from '../CommandApprovalCard'
import { formatClock, formatClockSeconds, formatDuration, truncateText } from '../../utils/format'

/* ---------------------------------------------------------------- meta maps */

export type Tone = 'ok' | 'warn' | 'danger' | 'signal' | 'idle'

const STAGE_META: Record<AgentRunStage, { label: string; tone: Tone; code: string }> = {
  queued: { label: '排队中', tone: 'idle', code: 'QUEUED' },
  thinking: { label: '分析中', tone: 'warn', code: 'THINKING' },
  tool_running: { label: '执行中', tone: 'signal', code: 'EXECUTING' },
  responding: { label: '生成回复', tone: 'signal', code: 'RESPONDING' },
  completed: { label: '已完成', tone: 'ok', code: 'DONE' },
  error: { label: '失败', tone: 'danger', code: 'FAILED' },
  stopped: { label: '已停止', tone: 'warn', code: 'STOPPED' },
  paused: { label: '待核查', tone: 'warn', code: 'PAUSED' },
  waiting_approval: { label: '等待确认', tone: 'warn', code: 'APPROVAL' },
}

const TOOL_META: Record<AgentToolStatus, { label: string; tone: Tone }> = {
  pending: { label: '等待中', tone: 'idle' },
  running: { label: '执行中', tone: 'signal' },
  success: { label: '成功', tone: 'ok' },
  error: { label: '失败', tone: 'danger' },
  blocked: { label: '已拦截', tone: 'danger' },
  unknown: { label: '结果未知', tone: 'warn' },
}

export const TERMINAL_STAGES: AgentRunStage[] = ['completed', 'error', 'stopped', 'paused', 'waiting_approval']

function toneColor(tone: Tone): string {
  switch (tone) {
    case 'ok': return 'var(--ok)'
    case 'warn': return 'var(--warn)'
    case 'danger': return 'var(--danger)'
    case 'signal': return 'var(--signal)'
    default: return 'var(--ink-3)'
  }
}

function isDispatchTool(tool?: Pick<AgentToolTrace, 'kind' | 'name' | 'sourceAgent'>): boolean {
  if (tool?.kind) return tool.kind === 'dispatch'
  return tool?.name === 'dispatchSubAgents'
    || tool?.name === 'planAndDispatchSubAgents'
    || Boolean(tool?.name && tool?.sourceAgent && tool.name === tool.sourceAgent)
}

export function toolLabel(tool: AgentToolTrace): string {
  if (isDispatchTool(tool)) return `派发子 Agent · ${tool.name}`
  return tool.name
}

/** 从参数 JSON 中提取可读命令，失败时退回原文本首行。 */
function commandPreview(tool: AgentToolTrace): string | undefined {
  const raw = tool.argumentsText?.trim()
  if (!raw) return undefined
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>
    const candidate = parsed.command ?? parsed.cmd ?? parsed.query ?? parsed.path ?? parsed.url
    if (typeof candidate === 'string' && candidate.trim()) return candidate.trim().replace(/\s+/g, ' ')
  } catch {
    /* 非 JSON 参数，直接使用文本 */
  }
  return raw.replace(/\s+/g, ' ').slice(0, 160)
}

/* ------------------------------------------------------------ status glyphs */

function StatusGlyph({ status }: { status: AgentToolStatus }) {
  if (status === 'running') return <span className="spinner agent-row-status" style={{ color: 'var(--signal)', borderWidth: 1.5 }} />
  if (status === 'success') return <Icon.Check size={12} className="agent-row-status" data-status="success" />
  if (status === 'error' || status === 'blocked') return <Icon.X size={12} className="agent-row-status" data-status={status} />
  if (status === 'unknown') return <Icon.Question size={12} className="agent-row-status" data-status="unknown" />
  return <Icon.Dot size={12} className="agent-row-status" />
}

/* --------------------------------------------------------------- tool group */

interface ToolGroup {
  key: string
  name: string
  sourceAgent?: string
  dispatch: boolean
  tools: AgentToolTrace[]
}

/** 连续的同名工具调用折叠为一个分组，避免日志刷屏。 */
function groupTools(tools: AgentToolTrace[]): ToolGroup[] {
  const groups: ToolGroup[] = []
  for (const tool of tools) {
    const last = groups[groups.length - 1]
    if (last && last.name === tool.name && last.sourceAgent === tool.sourceAgent && last.dispatch === isDispatchTool(tool)) {
      last.tools.push(tool)
      continue
    }
    groups.push({
      key: tool.id,
      name: tool.name,
      sourceAgent: tool.sourceAgent,
      dispatch: isDispatchTool(tool),
      tools: [tool],
    })
  }
  return groups
}

function aggregateStatus(tools: AgentToolTrace[]): AgentToolStatus {
  if (tools.some(t => t.status === 'running')) return 'running'
  if (tools.some(t => t.status === 'error' || t.status === 'blocked')) return 'error'
  if (tools.some(t => t.status === 'unknown')) return 'unknown'
  if (tools.some(t => t.status === 'pending')) return 'pending'
  return 'success'
}

function ToolGroupRow({ group, defaultOpen }: { group: ToolGroup; defaultOpen: boolean }) {
  const [open, setOpen] = useState(defaultOpen)
  const status = aggregateStatus(group.tools)
  const meta = TOOL_META[status]
  const first = group.tools[0]
  const last = group.tools[group.tools.length - 1]
  const preview = commandPreview(first)
  const elapsed = group.tools.reduce((sum, tool) => sum + Math.max(0, (tool.updatedAt ?? tool.startedAt) - tool.startedAt), 0)
  const hasDetail = group.tools.some(tool => tool.argumentsText || tool.resultText || tool.approvalId)

  return (
    <div>
      <button
        type="button"
        className="agent-row"
        data-status={status}
        onClick={() => hasDetail && setOpen(!open)}
        style={{ cursor: hasDetail ? 'pointer' : 'default' }}
        aria-expanded={hasDetail ? open : undefined}
      >
        <span className="agent-row-main">
          <span className="agent-row-name" title={toolLabel(first)}>{toolLabel(first)}</span>
          {group.tools.length > 1 && <span className="agent-row-count">×{group.tools.length}</span>}
          {group.dispatch && (
            <span className="agent-row-count" style={{ color: 'var(--warn)', borderColor: 'color-mix(in srgb, var(--warn) 32%, transparent)' }}>子 Agent</span>
          )}
        </span>
        <span className="agent-row-time">{elapsed > 0 ? formatDuration(last.startedAt, last.updatedAt) : '—'}</span>
        <StatusGlyph status={status} />
        <span className="agent-row-cmd" style={{ color: status === 'error' || status === 'blocked' ? 'var(--danger)' : undefined }}>
          {preview ?? meta.label}
        </span>
      </button>

      {open && (
        <div className="agent-detail panel-slide">
          {group.tools.map((tool, index) => {
            const toolStatus = TOOL_META[tool.status]
            return (
              <div key={tool.id} style={index > 0 ? { marginTop: 10, paddingTop: 8, borderTop: '1px solid var(--line-soft)' } : undefined}>
                {group.tools.length > 1 && (
                  <div className="agent-detail-label" style={{ marginTop: 2 }}>
                    <span>调用 {index + 1} / {group.tools.length}</span>
                    <span style={{ color: toneColor(toolStatus.tone) }}>{toolStatus.label}</span>
                  </div>
                )}
                {tool.argumentsText && (
                  <>
                    <div className="agent-detail-label"><span>参数</span></div>
                    <pre>{truncateText(tool.argumentsText, 1200)}</pre>
                  </>
                )}
                {tool.resultText && (
                  <>
                    <div className="agent-detail-label"><span>输出</span></div>
                    <pre data-kind={tool.status === 'error' || tool.status === 'blocked' ? 'error' : 'result'}>
                      {truncateText(tool.resultText, 1600)}
                    </pre>
                  </>
                )}
                {tool.ruleId && (
                  <div className="agent-detail-label" style={{ color: 'var(--danger)' }}>
                    <span>拦截规则 {tool.ruleId}</span>
                  </div>
                )}
                {tool.rewriteAllowed && (
                  <p style={{ margin: '6px 0 0', fontSize: 10.5, color: 'var(--warn)' }}>该命令被判定为需要改写，重新组织后再执行。</p>
                )}
                {tool.approvalId && <CommandApprovalCard approvalId={tool.approvalId} />}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

/* ------------------------------------------------------------- result layer */

function ResultSummary({ trace }: { trace: AgentRunTrace }) {
  const succeeded = trace.tools.filter(t => t.status === 'success' && !t.approvalId).length
  const failed = trace.tools.filter(t => t.status === 'error' || t.status === 'blocked').length
  const unknown = trace.tools.filter(t => t.status === 'unknown').length
  const pendingApproval = trace.tools.filter(t => t.approvalId && t.status === 'running').length

  const tone: Tone = trace.stage === 'error' ? 'danger'
    : trace.stage === 'waiting_approval' || trace.stage === 'paused' || unknown > 0 ? 'warn'
    : trace.stage === 'completed' ? 'ok'
    : 'idle'

  const headline = (() => {
    if (trace.stage === 'waiting_approval') return '变更已生成，等待你的确认'
    if (trace.stage === 'paused') return '任务已暂停，需要核查后再继续'
    if (trace.stage === 'error') return '本轮未能完成'
    if (trace.stage === 'stopped') return '已按你的要求停止'
    if (unknown > 0) return '部分操作结果未能确认'
    if (succeeded === 0 && trace.totalToolCalls === 0) return '本轮没有执行命令'
    return '本轮执行完成'
  })()

  const nextStep = (() => {
    if (trace.stage === 'waiting_approval') return '在下方确认卡中批准或拒绝，批准后只会执行一次。'
    if (trace.stage === 'paused') return '先核对服务器上的实际状态，再重新发起任务。'
    if (trace.stage === 'error') return '根据失败原因调整命令或权限后重试。'
    if (unknown > 0) return '登录服务器确认上一次操作是否已生效，避免重复执行。'
    if (trace.stage === 'stopped') return '需要继续时，重新描述目标即可。'
    if (failed > 0) return '部分步骤失败，可针对失败项单独排查。'
    return '结果已在上方回复中整理。'
  })()

  const IconGlyph = tone === 'ok' ? Icon.Check : tone === 'danger' ? Icon.Alert : tone === 'warn' ? Icon.Info : Icon.Dot

  return (
    <div className="agent-result" data-tone={tone}>
      <div className="agent-result-title">
        <IconGlyph size={13} />
        <span>{headline}</span>
      </div>
      <dl>
        <dt>已完成</dt>
        <dd>{succeeded} 项工具调用</dd>
        <dt>失败</dt>
        <dd data-tone={failed > 0 ? 'danger' : undefined}>{failed > 0 ? `${failed} 项需要处理` : '无'}</dd>
        {unknown > 0 && (<><dt>待核查</dt><dd data-tone="danger">{unknown} 项结果未知</dd></>)}
        {pendingApproval > 0 && (<><dt>待确认</dt><dd>{pendingApproval} 项变更</dd></>)}
        <dt>下一步</dt>
        <dd>{nextStep}</dd>
      </dl>
    </div>
  )
}

/* --------------------------------------------------------------- event log */

const LOG_TAG: Record<AgentTimelineEntry['type'], string> = {
  status: '··',
  text: '✎',
  tool_call: '→',
  tool_result: '←',
  round_end: '◆',
  done: '✓',
  error: '!',
  run_finished: '■',
}

function EventLog({ entries }: { entries: AgentTimelineEntry[] }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (ref.current) ref.current.scrollTop = ref.current.scrollHeight
  }, [entries.length])
  if (entries.length === 0) return null
  return (
    <details className="agent-log">
      <summary><Icon.Layers size={10} /> 原始事件 · {entries.length}</summary>
      <div className="agent-log-body" ref={ref}>
        {entries.map((entry) => (
          <div
            key={entry.id}
            className="agent-log-line"
            data-type={entry.type}
            data-ok={entry.status ? entry.status === 'success' : undefined}
          >
            <time>{formatClockSeconds(entry.timestamp)}</time>
            <span className="tag">{LOG_TAG[entry.type] ?? '·'}</span>
            <span className="txt" title={entry.detail || entry.label}>{entry.label}{entry.detail ? ` · ${entry.detail.replace(/\s+/g, ' ')}` : ''}</span>
          </div>
        ))}
      </div>
    </details>
  )
}

/* --------------------------------------------------------------- main panel */

interface AgentRunProps {
  trace: AgentRunTrace
  goal?: string
}

export function AgentRun({ trace, goal }: AgentRunProps) {
  const finished = TERMINAL_STAGES.includes(trace.stage) && trace.stage !== 'waiting_approval'
  const [collapsed, setCollapsed] = useState(finished)
  const stage = STAGE_META[trace.stage]
  const streaming = !finished && trace.stage !== 'waiting_approval'

  // 运行中与等待确认时保持展开；结束后折叠一次，让最终回答成为主角
  useEffect(() => {
    setCollapsed(TERMINAL_STAGES.includes(trace.stage) && trace.stage !== 'waiting_approval')
  }, [trace.stage])

  const groups = useMemo(() => groupTools(trace.tools), [trace.tools])
  const runningTool = [...trace.tools].reverse().find(tool => tool.status === 'running')
  const currentAction = runningTool
    ? `${toolLabel(runningTool)} · ${commandPreview(runningTool) ?? '执行中'}`
    : trace.statusText
  const stepTotal = Math.max(trace.totalSteps, trace.steps.length)

  return (
    <div className="agent-run" data-stage={trace.stage}>
      {/* 任务层：目标 + 状态 + 步骤 + 耗时 */}
      <button type="button" className="agent-run-head" onClick={() => setCollapsed(!collapsed)} aria-expanded={!collapsed}>
        <span
          className={`agent-run-dot nox-dot ${streaming ? 'nox-dot-pulse' : ''}`}
          data-tone={stage.tone === 'idle' ? undefined : stage.tone}
          data-pulse={streaming ? 'true' : undefined}
          style={{ marginTop: 5, width: 7, height: 7, color: toneColor(stage.tone) }}
        />
        <span style={{ minWidth: 0, textAlign: 'left' }}>
          <span className="agent-run-goal u-clamp-2" title={goal || trace.statusText}>
            {goal || '当前任务'}
          </span>
          <span className="agent-run-sub">
            <span className="agent-stage-pill" data-tone={stage.tone}>{stage.label}</span>
            {stepTotal > 0 && <span><span className="u-num">{trace.steps.length}/{stepTotal}</span> 步</span>}
            {stepTotal > 0 && <span className="sep" />}
            <span><span className="u-num">{trace.tools.length}</span> 工具</span>
            <span className="sep" />
            <span className="u-num">{formatDuration(trace.startedAt, trace.completedAt)}</span>
          </span>
        </span>
        <Icon.ChevronDown size={13} className="agent-run-chevron" data-collapsed={collapsed} style={{ marginTop: 6 }} />
      </button>

      {!collapsed && (
        <div className="agent-run-body">
          {/* 正在执行的动作 */}
          {(streaming || trace.stage === 'waiting_approval') && (
            <div className="agent-now">
              <span className="nox-dot" data-tone={stage.tone === 'idle' ? undefined : stage.tone} data-pulse="true" style={{ color: toneColor(stage.tone) }} />
              <span>{currentAction}</span>
            </div>
          )}

          {/* 执行层：按步骤时间线组织，同名连续调用折叠 */}
          {trace.tools.length > 0 && (
            <section>
              <div className="agent-run-section-head">
                <span className="u-caps">执行记录</span>
                <span className="u-num" style={{ fontSize: 9, color: 'var(--ink-4)' }}>{groups.length} 组 · {trace.tools.length} 次</span>
              </div>
              <div className="agent-spine">
                {groups.map((group) => {
                  const status = aggregateStatus(group.tools)
                  return (
                    <div className="agent-group" key={group.key}>
                      <span className="agent-group-dot" data-status={status} />
                      <ToolGroupRow group={group} defaultOpen={status === 'error' || status === 'running'} />
                    </div>
                  )
                })}
              </div>
            </section>
          )}

          {/* 结果层 */}
          {(finished || trace.stage === 'waiting_approval') && <ResultSummary trace={trace} />}

          {/* 失败原因 */}
          {trace.error && (
            <div className="inline-error" style={{ margin: 0 }} role="alert">
              <Icon.Alert size={13} style={{ flex: 'none', marginTop: 1 }} />
              <span style={{ flex: 1 }}>{trace.error}</span>
            </div>
          )}

          <EventLog entries={trace.timeline} />
        </div>
      )}
    </div>
  )
}

/* ------------------------------------------------- 实时状态胶囊（列表底部） */

export function RunCapsule({ trace }: { trace: AgentRunTrace }) {
  const stage = STAGE_META[trace.stage]
  const activeTool = [...trace.tools].reverse().find(tool => tool.status === 'running')
  const label = activeTool ? toolLabel(activeTool) : trace.statusText
  return (
    <div className="status-capsule" role="status" aria-live="polite">
      <span className="nox-dot" data-tone={stage.tone === 'idle' ? undefined : stage.tone} data-pulse="true" style={{ color: toneColor(stage.tone) }} />
      <span className="label" style={{ color: toneColor(stage.tone) }}>{stage.label}</span>
      <span className="now" title={label}>{label}</span>
      <span className="meta">{formatDuration(trace.startedAt)}</span>
    </div>
  )
}

export function stageLabelFor(stage: AgentRunStage): string {
  return STAGE_META[stage].label
}

export { formatClock }
