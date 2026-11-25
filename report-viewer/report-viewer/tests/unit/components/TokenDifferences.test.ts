import { describe, expect, it } from 'vitest'
import type { NormalizationGroup, NormalizationToken } from '@jplag/model'
import { unchangedTokenPositions } from '../../../src/components/TokenDifferences'

function token(index: number, line = index + 1): NormalizationToken {
  return {
    index,
    type: 'SAME',
    correspondenceKey: index,
    region: {
      file: 'Example.java',
      startLine: line,
      startColumn: 1,
      endLine: line,
      endColumn: 4
    },
    transformations: [],
    fileEnd: false
  }
}
function group(left: number[], right: number[]): NormalizationGroup {
  return { leftPositions: left, rightPositions: right }
}

describe('unchangedTokenPositions', () => {
  it('ignores provenance and index shifts from removals or unmatched insertions', () => {
    const left = [token(0), token(4), token(9)]
    const right = [{ ...token(20, 5), transformations: ['REORDER'] }, token(21, 7), token(30, 10)]
    const unchanged = unchangedTokenPositions(left, right, [group([9], [30]), group([4], [20])])
    expect(unchanged.Left).toEqual(new Set([4, 9]))
    expect(unchanged.Right).toEqual(new Set([20, 30]))
  })

  it.each(['Left', 'Right'])(
    'ignores dangling groups on the %s without hiding their tokens',
    (side) => {
      const left = (side === 'Left' ? [0, 1, 2] : [0, 2]).map((index) => token(index))
      const right = (side === 'Right' ? [10, 11, 12] : [10, 12]).map((index) => token(index))
      const dangling = side === 'Left' ? group([1], [99]) : group([99], [11])
      const unchanged = unchangedTokenPositions(left, right, [
        group([0], [10]),
        dangling,
        group([2], [12])
      ])
      expect(unchanged.Left).toEqual(new Set([0, 2]))
      expect(unchanged.Right).toEqual(new Set([10, 12]))
    }
  )

  it('keeps every crossing participant visible, including a reversed sequence midpoint with equal rank', () => {
    const unchanged = unchangedTokenPositions(
      [token(0), token(1), token(2), token(3)],
      [token(10, 3), token(11, 2), token(12, 1), token(13, 4)],
      [group([0], [12]), group([1], [11]), group([2], [10]), group([3], [13])]
    )
    expect(unchanged.Left).toEqual(new Set([3]))
    expect(unchanged.Right).toEqual(new Set([13]))
  })

  it('excludes type changes, ambiguous groups, unknown locations and unassociated tokens', () => {
    const left = Array.from({ length: 8 }, (_, index) => token(index))
    const right = Array.from({ length: 8 }, (_, index) => token(index + 10, index + 1))
    right[0].type = 'CHANGED'
    left[4].region = null
    right[5].region = null
    const unchanged = unchangedTokenPositions(left, right, [
      group([0], [10]),
      group([1], [11, 12]),
      group([2, 3], [13]),
      group([4], [14]),
      group([5], [15]),
      group([6], [16])
    ])
    expect(unchanged.Left).toEqual(new Set([6]))
    expect(unchanged.Right).toEqual(new Set([16]))
    expect(unchangedTokenPositions(left, [], [])).toEqual({ Left: new Set(), Right: new Set() })
  })
})
