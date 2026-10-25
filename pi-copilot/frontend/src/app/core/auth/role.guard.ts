import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { map, tap } from 'rxjs/operators';
import { selectUserRoles } from '../../store/auth/auth.selectors';
import { UserRole } from './auth-state.model';

export const roleGuard: CanActivateFn = (route) => {
  const store = inject(Store);
  const router = inject(Router);
  const requiredRoles: UserRole[] = route.data['roles'] ?? [];

  if (requiredRoles.length === 0) {
    return true;
  }

  return store.select(selectUserRoles).pipe(
    map((userRoles) => requiredRoles.some((r) => userRoles.includes(r))),
    tap((hasRole) => {
      if (!hasRole) {
        router.navigate(['/dashboard']);
      }
    }),
  );
};