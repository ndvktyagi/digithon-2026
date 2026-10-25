import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterModule } from '@angular/router';
import { Store } from '@ngrx/store';
import { map } from 'rxjs/operators';
import { selectCurrentUser } from '../../store/auth/auth.selectors';
import { StatusBadgeComponent } from '../../shared/components/status-badge/status-badge.component';
import { ConfidenceBarComponent } from '../../shared/components/confidence-bar/confidence-bar.component';
import { LIFECYCLE_STAGES } from '../lifecycle/lifecycle-stages';

interface Finding {
  id: string;
  tableName: string;
  columnName: string;
  candidateCategory: string;
  confidence: string;
  status: string;
}

interface KpiCard {
  title: string;
  value: number;
  hint: string;
  icon: string;
  tone: 'violet' | 'blue' | 'amber' | 'green';
  route: string;
}

interface Slice {
  label: string;
  value: number;
  color: string;
  dash: string;
  offset: number;
}

const CATEGORIES = [
  'DIRECT_IDENTIFIER', 'INDIRECT_IDENTIFIER', 'SENSITIVE_PERSONAL', 'CONTACT',
  'FINANCIAL', 'LOCATION', 'HEALTH', 'CREDENTIAL',
];

// Validated categorical order (violet, sky, amber) — see dataviz palette check
const CONFIDENCE_COLORS: Record<string, string> = { HIGH: '#5b3df5', MEDIUM: '#0ea5e9', LOW: '#f59e0b' };

const DONUT_RADIUS = 70;
const DONUT_CIRCUMFERENCE = 2 * Math.PI * DONUT_RADIUS;

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    CommonModule, MatIconModule, MatButtonModule, MatTooltipModule, RouterModule,
    StatusBadgeComponent, ConfidenceBarComponent,
  ],
  template: `
    <div class="pc-page">
      <section class="welcome">
        <div class="welcome-text">
          <span class="eyebrow"><mat-icon>verified_user</mat-icon> Personal Identity Security Pilot</span>
          <h1>Welcome back{{ (firstName$ | async) ? ', ' + (firstName$ | async) : '' }}</h1>
          <p>Here's what's happening with your personal information inventory today.</p>
          <div class="welcome-actions">
            <a mat-flat-button class="hero-primary" routerLink="/scans/new"><mat-icon>add</mat-icon> New Scan</a>
            <a mat-stroked-button class="hero-secondary" routerLink="/inventory"><mat-icon>inventory_2</mat-icon> View Inventory</a>
          </div>
        </div>
        <img class="welcome-art" src="brand/pi-banner.jpg" alt="Personal Identity Security Pilot" />
      </section>

      <!-- KPI row -->
      <section class="kpis">
        <a *ngFor="let k of kpis()" class="kpi" [routerLink]="k.route">
          <div class="kpi-head">
            <span class="kpi-icon" [ngClass]="k.tone"><mat-icon>{{ k.icon }}</mat-icon></span>
            <span class="kpi-title">{{ k.title }}</span>
          </div>
          <div class="kpi-value">{{ k.value | number }}</div>
          <div class="kpi-hint">{{ k.hint }}</div>
        </a>
      </section>

      <!-- Charts row -->
      <section class="row row-charts">
        <div class="panel">
          <div class="panel-head">
            <div>
              <h2>Findings by Category</h2>
              <p class="pc-muted">Personal-information columns discovered per category</p>
            </div>
            <a class="link" routerLink="/inventory">Details <mat-icon>arrow_forward</mat-icon></a>
          </div>
          <div class="bars" *ngIf="total() > 0; else noData">
            <div class="bar-row" *ngFor="let c of categoryCounts()"
                 [matTooltip]="c.label + ': ' + c.value + ' findings'" matTooltipPosition="above">
              <span class="bar-label">{{ c.label }}</span>
              <span class="bar-track">
                <span class="bar-fill" [style.width.%]="c.pct"></span>
              </span>
              <span class="bar-value">{{ c.value }}</span>
            </div>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <div>
              <h2>Confidence</h2>
              <p class="pc-muted">Detection confidence split</p>
            </div>
          </div>
          <div class="donut-wrap" *ngIf="total() > 0; else noData">
            <svg viewBox="0 0 180 180" class="donut" role="img" aria-label="Findings by confidence">
              <circle cx="90" cy="90" r="70" class="donut-track"></circle>
              <circle *ngFor="let s of slices()" cx="90" cy="90" r="70" class="donut-seg"
                      [attr.stroke]="s.color" [attr.stroke-dasharray]="s.dash"
                      [attr.stroke-dashoffset]="s.offset">
                <title>{{ s.label }}: {{ s.value }}</title>
              </circle>
            </svg>
            <div class="donut-center">
              <div class="donut-total">{{ total() | number }}</div>
              <div class="pc-muted">findings</div>
            </div>
          </div>
          <ul class="legend" *ngIf="total() > 0">
            <li *ngFor="let s of slices()">
              <span class="swatch" [style.background]="s.color"></span>
              <span class="legend-label">{{ s.label }}</span>
              <span class="legend-value">{{ s.value }} · {{ s.value / total() | percent:'1.0-0' }}</span>
            </li>
          </ul>
        </div>
      </section>

      <!-- Table + workflow row -->
      <section class="row row-bottom">
        <div class="panel table-panel">
          <div class="panel-head">
            <h2>Recent Findings</h2>
            <a class="link" routerLink="/inventory">View All <mat-icon>arrow_forward</mat-icon></a>
          </div>
          <div class="table-scroll" *ngIf="recent().length; else noData">
            <table class="simple-table">
              <thead>
                <tr><th>Column</th><th>Category</th><th>Confidence</th><th>Status</th></tr>
              </thead>
              <tbody>
                <tr *ngFor="let f of recent()" [routerLink]="['/findings', f.id]">
                  <td>
                    <div class="cell-main">
                      <span class="cell-icon"><mat-icon>table_chart</mat-icon></span>
                      <div>
                        <div class="cell-title">{{ f.columnName }}</div>
                        <div class="cell-sub">{{ f.tableName }}</div>
                      </div>
                    </div>
                  </td>
                  <td>{{ formatLabel(f.candidateCategory) }}</td>
                  <td><app-confidence-bar [confidence]="f.confidence"></app-confidence-bar></td>
                  <td><app-status-badge [label]="f.status" [status]="f.status"></app-status-badge></td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <div>
              <h2>Protection Workflow</h2>
              <p class="pc-muted">From discovery to encrypted reads</p>
            </div>
            <a class="link" routerLink="/lifecycle">Lifecycle <mat-icon>arrow_forward</mat-icon></a>
          </div>
          <ol class="steps">
            <li *ngFor="let s of workflow; let i = index">
              <a [routerLink]="s.route">
                <span class="step-icon"><mat-icon>{{ s.icon }}</mat-icon></span>
                <span class="step-text">
                  <span class="step-title">{{ i + 1 }}. {{ s.title }}</span>
                  <span class="step-sub">{{ s.description }}</span>
                </span>
                <mat-icon class="step-go">chevron_right</mat-icon>
              </a>
            </li>
          </ol>
        </div>
      </section>
    </div>

    <ng-template #noData>
      <div class="empty">
        <mat-icon>insights</mat-icon>
        <p>No findings yet. Connect a repository and database, then run a scan.</p>
        <a mat-stroked-button color="primary" routerLink="/connectors">Set Up Connectors</a>
      </div>
    </ng-template>
  `,
  styles: [`
    .welcome {
      display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.1fr); align-items: center; gap: 24px;
      padding: 28px; overflow: hidden;
      border-radius: var(--pc-radius);
      /* navy tones sampled from the brand banner so the artwork blends in */
      background:
        radial-gradient(circle at 75% 50%, rgba(34, 211, 238, 0.16), transparent 60%),
        linear-gradient(135deg, #0a2350 0%, #04122b 70%);
      color: #fff;
      box-shadow: var(--pc-shadow);
    }
    .eyebrow {
      display: inline-flex; align-items: center; gap: 6px; margin-bottom: 10px;
      font-size: 12px; font-weight: 600; letter-spacing: 0.08em; text-transform: uppercase; color: #67e8f9;
    }
    .eyebrow mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .welcome h1 { color: #fff; font-size: 26px; }
    .welcome p { margin-top: 6px; color: rgba(255, 255, 255, 0.75); }
    .welcome-actions { display: flex; gap: 10px; flex-wrap: wrap; margin-top: 20px; }
    .hero-primary { background: #22d3ee !important; color: #04122b !important; }
    .hero-secondary { color: #fff !important; border-color: rgba(255, 255, 255, 0.35) !important; }
    .welcome-art {
      display: block; width: 100%; height: auto; aspect-ratio: 1408 / 768;
      border-radius: var(--pc-radius-sm);
      border: 1px solid rgba(103, 232, 249, 0.25);
      box-shadow: 0 0 32px rgba(34, 211, 238, 0.2);
    }

    .kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: 20px; }
    .kpi {
      display: block; padding: 20px; text-decoration: none; color: inherit;
      background: var(--pc-surface); border: 1px solid var(--pc-border);
      border-radius: var(--pc-radius); box-shadow: var(--pc-shadow);
      transition: box-shadow 0.2s, transform 0.2s;
    }
    .kpi:hover { box-shadow: var(--pc-shadow-hover); transform: translateY(-2px); }
    .kpi-head { display: flex; align-items: center; gap: 10px; }
    .kpi-icon { display: grid; place-items: center; width: 30px; height: 30px; border-radius: 50%; color: #fff; }
    .kpi-icon mat-icon { font-size: 17px; width: 17px; height: 17px; }
    .kpi-icon.violet { background: #5b3df5; }
    .kpi-icon.blue { background: #0ea5e9; }
    .kpi-icon.amber { background: #f59e0b; }
    .kpi-icon.green { background: #10b981; }
    .kpi-title { color: var(--pc-text-muted); font-weight: 500; }
    .kpi-value { margin-top: 14px; font-size: 28px; font-weight: 700; letter-spacing: -0.02em; }
    .kpi-hint { margin-top: 6px; color: var(--pc-text-muted); font-size: 12.5px; }

    .row { display: grid; gap: 20px; }
    .row-charts { grid-template-columns: minmax(0, 2fr) minmax(0, 1fr); }
    .row-bottom { grid-template-columns: minmax(0, 2fr) minmax(0, 1fr); }

    .panel {
      padding: 20px; min-width: 0;
      background: var(--pc-surface); border: 1px solid var(--pc-border);
      border-radius: var(--pc-radius); box-shadow: var(--pc-shadow);
    }
    .panel-head {
      display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 20px;
    }
    .panel-head p { margin-top: 2px; font-size: 13px; }
    .link {
      display: inline-flex; align-items: center; gap: 4px; white-space: nowrap;
      font-weight: 600; text-decoration: none; color: var(--pc-primary);
    }
    .link mat-icon { font-size: 18px; width: 18px; height: 18px; }

    /* Horizontal bar chart */
    .bars { display: flex; flex-direction: column; gap: 6px; }
    .bar-row {
      display: grid; grid-template-columns: 150px 1fr 44px; align-items: center; gap: 12px;
      padding: 6px 8px; border-radius: 8px;
    }
    .bar-row:hover { background: #f8f7ff; }
    .bar-label { color: var(--pc-text-muted); font-size: 13px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .bar-track { height: 10px; border-radius: 999px; background: #f1f2f6; overflow: hidden; }
    .bar-fill { display: block; height: 100%; border-radius: 999px; background: var(--pc-primary); transition: width 0.4s ease; }
    .bar-value { text-align: right; font-weight: 600; font-variant-numeric: tabular-nums; }

    /* Donut */
    .donut-wrap { position: relative; width: 200px; max-width: 100%; margin: 0 auto; }
    .donut { display: block; width: 100%; transform: rotate(-90deg); }
    .donut-track { fill: none; stroke: #f1f2f6; stroke-width: 22; }
    .donut-seg { fill: none; stroke-width: 22; transition: stroke-width 0.15s; }
    .donut-seg:hover { stroke-width: 26; }
    .donut-center {
      position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center;
      font-size: 12px; pointer-events: none;
    }
    .donut-total { font-size: 24px; font-weight: 700; color: var(--pc-text); }
    .legend { list-style: none; margin-top: 20px; display: flex; flex-direction: column; gap: 10px; }
    .legend li { display: flex; align-items: center; gap: 10px; font-size: 13px; }
    .swatch { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
    .legend-label { flex: 1; color: var(--pc-text-muted); }
    .legend-value { font-weight: 600; font-variant-numeric: tabular-nums; }

    /* Table */
    .table-panel { padding-bottom: 8px; }
    .table-scroll { overflow-x: auto; margin: 0 -20px; }
    .simple-table { width: 100%; border-collapse: collapse; min-width: 560px; }
    .simple-table th {
      padding: 10px 20px; text-align: left; background: #fafbfc;
      font-size: 11px; font-weight: 600; letter-spacing: 0.06em; text-transform: uppercase;
      color: var(--pc-text-muted); border-top: 1px solid var(--pc-border); border-bottom: 1px solid var(--pc-border);
    }
    .simple-table td { padding: 12px 20px; border-bottom: 1px solid var(--pc-border); }
    .simple-table tr:last-child td { border-bottom: none; }
    .simple-table tbody tr { cursor: pointer; }
    .simple-table tbody tr:hover { background: #fafaff; }
    .cell-main { display: flex; align-items: center; gap: 12px; }
    .cell-icon {
      display: grid; place-items: center; width: 38px; height: 38px; border-radius: 10px;
      background: #f3f4f6; color: var(--pc-text-muted);
    }
    .cell-icon mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .cell-title { font-weight: 600; }
    .cell-sub { font-size: 12px; color: var(--pc-text-muted); }

    /* Workflow */
    .steps { list-style: none; display: flex; flex-direction: column; gap: 8px; }
    .steps a {
      display: flex; align-items: center; gap: 12px; padding: 10px;
      border: 1px solid var(--pc-border); border-radius: var(--pc-radius-sm);
      text-decoration: none; color: inherit; transition: border-color 0.15s, background 0.15s;
    }
    .steps a:hover { border-color: var(--pc-primary); background: #faf9ff; }
    .step-icon {
      display: grid; place-items: center; width: 36px; height: 36px; flex-shrink: 0;
      border-radius: 10px; background: var(--pc-primary-soft); color: var(--pc-primary);
    }
    .step-icon mat-icon { font-size: 19px; width: 19px; height: 19px; }
    .step-text { flex: 1; display: flex; flex-direction: column; min-width: 0; }
    .step-title { font-weight: 600; }
    .step-sub { font-size: 12px; color: var(--pc-text-muted); }
    .step-go { color: var(--pc-text-subtle); }

    .empty {
      display: flex; flex-direction: column; align-items: center; gap: 12px;
      padding: 32px 16px; text-align: center; color: var(--pc-text-muted);
    }
    .empty mat-icon { font-size: 40px; width: 40px; height: 40px; color: #c7b9ff; }

    @media (max-width: 1200px) {
      .kpis { grid-template-columns: repeat(2, 1fr); }
    }
    @media (max-width: 1024px) {
      .welcome { grid-template-columns: 1fr; }
      .welcome-art { max-width: 560px; }
      .row-charts, .row-bottom { grid-template-columns: 1fr; }
    }
    @media (max-width: 560px) {
      .kpis { grid-template-columns: 1fr; gap: 12px; }
      .bar-row { grid-template-columns: 100px 1fr 36px; gap: 8px; }
      .welcome-actions, .welcome-actions a { width: 100%; }
    }
  `],
})
export class DashboardComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly store = inject(Store);

  readonly firstName$ = this.store.select(selectCurrentUser).pipe(
    map((u) => u?.name?.split(/\s+/)[0] ?? ''),
  );

  readonly findings = signal<Finding[]>([]);
  readonly total = computed(() => this.findings().length);

  readonly kpis = computed<KpiCard[]>(() => {
    const f = this.findings();
    const high = f.filter((x) => x.confidence === 'HIGH').length;
    const reviewed = f.filter((x) => x.status === 'REVIEWED').length;
    const pending = f.filter((x) => x.status === 'DISCOVERED').length;
    const tables = new Set(f.map((x) => x.tableName)).size;
    return [
      { title: 'PI Findings', value: f.length, hint: `across ${tables} tables`, icon: 'fingerprint', tone: 'violet', route: '/inventory' },
      { title: 'High Confidence', value: high, hint: 'likely personal data', icon: 'verified', tone: 'blue', route: '/inventory' },
      { title: 'Pending Review', value: pending, hint: 'awaiting a reviewer', icon: 'pending_actions', tone: 'amber', route: '/inventory' },
      { title: 'Reviewed', value: reviewed, hint: 'confirmed classifications', icon: 'task_alt', tone: 'green', route: '/inventory' },
    ];
  });

  readonly categoryCounts = computed(() => {
    const f = this.findings();
    const counts = CATEGORIES.map((c) => ({
      label: this.formatLabel(c),
      value: f.filter((x) => x.candidateCategory === c).length,
    }));
    const max = Math.max(1, ...counts.map((c) => c.value));
    return counts.map((c) => ({ ...c, pct: (c.value / max) * 100 }));
  });

  readonly slices = computed<Slice[]>(() => {
    const f = this.findings();
    const total = Math.max(1, f.length);
    const gap = f.length > 0 ? 2 : 0;
    let consumed = 0;
    return ['HIGH', 'MEDIUM', 'LOW'].map((level) => {
      const value = f.filter((x) => x.confidence === level).length;
      const length = (value / total) * DONUT_CIRCUMFERENCE;
      const visible = Math.max(0, length - gap);
      const slice: Slice = {
        label: this.formatLabel(level),
        value,
        color: CONFIDENCE_COLORS[level],
        dash: `${visible} ${DONUT_CIRCUMFERENCE - visible}`,
        offset: -consumed,
      };
      consumed += length;
      return slice;
    });
  });

  readonly recent = computed(() => this.findings().slice(0, 6));

  readonly workflow = LIFECYCLE_STAGES;

  ngOnInit(): void {
    this.http.get<{ content?: Finding[] }>('/findings', { params: { page: 0, size: 500 } }).subscribe({
      next: (response) => this.findings.set(response.content || []),
      error: () => this.findings.set([]),
    });
  }

  formatLabel(value: string): string {
    if (!value) return '';
    return value.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase());
  }
}
