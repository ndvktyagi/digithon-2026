import { Component, computed, input } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-confidence-bar',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="confidence-bar" [title]="confidence()">
      <div class="bar-fill" [style.width.%]="percentage()" [class]="confidence().toLowerCase()"></div>
      <span class="label">{{ confidence() }}</span>
    </div>
  `,
  styles: [`
    .confidence-bar {
      display: flex;
      align-items: center;
      gap: 8px;
      min-width: 120px;
    }
    .bar-fill {
      height: 8px;
      border-radius: 4px;
      transition: width 0.3s ease;
    }
    .bar-fill.high { width: 100%; background: #2e7d32; }
    .bar-fill.medium { width: 66%; background: #e65100; }
    .bar-fill.low { width: 33%; background: #c62828; }
    .label {
      font-size: 12px;
      font-weight: 600;
      text-transform: uppercase;
      min-width: 50px;
    }
  `],
})
export class ConfidenceBarComponent {
  readonly confidence = input.required<string>();

  readonly percentage = computed(() => {
    switch (this.confidence().toLowerCase()) {
      case 'high': return 100;
      case 'medium': return 66;
      case 'low': return 33;
      default: return 50;
    }
  });
}