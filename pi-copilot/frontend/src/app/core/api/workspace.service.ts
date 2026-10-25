import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { WorkspaceSummary } from '../auth/auth-state.model';

export type WorkspaceStage =
  | 'CONNECT'
  | 'SCAN'
  | 'REVIEW'
  | 'ENCRYPTION_SETUP'
  | 'DATA_SYNC'
  | 'ENABLE_ENCRYPTION'
  | 'FINALIZE'
  | 'COMPLETED';

export interface StageStatus {
  stage: WorkspaceStage;
  state: 'DONE' | 'CURRENT' | 'UPCOMING';
  ready: boolean;
  detail: string;
}

export interface WorkspaceLifecycle {
  workspaceId: string;
  currentStage: WorkspaceStage;
  stageUpdatedAt: string | null;
  stages: StageStatus[];
}

export interface WorkspaceRequest {
  name: string;
  slug?: string;
  description?: string;
}

@Injectable({ providedIn: 'root' })
export class WorkspaceService {
  constructor(private http: HttpClient) {}

  listWorkspaces(): Observable<WorkspaceSummary[]> {
    return this.http.get<WorkspaceSummary[]>('/workspaces');
  }

  createWorkspace(request: WorkspaceRequest): Observable<WorkspaceSummary> {
    return this.http.post<WorkspaceSummary>('/workspaces', request);
  }

  updateWorkspace(id: string, request: WorkspaceRequest): Observable<WorkspaceSummary> {
    return this.http.put<WorkspaceSummary>(`/workspaces/${id}`, request);
  }

  archiveWorkspace(id: string): Observable<void> {
    return this.http.delete<void>(`/workspaces/${id}`);
  }

  getLifecycle(id: string): Observable<WorkspaceLifecycle> {
    return this.http.get<WorkspaceLifecycle>(`/workspaces/${id}/lifecycle`);
  }

  advanceStage(id: string, confirmed = false): Observable<WorkspaceLifecycle> {
    return this.http.post<WorkspaceLifecycle>(`/workspaces/${id}/lifecycle/advance`, { confirmed });
  }

  reopenStage(id: string, stage: WorkspaceStage): Observable<WorkspaceLifecycle> {
    return this.http.post<WorkspaceLifecycle>(`/workspaces/${id}/lifecycle/reopen`, { stage });
  }
}
