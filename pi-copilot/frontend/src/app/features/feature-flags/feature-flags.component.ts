import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatExpansionModule } from '@angular/material/expansion';
import { HttpClient } from '@angular/common/http';
import { StatusBadgeComponent } from '../../shared/components/status-badge/status-badge.component';

@Component({
  selector: 'app-feature-flags',
  standalone: true,
  imports: [
    CommonModule, MatCardModule, MatSlideToggleModule, MatButtonModule,
    MatIconModule, MatListModule, MatExpansionModule, StatusBadgeComponent,
  ],
  template: `
    <div class="flags-page">
      <h1>Feature Flags</h1>
      <p class="subtitle">Control encrypted read cutover and rollback for migration fields.</p>

      <mat-card *ngFor="let flag of flags()" class="flag-card">
        <mat-card-header>
          <mat-card-title>{{ flag.flagName }}</mat-card-title>
          <app-status-badge [label]="flag.currentState" [status]="flag.currentState"></app-status-badge>
        </mat-card-header>
        <mat-card-content>
          <p>{{ flag.description }}</p>
          <div class="flag-details">
            <span><strong>Scope:</strong> {{ flag.scope }}</span>
            <span><strong>Target:</strong> {{ flag.scopeTarget || 'All' }}</span>
            <span><strong>Rollback Window:</strong> {{ flag.rollbackWindowHours }}h</span>
          </div>

          <mat-expansion-panel>
            <mat-expansion-panel-header>Readiness Gates</mat-expansion-panel-header>
            <mat-list>
              <mat-list-item *ngFor="let check of flag.readinessChecks || []">
                <mat-icon [color]="check.passed ? 'primary' : 'warn'">
                  {{ check.passed ? 'check_circle' : 'cancel' }}
                </mat-icon>
                {{ check.name }}
              </mat-list-item>
            </mat-list>
          </mat-expansion-panel>

          <div *ngIf="flag.rollbackAt" class="rollback-info">
            <mat-icon>history</mat-icon>
            Rolled back by {{ flag.rollbackBy }}: {{ flag.rollbackReason }}
          </div>
        </mat-card-content>
        <mat-card-actions>
          <button mat-raised-button color="primary"
                  *ngIf="flag.currentState === 'DISABLED' || flag.currentState === 'LEGACY_READ'">
            <mat-icon>toggle_on</mat-icon> Enable Encrypted Read
          </button>
          <button mat-stroked-button color="warn"
                  *ngIf="flag.currentState === 'ENCRYPTED_READ'">
            <mat-icon>undo</mat-icon> Rollback to Legacy
          </button>
        </mat-card-actions>
      </mat-card>

      <mat-card *ngIf="flags().length === 0">
        <mat-card-content>
          <p class="empty-text">No feature flags configured. Create a migration plan to generate feature flags.</p>
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: [`
    .flags-page { padding: 24px; }
    h1 { margin: 0 0 8px; }
    .subtitle { color: #757575; margin: 0 0 24px; }
    .flag-card { margin-bottom: 16px; }
    .flag-details {
      display: flex;
      gap: 24px;
      margin: 12px 0;
      font-size: 14px;
    }
    .rollback-info {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 12px;
      background: #fff3e0;
      border-radius: 8px;
      margin-top: 12px;
    }
    .empty-text { color: #757575; padding: 24px 0; text-align: center; }
  `],
})
export class FeatureFlagsComponent {
  readonly flags = signal<any[]>([]);
}