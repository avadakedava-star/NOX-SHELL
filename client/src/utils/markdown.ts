/**
 * 轻量 Markdown 渲染：先整体转义，再做确定性替换，最后用 DOMParser 清洗。
 * 输出使用类名而不是内联样式，样式统一由 index.css 的 .md 规则控制。
 */

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/** 移除脚本、事件属性与危险协议，防 XSS。 */
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
    const tag = el.tagName.toLowerCase()
    if (['script', 'iframe', 'object', 'embed', 'link', 'meta', 'base', 'form', 'style'].includes(tag)) {
      el.remove()
      continue
    }
    for (const attr of Array.from(el.attributes)) {
      const name = attr.name.toLowerCase()
      if (name.startsWith('on')) { el.removeAttribute(attr.name); continue }
      if ((name === 'href' || name === 'src') && /^\s*(javascript|data|vbscript):/i.test(attr.value)) {
        el.removeAttribute(attr.name)
      }
    }
  }
  return doc.body.innerHTML
}

export function formatMarkdown(text: string): string {
  const codeBlocks: string[] = []
  let html = escapeHtml(text).replace(/```(\w*)\n?([\s\S]*?)```/g, (_match, lang, code) => {
    const placeholder = `__CODE_BLOCK_${codeBlocks.length}__`
    const language = lang ? `<div class="md-lang">${lang}</div>` : ''
    codeBlocks.push(
      `<div class="md-code-wrap"><button type="button" class="md-copy" data-code="${codeBlocks.length - 1}">复制</button><pre>${language}<code>${code.trim()}</code></pre></div>`,
    )
    return placeholder
  })

  html = html.replace(/`([^`\n]+)`/g, '<code>$1</code>')
  html = html.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>')
  html = html.replace(/(^|[^"'>])(https?:\/\/[^\s<]+)/g, '$1<a href="$2" target="_blank" rel="noopener noreferrer">$2</a>')

  html = html.replace(/(\|.+\|\n\|[-| :]+\|\n(?:\|.+\|\n?)+)/g, (match) => {
    const lines = match.trim().split('\n')
    const header = lines[0].split('|').filter(cell => cell.trim())
      .map(cell => `<th>${cell.trim()}</th>`).join('')
    const rows = lines.slice(2).map((line) => {
      const cells = line.split('|').filter(cell => cell.trim()).map(cell => `<td>${cell.trim()}</td>`).join('')
      return `<tr>${cells}</tr>`
    }).join('')
    return `<table><thead><tr>${header}</tr></thead><tbody>${rows}</tbody></table>`
  })

  html = html.replace(/^### (.+)$/gm, '<h3>$1</h3>')
  html = html.replace(/^## (.+)$/gm, '<h2>$1</h2>')
  html = html.replace(/^# (.+)$/gm, '<h1>$1</h1>')
  html = html.replace(/^---$/gm, '<hr/>')
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  html = html.replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g, '$1<em>$2</em>')
  html = html.replace(/~~([^~]+)~~/g, '<del>$1</del>')

  html = html.replace(/(?:^|\n)((?:- .+(?:\n|$))+)/g, (_match, listText) => {
    const items = listText.trim().split('\n')
      .map((line: string) => `<li>${line.replace(/^\-\s+/, '')}</li>`).join('')
    return `\n<ul>${items}</ul>`
  })
  html = html.replace(/(?:^|\n)((?:\d+\. .+(?:\n|$))+)/g, (_match, listText) => {
    const items = listText.trim().split('\n')
      .map((line: string) => `<li>${line.replace(/^\d+\.\s+/, '')}</li>`).join('')
    return `\n<ol>${items}</ol>`
  })
  html = html.replace(/(?:^|\n)((?:&gt;\s?.+(?:\n|$))+)/g, (_match, quoteText) => {
    const lines = quoteText.trim().split('\n')
      .map((line: string) => line.replace(/^&gt;\s?/, '')).join('<br/>')
    return `\n<blockquote>${lines}</blockquote>`
  })

  const placeholders = codeBlocks.map((_, index) => `__CODE_BLOCK_${index}__`)
  const blocks = html.split(/\n{2,}/).filter(Boolean).map((block) => {
    if (placeholders.some(placeholder => block.includes(placeholder))) return block
    if (/^<(h1|h2|h3|table|ul|ol|hr|a|p|blockquote)/.test(block.trim())) return block
    return `<p>${block.replace(/\n/g, '<br/>')}</p>`
  }).join('')

  const raw = codeBlocks.reduce(
    (content, block, index) => content.replace(`__CODE_BLOCK_${index}__`, block),
    blocks,
  )

  return sanitizeHtml(raw)
}
