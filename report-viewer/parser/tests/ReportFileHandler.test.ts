// @vitest-environment jsdom
import { describe, it, expect } from 'vitest'
import jszip from 'jszip'
import { ReportFileHandler } from '../src'

describe('ReportFileHandler normalization reports', () => {
  it('keeps normalization JSON as ordinary files alongside submission source files', async () => {
    const zip = new jszip()
    zip.file('normalizationAnalysis.json', '{"configurations":[]}')
    zip.file('normalization/first.json', '{"streams":[]}')
    zip.file('normalization\\second.json', '{"streams":[]}')
    zip.file('files/first/src/Main.java', 'class Main {}')
    const archive = await zip.generateAsync({ type: 'blob' })

    const result = await new ReportFileHandler().extractContent(archive)

    expect(result.files).toHaveLength(3)
    expect(result.files).toEqual(
      expect.arrayContaining([
        { fileName: 'normalizationAnalysis.json', data: '{"configurations":[]}' },
        { fileName: 'normalization/first.json', data: '{"streams":[]}' },
        { fileName: 'normalization/second.json', data: '{"streams":[]}' }
      ])
    )
    expect(result.submissionFiles).toEqual([
      { submissionId: 'first', fileName: 'first/src/Main.java', data: 'class Main {}' }
    ])
  })
})
