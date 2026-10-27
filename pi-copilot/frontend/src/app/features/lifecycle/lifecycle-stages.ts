import { WorkspaceStage } from '../../core/api/workspace.service';

export interface StageDefinition {
  stage: WorkspaceStage;
  title: string;
  description: string;
  icon: string;
  /** Page where the work for this stage is done; null while the stage has no page of its own. */
  route: string | null;
  actionLabel: string;
  /** Sidebar target for this stage: its page, or its card on the Lifecycle page. */
  navRoute: string;
  navFragment?: string;
}

/** Display metadata for each lifecycle stage, in order. Mirrors backend WorkspaceStage. */
export const LIFECYCLE_STAGES: StageDefinition[] = [
  {
    stage: 'CONNECT',
    title: 'Connect',
    description: 'Connect the GitHub repository and PostgreSQL database to protect.',
    icon: 'hub',
    route: '/connectors',
    actionLabel: 'Open Connectors',
    navRoute: '/connectors',
  },
  {
    stage: 'SCAN',
    title: 'Scan',
    description: 'Scan the connected databases, in your chosen order, for personal information.',
    icon: 'radar',
    route: '/scans',
    actionLabel: 'Open Scans',
    navRoute: '/scans',
  },
  {
    stage: 'REVIEW',
    title: 'Review & Approve',
    description: 'Select the tables and columns to encrypt and approve the scan.',
    icon: 'fact_check',
    route: '/review',
    actionLabel: 'Review Scan Results',
    navRoute: '/review',
  },
  {
    stage: 'ENCRYPTION_SETUP',
    title: 'Encryption Setup',
    description: 'Choose and verify the encryption method and key provider.',
    icon: 'vpn_key',
    route: '/encryption',
    actionLabel: 'Set Up Encryption',
    navRoute: '/encryption',
  },
  {
    stage: 'IMPLEMENTATION',
    title: 'Implementation',
    description: 'Add encrypted columns to the database and push the code changes to a new branch.',
    icon: 'integration_instructions',
    route: '/implementation',
    actionLabel: 'Open Implementation',
    navRoute: '/implementation',
  },
  {
    stage: 'FINAL_REVIEW',
    title: 'Final Review',
    description: 'Review the pull request and the new database columns, then approve, reject or request changes.',
    icon: 'rate_review',
    route: '/final-review',
    actionLabel: 'Open Final Review',
    navRoute: '/final-review',
  },
  {
    stage: 'DATA_SYNC',
    title: 'Data Sync',
    description: 'Encrypt the existing personal data into the new encrypted columns and verify every value.',
    icon: 'sync',
    route: '/data-sync',
    actionLabel: 'Open Data Sync',
    navRoute: '/data-sync',
  },
  {
    stage: 'FINALIZE',
    title: 'Finalize',
    description: 'Remove the plain-text code paths from the application, then drop the plain-text columns from the database.',
    icon: 'cleaning_services',
    route: '/audit',
    actionLabel: 'View Audit Trail',
    navRoute: '/lifecycle',
    navFragment: 'FINALIZE',
  },
];

export function stageIndex(stage: string | undefined): number {
  if (stage === 'COMPLETED') return LIFECYCLE_STAGES.length;
  // A rejected workspace stopped at Final Review
  if (stage === 'REJECTED') return LIFECYCLE_STAGES.findIndex((s) => s.stage === 'FINAL_REVIEW');
  const i = LIFECYCLE_STAGES.findIndex((s) => s.stage === stage);
  return i < 0 ? 0 : i;
}

export type WorkspaceStatus = 'IN_PROGRESS' | 'COMPLETED' | 'REJECTED' | 'ARCHIVED';

/** Overall status: Completed and Rejected are both final, so neither needs further action. */
export function workspaceStatus(ws: { lifecycleStage?: string; active?: boolean }): WorkspaceStatus {
  if (ws.active === false) return 'ARCHIVED';
  if (ws.lifecycleStage === 'COMPLETED') return 'COMPLETED';
  if (ws.lifecycleStage === 'REJECTED') return 'REJECTED';
  return 'IN_PROGRESS';
}

export function stageTitle(stage: string | undefined): string {
  if (stage === 'COMPLETED') return 'Completed';
  if (stage === 'REJECTED') return 'Rejected';
  return LIFECYCLE_STAGES[stageIndex(stage)].title;
}
