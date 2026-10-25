import { ErrorHandler, Injectable, inject } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { NotificationService } from '../notifications/notification.service';

@Injectable()
export class GlobalErrorHandler implements ErrorHandler {
  private readonly notificationService = inject(NotificationService);

  handleError(error: Error): void {
    console.error('[GlobalErrorHandler]', error);

    if (error instanceof HttpErrorResponse) {
      // HTTP errors are handled by the error interceptor
      return;
    }

    // For unhandled errors, show a notification
    this.notificationService.showError('An unexpected error occurred. Please try again.');
  }
}