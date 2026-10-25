import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { forkJoin } from 'rxjs';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTableModule } from '@angular/material/table';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { StatusBadgeComponent } from '../../shared/components/status-badge/status-badge.component';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog/confirm-dialog.component';
import { NotificationService } from '../../core/notifications/notification.service';
import { WorkspaceService } from '../../core/api/workspace.service';
import { GithubConnectorComponent } from './github/github-connector.component';
import { PostgresqlConnectorComponent } from './postgresql/postgresql-connector.component';
import {
  connectorApiBase, ConnectorKind, CONNECTOR_TYPES, GithubConnector, PostgresConnector,
} from './connector.models';

interface ConnectorRow {
  kind: ConnectorKind;
  id: string;
  title: string;
  link: string | null;
  project: string;
  details: { label: string; value: string }[];
  verified: boolean;
  status: string;
  createdAt: string;
}

type TabMode = 'new' | 'view' | 'edit';

interface ConnectorTab {
  key: string;
  kind: ConnectorKind;
  mode: TabMode;
  id: string | null;
  title: string;
}

/** Connector list with colored type tiles; new / view / edit open as closable tabs on the same page. */
@Component({
  selector: 'app-connectors',
  standalone: true,
  imports: [
    PageHeaderComponent, StatusBadgeComponent, CommonModule, MatCardModule, MatIconModule, MatButtonModule,
    MatTabsModule, MatTableModule, MatMenuModule, MatTooltipModule, MatDialogModule,
    GithubConnectorComponent, PostgresqlConnectorComponent,
  ],
  template: `
    <div class="pc-page connectors-page">
      <app-page-header title="Connectors"
        subtitle="Configure connections to your repositories and databases for PI discovery.">
        <button mat-stroked-button color="primary" type="button" (click)="completeAndScan()"
                [disabled]="loading || verifiedCount('postgresql') === 0 || completing"
                [matTooltip]="verifiedCount('postgresql') === 0 ? 'Add and verify a database connector first' : 'Finish the Connect stage and start a scan'">
          <mat-icon>radar</mat-icon> Complete &amp; Scan
        </button>
        <button mat-flat-button color="primary" [matMenuTriggerFor]="addMenu">
          <mat-icon>add</mat-icon> Add New
        </button>
      </app-page-header>

      <mat-menu #addMenu="matMenu">
        <button mat-menu-item *ngFor="let kind of kinds" (click)="openNew(kind)">
          <mat-icon>{{ types[kind].icon }}</mat-icon> {{ types[kind].label }}
        </button>
      </mat-menu>

      <mat-tab-group [(selectedIndex)]="selectedIndex" animationDuration="150ms" class="tabs">
        <mat-tab>
          <ng-template mat-tab-label><mat-icon class="tab-icon">hub</mat-icon> All Connectors</ng-template>

          <div class="tab-body">
            <div class="tiles">
              <div role="button" tabindex="0" class="tile tile-all" [class.active]="filter === null"
                   (click)="filter = null" (keydown.enter)="filter = null">
                <mat-icon class="tile-icon">hub</mat-icon>
                <div class="tile-text">
                  <span class="tile-count">{{ rows.length }}</span>
                  <span class="tile-label">All connectors</span>
                  <span class="tile-meta">{{ verifiedCount(null) }} verified</span>
                </div>
              </div>

              <div role="button" tabindex="0" *ngFor="let kind of kinds" class="tile" [ngClass]="'tile-' + kind"
                   [class.active]="filter === kind" (click)="filter = kind" (keydown.enter)="filter = kind">
                <mat-icon class="tile-icon">{{ types[kind].icon }}</mat-icon>
                <div class="tile-text">
                  <span class="tile-count">{{ count(kind) }}</span>
                  <span class="tile-label">{{ types[kind].label }}</span>
                  <span class="tile-meta">{{ verifiedCount(kind) }} verified</span>
                </div>
                <span class="tile-add" role="button" tabindex="0" [matTooltip]="'Add ' + types[kind].label"
                      (click)="$event.stopPropagation(); openNew(kind)"
                      (keydown.enter)="$event.stopPropagation(); openNew(kind)">
                  <mat-icon>add</mat-icon>
                </span>
              </div>
            </div>

            <mat-card class="list-card">
              <div class="list-head">
                <h2>{{ filter ? types[filter].label + ' connectors' : 'All connectors' }}</h2>
                <button *ngIf="filter" mat-stroked-button (click)="openNew(filter)">
                  <mat-icon>add</mat-icon> Add New
                </button>
                <button *ngIf="!filter" mat-stroked-button [matMenuTriggerFor]="addMenu">
                  <mat-icon>add</mat-icon> Add New
                </button>
              </div>

              <p *ngIf="loading" class="muted">Loading connectors…</p>
              <div *ngIf="!loading && filteredRows.length === 0" class="empty">
                <mat-icon>cable</mat-icon>
                <p>No connectors yet. Add a GitHub repository and a PostgreSQL database to start scanning.</p>
              </div>

              <div class="table-wrap" *ngIf="!loading && filteredRows.length > 0">
                <table mat-table [dataSource]="filteredRows">
                  <ng-container matColumnDef="name">
                    <th mat-header-cell *matHeaderCellDef>Connector</th>
                    <td mat-cell *matCellDef="let r">
                      <div class="name-cell">
                        <span class="type-icon" [ngClass]="'type-' + r.kind"><mat-icon>{{ type(r.kind).icon }}</mat-icon></span>
                        <div>
                          <div class="name">{{ r.title }}</div>
                          <div class="sub">{{ type(r.kind).label }}</div>
                        </div>
                      </div>
                    </td>
                  </ng-container>
                  <ng-container matColumnDef="project">
                    <th mat-header-cell *matHeaderCellDef>Project</th>
                    <td mat-cell *matCellDef="let r">{{ r.project }}</td>
                  </ng-container>
                  <ng-container matColumnDef="details">
                    <th mat-header-cell *matHeaderCellDef>Details</th>
                    <td mat-cell *matCellDef="let r">
                      <div *ngFor="let d of r.details.slice(0, 2)" class="detail">
                        <span class="muted">{{ d.label }}:</span> {{ d.value }}
                      </div>
                    </td>
                  </ng-container>
                  <ng-container matColumnDef="status">
                    <th mat-header-cell *matHeaderCellDef>Status</th>
                    <td mat-cell *matCellDef="let r">
                      <app-status-badge [label]="r.verified ? 'Verified' : 'Unverified'"
                                        [status]="r.verified ? 'success' : 'pending'"></app-status-badge>
                    </td>
                  </ng-container>
                  <ng-container matColumnDef="created">
                    <th mat-header-cell *matHeaderCellDef>Added</th>
                    <td mat-cell *matCellDef="let r">{{ r.createdAt | date: 'medium' }}</td>
                  </ng-container>
                  <ng-container matColumnDef="actions">
                    <th mat-header-cell *matHeaderCellDef class="actions-col">Actions</th>
                    <td mat-cell *matCellDef="let r" class="actions-col">
                      <button mat-icon-button matTooltip="View" (click)="openTab(r.kind, 'view', r.id, r.title)">
                        <mat-icon>visibility</mat-icon>
                      </button>
                      <button mat-icon-button matTooltip="Edit" (click)="openTab(r.kind, 'edit', r.id, r.title)">
                        <mat-icon>edit</mat-icon>
                      </button>
                      <button mat-icon-button matTooltip="Delete" class="delete" (click)="confirmDelete(r)">
                        <mat-icon>delete</mat-icon>
                      </button>
                    </td>
                  </ng-container>
                  <tr mat-header-row *matHeaderRowDef="columns"></tr>
                  <tr mat-row *matRowDef="let row; columns: columns" class="row"
                      (dblclick)="openTab(row.kind, 'view', row.id, row.title)"></tr>
                </table>
              </div>
            </mat-card>
          </div>
        </mat-tab>

        <mat-tab *ngFor="let tab of tabs; trackBy: trackTab">
          <ng-template mat-tab-label>
            <mat-icon class="tab-icon">{{ tab.mode === 'new' ? 'add_circle' : tab.mode === 'edit' ? 'edit' : types[tab.kind].icon }}</mat-icon>
            {{ tab.title }}
            <button mat-icon-button class="tab-close" aria-label="Close tab"
                    (click)="$event.stopPropagation(); closeTab(tab)">
              <mat-icon>close</mat-icon>
            </button>
          </ng-template>

          <div class="tab-body">
            <ng-container [ngSwitch]="tab.mode">
              <ng-container *ngSwitchCase="'view'">
                <mat-card *ngIf="rowFor(tab) as r; else missing" class="view-card">
                  <div class="view-head" [ngClass]="'tile-' + r.kind">
                    <mat-icon class="tile-icon">{{ types[r.kind].icon }}</mat-icon>
                    <div class="view-titles">
                      <h2>{{ r.title }}</h2>
                      <span>{{ types[r.kind].label }} · {{ r.project }}</span>
                    </div>
                    <app-status-badge [label]="r.verified ? 'Verified' : 'Unverified'"
                                      [status]="r.verified ? 'success' : 'pending'"></app-status-badge>
                  </div>
                  <dl class="view-grid">
                    <div><dt>Project</dt><dd>{{ r.project }}</dd></div>
                    <div *ngFor="let d of r.details"><dt>{{ d.label }}</dt><dd>{{ d.value }}</dd></div>
                    <div><dt>Status</dt><dd>{{ r.status }}</dd></div>
                    <div><dt>Added</dt><dd>{{ r.createdAt | date: 'medium' }}</dd></div>
                    <div *ngIf="r.link"><dt>Link</dt><dd><a [href]="r.link" target="_blank" rel="noopener">{{ r.link }}</a></dd></div>
                  </dl>
                  <div class="view-actions">
                    <button mat-button (click)="closeTab(tab)">Close</button>
                    <button mat-stroked-button class="delete" (click)="confirmDelete(r)">
                      <mat-icon>delete</mat-icon> Delete
                    </button>
                    <button mat-flat-button color="primary" (click)="openTab(r.kind, 'edit', r.id, r.title)">
                      <mat-icon>edit</mat-icon> Edit
                    </button>
                  </div>
                </mat-card>
                <ng-template #missing><p class="muted">This connector no longer exists.</p></ng-template>
              </ng-container>

              <ng-container *ngSwitchDefault>
                <app-github-connector *ngIf="tab.kind === 'github'" [connectorId]="tab.id"
                                      (saved)="onSaved(tab, $event.id, $event.owner + '/' + $event.name)"
                                      (cancelled)="closeTab(tab)"></app-github-connector>
                <app-postgresql-connector *ngIf="tab.kind === 'postgresql'" [connectorId]="tab.id"
                                          (saved)="onSaved(tab, $event.id, $event.databaseName)"
                                          (cancelled)="closeTab(tab)"></app-postgresql-connector>
              </ng-container>
            </ng-container>
          </div>
        </mat-tab>
      </mat-tab-group>
    </div>
  `,
  styles: [`
    .tabs { margin-top: 8px; }
    .tab-body { padding: 20px 0 8px; display: flex; flex-direction: column; gap: 20px; }
    .tab-icon { margin-right: 6px; font-size: 18px; width: 18px; height: 18px; }
    .tab-close { margin-left: 4px; width: 28px; height: 28px; padding: 2px; line-height: 1; }
    .tab-close mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .muted { color: var(--pc-text-muted); }

    .tiles {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(240px, 100%), 1fr));
      gap: 16px;
    }
    .tile {
      position: relative;
      display: flex; align-items: center; gap: 16px;
      padding: 20px; border: 0; border-radius: var(--pc-radius);
      color: #fff; text-align: left; cursor: pointer; font: inherit;
      box-shadow: var(--pc-shadow);
      transition: transform 0.15s, box-shadow 0.15s, outline-color 0.15s;
      outline: 3px solid transparent; outline-offset: 2px;
    }
    .tile:hover { transform: translateY(-2px); box-shadow: var(--pc-shadow-hover); }
    .tile.active { outline-color: var(--pc-primary); }
    .tile-all { background: linear-gradient(135deg, #7c5cff, #4527cc); }
    .tile-github { background: linear-gradient(135deg, #3b434d, #161b22); }
    .tile-postgresql { background: linear-gradient(135deg, #3d7cc9, #1d4f8a); }
    .tile-icon {
      flex-shrink: 0; width: 48px; height: 48px; font-size: 28px;
      display: grid; place-items: center; border-radius: 12px;
      background: rgba(255, 255, 255, 0.16);
    }
    .tile-text { display: flex; flex-direction: column; min-width: 0; }
    .tile-count { font-size: 28px; font-weight: 700; line-height: 1.1; }
    .tile-label { font-size: 14px; font-weight: 600; }
    .tile-meta { font-size: 12px; opacity: 0.8; }
    .tile-add {
      position: absolute; top: 12px; right: 12px;
      display: grid; place-items: center; width: 32px; height: 32px; border-radius: 50%;
      background: rgba(255, 255, 255, 0.18); color: #fff;
    }
    .tile-add:hover { background: rgba(255, 255, 255, 0.32); }

    .list-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 16px 16px 0; }
    .list-head h2 { margin: 0; }
    .list-card > p, .empty { padding: 0 16px 16px; }
    .empty { display: flex; flex-direction: column; align-items: center; color: var(--pc-text-muted); text-align: center; padding: 32px 16px; }
    .empty mat-icon { font-size: 40px; width: 40px; height: 40px; opacity: 0.5; }
    .table-wrap { overflow-x: auto; padding: 8px 0; }
    table { width: 100%; }
    .row:hover { background: var(--pc-bg); }
    .name-cell { display: flex; align-items: center; gap: 12px; padding: 8px 0; }
    .type-icon { display: grid; place-items: center; width: 36px; height: 36px; border-radius: 10px; color: #fff; flex-shrink: 0; }
    .type-icon mat-icon { font-size: 20px; width: 20px; height: 20px; }
    .type-github { background: #24292f; }
    .type-postgresql { background: #336791; }
    .name { font-weight: 600; }
    .sub { font-size: 12px; color: var(--pc-text-muted); }
    .detail { font-size: 13px; white-space: nowrap; }
    .actions-col { text-align: right; white-space: nowrap; }
    .delete { color: var(--pc-danger); }

    .view-card { overflow: hidden; padding: 0 !important; }
    .view-head { display: flex; align-items: center; flex-wrap: wrap; gap: 16px; padding: 20px; color: #fff; }
    .view-titles { flex: 1; min-width: 0; }
    .view-titles h2 { margin: 0; color: #fff; overflow-wrap: anywhere; }
    .view-titles span { font-size: 13px; opacity: 0.85; }
    .view-grid {
      display: grid; grid-template-columns: repeat(auto-fit, minmax(min(240px, 100%), 1fr));
      gap: 16px 24px; margin: 0; padding: 20px;
    }
    .view-grid dt { font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; color: var(--pc-text-muted); }
    .view-grid dd { margin: 4px 0 0; overflow-wrap: anywhere; }
    .view-actions { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 12px; padding: 0 20px 20px; }
  `],
})
export class ConnectorsComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly dialog = inject(MatDialog);
  private readonly notification = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly workspaceService = inject(WorkspaceService);

  readonly types = CONNECTOR_TYPES;
  readonly kinds: ConnectorKind[] = ['github', 'postgresql'];
  readonly columns = ['name', 'project', 'details', 'status', 'created', 'actions'];

  rows: ConnectorRow[] = [];
  tabs: ConnectorTab[] = [];
  filter: ConnectorKind | null = null;
  selectedIndex = 0;
  loading = true;
  completing = false;

  get filteredRows(): ConnectorRow[] {
    return this.filter ? this.rows.filter((r) => r.kind === this.filter) : this.rows;
  }

  ngOnInit(): void {
    this.load();
  }

  count(kind: ConnectorKind): number {
    return this.rows.filter((r) => r.kind === kind).length;
  }

  /** Completes the lifecycle's Connect stage (if the workspace is still on it) and opens New Scan. */
  completeAndScan(): void {
    const workspaceId = sessionStorage.getItem('workspace_id') ?? '';
    this.completing = true;
    this.workspaceService.getLifecycle(workspaceId).subscribe({
      next: (lc) => {
        if (lc.currentStage !== 'CONNECT') {
          this.router.navigate(['/scans/new']);
          return;
        }
        this.workspaceService.advanceStage(workspaceId).subscribe({
          next: () => {
            this.notification.showSuccess('Connect stage completed');
            this.router.navigate(['/scans/new']);
          },
          error: () => (this.completing = false),
        });
      },
      error: () => (this.completing = false),
    });
  }

  verifiedCount(kind: ConnectorKind | null): number {
    return this.rows.filter((r) => r.verified && (!kind || r.kind === kind)).length;
  }

  type(kind: ConnectorKind) {
    return CONNECTOR_TYPES[kind];
  }

  trackTab = (_: number, tab: ConnectorTab) => tab.key;

  rowFor(tab: ConnectorTab): ConnectorRow | undefined {
    return this.rows.find((r) => r.kind === tab.kind && r.id === tab.id);
  }

  openNew(kind: ConnectorKind): void {
    this.openTab(kind, 'new', null, `New ${kind === 'github' ? 'GitHub' : 'PostgreSQL'}`);
  }

  /** One tab per connector (view and edit share it); one "new" tab per connector type. */
  openTab(kind: ConnectorKind, mode: TabMode, id: string | null, title: string): void {
    const key = `${kind}:${id ?? 'new'}`;
    let index = this.tabs.findIndex((t) => t.key === key);
    if (index < 0) {
      this.tabs = [...this.tabs, { key, kind, mode, id, title }];
      index = this.tabs.length - 1;
    } else {
      this.tabs[index] = { ...this.tabs[index], mode };
    }
    this.selectedIndex = index + 1;
  }

  closeTab(tab: ConnectorTab): void {
    const index = this.tabs.indexOf(tab);
    if (index < 0) return;
    this.tabs = this.tabs.filter((t) => t !== tab);
    // Stay on the neighbouring tab, or fall back to the list
    this.selectedIndex = Math.min(this.selectedIndex, this.tabs.length);
    if (this.selectedIndex === index + 1) this.selectedIndex = index;
  }

  /** A saved form turns into the connector's view tab. */
  onSaved(tab: ConnectorTab, id: string, title: string): void {
    const key = `${tab.kind}:${id}`;
    // Saving a "new" connector may update one that already has a tab open
    this.tabs = this.tabs
      .filter((t) => t === tab || t.key !== key)
      .map((t) => (t === tab ? { ...t, key, id, title, mode: 'view' as TabMode } : t));
    this.selectedIndex = this.tabs.findIndex((t) => t.key === key) + 1;
    this.load();
  }

  confirmDelete(row: ConnectorRow): void {
    const ref = this.dialog.open(ConfirmDialogComponent, { width: '440px' });
    ref.componentRef!.setInput('title', 'Delete connector?');
    ref.componentRef!.setInput('message',
      `"${row.title}" will be removed and its stored credentials deleted. This cannot be undone.`);
    ref.componentRef!.setInput('confirmLabel', 'Delete');
    ref.componentRef!.setInput('severity', 'error');
    ref.componentInstance.confirm.subscribe(() => this.delete(row));
  }

  private delete(row: ConnectorRow): void {
    this.http.delete(`${connectorApiBase(row.kind)}/connectors/${row.id}`).subscribe({
      next: () => {
        this.notification.showSuccess(`Connector ${row.title} deleted`);
        const tab = this.tabs.find((t) => t.key === `${row.kind}:${row.id}`);
        if (tab) this.closeTab(tab);
        this.load();
      },
    });
  }

  private load(): void {
    forkJoin({
      github: this.http.get<GithubConnector[]>(`${connectorApiBase('github')}/connectors`),
      postgresql: this.http.get<PostgresConnector[]>(`${connectorApiBase('postgresql')}/connectors`),
    }).subscribe({
      next: ({ github, postgresql }) => {
        this.rows = [...github.map(toGithubRow), ...postgresql.map(toPostgresRow)]
          .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
        this.loading = false;
      },
      error: () => (this.loading = false),
    });
  }
}

function toGithubRow(c: GithubConnector): ConnectorRow {
  return {
    kind: 'github',
    id: c.id,
    title: `${c.owner}/${c.name}`,
    link: c.repositoryUrl,
    project: c.projectName,
    details: [
      { label: 'Branch', value: c.branch },
      { label: 'Folders', value: c.includePaths.length ? c.includePaths.join(', ') : 'Whole repository' },
      { label: 'GitHub API', value: c.apiBaseUrl },
    ],
    verified: c.verified,
    status: c.status,
    createdAt: c.createdAt,
  };
}

function toPostgresRow(c: PostgresConnector): ConnectorRow {
  return {
    kind: 'postgresql',
    id: c.id,
    title: c.databaseName,
    link: null,
    project: c.projectName,
    details: [
      { label: 'Server', value: `${c.username}@${c.host}:${c.port}` },
      { label: 'Schemas', value: c.schemaScope || 'All schemas' },
      { label: 'TLS', value: c.sslMode },
    ],
    verified: c.verified,
    status: c.status,
    createdAt: c.createdAt,
  };
}
