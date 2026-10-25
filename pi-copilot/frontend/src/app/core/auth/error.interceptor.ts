import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { NotificationService } from '../notifications/notification.service';

export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const notificationService = inject(NotificationService);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      let message = 'An unexpected error occurred.';

      if (error.status === 0) {
        message = 'Network error. Please check your connection.';
      } else if (error.status === 400) {
        message = error.error?.message || 'Invalid request.';
      } else if (error.status === 403) {
        message = 'You do not have permission to perform this action.';
      } else if (error.status === 404) {
        message = 'The requested resource was not found.';
      } else if (error.status === 409) {
        message = error.error?.message || 'A conflict occurred.';
      } else if (error.status === 422) {
        message = error.error?.message || 'Validation error.';
      } else if (error.status === 502 || error.status === 503) {
        message = error.error?.message || 'The service is temporarily unavailable.';
      } else if (error.status >= 500) {
        message = 'A server error occurred. The team has been notified.';
      }

      // Don't show error for auth endpoints
      if (!req.url.includes('/auth/')) {
        notificationService.showError(message);
      }

      return throwError(() => error);
    })
  );
};