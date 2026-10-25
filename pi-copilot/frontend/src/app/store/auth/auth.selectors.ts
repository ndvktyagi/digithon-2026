import { createFeatureSelector, createSelector } from '@ngrx/store';
import { AuthState } from './auth.state';
import { UserRole } from '../../core/auth/auth-state.model';

export const selectAuthState = createFeatureSelector<AuthState>('auth');

export const selectIsAuthenticated = createSelector(
  selectAuthState,
  (state) => state.isAuthenticated
);

export const selectCurrentUser = createSelector(
  selectAuthState,
  (state) => state.currentUser
);

export const selectUserRoles = createSelector(
  selectCurrentUser,
  (user) => user?.roles ?? []
);

export const selectHasRole = (role: UserRole) =>
  createSelector(selectUserRoles, (roles) => roles.includes(role));

export const selectIsLoading = createSelector(
  selectAuthState,
  (state) => state.isLoading
);

export const selectLoginError = createSelector(
  selectAuthState,
  (state) => state.loginError
);

export const selectAccessToken = createSelector(
  selectAuthState,
  (state) => state.accessToken
);

export const selectRefreshToken = createSelector(
  selectAuthState,
  (state) => state.refreshToken
);
