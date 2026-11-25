<template>
  <span
    v-if="compact"
    ref="contentRef"
    class="inline-flex"
    @mouseenter="setHover(true)"
    @mouseleave="setHover(false)"
    @focusin="setFocus(true)"
    @focusout="setFocus(false)"
  >
    <slot :tooltip-id="$slots.tooltip ? tooltipId : undefined"></slot>
    <Teleport v-if="$slots.tooltip" to="body">
      <div
        :id="tooltipId"
        ref="tooltipRef"
        role="tooltip"
        class="compact-tooltip"
        :style="compactPosition"
        :hidden="!compactOpen"
        @mouseenter="setHover(true)"
        @mouseleave="setHover(false)"
      >
        <slot name="tooltip"></slot>
      </div>
    </Teleport>
  </span>
  <div v-else class="group pointer-events-none inline">
    <div ref="contentRef" class="pointer-events-auto flex items-center gap-x-1">
      <slot :tooltip-id="undefined"></slot>
      <InfoIcon v-if="showInfoSymbol && $slots.tooltip" class="ml-0!" />
    </div>
    <span
      v-if="$slots.tooltip"
      ref="tooltipRef"
      class="invisible absolute box-border delay-0 group-hover:visible group-hover:delay-200"
    >
      <span
        class="arrowBase bg-tooltip pointer-events-auto relative z-10 block rounded-md px-1 text-center text-white after:absolute after:border-4 after:border-solid after:border-transparent"
        :style="tooltipPosition"
        :class="{
          'after:top-1/2 after:-mt-1': props.direction == 'left' || props.direction == 'right',
          'after:left-1/2! after:-ml-1': props.direction == 'top' || props.direction == 'bottom',
          'after:border-t-tooltip! after:top-full': props.direction == 'top',
          'after:border-b-tooltip! after:bottom-full': props.direction == 'bottom',
          'after:border-l-tooltip! after:left-full': props.direction == 'left',
          'after:border-r-tooltip! after:right-full': props.direction == 'right'
        }"
      >
        <slot name="tooltip"></slot>
      </span>
    </span>
  </div>
</template>

<script setup lang="ts">
import type { ToolTipDirection } from './ToolTip'
import {
  computed,
  nextTick,
  onBeforeUnmount,
  ref,
  useId,
  useSlots,
  type PropType,
  type Ref,
  type StyleValue
} from 'vue'
import InfoIcon from './InfoIcon.vue'

const props = defineProps({
  /** Keeps short hints outside scroll clipping, with keyboard and pointer access. */
  compact: { type: Boolean, default: false },
  direction: {
    type: String as PropType<ToolTipDirection>,
    required: false,
    default: 'top'
  },
  /** Sometimes the absolute div is centered horizontally on the content. Set this to true if that is the case. */
  toolTipContainerWillBeCentered: {
    type: Boolean,
    required: false,
    default: false
  },
  showInfoSymbol: {
    type: Boolean,
    required: false,
    default: true
  },
  /** Can be set if the tooltip is inside a scrollable container */
  scrollOffsetX: {
    type: Number,
    required: false,
    default: 0
  },
  scrollOffsetY: {
    type: Number,
    required: false,
    default: 0
  }
})

const contentRef: Ref<HTMLElement | null> = ref(null)
const tooltipRef: Ref<HTMLElement | null> = ref(null)
const arrowOffset = 4
const tooltipId = useId()
const slots = useSlots()
const compactOpen = ref(false)
const compactPosition = ref({ left: '0px', top: '0px', colorScheme: 'light', fontFamily: '' })
let hovered = false
let focused = false
let closeTimer: ReturnType<typeof setTimeout> | undefined

function setHover(value: boolean) {
  hovered = value
  if (value) void openCompactTooltip()
  else scheduleClose()
}
function setFocus(value: boolean) {
  focused = value
  if (value) void openCompactTooltip()
  else scheduleClose()
}
function cancelClose() {
  clearTimeout(closeTimer)
}
function closeCompactTooltip() {
  cancelClose()
  compactOpen.value = false
  window.removeEventListener('scroll', closeCompactTooltip, true)
  window.removeEventListener('resize', closeCompactTooltip)
  document.removeEventListener('keydown', dismissCompactTooltip)
}
function dismissCompactTooltip(event: KeyboardEvent) {
  if (event.key === 'Escape') closeCompactTooltip()
}
function scheduleClose() {
  cancelClose()
  closeTimer = setTimeout(() => {
    if (!hovered && !focused) closeCompactTooltip()
  }, 100)
}
async function openCompactTooltip() {
  cancelClose()
  if (!slots.tooltip || compactOpen.value || !contentRef.value) return
  // Teleport leaves the application's theme and font ancestors behind.
  const triggerStyle = getComputedStyle(contentRef.value)
  compactPosition.value.colorScheme = triggerStyle.colorScheme
  compactPosition.value.fontFamily = triggerStyle.fontFamily
  // Measure at the viewport origin so a stale position cannot constrain wrapping after a resize.
  compactPosition.value.left = '0px'
  compactPosition.value.top = '0px'
  compactOpen.value = true
  await nextTick()
  if (!compactOpen.value || !contentRef.value || !tooltipRef.value) return
  const trigger = contentRef.value.getBoundingClientRect()
  const tooltip = tooltipRef.value.getBoundingClientRect()
  const gap = 6
  const top = trigger.top - tooltip.height - gap
  compactPosition.value.left = `${Math.max(gap, Math.min(trigger.left, window.innerWidth - tooltip.width - gap))}px`
  compactPosition.value.top = `${Math.max(gap, Math.min(top >= gap ? top : trigger.bottom + gap, window.innerHeight - tooltip.height - gap))}px`
  window.addEventListener('scroll', closeCompactTooltip, true)
  window.addEventListener('resize', closeCompactTooltip)
  document.addEventListener('keydown', dismissCompactTooltip)
}
onBeforeUnmount(() => {
  if (props.compact) closeCompactTooltip()
})

const tooltipPosition = computed(() => {
  const style: StyleValue = {}
  const contentDiv = contentRef.value
  const tooltipDiv = tooltipRef.value
  if (!contentDiv || !tooltipDiv) {
    return style
  }
  // zeros the tooltip on the top-left of the content
  let top = -contentDiv.offsetHeight - props.scrollOffsetY
  let left =
    (props.toolTipContainerWillBeCentered ? -contentDiv.offsetWidth / 2 : 0) - props.scrollOffsetX
  if (props.direction == 'right' || props.direction == 'left') {
    top += (contentDiv.offsetHeight - tooltipDiv.offsetHeight) / 2
  } else {
    left -= (tooltipDiv.offsetWidth - contentDiv.offsetWidth) / 2
  }

  if (props.direction == 'right') {
    left += contentDiv.offsetWidth + arrowOffset
  } else if (props.direction == 'left') {
    left -= tooltipDiv.offsetWidth + arrowOffset
  } else if (props.direction == 'bottom') {
    top += contentDiv.offsetHeight + arrowOffset
  } else {
    top -= tooltipDiv.offsetHeight + arrowOffset
  }

  style.top = top + 'px'
  style.left = left + 'px'
  return style
})
</script>

<style scoped>
.compact-tooltip {
  position: fixed;
  z-index: 100;
  max-width: min(20rem, calc(100vw - 12px));
  border: 1px solid light-dark(#d1d5db, #4b5563);
  border-radius: 0.375rem;
  padding: 0.625rem;
  background: light-dark(#f8fafc, #292d38);
  color: light-dark(#1f2937, #e5e7eb);
  color-scheme: light;
  font-size: 0.75rem;
  line-height: 1.5;
  overflow-wrap: anywhere;
  box-shadow: 0 2px 6px #0002;
}

.arrowBase::after {
  content: ' ';
}
</style>
