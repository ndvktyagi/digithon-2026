import { createActionGroup, emptyProps, props } from '@ngrx/store';
import { WorkspaceSummary } from '../../core/auth/auth-state.model';

export const workspaceActions = createActionGroup({
  source: 'Workspace',
  events: {
    'Load Workspaces': emptyProps(),
    'Load Workspaces Success': props<{ workspaces: WorkspaceSummary[] }>(),
    'Load Workspaces Failure': props<{ error: string }>(),
    'Select Workspace': props<{ workspaceId: string }>(),
    'Clear Workspace': emptyProps(),
  },
});