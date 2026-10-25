import { Component, computed, inject, Injectable, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { RouterModule } from '@angular/router';
import { Observable, Subscription, timer } from 'rxjs';
import { switchMap } from 'rxjs/operators';
import { Store } from '@ngrx/store';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { NotificationService } from '../../core/notifications/notification.service';
import { workspaceActions } from '../../store/workspace/workspace.actions';

type RowFilter = 'ALL' | 'ENCRYPTED' | 'PENDING';

interface ColumnStat { column: string; encryptedColumn: string; values: number; encrypted: number; pending: number; }
interface TableView {
  connectorId: string; database: string; schema: string; table: string; rows: number;
  rowsWithPi: number; encryptedRows: number; pendingRows: number; columns: ColumnStat[]; error?: string;
}
interface RunColumn { column: string; encryptedColumn: string; encrypted: number; verified: number; failures: number; }
interface RunTable {
  database: string; schema: string; table: string; rowsEncrypted: number; rowsVerified?: number; rowsFailed?: number;
  columns: RunColumn[];
}
interface SyncRun {
  id: string; status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'; phase?: string;
  rowsEncrypted: number; valuesEncrypted: number; valuesVerified: number; verifyFailures: number;
  rowsVerified: number; rowsFailed: number;
  tables: RunTable[]; log: string[]; createdAt: string; startedAt?: string; completedAt?: string; errorMessage?: string;
}
interface Overview {
  implementationRunId?: string; branchName?: string; prNumber?: number; prUrl?: string; stage: string;
  canStart: boolean; problems: string[]; tables: TableView[];
  totalRows: number; encryptedRows: number; pendingRows: number; runs: SyncRun[];
}
interface CellView { column: string; plainMasked: string | null; encryptedPreview: string | null; state: string; }
interface RowsPage {
  schema: string; table: string; filter: RowFilter; total: number; page: number; size: number;
  columns: string[]; rows: CellView[][];
}

const POLL_MS = 2500;
const PAGE_SIZE = 25;

@Injectable({ providedIn: 'root' })
export class DataSyncApi {
  private readonly http = inject(HttpClient);

  private get base(): string {
    return `/workspaces/${sessionStorage.getItem('workspace_id')}/data-sync`;
  }

  overview(): Observable<Overview> {
    return this.http.get<Overview>(this.base);
  }

  start(): Observable<SyncRun> {
    return this.http.post<SyncRun>(`${this.base}/runs`, {});
  }

  rows(t: TableView, filter: RowFilter, page: number): Observable<RowsPage> {
    return this.http.get<RowsPage>(`${this.base}/rows`, {
      params: { connectorId: t.connectorId, schema: t.schema, table: t.table, filter, page, size: PAGE_SIZE },
    });
  }
}

@Component({
  selector: 'app-data-sync',
  standalone: true,
  imports: [CommonModule, RouterModule, MatIconModule, MatButtonModule, MatProgressBarModule, PageHeaderComponent],
  template: `
    <div class="pc-page">
      <app-page-header title="Data Sync"
        subtitle="Encrypt the existing personal data into the new encrypted columns, then verify every row.">
        <a *ngIf="data()?.prUrl" mat-stroked-button [href]="data()!.prUrl" target="_blank" rel="noopener">
          <mat-icon>open_in_new</mat-icon> Pull request #{{ data()!.prNumber }}
        </a>
        <button mat-flat-button color="primary" type="button" (click)="start()"
                [disabled]="!data()?.canStart || busy() || running()">
          <mat-icon>sync_lock</mat-icon> {{ latest()?.status === 'COMPLETED' ? 'Run again' : 'Start Data Sync' }}
        </button>
      </app-page-header>

      <mat-progress-bar *ngIf="!data()" mode="indeterminate"></mat-progress-bar>

      <ng-container *ngIf="data() as d">
        <ul class="problems" *ngIf="d.problems.length">
          <li *ngFor="let p of d.problems"><mat-icon>error_outline</mat-icon>{{ p }}</li>
        </ul>

        <div class="explain">
          <mat-icon>info</mat-icon>
          <span>Only the new <code>_enc</code> columns are written: each plain value is encrypted (AES-256-GCM,
            <code>enc:v1:</code>) into its encrypted twin. Plain columns stay unchanged until Finalize.
            <ng-container *ngIf="d.branchName"> Columns come from the approved implementation on <code>{{ d.branchName }}</code>.</ng-container>
            Click any number to see the rows.</span>
        </div>

        <!-- Totals (rows) -->
        <section class="kpis">
          <button type="button" class="kpi" (click)="openViewer(d.tables[0], 'ALL')" [disabled]="!d.tables.length">
            <span class="kpi-label">Rows</span><span class="kpi-value">{{ d.totalRows | number }}</span>
          </button>
          <button type="button" class="kpi" (click)="openViewer(d.tables[0], 'ENCRYPTED')" [disabled]="!d.tables.length">
            <span class="kpi-label">Encrypted rows</span><span class="kpi-value ok">{{ d.encryptedRows | number }}</span>
          </button>
          <button type="button" class="kpi" (click)="openViewer(d.tables[0], 'PENDING')" [disabled]="!d.tables.length">
            <span class="kpi-label">Pending rows</span>
            <span class="kpi-value" [class.warn]="d.pendingRows > 0">{{ d.pendingRows | number }}</span>
          </button>
          <button type="button" class="kpi" (click)="openViewer(d.tables[0], 'ENCRYPTED')" [disabled]="!d.tables.length || !latest()">
            <span class="kpi-label">Verified rows</span>
            <span class="kpi-value" [class.ok]="latest()?.status === 'COMPLETED'" [class.bad]="(latest()?.rowsFailed ?? 0) > 0">
              {{ latest() ? (latest()!.rowsVerified | number) : '—' }}
            </span>
          </button>
        </section>

        <!-- Current / latest run -->
        <section class="panel run" *ngIf="latest() as r">
          <div class="panel-head">
            <h2>{{ running() ? 'Syncing…' : 'Latest run' }}</h2>
            <span class="pill" [ngClass]="r.status.toLowerCase()">{{ runLabel(r) }}</span>
          </div>
          <mat-progress-bar [mode]="running() && r.phase === 'VERIFYING' ? 'indeterminate' : 'determinate'"
                            [value]="progress()"></mat-progress-bar>
          <p class="sub">
            {{ r.rowsEncrypted | number }} row(s) encrypted · {{ r.rowsVerified | number }} row(s) verified
            <ng-container *ngIf="r.rowsFailed"> · <span class="bad">{{ r.rowsFailed | number }} row(s) failed verification</span></ng-container>
            · started {{ r.startedAt | date:'d MMM, HH:mm:ss' }}
          </p>
          <div class="banner error" *ngIf="r.errorMessage"><mat-icon>error_outline</mat-icon>{{ r.errorMessage }}</div>
          <div class="log-head">
            <h3>Log</h3>
            <button mat-button type="button" (click)="showLog.set(!showLog())">
              <mat-icon>{{ showLog() ? 'expand_less' : 'expand_more' }}</mat-icon> {{ showLog() ? 'Minimize' : 'Show log' }}
            </button>
          </div>
          <pre class="log" *ngIf="showLog()">{{ r.log.join('\\n') }}</pre>
        </section>

        <!-- What changes, table by table -->
        <article class="panel table-card" *ngFor="let t of d.tables">
          <header class="table-head">
            <mat-icon>table_chart</mat-icon>
            <div class="table-title">
              <h3><span class="schema">{{ t.schema }}.</span>{{ t.table }}</h3>
              <span class="sub">{{ t.database }}</span>
            </div>
            <div class="row-stats">
              <button type="button" class="stat" (click)="openViewer(t, 'ALL')">
                <span class="stat-value">{{ t.rows | number }}</span><span class="stat-label">Rows</span>
              </button>
              <button type="button" class="stat" (click)="openViewer(t, 'ENCRYPTED')">
                <span class="stat-value ok">{{ t.encryptedRows | number }}</span><span class="stat-label">Encrypted</span>
              </button>
              <button type="button" class="stat" (click)="openViewer(t, 'PENDING')">
                <span class="stat-value" [class.warn]="t.pendingRows > 0">{{ t.pendingRows | number }}</span><span class="stat-label">Pending</span>
              </button>
              <button type="button" class="stat" (click)="openViewer(t, 'ENCRYPTED')" [disabled]="!runTable(t)">
                <span class="stat-value" [class.bad]="(runTable(t)?.rowsFailed ?? 0) > 0">
                  {{ runTable(t)?.rowsVerified != null ? (runTable(t)!.rowsVerified | number) : '—' }}</span>
                <span class="stat-label">Verified</span>
              </button>
            </div>
          </header>
          <div class="banner error" *ngIf="t.error"><mat-icon>error_outline</mat-icon>{{ t.error }}</div>

          <table class="cols" *ngIf="t.columns.length">
            <thead>
              <tr><th>Plain column</th><th></th><th>Encrypted column</th><th class="num">Rows with value</th>
                <th class="num">Encrypted</th><th class="num">Pending</th><th class="num">Verified</th><th class="bar-col">Progress</th></tr>
            </thead>
            <tbody>
              <tr *ngFor="let c of t.columns">
                <td class="mono">{{ c.column }}</td>
                <td class="arrow"><mat-icon>arrow_forward</mat-icon></td>
                <td class="mono new">{{ c.encryptedColumn }}</td>
                <td class="num"><button type="button" class="num-link" (click)="openViewer(t, 'ALL')">{{ c.values | number }}</button></td>
                <td class="num"><button type="button" class="num-link ok" (click)="openViewer(t, 'ENCRYPTED')">{{ c.encrypted | number }}</button></td>
                <td class="num"><button type="button" class="num-link" [class.warn]="c.pending > 0" (click)="openViewer(t, 'PENDING')">{{ c.pending | number }}</button></td>
                <td class="num">
                  <ng-container *ngIf="verified(t, c.column) as v; else dash">
                    <span [class.bad]="v.failures > 0">{{ v.verified | number }}<ng-container *ngIf="v.failures"> / {{ v.failures }} failed</ng-container></span>
                  </ng-container>
                </td>
                <td class="bar-col"><div class="bar"><span [style.width.%]="c.values ? (c.encrypted / c.values) * 100 : 0"></span></div></td>
              </tr>
            </tbody>
          </table>
          <ng-template #dash><span class="muted">—</span></ng-template>
        </article>

        <section class="panel" *ngIf="d.runs.length > 1">
          <h2>Previous runs</h2>
          <ul class="history">
            <li *ngFor="let r of d.runs.slice(1)">
              <span class="pill" [ngClass]="r.status.toLowerCase()">{{ runLabel(r) }}</span>
              <span>{{ r.createdAt | date:'d MMM y, HH:mm' }}</span>
              <span class="sub">{{ r.rowsEncrypted | number }} row(s) encrypted · {{ r.rowsVerified | number }} verified</span>
            </li>
          </ul>
        </section>
      </ng-container>

      <!-- Row viewer -->
      <div class="viewer-backdrop" *ngIf="viewer() as v" (click)="closeViewer()">
        <div class="viewer" role="dialog" aria-modal="true" aria-label="Rows" (click)="$event.stopPropagation()">
          <header class="viewer-head">
            <div>
              <h2><span class="schema">{{ v.table.schema }}.</span>{{ v.table.table }}</h2>
              <span class="sub">{{ v.table.database }} · values masked, each checked by decrypting it</span>
            </div>
            <select *ngIf="(data()?.tables?.length ?? 0) > 1" class="table-select" [value]="tableKey(v.table)"
                    (change)="switchTable($any($event.target).value)" aria-label="Table">
              <option *ngFor="let t of data()!.tables" [value]="tableKey(t)">{{ t.schema }}.{{ t.table }}</option>
            </select>
            <button mat-icon-button type="button" (click)="closeViewer()" aria-label="Close"><mat-icon>close</mat-icon></button>
          </header>
          <div class="tabs" role="tablist">
            <button type="button" role="tab" *ngFor="let f of filters" class="tab" [class.active]="v.filter === f.value"
                    [attr.aria-selected]="v.filter === f.value" (click)="loadRows(v.table, f.value, 0)">{{ f.label }}</button>
          </div>
          <mat-progress-bar *ngIf="rowsLoading()" mode="indeterminate"></mat-progress-bar>
          <div class="viewer-body">
            <p class="sub" *ngIf="rowsPage() && !rowsPage()!.rows.length">No rows.</p>
            <table class="rows" *ngIf="rowsPage()?.rows?.length">
              <thead>
                <tr><th class="idx">#</th><th *ngFor="let c of rowsPage()!.columns">{{ c }} <mat-icon class="tiny">arrow_forward</mat-icon> {{ c }}_enc</th></tr>
              </thead>
              <tbody>
                <tr *ngFor="let row of rowsPage()!.rows; let i = index">
                  <td class="idx">{{ rowsPage()!.page * rowsPage()!.size + i + 1 | number }}</td>
                  <td *ngFor="let cell of row">
                    <div class="cell">
                      <span class="plain">{{ cell.plainMasked ?? 'NULL' }}</span>
                      <span class="state" [ngClass]="cell.state.toLowerCase()">{{ stateLabel(cell.state) }}</span>
                    </div>
                    <span class="enc" [class.empty]="!cell.encryptedPreview">{{ cell.encryptedPreview ?? 'not encrypted yet' }}</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <footer class="viewer-foot" *ngIf="rowsPage() as p">
            <span class="sub">{{ p.total | number }} row(s) · page {{ p.page + 1 }} of {{ pages() | number }}</span>
            <span class="spacer"></span>
            <button mat-stroked-button type="button" [disabled]="p.page === 0 || rowsLoading()"
                    (click)="loadRows(v.table, v.filter, p.page - 1)"><mat-icon>chevron_left</mat-icon> Previous</button>
            <button mat-stroked-button type="button" [disabled]="p.page + 1 >= pages() || rowsLoading()"
                    (click)="loadRows(v.table, v.filter, p.page + 1)">Next <mat-icon iconPositionEnd>chevron_right</mat-icon></button>
          </footer>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .panel { padding: 20px; min-width: 0; background: var(--pc-surface); border: 1px solid var(--pc-border); border-radius: var(--pc-radius); box-shadow: var(--pc-shadow); }
    .panel h2 { margin: 0; }
    .panel-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
    .sub { display: block; margin: 8px 0 0; font-size: 12.5px; color: var(--pc-text-muted); }
    .mono, code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12.5px; }
    code { background: #f3f4f6; padding: 1px 5px; border-radius: 4px; }
    .muted { color: var(--pc-text-subtle); }
    .ok { color: #047857; }
    .warn { color: #b45309; }
    .bad { color: #b91c1c; font-weight: 600; }

    .problems { list-style: none; margin: 0; padding: 12px 14px; border-radius: var(--pc-radius-sm); background: var(--pc-warning-soft); color: #92400e; }
    .problems li { display: flex; align-items: center; gap: 8px; font-size: 13.5px; }
    .problems li + li { margin-top: 6px; }
    .problems mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .explain { display: flex; gap: 10px; align-items: flex-start; padding: 12px 16px; border-radius: var(--pc-radius); background: var(--pc-primary-soft); color: var(--pc-primary-600); font-size: 13.5px; }
    .banner { display: flex; align-items: center; gap: 8px; margin-top: 10px; padding: 10px 14px; border-radius: var(--pc-radius-sm); font-size: 13.5px; }
    .banner.error { background: var(--pc-danger-soft); color: #991b1b; }

    .kpis { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; }
    .kpi {
      display: flex; flex-direction: column; gap: 6px; padding: 16px 18px; text-align: left; cursor: pointer; font: inherit;
      background: var(--pc-surface); border: 1px solid var(--pc-border); border-radius: var(--pc-radius); box-shadow: var(--pc-shadow);
      transition: border-color 0.15s, box-shadow 0.15s;
    }
    .kpi:hover:not(:disabled) { border-color: var(--pc-primary); box-shadow: var(--pc-shadow-hover); }
    .kpi:disabled { cursor: default; }
    .kpi-label { font-size: 12.5px; color: var(--pc-text-muted); font-weight: 500; }
    .kpi-value { font-size: 26px; font-weight: 700; font-variant-numeric: tabular-nums; color: var(--pc-text); }
    .kpi-value.ok { color: #047857; }
    .kpi-value.warn { color: #b45309; }
    .kpi-value.bad { color: #b91c1c; }

    .pill { padding: 3px 10px; border-radius: 999px; font-size: 12px; font-weight: 600; background: #f3f4f6; color: #4b5563; }
    .pill.running, .pill.pending { background: var(--pc-warning-soft); color: #b45309; }
    .pill.completed { background: var(--pc-success-soft); color: #047857; }
    .pill.failed { background: var(--pc-danger-soft); color: #b91c1c; }
    .log-head { display: flex; align-items: center; justify-content: space-between; margin-top: 12px; }
    .log-head h3 { margin: 0; font-size: 14px; }
    .log { margin: 6px 0 0; padding: 12px; max-height: 260px; overflow: auto; border-radius: var(--pc-radius-sm); background: #0f172a; color: #e2e8f0; font-size: 12px; white-space: pre-wrap; }

    .table-head { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; margin-bottom: 12px; }
    .table-head > mat-icon { color: var(--pc-primary); }
    .table-title { flex: 1; min-width: 200px; }
    .table-head h3 { margin: 0; }
    .table-head .sub { margin: 2px 0 0; }
    .schema { color: var(--pc-text-subtle); font-weight: 500; }
    .row-stats { display: flex; gap: 8px; flex-wrap: wrap; }
    .stat {
      display: flex; flex-direction: column; align-items: flex-end; min-width: 84px; padding: 6px 12px; cursor: pointer;
      border: 1px solid var(--pc-border); border-radius: var(--pc-radius-sm); background: #fff; font: inherit;
    }
    .stat:hover:not(:disabled) { border-color: var(--pc-primary); background: #faf9ff; }
    .stat:disabled { cursor: default; }
    .stat-value { font-size: 18px; font-weight: 700; font-variant-numeric: tabular-nums; }
    .stat-label { font-size: 11.5px; color: var(--pc-text-muted); }
    .cols { width: 100%; border-collapse: collapse; }
    .cols th { text-align: left; padding: 8px 10px; font-size: 11.5px; text-transform: uppercase; letter-spacing: 0.04em; color: var(--pc-text-muted); background: #fafbfc; }
    .cols td { padding: 10px; border-top: 1px solid var(--pc-border); }
    .num { text-align: right !important; font-variant-numeric: tabular-nums; }
    .num-link { border: none; background: none; padding: 0; font: inherit; font-variant-numeric: tabular-nums; color: inherit; cursor: pointer; text-decoration: underline dotted; text-underline-offset: 3px; }
    .num-link:hover { color: var(--pc-primary); }
    .arrow { width: 24px; color: var(--pc-text-subtle); }
    .arrow mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .new { color: #047857; font-weight: 600; }
    .bar-col { width: 160px; }
    .bar { height: 8px; border-radius: 999px; background: #f1f2f6; overflow: hidden; }
    .bar span { display: block; height: 100%; background: var(--pc-success); transition: width 0.4s; }
    .history { list-style: none; margin: 12px 0 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
    .history li { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
    .history .sub { margin: 0; }

    .viewer-backdrop { position: fixed; inset: 0; z-index: 1000; display: grid; place-items: center; padding: 24px; background: rgba(15, 23, 42, 0.45); }
    .viewer { display: flex; flex-direction: column; width: min(1100px, 100%); max-height: calc(100vh - 48px); background: #fff; border-radius: var(--pc-radius); box-shadow: 0 24px 64px rgba(0, 0, 0, 0.25); overflow: hidden; }
    .viewer-head { display: flex; align-items: center; gap: 12px; padding: 16px 20px; border-bottom: 1px solid var(--pc-border); }
    .viewer-head > div { flex: 1; min-width: 0; }
    .viewer-head h2 { margin: 0; }
    .viewer-head .sub { margin: 2px 0 0; }
    .table-select { height: 34px; padding: 0 8px; border: 1px solid var(--pc-border); border-radius: 8px; font: inherit; }
    .tabs { display: flex; gap: 4px; padding: 8px 20px 0; border-bottom: 1px solid var(--pc-border); }
    .tab { padding: 8px 14px; border: none; border-bottom: 2px solid transparent; background: none; font: inherit; font-weight: 600; color: var(--pc-text-muted); cursor: pointer; }
    .tab.active { color: var(--pc-primary); border-bottom-color: var(--pc-primary); }
    .viewer-body { flex: 1; overflow: auto; padding: 0 20px; }
    .rows { width: 100%; border-collapse: collapse; font-size: 12.5px; }
    .rows th { position: sticky; top: 0; text-align: left; padding: 8px 10px; background: #fafbfc; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; white-space: nowrap; }
    .rows td { padding: 8px 10px; border-top: 1px solid var(--pc-border); vertical-align: top; }
    .idx { width: 1%; color: var(--pc-text-subtle); text-align: right; font-variant-numeric: tabular-nums; }
    .tiny { font-size: 13px; width: 13px; height: 13px; vertical-align: middle; color: var(--pc-text-subtle); }
    .cell { display: flex; align-items: center; gap: 8px; }
    .plain { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
    .enc { display: block; margin-top: 2px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; color: #047857; word-break: break-all; }
    .enc.empty { color: var(--pc-text-subtle); font-style: italic; }
    .state { padding: 0 7px; border-radius: 999px; font-size: 11px; font-weight: 600; background: #f3f4f6; color: #4b5563; }
    .state.verified { background: var(--pc-success-soft); color: #047857; }
    .state.pending { background: var(--pc-warning-soft); color: #b45309; }
    .state.mismatch { background: var(--pc-danger-soft); color: #b91c1c; }
    .viewer-foot { display: flex; align-items: center; gap: 8px; padding: 12px 20px; border-top: 1px solid var(--pc-border); }
    .viewer-foot .sub { margin: 0; }
    .spacer { flex: 1; }

    @media (max-width: 900px) { .kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); } .bar-col { display: none; } }
  `],
})
export class DataSyncComponent implements OnInit, OnDestroy {
  private readonly api = inject(DataSyncApi);
  private readonly notifications = inject(NotificationService);
  private readonly store = inject(Store);
  private pollSub?: Subscription;

  readonly filters: { value: RowFilter; label: string }[] = [
    { value: 'ALL', label: 'All rows' }, { value: 'ENCRYPTED', label: 'Encrypted' }, { value: 'PENDING', label: 'Pending' },
  ];

  readonly data = signal<Overview | null>(null);
  readonly busy = signal(false);
  /** The log is open by default; the user can minimize it. */
  readonly showLog = signal(true);
  readonly viewer = signal<{ table: TableView; filter: RowFilter } | null>(null);
  readonly rowsPage = signal<RowsPage | null>(null);
  readonly rowsLoading = signal(false);

  readonly latest = computed(() => this.data()?.runs[0] ?? null);
  readonly running = computed(() => ['PENDING', 'RUNNING'].includes(this.latest()?.status ?? ''));
  readonly progress = computed(() => {
    const d = this.data();
    const r = this.latest();
    if (!d || !r) return 0;
    if (r.status === 'COMPLETED') return 100;
    const withPi = d.tables.reduce((n, t) => n + t.rowsWithPi, 0);
    return withPi ? Math.min(100, (d.encryptedRows / withPi) * 100) : 0;
  });
  readonly pages = computed(() => {
    const p = this.rowsPage();
    return p ? Math.max(1, Math.ceil(p.total / p.size)) : 1;
  });

  ngOnInit(): void {
    this.refresh();
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
  }

  /** Loads the overview; keeps polling while a sync runs. */
  private refresh(): void {
    this.pollSub?.unsubscribe();
    let wasRunning = false;
    this.pollSub = timer(0, POLL_MS).pipe(switchMap(() => this.api.overview())).subscribe({
      next: (d) => {
        this.data.set(d);
        if (this.running()) wasRunning = true;
        if (!this.running()) {
          this.pollSub?.unsubscribe();
          // A completed sync advances the lifecycle; refresh the stage chip
          if (wasRunning) this.store.dispatch(workspaceActions.loadWorkspaces());
        }
      },
      error: () => this.pollSub?.unsubscribe(),
    });
  }

  start(): void {
    this.busy.set(true);
    this.api.start().subscribe({
      next: () => {
        this.busy.set(false);
        this.notifications.showSuccess('Data sync started');
        this.refresh();
      },
      error: () => this.busy.set(false),
    });
  }

  openViewer(table: TableView | undefined, filter: RowFilter): void {
    if (table) this.loadRows(table, filter, 0);
  }

  loadRows(table: TableView, filter: RowFilter, page: number): void {
    this.viewer.set({ table, filter });
    this.rowsLoading.set(true);
    this.api.rows(table, filter, page).subscribe({
      next: (p) => {
        this.rowsPage.set(p);
        this.rowsLoading.set(false);
      },
      error: () => this.rowsLoading.set(false),
    });
  }

  closeViewer(): void {
    this.viewer.set(null);
    this.rowsPage.set(null);
  }

  tableKey(t: TableView): string {
    return `${t.connectorId}|${t.schema}|${t.table}`;
  }

  switchTable(key: string): void {
    const t = this.data()?.tables.find((x) => this.tableKey(x) === key);
    if (t) this.loadRows(t, this.viewer()?.filter ?? 'ALL', 0);
  }

  runTable(t: TableView): RunTable | null {
    return this.latest()?.tables.find((x) => x.schema === t.schema && x.table === t.table && x.database === t.database) ?? null;
  }

  verified(t: TableView, column: string): RunColumn | null {
    return this.runTable(t)?.columns.find((c) => c.column === column) ?? null;
  }

  stateLabel(state: string): string {
    return ({ VERIFIED: 'Verified', PENDING: 'Pending', MISMATCH: 'Mismatch', EMPTY: 'Empty' } as Record<string, string>)[state] ?? state;
  }

  runLabel(r: SyncRun): string {
    if (r.status === 'RUNNING') return r.phase === 'VERIFYING' ? 'Verifying' : 'Encrypting';
    return { PENDING: 'Queued', RUNNING: 'Running', COMPLETED: 'Completed', FAILED: 'Failed' }[r.status];
  }
}
