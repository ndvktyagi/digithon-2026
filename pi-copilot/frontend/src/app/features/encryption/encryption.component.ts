import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { HttpClient } from '@angular/common/http';
import { NotificationService } from '../../core/notifications/notification.service';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { StatusBadgeComponent } from '../../shared/components/status-badge/status-badge.component';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog/confirm-dialog.component';

interface EncryptionProvider {
  id: string;
  providerType: string;
  name: string;
  endpoint?: string;
  keyName?: string;
  keyId?: string;
  authMethod?: string;
  rotationPolicyDays?: number;
  timeoutSeconds?: number;
  healthCheckEnabled: boolean;
  verified: boolean;
  development: boolean;
  createdAt?: string;
  updatedAt?: string;
}

const AUTH_LABELS: Record<string, string> = {
  WORKLOAD_IDENTITY: 'Workload Identity',
  MANAGED_IDENTITY: 'Managed Identity',
  SERVICE_ACCOUNT: 'Service Account',
};

const PROVIDER_LABELS: Record<string, string> = {
  AWS_KMS: 'AWS KMS',
  AZURE_KEY_VAULT: 'Azure Key Vault',
  GCP_KMS: 'Google Cloud KMS',
  VAULT_TRANSIT: 'HashiCorp Vault Transit',
  LOCAL_DEV: 'Local/Dev',
};

/** Fields that only apply to a remote key service; Local/Dev uses the key from server configuration. */
const REMOTE_ONLY_FIELDS = ['endpoint', 'authMethod'];

@Component({
  selector: 'app-encryption',
  standalone: true,
  imports: [
    PageHeaderComponent, StatusBadgeComponent, CommonModule, ReactiveFormsModule, MatCardModule, MatFormFieldModule,
    MatInputModule, MatSelectModule, MatButtonModule, MatIconModule, MatSlideToggleModule, MatDialogModule,
  ],
  template: `
    <div class="pc-page encryption-page">
      <app-page-header title="Encryption Provider Configuration"
        subtitle="Configure your key management service for PI data encryption."></app-page-header>

      <mat-card *ngIf="providers.length" class="providers-card">
        <mat-card-header>
          <mat-card-title><mat-icon>key</mat-icon> Configured Providers</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <ng-container *ngFor="let p of providers">
            <div class="provider-row" [class.editing]="editingId === p.id">
              <div class="provider-info">
                <strong>{{ p.name }}</strong>
                <span class="muted">{{ label(p.providerType) }} · {{ p.keyName || '—' }}{{ p.endpoint ? ' · ' + p.endpoint : '' }}</span>
              </div>
              <app-status-badge [label]="p.verified ? 'Verified' : 'Unverified'"
                                [status]="p.verified ? 'success' : 'pending'"></app-status-badge>
              <button mat-stroked-button type="button" [disabled]="checking === p.id" (click)="healthCheck(p)">
                <mat-icon>health_and_safety</mat-icon> Health Check
              </button>
              <div class="row-actions">
                <button mat-icon-button type="button" (click)="toggleView(p)"
                        [attr.aria-label]="'View ' + p.name" [title]="viewingId === p.id ? 'Hide details' : 'View'">
                  <mat-icon>{{ viewingId === p.id ? 'visibility_off' : 'visibility' }}</mat-icon>
                </button>
                <button mat-icon-button type="button" (click)="edit(p)" [attr.aria-label]="'Edit ' + p.name" title="Edit">
                  <mat-icon>edit</mat-icon>
                </button>
                <button mat-icon-button type="button" class="danger" (click)="confirmDelete(p)"
                        [attr.aria-label]="'Delete ' + p.name" title="Delete">
                  <mat-icon>delete</mat-icon>
                </button>
              </div>
            </div>
            <dl class="provider-details" *ngIf="viewingId === p.id">
              <div><dt>Provider Type</dt><dd>{{ label(p.providerType) }}</dd></div>
              <div><dt>Key Name</dt><dd>{{ p.keyName || '—' }}</dd></div>
              <div><dt>Key ID</dt><dd>{{ p.keyId || '—' }}</dd></div>
              <div><dt>Endpoint</dt><dd>{{ p.development ? 'Not used (local key)' : (p.endpoint || '—') }}</dd></div>
              <div><dt>Authentication</dt><dd>{{ p.development ? 'Not used (local key)' : (authLabel(p.authMethod) || '—') }}</dd></div>
              <div><dt>Rotation Policy</dt><dd>{{ p.rotationPolicyDays ? p.rotationPolicyDays + ' days' : '—' }}</dd></div>
              <div><dt>Timeout</dt><dd>{{ p.timeoutSeconds ? p.timeoutSeconds + ' seconds' : '—' }}</dd></div>
              <div><dt>Health Check</dt><dd>{{ p.healthCheckEnabled ? 'Enabled' : 'Disabled' }}</dd></div>
              <div><dt>Created</dt><dd>{{ p.createdAt ? (p.createdAt | date:'medium') : '—' }}</dd></div>
              <div><dt>Updated</dt><dd>{{ p.updatedAt ? (p.updatedAt | date:'medium') : '—' }}</dd></div>
            </dl>
          </ng-container>
        </mat-card-content>
      </mat-card>

      <mat-card>
        <mat-card-header>
          <mat-card-title>
            <mat-icon>{{ editingId ? 'edit' : 'vpn_key' }}</mat-icon> {{ editingId ? 'Edit Provider' : 'Add Provider' }}
          </mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <form [formGroup]="form" (ngSubmit)="onSubmit()">
            <mat-form-field class="full-width">
              <mat-label>Provider Type</mat-label>
              <mat-select formControlName="providerType">
                <mat-option value="AWS_KMS">AWS KMS</mat-option>
                <mat-option value="AZURE_KEY_VAULT">Azure Key Vault</mat-option>
                <mat-option value="GCP_KMS">Google Cloud KMS</mat-option>
                <mat-option value="VAULT_TRANSIT">HashiCorp Vault Transit</mat-option>
                <mat-option value="LOCAL_DEV">Local/Dev (Not for production)</mat-option>
              </mat-select>
            </mat-form-field>

            <div class="form-grid">
              <mat-form-field>
                <mat-label>Provider Name</mat-label>
                <input matInput formControlName="name" placeholder="production-kms" />
              </mat-form-field>

              <mat-form-field *ngIf="!isLocal">
                <mat-label>Endpoint / Address</mat-label>
                <input matInput formControlName="endpoint" placeholder="https://kms.us-east-1.amazonaws.com" />
              </mat-form-field>

              <mat-form-field>
                <mat-label>Key Name</mat-label>
                <input matInput formControlName="keyName" placeholder="picopilot-prod-key" />
              </mat-form-field>

              <mat-form-field *ngIf="!isLocal">
                <mat-label>Authentication Method</mat-label>
                <mat-select formControlName="authMethod">
                  <mat-option value="WORKLOAD_IDENTITY">Workload Identity</mat-option>
                  <mat-option value="MANAGED_IDENTITY">Managed Identity</mat-option>
                  <mat-option value="SERVICE_ACCOUNT">Service Account</mat-option>
                </mat-select>
              </mat-form-field>

              <mat-form-field>
                <mat-label>Rotation Policy (days)</mat-label>
                <input matInput type="number" formControlName="rotationPolicyDays" placeholder="90" />
              </mat-form-field>

              <mat-form-field>
                <mat-label>Timeout (seconds)</mat-label>
                <input matInput type="number" formControlName="timeoutSeconds" placeholder="10" />
              </mat-form-field>
            </div>

            <mat-slide-toggle formControlName="healthCheckEnabled" class="toggle">
              Enable Health Check (runs on save)
            </mat-slide-toggle>

            <div *ngIf="isLocal" class="dev-warning">
              <mat-icon>warning</mat-icon>
              <p><strong>Local/Dev mode is for development only.</strong>
                Data is encrypted with AES-256-GCM using the local key from server configuration
                (<code>PI_LOCAL_ENCRYPTION_KEY</code>); no endpoint or credentials are needed.
                Production deployments must use an approved customer-managed key service.</p>
            </div>

            <div class="form-actions">
              <button mat-stroked-button type="button" *ngIf="editingId" (click)="cancelEdit()">Cancel</button>
              <button mat-raised-button color="primary" type="submit" [disabled]="saving">
                <mat-icon>save</mat-icon> {{ editingId ? 'Update' : 'Save' }}
              </button>
            </div>
          </form>
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: [`
    .full-width { width: 100%; margin-bottom: 8px; }
    .form-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 16px;
    }
    .toggle { margin: 16px 0; display: block; }
    .dev-warning {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 12px 16px;
      background: #fff3e0;
      border-radius: 8px;
      color: #e65100;
      margin: 16px 0;
    }
    .form-actions {
      display: flex;
      justify-content: flex-end;
      gap: 12px;
      margin-top: 24px;
    }
    .providers-card { margin-bottom: 16px; }
    .provider-row {
      display: flex;
      align-items: center;
      gap: 16px;
      padding: 10px 0;
      border-bottom: 1px solid #f0f0f0;
    }
    .provider-row:last-of-type { border-bottom: none; }
    .provider-row.editing { background: var(--pc-primary-soft); margin: 0 -12px; padding: 10px 12px; border-radius: 8px; }
    .row-actions { display: flex; gap: 2px; }
    .row-actions .danger { color: #dc2626; }
    .provider-details {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
      gap: 12px 24px;
      margin: 0 0 12px;
      padding: 14px 16px;
      background: #f9fafb;
      border-radius: 8px;
    }
    .provider-details dt { font-size: 12px; color: #6b7280; }
    .provider-details dd { margin: 2px 0 0; font-weight: 500; word-break: break-word; }
    .provider-info { flex: 1; display: flex; flex-direction: column; min-width: 0; }
    .muted { color: #6b7280; font-size: 13px; overflow: hidden; text-overflow: ellipsis; }
    @media (max-width: 768px) {
      .form-grid { grid-template-columns: 1fr; }
      .provider-row { flex-wrap: wrap; }
    }
  `],
})
export class EncryptionComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly http = inject(HttpClient);
  private readonly notification = inject(NotificationService);
  private readonly dialog = inject(MatDialog);

  form!: FormGroup;
  providers: EncryptionProvider[] = [];
  saving = false;
  checking: string | null = null;
  viewingId: string | null = null;
  editingId: string | null = null;

  private get base(): string {
    return `/workspaces/${sessionStorage.getItem('workspace_id')}/encryption-providers`;
  }

  get isLocal(): boolean {
    return this.form.get('providerType')?.value === 'LOCAL_DEV';
  }

  ngOnInit(): void {
    this.form = this.fb.group({
      providerType: ['AWS_KMS', Validators.required],
      name: ['', Validators.required],
      endpoint: [''],
      keyName: ['', Validators.required],
      authMethod: ['WORKLOAD_IDENTITY', Validators.required],
      rotationPolicyDays: [90, [Validators.min(1), Validators.max(365)]],
      timeoutSeconds: [10],
      healthCheckEnabled: [true],
    });
    this.form.get('providerType')!.valueChanges.subscribe((type) => this.applyProviderType(type));
    this.load();
  }

  label(type: string): string {
    return PROVIDER_LABELS[type] ?? type;
  }

  load(): void {
    this.http.get<EncryptionProvider[]>(this.base).subscribe({
      next: (providers) => (this.providers = providers),
    });
  }

  healthCheck(provider: EncryptionProvider): void {
    this.checking = provider.id;
    this.http.post<EncryptionProvider>(`${this.base}/${provider.id}/validate`, {}).subscribe({
      next: () => {
        this.checking = null;
        this.notification.showSuccess(`Health check passed for ${provider.name}`);
        this.load();
      },
      error: (err) => {
        this.checking = null;
        this.notification.showError('Health check failed: ' + (err.error?.message ?? err.message));
        this.load();
      },
    });
  }

  authLabel(method?: string): string {
    return method ? AUTH_LABELS[method] ?? method : '';
  }

  toggleView(provider: EncryptionProvider): void {
    this.viewingId = this.viewingId === provider.id ? null : provider.id;
  }

  edit(provider: EncryptionProvider): void {
    this.editingId = provider.id;
    this.form.patchValue({ providerType: provider.providerType });
    this.form.patchValue({
      name: provider.name,
      endpoint: provider.endpoint ?? '',
      keyName: provider.keyName ?? '',
      authMethod: provider.authMethod ?? 'WORKLOAD_IDENTITY',
      rotationPolicyDays: provider.rotationPolicyDays ?? null,
      timeoutSeconds: provider.timeoutSeconds ?? null,
      healthCheckEnabled: provider.healthCheckEnabled,
    });
    window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' });
  }

  cancelEdit(): void {
    this.editingId = null;
    this.resetForm();
  }

  confirmDelete(provider: EncryptionProvider): void {
    const ref = this.dialog.open(ConfirmDialogComponent, { width: '440px' });
    ref.componentRef!.setInput('title', 'Delete encryption provider?');
    ref.componentRef!.setInput('message',
      `"${provider.name}" and its key records will be removed. This cannot be undone.`);
    ref.componentRef!.setInput('confirmLabel', 'Delete');
    ref.componentRef!.setInput('severity', 'error');
    ref.componentInstance.confirm.subscribe(() => this.delete(provider));
  }

  private delete(provider: EncryptionProvider): void {
    this.http.delete(`${this.base}/${provider.id}`).subscribe({
      next: () => {
        this.notification.showSuccess(`Provider ${provider.name} deleted`);
        if (this.editingId === provider.id) this.cancelEdit();
        if (this.viewingId === provider.id) this.viewingId = null;
        this.load();
      },
      error: (err) => this.notification.showError('Failed to delete provider: ' + (err.error?.message ?? err.message)),
    });
  }

  onSubmit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving = true;
    const editing = this.editingId;
    // Disabled (remote-only) controls are left out of form.value
    const request = editing
      ? this.http.put<EncryptionProvider>(`${this.base}/${editing}`, this.form.value)
      : this.http.post<EncryptionProvider>(this.base, this.form.value);
    request.subscribe({
      next: (saved) => {
        this.saving = false;
        this.notification.showSuccess(editing ? 'Encryption provider updated' : 'Encryption provider saved successfully');
        if (this.form.value.healthCheckEnabled) {
          this.healthCheck(saved);
        } else {
          this.load();
        }
        this.editingId = null;
        this.resetForm();
      },
      error: (err) => {
        this.saving = false;
        this.notification.showError('Failed to save provider: ' + (err.error?.message ?? err.message));
      },
    });
  }

  private resetForm(): void {
    this.form.reset({ ...this.form.getRawValue(), name: '', keyName: '' });
    this.applyProviderType(this.form.value.providerType);
  }

  private applyProviderType(type: string): void {
    const local = type === 'LOCAL_DEV';
    for (const field of REMOTE_ONLY_FIELDS) {
      const control = this.form.get(field)!;
      if (local) control.disable(); else control.enable();
    }
    if (local) {
      if (!this.form.value.name) this.form.patchValue({ name: 'local-dev' });
      if (!this.form.value.keyName) this.form.patchValue({ keyName: 'pi-local-dev-key' });
    }
  }
}
