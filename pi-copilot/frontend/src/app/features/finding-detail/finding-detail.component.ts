import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatDividerModule } from '@angular/material/divider';
import { MatChipsModule } from '@angular/material/chips';
import { MatTabsModule } from '@angular/material/tabs';
import { MatListModule } from '@angular/material/list';
import { MatExpansionModule } from '@angular/material/expansion';
import { HttpClient } from '@angular/common/http';
import { StatusBadgeComponent } from '../../shared/components/status-badge/status-badge.component';
import { ConfidenceBarComponent } from '../../shared/components/confidence-bar/confidence-bar.component';

@Component({
  selector: 'app-finding-detail',
  standalone: true,
  imports: [
    CommonModule, MatCardModule, MatIconModule, MatButtonModule, MatDividerModule,
    MatChipsModule, MatTabsModule, MatListModule, MatExpansionModule,
    StatusBadgeComponent, ConfidenceBarComponent,
  ],
  template: `
    <div class="detail-page" *ngIf="finding() as f">
      <div class="page-header">
        <button mat-icon-button routerLink="/inventory">
          <mat-icon>arrow_back</mat-icon>
        </button>
        <h1>{{ f.columnName }}</h1>
        <app-status-badge [label]="f.status" [status]="f.status"></app-status-badge>
      </div>

      <div class="detail-grid">
        <!-- Key Info -->
        <mat-card>
          <mat-card-header><mat-card-title>Field Details</mat-card-title></mat-card-header>
          <mat-card-content>
            <div class="info-grid">
              <div class="info-item"><span class="label">Schema:</span> {{ f.dbSchema }}</div>
              <div class="info-item"><span class="label">Table:</span> {{ f.tableName }}</div>
              <div class="info-item"><span class="label">Column:</span> {{ f.columnName }}</div>
              <div class="info-item"><span class="label">Data Type:</span> {{ f.dataType }}</div>
              <div class="info-item"><span class="label">Nullable:</span> {{ f.nullable ? 'Yes' : 'No' }}</div>
              <div class="info-item"><span class="label">Category:</span> {{ f.candidateCategory }}</div>
              <div class="info-item"><span class="label">Detection:</span> {{ f.detectionMethod }}</div>
              <div class="info-item">
                <span class="label">Confidence:</span>
                <app-confidence-bar [confidence]="f.confidence"></app-confidence-bar>
              </div>
            </div>
          </mat-card-content>
        </mat-card>

        <!-- Evidence -->
        <mat-card>
          <mat-card-header><mat-card-title>Evidence</mat-card-title></mat-card-header>
          <mat-card-content>
            <mat-accordion *ngIf="evidence()?.length; else noEvidence">
              <mat-expansion-panel *ngFor="let e of evidence()">
                <mat-expansion-panel-header>
                  <mat-panel-title>
                    <mat-icon>description</mat-icon>
                    {{ e.evidenceType }}
                  </mat-panel-title>
                  <mat-panel-description>
                    {{ e.sourceFile }}{{ e.lineNumber ? ' (line ' + e.lineNumber + ')' : '' }}
                  </mat-panel-description>
                </mat-expansion-panel-header>
                <pre *ngIf="e.codeSnippet" class="code-snippet">{{ e.codeSnippet }}</pre>
                <p>{{ e.description }}</p>
              </mat-expansion-panel>
            </mat-accordion>
            <ng-template #noEvidence>
              <p class="empty-text">No evidence recorded.</p>
            </ng-template>
          </mat-card-content>
        </mat-card>

        <!-- AI/Rule Explanation -->
        <mat-card>
          <mat-card-header><mat-card-title>Analysis</mat-card-title></mat-card-header>
          <mat-card-content>
            <p><strong>Engine:</strong> {{ f.analysisEngine || 'RULES_ONLY' }}</p>
            <p><strong>Detector Rule:</strong> {{ f.detectorRule || 'N/A' }}</p>
            <mat-expansion-panel>
              <mat-expansion-panel-header>Reasoning</mat-expansion-panel-header>
              <p>{{ f.reasoning || 'No detailed reasoning available.' }}</p>
            </mat-expansion-panel>
            <p *ngIf="f.impactSummary">
              <strong>Impact Summary:</strong> {{ f.impactSummary }}
            </p>
          </mat-card-content>
        </mat-card>

        <!-- Review History -->
        <mat-card>
          <mat-card-header><mat-card-title>Review History</mat-card-title></mat-card-header>
          <mat-card-content>
            <mat-list *ngIf="reviews()?.length; else noReviews">
              <mat-list-item *ngFor="let r of reviews()">
                <mat-icon matListItemIcon>rate_review</mat-icon>
                <div matListItemTitle>{{ r.decision }} by {{ r.reviewerName }}</div>
                <div matListItemLine>{{ r.rationale }}</div>
                <div matListItemMeta>{{ r.reviewedAt | date:'medium' }}</div>
              </mat-list-item>
            </mat-list>
            <ng-template #noReviews>
              <p class="empty-text">Not yet reviewed.</p>
            </ng-template>
          </mat-card-content>
        </mat-card>
      </div>
    </div>
  `,
  styles: [`
    .detail-page { padding: 24px; }
    .page-header { display: flex; align-items: center; gap: 16px; margin-bottom: 24px; }
    h1 { margin: 0; }
    .detail-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(400px, 1fr));
      gap: 16px;
    }
    .info-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 12px;
    }
    .info-item { font-size: 14px; }
    .info-item .label { color: #757575; margin-right: 8px; font-weight: 500; }
    .code-snippet {
      background: #f5f5f5;
      padding: 12px;
      border-radius: 4px;
      font-family: 'JetBrains Mono', monospace;
      font-size: 12px;
      overflow-x: auto;
    }
    .empty-text { color: #757575; padding: 16px 0; }
  `],
})
export class FindingDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly http = inject(HttpClient);

  readonly finding = signal<any>(null);
  readonly evidence = signal<any[]>([]);
  readonly reviews = signal<any[]>([]);

  ngOnInit(): void {
    const findingId = this.route.snapshot.paramMap.get('findingId');
    if (findingId) {
      this.http.get<any>(`/findings/${findingId}`).subscribe((f) => {
        this.finding.set(f);
      });
      this.http.get<any[]>(`/findings/${findingId}/evidence`).subscribe((e) => {
        this.evidence.set(e);
      });
      this.http.get<any[]>(`/findings/${findingId}/reviews`).subscribe((r) => {
        this.reviews.set(r);
      });
    }
  }
}