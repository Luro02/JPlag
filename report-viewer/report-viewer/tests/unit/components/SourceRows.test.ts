// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { ParserLanguage, type NormalizationRegion } from '@jplag/model'
import { highlightSource } from '../../../src/components/SourceHighlight'
import {
  buildSourceRows,
  decorateSelection,
  foldSourceRows
} from '../../../src/components/SourceRows'
import type { TokenView } from '../../../src/components/useNormalizationComparison'

const fileName = 'Example.java'
const region: NormalizationRegion = {
  file: fileName,
  startLine: 1,
  startColumn: 2,
  endLine: 1,
  endColumn: 4
}
function view(index: number, location: NormalizationRegion | null = region): TokenView {
  return {
    token: {
      index,
      region: location,
      correspondenceKey: index,
      type: 'SAME',
      transformations: [],
      fileEnd: false
    },
    symbol: '',
    className: ''
  }
}
function rows(source: string, tokens: TokenView[] = [], canFold = true) {
  return buildSourceRows(
    highlightSource(source, ParserLanguage.JAVA),
    { Left: new Map(tokens.map((entry) => [entry.token.index, entry])), Right: new Map() },
    fileName,
    new Set([fileName, 'Other.java']),
    { Left: new Set(tokens.map((entry) => entry.token.index)), Right: new Set() },
    canFold
  )
}

describe('source rows', () => {
  it('preserves encounter order and fallback tokens while excluding other available files', () => {
    const result = rows('first\nlast', [
      view(7),
      view(2),
      view(9, null),
      view(10, { ...region, file: 'Missing.java' }),
      view(11, { ...region, startLine: 5, endLine: 5 }),
      view(12, { ...region, file: 'Other.java' })
    ])
    expect(result.map((row) => row.number)).toEqual([1, 2, 0])
    expect(result.map((row) => row.tokens.Left.map((entry) => entry.token.index))).toEqual([
      [7, 2],
      [],
      [9, 10, 11]
    ])
    expect(result[2].unchanged).toBe(false)
  })

  it('explicitly folds tokenless context only when a complete comparison is available', () => {
    const source = '// context\n\nlast'
    expect(rows(source).every((row) => row.unchanged)).toBe(true)
    expect(rows(source, [], false).some((row) => row.unchanged)).toBe(false)
  })

  it('expands selected half-open spans and manual folds without mutating base rows', () => {
    const base = rows(Array.from({ length: 12 }, () => 'context').join('\n'))
    const fold = (location: NormalizationRegion | null, expanded = new Set<number>()) =>
      foldSourceRows(base, expanded, location, fileName)
    expect(fold(null)).toHaveLength(5)
    expect(fold({ ...region, startLine: 10, endLine: 11, endColumn: 1 })).toHaveLength(13)
    expect(fold({ ...region, startLine: 1, endLine: 3, endColumn: 1 })).toHaveLength(5)
    expect(fold({ ...region, file: 'Other.java', endLine: 12 })).toHaveLength(5)
    expect(fold(null, new Set([3]))).toHaveLength(13)
    expect(base.every((row) => !row.selected)).toBe(true)
  })

  it('preserves CRLF source, UTF-16 offsets, tabs and empty lines in exclusive selections', () => {
    const base = rows('a😀\t<b>&\r\n\r\nz')
    expect(base.map((row) => row.parts.map((part) => part.text).join(''))).toEqual([
      'a😀\t<b>&',
      '',
      'z'
    ])
    const span = { ...region, endLine: 2, endColumn: 1 }
    const selected = base.map((row) => decorateSelection(row, span, fileName))
    expect(selected.map((row) => row.parts.map((part) => part.selected).join(''))).toEqual([
      '😀\t<b>&',
      '',
      ''
    ])

    const tab = decorateSelection(base[0], { ...region, startColumn: 4, endColumn: 5 }, fileName)
    expect(tab.parts.map((part) => part.selected).join('')).toBe('\t')
    expect(base.every((row) => !row.selected && row.parts.every((part) => !part.selected))).toBe(
      true
    )
  })
})
