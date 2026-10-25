import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { RouterModule } from '@angular/router';

@Component({
  selector: 'app-connectors',
  standalone: true,
  imports: [CommonModule, MatCardModule, MatIconModule, MatButtonModule, RouterModule],
  template: `
    <div class="connectors-page">
      <h1>Connectors</h1>
      <p class="subtitle">Configure connections to your repositories and databases for PI discovery.</p>

      <div class="connector-cards">
        <mat-card class="connector-card" routerLink="github">
          <mat-card-header>
            <mat-icon mat-card-avatar>code</mat-icon>
            <mat-card-title>GitHub Repository</mat-card-title>
            <mat-card-subtitle>GitHub or GitHub Enterprise via access token</mat-card-subtitle>
          </mat-card-header>
          <mat-card-content>
            <p>Connect to a GitHub repository to scan source code for PI references.</p>
          </mat-card-content>
          <mat-card-actions>
            <button mat-button color="primary">Configure</button>
          </mat-card-actions>
        </mat-card>

        <mat-card class="connector-card" routerLink="postgresql">
          <mat-card-header>
            <mat-icon mat-card-avatar>storage</mat-icon>
            <mat-card-title>PostgreSQL Database</mat-card-title>
            <mat-card-subtitle>Connect for schema and metadata analysis</mat-card-subtitle>
          </mat-card-header>
          <mat-card-content>
            <p>Configure a read-only database connection for PI discovery scanning.</p>
          </mat-card-content>
          <mat-card-actions>
            <button mat-button color="primary">Configure</button>
          </mat-card-actions>
        </mat-card>
      </div>
    </div>
  `,
  styles: [`
    .connectors-page { padding: 24px; }
    h1 { margin: 0 0 8px; }
    .subtitle { color: #757575; margin: 0 0 24px; }
    .connector-cards {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
      gap: 16px;
    }
    .connector-card { cursor: pointer; transition: box-shadow 0.2s; }
    .connector-card:hover { box-shadow: 0 4px 12px rgba(0,0,0,0.15); }
  `],
})
export class ConnectorsComponent {}