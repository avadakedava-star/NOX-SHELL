import { useState, useEffect, useCallback, useMemo } from 'react'
import { useConnectionStore } from '../stores/connectionStore'
import { AuthType } from '../types'
import type { SshConnectionPayload } from '../api/sshConnection'
import { Icon } from './Icon'

interface SSHConnectionModalProps {
  open: boolean
  onClose: () => void
  /** 编辑模式时传入已有连接数据 */
  editingConnection?: {
    id: string
    name: string
    host: string
    port: number
    username: string
    authType: number
  } | null
}

type AuthMethod = 'password' | 'privateKey'
type Tab = 'basic' | 'auth' | 'advanced'

const TABS: { id: Tab; label: string }[] = [
  { id: 'basic', label: '基本信息' },
  { id: 'auth', label: '认证' },
  { id: 'advanced', label: '高级' },
]

export function SSHConnectionModal({ open, onClose, editingConnection }: SSHConnectionModalProps) {
  const { createConnection, updateConnection, error, clearError } = useConnectionStore()
  const isEditing = !!editingConnection

  const [tab, setTab] = useState<Tab>('basic')
  const [authMethod, setAuthMethod] = useState<AuthMethod>('password')

  const [name, setName] = useState('')
  const [host, setHost] = useState('')
  const [port, setPort] = useState(22)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [privateKey, setPrivateKey] = useState('')

  const [connectionTimeout, setConnectionTimeout] = useState(30)
  const [keepAliveInterval, setKeepAliveInterval] = useState(60)
  const [enableCompression, setEnableCompression] = useState(false)
  const [startupCommand, setStartupCommand] = useState('')

  const [submitting, setSubmitting] = useState(false)
  const [errors, setErrors] = useState<Record<string, string>>({})

  useEffect(() => {
    if (!open) return
    clearError()
    setErrors({})
    setTab('basic')
    if (editingConnection) {
      setName(editingConnection.name)
      setHost(editingConnection.host)
      setPort(editingConnection.port)
      setUsername(editingConnection.username)
      setAuthMethod(editingConnection.authType === 2 ? 'privateKey' : 'password')
      setPassword('')
      setPrivateKey('')
    } else {
      setName('')
      setHost('')
      setPort(22)
      setUsername('')
      setPassword('')
      setPrivateKey('')
      setAuthMethod('password')
      setConnectionTimeout(30)
      setKeepAliveInterval(60)
      setEnableCompression(false)
      setStartupCommand('')
    }
    // clearError 来自 zustand，引用稳定
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, editingConnection])

  // 逐字段清除错误，避免用户修改后仍显示旧提示
  const patch = <T,>(setter: (v: T) => void, field: string) => (value: T) => {
    setter(value)
    setErrors((prev) => {
      if (!prev[field]) return prev
      const next = { ...prev }
      delete next[field]
      return next
    })
  }

  const validate = useCallback(() => {
    const errs: Record<string, string> = {}
    if (!name.trim()) errs.name = '连接名称不能为空'
    if (!host.trim()) errs.host = '主机地址不能为空'
    else if (!/^[a-zA-Z0-9._:\-一-龥]+$/.test(host.trim())) errs.host = '主机地址包含不支持的字符'
    if (!username.trim()) errs.username = '用户名不能为空'
    // 编辑模式下留空表示沿用原凭据
    if (!isEditing && authMethod === 'password' && !password) errs.password = '密码不能为空'
    if (!isEditing && authMethod === 'privateKey' && !privateKey.trim()) errs.privateKey = '私钥内容不能为空'
    if (!Number.isFinite(port) || port < 1 || port > 65535) errs.port = '端口需在 1–65535 之间'
    setErrors(errs)
    if (Object.keys(errs).length > 0) {
      const firstTab = errs.name || errs.host || errs.port ? 'basic' : 'auth'
      setTab(firstTab)
    }
    return Object.keys(errs).length === 0
  }, [name, host, username, password, privateKey, port, authMethod, isEditing])

  const handleConnect = useCallback(async () => {
    if (!validate()) return
    setSubmitting(true)
    const payload: SshConnectionPayload = {
      connectionId: editingConnection?.id,
      connectionName: name.trim(),
      host: host.trim(),
      port,
      username: username.trim(),
      authType: authMethod === 'password' ? AuthType.PASSWORD : AuthType.PRIVATE_KEY,
      password: authMethod === 'password' && password ? password : undefined,
      privateKey: authMethod === 'privateKey' ? privateKey : undefined,
      userId: 'default',
      connectTimeout: connectionTimeout,
      keepaliveInterval: keepAliveInterval,
      startupCommand: startupCommand || undefined,
      compression: enableCompression,
      strictHostKeyCheck: true,
    }
    const ok = isEditing ? await updateConnection(payload) : await createConnection(payload)
    setSubmitting(false)
    if (ok) onClose()
  }, [validate, editingConnection, name, host, port, username, authMethod, password, privateKey, connectionTimeout, keepAliveInterval, startupCommand, enableCompression, isEditing, updateConnection, createConnection, onClose])

  useEffect(() => {
    if (!open) return
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !submitting) onClose()
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [open, onClose, submitting])

  const preview = useMemo(() => {
    const user = username.trim() || 'user'
    const target = host.trim() || 'host'
    const flags = enableCompression ? ' -C' : ''
    const key = authMethod === 'privateKey' ? ' -i ~/.ssh/id_ed25519' : ''
    return `ssh${flags}${key} -p ${port || 22} ${user}@${target}`
  }, [username, host, port, enableCompression, authMethod])

  if (!open) return null

  return (
    <div className="dialog-scrim" onMouseDown={(e) => { if (e.target === e.currentTarget && !submitting) onClose() }}>
      <div className="dialog" data-size="ssh" role="dialog" aria-modal="true" aria-labelledby="ssh-dialog-title">
        <div className="dialog-head">
          <div className="dialog-head-id">
            <span className="dialog-glyph"><Icon.Server size={15} /></span>
            <span className="dialog-title">
              <span className="u-caps">{isEditing ? 'EDIT HOST' : 'NEW HOST'}</span>
              <strong id="ssh-dialog-title">{isEditing ? '编辑 SSH 连接' : '新建 SSH 连接'}</strong>
            </span>
          </div>
          <button className="nox-icon-btn" onClick={onClose} disabled={submitting} aria-label="关闭">
            <Icon.X size={14} />
          </button>
        </div>

        <div className="dialog-tabs" role="tablist" aria-label="连接配置">
          {TABS.map((item) => (
            <button
              key={item.id}
              role="tab"
              aria-selected={tab === item.id}
              onClick={() => setTab(item.id)}
            >
              {item.label}
            </button>
          ))}
        </div>

        <div className="dialog-body">
          {tab === 'basic' && (
            <div className="form-grid">
              <div className="field">
                <label className="field-label" htmlFor="ssh-name">
                  连接名称 <span className="req">必填</span>
                </label>
                <input
                  id="ssh-name"
                  className="input"
                  value={name}
                  onChange={(e) => patch(setName, 'name')(e.target.value)}
                  placeholder="例如：prod-web-01"
                  aria-invalid={!!errors.name}
                  autoFocus
                />
                {errors.name && <p className="field-error"><Icon.Alert size={10} />{errors.name}</p>}
                <p className="field-hint">只用于列表展示，建议和主机名保持一致。</p>
              </div>

              <div className="form-row">
                <div className="field">
                  <label className="field-label" htmlFor="ssh-host">
                    主机地址 <span className="req">必填</span>
                  </label>
                  <input
                    id="ssh-host"
                    className="input input-mono"
                    value={host}
                    onChange={(e) => patch(setHost, 'host')(e.target.value)}
                    placeholder="192.168.1.100 或 example.com"
                    aria-invalid={!!errors.host}
                    spellCheck={false}
                  />
                  {errors.host && <p className="field-error"><Icon.Alert size={10} />{errors.host}</p>}
                </div>
                <div className="field">
                  <label className="field-label" htmlFor="ssh-port">端口</label>
                  <input
                    id="ssh-port"
                    type="number"
                    min={1}
                    max={65535}
                    className="input input-mono"
                    value={port}
                    onChange={(e) => patch(setPort, 'port')(Number(e.target.value) || 0)}
                    aria-invalid={!!errors.port}
                  />
                  {errors.port && <p className="field-error"><Icon.Alert size={10} />{errors.port}</p>}
                </div>
              </div>

              <div className="field">
                <span className="field-label">连接命令预览</span>
                <div className="preview-line">
                  <span>$</span>
                  <span>{preview}</span>
                </div>
              </div>

              <div className="form-note">
                <Icon.Info size={13} />
                <span>请确认目标主机已启用 SSH 服务，且当前网络可以访问该端口。连接使用严格主机密钥检查。</span>
              </div>
            </div>
          )}

          {tab === 'auth' && (
            <div className="form-grid">
              <div className="field">
                <span className="field-label">认证方式</span>
                <div className="segmented" role="group" aria-label="认证方式">
                  <button aria-pressed={authMethod === 'password'} onClick={() => setAuthMethod('password')}>
                    <Icon.Shield size={12} /> 密码
                  </button>
                  <button aria-pressed={authMethod === 'privateKey'} onClick={() => setAuthMethod('privateKey')}>
                    <Icon.Question size={12} /> 私钥
                  </button>
                </div>
              </div>

              <div className="field">
                <label className="field-label" htmlFor="ssh-user">
                  用户名 <span className="req">必填</span>
                </label>
                <input
                  id="ssh-user"
                  className="input input-mono"
                  value={username}
                  onChange={(e) => patch(setUsername, 'username')(e.target.value)}
                  placeholder="root"
                  aria-invalid={!!errors.username}
                  spellCheck={false}
                  autoComplete="off"
                />
                {errors.username && <p className="field-error"><Icon.Alert size={10} />{errors.username}</p>}
              </div>

              {authMethod === 'password' ? (
                <div className="field">
                  <label className="field-label" htmlFor="ssh-pass">
                    密码
                    {isEditing ? <span className="opt">留空则不改</span> : <span className="req">必填</span>}
                  </label>
                  <input
                    id="ssh-pass"
                    type="password"
                    className="input"
                    value={password}
                    onChange={(e) => patch(setPassword, 'password')(e.target.value)}
                    placeholder={isEditing ? '留空则保持原密码' : '输入登录密码'}
                    aria-invalid={!!errors.password}
                    autoComplete="new-password"
                  />
                  {errors.password && <p className="field-error"><Icon.Alert size={10} />{errors.password}</p>}
                  <p className="field-hint">密码仅用于建立连接，不会显示在界面或对话中。</p>
                </div>
              ) : (
                <div className="field">
                  <label className="field-label" htmlFor="ssh-key">
                    私钥内容
                    {isEditing ? <span className="opt">留空则不改</span> : <span className="req">必填</span>}
                  </label>
                  <textarea
                    id="ssh-key"
                    className="textarea"
                    value={privateKey}
                    onChange={(e) => patch(setPrivateKey, 'privateKey')(e.target.value)}
                    placeholder={'-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----'}
                    aria-invalid={!!errors.privateKey}
                    spellCheck={false}
                  />
                  {errors.privateKey && <p className="field-error"><Icon.Alert size={10} />{errors.privateKey}</p>}
                  <p className="field-hint">支持 RSA、ED25519 等 OpenSSH 格式，直接粘贴私钥全文。</p>
                </div>
              )}
            </div>
          )}

          {tab === 'advanced' && (
            <div className="form-grid">
              <div className="field">
                <span className="field-label">连接超时</span>
                <div className="slider-row">
                  <input
                    type="range"
                    className="slider"
                    min={5}
                    max={120}
                    step={5}
                    value={connectionTimeout}
                    onChange={(e) => setConnectionTimeout(Number(e.target.value))}
                    aria-label="连接超时秒数"
                  />
                  <output>{connectionTimeout}s</output>
                </div>
                <p className="field-hint">握手阶段的最长等待时间，跨地域链路建议放宽到 30s 以上。</p>
              </div>

              <div className="field">
                <span className="field-label">保活间隔</span>
                <div className="slider-row">
                  <input
                    type="range"
                    className="slider"
                    min={0}
                    max={300}
                    step={10}
                    value={keepAliveInterval}
                    onChange={(e) => setKeepAliveInterval(Number(e.target.value))}
                    aria-label="保活间隔秒数"
                  />
                  <output>{keepAliveInterval === 0 ? '关闭' : `${keepAliveInterval}s`}</output>
                </div>
                <p className="field-hint">定期发送心跳，防止空闲连接被中间设备断开。</p>
              </div>

              <div className="field">
                <label className="field-label" htmlFor="ssh-start">连接后自动执行 <span className="opt">可选</span></label>
                <input
                  id="ssh-start"
                  className="input input-mono"
                  value={startupCommand}
                  onChange={(e) => setStartupCommand(e.target.value)}
                  placeholder="cd /srv/app && ls -la"
                  spellCheck={false}
                />
                <p className="field-hint">连接成功后自动执行的命令，多条命令用 ; 分隔。</p>
              </div>

              <div className="settings-divider" />

              <div className="settings-row">
                <span className="copy">
                  <strong>启用压缩</strong>
                  <span>对 SSH 数据流进行压缩，低带宽链路上有帮助</span>
                </span>
                <button
                  type="button"
                  role="switch"
                  aria-checked={enableCompression}
                  className="switch"
                  onClick={() => setEnableCompression(!enableCompression)}
                  aria-label="启用压缩"
                />
              </div>

              <div className="readonly-row">
                <span>
                  严格主机密钥检查
                  <small>首次连接会校验主机指纹，不可在此关闭</small>
                </span>
                <span className="u-mono">ENABLED</span>
              </div>
            </div>
          )}

          {error && (
            <div className="form-note" data-tone="danger" style={{ marginTop: 16 }} role="alert">
              <Icon.Alert size={13} />
              <span>{error}</span>
            </div>
          )}
        </div>

        <div className="dialog-foot">
          <span className="dialog-foot-hint"><kbd>Esc</kbd> 关闭</span>
          <div className="dialog-foot-actions">
            <button className="btn btn-ghost" onClick={onClose} disabled={submitting}>取消</button>
            <button className="btn btn-primary" onClick={handleConnect} disabled={submitting}>
              {submitting ? (
                <><span className="spinner" /> 提交中…</>
              ) : isEditing ? (
                <><Icon.Check size={13} /> 保存修改</>
              ) : (
                <><Icon.Plug size={13} /> 创建并连接</>
              )}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
