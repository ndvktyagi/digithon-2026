import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export type ScanStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export interface ScanTarget {
  id: string;
  connectorId: string | null;
  connectorType: string;
  label: string;
  executionOrder: number;
  schemas: string | null;
  status: ScanStatus;
  tablesScanned: number;
  columnsScanned: number;
  findingsCount: number;
  startedAt: string | null;
  completedAt: string | null;
  errorMessage: string | null;
}

export interface Scan {
  id: string;
  name: string;
  status: ScanStatus;
  scanType: string;
  approvalState: 'PENDING' | 'APPROVED';
  approvedBy: string | null;
  approvedAt: string | null;
  databaseCount: number;
  totalTablesScanned: number | null;
  tablesWithPi: number | null;
  findingsCount: number | null;
  createdAt: string;
  createdBy: string | null;
  startedAt: string | null;
  completedAt: string | null;
  errorMessage: string | null;
  valuePatternScan: boolean;
  triggerType: 'MANUAL' | 'LIFECYCLE' | 'SCHEDULED';
  targets: ScanTarget[];
}

export interface ScanFinding {
  id: string;
  databaseConnectorId: string | null;
  dbSchema: string;
  tableName: string;
  columnName: string;
  dataType: string;
  nullable: boolean;
  primaryKey: boolean;
  foreignKey: boolean;
  foreignKeyReference: string | null;
  candidateCategory: string;
  confidence: 'HIGH' | 'MEDIUM' | 'LOW';
  detectionMethod: string;
  detectorRule: string;
  reasoning: string;
  status: string;
  approved: boolean;
  /** PLAIN PI, or ENCRYPTED when the column already holds ciphertext (not selectable). */
  encryptionState?: 'PLAIN' | 'ENCRYPTED';
  /** For plain PI that already has an encrypted twin: the twin's name. */
  encryptedColumn?: string | null;
}

export interface CreateScanRequest {
  name: string;
  databaseConnectorIds: string[];
  schemas: string | null;
  valuePatternScan: boolean;
  /** false leaves the scan Initiated; omitted or true starts it immediately. */
  start?: boolean;
}

/** User-facing names for scan states. */
export const SCAN_STATUS_LABELS: Record<ScanStatus, string> = {
  PENDING: 'Initiated',
  RUNNING: 'Processing',
  COMPLETED: 'Completed',
  CANCELLED: 'Stopped',
  FAILED: 'Error',
};

export const SCAN_STATUS_ORDER: ScanStatus[] = ['PENDING', 'RUNNING', 'COMPLETED', 'CANCELLED', 'FAILED'];

/** Status key for StatusBadgeComponent; a user-stopped scan reads as neutral, not as an error. */
export function scanBadgeStatus(status: ScanStatus): string {
  return status === 'CANCELLED' ? 'stopped' : status;
}

export function isScanActive(status: ScanStatus): boolean {
  return status === 'PENDING' || status === 'RUNNING';
}

export function formatLabel(value: string | null | undefined): string {
  if (!value) return '';
  return value.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase());
}

export function formatDuration(start: string | null, end: string | null): string {
  if (!start) return '—';
  const ms = (end ? new Date(end).getTime() : Date.now()) - new Date(start).getTime();
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  return m < 60 ? `${m}m ${s % 60}s` : `${Math.floor(m / 60)}h ${m % 60}m`;
}

@Injectable({ providedIn: 'root' })
export class ScanService {
  private readonly http = inject(HttpClient);

  private get base(): string {
    return `/workspaces/${sessionStorage.getItem('workspace_id')}/scans`;
  }

  list(): Observable<Scan[]> {
    return this.http.get<Scan[]>(this.base);
  }

  get(id: string): Observable<Scan> {
    return this.http.get<Scan>(`${this.base}/${id}`);
  }

  create(request: CreateScanRequest): Observable<Scan> {
    return this.http.post<Scan>(this.base, request);
  }

  /** Starts an Initiated scan, optionally with a new execution order (target ids). */
  start(id: string, targetOrder?: string[]): Observable<Scan> {
    return this.http.post<Scan>(`${this.base}/${id}/start`, { targetOrder: targetOrder ?? null });
  }

  stop(id: string): Observable<Scan> {
    return this.http.post<Scan>(`${this.base}/${id}/cancel`, {});
  }

  findings(id: string): Observable<ScanFinding[]> {
    return this.http.get<ScanFinding[]>(`${this.base}/${id}/findings`);
  }

  approve(id: string, findingIds: string[], comment: string): Observable<Scan> {
    return this.http.post<Scan>(`${this.base}/${id}/approve`, { findingIds, comment });
  }
}
