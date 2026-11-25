// @vitest-environment jsdom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'

import { afterEach, describe, expect, it, vi } from 'vitest'
import type {
  NormalizationAnalysis,
  NormalizationRegion,
  NormalizationSubmission,
  NormalizationToken,
  SubmissionFile
} from '@jplag/model'
import NormalizationInspector from '../../../src/components/NormalizationInspector.vue'
import { useNormalizationComparison } from '../../../src/components/useNormalizationComparison'

enableAutoUnmount(afterEach)
afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
})

const region: NormalizationRegion = {
  file: 'Example.java',
  startLine: 1,
  startColumn: 2,
  endLine: 2,
  endColumn: 4
}
function token(index: number, location: NormalizationRegion | null = region): NormalizationToken {
  return {
    index,
    type: `TYPE_${index}`,
    region: location,
    correspondenceKey: index,
    transformations: ['REORDER'],
    fileEnd: false
  }
}
function fixture() {
  const analysis: NormalizationAnalysis = {
    configurations: ['baseline', 'normalized', 'third', 'unexported'].map((id) => ({
      id,
      name: id,
      normalizations: id === 'baseline' ? [] : ['REORDER'],
      normalize: id !== 'baseline'
    })),
    baselineConfigurationId: 'baseline',
    submissions: { example: 'normalization/example.json' }
  }
  const submission: NormalizationSubmission = {
    streams: [
      {
        configurationId: 'baseline',
        tokens: [
          { ...token(7), correspondenceKey: 0 },
          { ...token(2), correspondenceKey: 0 },
          token(8),
          token(9, null),
          { ...token(10, null), correspondenceKey: null, fileEnd: true }
        ]
      },
      {
        configurationId: 'normalized',
        tokens: [
          { ...token(20), correspondenceKey: 0 },
          { ...token(21), correspondenceKey: 0 },
          token(22)
        ]
      }
    ]
  }
  const files: SubmissionFile[] = [
    { submissionId: 'example', fileName: 'Example.java', data: 'x\t<b>&\nabc<script>\nlast' },
    { submissionId: 'example', fileName: 'Other.java', data: '<img src=x onerror=alert(1)>' }
  ]
  return { analysis, submission, files }
}
function largeFixture() {
  const props = fixture()
  const location = (line: number) => ({ ...region, startLine: line, endLine: line })
  props.files[0].data = Array.from({ length: 125 }, () => 'source').join('\n')
  props.submission.streams[0].tokens = [
    { ...token(7, location(1)), transformations: [] },
    token(2, location(121)),
    { ...token(8, location(125)), transformations: [] }
  ]

  props.submission.streams = [props.submission.streams[0]]
  return props
}
function unchangedFixture(lineCount = 12) {
  const props = fixture()
  props.files[0].data = Array.from({ length: lineCount }, (_, index) => `source ${index + 1}`).join(
    '\n'
  )
  const tokens = Array.from({ length: lineCount }, (_, index) => ({
    ...token(index + 1, { ...region, startLine: index + 1, endLine: index + 1 }),
    type: 'SAME',
    transformations: []
  }))
  props.submission.streams[0].tokens = tokens
  props.submission.streams[1].tokens = tokens.map((entry) => ({
    ...entry,
    index: entry.index + 100,
    transformations: entry.index === Math.ceil(lineCount / 2) ? ['REORDER'] : []
  }))

  return props
}
function inspector(props = fixture()) {
  return mount(NormalizationInspector, { props })
}

function markedSource(wrapper: ReturnType<typeof inspector>) {
  return wrapper.findAll('tbody code').map((row) =>
    row
      .findAll('[data-selected-source]')
      .map((mark) => mark.element.textContent)
      .join('')
  )
}

describe('NormalizationInspector', () => {
  it('retains the selected file when source arrays are replaced, falling back only when removed', async () => {
    const props = fixture()
    const wrapper = inspector(props)
    const files = wrapper.get('[aria-label="Source file"]')
    await files.setValue('Other.java')
    await wrapper.setProps({ files: props.files.map((file) => ({ ...file })) })
    expect(files.element).toHaveProperty('value', 'Other.java')
    await wrapper.setProps({ files: [props.files[0]] })
    expect(files.element).toHaveProperty('value', 'Example.java')
  })

  it('shows only the transformation history heading and rule names in a compact hover tooltip', async () => {
    vi.useFakeTimers()
    const props = fixture()
    props.submission.streams[0].tokens[0].transformations = ['REORDER', 'RENAME']
    const wrapper = inspector(props)
    try {
      const chip = wrapper.get('[aria-label="Left token 7: TYPE_7"]')

      const tooltipId = chip.attributes('aria-describedby')
      expect(tooltipId).toBeTruthy()
      const tooltip = document.getElementById(tooltipId)!
      expect(tooltip).not.toBeNull()
      expect(tooltip.parentElement).toBe(document.body)
      expect(tooltip.getAttribute('role')).toBe('tooltip')
      expect(tooltip.hidden).toBe(true)
      expect(tooltip.querySelector('p')?.textContent).toBe('Transformation history')
      expect(Array.from(tooltip.querySelectorAll('ul li'), (item) => item.textContent)).toEqual([
        'REORDER',
        'RENAME'
      ])
      expect(tooltip.textContent?.replace(/\s+/g, '')).toBe('TransformationhistoryREORDERRENAME')

      expect(chip.attributes('title')).toBeUndefined()

      const trigger = chip.element.parentElement!
      trigger.dispatchEvent(new MouseEvent('mouseenter'))
      await flushPromises()
      expect(tooltip.hidden).toBe(false)
      trigger.dispatchEvent(new MouseEvent('mouseleave'))
      tooltip.dispatchEvent(new MouseEvent('mouseenter'))
      await vi.runAllTimersAsync()
      expect(tooltip.hidden).toBe(false)
      tooltip.dispatchEvent(new MouseEvent('mouseleave'))
      await vi.runAllTimersAsync()
      expect(tooltip.hidden).toBe(true)
    } finally {
      wrapper.unmount()
    }
  })

  it('copies the trigger theme and remeasures the tooltip from the viewport origin on each open', async () => {
    const wrapper = inspector()
    try {
      const chip = wrapper.get('[aria-label="Left token 7: TYPE_7"]')
      const trigger = chip.element.parentElement!
      const tooltip = document.getElementById(chip.attributes('aria-describedby'))!
      trigger.style.colorScheme = 'dark'
      trigger.style.fontFamily = 'monospace'
      const measuredPositions: string[] = []
      vi.spyOn(trigger, 'getBoundingClientRect').mockReturnValue(new DOMRect(300, 200, 100, 24))
      vi.spyOn(tooltip, 'getBoundingClientRect').mockImplementation(() => {
        measuredPositions.push(tooltip.style.left)
        return new DOMRect(0, 0, 160, 50)
      })
      const computedStyle = window.getComputedStyle(trigger)

      expect(tooltip.parentElement).toBe(document.body)
      expect(tooltip.hidden).toBe(true)
      trigger.dispatchEvent(new MouseEvent('mouseenter'))
      await flushPromises()
      expect(tooltip.hidden).toBe(false)
      expect(tooltip.style.colorScheme).toBe(computedStyle.colorScheme)
      expect(tooltip.style.fontFamily).toBe(computedStyle.fontFamily)
      expect(tooltip.style.left).toBe('300px')
      window.dispatchEvent(new Event('resize'))
      await nextTick()
      trigger.dispatchEvent(new MouseEvent('mouseenter'))
      await flushPromises()
      expect(measuredPositions).toEqual(['0px', '0px'])
    } finally {
      wrapper.unmount()
    }
  })

  it('opens history on focus, preserves token selection and highlighting, and closes on Escape', async () => {
    const wrapper = mount(NormalizationInspector, { props: fixture(), attachTo: document.body })
    try {
      const chip = wrapper.get<HTMLButtonElement>('[aria-label="Left token 7: TYPE_7"]')
      const tooltip = document.getElementById(chip.attributes('aria-describedby'))!
      expect(tooltip).not.toBeNull()
      expect(tooltip.hidden).toBe(true)
      chip.element.focus()
      await flushPromises()
      expect(document.activeElement).toBe(chip.element)
      expect(tooltip.hidden).toBe(false)
      await chip.trigger('click')
      expect(chip.attributes('aria-pressed')).toBe('true')
      expect(wrapper.findAll('.token-chip.selected-token')).toHaveLength(4)
      expect(markedSource(wrapper)).toEqual(['\t<b>&', 'abc', ''])
      expect(wrapper.findAll('.selected-source-line')).toHaveLength(2)
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
      await nextTick()
      expect(tooltip.hidden).toBe(true)
      expect(chip.attributes('aria-pressed')).toBe('true')
      expect(document.activeElement).toBe(chip.element)
    } finally {
      wrapper.unmount()
    }
  })

  it('omits tooltips and descriptions for ordinary tokens without history', async () => {
    const props = fixture()
    for (const stream of props.submission.streams) {
      for (const entry of stream.tokens) entry.transformations = []
    }
    const wrapper = mount(NormalizationInspector, { props, attachTo: document.body })
    try {
      const chips = wrapper.findAll('.token-chip')
      expect(chips.length).toBeGreaterThan(0)
      for (const chip of chips) {
        expect(chip.attributes('aria-describedby')).toBeUndefined()
        expect(chip.attributes('title')).toBeUndefined()
        expect(chip.find('[aria-label="Recorded transformation history"]').exists()).toBe(false)
      }
      expect(document.querySelector('[role="tooltip"]')).toBeNull()
      const chip = wrapper.get<HTMLButtonElement>('[aria-label="Left token 7: TYPE_7"]')
      chip.element.focus()
      await flushPromises()
      expect(chip.attributes('aria-describedby')).toBeUndefined()
      await chip.trigger('click')
      expect(chip.attributes('aria-pressed')).toBe('true')
      expect(markedSource(wrapper)).toEqual(['\t<b>&', 'abc', ''])
    } finally {
      wrapper.unmount()
    }
  })

  it('groups chips by starting source line, retaining indices and neutral many-to-many associations', async () => {
    const props = fixture()
    const tokens = props.submission.streams[0].tokens
    tokens[2].region = { ...region, startLine: 3, endLine: 3 }
    props.submission.streams[0].tokens = [tokens[2], tokens[0], tokens[1], ...tokens.slice(3)]
    const wrapper = inspector(props)

    expect(
      wrapper.findAll('[data-token-side="Left"]').map((chip) => chip.attributes('data-token-index'))
    ).toEqual(['7', '2', '8', '9'])
    expect(
      wrapper
        .get('[data-source-line="1"]')
        .findAll('[data-token-side="Left"]')
        .map((chip) => chip.text())
    ).toEqual(['TYPE_7◇', 'TYPE_2◇'])
    expect(wrapper.get('[data-source-line="2"]').findAll('.token-chip')).toHaveLength(0)
    expect(wrapper.get('[data-source-line="3"] [data-token-side="Left"]').text()).toBe('−TYPE_8◇')
    expect(wrapper.find('[aria-label="Left token 10: TYPE_10"]').exists()).toBe(false)
    expect(wrapper.findAll('.token-chip.grouped-token')).toHaveLength(0)
    expect(wrapper.findAll('.token-chip.changed-token')).toHaveLength(0)

    await wrapper.get('[aria-label="Left token 2: TYPE_2"]').trigger('click')
    await flushPromises()
    expect(
      wrapper.findAll('[aria-pressed="true"]').map((chip) => chip.attributes('aria-label'))
    ).toEqual([
      'Left token 7: TYPE_7',
      'Left token 2: TYPE_2',
      'Right token 20: TYPE_20',
      'Right token 21: TYPE_21'
    ])

    await wrapper.get('[aria-label="Right token 21: TYPE_21"]').trigger('click')
    expect(wrapper.findAll('[aria-pressed="true"]')).toHaveLength(4)
  })

  it('keeps unlocated tokens reachable after all source rows, distinct from unmatched tokens', async () => {
    const props = fixture()
    props.files[0].data = Array.from({ length: 125 }, () => 'source').join('\n')
    props.submission.streams[0].tokens.push(
      ...Array.from({ length: 100 }, (_, index) =>
        token(100 + index, { ...region, startLine: index + 1, endLine: index + 1 })
      ),
      token(11, { ...region, file: 'Missing.java' }),
      token(12, { ...region, startLine: 200, endLine: 200 })
    )
    props.submission.streams = [props.submission.streams[0]]
    const wrapper = inspector(props)
    expect(wrapper.get('[aria-label="Left token 8: TYPE_8"]').classes()).not.toContain(
      'left-only-token'
    )

    expect(wrapper.findAll('[data-source-line]')).toHaveLength(126)
    expect(wrapper.get('tbody tr:last-child').attributes('data-source-line')).toBe('0')
    expect(wrapper.find('footer').exists()).toBe(false)
    expect(wrapper.get('[data-source-line="0"]').text()).toContain(
      'Submission tokens without an available source line'
    )
    expect(wrapper.get('[data-source-line="0"] [data-token-index="9"]').text()).toBe('TYPE_9◇')
    await wrapper.get('[aria-label="Left token 9: TYPE_9"]').trigger('click')
    await flushPromises()
    const unlocated = wrapper.get('[aria-label="Left token 9: TYPE_9"]')

    expect(unlocated.classes()).not.toContain('left-only-token')

    for (const index of [11, 12]) {
      await wrapper.get(`[data-source-line="0"] [data-token-index="${index}"]`).trigger('click')
      await flushPromises()
      expect(
        wrapper
          .get(`[data-source-line="0"] [data-token-index="${index}"]`)
          .attributes('aria-pressed')
      ).toBe('true')
      expect(wrapper.get('[aria-label="Source file"]').element).toHaveProperty(
        'value',
        'Example.java'
      )
      expect(wrapper.findAll('[data-source-line]')).toHaveLength(126)
      expect(wrapper.findAll('[data-selected-source]')).toHaveLength(0)
    }
  })

  it('renders safe syntax highlighting and concatenates exclusive multiline marks per source row', async () => {
    const props = fixture()
    props.files[0].data = 'class Example {\n\tString s = "<b>&<script>";\nlast'
    props.submission.streams[0].tokens[0].region = { ...region, endColumn: 20 }
    const wrapper = inspector(props)
    await wrapper.get('[aria-label="Left token 7: TYPE_7"]').trigger('click')
    expect(markedSource(wrapper)).toEqual(['lass Example {', '\tString s = "<b>&<s', ''])
    expect(wrapper.get('tbody .hljs-keyword').text()).toBe('class')
    expect(wrapper.findAll('tbody code').map((row) => row.element.textContent)).toEqual(
      props.files[0].data.split('\n')
    )
    expect(wrapper.find('tbody b, tbody script').exists()).toBe(false)
    await wrapper.get('[aria-label="Source file"]').setValue('Other.java')
    expect(wrapper.get('tbody code').element.textContent).toBe('<img src=x onerror=alert(1)>')
    expect(wrapper.find('tbody img').exists()).toBe(false)
    expect(wrapper.findAll('[data-selected-source]')).toHaveLength(0)
  })

  it('selects spans from interior source lines but excludes an end line at column one', async () => {
    const props = fixture()
    props.submission.streams[0].tokens = [
      token(7, { ...region, endLine: 3, endColumn: 1 }),
      token(2, { ...region, startLine: 3, startColumn: 1, endLine: 3, endColumn: 4 }),
      token(3, { ...region, startLine: 3, startColumn: 2, endLine: 3, endColumn: 3 })
    ]
    props.submission.streams[1].tokens = []

    const wrapper = inspector(props)
    expect(wrapper.get('[data-source-line="2"]').findAll('.token-chip')).toHaveLength(0)
    await wrapper.get('[aria-label="Inspect tokens at line 2"]').trigger('click')
    expect(markedSource(wrapper)).toEqual(['\t<b>&', 'abc<script>', ''])
    expect(wrapper.findAll('.selected-source-line')).toHaveLength(2)
    expect(wrapper.get('[data-source-line="3"] .source-cell').classes()).not.toContain(
      'selected-source-line'
    )
    await wrapper.get('[aria-label="Left token 7: TYPE_7"]').trigger('click')
    await wrapper.get('[aria-label="Inspect tokens at line 3"]').trigger('click')
    expect(markedSource(wrapper)).toEqual(['', '', 'las'])
    await wrapper.get('[aria-label="Inspect tokens at line 3"]').trigger('click')
    expect(markedSource(wrapper)).toEqual(['', '', 'a'])
    await wrapper.get('[aria-label="Inspect tokens at line 3"]').trigger('click')
    expect(markedSource(wrapper)).toEqual(['', '', 'las'])
  })

  it('compares nonbaseline streams directly, including keys absent from the baseline', async () => {
    const props = fixture()
    props.submission.streams[1].tokens = [{ ...token(20), correspondenceKey: 40 }, token(22)]
    props.submission.streams.push({
      configurationId: 'third',
      tokens: [{ ...token(31, { ...region, startColumn: 3 }), correspondenceKey: 40 }, token(32)]
    })
    const wrapper = inspector(props)
    await wrapper.get('[aria-label="Left configuration"]').setValue('normalized')
    await wrapper.get('[aria-label="Right configuration"]').setValue('third')

    expect(wrapper.get('[aria-label="Left token 20: TYPE_20"]').text()).toBe('~TYPE_20◇')
    expect(wrapper.get('[aria-label="Right token 31: TYPE_31"]').text()).toBe('~TYPE_31◇')
    expect(wrapper.get('[aria-label="Left token 22: TYPE_22"]').text()).toBe('−TYPE_22◇')
    expect(wrapper.get('[aria-label="Right token 32: TYPE_32"]').text()).toBe('+TYPE_32◇')

    await wrapper.get('[aria-label="Left token 20: TYPE_20"]').trigger('click')
    expect(
      wrapper.findAll('[aria-pressed="true"]').map((chip) => chip.attributes('aria-label'))
    ).toEqual(['Left token 20: TYPE_20', 'Right token 31: TYPE_31'])
  })

  it('keeps excluded tokens and groups with hidden counterparts neutral rather than unmatched', async () => {
    const props = fixture()
    props.submission.streams[0].tokens = [
      { ...token(7), correspondenceKey: null },
      { ...token(8), correspondenceKey: 41 },
      { ...token(9), correspondenceKey: 42 },
      { ...token(11, null), correspondenceKey: 43 },
      token(12),
      { ...token(13), correspondenceKey: null, fileEnd: true }
    ]
    props.submission.streams[1].tokens = [
      { ...token(20), correspondenceKey: null },
      { ...token(21, null), correspondenceKey: 41 },
      { ...token(22), correspondenceKey: 42 },
      { ...token(23, null), correspondenceKey: 42 },
      { ...token(24), correspondenceKey: 43 },
      token(25)
    ]
    const wrapper = inspector(props)

    expect(wrapper.findAll('.changed-token')).toHaveLength(0)
    expect(wrapper.findAll('.left-only-token').map((chip) => chip.text())).toEqual(['−TYPE_12◇'])
    expect(wrapper.findAll('.right-only-token').map((chip) => chip.text())).toEqual(['+TYPE_25◇'])
    expect(wrapper.find('[aria-label="Left token 13: TYPE_13"]').exists()).toBe(false)
    for (const label of ['Left token 7: TYPE_7', 'Left token 9: TYPE_9']) {
      await wrapper.get(`[aria-label="${label}"]`).trigger('click')
      expect(wrapper.findAll('[aria-pressed="true"]')).toHaveLength(1)
    }
  })

  it('derives many-to-many groups in stream order regardless of differing display regions', () => {
    const props = fixture()
    props.submission.streams[1].tokens[1].region = { ...region, endColumn: 5 }
    expect(useNormalizationComparison(props).correspondence.value).toEqual({
      groups: [{ leftPositions: [7, 2], rightPositions: [20, 21] }],
      unmatchedLeftPositions: [8],
      unmatchedRightPositions: [22]
    })
  })

  it('keeps configurations selectable, resets selection, and reverses groups and unmatched positions', async () => {
    const wrapper = inspector()
    const left = wrapper.get('[aria-label="Left configuration"]')
    const right = wrapper.get('[aria-label="Right configuration"]')
    expect(left.element).toHaveProperty('value', 'baseline')
    expect(right.element).toHaveProperty('value', 'normalized')
    expect(left.findAll('option').map((option) => option.element.value)).toEqual([
      'baseline',
      'normalized',
      'third',
      'unexported'
    ])
    expect(right.findAll('option').map((option) => option.element.value)).toEqual([
      'normalized',
      'third',
      'unexported'
    ])
    await wrapper.get('[aria-label="Left token 7: TYPE_7"]').trigger('click')
    await right.setValue('third')
    expect(wrapper.find('[aria-label="Selected token"]').exists()).toBe(false)
    expect(wrapper.findAll('[aria-pressed="true"]')).toHaveLength(0)
    expect(wrapper.get('[aria-label="Right token stream"]').text()).toContain(
      'Token stream unavailable'
    )
    expect(wrapper.find('[data-token-side="Right"]').exists()).toBe(false)
    expect(wrapper.findAll('.left-only-token')).toHaveLength(0)
    await left.setValue('normalized')
    await right.setValue('baseline')
    expect(right.element).toHaveProperty('value', 'baseline')
    await wrapper.get('[aria-label="Left token 20: TYPE_20"]').trigger('click')
    expect(wrapper.findAll('[aria-pressed="true"]')).toHaveLength(4)
    expect(wrapper.get('[aria-label="Right token 8: TYPE_8"]').classes()).toContain(
      'right-only-token'
    )
    await wrapper.get('[aria-label="Right token 8: TYPE_8"]').trigger('click')

    await left.setValue('baseline')
    expect(wrapper.find('[aria-label="Selected token"]').exists()).toBe(false)
  })

  it.each([false, true])(
    'reverses status prefixes independently of transformation history (%s)',
    async (withHistory) => {
      const props = fixture()
      props.submission.streams[0].tokens = [token(7), token(8), { ...token(9), type: 'SAME' }]
      props.submission.streams[1].tokens = [
        { ...token(20), correspondenceKey: 7 },
        token(22),
        { ...token(23), type: 'SAME', correspondenceKey: 9 }
      ]
      for (const stream of props.submission.streams) {
        for (const entry of stream.tokens) entry.transformations = withHistory ? ['REORDER'] : []
      }

      const wrapper = inspector(props)
      const history = withHistory ? '◇' : ''
      const expectChip = (
        side: string,
        index: number,
        type: string,
        prefix: string,
        className: string
      ) => {
        const chip = wrapper.get(`[aria-label="${side} token ${index}: ${type}"]`)
        expect(chip.text()).toBe(`${prefix}${type}${history}`)

        expect(chip.classes()).toContain(className)
        expect(chip.find('[aria-label="Recorded transformation history"]').exists()).toBe(
          withHistory
        )
      }
      for (const reversed of [false, true]) {
        if (reversed) await wrapper.get('[aria-label="Left configuration"]').setValue('normalized')
        expectChip(
          'Left',
          reversed ? 22 : 8,
          reversed ? 'TYPE_22' : 'TYPE_8',
          '−',
          'left-only-token'
        )
        expectChip(
          'Right',
          reversed ? 8 : 22,
          reversed ? 'TYPE_8' : 'TYPE_22',
          '+',
          'right-only-token'
        )
        expectChip('Left', reversed ? 20 : 7, reversed ? 'TYPE_20' : 'TYPE_7', '~', 'changed-token')
        expectChip(
          'Right',
          reversed ? 7 : 20,
          reversed ? 'TYPE_7' : 'TYPE_20',
          '~',
          'changed-token'
        )
        const unchanged = wrapper.get(`[aria-label="Left token ${reversed ? 23 : 9}: SAME"]`)
        expect(unchanged.text()).toBe(`SAME${history}`)
        expect(unchanged.classes()).not.toContain('changed-token')
      }
    }
  )

  it('keeps metadata selectable without pairs and exposes a single exported stream', async () => {
    const props = fixture()
    props.submission.streams = [props.submission.streams[0]]
    const wrapper = inspector(props)
    expect(wrapper.text()).toContain('Fewer than two configuration streams are available')
    expect(wrapper.get('[aria-label="Right token stream"]').text()).toContain(
      'Token stream unavailable'
    )
    expect(
      wrapper
        .get('[aria-label="Right configuration"]')
        .findAll('option')
        .map((option) => option.element.value)
    ).toEqual(['normalized', 'third', 'unexported'])
    await wrapper.get('[aria-label="Left token 7: TYPE_7"]').trigger('click')
    expect(wrapper.get('[aria-label="Left token 7: TYPE_7"]').attributes('aria-pressed')).toBe(
      'true'
    )
    expect(
      wrapper
        .findAll('.token-chip')
        .every(
          (chip) =>
            !chip
              .classes()
              .some((className) =>
                ['left-only-token', 'right-only-token', 'changed-token'].includes(className)
              )
        )
    ).toBe(true)
    await wrapper.get('[aria-label="Left configuration"]').setValue('third')
    expect(wrapper.find('[aria-label="Selected token"]').exists()).toBe(false)
    expect(wrapper.get('[aria-label="Left token stream"]').text()).toContain(
      'Token stream unavailable'
    )
    await wrapper.get('[aria-label="Right configuration"]').setValue('baseline')
    expect(wrapper.find('[aria-label="Right token 7: TYPE_7"]').exists()).toBe(true)
  })

  it('uses the only metadata configuration when baseline metadata is missing', async () => {
    const props = fixture()
    props.analysis.configurations = [props.analysis.configurations[1]]
    props.submission.streams = [props.submission.streams[1]]
    const wrapper = inspector(props)
    expect(wrapper.get('[aria-label="Left configuration"]').element).toHaveProperty(
      'value',
      'normalized'
    )
    expect(wrapper.get('[aria-label="Right configuration"]').text()).toContain(
      'No configuration available'
    )
    expect(wrapper.get('[aria-label="Right token stream"]').text()).toContain(
      'Token stream unavailable'
    )
    await wrapper.get('[aria-label="Left token 20: TYPE_20"]').trigger('click')
    expect(wrapper.findAll('[aria-pressed="true"]')).toHaveLength(1)
  })

  it('folds unchanged stretches by default despite provenance differences, with show and hide controls', async () => {
    const wrapper = inspector(unchangedFixture())
    expect(
      wrapper.findAll('[data-source-line]').map((row) => row.attributes('data-source-line'))
    ).toEqual(['1', '2', '11', '12'])
    expect(wrapper.findAll('tbody tr')).toHaveLength(5)
    expect(wrapper.find('footer').exists()).toBe(false)
    const fold = wrapper.get('.fold-row button')
    expect(fold.text()).toContain('Show 8 unchanged source lines (3–10)')
    expect(fold.attributes('aria-expanded')).toBe('false')
    await fold.trigger('click')
    expect(fold.text()).toContain('Hide 8 unchanged source lines (3–10)')
    expect(fold.attributes('aria-expanded')).toBe('true')
    expect(wrapper.findAll('[data-source-line]')).toHaveLength(12)
    expect(wrapper.get('[aria-label="Right token 106: SAME"]').text()).toContain('◇')
    expect(wrapper.get('[aria-label="Left token 6: SAME"]').text()).not.toContain('◇')
    expect(wrapper.findAll('tbody tr')).toHaveLength(13)
    const chip = wrapper.get('[aria-label="Right token 106: SAME"]')
    await chip.trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.fold-row')).toHaveLength(1)
    expect(fold.text()).toContain('Hide 8 unchanged source lines (3–10)')
    await chip.trigger('click')
    await flushPromises()
    expect(wrapper.find('[aria-label="Selected token"]').exists()).toBe(false)
    expect(fold.attributes('aria-expanded')).toBe('true')
    expect(wrapper.findAll('[data-source-line]')).toHaveLength(12)
    expect(wrapper.findAll('tbody tr')).toHaveLength(13)
    await fold.trigger('click')
    expect(fold.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('[data-source-line="6"]').exists()).toBe(false)
  })

  it('keeps unknown tokens and source rows with a missing stream visible', async () => {
    const props = unchangedFixture()
    props.submission.streams[0].tokens.push(token(50, null))
    props.submission.streams[1].tokens.push({ ...token(150, null), correspondenceKey: 50 })
    const wrapper = inspector(props)
    expect(wrapper.find('.fold-row').exists()).toBe(true)
    expect(wrapper.get('[data-source-line="0"]').findAll('.token-chip')).toHaveLength(2)
    await wrapper.get('[aria-label="Right configuration"]').setValue('third')
    expect(wrapper.get('[aria-label="Right token stream"]').text()).toContain(
      'Token stream unavailable'
    )
    expect(wrapper.find('.fold-row').exists()).toBe(false)
    expect(wrapper.findAll('[data-source-line]')).toHaveLength(13)
  })

  it('clears all selection effects on a repeated click but selects a different counterpart first', async () => {
    const props = fixture()
    props.submission.streams[1].tokens[0].index = 7

    const wrapper = inspector(props)
    for (const switchCounterpart of [false, true]) {
      await wrapper.get('[aria-label="Left token 7: TYPE_7"]').trigger('click')
      await flushPromises()
      expect(wrapper.findAll('.token-chip.selected-token')).toHaveLength(4)
      expect(wrapper.findAll('[data-selected-source]').length).toBeGreaterThan(0)
      expect(wrapper.findAll('.selected-source-line')).toHaveLength(2)
      if (switchCounterpart) {
        await wrapper.get('[aria-label="Right token 7: TYPE_20"]').trigger('click')
        await flushPromises()
        expect(wrapper.findAll('[aria-pressed="true"]')).toHaveLength(4)
      }
      const label = switchCounterpart ? 'Right token 7: TYPE_20' : 'Left token 7: TYPE_7'
      await wrapper.get(`[aria-label="${label}"]`).trigger('click')
      expect(wrapper.find('[aria-label="Selected token"]').exists()).toBe(false)
      expect(wrapper.findAll('[aria-pressed="true"]')).toHaveLength(0)
      expect(wrapper.findAll('.token-chip.selected-token')).toHaveLength(0)
      expect(wrapper.findAll('[data-selected-source], .selected-source-line')).toHaveLength(0)
    }
  })

  it('expands unchanged sections independently and clears selection on hide', async () => {
    const props = unchangedFixture(20)
    props.submission.streams[1].tokens[12].type = 'CHANGED'
    const wrapper = inspector(props)
    const folds = wrapper.findAll('.fold-row button')
    expect(folds.map((fold) => fold.text())).toEqual([
      expect.stringContaining('Show 8 unchanged source lines (3–10)'),
      expect.stringContaining('Show 3 unchanged source lines (16–18)')
    ])
    expect(folds.map((fold) => fold.attributes('aria-expanded'))).toEqual(['false', 'false'])
    expect(wrapper.find('[data-source-line="10"]').exists()).toBe(false)
    await folds[0].trigger('click')
    await wrapper.get('[aria-label="Right token 110: SAME"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-source-line="10"] code').element.textContent).toBe('source 10')
    expect(wrapper.get('[data-source-line="10"] [data-selected-source]').text()).toBe('ou')
    expect(wrapper.get('[aria-label="Right token 110: SAME"]').attributes('aria-pressed')).toBe(
      'true'
    )
    expect(wrapper.get('[aria-label="Source file"]').element).toHaveProperty(
      'value',
      'Example.java'
    )
    expect(wrapper.find('[data-source-line="16"]').exists()).toBe(false)
    expect(wrapper.findAll('.fold-row')).toHaveLength(2)
    expect(folds[0].text()).toContain('Hide 8 unchanged source lines (3–10)')
    expect(folds[1].text()).toContain('Show 3 unchanged source lines (16–18)')
    expect(folds.map((fold) => fold.attributes('aria-expanded'))).toEqual(['true', 'false'])
    await folds[1].trigger('click')
    expect(folds.map((fold) => fold.attributes('aria-expanded'))).toEqual(['true', 'true'])
    expect(wrapper.get('[data-source-line="16"]').exists()).toBe(true)
    expect(wrapper.get('[data-source-line="10"] [data-selected-source]').text()).toBe('ou')
    await folds[0].trigger('click')
    await flushPromises()
    expect(wrapper.find('[aria-label="Selected token"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-selected-source], .selected-source-line')).toHaveLength(0)
    expect(wrapper.find('[data-source-line="10"]').exists()).toBe(false)
    expect(wrapper.get('[data-source-line="16"]').exists()).toBe(true)
    expect(folds.map((fold) => fold.attributes('aria-expanded'))).toEqual(['false', 'true'])
    await folds[1].trigger('click')
    expect(wrapper.find('[data-source-line="16"]').exists()).toBe(false)
    expect(folds.map((fold) => fold.attributes('aria-expanded'))).toEqual(['false', 'false'])
  })

  it('renders all 125 source rows and their tokens continuously without pagination', async () => {
    const wrapper = inspector(largeFixture())
    expect(wrapper.findAll('tbody tr')).toHaveLength(125)
    expect(
      wrapper.findAll('[data-source-line]').map((row) => Number(row.attributes('data-source-line')))
    ).toEqual(Array.from({ length: 125 }, (_, index) => index + 1))
    expect(wrapper.findAll('.token-chip')).toHaveLength(3)
    expect(wrapper.get('[data-source-line="1"] [aria-label="Left token 7: TYPE_7"]').exists()).toBe(
      true
    )
    expect(
      wrapper.get('[data-source-line="121"] [aria-label="Left token 2: TYPE_2"]').exists()
    ).toBe(true)
    expect(
      wrapper.get('[data-source-line="125"] [aria-label="Left token 8: TYPE_8"]').exists()
    ).toBe(true)
    expect(wrapper.find('footer').exists()).toBe(false)
    expect(wrapper.text()).not.toMatch(/Previous page|Next page|display rows/)
    await wrapper.get('[aria-label="Left token 2: TYPE_2"]').trigger('click')
    expect(wrapper.get('[data-source-line="121"] [data-selected-source]').text()).toBe('ou')
    await wrapper.get('[aria-label="Left token 8: TYPE_8"]').trigger('click')
    expect(wrapper.get('[data-source-line="125"] [data-selected-source]').text()).toBe('ou')
    expect(wrapper.findAll('tbody tr')).toHaveLength(125)
    expect(wrapper.get('[aria-label="Left token 2: TYPE_2"]').exists()).toBe(true)
  })
})
