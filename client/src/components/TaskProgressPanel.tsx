import { useEffect, useState } from 'react'
import { listTasks, taskAction, taskDetail, taskEnded, type AgentTask } from '../api/tasks'
import { Icon } from './Icon'

const LABELS: Record<string, { text: string; tone: 'ok' | 'warn' | 'danger' | 'busy' | 'idle' }> = {
  PENDING: { text: '等待执行', tone: 'idle' },
  RUNNING: { text: '执行中', tone: 'busy' },
  WAITING: { text: '等待就绪', tone: 'idle' },
  CHECKING: { text: '核查进展', tone: 'busy' },
  WAITING_CONNECTION: { text: '连接中断', tone: 'warn' },
  NEEDS_ATTENTION: { text: '待核查', tone: 'warn' },
  CANCELLING: { text: '取消中', tone: 'warn' },
  SUCCEEDED: { text: '已完成', tone: 'ok' },
  FAILED: { text: '未完成', tone: 'danger' },
  UNKNOWN: { text: '结果待核查', tone: 'danger' },
  CANCELLED: { text: '已取消', tone: 'idle' },
}

function stepMark(status: string): string {
  if (status === 'SUCCEEDED') return '✓'
  if (status === 'FAILED') return '×'
  if (status === 'RUNNING') return '›'
  return '·'
}

export function TaskProgressPanel({ sessionId, chatRunning }: { sessionId?: string; chatRunning: boolean }) {
  const [tasks, setTasks] = useState<AgentTask[]>([])
  const [error, setError] = useState('')
  const [busy, setBusy] = useState('')
  const [detail, setDetail] = useState<AgentTask>()

  useEffect(() => {
    setTasks([]); setDetail(undefined); setError('')
    if (!sessionId) return
    let stopped = false
    let timer: ReturnType<typeof setTimeout>
    const controller = new AbortController()

    async function refresh() {
      let next = 8000
      try {
        const items = await listTasks(sessionId!, controller.signal)
        if (stopped) return
        setTasks(items); setError('')
        if (items.some(task => !taskEnded(task.status)) || chatRunning) next = 2000
      } catch (e) {
        if (!stopped) setError((e as Error).message)
      } finally {
        if (!stopped) timer = setTimeout(refresh, next)
      }
    }
    void refresh()
    return () => { stopped = true; clearTimeout(timer); controller.abort() }
  }, [sessionId, chatRunning])

  async function action(task: AgentTask, kind: 'cancel' | 'resume') {
    if (!sessionId) return
    setBusy(task.id); setError('')
    try {
      await taskAction(kind, task.id, sessionId)
      setTasks(await listTasks(sessionId))
      if (detail?.id === task.id) setDetail(await taskDetail(task.id, sessionId))
    } catch (e) {
      setError((e as Error).message)
    } finally { setBusy('') }
  }

  if (!sessionId || (!tasks.length && !error)) return null

  return (
    <section className="task-panel" aria-label="后台任务">
      <span className="u-caps">
        <Icon.Layers size={10} /> 后台任务 · 关闭对话后仍会继续
      </span>
      {tasks.slice(0, 6).map((task) => {
        const meta = LABELS[task.status] ?? { text: task.status, tone: 'idle' as const }
        return (
          <article key={task.id} className="task-card">
            <div className="task-card-head">
              <strong>{task.title}</strong>
              <span className="task-state" data-tone={meta.tone}>{meta.text}</span>
            </div>
            {task.message && <p>{task.message}</p>}
            {task.progressEvidence && <p style={{ color: 'var(--ink-4)' }}>最近观察：{task.progressEvidence}</p>}
            <p className="u-mono" style={{ fontSize: 10 }}>{task.target}{task.port ? ` · ${task.port}` : ''}</p>
            {task.steps.length > 0 && (
              <ol className="task-steps">
                {task.steps.map(step => (
                  <li key={step.key} data-status={step.status}>
                    <span>{stepMark(step.status)}</span>
                    <span>{step.title}</span>
                  </li>
                ))}
              </ol>
            )}
            <div className="task-actions">
              {!taskEnded(task.status) && (
                <button disabled={busy === task.id || task.cancelRequested || task.status === 'CANCELLING'} onClick={() => void action(task, 'cancel')}>
                  {task.cancelRequested ? '取消请求已提交' : '取消任务'}
                </button>
              )}
              {(task.status === 'UNKNOWN' || task.status === 'NEEDS_ATTENTION') && (
                <button disabled={busy === task.id} onClick={() => void action(task, 'resume')}>重新核查作业</button>
              )}
              <button onClick={() => {
                if (detail?.id === task.id) setDetail(undefined)
                else void taskDetail(task.id, sessionId).then(setDetail).catch(e => setError(e.message))
              }}>
                {detail?.id === task.id ? '收起详情' : '执行详情'}
              </button>
              <span className="u-mono" style={{ marginLeft: 'auto', fontSize: 9, color: 'var(--ink-4)' }}>{task.id.slice(0, 8)}</span>
            </div>
            {detail?.id === task.id && (
              <div className="task-detail">
                {detail.details?.map(step => (
                  <div key={step.key}>
                    <p style={{ color: 'var(--ink-2)' }}>
                      {step.title}{step.exitCode !== null ? ` · exit ${step.exitCode}` : ''}
                    </p>
                    <pre>{step.output || '暂无输出'}</pre>
                  </div>
                ))}
              </div>
            )}
          </article>
        )
      })}
      {error && (
        <p style={{ margin: 0, fontSize: 10.5, color: 'var(--danger)' }} role="alert">
          任务进度暂时无法读取：{error}。这不表示远端任务已经停止。
        </p>
      )}
    </section>
  )
}
