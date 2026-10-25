import { Component, input, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

@Component({
  selector: 'app-loading-overlay',
  standalone: true,
  imports: [CommonModule, MatProgressSpinnerModule],
  template: `
    <div class="loading-overlay" *ngIf="isLoading()">
      <mat-spinner diameter="48" strokeWidth="3"></mat-spinner>
      <p *ngIf="message()">{{ message() }}</p>
    </div>
  `,
  styles: [`
    .loading-overlay {
      position: absolute;
      top: 0;
      left: 0;
      right: 0;
      bottom: 0;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      background: rgba(255, 255, 255, 0.85);
      z-index: 100;
      border-radius: 8px;
    }
    p { margin-top: 16px; color: #616161; font-size: 14px; }
  `],
})
export class LoadingOverlayComponent {
  readonly isLoading = input.required<boolean>();
  readonly message = input<string>();
}