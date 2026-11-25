import type { NormalizationGroup, NormalizationToken } from '@jplag/model'

/**
 * Finds equal-type, one-to-one correspondences that retain their relative stream order.
 * Unmatched tokens do not shift the comparison; ambiguous regions remain open for inspection.
 */
export function unchangedTokenPositions(
  left: NormalizationToken[],
  right: NormalizationToken[],
  groups: NormalizationGroup[]
) {
  const leftIndices = new Set(left.map((token) => token.index))
  const rightIndices = new Set(right.map((token) => token.index))
  const leftGroups = new Map<number, NormalizationGroup>()
  const rightGroups = new Map<number, NormalizationGroup>()
  for (const group of groups) {
    if (
      group.leftPositions.length === 1 &&
      group.rightPositions.length === 1 &&
      leftIndices.has(group.leftPositions[0]) &&
      rightIndices.has(group.rightPositions[0])
    ) {
      leftGroups.set(group.leftPositions[0], group)
      rightGroups.set(group.rightPositions[0], group)
    }
  }
  const leftShared = left.filter((token) => leftGroups.has(token.index))
  const rightShared = right.filter((token) => rightGroups.has(token.index))
  const rightRanks = new Map(rightShared.map((token, rank) => [rightGroups.get(token.index), rank]))
  const ranks = leftShared.map((token) => rightRanks.get(leftGroups.get(token.index)) ?? -1)
  const smallestAfter: number[] = []
  let smallest = Infinity
  for (let index = ranks.length - 1; index >= 0; index--) {
    smallestAfter[index] = smallest
    smallest = Math.min(smallest, ranks[index])
  }
  const unchanged = { Left: new Set<number>(), Right: new Set<number>() }
  let largestBefore = -1
  leftShared.forEach((token, index) => {
    const rank = ranks[index]
    const counterpart = rightShared[rank]
    // Check crossings on both sides: equal ranks alone can hide the middle of a reversed sequence.
    if (
      rank > largestBefore &&
      rank < smallestAfter[index] &&
      counterpart?.region &&
      token.region &&
      token.type === counterpart.type
    ) {
      unchanged.Left.add(token.index)
      unchanged.Right.add(counterpart.index)
    }
    largestBefore = Math.max(largestBefore, rank)
  })
  return unchanged
}
