import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { map, tap } from 'rxjs/operators';
import { selectIsWorkspaceSelected } from '../../store/workspace/workspace.selectors';

export const workspaceGuard: CanActivateFn = () => {
  const store = inject(Store);
  const router = inject(Router);
  const workspaceId = sessionStorage.getItem('workspace_id');

  if (workspaceId) {
    return true;
  }

  return store.select(selectIsWorkspaceSelected).pipe(
    tap((selected) => {
      if (!selected) {
        router.navigate(['/workspaces']);
      }
    }),
    map((selected) => selected),
  );
};