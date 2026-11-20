export type AccountType = "ORDINARY" | "SAVINGS";
export type AccountStatus = "ACTIVE" | "FROZEN" | "DORMANT" | "CLOSED";
export type TxType =
  | "DEPOSIT"
  | "WITHDRAWAL"
  | "TRANSFER_OUT"
  | "TRANSFER_IN"
  | "INTEREST"
  | "TAX"
  | "CLOSE_PAYOUT";
export type KycStatus = "PENDING" | "VERIFIED" | "REJECTED";
export type IdDocType = "DRIVER_LICENSE" | "MY_NUMBER_CARD" | "PASSPORT" | "RESIDENCE_CARD";

export interface Account {
  accountNo: string;
  customerId: string;
  customerName: string | null;
  accountType: AccountType;
  balance: number;
  status: AccountStatus;
  updatedAt: string;
  openedAt: string | null;
  closedAt: string | null;
  /** Last customer-initiated transaction (interest credits do not count) */
  lastActivityAt: string | null;
  dormancyNotifiedAt: string | null;
  dormantAt: string | null;
}

export interface AccountTransaction {
  txId: number;
  accountNo: string;
  txType: TxType;
  amount: number;
  balanceAfter: number;
  counterpartyAccountNo: string | null;
  memo: string | null;
  createdAt: string;
}

export interface AccountDetail {
  account: Account;
  transactions: AccountTransaction[];
}

export interface TransactionPage {
  items: AccountTransaction[];
  page: number;
  size: number;
  total: number;
  hasNext: boolean;
  openingBalance: number;
  closingBalance: number;
  totalIn: number;
  totalOut: number;
}

export interface OperationResult {
  operation: "DEPOSIT" | "WITHDRAWAL" | "TRANSFER";
  accountNo: string;
  counterpartyAccountNo: string | null;
  amount: number;
  balanceAfter: number;
  executedAt: string;
}

export interface Customer {
  customerId: string;
  name: string;
  nameKana: string | null;
  birthDate: string | null;
  postalCode: string | null;
  address: string | null;
  phone: string | null;
  idDocType: IdDocType | null;
  idDocLast4: string | null;
  kycStatus: KycStatus;
  kycDecidedAt: string | null;
  kycNote: string | null;
  createdAt: string;
  accounts: Account[];
}

export interface InterestResult {
  accountNo: string;
  accountType: AccountType | null;
  periodFrom: string | null;
  periodTo: string | null;
  days: number;
  balanceDays: number;
  averageBalance: number;
  grossInterest: number;
  nationalTax: number;
  localTax: number;
  tax: number;
  netInterest: number;
  settled: boolean;
  note: string | null;
}

export interface SettlementSummary {
  asOf: string;
  results: InterestResult[];
  settledCount: number;
  skippedCount: number;
  totalGross: number;
  totalTax: number;
  totalNet: number;
}

export interface CloseResult {
  accountNo: string;
  interest: InterestResult;
  payoutAmount: number;
  payoutAccountNo: string | null;
  closedAt: string;
}

export interface DormancyReport {
  asOf: string;
  noticeTargets: Account[];
  dormancyTargets: Account[];
  dormantAccounts: Account[];
  noticed: number;
  madeDormant: number;
}

export interface ApiErrorBody {
  code: string;
  message: string;
  fieldErrors?: Record<string, string[]>;
  actionErrors?: string[];
}
