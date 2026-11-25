import { describe, it, expect } from 'vitest'
import type { NormalizationAnalysis, NormalizationSubmission } from '@jplag/model'
import { NormalizationAnalysisFactory } from '../../src'

describe('NormalizationAnalysisFactory', () => {
  it('preserves manifest configurations and identifiers while normalizing archive paths', () => {
    const manifest: NormalizationAnalysis = {
      configurations: [
        { id: 'baseline', name: 'Original', normalizations: [], normalize: false },
        {
          id: 'normalized',
          name: 'Normalized',
          normalizations: ['SORT', 'SORT', 'REMOVE'],
          normalize: true
        }
      ],
      baselineConfigurationId: 'baseline',
      submissions: {
        'submission\\id': 'normalization\\submissions\\first.json',
        second: 'normalization/submissions/second.json'
      }
    }

    expect(NormalizationAnalysisFactory.getAnalysis(JSON.stringify(manifest))).toEqual({
      ...manifest,
      submissions: {
        'submission\\id': 'normalization/submissions/first.json',
        second: 'normalization/submissions/second.json'
      }
    })
  })

  it('preserves indices, opaque keys, null regions, file ends and exclusive coordinates', () => {
    const region = {
      file: 'files\\submission\\src/Main.java',
      startLine: 1,
      startColumn: 1,
      endLine: 3,
      endColumn: 8
    }
    const normalizedRegion = { ...region, file: 'files/submission/src/Main.java' }
    const submission: NormalizationSubmission = {
      streams: [
        {
          configurationId: 'baseline',
          tokens: [
            {
              index: 7,
              type: 'DECL',
              region,
              correspondenceKey: 42,
              transformations: ['SORT', 'SORT'],
              fileEnd: false
            },
            {
              index: 2,
              type: 'DECL',
              region,
              correspondenceKey: 42,
              transformations: [],
              fileEnd: false
            },
            {
              index: 9,
              type: 'FILE_END',
              region: null,
              correspondenceKey: null,
              transformations: [],
              fileEnd: true
            }
          ]
        },
        {
          configurationId: 'normalized',
          tokens: [
            {
              index: 4,
              type: 'SYNTHETIC',
              region: null,
              correspondenceKey: 42,
              transformations: ['INSERT'],
              fileEnd: false
            },
            {
              index: 8,
              type: 'FILE_END',
              region: { ...region, file: 'files/submission/Other.java' },
              correspondenceKey: null,
              transformations: [],
              fileEnd: true
            }
          ]
        }
      ]
    }

    expect(NormalizationAnalysisFactory.getSubmission(JSON.stringify(submission))).toEqual({
      streams: [
        {
          ...submission.streams[0],
          tokens: [
            { ...submission.streams[0].tokens[0], region: normalizedRegion },
            { ...submission.streams[0].tokens[1], region: normalizedRegion },
            submission.streams[0].tokens[2]
          ]
        },
        submission.streams[1]
      ]
    })
  })

  it('accepts empty manifests and streams', () => {
    const manifest: NormalizationAnalysis = {
      configurations: [],
      baselineConfigurationId: '',
      submissions: {}
    }
    const submission: NormalizationSubmission = {
      streams: [{ configurationId: 'baseline', tokens: [] }]
    }

    expect(NormalizationAnalysisFactory.getAnalysis(JSON.stringify(manifest))).toEqual(manifest)
    expect(NormalizationAnalysisFactory.getSubmission(JSON.stringify(submission))).toEqual(
      submission
    )
    expect(NormalizationAnalysisFactory.getSubmission('{"streams":[]}')).toEqual({ streams: [] })
  })
})
