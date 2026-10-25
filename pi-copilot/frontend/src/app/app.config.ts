import { ApplicationConfig, ErrorHandler, inject, provideAppInitializer, provideZoneChangeDetection } from '@angular/core';
import { provideRouter, withPreloading, PreloadAllModules } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAnimations } from '@angular/platform-browser/animations';
import { Store, provideStore } from '@ngrx/store';
import { provideEffects } from '@ngrx/effects';
import { provideStoreDevtools } from '@ngrx/store-devtools';
import { provideRouterStore } from '@ngrx/router-store';
import { MAT_FORM_FIELD_DEFAULT_OPTIONS } from '@angular/material/form-field';
import { MAT_SNACK_BAR_DEFAULT_OPTIONS } from '@angular/material/snack-bar';

import { routes } from './app.routes';
import { authReducer } from './store/auth/auth.reducer';
import { workspaceReducer } from './store/workspace/workspace.reducer';
import { AuthEffects } from './store/auth/auth.effects';
import { WorkspaceEffects } from './store/workspace/workspace.effects';
import { authActions } from './store/auth/auth.actions';
import { AuthService } from './core/auth/auth.service';
import { GlobalErrorHandler } from './core/error-handling/global-error-handler';
import {
  authInterceptor,
  correlationInterceptor,
  baseUrlInterceptor,
  errorInterceptor,
} from './core/auth';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes, withPreloading(PreloadAllModules)),
    provideHttpClient(
      withInterceptors([
        correlationInterceptor,
        baseUrlInterceptor,
        authInterceptor,
        errorInterceptor,
      ])
    ),
    provideAnimations(),
    provideStore({
      auth: authReducer,
      workspace: workspaceReducer,
    }),
    provideEffects([AuthEffects, WorkspaceEffects]),
    provideRouterStore(),
    provideStoreDevtools({ maxAge: 25, logOnly: false }),
    provideAppInitializer(() => {
      const session = inject(AuthService).restoreSession();
      if (session) {
        inject(Store).dispatch(authActions.sessionRestored(session));
      }
    }),
    { provide: ErrorHandler, useClass: GlobalErrorHandler },
    {
      provide: MAT_FORM_FIELD_DEFAULT_OPTIONS,
      useValue: { appearance: 'outline', floatLabel: 'auto' }
    },
    {
      provide: MAT_SNACK_BAR_DEFAULT_OPTIONS,
      useValue: { duration: 5000, horizontalPosition: 'end', verticalPosition: 'bottom' }
    },
  ],
};