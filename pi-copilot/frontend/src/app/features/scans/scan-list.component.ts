import { Component, computed, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Subscription, timer } from 'rxjs';
import { switchMap } from 'rxjs/operators';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { StatusBadgeComponent } from '../../shared/components/status-badge/status-badge.component';
import { NotificationService } from '../../core/notifications/notification.service';
import {
  formatDuration, isScanActive, Scan, scanBadgeStatus, SCAN_STATUS_LABELS, SCAN_STATUS_ORDER, ScanService, ScanStatus,
} from './scan.models';

const POLL_MS = 4000;

@Component({
  selector: 'app-scan-list',
  standalone: true,
  imports: [
    CommonModule, RouterModule, MatIconModule, MatButtonModule, MatTooltipModule, MatProgressBarModule,
    PageHeaderComponent, StatusBadgeComponent,
  ],
  template: `
    <div class="pc-page">
      <app-page-header title="Scans"
        subtitle="Every discovery scan in this workspace, with its progress and review status.">
        <a mat-flat-button color="primary" routerLink="/scans/new"><mat-icon>add</mat-icon> New Scan</a>
      </app-page-header>

      <div class="filters" role="tablist" aria-label="Filter by status">
        <button type="button" class="filter" role="tab" [class.active]="filter() === null"
                [attr.aria-selected]="filter() === null" (click)="filter.set(null)">
          All <span class="count">{{ scans().length }}</span>
        </button>
        <button type="button" *ngFor="let s of statuses" class="filter" role="tab"
                [class.active]="filter() === s" [attr.aria-selected]="filter() === s" (click)="filter.set(s)">
          <span class="dot" [ngClass]="s.toLowerCase()"></span>
          {{ labels[s] }} <span class="count">{{ countOf(s) }}</span>
        </button>
      </div>

      <div class="panel">
        <mat-progress-bar *ngIf="loading()" mode="indeterminate"></mat-progress-bar>

        <div class="empty" *ngIf="!loading() && filtered().length === 0">
          <mat-icon>radar</mat-icon>
          <p *ngIf="scans().length === 0">No scans yet. Pick the databases to scan and set their execution order.</p>
          <p *ngIf="scans().length > 0">No scans are {{ labels[filter()!] | lowercase }}.</p>
          <a *ngIf="scans().length === 0" mat-stroked-button color="primary" routerLink="/scans/new">Start your first scan</a>
        </div>

        <div class="table-scroll" *ngIf="filtered().length > 0">
          <table class="scan-table">
            <thead>
              <tr>
                <th>Scan</th><th>Status</th><th class="num">Databases</th><th class="num">Tables</th>
                <th class="num">Tables with PI</th><th class="num">PI columns</th><th>Review</th>
                <th>Duration</th><th></th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let s of filtered(); trackBy: trackById" (click)="open(s)" tabindex="0"
                  (keydown.enter)="open(s)">
                <td>
                  <div class="name">{{ s.name }}</div>
                  <div class="sub">{{ s.createdAt | date:'d MMM y, HH:mm' }}</div>
                </td>
                <td>
                  <app-status-badge [label]="labels[s.status]" [status]="badge(s.status)"
                                    [tooltip]="s.errorMessage ?? undefined"></app-status-badge>
                </td>
                <td class="num">{{ s.databaseCount }}</td>
                <td class="num">{{ s.totalTablesScanned ?? 0 }}</td>
                <td class="num">{{ s.tablesWithPi ?? 0 }}</td>
                <td class="num strong">{{ s.findingsCount ?? 0 }}</td>
                <td>
                  <span class="review" [class.approved]="s.approvalState === 'APPROVED'"
                        *ngIf="s.status === 'COMPLETED'; else noReview">
                    <mat-icon>{{ s.approvalState === 'APPROVED' ? 'verified' : 'pending_actions' }}</mat-icon>
                    {{ s.approvalState === 'APPROVED' ? 'Approved' : 'Awaiting review' }}
                  </span>
                  <ng-template #noReview><span class="sub">—</span></ng-template>
                </td>
                <td class="sub">{{ duration(s) }}</td>
                <td class="actions" (click)="$event.stopPropagation()">
                  <button *ngIf="isActive(s.status)" mat-stroked-button color="warn" type="button"
                          (click)="stop(s)" [disabled]="stopping() === s.id">
                    <mat-icon>stop_circle</mat-icon> Stop
                  </button>
                  <a mat-icon-button [routerLink]="['/scans', s.id]" aria-label="Open scan" matTooltip="Open">
                    <mat-icon>chevron_right</mat-icon>
                  </a>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .filters { display: flex; flex-wrap: wrap; gap: 8px; }
    .filter {
      display: inline-flex; align-items: center; gap: 8px; height: 36px; padding: 0 14px;
      border: 1px solid var(--pc-border); border-radius: 999px; background: var(--pc-surface);
      font: inherit; font-weight: 500; color: var(--pc-text-muted); cursor: pointer;
    }
    .filter:hover { border-color: var(--pc-primary); color: var(--pc-primary); }
    .filter.active { background: var(--pc-primary-soft); border-color: var(--pc-primary); color: var(--pc-primary-600); }
    .count {
      min-width: 20px; padding: 0 6px; border-radius: 999px; background: #f3f4f6;
      font-size: 12px; font-weight: 600; text-align: center; color: var(--pc-text);
    }
    .dot { width: 8px; height: 8px; border-radius: 50%; }
    .dot.pending, .dot.running { background: var(--pc-warning); }
    .dot.completed { background: var(--pc-success); }
    .dot.cancelled { background: #9ca3af; }
    .dot.failed { background: var(--pc-danger); }

    .panel {
      background: var(--pc-surface); border: 1px solid var(--pc-border);
      border-radius: var(--pc-radius); box-shadow: var(--pc-shadow); overflow: hidden;
    }
    .table-scroll { overflow-x: auto; }
    .scan-table { width: 100%; border-collapse: collapse; min-width: 880px; }
    .scan-table th {
      padding: 12px 16px; text-align: left; background: #fafbfc; border-bottom: 1px solid var(--pc-border);
      font-size: 12px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.04em; color: var(--pc-text-muted);
    }
    .scan-table td { padding: 14px 16px; border-bottom: 1px solid var(--pc-border); vertical-align: middle; }
    .scan-table tbody tr { cursor: pointer; transition: background 0.15s; }
    .scan-table tbody tr:hover, .scan-table tbody tr:focus-visible { background: #fafaff; outline: none; }
    .scan-table tbody tr:last-child td { border-bottom: none; }
    .num { text-align: right !important; font-variant-numeric: tabular-nums; }
    .strong { font-weight: 600; }
    .name { font-weight: 600; color: var(--pc-text); }
    .sub { font-size: 12.5px; color: var(--pc-text-muted); }
    .review { display: inline-flex; align-items: center; gap: 4px; font-size: 13px; color: #b45309; }
    .review mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .review.approved { color: #047857; }
    .actions { text-align: right; white-space: nowrap; }

    .empty { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 48px 16px; text-align: center; color: var(--pc-text-muted); }
    .empty > mat-icon { font-size: 36px; width: 36px; height: 36px; color: var(--pc-primary); }
  `],
})
export class ScanListComponent implements OnInit, OnDestroy {
  private readonly scanService = inject(ScanService);
  private readonly router = inject(Router);
  private readonly notifications = inject(NotificationService);
  private pollSub?: Subscription;

  readonly labels = SCAN_STATUS_LABELS;
  readonly statuses = SCAN_STATUS_ORDER;
  readonly scans = signal<Scan[]>([]);
  readonly loading = signal(true);
  readonly filter = signal<ScanStatus | null>(null);
  readonly stopping = signal<string | null>(null);

  readonly filtered = computed(() => {
    const f = this.filter();
    return f ? this.scans().filter((s) => s.status === f) : this.scans();
  });

  ngOnInit(): void {
    this.load();
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
  }

  /** Loads now, then keeps polling only while some scan is still initiated or processing. */
  private load(): void {
    this.pollSub?.unsubscribe();
    this.pollSub = timer(0, POLL_MS).pipe(switchMap(() => this.scanService.list())).subscribe({
      next: (scans) => {
        this.scans.set(scans);
        this.loading.set(false);
        if (!scans.some((s) => isScanActive(s.status))) {
          this.pollSub?.unsubscribe();
        }
      },
      error: () => {
        this.loading.set(false);
        this.pollSub?.unsubscribe();
      },
    });
  }

  countOf(status: ScanStatus): number {
    return this.scans().filter((s) => s.status === status).length;
  }

  isActive(status: ScanStatus): boolean {
    return isScanActive(status);
  }

  badge(status: ScanStatus): string {
    return scanBadgeStatus(status);
  }

  duration(s: Scan): string {
    return formatDuration(s.startedAt, s.completedAt);
  }

  open(s: Scan): void {
    this.router.navigate(['/scans', s.id]);
  }

  stop(s: Scan): void {
    this.stopping.set(s.id);
    this.scanService.stop(s.id).subscribe({
      next: () => {
        this.stopping.set(null);
        this.notifications.showSuccess(`Scan "${s.name}" stopped`);
        this.load();
      },
      error: () => this.stopping.set(null),
    });
  }

  trackById(_: number, s: Scan): string {
    return s.id;
  }
}
