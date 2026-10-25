import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { HttpClient } from '@angular/common/http';
import { interval, Subscription } from 'rxjs';
import { switchMap } from 'rxjs/operators';
import { StatusBadgeComponent } from '../../shared/components/status-badge/status-badge.component';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';

@Component({
  selector: 'app-scan-progress',
  standalone: true,
  imports: [
    PageHeaderComponent, CommonModule, MatCardModule, MatProgressBarModule, MatButtonModule,
    MatIconModule, MatListModule, StatusBadgeComponent,
  ],
  template: `
    <div class="pc-page progress-page">
      <app-page-header title="Scan Progress"
        subtitle="Live status of the running discovery scan."></app-page-header>

      <mat-card>
        <mat-card-header>
          <mat-card-title [style.display]="'flex'" [style.alignItems]="'center'" [style.gap]="'12px'">
            Scan Job
            <app-status-badge [label]="scan()?.status || 'PENDING'" [status]="scan()?.status || 'PENDING'"></app-status-badge>
          </mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <mat-progress-bar
            mode="determinate"
            [value]="progressPercent()"
            [color]="scan()?.status === 'FAILED' ? 'warn' : 'primary'">
          </mat-progress-bar>
          <p class="progress-text">{{ progressPercent() }}% complete</p>

          <div class="scan-stats" *ngIf="scan()">
            <div class="stat">
              <mat-icon>description</mat-icon>
              <span>{{ scan().totalFilesScanned || 0 }} files scanned</span>
            </div>
            <div class="stat">
              <mat-icon>table_chart</mat-icon>
              <span>{{ scan().totalTablesScanned || 0 }} tables scanned</span>
            </div>
            <div class="stat">
              <mat-icon>search</mat-icon>
              <span>{{ scan().findingsCount || 0 }} findings</span>
            </div>
          </div>

          <div *ngIf="scan()?.status === 'COMPLETED'" class="complete-msg">
            <mat-icon>check_circle</mat-icon>
            <span>Scan completed successfully. <a routerLink="/scans">View findings</a></span>
          </div>

          <div *ngIf="scan()?.status === 'FAILED'" class="error-msg">
            <mat-icon>error</mat-icon>
            <span>{{ scan()?.errorMessage || 'Scan failed' }}</span>
          </div>
        </mat-card-content>
        <mat-card-actions>
          <button mat-stroked-button color="warn" (click)="cancelScan()"
                  *ngIf="scan()?.status === 'RUNNING' || scan()?.status === 'PENDING'">
            <mat-icon>stop</mat-icon> Cancel Scan
          </button>
        </mat-card-actions>
      </mat-card>
    </div>
  `,
  styles: [`
    .progress-text { text-align: center; color: var(--pc-text-muted); margin: 8px 0 16px; }
    .scan-stats {
      display: flex;
      gap: 24px;
      margin: 16px 0;
    }
    .stat { display: flex; align-items: center; gap: 8px; }
    .complete-msg, .error-msg {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 12px;
      border-radius: 8px;
      margin: 16px 0;
    }
    .complete-msg { background: #e8f5e9; color: #2e7d32; }
    .error-msg { background: #ffebee; color: #c62828; }
  `],
})
export class ScanProgressComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly http = inject(HttpClient);

  readonly scan = signal<any>(null);
  readonly progressPercent = signal(0);
  private pollSub?: Subscription;

  ngOnInit(): void {
    const scanId = this.route.snapshot.paramMap.get('scanId');
    if (scanId) {
      this.pollSub = interval(3000)
        .pipe(switchMap(() => this.http.get<any>(`/scans/${scanId}`)))
        .subscribe((scan) => {
          this.scan.set(scan);
          if (scan.totalFilesScanned && scan.totalTablesScanned) {
            this.progressPercent.set(
              Math.min(95, ((scan.totalFilesScanned + scan.totalTablesScanned) / 100) * 100)
            );
          }
          if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(scan.status)) {
            this.pollSub?.unsubscribe();
            if (scan.status === 'COMPLETED') this.progressPercent.set(100);
          }
        });
    }
  }

  cancelScan(): void {
    const scanId = this.route.snapshot.paramMap.get('scanId');
    if (scanId) {
      this.http.post(`/scans/${scanId}/cancel`, {}).subscribe(() => {
        this.pollSub?.unsubscribe();
      });
    }
  }
}