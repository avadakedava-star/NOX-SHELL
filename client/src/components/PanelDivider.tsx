import { useCallback, useEffect, useRef, useState } from 'react'

interface PanelDividerProps {
  /** 当前宽度（用于拖拽基准与键盘步进） */
  value: number
  min: number
  max: number
  /** 拖拽方向：sidebar 为正，右侧面板为负 */
  direction?: 1 | -1
  onChange: (next: number) => void
  onReset?: () => void
  label: string
}

/**
 * 1px 视觉分割线 + 9px 命中区。
 * 拖拽期间给 body 加类，保证光标不闪烁、文本不被选中；
 * 双击恢复默认宽度，方向键可按 16px 步进（带 Shift 为 64px）。
 */
export function PanelDivider({ value, min, max, direction = 1, onChange, onReset, label }: PanelDividerProps) {
  const [dragging, setDragging] = useState(false)
  const valueRef = useRef(value)
  valueRef.current = value
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange

  const stop = useCallback(() => {
    setDragging(false)
    document.body.classList.remove('nox-col-resizing')
  }, [])

  const onPointerDown = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    if (event.button !== 0) return
    event.preventDefault()
    const startX = event.clientX
    const startValue = valueRef.current
    setDragging(true)
    document.body.classList.add('nox-col-resizing')

    const move = (e: PointerEvent) => {
      const delta = (e.clientX - startX) * direction
      onChangeRef.current(Math.min(max, Math.max(min, Math.round(startValue + delta))))
    }
    const up = () => {
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', up)
      window.removeEventListener('pointercancel', up)
      stop()
    }
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', up)
    window.addEventListener('pointercancel', up)
  }, [direction, max, min, stop])

  useEffect(() => stop, [stop])

  const onKeyDown = useCallback((event: React.KeyboardEvent<HTMLDivElement>) => {
    const step = event.shiftKey ? 64 : 16
    if (event.key === 'ArrowLeft') {
      event.preventDefault()
      onChange(Math.min(max, Math.max(min, value - step * direction)))
    } else if (event.key === 'ArrowRight') {
      event.preventDefault()
      onChange(Math.min(max, Math.max(min, value + step * direction)))
    } else if (event.key === 'Home') {
      event.preventDefault()
      onChange(min)
    } else if (event.key === 'End') {
      event.preventDefault()
      onChange(max)
    }
  }, [direction, max, min, onChange, value])

  return (
    <div
      role="separator"
      aria-orientation="vertical"
      aria-label={label}
      aria-valuenow={Math.round(value)}
      aria-valuemin={min}
      aria-valuemax={max}
      tabIndex={0}
      className="nox-divider"
      data-dragging={dragging}
      onPointerDown={onPointerDown}
      onDoubleClick={() => onReset?.()}
      onKeyDown={onKeyDown}
      title="拖拽调整宽度 · 双击恢复默认"
    />
  )
}
