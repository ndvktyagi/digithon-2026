import { Component, inject, signal, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { HttpClient } from '@angular/common/http';
import { StatusBadgeComponent } from '../../shared/components/status-badge/status-badge.component';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';

@Component({
  selector: 'app-admin',
  standalone: true,
  imports: [
    PageHeaderComponent, CommonModule, MatCardModule, MatTableModule, MatButtonModule, MatIconModule,
    MatDialogModule, MatFormFieldModule, MatSelectModule, StatusBadgeComponent,
  ],
  template: `
    <div class="pc-page admin-page">
      <app-page-header title="Administration"
        subtitle="Manage workspace members and their roles."></app-page-header>

      <mat-card>
        <mat-card-header>
          <mat-card-title><mat-icon>people</mat-icon> Workspace Members</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <table mat-table [dataSource]="members()">
            <ng-container matColumnDef="user">
              <th mat-header-cell *matHeaderCellDef>User</th>
              <td mat-cell *matCellDef="let m">{{ m.userName || m.userId }}</td>
            </ng-container>
            <ng-container matColumnDef="email">
              <th mat-header-cell *matHeaderCellDef>Email</th>
              <td mat-cell *matCellDef="let m">{{ m.userEmail }}</td>
            </ng-container>
            <ng-container matColumnDef="role">
              <th mat-header-cell *matHeaderCellDef>Role</th>
              <td mat-cell *matCellDef="let m">{{ m.role }}</td>
            </ng-container>
            <ng-container matColumnDef="status">
              <th mat-header-cell *matHeaderCellDef>Status</th>
              <td mat-cell *matCellDef="let m">
                <app-status-badge [label]="m.active ? 'Active' : 'Inactive'" [status]="m.active ? 'ACTIVE' : 'INACTIVE'"></app-status-badge>
              </td>
            </ng-container>
            <ng-container matColumnDef="actions">
              <th mat-header-cell *matHeaderCellDef>Actions</th>
              <td mat-cell *matCellDef="let m">
                <button mat-icon-button><mat-icon>edit</mat-icon></button>
                <button mat-icon-button color="warn"><mat-icon>remove_circle</mat-icon></button>
              </td>
            </ng-container>
            <tr mat-header-row *matHeaderRowDef="['user','email','role','status','actions']"></tr>
            <tr mat-row *matRowDef="let row; columns: ['user','email','role','status','actions']"></tr>
          </table>
        </mat-card-content>
        <mat-card-actions>
          <button mat-raised-button color="primary">
            <mat-icon>person_add</mat-icon> Add Member
          </button>
        </mat-card-actions>
      </mat-card>
    </div>
  `,
  styles: [`
  `],
})
export class AdminComponent implements OnInit {
  private readonly http = inject(HttpClient);
  readonly members = signal<any[]>([]);

  ngOnInit(): void {
    this.http.get<any[]>('/workspaces/current/members').subscribe({
      next: (members) => this.members.set(members),
    });
  }
}