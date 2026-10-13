package com.rhythm.los.decision.engine;

import java.util.List;

/**
 * A trained probability-of-default model: z = intercept + sum(weight * (feature - mean)). The mean is the training
 * average, so each term is the feature's contribution against an average applicant, which keeps decisions explainable.
 */
public record PdModel(String name, String version, double intercept, List<Term> terms) {
    public record Term(String feature, double weight, double mean) {}

    public String label() { return name + " " + version; }
}
