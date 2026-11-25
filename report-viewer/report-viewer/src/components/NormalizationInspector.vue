<template>
  <ContainerComponent
    role="region"
    aria-label="Normalization inspector"
    class="normalization-inspector flex min-h-0 flex-1 flex-col text-left"
  >
    <header class="flex flex-wrap items-center gap-x-4 gap-y-1 border-b pb-1">
      <label class="flex min-w-0 items-center gap-2 text-xs">
        File
        <select
          v-model="sourceFileName"
          aria-label="Source file"
          class="max-w-sm min-w-0 rounded border p-1 dark:bg-gray-800"
        >
          <option v-for="file in files" :key="file.fileName" :value="file.fileName">
            {{ file.fileName }}
          </option>
        </select>
      </label>
      <span class="ml-auto text-xs whitespace-nowrap text-gray-600 dark:text-gray-300"
        >{{ sideState.Left.tokens.length }} → {{ sideState.Right.tokens.length }} tokens ·
        {{ correspondence?.groups.length ?? 0 }} correspondence groups</span
      >
    </header>
    <p v-if="availableConfigurations.length < 2" role="status" class="pb-2 text-sm">
      Fewer than two configuration streams are available.
    </p>
    <div
      class="source-token-viewport min-h-0 flex-1 overflow-auto"
      aria-label="Source-aligned token streams"
      tabindex="0"
    >
      <SourceTokenTable
        :rows="renderedRows"
        :side-state="sideState"
        :configuration-options="configurationOptions"
        :highlighted="highlighted"
        @configuration="configure"
        @token="toggleToken"
        @line="selectLine"
        @fold="toggleFold"
      />

      <p v-if="!sourceFile" role="status" class="p-2">Source file unavailable.</p>
    </div>
  </ContainerComponent>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ContainerComponent } from '@jplag/ui-components/base'
import {
  ParserLanguage,
  type Language,
  type NormalizationAnalysis,
  type NormalizationSubmission,
  type NormalizationToken,
  type SubmissionFile
} from '@jplag/model'
import { highlightSource } from './SourceHighlight'
import { unchangedTokenPositions } from './TokenDifferences'
import {
  buildSourceRows,
  decorateSelection,
  foldSourceRows,
  regionOverlapsLines,
  type FoldRow
} from './SourceRows'
import SourceTokenTable from './SourceTokenTable.vue'
import {
  positions,
  sides,
  useNormalizationComparison,
  type Side
} from './useNormalizationComparison'

const props = withDefaults(
  defineProps<{
    analysis: NormalizationAnalysis
    submission: NormalizationSubmission
    files: SubmissionFile[]

    language?: Language
  }>(),
  { language: ParserLanguage.JAVA }
)

const {
  availableConfigurations,
  leftId,
  rightId,
  correspondence,
  sideState,
  tokenViews,
  options,
  changeConfiguration
} = useNormalizationComparison(props)
const configurationOptions = computed(() => ({ Left: options('Left'), Right: options('Right') }))
const selection = ref<{ side: Side; token: NormalizationToken } | null>(null)
const sourceFileName = ref('')

const expandedFolds = ref(new Set<number>())
watch(
  () => props.files,
  (files) => {
    if (!files.some((file) => file.fileName === sourceFileName.value))
      sourceFileName.value = files[0]?.fileName ?? ''
  },
  { immediate: true }
)
watch([leftId, rightId, availableConfigurations], () => {
  selection.value = null
})
function configure(side: Side, id: string) {
  changeConfiguration(side, id)
  selection.value = null
}
const selectedGroup = computed(() =>
  selection.value
    ? sideState.value[selection.value.side].groups.get(selection.value.token.index)
    : undefined
)
const selectedRegion = computed(() => selection.value?.token.region ?? null)
const highlighted = computed<Record<Side, ReadonlySet<number>>>(() => {
  const indices = {
    Left: new Set(positions('Left', selectedGroup.value)),
    Right: new Set(positions('Right', selectedGroup.value))
  }
  if (selection.value) indices[selection.value.side].add(selection.value.token.index)
  return indices
})

const sourceFile = computed(() =>
  props.files.find((file) => file.fileName === sourceFileName.value)
)
const syntaxLines = computed(() => highlightSource(sourceFile.value?.data ?? '', props.language))
const availableFiles = computed(() => new Set(props.files.map((file) => file.fileName)))

const unchanged = computed(() =>
  unchangedTokenPositions(
    sideState.value.Left.tokens,
    sideState.value.Right.tokens,
    correspondence.value?.groups ?? []
  )
)
const canFold = computed(
  () => !!correspondence.value && !!sideState.value.Left.stream && !!sideState.value.Right.stream
)
const sourceRows = computed(() =>
  buildSourceRows(
    syntaxLines.value,
    tokenViews.value,
    sourceFileName.value,
    availableFiles.value,
    unchanged.value,
    canFold.value
  )
)
const displayRows = computed(() =>
  foldSourceRows(sourceRows.value, expandedFolds.value, selectedRegion.value, sourceFileName.value)
)
function toggleFold(row: FoldRow) {
  if (!row.expanded) {
    expandedFolds.value.add(row.start)
    return
  }
  expandedFolds.value.delete(row.start)
  // A selected span keeps its source visible until the selection or its fold is explicitly closed.
  if (regionOverlapsLines(selectedRegion.value, sourceFileName.value, row.start, row.end))
    selection.value = null
}
watch([sourceFileName, leftId, rightId], () => {
  expandedFolds.value.clear()
})
const renderedRows = computed(() =>
  displayRows.value.map((row) =>
    'hidden' in row ? row : decorateSelection(row, selectedRegion.value, sourceFileName.value)
  )
)
function toggleToken(side: Side, token: NormalizationToken) {
  selection.value =
    selection.value?.side === side && selection.value.token.index === token.index
      ? null
      : { side, token }
}
function selectLine(line: number) {
  const candidates = sides.flatMap((side) =>
    sideState.value[side].tokens
      .filter((token) => regionOverlapsLines(token.region, sourceFileName.value, line))
      .map((token) => ({ side, token }))
  )
  const current = candidates.findIndex(
    (entry) =>
      entry.side === selection.value?.side && entry.token.index === selection.value.token.index
  )
  const next = candidates[(current + 1) % candidates.length]
  if (next) selection.value = next
}
</script>

<style scoped>
.normalization-inspector {
  color-scheme: light;
}
:global(.dark .normalization-inspector) {
  color-scheme: dark;
}
.source-token-viewport {
  min-height: 22rem;
  max-height: 65vh;
  content-visibility: auto;
  contain-intrinsic-size: auto 600px;
  contain: layout style paint;
}
:deep(.source-cell) {
  tab-size: 4;
}
:deep(.source-row) {
  min-height: 1.75rem;
}
:deep(.source-cell mark) {
  background: light-dark(#bfdbfe, #1e40af);
  color: light-dark(#111827, #ffffff);
  text-decoration: underline;
}
:deep(.selected-source-line) {
  background: light-dark(#eff6ff, #15243a);
}
:deep(.token-chip) {
  cursor: pointer;
  color: light-dark(#374151, #e5e7eb);
  background: var(--token-background, transparent);
}
:deep(.left-only-token) {
  --token-background: light-dark(#fecaca, #572c32);
}
:deep(.right-only-token) {
  --token-background: light-dark(#bbf7d0, #234b38);
}
:deep(.changed-token) {
  --token-background: light-dark(#fde68a, #55431e);
}
:deep(.left-only-token .token-prefix) {
  color: light-dark(#b91c1c, #f87171);
}
:deep(.right-only-token .token-prefix) {
  color: light-dark(#15803d, #4ade80);
}
:deep(.changed-token .token-prefix) {
  color: light-dark(#a16207, #facc15);
}
:deep(.token-chip:hover:not(.selected-token)) {
  background: color-mix(
    in srgb,
    var(--token-background, light-dark(#e5e7eb, #374151)),
    light-dark(black, white) 8%
  );
}
:deep(.token-chip:active:not(.selected-token)) {
  background: color-mix(
    in srgb,
    var(--token-background, light-dark(#e5e7eb, #374151)),
    light-dark(black, white) 15%
  );
}
:deep(.selected-token) {
  background: light-dark(#dbeafe, #1e293b);
  outline: 1px solid #60a5fa;
}
:deep(.selected-token:hover) {
  background: light-dark(#bfdbfe, #263752);
}
:deep(.token-chip:focus-visible) {
  outline: 2px solid light-dark(#2563eb, #93c5fd);
  outline-offset: 2px;
}
</style>
