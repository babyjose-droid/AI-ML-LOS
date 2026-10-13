package com.rhythm.los.model;

import java.util.Map;

/** Port to the training service (the AI service in this repository). Returns the trainer's JSON result as a map. */
public interface ModelTrainer {
    Map<String, Object> train(Map<String, Object> request);
}
