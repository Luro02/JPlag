import type { NormalizationRegion, NormalizationToken } from '@jplag/model'
import type { highlightSource } from './SourceHighlight'
import { sides, type Side, type TokenView } from './useNormalizationComparison'

type SourcePart = ReturnType<typeof highlightSource>[number][number] & {
  before: string
  selected: string
  after: string
}
export type SourceRow = {
  number: number
  parts: SourcePart[]
  tokens: Record<Side, TokenView[]>
  selected: boolean
  unchanged: boolean
}
export type FoldRow = { start: number; end: number; hidden: number; expanded: boolean }

/** Determines whether a half-open source span covers any line in the inclusive range. */
export function regionOverlapsLines(
  region: NormalizationRegion | null,
  fileName: string,
  start: number,
  end = start
) {
  return (
    !!region &&
    region.file === fileName &&
    region.startLine <= end &&
    (region.endLine > start || (region.endLine === start && region.endColumn > 1))
  )
}

/** Keeps unavailable source starts reachable in the fallback row. */
export function tokenSourceLine(token: NormalizationToken, fileName: string, lineCount: number) {
  const region = token.region
  return region?.file === fileName && region.startLine > 0 && region.startLine <= lineCount
    ? region.startLine
    : 0
}

/** Builds source-ordered rows without depending on the current selection or page. */
export function buildSourceRows(
  syntaxLines: ReturnType<typeof highlightSource>,
  tokenViews: Record<Side, Map<number, TokenView>>,
  fileName: string,
  availableFiles: ReadonlySet<string>,
  unchanged: Record<Side, ReadonlySet<number>>,
  canFold: boolean
): SourceRow[] {
  const group = (side: Side) => {
    const lines = new Map<number, TokenView[]>()
    for (const view of tokenViews[side].values()) {
      const region = view.token.region
      if (region && region.file !== fileName && availableFiles.has(region.file)) continue
      const line = tokenSourceLine(view.token, fileName, syntaxLines.length)
      const tokens = lines.get(line) ?? []
      tokens.push(view)
      lines.set(line, tokens)
    }
    return lines
  }
  const tokensByLine = { Left: group('Left'), Right: group('Right') }
  const rows = syntaxLines.map((parts, index) => ({ number: index + 1, parts }))
  if (sides.some((side) => tokensByLine[side].has(0))) rows.push({ number: 0, parts: [] })
  return rows.map(({ number, parts }) => {
    const tokens = {
      Left: tokensByLine.Left.get(number) ?? [],
      Right: tokensByLine.Right.get(number) ?? []
    }

    return {
      number,
      parts: parts.map((part) => ({ ...part, before: part.text, selected: '', after: '' })),
      tokens,
      selected: false,
      // Tokenless source is foldable context, not evidence of a token correspondence.
      unchanged:
        canFold &&
        number > 0 &&
        sides.every((side) => tokens[side].every((view) => unchanged[side].has(view.token.index)))
    }
  })
}

/** Folds consecutive unchanged rows while keeping selected spans and manual expansions visible. */
export function foldSourceRows(
  rows: SourceRow[],
  expandedFolds: ReadonlySet<number>,
  selectedRegion: NormalizationRegion | null,
  fileName: string
): (SourceRow | FoldRow)[] {
  const runs: SourceRow[][] = []
  for (const row of rows) {
    const previous = runs.at(-1)
    if (row.unchanged && previous?.[0].unchanged && row.number === previous.at(-1)!.number + 1)
      previous.push(row)
    else runs.push([row])
  }
  return runs.flatMap<SourceRow | FoldRow>((run) => {
    if (!run[0].unchanged || run.length <= 5) return run
    // Two context lines keep changes readable without exposing the full unchanged stretch.
    const hidden = run.slice(2, -2)
    const start = hidden[0].number
    const end = hidden.at(-1)!.number
    const expanded =
      expandedFolds.has(start) || regionOverlapsLines(selectedRegion, fileName, start, end)
    const fold: FoldRow = { start, end, hidden: hidden.length, expanded }
    return [...run.slice(0, 2), fold, ...(expanded ? hidden : []), ...run.slice(-2)]
  })
}

/** Decorates only selected, displayed source rows, preserving all unselected row objects. */
export function decorateSelection(
  row: SourceRow,
  region: NormalizationRegion | null,
  fileName: string
): SourceRow {
  if (!region || !regionOverlapsLines(region, fileName, row.number)) return row
  // Columns refer to UTF-16 source characters, not expanded tab widths; the end is exclusive.
  const start = row.number === region.startLine ? region.startColumn - 1 : 0
  const end = row.number === region.endLine ? region.endColumn - 1 : Infinity
  return {
    ...row,
    selected: true,
    parts: row.parts.map((part) => {
      const from = Math.max(0, Math.min(part.text.length, start - part.offset))
      const to = Math.max(from, Math.min(part.text.length, end - part.offset))
      return {
        ...part,
        before: part.text.slice(0, from),
        selected: part.text.slice(from, to),
        after: part.text.slice(to)
      }
    })
  }
}
