import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { map, tap } from 'rxjs/operators';
import { selectIsAuthenticated } from '../../store/auth/auth.selectors';
import { AuthService } from './auth.service';

export const authGuard: CanActivateFn = () => {
  const store = inject(Store);
  const router = inject(Router);
  const authService = inject(AuthService);

  // Check for existing token in storage
  const token = authService.getAccessToken();
  if (token) {
    return true;
  }

  return store.select(selectIsAuthenticated).pipe(
    tap((isAuth) => {
      if (!isAuth) {
        router.navigate(['/login']);
      }
    }),
    map((isAuth) => isAuth),
  );
};