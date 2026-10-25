import { createReducer, on } from '@ngrx/store';
import { AuthState, initialAuthState } from './auth.state';
import { authActions } from './auth.actions';

export const authReducer = createReducer(
  initialAuthState,
  on(authActions.loginRedirect, (state) => ({
    ...state,
    isLoading: true,
    loginError: null,
  })),
  on(authActions.passwordLogin, authActions.googleLogin, authActions.loginCallback, (state) => ({
    ...state,
    isLoading: true,
    loginError: null,
  })),
  on(authActions.loginSuccess, authActions.sessionRestored, (state, { tokens, user }) => ({
    ...state,
    isAuthenticated: true,
    accessToken: tokens.accessToken,
    refreshToken: tokens.refreshToken,
    idToken: tokens.idToken,
    tokenExpiry: tokens.expiresAt,
    currentUser: user,
    isLoading: false,
    loginError: null,
  })),
  on(authActions.loginFailure, (state, { error }) => ({
    ...state,
    isAuthenticated: false,
    isLoading: false,
    loginError: error,
  })),
  on(authActions.tokenRefreshSuccess, (state, { accessToken, expiry }) => ({
    ...state,
    accessToken,
    tokenExpiry: expiry,
  })),
  on(authActions.tokenRefreshFailure, (state) => ({
    ...state,
    isAuthenticated: false,
    accessToken: null,
    refreshToken: null,
    tokenExpiry: null,
  })),
  on(authActions.logoutSuccess, () => initialAuthState),
  on(authActions.sessionExpired, () => ({
    ...initialAuthState,
    loginError: 'Session expired. Please log in again.',
  }))
);