import type { Language } from '@jplag/model'
import { highlight } from '@jplag/ui-components/widget/fileDisplaying/CodeHighlighter'

/**
 * Retains syntax classes and original character offsets so source selections can use semantic marks.
 */
export function highlightSource(source: string, language: Language) {
  return highlight(source.replace(/\r\n/g, '\n'), language).map((html) => {
    const template = document.createElement('template')
    // Only the existing highlighter's escaped output is parsed, never raw submission source.
    template.innerHTML = html
    const walker = document.createTreeWalker(template.content, NodeFilter.SHOW_TEXT)
    const parts: { text: string; classes: string; offset: number }[] = []
    let offset = 0
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const classes: string[] = []
      for (let parent = node.parentElement; parent; parent = parent.parentElement) {
        if (parent.className) classes.push(parent.className)
      }
      const text = node.textContent ?? ''
      parts.push({ text, classes: classes.join(' '), offset })
      offset += text.length
    }
    return parts
  })
}
