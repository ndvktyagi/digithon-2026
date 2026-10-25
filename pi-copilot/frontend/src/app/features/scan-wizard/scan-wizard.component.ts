import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatStepperModule } from '@angular/material/stepper';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { HttpClient } from '@angular/common/http';
import { NotificationService } from '../../core/notifications/notification.service';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';

@Component({
  selector: 'app-scan-wizard',
  standalone: true,
  imports: [
    PageHeaderComponent, CommonModule, ReactiveFormsModule, MatCardModule, MatFormFieldModule,
    MatInputModule, MatSelectModule, MatButtonModule, MatIconModule,
    MatStepperModule, MatCheckboxModule, MatSlideToggleModule,
  ],
  template: `
    <div class="pc-page wizard-page">
      <app-page-header title="New Scan"
        subtitle="Choose a project and scope, then start PI discovery."></app-page-header>

      <mat-card>
        <mat-card-content>
          <mat-horizontal-stepper linear #stepper>
            <!-- Step 1: Scope -->
            <mat-step label="Scope" [stepControl]="scopeForm">
              <form [formGroup]="scopeForm">
                <mat-form-field class="full-width">
                  <mat-label>Scan Type</mat-label>
                  <mat-select formControlName="scanType">
                    <mat-option value="CODE">Code Only</mat-option>
                    <mat-option value="DATABASE">Database Only</mat-option>
                    <mat-option value="FULL">Full (Code + Database)</mat-option>
                  </mat-select>
                </mat-form-field>

                <mat-form-field class="full-width">
                  <mat-label>Analysis Engine</mat-label>
                  <mat-select formControlName="analysisEngine">
                    <mat-option value="RULES_ONLY">Rules Only (Deterministic)</mat-option>
                    <mat-option value="AI_ASSISTED">AI Assisted</mat-option>
                  </mat-select>
                  <mat-hint>AI-assisted mode requires an approved AI provider configuration</mat-hint>
                </mat-form-field>

                <mat-form-field class="full-width">
                  <mat-label>Repository Branch</mat-label>
                  <input matInput formControlName="branch" placeholder="main" />
                </mat-form-field>

                <mat-form-field class="full-width">
                  <mat-label>Repository Paths (comma-separated)</mat-label>
                  <input matInput formControlName="repoPaths" placeholder="src/main/java, src/main/resources" />
                  <mat-hint>Leave empty to scan entire repository</mat-hint>
                </mat-form-field>

                <mat-form-field class="full-width">
                  <mat-label>Database Schemas (comma-separated)</mat-label>
                  <input matInput formControlName="schemas" placeholder="public" />
                </mat-form-field>

                <div>
                  <button mat-raised-button color="primary" matStepperNext>Next</button>
                </div>
              </form>
            </mat-step>

            <!-- Step 2: Options -->
            <mat-step label="Options" [stepControl]="optionsForm">
              <form [formGroup]="optionsForm">
                <mat-slide-toggle formControlName="valuePatternScan" class="toggle">
                  Enable value-pattern scanning (opt-in, runs inside customer boundary)
                </mat-slide-toggle>

                <mat-slide-toggle formControlName="includeComments" class="toggle">
                  Include comments in code analysis
                </mat-slide-toggle>

                <mat-form-field class="full-width">
                  <mat-label>Max File Size (bytes)</mat-label>
                  <input matInput type="number" formControlName="maxFileSize" placeholder="1048576" />
                  <mat-hint>Files larger than this will be skipped</mat-hint>
                </mat-form-field>

                <mat-form-field class="full-width">
                  <mat-label>Excluded Paths</mat-label>
                  <input matInput formControlName="excludedPaths" placeholder="**/test/**, **/node_modules/**" />
                </mat-form-field>

                <mat-form-field class="full-width">
                  <mat-label>Idempotency Key</mat-label>
                  <input matInput formControlName="idempotencyKey" [value]="idempotencyKey()" />
                  <mat-hint>Auto-generated to prevent duplicate scans</mat-hint>
                </mat-form-field>

                <div class="step-actions">
                  <button mat-button matStepperPrevious>Back</button>
                  <button mat-raised-button color="primary" matStepperNext>Next</button>
                </div>
              </form>
            </mat-step>

            <!-- Step 3: Review & Launch -->
            <mat-step label="Review">
              <div class="review-section">
                <h3>Scan Summary</h3>
                <div class="summary-grid">
                  <div><strong>Type:</strong> {{ scopeForm.get('scanType')?.value }}</div>
                  <div><strong>Engine:</strong> {{ scopeForm.get('analysisEngine')?.value }}</div>
                  <div><strong>Branch:</strong> {{ scopeForm.get('branch')?.value || 'default' }}</div>
                  <div><strong>Value Pattern Scan:</strong> {{ optionsForm.get('valuePatternScan')?.value ? 'Yes' : 'No' }}</div>
                </div>
                <p class="note">
                  <mat-icon>info</mat-icon>
                  The scan will be read-only. No production data will be accessed. Secrets will not be logged.
                </p>
              </div>
              <div class="step-actions">
                <button mat-button matStepperPrevious>Back</button>
                <button mat-raised-button color="accent" (click)="launchScan()">
                  <mat-icon>play_arrow</mat-icon> Launch Scan
                </button>
              </div>
            </mat-step>
          </mat-horizontal-stepper>
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: [`
    .full-width { width: 100%; margin-bottom: 12px; }
    .toggle { margin-bottom: 16px; display: block; }
    .step-actions {
      display: flex;
      justify-content: flex-end;
      gap: 12px;
      margin-top: 24px;
    }
    .review-section { padding: 16px 0; }
    .summary-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 12px;
      margin: 16px 0;
    }
    .note {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 12px;
      background: var(--pc-primary-soft);
      border-radius: 8px;
      color: var(--pc-primary);
      font-size: 14px;
    }
    @media (max-width: 600px) { .summary-grid { grid-template-columns: 1fr; } }
  `],
})
export class ScanWizardComponent {
  private readonly fb = inject(FormBuilder);
  private readonly http = inject(HttpClient);
  private readonly notification = inject(NotificationService);

  readonly idempotencyKey = signal(crypto.randomUUID());

  scopeForm = this.fb.group({
    scanType: ['FULL', Validators.required],
    analysisEngine: ['RULES_ONLY', Validators.required],
    branch: ['main'],
    repoPaths: [''],
    schemas: ['public'],
  });

  optionsForm = this.fb.group({
    valuePatternScan: [false],
    includeComments: [true],
    maxFileSize: [1048576],
    excludedPaths: ['**/test/**'],
    idempotencyKey: [this.idempotencyKey()],
  });

  launchScan(): void {
    const payload = {
      scanJob: {
        ...this.scopeForm.value,
        idempotencyKey: this.idempotencyKey(),
      },
      scanScope: {
        repositoryPaths: this.scopeForm.value.repoPaths?.split(',').map((s: string) => s.trim()).filter(Boolean),
        schemas: this.scopeForm.value.schemas?.split(',').map((s: string) => s.trim()).filter(Boolean),
        valuePatternScanEnabled: this.optionsForm.value.valuePatternScan,
        maxFileSizeBytes: this.optionsForm.value.maxFileSize,
        excludedPaths: this.optionsForm.value.excludedPaths?.split(',').map((s: string) => s.trim()).filter(Boolean),
      },
    };

    this.http.post('/scans', payload).subscribe({
      next: (resp: any) => {
        this.notification.showSuccess('Scan created successfully');
        // Navigate to scan progress
      },
      error: (err) => this.notification.showError('Failed to create scan: ' + err.message),
    });
  }
}