/**
 * Manifest of normalization configurations and report-relative submission archive paths.
 */
export interface NormalizationAnalysis {
  configurations: NormalizationConfiguration[]
  baselineConfigurationId: string
  submissions: Record<string, string>
}

/**
 * Configuration used to produce a normalization token stream.
 */
export interface NormalizationConfiguration {
  id: string
  name: string
  normalizations: string[]
  normalize: boolean
}

/**
 * Token streams for a single submission, sharing correspondence keys across configurations.
 */
export interface NormalizationSubmission {
  streams: NormalizationStream[]
}

/**
 * Tokens produced by a normalization configuration, in stream order.
 */
export interface NormalizationStream {
  configurationId: string
  tokens: NormalizationToken[]
}

/**
 * Token with its original index and optional source location.
 */
export interface NormalizationToken {
  index: number
  type: string
  region: NormalizationRegion | null
  /**
   * Tokens with the same key correspond across configurations of this submission.
   * Keys are opaque and cannot be compared across submissions. Repeated keys form
   * many-to-many groups; null excludes a token from correspondence, including file ends.
   */
  correspondenceKey: number | null
  transformations: string[]
  fileEnd: boolean
}

/**
 * Report-relative source location with one-based coordinates and an exclusive end.
 */
export interface NormalizationRegion {
  file: string
  startLine: number
  startColumn: number
  endLine: number
  endColumn: number
}

/**
 * Correspondence groups and unmatched token positions derived from two configuration streams.
 */
export interface NormalizationCorrespondence {
  groups: NormalizationGroup[]
  unmatchedLeftPositions: number[]
  unmatchedRightPositions: number[]
}

/**
 * Original token indices in two streams that share a correspondence key, in stream order.
 */
export interface NormalizationGroup {
  leftPositions: number[]
  rightPositions: number[]
}
