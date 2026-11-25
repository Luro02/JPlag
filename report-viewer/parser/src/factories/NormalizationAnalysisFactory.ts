import type {
  NormalizationAnalysis,
  NormalizationRegion,
  NormalizationSubmission
} from '@jplag/model'
import slash from 'slash'

/**
 * Factory class for parsing normalization analysis reports.
 */
export class NormalizationAnalysisFactory {
  /**
   * Parses the normalization manifest and normalizes its submission archive paths.
   *
   * @param json the manifest JSON string
   */
  public static getAnalysis(json: string): NormalizationAnalysis {
    const analysis: NormalizationAnalysis = JSON.parse(json)
    return {
      ...analysis,
      submissions: Object.fromEntries(
        Object.entries(analysis.submissions).map(([submissionId, path]) => [
          submissionId,
          slash(path)
        ])
      )
    }
  }

  /**
   * Parses a submission payload without changing token positions or source coordinates.
   *
   * @param json the submission JSON string
   */
  public static getSubmission(json: string): NormalizationSubmission {
    const submission: NormalizationSubmission = JSON.parse(json)
    return {
      streams: submission.streams.map((stream) => ({
        ...stream,
        tokens: stream.tokens.map((token) => ({
          ...token,
          region: token.region === null ? null : this.normalizeRegion(token.region)
        }))
      }))
    }
  }

  private static normalizeRegion(region: NormalizationRegion): NormalizationRegion {
    return { ...region, file: slash(region.file) }
  }
}
