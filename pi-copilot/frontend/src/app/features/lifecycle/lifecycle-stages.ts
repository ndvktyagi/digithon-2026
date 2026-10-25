import { WorkspaceStage } from '../../core/api/workspace.service';

export interface StageDefinition {
  stage: WorkspaceStage;
  title: string;
  description: string;
  icon: string;
  /** Page where the work for this stage is done. */
  route: string;
  actionLabel: string;
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
  },
  {
    stage: 'SCAN',
    title: 'Scan',
    description: 'Scan the connected databases, in your chosen order, for personal information.',
    icon: 'radar',
    route: '/scans',
    actionLabel: 'Open Scans',
  },
  {
    stage: 'REVIEW',
    title: 'Review & Approve',
    description: 'Select the tables and columns to encrypt and approve the scan.',
    icon: 'fact_check',
    route: '/scans',
    actionLabel: 'Review Scan Results',
  },
  {
    stage: 'ENCRYPTION_SETUP',
    title: 'Encryption Setup',
    description: 'Choose and verify the encryption method and key provider.',
    icon: 'vpn_key',
    route: '/encryption',
    actionLabel: 'Set Up Encryption',
  },
  {
    stage: 'DATA_SYNC',
    title: 'Data Sync',
    description: 'Backfill existing rows into encrypted columns and verify them.',
    icon: 'sync',
    route: '/backfill',
    actionLabel: 'Open Backfill',
  },
  {
    stage: 'ENABLE_ENCRYPTION',
    title: 'Enable Encryption',
    description: 'Switch the application to read encrypted data via feature flags.',
    icon: 'lock',
    route: '/feature-flags',
    actionLabel: 'Open Feature Flags',
  },
  {
    stage: 'FINALIZE',
    title: 'Finalize',
    description: 'Delete duplicate code paths and the plain-text columns in the database.',
    icon: 'cleaning_services',
    route: '/audit',
    actionLabel: 'View Audit Trail',
  },
];

export function stageIndex(stage: string | undefined): number {
  if (stage === 'COMPLETED') return LIFECYCLE_STAGES.length;
  const i = LIFECYCLE_STAGES.findIndex((s) => s.stage === stage);
  return i < 0 ? 0 : i;
}

export function stageTitle(stage: string | undefined): string {
  if (stage === 'COMPLETED') return 'Completed';
  return LIFECYCLE_STAGES[stageIndex(stage)].title;
}
