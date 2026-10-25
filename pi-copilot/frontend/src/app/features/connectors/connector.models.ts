export type ConnectorKind = 'github' | 'postgresql';

export interface GithubConnector {
  id: string;
  projectId: string;
  projectName: string;
  repositoryUrl: string;
  owner: string;
  name: string;
  branch: string;
  apiBaseUrl: string;
  includePaths: string[];
  verified: boolean;
  status: string;
  createdAt: string;
}

export interface PostgresConnector {
  id: string;
  projectId: string;
  projectName: string;
  host: string;
  port: number;
  databaseName: string;
  sslMode: string;
  username: string;
  schemaScope: string | null;
  verified: boolean;
  status: string;
  createdAt: string;
}

export const CONNECTOR_TYPES: Record<ConnectorKind, { label: string; icon: string; apiPath: string }> = {
  github: { label: 'GitHub Repository', icon: 'code', apiPath: 'github' },
  postgresql: { label: 'PostgreSQL Database', icon: 'storage', apiPath: 'database' },
};

export function connectorApiBase(kind: ConnectorKind): string {
  return `/workspaces/${sessionStorage.getItem('workspace_id')}/${CONNECTOR_TYPES[kind].apiPath}`;
}
