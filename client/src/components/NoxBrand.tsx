export function NoxMark({ className = '' }: { className?: string }) {
  return <svg className={className} viewBox="0 0 48 48" fill="none" aria-hidden="true">
    <path d="M7 36V12h6l15 24h-7L13 23v13H7Zm22-24h12L26 36h-7l15-24h-5Z" fill="currentColor" />
    <path d="m32 27 9 9h-9l-4-4 4-5Z" fill="currentColor" />
  </svg>
}

export function NoxBrand() {
  return <div className="nox-brand"><NoxMark /><span>NOX<span className="nox-brand-separator">-</span>SHELL<span className="nox-brand-dot">.</span></span></div>
}
