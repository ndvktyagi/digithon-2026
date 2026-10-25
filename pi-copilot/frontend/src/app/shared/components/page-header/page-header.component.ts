import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';

/** Standard page title row: optional back link, title + subtitle, and projected actions on the right. */
@Component({
  selector: 'app-page-header',
  standalone: true,
  imports: [CommonModule, RouterModule, MatIconModule],
  template: `
    <div class="page-header">
      <a *ngIf="backLink" class="back" [routerLink]="backLink" aria-label="Back">
        <mat-icon>arrow_back</mat-icon>
      </a>
      <div class="titles">
        <h1>{{ title }}<ng-content select="[headerBadge]"></ng-content></h1>
        <p *ngIf="subtitle" class="subtitle">{{ subtitle }}</p>
      </div>
      <div class="actions"><ng-content></ng-content></div>
    </div>
  `,
  styles: [`
    .page-header {
      display: flex; align-items: center; flex-wrap: wrap; gap: 12px 16px;
    }
    .titles { flex: 1; min-width: 220px; }
    h1 { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin: 0; }
    .subtitle { margin-top: 4px; color: var(--pc-text-muted); font-size: 14px; }
    .actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
    .actions:empty { display: none; }
    .back {
      display: grid; place-items: center; width: 40px; height: 40px; flex-shrink: 0;
      border: 1px solid var(--pc-border); border-radius: var(--pc-radius-sm);
      background: var(--pc-surface); color: var(--pc-text);
    }
    .back:hover { border-color: var(--pc-primary); color: var(--pc-primary); }
  `],
})
export class PageHeaderComponent {
  @Input({ required: true }) title = '';
  @Input() subtitle = '';
  @Input() backLink: string | null = null;
}
