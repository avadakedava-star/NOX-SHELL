import type { SVGProps } from 'react'

/**
 * 统一图标：1.6px 线宽、24 网格、currentColor。
 * 避免 emoji——它们在不同系统上尺寸和基线不一致，会破坏终端的精密感。
 */
type IconProps = SVGProps<SVGSVGElement> & { size?: number }

function base({ size = 16, strokeWidth = 1.6, ...rest }: IconProps) {
  return {
    width: size,
    height: size,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    'aria-hidden': true,
    ...rest,
  }
}

export const Icon = {
  Terminal: (p: IconProps) => (
    <svg {...base(p)}><path d="M4 7l4 5-4 5" /><path d="M12 17h8" /></svg>
  ),
  Server: (p: IconProps) => (
    <svg {...base(p)}><rect x="3" y="4" width="18" height="6" rx="1.5" /><rect x="3" y="14" width="18" height="6" rx="1.5" /><path d="M7 7h.01M7 17h.01" /></svg>
  ),
  Folder: (p: IconProps) => (
    <svg {...base(p)}><path d="M3 7a2 2 0 0 1 2-2h4l2 2.5h8a2 2 0 0 1 2 2V18a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" /></svg>
  ),
  Transfer: (p: IconProps) => (
    <svg {...base(p)}><path d="M7 17V7m0 0L4 10m3-3l3 3" /><path d="M17 7v10m0 0l3-3m-3 3l-3-3" /></svg>
  ),
  Puzzle: (p: IconProps) => (
    <svg {...base(p)}><path d="M10 4a1.5 1.5 0 1 1 3 0v1h3a1 1 0 0 1 1 1v3h1a1.5 1.5 0 1 1 0 3h-1v3a1 1 0 0 1-1 1h-3v1a1.5 1.5 0 1 1-3 0v-1H7a1 1 0 0 1-1-1v-3H5a1.5 1.5 0 1 1 0-3h1V6a1 1 0 0 1 1-1h3z" /></svg>
  ),
  Settings: (p: IconProps) => (
    <svg {...base(p)}><path d="M4 7.5h10M18 7.5h2M4 16.5h2M10 16.5h10" /><circle cx="16" cy="7.5" r="2" /><circle cx="8" cy="16.5" r="2" /></svg>
  ),
  Spark: (p: IconProps) => (
    <svg {...base(p)}><path d="M12 3v4M12 17v4M4.9 7.9l2.8 2.8M16.3 13.3l2.8 2.8M3 12h4M17 12h4M4.9 16.1l2.8-2.8M16.3 10.7l2.8-2.8" /></svg>
  ),
  Plus: (p: IconProps) => <svg {...base(p)}><path d="M12 5v14M5 12h14" /></svg>,
  X: (p: IconProps) => <svg {...base(p)}><path d="M6 6l12 12M18 6L6 18" /></svg>,
  Check: (p: IconProps) => <svg {...base(p)}><path d="M20 6L9 17l-5-5" /></svg>,
  Alert: (p: IconProps) => (
    <svg {...base(p)}><path d="M12 4l9 16H3z" /><path d="M12 10v4M12 17h.01" /></svg>
  ),
  Info: (p: IconProps) => (
    <svg {...base(p)}><circle cx="12" cy="12" r="8.5" /><path d="M12 11v5M12 8h.01" /></svg>
  ),
  ChevronDown: (p: IconProps) => <svg {...base(p)}><path d="M6 9l6 6 6-6" /></svg>,
  ChevronRight: (p: IconProps) => <svg {...base(p)}><path d="M9 6l6 6-6 6" /></svg>,
  ArrowDown: (p: IconProps) => <svg {...base(p)}><path d="M12 5v14M6 13l6 6 6-6" /></svg>,
  ArrowRight: (p: IconProps) => <svg {...base(p)}><path d="M5 12h14M13 6l6 6-6 6" /></svg>,
  Search: (p: IconProps) => (
    <svg {...base(p)}><circle cx="11" cy="11" r="6.5" /><path d="M16 16l4 4" /></svg>
  ),
  Refresh: (p: IconProps) => (
    <svg {...base(p)}><path d="M20 11a8 8 0 1 0-.7 4.3" /><path d="M20 5v6h-6" /></svg>
  ),
  Plug: (p: IconProps) => (
    <svg {...base(p)}><path d="M9 3v4M15 3v4" /><path d="M6 7h12v3a6 6 0 0 1-12 0z" /><path d="M12 16v5" /></svg>
  ),
  PlugOff: (p: IconProps) => (
    <svg {...base(p)}><path d="M9 3v4M15 3v4" /><path d="M6 7h12v3a6 6 0 0 1-.9 3.1" /><path d="M12 16v5M4 4l16 16" /></svg>
  ),
  Edit: (p: IconProps) => (
    <svg {...base(p)}><path d="M4 20h4L19 9a2.1 2.1 0 0 0-3-3L5 17z" /><path d="M14.5 6.5l3 3" /></svg>
  ),
  Trash: (p: IconProps) => (
    <svg {...base(p)}><path d="M4 7h16M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2" /><path d="M6 7l1 12a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-12" /></svg>
  ),
  Copy: (p: IconProps) => (
    <svg {...base(p)}><rect x="9" y="9" width="11" height="11" rx="1.5" /><path d="M5 15V5a1 1 0 0 1 1-1h10" /></svg>
  ),
  Clock: (p: IconProps) => (
    <svg {...base(p)}><circle cx="12" cy="12" r="8.5" /><path d="M12 8v4.5l3 1.5" /></svg>
  ),
  History: (p: IconProps) => (
    <svg {...base(p)}><path d="M3.5 12a8.5 8.5 0 1 0 2.6-6.1" /><path d="M3 4v5h5" /><path d="M12 8v4.5l3 1.5" /></svg>
  ),
  Eraser: (p: IconProps) => (
    <svg {...base(p)}><path d="M4 16l7-7 6 6-4 4H7z" /><path d="M10 20h10" /></svg>
  ),
  Wrench: (p: IconProps) => (
    <svg {...base(p)}><path d="M15 4a5 5 0 0 0-4.6 7L4 17.4V20h2.6l6.4-6.4A5 5 0 0 0 20 9l-3 1-2-2z" /></svg>
  ),
  Layers: (p: IconProps) => (
    <svg {...base(p)}><path d="M12 3l9 5-9 5-9-5z" /><path d="M3 13l9 5 9-5" /></svg>
  ),
  Stop: (p: IconProps) => <svg {...base(p)} fill="currentColor" stroke="none"><rect x="6" y="6" width="12" height="12" rx="1.5" /></svg>,
  Send: (p: IconProps) => (
    <svg {...base(p)}><path d="M5 12h13M12 5l7 7-7 7" /></svg>
  ),
  PanelLeft: (p: IconProps) => (
    <svg {...base(p)}><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M9 4v16" /></svg>
  ),
  PanelRight: (p: IconProps) => (
    <svg {...base(p)}><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M15 4v16" /></svg>
  ),
  Dot: (p: IconProps) => <svg {...base(p)} fill="currentColor" stroke="none"><circle cx="12" cy="12" r="3" /></svg>,
  Question: (p: IconProps) => (
    <svg {...base(p)}><circle cx="12" cy="12" r="8.5" /><path d="M9.5 9.5a2.5 2.5 0 1 1 3.4 2.3c-.6.3-.9.8-.9 1.4v.3" /><path d="M12 16.5h.01" /></svg>
  ),
  Shield: (p: IconProps) => (
    <svg {...base(p)}><path d="M12 3l7 3v6c0 4-3 7-7 9-4-2-7-5-7-9V6z" /><path d="M9 12l2 2 4-4" /></svg>
  ),
}

export type IconName = keyof typeof Icon
