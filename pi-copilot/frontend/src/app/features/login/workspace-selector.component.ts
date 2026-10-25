import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { ActivatedRoute, Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { workspaceActions } from '../../store/workspace/workspace.actions';
import { selectSelectedWorkspaceId, selectWorkspaces } from '../../store/workspace/workspace.selectors';
import { authActions } from '../../store/auth/auth.actions';
import { WorkspaceService } from '../../core/api/workspace.service';
import { NotificationService } from '../../core/notifications/notification.service';
import { WorkspaceSummary } from '../../core/auth/auth-state.model';
import {
  LIFECYCLE_STAGES, stageIndex, stageTitle, workspaceStatus, WorkspaceStatus,
} from '../lifecycle/lifecycle-stages';

const STATUS_TABS: { status: WorkspaceStatus; label: string; icon: string }[] = [
  { status: 'IN_PROGRESS', label: 'In Progress', icon: 'pending' },
  { status: 'COMPLETED', label: 'Completed', icon: 'verified' },
  { status: 'REJECTED', label: 'Rejected', icon: 'block' },
  { status: 'ARCHIVED', label: 'Archived', icon: 'archive' },
];

const SLUG_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

@Component({
  selector: 'app-workspace-selector',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule, MatCardModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatMenuModule, MatProgressBarModule, PageHeaderComponent,
  ],
  template: `
    <div class="ws-container" [class.embedded]="embedded">
      <div class="ws-panel">
        <app-page-header *ngIf="embedded; else standaloneHead" title="Workspaces"
          subtitle="Each workspace takes one application from discovery to encrypted data.">
          <button mat-flat-button color="primary" type="button" *ngIf="!showCreate()" (click)="openCreate()">
            <mat-icon>add</mat-icon> New Workspace
          </button>
        </app-page-header>
        <ng-template #standaloneHead>
          <div class="ws-head">
            <div>
              <h1>Workspaces</h1>
              <p class="pc-muted">Each workspace takes one application from discovery to encrypted data.</p>
            </div>
            <div class="head-actions">
              <button mat-button type="button" (click)="logout()"><mat-icon>logout</mat-icon> Sign Out</button>
              <button mat-flat-button color="primary" type="button" *ngIf="!showCreate()" (click)="openCreate()">
                <mat-icon>add</mat-icon> New Workspace
              </button>
            </div>
          </div>
        </ng-template>

        <mat-card class="create-card" *ngIf="showCreate()">
          <form [formGroup]="form" (ngSubmit)="create()">
            <h2>Create workspace</h2>
            <div class="form-grid">
              <mat-form-field appearance="outline">
                <mat-label>Name</mat-label>
                <input matInput formControlName="name" maxlength="100" (input)="syncSlug()" autofocus />
                <mat-error>Name is required</mat-error>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Slug</mat-label>
                <input matInput formControlName="slug" maxlength="50" (input)="slugEdited = true" />
                <mat-hint>Lowercase letters, numbers and hyphens</mat-hint>
                <mat-error>Use lowercase letters, numbers and single hyphens</mat-error>
              </mat-form-field>
              <mat-form-field appearance="outline" class="full">
                <mat-label>Description (optional)</mat-label>
                <textarea matInput formControlName="description" maxlength="500" rows="2"></textarea>
              </mat-form-field>
            </div>
            <div class="form-actions">
              <button mat-button type="button" (click)="cancelCreate()" [disabled]="saving()">Cancel</button>
              <button mat-flat-button color="primary" type="submit" [disabled]="form.invalid || saving()">
                {{ saving() ? 'Creating…' : 'Create and Start' }}
              </button>
            </div>
          </form>
        </mat-card>

        <div class="status-tabs" role="tablist" aria-label="Filter workspaces by status">
          <button *ngFor="let tab of statusTabs" type="button" role="tab" class="status-tab"
                  [ngClass]="tab.status" [class.active]="filter() === tab.status"
                  [attr.aria-selected]="filter() === tab.status" (click)="filter.set(tab.status)">
            <span class="tab-icon"><mat-icon>{{ tab.icon }}</mat-icon></span>
            <span class="tab-text">
              <span class="tab-label">{{ tab.label }}</span>
              <span class="tab-count">{{ counts()[tab.status] }}</span>
            </span>
          </button>
        </div>

        <div class="ws-list">
          <div *ngFor="let ws of visible()" class="ws-item" role="button" tabindex="0"
               [ngClass]="statusOf(ws)"
               [class.current]="ws.id === (selectedId$ | async)"
               (click)="selectWorkspace(ws, false)" (keydown.enter)="selectWorkspace(ws, false)">
            <span class="ws-icon"><mat-icon>{{ statusIcon(ws) }}</mat-icon></span>
            <div class="ws-main">
              <div class="ws-title">
                <span class="ws-name">{{ ws.name }}</span>
                <span class="current-chip" *ngIf="ws.id === (selectedId$ | async)">Current</span>
                <span class="stage-chip" [ngClass]="statusOf(ws)">
                  <mat-icon *ngIf="statusOf(ws) !== 'IN_PROGRESS'">{{ statusIcon(ws) }}</mat-icon>
                  {{ stageLabel(ws) }}
                </span>
              </div>
              <div class="ws-sub">{{ statusNote(ws) || ws.description || ws.slug }}</div>
              <mat-progress-bar mode="determinate" [value]="progress(ws)" [ngClass]="statusOf(ws)"></mat-progress-bar>
            </div>

            <ng-container *ngIf="confirmArchiveId() === ws.id; else rowActions">
              <div class="confirm" (click)="$event.stopPropagation()">
                <span>Archive?</span>
                <button mat-button type="button" (click)="confirmArchiveId.set(null)">Cancel</button>
                <button mat-flat-button color="warn" type="button" (click)="archive(ws)">Archive</button>
              </div>
            </ng-container>
            <ng-template #rowActions>
              <button *ngIf="statusOf(ws) === 'ARCHIVED'" mat-stroked-button type="button" class="lifecycle-btn"
                      (click)="$event.stopPropagation(); restore(ws)">
                <mat-icon>unarchive</mat-icon> Restore
              </button>
              <ng-container *ngIf="statusOf(ws) !== 'ARCHIVED'">
                <button mat-stroked-button type="button" class="lifecycle-btn"
                        (click)="$event.stopPropagation(); selectWorkspace(ws, true)">
                  Lifecycle
                </button>
                <button mat-icon-button type="button" [matMenuTriggerFor]="menu" aria-label="Workspace actions"
                        (click)="$event.stopPropagation()">
                  <mat-icon>more_vert</mat-icon>
                </button>
                <mat-menu #menu="matMenu" xPosition="before">
                  <button mat-menu-item (click)="confirmArchiveId.set(ws.id)">
                    <mat-icon>archive</mat-icon> Archive workspace
                  </button>
                </mat-menu>
              </ng-container>
            </ng-template>
          </div>

          <div *ngIf="loaded() && all().length === 0 && !showCreate()" class="empty-hint">
            <mat-icon>workspaces</mat-icon>
            <p>No workspaces yet. Create one to connect an application and start protecting its data.</p>
            <button mat-flat-button color="primary" type="button" (click)="openCreate()">
              <mat-icon>add</mat-icon> New Workspace
            </button>
          </div>
          <div *ngIf="loaded() && all().length > 0 && visible().length === 0" class="empty-hint small">
            <p>No {{ filterLabel() }} workspaces.</p>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .ws-container {
      display: flex; justify-content: center;
      min-height: 100vh; padding: 64px 16px 32px;
      background:
        radial-gradient(circle at 15% 20%, rgba(124, 92, 255, 0.12), transparent 40%),
        radial-gradient(circle at 85% 80%, rgba(14, 165, 233, 0.10), transparent 40%),
        var(--pc-bg);
    }
    .ws-container.embedded { display: block; min-height: 0; padding: 0; background: none; }
    .ws-container.embedded .ws-panel { max-width: none; }
    .ws-panel { width: 100%; max-width: 760px; display: flex; flex-direction: column; gap: 20px; }
    .ws-head { display: flex; align-items: flex-end; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
    .ws-head p { margin-top: 4px; }
    .head-actions { display: flex; gap: 8px; }

    .create-card { padding: 20px; }
    .create-card h2 { margin: 0 0 16px; }
    .form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 4px 16px; }
    .form-grid .full { grid-column: 1 / -1; }
    .form-actions { display: flex; justify-content: flex-end; gap: 8px; }

    .ws-list { display: flex; flex-direction: column; gap: 10px; }
    .ws-item {
      display: flex; align-items: center; gap: 14px;
      padding: 16px; cursor: pointer;
      background: var(--pc-surface); border: 1px solid var(--pc-border);
      border-radius: var(--pc-radius); box-shadow: var(--pc-shadow);
      transition: border-color 0.15s, box-shadow 0.15s;
    }
    .ws-item:hover, .ws-item:focus-visible { border-color: var(--pc-primary); box-shadow: var(--pc-shadow-hover); outline: none; }
    .ws-icon {
      display: grid; place-items: center; width: 40px; height: 40px; flex-shrink: 0;
      border-radius: 10px; background: var(--pc-primary-soft); color: var(--pc-primary);
    }
    .ws-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px; }
    .ws-title { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
    .ws-name { font-weight: 600; font-size: 15px; color: var(--pc-text); }
    .ws-sub { color: var(--pc-text-muted); font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .stage-chip {
      padding: 2px 10px; border-radius: 999px; font-size: 12px; font-weight: 600;
      background: var(--pc-primary-soft); color: var(--pc-primary-600);
    }
    .ws-item.current { border-color: var(--pc-primary); }
    .current-chip {
      padding: 2px 10px; border-radius: 999px; font-size: 12px; font-weight: 600;
      background: var(--pc-primary); color: #fff;
    }
    .stage-chip { display: inline-flex; align-items: center; gap: 4px; }
    .stage-chip mat-icon { font-size: 14px; width: 14px; height: 14px; }
    .stage-chip.COMPLETED { background: var(--pc-success-soft); color: #047857; }
    .stage-chip.REJECTED { background: var(--pc-danger-soft); color: #b91c1c; }
    .stage-chip.ARCHIVED { background: #f3f4f6; color: #4b5563; }

    /* Final states: the bar is full and colored by outcome */
    mat-progress-bar.COMPLETED {
      --mat-progress-bar-active-indicator-color: var(--pc-success);
      --mdc-linear-progress-active-indicator-color: var(--pc-success);
    }
    mat-progress-bar.REJECTED {
      --mat-progress-bar-active-indicator-color: var(--pc-danger);
      --mdc-linear-progress-active-indicator-color: var(--pc-danger);
    }
    mat-progress-bar.ARCHIVED {
      --mat-progress-bar-active-indicator-color: #9ca3af;
      --mdc-linear-progress-active-indicator-color: #9ca3af;
    }
    .ws-item.COMPLETED .ws-icon { background: var(--pc-success-soft); color: #047857; }
    .ws-item.REJECTED .ws-icon { background: var(--pc-danger-soft); color: var(--pc-danger); }
    .ws-item.REJECTED { border-left: 4px solid var(--pc-danger); }
    .ws-item.REJECTED .ws-sub { color: #b91c1c; }
    .ws-item.ARCHIVED { cursor: default; opacity: 0.75; }
    .ws-item.ARCHIVED:hover { border-color: var(--pc-border); box-shadow: var(--pc-shadow); }
    .ws-item.ARCHIVED .ws-icon { background: #f3f4f6; color: #6b7280; }

    /* Status filters as summary cards */
    .status-tabs { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; }
    .status-tab {
      display: flex; align-items: center; gap: 14px; padding: 16px 18px; cursor: pointer; text-align: left;
      font: inherit; color: var(--pc-text);
      background: var(--pc-surface); border: 1px solid var(--pc-border);
      border-radius: var(--pc-radius); box-shadow: var(--pc-shadow);
      transition: border-color 0.15s, box-shadow 0.15s;
    }
    .status-tab:hover { border-color: var(--pc-primary); box-shadow: var(--pc-shadow-hover); }
    .tab-icon {
      display: grid; place-items: center; width: 44px; height: 44px; flex-shrink: 0;
      border-radius: 12px; background: var(--pc-primary-soft); color: var(--pc-primary);
    }
    .status-tab.COMPLETED .tab-icon { background: var(--pc-success-soft); color: #047857; }
    .status-tab.REJECTED .tab-icon { background: var(--pc-danger-soft); color: #b91c1c; }
    .status-tab.ARCHIVED .tab-icon { background: #f3f4f6; color: #4b5563; }
    .tab-text { display: flex; flex-direction: column; min-width: 0; }
    .tab-label { font-size: 13px; font-weight: 500; color: var(--pc-text-muted); }
    .tab-count { font-size: 22px; font-weight: 700; line-height: 1.2; }
    .status-tab.active { border-color: var(--pc-primary); box-shadow: 0 0 0 3px var(--pc-primary-soft); }
    .status-tab.active.COMPLETED { border-color: var(--pc-success); box-shadow: 0 0 0 3px var(--pc-success-soft); }
    .status-tab.active.REJECTED { border-color: var(--pc-danger); box-shadow: 0 0 0 3px var(--pc-danger-soft); }
    .status-tab.active.ARCHIVED { border-color: #9ca3af; box-shadow: 0 0 0 3px #f3f4f6; }
    .empty-hint.small { padding: 24px 16px; }
    .lifecycle-btn { flex-shrink: 0; }
    .confirm { display: flex; align-items: center; gap: 6px; flex-shrink: 0; font-weight: 500; }

    .empty-hint {
      display: flex; flex-direction: column; align-items: center; gap: 12px; text-align: center;
      padding: 40px 16px; color: var(--pc-text-muted);
      background: var(--pc-surface); border: 1px dashed var(--pc-border); border-radius: var(--pc-radius);
    }
    .empty-hint > mat-icon { font-size: 36px; width: 36px; height: 36px; color: var(--pc-primary); }

    @media (max-width: 900px) {
      .status-tabs { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    }
    @media (max-width: 600px) {
      .ws-container:not(.embedded) { padding-top: 24px; }
      .form-grid { grid-template-columns: 1fr; }
      .lifecycle-btn { display: none; }
    }
  `],
})
export class WorkspaceSelectorComponent implements OnInit {
  private readonly store = inject(Store);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);
  private readonly workspaceService = inject(WorkspaceService);
  private readonly notifications = inject(NotificationService);

  /** True on /workspaces (inside the app shell); false on the full-screen post-login picker. */
  readonly embedded = !!this.route.snapshot.data['embedded'];
  readonly workspaces$ = this.store.select(selectWorkspaces);
  readonly selectedId$ = this.store.select(selectSelectedWorkspaceId);
  readonly showCreate = signal(false);
  readonly saving = signal(false);
  readonly confirmArchiveId = signal<string | null>(null);

  readonly statusTabs = STATUS_TABS;
  /** Every workspace including archived ones; the store only holds active workspaces. */
  readonly all = signal<WorkspaceSummary[]>([]);
  readonly loaded = signal(false);
  readonly filter = signal<WorkspaceStatus>('IN_PROGRESS');
  readonly counts = computed(() => {
    const counts: Record<WorkspaceStatus, number> = { IN_PROGRESS: 0, COMPLETED: 0, REJECTED: 0, ARCHIVED: 0 };
    for (const ws of this.all()) counts[workspaceStatus(ws)]++;
    return counts;
  });
  readonly visible = computed(() => this.all().filter((ws) => workspaceStatus(ws) === this.filter()));
  readonly filterLabel = computed(() => STATUS_TABS.find((t) => t.status === this.filter())!.label.toLowerCase());

  slugEdited = false;

  readonly form = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(100)]],
    slug: ['', [Validators.required, Validators.maxLength(50), Validators.pattern(SLUG_PATTERN)]],
    description: ['', Validators.maxLength(500)],
  });

  ngOnInit(): void {
    this.refresh(true);
  }

  /** Reloads this page's list and the store's active workspaces. */
  private refresh(pickTab = false): void {
    this.store.dispatch(workspaceActions.loadWorkspaces());
    this.workspaceService.listWorkspaces(true).subscribe((workspaces) => {
      this.all.set(workspaces);
      this.loaded.set(true);
      // Open on the first status that has workspaces, so the page is never empty on arrival
      if (pickTab && this.counts()[this.filter()] === 0) {
        const first = STATUS_TABS.find((t) => this.counts()[t.status] > 0);
        if (first) this.filter.set(first.status);
      }
    });
  }

  openCreate(): void {
    this.form.reset();
    this.slugEdited = false;
    this.showCreate.set(true);
  }

  cancelCreate(): void {
    this.showCreate.set(false);
  }

  /** Keep the slug derived from the name until the user edits it directly. */
  syncSlug(): void {
    if (this.slugEdited) return;
    const slug = this.form.controls.name.value
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 50);
    this.form.controls.slug.setValue(slug);
  }

  create(): void {
    if (this.form.invalid) return;
    this.saving.set(true);
    const { name, slug, description } = this.form.getRawValue();
    this.workspaceService.createWorkspace({ name: name.trim(), slug, description: description.trim() || undefined })
      .subscribe({
        next: (ws) => {
          this.saving.set(false);
          this.showCreate.set(false);
          this.notifications.showSuccess(`Workspace "${ws.name}" created`);
          this.refresh();
          this.selectWorkspace(ws, true);
        },
        error: () => this.saving.set(false),
      });
  }

  archive(ws: WorkspaceSummary): void {
    this.workspaceService.archiveWorkspace(ws.id).subscribe({
      next: () => {
        this.confirmArchiveId.set(null);
        if (sessionStorage.getItem('workspace_id') === ws.id) {
          sessionStorage.removeItem('workspace_id');
          this.store.dispatch(workspaceActions.clearWorkspace());
        }
        this.notifications.showSuccess(`Workspace "${ws.name}" archived`);
        this.refresh();
      },
      error: () => this.confirmArchiveId.set(null),
    });
  }

  restore(ws: WorkspaceSummary): void {
    this.workspaceService.restoreWorkspace(ws.id).subscribe(() => {
      this.notifications.showSuccess(`Workspace "${ws.name}" restored`);
      this.filter.set(workspaceStatus({ ...ws, active: true }));
      this.refresh();
    });
  }

  selectWorkspace(ws: WorkspaceSummary, openLifecycle: boolean): void {
    if (workspaceStatus(ws) === 'ARCHIVED') return; // restore it first
    sessionStorage.setItem('workspace_id', ws.id);
    this.store.dispatch(workspaceActions.selectWorkspace({ workspaceId: ws.id }));
    this.router.navigate([openLifecycle ? '/lifecycle' : '/dashboard']);
  }

  statusOf(ws: WorkspaceSummary): WorkspaceStatus {
    return workspaceStatus(ws);
  }

  statusIcon(ws: WorkspaceSummary): string {
    const status = workspaceStatus(ws);
    return status === 'IN_PROGRESS' ? 'workspaces' : STATUS_TABS.find((t) => t.status === status)!.icon;
  }

  stageLabel(ws: WorkspaceSummary): string {
    switch (workspaceStatus(ws)) {
      case 'COMPLETED': return 'Completed';
      case 'REJECTED': return 'Rejected · Completed';
      case 'ARCHIVED': return 'Archived';
      default:
        return `Step ${stageIndex(ws.lifecycleStage) + 1} of ${LIFECYCLE_STAGES.length} · ${stageTitle(ws.lifecycleStage)}`;
    }
  }

  statusNote(ws: WorkspaceSummary): string | null {
    return workspaceStatus(ws) === 'REJECTED'
      ? 'Rejected at Final Review. The workflow has ended; no further action is needed.'
      : null;
  }

  /** Completed and rejected workflows have ended, so their bar is full (colored by outcome). */
  progress(ws: WorkspaceSummary): number {
    const status = workspaceStatus(ws);
    if (status === 'COMPLETED' || status === 'REJECTED') return 100;
    return (stageIndex(ws.lifecycleStage) / LIFECYCLE_STAGES.length) * 100;
  }

  logout(): void {
    this.store.dispatch(authActions.logout());
  }
}
