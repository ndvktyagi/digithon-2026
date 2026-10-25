import { createFeatureSelector, createSelector } from '@ngrx/store';
import { WorkspaceState } from './workspace.state';

export const selectWorkspaceState = createFeatureSelector<WorkspaceState>('workspace');

export const selectSelectedWorkspaceId = createSelector(
  selectWorkspaceState,
  (state) => state.selectedWorkspaceId
);

export const selectWorkspaces = createSelector(
  selectWorkspaceState,
  (state) => state.workspaces
);

export const selectWorkspaceError = createSelector(
  selectWorkspaceState,
  (state) => state.error
);

export const selectIsWorkspaceSelected = createSelector(
  selectSelectedWorkspaceId,
  (id) => id !== null
);