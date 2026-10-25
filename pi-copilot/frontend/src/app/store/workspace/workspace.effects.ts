import { Injectable, inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { Router } from '@angular/router';
import { of } from 'rxjs';
import { map, exhaustMap, catchError } from 'rxjs/operators';
import { workspaceActions } from './workspace.actions';
import { WorkspaceService } from '../../core/api/workspace.service';

@Injectable()
export class WorkspaceEffects {
  private readonly actions$ = inject(Actions);
  private readonly workspaceService = inject(WorkspaceService);
  private readonly router = inject(Router);

  loadWorkspaces$ = createEffect(() =>
    this.actions$.pipe(
      ofType(workspaceActions.loadWorkspaces),
      exhaustMap(() =>
        this.workspaceService.listWorkspaces().pipe(
          map((workspaces) =>
            workspaceActions.loadWorkspacesSuccess({ workspaces })
          ),
          catchError((error) =>
            of(workspaceActions.loadWorkspacesFailure({ error: error.message }))
          )
        )
      )
    )
  );
}