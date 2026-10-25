import { Component, computed, input } from '@angular/core';
import { CommonModule } from '@angular/common';

type StatusColor = 'success' | 'warning' | 'error' | 'info' | 'neutral';

@Component({
  selector: 'app-status-badge',
  standalone: true,
  imports: [CommonModule],
  template: `
    <span class="status-badge" [class]="color()" [title]="tooltip() ?? label()">
      <span class="badge-dot"></span>
      {{ label() }}
    </span>
  `,
  styles: [`
    .status-badge {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 4px 10px;
      border-radius: 999px;
      font-size: 12px;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }
    .badge-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
    }
    .success { background: #ecfdf5; color: #047857; .badge-dot { background: #10b981; } }
    .warning { background: #fffbeb; color: #b45309; .badge-dot { background: #f59e0b; } }
    .error { background: #fef2f2; color: #b91c1c; .badge-dot { background: #ef4444; } }
    .info { background: var(--pc-primary-soft); color: var(--pc-primary-600); .badge-dot { background: var(--pc-primary); } }
    .neutral { background: #f3f4f6; color: #4b5563; .badge-dot { background: #9ca3af; } }
  `],
})
export class StatusBadgeComponent {
  readonly label = input.required<string>();
  readonly status = input.required<string>();
  readonly tooltip = input<string>();

  readonly color = computed<StatusColor>(() => {
    const s = this.status().toLowerCase();
    if (['active', 'success', 'completed', 'approved', 'retired', 'high', 'enabled', 'encrypted_read'].includes(s)) return 'success';
    if (['pending', 'running', 'discovered', 'preview', 'in_progress', 'dual_write', 'medium'].includes(s)) return 'warning';
    if (['failed', 'error', 'cancelled', 'revoked', 'rollback', 'low'].includes(s)) return 'error';
    if (['reviewed', 'plan_approved', 'compatibility_deployed', 'backfill_running', 'backfill_verified', 'encrypted_read_enabled', 'retirement_approved'].includes(s)) return 'info';
    return 'neutral';
  });
}