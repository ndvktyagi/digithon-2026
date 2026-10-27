import { Component, computed, inject, Injectable, OnInit, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatRadioModule } from '@angular/material/radio';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Store } from '@ngrx/store';
import { combineLatest, Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import {
  StageStatus, WorkspaceLifecycle, WorkspaceService, WorkspaceStage,
} from '../../core/api/workspace.service';
import { NotificationService } from '../../core/notifications/notification.service';
import { workspaceActions } from '../../store/workspace/workspace.actions';
import { selectSelectedWorkspaceId, selectWorkspaces } from '../../store/workspace/workspace.selectors';
import { LIFECYCLE_STAGES, StageDefinition, stageIndex } from './lifecycle-stages';

interface StageView extends StageDefinition {
  number: number;
  status: StageStatus | undefined;
}

/**
 * Finalize, after the approved release branch was deployed: the user reports the release as succeeded (clean up,
 * merge, then drop the plain columns) or failed (close the pull request, optionally drop the encrypted columns).
 */
interface FinalizeStatus {
  stage: WorkspaceStage;
  implementationRunId?: string;
  baseBranch?: string;
  releaseBranch?: string;
  releasePrNumber?: number;
  releasePrUrl?: string;
  columns: string[];
  releaseOutcome?: 'SUCCEEDED' | 'FAILED' | null;
  problems: string[];
  canChooseOutcome: boolean;
  canCleanUp: boolean;
  canDropColumns: boolean;
  codeStatus?: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  commitSha?: string;
  mergeStatus?: 'MERGED' | 'FAILED' | 'CLOSED';
  mergeMessage?: string;
  changedFiles: string[];
  warnings: string[];
  columnsStatus?: 'PENDING' | 'DROPPED' | 'KEPT' | 'FAILED';
  droppedColumns: string[];
  errorMessage?: string;
}

@Injectable({ providedIn: 'root' })
export class FinalizeApi {
  private readonly http = inject(HttpClient);

  status(workspaceId: string): Observable<FinalizeStatus> {
    return this.http.get<FinalizeStatus>(`/workspaces/${workspaceId}/finalize`);
  }

  releaseSucceeded(workspaceId: string, acceptQueryWarnings: boolean): Observable<FinalizeStatus> {
    return this.http.post<FinalizeStatus>(`/workspaces/${workspaceId}/finalize/release-succeeded`, { acceptQueryWarnings });
  }

  releaseFailed(workspaceId: string, dropEncryptedColumns: boolean): Observable<FinalizeStatus> {
    return this.http.post<FinalizeStatus>(`/workspaces/${workspaceId}/finalize/release-failed`, { dropEncryptedColumns });
  }

  dropColumns(workspaceId: string, deployed: boolean): Observable<FinalizeStatus> {
    return this.http.post<FinalizeStatus>(`/workspaces/${workspaceId}/finalize/columns`, { deployed });
  }
}

@Component({
  selector: 'app-workspace-lifecycle',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterModule, MatIconModule, MatButtonModule,
    MatCheckboxModule, MatRadioModule, MatProgressBarModule, PageHeaderComponent,
  ],
  template: `
    <div class="pc-page">
      <app-page-header title="Workspace Lifecycle"
        [subtitle]="(workspaceName() || 'This workspace') + ' · from connecting sources to encrypted data'">
        <a mat-stroked-button routerLink="/workspaces"><mat-icon>workspaces</mat-icon> All Workspaces</a>
      </app-page-header>

      <mat-progress-bar *ngIf="loading()" mode="indeterminate"></mat-progress-bar>

      <ng-container *ngIf="lifecycle() as lc">
        <section class="overview">
          <div class="overview-text">
            <span class="overview-label">Progress</span>
            <span class="overview-value">
              {{ completedCount() }} of {{ stages().length }} stages complete
            </span>
          </div>
          <ol class="track" aria-label="Lifecycle stages">
            <li *ngFor="let s of stages()" [ngClass]="stateClass(s)"
                [attr.aria-current]="s.status?.state === 'CURRENT' ? 'step' : null"
                [title]="s.title">
              <span class="track-dot">
                <mat-icon *ngIf="s.status?.state === 'DONE'">check</mat-icon>
                <ng-container *ngIf="s.status?.state !== 'DONE'">{{ s.number }}</ng-container>
              </span>
              <span class="track-label">{{ s.title }}</span>
            </li>
          </ol>
        </section>

        <div class="rejected-banner" *ngIf="lc.currentStage === 'REJECTED'">
          <mat-icon>block</mat-icon>
          <div>
            <strong>Workflow ended: rejected in Final Review.</strong>
            The review history and pull request are kept in Final Review. Reopen an earlier stage to start again.
          </div>
          <a mat-stroked-button routerLink="/final-review" class="banner-action">View review history</a>
        </div>

        <div class="complete-banner" *ngIf="lc.currentStage === 'COMPLETED'">
          <mat-icon>verified_user</mat-icon>
          <div>
            <strong>Lifecycle complete.</strong>
            Personal data in this workspace is encrypted and the plain-text columns have been retired.
          </div>
          <a mat-stroked-button routerLink="/monitoring" class="banner-action">
            <mat-icon>monitor_heart</mat-icon> Set up monitoring
          </a>
        </div>

        <section class="stages">
          <article *ngFor="let s of stages()" class="stage" [id]="s.stage"
                   [class.highlight]="fragment() === s.stage" [ngClass]="stateClass(s)">
            <div class="stage-icon"><mat-icon>{{ s.icon }}</mat-icon></div>

            <div class="stage-body">
              <div class="stage-head">
                <h3>{{ s.number }}. {{ s.title }}</h3>
                <span class="state-pill" [ngClass]="stateClass(s)">
                  {{ stateLabel(stateClass(s)) }}
                </span>
              </div>
              <p class="stage-desc" *ngIf="!finalizing(s)">{{ s.description }}</p>

              <div class="stage-detail" *ngIf="!finalizing(s) && s.status as st" [class.ready]="st.ready">
                <mat-icon>{{ st.ready ? 'check_circle' : 'radio_button_unchecked' }}</mat-icon>
                <span>{{ st.detail }}</span>
              </div>

              <ng-container *ngIf="s.status?.state === 'CURRENT' && s.stage === 'FINALIZE'">
                <ng-container *ngIf="finalize() as f">
                  <ul class="finalize-problems" *ngIf="f.problems.length">
                    <li *ngFor="let p of f.problems"><mat-icon>error_outline</mat-icon>{{ p }}</li>
                  </ul>

                  <p class="step-desc" *ngIf="f.releaseBranch">
                    Release <code>{{ f.releaseBranch }}</code>
                    <a *ngIf="f.releasePrUrl" [href]="f.releasePrUrl" target="_blank" rel="noopener">(pull request #{{ f.releasePrNumber }})</a>
                    into <code>{{ f.baseBranch }}</code>. Deploy it, then report how the release went.
                  </p>

                  <mat-radio-group class="outcome-grid" [ngModel]="selectedOutcome(f)" (ngModelChange)="outcome = $event"
                                   [disabled]="outcomeLocked(f)" aria-label="How did the release go?">
                    <div class="outcome" [class.selected]="selectedOutcome(f) === 'SUCCEEDED'" [class.locked]="outcomeLocked(f)">
                      <mat-radio-button value="SUCCEEDED" class="outcome-title">Release succeeded</mat-radio-button>
                      <p class="step-desc">
                        Brings every encrypted copy up to date and makes the plain columns nullable, pushes a cleanup to the
                        release branch (fields map only to their <code>_enc</code> columns, native queries read only them, and a
                        migration drops the plain columns), then merges the pull request into <code>{{ f.baseBranch }}</code>.
                      </p>
                      <div class="step-warnings" *ngIf="f.warnings.length">
                        <strong>Queries on encrypted fields:</strong> they compare or sort by values that will only exist
                        encrypted, so they can no longer match. Change them on the release branch, or continue anyway.
                        <ul><li *ngFor="let w of f.warnings">{{ w }}</li></ul>
                        <mat-checkbox [(ngModel)]="acceptQueryWarnings"
                                      [disabled]="outcomeLocked(f) || selectedOutcome(f) !== 'SUCCEEDED'">Continue anyway</mat-checkbox>
                      </div>
                      <p class="step-error" *ngIf="f.codeStatus === 'FAILED' && f.errorMessage">{{ f.errorMessage }}</p>
                    </div>

                    <div class="outcome" [class.selected]="selectedOutcome(f) === 'FAILED'" [class.locked]="outcomeLocked(f)">
                      <mat-radio-button value="FAILED" class="outcome-title">Release failed</mat-radio-button>
                      <p class="step-desc">
                        Closes the pull request without merging, so <code>{{ f.baseBranch }}</code> stays the version to redeploy,
                        and removes PI Co-Pilot's migrations from the Flyway history so it starts. The plain columns are untouched.
                      </p>
                      <mat-checkbox class="finalize-confirm" [(ngModel)]="dropEncrypted"
                                    [disabled]="outcomeLocked(f) || selectedOutcome(f) !== 'FAILED'">
                        Also drop the encrypted columns ({{ encryptedColumns(f) }}). This cannot be undone.
                      </mat-checkbox>
                    </div>
                  </mat-radio-group>

                  <div class="stage-actions" *ngIf="!outcomeLocked(f) || busy()">
                    <button mat-flat-button type="button" [color]="selectedOutcome(f) === 'FAILED' ? 'warn' : 'primary'"
                            [disabled]="!canSubmitOutcome(f)" (click)="submitOutcome()">
                      <mat-icon>{{ selectedOutcome(f) === 'FAILED' ? 'undo' : 'task_alt' }}</mat-icon>
                      {{ selectedOutcome(f) === 'FAILED' ? 'Confirm release failed'
                        : selectedOutcome(f) === 'SUCCEEDED' ? 'Confirm release succeeded' : 'Choose how the release went' }}
                    </button>
                  </div>

                  <ol class="finalize-steps" *ngIf="!choosing(f)">
                    <li [class.done]="codeMerged(f)">
                      <div class="step-title">
                        <mat-icon>{{ codeMerged(f) ? 'check_circle' : 'looks_one' }}</mat-icon>
                        Clean up and merge the release
                      </div>
                      <div class="step-result">
                        <span *ngIf="f.commitSha">Cleanup <code>{{ f.commitSha.substring(0, 7) }}</code></span>
                        <span class="pill" [class.ok]="f.mergeStatus === 'MERGED'" [class.bad]="f.mergeStatus === 'FAILED'">
                          {{ f.mergeStatus === 'MERGED' ? 'Merged into ' + f.baseBranch : f.mergeStatus === 'FAILED' ? 'Not merged' : 'Pushed' }}
                        </span>
                      </div>
                      <p class="step-error" *ngIf="f.mergeStatus === 'FAILED' && f.mergeMessage">{{ f.mergeMessage }}</p>
                      <details *ngIf="f.changedFiles.length">
                        <summary>{{ f.changedFiles.length }} file(s) changed</summary>
                        <ul class="file-list">
                          <li *ngFor="let c of f.changedFiles">
                            <span class="kind">{{ c.split('|')[0] }}</span> <code>{{ c.split('|')[1] }}</code>
                          </li>
                        </ul>
                      </details>
                      <div class="stage-actions" *ngIf="!codeMerged(f)">
                        <button mat-flat-button color="primary" type="button" [disabled]="busy()" (click)="releaseSucceeded()">
                          <mat-icon>merge</mat-icon> Retry merge
                        </button>
                      </div>
                    </li>

                    <li [class.upcoming]="!codeMerged(f)">
                      <div class="step-title"><mat-icon>looks_two</mat-icon> Drop the plain-text columns</div>
                      <p class="step-desc">
                        Deploy the merged code first. The tables are locked against writes, every encrypted copy is checked once
                        more against its plain value, then these columns are dropped. This cannot be undone.
                      </p>
                      <div class="column-chips">
                        <code *ngFor="let c of f.columns">{{ c }}</code>
                      </div>
                      <p class="step-error" *ngIf="f.columnsStatus === 'FAILED' && f.errorMessage">{{ f.errorMessage }}</p>
                      <mat-checkbox class="finalize-confirm" [(ngModel)]="finalizeConfirmed" [disabled]="!f.canDropColumns">
                        The merged application is deployed and no longer uses the plain-text columns.
                      </mat-checkbox>
                      <div class="stage-actions">
                        <a *ngIf="s.route" mat-stroked-button [routerLink]="s.route">{{ s.actionLabel }}</a>
                        <button mat-flat-button color="warn" type="button"
                                [disabled]="busy() || !f.canDropColumns || !finalizeConfirmed" (click)="dropColumns()">
                          <mat-icon>delete_forever</mat-icon> Drop columns &amp; finalize
                        </button>
                      </div>
                    </li>
                  </ol>
                </ng-container>
              </ng-container>

              <ng-container *ngIf="s.status?.state === 'CURRENT' && s.stage !== 'FINALIZE'">
                <div class="stage-actions">
                  <a *ngIf="s.route" mat-stroked-button [routerLink]="s.route">{{ s.actionLabel }}</a>
                  <button mat-flat-button color="primary" type="button"
                          [disabled]="!s.status?.ready || busy()" (click)="advance()">
                    <mat-icon>arrow_forward</mat-icon> Complete &amp; Continue
                  </button>
                </div>
                <p class="hint" *ngIf="!s.status?.ready">
                  <ng-container *ngIf="s.route">Use "{{ s.actionLabel }}" to finish this stage, then continue.</ng-container>
                  <ng-container *ngIf="!s.route">This stage runs as part of the implementation phase.</ng-container>
                </p>
              </ng-container>

              <div class="stage-actions" *ngIf="s.status?.state === 'DONE'">
                <a *ngIf="s.route" mat-button [routerLink]="s.route">{{ s.actionLabel }}</a>
                <button mat-button type="button" [disabled]="busy()" (click)="reopen(s.stage)">
                  <mat-icon>undo</mat-icon> Reopen
                </button>
              </div>
            </div>
          </article>
        </section>
      </ng-container>
    </div>
  `,
  styles: [`
    .overview {
      display: flex; flex-direction: column; gap: 16px; padding: 20px;
      background: var(--pc-surface); border: 1px solid var(--pc-border);
      border-radius: var(--pc-radius); box-shadow: var(--pc-shadow);
    }
    .overview-text { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
    .overview-label { font-size: 12px; font-weight: 600; letter-spacing: 0.06em; text-transform: uppercase; color: var(--pc-text-subtle); }
    .overview-value { font-weight: 600; }

    .track { display: flex; margin: 0; padding: 0; list-style: none; }
    .track li {
      position: relative; flex: 1; min-width: 0;
      display: flex; flex-direction: column; align-items: center; gap: 8px; text-align: center;
    }
    .track li:not(:last-child)::after {
      content: ''; position: absolute; top: 15px; left: calc(50% + 20px); right: calc(-50% + 20px);
      height: 2px; background: var(--pc-border);
    }
    .track li.done:not(:last-child)::after { background: var(--pc-primary); }
    .track-dot {
      display: grid; place-items: center; width: 32px; height: 32px; border-radius: 50%;
      border: 2px solid var(--pc-border); background: var(--pc-surface);
      font-size: 13px; font-weight: 600; color: var(--pc-text-muted);
    }
    .track-dot mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .track li.done .track-dot { background: var(--pc-primary); border-color: var(--pc-primary); color: #fff; }
    .track li.current .track-dot { border-color: var(--pc-primary); color: var(--pc-primary); box-shadow: 0 0 0 4px var(--pc-primary-soft); }
    .track-label { font-size: 12px; color: var(--pc-text-muted); line-height: 1.3; padding: 0 4px; }
    .track li.current .track-label { color: var(--pc-text); font-weight: 600; }

    .complete-banner {
      display: flex; align-items: center; gap: 12px; padding: 16px 20px;
      border-radius: var(--pc-radius); background: var(--pc-success-soft); color: #065f46;
    }
    .complete-banner mat-icon { color: var(--pc-success); }
    .rejected-banner {
      display: flex; align-items: center; gap: 12px; padding: 16px 20px;
      border-radius: var(--pc-radius); background: var(--pc-danger-soft); color: #991b1b;
    }
    .rejected-banner > div { flex: 1; }
    .state-pill.rejected { background: var(--pc-danger-soft); color: #b91c1c; }
    .stage.rejected { border-color: var(--pc-danger); }
    .track li.rejected .track-dot, .track li.na .track-dot { background: var(--pc-danger); border-color: var(--pc-danger); color: #fff; }
    .track li.rejected:not(:last-child)::after, .track li.na:not(:last-child)::after { background: var(--pc-danger); }
    .track li.rejected .track-label, .track li.na .track-label { color: #b91c1c; }
    .state-pill.na { background: var(--pc-danger-soft); color: #b91c1c; }
    .stage.na { border-color: #fca5a5; }
    .stage.na .stage-icon { background: var(--pc-danger-soft); color: #b91c1c; }
    .complete-banner > div { flex: 1; }
    .banner-action { flex-shrink: 0; }
    .stage { scroll-margin-top: calc(var(--pc-header-height) + 16px); }
    .stage.highlight { animation: flash 1.6s ease-out 1; }
    @keyframes flash { 0% { box-shadow: 0 0 0 4px var(--pc-primary); } 100% { box-shadow: var(--pc-shadow); } }

    .stages { display: flex; flex-direction: column; gap: 12px; }
    .stage {
      display: flex; gap: 16px; padding: 20px;
      background: var(--pc-surface); border: 1px solid var(--pc-border);
      border-radius: var(--pc-radius); box-shadow: var(--pc-shadow);
    }
    .stage.current { border-color: var(--pc-primary); box-shadow: 0 0 0 3px var(--pc-primary-soft); }
    .stage.upcoming { opacity: 0.7; }
    .stage-icon {
      display: grid; place-items: center; width: 44px; height: 44px; flex-shrink: 0;
      border-radius: 12px; background: #f3f4f6; color: var(--pc-text-muted);
    }
    .stage.current .stage-icon { background: var(--pc-primary); color: #fff; }
    .stage.done .stage-icon { background: var(--pc-primary-soft); color: var(--pc-primary); }
    .stage-body { flex: 1; min-width: 0; }
    .stage-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
    .stage-head h3 { margin: 0; }
    .stage-desc { margin: 4px 0 0; color: var(--pc-text-muted); font-size: 13.5px; }
    .state-pill {
      padding: 3px 10px; border-radius: 999px; font-size: 12px; font-weight: 600;
      background: #f3f4f6; color: #4b5563;
    }
    .state-pill.done { background: var(--pc-success-soft); color: #047857; }
    .state-pill.current { background: var(--pc-primary-soft); color: var(--pc-primary-600); }

    .stage-detail {
      display: inline-flex; align-items: center; gap: 6px; margin-top: 12px;
      font-size: 13px; color: var(--pc-text-muted);
    }
    .stage-detail mat-icon { font-size: 18px; width: 18px; height: 18px; color: var(--pc-text-subtle); }
    .stage-detail.ready mat-icon { color: var(--pc-success); }
    .finalize-confirm { display: block; margin-top: 12px; }
    .finalize-problems { margin: 12px 0 0; padding: 0; list-style: none; font-size: 13px; color: #b91c1c; }
    .finalize-problems li { display: flex; align-items: flex-start; gap: 6px; margin-top: 4px; }
    .finalize-problems mat-icon { font-size: 18px; width: 18px; height: 18px; flex-shrink: 0; }
    .outcome-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 14px; }
    .outcome {
      padding: 14px 16px; border: 1px solid var(--pc-border); border-radius: var(--pc-radius);
      transition: border-color 0.15s, box-shadow 0.15s;
    }
    .outcome.selected { border-color: var(--pc-primary); box-shadow: 0 0 0 3px var(--pc-primary-soft); }
    .outcome.locked:not(.selected) { opacity: 0.55; }
    .outcome-title { font-weight: 600; margin-left: -8px; }
    @media (max-width: 900px) { .outcome-grid { grid-template-columns: 1fr; } }
    .finalize-steps { margin: 14px 0 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 12px; }
    .finalize-steps > li { padding: 14px 16px; border: 1px solid var(--pc-border); border-radius: var(--pc-radius); }
    .finalize-steps > li.done { border-color: var(--pc-success); background: var(--pc-success-soft); }
    .finalize-steps > li.upcoming { opacity: 0.75; }
    .step-title { display: flex; align-items: center; gap: 8px; font-weight: 600; }
    .step-title mat-icon { color: var(--pc-primary); }
    .finalize-steps > li.done .step-title mat-icon { color: var(--pc-success); }
    .step-desc { margin: 6px 0 0; font-size: 13px; color: var(--pc-text-muted); }
    .step-result { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-top: 10px; font-size: 13px; }
    .pill { padding: 2px 8px; border-radius: 999px; font-size: 12px; font-weight: 600; background: #f3f4f6; color: #4b5563; }
    .pill.ok { background: var(--pc-success-soft); color: #047857; }
    .pill.bad { background: var(--pc-danger-soft); color: #b91c1c; }
    .step-error { margin: 8px 0 0; font-size: 13px; color: #b91c1c; }
    .step-warnings { margin-top: 10px; padding: 10px 12px; border-radius: 8px; background: #fffbeb; color: #92400e; font-size: 13px; }
    .step-warnings ul { margin: 6px 0 0; padding-left: 18px; }
    details { margin-top: 10px; font-size: 13px; }
    .file-list { margin: 6px 0 0; padding-left: 18px; }
    .file-list .kind { display: inline-block; min-width: 72px; font-size: 11px; font-weight: 600; color: var(--pc-text-subtle); }
    .column-chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
    .column-chips code { padding: 2px 8px; border-radius: 6px; background: #f3f4f6; font-size: 12px; }
    code { word-break: break-all; }
    .stage-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 14px; }
    .hint { margin: 8px 0 0; font-size: 12.5px; color: var(--pc-text-subtle); }

    @media (max-width: 720px) {
      .track-label { display: none; }
      .stage { flex-direction: column; gap: 12px; }
    }
  `],
})
export class WorkspaceLifecycleComponent implements OnInit {
  private readonly store = inject(Store);
  private readonly workspaceService = inject(WorkspaceService);
  private readonly notifications = inject(NotificationService);
  private readonly route = inject(ActivatedRoute);
  private readonly finalizeApi = inject(FinalizeApi);

  /** Stage card targeted from the sidebar, e.g. /lifecycle#DATA_SYNC. */
  readonly fragment = toSignal(this.route.fragment, { initialValue: null });

  private readonly workspaceId = sessionStorage.getItem('workspace_id') ?? '';

  readonly lifecycle = signal<WorkspaceLifecycle | null>(null);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly finalize = signal<FinalizeStatus | null>(null);
  finalizeConfirmed = false;
  acceptQueryWarnings = false;
  dropEncrypted = false;
  outcome: 'SUCCEEDED' | 'FAILED' | null = null;

  readonly workspaceName = toSignal(
    combineLatest([
      this.store.select(selectWorkspaces),
      this.store.select(selectSelectedWorkspaceId),
    ]).pipe(map(([workspaces, id]) => workspaces.find((w) => w.id === (id ?? this.workspaceId))?.name)),
  );

  readonly stages = computed<StageView[]>(() => {
    const statuses = this.lifecycle()?.stages ?? [];
    return LIFECYCLE_STAGES.map((def, i) => ({
      ...def,
      number: i + 1,
      status: statuses.find((s) => s.stage === def.stage),
    }));
  });

  readonly completedCount = computed(() => stageIndex(this.lifecycle()?.currentStage));

  ngOnInit(): void {
    this.store.dispatch(workspaceActions.loadWorkspaces());
    this.load();
    // Same-page sidebar clicks only change the fragment, so scroll on every change
    this.route.fragment.subscribe(() => this.scrollToFragment());
  }

  private scrollToFragment(): void {
    const id = this.fragment();
    if (id) setTimeout(() => document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
  }

  load(): void {
    this.loading.set(true);
    this.workspaceService.getLifecycle(this.workspaceId).subscribe({
      next: (lc) => {
        this.lifecycle.set(lc);
        this.loading.set(false);
        this.scrollToFragment();
        if (lc.currentStage === 'FINALIZE') this.loadFinalize();
      },
      error: () => this.loading.set(false),
    });
  }

  private loadFinalize(): void {
    this.finalizeApi.status(this.workspaceId).subscribe((f) => this.finalize.set(f));
  }

  codeMerged(f: FinalizeStatus): boolean {
    return f.codeStatus === 'COMPLETED' && f.mergeStatus === 'MERGED';
  }

  /** The release outcome is still to be reported (or the cleanup has to be run again). */
  choosing(f: FinalizeStatus): boolean {
    return !f.releaseOutcome || (f.releaseOutcome === 'SUCCEEDED' && f.codeStatus !== 'COMPLETED');
  }

  /** The Finalize card while it is the current stage: its outcome choice replaces the description and status line. */
  finalizing(s: StageView): boolean {
    return s.stage === 'FINALIZE' && s.status?.state === 'CURRENT';
  }

  /** Once an outcome is recorded (and its cleanup went through) the choice can no longer change. */
  outcomeLocked(f: FinalizeStatus): boolean {
    return this.busy() || (!!f.releaseOutcome && !this.choosing(f));
  }

  selectedOutcome(f: FinalizeStatus): 'SUCCEEDED' | 'FAILED' | null {
    if (this.outcomeLocked(f) && f.releaseOutcome) return f.releaseOutcome;
    return this.outcome ?? f.releaseOutcome ?? null;
  }

  canSubmitOutcome(f: FinalizeStatus): boolean {
    if (this.busy()) return false;
    switch (this.selectedOutcome(f)) {
      case 'SUCCEEDED': return f.canCleanUp && (f.warnings.length === 0 || this.acceptQueryWarnings);
      case 'FAILED': return f.canChooseOutcome;
      default: return false;
    }
  }

  submitOutcome(): void {
    const f = this.finalize();
    if (!f) return;
    if (this.selectedOutcome(f) === 'SUCCEEDED') this.releaseSucceeded();
    else if (this.selectedOutcome(f) === 'FAILED') this.releaseFailed();
  }

  encryptedColumns(f: FinalizeStatus): string {
    return f.columns.map((c) => c.split('.').pop() + '_enc').join(', ');
  }

  advance(): void {
    this.busy.set(true);
    this.workspaceService.advanceStage(this.workspaceId).subscribe({
      next: (lc) => this.applyUpdate(lc, 'Stage completed'),
      error: () => this.busy.set(false),
    });
  }

  /** Release succeeded: clean up and merge the release (or retry its merge). */
  releaseSucceeded(): void {
    this.busy.set(true);
    this.finalizeApi.releaseSucceeded(this.workspaceId, this.acceptQueryWarnings).subscribe({
      next: (f) => {
        this.finalize.set(f);
        this.busy.set(false);
        if (this.codeMerged(f)) this.notifications.showSuccess(`Release merged into ${f.baseBranch}`);
        else this.notifications.showError(f.mergeMessage || 'The cleanup was pushed but could not be merged');
        this.load();
      },
      // The failure (or the queries waiting for confirmation) is recorded on the run; show it
      error: () => { this.busy.set(false); this.loadFinalize(); },
    });
  }

  /** Release failed: close the pull request, optionally drop the encrypted columns; completes the lifecycle. */
  releaseFailed(): void {
    this.busy.set(true);
    this.finalizeApi.releaseFailed(this.workspaceId, this.dropEncrypted).subscribe({
      next: (f) => {
        this.finalize.set(f);
        this.workspaceService.getLifecycle(this.workspaceId).subscribe((lc) =>
          this.applyUpdate(lc, `Rolled back: redeploy ${f.baseBranch}`));
      },
      error: () => { this.busy.set(false); this.loadFinalize(); },
    });
  }

  /** Finalize step 2: drops the plain columns, which completes the lifecycle. */
  dropColumns(): void {
    this.busy.set(true);
    this.finalizeApi.dropColumns(this.workspaceId, this.finalizeConfirmed).subscribe({
      next: (f) => {
        this.finalize.set(f);
        this.workspaceService.getLifecycle(this.workspaceId).subscribe((lc) => this.applyUpdate(lc, 'Workspace finalized'));
      },
      error: () => { this.busy.set(false); this.loadFinalize(); },
    });
  }

  reopen(stage: WorkspaceStage): void {
    this.busy.set(true);
    this.workspaceService.reopenStage(this.workspaceId, stage).subscribe({
      next: (lc) => this.applyUpdate(lc, 'Stage reopened'),
      error: () => this.busy.set(false),
    });
  }

  /** CSS class / display state: stages after a rejection in Final Review will not run, so they show as N/A. */
  stateClass(s: StageView): string {
    const state = s.status?.state;
    if (this.lifecycle()?.currentStage === 'REJECTED' && state === 'UPCOMING') return 'na';
    return state ? state.toLowerCase() : 'upcoming';
  }

  stateLabel(state: string): string {
    switch (state) {
      case 'rejected': return 'Rejected';
      case 'na': return 'N/A';
      case 'done': return 'Completed';
      case 'current': return 'In progress';
      default: return 'Upcoming';
    }
  }

  private applyUpdate(lc: WorkspaceLifecycle, message: string): void {
    this.lifecycle.set(lc);
    this.busy.set(false);
    this.finalizeConfirmed = false;
    this.notifications.showSuccess(message);
    // Refresh summaries so the workspace list shows the new stage
    this.store.dispatch(workspaceActions.loadWorkspaces());
  }
}
