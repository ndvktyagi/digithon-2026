import { Component, computed, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { forkJoin, Subscription, timer } from 'rxjs';
import { switchMap } from 'rxjs/operators';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { StatusBadgeComponent } from '../../shared/components/status-badge/status-badge.component';
import { ConfidenceBarComponent } from '../../shared/components/confidence-bar/confidence-bar.component';
import { NotificationService } from '../../core/notifications/notification.service';
import {
  formatDuration, formatLabel, isScanActive, Scan, SCAN_STATUS_LABELS, scanBadgeStatus,
  ScanFinding, ScanService,
} from './scan.models';

const POLL_MS = 3000;

interface TableGroup {
  key: string;
  schema: string;
  table: string;
  findings: ScanFinding[];
  categories: string[];
}

interface DatabaseGroup {
  key: string;
  label: string;
  order: number | null;
  tables: TableGroup[];
  columnCount: number;
}

@Component({
  selector: 'app-scan-detail',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterModule, MatIconModule, MatButtonModule, MatCheckboxModule,
    MatFormFieldModule, MatInputModule, MatTooltipModule, MatProgressBarModule,
    PageHeaderComponent, StatusBadgeComponent, ConfidenceBarComponent,
  ],
  template: `
    <div class="pc-page">
      <app-page-header [title]="scan()?.name || 'Scan'" backLink="/scans"
        [subtitle]="subtitle()">
        <app-status-badge headerBadge *ngIf="scan() as s" [label]="labels[s.status]"
                          [status]="badge(s.status)"></app-status-badge>
        <button *ngIf="scan() && active()" mat-stroked-button color="warn" type="button"
                (click)="stop()" [disabled]="stopping()">
          <mat-icon>stop_circle</mat-icon> Stop Scan
        </button>
        <a *ngIf="scan() && !active()" mat-stroked-button routerLink="/scans/new">
          <mat-icon>replay</mat-icon> New Scan
        </a>
      </app-page-header>

      <mat-progress-bar *ngIf="!scan()" mode="indeterminate"></mat-progress-bar>

      <ng-container *ngIf="scan() as s">
        <div class="banner error" *ngIf="s.errorMessage">
          <mat-icon>error_outline</mat-icon><span>{{ s.errorMessage }}</span>
        </div>

        <!-- KPIs -->
        <section class="kpis">
          <div class="kpi"><span class="kpi-label">Databases</span><span class="kpi-value">{{ s.databaseCount }}</span></div>
          <div class="kpi"><span class="kpi-label">Tables scanned</span><span class="kpi-value">{{ s.totalTablesScanned ?? 0 }}</span></div>
          <div class="kpi"><span class="kpi-label">Tables with PI</span><span class="kpi-value accent">{{ tableCount() }}</span></div>
          <div class="kpi"><span class="kpi-label">PI columns</span><span class="kpi-value accent">{{ findings().length }}</span></div>
          <div class="kpi"><span class="kpi-label">High confidence</span><span class="kpi-value">{{ highCount() }}</span></div>
        </section>

        <!-- Execution order -->
        <section class="panel">
          <div class="panel-head">
            <h2>Execution</h2>
            <span class="pc-muted small">{{ duration(s.startedAt, s.completedAt) }}</span>
          </div>
          <ol class="targets">
            <li *ngFor="let t of s.targets" class="target" [ngClass]="t.status.toLowerCase()">
              <span class="t-icon">
                <mat-icon *ngIf="t.status === 'COMPLETED'">check_circle</mat-icon>
                <mat-icon *ngIf="t.status === 'FAILED'">error</mat-icon>
                <mat-icon *ngIf="t.status === 'CANCELLED'">block</mat-icon>
                <mat-icon *ngIf="t.status === 'RUNNING'" class="spin">autorenew</mat-icon>
                <mat-icon *ngIf="t.status === 'PENDING'">schedule</mat-icon>
              </span>
              <span class="badge">DB-{{ t.executionOrder }}</span>
              <span class="t-main">
                <span class="t-label">{{ t.label }}</span>
                <span class="t-sub">
                  {{ labels[t.status] }}
                  <ng-container *ngIf="t.tablesScanned"> · {{ t.tablesScanned }} tables · {{ t.columnsScanned }} columns</ng-container>
                  <ng-container *ngIf="t.schemas"> · schemas: {{ t.schemas }}</ng-container>
                  <ng-container *ngIf="t.startedAt"> · {{ duration(t.startedAt, t.completedAt) }}</ng-container>
                </span>
                <span class="t-error" *ngIf="t.errorMessage">{{ t.errorMessage }}</span>
              </span>
              <span class="t-count" *ngIf="t.findingsCount" matTooltip="PI columns found">
                {{ t.findingsCount }} <mat-icon>fingerprint</mat-icon>
              </span>
            </li>
          </ol>
          <mat-progress-bar *ngIf="active()" mode="indeterminate" class="run-bar"></mat-progress-bar>
        </section>

        <!-- Approval state -->
        <div class="banner success" *ngIf="s.approvalState === 'APPROVED' && !editing()">
          <mat-icon>verified</mat-icon>
          <span>
            <strong>Approved for encryption</strong> by {{ s.approvedBy }} on {{ s.approvedAt | date:'d MMM y, HH:mm' }}:
            {{ approvedColumns() }} columns in {{ approvedTables() }} tables.
          </span>
          <button mat-button type="button" (click)="startEditing()">Change selection</button>
        </div>
        <div class="banner info" *ngIf="s.status === 'COMPLETED' && s.approvalState !== 'APPROVED' && findings().length">
          <mat-icon>fact_check</mat-icon>
          <span>Select the tables (or individual columns) to encrypt, then approve. Only approved scans move on to implementation.</span>
        </div>

        <!-- Results -->
        <section class="panel">
          <div class="panel-head wrap">
            <h2>Results</h2>
            <div class="tools" *ngIf="findings().length">
              <label class="search">
                <mat-icon>search</mat-icon>
                <input type="search" placeholder="Filter tables or columns" [ngModel]="search()"
                       (ngModelChange)="search.set($event)" />
              </label>
              <button mat-button type="button" (click)="expandAll(true)">Expand all</button>
              <button mat-button type="button" (click)="expandAll(false)">Collapse all</button>
            </div>
          </div>

          <div class="chips" *ngIf="categoryCounts().length > 1">
            <button type="button" class="chip" [class.active]="category() === null" (click)="category.set(null)">
              All categories
            </button>
            <button type="button" *ngFor="let c of categoryCounts()" class="chip" [class.active]="category() === c.category"
                    (click)="category.set(category() === c.category ? null : c.category)">
              {{ format(c.category) }} <span class="chip-count">{{ c.count }}</span>
            </button>
          </div>

          <div class="empty" *ngIf="findings().length === 0">
            <mat-icon>{{ active() ? 'hourglass_top' : 'verified_user' }}</mat-icon>
            <p *ngIf="active()">Scanning… results appear here as each table is analysed.</p>
            <p *ngIf="!active()">No personal information was detected in the scanned tables.</p>
          </div>

          <div class="review-tools" *ngIf="reviewing() && findings().length">
            <button mat-button type="button" (click)="selectAll(true)"><mat-icon>done_all</mat-icon> Select all</button>
            <button mat-button type="button" (click)="selectAll(false)">Clear selection</button>
            <button mat-button type="button" (click)="selectHighConfidence()">Select high confidence</button>
          </div>

          <div class="db-group" *ngFor="let db of groups()">
            <div class="db-head">
              <span class="badge" *ngIf="db.order">DB-{{ db.order }}</span>
              <mat-icon>storage</mat-icon>
              <span class="db-label">{{ db.label }}</span>
              <span class="pc-muted small">{{ db.tables.length }} tables · {{ db.columnCount }} PI columns</span>
            </div>

            <article *ngFor="let t of db.tables" class="table-card" [class.selected]="tableState(t) !== 'none'">
              <header class="table-head" (click)="toggleExpanded(t.key)">
                <mat-checkbox *ngIf="reviewing()" (click)="$event.stopPropagation()"
                              [checked]="tableState(t) === 'all'" [indeterminate]="tableState(t) === 'some'"
                              (change)="toggleTable(t, $event.checked)" [attr.aria-label]="'Select table ' + t.table">
                </mat-checkbox>
                <mat-icon class="approved-icon" *ngIf="!reviewing() && tableState(t) !== 'none'"
                          matTooltip="Approved for encryption">lock</mat-icon>
                <mat-icon class="tbl-icon">table_chart</mat-icon>
                <span class="tbl-name"><span class="schema">{{ t.schema }}.</span>{{ t.table }}</span>
                <span class="cats">
                  <span class="cat" *ngFor="let c of t.categories">{{ format(c) }}</span>
                </span>
                <span class="tbl-count">{{ t.findings.length }} PI column{{ t.findings.length > 1 ? 's' : '' }}</span>
                <mat-icon class="chev" [class.open]="isExpanded(t.key)">expand_more</mat-icon>
              </header>

              <div class="cols" *ngIf="isExpanded(t.key)">
                <table class="col-table">
                  <thead>
                    <tr>
                      <th *ngIf="reviewing()" class="cb"></th>
                      <th>Column</th><th>Type</th><th>Category</th><th>Confidence</th><th>Why</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr *ngFor="let f of t.findings" [class.sel]="selected().has(f.id)">
                      <td *ngIf="reviewing()" class="cb">
                        <mat-checkbox [checked]="selected().has(f.id)" (change)="toggleFinding(f.id)"
                                      [attr.aria-label]="'Select column ' + f.columnName"></mat-checkbox>
                      </td>
                      <td>
                        <span class="col-name">{{ f.columnName }}</span>
                        <span class="key" *ngIf="f.primaryKey" matTooltip="Primary key">PK</span>
                        <span class="key" *ngIf="f.foreignKey" [matTooltip]="'References ' + f.foreignKeyReference">FK</span>
                        <mat-icon class="approved-icon sm" *ngIf="!reviewing() && f.approved" matTooltip="Approved for encryption">lock</mat-icon>
                      </td>
                      <td class="muted">{{ f.dataType }}{{ f.nullable ? '' : ' · not null' }}</td>
                      <td><span class="cat">{{ format(f.candidateCategory) }}</span></td>
                      <td><app-confidence-bar [confidence]="f.confidence"></app-confidence-bar></td>
                      <td class="why">
                        <span class="method" [class.value]="f.detectionMethod === 'VALUE_PATTERN'">
                          {{ f.detectionMethod === 'VALUE_PATTERN' ? 'Values' : 'Name' }}
                        </span>
                        {{ f.reasoning }}
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </article>
          </div>

          <p class="empty small" *ngIf="findings().length && groups().length === 0">No results match the filter.</p>
        </section>
      </ng-container>

      <!-- Sticky approval bar -->
      <div class="approve-bar" *ngIf="reviewing() && findings().length">
        <div class="approve-summary">
          <strong>{{ selected().size }}</strong> column{{ selected().size === 1 ? '' : 's' }} in
          <strong>{{ selectedTableCount() }}</strong> table{{ selectedTableCount() === 1 ? '' : 's' }} selected
        </div>
        <mat-form-field appearance="outline" class="comment" subscriptSizing="dynamic">
          <mat-label>Comment (optional)</mat-label>
          <input matInput [(ngModel)]="comment" maxlength="1000" />
        </mat-form-field>
        <button *ngIf="editing()" mat-button type="button" (click)="cancelEditing()">Cancel</button>
        <button mat-flat-button color="primary" type="button" (click)="approve()"
                [disabled]="selected().size === 0 || approving()">
          <mat-icon>verified</mat-icon> {{ approving() ? 'Approving…' : 'Approve for Encryption' }}
        </button>
      </div>
    </div>
  `,
  styles: [`
    .small { font-size: 13px; }
    .banner {
      display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
      padding: 14px 18px; border-radius: var(--pc-radius); font-size: 14px;
    }
    .banner span { flex: 1; min-width: 200px; }
    .banner.error { background: var(--pc-danger-soft); color: #991b1b; }
    .banner.success { background: var(--pc-success-soft); color: #065f46; }
    .banner.success mat-icon { color: var(--pc-success); }
    .banner.info { background: var(--pc-primary-soft); color: var(--pc-primary-600); }

    .kpis { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 16px; }
    .kpi {
      display: flex; flex-direction: column; gap: 6px; padding: 16px 18px;
      background: var(--pc-surface); border: 1px solid var(--pc-border);
      border-radius: var(--pc-radius); box-shadow: var(--pc-shadow);
    }
    .kpi-label { font-size: 12.5px; color: var(--pc-text-muted); font-weight: 500; }
    .kpi-value { font-size: 26px; font-weight: 700; letter-spacing: -0.02em; font-variant-numeric: tabular-nums; }
    .kpi-value.accent { color: var(--pc-primary); }

    .panel {
      padding: 20px; min-width: 0;
      background: var(--pc-surface); border: 1px solid var(--pc-border);
      border-radius: var(--pc-radius); box-shadow: var(--pc-shadow);
    }
    .panel-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
    .panel-head.wrap { flex-wrap: wrap; }
    .panel-head h2 { margin: 0; }

    .targets { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
    .target {
      display: flex; align-items: flex-start; gap: 12px; padding: 12px;
      border: 1px solid var(--pc-border); border-radius: var(--pc-radius-sm);
    }
    .target.running { border-color: var(--pc-primary); background: #faf9ff; }
    .t-icon mat-icon { font-size: 22px; width: 22px; height: 22px; color: var(--pc-text-subtle); }
    .target.completed .t-icon mat-icon { color: var(--pc-success); }
    .target.failed .t-icon mat-icon { color: var(--pc-danger); }
    .target.running .t-icon mat-icon { color: var(--pc-primary); }
    .spin { animation: spin 1.2s linear infinite; }
    @keyframes spin { to { transform: rotate(360deg); } }
    .badge {
      padding: 3px 8px; border-radius: 6px; background: var(--pc-primary); color: #fff; flex-shrink: 0;
      font-size: 12px; font-weight: 700; font-variant-numeric: tabular-nums;
    }
    .t-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
    .t-label { font-weight: 600; overflow-wrap: anywhere; }
    .t-sub { font-size: 12.5px; color: var(--pc-text-muted); }
    .t-error { font-size: 12.5px; color: #b91c1c; }
    .t-count { display: inline-flex; align-items: center; gap: 4px; font-weight: 600; color: var(--pc-primary); }
    .t-count mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .run-bar { margin-top: 12px; }

    .tools { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
    .search {
      display: flex; align-items: center; gap: 6px; height: 36px; padding: 0 10px; width: 240px; max-width: 100%;
      border: 1px solid var(--pc-border); border-radius: var(--pc-radius-sm); background: #f9fafb; color: var(--pc-text-subtle);
    }
    .search:focus-within { border-color: var(--pc-primary); background: #fff; }
    .search mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .search input { flex: 1; min-width: 0; border: none; outline: none; background: transparent; font: inherit; color: var(--pc-text); }

    .chips { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 16px; }
    .chip {
      display: inline-flex; align-items: center; gap: 6px; height: 30px; padding: 0 12px;
      border: 1px solid var(--pc-border); border-radius: 999px; background: #fff; cursor: pointer;
      font: inherit; font-size: 13px; color: var(--pc-text-muted);
    }
    .chip:hover { border-color: var(--pc-primary); color: var(--pc-primary); }
    .chip.active { background: var(--pc-primary-soft); border-color: var(--pc-primary); color: var(--pc-primary-600); font-weight: 600; }
    .chip-count { font-size: 12px; font-weight: 600; }

    .review-tools { display: flex; flex-wrap: wrap; gap: 4px; margin-bottom: 12px; }

    .db-group + .db-group { margin-top: 24px; }
    .db-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
    .db-head mat-icon { color: #0369a1; font-size: 20px; width: 20px; height: 20px; }
    .db-label { font-weight: 600; overflow-wrap: anywhere; }

    .table-card { border: 1px solid var(--pc-border); border-radius: var(--pc-radius-sm); margin-bottom: 8px; overflow: hidden; }
    .table-card.selected { border-color: var(--pc-primary); }
    .table-head {
      display: flex; align-items: center; gap: 10px; padding: 10px 12px; cursor: pointer; flex-wrap: wrap;
      transition: background 0.15s;
    }
    .table-head:hover { background: #fafaff; }
    .tbl-icon { color: var(--pc-text-subtle); font-size: 20px; width: 20px; height: 20px; }
    .tbl-name { font-weight: 600; }
    .schema { color: var(--pc-text-subtle); font-weight: 500; }
    .cats { display: flex; flex-wrap: wrap; gap: 4px; flex: 1; }
    .cat {
      display: inline-block; padding: 2px 8px; border-radius: 999px; font-size: 12px; font-weight: 500;
      background: var(--pc-primary-soft); color: var(--pc-primary-600); white-space: nowrap;
    }
    .tbl-count { font-size: 13px; color: var(--pc-text-muted); white-space: nowrap; }
    .chev { color: var(--pc-text-subtle); transition: transform 0.2s; }
    .chev.open { transform: rotate(180deg); }
    .approved-icon { color: var(--pc-success); font-size: 20px; width: 20px; height: 20px; }
    .approved-icon.sm { font-size: 16px; width: 16px; height: 16px; vertical-align: middle; margin-left: 4px; }

    .cols { border-top: 1px solid var(--pc-border); overflow-x: auto; }
    .col-table { width: 100%; border-collapse: collapse; min-width: 760px; }
    .col-table th {
      padding: 8px 12px; text-align: left; background: #fafbfc; font-size: 11.5px; font-weight: 600;
      text-transform: uppercase; letter-spacing: 0.04em; color: var(--pc-text-muted);
    }
    .col-table td { padding: 10px 12px; border-top: 1px solid var(--pc-border); font-size: 13.5px; vertical-align: middle; }
    .col-table tr.sel td { background: #faf9ff; }
    .cb { width: 44px; }
    .col-name { font-weight: 600; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 13px; }
    .key {
      margin-left: 6px; padding: 1px 5px; border-radius: 4px; background: #f3f4f6;
      font-size: 10.5px; font-weight: 700; color: var(--pc-text-muted);
    }
    .muted { color: var(--pc-text-muted); }
    .why { color: var(--pc-text-muted); font-size: 12.5px; max-width: 360px; }
    .method {
      display: inline-block; margin-right: 6px; padding: 1px 6px; border-radius: 4px;
      background: #f3f4f6; color: #4b5563; font-size: 11px; font-weight: 600;
    }
    .method.value { background: #e0f2fe; color: #0369a1; }

    .empty { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 32px 8px; text-align: center; color: var(--pc-text-muted); }
    .empty > mat-icon { font-size: 32px; width: 32px; height: 32px; color: var(--pc-primary); }
    .empty.small { padding: 12px; }

    .approve-bar {
      position: sticky; bottom: 12px; z-index: 20;
      display: flex; align-items: center; gap: 12px; flex-wrap: wrap; justify-content: flex-end;
      padding: 12px 16px; border-radius: var(--pc-radius);
      background: rgba(255, 255, 255, 0.97); backdrop-filter: blur(8px);
      border: 1px solid var(--pc-primary); box-shadow: 0 8px 24px rgba(16, 24, 40, 0.12);
    }
    .approve-summary { margin-right: auto; color: var(--pc-text-muted); }
    .approve-summary strong { color: var(--pc-text); }
    .comment { width: 280px; max-width: 100%; }

    @media (max-width: 1100px) { .kpis { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
    @media (max-width: 600px) {
      .kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .approve-summary { width: 100%; }
      .comment { width: 100%; }
    }
  `],
})
export class ScanDetailComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly scanService = inject(ScanService);
  private readonly notifications = inject(NotificationService);
  private pollSub?: Subscription;
  private readonly scanId = this.route.snapshot.paramMap.get('scanId') ?? '';

  readonly labels = SCAN_STATUS_LABELS;
  readonly scan = signal<Scan | null>(null);
  readonly findings = signal<ScanFinding[]>([]);
  readonly selected = signal<Set<string>>(new Set());
  readonly expanded = signal<Set<string>>(new Set());
  readonly search = signal('');
  readonly category = signal<string | null>(null);
  readonly editing = signal(false);
  readonly stopping = signal(false);
  readonly approving = signal(false);
  comment = '';
  private expandedInitialised = false;

  readonly active = computed(() => !!this.scan() && isScanActive(this.scan()!.status));
  readonly reviewing = computed(() => {
    const s = this.scan();
    return !!s && s.status === 'COMPLETED' && (s.approvalState !== 'APPROVED' || this.editing());
  });

  readonly subtitle = computed(() => {
    const s = this.scan();
    if (!s) return '';
    const started = s.startedAt ? new Date(s.startedAt).toLocaleString() : '';
    return `${s.databaseCount} database${s.databaseCount === 1 ? '' : 's'} · started ${started}`
      + (s.valuePatternScan ? ' · value sampling on' : '');
  });

  readonly highCount = computed(() => this.findings().filter((f) => f.confidence === 'HIGH').length);
  readonly tableCount = computed(() => new Set(this.findings().map((f) => this.tableKey(f))).size);
  readonly approvedColumns = computed(() => this.findings().filter((f) => f.approved).length);
  readonly approvedTables = computed(() =>
    new Set(this.findings().filter((f) => f.approved).map((f) => this.tableKey(f))).size);
  readonly selectedTableCount = computed(() => {
    const sel = this.selected();
    return new Set(this.findings().filter((f) => sel.has(f.id)).map((f) => this.tableKey(f))).size;
  });

  readonly categoryCounts = computed(() => {
    const counts = new Map<string, number>();
    for (const f of this.findings()) counts.set(f.candidateCategory, (counts.get(f.candidateCategory) ?? 0) + 1);
    return [...counts.entries()].map(([category, count]) => ({ category, count })).sort((a, b) => b.count - a.count);
  });

  readonly groups = computed<DatabaseGroup[]>(() => {
    const term = this.search().trim().toLowerCase();
    const cat = this.category();
    const targets = this.scan()?.targets ?? [];
    const visible = this.findings().filter((f) =>
      (!cat || f.candidateCategory === cat)
      && (!term || f.tableName.toLowerCase().includes(term) || f.columnName.toLowerCase().includes(term)));

    const dbs = new Map<string, Map<string, ScanFinding[]>>();
    for (const f of visible) {
      const dbKey = f.databaseConnectorId ?? 'unknown';
      const tables = dbs.get(dbKey) ?? new Map<string, ScanFinding[]>();
      const list = tables.get(this.tableKey(f)) ?? [];
      list.push(f);
      tables.set(this.tableKey(f), list);
      dbs.set(dbKey, tables);
    }

    return [...dbs.entries()].map(([dbKey, tables]) => {
      const target = targets.find((t) => t.connectorId === dbKey);
      const tableGroups = [...tables.entries()].map(([key, list]) => this.toTableGroup(key, list));
      return {
        key: dbKey,
        label: target?.label ?? 'Deleted connector',
        order: target?.executionOrder ?? null,
        tables: tableGroups,
        columnCount: tableGroups.reduce((n, t) => n + t.findings.length, 0),
      };
    }).sort((a, b) => (a.order ?? 99) - (b.order ?? 99));
  });

  ngOnInit(): void {
    this.pollSub = timer(0, POLL_MS)
      .pipe(switchMap(() => forkJoin([this.scanService.get(this.scanId), this.scanService.findings(this.scanId)])))
      .subscribe({
        next: ([scan, findings]) => this.apply(scan, findings),
        error: () => this.pollSub?.unsubscribe(),
      });
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
  }

  private apply(scan: Scan, findings: ScanFinding[]): void {
    this.scan.set(scan);
    this.findings.set(findings);
    if (!isScanActive(scan.status)) {
      this.pollSub?.unsubscribe();
      this.resetSelection();
    }
    // Small results start expanded; large ones start collapsed so the table list stays scannable
    if (!this.expandedInitialised && findings.length && !isScanActive(scan.status)) {
      const keys = new Set(findings.map((f) => this.tableKey(f)));
      if (keys.size <= 6) this.expanded.set(keys);
      this.expandedInitialised = true;
    }
  }

  private resetSelection(): void {
    this.selected.set(new Set(this.findings().filter((f) => f.approved).map((f) => f.id)));
  }

  private toTableGroup(key: string, findings: ScanFinding[]): TableGroup {
    return {
      key,
      schema: findings[0].dbSchema,
      table: findings[0].tableName,
      findings,
      categories: [...new Set(findings.map((f) => f.candidateCategory))],
    };
  }

  tableKey(f: ScanFinding): string {
    return `${f.databaseConnectorId}|${f.dbSchema}|${f.tableName}`;
  }

  tableState(t: TableGroup): 'all' | 'some' | 'none' {
    const sel = this.reviewing() ? this.selected() : new Set(t.findings.filter((f) => f.approved).map((f) => f.id));
    const n = t.findings.filter((f) => sel.has(f.id)).length;
    return n === 0 ? 'none' : n === t.findings.length ? 'all' : 'some';
  }

  toggleTable(t: TableGroup, checked: boolean): void {
    this.selected.update((s) => {
      const next = new Set(s);
      t.findings.forEach((f) => checked ? next.add(f.id) : next.delete(f.id));
      return next;
    });
  }

  toggleFinding(id: string): void {
    this.selected.update((s) => {
      const next = new Set(s);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  selectAll(all: boolean): void {
    this.selected.set(all ? new Set(this.findings().map((f) => f.id)) : new Set());
  }

  selectHighConfidence(): void {
    this.selected.set(new Set(this.findings().filter((f) => f.confidence === 'HIGH').map((f) => f.id)));
  }

  isExpanded(key: string): boolean {
    return this.expanded().has(key);
  }

  toggleExpanded(key: string): void {
    this.expanded.update((s) => {
      const next = new Set(s);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  }

  expandAll(open: boolean): void {
    this.expanded.set(open ? new Set(this.findings().map((f) => this.tableKey(f))) : new Set());
  }

  startEditing(): void {
    this.resetSelection();
    this.editing.set(true);
  }

  cancelEditing(): void {
    this.resetSelection();
    this.editing.set(false);
  }

  approve(): void {
    this.approving.set(true);
    this.scanService.approve(this.scanId, [...this.selected()], this.comment.trim()).subscribe({
      next: (scan) => {
        this.approving.set(false);
        this.editing.set(false);
        this.comment = '';
        this.scan.set(scan);
        this.notifications.showSuccess(
          `Approved ${this.selected().size} columns in ${this.selectedTableCount()} tables for encryption`);
        this.scanService.findings(this.scanId).subscribe((f) => {
          this.findings.set(f);
          this.resetSelection();
        });
      },
      error: () => this.approving.set(false),
    });
  }

  stop(): void {
    this.stopping.set(true);
    this.scanService.stop(this.scanId).subscribe({
      next: (scan) => {
        this.stopping.set(false);
        this.scan.set(scan);
        this.notifications.showSuccess('Scan stopped');
      },
      error: () => this.stopping.set(false),
    });
  }

  badge(status: Scan['status']): string {
    return scanBadgeStatus(status);
  }

  duration(start: string | null, end: string | null): string {
    return formatDuration(start, end);
  }

  format(value: string): string {
    return formatLabel(value);
  }
}
