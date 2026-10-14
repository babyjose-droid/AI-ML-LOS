package com.rhythm.los.application;

import java.util.List;

/** Something that must be true before an application is submitted (for example the co-applicants a product requires). */
public interface SubmitCheck {
    /** Plain-words reasons the application cannot be submitted yet; empty when it can. */
    List<String> beforeSubmit(LoanApplication a);
}
