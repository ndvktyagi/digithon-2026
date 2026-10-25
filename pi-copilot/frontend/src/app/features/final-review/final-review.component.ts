import { Component, computed, inject, Injectable, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { RouterModule } from '@angular/router';
import { forkJoin, Observable, of, Subscription, timer } from 'rxjs';
import { map, switchMap } from 'rxjs/operators';
import { Store } from '@ngrx/store';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { PageHeaderComponent } from '../../shared/components/page-header/page-header.component';
import { NotificationService } from '../../core/notifications/notification.service';
import { WorkspaceService, WorkspaceStage } from '../../core/api/workspace.service';
import { workspaceActions } from '../../store/workspace/workspace.actions';
import { diffLines, FileDiff } from './line-diff';

type ReviewStatus = 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED' | 'CHANGES_REQUESTED';
type Decision = 'APPROVE' | 'REJECT' | 'REQUEST_CHANGES';

interface ColumnResult {
  database: string;
  schema: string;
  table: string;
  column: string;
  encryptedColumn: string;
  codeStatus: string;
}

interface LineComment {
  path: string;
  line: number;
  /** NEW / OLD: a diff line; FILE: the whole file (line 0). */
  side: 'NEW' | 'OLD' | 'FILE';
  lineText: string | null;
  body: string;
  author?: string;
  createdAt?: string;
  resolved?: boolean;
  resolvedInRound?: number;
  resolutionNote?: string;
}

interface Round {
  id: string;
  status: 'WAITING' | 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  roundNumber: number;
  parentRunId?: string;
  improvementRequest?: string;
  aiSummary?: string;
  reviewStatus?: ReviewStatus;
  reviewComment?: string;
  reviewedBy?: string;
  reviewedAt?: string;
  branchName?: string;
  baseBranch?: string;
  commitSha?: string;
  compareUrl?: string;
  prNumber?: number;
  prUrl?: string;
  /** After approval: MERGED into the base branch, or FAILED (mergeMessage says why). */
  mergeStatus?: 'MERGED' | 'FAILED' | null;
  mergeSha?: string;
  mergeMessage?: string;
  mergedAt?: string;
  repository?: string;
  ddlScript?: string;
  columns: ColumnResult[];
  log: string[];
  lineComments: LineComment[];
  createdAt: string;
  completedAt?: string;
  errorMessage?: string;
}

interface FileChange {
  path: string;
  changeKind: 'ADDED' | 'MODIFIED';
  baseContent: string | null;
  previousContent: string | null;
  newContent: string;
}

interface FileView {
  path: string;
  kind: 'ADDED' | 'MODIFIED';
  diff: FileDiff;
}

const POLL_MS = 5000;

@Injectable({ providedIn: 'root' })
export class FinalReviewApi {
  private readonly http = inject(HttpClient);

  private get base(): string {
    return `/workspaces/${sessionStorage.getItem('workspace_id')}/implementation`;
  }

  runs(): Observable<Round[]> {
    return this.http.get<Round[]>(`${this.base}/runs`);
  }

  files(runId: string): Observable<FileChange[]> {
    return this.http.get<FileChange[]>(`${this.base}/runs/${runId}/files`);
  }

  review(runId: string, decision: Decision, comment: string, lineComments: LineComment[]): Observable<void> {
    return this.http.post<void>(`${this.base}/runs/${runId}/review`, { decision, comment, lineComments });
  }

  retry(runId: string): Observable<void> {
    return this.http.post<void>(`${this.base}/runs/${runId}/retry`, {});
  }

  merge(runId: string): Observable<void> {
    return this.http.post<void>(`${this.base}/runs/${runId}/merge`, {});
  }
}

@Component({
  selector: 'app-final-review',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterModule, MatIconModule, MatButtonModule, MatProgressBarModule,
    MatButtonToggleModule, MatTooltipModule, PageHeaderComponent,
  ],
  template: `
    <div class="pc-page">
      <app-page-header title="Final Review"
        subtitle="Review the code changes and the new database columns, then approve, reject or request changes.">
        <a *ngIf="latest()?.prUrl" mat-stroked-button [href]="latest()!.prUrl" target="_blank" rel="noopener">
          <mat-icon>open_in_new</mat-icon> Pull request #{{ latest()!.prNumber }}
        </a>
        <a *ngIf="!latest()?.prUrl && latest()?.compareUrl" mat-stroked-button [href]="latest()!.compareUrl" target="_blank" rel="noopener">
          <mat-icon>open_in_new</mat-icon> Branch on GitHub
        </a>
      </app-page-header>

      <mat-progress-bar *ngIf="!loaded()" mode="indeterminate"></mat-progress-bar>

      <div class="empty panel" *ngIf="loaded() && chain().length === 0">
        <mat-icon>rate_review</mat-icon>
        <p>Nothing to review yet. Run the implementation first.</p>
        <a mat-stroked-button color="primary" routerLink="/implementation">Open Implementation</a>
      </div>

      <ng-container *ngIf="latest() as last">
        <!-- Where the review stands -->
        <div class="banner" [ngClass]="bannerTone()">
          <mat-icon [class.spin]="isWorking(last)">{{ bannerIcon() }}</mat-icon>
          <div class="banner-text">
            <ng-container [ngSwitch]="bannerKind()">
              <span *ngSwitchCase="'review'"><strong>Round {{ last.roundNumber }} is waiting for your review.</strong>
                Check the changes below, then approve, request changes or reject.</span>
              <span *ngSwitchCase="'working'"><strong>Round {{ last.roundNumber }} is resolving your comment…</strong>
                The code is updated and pushed to the same pull request, then it comes back here for review.</span>
              <span *ngSwitchCase="'waiting'"><strong>Changes requested.</strong>
                Round {{ last.roundNumber }} resolves them once you click Run again on the Implementation page.</span>
              <span *ngSwitchCase="'failed'"><strong>Round {{ last.roundNumber }} failed:</strong> {{ last.errorMessage }}</span>
              <span *ngSwitchCase="'approved'"><strong>Approved in round {{ last.roundNumber }}</strong>
                by {{ last.reviewedBy }}. The workspace moved on to Data Sync.
                <ng-container [ngSwitch]="last.mergeStatus">
                  <span *ngSwitchCase="'MERGED'" class="merge-note">
                    <mat-icon>merge</mat-icon> Merged into <code>{{ last.baseBranch }}</code>
                    <ng-container *ngIf="last.mergeSha"> ({{ last.mergeSha.substring(0, 7) }})</ng-container>.
                    Safe to deploy: the new code stays off until encrypted read is enabled.
                  </span>
                  <span *ngSwitchCase="'FAILED'" class="merge-note failed">
                    <mat-icon>error</mat-icon> Could not merge into <code>{{ last.baseBranch }}</code>: {{ last.mergeMessage }}
                  </span>
                </ng-container>
              </span>
              <span *ngSwitchCase="'rejected'"><strong>Rejected in round {{ last.roundNumber }}</strong>
                by {{ last.reviewedBy }}. The workflow ended and the pull request was closed.</span>
              <span *ngSwitchDefault>Round {{ last.roundNumber }} · {{ statusLabel(last) }}</span>
            </ng-container>
          </div>
          <a *ngIf="bannerKind() === 'waiting'" mat-flat-button color="primary" routerLink="/implementation">
            <mat-icon>rocket_launch</mat-icon> Open Implementation
          </a>
          <button *ngIf="bannerKind() === 'failed' && last.parentRunId" mat-flat-button color="primary" type="button"
                  (click)="retry(last)" [disabled]="busy()">
            <mat-icon>replay</mat-icon> Retry round
          </button>
          <button *ngIf="bannerKind() === 'approved' && last.mergeStatus !== 'MERGED'" mat-flat-button color="primary"
                  type="button" (click)="merge(last)" [disabled]="busy()">
            <mat-icon>merge</mat-icon> {{ last.mergeStatus === 'FAILED' ? 'Retry merge' : 'Merge' }}
          </button>
        </div>
        <mat-progress-bar *ngIf="isWorking(last)" mode="indeterminate"></mat-progress-bar>

        <div class="layout">
          <!-- Selected round -->
          <section class="detail" *ngIf="selected() as r">
            <div class="panel">
              <div class="panel-head wrap">
                <h2>Code changes · round {{ r.roundNumber }}</h2>
                <mat-button-toggle-group [value]="mode()" (change)="setMode($event.value)" aria-label="Diff mode">
                  <mat-button-toggle value="round" [disabled]="!r.parentRunId">This round</mat-button-toggle>
                  <mat-button-toggle value="all">All changes vs original</mat-button-toggle>
                </mat-button-toggle-group>
              </div>
              <p class="sub">
                <code>{{ r.repository }}</code> · <code>{{ r.baseBranch }}</code> → <code>{{ r.branchName }}</code>
                <ng-container *ngIf="mode() === 'round' && r.parentRunId"> · changes made in this round only</ng-container>
                <ng-container *ngIf="mode() === 'all'"> · original code on {{ r.baseBranch }} compared with this round</ng-container>
              </p>

              <mat-progress-bar *ngIf="filesLoading()" mode="indeterminate"></mat-progress-bar>
              <p class="sub" *ngIf="!filesLoading() && fileViews().length === 0">
                {{ isWorking(r) || r.status === 'WAITING' ? 'Changes appear when this round is pushed.' : 'No file changes recorded for this round.' }}
              </p>

              <div class="file-summary" *ngIf="fileViews().length">
                <span>{{ fileViews().length }} file{{ fileViews().length === 1 ? '' : 's' }} changed</span>
                <span class="add">+{{ totals().added }}</span><span class="del">−{{ totals().removed }}</span>
              </div>

              <article class="file" *ngFor="let f of fileViews()">
                <header class="file-head" (click)="toggleFile(f.path)">
                  <mat-icon class="chev" [class.open]="!collapsed().has(f.path)">expand_more</mat-icon>
                  <span class="kind" [class.added]="f.kind === 'ADDED'">{{ f.kind === 'ADDED' ? 'A' : 'M' }}</span>
                  <span class="path">{{ f.path }}</span>
                  <span class="add">+{{ f.diff.added }}</span><span class="del">−{{ f.diff.removed }}</span>
                  <button *ngIf="canComment()" mat-icon-button type="button" class="file-comment-btn"
                          (click)="$event.stopPropagation(); openFileComposer(f.path)"
                          matTooltip="Comment on this file" aria-label="Comment on this file">
                    <mat-icon>add_comment</mat-icon>
                  </button>
                </header>
                <div class="file-comments" *ngIf="fileComments(f.path).length || isComposingFile(f.path)">
                  <div class="gh-thread" *ngIf="fileComments(f.path).length">
                                <div class="gh-comment" [class.pending]="!c.createdAt" [class.resolved]="c.resolved" *ngFor="let c of fileComments(f.path)">
                                  <div class="gh-resolved-bar" *ngIf="c.resolved">
                                    <mat-icon>check_circle</mat-icon>
                                    <span>Resolved by PI Co-Pilot AI in round {{ c.resolvedInRound }}</span>
                                    <button type="button" class="gh-toggle" (click)="toggleResolved(c)">{{ isExpanded(c) ? 'Hide' : 'Show' }} conversation</button>
                                  </div>
                                  <div class="gh-comment-content" *ngIf="!c.resolved || isExpanded(c)">
                                  <span class="gh-avatar">{{ initials(c.author) }}</span>
                                  <div class="gh-comment-main">
                                    <div class="gh-comment-head">
                                      <strong>{{ c.author || 'You' }}</strong>
                                      <span class="muted">{{ c.createdAt ? 'commented ' : '' }}{{ c.createdAt | date:'d MMM, HH:mm' }}</span>
                                      <span class="gh-pending" *ngIf="!c.createdAt">Pending</span>
                                      <button *ngIf="!c.createdAt" type="button" class="gh-delete" (click)="removeDraft(c)"
                                              title="Delete comment" aria-label="Delete comment"><mat-icon>delete_outline</mat-icon></button>
                                    </div>
                                    <div class="gh-comment-body">{{ c.body }}</div>
                                      <div class="gh-resolution" *ngIf="c.resolved && c.resolutionNote"><mat-icon>auto_awesome</mat-icon><span>{{ c.resolutionNote }}</span></div>
                                  </div>
                                  </div>
                                </div>
                              </div>
                  <ng-container *ngIf="isComposingFile(f.path)">
                  <div class="gh-composer">
                                <div class="gh-tabs"><span class="gh-tab active">Write</span>
                                  <span class="gh-hint">Commenting on the whole file</span></div>
                                <textarea [(ngModel)]="composerText" rows="4" maxlength="2000" autofocus
                                          placeholder="Leave a comment"></textarea>
                                <div class="gh-composer-actions">
                                  <button type="button" class="gh-btn" (click)="cancelComposer()">Cancel</button>
                                  <button type="button" class="gh-btn gh-btn-primary" (click)="addDraft()"
                                          [disabled]="!composerText.trim()">Add review comment</button>
                                </div>
                              </div>
                  </ng-container>
                </div>
                <div class="diff" *ngIf="!collapsed().has(f.path)">
                  <table>
                    <tbody>
                      <ng-container *ngFor="let h of f.diff.hunks">
                        <tr class="hunk"><td colspan="4">{{ h.header }}</td></tr>
                        <ng-container *ngFor="let l of h.lines">
                          <tr class="line" [ngClass]="l.type" [class.composing]="isComposing(f.path, l)">
                            <td class="no">{{ l.oldNo ?? '' }}</td>
                            <td class="no">{{ l.newNo ?? '' }}</td>
                            <td class="gutter">
                              <button *ngIf="canComment()" type="button" class="gh-add" (click)="openComposer(f.path, l)"
                                      aria-label="Add a comment to this line" title="Add a comment to this line">+</button>
                            </td>
                            <td class="code"><span class="sign">{{ l.type === 'add' ? '+' : l.type === 'del' ? '−' : ' ' }}</span>{{ l.text }}</td>
                          </tr>
                          <tr *ngIf="commentsAt(f.path, l).length" class="comment-row">
                            <td colspan="4">
                              <div class="gh-thread">
                                <div class="gh-comment" [class.pending]="!c.createdAt" [class.resolved]="c.resolved" *ngFor="let c of commentsAt(f.path, l)">
                                  <div class="gh-resolved-bar" *ngIf="c.resolved">
                                    <mat-icon>check_circle</mat-icon>
                                    <span>Resolved by PI Co-Pilot AI in round {{ c.resolvedInRound }}</span>
                                    <button type="button" class="gh-toggle" (click)="toggleResolved(c)">{{ isExpanded(c) ? 'Hide' : 'Show' }} conversation</button>
                                  </div>
                                  <div class="gh-comment-content" *ngIf="!c.resolved || isExpanded(c)">
                                  <span class="gh-avatar">{{ initials(c.author) }}</span>
                                  <div class="gh-comment-main">
                                    <div class="gh-comment-head">
                                      <strong>{{ c.author || 'You' }}</strong>
                                      <span class="muted">{{ c.createdAt ? 'commented ' : '' }}{{ c.createdAt | date:'d MMM, HH:mm' }}</span>
                                      <span class="gh-pending" *ngIf="!c.createdAt">Pending</span>
                                      <button *ngIf="!c.createdAt" type="button" class="gh-delete" (click)="removeDraft(c)"
                                              title="Delete comment" aria-label="Delete comment"><mat-icon>delete_outline</mat-icon></button>
                                    </div>
                                    <div class="gh-comment-body">{{ c.body }}</div>
                                      <div class="gh-resolution" *ngIf="c.resolved && c.resolutionNote"><mat-icon>auto_awesome</mat-icon><span>{{ c.resolutionNote }}</span></div>
                                  </div>
                                  </div>
                                </div>
                              </div>
                            </td>
                          </tr>
                          <tr *ngIf="isComposing(f.path, l)" class="comment-row">
                            <td colspan="4">
                              <div class="gh-composer">
                                <div class="gh-tabs"><span class="gh-tab active">Write</span>
                                  <span class="gh-hint">Commenting on line {{ composer()?.line }}</span></div>
                                <textarea [(ngModel)]="composerText" rows="4" maxlength="2000" autofocus
                                          placeholder="Leave a comment"></textarea>
                                <div class="gh-composer-actions">
                                  <button type="button" class="gh-btn" (click)="cancelComposer()">Cancel</button>
                                  <button type="button" class="gh-btn gh-btn-primary" (click)="addDraft()"
                                          [disabled]="!composerText.trim()">Add review comment</button>
                                </div>
                              </div>
                            </td>
                          </tr>
                        </ng-container>
                      </ng-container>
                    </tbody>
                  </table>
                </div>
              </article>
            </div>

            <div class="panel">
              <h2>Database changes</h2>
              <p class="sub">Only new columns are added. Existing columns and data are not changed.</p>
              <table class="tbl" *ngIf="r.columns.length; else noColumns">
                <thead><tr><th>Table</th><th>Plain column</th><th>New encrypted column</th><th>Type</th></tr></thead>
                <tbody>
                  <tr *ngFor="let c of r.columns">
                    <td class="mono">{{ c.schema }}.{{ c.table }}<div class="sub">{{ c.database }}</div></td>
                    <td class="mono">{{ c.column }}</td>
                    <td class="mono new-col">+ {{ c.encryptedColumn }}</td>
                    <td class="mono">text</td>
                  </tr>
                </tbody>
              </table>
              <ng-template #noColumns><p class="sub">No column changes recorded.</p></ng-template>
              <pre class="ddl" *ngIf="r.ddlScript">{{ r.ddlScript }}</pre>
            </div>

            <div class="panel" *ngIf="r.log.length">
              <div class="panel-head">
                <h2>Log · round {{ r.roundNumber }}</h2>
                <button mat-button type="button" (click)="showLog.set(!showLog())">
                  <mat-icon>{{ showLog() ? 'expand_less' : 'expand_more' }}</mat-icon> {{ showLog() ? 'Minimize' : 'Show log' }}
                </button>
              </div>
              <pre class="log" *ngIf="showLog()">{{ r.log.join('
') }}</pre>
            </div>
          </section>

          <!-- Right column: review box, then the rounds -->
          <aside class="side">
            <div class="panel review-box" *ngIf="canReview()">
              <h2>Review changes</h2>
              <p class="sub">Round {{ latest()!.roundNumber }}<ng-container *ngIf="latest()!.prNumber">
                · also posted on pull request #{{ latest()!.prNumber }}</ng-container></p>
              <textarea [(ngModel)]="comment" rows="5" maxlength="4000" placeholder="Leave a comment"></textarea>
              <div class="pending" *ngIf="drafts().length">
                <mat-icon>forum</mat-icon> {{ drafts().length }} pending comment{{ drafts().length === 1 ? '' : 's' }} in the diff
              </div>
              <div class="pending muted" *ngIf="!drafts().length">
                Hover a line and click <span class="gh-add static">+</span> to comment on it, or use
                <mat-icon class="inline-icon">add_comment</mat-icon> on a file.
              </div>
              <div class="choices" role="radiogroup" aria-label="Review decision">
                <label class="choice" [class.selected]="choice === 'REQUEST_CHANGES'">
                  <input type="radio" name="decision" value="REQUEST_CHANGES" [(ngModel)]="choice" />
                  <span><strong>Request changes</strong>
                    <small>Back to Implementation: click Run again there to resolve your comments in a new round.</small></span>
                </label>
                <label class="choice" [class.selected]="choice === 'APPROVE'">
                  <input type="radio" name="decision" value="APPROVE" [(ngModel)]="choice" />
                  <span><strong>Approve</strong><small>Accept the changes and move on to Data Sync.</small></span>
                </label>
                <label class="choice reject" [class.selected]="choice === 'REJECT'">
                  <input type="radio" name="decision" value="REJECT" [(ngModel)]="choice" />
                  <span><strong>Reject</strong><small>End the workflow and close the pull request.</small></span>
                </label>
              </div>
              <p class="choice-error" *ngIf="choice && choiceError()">{{ choiceError() }}</p>
              <button mat-flat-button color="primary" type="button" class="submit" (click)="submitReview()"
                      [disabled]="busy() || !choice || !!choiceError()">
                {{ busy() ? 'Submitting…' : 'Submit review' }}
              </button>
            </div>

            <div class="panel rounds">
              <h2>Review rounds <span class="count">{{ chain().length }}</span></h2>
            <ol class="timeline">
              <li *ngFor="let r of chainNewestFirst()" class="round" [class.selected]="r.id === selectedId()"
                  (click)="select(r)" (keydown.enter)="select(r)" tabindex="0" role="button">
                <div class="round-head">
                  <span class="round-no">Round {{ r.roundNumber }}</span>
                  <span class="pill" [ngClass]="statusTone(r)">{{ statusLabel(r) }}</span>
                </div>
                <div class="round-sub">{{ r.createdAt | date:'d MMM y, HH:mm' }}
                  <span *ngIf="r.commitSha"> · <code>{{ r.commitSha.slice(0, 7) }}</code></span>
                </div>
                <p *ngIf="r.aiSummary" class="ai"><mat-icon>auto_awesome</mat-icon><span>{{ r.aiSummary }}</span></p>
                <!-- The reviewer's own comments only; the AI instruction built from them is not repeated here -->
                <blockquote *ngIf="r.reviewComment" class="quote decision" [ngClass]="statusTone(r)">
                  <mat-icon>{{ r.reviewStatus === 'APPROVED' ? 'check_circle' : r.reviewStatus === 'REJECTED' ? 'block' : 'reply' }}</mat-icon>
                  <span><strong>{{ r.reviewedBy }}:</strong> {{ r.reviewComment }}</span>
                </blockquote>
                <ng-container *ngIf="r.lineComments.length">
                  <p class="line-count">
                    <mat-icon>forum</mat-icon> {{ r.lineComments.length }} line comment{{ r.lineComments.length === 1 ? '' : 's' }}
                    <span class="resolved-count" *ngIf="resolvedCount(r)">· {{ resolvedCount(r) }} resolved</span>
                  </p>
                  <ul class="round-comments">
                    <li *ngFor="let c of r.lineComments" [class.resolved]="c.resolved">
                      <span class="where" [title]="c.path">
                        {{ fileName(c.path) }}{{ c.side === 'FILE' ? ' (whole file)' : ':' + c.line + (c.side === 'OLD' ? ' (original)' : '') }}
                        <mat-icon *ngIf="c.resolved" class="ok" title="Resolved">check_circle</mat-icon>
                      </span>
                      <span class="body">{{ c.body }}</span>
                    </li>
                  </ul>
                </ng-container>
              </li>
            </ol>
            </div>
          </aside>
        </div>
      </ng-container>
    </div>
  `,
  styleUrls: ['./github-review.css'],
  styles: [`
    .panel {
      padding: 20px; min-width: 0;
      background: var(--pc-surface); border: 1px solid var(--pc-border);
      border-radius: var(--pc-radius); box-shadow: var(--pc-shadow);
    }
    .panel h2 { margin: 0 0 12px; }
    .panel-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 6px; }
    .panel-head.wrap { flex-wrap: wrap; }
    .panel-head h2 { margin: 0; }
    .sub { margin: 0 0 12px; font-size: 12.5px; color: var(--pc-text-muted); }
    .mono, code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12.5px; }
    code { background: #f3f4f6; padding: 1px 5px; border-radius: 4px; }
    .empty { display: flex; flex-direction: column; align-items: center; gap: 10px; text-align: center; color: var(--pc-text-muted); }
    .empty > mat-icon { font-size: 34px; width: 34px; height: 34px; color: var(--pc-primary); }

    .banner { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; padding: 14px 18px; border-radius: var(--pc-radius); }
    .banner-text { flex: 1; min-width: 240px; }
    .banner.info { background: var(--pc-primary-soft); color: var(--pc-primary-600); }
    .banner.warn { background: var(--pc-warning-soft); color: #92400e; }
    .banner.error { background: var(--pc-danger-soft); color: #991b1b; }
    .banner.success { background: var(--pc-success-soft); color: #065f46; }
    .spin { animation: spin 1.2s linear infinite; }
    @keyframes spin { to { transform: rotate(360deg); } }

    .layout { display: grid; grid-template-columns: minmax(0, 1fr) 360px; gap: 20px; align-items: start; }
    .side {
      position: sticky; top: calc(var(--pc-header-height) + 16px); display: flex; flex-direction: column; gap: 16px;
      max-height: calc(100vh - var(--pc-header-height) - 32px); overflow-y: auto; overflow-x: hidden; min-width: 0;
    }
    .count { margin-left: 6px; padding: 1px 8px; border-radius: 999px; background: #f3f4f6; font-size: 12px; color: var(--pc-text-muted); }
    .inline-icon { font-size: 15px; width: 15px; height: 15px; vertical-align: middle; }
    .review-box { border-color: var(--pc-primary); }
    .review-box textarea {
      width: 100%; box-sizing: border-box; resize: vertical; padding: 10px 12px; font: inherit; font-size: 14px;
      border: 1px solid var(--pc-border); border-radius: var(--pc-radius-sm); outline: none;
    }
    .review-box textarea:focus { border-color: var(--pc-primary); }
    .pending { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin: 8px 0 4px; font-size: 12.5px; font-weight: 600; color: #92400e; }
    .pending.muted { color: var(--pc-text-muted); font-weight: 400; }
    .pending > mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .choices { display: flex; flex-direction: column; gap: 8px; margin-top: 12px; }
    .choice {
      display: flex; gap: 10px; align-items: flex-start; padding: 10px 12px; cursor: pointer;
      border: 1px solid var(--pc-border); border-radius: var(--pc-radius-sm);
    }
    .choice input { margin-top: 3px; accent-color: var(--pc-primary); }
    .choice span { display: flex; flex-direction: column; gap: 2px; }
    .choice small { color: var(--pc-text-muted); font-size: 12px; line-height: 1.4; }
    .choice.selected { border-color: var(--pc-primary); background: #faf9ff; }
    .choice.reject.selected { border-color: var(--pc-danger); background: var(--pc-danger-soft); }
    .choice-error { margin: 8px 0 0; font-size: 12.5px; color: #b91c1c; }
    .submit { width: 100%; margin-top: 12px; height: 40px; }
    .file-comment-btn { margin-left: 4px; }
    .file-comments { display: flex; flex-direction: column; gap: 8px; padding: 10px 12px; background: #f6f8fa; border-bottom: 1px solid #d0d7de; }

    .timeline { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
    .round { padding: 12px; border: 1px solid var(--pc-border); border-radius: var(--pc-radius-sm); cursor: pointer; }
    .round:hover, .round:focus-visible { border-color: var(--pc-primary); outline: none; }
    .round.selected { border-color: var(--pc-primary); box-shadow: 0 0 0 3px var(--pc-primary-soft); }
    .round-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
    .round-no { font-weight: 700; }
    .round-sub { font-size: 12px; color: var(--pc-text-muted); margin-top: 2px; }
    .pill { padding: 2px 9px; border-radius: 999px; font-size: 11.5px; font-weight: 600; background: #f3f4f6; color: #4b5563; white-space: nowrap; }
    .pill.info { background: var(--pc-primary-soft); color: var(--pc-primary-600); }
    .pill.warn { background: var(--pc-warning-soft); color: #b45309; }
    .pill.success { background: var(--pc-success-soft); color: #047857; }
    .pill.error { background: var(--pc-danger-soft); color: #b91c1c; }
    .quote, .ai { display: flex; gap: 6px; margin: 8px 0 0; padding: 8px 10px; border-radius: 8px; font-size: 12.5px; line-height: 1.45; }
    .quote span, .ai span { min-width: 0; overflow-wrap: anywhere; word-break: break-word; }
    .line-count { display: flex; align-items: center; gap: 4px; margin: 8px 0 0; font-size: 12px; color: var(--pc-text-muted); }
    .line-count mat-icon { font-size: 15px; width: 15px; height: 15px; }
    .resolved-count { color: #1a7f37; font-weight: 600; }
    .round { min-width: 0; overflow: hidden; }
    .quote mat-icon, .ai mat-icon { font-size: 16px; width: 16px; height: 16px; flex-shrink: 0; margin-top: 1px; }
    .ai { background: #eef2ff; color: #3730a3; }
    .quote.decision { background: #f3f4f6; color: #374151; }
    .quote.decision.success { background: var(--pc-success-soft); color: #065f46; }
    .quote.decision.error { background: var(--pc-danger-soft); color: #991b1b; }

    .detail { display: flex; flex-direction: column; gap: 20px; min-width: 0; }
    .file-summary { display: flex; gap: 10px; align-items: center; margin-bottom: 10px; font-size: 13px; font-weight: 600; }
    .add { color: #047857; font-weight: 600; font-size: 12.5px; }
    .del { color: #b91c1c; font-weight: 600; font-size: 12.5px; }
    .file { border: 1px solid var(--pc-border); border-radius: var(--pc-radius-sm); margin-bottom: 10px; overflow: hidden; }
    .file-head { display: flex; align-items: center; gap: 8px; padding: 8px 10px; background: #fafbfc; cursor: pointer; }
    .chev { color: var(--pc-text-subtle); transform: rotate(-90deg); transition: transform 0.15s; }
    .chev.open { transform: rotate(0); }
    .kind { width: 18px; height: 18px; display: grid; place-items: center; border-radius: 4px; font-size: 11px; font-weight: 700; background: #fef3c7; color: #92400e; flex-shrink: 0; }
    .kind.added { background: var(--pc-success-soft); color: #047857; }
    .path { flex: 1; min-width: 0; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12.5px; overflow-wrap: anywhere; }
    .diff { overflow-x: auto; }
    .diff table { width: 100%; border-collapse: collapse; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
    .diff td { padding: 0 8px; white-space: pre; line-height: 1.6; }
    .diff td.no { width: 1%; text-align: right; color: var(--pc-text-subtle); user-select: none; border-right: 1px solid var(--pc-border); }
    .diff tr.add td { background: #ecfdf5; }
    .diff tr.add td.code { background: #d1fae5; }
    .diff tr.del td { background: #fef2f2; }
    .diff tr.del td.code { background: #fee2e2; }
    .diff tr.hunk td { background: #eff6ff; color: #1e40af; padding: 2px 8px; }
    .sign { display: inline-block; width: 14px; color: var(--pc-text-subtle); }

    .tbl { width: 100%; border-collapse: collapse; }
    .tbl th { text-align: left; padding: 8px 10px; font-size: 11.5px; text-transform: uppercase; letter-spacing: 0.04em; color: var(--pc-text-muted); background: #fafbfc; }
    .tbl td { padding: 10px; border-top: 1px solid var(--pc-border); vertical-align: top; }
    .new-col { color: #047857; font-weight: 600; }
    .ddl { margin: 14px 0 0; padding: 12px; border-radius: var(--pc-radius-sm); background: #0f172a; color: #e2e8f0; font-size: 12px; overflow-x: auto; }



    @media (max-width: 1100px) {
      .layout { grid-template-columns: 1fr; }
      .side { position: static; max-height: none; }
    }
  `],
})
export class FinalReviewComponent implements OnInit, OnDestroy {
  private readonly api = inject(FinalReviewApi);
  private readonly workspaces = inject(WorkspaceService);
  private readonly notifications = inject(NotificationService);
  private readonly store = inject(Store);
  private pollSub?: Subscription;

  readonly runs = signal<Round[]>([]);
  readonly stage = signal<WorkspaceStage | null>(null);
  readonly loaded = signal(false);
  readonly busy = signal(false);
  readonly selectedId = signal<string | null>(null);
  readonly mode = signal<'round' | 'all'>('all');
  readonly filesByRun = signal<Map<string, FileChange[]>>(new Map());
  readonly filesLoading = signal(false);
  readonly collapsed = signal<Set<string>>(new Set());
  comment = '';
  /** The round's log is open by default; the user can minimize it. */
  readonly showLog = signal(true);
  /** Line comments written in this review, sent with the decision. */
  readonly drafts = signal<LineComment[]>([]);
  readonly composer = signal<{ path: string; line: number; side: 'NEW' | 'OLD' | 'FILE'; lineText: string } | null>(null);
  choice: Decision | null = null;
  composerText = '';

  /** Rounds of the most recent implementation, oldest first (following parent links). */
  readonly chain = computed<Round[]>(() => {
    const runs = this.runs();
    if (!runs.length) return [];
    const byId = new Map(runs.map((r) => [r.id, r]));
    const chain: Round[] = [];
    for (let r: Round | undefined = runs[0]; r; r = r.parentRunId ? byId.get(r.parentRunId) : undefined) chain.unshift(r);
    return chain;
  });
  readonly chainNewestFirst = computed(() => [...this.chain()].reverse());
  readonly latest = computed<Round | null>(() => this.chain()[this.chain().length - 1] ?? null);
  readonly selected = computed<Round | null>(() => this.chain().find((r) => r.id === this.selectedId()) ?? this.latest());

  /** A round waiting for review can be reviewed unless the workflow already ended. */
  readonly canReview = computed(() => {
    const last = this.latest();
    const stage = this.stage();
    return !!last && stage !== 'COMPLETED' && stage !== 'REJECTED'
      && last.status === 'COMPLETED' && last.reviewStatus === 'PENDING_REVIEW';
  });

  /** Line comments can be added while reviewing the latest round. */
  readonly canComment = computed(() => this.canReview() && this.selected()?.id === this.latest()?.id);

  readonly bannerKind = computed(() => {
    const last = this.latest();
    if (!last) return 'none';
    if (this.isWorking(last)) return 'working';
    if (last.status === 'WAITING') return 'waiting';
    if (last.status === 'FAILED') return 'failed';
    if (last.reviewStatus === 'APPROVED') return 'approved';
    if (last.reviewStatus === 'REJECTED') return 'rejected';
    if (last.reviewStatus === 'PENDING_REVIEW') return 'review';
    return 'other';
  });
  readonly bannerTone = computed(() => ({
    review: 'info', working: 'warn', waiting: 'warn', failed: 'error', approved: 'success', rejected: 'error',
  } as Record<string, string>)[this.bannerKind()] ?? 'info');
  readonly bannerIcon = computed(() => ({
    review: 'rate_review', working: 'autorenew', waiting: 'pending_actions', failed: 'error', approved: 'verified', rejected: 'block',
  } as Record<string, string>)[this.bannerKind()] ?? 'info');

  /** Diff per file for the selected round: this round only, or base branch vs. this round. */
  readonly fileViews = computed<FileView[]>(() => {
    const sel = this.selected();
    if (!sel) return [];
    const files = this.filesByRun();
    if (this.mode() === 'round' && sel.parentRunId) {
      return (files.get(sel.id) ?? []).map((f) => ({
        path: f.path, kind: f.changeKind, diff: diffLines(f.previousContent, f.newContent),
      }));
    }
    const upTo = this.chain().filter((r) => r.roundNumber <= sel.roundNumber);
    const merged = new Map<string, { base: string | null; content: string; kind: 'ADDED' | 'MODIFIED' }>();
    for (const r of upTo) {
      for (const f of files.get(r.id) ?? []) {
        const prev = merged.get(f.path);
        merged.set(f.path, { base: prev ? prev.base : f.baseContent, content: f.newContent, kind: prev?.kind ?? f.changeKind });
      }
    }
    return [...merged.entries()].map(([path, m]) => ({ path, kind: m.kind, diff: diffLines(m.base, m.content) }));
  });
  readonly totals = computed(() => this.fileViews().reduce(
    (t, f) => ({ added: t.added + f.diff.added, removed: t.removed + f.diff.removed }), { added: 0, removed: 0 }));

  ngOnInit(): void {
    this.refresh();
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
  }

  /** Loads runs and stage; keeps polling while a round is being resolved. */
  private refresh(): void {
    this.pollSub?.unsubscribe();
    const workspaceId = sessionStorage.getItem('workspace_id') ?? '';
    let wasWorking = false;
    this.pollSub = timer(0, POLL_MS).pipe(
      switchMap(() => forkJoin([this.api.runs(), this.workspaces.getLifecycle(workspaceId)])),
    ).subscribe({
      next: ([runs, lifecycle]) => {
        this.runs.set(runs.map((r) => ({ ...r, columns: r.columns ?? [], log: r.log ?? [], lineComments: r.lineComments ?? [] })));
        this.stage.set(lifecycle.currentStage);
        this.loaded.set(true);
        const last = this.latest();
        const working = !!last && this.isWorking(last);
        if (working) wasWorking = true;
        if (!working) {
          this.pollSub?.unsubscribe();
          if (wasWorking) {
            this.store.dispatch(workspaceActions.loadWorkspaces());
            this.selectedId.set(null);
          }
        }
        this.loadFiles();
      },
      error: () => {
        this.loaded.set(true);
        this.pollSub?.unsubscribe();
      },
    });
  }

  /** Fetches file changes for every round up to the selected one that isn't cached yet. */
  private loadFiles(): void {
    const sel = this.selected();
    if (!sel) return;
    const cache = this.filesByRun();
    const needed = this.chain().filter((r) => r.roundNumber <= sel.roundNumber && r.status === 'COMPLETED' && !cache.has(r.id));
    if (!needed.length) return;
    this.filesLoading.set(true);
    forkJoin(needed.map((r) => this.api.files(r.id).pipe(map((files) => [r.id, files] as const)))).pipe(
      switchMap((pairs) => of(pairs)),
    ).subscribe({
      next: (pairs) => {
        const next = new Map(this.filesByRun());
        pairs.forEach(([id, files]) => next.set(id, files));
        this.filesByRun.set(next);
        this.filesLoading.set(false);
      },
      error: () => this.filesLoading.set(false),
    });
  }

  select(r: Round): void {
    this.selectedId.set(r.id);
    this.mode.set(r.parentRunId ? 'round' : 'all');
    this.loadFiles();
  }

  setMode(mode: 'round' | 'all'): void {
    this.mode.set(mode);
  }

  toggleFile(path: string): void {
    this.collapsed.update((s) => {
      const next = new Set(s);
      if (next.has(path)) next.delete(path); else next.add(path);
      return next;
    });
  }

  /** Diff side and line number a comment on this line refers to. */
  private anchor(l: { type: string; oldNo: number | null; newNo: number | null }): { side: 'NEW' | 'OLD'; line: number } {
    return l.type === 'del' || l.newNo === null ? { side: 'OLD', line: l.oldNo ?? 0 } : { side: 'NEW', line: l.newNo };
  }

  commentsAt(path: string, l: { type: string; oldNo: number | null; newNo: number | null }): LineComment[] {
    const a = this.anchor(l);
    const matches = (c: LineComment) => c.path === path && c.side === a.side && c.line === a.line;
    const saved = (this.selected()?.lineComments ?? []).filter(matches);
    const drafts = this.canComment() ? this.drafts().filter(matches) : [];
    return [...saved, ...drafts];
  }

  openComposer(path: string, l: { type: string; oldNo: number | null; newNo: number | null; text: string }): void {
    this.composer.set({ path, ...this.anchor(l), lineText: l.text });
    this.composerText = '';
  }

  isComposing(path: string, l: { type: string; oldNo: number | null; newNo: number | null }): boolean {
    const c = this.composer();
    const a = this.anchor(l);
    return !!c && c.path === path && c.side === a.side && c.line === a.line;
  }

  cancelComposer(): void {
    this.composer.set(null);
    this.composerText = '';
  }

  addDraft(): void {
    const c = this.composer();
    if (!c || !this.composerText.trim()) return;
    this.drafts.update((d) => [...d, { ...c, body: this.composerText.trim() }]);
    this.cancelComposer();
  }

  fileComments(path: string): LineComment[] {
    const matches = (c: LineComment) => c.path === path && c.side === 'FILE';
    const saved = (this.selected()?.lineComments ?? []).filter(matches);
    return [...saved, ...(this.canComment() ? this.drafts().filter(matches) : [])];
  }

  openFileComposer(path: string): void {
    this.composer.set({ path, line: 0, side: 'FILE', lineText: '' });
    this.composerText = '';
  }

  isComposingFile(path: string): boolean {
    const c = this.composer();
    return !!c && c.path === path && c.side === 'FILE';
  }


  /** Why the chosen decision can't be submitted yet, if anything. */
  choiceError(): string | null {
    if (this.choice === 'REJECT' && !this.comment.trim()) return 'Add a comment explaining why you reject.';
    if (this.choice === 'REQUEST_CHANGES' && !this.comment.trim() && !this.drafts().length) {
      return 'Add a comment, or comment on lines in the diff, to request changes.';
    }
    return null;
  }

  submitReview(): void {
    if (this.choice && !this.choiceError()) this.decide(this.choice);
  }

  /** Resolved conversations the reviewer expanded (collapsed by default, as on GitHub). */
  readonly expandedResolved = signal<Set<string>>(new Set());

  private threadKey(c: LineComment): string {
    return `${c.path}|${c.side}|${c.line}|${c.body}`;
  }

  isExpanded(c: LineComment): boolean {
    return this.expandedResolved().has(this.threadKey(c));
  }

  toggleResolved(c: LineComment): void {
    const key = this.threadKey(c);
    this.expandedResolved.update((set) => {
      const next = new Set(set);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  }

  fileName(path: string): string {
    return path.substring(path.lastIndexOf('/') + 1);
  }

  resolvedCount(r: Round): number {
    return r.lineComments.filter((c) => c.resolved).length;
  }

  initials(name?: string): string {
    const parts = (name || 'You').replace(/^local:/, '').split(/[\s._@-]+/).filter(Boolean);
    return parts.slice(0, 2).map((p) => p[0].toUpperCase()).join('');
  }

  removeDraft(draft: LineComment): void {
    this.drafts.update((d) => d.filter((x) => x !== draft));
  }

  decide(decision: Decision): void {
    const last = this.latest();
    if (!last) return;
    this.busy.set(true);
    this.api.review(last.id, decision, this.comment.trim(), this.drafts()).subscribe({
      next: () => {
        this.busy.set(false);
        this.comment = '';
        this.choice = null;
        this.drafts.set([]);
        this.cancelComposer();
        this.notifications.showSuccess(decision === 'APPROVE' ? 'Approved: moving on to Data Sync'
          : decision === 'REJECT' ? 'Rejected: the workflow has ended'
          : 'Changes requested: click Run again on Implementation to start the next round');
        this.store.dispatch(workspaceActions.loadWorkspaces());
        this.selectedId.set(null);
        this.refresh();
      },
      error: () => this.busy.set(false),
    });
  }

  merge(r: Round): void {
    this.busy.set(true);
    this.api.merge(r.id).subscribe({
      next: () => {
        this.busy.set(false);
        this.refresh();
      },
      error: () => this.busy.set(false),
    });
  }

  retry(r: Round): void {
    this.busy.set(true);
    this.api.retry(r.id).subscribe({
      next: () => {
        this.busy.set(false);
        this.notifications.showSuccess(`Round ${r.roundNumber} queued again`);
        this.refresh();
      },
      error: () => this.busy.set(false),
    });
  }

  isWorking(r: Round): boolean {
    return r.status === 'PENDING' || r.status === 'RUNNING';
  }

  statusLabel(r: Round): string {
    if (this.isWorking(r)) return r.parentRunId ? 'Resolving comment' : 'Implementing';
    if (r.status === 'WAITING') return 'Waiting to start';
    if (r.status === 'FAILED') return 'Failed';
    return ({ PENDING_REVIEW: 'Waiting for review', APPROVED: 'Approved', REJECTED: 'Rejected',
      CHANGES_REQUESTED: 'Changes requested' } as Record<string, string>)[r.reviewStatus ?? ''] ?? 'Completed';
  }

  statusTone(r: Round): string {
    if (this.isWorking(r)) return 'warn';
    if (r.status === 'WAITING') return 'warn';
    if (r.status === 'FAILED' || r.reviewStatus === 'REJECTED') return 'error';
    if (r.reviewStatus === 'APPROVED') return 'success';
    if (r.reviewStatus === 'CHANGES_REQUESTED') return 'warn';
    return 'info';
  }
}
