export function formatDuration(startedAt?: number, completedAt?: number): string {
  if (!startedAt) return '—'
  const duration = Math.max(0, (completedAt ?? Date.now()) - startedAt)
  if (duration < 1000) return '0s'
  if (duration < 60_000) return `${Math.round(duration / 1000)}s`
  const minutes = Math.floor(duration / 60_000)
  const seconds = Math.round((duration % 60_000) / 1000)
  return `${minutes}m${seconds.toString().padStart(2, '0')}s`
}

export function formatClock(ts?: number): string {
  if (!ts) return '--:--'
  const date = new Date(ts)
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

export function formatClockSeconds(ts?: number): string {
  if (!ts) return '--:--:--'
  const date = new Date(ts)
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}:${String(date.getSeconds()).padStart(2, '0')}`
}

export function truncateText(text: string, maxLength = 900): string {
  if (text.length <= maxLength) return text
  return `${text.slice(0, maxLength)}\n…（已截断 ${text.length - maxLength} 字符）`
}

export function tailText(text: string, maxLength = 600): string {
  if (text.length <= maxLength) return text
  return `…${text.slice(-maxLength)}`
}
