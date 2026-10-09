export type Role = 'ADMIN' | 'SALES' | 'OPERATIONS' | 'CREDIT_OFFICER' | 'CREDIT_MANAGER' | 'CRO' | 'FRAUD_ANALYST' | 'COMPLIANCE'

export interface User { username: string; name: string; role: Role; branchId: number; sanctionLevel: number }

export type States = Record<'APP' | 'KYC' | 'DATA' | 'DOCS' | 'FIELD' | 'DECISION' | 'FRAUD' | 'SANCTION' | 'DISB', string>

export interface AppSummary {
  id: number; appNo: string; applicantName: string; pan: string; productCode: string; segment: string
  loanAmount: number; tenureMonths: number; city?: string; states: States; createdBy: string; createdAt: string; updatedAt: string
}

export interface LoanApplication {
  id: number; appNo: string; productCode: string; applicantName: string; pan: string; mobile: string; email?: string
  dob: string; gender?: string; address?: string; city?: string; pincode?: string; segment: string; businessName?: string
  businessVintageYears: number; declaredMonthlyIncome: number; essentialExpenses: number; loanAmount: number; tenureMonths: number
  purpose?: string; bankAccountNo?: string; bankIfsc?: string; consentBureau: boolean; consentAa: boolean; consentKyc: boolean
  rejectionReason?: string; createdBy: string; createdAt: string; updatedAt: string
}

export interface Product {
  id: number; code: string; name: string; segment: string; secured: boolean; status: string; version: number
  minAmount: number; maxAmount: number; minTenure: number; maxTenure: number; minAge: number; maxAge: number
  processingFeePct: number; rateMin: number; rateMax: number; fieldVisitRule: string; fieldVisitThreshold?: number; requiredDocs: string
}

export interface AppView {
  application: LoanApplication; states: States; actions: string[]; blockers: string[]; missingDocuments: string[]; product: Product
}

export interface Rule { id: string; description: string; pass: boolean; action: string }
export interface Contribution { label: string; value: number; reasonCode?: string }
export interface Step { name: string; value: string; status: string }

export interface Decision {
  id: number; decision: string; pd: number; score: number; riskBand: string; fraudScore: number; fraudStatus: string; kycStatus: string
  sustainableIncome: number; maxEmi: number; requestedEmi: number; recommendedAmount: number; recommendedEmi: number; rate: number
  foirPost: number; expectedLoss: number; delegationLevel?: string; rulesJson: string; contributionsJson: string; stepsJson: string
  reasonCodes: string; conditionsJson: string; narrative: string; creditMemo: string; modelVersion: string; policyVersion: string
  inputHash: string; createdBy: string; createdAt: string
}

export interface Kfs {
  applicationNo: string; borrower: string; product: string; sanctionedAmount: number; processingFee: number; gstOnFee: number
  insurance: number; netDisbursed: number; ratePa: number; aprPa: number; tenureMonths: number; emi: number; totalInterest: number
  totalPayable: number; coolingOffDays: number; repaymentMode: string; grievanceOfficer: string
  schedule: { month: number; emi: number; interest: number; principal: number; balance: number }[]
}

export interface Doc { id: number; docType: string; fileName: string; contentType: string; sizeBytes: number; sha256: string; status: string; remarks?: string; tamperFlag: boolean; readConfidence?: number; uploadedBy: string; uploadedAt: string }
export interface History { id: number; domain: string; fromState: string; toState: string; event: string; actor: string; note?: string; createdAt: string }
export interface IntegrationLog { id: number; applicationId?: number; vendor: string; operation: string; attempt: number; status: string; latencyMs: number; requestRef: string; responseSummary?: string; errorMessage?: string; retryOf?: number; createdAt: string }
export interface Snapshot { id: number; kind: string; vendor: string; payloadJson: string; fetchedAt: string }
export interface SanctionRecord { id: number; action: string; level: string; amount?: number; rate?: number; overrideFlag: boolean; note?: string; actor: string; createdAt: string; kfsAcceptedAt?: string }
export interface Disbursement { id: number; status: string; grossAmount: number; deductions: number; netAmount: number; beneficiaryAccount: string; ifsc: string; nameMatchScore?: number; utr?: string; failureReason?: string; lmsPayload?: string; actor: string; createdAt: string }
export interface Task { applicationId?: number; appNo?: string; applicant?: string; product?: string; amount: number; task: string; action: string; ageHours: number; slaHours: number }
export interface AuditEvent { id: number; actor: string; actorRole?: string; action: string; entityType: string; entityId?: string; applicationId?: number; details?: string; createdAt: string }
export interface Branch { id: number; code: string; name: string; level: string; parentId?: number; city?: string; active: boolean }
export interface AppUser { id: number; username: string; fullName: string; role: Role; branchId?: number; email?: string; mobile?: string; active: boolean }
