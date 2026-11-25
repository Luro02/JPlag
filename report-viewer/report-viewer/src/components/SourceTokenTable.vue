<template>
  <table class="w-full min-w-240 table-fixed border-collapse text-xs">
    <colgroup>
      <col class="w-10" />
      <col style="width: 43%" />
      <col />
      <col />
    </colgroup>
    <thead class="bg-container-light dark:bg-container-dark sticky top-0 z-10">
      <tr>
        <th
          colspan="2"
          class="border-b p-2 text-left"
          title="Tokens are grouped by their starting source line, not stream order."
        >
          Original source
        </th>
        <template v-for="side in sides" :key="side">
          <th :aria-label="`${side} token stream`" class="border-b p-2 text-left align-top">
            <label class="flex items-center gap-1">
              <span class="sr-only">{{ side }} configuration</span>
              <select
                :value="sideState[side].id"
                :aria-label="`${side} configuration`"
                class="w-full rounded border p-1 dark:bg-gray-800"
                @change="emit('configuration', side, ($event.target as HTMLSelectElement).value)"
              >
                <option v-if="!sideState[side].id" value="">No configuration available</option>
                <option
                  v-for="option in configurationOptions[side]"
                  :key="option.id"
                  :value="option.id"
                >
                  {{ option.name }}
                </option>
              </select>
            </label>
            <details
              v-if="sideState[side].configuration"
              class="mt-1 font-normal text-gray-600 dark:text-gray-300"
            >
              <summary class="cursor-pointer">
                {{ sideState[side].configuration?.normalizations.length }} optional normalizations
              </summary>
              Normalize: {{ sideState[side].configuration?.normalize ? 'yes' : 'no' }};
              {{ sideState[side].configuration?.normalizations.join(', ') || 'none' }}
            </details>
            <p v-if="!sideState[side].stream" role="status">Token stream unavailable.</p>
          </th>
        </template>
      </tr>
    </thead>
    <tbody>
      <template v-for="row in rows" :key="'hidden' in row ? `fold-${row.start}` : row.number">
        <tr v-if="'hidden' in row" class="fold-row">
          <td colspan="4" class="border-y border-dashed border-gray-300 dark:border-gray-600">
            <button
              type="button"
              :aria-expanded="row.expanded"
              class="w-full px-3 py-2 text-left text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-800"
              @click="emit('fold', row)"
            >
              <span aria-hidden="true">{{ row.expanded ? '▾' : '▸' }}</span>
              {{ row.expanded ? 'Hide' : 'Show' }} {{ row.hidden }} unchanged source lines ({{
                row.start
              }}–{{ row.end }})
            </button>
          </td>
        </tr>
        <tr
          v-else
          :data-source-line="row.number"
          class="source-row border-b border-gray-100 dark:border-gray-800"
        >
          <td class="p-1 text-right align-top text-gray-400">
            <button
              v-if="row.number"
              type="button"
              :aria-label="`Inspect tokens at line ${row.number}`"
              class="hover:text-blue-600"
              @click="emit('line', row.number)"
            >
              {{ row.number }}
            </button>
          </td>
          <td
            class="source-cell px-2 py-1 align-top"
            :class="{ 'selected-source-line': row.selected }"
          >
            <code v-if="row.number" class="leading-5 wrap-break-word whitespace-pre-wrap"
              ><span v-for="(part, index) in row.parts" :key="index" :class="part.classes"
                ><span>{{ part.before }}</span
                ><mark v-if="part.selected" data-selected-source>{{ part.selected }}</mark
                ><span>{{ part.after }}</span></span
              ></code
            >
            <span v-else class="text-gray-500 italic"
              >Submission tokens without an available source line</span
            >
          </td>
          <template v-for="side in sides" :key="side">
            <td class="border-l border-gray-200 p-1 align-top dark:border-gray-700">
              <div class="flex flex-wrap gap-x-2 gap-y-1">
                <ToolTipComponent
                  v-for="{ token, symbol, className } in row.tokens[side]"
                  :key="token.index"
                  compact
                  :show-info-symbol="false"
                >
                  <template #default="{ tooltipId }">
                    <button
                      type="button"
                      :data-token-side="side"
                      :data-token-index="token.index"
                      :aria-label="`${side} token ${token.index}: ${token.type}`"
                      :aria-pressed="highlighted[side].has(token.index)"
                      :aria-describedby="tooltipId"
                      class="token-chip rounded px-1.5 py-1 text-left font-mono text-[11px]"
                      :class="[className, { 'selected-token': highlighted[side].has(token.index) }]"
                      @click="emit('token', side, token)"
                    >
                      <span v-if="symbol" class="token-prefix mr-1" aria-hidden="true">{{
                        symbol
                      }}</span
                      >{{ token.type
                      }}<span
                        v-if="token.transformations.length"
                        class="ml-1.5 text-[10px] text-violet-600 dark:text-violet-300"
                        role="img"
                        aria-label="Recorded transformation history"
                        >◇</span
                      >
                    </button>
                  </template>
                  <template v-if="token.transformations.length" #tooltip>
                    <p class="mb-1.5 text-[11px] font-medium opacity-70">Transformation history</p>
                    <ul class="space-y-1">
                      <li v-for="name in token.transformations" :key="name">{{ name }}</li>
                    </ul>
                  </template>
                </ToolTipComponent>
              </div>
            </td>
          </template>
        </tr>
      </template>
    </tbody>
  </table>
</template>

<script setup lang="ts">
import type { NormalizationConfiguration, NormalizationToken } from '@jplag/model'
import { ToolTipComponent } from '@jplag/ui-components/base'
import type { FoldRow, SourceRow } from './SourceRows'
import { sides, type Side, type useNormalizationComparison } from './useNormalizationComparison'

defineProps<{
  rows: (SourceRow | FoldRow)[]
  sideState: ReturnType<typeof useNormalizationComparison>['sideState']['value']
  configurationOptions: Record<Side, NormalizationConfiguration[]>
  highlighted: Record<Side, ReadonlySet<number>>
}>()

const emit = defineEmits<{
  configuration: [side: Side, id: string]
  token: [side: Side, token: NormalizationToken]
  line: [number: number]
  fold: [row: FoldRow]
}>()
</script>
