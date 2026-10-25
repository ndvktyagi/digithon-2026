import { Component, computed, inject, Injectable, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { Router, RouterModule } from '@angular/router';
import { Observable, Subscription, timer } from 'rxjs';
import { switchMap } from 'rxjs/operators';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { StatusBadgeComponent } from '../../shared/components/status-badge/status-badge.component';
import { NotificationService } from '../../core/notifications/notification.service';
import { formatDuration, Scan, SCAN_STATUS_LABELS, scanBadgeStatus, ScanStatus } from '../scans/scan.models';

type IntervalUnit = 'MINUTES' | 'HOURS' | 'DAYS' | 'WEEKS' | 'MONTHS';

interface Schedule {
  enabled: boolean;
  intervalValue: number;
  intervalUnit: IntervalUnit;
  valuePatternScan: boolean;
  nextRunAt: string | null;
  lastRunAt: string | null;
  lastScanId: string | null;
  workspaceFinalized: boolean;
}

interface MonitoringRun {
  scanId: string;
  name: string;
  status: ScanStatus;
  startedAt: string | null;
  completedAt: string | null;
  databaseCount: number;
  tablesScanned: number | null;
  findingsCount: number | null;
  newPiColumns: number | null;
  errorMessage: string | null;
}

const POLL_MS = 10000;
const MIN_MINUTES = 5;

@Injectable({ providedIn: 'root' })
export class MonitoringService {
  private readonly http = inject(HttpClient);

  private get base(): string {
    return `/workspaces/${sessionStorage.getItem('workspace_id')}/monitoring`;
  }

  get(): Observable<Schedule> {
    return this.http.get<Schedule>(this.base);
  }

  save(request: Pick<Schedule, 'enabled' | 'intervalValue' | 'intervalUnit' | 'valuePatternScan'>): Observable<Schedule> {
    return this.http.put<Schedule>(this.base, request);
  }

  runNow(): Observable<Scan> {
    return this.http.post<Scan>(`${this.base}/run`, {});
  }

  runs(): Observable<MonitoringRun[]> {
    return this.http.get<MonitoringRun[]>(`${this.base}/runs`);
  }
}

@Component({
  selector: 'app-monitoring',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterModule, MatIconModule, MatButtonModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatSlideToggleModule, MatProgressBarModule, PageHeaderComponent, StatusBadgeComponent,
  ],
  template: `
    <div class="pc-page">
      <app-page-header title="Monitoring"
        subtitle="Re-scan this workspace's databases on a schedule and catch new personal data after protection.">
        <button mat-stroked-button color="primary" type="button" (click)="runNow()"
                [disabled]="running() || busy() || !loaded()">
          <mat-icon>play_arrow</mat-icon> Run now
        </button>
      </app-page-header>

      <mat-progress-bar *ngIf="!loaded()" mode="indeterminate"></mat-progress-bar>

      <div class="banner info" *ngIf="loaded() && !finalized()">
        <mat-icon>info</mat-icon>
        <span>Monitoring is meant for after the workspace is finalized. You can set it up now; it uses every verified database connector.</span>
      </div>

      <div class="layout" *ngIf="loaded()">
        <!-- Schedule -->
        <section class="panel">
          <div class="panel-head">
            <h2>Schedule</h2>
            <mat-slide-toggle [(ngModel)]="enabled">{{ enabled ? 'On' : 'Off' }}</mat-slide-toggle>
          </div>

          <div class="every" [class.disabled]="!enabled">
            <span class="every-label">Run every</span>
            <mat-form-field appearance="outline" class="num" subscriptSizing="dynamic">
              <input matInput type="number" [min]="unit === 'MINUTES' ? minMinutes : 1" max="1000"
                     [(ngModel)]="intervalValue" [disabled]="!enabled" aria-label="Interval" />
            </mat-form-field>
            <mat-form-field appearance="outline" class="unit" subscriptSizing="dynamic">
              <mat-select [(ngModel)]="unit" [disabled]="!enabled" aria-label="Interval unit">
                <mat-option *ngFor="let u of units" [value]="u.value">{{ u.label }}</mat-option>
              </mat-select>
            </mat-form-field>
          </div>

          <div class="presets">
            <span class="pc-muted small">Quick picks:</span>
            <button type="button" class="preset" *ngFor="let p of presets" [disabled]="!enabled"
                    (click)="intervalValue = p.value; unit = p.unit">{{ p.label }}</button>
          </div>

          <p class="error" *ngIf="enabled && invalidReason()">{{ invalidReason() }}</p>

          <mat-slide-toggle [(ngModel)]="valuePatternScan" class="toggle">
            Sample values to catch PI in generically named columns
          </mat-slide-toggle>

          <div class="actions">
            <button mat-flat-button color="primary" type="button" (click)="save()"
                    [disabled]="busy() || (enabled && !!invalidReason()) || !dirty()">
              <mat-icon>save</mat-icon> Save schedule
            </button>
          </div>
        </section>

        <!-- Status -->
        <section class="panel status">
          <h2>Status</h2>
          <div class="stat">
            <span class="stat-label">State</span>
            <span class="stat-value">
              <span class="state" [class.on]="schedule()?.enabled">{{ schedule()?.enabled ? 'Scheduled' : 'Off' }}</span>
            </span>
          </div>
          <div class="stat" *ngIf="schedule()?.enabled">
            <span class="stat-label">Frequency</span>
            <span class="stat-value">Every {{ describe(schedule()!.intervalValue, schedule()!.intervalUnit) }}</span>
          </div>
          <div class="stat">
            <span class="stat-label">Next run</span>
            <span class="stat-value">
              <ng-container *ngIf="schedule()?.nextRunAt; else none">
                {{ schedule()!.nextRunAt | date:'d MMM y, HH:mm' }} <span class="pc-muted">({{ relative(schedule()!.nextRunAt!) }})</span>
              </ng-container>
            </span>
          </div>
          <div class="stat">
            <span class="stat-label">Last run</span>
            <span class="stat-value">
              <ng-container *ngIf="schedule()?.lastRunAt; else none">{{ schedule()!.lastRunAt | date:'d MMM y, HH:mm' }}</ng-container>
            </span>
          </div>
          <div class="stat">
            <span class="stat-label">New PI in last run</span>
            <span class="stat-value" [class.alert]="(runs()[0]?.newPiColumns ?? 0) > 0">
              {{ runs()[0]?.newPiColumns ?? '—' }}
            </span>
          </div>
          <ng-template #none><span class="pc-muted">—</span></ng-template>
        </section>
      </div>

      <!-- History -->
      <section class="panel" *ngIf="loaded()">
        <div class="panel-head"><h2>Run history</h2></div>
        <div class="empty" *ngIf="runs().length === 0">
          <mat-icon>monitor_heart</mat-icon>
          <p>No monitoring runs yet. Turn on the schedule or press <strong>Run now</strong>.</p>
        </div>
        <div class="table-scroll" *ngIf="runs().length">
          <table class="runs">
            <thead>
              <tr>
                <th>Run</th><th>Status</th><th class="num">Databases</th><th class="num">Tables</th>
                <th class="num">PI columns</th><th class="num">New PI</th><th>Duration</th><th></th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let r of runs()" (click)="open(r)" tabindex="0" (keydown.enter)="open(r)">
                <td>
                  <div class="name">{{ r.startedAt | date:'d MMM y, HH:mm' }}</div>
                  <div class="sub">{{ r.name }}</div>
                </td>
                <td><app-status-badge [label]="labels[r.status]" [status]="badge(r.status)"
                                      [tooltip]="r.errorMessage ?? undefined"></app-status-badge></td>
                <td class="num">{{ r.databaseCount }}</td>
                <td class="num">{{ r.tablesScanned ?? 0 }}</td>
                <td class="num">{{ r.findingsCount ?? 0 }}</td>
                <td class="num">
                  <span *ngIf="r.newPiColumns === null" class="pc-muted">—</span>
                  <span *ngIf="r.newPiColumns === 0" class="ok"><mat-icon>check_circle</mat-icon> 0</span>
                  <span *ngIf="r.newPiColumns" class="new"><mat-icon>warning</mat-icon> {{ r.newPiColumns }}</span>
                </td>
                <td class="sub">{{ duration(r) }}</td>
                <td class="go"><mat-icon>chevron_right</mat-icon></td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="hint" *ngIf="runs().length">
          <strong>New PI</strong> counts PI columns a run found that were never approved for encryption in this workspace.
          Open a run to review them.
        </p>
      </section>
    </div>
  `,
  styles: [`
    .small { font-size: 13px; }
    .banner { display: flex; align-items: center; gap: 12px; padding: 14px 18px; border-radius: var(--pc-radius); font-size: 14px; }
    .banner.info { background: var(--pc-primary-soft); color: var(--pc-primary-600); }
    .layout { display: grid; grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr); gap: 20px; align-items: start; }
    .panel {
      padding: 20px; min-width: 0;
      background: var(--pc-surface); border: 1px solid var(--pc-border);
      border-radius: var(--pc-radius); box-shadow: var(--pc-shadow);
    }
    .panel h2 { margin: 0; }
    .panel-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; }

    .every { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
    .every.disabled { opacity: 0.55; }
    .every-label { font-weight: 500; }
    .num { width: 110px; }
    .unit { width: 160px; }
    .presets { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; margin-top: 12px; }
    .preset {
      height: 28px; padding: 0 10px; border: 1px solid var(--pc-border); border-radius: 999px;
      background: #fff; font: inherit; font-size: 12.5px; color: var(--pc-text-muted); cursor: pointer;
    }
    .preset:hover:not(:disabled) { border-color: var(--pc-primary); color: var(--pc-primary); }
    .preset:disabled { cursor: not-allowed; opacity: 0.5; }
    .error { margin: 8px 0 0; color: #b91c1c; font-size: 13px; }
    .toggle { display: block; margin-top: 18px; }
    .actions { margin-top: 20px; }

    .status { display: flex; flex-direction: column; gap: 14px; }
    .stat { display: flex; justify-content: space-between; gap: 12px; padding-bottom: 12px; border-bottom: 1px solid var(--pc-border); }
    .stat:last-of-type { border-bottom: none; padding-bottom: 0; }
    .stat-label { color: var(--pc-text-muted); font-size: 13px; }
    .stat-value { font-weight: 600; text-align: right; }
    .stat-value.alert { color: #b45309; }
    .state { padding: 2px 10px; border-radius: 999px; background: #f3f4f6; color: #4b5563; font-size: 12.5px; }
    .state.on { background: var(--pc-success-soft); color: #047857; }

    .table-scroll { overflow-x: auto; }
    .runs { width: 100%; border-collapse: collapse; min-width: 760px; }
    .runs th {
      padding: 10px 14px; text-align: left; background: #fafbfc; border-bottom: 1px solid var(--pc-border);
      font-size: 12px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.04em; color: var(--pc-text-muted);
    }
    .runs td { padding: 12px 14px; border-bottom: 1px solid var(--pc-border); }
    .runs tbody tr { cursor: pointer; }
    .runs tbody tr:hover, .runs tbody tr:focus-visible { background: #fafaff; outline: none; }
    .num { text-align: right !important; font-variant-numeric: tabular-nums; }
    .name { font-weight: 600; }
    .sub { font-size: 12.5px; color: var(--pc-text-muted); }
    .ok, .new { display: inline-flex; align-items: center; gap: 4px; font-weight: 600; }
    .ok { color: #047857; }
    .new { color: #b45309; }
    .ok mat-icon, .new mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .go { color: var(--pc-text-subtle); text-align: right; }
    .hint { margin: 12px 0 0; font-size: 12.5px; color: var(--pc-text-muted); }

    .empty { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 32px 8px; text-align: center; color: var(--pc-text-muted); }
    .empty > mat-icon { font-size: 32px; width: 32px; height: 32px; color: var(--pc-primary); }

    @media (max-width: 960px) { .layout { grid-template-columns: 1fr; } }
  `],
})
export class MonitoringComponent implements OnInit, OnDestroy {
  private readonly monitoring = inject(MonitoringService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private pollSub?: Subscription;

  readonly labels = SCAN_STATUS_LABELS;
  readonly minMinutes = MIN_MINUTES;
  readonly units: { value: IntervalUnit; label: string }[] = [
    { value: 'MINUTES', label: 'Minutes' }, { value: 'HOURS', label: 'Hours' }, { value: 'DAYS', label: 'Days' },
    { value: 'WEEKS', label: 'Weeks' }, { value: 'MONTHS', label: 'Months' },
  ];
  readonly presets: { label: string; value: number; unit: IntervalUnit }[] = [
    { label: '10 minutes', value: 10, unit: 'MINUTES' }, { label: 'Hourly', value: 1, unit: 'HOURS' },
    { label: 'Daily', value: 1, unit: 'DAYS' }, { label: 'Weekly', value: 1, unit: 'WEEKS' },
    { label: 'Monthly', value: 1, unit: 'MONTHS' },
  ];

  readonly schedule = signal<Schedule | null>(null);
  readonly runs = signal<MonitoringRun[]>([]);
  readonly loaded = signal(false);
  readonly busy = signal(false);
  readonly finalized = computed(() => !!this.schedule()?.workspaceFinalized);
  readonly running = computed(() => this.runs().some((r) => r.status === 'RUNNING' || r.status === 'PENDING'));

  // Form state
  enabled = false;
  intervalValue = 1;
  unit: IntervalUnit = 'WEEKS';
  valuePatternScan = false;

  ngOnInit(): void {
    this.monitoring.get().subscribe({
      next: (s) => {
        this.applySchedule(s);
        this.loaded.set(true);
      },
      error: () => this.loaded.set(true),
    });
    // Poll so scheduled runs show up without a refresh
    this.pollSub = timer(0, POLL_MS).pipe(switchMap(() => this.monitoring.runs())).subscribe({
      next: (runs) => this.runs.set(runs),
      error: () => this.pollSub?.unsubscribe(),
    });
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
  }

  private applySchedule(s: Schedule): void {
    this.schedule.set(s);
    this.enabled = s.enabled;
    this.intervalValue = s.intervalValue;
    this.unit = s.intervalUnit;
    this.valuePatternScan = s.valuePatternScan;
  }

  invalidReason(): string | null {
    const v = Number(this.intervalValue);
    if (!Number.isInteger(v) || v < 1 || v > 1000) return 'Enter a whole number between 1 and 1000.';
    if (this.unit === 'MINUTES' && v < MIN_MINUTES) return `The shortest interval is ${MIN_MINUTES} minutes.`;
    return null;
  }

  dirty(): boolean {
    const s = this.schedule();
    return !s || s.enabled !== this.enabled || s.intervalValue !== Number(this.intervalValue)
      || s.intervalUnit !== this.unit || s.valuePatternScan !== this.valuePatternScan;
  }

  save(): void {
    this.busy.set(true);
    this.monitoring.save({
      enabled: this.enabled,
      intervalValue: Number(this.intervalValue),
      intervalUnit: this.unit,
      valuePatternScan: this.valuePatternScan,
    }).subscribe({
      next: (s) => {
        this.busy.set(false);
        this.applySchedule(s);
        this.notifications.showSuccess(s.enabled
          ? `Monitoring scheduled every ${this.describe(s.intervalValue, s.intervalUnit)}`
          : 'Monitoring turned off');
      },
      error: () => this.busy.set(false),
    });
  }

  runNow(): void {
    this.busy.set(true);
    this.monitoring.runNow().subscribe({
      next: (scan) => {
        this.busy.set(false);
        this.notifications.showSuccess('Monitoring scan started');
        this.router.navigate(['/scans', scan.id]);
      },
      error: () => this.busy.set(false),
    });
  }

  open(r: MonitoringRun): void {
    this.router.navigate(['/scans', r.scanId]);
  }

  describe(value: number, unit: IntervalUnit): string {
    const name = unit.toLowerCase();
    return value === 1 ? name.slice(0, -1) : `${value} ${name}`;
  }

  relative(iso: string): string {
    const diff = new Date(iso).getTime() - Date.now();
    if (diff <= 0) return 'due now';
    const m = Math.round(diff / 60000);
    if (m < 60) return `in ${m} min`;
    const h = Math.round(m / 60);
    return h < 48 ? `in ${h} h` : `in ${Math.round(h / 24)} days`;
  }

  badge(status: ScanStatus): string {
    return scanBadgeStatus(status);
  }

  duration(r: MonitoringRun): string {
    return formatDuration(r.startedAt, r.completedAt);
  }
}
