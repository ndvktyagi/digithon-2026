import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterModule } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { StatusBadgeComponent } from '../../shared/components/status-badge/status-badge.component';
import { ConfidenceBarComponent } from '../../shared/components/confidence-bar/confidence-bar.component';

interface PiFinding {
  id: string;
  dbSchema: string;
  tableName: string;
  columnName: string;
  dataType: string;
  candidateCategory: string;
  confidence: string;
  detectionMethod: string;
  status: string;
  reviewedAt: string | null;
  reviewedBy: string | null;
}

@Component({
  selector: 'app-inventory',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatCardModule, MatTableModule, MatPaginatorModule,
    MatSortModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatIconModule,
    MatButtonModule, MatChipsModule, MatTooltipModule, RouterModule,
    StatusBadgeComponent, ConfidenceBarComponent,
  ],
  template: `
    <div class="inventory-page">
      <h1>PI Inventory</h1>

      <!-- Filters -->
      <mat-card class="filters-card">
        <mat-card-content>
          <div class="filter-row">
            <mat-form-field appearance="outline" class="search-field">
              <mat-label>Search</mat-label>
              <input matInput [(ngModel)]="search" (keyup.enter)="loadFindings()"
                     placeholder="Search tables, columns..." />
              <mat-icon matSuffix>search</mat-icon>
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Category</mat-label>
              <mat-select [(ngModel)]="categoryFilter" (selectionChange)="loadFindings()">
                <mat-option value="">All</mat-option>
                <mat-option value="DIRECT_IDENTIFIER">Direct Identifier</mat-option>
                <mat-option value="INDIRECT_IDENTIFIER">Indirect Identifier</mat-option>
                <mat-option value="SENSITIVE_PERSONAL">Sensitive Personal</mat-option>
                <mat-option value="CONTACT">Contact</mat-option>
                <mat-option value="FINANCIAL">Financial</mat-option>
                <mat-option value="LOCATION">Location</mat-option>
                <mat-option value="HEALTH">Health</mat-option>
                <mat-option value="CREDENTIAL">Credential</mat-option>
              </mat-select>
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Confidence</mat-label>
              <mat-select [(ngModel)]="confidenceFilter" (selectionChange)="loadFindings()">
                <mat-option value="">All</mat-option>
                <mat-option value="HIGH">High</mat-option>
                <mat-option value="MEDIUM">Medium</mat-option>
                <mat-option value="LOW">Low</mat-option>
              </mat-select>
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Status</mat-label>
              <mat-select [(ngModel)]="statusFilter" (selectionChange)="loadFindings()">
                <mat-option value="">All</mat-option>
                <mat-option value="DISCOVERED">Discovered</mat-option>
                <mat-option value="REVIEWED">Reviewed</mat-option>
                <mat-option value="FAILED">Failed</mat-option>
                <mat-option value="ROLLBACK">Rollback</mat-option>
              </mat-select>
            </mat-form-field>

            <button mat-stroked-button (click)="resetFilters()">
              <mat-icon>clear_all</mat-icon> Reset
            </button>
          </div>
        </mat-card-content>
      </mat-card>

      <!-- Table -->
      <mat-card class="table-card">
        <mat-card-content>
          <table mat-table [dataSource]="findings()" matSort (matSortChange)="onSortChange($event)">

            <ng-container matColumnDef="schema">
              <th mat-header-cell *matHeaderCellDef mat-sort-header>Schema</th>
              <td mat-cell *matCellDef="let f">{{ f.dbSchema }}</td>
            </ng-container>

            <ng-container matColumnDef="table">
              <th mat-header-cell *matHeaderCellDef mat-sort-header>Table</th>
              <td mat-cell *matCellDef="let f">{{ f.tableName }}</td>
            </ng-container>

            <ng-container matColumnDef="column">
              <th mat-header-cell *matHeaderCellDef mat-sort-header>Column</th>
              <td mat-cell *matCellDef="let f">
                <a [routerLink]="['/findings', f.id]" class="finding-link">{{ f.columnName }}</a>
              </td>
            </ng-container>

            <ng-container matColumnDef="category">
              <th mat-header-cell *matHeaderCellDef mat-sort-header>Category</th>
              <td mat-cell *matCellDef="let f">
                <mat-chip [class]="'chip-' + f.candidateCategory?.toLowerCase()" highlighted>
                  {{ formatCategory(f.candidateCategory) }}
                </mat-chip>
              </td>
            </ng-container>

            <ng-container matColumnDef="confidence">
              <th mat-header-cell *matHeaderCellDef mat-sort-header>Confidence</th>
              <td mat-cell *matCellDef="let f">
                <app-confidence-bar [confidence]="f.confidence"></app-confidence-bar>
              </td>
            </ng-container>

            <ng-container matColumnDef="status">
              <th mat-header-cell *matHeaderCellDef mat-sort-header>Status</th>
              <td mat-cell *matCellDef="let f">
                <app-status-badge [label]="f.status" [status]="f.status"></app-status-badge>
              </td>
            </ng-container>

            <ng-container matColumnDef="actions">
              <th mat-header-cell *matHeaderCellDef>Actions</th>
              <td mat-cell *matCellDef="let f">
                <button mat-icon-button [routerLink]="['/findings', f.id]"
                        matTooltip="View Details">
                  <mat-icon>visibility</mat-icon>
                </button>
              </td>
            </ng-container>

            <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
            <tr mat-row *matRowDef="let row; columns: displayedColumns"></tr>
          </table>

          <mat-paginator
            [length]="totalCount()"
            [pageSize]="pageSize"
            [pageSizeOptions]="[10, 25, 50]"
            (page)="onPageChange($event)">
          </mat-paginator>
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: [`
    .inventory-page { padding: 24px; }
    h1 { margin: 0 0 16px; }
    .filters-card { margin-bottom: 16px; }
    .filter-row {
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
      align-items: center;
    }
    .search-field { flex: 1; min-width: 200px; }
    .finding-link { color: #1565c0; text-decoration: none; font-weight: 500; cursor: pointer; }
    .finding-link:hover { text-decoration: underline; }
  `],
})
export class InventoryComponent implements OnInit {
  private readonly http = inject(HttpClient);

  readonly findings = signal<PiFinding[]>([]);
  readonly totalCount = signal(0);
  pageSize = 25;
  currentPage = 0;
  sortActive = '';
  sortDirection = '';

  search = '';
  categoryFilter = '';
  confidenceFilter = '';
  statusFilter = '';

  displayedColumns = ['schema', 'table', 'column', 'category', 'confidence', 'status', 'actions'];

  ngOnInit(): void {
    this.loadFindings();
  }

  loadFindings(): void {
    const params: any = {
      page: this.currentPage,
      size: this.pageSize,
      search: this.search || undefined,
      category: this.categoryFilter || undefined,
      confidence: this.confidenceFilter || undefined,
      status: this.statusFilter || undefined,
    };
    if (this.sortActive) {
      params.sort = `${this.sortActive},${this.sortDirection || 'asc'}`;
    }

    this.http.get<any>('/findings', { params }).subscribe({
      next: (response) => {
        this.findings.set(response.content || []);
        this.totalCount.set(response.totalElements || 0);
      },
    });
  }

  onPageChange(event: PageEvent): void {
    this.pageSize = event.pageSize;
    this.currentPage = event.pageIndex;
    this.loadFindings();
  }

  onSortChange(sort: Sort): void {
    this.sortActive = sort.active;
    this.sortDirection = sort.direction;
    this.loadFindings();
  }

  resetFilters(): void {
    this.search = '';
    this.categoryFilter = '';
    this.confidenceFilter = '';
    this.statusFilter = '';
    this.currentPage = 0;
    this.loadFindings();
  }

  formatCategory(cat: string): string {
    if (!cat) return '';
    return cat.replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
  }
}