import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { HttpClient } from '@angular/common/http';
import { NotificationService } from '../../core/notifications/notification.service';
import { StatusBadgeComponent } from '../../shared/components/status-badge/status-badge.component';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';

interface AuditEvent {
  id: string;
  eventTime: string;
  actor: string;
  actorRole?: string;
  action: string;
  targetType: string;
  targetName?: string;
  outcome: string;
  severity?: string;
  eventMetadata?: string;
}

interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
}

const COLUMNS = ['time', 'actor', 'action', 'target', 'outcome'];
/** Most events an export fetches in one request. */
const EXPORT_LIMIT = 2000;

@Component({
  selector: 'app-audit',
  standalone: true,
  imports: [
    PageHeaderComponent, CommonModule, FormsModule, MatCardModule, MatTableModule, MatPaginatorModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule, MatTooltipModule,
    StatusBadgeComponent,
  ],
  template: `
    <div class="pc-page audit-page">
      <app-page-header title="Audit Trail"
        subtitle="Every security-relevant action, who did it and when."></app-page-header>

      <mat-card class="filters-card">
        <mat-card-content>
          <div class="filter-row">
            <mat-form-field appearance="outline">
              <mat-label>Action</mat-label>
              <mat-select [(ngModel)]="actionFilter" (selectionChange)="apply()">
                <mat-option value="">All Actions</mat-option>
                <mat-option *ngFor="let a of actions()" [value]="a">{{ label(a) }}</mat-option>
              </mat-select>
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Outcome</mat-label>
              <mat-select [(ngModel)]="outcomeFilter" (selectionChange)="apply()">
                <mat-option value="">All</mat-option>
                <mat-option value="SUCCESS">Success</mat-option>
                <mat-option value="FAILURE">Failure</mat-option>
              </mat-select>
            </mat-form-field>

            <button mat-stroked-button type="button" (click)="apply()">
              <mat-icon>refresh</mat-icon> Refresh
            </button>
            <button mat-stroked-button type="button" (click)="export()" [disabled]="exporting() || !total()">
              <mat-icon>download</mat-icon> Export CSV
            </button>
          </div>
        </mat-card-content>
      </mat-card>

      <mat-card class="table-card">
        <mat-card-content>
          <table mat-table [dataSource]="events()">
            <ng-container matColumnDef="time">
              <th mat-header-cell *matHeaderCellDef>Time</th>
              <td mat-cell *matCellDef="let e" class="nowrap">{{ e.eventTime | date:'d MMM y, HH:mm:ss' }}</td>
            </ng-container>
            <ng-container matColumnDef="actor">
              <th mat-header-cell *matHeaderCellDef>Actor</th>
              <td mat-cell *matCellDef="let e">{{ actorName(e.actor) }}<span class="muted" *ngIf="e.actorRole"> ({{ e.actorRole }})</span></td>
            </ng-container>
            <ng-container matColumnDef="action">
              <th mat-header-cell *matHeaderCellDef>Action</th>
              <td mat-cell *matCellDef="let e">
                <span class="action" [matTooltip]="details(e)" [matTooltipDisabled]="!e.eventMetadata">{{ label(e.action) }}</span>
              </td>
            </ng-container>
            <ng-container matColumnDef="target">
              <th mat-header-cell *matHeaderCellDef>Target</th>
              <td mat-cell *matCellDef="let e">
                <span class="muted">{{ label(e.targetType) }}</span>
                <div class="target-name" *ngIf="e.targetName">{{ e.targetName }}</div>
              </td>
            </ng-container>
            <ng-container matColumnDef="outcome">
              <th mat-header-cell *matHeaderCellDef>Outcome</th>
              <td mat-cell *matCellDef="let e">
                <app-status-badge [label]="e.outcome" [status]="e.outcome === 'FAILURE' ? 'failed' : e.outcome"></app-status-badge>
              </td>
            </ng-container>
            <tr mat-header-row *matHeaderRowDef="columns"></tr>
            <tr mat-row *matRowDef="let row; columns: columns"></tr>
          </table>

          <p class="empty" *ngIf="!loading() && !events().length">
            {{ error() || 'No audit events yet. Actions such as connecting sources, scans, approvals and encryption changes appear here.' }}
          </p>

          <mat-paginator [length]="total()" [pageIndex]="pageIndex" [pageSize]="pageSize"
                         [pageSizeOptions]="[10, 25, 50, 100]" (page)="onPage($event)"></mat-paginator>
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: [`
    .filters-card .filter-row { padding-top: 4px; }
    .filter-row { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; }
    .table-card table { width: 100%; }
    .nowrap { white-space: nowrap; }
    .muted { color: var(--pc-text-muted); font-size: 12.5px; }
    .target-name { word-break: break-word; }
    .action { font-weight: 500; }
    .empty { margin: 24px 0 8px; text-align: center; color: var(--pc-text-muted); }
  `],
})
export class AuditComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly notification = inject(NotificationService);

  readonly columns = COLUMNS;
  readonly events = signal<AuditEvent[]>([]);
  readonly actions = signal<string[]>([]);
  readonly total = signal(0);
  readonly loading = signal(false);
  readonly exporting = signal(false);
  readonly error = signal<string | null>(null);
  actionFilter = '';
  outcomeFilter = '';
  pageIndex = 0;
  pageSize = 25;

  private get base(): string {
    return `/workspaces/${sessionStorage.getItem('workspace_id')}/audit-events`;
  }

  ngOnInit(): void {
    this.load();
    this.http.get<string[]>(`${this.base}/actions`).subscribe({ next: (a) => this.actions.set(a) });
  }

  apply(): void {
    this.pageIndex = 0;
    this.load();
  }

  onPage(e: PageEvent): void {
    this.pageIndex = e.pageIndex;
    this.pageSize = e.pageSize;
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.search(this.pageIndex, this.pageSize).subscribe({
      next: (page) => {
        this.events.set(page.content);
        this.total.set(page.totalElements);
        this.error.set(null);
        this.loading.set(false);
      },
      error: (err) => {
        this.events.set([]);
        this.total.set(0);
        this.error.set('Could not load the audit trail: ' + (err.error?.message ?? err.message));
        this.loading.set(false);
      },
    });
  }

  /** CSV of every event matching the filters (up to EXPORT_LIMIT), newest first. */
  export(): void {
    this.exporting.set(true);
    this.search(0, EXPORT_LIMIT).subscribe({
      next: (page) => {
        const rows = [['Time', 'Actor', 'Role', 'Action', 'Target type', 'Target', 'Outcome', 'Severity', 'Details']]
          .concat(page.content.map((e) => [e.eventTime, e.actor, e.actorRole ?? '', e.action, e.targetType,
            e.targetName ?? '', e.outcome, e.severity ?? '', e.eventMetadata ?? '']));
        const csv = rows.map((r) => r.map((v) => `"${String(v).replace(/"/g, '""')}"`).join(',')).join('\r\n');
        const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
        const a = document.createElement('a');
        a.href = url;
        a.download = `audit-trail-${new Date().toISOString().slice(0, 10)}.csv`;
        a.click();
        URL.revokeObjectURL(url);
        this.exporting.set(false);
        if (page.totalElements > EXPORT_LIMIT) {
          this.notification.showSuccess(`Exported the newest ${EXPORT_LIMIT} of ${page.totalElements} events`);
        }
      },
      error: (err) => {
        this.exporting.set(false);
        this.notification.showError('Export failed: ' + (err.error?.message ?? err.message));
      },
    });
  }

  /** SCAN_APPROVED -> Scan Approved. */
  label(value?: string): string {
    if (!value) return '';
    return value.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase());
  }

  actorName(actor: string): string {
    return actor?.replace(/^local:/, '') ?? '';
  }

  details(e: AuditEvent): string {
    if (!e.eventMetadata) return '';
    try {
      return Object.entries(JSON.parse(e.eventMetadata)).map(([k, v]) => `${k}: ${v}`).join(' · ');
    } catch {
      return e.eventMetadata;
    }
  }

  private search(page: number, size: number) {
    const params: Record<string, string | number> = { page, size };
    if (this.actionFilter) params['action'] = this.actionFilter;
    if (this.outcomeFilter) params['outcome'] = this.outcomeFilter;
    return this.http.get<Page<AuditEvent>>(`${this.base}/search`, { params });
  }
}
