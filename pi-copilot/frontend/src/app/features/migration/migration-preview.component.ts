import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDividerModule } from '@angular/material/divider';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTableModule } from '@angular/material/table';
import { HttpClient } from '@angular/common/http';
import { StatusBadgeComponent } from '../../shared/components/status-badge/status-badge.component';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';

@Component({
  selector: 'app-migration-preview',
  standalone: true,
  imports: [
    PageHeaderComponent, CommonModule, MatCardModule, MatButtonModule, MatIconModule, MatDividerModule,
    MatTabsModule, MatTableModule, StatusBadgeComponent,
  ],
  template: `
    <div class="pc-page migration-page">
      <app-page-header title="Migration Plan Preview"
        subtitle="Review generated changes before approving the migration."></app-page-header>

      <mat-card>
        <mat-card-header>
          <mat-card-title>{{ plan()?.name || 'Migration Plan' }}</mat-card-title>
          <app-status-badge [label]="plan()?.state || 'PREVIEW'" [status]="plan()?.state || 'PREVIEW'"></app-status-badge>
        </mat-card-header>
        <mat-card-content>
          <mat-tab-group>
            <mat-tab label="DDL Script">
              <pre class="code-block">{{ plan()?.ddlScript || 'No DDL script generated yet.' }}</pre>
            </mat-tab>
            <mat-tab label="Rollback DDL">
              <pre class="code-block">{{ plan()?.rollbackDdl || 'No rollback DDL script generated yet.' }}</pre>
            </mat-tab>
            <mat-tab label="Impacted Tables">
              <div class="info-section">
                <p><strong>Estimated Row Count:</strong> {{ plan()?.estimatedRowCount || 0 }}</p>
                <p><strong>Impacted Tables:</strong> {{ plan()?.impactedTables || 'None identified' }}</p>
              </div>
            </mat-tab>
            <mat-tab label="Test Plan">
              <div class="info-section">
                <p>{{ plan()?.testPlan || 'No test plan generated.' }}</p>
              </div>
            </mat-tab>
            <mat-tab label="Rollback Plan">
              <div class="info-section">
                <p>{{ plan()?.rollbackPlan || 'No rollback plan generated.' }}</p>
              </div>
            </mat-tab>
          </mat-tab-group>

          <mat-divider style="margin: 24px 0;"></mat-divider>

          <div *ngIf="plan()?.prUrl" class="pr-info">
            <mat-icon>link</mat-icon>
            <span>Pull Request: <a [href]="plan().prUrl" target="_blank">#{{ plan().prNumber }}</a></span>
          </div>
        </mat-card-content>
        <mat-card-actions>
          <button mat-stroked-button>
            <mat-icon>download</mat-icon> Download Plan
          </button>
          <button mat-raised-button color="primary"
                  *ngIf="plan()?.state === 'PREVIEW'">
            <mat-icon>check</mat-icon> Approve Plan
          </button>
        </mat-card-actions>
      </mat-card>
    </div>
  `,
  styles: [`
    .code-block {
      background: #f5f5f5;
      padding: 16px;
      border-radius: 4px;
      font-family: 'JetBrains Mono', monospace;
      font-size: 13px;
      overflow-x: auto;
      white-space: pre-wrap;
    }
    .info-section { padding: 16px 0; }
    .pr-info {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 12px;
      background: #e8f5e9;
      border-radius: 8px;
    }
  `],
})
export class MigrationPreviewComponent implements OnInit {
  private readonly http = inject(HttpClient);
  readonly plan = signal<any>(null);

  ngOnInit(): void {
    // Plan data would be loaded from API
  }
}