import { CurrentUser } from '../../core/auth/auth-state.model';

export interface AuthState {
  isAuthenticated: boolean;
  accessToken: string | null;
  refreshToken: string | null;
  idToken: string | null;
  tokenExpiry: number | null;
  currentUser: CurrentUser | null;
  loginError: string | null;
  isLoading: boolean;
}

export const initialAuthState: AuthState = {
  isAuthenticated: false,
  accessToken: null,
  refreshToken: null,
  idToken: null,
  tokenExpiry: null,
  currentUser: null,
  loginError: null,
  isLoading: false,
};