import { Component, EventEmitter, inject, Input, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { NotificationService } from '../../../core/notifications/notification.service';
import { connectorApiBase, PostgresConnector } from '../connector.models';

interface TestResult {
  success: boolean;
  serverVersion: string;
  schemas: string[];
}

/** Create / edit form for a PostgreSQL connector, embedded in a Connectors page tab. */
@Component({
  selector: 'app-postgresql-connector',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule, MatCardModule, MatFormFieldModule,
    MatInputModule, MatSelectModule, MatButtonModule, MatIconModule, MatChipsModule,
    MatProgressBarModule,
  ],
  template: `
    <mat-card>
      <mat-progress-bar *ngIf="busy" mode="indeterminate"></mat-progress-bar>
      <mat-card-header>
        <mat-card-title><mat-icon>storage</mat-icon> {{ connectorId ? 'Edit' : 'New' }} PostgreSQL Database Connector</mat-card-title>
        <mat-card-subtitle>
          Configure a read-only connection for PI discovery scanning. The password is encrypted at rest
          and never returned to the browser.
        </mat-card-subtitle>
      </mat-card-header>
      <mat-card-content>
        <form [formGroup]="form" (ngSubmit)="save()">
          <mat-form-field class="full-width">
            <mat-label>Application / Project Name*</mat-label>
            <input matInput formControlName="projectName" placeholder="Employee Management" />
            <mat-hint>Required to save. The connector is attached to this project (created if it doesn't exist)</mat-hint>
          </mat-form-field>

          <div class="form-grid">
            <mat-form-field>
              <mat-label>Host</mat-label>
              <input matInput formControlName="host" placeholder="db.example.com" />
              <mat-error *ngIf="form.controls.host.hasError('required')">Host is required</mat-error>
            </mat-form-field>

            <mat-form-field>
              <mat-label>Port</mat-label>
              <input matInput type="number" formControlName="port" placeholder="5432" />
            </mat-form-field>

            <mat-form-field>
              <mat-label>Database Name</mat-label>
              <input matInput formControlName="databaseName" placeholder="myapp_db" />
              <mat-error *ngIf="form.controls.databaseName.hasError('required')">Database name is required</mat-error>
            </mat-form-field>

            <mat-form-field>
              <mat-label>TLS Mode</mat-label>
              <mat-select formControlName="sslMode">
                <mat-option value="require">require</mat-option>
                <mat-option value="verify-ca">verify-ca</mat-option>
                <mat-option value="verify-full">verify-full</mat-option>
                <mat-option value="prefer">prefer (TLS if available)</mat-option>
                <mat-option value="disable">disable (local only, not recommended)</mat-option>
              </mat-select>
            </mat-form-field>

            <mat-form-field>
              <mat-label>Username</mat-label>
              <input matInput formControlName="username" autocomplete="off" placeholder="readonly_user" />
              <mat-hint>Use a read-only database user</mat-hint>
            </mat-form-field>

            <mat-form-field>
              <mat-label>{{ connectorId ? 'New Password' : 'Password' }}</mat-label>
              <input matInput [type]="showPassword ? 'text' : 'password'" formControlName="password" autocomplete="new-password" />
              <button mat-icon-button matSuffix type="button" (click)="showPassword = !showPassword">
                <mat-icon>{{ showPassword ? 'visibility' : 'visibility_off' }}</mat-icon>
              </button>
              <mat-hint *ngIf="connectorId">Leave blank to keep the current password</mat-hint>
            </mat-form-field>
          </div>

          <mat-form-field class="full-width">
            <mat-label>Schema Scope (comma-separated)</mat-label>
            <input matInput formControlName="schemaScope" placeholder="public, app_data" />
            <mat-hint>Leave empty to include all schemas</mat-hint>
          </mat-form-field>

          <div class="test-result" *ngIf="testResult">
            <div class="ok"><mat-icon>check_circle</mat-icon> Connected · {{ testResult.serverVersion }}</div>
            <div *ngIf="testResult.schemas.length" class="schemas">
              <span>Schemas found (click to add):</span>
              <mat-chip-set>
                <mat-chip *ngFor="let s of testResult.schemas" (click)="addSchema(s)"
                          [highlighted]="hasSchema(s)">{{ s }}</mat-chip>
              </mat-chip-set>
            </div>
          </div>

          <div class="form-actions">
            <button mat-button type="button" (click)="cancelled.emit()">Cancel</button>
            <button mat-stroked-button type="button" (click)="testConnection()" [disabled]="!form.valid || busy">
              <mat-icon>network_check</mat-icon> Test Connection
            </button>
            <button mat-raised-button color="primary" type="submit" [disabled]="!form.valid || !form.value.projectName || busy">
              <mat-icon>save</mat-icon> {{ connectorId ? 'Update Connector' : 'Save Connector' }}
            </button>
          </div>
        </form>
      </mat-card-content>
    </mat-card>
  `,
  styles: [`
    mat-card-title { display: flex; align-items: center; gap: 8px; }
    mat-card-content { padding-top: 24px; }
    .form-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 16px;
      margin-top: 16px;
    }
    .full-width { width: 100%; margin-top: 8px; }
    .form-actions {
      display: flex;
      justify-content: flex-end;
      flex-wrap: wrap;
      gap: 12px;
      margin-top: 24px;
    }
    .test-result {
      margin-top: 16px;
      padding: 12px 16px;
      border-radius: 4px;
      background: #e8f5e9;
    }
    .test-result .ok { display: flex; align-items: center; gap: 6px; color: #2e7d32; font-weight: 500; }
    .schemas { margin-top: 8px; color: #424242; }
    @media (max-width: 768px) {
      .form-grid { grid-template-columns: 1fr; }
    }
  `],
})
export class PostgresqlConnectorComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly http = inject(HttpClient);
  private readonly notification = inject(NotificationService);

  /** Set to edit an existing connector; null creates a new one. */
  @Input() connectorId: string | null = null;
  @Output() saved = new EventEmitter<PostgresConnector>();
  @Output() cancelled = new EventEmitter<void>();

  readonly form = this.fb.nonNullable.group({
    projectName: [''],
    host: ['', Validators.required],
    port: [5432, [Validators.required, Validators.min(1), Validators.max(65535)]],
    databaseName: ['', Validators.required],
    sslMode: ['require', Validators.required],
    username: ['', Validators.required],
    password: ['', Validators.required],
    schemaScope: [''],
  });

  testResult: TestResult | null = null;
  showPassword = false;
  busy = false;

  private readonly basePath = connectorApiBase('postgresql');

  ngOnInit(): void {
    if (this.connectorId) {
      this.loadConnector(this.connectorId);
    }
    this.form.valueChanges.subscribe(() => (this.testResult = null));
  }

  testConnection(): void {
    this.busy = true;
    const url = this.connectorId ? `${this.basePath}/connectors/${this.connectorId}/test` : `${this.basePath}/test`;
    this.http.post<TestResult>(url, this.form.getRawValue()).subscribe({
      next: (result) => {
        this.busy = false;
        this.testResult = result;
        this.notification.showSuccess('Database connection test successful');
      },
      error: () => (this.busy = false),
    });
  }

  save(): void {
    if (this.form.invalid) return;
    this.busy = true;
    const request = this.connectorId
      ? this.http.put<PostgresConnector>(`${this.basePath}/connectors/${this.connectorId}`, this.form.getRawValue())
      : this.http.post<PostgresConnector>(`${this.basePath}/connectors`, this.form.getRawValue());
    request.subscribe({
      next: (saved) => {
        this.busy = false;
        this.notification.showSuccess(`Database ${saved.databaseName} saved for project ${saved.projectName}`);
        this.form.controls.password.setValue('');
        this.saved.emit(saved);
      },
      error: () => (this.busy = false),
    });
  }

  hasSchema(schema: string): boolean {
    return this.schemaList().includes(schema);
  }

  addSchema(schema: string): void {
    const result = this.testResult;
    const schemas = this.schemaList();
    const next = schemas.includes(schema) ? schemas.filter((s) => s !== schema) : [...schemas, schema];
    this.form.controls.schemaScope.setValue(next.join(', '));
    this.testResult = result; // keep the result visible while editing scope
  }

  private schemaList(): string[] {
    return this.form.controls.schemaScope.value.split(',').map((s) => s.trim()).filter(Boolean);
  }

  private loadConnector(id: string): void {
    // The stored password is never returned, so it becomes optional when editing
    this.form.controls.password.clearValidators();
    this.form.controls.password.updateValueAndValidity();
    this.busy = true;
    this.http.get<PostgresConnector>(`${this.basePath}/connectors/${id}`).subscribe({
      next: (c) => {
        this.form.patchValue({
          projectName: c.projectName,
          host: c.host,
          port: c.port,
          databaseName: c.databaseName,
          sslMode: c.sslMode || 'require',
          username: c.username,
          password: '',
          schemaScope: c.schemaScope ?? '',
        });
        this.busy = false;
      },
      error: () => (this.busy = false),
    });
  }
}
