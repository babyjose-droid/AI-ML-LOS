-- Phase 3: AI bank statement analysis. The full analysis (transactions, monthly cash flow, summary) is kept with the document.
alter table app_document add column analysis_json text;
