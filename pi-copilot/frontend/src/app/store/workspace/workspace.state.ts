import { WorkspaceSummary } from '../../core/auth/auth-state.model';

export interface WorkspaceState {
  selectedWorkspaceId: string | null;
  workspaces: WorkspaceSummary[];
  isLoading: boolean;
  error: string | null;
}

export const initialWorkspaceState: WorkspaceState = {
  selectedWorkspaceId: null,
  workspaces: [],
  isLoading: false,
  error: null,
};