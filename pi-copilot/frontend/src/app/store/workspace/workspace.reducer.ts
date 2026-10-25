import { createReducer, on } from '@ngrx/store';
import { WorkspaceState, initialWorkspaceState } from './workspace.state';
import { workspaceActions } from './workspace.actions';

export const workspaceReducer = createReducer(
  initialWorkspaceState,
  on(workspaceActions.loadWorkspaces, (state) => ({
    ...state,
    isLoading: true,
    error: null,
  })),
  on(workspaceActions.loadWorkspacesSuccess, (state, { workspaces }) => ({
    ...state,
    workspaces,
    isLoading: false,
  })),
  on(workspaceActions.loadWorkspacesFailure, (state, { error }) => ({
    ...state,
    error,
    isLoading: false,
  })),
  on(workspaceActions.selectWorkspace, (state, { workspaceId }) => ({
    ...state,
    selectedWorkspaceId: workspaceId,
  })),
  on(workspaceActions.clearWorkspace, (state) => ({
    ...state,
    selectedWorkspaceId: null,
  }))
);