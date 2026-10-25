import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ActivatedRoute, Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { workspaceActions } from '../../store/workspace/workspace.actions';
import { selectSelectedWorkspaceId, selectWorkspaces } from '../../store/workspace/workspace.selectors';
import { authActions } from '../../store/auth/auth.actions';
import { WorkspaceService } from '../../core/api/workspace.service';
import { NotificationService } from '../../core/notifications/notification.service';
import { WorkspaceSummary } from '../../core/auth/auth-state.model';
import { LIFECYCLE_STAGES, stageIndex, stageTitle } from '../lifecycle/lifecycle-stages';

const SLUG_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

@Component({
  selector: 'app-workspace-selector',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule, MatCardModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatMenuModule, MatProgressBarModule,
  ],
  template: `
    <div class="ws-container" [class.embedded]="embedded">
      <div class="ws-panel">
        <div class="ws-head">
          <div>
            <h1>Workspaces</h1>
            <p class="pc-muted">Each workspace takes one application from discovery to encrypted data.</p>
          </div>
          <div class="head-actions">
            <button mat-button type="button" *ngIf="!embedded" (click)="logout()"><mat-icon>logout</mat-icon> Sign Out</button>
            <button mat-flat-button color="primary" type="button" *ngIf="!showCreate()" (click)="openCreate()">
              <mat-icon>add</mat-icon> New Workspace
            </button>
          </div>
        </div>

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

        <div class="ws-list">
          <div *ngFor="let ws of workspaces$ | async" class="ws-item" role="button" tabindex="0"
               [class.current]="ws.id === (selectedId$ | async)"
               (click)="selectWorkspace(ws, false)" (keydown.enter)="selectWorkspace(ws, false)">
            <span class="ws-icon"><mat-icon>workspaces</mat-icon></span>
            <div class="ws-main">
              <div class="ws-title">
                <span class="ws-name">{{ ws.name }}</span>
                <span class="current-chip" *ngIf="ws.id === (selectedId$ | async)">Current</span>
                <span class="stage-chip" [class.done]="ws.lifecycleStage === 'COMPLETED'">
                  {{ stageLabel(ws) }}
                </span>
              </div>
              <div class="ws-sub">{{ ws.description || ws.slug }}</div>
              <mat-progress-bar mode="determinate" [value]="progress(ws)"></mat-progress-bar>
            </div>

            <ng-container *ngIf="confirmArchiveId() === ws.id; else rowActions">
              <div class="confirm" (click)="$event.stopPropagation()">
                <span>Archive?</span>
                <button mat-button type="button" (click)="confirmArchiveId.set(null)">Cancel</button>
                <button mat-flat-button color="warn" type="button" (click)="archive(ws)">Archive</button>
              </div>
            </ng-container>
            <ng-template #rowActions>
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
            </ng-template>
          </div>

          <div *ngIf="(workspaces$ | async)?.length === 0 && !showCreate()" class="empty-hint">
            <mat-icon>workspaces</mat-icon>
            <p>No workspaces yet. Create one to connect an application and start protecting its data.</p>
            <button mat-flat-button color="primary" type="button" (click)="openCreate()">
              <mat-icon>add</mat-icon> New Workspace
            </button>
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
    .ws-container.embedded { min-height: 0; padding: 0; background: none; }
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
    .stage-chip.done { background: var(--pc-success-soft); color: #047857; }
    .lifecycle-btn { flex-shrink: 0; }
    .confirm { display: flex; align-items: center; gap: 6px; flex-shrink: 0; font-weight: 500; }

    .empty-hint {
      display: flex; flex-direction: column; align-items: center; gap: 12px; text-align: center;
      padding: 40px 16px; color: var(--pc-text-muted);
      background: var(--pc-surface); border: 1px dashed var(--pc-border); border-radius: var(--pc-radius);
    }
    .empty-hint > mat-icon { font-size: 36px; width: 36px; height: 36px; color: var(--pc-primary); }

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

  slugEdited = false;

  readonly form = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(100)]],
    slug: ['', [Validators.required, Validators.maxLength(50), Validators.pattern(SLUG_PATTERN)]],
    description: ['', Validators.maxLength(500)],
  });

  ngOnInit(): void {
    this.store.dispatch(workspaceActions.loadWorkspaces());
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
          this.store.dispatch(workspaceActions.loadWorkspaces());
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
        this.store.dispatch(workspaceActions.loadWorkspaces());
      },
      error: () => this.confirmArchiveId.set(null),
    });
  }

  selectWorkspace(ws: WorkspaceSummary, openLifecycle: boolean): void {
    sessionStorage.setItem('workspace_id', ws.id);
    this.store.dispatch(workspaceActions.selectWorkspace({ workspaceId: ws.id }));
    this.router.navigate([openLifecycle ? '/lifecycle' : '/dashboard']);
  }

  stageLabel(ws: WorkspaceSummary): string {
    if (ws.lifecycleStage === 'COMPLETED') return 'Completed';
    return `Step ${stageIndex(ws.lifecycleStage) + 1} of ${LIFECYCLE_STAGES.length} · ${stageTitle(ws.lifecycleStage)}`;
  }

  progress(ws: WorkspaceSummary): number {
    return (stageIndex(ws.lifecycleStage) / LIFECYCLE_STAGES.length) * 100;
  }

  logout(): void {
    this.store.dispatch(authActions.logout());
  }
}
