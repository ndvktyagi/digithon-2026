import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatGridListModule } from '@angular/material/grid-list';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatDividerModule } from '@angular/material/divider';
import { RouterModule } from '@angular/router';
import { StatusBadgeComponent } from '../../shared/components/status-badge/status-badge.component';

interface DashboardCard {
  title: string;
  value: string | number;
  icon: string;
  route: string;
  color: string;
}

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    CommonModule, MatCardModule, MatGridListModule, MatIconModule,
    MatButtonModule, MatDividerModule, RouterModule, StatusBadgeComponent,
  ],
  template: `
    <div class="dashboard">
      <h1>Dashboard</h1>

      <div class="stats-grid">
        <mat-card *ngFor="let card of cards" class="stat-card" [routerLink]="card.route">
          <mat-card-content>
            <div class="stat-header">
              <mat-icon [style.color]="card.color">{{ card.icon }}</mat-icon>
              <span class="stat-value">{{ card.value }}</span>
            </div>
            <div class="stat-title">{{ card.title }}</div>
          </mat-card-content>
        </mat-card>
      </div>

      <div class="dashboard-grid">
        <mat-card class="section-card">
          <mat-card-header>
            <mat-card-title><mat-icon>play_arrow</mat-icon> Latest Scan</mat-card-title>
          </mat-card-header>
          <mat-card-content>
            <p class="empty-text">No scans yet. Start by connecting a repository and database.</p>
            <button mat-raised-button color="primary" routerLink="/connectors">
              Set Up Connectors
            </button>
          </mat-card-content>
        </mat-card>

        <mat-card class="section-card">
          <mat-card-header>
            <mat-card-title><mat-icon>warning</mat-icon> Open Findings</mat-card-title>
          </mat-card-header>
          <mat-card-content>
            <p class="empty-text">No findings to review.</p>
          </mat-card-content>
        </mat-card>

        <mat-card class="section-card">
          <mat-card-header>
            <mat-card-title><mat-icon>sync</mat-icon> Migration Jobs</mat-card-title>
          </mat-card-header>
          <mat-card-content>
            <p class="empty-text">No active migration jobs.</p>
          </mat-card-content>
        </mat-card>

        <mat-card class="section-card">
          <mat-card-header>
            <mat-card-title><mat-icon>history</mat-icon> Recent Audit Activity</mat-card-title>
          </mat-card-header>
          <mat-card-content>
            <p class="empty-text">No recent audit events.</p>
          </mat-card-content>
        </mat-card>
      </div>
    </div>
  `,
  styles: [`
    .dashboard { padding: 24px; }
    h1 { margin: 0 0 24px; font-weight: 500; }
    .stats-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: 16px;
      margin-bottom: 24px;
    }
    .stat-card {
      cursor: pointer;
      transition: transform 0.2s, box-shadow 0.2s;
    }
    .stat-card:hover {
      transform: translateY(-2px);
      box-shadow: 0 4px 12px rgba(0,0,0,0.15);
    }
    .stat-header {
      display: flex;
      align-items: center;
      gap: 12px;
    }
    .stat-header mat-icon { font-size: 32px; width: 32px; height: 32px; }
    .stat-value { font-size: 28px; font-weight: 600; }
    .stat-title { margin-top: 8px; color: #757575; font-size: 14px; }
    .dashboard-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(400px, 1fr));
      gap: 16px;
    }
    .section-card { min-height: 200px; }
    .empty-text { color: #757575; margin: 24px 0 16px; }
  `],
})
export class DashboardComponent implements OnInit {
  cards: DashboardCard[] = [];

  ngOnInit(): void {
    this.cards = [
      { title: 'Projects', value: 0, icon: 'folder', route: '/connectors', color: '#1565c0' },
      { title: 'Findings', value: 0, icon: 'search', route: '/inventory', color: '#e65100' },
      { title: 'Migrations', value: 0, icon: 'swap_horiz', route: '/backfill', color: '#2e7d32' },
      { title: 'Scans', value: 0, icon: 'radar', route: '/scans/new', color: '#6a1b9a' },
    ];
  }
}