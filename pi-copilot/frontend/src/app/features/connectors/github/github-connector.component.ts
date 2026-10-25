import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { RouterModule } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { MatChipsModule } from '@angular/material/chips';
import { map, startWith } from 'rxjs/operators';
import { NotificationService } from '../../../core/notifications/notification.service';

interface RepositorySummary {
  owner: string;
  name: string;
  fullName: string;
  description: string | null;
  privateRepo: boolean;
  defaultBranch: string;
  htmlUrl: string;
  updatedAt: string | null;
}

interface ConnectResponse {
  login: string;
  apiBaseUrl: string;
  repositories: RepositorySummary[];
}

interface BranchSummary {
  name: string;
  protectedBranch: boolean;
}

interface ConnectorSummary {
  id: string;
  projectName: string;
  repositoryUrl: string;
  owner: string;
  name: string;
  branch: string;
  apiBaseUrl: string;
  verified: boolean;
  createdAt: string;
}

@Component({
  selector: 'app-github-connector',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule, RouterModule, MatCardModule, MatFormFieldModule,
    MatInputModule, MatSelectModule, MatButtonModule, MatIconModule, MatAutocompleteModule,
    MatProgressBarModule, MatTableModule, MatChipsModule,
  ],
  template: `
    <div class="page">
      <a mat-button routerLink="/connectors" class="back"><mat-icon>arrow_back</mat-icon> Connectors</a>

      <mat-card>
        <mat-progress-bar *ngIf="busy" mode="indeterminate"></mat-progress-bar>
        <mat-card-header>
          <mat-card-title><mat-icon>code</mat-icon> GitHub Repository Connector</mat-card-title>
          <mat-card-subtitle>
            Connect any GitHub or GitHub Enterprise account with an access token, then choose the
            repository and branch to scan.
          </mat-card-subtitle>
        </mat-card-header>

        <mat-card-content>
          <!-- Step 1: credentials -->
          <section class="step">
            <h3><span class="num">1</span> Connect to GitHub</h3>
            <form [formGroup]="credentials" (ngSubmit)="connect()" class="grid">
              <mat-form-field>
                <mat-label>GitHub URL</mat-label>
                <input matInput formControlName="githubUrl" placeholder="https://github.com" />
                <mat-hint>https://github.com or your GitHub Enterprise URL</mat-hint>
              </mat-form-field>
              <mat-form-field>
                <mat-label>Personal Access Token</mat-label>
                <input matInput formControlName="token" [type]="showToken ? 'text' : 'password'"
                       autocomplete="off" placeholder="ghp_… or github_pat_…" />
                <button mat-icon-button matSuffix type="button" (click)="showToken = !showToken">
                  <mat-icon>{{ showToken ? 'visibility' : 'visibility_off' }}</mat-icon>
                </button>
                <mat-hint>Needs read access to repository contents and metadata</mat-hint>
              </mat-form-field>
              <div class="actions">
                <button *ngIf="connection" mat-button type="button" (click)="reset()">Disconnect</button>
                <button mat-raised-button color="primary" type="submit" [disabled]="credentials.invalid || busy">
                  <mat-icon>link</mat-icon> {{ connection ? 'Reconnect' : 'Connect' }}
                </button>
              </div>
            </form>
          </section>

          <ng-container *ngIf="connection">
            <!-- Step 2: repository -->
            <section class="step">
              <h3><span class="num">2</span> Select repository</h3>
              <p class="connected">
                <mat-icon>check_circle</mat-icon>
                Connected as <strong>&#64;{{ connection.login }}</strong> ·
                {{ connection.repositories.length }} repositories accessible
              </p>
              <mat-form-field class="full">
                <mat-label>Repository</mat-label>
                <mat-icon matPrefix>search</mat-icon>
                <input matInput [formControl]="repoSearch" [matAutocomplete]="repoAuto"
                       placeholder="Type to filter repositories" />
                <mat-autocomplete #repoAuto="matAutocomplete" [displayWith]="displayRepo"
                                  (optionSelected)="selectRepo($event.option.value)">
                  <mat-option *ngFor="let repo of filteredRepos$ | async" [value]="repo">
                    <mat-icon>{{ repo.privateRepo ? 'lock' : 'public' }}</mat-icon>
                    <span class="repo-name">{{ repo.fullName }}</span>
                    <span class="repo-desc" *ngIf="repo.description"> — {{ repo.description }}</span>
                  </mat-option>
                </mat-autocomplete>
              </mat-form-field>
            </section>

            <!-- Step 3: branch -->
            <section class="step" *ngIf="selectedRepo">
              <h3><span class="num">3</span> Select branch</h3>
              <div class="grid">
                <mat-form-field>
                  <mat-label>Branch</mat-label>
                  <mat-select [formControl]="branch">
                    <mat-option *ngFor="let b of branches" [value]="b.name">
                      {{ b.name }}
                      <span class="tag" *ngIf="b.name === selectedRepo.defaultBranch">default</span>
                      <span class="tag" *ngIf="b.protectedBranch">protected</span>
                    </mat-option>
                  </mat-select>
                  <mat-hint>{{ branches.length }} branches</mat-hint>
                </mat-form-field>
                <div class="actions">
                  <a mat-button [href]="selectedRepo.htmlUrl" target="_blank" rel="noopener">
                    <mat-icon>open_in_new</mat-icon> View on GitHub
                  </a>
                  <button mat-raised-button color="primary" (click)="save()" [disabled]="!branch.value || busy">
                    <mat-icon>save</mat-icon> Save for scanning
                  </button>
                </div>
              </div>
            </section>
          </ng-container>
        </mat-card-content>
      </mat-card>

      <mat-card class="saved">
        <mat-card-header>
          <mat-card-title>Saved GitHub connectors</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <p *ngIf="connectors.length === 0" class="empty">No repositories saved yet.</p>
          <table mat-table [dataSource]="connectors" *ngIf="connectors.length > 0">
            <ng-container matColumnDef="repo">
              <th mat-header-cell *matHeaderCellDef>Repository</th>
              <td mat-cell *matCellDef="let c"><a [href]="c.repositoryUrl" target="_blank" rel="noopener">{{ c.owner }}/{{ c.name }}</a></td>
            </ng-container>
            <ng-container matColumnDef="branch">
              <th mat-header-cell *matHeaderCellDef>Branch</th>
              <td mat-cell *matCellDef="let c"><code>{{ c.branch }}</code></td>
            </ng-container>
            <ng-container matColumnDef="host">
              <th mat-header-cell *matHeaderCellDef>GitHub host</th>
              <td mat-cell *matCellDef="let c">{{ c.apiBaseUrl }}</td>
            </ng-container>
            <ng-container matColumnDef="status">
              <th mat-header-cell *matHeaderCellDef>Status</th>
              <td mat-cell *matCellDef="let c">
                <span class="status" [class.ok]="c.verified">{{ c.verified ? 'Verified' : 'Unverified' }}</span>
              </td>
            </ng-container>
            <ng-container matColumnDef="created">
              <th mat-header-cell *matHeaderCellDef>Added</th>
              <td mat-cell *matCellDef="let c">{{ c.createdAt | date: 'medium' }}</td>
            </ng-container>
            <tr mat-header-row *matHeaderRowDef="columns"></tr>
            <tr mat-row *matRowDef="let row; columns: columns"></tr>
          </table>
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: [`
    .page { padding: 24px; display: flex; flex-direction: column; gap: 16px; }
    .back { align-self: flex-start; }
    mat-card-title { display: flex; align-items: center; gap: 8px; }
    .step { margin-top: 24px; }
    .step h3 { display: flex; align-items: center; gap: 8px; margin: 0 0 16px; font-weight: 500; }
    .num {
      display: inline-flex; align-items: center; justify-content: center;
      width: 24px; height: 24px; border-radius: 50%;
      background: #1565c0; color: #fff; font-size: 13px;
    }
    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; align-items: start; }
    .actions { grid-column: 1 / -1; display: flex; justify-content: flex-end; gap: 12px; }
    .full { width: 100%; }
    .connected { display: flex; align-items: center; gap: 6px; color: #2e7d32; margin: 0 0 16px; }
    .connected mat-icon { font-size: 20px; width: 20px; height: 20px; }
    .repo-name { font-weight: 500; }
    .repo-desc { color: #757575; font-size: 12px; }
    .tag {
      margin-left: 8px; padding: 1px 6px; border-radius: 8px;
      background: #e3f2fd; color: #1565c0; font-size: 11px;
    }
    .empty { color: #757575; }
    table { width: 100%; }
    .status { padding: 2px 8px; border-radius: 8px; background: #fff3e0; color: #e65100; font-size: 12px; }
    .status.ok { background: #e8f5e9; color: #2e7d32; }
    @media (max-width: 768px) { .grid { grid-template-columns: 1fr; } }
  `],
})
export class GithubConnectorComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly http = inject(HttpClient);
  private readonly notification = inject(NotificationService);

  readonly credentials = this.fb.nonNullable.group({
    githubUrl: ['https://github.com', Validators.required],
    token: ['', Validators.required],
  });
  readonly repoSearch = new FormControl<RepositorySummary | string>('', { nonNullable: true });
  readonly branch = new FormControl<string>('', { nonNullable: true });

  readonly columns = ['repo', 'branch', 'host', 'status', 'created'];

  connection: ConnectResponse | null = null;
  selectedRepo: RepositorySummary | null = null;
  branches: BranchSummary[] = [];
  connectors: ConnectorSummary[] = [];
  showToken = false;
  busy = false;

  readonly filteredRepos$ = this.repoSearch.valueChanges.pipe(
    startWith(''),
    map((value) => {
      const repos = this.connection?.repositories ?? [];
      const term = (typeof value === 'string' ? value : value?.fullName ?? '').toLowerCase();
      return term ? repos.filter((r) => r.fullName.toLowerCase().includes(term)) : repos;
    }),
  );

  private get basePath(): string {
    return `/workspaces/${sessionStorage.getItem('workspace_id')}/github`;
  }

  ngOnInit(): void {
    this.loadConnectors();
  }

  displayRepo = (repo: RepositorySummary | string | null): string =>
    typeof repo === 'string' ? repo : repo?.fullName ?? '';

  connect(): void {
    this.busy = true;
    this.resetSelection();
    this.http.post<ConnectResponse>(`${this.basePath}/repositories`, this.credentials.getRawValue()).subscribe({
      next: (response) => {
        this.connection = response;
        this.repoSearch.setValue('');
        this.busy = false;
      },
      error: () => (this.busy = false),
    });
  }

  selectRepo(repo: RepositorySummary): void {
    this.selectedRepo = repo;
    this.branches = [];
    this.branch.setValue('');
    this.busy = true;
    this.http.post<BranchSummary[]>(`${this.basePath}/branches`, {
      ...this.credentials.getRawValue(),
      owner: repo.owner,
      repo: repo.name,
    }).subscribe({
      next: (branches) => {
        this.branches = branches;
        const preferred = branches.find((b) => b.name === repo.defaultBranch) ?? branches[0];
        this.branch.setValue(preferred?.name ?? '');
        this.busy = false;
      },
      error: () => (this.busy = false),
    });
  }

  save(): void {
    if (!this.selectedRepo || !this.branch.value) return;
    this.busy = true;
    this.http.post<ConnectorSummary>(`${this.basePath}/connectors`, {
      ...this.credentials.getRawValue(),
      owner: this.selectedRepo.owner,
      repo: this.selectedRepo.name,
      branch: this.branch.value,
    }).subscribe({
      next: (saved) => {
        this.notification.showSuccess(`${saved.owner}/${saved.name} (${saved.branch}) saved for scanning`);
        this.busy = false;
        this.resetSelection();
        this.loadConnectors();
      },
      error: () => (this.busy = false),
    });
  }

  reset(): void {
    this.connection = null;
    this.credentials.controls.token.setValue('');
    this.resetSelection();
  }

  private resetSelection(): void {
    this.selectedRepo = null;
    this.branches = [];
    this.branch.setValue('');
    this.repoSearch.setValue('');
  }

  private loadConnectors(): void {
    this.http.get<ConnectorSummary[]>(`${this.basePath}/connectors`).subscribe({
      next: (connectors) => (this.connectors = connectors),
    });
  }
}
