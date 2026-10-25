import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { Router, RouterModule } from '@angular/router';
import { CdkDragDrop, DragDropModule, moveItemInArray } from '@angular/cdk/drag-drop';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { NotificationService } from '../../core/notifications/notification.service';
import { connectorApiBase, PostgresConnector } from '../connectors/connector.models';
import { ScanService } from './scan.models';

@Component({
  selector: 'app-new-scan',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterModule, DragDropModule, MatIconModule, MatButtonModule, MatCheckboxModule,
    MatFormFieldModule, MatInputModule, MatSlideToggleModule, MatTooltipModule, MatProgressBarModule,
    PageHeaderComponent,
  ],
  template: `
    <div class="pc-page">
      <app-page-header title="New Scan" backLink="/scans"
        subtitle="Choose the databases to scan and the order they run in."></app-page-header>

      <mat-progress-bar *ngIf="loading()" mode="indeterminate"></mat-progress-bar>

      <div class="layout" *ngIf="!loading()">
        <div class="col">
          <!-- 1. Databases -->
          <section class="panel">
            <div class="panel-head">
              <span class="step">1</span>
              <div>
                <h2>Databases</h2>
                <p class="pc-muted">Completed (verified) database connectors in this workspace.</p>
              </div>
            </div>

            <div class="empty" *ngIf="connectors().length === 0">
              <mat-icon>storage</mat-icon>
              <p>No completed database connectors yet. Add one and test its connection first.</p>
              <a mat-stroked-button color="primary" routerLink="/connectors">Open Connectors</a>
            </div>

            <div class="db-list" *ngIf="connectors().length > 0">
              <label *ngFor="let c of connectors()" class="db" [class.checked]="isSelected(c.id)">
                <mat-checkbox [checked]="isSelected(c.id)" (change)="toggle(c)"></mat-checkbox>
                <span class="db-icon"><mat-icon>storage</mat-icon></span>
                <span class="db-text">
                  <span class="db-name">{{ c.databaseName }}</span>
                  <span class="db-sub">{{ c.host }}:{{ c.port }} · {{ c.projectName }}</span>
                </span>
              </label>
              <p class="hint" *ngIf="unverifiedCount()">
                {{ unverifiedCount() }} unverified connector{{ unverifiedCount() > 1 ? 's are' : ' is' }} hidden.
                <a routerLink="/connectors">Test the connection</a> to include {{ unverifiedCount() > 1 ? 'them' : 'it' }}.
              </p>
              <div class="select-all" *ngIf="connectors().length > 1">
                <button mat-button type="button" (click)="selectAll()">Select all</button>
                <button mat-button type="button" (click)="order.set([])" [disabled]="order().length === 0">Clear</button>
              </div>
            </div>
          </section>

          <!-- 3. Options -->
          <section class="panel">
            <div class="panel-head">
              <span class="step">3</span>
              <div>
                <h2>Options</h2>
                <p class="pc-muted">Name the scan and fine-tune what it reads.</p>
              </div>
            </div>
            <mat-form-field appearance="outline" class="full">
              <mat-label>Scan name</mat-label>
              <input matInput [(ngModel)]="name" maxlength="150" [placeholder]="defaultName" />
            </mat-form-field>
            <mat-form-field appearance="outline" class="full">
              <mat-label>Schemas (optional)</mat-label>
              <input matInput [(ngModel)]="schemas" placeholder="public, sales" />
              <mat-hint>Comma-separated. Leave empty to use each connector's schema scope.</mat-hint>
            </mat-form-field>
            <mat-slide-toggle [(ngModel)]="valuePatternScan" class="toggle">
              Sample values to catch PI in generically named columns
            </mat-slide-toggle>
            <p class="hint">
              Reads up to 50 values per text column and matches them in memory (emails, phone numbers, IDs, card numbers).
              Values are never stored.
            </p>
          </section>
        </div>

        <!-- 2. Execution order -->
        <section class="panel order-panel">
          <div class="panel-head">
            <span class="step">2</span>
            <div>
              <h2>Execution order</h2>
              <p class="pc-muted">Drag to reorder. Databases are scanned one after another.</p>
            </div>
          </div>

          <div class="empty small" *ngIf="order().length === 0">
            <mat-icon>low_priority</mat-icon>
            <p>Select databases to set their order.</p>
          </div>

          <ol cdkDropList class="order-list" (cdkDropListDropped)="drop($event)" *ngIf="order().length > 0">
            <li *ngFor="let c of order(); let i = index; let first = first; let last = last" cdkDrag class="order-item">
              <span class="handle" cdkDragHandle aria-label="Drag to reorder"><mat-icon>drag_indicator</mat-icon></span>
              <span class="badge">DB-{{ i + 1 }}</span>
              <span class="db-text">
                <span class="db-name">{{ c.databaseName }}</span>
                <span class="db-sub">{{ c.host }}:{{ c.port }}</span>
              </span>
              <span class="move">
                <button mat-icon-button type="button" [disabled]="first" (click)="move(i, -1)" aria-label="Move up">
                  <mat-icon>arrow_upward</mat-icon>
                </button>
                <button mat-icon-button type="button" [disabled]="last" (click)="move(i, 1)" aria-label="Move down">
                  <mat-icon>arrow_downward</mat-icon>
                </button>
                <button mat-icon-button type="button" (click)="toggle(c)" aria-label="Remove">
                  <mat-icon>close</mat-icon>
                </button>
              </span>
            </li>
          </ol>

          <div class="start">
            <button mat-flat-button color="primary" type="button" class="start-btn"
                    [disabled]="order().length === 0 || starting()" (click)="start()">
              <mat-icon>play_arrow</mat-icon>
              {{ starting() ? 'Starting…' : 'Start Scan' + (order().length ? ' (' + order().length + ' database' + (order().length > 1 ? 's' : '') + ')' : '') }}
            </button>
          </div>
        </section>
      </div>
    </div>
  `,
  styles: [`
    .layout { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr); gap: 20px; align-items: start; }
    .col { display: flex; flex-direction: column; gap: 20px; min-width: 0; }
    .panel {
      padding: 20px; min-width: 0;
      background: var(--pc-surface); border: 1px solid var(--pc-border);
      border-radius: var(--pc-radius); box-shadow: var(--pc-shadow);
    }
    .order-panel { position: sticky; top: calc(var(--pc-header-height) + 16px); }
    .panel-head { display: flex; gap: 12px; align-items: flex-start; margin-bottom: 16px; }
    .panel-head h2 { margin: 0; }
    .panel-head p { margin: 2px 0 0; font-size: 13px; }
    .step {
      display: grid; place-items: center; width: 28px; height: 28px; flex-shrink: 0; border-radius: 50%;
      background: var(--pc-primary-soft); color: var(--pc-primary); font-weight: 700; font-size: 13px;
    }

    .db-list { display: flex; flex-direction: column; gap: 8px; }
    .db {
      display: flex; align-items: center; gap: 10px; padding: 10px 12px; cursor: pointer;
      border: 1px solid var(--pc-border); border-radius: var(--pc-radius-sm); transition: border-color 0.15s, background 0.15s;
    }
    .db:hover { border-color: var(--pc-primary); }
    .db.checked { border-color: var(--pc-primary); background: #faf9ff; }
    .db-icon {
      display: grid; place-items: center; width: 34px; height: 34px; flex-shrink: 0; border-radius: 9px;
      background: #e0f2fe; color: #0369a1;
    }
    .db-icon mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .db-text { display: flex; flex-direction: column; min-width: 0; flex: 1; }
    .db-name { font-weight: 600; color: var(--pc-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .db-sub { font-size: 12.5px; color: var(--pc-text-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .select-all { display: flex; gap: 4px; }

    .full { width: 100%; }
    .toggle { display: block; margin-top: 12px; }
    .hint { margin: 6px 0 0; font-size: 12.5px; color: var(--pc-text-subtle); }

    .order-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
    .order-item {
      display: flex; align-items: center; gap: 10px; padding: 8px 8px 8px 4px;
      background: var(--pc-surface); border: 1px solid var(--pc-border); border-radius: var(--pc-radius-sm);
    }
    .handle { display: grid; place-items: center; color: var(--pc-text-subtle); cursor: grab; }
    .badge {
      padding: 3px 8px; border-radius: 6px; background: var(--pc-primary); color: #fff;
      font-size: 12px; font-weight: 700; font-variant-numeric: tabular-nums;
    }
    .move { display: flex; }
    .cdk-drag-preview { box-shadow: var(--pc-shadow-hover); border-radius: var(--pc-radius-sm); }
    .cdk-drag-placeholder { opacity: 0.3; }
    .cdk-drag-animating, .order-list.cdk-drop-list-dragging .order-item:not(.cdk-drag-placeholder) {
      transition: transform 200ms ease;
    }

    .start { margin-top: 20px; }
    .start-btn { width: 100%; height: 44px; }

    .empty { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 24px 8px; text-align: center; color: var(--pc-text-muted); }
    .empty > mat-icon { font-size: 32px; width: 32px; height: 32px; color: var(--pc-primary); }
    .empty.small { padding: 16px 8px; }

    @media (max-width: 960px) {
      .layout { grid-template-columns: 1fr; }
      .order-panel { position: static; }
    }
  `],
})
export class NewScanComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly scanService = inject(ScanService);
  private readonly notifications = inject(NotificationService);

  readonly connectors = signal<PostgresConnector[]>([]);
  readonly order = signal<PostgresConnector[]>([]);
  readonly loading = signal(true);
  readonly starting = signal(false);
  readonly unverifiedCount = signal(0);

  readonly defaultName = `Database scan ${new Date().toISOString().slice(0, 10)}`;
  name = '';
  schemas = '';
  valuePatternScan = false;

  ngOnInit(): void {
    this.http.get<PostgresConnector[]>(`${connectorApiBase('postgresql')}/connectors`).subscribe({
      next: (list) => {
        const verified = list.filter((c) => c.verified);
        this.connectors.set(verified);
        this.unverifiedCount.set(list.length - verified.length);
        this.loading.set(false);
        // Preselect the only database so a single-DB workspace is one click away
        if (verified.length === 1) this.order.set(verified);
      },
      error: () => this.loading.set(false),
    });
  }

  isSelected(id: string): boolean {
    return this.order().some((c) => c.id === id);
  }

  toggle(c: PostgresConnector): void {
    this.order.update((list) => this.isSelected(c.id) ? list.filter((x) => x.id !== c.id) : [...list, c]);
  }

  selectAll(): void {
    const selected = this.order();
    const rest = this.connectors().filter((c) => !selected.some((s) => s.id === c.id));
    this.order.set([...selected, ...rest]);
  }

  drop(event: CdkDragDrop<PostgresConnector[]>): void {
    const list = [...this.order()];
    moveItemInArray(list, event.previousIndex, event.currentIndex);
    this.order.set(list);
  }

  move(index: number, delta: number): void {
    const list = [...this.order()];
    moveItemInArray(list, index, index + delta);
    this.order.set(list);
  }

  start(): void {
    this.starting.set(true);
    this.scanService.create({
      name: this.name.trim() || this.defaultName,
      databaseConnectorIds: this.order().map((c) => c.id),
      schemas: this.schemas.trim() || null,
      valuePatternScan: this.valuePatternScan,
    }).subscribe({
      next: (scan) => {
        this.notifications.showSuccess('Scan started');
        this.router.navigate(['/scans', scan.id]);
      },
      error: () => this.starting.set(false),
    });
  }
}
