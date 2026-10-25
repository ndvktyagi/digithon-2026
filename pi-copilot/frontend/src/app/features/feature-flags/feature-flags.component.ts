import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatSlideToggleChange, MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { NotificationService } from '../../core/notifications/notification.service';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { StatusBadgeComponent } from '../../shared/components/status-badge/status-badge.component';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog/confirm-dialog.component';

interface DatabaseSwitch {
  connectorId: string;
  database: string;
  enabled: boolean | null;
  error: string | null;
}

interface SwitchStatus {
  enabled: boolean;
  stage: string;
  branchName: string | null;
  prNumber: number | null;
  prUrl: string | null;
  tables: string[];
  databases: DatabaseSwitch[];
  canEnable: boolean;
  problems: string[];
  readGaps: string[];
  enabledAt: string | null;
  enabledBy: string | null;
  disabledAt: string | null;
  disabledBy: string | null;
  disableReason: string | null;
}

/**
 * Enable Encryption: one switch that moves the protected application's reads to the encrypted
 * columns. It writes pi_feature_flag in the target database, which the generated PiEncryptionFlags polls.
 */
@Component({
  selector: 'app-feature-flags',
  standalone: true,
  imports: [
    PageHeaderComponent, StatusBadgeComponent, CommonModule, MatCardModule, MatSlideToggleModule,
    MatButtonModule, MatIconModule, MatDialogModule,
  ],
  template: `
    <div class="pc-page">
      <app-page-header title="Enable Encryption"
        subtitle="Switch the application to read personal data from the encrypted columns."></app-page-header>

      <mat-card *ngIf="status() as s; else loadingTpl">
        <mat-card-content>
          <div class="switch-row">
            <div class="switch-icon" [class.on]="s.enabled">
              <mat-icon>{{ s.enabled ? 'lock' : 'lock_open' }}</mat-icon>
            </div>
            <div class="switch-text">
              <h2>Encrypted read</h2>
              <p *ngIf="s.enabled && !s.readGaps.length">
                <strong>On.</strong> All requests read PI values from the encrypted <code>_enc</code> columns.
              </p>
              <p *ngIf="s.enabled && s.readGaps.length">
                <strong>On, with gaps.</strong> Reads use the encrypted columns except where listed below.
              </p>
              <p *ngIf="!s.enabled">
                <strong>Off.</strong> Requests read the plain columns. New writes already go to both.
              </p>
            </div>
            <mat-slide-toggle color="primary" [checked]="s.enabled" [disabled]="busy() || (!s.enabled && !s.canEnable)"
                              (change)="onToggle($event, s)" aria-label="Encrypted read"></mat-slide-toggle>
          </div>

          <ul class="problems" *ngIf="!s.enabled && s.problems.length">
            <li *ngFor="let p of s.problems"><mat-icon>info</mat-icon>{{ p }}</li>
          </ul>

          <ul class="problems gaps" *ngIf="s.readGaps.length">
            <li *ngFor="let g of s.readGaps"><mat-icon>warning</mat-icon>{{ g }}</li>
          </ul>

          <div class="note">
            <mat-icon>schedule</mat-icon>
            <span>The application checks the switch every 5 seconds, so no redeploy is needed. It needs the code from
              Implementation (<code>PiEncryptionFlags</code>) to be deployed. Turning it off rolls reads back to the plain columns.</span>
          </div>
        </mat-card-content>
      </mat-card>

      <div class="grid" *ngIf="status() as s">
        <mat-card>
          <mat-card-header><mat-card-title><mat-icon>storage</mat-icon> Databases</mat-card-title></mat-card-header>
          <mat-card-content>
            <p class="muted" *ngIf="!s.databases.length">No approved implementation yet.</p>
            <div class="db-row" *ngFor="let d of s.databases">
              <div class="db-info">
                <strong>{{ d.database }}</strong>
                <span class="muted error" *ngIf="d.error">{{ d.error }}</span>
              </div>
              <app-status-badge *ngIf="d.enabled !== null" [label]="d.enabled ? 'Encrypted read' : 'Plain read'"
                                [status]="d.enabled ? 'success' : 'neutral'"></app-status-badge>
              <app-status-badge *ngIf="d.enabled === null" label="Unknown" status="error"></app-status-badge>
            </div>
            <div class="tables" *ngIf="s.tables.length">
              <span class="muted">Tables:</span>
              <code *ngFor="let t of s.tables">{{ t }}</code>
            </div>
          </mat-card-content>
        </mat-card>

        <mat-card>
          <mat-card-header><mat-card-title><mat-icon>history</mat-icon> Activity</mat-card-title></mat-card-header>
          <mat-card-content>
            <dl class="facts">
              <div *ngIf="s.branchName"><dt>Code branch</dt>
                <dd><a *ngIf="s.prUrl; else plainBranch" [href]="s.prUrl" target="_blank" rel="noopener">
                  {{ s.branchName }}{{ s.prNumber ? ' (PR #' + s.prNumber + ')' : '' }}</a>
                  <ng-template #plainBranch>{{ s.branchName }}</ng-template></dd></div>
              <div><dt>Last enabled</dt>
                <dd>{{ s.enabledAt ? (s.enabledAt | date:'medium') + ' by ' + s.enabledBy : '—' }}</dd></div>
              <div><dt>Last disabled</dt>
                <dd>{{ s.disabledAt ? (s.disabledAt | date:'medium') + ' by ' + s.disabledBy : '—' }}</dd></div>
            </dl>
          </mat-card-content>
        </mat-card>
      </div>

      <ng-template #loadingTpl>
        <mat-card><mat-card-content><p class="muted center">{{ loadError() || 'Loading…' }}</p></mat-card-content></mat-card>
      </ng-template>
    </div>
  `,
  styles: [`
    .switch-row { display: flex; align-items: center; gap: 16px; }
    .switch-icon {
      display: grid; place-items: center; width: 48px; height: 48px; flex-shrink: 0;
      border-radius: 12px; background: #f3f4f6; color: #6b7280;
    }
    .switch-icon.on { background: #ecfdf5; color: #047857; }
    .switch-text { flex: 1; min-width: 0; }
    .switch-text h2 { margin: 0 0 4px; font-size: 18px; }
    .switch-text p { margin: 0; color: #4b5563; }
    .problems { list-style: none; margin: 16px 0 0; padding: 12px 16px; background: #fffbeb; border-radius: 8px; color: #b45309; }
    .problems li { display: flex; align-items: center; gap: 8px; padding: 2px 0; }
    .problems mat-icon { font-size: 18px; width: 18px; height: 18px; flex-shrink: 0; }
    .problems.gaps { background: #fef2f2; color: #b91c1c; }
    .note { display: flex; gap: 8px; margin-top: 16px; color: #6b7280; font-size: 13px; }
    .note mat-icon { font-size: 18px; width: 18px; height: 18px; flex-shrink: 0; }
    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-top: 16px; }
    .db-row { display: flex; align-items: center; gap: 12px; padding: 8px 0; border-bottom: 1px solid #f0f0f0; }
    .db-row:last-of-type { border-bottom: none; }
    .db-info { flex: 1; display: flex; flex-direction: column; min-width: 0; word-break: break-word; }
    .tables { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; margin-top: 12px; }
    .tables code { background: #f3f4f6; padding: 2px 6px; border-radius: 4px; font-size: 12px; }
    .facts { margin: 0; display: grid; gap: 10px; }
    .facts dt { font-size: 12px; color: #6b7280; }
    .facts dd { margin: 2px 0 0; word-break: break-word; }
    .muted { color: #6b7280; font-size: 13px; }
    .error { color: #b91c1c; }
    .center { text-align: center; }
    @media (max-width: 768px) {
      .grid { grid-template-columns: 1fr; }
      .switch-row { flex-wrap: wrap; }
    }
  `],
})
export class FeatureFlagsComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly dialog = inject(MatDialog);
  private readonly notification = inject(NotificationService);

  readonly status = signal<SwitchStatus | null>(null);
  readonly busy = signal(false);
  readonly loadError = signal<string | null>(null);

  private get base(): string {
    return `/workspaces/${sessionStorage.getItem('workspace_id')}/encryption-switch`;
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.http.get<SwitchStatus>(this.base).subscribe({
      next: (s) => this.status.set(s),
      error: (err) => this.loadError.set('Could not load the switch: ' + (err.error?.message ?? err.message)),
    });
  }

  onToggle(event: MatSlideToggleChange, current: SwitchStatus): void {
    // Keep the toggle where it was until the user confirms
    event.source.checked = current.enabled;
    const enable = event.checked;
    const ref = this.dialog.open(ConfirmDialogComponent, { width: '460px' });
    ref.componentRef!.setInput('title', enable ? 'Enable encrypted read?' : 'Disable encrypted read?');
    ref.componentRef!.setInput('message', enable
      ? 'Within a few seconds every request will read personal data from the encrypted columns.'
      : 'Requests will go back to reading the plain columns. Writes keep going to both.');
    ref.componentRef!.setInput('confirmLabel', enable ? 'Enable' : 'Disable');
    ref.componentRef!.setInput('severity', enable ? 'info' : 'warning');
    ref.componentInstance.confirm.subscribe(() => this.apply(enable));
  }

  private apply(enabled: boolean): void {
    this.busy.set(true);
    this.http.put<SwitchStatus>(this.base, { enabled, reason: null }).subscribe({
      next: (s) => {
        this.busy.set(false);
        this.status.set(s);
        this.notification.showSuccess(enabled ? 'Encrypted read enabled' : 'Encrypted read disabled');
      },
      error: (err) => {
        this.busy.set(false);
        this.notification.showError('Could not change the switch: ' + (err.error?.message ?? err.message));
        this.load();
      },
    });
  }
}
