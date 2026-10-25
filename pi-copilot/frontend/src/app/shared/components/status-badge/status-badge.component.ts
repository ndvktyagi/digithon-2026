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
      padding: 4px 12px;
      border-radius: 16px;
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
    .success { background: #e8f5e9; color: #2e7d32; .badge-dot { background: #2e7d32; } }
    .warning { background: #fff3e0; color: #e65100; .badge-dot { background: #e65100; } }
    .error { background: #ffebee; color: #c62828; .badge-dot { background: #c62828; } }
    .info { background: #e3f2fd; color: #1565c0; .badge-dot { background: #1565c0; } }
    .neutral { background: #f5f5f5; color: #616161; .badge-dot { background: #616161; } }
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