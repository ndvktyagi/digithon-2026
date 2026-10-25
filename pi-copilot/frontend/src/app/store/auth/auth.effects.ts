import { Injectable, inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { Store } from '@ngrx/store';
import { Router } from '@angular/router';
import { of, from, throwError } from 'rxjs';
import { map, exhaustMap, catchError, tap, withLatestFrom } from 'rxjs/operators';
import { authActions } from './auth.actions';
import { AuthService } from '../../core/auth/auth.service';
import { selectRefreshToken } from './auth.selectors';
import { HttpErrorResponse } from '@angular/common/http';

@Injectable()
export class AuthEffects {
  private readonly actions$ = inject(Actions);
  private readonly authService = inject(AuthService);
  private readonly store = inject(Store);
  private readonly router = inject(Router);

  loginRedirect$ = createEffect(() =>
    this.actions$.pipe(
      ofType(authActions.loginRedirect),
      tap(() => this.authService.startLogin()),
    ),
    { dispatch: false }
  );

  loginCallback$ = createEffect(() =>
    this.actions$.pipe(
      ofType(authActions.loginCallback),
      exhaustMap(({ code, state }) =>
        this.authService.exchangeCode(code, state).pipe(
          map(({ tokens, user }) =>
            authActions.loginSuccess({ tokens, user })
          ),
          catchError((error: HttpErrorResponse) =>
            of(authActions.loginFailure({ error: error.message }))
          )
        )
      )
    )
  );

  passwordLogin$ = createEffect(() =>
    this.actions$.pipe(
      ofType(authActions.passwordLogin),
      exhaustMap(({ username, password }) =>
        this.authService.loginWithPassword(username, password).pipe(
          map(({ tokens, user }) => authActions.loginSuccess({ tokens, user })),
          catchError((error: Error) => of(authActions.loginFailure({ error: error.message })))
        )
      )
    )
  );

  googleLogin$ = createEffect(() =>
    this.actions$.pipe(
      ofType(authActions.googleLogin),
      exhaustMap(({ credential }) =>
        this.authService.loginWithGoogle(credential).pipe(
          map(({ tokens, user }) => authActions.loginSuccess({ tokens, user })),
          catchError((error: Error) => of(authActions.loginFailure({ error: error.message })))
        )
      )
    )
  );

  loginSuccess$ = createEffect(() =>
    this.actions$.pipe(
      ofType(authActions.loginSuccess),
      tap(({ tokens }) => {
        this.authService.setTokens(tokens);
        this.router.navigate(['/select-workspace']);
      })
    ),
    { dispatch: false }
  );

  loginFailure$ = createEffect(() =>
    this.actions$.pipe(
      ofType(authActions.loginFailure),
      tap(() => {
        if (this.router.url.startsWith('/login/callback')) {
          this.router.navigate(['/login']);
        }
      })
    ),
    { dispatch: false }
  );

  tokenRefresh$ = createEffect(() =>
    this.actions$.pipe(
      ofType(authActions.tokenRefresh),
      withLatestFrom(this.store.select(selectRefreshToken)),
      exhaustMap(([_, refreshToken]) => {
        if (!refreshToken) {
          return of(authActions.tokenRefreshFailure());
        }
        return this.authService.refreshToken(refreshToken).pipe(
          map(({ accessToken, expiresAt }) =>
            authActions.tokenRefreshSuccess({ accessToken, expiry: expiresAt })
          ),
          catchError(() => of(authActions.tokenRefreshFailure()))
        );
      })
    )
  );

  sessionExpired$ = createEffect(() =>
    this.actions$.pipe(
      ofType(authActions.sessionExpired),
      tap(() => {
        this.authService.clearSession();
        this.router.navigate(['/login']);
      })
    ),
    { dispatch: false }
  );

  logout$ = createEffect(() =>
    this.actions$.pipe(
      ofType(authActions.logout),
      tap(() => {
        this.authService.clearSession();
        this.router.navigate(['/login']);
      }),
      map(() => authActions.logoutSuccess())
    )
  );
}