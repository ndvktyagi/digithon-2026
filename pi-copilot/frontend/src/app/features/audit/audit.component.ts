import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { HttpClient } from '@angular/common/http';
import { StatusBadgeComponent } from '../../shared/components/status-badge/status-badge.component';

@Component({
  selector: 'app-audit',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatCardModule, MatTableModule, MatPaginatorModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule,
    StatusBadgeComponent,
  ],
  template: `
    <div class="audit-page">
      <h1>Audit Trail</h1>

      <mat-card class="filters-card">
        <mat-card-content>
          <div class="filter-row">
            <mat-form-field appearance="outline">
              <mat-label>Action</mat-label>
              <mat-select [(ngModel)]="actionFilter">
                <mat-option value="">All Actions</mat-option>
                <mat-option value="SCAN_STARTED">Scan Started</mat-option>
                <mat-option value="FINDING_REVIEWED">Finding Reviewed</mat-option>
                <mat-option value="KEY_ROTATED">Key Rotated</mat-option>
                <mat-option value="MIGRATION_APPROVED">Migration Approved</mat-option>
                <mat-option value="BACKFILL_STARTED">Backfill Started</mat-option>
                <mat-option value="FEATURE_FLAG_CHANGED">Flag Changed</mat-option>
                <mat-option value="RETIREMENT_APPROVED">Retirement Approved</mat-option>
              </mat-select>
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Outcome</mat-label>
              <mat-select [(ngModel)]="outcomeFilter">
                <mat-option value="">All</mat-option>
                <mat-option value="SUCCESS">Success</mat-option>
                <mat-option value="FAILURE">Failure</mat-option>
              </mat-select>
            </mat-form-field>

            <button mat-stroked-button>
              <mat-icon>filter_list</mat-icon> Apply
            </button>
            <button mat-stroked-button>
              <mat-icon>download</mat-icon> Export
            </button>
          </div>
        </mat-card-content>
      </mat-card>

      <mat-card class="table-card">
        <mat-card-content>
          <table mat-table [dataSource]="events()">
            <ng-container matColumnDef="time">
              <th mat-header-cell *matHeaderCellDef>Time</th>
              <td mat-cell *matCellDef="let e">{{ e.eventTime | date:'medium' }}</td>
            </ng-container>
            <ng-container matColumnDef="actor">
              <th mat-header-cell *matHeaderCellDef>Actor</th>
              <td mat-cell *matCellDef="let e">{{ e.actor }} ({{ e.actorRole }})</td>
            </ng-container>
            <ng-container matColumnDef="action">
              <th mat-header-cell *matHeaderCellDef>Action</th>
              <td mat-cell *matCellDef="let e">{{ e.action }}</td>
            </ng-container>
            <ng-container matColumnDef="target">
              <th mat-header-cell *matHeaderCellDef>Target</th>
              <td mat-cell *matCellDef="let e">{{ e.targetType }}: {{ e.targetName }}</td>
            </ng-container>
            <ng-container matColumnDef="outcome">
              <th mat-header-cell *matHeaderCellDef>Outcome</th>
              <td mat-cell *matCellDef="let e">
                <app-status-badge [label]="e.outcome" [status]="e.outcome"></app-status-badge>
              </td>
            </ng-container>
            <tr mat-header-row *matHeaderRowDef="['time','actor','action','target','outcome']"></tr>
            <tr mat-row *matRowDef="let row; columns: ['time','actor','action','target','outcome']"></tr>
          </table>

          <mat-paginator [pageSize]="25" [pageSizeOptions]="[10,25,50]"></mat-paginator>
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: [`
    .audit-page { padding: 24px; }
    h1 { margin: 0 0 16px; }
    .filters-card { margin-bottom: 16px; }
    .filter-row {
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
      align-items: center;
    }
  `],
})
export class AuditComponent {
  private readonly http = inject(HttpClient);
  readonly events = signal<any[]>([]);
  actionFilter = '';
  outcomeFilter = '';

  ngOnInit(): void {
    this.http.get<any[]>('/audit-events').subscribe({
      next: (events) => this.events.set(events),
    });
  }
}