import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Employee, EmployeeRequest, Department } from '../models/employee.model';

@Injectable({ providedIn: 'root' })
export class EmployeeService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = 'http://localhost:8080/api/employees';

  findAll(): Observable<Employee[]> { return this.http.get<Employee[]>(this.apiUrl); }
  create(request: EmployeeRequest): Observable<Employee> { return this.http.post<Employee>(this.apiUrl, request); }
  update(id: number, request: EmployeeRequest): Observable<Employee> { return this.http.put<Employee>(`${this.apiUrl}/${id}`, request); }
  delete(id: number): Observable<void> { return this.http.delete<void>(`${this.apiUrl}/${id}`); }
}

@Injectable({ providedIn: 'root' })
export class DepartmentService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = 'http://localhost:8080/api/departments';

  findAll(): Observable<Department[]> { return this.http.get<Department[]>(this.apiUrl); }
  create(name: string): Observable<Department> { return this.http.post<Department>(this.apiUrl, { name }); }
  update(id: number, name: string): Observable<Department> { return this.http.put<Department>(`${this.apiUrl}/${id}`, { name }); }
  delete(id: number): Observable<void> { return this.http.delete<void>(`${this.apiUrl}/${id}`); }
}