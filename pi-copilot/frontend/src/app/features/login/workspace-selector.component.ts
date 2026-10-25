import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatListModule } from '@angular/material/list';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { workspaceActions } from '../../store/workspace/workspace.actions';
import { selectWorkspaces } from '../../store/workspace/workspace.selectors';

@Component({
  selector: 'app-workspace-selector',
  standalone: true,
  imports: [CommonModule, MatCardModule, MatListModule, MatIconModule, MatButtonModule],
  template: `
    <div class="ws-container">
      <mat-card class="ws-card">
        <mat-card-header>
          <mat-card-title>Select Workspace</mat-card-title>
          <mat-card-subtitle>Choose a workspace to continue</mat-card-subtitle>
        </mat-card-header>
        <mat-card-content>
          <mat-list>
            <mat-list-item *ngFor="let ws of workspaces$ | async" (click)="selectWorkspace(ws.id, ws.slug)">
              <mat-icon matListItemIcon>workspaces</mat-icon>
              <div matListItemTitle>{{ ws.name }}</div>
              <div matListItemLine>{{ ws.slug }}</div>
            </mat-list-item>
          </mat-list>
          <div *ngIf="(workspaces$ | async)?.length === 0" class="empty-hint">
            <mat-icon>info</mat-icon>
            <p>No workspaces available. Contact your administrator.</p>
          </div>
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: [`
    .ws-container {
      display: flex;
      align-items: center;
      justify-content: center;
      min-height: 100vh;
      background: #f5f5f5;
    }
    .ws-card { width: 100%; max-width: 480px; padding: 24px; }
    mat-list-item { cursor: pointer; border-radius: 8px; margin: 4px 0; }
    mat-list-item:hover { background: #e3f2fd; }
    .empty-hint { display: flex; align-items: center; gap: 8px; padding: 16px; color: #757575; }
  `],
})
export class WorkspaceSelectorComponent implements OnInit {
  private readonly store = inject(Store);
  private readonly router = inject(Router);

  readonly workspaces$ = this.store.select(selectWorkspaces);

  ngOnInit(): void {
    this.store.dispatch(workspaceActions.loadWorkspaces());
  }

  selectWorkspace(id: string, _slug: string): void {
    sessionStorage.setItem('workspace_id', id);
    this.store.dispatch(workspaceActions.selectWorkspace({ workspaceId: id }));
    this.router.navigate(['/dashboard']);
  }
}