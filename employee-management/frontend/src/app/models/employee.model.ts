export interface Employee {
  id: number;
  firstName: string;
  lastName: string;
  email: string;
  phone: string | null;
  departmentId: number;
  departmentName: string;
  joiningDate: string;
}

export interface EmployeeRequest {
  firstName: string;
  lastName: string;
  email: string;
  phone: string | null;
  departmentId: number;
  joiningDate: string;
}

export interface Department {
  id: number;
  name: string;
}