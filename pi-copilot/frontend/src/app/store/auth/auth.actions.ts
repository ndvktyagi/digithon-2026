import { createActionGroup, emptyProps, props } from '@ngrx/store';
import { CurrentUser, TokenSet } from '../../core/auth/auth-state.model';

export const authActions = createActionGroup({
  source: 'Auth',
  events: {
    'Login Redirect': emptyProps(),
    'Login Callback': props<{ code: string; state: string }>(),
    'Password Login': props<{ username: string; password: string }>(),
    'Google Login': props<{ credential: string }>(),
    'Session Restored': props<{ tokens: TokenSet; user: CurrentUser }>(),
    'Login Success': props<{ tokens: TokenSet; user: CurrentUser }>(),
    'Login Failure': props<{ error: string }>(),
    'Token Refresh': props<{ refreshToken: string }>(),
    'Token Refresh Success': props<{ accessToken: string; expiry: number }>(),
    'Token Refresh Failure': emptyProps(),
    'Logout': emptyProps(),
    'Logout Success': emptyProps(),
    'Session Expired': emptyProps(),
  },
});
