import { computed, ref, watch } from 'vue'
import type {
  NormalizationAnalysis,
  NormalizationCorrespondence,
  NormalizationGroup,
  NormalizationStream,
  NormalizationSubmission,
  NormalizationToken
} from '@jplag/model'

export type Side = 'Left' | 'Right'
export const sides: Side[] = ['Left', 'Right']
export type TokenView = {
  token: NormalizationToken
  symbol: string
  className: string
}

/**
 * Returns positions from a group already oriented to the displayed sides.
 */
export function positions(side: Side, group?: NormalizationGroup): number[] {
  return group ? (side === 'Left' ? group.leftPositions : group.rightPositions) : []
}

/**
 * Identifies groups that cannot provide a unique token counterpart.
 */
export function ambiguous(group: NormalizationGroup) {
  return group.leftPositions.length > 1 || group.rightPositions.length > 1
}

function indexTokens(stream: NormalizationStream) {
  const byKey = new Map<number, NormalizationToken[]>()
  for (const token of stream.tokens) {
    if (token.fileEnd || token.correspondenceKey === null) continue
    const group = byKey.get(token.correspondenceKey)
    if (group) group.push(token)
    else byKey.set(token.correspondenceKey, [token])
  }
  return byKey
}

function unmatchedPositions(
  stream: NormalizationStream,
  otherTokens: Map<number, NormalizationToken[]>
) {
  return stream.tokens
    .filter(
      (token) =>
        !token.fileEnd &&
        token.correspondenceKey !== null &&
        token.region !== null &&
        !otherTokens.has(token.correspondenceKey)
    )
    .map((token) => token.index)
}

function compareStreams(
  left: NormalizationStream,
  right: NormalizationStream
): NormalizationCorrespondence {
  const leftTokens = indexTokens(left)
  const rightTokens = indexTokens(right)
  const groups: NormalizationGroup[] = []

  for (const [key, leftGroup] of leftTokens) {
    const rightGroup = rightTokens.get(key)
    if (!rightGroup) continue
    // Hidden counterparts still occupy their keys, but cannot form a displayed group.
    if ([...leftGroup, ...rightGroup].some((token) => token.region === null)) continue

    groups.push({
      leftPositions: leftGroup.map((token) => token.index),
      rightPositions: rightGroup.map((token) => token.index)
    })
  }

  return {
    groups,
    unmatchedLeftPositions: unmatchedPositions(left, rightTokens),
    unmatchedRightPositions: unmatchedPositions(right, leftTokens)
  }
}

/**
 * Prepares configuration comparisons and cached token presentation independently of selection.
 * The parent owns selection and must clear it when changing configurations.
 */
export function useNormalizationComparison(props: {
  analysis: NormalizationAnalysis
  submission: NormalizationSubmission
}) {
  const availableConfigurations = computed(() =>
    props.analysis.configurations.filter((configuration) =>
      props.submission.streams.some((stream) => stream.configurationId === configuration.id)
    )
  )
  const leftId = ref('')
  const rightId = ref('')

  function options(side: Side) {
    return props.analysis.configurations.filter(
      (entry) => side === 'Left' || entry.id !== leftId.value
    )
  }

  watch(
    availableConfigurations,
    () => {
      leftId.value =
        availableConfigurations.value.find(
          (entry) => entry.id === props.analysis.baselineConfigurationId
        )?.id ??
        availableConfigurations.value[0]?.id ??
        props.analysis.configurations[0]?.id ??
        ''
      rightId.value =
        availableConfigurations.value.find((entry) => entry.id !== leftId.value)?.id ??
        options('Right')[0]?.id ??
        ''
    },
    { immediate: true }
  )

  const selectedStreams = computed(() => {
    const build = (id: string) => {
      const stream = props.submission.streams.find((entry) => entry.configurationId === id)
      const tokens = stream?.tokens.filter((token) => !token.fileEnd) ?? []

      return {
        id,
        configuration: props.analysis.configurations.find((entry) => entry.id === id),
        stream,
        tokens,
        byIndex: new Map(tokens.map((token) => [token.index, token]))
      }
    }
    return { Left: build(leftId.value), Right: build(rightId.value) }
  })

  const correspondence = computed(() => {
    if (leftId.value === rightId.value) return undefined
    const { Left, Right } = selectedStreams.value
    return Left.stream && Right.stream ? compareStreams(Left.stream, Right.stream) : undefined
  })

  function changeConfiguration(side: Side, id: string) {
    if (side === 'Left') {
      leftId.value = id
      if (!options('Right').some((entry) => entry.id === rightId.value))
        rightId.value = options('Right')[0]?.id ?? ''
    } else rightId.value = id
  }

  const sideState = computed(() => {
    const pair = correspondence.value
    const build = (side: Side) => {
      const groups = new Map<number, NormalizationGroup>()
      for (const group of pair?.groups ?? []) {
        for (const position of positions(side, group)) groups.set(position, group)
      }
      return {
        ...selectedStreams.value[side],
        groups,
        unmatched: new Set(
          (side === 'Left' ? pair?.unmatchedLeftPositions : pair?.unmatchedRightPositions) ?? []
        )
      }
    }
    return { Left: build('Left'), Right: build('Right') }
  })

  const tokenViews = computed(() => {
    const state = sideState.value
    const appearance = (side: Side, token: NormalizationToken) => {
      if (!token.region) return
      const group = state[side].groups.get(token.index)
      if (group) {
        if (ambiguous(group)) return
        const otherSide = side === 'Left' ? 'Right' : 'Left'
        const counterpart = state[otherSide].byIndex.get(positions(otherSide, group)[0])
        if (counterpart && token.type !== counterpart.type)
          return { symbol: '~', className: 'changed-token' }
      }
      if (state[side].unmatched.has(token.index))
        return {
          symbol: side === 'Left' ? '−' : '+',
          className: side === 'Left' ? 'left-only-token' : 'right-only-token'
        }
    }
    const build = (side: Side) =>
      new Map<number, TokenView>(
        state[side].tokens.map((token) => [
          token.index,
          { token, symbol: '', className: '', ...appearance(side, token) }
        ])
      )
    return { Left: build('Left'), Right: build('Right') }
  })

  return {
    availableConfigurations,
    leftId,
    rightId,
    correspondence,
    options,
    changeConfiguration,
    sideState,
    tokenViews
  }
}
