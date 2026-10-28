import { Component, EventEmitter, Input, OnInit, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { map, startWith } from 'rxjs/operators';
import { NotificationService } from '../../../core/notifications/notification.service';
import { connectorApiBase, GithubConnector } from '../connector.models';
import { FolderPickerComponent } from './folder-picker.component';

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

interface FoldersResponse {
  folders: string[];
  truncated: boolean;
}

interface BranchSummary {
  name: string;
  protectedBranch: boolean;
}

/** Create / edit form for a GitHub connector, embedded in a Connectors page tab. */
@Component({
  selector: 'app-github-connector',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule, MatCardModule, MatFormFieldModule,
    MatInputModule, MatSelectModule, MatButtonModule, MatIconModule, MatAutocompleteModule,
    MatProgressBarModule, FolderPickerComponent,
  ],
  template: `
    <mat-card>
      <mat-progress-bar *ngIf="busy" mode="indeterminate"></mat-progress-bar>
      <mat-card-header>
        <mat-card-title><mat-icon>code</mat-icon> {{ editing ? 'Edit' : 'New' }} GitHub Repository Connector</mat-card-title>
        <mat-card-subtitle>
          Connect any GitHub or GitHub Enterprise account with an access token, then choose the
          repository and branch to scan.
        </mat-card-subtitle>
      </mat-card-header>

      <mat-card-content>
        <!-- Edit: the repository is fixed, the token is optional -->
        <section class="step" *ngIf="editing">
          <h3><span class="num">1</span> Repository</h3>
          <p class="connected">
            <mat-icon>check_circle</mat-icon>
            <a [href]="editing.repositoryUrl" target="_blank" rel="noopener"><strong>{{ editing.owner }}/{{ editing.name }}</strong></a>
            <span class="muted">· {{ editing.apiBaseUrl }}</span>
          </p>
          <mat-form-field class="full">
            <mat-label>New Personal Access Token</mat-label>
            <input matInput [formControl]="newToken" [type]="showToken ? 'text' : 'password'"
                   autocomplete="off" placeholder="ghp_… or github_pat_…" />
            <button mat-icon-button matSuffix type="button" (click)="showToken = !showToken">
              <mat-icon>{{ showToken ? 'visibility' : 'visibility_off' }}</mat-icon>
            </button>
            <mat-hint>Leave blank to keep the current token</mat-hint>
          </mat-form-field>
        </section>

        <!-- New: step 1 credentials -->
        <section class="step" *ngIf="!editing && !connectorId">
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
        </ng-container>

        <!-- Step 3: branch -->
        <section class="step" *ngIf="selectedRepo">
          <h3><span class="num">{{ editing ? 2 : 3 }}</span> Select branch</h3>
          <div class="grid">
            <mat-form-field>
              <mat-label>Branch</mat-label>
              <mat-select [formControl]="branch">
                <mat-option *ngFor="let b of branches" [value]="b.name">
                  {{ b.name }}
                  <span class="tag" *ngIf="b.name === selectedRepo.defaultBranch">{{ editing ? 'current' : 'default' }}</span>
                  <span class="tag" *ngIf="b.protectedBranch">protected</span>
                </mat-option>
              </mat-select>
              <mat-hint>{{ branches.length }} branches</mat-hint>
            </mat-form-field>
            <div class="repo-link">
              <a mat-button [href]="selectedRepo.htmlUrl" target="_blank" rel="noopener">
                <mat-icon>open_in_new</mat-icon> View on GitHub
              </a>
            </div>
          </div>
        </section>

        <!-- Step 4: folders + project -->
        <section class="step" *ngIf="selectedRepo && branch.value">
          <h3><span class="num">{{ editing ? 3 : 4 }}</span> Select folders to scan</h3>
          <p *ngIf="folders === null" class="muted">Loading folders…</p>
          <app-folder-picker *ngIf="folders !== null"
                             [folders]="folders" [truncated]="foldersTruncated"
                             [initialSelection]="initialPaths"
                             (selectionChange)="includePaths = $event"></app-folder-picker>

          <mat-form-field class="full project">
            <mat-label>Application / Project Name</mat-label>
            <input matInput [formControl]="projectName" [placeholder]="selectedRepo.fullName" />
            <mat-hint>Use the same name as the database connector to scan them together. Defaults to the repository name.</mat-hint>
          </mat-form-field>
        </section>

        <div class="actions save-row">
          <button mat-button type="button" (click)="cancelled.emit()">Cancel</button>
          <button mat-raised-button color="primary" (click)="save()"
                  [disabled]="!selectedRepo || !branch.value || folders === null || busy">
            <mat-icon>save</mat-icon> {{ editing ? 'Update Connector' : 'Save for scanning' }}
          </button>
        </div>
      </mat-card-content>
    </mat-card>
  `,
  styles: [`
    mat-card-title { display: flex; align-items: center; gap: 8px; }
    .step { margin-top: 24px; }
    .step h3 { display: flex; align-items: center; gap: 8px; margin: 0 0 16px; font-weight: 500; }
    .num {
      display: inline-flex; align-items: center; justify-content: center;
      width: 24px; height: 24px; border-radius: 50%;
      background: var(--pc-primary); color: #fff; font-size: 13px;
    }
    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; align-items: start; }
    .actions { grid-column: 1 / -1; display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 12px; }
    .save-row { margin-top: 24px; }
    .repo-link { display: flex; align-items: center; height: 56px; }
    .muted { color: var(--pc-text-muted); }
    .full { width: 100%; }
    .project { margin-top: 20px; }
    .connected { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; color: #2e7d32; margin: 0 0 16px; }
    .connected mat-icon { font-size: 20px; width: 20px; height: 20px; }
    .repo-name { font-weight: 500; }
    .repo-desc { color: var(--pc-text-muted); font-size: 12px; }
    .tag {
      margin-left: 8px; padding: 1px 6px; border-radius: 8px;
      background: var(--pc-primary-soft); color: var(--pc-primary); font-size: 11px;
    }
    @media (max-width: 768px) { .grid { grid-template-columns: 1fr; } }
  `],
})
export class GithubConnectorComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly http = inject(HttpClient);
  private readonly notification = inject(NotificationService);

  /** Set to edit an existing connector; null creates a new one. */
  @Input() connectorId: string | null = null;
  @Output() saved = new EventEmitter<GithubConnector>();
  @Output() cancelled = new EventEmitter<void>();

  readonly credentials = this.fb.nonNullable.group({
    githubUrl: ['https://github.com/ndvktyagi/employee.git', Validators.required],
    token: ['', Validators.required],
  });
  readonly repoSearch = new FormControl<RepositorySummary | string>('', { nonNullable: true });
  readonly branch = new FormControl<string>('', { nonNullable: true });
  readonly projectName = new FormControl<string>('', { nonNullable: true });
  readonly newToken = new FormControl<string>('', { nonNullable: true });

  editing: GithubConnector | null = null;
  connection: ConnectResponse | null = null;
  selectedRepo: RepositorySummary | null = null;
  branches: BranchSummary[] = [];
  folders: string[] | null = null;
  foldersTruncated = false;
  initialPaths: string[] = [];
  includePaths: string[] = [];
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

  private readonly basePath = connectorApiBase('github');

  ngOnInit(): void {
    this.branch.valueChanges.subscribe((name) => this.loadFolders(name));
    if (this.connectorId) {
      this.loadConnector(this.connectorId);
    }
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
        const fromUrl = this.repoFromUrl(response.repositories);
        if (fromUrl) {
          this.repoSearch.setValue(fromUrl);
          this.selectRepo(fromUrl);
        }
      },
      error: () => (this.busy = false),
    });
  }

  selectRepo(repo: RepositorySummary): void {
    this.selectedRepo = repo;
    this.branches = [];
    this.branch.setValue('');
    this.busy = true;
    const request = this.editing
      ? this.http.get<BranchSummary[]>(`${this.basePath}/connectors/${this.editing.id}/branches`)
      : this.http.post<BranchSummary[]>(`${this.basePath}/branches`, {
          ...this.credentials.getRawValue(),
          owner: repo.owner,
          repo: repo.name,
        });
    request.subscribe({
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
    const request = this.editing
      ? this.http.put<GithubConnector>(`${this.basePath}/connectors/${this.editing.id}`, {
          projectName: this.projectName.value,
          token: this.newToken.value,
          branch: this.branch.value,
          includePaths: this.includePaths,
        })
      : this.http.post<GithubConnector>(`${this.basePath}/connectors`, {
          ...this.credentials.getRawValue(),
          owner: this.selectedRepo.owner,
          repo: this.selectedRepo.name,
          branch: this.branch.value,
          includePaths: this.includePaths,
          projectName: this.projectName.value,
        });
    request.subscribe({
      next: (saved) => {
        this.notification.showSuccess(`${saved.owner}/${saved.name} (${saved.branch}) saved for scanning`);
        this.busy = false;
        this.saved.emit(saved);
      },
      error: () => (this.busy = false),
    });
  }

  reset(): void {
    this.connection = null;
    this.credentials.controls.token.setValue('');
    this.resetSelection();
  }

  private loadConnector(id: string): void {
    this.busy = true;
    this.http.get<GithubConnector>(`${this.basePath}/connectors/${id}`).subscribe({
      next: (c) => {
        this.editing = c;
        this.projectName.setValue(c.projectName);
        this.initialPaths = c.includePaths;
        this.selectRepo({
          owner: c.owner, name: c.name, fullName: `${c.owner}/${c.name}`, description: null,
          privateRepo: false, defaultBranch: c.branch, htmlUrl: c.repositoryUrl, updatedAt: null,
        });
      },
      error: () => (this.busy = false),
    });
  }

  private loadFolders(branch: string): void {
    this.folders = null;
    this.includePaths = [];
    const repo = this.selectedRepo;
    if (!repo || !branch) return;
    const request = this.editing
      ? this.http.get<FoldersResponse>(`${this.basePath}/connectors/${this.editing.id}/folders`, { params: { branch } })
      : this.http.post<FoldersResponse>(`${this.basePath}/folders`, {
          ...this.credentials.getRawValue(),
          owner: repo.owner,
          repo: repo.name,
          branch,
        });
    request.subscribe({
      next: (response) => {
        // Ignore late responses for a previously selected repo/branch
        if (this.selectedRepo === repo && this.branch.value === branch) {
          // Saved folders only apply to the branch they were chosen on
          this.initialPaths = this.editing && branch === this.editing.branch ? this.editing.includePaths : [];
          this.folders = response.folders;
          this.foldersTruncated = response.truncated;
        }
      },
    });
  }

  /** Picks the repo when the URL was pasted as https://host/owner/repo(.git). */
  private repoFromUrl(repos: RepositorySummary[]): RepositorySummary | undefined {
    try {
      const parts = new URL(this.credentials.controls.githubUrl.value).pathname
        .replace(/\.git$/, '').split('/').filter(Boolean);
      if (parts.length < 2) return undefined;
      const fullName = `${parts[0]}/${parts[1]}`.toLowerCase();
      return repos.find((r) => r.fullName.toLowerCase() === fullName);
    } catch {
      return undefined;
    }
  }

  private resetSelection(): void {
    this.selectedRepo = null;
    this.branches = [];
    this.folders = null;
    this.includePaths = [];
    this.branch.setValue('');
    this.repoSearch.setValue('');
  }
}
