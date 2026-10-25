import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Store } from '@ngrx/store';
import { catchError, throwError, switchMap, first } from 'rxjs';
import { AuthService } from './auth.service';
import { authActions } from '../../store/auth/auth.actions';

const REFRESH_IN_PROGRESS = 'refresh_in_progress';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const store = inject(Store);

  // Skip auth for OAuth endpoints
  if (req.url.includes('/auth/') || req.url.includes('/actuator/')) {
    return next(req);
  }

  const token = authService.getAccessToken();
  if (token) {
    req = req.clone({
      setHeaders: { Authorization: `Bearer ${token}` },
    });
  }

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401 && !req.url.includes('/auth/refresh')) {
        // Attempt token refresh
        const refreshToken = authService.getRefreshToken();
        if (refreshToken) {
          store.dispatch(authActions.tokenRefresh({ refreshToken }));
          // Retry mechanism would go here - simplified for MVP
        }
        store.dispatch(authActions.sessionExpired());
      }
      return throwError(() => error);
    })
  );
};