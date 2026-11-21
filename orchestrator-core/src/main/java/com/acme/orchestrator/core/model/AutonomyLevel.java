package com.acme.orchestrator.core.model;

/** How much agents may do without a human (config/autonomy.yml). Never relaxes DENY policies. */
public enum AutonomyLevel {
  /** Agents produce artifacts only; every write needs approval. */
  L0_SUGGEST,
  /** Writes inside the path jail are allowed; policy and stage approvals apply. The default. */
  L1_SUPERVISED,
  /**
   * Like L1, but design approval is skipped for low-risk changes. Publish always needs approval.
   */
  L2_TRUSTED
}
