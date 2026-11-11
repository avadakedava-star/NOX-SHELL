import { useEffect, useState } from 'react'
import { decideApproval, listApprovals, type CommandApproval } from '../api/agent'
import { useAgentStore } from '../stores/agentStore'
import { Icon } from './Icon'
import { formatClockSeconds } from '../utils/format'

const USER_ID = 'walissh-user'

const STATUS_TEXT: Record<string, string> = {
  RUNNING: '执行中或结果尚未保存，请核查，勿重复执行',
  SUCCEEDED: '执行成功',
  FAILED: '执行失败',
  UNKNOWN: '结果未知，请核查服务器',
  REJECTED: '已拒绝，未执行变更',
  EXPIRED: '确认方案已过期，未执行变更',
}

export function CommandApprovalCard({ approvalId }: { approvalId: string }) {
  const currentSessionId = useAgentStore(s => s.currentSessionId)
  const session = useAgentStore(s => (currentSessionId ? s.sessions.get(currentSessionId) : undefined))
  const isLoading = useAgentStore(s => s.isLoading)
  const [approval, setApproval] = useState<CommandApproval>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [now, setNow] = useState(Date.now())

  useEffect(() => {
    let active = true
    if (session?.backendSessionId) {
      listApprovals(USER_ID, session.backendSessionId).then((items) => {
        if (!active) return
        const record = items.find(item => item.id === approvalId)
        setApproval(record)
        if (record && record.status !== 'PENDING') useAgentStore.getState().setApprovalResult(approvalId, record.status)
      }).catch((e) => { if (active) setError(String(e.message)) })
    }
    return () => { active = false }
  }, [approvalId, session?.backendSessionId])

  // 过期倒计时
  useEffect(() => {
    if (!approval || approval.status !== 'PENDING') return
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [approval])

  async function decide(approve: boolean) {
    if (!approval || busy) return
    setBusy(true); setError('')
    try {
      const result = await decideApproval(approval.id, USER_ID, approval.sessionId, approve)
      setApproval(result)
      useAgentStore.getState().setApprovalResult(approvalId, result.status)
      if (result.status === 'SUCCEEDED' && useAgentStore.getState().currentSessionId === currentSessionId && !useAgentStore.getState().isLoading) {
        const task = [...(session?.messages || [])].reverse().find(message => message.role === 'user')?.content || ''
        await useAgentStore.getState().sendMessage(`用户已通过确认卡批准并执行操作，审批编号 ${result.id}。操作：${result.command}。执行结果：${result.result}。该操作已经执行，请勿重复。请基于结果继续完成原任务；新的变更仍须单独确认。原任务：${task}`, result.terminalSessionId)
      }
    } catch (e) {
      setError((e as Error).message)
      // 响应丢失可能发生在执行之后，只查询保存状态，不自动再次批准或执行。
      try {
        const saved = (await listApprovals(USER_ID, approval.sessionId)).find(item => item.id === approvalId)
        if (saved) { setApproval(saved); useAgentStore.getState().setApprovalResult(approvalId, saved.status) }
      } catch { /* 保留原始错误 */ }
    } finally { setBusy(false) }
  }

  const expired = Boolean(approval && approval.status === 'PENDING' && approval.expiresAt <= now)
  const remaining = approval && approval.status === 'PENDING'
    ? Math.max(0, Math.floor((approval.expiresAt - now) / 1000))
    : 0

  return (
    <section className="approval" data-expired={expired} aria-label="变更确认">
      <div className="approval-head">
        <Icon.Shield size={13} />
        <span>{approval?.status === 'PENDING' ? (expired ? '确认已过期' : '等待你确认变更') : '变更确认记录'}</span>
        {approval?.status === 'PENDING' && !expired && (
          <span className="expiry u-num">{Math.floor(remaining / 60)}:{String(remaining % 60).padStart(2, '0')} 后失效</span>
        )}
      </div>

      <div className="approval-body">
        {approval ? (
          <>
            <dl className="approval-kv">
              <dt>目标</dt>
              <dd>{approval.host}{approval.username ? ` · ${approval.username}` : ''}</dd>
              <dt>类型</dt>
              <dd>{approval.kind === 'CONFIG' ? '配置文件变更' : '命令执行'}</dd>
            </dl>

            <pre className="approval-cmd">{approval.kind === 'CONFIG' ? approval.command : approval.executionCommand}</pre>

            {approval.kind === 'CONFIG' ? (
              <>
                <p className="approval-note">
                  文件：{approval.path}。使用 sudo -n；先核对旧内容、生成备份，再替换。
                </p>
                <details>
                  <summary>查看当前配置（变更前）</summary>
                  <pre>{approval.expectedContent}</pre>
                </details>
                <details open>
                  <summary>查看目标配置（变更后）</summary>
                  <pre>{approval.content}</pre>
                </details>
              </>
            ) : null}

            <p className="approval-note">
              批准只执行上面的操作一次；账号权限不足时会直接报错，不会等待密码。确认于 {formatClockSeconds(approval.expiresAt)} 失效。
            </p>

            {approval.status === 'PENDING' && !expired ? (
              <div className="approval-actions">
                <button className="approval-approve" disabled={busy || isLoading} onClick={() => decide(true)}>
                  {busy ? '处理中…' : '批准并执行一次'}
                </button>
                <button className="approval-reject" disabled={busy} onClick={() => decide(false)}>拒绝</button>
                {isLoading && <span style={{ fontSize: 10, color: 'var(--ink-4)' }}>AI 正在处理，稍后可确认</span>}
              </div>
            ) : (
              <div className="approval-state" data-status={expired ? 'EXPIRED' : approval.status}>
                <span className="nox-dot" data-tone={approval.status === 'SUCCEEDED' && !expired ? 'ok' : 'warn'} />
                {expired ? STATUS_TEXT.EXPIRED : STATUS_TEXT[approval.status] ?? approval.status}
              </div>
            )}

            {approval.result && (
              <>
                <div className="u-caps" style={{ marginTop: 2 }}>执行结果</div>
                <pre style={{ marginTop: 0 }}>{approval.result}</pre>
              </>
            )}
          </>
        ) : !error ? (
          <p className="approval-note" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span className="spinner" style={{ color: 'var(--warn)' }} /> 正在读取保存的操作方案…
          </p>
        ) : null}

        {error && <p style={{ margin: 0, color: 'var(--danger)', fontSize: 11 }} role="alert">{error}</p>}
      </div>
    </section>
  )
}
