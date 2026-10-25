import { Component, inject, signal, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatDividerModule } from '@angular/material/divider';
import { HttpClient } from '@angular/common/http';
import { interval, Subscription } from 'rxjs';
import { switchMap } from 'rxjs/operators';
import { StatusBadgeComponent } from '../../shared/components/status-badge/status-badge.component';

@Component({
  selector: 'app-backfill-monitor',
  standalone: true,
  imports: [
    CommonModule, MatCardModule, MatProgressBarModule, MatButtonModule,
    MatIconModule, MatTableModule, MatDividerModule, StatusBadgeComponent,
  ],
  template: `
    <div class="backfill-page">
      <h1>Backfill Monitor</h1>

      <mat-card>
        <mat-card-header>
          <mat-card-title>Backfill Job</mat-card-title>
          <app-status-badge [label]="job()?.status || 'PENDING'" [status]="job()?.status || 'PENDING'"></app-status-badge>
        </mat-card-header>
        <mat-card-content>
          <mat-progress-bar mode="determinate" [value]="progressPercent()"></mat-progress-bar>
          <p class="progress-text">{{ formattedProgress() }}</p>

          <div class="stats-grid">
            <div class="stat"><strong>Total Rows:</strong> {{ job()?.totalRows || 0 }}</div>
            <div class="stat"><strong>Processed:</strong> {{ job()?.processedRows || 0 }}</div>
            <div class="stat"><strong>Failed:</strong> {{ job()?.failedRows || 0 }}</div>
            <div class="stat"><strong>Batch Size:</strong> {{ job()?.batchSize || 1000 }}</div>
          </div>

          <mat-divider></mat-divider>

          <h3>Batch Details</h3>
          <table mat-table [dataSource]="batches()">
            <ng-container matColumnDef="batch">
              <th mat-header-cell *matHeaderCellDef>Batch #</th>
              <td mat-cell *matCellDef="let b">{{ b.batchNumber }}</td>
            </ng-container>
            <ng-container matColumnDef="rows">
              <th mat-header-cell *matHeaderCellDef>Rows</th>
              <td mat-cell *matCellDef="let b">{{ b.processedCount }}/{{ b.rowCount }}</td>
            </ng-container>
            <ng-container matColumnDef="errors">
              <th mat-header-cell *matHeaderCellDef>Errors</th>
              <td mat-cell *matCellDef="let b">{{ b.errorCount }}</td>
            </ng-container>
            <ng-container matColumnDef="status">
              <th mat-header-cell *matHeaderCellDef>Status</th>
              <td mat-cell *matCellDef="let b">
                <app-status-badge [label]="b.status" [status]="b.status"></app-status-badge>
              </td>
            </ng-container>
            <tr mat-header-row *matHeaderRowDef="['batch','rows','errors','status']"></tr>
            <tr mat-row *matRowDef="let row; columns: ['batch','rows','errors','status']"></tr>
          </table>
        </mat-card-content>
        <mat-card-actions>
          <button mat-stroked-button *ngIf="job()?.status === 'RUNNING'" (click)="action('pause')">
            <mat-icon>pause</mat-icon> Pause
          </button>
          <button mat-stroked-button color="warn" *ngIf="job()?.status !== 'STOPPED'" (click)="action('stop')">
            <mat-icon>stop</mat-icon> Stop
          </button>
        </mat-card-actions>
      </mat-card>
    </div>
  `,
  styles: [`
    .backfill-page { padding: 24px; }
    h1 { margin: 0 0 16px; }
    .progress-text { text-align: center; color: #757575; margin: 8px 0 16px; }
    .stats-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
      gap: 16px;
      margin: 16px 0;
    }
    h3 { margin: 16px 0 8px; }
  `],
})
export class BackfillMonitorComponent implements OnDestroy {
  private readonly http = inject(HttpClient);

  readonly job = signal<any>(null);
  readonly batches = signal<any[]>([]);
  readonly progressPercent = signal(0);
  private pollSub?: Subscription;

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
  }

  formattedProgress(): string {
    const j = this.job();
    if (!j) return '0%';
    if (j.totalRows === 0) return '0%';
    return `${Math.round((j.processedRows / j.totalRows) * 100)}%`;
  }

  action(action: string): void {
    this.http.post(`/backfills/job-id/${action}`, {}).subscribe();
  }
}