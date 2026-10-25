import { Injectable, inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';

export interface NotificationOptions {
  duration?: number;
  action?: string;
  persistent?: boolean;
}

@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly snackBar = inject(MatSnackBar);

  showSuccess(message: string, options?: NotificationOptions): void {
    this.snackBar.open(message, options?.action || 'Dismiss', {
      duration: options?.persistent ? 0 : (options?.duration || 5000),
      panelClass: ['snackbar-success'],
    });
  }

  showError(message: string, options?: NotificationOptions): void {
    this.snackBar.open(message, options?.action || 'Dismiss', {
      duration: options?.persistent ? 0 : (options?.duration || 8000),
      panelClass: ['snackbar-error'],
    });
  }

  showWarning(message: string, options?: NotificationOptions): void {
    this.snackBar.open(message, options?.action || 'Dismiss', {
      duration: options?.duration || 6000,
      panelClass: ['snackbar-warning'],
    });
  }
}