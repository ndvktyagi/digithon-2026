import { DestroyRef, Injectable, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { Store } from '@ngrx/store';
import { Subject, interval } from 'rxjs';
import { debounceTime, filter, tap } from 'rxjs/operators';
import { workspaceActions } from '../../store/workspace/workspace.actions';

/** How often the stage is re-read while the tab is visible, for stages that advance in the background. */
const POLL_MS = 20_000;

/**
 * Keeps the workspace lifecycle stage (sidebar ticks, stage chip) current: reloads it after any
 * action that changes workspace data, and periodically for stages that advance in the background
 * (a scan or data sync finishing). Requests in quick succession cause a single reload.
 */
@Injectable({ providedIn: 'root' })
export class WorkspaceRefreshService {
  private readonly store = inject(Store);
  private readonly requests = new Subject<void>();

  constructor() {
    const destroyRef = inject(DestroyRef);
    this.requests.pipe(
      debounceTime(400),
      filter(() => !!sessionStorage.getItem('workspace_id')),
      takeUntilDestroyed(destroyRef),
    ).subscribe(() => this.store.dispatch(workspaceActions.loadWorkspaces()));

    interval(POLL_MS).pipe(
      filter(() => document.visibilityState === 'visible'),
      takeUntilDestroyed(destroyRef),
    ).subscribe(() => this.refresh());
  }

  refresh(): void {
    this.requests.next();
  }
}

/** Matches changes to a workspace's data, e.g. POST /workspaces/{id}/scans/{scanId}/approve. */
const WORKSPACE_CHANGE = /\/workspaces\/[^/]+\/.+/;

export const workspaceRefreshInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.method === 'GET' || !WORKSPACE_CHANGE.test(req.url)) {
    return next(req);
  }
  const refresher = inject(WorkspaceRefreshService);
  return next(req).pipe(
    tap((event) => {
      if (event instanceof HttpResponse) refresher.refresh();
    }),
  );
};
