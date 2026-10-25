import { Component, EventEmitter, Input, OnChanges, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatChipsModule } from '@angular/material/chips';

interface FolderNode {
  path: string;
  name: string;
  depth: number;
  children: FolderNode[];
}

interface Row {
  node: FolderNode;
  label: string;
  depth: number;
}

/** Tree of repository folders with multi-select. Selecting a folder includes everything below it. */
@Component({
  selector: 'app-folder-picker',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatCheckboxModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatChipsModule,
  ],
  template: `
    <div class="summary">
      <ng-container *ngIf="selected.size === 0; else chips">
        <mat-icon>select_all</mat-icon> Whole repository will be scanned. Tick folders to limit the scan.
      </ng-container>
      <ng-template #chips>
        <mat-chip-set>
          <mat-chip *ngFor="let path of selectedList" (removed)="toggle(path)">
            <mat-icon matChipAvatar>folder</mat-icon>{{ path }}
            <button matChipRemove><mat-icon>cancel</mat-icon></button>
          </mat-chip>
        </mat-chip-set>
        <button mat-button (click)="clear()">Clear</button>
      </ng-template>
    </div>

    <mat-form-field class="search" subscriptSizing="dynamic">
      <mat-icon matPrefix>search</mat-icon>
      <input matInput placeholder="Filter folders" [(ngModel)]="filter" (ngModelChange)="refresh()" />
    </mat-form-field>

    <div class="tree">
      <div *ngIf="rows.length === 0" class="empty">No folders match.</div>
      <div *ngFor="let row of rows; trackBy: trackRow" class="row" [style.padding-left.px]="row.depth * 24">
        <button mat-icon-button class="expander" *ngIf="row.node.children.length && !filter; else spacer"
                (click)="expand(row.node.path)">
          <mat-icon>{{ expanded.has(row.node.path) ? 'expand_more' : 'chevron_right' }}</mat-icon>
        </button>
        <ng-template #spacer><span class="spacer"></span></ng-template>
        <mat-checkbox [checked]="isChecked(row.node.path)"
                      [disabled]="ancestorSelected(row.node.path)"
                      [indeterminate]="!isChecked(row.node.path) && descendantSelected(row.node.path)"
                      (change)="toggle(row.node.path)">
          <mat-icon class="folder">folder</mat-icon>
          <span>{{ row.label }}</span>
        </mat-checkbox>
      </div>
    </div>
    <p class="note" *ngIf="truncated">
      This repository is very large; only part of the folder list was returned. Use the filter to find folders.
    </p>
  `,
  styles: [`
    :host { display: block; }
    .summary { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; color: #616161; margin-bottom: 12px; }
    .search { width: 100%; margin-bottom: 8px; }
    .tree {
      max-height: 360px;
      overflow: auto;
      border: 1px solid #e0e0e0;
      border-radius: 4px;
      padding: 4px 0;
    }
    .row { display: flex; align-items: center; min-height: 36px; }
    .expander { width: 36px; height: 36px; padding: 6px; }
    .spacer { display: inline-block; width: 36px; }
    .folder { color: #f9a825; font-size: 18px; width: 18px; height: 18px; vertical-align: middle; margin-right: 6px; }
    .empty, .note { color: var(--pc-text-muted); padding: 8px 16px; }
  `],
})
export class FolderPickerComponent implements OnChanges {
  @Input() folders: string[] = [];
  @Input() truncated = false;
  /** Folders pre-selected whenever the folder list is (re)loaded, e.g. a saved connector's paths. */
  @Input() initialSelection: string[] = [];
  @Output() selectionChange = new EventEmitter<string[]>();

  selected = new Set<string>();
  expanded = new Set<string>();
  filter = '';
  rows: Row[] = [];
  private roots: FolderNode[] = [];
  private all: FolderNode[] = [];

  get selectedList(): string[] {
    return [...this.selected].sort();
  }

  ngOnChanges(): void {
    this.build();
    this.selected = new Set(this.initialSelection);
    this.expanded = new Set(this.roots.map((r) => r.path));
    this.filter = '';
    this.refresh();
    this.emit();
  }

  trackRow = (_: number, row: Row) => row.node.path;

  expand(path: string): void {
    this.expanded.has(path) ? this.expanded.delete(path) : this.expanded.add(path);
    this.refresh();
  }

  toggle(path: string): void {
    if (this.selected.has(path)) {
      this.selected.delete(path);
    } else {
      // A selected folder covers its subtree, so drop redundant child selections
      for (const p of [...this.selected]) {
        if (p.startsWith(path + '/')) this.selected.delete(p);
      }
      this.selected.add(path);
    }
    this.emit();
  }

  clear(): void {
    this.selected.clear();
    this.emit();
  }

  isChecked(path: string): boolean {
    return this.selected.has(path) || this.ancestorSelected(path);
  }

  ancestorSelected(path: string): boolean {
    for (const p of this.selected) {
      if (path.startsWith(p + '/')) return true;
    }
    return false;
  }

  descendantSelected(path: string): boolean {
    for (const p of this.selected) {
      if (p.startsWith(path + '/')) return true;
    }
    return false;
  }

  refresh(): void {
    const term = this.filter.trim().toLowerCase();
    if (term) {
      this.rows = this.all
        .filter((n) => n.path.toLowerCase().includes(term))
        .slice(0, 500)
        .map((node) => ({ node, label: node.path, depth: 0 }));
      return;
    }
    const rows: Row[] = [];
    const walk = (nodes: FolderNode[]) => {
      for (const node of nodes) {
        rows.push({ node, label: node.name, depth: node.depth });
        if (this.expanded.has(node.path)) walk(node.children);
      }
    };
    walk(this.roots);
    this.rows = rows;
  }

  private build(): void {
    const byPath = new Map<string, FolderNode>();
    this.roots = [];
    const ensure = (path: string): FolderNode => {
      let node = byPath.get(path);
      if (node) return node;
      const slash = path.lastIndexOf('/');
      node = { path, name: path.slice(slash + 1), depth: path.split('/').length - 1, children: [] };
      byPath.set(path, node);
      if (slash < 0) {
        this.roots.push(node);
      } else {
        ensure(path.slice(0, slash)).children.push(node);
      }
      return node;
    };
    [...this.folders].sort().forEach(ensure);
    this.all = [...byPath.values()].sort((a, b) => a.path.localeCompare(b.path));
  }

  private emit(): void {
    this.selectionChange.emit(this.selectedList);
  }
}
