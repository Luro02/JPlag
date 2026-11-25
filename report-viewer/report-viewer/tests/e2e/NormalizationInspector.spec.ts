import type { Buffer } from 'node:buffer'
import { readFile } from 'node:fs/promises'
import { expect, test } from '@playwright/test'
import JSZip from 'jszip'
import type {
  NormalizationAnalysis,
  NormalizationSubmission,
  NormalizationToken
} from '@jplag/model'

test('inspects optional normalization streams and restores the ordinary comparison', async ({
  page
}) => {
  let reportBuffer: Buffer = await readFile(
    new URL('./assets/result_small_cluster.jplag', import.meta.url)
  )
  await page.route('**/results.jplag', async (route) => {
    await route.fulfill({ body: reportBuffer, contentType: 'application/zip' })
  })
  await page.goto('/comparison/A/C')
  const comparisonHeading = page.getByRole('heading', { name: /Comparison:\s*A\s*-\s*C/ })
  await expect(comparisonHeading).toBeVisible()
  await expect(page.getByRole('button', { name: /^Normalization:/ })).toHaveCount(0)

  const archive = await JSZip.loadAsync(reportBuffer)
  const sourceEntry = Object.values(archive.files).find(
    (entry) => !entry.dir && entry.name.startsWith('files/A/')
  )
  if (!sourceEntry) throw new Error('Fixture has no source file for submission A')
  const sourceLines = (await sourceEntry.async('string')).split(/\r?\n/)
  const lineIndex = sourceLines.findIndex((line) => line.length >= 12)
  expect(lineIndex).toBeGreaterThanOrEqual(0)
  const region = {
    file: sourceEntry.name.slice('files/'.length),
    startLine: lineIndex + 1,
    startColumn: 2,
    endLine: lineIndex + 1,
    endColumn: 8
  }
  const analysis: NormalizationAnalysis = {
    baselineConfigurationId: 'baseline',
    configurations: [
      { id: 'baseline', name: 'Baseline', normalizations: [], normalize: false },
      { id: 'normalized', name: 'Normalized', normalizations: ['REORDER'], normalize: true }
    ],
    submissions: { A: 'normalization/A.json' }
  }
  const tokens: NormalizationToken[] = [
    {
      index: 0,
      type: 'TOKEN_0',
      region,
      correspondenceKey: 0,
      transformations: [],
      fileEnd: false
    },
    {
      index: 1,
      type: 'FILE_END',
      region: null,
      correspondenceKey: null,
      transformations: [],
      fileEnd: true
    },
    {
      index: 2,
      type: 'TOKEN_2',
      region,
      correspondenceKey: 0,
      transformations: ['REORDER', 'RENAME'],
      fileEnd: false
    }
  ]
  const submission: NormalizationSubmission = {
    streams: [
      { configurationId: 'baseline', tokens },
      { configurationId: 'normalized', tokens }
    ]
  }
  archive.file('normalizationAnalysis.json', JSON.stringify(analysis))
  archive.file('normalization/A.json', JSON.stringify(submission))
  reportBuffer = await archive.generateAsync({ type: 'nodebuffer' })
  await page.reload()
  await expect(comparisonHeading).toBeVisible()
  await page.getByRole('button', { name: 'Normalization: A', exact: true }).click()

  const inspector = page.getByRole('region', { name: 'Normalization inspector', exact: true })
  await expect(inspector).toBeVisible()

  await expect(inspector.getByRole('button', { name: /FILE_END/ })).toHaveCount(0)
  await inspector.getByRole('button', { name: 'Left token 2: TOKEN_2', exact: true }).click()
  await expect(inspector.locator('button[aria-pressed="true"]')).toHaveCount(4)

  const selectedToken = inspector.getByRole('button', {
    name: 'Left token 2: TOKEN_2',
    exact: true
  })
  await expect(selectedToken).toHaveText('TOKEN_2◇')

  await expect(selectedToken).not.toHaveAttribute('title')

  const tooltipId = await selectedToken.getAttribute('aria-describedby')
  expect(tooltipId).toBeTruthy()
  const historyTooltip = page.locator(`[id="${tooltipId}"]`)
  await selectedToken.focus()
  await expect(historyTooltip).toBeVisible()
  await expect(historyTooltip).toHaveAttribute('role', 'tooltip')

  await expect(historyTooltip).toHaveText(/^\s*Transformation history\s*REORDER\s*RENAME\s*$/)

  await selectedToken.press('Escape')
  await expect(historyTooltip).toBeHidden()
  await expect(selectedToken).toBeFocused()
  await expect(selectedToken).toHaveAttribute('aria-pressed', 'true')

  await expect(inspector.getByRole('combobox', { name: 'Source file', exact: true })).toHaveValue(
    region.file
  )
  const selectedSource = inspector.locator('[data-selected-source]')
  await expect(selectedSource.first()).toBeVisible()
  expect(
    await selectedSource.evaluateAll((marks) => marks.map((mark) => mark.textContent).join(''))
  ).toBe(sourceLines[lineIndex].slice(1, 7))
  await expect(selectedSource.first()).toBeInViewport()
  const counterpart = inspector.getByRole('button', { name: 'Right token 2: TOKEN_2', exact: true })
  await counterpart.click()
  await expect(inspector.locator('button[aria-pressed="true"]')).toHaveCount(4)
  await expect(selectedSource.first()).toBeVisible()
  await counterpart.click()
  await expect(inspector.locator('button[aria-pressed="true"]')).toHaveCount(0)
  await expect(selectedSource).toHaveCount(0)

  await page.getByRole('button', { name: 'Matches', exact: true }).click()
  await expect(inspector).toHaveCount(0)
  await expect(comparisonHeading).toBeVisible()
  await expect(page.getByRole('button', { name: 'Normalization: A', exact: true })).toBeVisible()
})
