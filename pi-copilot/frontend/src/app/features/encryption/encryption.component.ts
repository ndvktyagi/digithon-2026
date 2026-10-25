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
import { HttpClient } from '@angular/common/http';
import { NotificationService } from '../../core/notifications/notification.service';

@Component({
  selector: 'app-encryption',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule, MatCardModule, MatFormFieldModule,
    MatInputModule, MatSelectModule, MatButtonModule, MatIconModule, MatSlideToggleModule,
  ],
  template: `
    <div class="encryption-page">
      <h1>Encryption Provider Configuration</h1>
      <p class="subtitle">Configure your key management service for PI data encryption.</p>

      <mat-card>
        <mat-card-header>
          <mat-card-title><mat-icon>vpn_key</mat-icon> Add Provider</mat-card-title>
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

              <mat-form-field>
                <mat-label>Endpoint / Address</mat-label>
                <input matInput formControlName="endpoint" placeholder="https://kms.us-east-1.amazonaws.com" />
              </mat-form-field>

              <mat-form-field>
                <mat-label>Key Name</mat-label>
                <input matInput formControlName="keyName" placeholder="picopilot-prod-key" />
              </mat-form-field>

              <mat-form-field>
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
              Enable Health Check
            </mat-slide-toggle>

            <div *ngIf="form.get('providerType')?.value === 'LOCAL_DEV'" class="dev-warning">
              <mat-icon>warning</mat-icon>
              <p><strong>Local/Dev mode is for development only.</strong> Production deployments must use an approved customer-managed key service.</p>
            </div>

            <div class="form-actions">
              <button mat-stroked-button type="button" (click)="healthCheck()">
                <mat-icon>health_and_safety</mat-icon> Health Check
              </button>
              <button mat-raised-button color="primary" type="submit">
                <mat-icon>save</mat-icon> Save
              </button>
            </div>
          </form>
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: [`
    .encryption-page { padding: 24px; }
    h1 { margin: 0 0 8px; }
    .subtitle { color: #757575; margin: 0 0 24px; }
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
    @media (max-width: 768px) {
      .form-grid { grid-template-columns: 1fr; }
    }
  `],
})
export class EncryptionComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly http = inject(HttpClient);
  private readonly notification = inject(NotificationService);

  form!: FormGroup;

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
  }

  healthCheck(): void {
    this.http.post('/encryption-providers/validate', this.form.value).subscribe({
      next: () => this.notification.showSuccess('Provider health check passed'),
      error: (err) => this.notification.showError('Health check failed: ' + err.message),
    });
  }

  onSubmit(): void {
    if (this.form.invalid) return;
    this.http.post('/encryption-providers', this.form.value).subscribe({
      next: () => this.notification.showSuccess('Encryption provider saved successfully'),
      error: (err) => this.notification.showError('Failed to save provider: ' + err.message),
    });
  }
}