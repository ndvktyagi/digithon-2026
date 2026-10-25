import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Store } from '@ngrx/store';
import { combineLatest } from 'rxjs';
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

@Component({
  selector: 'app-workspace-lifecycle',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterModule, MatIconModule, MatButtonModule,
    MatCheckboxModule, MatProgressBarModule, PageHeaderComponent,
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
              <p class="stage-desc">{{ s.description }}</p>

              <div class="stage-detail" *ngIf="s.status as st" [class.ready]="st.ready">
                <mat-icon>{{ st.ready ? 'check_circle' : 'radio_button_unchecked' }}</mat-icon>
                <span>{{ st.detail }}</span>
              </div>

              <ng-container *ngIf="s.status?.state === 'CURRENT'">
                <mat-checkbox *ngIf="s.stage === 'FINALIZE'" class="finalize-confirm" [(ngModel)]="finalizeConfirmed">
                  I confirm the duplicate code paths and plain-text database columns have been removed.
                </mat-checkbox>
                <div class="stage-actions">
                  <a *ngIf="s.route" mat-stroked-button [routerLink]="s.route">{{ s.actionLabel }}</a>
                  <button mat-flat-button color="primary" type="button"
                          [disabled]="!canAdvance(s) || busy()" (click)="advance()">
                    <mat-icon>{{ s.stage === 'FINALIZE' ? 'task_alt' : 'arrow_forward' }}</mat-icon>
                    {{ s.stage === 'FINALIZE' ? 'Finalize Workspace' : 'Complete & Continue' }}
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

  /** Stage card targeted from the sidebar, e.g. /lifecycle#DATA_SYNC. */
  readonly fragment = toSignal(this.route.fragment, { initialValue: null });

  private readonly workspaceId = sessionStorage.getItem('workspace_id') ?? '';

  readonly lifecycle = signal<WorkspaceLifecycle | null>(null);
  readonly loading = signal(false);
  readonly busy = signal(false);
  finalizeConfirmed = false;

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
      },
      error: () => this.loading.set(false),
    });
  }

  canAdvance(s: StageView): boolean {
    if (!s.status?.ready) return false;
    return s.stage !== 'FINALIZE' || this.finalizeConfirmed;
  }

  advance(): void {
    this.busy.set(true);
    this.workspaceService.advanceStage(this.workspaceId, this.finalizeConfirmed).subscribe({
      next: (lc) => this.applyUpdate(lc, lc.currentStage === 'COMPLETED'
        ? 'Workspace finalized'
        : 'Stage completed'),
      error: () => this.busy.set(false),
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
