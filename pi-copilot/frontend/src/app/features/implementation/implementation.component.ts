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
import { MatTooltipModule } from '@angular/material/tooltip';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { NotificationService } from '../../core/notifications/notification.service';
import { workspaceActions } from '../../store/workspace/workspace.actions';

type RunStatus = 'WAITING' | 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED';

interface Readiness {
  ready: boolean;
  approvedScanName: string | null;
  approvedScanId: string | null;
  approvedColumns: number;
  providerName: string | null;
  repository: string | null;
  baseBranch: string | null;
  problems: string[];
}

interface ColumnResult {
  database: string;
  schema: string;
  table: string;
  column: string;
  encryptedColumn: string;
  codeStatus: string;
  entityFile: string | null;
}

interface ImplementationRun {
  id: string;
  status: RunStatus;
  dbStatus: RunStatus;
  codeStatus: RunStatus;
  scanName: string | null;
  providerName: string | null;
  repository: string | null;
  baseBranch: string | null;
  branchName: string | null;
  commitSha: string | null;
  compareUrl: string | null;
  ddlScript: string | null;
  columns: ColumnResult[];
  changedFiles: string[];
  log: string[];
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  errorMessage: string | null;
  roundNumber: number;
  parentRunId?: string;
  improvementRequest?: string;
  prUrl?: string;
  prNumber?: number;
}

const POLL_MS = 2500;

@Injectable({ providedIn: 'root' })
export class ImplementationApi {
  private readonly http = inject(HttpClient);

  private get base(): string {
    return `/workspaces/${sessionStorage.getItem('workspace_id')}/implementation`;
  }

  readiness(): Observable<Readiness> {
    return this.http.get<Readiness>(`${this.base}/readiness`);
  }

  runs(): Observable<ImplementationRun[]> {
    return this.http.get<ImplementationRun[]>(`${this.base}/runs`);
  }

  start(): Observable<ImplementationRun> {
    return this.http.post<ImplementationRun>(`${this.base}/runs`, {});
  }

  retry(runId: string): Observable<void> {
    return this.http.post<void>(`${this.base}/runs/${runId}/retry`, {});
  }
}

@Component({
  selector: 'app-implementation',
  standalone: true,
  imports: [CommonModule, RouterModule, MatIconModule, MatButtonModule, MatProgressBarModule, MatTooltipModule, PageHeaderComponent],
  template: `
    <div class="pc-page">
      <app-page-header title="Encryption Implementation"
        subtitle="Add encrypted columns to the database and push the code changes to a new branch, ready to deploy.">
        <button mat-flat-button color="primary" type="button" (click)="start()"
                [disabled]="!readiness()?.ready || running() || busy()">
          <mat-icon>rocket_launch</mat-icon> {{ latest() ? 'Run again' : 'Start Implementation' }}
        </button>
      </app-page-header>

      <mat-progress-bar *ngIf="!loaded()" mode="indeterminate"></mat-progress-bar>

      <!-- What the run will use -->
      <section class="panel" *ngIf="readiness() as r">
        <h2>Inputs</h2>
        <div class="inputs">
          <div class="input">
            <mat-icon>fact_check</mat-icon>
            <div><span class="label">Approved scan</span>
              <a *ngIf="r.approvedScanId; else missing" [routerLink]="['/scans', r.approvedScanId]">{{ r.approvedScanName }}</a>
              <span class="sub" *ngIf="r.approvedScanId">{{ r.approvedColumns }} column{{ r.approvedColumns === 1 ? '' : 's' }} to encrypt</span>
            </div>
          </div>
          <div class="input">
            <mat-icon>vpn_key</mat-icon>
            <div><span class="label">Encryption</span>
              <span *ngIf="r.providerName; else missing">{{ r.providerName }}</span>
              <span class="sub" *ngIf="r.providerName">AES-256-GCM (LOCAL_DEV)</span>
            </div>
          </div>
          <div class="input">
            <mat-icon>code</mat-icon>
            <div><span class="label">Repository</span>
              <span *ngIf="r.repository; else missing">{{ r.repository }}</span>
              <span class="sub" *ngIf="r.baseBranch">new branch from <code>{{ r.baseBranch }}</code></span>
            </div>
          </div>
        </div>
        <ng-template #missing><span class="missing">Missing</span></ng-template>
        <ul class="problems" *ngIf="r.problems.length">
          <li *ngFor="let p of r.problems"><mat-icon>error_outline</mat-icon>{{ p }}</li>
        </ul>
      </section>

      <!-- Latest run -->
      <ng-container *ngIf="latest() as run">
        <section class="panel">
          <div class="panel-head">
            <h2>{{ run.status === 'RUNNING' || run.status === 'PENDING'
              ? (run.parentRunId ? 'Resolving review comments…' : 'Implementing…')
              : run.status === 'WAITING' ? 'Changes requested' : 'Latest run' }} <span class="round-tag">Round {{ run.roundNumber }}</span></h2>
            <span class="pill" [ngClass]="run.status.toLowerCase()">{{ label(run.status) }}</span>
          </div>
          <p class="request" *ngIf="run.parentRunId && run.improvementRequest">
            <mat-icon>chat</mat-icon><span><strong>Review request:</strong> {{ run.improvementRequest }}</span>
          </p>
          <p class="request" *ngIf="run.status === 'WAITING'">
            <mat-icon>info</mat-icon><span>Click <strong>Run again</strong> to start round {{ run.roundNumber }} and resolve these comments on the same pull request.</span>
          </p>

          <ol class="steps">
            <li class="step" [ngClass]="run.dbStatus.toLowerCase()">
              <span class="step-icon"><mat-icon>{{ stepIcon(run.dbStatus) }}</mat-icon></span>
              <div>
                <div class="step-title">1. Add encrypted columns to the database
                  <span class="sub inline" *ngIf="run.dbStatus === 'SKIPPED'">(already added in an earlier round)</span></div>
                <div class="sub">ALTER TABLE … ADD COLUMN &lt;column&gt;_enc for each approved PI column</div>
              </div>
            </li>
            <li class="step" [ngClass]="run.codeStatus.toLowerCase()">
              <span class="step-icon"><mat-icon>{{ stepIcon(run.codeStatus) }}</mat-icon></span>
              <div>
                <div class="step-title">2. Change code, commit and push a new branch</div>
                <div class="sub">Encrypted entity fields with dual write, cipher, key config and a Flyway migration</div>
              </div>
            </li>
          </ol>
          <mat-progress-bar *ngIf="run.status === 'RUNNING' || run.status === 'PENDING'" mode="indeterminate"></mat-progress-bar>

          <div class="banner error" *ngIf="run.errorMessage">
            <mat-icon>error_outline</mat-icon><span>{{ run.errorMessage }}</span>
            <ng-container *ngIf="run.status === 'FAILED' && run.parentRunId">
              <button mat-flat-button color="primary" type="button" (click)="retry(run)" [disabled]="busy()">
                <mat-icon>replay</mat-icon> Retry round
              </button>
              <a mat-stroked-button routerLink="/final-review">Final Review</a>
            </ng-container>
          </div>

          <div class="branch" *ngIf="run.branchName">
            <mat-icon>call_split</mat-icon>
            <div>
              <div><strong>{{ run.branchName }}</strong> <span class="sub">from {{ run.baseBranch }} · commit {{ run.commitSha?.slice(0, 7) }}</span></div>
              <div class="sub">Ready for deployment. Set <code>PI_LOCAL_ENCRYPTION_KEY</code> to the workspace's LOCAL_DEV key in each environment.</div>
            </div>
            <a *ngIf="githubUrl(run)" mat-stroked-button [href]="githubUrl(run)" target="_blank" rel="noopener">
              <mat-icon>open_in_new</mat-icon> View on GitHub
            </a>
          </div>
        </section>

        <div class="grid" *ngIf="run.columns.length || run.changedFiles.length">
          <section class="panel" *ngIf="run.columns.length">
            <h2>Columns</h2>
            <table class="tbl">
              <thead><tr><th>Column</th><th>Encrypted column</th><th>Code</th></tr></thead>
              <tbody>
                <tr *ngFor="let c of run.columns">
                  <td><span class="mono">{{ c.table }}.{{ c.column }}</span><div class="sub">{{ c.database }}</div></td>
                  <td class="mono">{{ c.encryptedColumn }}</td>
                  <td>
                    <span class="code-status" [ngClass]="codeTone(c.codeStatus)" [matTooltip]="c.entityFile ?? ''">
                      {{ codeLabel(c.codeStatus) }}
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>
          </section>

          <section class="panel" *ngIf="run.changedFiles.length">
            <h2>Changed files</h2>
            <ul class="files">
              <li *ngFor="let f of run.changedFiles">
                <span class="kind" [class.added]="f.startsWith('ADDED')">{{ f.startsWith('ADDED') ? 'A' : 'M' }}</span>
                <span class="mono">{{ f.split('|')[1] }}</span>
              </li>
            </ul>
          </section>
        </div>

        <section class="panel">
          <div class="panel-head">
            <h2>Log</h2>
            <button mat-button type="button" (click)="showLog.set(!showLog())">
              <mat-icon>{{ showLog() ? 'expand_less' : 'expand_more' }}</mat-icon> {{ showLog() ? 'Minimize' : 'Show log' }}
            </button>
          </div>
          <pre class="log" *ngIf="showLog()">{{ run.log.join('\\n') }}</pre>
        </section>
      </ng-container>

      <section class="panel" *ngIf="runs().length > 1">
        <h2>Previous runs</h2>
        <ul class="history">
          <li *ngFor="let r of runs().slice(1)">
            <span class="pill" [ngClass]="r.status.toLowerCase()">{{ label(r.status) }}</span>
            <span class="round-tag">Round {{ r.roundNumber }}</span>
            <span>{{ r.createdAt | date:'d MMM y, HH:mm' }}</span>
            <span class="mono sub">{{ r.branchName || '—' }}</span>
            <a *ngIf="githubUrl(r)" class="gh-link" [href]="githubUrl(r)" target="_blank" rel="noopener">
              <mat-icon>open_in_new</mat-icon> View on GitHub
            </a>
          </li>
        </ul>
      </section>
    </div>
  `,
  styles: [`
    .round-tag { margin-left: 6px; padding: 1px 8px; border-radius: 999px; background: #f3f4f6; font-size: 12px; font-weight: 600; color: var(--pc-text-muted); vertical-align: middle; }
    .request { display: flex; gap: 8px; margin: 0 0 12px; padding: 10px 12px; border-radius: 8px; background: #fef3c7; color: #78350f; font-size: 13px; }
    .request mat-icon { font-size: 18px; width: 18px; height: 18px; flex-shrink: 0; }
    .request span { min-width: 0; overflow-wrap: anywhere; }
    .sub.inline { display: inline; font-weight: 400; }
    .gh-link { display: inline-flex; align-items: center; gap: 4px; font-weight: 600; text-decoration: none; }
    .gh-link mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .panel {
      padding: 20px; min-width: 0;
      background: var(--pc-surface); border: 1px solid var(--pc-border);
      border-radius: var(--pc-radius); box-shadow: var(--pc-shadow);
    }
    .panel h2 { margin: 0 0 14px; }
    .panel-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
    .panel-head h2 { margin: 0; }
    .sub { display: block; font-size: 12.5px; color: var(--pc-text-muted); }
    .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12.5px; overflow-wrap: anywhere; }
    code { font-size: 12.5px; background: #f3f4f6; padding: 1px 5px; border-radius: 4px; }

    .inputs { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
    .input { display: flex; gap: 12px; align-items: flex-start; }
    .input > mat-icon { color: var(--pc-primary); }
    .input .label { display: block; font-size: 12px; color: var(--pc-text-subtle); font-weight: 600; text-transform: uppercase; letter-spacing: 0.04em; }
    .missing { color: #b45309; font-weight: 600; }
    .problems { list-style: none; margin: 16px 0 0; padding: 12px 14px; border-radius: var(--pc-radius-sm); background: var(--pc-warning-soft); color: #92400e; }
    .problems li { display: flex; align-items: center; gap: 8px; font-size: 13.5px; }
    .problems li + li { margin-top: 6px; }
    .problems mat-icon { font-size: 18px; width: 18px; height: 18px; }

    .pill { padding: 3px 10px; border-radius: 999px; font-size: 12px; font-weight: 600; background: #f3f4f6; color: #4b5563; }
    .pill.running, .pill.pending, .pill.waiting { background: var(--pc-warning-soft); color: #b45309; }
    .pill.completed { background: var(--pc-success-soft); color: #047857; }
    .pill.failed { background: var(--pc-danger-soft); color: #b91c1c; }

    .steps { list-style: none; margin: 0 0 14px; padding: 0; display: flex; flex-direction: column; gap: 8px; }
    .step { display: flex; gap: 12px; align-items: center; padding: 12px; border: 1px solid var(--pc-border); border-radius: var(--pc-radius-sm); }
    .step-title { font-weight: 600; }
    .step-icon mat-icon { color: var(--pc-text-subtle); }
    .step.running { border-color: var(--pc-primary); background: #faf9ff; }
    .step.running .step-icon mat-icon { color: var(--pc-primary); animation: spin 1.2s linear infinite; }
    .step.completed .step-icon mat-icon { color: var(--pc-success); }
    .step.failed .step-icon mat-icon { color: var(--pc-danger); }
    @keyframes spin { to { transform: rotate(360deg); } }

    .banner { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin-top: 14px; padding: 12px 16px; border-radius: var(--pc-radius-sm); }
    .banner > span { flex: 1; min-width: 240px; overflow-wrap: anywhere; }
    .banner.error { background: var(--pc-danger-soft); color: #991b1b; }
    .branch {
      display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-top: 14px; padding: 14px 16px;
      border-radius: var(--pc-radius-sm); background: var(--pc-success-soft);
    }
    .branch > mat-icon { color: var(--pc-success); }
    .branch > div { flex: 1; min-width: 240px; }

    .grid { display: grid; grid-template-columns: minmax(0, 1.3fr) minmax(0, 1fr); gap: 20px; }
    .tbl { width: 100%; border-collapse: collapse; }
    .tbl th { text-align: left; padding: 8px 10px; font-size: 11.5px; text-transform: uppercase; letter-spacing: 0.04em; color: var(--pc-text-muted); background: #fafbfc; }
    .tbl td { padding: 10px; border-top: 1px solid var(--pc-border); vertical-align: top; }
    .code-status { padding: 2px 8px; border-radius: 999px; font-size: 12px; font-weight: 600; }
    .code-status.ok { background: var(--pc-success-soft); color: #047857; }
    .code-status.warn { background: var(--pc-warning-soft); color: #b45309; }
    .code-status.neutral { background: #f3f4f6; color: #4b5563; }
    .files { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
    .files li { display: flex; gap: 8px; align-items: baseline; }
    .kind { width: 18px; height: 18px; display: grid; place-items: center; border-radius: 4px; font-size: 11px; font-weight: 700; background: #fef3c7; color: #92400e; flex-shrink: 0; }
    .kind.added { background: var(--pc-success-soft); color: #047857; }
    .log { margin: 0; padding: 12px; max-height: 320px; overflow: auto; border-radius: var(--pc-radius-sm); background: #0f172a; color: #e2e8f0; font-size: 12px; line-height: 1.6; white-space: pre-wrap; }
    .history { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
    .history li { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }

    @media (max-width: 960px) { .inputs, .grid { grid-template-columns: 1fr; } }
  `],
})
export class ImplementationComponent implements OnInit, OnDestroy {
  private readonly api = inject(ImplementationApi);
  private readonly notifications = inject(NotificationService);
  private readonly store = inject(Store);
  private pollSub?: Subscription;

  readonly readiness = signal<Readiness | null>(null);
  readonly runs = signal<ImplementationRun[]>([]);
  readonly loaded = signal(false);
  readonly busy = signal(false);
  /** The log is open by default; the user can minimize it. */
  readonly showLog = signal(true);
  readonly latest = computed(() => this.runs()[0] ?? null);
  readonly running = computed(() => ['PENDING', 'RUNNING'].includes(this.latest()?.status ?? ''));

  ngOnInit(): void {
    this.api.readiness().subscribe({ next: (r) => this.readiness.set(r) });
    this.poll();
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
  }

  /** Polls while a run is in progress; stops once it finishes. */
  private poll(): void {
    this.pollSub?.unsubscribe();
    let wasRunning = false;
    this.pollSub = timer(0, POLL_MS).pipe(switchMap(() => this.api.runs())).subscribe({
      next: (runs) => {
        this.runs.set(runs);
        this.loaded.set(true);
        const active = ['PENDING', 'RUNNING'].includes(runs[0]?.status ?? '');
        if (active) wasRunning = true;
        if (!active) {
          this.pollSub?.unsubscribe();
          if (wasRunning) {
            // A completed run advances the lifecycle; refresh the stage chip
            this.store.dispatch(workspaceActions.loadWorkspaces());
            if (runs[0]?.status === 'FAILED') this.showLog.set(true);
          }
        }
      },
      error: () => {
        this.loaded.set(true);
        this.pollSub?.unsubscribe();
      },
    });
  }

  start(): void {
    this.busy.set(true);
    this.api.start().subscribe({
      next: (run) => {
        this.busy.set(false);
        this.notifications.showSuccess(`Round ${run.roundNumber} started`);
        this.poll();
      },
      error: () => this.busy.set(false),
    });
  }

  /** Pull request when one exists, otherwise the branch comparison. */
  githubUrl(r: ImplementationRun): string | null {
    return r.prUrl || r.compareUrl || null;
  }

  retry(r: ImplementationRun): void {
    this.busy.set(true);
    this.api.retry(r.id).subscribe({
      next: () => {
        this.busy.set(false);
        this.notifications.showSuccess(`Round ${r.roundNumber} queued again`);
        this.poll();
      },
      error: () => this.busy.set(false),
    });
  }

  label(status: RunStatus): string {
    return { WAITING: 'Waiting to start', PENDING: 'Queued', RUNNING: 'Running', COMPLETED: 'Completed', FAILED: 'Failed', SKIPPED: 'Skipped' }[status];
  }

  stepIcon(status: RunStatus): string {
    return { WAITING: 'radio_button_unchecked', PENDING: 'radio_button_unchecked', RUNNING: 'autorenew', COMPLETED: 'check_circle', FAILED: 'error', SKIPPED: 'remove_circle_outline' }[status];
  }

  codeLabel(status: string): string {
    if (status.startsWith('MAPPED')) return 'Writes both, reads encrypted';
    if (status.startsWith('UPDATED')) return 'Updated';
    if (status === 'ALREADY_PRESENT') return 'Already encrypted';
    if (status === 'NO_ENTITY') return 'No entity found';
    if (status === 'NO_FIELD') return 'No matching field';
    if (status.startsWith('UNSUPPORTED_TYPE')) return 'Not a text field';
    return status === 'PENDING' ? 'Pending' : status;
  }

  codeTone(status: string): string {
    if (status.startsWith('MAPPED') || status.startsWith('UPDATED') || status === 'ALREADY_PRESENT') return 'ok';
    if (status === 'PENDING') return 'neutral';
    return 'warn';
  }
}
